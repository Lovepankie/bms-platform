package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.reports.internal.ReportsApi.BranchTotal;
import com.rincoltech.bms.retail.reports.internal.ReportsApi.CategoryTotal;
import com.rincoltech.bms.retail.reports.internal.ReportsApi.DailyProfit;
import com.rincoltech.bms.retail.reports.internal.ReportsApi.ProfitRow;
import com.rincoltech.bms.retail.reports.internal.ReportsApi.Valuation;
import com.rincoltech.bms.retail.reports.internal.ReportsApi.ValuationRow;
import com.rincoltech.bms.retail.reports.internal.ReportsRepository.DayFigures;
import com.rincoltech.bms.retail.reports.internal.ReportsRepository.Holding;
import com.rincoltech.bms.retail.stock.Quantities;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** FR-RET-09 and FR-RET-10. Amounts are rounded half up once per row and totals are sums of rows. */
@Service
class ReportsService {

    static final String PROFIT_READ = "retail.profit.read";
    static final int MAX_DAYS = 366;

    private final ReportsRepository repo;
    private final LedgerAccounts accounts;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    ReportsService(ReportsRepository repo, LedgerAccounts accounts, CurrentTenant tenant, BusinessClock clock) {
        this.repo = repo;
        this.accounts = accounts;
        this.tenant = tenant;
        this.clock = clock;
    }

