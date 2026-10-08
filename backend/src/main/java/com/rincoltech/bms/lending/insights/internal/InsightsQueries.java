package com.rincoltech.bms.lending.insights.internal;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The flow and count queries of the insights page (chapter 14 sections 14.3, 14.4 and 14.9).
 * Plain SQL, tenant scoped by row-level security (ADR-003), every one filtered through
 * {@link Scope#loanFilter}. Amounts are summed in the database as integers.
 */
@Repository
class InsightsQueries {

    /** A money and count total on one date. */
    record DayTotal(LocalDate date, long amountMinor, long count) {}

    /** Collected on due: allocations to items due on {@code dueDate} from repayments dated {@code paidOn}. */
    record DuePaid(LocalDate dueDate, LocalDate paidOn, long amountMinor) {}

    /** Posted journal amounts by entry date, account, product and branch (income positive). */
    record LedgerAmount(LocalDate date, String systemKey, UUID productId, UUID branchId, long amountMinor) {}

    static final String COLLECTED_TYPES = "('repayment', 'recovery')";

    /** Ledger accounts the revenue page reads, by system key (chapter 6 section 6.6.2). */
    static final List<String> REVENUE_ACCOUNTS = List.of(
            "loan_interest_income",
            "loan_fee_income",
            "loan_penalty_income",
            "bad_debt_recovered",
            "loan_write_off_expense");

    private final JdbcClient jdbc;

    InsightsQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private Map<String, Object> range(Scope s) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", Date.valueOf(s.from()));
        p.put("to", Date.valueOf(s.to()));
        return p;
    }

    /** Disbursements by value date in the range. */
    List<DayTotal> disbursedByDay(Scope s) {
        if (s.empty()) {
            return List.of();
        }
        Map<String, Object> p = range(s);
        String sql = """
                SELECT t.value_date, sum(t.amount_minor) AS amount, count(*) AS n
                  FROM lending_loan_transactions t
                  JOIN lending_loans l ON l.id = t.loan_id
                 WHERE t.txn_type = 'disbursement' AND t.value_date BETWEEN :from AND :to""" + s.loanFilter("l", p) + """

                 GROUP BY t.value_date ORDER BY t.value_date
                """;
        return jdbc.sql(sql).params(p).query(InsightsQueries::dayTotal).list();
    }

    /** Repayments and recoveries by value date in the range, a reversed one excluded (chapter 14 section 14.1). */
    List<DayTotal> collectedByDay(Scope s, String types) {
        if (s.empty()) {
            return List.of();
        }
        Map<String, Object> p = range(s);
        String sql = """
                SELECT t.value_date, sum(t.amount_minor) AS amount, count(*) AS n
                  FROM lending_loan_transactions t
                  JOIN lending_loans l ON l.id = t.loan_id
                 WHERE t.txn_type IN """ + types + " " + """
                    AND t.value_date BETWEEN :from AND :to
                   AND NOT EXISTS (SELECT 1 FROM lending_loan_transactions r WHERE r.reverses_txn_id = t.id)""" + s.loanFilter("l", p) + """

                 GROUP BY t.value_date ORDER BY t.value_date
                """;
        return jdbc.sql(sql).params(p).query(InsightsQueries::dayTotal).list();
    }

    /** Write-offs by date in the range (principal written off). */
    List<DayTotal> writtenOffByDay(Scope s) {
        if (s.empty()) {
            return List.of();
        }
        Map<String, Object> p = range(s);
        String sql = """
                SELECT t.value_date, sum(t.amount_minor) AS amount, count(*) AS n
                  FROM lending_loan_transactions t
                  JOIN lending_loans l ON l.id = t.loan_id
                 WHERE t.txn_type = 'write_off' AND t.value_date BETWEEN :from AND :to""" + s.loanFilter("l", p) + """

                 GROUP BY t.value_date ORDER BY t.value_date
                """;
        return jdbc.sql(sql).params(p).query(InsightsQueries::dayTotal).list();
    }

    /** The loans whose schedule items count as expected in the range: active at some point in it. */
    private static final String ACTIVE_IN_RANGE = " " + """
             AND l.disbursed_on IS NOT NULL
             AND (l.written_off_on IS NULL OR l.written_off_on >= :from)
             AND (l.closed_on IS NULL OR l.closed_on >= :from)""";

    /** Principal, interest and fees due by due date in the range. */
    List<DayTotal> expectedByDay(Scope s) {
        if (s.empty()) {
            return List.of();
        }
        Map<String, Object> p = range(s);
        String sql = """
                SELECT si.due_date AS value_date,
                       sum(si.principal_due_minor + si.interest_due_minor + si.fees_due_minor) AS amount, count(*) AS n
                  FROM lending_schedule_items si
                  JOIN lending_loans l ON l.id = si.loan_id
                 WHERE si.due_date BETWEEN :from AND :to""" + ACTIVE_IN_RANGE + s.loanFilter("l", p) + """

                 GROUP BY si.due_date ORDER BY si.due_date
                """;
        return jdbc.sql(sql).params(p).query(InsightsQueries::dayTotal).list();
    }

    /**
     * Allocations of principal, interest and fees to items due in the range, by due date and by the
     * value date of the repayment they now belong to ({@code applies_to_txn_id}). A reversed
     * repayment's rows and their negatives sum to zero, and a re-allocated repayment carries its
     * final split, so no reversed money is counted.
     */
    List<DuePaid> collectedOnDue(Scope s) {
        if (s.empty()) {
            return List.of();
        }
        Map<String, Object> p = range(s);
        String sql = """
                SELECT si.due_date, t.value_date, sum(a.amount_minor) AS amount
                  FROM lending_repayment_allocations a
                  JOIN lending_loan_transactions t ON t.id = a.applies_to_txn_id
                  JOIN lending_schedule_items si ON si.id = a.schedule_item_id
                  JOIN lending_loans l ON l.id = si.loan_id
                 WHERE a.component IN ('principal', 'interest', 'fee')
                   AND si.due_date BETWEEN :from AND :to
                   AND t.value_date <= :to""" + ACTIVE_IN_RANGE + s.loanFilter("l", p) + """

                 GROUP BY si.due_date, t.value_date
                HAVING sum(a.amount_minor) <> 0
                """;
        return jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> new DuePaid(
                        rs.getDate("due_date").toLocalDate(),
                        rs.getDate("value_date").toLocalDate(),
                        rs.getLong("amount")))
                .list();
    }

    /** Unpaid principal, interest and fees of items of active loans due after today, up to {@code until}. */
    List<DayTotal> forecast(Scope s, LocalDate until) {
        if (s.empty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("today", Date.valueOf(s.today()));
        p.put("until", Date.valueOf(until));
        String sql = """
                SELECT si.due_date AS value_date,
                       sum((si.principal_due_minor - si.principal_paid_minor)
                           + (si.interest_due_minor - si.interest_paid_minor - si.interest_waived_minor)
                           + (si.fees_due_minor - si.fees_paid_minor - si.fees_waived_minor)) AS amount,
                       count(*) AS n
                  FROM lending_schedule_items si
                  JOIN lending_loans l ON l.id = si.loan_id
                 WHERE l.status = 'active' AND si.due_date > :today AND si.due_date <= :until""" + s.loanFilter("l", p) + """

                 GROUP BY si.due_date ORDER BY si.due_date
                """;
        return jdbc.sql(sql).params(p).query(InsightsQueries::dayTotal).list().stream()
                .filter(d -> d.amountMinor() > 0)
                .toList();
    }

    /** Members disbursed a loan in the range, and how many of them had an earlier disbursed loan. */
    long[] repeatBorrowers(Scope s) {
        if (s.empty()) {
            return new long[] {0, 0};
        }
        Map<String, Object> p = range(s);
        String sql = """
                WITH d AS (
                    SELECT l.member_id, min(t.value_date) AS first_in
                      FROM lending_loan_transactions t
                      JOIN lending_loans l ON l.id = t.loan_id
                     WHERE t.txn_type = 'disbursement' AND t.value_date BETWEEN :from AND :to""" + s.loanFilter("l", p) + """

                     GROUP BY l.member_id
                )
                SELECT count(*) AS members,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM lending_loans e WHERE e.member_id = d.member_id AND e.disbursed_on < d.first_in))
                           AS repeat
                  FROM d
                """;
        return jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> new long[] {rs.getLong("members"), rs.getLong("repeat")})
                .single();
    }

    /** Sum of days from disbursement to maturity, and the number of loans, for disbursements in the range. */
    long[] tenor(Scope s) {
        if (s.empty()) {
            return new long[] {0, 0};
        }
        Map<String, Object> p = range(s);
        String sql = """
                SELECT coalesce(sum(l.maturity_date - l.disbursed_on), 0) AS days, count(*) AS n
                  FROM lending_loans l
                 WHERE l.disbursed_on BETWEEN :from AND :to AND l.maturity_date IS NOT NULL""" + s.loanFilter("l", p);
        return jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> new long[] {rs.getLong("days"), rs.getLong("n")})
                .single();
    }

    /** Count and principal (approved, else requested) of loans by status. */
    List<Object[]> byStatus(Scope s) {
        if (s.empty()) {
            return List.of();
        }
        Map<String, Object> p = new HashMap<>();
        String sql = """
                SELECT l.status, count(*) AS n, sum(coalesce(l.approved_principal_minor, l.requested_principal_minor)) AS amount
                  FROM lending_loans l WHERE true""" + s.loanFilter("l", p) + " GROUP BY l.status ORDER BY l.status";
        return jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> new Object[] {rs.getString("status"), rs.getLong("n"), rs.getLong("amount")})
                .list();
    }

    /**
     * Posted amounts on the revenue accounts by entry date, account, product and branch, for entries
     * dated in {@code [from, to]}: credits less debits, so income is positive and write-off expense
     * negative. Only entries posted by lending for a loan transaction count; a reversal entry keeps
     * the source of the entry it reverses (core.ledger), and a re-allocation entry names the reversal.
     */
    List<LedgerAmount> ledger(Scope s, LocalDate from, LocalDate to) {
        if (s.empty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", Date.valueOf(from));
        p.put("to", Date.valueOf(to));
        p.put("keys", REVENUE_ACCOUNTS);
        String sql = """
                SELECT je.entry_date, ga.system_key, pv.product_id, l.branch_id,
                       sum(jl.credit - jl.debit) AS amount
                  FROM journal_entries je
                  JOIN journal_lines jl ON jl.entry_id = je.id
                  JOIN gl_accounts ga ON ga.id = jl.account_id
                  JOIN lending_loan_transactions t ON t.id = je.source_id
                  JOIN lending_loans l ON l.id = t.loan_id
                  JOIN lending_loan_product_versions pv ON pv.id = l.product_version_id
                 WHERE je.entry_date BETWEEN :from AND :to
                   AND je.source_module = 'lending' AND je.source_type = 'loan_transaction'
                   AND ga.system_key IN (:keys)""" + s.loanFilter("l", p) + """

                 GROUP BY 1, 2, 3, 4
                """;
        return jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> new LedgerAmount(
                        rs.getDate("entry_date").toLocalDate(),
                        rs.getString("system_key"),
                        rs.getObject("product_id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getLong("amount")))
                .list();
    }

    /**
     * Principal outstanding by business date and product from the snapshots in the range, for the
     * average daily balance under the yields.
     */
    List<Object[]> snapshotPrincipal(Scope s, LocalDate from, LocalDate to) {
        if (s.empty() || to.isBefore(from)) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", Date.valueOf(from));
        p.put("to", Date.valueOf(to));
        String sql = """
                SELECT sn.business_date, sn.product_id, sum(sn.principal_outstanding_minor) AS po
                  FROM lending_loan_daily_snapshots sn
                 WHERE sn.business_date BETWEEN :from AND :to""" + s.snapshotFilter("sn", p) + """

                 GROUP BY sn.business_date, sn.product_id
                """;
        return jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> new Object[] {
                    rs.getDate("business_date").toLocalDate(), rs.getObject("product_id", UUID.class), rs.getLong("po")
                })
                .list();
    }

    /** Month-end portfolio from the snapshots: principal outstanding, PAR 30 principal and active loans. */
    List<Object[]> snapshotTrend(Scope s, List<LocalDate> dates) {
        if (s.empty() || dates.isEmpty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dates", dates.stream().map(Date::valueOf).toList());
        String sql = """
                SELECT sn.business_date, sum(sn.principal_outstanding_minor) AS po,
                       coalesce(sum(sn.principal_outstanding_minor) FILTER (WHERE sn.days_past_due > 30), 0) AS par30,
                       count(*) AS n
                  FROM lending_loan_daily_snapshots sn
                 WHERE sn.business_date IN (:dates)""" + s.snapshotFilter("sn", p) + """

                 GROUP BY sn.business_date
                """;
        return jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> new Object[] {
                    rs.getDate("business_date").toLocalDate(), rs.getLong("po"), rs.getLong("par30"), rs.getLong("n")
                })
                .list();
    }

    // ---- Members ----------------------------------------------------------------------------

    /** Branch and officer predicates on a {@code lending_members} alias. */
    private static String memberFilter(Scope s, String alias, Map<String, Object> p) {
        StringBuilder sql = new StringBuilder();
        if (s.branchIds() != null) {
            sql.append(" AND ").append(alias).append(".branch_id IN (:branches)");
            p.put("branches", s.branchIds());
        }
        if (s.officerId() != null) {
            sql.append(" AND ").append(alias).append(".officer_user_id = :officer");
            p.put("officer", s.officerId());
        }
        return sql.toString();
    }

    private static Map<String, Object> instants(Scope s, ZoneId zone) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("fromTs", Timestamp.from(s.from().atStartOfDay(zone).toInstant()));
        p.put("toTs", Timestamp.from(s.to().plusDays(1).atStartOfDay(zone).toInstant()));
        p.put("zone", zone.getId());
        return p;
    }

    /** New members by registration date (tenant time zone). */
    List<DayTotal> newMembersByDay(Scope s, ZoneId zone) {
        if (s.empty()) {
            return List.of();
        }
        Map<String, Object> p = instants(s, zone);
        String sql = """
                SELECT (m.created_at AT TIME ZONE :zone)::date AS value_date, 0 AS amount, count(*) AS n
                  FROM lending_members m
                 WHERE m.created_at >= :fromTs AND m.created_at < :toTs""" + memberFilter(s, "m", p) + """

                 GROUP BY 1 ORDER BY 1
                """;
        return jdbc.sql(sql).params(p).query(InsightsQueries::dayTotal).list();
    }

    /** Applied, appraised, approved and disbursed among applications submitted in the range. */
    long[] funnel(Scope s, ZoneId zone) {
        if (s.empty()) {
            return new long[] {0, 0, 0, 0};
        }
        Map<String, Object> p = instants(s, zone);
        String sql = """
                SELECT count(*) AS applied,
                       count(*) FILTER (WHERE EXISTS (SELECT 1 FROM lending_loan_appraisals a WHERE a.loan_id = l.id))
                           AS appraised,
                       count(*) FILTER (WHERE l.approved_at IS NOT NULL) AS approved,
                       count(*) FILTER (WHERE l.disbursed_on IS NOT NULL) AS disbursed
                  FROM lending_loans l
                 WHERE l.submitted_at >= :fromTs AND l.submitted_at < :toTs""" + s.loanFilter("l", p);
        return jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> new long[] {
                    rs.getLong("applied"), rs.getLong("appraised"), rs.getLong("approved"), rs.getLong("disbursed")
                })
                .single();
    }

    /** Median hours from submission to approval or rejection, for decisions made in the range; null for none. */
    Long medianDecisionHours(Scope s, ZoneId zone) {
        if (s.empty()) {
            return null;
        }
        Map<String, Object> p = instants(s, zone);
        String sql = """
                WITH decided AS (
                    SELECT l.submitted_at,
                           coalesce(l.approved_at,
                                    (SELECT min(h.created_at) FROM lending_loan_status_history h
                                      WHERE h.loan_id = l.id AND h.to_status = 'rejected')) AS decided_at
                      FROM lending_loans l
                     WHERE l.submitted_at IS NOT NULL AND l.status NOT IN ('draft', 'submitted', 'appraised', 'cancelled')""" + s.loanFilter("l", p) + """

                )
                SELECT round(percentile_cont(0.5) WITHIN GROUP (
                           ORDER BY extract(epoch FROM (decided_at - submitted_at)) / 3600.0))::bigint AS hours
                  FROM decided
                 WHERE decided_at >= :fromTs AND decided_at < :toTs AND decided_at >= submitted_at
                """;
        return jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> (Long) rs.getObject("hours", Long.class))
                .single();
    }

    /** Active members by KYC status. */
    List<Object[]> kyc(Scope s) {
        if (s.empty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        String sql = "SELECT m.kyc_status, count(*) AS n FROM lending_members m WHERE m.status = 'active'"
                + memberFilter(s, "m", p) + " GROUP BY m.kyc_status ORDER BY m.kyc_status";
        return jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> new Object[] {rs.getString("kyc_status"), rs.getLong("n")})
                .list();
    }

    /** Latest appraisal band of each application submitted in the range. */
    List<Object[]> scoreBands(Scope s, ZoneId zone) {
        if (s.empty()) {
            return List.of();
        }
        Map<String, Object> p = instants(s, zone);
        String sql = """
                SELECT band, count(*) AS n FROM (
                    SELECT DISTINCT ON (a.loan_id) a.band
                      FROM lending_loan_appraisals a
                      JOIN lending_loans l ON l.id = a.loan_id
                     WHERE l.submitted_at >= :fromTs AND l.submitted_at < :toTs""" + s.loanFilter("l", p) + """

                     ORDER BY a.loan_id, a.created_at DESC
                ) latest GROUP BY band ORDER BY band
                """;
        return jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> new Object[] {rs.getString("band"), rs.getLong("n")})
                .list();
    }

    /** The dormancy condition on a {@code lending_members} alias {@code m}; needs {@code :since}. */
    static final String DORMANT = " " + """
             AND m.status = 'active'
             AND NOT EXISTS (SELECT 1 FROM lending_loans dl WHERE dl.member_id = m.id AND dl.status = 'active')
             AND NOT EXISTS (SELECT 1 FROM lending_loan_transactions dt JOIN lending_loans dl ON dl.id = dt.loan_id
                              WHERE dl.member_id = m.id AND dt.value_date > :since)""";

    long dormant(Scope s) {
        if (s.empty()) {
            return 0;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("since", Date.valueOf(s.today().minusDays(90)));
        String sql = "SELECT count(*) FROM lending_members m WHERE true" + DORMANT + memberFilter(s, "m", p);
        return jdbc.sql(sql).params(p).query(Long.class).single();
    }

    long activeMembers(Scope s) {
        if (s.empty()) {
            return 0;
        }
        Map<String, Object> p = new LinkedHashMap<>();
        String sql = "SELECT count(*) FROM lending_members m WHERE m.status = 'active'" + memberFilter(s, "m", p);
        return jdbc.sql(sql).params(p).query(Long.class).single();
    }

    /** Work done by each staff user in the range: what they registered, submitted, appraised, disbursed and received. */
    List<Object[]> staffActivity(Scope s, ZoneId zone) {
        if (s.empty()) {
            return List.of();
        }
        Map<String, Object> p = instants(s, zone);
        p.put("from", Date.valueOf(s.from()));
        p.put("to", Date.valueOf(s.to()));
        String members = "SELECT m.created_by AS user_id, 'members' AS kind FROM lending_members m"
                + " WHERE m.created_by IS NOT NULL AND m.created_at >= :fromTs AND m.created_at < :toTs"
                + memberFilter(s, "m", p);
        String submitted = "SELECT l.submitted_by, 'submitted' FROM lending_loans l"
                + " WHERE l.submitted_by IS NOT NULL AND l.submitted_at >= :fromTs AND l.submitted_at < :toTs"
                + s.loanFilter("l", p);
        String appraisals = "SELECT a.appraised_by, 'appraisals' FROM lending_loan_appraisals a"
                + " JOIN lending_loans l ON l.id = a.loan_id"
                + " WHERE a.created_at >= :fromTs AND a.created_at < :toTs" + s.loanFilter("l", p);
        String money = "SELECT t.recorded_by, t.txn_type FROM lending_loan_transactions t"
                + " JOIN lending_loans l ON l.id = t.loan_id"
                + " WHERE t.recorded_by IS NOT NULL AND t.txn_type IN ('disbursement', 'repayment')"
                + " AND t.value_date BETWEEN :from AND :to" + s.loanFilter("l", p);
        String sql = "SELECT w.user_id, u.full_name,"
                + " count(*) FILTER (WHERE w.kind = 'members') AS members,"
                + " count(*) FILTER (WHERE w.kind = 'submitted') AS submitted,"
                + " count(*) FILTER (WHERE w.kind = 'appraisals') AS appraisals,"
                + " count(*) FILTER (WHERE w.kind = 'disbursement') AS disbursements,"
                + " count(*) FILTER (WHERE w.kind = 'repayment') AS repayments"
                + " FROM (" + members + " UNION ALL " + submitted + " UNION ALL " + appraisals + " UNION ALL " + money
                + ") w LEFT JOIN users u ON u.id = w.user_id"
                + (s.officerId() != null ? " WHERE w.user_id = :officer" : "")
                + " GROUP BY w.user_id, u.full_name ORDER BY count(*) DESC, w.user_id LIMIT 50";
        if (s.officerId() != null) {
            p.put("officer", s.officerId());
        }
        return jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> new Object[] {
                    rs.getObject("user_id", UUID.class),
                    rs.getString("full_name"),
                    rs.getLong("members"),
                    rs.getLong("submitted"),
                    rs.getLong("appraisals"),
                    rs.getLong("disbursements"),
                    rs.getLong("repayments")
                })
                .list();
    }

    // ---- Names ------------------------------------------------------------------------------

    Map<UUID, String> productNames() {
        Map<UUID, String> names = new HashMap<>();
        jdbc.sql("SELECT id, name FROM lending_loan_products")
                .query((rs, n) -> names.put(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
        return names;
    }

    Map<UUID, String> branchNames() {
        Map<UUID, String> names = new HashMap<>();
        jdbc.sql("SELECT id, name FROM branches")
                .query((rs, n) -> names.put(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
        return names;
    }

    Map<UUID, String> userNames(java.util.Collection<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, full_name FROM users WHERE id IN (:ids)")
                .param("ids", List.copyOf(ids))
                .query((rs, n) -> names.put(rs.getObject("id", UUID.class), rs.getString("full_name")))
                .list();
        return names;
    }

    /** Member number and name by id. */
    Map<UUID, String[]> members(java.util.Collection<UUID> ids) {
        Map<UUID, String[]> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.sql("SELECT id, member_no, full_name FROM lending_members WHERE id IN (:ids)")
                .param("ids", List.copyOf(ids))
                .query((rs, n) -> out.put(
                        rs.getObject("id", UUID.class),
                        new String[] {rs.getString("member_no"), rs.getString("full_name")}))
                .list();
        return out;
    }

    private static DayTotal dayTotal(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new DayTotal(rs.getDate("value_date").toLocalDate(), rs.getLong("amount"), rs.getLong("n"));
    }
}
