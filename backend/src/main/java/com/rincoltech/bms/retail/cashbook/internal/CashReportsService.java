package com.rincoltech.bms.retail.cashbook.internal;

import static com.rincoltech.bms.retail.cashbook.internal.CashbookSupport.mayProfit;

import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.cashbook.internal.CashFigures.Day;
import com.rincoltech.bms.retail.cashbook.internal.CashFigures.Position;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.AdvanceParty;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.AdvanceReport;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.BankingDay;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.BankingEntry;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.BankingReport;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.CashDailyReport;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.CashDay;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ExpenseGroup;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.ExpenseReport;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.SavingsDay;
import com.rincoltech.bms.retail.cashbook.internal.CashbookApi.SavingsReport;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.AdvanceRow;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.BankingRow;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.ExpenseGroupRow;
import com.rincoltech.bms.retail.cashbook.internal.CashbookRepository.SavingsRow;
import com.rincoltech.bms.retail.reports.DailyProfits;
import com.rincoltech.bms.retail.stock.RetailBranchContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cash book reports (FR-RET-23, FR-RET-27, FR-RET-29; chapter 7 section 7.11.21): the daily cash
 * summary, the banking report, expenses by category and item, savings by day, and outstanding
 * advances by party. Every figure that embeds savings or cash purchases, and the day's profit, is
 * absent without {@code retail.profit.read} in the row's branch (ADR-022 decision 13).
 */
@Service
class CashReportsService {

    static final Set<String> GROUPS = Set.of("category", "item", "branch", "month");

    private final CashbookRepository repo;
    private final CashbookSupport support;
    private final CashFigures figures;
    private final DailyProfits profits;
    private final RetailBranchContext branchContext;

    CashReportsService(
            CashbookRepository repo,
            CashbookSupport support,
            CashFigures figures,
            DailyProfits profits,
            RetailBranchContext branchContext) {
        this.repo = repo;
        this.support = support;
        this.figures = figures;
        this.profits = profits;
        this.branchContext = branchContext;
    }

    /** The active branches the caller may read, narrowed to the ones asked for. */
    private List<Branches.Branch> branchesFor(List<UUID> requested) {
        List<Branches.Branch> visible = branchContext.visible(CashbookSupport.READ);
        if (requested == null || requested.isEmpty()) {
            return visible;
        }
        return visible.stream().filter(b -> requested.contains(b.id())).toList();
    }

    private List<UUID> filter(List<UUID> requested) {
        return CurrentPrincipal.require().branchFilter(CashbookSupport.READ, requested);
    }

    // ------------------------------------------------------------------------------------- daily

    @Transactional(readOnly = true)
    CashDailyReport daily(List<UUID> branchIds, LocalDate from, LocalDate to) {
        LocalDate[] range = support.range(from, to);
        List<CashDay> rows = new ArrayList<>();
        for (Branches.Branch b : branchesFor(branchIds)) {
            LocalDate firstLive = repo.firstLiveDay(b.id());
            LocalDate start = firstLive != null && firstLive.isBefore(range[0]) ? firstLive : range[0];
            NavigableMap<LocalDate, Day> days = figures.days(b.id(), start, range[1]);
            Map<LocalDate, Position> positions = figures.positions(b.id(), days, range[0], range[1]);
            Map<LocalDate, Long> running = figures.unbankedRunning(b.id(), firstLive, days);
            boolean profit = mayProfit(b.id());
            Map<LocalDate, Long> dailyProfit = profit ? profits.profitByDay(b.id(), range[0], range[1]) : Map.of();
            for (Day d : days.subMap(range[0], true, range[1], true).values()) {
                Position p = positions.get(d.date());
                boolean ledger = !d.historical();
                rows.add(new CashDay(
                        b.id(),
                        d.date(),
                        ledger,
                        d.historical(),
                        d.cashTakings(),
                        d.cashSaleVoids(),
                        d.expenseVoids(),
                        d.advanceVoids(),
                        d.repaymentVoids(),
                        d.bankingVoids(),
                        d.withdrawalVoids(),
                        d.expenses(),
                        d.advancesOut(),
                        d.repaymentsIn(),
                        d.withdrawalsIn(),
                        d.banked(),
                        d.cashExpected(),
                        profit ? d.cashPurchases() : null,
                        profit ? d.savings() : null,
                        profit ? d.savingsVoids() : null,
                        profit && ledger && p != null ? p.openingMinor() : null,
                        profit && ledger && p != null ? p.closingMinor() : null,
                        profit && ledger && p != null ? p.otherMovementsMinor() : null,
                        profit ? d.expectedToBank() : null,
                        profit ? running.get(d.date()) : null,
                        profit ? dailyProfit.getOrDefault(d.date(), 0L) : null));
            }
        }
        rows.sort(Comparator.comparing(CashDay::businessDate).reversed().thenComparing(CashDay::branchId));
        return new CashDailyReport(range[0], range[1], support.currency(), rows);
    }

    // ----------------------------------------------------------------------------------- banking