    /**
     * FR-RET-09: per branch and product, quantity times the current cost and the current sell price
     * (ADR-020 decision 6), with branch and overall totals. Where the caller holds {@code
     * retail.profit.read} in a row's branch (ADR-017; review F5), that row and branch also show cost,
     * the inventory account balance and the revaluation difference (decision 8). The overall value
     * at cost shows only when cost shows for every reported branch.
     */
    @Transactional(readOnly = true)
    Valuation valuation(List<UUID> branchIds, LocalDate asOf) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter("retail.stock.read", branchIds);
        LocalDate today = clock.today(tenant.profile().timezone());
        if (asOf != null && asOf.isAfter(today)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("as_of", "invalid", "A valuation cannot be dated in the future.")));
        }
        List<ValuationRow> rows = new ArrayList<>();
        Map<UUID, long[]> byBranch = new LinkedHashMap<>();
        // Per category: sales, cost (cost-visible rows only) and whether every row's cost is visible.
        Map<UUID, CategoryAcc> byCategory = new LinkedHashMap<>();
        for (Holding h : repo.holdings(filter, asOf)) {
            boolean cost = principal.may(PROFIT_READ, h.branchId());
            long[] t = byBranch.computeIfAbsent(h.branchId(), b -> new long[2]);
            Long expected;
            Long atCost;
            try {
                expected = Quantities.value(h.qty(), h.sellMinor());
                atCost = Quantities.value(h.qty(), h.costMinor());
            } catch (ArithmeticException e) {
                // Review F7: one row too large to value leaves the report working for the others; it
                // is flagged and left out of the totals.
                expected = null;
                atCost = null;
            }
            CategoryAcc c = byCategory.computeIfAbsent(h.categoryId(), id -> new CategoryAcc(h.category()));
            if (expected != null) {
                t[0] = Math.addExact(t[0], expected);
                t[1] = Math.addExact(t[1], atCost);
                c.sales = Math.addExact(c.sales, expected);
                if (cost) {
                    c.cost = Math.addExact(c.cost, atCost);
                }
            }
            c.costEverywhere &= cost;
            rows.add(new ValuationRow(
                    h.branchId(),
                    h.productId(),
                    h.code(),
                    h.description(),
                    h.categoryId(),
                    h.category(),
                    h.unit(),
                    Quantities.format(h.qty()),
                    h.qty().signum() < 0,
                    h.sellMinor(),
                    expected,
                    expected == null,
                    cost ? h.costMinor() : null,
                    cost ? atCost : null,
                    cost && expected != null ? Math.subtractExact(expected, atCost) : null,
                    cost && expected != null ? basisPoints(Math.subtractExact(expected, atCost), atCost) : null));
        }
        Map<UUID, Long> inventory = principal.hasPermission(PROFIT_READ)
                ? accounts.balanceByBranch("inventory", asOf == null ? today : asOf)
                : Map.of();
        // Review F3: a branch that holds no stock but whose inventory account is not zero is reported
        // too, with value at cost 0, so its revaluation difference is not hidden.
        Set<UUID> reported = new LinkedHashSet<>(byBranch.keySet());
        inventory.entrySet().stream()
                .filter(e -> e.getValue() != 0
                        && (filter == null || filter.contains(e.getKey()))
                        && principal.may(PROFIT_READ, e.getKey()))
                .map(Map.Entry::getKey)
                .sorted()
                .forEach(reported::add);
        List<BranchTotal> branches = new ArrayList<>();
        long expectedTotal = 0;
        long atCostTotal = 0;
        boolean costEverywhere = true;
        for (UUID branch : reported) {
            boolean cost = principal.may(PROFIT_READ, branch);
            costEverywhere &= cost;
            long[] t = byBranch.getOrDefault(branch, new long[2]);
            expectedTotal = Math.addExact(expectedTotal, t[0]);
            atCostTotal = Math.addExact(atCostTotal, t[1]);
            long account = inventory.getOrDefault(branch, 0L);
            branches.add(new BranchTotal(
                    branch,
                    t[0],
                    cost ? t[1] : null,
                    cost ? account : null,
                    cost ? Math.subtractExact(t[1], account) : null,
                    cost ? Math.subtractExact(t[0], t[1]) : null,
                    cost ? basisPoints(Math.subtractExact(t[0], t[1]), t[1]) : null));
        }
        boolean costTotal = costEverywhere && principal.hasPermission(PROFIT_READ);
        List<CategoryTotal> categories = new ArrayList<>();
        byCategory.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().name.toLowerCase(Locale.ROOT)))
                .forEach(e -> {
                    CategoryAcc c = e.getValue();
                    boolean cost = c.costEverywhere && principal.hasPermission(PROFIT_READ);
                    categories.add(new CategoryTotal(
                            e.getKey(),
                            c.name,
                            c.sales,
                            cost ? c.cost : null,
                            cost ? Math.subtractExact(c.sales, c.cost) : null,
                            cost ? basisPoints(Math.subtractExact(c.sales, c.cost), c.cost) : null));
                });
        return new Valuation(
                asOf == null ? today : asOf,
                tenant.profile().currency(),
                rows,
                branches,
                categories,
                expectedTotal,
                costTotal ? atCostTotal : null,
                costTotal ? Math.subtractExact(expectedTotal, atCostTotal) : null,
                costTotal ? basisPoints(Math.subtractExact(expectedTotal, atCostTotal), atCostTotal) : null);
    }

    private static final class CategoryAcc {
        final String name;
        long sales;
        long cost;
        boolean costEverywhere = true;

        CategoryAcc(String name) {
            this.name = name;
        }
    }

    /**
     * Profit over cost in basis points, half up; null when the cost is not above zero, where a
     * percentage over cost has no meaning.
     */
    static Long basisPoints(long profit, long cost) {
        if (cost <= 0) {
            return null;
        }
        return BigDecimal.valueOf(profit)
                .multiply(BigDecimal.valueOf(10_000))
                .divide(BigDecimal.valueOf(cost), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** FR-RET-10: sale lines less their cost snapshots less usage and damage at cost, per branch and day. */
    @Transactional(readOnly = true)
    DailyProfit dailyProfit(List<UUID> branchIds, LocalDate from, LocalDate to) {
        Principal principal = CurrentPrincipal.require();
        List<UUID> filter = principal.branchFilter(PROFIT_READ, branchIds);
        LocalDate end = to == null ? clock.today(tenant.profile().timezone()) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw ApiException.validation(List.of(
                    new FieldProblem("from", "invalid", "from must be on or before to, at most 366 days apart.")));
        }
        List<ProfitRow> rows = new ArrayList<>();
        long sales = 0;
        long costOfSales = 0;
        long usage = 0;
        for (DayFigures d : repo.daily(filter, start, end)) {
            long gross = Math.subtractExact(d.sales(), d.costOfSales());
            rows.add(new ProfitRow(
                    d.branchId(),
                    d.date(),
                    d.sales(),
                    d.costOfSales(),
                    gross,
                    d.usageCost(),
                    Math.subtractExact(gross, d.usageCost())));
            sales = Math.addExact(sales, d.sales());
            costOfSales = Math.addExact(costOfSales, d.costOfSales());
            usage = Math.addExact(usage, d.usageCost());
        }
        return new DailyProfit(
                start,
                end,
                tenant.profile().currency(),
                rows,
                sales,
                costOfSales,
                usage,
                Math.subtractExact(Math.subtractExact(sales, costOfSales), usage));
    }
}
