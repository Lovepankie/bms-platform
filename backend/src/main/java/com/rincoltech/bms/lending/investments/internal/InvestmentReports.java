package com.rincoltech.bms.lending.investments.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.investments.InvestmentMetrics;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Maturities;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Maturity;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Metrics;
import com.rincoltech.bms.lending.investments.internal.InvestmentRepository.Inv;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The maturity ladder and the investment metrics (FR-INV-12; chapter 14 section 14.5): balances
 * from the ledger's two controlled liabilities, returns accrued from the return expense account,
 * flows from the investment transactions net of their reversals, maturities and concentration from
 * the investments. Implements {@link InvestmentMetrics} for insights; the staff routes narrow the
 * branches to the caller's scope first.
 */
@Service
class InvestmentReports implements InvestmentMetrics {

    static final List<String> BUCKETS = List.of("overdue", "0_7", "8_30", "31_90");

    private final JdbcClient jdbc;
    private final InvestmentRepository repo;
    private final InvestmentServicer servicer;
    private final CurrentTenant currentTenant;

    InvestmentReports(
            JdbcClient jdbc, InvestmentRepository repo, InvestmentServicer servicer, CurrentTenant currentTenant) {
        this.jdbc = jdbc;
        this.repo = repo;
        this.servicer = servicer;
        this.currentTenant = currentTenant;
    }

    // ---- Staff routes ---------------------------------------------------------------------

    /** FR-INV-12: the ladder and the investments maturing within {@code days} (90 at most), overdue first. */
    @Transactional(readOnly = true)
    Maturities maturities(Integer days, List<UUID> branchIds) {
        List<UUID> scope = scope(branchIds);
        LocalDate today = servicer.today();
        int horizon = days == null ? 90 : Math.clamp(days, 0, 90);
        List<Maturity> items = new ArrayList<>();
        for (Inv i : repo.maturities(scope, today.plusDays(horizon))) {
            long ret = owedReturn(i);
            items.add(new Maturity(
                    i.id(),
                    i.accountNo(),
                    i.memberName(),
                    i.productName(),
                    i.status(),
                    i.maturityDate(),
                    (int) ChronoUnit.DAYS.between(today, i.maturityDate()),
                    i.principalHeldMinor(),
                    ret,
                    i.principalHeldMinor() + ret,
                    i.maturityInstruction()));
        }
        List<InvestmentApi.LadderBucket> ladder = maturityLadder(today, scope).stream()
                .map(b -> new InvestmentApi.LadderBucket(
                        b.bucket(), b.count(), b.principalMinor(), b.returnMinor(), b.totalMinor()))
                .toList();
        return new Maturities(currentTenant.profile().currency(), today, ladder, items);
    }

    /** FR-INV-12: the metrics, the ladder and the concentration, for the caller's branches. */
    @Transactional(readOnly = true)
    Metrics report(LocalDate from, LocalDate to, List<UUID> branchIds) {
        List<UUID> scope = scope(branchIds);
        LocalDate today = servicer.today();
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.withDayOfMonth(1);
        Concentration c = concentration(scope, 10);
        return new Metrics(
                start,
                end,
                metrics(start, end, scope).stream()
                        .map(m -> new InvestmentApi.Metric(
                                m.key(), m.label(), m.kind(), m.value(), m.currency(), m.definition()))
                        .toList(),
                maturityLadder(today, scope).stream()
                        .map(b -> new InvestmentApi.LadderBucket(
                                b.bucket(), b.count(), b.principalMinor(), b.returnMinor(), b.totalMinor()))
                        .toList(),
                c.totalPrincipalMinor(),
                c.investors().stream()
                        .map(s -> new InvestmentApi.Share(s.id(), s.label(), s.principalMinor(), s.shareBp()))
                        .toList(),
                c.products().stream()
                        .map(s -> new InvestmentApi.Share(s.id(), s.label(), s.principalMinor(), s.shareBp()))
                        .toList());
    }

    private static List<UUID> scope(List<UUID> requested) {
        Principal principal = CurrentPrincipal.require();
        return principal.branchFilter("lending.investments.read", requested);
    }