    @Transactional(readOnly = true)
    BankingReport banking(List<UUID> branchIds, LocalDate from, LocalDate to, String flag) {
        LocalDate[] range = support.range(from, to);
        long tolerance = support.toleranceMinor();
        List<BankingDay> rows = new ArrayList<>();
        List<Branches.Branch> branches = branchesFor(branchIds);
        List<UUID> ids = branches.stream().map(Branches.Branch::id).toList();
        Map<String, List<BankingRow>> entries = ids.isEmpty()
                ? Map.of()
                : repo.bankingEntries(ids, range[0], range[1]).stream()
                        .collect(Collectors.groupingBy(e -> e.branchId() + "|" + e.businessDate()));
        for (Branches.Branch b : branches) {
            LocalDate firstLive = repo.firstLiveDay(b.id());
            LocalDate start = firstLive != null && firstLive.isBefore(range[0]) ? firstLive : range[0];
            NavigableMap<LocalDate, Day> days = figures.days(b.id(), start, range[1]);
            Map<LocalDate, Long> running = figures.unbankedRunning(b.id(), firstLive, days);
            boolean profit = mayProfit(b.id());
            for (Day d : days.subMap(range[0], true, range[1], true).values()) {
                List<BankingRow> own = entries.getOrDefault(b.id() + "|" + d.date(), List.of());
                // A row appears only for a figure the caller may see: a cash restock or savings alone must not
                // show a caller without profit access that one happened.
                if (own.isEmpty()
                        && d.cashExpected() == 0
                        && d.bankedNet() == 0
                        && (!profit || d.expectedToBank() == 0)) {
                    continue;
                }
                String dayFlag = CashFlag.of(d.expectedToBank(), d.bankedNet(), tolerance);
                if (flag != null && !(profit && flag.equals(dayFlag))) {
                    continue;
                }
                rows.add(new BankingDay(
                        b.id(),
                        d.date(),
                        d.historical(),
                        d.cashExpected(),
                        d.bankedNet(),
                        profit ? d.expectedToBank() : null,
                        profit ? d.bankedNet() - d.expectedToBank() : null,
                        profit ? dayFlag : null,
                        profit ? running.get(d.date()) : null,
                        own.stream()
                                .map(e -> new BankingEntry(
                                        e.id(),
                                        e.amountMinor(),
                                        e.bankedAt(),
                                        e.by(),
                                        e.byName(),
                                        e.voided().voided()))
                                .toList()));
            }
        }
        rows.sort(Comparator.comparing(BankingDay::businessDate).reversed().thenComparing(BankingDay::branchId));
        return new BankingReport(range[0], range[1], support.currency(), rows);
    }

    // ---------------------------------------------------------------------------------- expenses

    @Transactional(readOnly = true)
    ExpenseReport expenses(List<UUID> branchIds, LocalDate from, LocalDate to, String groupBy, boolean includeVoided) {
        LocalDate[] range = support.range(from, to);
        String group = groupBy == null ? "category" : groupBy;
        if (!GROUPS.contains(group)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("group_by", "invalid", "Group by category, item, branch or month.")));
        }
        List<UUID> filter = filter(branchIds);
        List<ExpenseGroupRow> groups = repo.expenseGroups(group, filter, range[0], range[1], includeVoided);
        long total = groups.stream().mapToLong(ExpenseGroupRow::total).sum();
        long count = groups.stream().mapToLong(ExpenseGroupRow::count).sum();
        return new ExpenseReport(
                range[0],
                range[1],
                support.currency(),
                group,
                total,
                count,
                groups.stream()
                        .map(g -> new ExpenseGroup(g.key(), g.label(), g.branchId(), g.total(), g.count()))
                        .toList());
    }

    // ------------------------------------------------------------------------------------ savings

    @Transactional(readOnly = true)
    SavingsReport savings(List<UUID> branchIds, LocalDate from, LocalDate to) {
        LocalDate[] range = support.range(from, to);
        List<SavingsRow> rows = repo.savingsReport(filter(branchIds), range[0], range[1]);
        Map<UUID, Map<LocalDate, Long>> profitByBranch = new LinkedHashMap<>();
        List<SavingsDay> items = new ArrayList<>();
        for (SavingsRow r : rows) {
            boolean profit = mayProfit(r.branchId());
            Long dailyProfit = null;
            if (profit) {
                dailyProfit = profitByBranch
                        .computeIfAbsent(r.branchId(), b -> profits.profitByDay(b, range[0], range[1]))
                        .getOrDefault(r.businessDate(), 0L);
            }
            items.add(new SavingsDay(
                    r.branchId(),
                    r.businessDate(),
                    r.totalSoldMinor(),
                    profit ? r.amountMinor() : null,
                    profit ? r.suggestedMinor() : null,
                    profit ? r.overwritten() : null,
                    dailyProfit));
        }
        return new SavingsReport(range[0], range[1], support.currency(), items);
    }

    // ----------------------------------------------------------------------------------- advances

    @Transactional(readOnly = true)
    AdvanceReport advances(List<UUID> branchIds) {
        List<AdvanceRow> open = repo.openAdvances(filter(branchIds));
        Map<UUID, List<AdvanceRow>> byParty = open.stream().collect(Collectors.groupingBy(AdvanceRow::partyId));
        List<AdvanceParty> items = new ArrayList<>();
        byParty.forEach((party, list) -> items.add(new AdvanceParty(
                party,
                list.getFirst().partyName(),
                list.size(),
                list.stream().mapToLong(AdvanceRow::principalMinor).sum(),
                list.stream().mapToLong(AdvanceRow::repaidMinor).sum(),
                list.stream()
                        .mapToLong(a -> a.principalMinor() - a.repaidMinor())
                        .sum(),
                list.stream()
                        .map(AdvanceRow::businessDate)
                        .min(Comparator.naturalOrder())
                        .orElseThrow())));
        items.sort(Comparator.comparingLong(AdvanceParty::balanceMinor).reversed());
        return new AdvanceReport(
                support.currency(),
                items.stream().mapToLong(AdvanceParty::balanceMinor).sum(),
                items);
    }
}
