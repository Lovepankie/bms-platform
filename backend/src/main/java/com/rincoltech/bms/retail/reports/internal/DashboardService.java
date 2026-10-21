package com.rincoltech.bms.retail.reports.internal;

import static com.rincoltech.bms.retail.reports.internal.AnalyticsSupport.PROFIT_READ;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.BusiestDay;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.Day;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.Figures;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.Leader;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.Point;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.Range;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.Report;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.Shop;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.Stock;
import com.rincoltech.bms.retail.reports.internal.DashboardApi.Today;
import com.rincoltech.bms.retail.reports.internal.DashboardRepository.DaySales;
import com.rincoltech.bms.retail.reports.internal.DashboardRepository.Position;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Agg;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Dimension;
import com.rincoltech.bms.retail.reports.internal.SalesAnalysisRepository.Order;
import com.rincoltech.bms.retail.stock.CreditPeriods;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner dashboard of issue #149: today, the last 7 and 30 days, stock value and counts, by shop; and with a range (issue #428)
 * the figures, the chart and the best of the range against the period before, from the same dated queries.
 */
@Service
class DashboardService {

    static final String STOCK_READ = "retail.stock.read";
    static final String SALE_READ = "retail.sale.read";
    /** The low stock threshold of ADR-029 (the stock lists report the same value as low_stock_threshold). */
    static final BigDecimal LOW_STOCK = new BigDecimal("5.000");

    private final DashboardRepository repo;
    private final SalesAnalysisRepository analysis;
    private final CreditRecognitionRepository credit;
    private final RetailCapabilities capabilities;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    DashboardService(
            DashboardRepository repo,
            SalesAnalysisRepository analysis,
            CreditRecognitionRepository credit,
            RetailCapabilities capabilities,
            CurrentTenant tenant,
            BusinessClock clock) {
        this.repo = repo;
        this.analysis = analysis;
        this.credit = credit;
        this.capabilities = capabilities;
        this.tenant = tenant;
        this.clock = clock;
    }

    private static void add(Map<LocalDate, long[]> into, LocalDate day, long revenue, long cost) {
        long[] f = into.computeIfAbsent(day, x -> new long[3]);
        f[0] += revenue;
        f[1] += cost;
    }

    /**
     * Sales (sales, cost, count), profit (revenue, cost) and money collected by branch and day over a window, by the rules of
     * the dashboard: what was sold, and the profit of credit sales counted when the buyer pays (ADR-043) through the payments.
     */
    private record Book(
            Map<UUID, Map<LocalDate, long[]>> byBranch,
            Map<LocalDate, long[]> byDay,
            Map<UUID, Map<LocalDate, long[]>> recBranch,
            Map<UUID, Map<LocalDate, long[]>> profitBranch,
            Map<LocalDate, long[]> profitDay,
            Map<UUID, Map<LocalDate, long[]>> collBranch,
            Map<LocalDate, long[]> collDay,
            List<DaySales> rows) {}

