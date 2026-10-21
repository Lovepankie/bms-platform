package com.rincoltech.bms.retail.reports.internal;

import static com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.PROFIT_READ;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.Day;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.Report;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.Shop;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.Stock;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.Today;
import com.rincoltech.bms.retail.reports.internal.DashboardRepository.DaySales;
import com.rincoltech.bms.retail.reports.internal.DashboardRepository.Position;
import com.rincoltech.bms.retail.stock.CreditPeriods;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The owner dashboard of issue #149: today, the last 7 and 30 days, stock value and counts, by shop. */
@Service
class DashboardService {

    static final String STOCK_READ = "retail.stock.read";
    static final String SALE_READ = "retail.sale.read";
    /** The low stock threshold of ADR-029 (the stock lists report the same value as low_stock_threshold). */
    static final BigDecimal LOW_STOCK = new BigDecimal("5.000");

    private final DashboardRepository repo;
    private final CreditRecognitionRepository credit;
    private final RetailCapabilities capabilities;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    DashboardService(
            DashboardRepository repo,
            CreditRecognitionRepository credit,
            RetailCapabilities capabilities,
            CurrentTenant tenant,
            BusinessClock clock) {
        this.repo = repo;
        this.credit = credit;
        this.capabilities = capabilities;
        this.tenant = tenant;
        this.clock = clock;
    }

    private static void add(Map<LocalDate, long[]> into, LocalDate day, long revenue, long cost) {
        long[] f = into.computeIfAbsent(day, x -> new long[2]);
        f[0] += revenue;
        f[1] += cost;
    }

    @Transactional(readOnly = true)
    Report dashboard(List<UUID> branchIds) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter(SALE_READ, branchIds);
        LocalDate today = clock.today(tenant.profile().timezone());
        boolean profit = AnalyticsSupport.profitVisible(principal, filter);

        // Per branch and day (sales, cost), and today's split by the way of paying.
        Map<UUID, Map<LocalDate, long[]>> byBranch = new LinkedHashMap<>();
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        long[] now = new long[5]; // sales, count, cash, credit, cost
        for (DaySales d : repo.sales(filter, today.minusDays(29), today)) {
            long[] b = byBranch.computeIfAbsent(d.branchId(), x -> new LinkedHashMap<>())
                    .computeIfAbsent(d.day(), x -> new long[2]);
            b[0] += d.sales();
            b[1] += d.cost();
            long[] all = byDay.computeIfAbsent(d.day(), x -> new long[2]);
            all[0] += d.sales();
            all[1] += d.cost();
            if (d.day().equals(today)) {
                now[0] += d.sales();
                now[1] += d.saleCount();
                now[d.credit() ? 3 : 2] += d.sales();
                now[4] += d.cost();
            }
        }
        // ADR-043: credit sales counted from the start day of the rule "when the buyer pays" bring profit through the
        // money
        // received (a
        // payment on the day it was recorded, with its share of the sale's profit); Sold stays what was sold, and every
        // other credit
        // sale counts at the sale as it always did.
        CreditPeriods periods = capabilities.onPaymentPeriods();
        // The when-paid fields appear once the rule has started, not while its start day is still ahead.
        boolean onPay = periods.contains(today);
        LocalDate windowStart = today.minusDays(29);
        Map<UUID, Map<LocalDate, long[]>> recBranch = new LinkedHashMap<>();
        Map<LocalDate, long[]> recDay = new LinkedHashMap<>();
        // Received (ADR-043): the money recorded as collected on the day it was entered, by any method. It is the sales
        // paid at
        // the sale plus every payment recorded on a credit sale, less money paid back, and is not the profit basis
        // above.
        Map<UUID, Map<LocalDate, long[]>> collBranch = new LinkedHashMap<>();
        Map<LocalDate, long[]> collDay = new LinkedHashMap<>();
        if (onPay) {
            for (DaySales d : repo.sales(filter, windowStart, today, periods)) {
                add(recBranch.computeIfAbsent(d.branchId(), x -> new LinkedHashMap<>()), d.day(), d.sales(), d.cost());
                add(recDay, d.day(), d.sales(), d.cost());
            }
            for (ReportsRepository.DayFigures d : credit.cashSalesEntered(filter, windowStart, today)) {
                add(collBranch.computeIfAbsent(d.branchId(), x -> new LinkedHashMap<>()), d.date(), d.sales(), 0);
                add(collDay, d.date(), d.sales(), 0);
            }
            Set<UUID> scope = filter == null ? null : Set.copyOf(filter);
            for (ReportsRepository.DayFigures d : CreditRecognition.deltas(
                    credit.events(filter, windowStart, today, periods), windowStart, today, scope)) {
                add(
                        recBranch.computeIfAbsent(d.branchId(), x -> new LinkedHashMap<>()),
                        d.date(),
                        d.sales(),
                        d.costOfSales());
                add(recDay, d.date(), d.sales(), d.costOfSales());
            }
            // Received is money recorded as collected, by any method, the same figure as in the profit report
            // (ADR-043): every
            // payment on a credit sale, counted when the buyer pays or not, imported ones included, less reversals and
            // refunds.
            for (ReportsRepository.DayFigures d : credit.collections(filter, windowStart, today)) {
                add(collBranch.computeIfAbsent(d.branchId(), x -> new LinkedHashMap<>()), d.date(), d.sales(), 0);
                add(collDay, d.date(), d.sales(), 0);
            }
        }
        Map<LocalDate, long[]> profitDay = onPay ? recDay : byDay;
        Map<UUID, Map<LocalDate, long[]>> profitBranch = onPay ? recBranch : byBranch;
        // A debt written off is a loss on its day in Daily profit and in the savings suggestion, so it is here too.
        for (ReportsRepository.DayFigures d : credit.badDebt(filter, windowStart, today)) {
            add(profitBranch.computeIfAbsent(d.branchId(), x -> new LinkedHashMap<>()), d.date(), 0, d.badDebt());
            add(profitDay, d.date(), 0, d.badDebt());
        }
        List<Day> days = new ArrayList<>();
        long last7 = 0;
        long last30 = 0;
        long profit7 = 0;
        long profit30 = 0;
        long received7 = 0;
        long received30 = 0;
        for (int i = 29; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            long[] f = byDay.getOrDefault(date, new long[2]);
            long[] p = profitDay.getOrDefault(date, new long[2]);
            days.add(new Day(
                    date,
                    f[0],
                    profit ? p[0] - p[1] : null,
                    onPay ? collDay.getOrDefault(date, new long[2])[0] : null));
            last30 += f[0];
            profit30 += p[0] - p[1];
            received30 += onPay ? collDay.getOrDefault(date, new long[2])[0] : 0;
            if (i < 7) {
                last7 += f[0];
                profit7 += p[0] - p[1];
                received7 += onPay ? collDay.getOrDefault(date, new long[2])[0] : 0;
            }
        }

