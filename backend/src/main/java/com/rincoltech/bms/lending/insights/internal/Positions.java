package com.rincoltech.bms.lending.insights.internal;

import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Where each loan stands on a business date (R-DPD of chapter 3, the definitions of chapter 14
 * section 14.3). Two readings of the same rule: {@link #live} from the paid columns of the
 * schedule items, for today; {@link #asOf} from the allocation rows dated by their transaction's
 * value date, for any past date (the nightly snapshot and its backfill). A test checks that both
 * agree on today.
 */
@Repository
class Positions {

    /** One active loan on the date. */
    record Position(
            UUID loanId,
            String loanNo,
            UUID memberId,
            UUID branchId,
            UUID officerUserId,
            UUID productId,
            long principalOutstandingMinor,
            long interestOutstandingMinor,
            long arrearsMinor,
            int daysPastDue) {

        String bucket() {
            return bucketOf(daysPastDue);
        }
    }

    static String bucketOf(int dpd) {
        if (dpd <= 0) {
            return "current";
        }
        if (dpd <= 30) {
            return "1_30";
        }
        if (dpd <= 60) {
            return "31_60";
        }
        if (dpd <= 90) {
            return "61_90";
        }
        return "over_90";
    }

    private final JdbcClient jdbc;

    Positions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Active loans in the scope today, from the schedule items as they stand. */
    List<Position> live(Scope s) {
        if (s.empty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("today", Date.valueOf(s.today()));
        String sql = """
                WITH items AS (
                    SELECT si.loan_id, si.due_date,
                           si.principal_due_minor - si.principal_paid_minor AS p_un,
                           si.interest_due_minor - si.interest_paid_minor - si.interest_waived_minor AS i_un,
                           si.fees_due_minor - si.fees_paid_minor - si.fees_waived_minor AS f_un
                      FROM lending_schedule_items si
                      JOIN lending_loans l ON l.id = si.loan_id
                     WHERE l.status = 'active'""" + s.loanFilter("l", p) + """
                )
                SELECT l.id, l.loan_no, l.member_id, l.branch_id, l.officer_user_id, pv.product_id,
                       coalesce(sum(i.p_un), 0) AS po, coalesce(sum(i.i_un), 0) AS io,
                       coalesce(sum(i.p_un + i.i_un + i.f_un) FILTER (WHERE i.due_date < :today), 0) AS arrears,
                       min(i.due_date) FILTER (WHERE i.due_date < :today AND i.p_un + i.i_un + i.f_un > 0) AS oldest
                  FROM items i
                  JOIN lending_loans l ON l.id = i.loan_id
                  JOIN lending_loan_product_versions pv ON pv.id = l.product_version_id
                 GROUP BY l.id, l.loan_no, l.member_id, l.branch_id, l.officer_user_id, pv.product_id
                """;
        return jdbc.sql(sql).params(p).query((rs, n) -> map(rs, s.today())).list();
    }

    /**
     * Every loan of the tenant that was active at the end of {@code date}: disbursed on or before
     * it, not written off by it, and owing something once the allocations dated on or before it
     * are applied. Reversal rows carry negative amounts and the reversal's own value date, so a
     * repayment reversed later still counts on the dates in between, as it did then.
     */
    List<Position> asOf(LocalDate date) {
        return jdbc.sql(AS_OF)
                .param("d", Date.valueOf(date))
                .query((rs, n) -> map(rs, date))
                .list();
    }

    /**
     * Writes the snapshot of {@code date} from {@link #AS_OF} in one statement, replacing any rows
     * the date already has (a rerun upserts, FR-ARR-02). Returns the number of loans written.
     */
    int writeSnapshot(LocalDate date) {
        Date d = Date.valueOf(date);
        jdbc.sql("DELETE FROM lending_loan_daily_snapshots WHERE business_date = ?")
                .param(d)
                .update();
        return jdbc.sql("""
                        INSERT INTO lending_loan_daily_snapshots (tenant_id, business_date, loan_id, branch_id,
                            officer_user_id, product_id, principal_outstanding_minor, interest_outstanding_minor,
                            arrears_minor, days_past_due, par_bucket)
                        SELECT current_setting('app.tenant_id')::uuid, :d, x.id, x.branch_id, x.officer_user_id,
                               x.product_id, x.po, x.io, x.arrears, z.dpd,
                               CASE WHEN z.dpd = 0 THEN 'current' WHEN z.dpd <= 30 THEN '1_30'
                                    WHEN z.dpd <= 60 THEN '31_60' WHEN z.dpd <= 90 THEN '61_90' ELSE 'over_90' END
                          FROM (""" + AS_OF + """
                        ) x CROSS JOIN LATERAL (SELECT coalesce(:d - x.oldest, 0) AS dpd) z
                        """).param("d", d).update();
    }

    /** Every loan of the tenant active at the end of {@code :d}, with its oldest unpaid due date. */
    static final String AS_OF = """
                WITH a AS (
                    SELECT al.schedule_item_id AS item_id,
                           sum(al.amount_minor) FILTER (WHERE al.component = 'principal' AND t.value_date <= :d) AS p,
                           sum(al.amount_minor) FILTER (WHERE al.component = 'interest' AND t.value_date <= :d) AS i,
                           sum(al.amount_minor) FILTER (WHERE al.component = 'interest_rebate' AND t.value_date <= :d) AS r_le,
                           sum(al.amount_minor) FILTER (WHERE al.component = 'interest_rebate') AS r_all,
                           sum(al.amount_minor) FILTER (WHERE al.component = 'fee' AND t.value_date <= :d) AS f
                      FROM lending_repayment_allocations al
                      JOIN lending_loan_transactions t ON t.id = al.transaction_id
                     WHERE al.schedule_item_id IS NOT NULL
                     GROUP BY al.schedule_item_id
                ), items AS (
                    SELECT si.loan_id, si.due_date,
                           si.principal_due_minor - coalesce(a.p, 0) AS p_un,
                           si.interest_due_minor - si.interest_waived_minor + coalesce(a.r_all, 0)
                               - coalesce(a.r_le, 0) - coalesce(a.i, 0) AS i_un,
                           si.fees_due_minor - si.fees_waived_minor - coalesce(a.f, 0) AS f_un
                      FROM lending_schedule_items si
                      JOIN lending_loans l ON l.id = si.loan_id
                      LEFT JOIN a ON a.item_id = si.id
                     WHERE l.disbursed_on <= :d AND (l.written_off_on IS NULL OR l.written_off_on > :d)
                )
                SELECT l.id, l.loan_no, l.member_id, l.branch_id, l.officer_user_id, pv.product_id,
                       sum(i.p_un) AS po, sum(i.i_un) AS io,
                       coalesce(sum(i.p_un + i.i_un + i.f_un) FILTER (WHERE i.due_date < :d), 0) AS arrears,
                       min(i.due_date) FILTER (WHERE i.due_date < :d AND i.p_un + i.i_un + i.f_un > 0) AS oldest
                  FROM items i
                  JOIN lending_loans l ON l.id = i.loan_id
                  JOIN lending_loan_product_versions pv ON pv.id = l.product_version_id
                 GROUP BY l.id, l.loan_no, l.member_id, l.branch_id, l.officer_user_id, pv.product_id
                HAVING sum(i.p_un + i.i_un + i.f_un) > 0
                """;

    /** The snapshot rows of one business date in the scope, as positions. */
    List<Position> snapshot(Scope s, LocalDate date) {
        if (s.empty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("d", Date.valueOf(date));
        String sql = """
                SELECT sn.loan_id AS id, l.loan_no, l.member_id, sn.branch_id, sn.officer_user_id, sn.product_id,
                       sn.principal_outstanding_minor AS po, sn.interest_outstanding_minor AS io,
                       sn.arrears_minor AS arrears, sn.days_past_due
                  FROM lending_loan_daily_snapshots sn
                  JOIN lending_loans l ON l.id = sn.loan_id
                 WHERE sn.business_date = :d""" + s.snapshotFilter("sn", p);
        return jdbc.sql(sql)
                .params(p)
                .query((rs, n) -> new Position(
                        rs.getObject("id", UUID.class),
                        rs.getString("loan_no"),
                        rs.getObject("member_id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("officer_user_id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getLong("po"),
                        rs.getLong("io"),
                        rs.getLong("arrears"),
                        rs.getInt("days_past_due")))
                .list();
    }

    /**
     * True when the snapshot job has passed {@code date}: it fills every date in order, so a row on
     * or after it means the date was written (with no rows when no loan was active).
     */
    boolean snapshotTaken(LocalDate date) {
        return Boolean.TRUE.equals(
                jdbc.sql("SELECT EXISTS (SELECT 1 FROM lending_loan_daily_snapshots WHERE business_date >= ?)")
                        .param(Date.valueOf(date))
                        .query(Boolean.class)
                        .single());
    }

    private static Position map(java.sql.ResultSet rs, LocalDate date) throws java.sql.SQLException {
        Date oldest = rs.getDate("oldest");
        int dpd = oldest == null ? 0 : (int) ChronoUnit.DAYS.between(oldest.toLocalDate(), date);
        return new Position(
                rs.getObject("id", UUID.class),
                rs.getString("loan_no"),
                rs.getObject("member_id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("officer_user_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getLong("po"),
                rs.getLong("io"),
                rs.getLong("arrears"),
                dpd);
    }
}