    // ---- InvestmentMetrics ----------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<Metric> metrics(LocalDate from, LocalDate to, List<UUID> branchIds) {
        if (branchIds != null && branchIds.isEmpty()) {
            branchIds = List.of(new UUID(0, 0));
        }
        String currency = currentTenant.profile().currency();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", from);
        p.put("to", to);
        String branchFilter = "";
        if (branchIds != null) {
            p.put("branchIds", branchIds);
            branchFilter = " AND e.branch_id IN (:branchIds)";
        }
        String ledger = """
                SELECT coalesce(sum(%s), 0) FROM journal_lines l
                  JOIN journal_entries e ON e.id = l.entry_id
                  JOIN gl_accounts a ON a.id = l.account_id
                 WHERE a.system_key = '%s' AND %s
                """;
        long principal = jdbc.sql(ledger.formatted("l.credit - l.debit", InvestmentBooks.PAYABLE, "e.entry_date <= :to")
                        + branchFilter)
                .params(p)
                .query(Long.class)
                .single();
        long payable = jdbc.sql(
                        ledger.formatted("l.credit - l.debit", InvestmentBooks.RETURNS_PAYABLE, "e.entry_date <= :to")
                                + branchFilter)
                .params(p)
                .query(Long.class)
                .single();
        long accrued = jdbc.sql(ledger.formatted(
                                "l.debit - l.credit",
                                InvestmentBooks.RETURN_EXPENSE,
                                "e.entry_date BETWEEN :from AND :to AND e.source_module = 'lending'")
                        + branchFilter)
                .params(p)
                .query(Long.class)
                .single();
        long penalties = jdbc.sql(ledger.formatted(
                                "l.credit - l.debit",
                                InvestmentBooks.PENALTY_INCOME,
                                "e.entry_date BETWEEN :from AND :to")
                        + branchFilter)
                .params(p)
                .query(Long.class)
                .single();
        // Flows by value date, a reversal counting against the type it reverses.
        String flows = """
                SELECT coalesce(sum(CASE WHEN t.txn_type = 'reversal' THEN -o.%1$s ELSE t.%1$s END), 0)
                  FROM lending_investment_transactions t
                  LEFT JOIN lending_investment_transactions o ON o.id = t.reverses_txn_id
                 WHERE coalesce(o.txn_type, t.txn_type) IN (%2$s) AND t.value_date BETWEEN :from AND :to
                """;
        String txnBranch = branchIds == null ? "" : " AND t.branch_id IN (:branchIds)";
        long inflows = jdbc.sql(flows.formatted("amount_minor", "'funding'") + txnBranch)
                .params(p)
                .query(Long.class)
                .single();
        long outflows = jdbc.sql(
                        flows.formatted("amount_minor", "'return_payout', 'maturity_payout', 'early_withdrawal'")
                                + txnBranch)
                .params(p)
                .query(Long.class)
                .single();
        long returnsPaid = jdbc.sql(
                        flows.formatted("return_minor", "'return_payout', 'maturity_payout', 'early_withdrawal'")
                                + txnBranch)
                .params(p)
                .query(Long.class)
                .single();
        long reinvested = jdbc.sql(flows.formatted("return_minor", "'rollover_out'") + txnBranch)
                .params(p)
                .query(Long.class)
                .single();
        String counts = """
                SELECT count(*) AS n, count(DISTINCT member_id) AS members FROM lending_investments i
                 WHERE i.status IN ('active', 'matured') AND i.principal_held_minor > 0
                """ + (branchIds == null ? "" : " AND i.branch_id IN (:branchIds)");
        long[] n = jdbc.sql(counts)
                .params(p)
                .query((rs, row) -> new long[] {rs.getLong("n"), rs.getLong("members")})
                .single();
        return List.of(
                new Metric(
                        KEY + ".balance",
                        "Investments held",
                        "money",
                        principal,
                        currency,
                        "Balance of member investments payable (2020) at the end date"),
                new Metric(
                        KEY + ".returns_payable",
                        "Returns payable",
                        "money",
                        payable,
                        currency,
                        "Balance of investment returns payable (2021) at the end date: accrued, not yet paid"),
                new Metric(
                        KEY + ".inflows",
                        "New money invested",
                        "money",
                        inflows,
                        currency,
                        "Fundings in the period by value date, less fundings reversed; rollovers are not new money"),
                new Metric(
                        KEY + ".outflows",
                        "Money paid to investors",
                        "money",
                        outflows,
                        currency,
                        "Return payouts, maturity payouts and early withdrawals paid in the period, less reversals"),
                new Metric(
                        KEY + ".returns_accrued",
                        "Returns accrued",
                        "money",
                        accrued,
                        currency,
                        "Net debits to investment return expense (5020) in the period"),
                new Metric(
                        KEY + ".returns_paid",
                        "Returns paid",
                        "money",
                        returnsPaid,
                        currency,
                        "The return part of payouts and early withdrawals in the period, less reversals"),
                new Metric(
                        KEY + ".returns_reinvested",
                        "Returns rolled over",
                        "money",
                        reinvested,
                        currency,
                        "Unpaid returns moved into a new investment by a rollover in the period"),
                new Metric(
                        KEY + ".penalties",
                        "Early withdrawal penalties",
                        "money",
                        penalties,
                        currency,
                        "Net credits to investment penalty income (4060) in the period"),
                new Metric(
                        KEY + ".open_count",
                        "Open investments",
                        "count",
                        n[0],
                        null,
                        "Investments active or matured with principal still held, now"),
                new Metric(
                        KEY + ".investor_count",
                        "Investors",
                        "count",
                        n[1],
                        null,
                        "Members holding at least one open investment, now"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<LadderBucket> maturityLadder(LocalDate asOf, List<UUID> branchIds) {
        Map<String, long[]> sums = new LinkedHashMap<>();
        BUCKETS.forEach(b -> sums.put(b, new long[3]));
        for (Inv i : repo.maturities(branchIds, asOf.plusDays(90))) {
            long days = ChronoUnit.DAYS.between(asOf, i.maturityDate());
            String bucket = i.status().equals("matured") || days < 0
                    ? "overdue"
                    : days <= 7 ? "0_7" : days <= 30 ? "8_30" : "31_90";
            long[] s = sums.get(bucket);
            s[0]++;
            s[1] += i.principalHeldMinor();
            s[2] += owedReturn(i);
        }
        List<LadderBucket> out = new ArrayList<>();
        sums.forEach((b, s) -> out.add(new LadderBucket(b, (int) s[0], s[1], s[2], s[1] + s[2])));
        return out;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Concentration concentration(List<UUID> branchIds, int top) {
        if (branchIds != null && branchIds.isEmpty()) {
            return new Concentration(0, List.of(), List.of());
        }
        String where = " WHERE i.status IN ('active', 'matured') AND i.principal_held_minor > 0"
                + (branchIds == null ? "" : " AND i.branch_id IN (:branchIds)");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("top", top);
        if (branchIds != null) {
            p.put("branchIds", branchIds);
        }
        long total = jdbc.sql("SELECT coalesce(sum(i.principal_held_minor), 0) FROM lending_investments i" + where)
                .params(p)
                .query(Long.class)
                .single();
        List<Share> investors = jdbc.sql("""
                        SELECT m.id, m.member_no || ' ' || m.full_name AS label, sum(i.principal_held_minor) AS held
                          FROM lending_investments i JOIN lending_members m ON m.id = i.member_id
                        """ + where
                        + " GROUP BY m.id, m.member_no, m.full_name ORDER BY held DESC, m.member_no LIMIT :top")
                .params(p)
                .query((rs, n) -> new Share(
                        rs.getObject("id", UUID.class),
                        rs.getString("label"),
                        rs.getLong("held"),
                        share(rs.getLong("held"), total)))
                .list();
        List<Share> products = jdbc.sql("""
                        SELECT pr.id, pr.code || ' ' || pr.name AS label, sum(i.principal_held_minor) AS held
                          FROM lending_investments i JOIN lending_investment_products pr ON pr.id = i.product_id
                        """ + where + " GROUP BY pr.id, pr.code, pr.name ORDER BY held DESC, pr.code")
                .params(p)
                .query((rs, n) -> new Share(
                        rs.getObject("id", UUID.class),
                        rs.getString("label"),
                        rs.getLong("held"),
                        share(rs.getLong("held"), total)))
                .list();
        return new Concentration(total, investors, products);
    }

    /** What the investment still owes in return at maturity: the agreed return not yet paid, or the payable once matured. */
    private static long owedReturn(Inv i) {
        return i.status().equals("matured")
                ? i.returnPayableMinor()
                : Math.max(0, i.agreedReturnMinor() - i.returnPaidMinor());
    }

    private static int share(long part, long total) {
        return total == 0
                ? 0
                : (int) InvestmentReturns.roundHalfUp(
                        BigInteger.valueOf(part).multiply(BigInteger.valueOf(10_000)), BigInteger.valueOf(total));
    }
}
