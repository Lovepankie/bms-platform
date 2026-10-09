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
import com.rincoltech.bms.retail.stock.Quantities;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    DashboardService(DashboardRepository repo, CurrentTenant tenant, BusinessClock clock) {
        this.repo = repo;
        this.tenant = tenant;
        this.clock = clock;
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
        List<Day> days = new ArrayList<>();
        long last7 = 0;
        long last30 = 0;
        long cost7 = 0;
        long cost30 = 0;
        for (int i = 29; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            long[] f = byDay.getOrDefault(date, new long[2]);
            days.add(new Day(date, f[0], profit ? f[0] - f[1] : null));
            last30 += f[0];
            cost30 += f[1];
            if (i < 7) {
                last7 += f[0];
                cost7 += f[1];
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
            stock = new Stock(
                    price,
                    AnalyticsSupport.profitVisible(principal, stockFilter) ? cost : null,
                    counts[0],
                    counts[1],
                    Quantities.format(LOW_STOCK));
        }

        // A shop row for every branch with sales in the window or with stock, in scope.
        List<UUID> branches = new ArrayList<>(byBranch.keySet());
        positions.keySet().stream().filter(b -> !byBranch.containsKey(b)).forEach(branches::add);
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
            // A shop outside the caller's sales scope carries no sales figures, not invented zeros.
            boolean sold = principal.may(SALE_READ, b);
            boolean cost = sold && principal.may(PROFIT_READ, b);
            Position p = principal.may(STOCK_READ, b) ? positions.get(b) : null;
            shops.add(new Shop(
                    b,
                    sold ? t[0] : null,
                    sold ? s7 : null,
                    sold ? s30 : null,
                    cost ? t[0] - t[1] : null,
                    p == null ? null : p.atPrice(),
                    p != null && cost ? p.atCost() : null,
                    p == null ? null : p.out(),
                    p == null ? null : p.low()));
        }
        return new Report(
                today,
                tenant.profile().currency(),
                profit,
                new Today(now[0], now[1], now[2], now[3], profit ? now[0] - now[4] : null),
                days,
                last7,
                last30,
                profit ? last7 - cost7 : null,
                profit ? last30 - cost30 : null,
                stock,
                shops);
    }
}
