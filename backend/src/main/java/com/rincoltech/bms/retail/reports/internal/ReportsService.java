package com.rincoltech.bms.retail.reports.internal;

import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.reports.internal.ReportsApi.BranchTotal;
import com.rincoltech.bms.retail.reports.internal.ReportsApi.DailyProfit;
import com.rincoltech.bms.retail.reports.internal.ReportsApi.ProfitRow;
import com.rincoltech.bms.retail.reports.internal.ReportsApi.Valuation;
import com.rincoltech.bms.retail.reports.internal.ReportsApi.ValuationRow;
import com.rincoltech.bms.retail.reports.internal.ReportsRepository.DayFigures;
import com.rincoltech.bms.retail.reports.internal.ReportsRepository.Holding;
import com.rincoltech.bms.retail.stock.Quantities;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * (ADR-020 decision 6), with branch and overall totals. A caller with {@code retail.profit.read}
     * also gets each branch's inventory account balance and the revaluation difference (decision 8).
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
        boolean cost = principal.hasPermission(PROFIT_READ);
        List<ValuationRow> rows = new ArrayList<>();
        Map<UUID, long[]> byBranch = new LinkedHashMap<>();
        for (Holding h : repo.holdings(filter, asOf, tenant.profile().timezone().getId())) {
            long expected = Quantities.value(h.qty(), h.sellMinor());
            long atCost = Quantities.value(h.qty(), h.costMinor());
            long[] t = byBranch.computeIfAbsent(h.branchId(), b -> new long[2]);
            t[0] = Math.addExact(t[0], expected);
            t[1] = Math.addExact(t[1], atCost);
            rows.add(new ValuationRow(
                    h.branchId(),
                    h.productId(),
                    h.code(),
                    h.description(),
                    h.unit(),
                    Quantities.format(h.qty()),
                    h.qty().signum() < 0,
                    h.sellMinor(),
                    expected,
                    cost ? h.costMinor() : null,
                    cost ? atCost : null));
        }
        Map<UUID, Long> inventory =
                cost ? accounts.balanceByBranch("inventory", asOf == null ? today : asOf) : Map.of();
        List<BranchTotal> branches = new ArrayList<>();
        long expectedTotal = 0;
        long atCostTotal = 0;
        for (Map.Entry<UUID, long[]> e : byBranch.entrySet()) {
            long[] t = e.getValue();
            expectedTotal = Math.addExact(expectedTotal, t[0]);
            atCostTotal = Math.addExact(atCostTotal, t[1]);
            long account = inventory.getOrDefault(e.getKey(), 0L);
            branches.add(new BranchTotal(
                    e.getKey(),
                    t[0],
                    cost ? t[1] : null,
                    cost ? account : null,
                    cost ? Math.subtractExact(t[1], account) : null));
        }
        return new Valuation(
                asOf == null ? today : asOf,
                tenant.profile().currency(),
                rows,
                branches,
                expectedTotal,
                cost ? atCostTotal : null);
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