    private Book book(
            List<UUID> filter, LocalDate windowStart, LocalDate windowEnd, CreditPeriods periods, boolean onPay) {
        // Per branch and day (sales, cost, count).
        Map<UUID, Map<LocalDate, long[]>> byBranch = new LinkedHashMap<>();
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        List<DaySales> rows = repo.sales(filter, windowStart, windowEnd);
        for (DaySales d : rows) {
            long[] b = byBranch.computeIfAbsent(d.branchId(), x -> new LinkedHashMap<>())
                    .computeIfAbsent(d.day(), x -> new long[3]);
            b[0] += d.sales();
            b[1] += d.cost();
            b[2] += d.saleCount();
            long[] all = byDay.computeIfAbsent(d.day(), x -> new long[3]);
            all[0] += d.sales();
            all[1] += d.cost();
            all[2] += d.saleCount();
        }
        // ADR-043: credit sales counted from the start day of the rule "when the buyer pays" bring profit through the
        // money received (a payment on the day it was recorded, with its share of the sale's profit); Sold stays what
        // was
        // sold, and every other credit sale counts at the sale as it always did.
        Map<UUID, Map<LocalDate, long[]>> recBranch = new LinkedHashMap<>();
        Map<LocalDate, long[]> recDay = new LinkedHashMap<>();
        // Received (ADR-043): the money recorded as collected on the day it was entered, by any method. It is the sales
        // paid at the sale plus every payment recorded on a credit sale, less money paid back, and is not the profit
        // basis
        // above.
        Map<UUID, Map<LocalDate, long[]>> collBranch = new LinkedHashMap<>();
        Map<LocalDate, long[]> collDay = new LinkedHashMap<>();
        if (onPay) {
            for (DaySales d : repo.sales(filter, windowStart, windowEnd, periods)) {
                add(recBranch.computeIfAbsent(d.branchId(), x -> new LinkedHashMap<>()), d.day(), d.sales(), d.cost());
                add(recDay, d.day(), d.sales(), d.cost());
            }
            for (ReportsRepository.DayFigures d : credit.cashSalesEntered(filter, windowStart, windowEnd)) {
                add(collBranch.computeIfAbsent(d.branchId(), x -> new LinkedHashMap<>()), d.date(), d.sales(), 0);
                add(collDay, d.date(), d.sales(), 0);
            }
            Set<UUID> scope = filter == null ? null : Set.copyOf(filter);
            for (ReportsRepository.DayFigures d : CreditRecognition.deltas(
                    credit.events(filter, windowStart, windowEnd, periods), windowStart, windowEnd, scope)) {
                add(
                        recBranch.computeIfAbsent(d.branchId(), x -> new LinkedHashMap<>()),
                        d.date(),
                        d.sales(),
                        d.costOfSales());
                add(recDay, d.date(), d.sales(), d.costOfSales());
            }
            // Received is money recorded as collected, by any method, the same figure as in the profit report
            // (ADR-043): every payment on a credit sale, counted when the buyer pays or not, imported ones included,
            // less
            // reversals and refunds.
            for (ReportsRepository.DayFigures d : credit.collections(filter, windowStart, windowEnd)) {
                add(collBranch.computeIfAbsent(d.branchId(), x -> new LinkedHashMap<>()), d.date(), d.sales(), 0);
                add(collDay, d.date(), d.sales(), 0);
            }
        }
        Map<LocalDate, long[]> profitDay = onPay ? recDay : byDay;
        Map<UUID, Map<LocalDate, long[]>> profitBranch = onPay ? recBranch : byBranch;
        // A debt written off is a loss on its day in Daily profit and in the savings suggestion, so it is here too.
        for (ReportsRepository.DayFigures d : credit.badDebt(filter, windowStart, windowEnd)) {
            add(profitBranch.computeIfAbsent(d.branchId(), x -> new LinkedHashMap<>()), d.date(), 0, d.badDebt());
            add(profitDay, d.date(), 0, d.badDebt());
        }
        return new Book(byBranch, byDay, recBranch, profitBranch, profitDay, collBranch, collDay, rows);
    }

    @Transactional(readOnly = true)
    Report dashboard(List<UUID> branchIds, String period, LocalDate from, LocalDate to) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = AnalyticsSupport.everySellerFilter(principal, branchIds);
        LocalDate today = clock.today(tenant.profile().timezone());
        boolean profit = AnalyticsSupport.profitVisible(principal, filter);
        DashboardRange range =
                DashboardRange.of(period, from, to, today, "all".equals(period) ? repo.firstSaleDay(filter) : null);

        CreditPeriods periods = capabilities.onPaymentPeriods();
        // The when-paid fields appear once the rule has started, not while its start day is still ahead.
        boolean onPay = periods.contains(today);
        LocalDate windowStart = today.minusDays(29);
        // The last 30 days (today, the 7 and 30 day figures, the shops) and, apart, the range with the period before
        // it: a
        // range far in the past is read for its own days and the 30 before them, never for everything up to today.
        Book book = book(filter, windowStart, today, periods, onPay);
        Book ranged = range == null
                ? null
                : range.first().isBefore(windowStart) ? book(filter, range.first(), range.to(), periods, onPay) : book;
        Map<UUID, Map<LocalDate, long[]>> byBranch = book.byBranch();
        Map<LocalDate, long[]> byDay = book.byDay();
        Map<LocalDate, long[]> profitDay = book.profitDay();
        Map<LocalDate, long[]> collDay = book.collDay();

