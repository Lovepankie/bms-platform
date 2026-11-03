package com.rincoltech.bms.lending.insights.internal;

import com.rincoltech.bms.lending.insights.InsightsFigures.DisbursementLine;
import com.rincoltech.bms.lending.insights.InsightsFigures.ParGroupBy;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The range figures of the formal reports of slice D3 (issue #222; chapter 14 sections 14.3 and 14.4), grouped in the
 * database by branch, product or officer. Each query keeps the predicates of the insights query it must agree with
 * ({@link InsightsQueries#expectedByDay}, {@link InsightsQueries#collectedOnDue}, {@link InsightsQueries#collectedByDay}
 * and {@link InsightsQueries#disbursedByDay}) and only adds the grouping, so a report's total is the insights page's
 * figure for the same range and filter. Plain SQL under row-level security, read only, every query filtered through
 * {@link Scope#loanFilter}; amounts are summed in the database as integers.
 */
@Repository
class FlowFigures {

    /** The parts of {@link #collections}, in the order of its arrays. */
    static final int EXPECTED = 0;

    static final int ON_DUE = 1;
    static final int ON_DUE_IN_RANGE = 2;
    static final int ARREARS_RECOVERED = 3;
    static final int PREPAID = 4;
    static final int PENALTIES = 5;
    static final int OVERPAID = 6;
    static final int RECOVERIES = 7;
    static final int TOTAL = 8;
    static final int PARTS = 9;

    /** The parts of {@link #disbursements}: loans, principal, upfront fees, median (-1 when none). */
    static final int LOANS = 0;

    static final int PRINCIPAL = 1;
    static final int FEES = 2;
    static final int MEDIAN = 3;

    private static final String NOT_REVERSED =
            " AND NOT EXISTS (SELECT 1 FROM lending_loan_transactions r WHERE r.reverses_txn_id = t.id)";
    private static final String PRODUCT_JOIN = " JOIN lending_loan_product_versions pv ON pv.id = l.product_version_id";

    private final JdbcClient jdbc;
    private final JdbcClient streaming;

    FlowFigures(JdbcClient jdbc, javax.sql.DataSource dataSource) {
        this.jdbc = jdbc;
        // As Positions: the transaction in progress, rows handed over in blocks rather than loaded whole.
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(Positions.FETCH);
        this.streaming = JdbcClient.create(template);
    }

    /** The grouping column on the {@code l} (loan) and {@code pv} (product version) aliases. */
    static String groupColumn(ParGroupBy by) {
        return switch (by) {
            case NONE -> "NULL::uuid";
            case BRANCH -> "l.branch_id";
            case PRODUCT -> "pv.product_id";
            case OFFICER -> "l.officer_user_id";
        };
    }

    private static Map<String, Object> range(Scope s) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("from", Date.valueOf(s.from()));
        p.put("to", Date.valueOf(s.to()));
        return p;
    }

    private static long[] part(Map<UUID, long[]> out, UUID key, int size) {
        return out.computeIfAbsent(key, k -> new long[size]);
    }

    /**
     * Expected against collected per group (a null key for {@link ParGroupBy#NONE}), each an array indexed by
     * {@link #EXPECTED} to {@link #TOTAL}. Expected and collected on due are {@code expectedByDay} and
     * {@code collectedOnDue} summed; the total is {@code collectedByDay} of repayments and recoveries. The parts of the
     * total are the allocations of the range's repayments by where they went: items due in the range, before it, after
     * it, penalties and overpayments; a recovery settles no item, so it is its own part. A reversed repayment's rows and
     * their negatives cancel, as in {@code collectedOnDue}.
     */
    Map<UUID, long[]> collections(Scope s, ParGroupBy by) {
        Map<UUID, long[]> out = new LinkedHashMap<>();
        if (s.empty()) {
            return out;
        }
        String g = groupColumn(by);

        Map<String, Object> p = range(s);
        String expected = "SELECT " + g + " AS g,"
                + " sum(si.principal_due_minor + si.interest_due_minor + si.fees_due_minor) AS amount"
                + " FROM lending_schedule_items si JOIN lending_loans l ON l.id = si.loan_id" + PRODUCT_JOIN
                + " WHERE si.due_date BETWEEN :from AND :to" + InsightsQueries.ACTIVE_IN_RANGE + s.loanFilter("l", p)
                + " GROUP BY 1";
        jdbc.sql(expected)
                .params(p)
                .query((rs, n) -> part(out, rs.getObject("g", UUID.class), PARTS)[EXPECTED] = rs.getLong("amount"))
                .list();

        p = range(s);
        String allocations = "SELECT " + g + " AS g," + """
                 coalesce(sum(a.amount_minor) FILTER (WHERE a.component IN ('principal', 'interest', 'fee')
                     AND si.due_date BETWEEN :from AND :to AND l.disbursed_on IS NOT NULL
                     AND (l.written_off_on IS NULL OR l.written_off_on >= :from)
                     AND (l.closed_on IS NULL OR l.closed_on >= :from)), 0) AS on_due,
                 coalesce(sum(a.amount_minor) FILTER (WHERE t.value_date >= :from AND t.txn_type = 'repayment'
                     AND a.component IN ('principal', 'interest', 'fee') AND si.due_date BETWEEN :from AND :to), 0) AS in_range,
                 coalesce(sum(a.amount_minor) FILTER (WHERE t.value_date >= :from AND t.txn_type = 'repayment'
                     AND a.component IN ('principal', 'interest', 'fee') AND si.due_date < :from), 0) AS arrears,
                 coalesce(sum(a.amount_minor) FILTER (WHERE t.value_date >= :from AND t.txn_type = 'repayment'
                     AND a.component IN ('principal', 'interest', 'fee') AND si.due_date > :to), 0) AS prepaid,
                 coalesce(sum(a.amount_minor) FILTER (WHERE t.value_date >= :from AND t.txn_type = 'repayment'
                     AND a.component = 'penalty'), 0) AS penalties,
                 coalesce(sum(a.amount_minor) FILTER (WHERE t.value_date >= :from AND t.txn_type = 'repayment'
                     AND a.component = 'overpayment'), 0) AS overpaid
                  FROM lending_repayment_allocations a
                  JOIN lending_loan_transactions t ON t.id = a.applies_to_txn_id
                  LEFT JOIN lending_schedule_items si ON si.id = a.schedule_item_id
                  JOIN lending_loans l ON l.id = t.loan_id""" + PRODUCT_JOIN + """

                 WHERE t.value_date <= :to""" + s.loanFilter("l", p) + " GROUP BY 1";
        jdbc.sql(allocations)
                .params(p)
                .query((rs, n) -> {
                    long[] a = part(out, rs.getObject("g", UUID.class), PARTS);
                    a[ON_DUE] = rs.getLong("on_due");
                    a[ON_DUE_IN_RANGE] = rs.getLong("in_range");
                    a[ARREARS_RECOVERED] = rs.getLong("arrears");
                    a[PREPAID] = rs.getLong("prepaid");
                    a[PENALTIES] = rs.getLong("penalties");
                    a[OVERPAID] = rs.getLong("overpaid");
                    return 0;
                })
                .list();

        p = range(s);
        String collected = "SELECT " + g + " AS g, sum(t.amount_minor) AS total,"
                + " coalesce(sum(t.amount_minor) FILTER (WHERE t.txn_type = 'recovery'), 0) AS recovered"
                + " FROM lending_loan_transactions t JOIN lending_loans l ON l.id = t.loan_id" + PRODUCT_JOIN
                + " WHERE t.txn_type IN " + InsightsQueries.COLLECTED_TYPES
                + " AND t.value_date BETWEEN :from AND :to" + NOT_REVERSED + s.loanFilter("l", p) + " GROUP BY 1";
        jdbc.sql(collected)
                .params(p)
                .query((rs, n) -> {
                    long[] a = part(out, rs.getObject("g", UUID.class), PARTS);
                    a[TOTAL] = rs.getLong("total");
                    a[RECOVERIES] = rs.getLong("recovered");
                    return 0;
                })
                .list();
        return out;
    }

    /**
     * The loans disbursed in the range per group, each indexed by {@link #LOANS} to {@link #MEDIAN} (a median of -1
     * when there are no loans), over {@link #perLoan}: one row per loan, so the loans, the principal, the fees, the
     * average and the median all count a loan once. The median is the middle loan, or the mean of the two middle ones
     * rounded half up.
     *
     * @return the groups, and under the key {@link #TOTAL_KEY} the whole range
     */
    Map<UUID, long[]> disbursements(Scope s, ParGroupBy by) {
        Map<UUID, long[]> out = new LinkedHashMap<>();
        if (s.empty()) {
            out.put(TOTAL_KEY, new long[] {0, 0, 0, -1});
            return out;
        }
        Map<String, Object> p = range(s);
        String g = groupColumn(by);
        String grouping = by == ParGroupBy.NONE
                ? " SELECT NULL::uuid AS g, 1 AS whole,"
                : " SELECT " + g + " AS g, GROUPING(" + g + ") AS whole,";
        String sql = perLoan(s, p) + grouping + """
                 count(*) AS n, coalesce(sum(x.amount), 0) AS amount, coalesce(sum(x.fee), 0) AS fees,
                 round((percentile_cont(0.5) WITHIN GROUP (ORDER BY x.amount))::numeric) AS median
                  FROM x
                  JOIN lending_loans l ON l.id = x.loan_id""" + PRODUCT_JOIN
                + (by == ParGroupBy.NONE ? "" : " GROUP BY GROUPING SETS ((" + g + "), ())");
        jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> {
                    UUID key = rs.getInt("whole") == 1 ? TOTAL_KEY : rs.getObject("g", UUID.class);
                    long median = rs.getObject("median") == null ? -1 : rs.getLong("median");
                    out.put(key, new long[] {rs.getLong("n"), rs.getLong("amount"), rs.getLong("fees"), median});
                    return 0;
                })
                .list();
        out.putIfAbsent(TOTAL_KEY, new long[] {0, 0, 0, -1});
        return out;
    }

    /** The key of the whole range in {@link #disbursements}; no branch, product or user has it. */
    static final UUID TOTAL_KEY = new UUID(0, 0);

    // JUSTIFICATION-A3: review round 2 of #750: one per-loan table replaces the two per-transaction fee joins of the
    // summary and the detail, so both count a loan and its fee once.

    /**
     * The common table {@code x}: one row per loan disbursed in the range, with the date of its first disbursement in
     * the range ({@code first_on}), its principal disbursed in the range ({@code amount}, every disbursement that is not
     * reversed, summed) and its upfront fee ({@code fee}: the {@code fee_upfront} entries of that loan dated on
     * {@code first_on}, a reversed one excluded), so a fee belongs to exactly one disbursement, the loan's first. The
     * disbursement service posts the upfront fee with the disbursement's own value date ({@code LoanServicer}), so the
     * fee is found on that date. The index {@code lending_loan_transactions_one_disbursement} (V29) allows one
     * disbursement row per loan, reversed or not, so a loan has exactly one; if tranches were ever allowed, the fees of
     * later tranches and the page's per-day loan count would both need revisiting, and this does not claim otherwise.
     */
    private static String perLoan(Scope s, Map<String, Object> p) {
        return """
                WITH d AS (
                    SELECT t.loan_id, min(t.value_date) AS first_on, sum(t.amount_minor) AS amount
                      FROM lending_loan_transactions t
                      JOIN lending_loans l ON l.id = t.loan_id
                     WHERE t.txn_type = 'disbursement' AND t.value_date BETWEEN :from AND :to""" + NOT_REVERSED + s.loanFilter("l", p) + """

                     GROUP BY t.loan_id
                ), x AS (
                    SELECT d.loan_id, d.first_on, d.amount,
                           coalesce((SELECT sum(u.amount_minor) FROM lending_loan_transactions u
                                      WHERE u.loan_id = d.loan_id AND u.txn_type = 'fee_upfront'
                                        AND u.value_date = d.first_on
                                        AND NOT EXISTS (SELECT 1 FROM lending_loan_transactions r
                                                         WHERE r.reverses_txn_id = u.id)), 0) AS fee
                      FROM d
                )
                """;
    }

    /** Each loan disbursed in the range, by date then loan number, one at a time in blocks. */
    void disbursementLines(Scope s, Consumer<DisbursementLine> sink) {
        if (s.empty()) {
            return;
        }
        Map<String, Object> p = range(s);
        String sql = perLoan(s, p) + """
                 SELECT x.first_on AS value_date, l.loan_no, m.member_no, b.name AS branch, pr.name AS product,
                        l.officer_user_id, u.full_name AS officer, x.amount AS amount_minor, x.fee
                   FROM x
                   JOIN lending_loans l ON l.id = x.loan_id
                   JOIN lending_members m ON m.id = l.member_id
                   JOIN branches b ON b.id = l.branch_id""" + PRODUCT_JOIN + """

                   JOIN lending_loan_products pr ON pr.id = pv.product_id
                   LEFT JOIN users u ON u.id = l.officer_user_id
                  ORDER BY x.first_on, l.loan_no
                """;
        try (java.util.stream.Stream<DisbursementLine> rows = streaming
                .sql(sql)
                .params(p)
                .query((rs, n) -> new DisbursementLine(
                        rs.getDate("value_date").toLocalDate(),
                        rs.getString("loan_no"),
                        rs.getString("member_no"),
                        rs.getString("branch"),
                        rs.getString("product"),
                        rs.getString("officer") != null
                                ? rs.getString("officer")
                                : InsightsService.staffLabel(rs.getObject("officer_user_id", UUID.class)),
                        rs.getLong("amount_minor"),
                        rs.getLong("fee")))
                .stream()) {
            rows.forEach(sink);
        }
    }

    /**
     * Per responsible officer of the loans: the collection actions logged in the range (by the business date in
     * {@code zone}), and the promises to pay settled kept and broken in it; arrays of three.
     */
    Map<UUID, long[]> officerActivity(Scope s, ZoneId zone) {
        Map<UUID, long[]> out = new HashMap<>();
        if (s.empty()) {
            return out;
        }
        Map<String, Object> p = range(s);
        p.put("zone", zone.getId());
        String sql = """
                SELECT l.officer_user_id AS g,
                       count(*) FILTER (WHERE (a.performed_at AT TIME ZONE :zone)::date BETWEEN :from AND :to) AS actions,
                       count(*) FILTER (WHERE a.promise_status = 'kept' AND a.promise_settled_on BETWEEN :from AND :to) AS kept,
                       count(*) FILTER (WHERE a.promise_status = 'broken' AND a.promise_settled_on BETWEEN :from AND :to) AS broken
                  FROM lending_collection_actions a
                  JOIN lending_loans l ON l.id = a.loan_id
                 WHERE ((a.performed_at AT TIME ZONE :zone)::date BETWEEN :from AND :to
                        OR a.promise_settled_on BETWEEN :from AND :to)""" + s.loanFilter("l", p) + " GROUP BY 1";
        jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> out.put(
                        rs.getObject("g", UUID.class),
                        new long[] {rs.getLong("actions"), rs.getLong("kept"), rs.getLong("broken")}))
                .list();
        return out;
    }

    /** What the arrears list prints beside a loan's position, read for a block of loans on {@code date}. */
    record ArrearsExtras(
            String memberNo,
            String memberName,
            String phone,
            Long penaltiesOutstandingMinor,
            LocalDate lastRepaymentOn,
            String lastAction,
            LocalDate lastActionOn,
            LocalDate promisedDate,
            Long promisedAmountMinor) {

        /** For a position whose loan row cannot be read: every extra blank, so the line is still printed. */
        static final ArrearsExtras NONE = new ArrearsExtras(null, null, null, null, null, null, null, null, null);
    }

    /**
     * For each loan: the member, the penalties outstanding today, the last repayment on or before {@code date} (one
     * reversed by then excluded; a reversal after the date does not change what stood on it), the last collection action
     * logged on or before it (by the business date in
     * {@code zone}), and the earliest promise to pay made by then and not settled by then.
     */
    Map<UUID, ArrearsExtras> arrearsExtras(Collection<UUID> loanIds, LocalDate date, ZoneId zone) {
        Map<UUID, ArrearsExtras> out = new HashMap<>();
        if (loanIds.isEmpty()) {
            return out;
        }
        String sql = """
                SELECT l.id, m.member_no, m.full_name, m.phone_e164, l.penalties_outstanding_minor,
                       (SELECT max(t.value_date) FROM lending_loan_transactions t
                         WHERE t.loan_id = l.id AND t.txn_type = 'repayment' AND t.value_date <= :d
                           AND NOT EXISTS (SELECT 1 FROM lending_loan_transactions r
                                            WHERE r.reverses_txn_id = t.id AND r.value_date <= :d)
                       ) AS last_repayment_on,
                       la.action_type, la.performed_on, op.promised_date, op.promised_amount_minor
                  FROM lending_loans l
                  JOIN lending_members m ON m.id = l.member_id
                  LEFT JOIN LATERAL (SELECT a.action_type, (a.performed_at AT TIME ZONE :zone)::date AS performed_on
                                       FROM lending_collection_actions a
                                      WHERE a.loan_id = l.id AND (a.performed_at AT TIME ZONE :zone)::date <= :d
                                      ORDER BY a.performed_at DESC, a.id DESC LIMIT 1) la ON true
                  LEFT JOIN LATERAL (SELECT a.promised_date, a.promised_amount_minor FROM lending_collection_actions a
                                      WHERE a.loan_id = l.id AND a.action_type = 'promise_to_pay'
                                        AND (a.performed_at AT TIME ZONE :zone)::date <= :d
                                        AND (a.promise_settled_on IS NULL OR a.promise_settled_on > :d)
                                      ORDER BY a.promised_date, a.id LIMIT 1) op ON true
                 WHERE l.id IN (:ids)
                """;
        jdbc.sql(sql)
                .param("d", Date.valueOf(date))
                .param("zone", zone.getId())
                .param("ids", List.copyOf(loanIds))
                .query((rs, n) -> out.put(
                        rs.getObject("id", UUID.class),
                        new ArrearsExtras(
                                rs.getString("member_no"),
                                rs.getString("full_name"),
                                rs.getString("phone_e164"),
                                rs.getLong("penalties_outstanding_minor"),
                                rs.getObject("last_repayment_on", LocalDate.class),
                                rs.getString("action_type"),
                                rs.getObject("performed_on", LocalDate.class),
                                rs.getObject("promised_date", LocalDate.class),
                                rs.getObject("promised_amount_minor", Long.class))))
                .list();
        return out;
    }
}