        boolean stockRead = principal.hasPermission(STOCK_READ);
        List<UUID> stockFilter = principal.branchFilter(STOCK_READ, branchIds);
        Map<UUID, Position> positions = new LinkedHashMap<>();
        if (stockRead) {
            repo.positions(stockFilter, LOW_STOCK).forEach(p -> positions.put(p.branchId(), p));
        }
        Stock stock = null;
        if (stockRead) {
            long price = 0;
            long cost = 0;
            for (Position p : positions.values()) {
                price = Math.addExact(price, p.atPrice());
                cost = Math.addExact(cost, p.atCost());
            }
            // As the stock list judges All branches: one balance per item, summed over the branches.
            int[] counts = repo.counts(stockFilter, LOW_STOCK);
            boolean stockValue = AnalyticsSupport.profitVisible(principal, stockFilter);
            stock = new Stock(
                    // ADR-055: the value of the stock, even at the selling price, goes with the cost permission.
                    stockValue ? price : null,
                    stockValue ? cost : null,
                    counts[0],
                    counts[1],
                    Quantities.format(LOW_STOCK));
        }

        // A shop row for every branch with sales in the window or with stock, in scope.
        List<UUID> branches = new ArrayList<>(byBranch.keySet());
        recBranch.keySet().stream().filter(b -> !branches.contains(b)).forEach(branches::add);
        // A branch that only took payments on credit sales has money collected today and needs its row.
        collBranch.keySet().stream().filter(b -> !branches.contains(b)).forEach(branches::add);
        positions.keySet().stream().filter(b -> !branches.contains(b)).forEach(branches::add);
        branches.sort(null);
        List<Shop> shops = new ArrayList<>();
        for (UUID b : branches) {
            Map<LocalDate, long[]> sales = byBranch.getOrDefault(b, Map.of());
            long s7 = 0;
            long s30 = 0;
            for (Map.Entry<LocalDate, long[]> e : sales.entrySet()) {
                s30 += e.getValue()[0];
                if (!e.getKey().isBefore(today.minusDays(6))) {
                    s7 += e.getValue()[0];
                }
            }
            long[] t = sales.getOrDefault(today, new long[2]);
            long[] rt = onPay ? recBranch.getOrDefault(b, Map.of()).getOrDefault(today, new long[2]) : t;
            // A shop outside the caller's sales scope carries no sales figures, not invented zeros.
            boolean sold = principal.may(SALE_READ, b);
            boolean cost = sold && principal.may(PROFIT_READ, b);
            Position p = principal.may(STOCK_READ, b) ? positions.get(b) : null;
            shops.add(new Shop(
                    b,
                    sold ? t[0] : null,
                    sold ? s7 : null,
                    sold ? s30 : null,
                    cost ? rt[0] - rt[1] : null,
                    p != null && principal.may(PROFIT_READ, b) ? p.atPrice() : null,
                    p != null && cost ? p.atCost() : null,
                    p == null ? null : p.out(),
                    p == null ? null : p.low(),
                    onPay && sold ? collBranch.getOrDefault(b, Map.of()).getOrDefault(today, new long[2])[0] : null));
        }
        long[] rToday = collDay.getOrDefault(today, new long[2]);
        long[] owed = onPay ? credit.onCredit(filter, periods) : new long[2];
        long[] pToday = profitDay.getOrDefault(today, new long[2]);
        return new Report(
                today,
                tenant.profile().currency(),
                profit,
                new Today(
                        now[0],
                        now[1],
                        now[2],
                        now[3],
                        profit ? pToday[0] - pToday[1] : null,
                        onPay ? rToday[0] : null,
                        onPay ? owed[0] : null,
                        onPay ? (int) owed[1] : null),
                days,
                last7,
                last30,
                profit ? profit7 : null,
                profit ? profit30 : null,
                stock,
                shops,
                capabilities.creditRecognitionOn(today),
                onPay ? received7 : null,
                onPay ? received30 : null,
                periods.from());
    }
}