        // Today's split by the way of paying.
        long[] now = new long[5]; // sales, count, cash, credit, cost
        for (DaySales d : book.rows()) {
            if (d.day().equals(today)) {
                now[0] += d.sales();
                now[1] += d.saleCount();
                now[d.credit() ? 3 : 2] += d.sales();
                now[4] += d.cost();
            }
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
            long[] f = byDay.getOrDefault(date, new long[3]);
            long[] p = profitDay.getOrDefault(date, new long[3]);
            days.add(new Day(
                    date,
                    f[0],
                    profit ? p[0] - p[1] : null,
                    onPay ? collDay.getOrDefault(date, new long[3])[0] : null));
            last30 += f[0];
            profit30 += p[0] - p[1];
            received30 += onPay ? collDay.getOrDefault(date, new long[3])[0] : 0;
            if (i < 7) {
                last7 += f[0];
                profit7 += p[0] - p[1];
                received7 += onPay ? collDay.getOrDefault(date, new long[3])[0] : 0;
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
        book.recBranch().keySet().stream().filter(b -> !branches.contains(b)).forEach(branches::add);
        // A branch that only took payments on credit sales has money collected today and needs its row.
        book.collBranch().keySet().stream().filter(b -> !branches.contains(b)).forEach(branches::add);
        if (ranged != null && ranged != book) {
            ranged.byBranch().keySet().stream()
                    .filter(b -> !branches.contains(b))
                    .forEach(branches::add);
        }
        positions.keySet().stream().filter(b -> !branches.contains(b)).forEach(branches::add);
        branches.sort(null);
        List<Shop> shops = new ArrayList<>();
        for (UUID b : branches) {
            Map<LocalDate, long[]> sales = byBranch.getOrDefault(b, Map.of());
            long s7 = 0;
            long s30 = 0;
            long rs = 0;
            long rp = 0;
            long rProfit = 0;
            for (Map.Entry<LocalDate, long[]> e : sales.entrySet()) {
                LocalDate day = e.getKey();
                if (!day.isBefore(windowStart) && !day.isAfter(today)) {
                    s30 += e.getValue()[0];
                    if (!day.isBefore(today.minusDays(6))) {
                        s7 += e.getValue()[0];
                    }
                }
            }
            if (range != null) {
                for (Map.Entry<LocalDate, long[]> e :
                        ranged.byBranch().getOrDefault(b, Map.of()).entrySet()) {
                    if (range.contains(e.getKey())) {
                        rs += e.getValue()[0];
                    } else if (range.inPrevious(e.getKey())) {
                        rp += e.getValue()[0];
                    }
                }
                for (Map.Entry<LocalDate, long[]> e :
                        ranged.profitBranch().getOrDefault(b, Map.of()).entrySet()) {
                    if (range.contains(e.getKey())) {
                        rProfit += e.getValue()[0] - e.getValue()[1];
                    }
                }
            }
            long[] t = sales.getOrDefault(today, new long[3]);
            long[] rt = onPay ? book.recBranch().getOrDefault(b, Map.of()).getOrDefault(today, new long[3]) : t;
            // A shop outside the caller's sales scope carries no sales figures, not invented zeros.
            boolean sold = principal.may(SALE_READ, b);
            boolean cost = sold && principal.may(PROFIT_READ, b);
            Position p = principal.may(STOCK_READ, b) ? positions.get(b) : null;
            boolean inRange = range != null && sold;
            shops.add(new Shop(
                    b,
                    sold ? t[0] : null,
                    sold && range == null ? s7 : null,
                    sold && range == null ? s30 : null,
                    cost ? rt[0] - rt[1] : null,
                    p != null && principal.may(PROFIT_READ, b) ? p.atPrice() : null,
                    p != null && cost ? p.atCost() : null,
                    p == null ? null : p.out(),
                    p == null ? null : p.low(),
                    onPay && sold
                            ? book.collBranch().getOrDefault(b, Map.of()).getOrDefault(today, new long[3])[0]
                            : null,
                    inRange ? rs : null,
                    inRange && range.hasPrevious() ? rp : null,
                    inRange && cost ? rProfit : null));
        }
        long[] rToday = collDay.getOrDefault(today, new long[3]);
        long[] owed = onPay ? credit.onCredit(filter, periods) : new long[2];
        long[] pToday = profitDay.getOrDefault(today, new long[3]);
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
                range == null ? days : null,
                last7,
                last30,
                profit ? profit7 : null,
                profit ? profit30 : null,
                stock,
                shops,
                capabilities.creditRecognitionOn(today),
                onPay ? received7 : null,
                onPay ? received30 : null,
                periods.from(),
                range == null ? null : range(range, ranged, filter, principal, profit, onPay));
    }

    /** The range part (issue #428): its figures against the period before, the chart, and who and what did best. */
    private Range range(
            DashboardRange range, Book book, List<UUID> filter, Principal principal, boolean profit, boolean onPay) {
        long[] cur = new long[5]; // sales, count, profit revenue, profit cost, received
        long[] prev = new long[5];
        long[][] steps = new long[range.steps()][4]; // sales, profit revenue, profit cost, sales before
        LocalDate busiest = null;
        LocalDate busiestBefore = null;
        for (Map.Entry<LocalDate, long[]> e : book.byDay().entrySet()) {
            LocalDate day = e.getKey();
            long[] f = e.getValue();
            if (range.contains(day)) {
                cur[0] += f[0];
                cur[1] += f[2];
                steps[range.step(range.from(), day)][0] += f[0];
                if (busiest == null || more(f[0], day, book.byDay().get(busiest)[0], busiest)) {
                    busiest = day;
                }
            } else if (range.inPrevious(day)) {
                prev[0] += f[0];
                prev[1] += f[2];
                int i = range.step(range.previousFrom(), day);
                if (i < steps.length) {
                    steps[i][3] += f[0];
                }
                if (busiestBefore == null || more(f[0], day, book.byDay().get(busiestBefore)[0], busiestBefore)) {
                    busiestBefore = day;
                }
            }
        }
        for (Map.Entry<LocalDate, long[]> e : book.profitDay().entrySet()) {
            long[] f = e.getValue();
            if (range.contains(e.getKey())) {
                cur[2] += f[0];
                cur[3] += f[1];
                steps[range.step(range.from(), e.getKey())][1] += f[0];
                steps[range.step(range.from(), e.getKey())][2] += f[1];
            } else if (range.inPrevious(e.getKey())) {
                prev[2] += f[0];
                prev[3] += f[1];
            }
        }
        for (Map.Entry<LocalDate, long[]> e : book.collDay().entrySet()) {
            if (range.contains(e.getKey())) {
                cur[4] += e.getValue()[0];
            } else if (range.inPrevious(e.getKey())) {
                prev[4] += e.getValue()[0];
            }
        }
        List<Point> series = new ArrayList<>();
        for (int i = 0; i < steps.length; i++) {
            series.add(new Point(
                    range.stepStart(i),
                    steps[i][0],
                    profit ? steps[i][1] - steps[i][2] : null,
                    range.hasPrevious() ? steps[i][3] : null));
        }
        // A day with no sale is not the busiest day.
        BusiestDay busiestDay = busiest == null || book.byDay().get(busiest)[0] <= 0
                ? null
                : new BusiestDay(
                        busiest,
                        book.byDay().get(busiest)[0],
                        busiestBefore == null || book.byDay().get(busiestBefore)[0] <= 0 ? null : busiestBefore,
                        busiestBefore == null || book.byDay().get(busiestBefore)[0] <= 0
                                ? null
                                : book.byDay().get(busiestBefore)[0]);
        return new Range(
                range.period(),
                range.from(),
                range.to(),
                range.previousFrom(),
                range.previousTo(),
                range.group(),
                figures(cur, profit, onPay),
                range.hasPrevious() ? figures(prev, profit, onPay) : null,
                series,
                busiestDay,
                bestShop(range, book, principal),
                // Over more than a year the two rankings, which scan every sale line of the span, are left out
                // (ADR-060).
                range.leaders() ? leader(Dimension.PRODUCT, filter, range) : null,
                // A ranking of colleagues is management information, as the salesperson level of the sales summary.
                profit && range.leaders() ? leader(Dimension.SELLER, filter, range) : null);
    }

    /** More sales, or as many on an earlier day. */
    private static boolean more(long sales, LocalDate day, long best, LocalDate bestDay) {
        return sales > best || (sales == best && day.isBefore(bestDay));
    }

    private static Figures figures(long[] f, boolean profit, boolean onPay) {
        return new Figures(
                f[0],
                f[1],
                f[1] == 0
                        ? null
                        : BigDecimal.valueOf(f[0])
                                .divide(BigDecimal.valueOf(f[1]), 0, RoundingMode.HALF_UP)
                                .longValueExact(),
                profit ? f[2] - f[3] : null,
                onPay ? f[4] : null);
    }

    /** The shop with the most sales in the range, among the shops whose sales the caller reads. */
    private static Leader bestShop(DashboardRange range, Book book, Principal principal) {
        Leader best = null;
        for (Map.Entry<UUID, Map<LocalDate, long[]>> b : book.byBranch().entrySet()) {
            if (!principal.may(SALE_READ, b.getKey())) {
                continue;
            }
            long sales = 0;
            long count = 0;
            long before = 0;
            for (Map.Entry<LocalDate, long[]> e : b.getValue().entrySet()) {
                if (range.contains(e.getKey())) {
                    sales += e.getValue()[0];
                    count += e.getValue()[2];
                } else if (range.inPrevious(e.getKey())) {
                    before += e.getValue()[0];
                }
            }
            if (sales > 0
                    && (best == null
                            || sales > best.salesMinor()
                            || (sales == best.salesMinor() && b.getKey().compareTo(best.id()) < 0))) {
                best = new Leader(b.getKey(), null, null, sales, count, range.hasPrevious() ? before : null);
            }
        }
        return best;
    }

    /** The item or person with the most sales in the range, and the same one's sales in the period before. */
    private Leader leader(Dimension dimension, List<UUID> filter, DashboardRange range) {
        List<Agg> top = analysis.grouped(dimension, filter, range.from(), range.to(), Order.SALES, null, 1);
        if (top.isEmpty() || top.getFirst().sales() <= 0) {
            return null;
        }
        Agg a = top.getFirst();
        Long before = range.hasPrevious()
                ? analysis.one(dimension, filter, range.previousFrom(), range.previousTo(), a.id())
                        .map(Agg::sales)
                        .orElse(0L)
                : null;
        return new Leader(a.id(), a.code(), a.label(), a.sales(), a.saleCount(), before);
    }
}
