package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.lending.loans.internal.CollectionsApi.Action;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.ArrearsLine;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.DueLine;
import com.rincoltech.bms.lending.loans.internal.CollectionsApi.PromiseLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The collections tables and reads (FR-CLN-01 to FR-CLN-05, chapter 14 section 14.4). A branch
 * filter of null means every branch; an empty one means no rows (chapter 6 section 6.3.3).
 */
@Repository
class CollectionsRepository {

    private final JdbcClient jdbc;

    CollectionsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** FR-CLN-01. Not a new loan version: a pending money approval on the loan stays current. */
    void reassign(UUID loanId, UUID officerUserId) {
        jdbc.sql("UPDATE lending_loans SET officer_user_id = ?, updated_at = now() WHERE id = ?")
                .params(officerUserId, loanId)
                .update();
    }

    void insertAction(
            UUID id,
            UUID loanId,
            String actionType,
            String note,
            LocalDate promisedDate,
            Long promisedAmountMinor,
            UUID performedBy) {
        jdbc.sql("""
                        INSERT INTO lending_collection_actions (id, tenant_id, loan_id, action_type, note, promised_date,
                            promised_amount_minor, promise_status, performed_by, performed_at)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, now())
                        """)
                .params(
                        id,
                        loanId,
                        actionType,
                        note,
                        promisedDate,
                        promisedAmountMinor,
                        promisedDate == null ? null : "pending",
                        performedBy)
                .update();
    }

    private static final String ACTION = """
            SELECT a.id, a.loan_id, a.action_type, a.note, a.promised_date, a.promised_amount_minor, a.promise_status,
                   a.performed_by, u.full_name AS performed_by_name, a.performed_at
              FROM lending_collection_actions a
              LEFT JOIN users u ON u.id = a.performed_by
            """;

    private static Action action(ResultSet rs, int n) throws SQLException {
        return new Action(
                rs.getObject("id", UUID.class),
                rs.getObject("loan_id", UUID.class),
                rs.getString("action_type"),
                rs.getString("note"),
                rs.getObject("promised_date", LocalDate.class),
                rs.getObject("promised_amount_minor", Long.class),
                rs.getString("promise_status"),
                rs.getObject("performed_by", UUID.class),
                rs.getString("performed_by_name"),
                instant(rs.getTimestamp("performed_at")));
    }

    Action action(UUID id) {
        return jdbc.sql(ACTION + " WHERE a.id = ?")
                .param(id)
                .query(CollectionsRepository::action)
                .single();
    }

    /** FR-CLN-04: the loan's timeline, newest first. */
    List<Action> actions(UUID loanId) {
        return jdbc.sql(ACTION + " WHERE a.loan_id = ? ORDER BY a.performed_at DESC, a.id DESC")
                .param(loanId)
                .query(CollectionsRepository::action)
                .list();
    }

    /** FR-CLN-02: every item of an active loan due in the range. */
    List<DueLine> dueList(List<UUID> branchIds, UUID officerId, LocalDate from, LocalDate to) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("""
                SELECT l.id, l.loan_no, l.branch_id, m.member_no, m.full_name, m.phone_e164, i.item_no, i.due_date,
                       i.principal_due_minor + i.interest_due_minor + i.fees_due_minor + i.penalties_due_minor AS due,
                       i.principal_paid_minor + i.interest_paid_minor + i.fees_paid_minor + i.penalties_paid_minor AS paid,
                       i.interest_waived_minor + i.fees_waived_minor + i.penalties_waived_minor AS waived,
                       i.written_off_minor, l.officer_user_id, u.full_name AS officer_name
                  FROM lending_schedule_items i
                  JOIN lending_loans l ON l.id = i.loan_id
                  JOIN lending_members m ON m.id = l.member_id
                  LEFT JOIN users u ON u.id = l.officer_user_id
                 WHERE l.status = 'active' AND i.due_date BETWEEN :from AND :to
                """);
        p.put("from", from);
        p.put("to", to);
        filters(sql, p, branchIds, officerId);
        sql.append(" ORDER BY u.full_name NULLS LAST, l.officer_user_id, i.due_date, l.loan_no, i.item_no");
        return jdbc.sql(sql.toString())
                .params(p)
                .query((rs, n) -> {
                    long due = rs.getLong("due");
                    long paid = rs.getLong("paid");
                    long waived = rs.getLong("waived");
                    return new DueLine(
                            rs.getObject("id", UUID.class),
                            rs.getString("loan_no"),
                            rs.getObject("branch_id", UUID.class),
                            rs.getString("member_no"),
                            rs.getString("full_name"),
                            rs.getString("phone_e164"),
                            rs.getInt("item_no"),
                            rs.getObject("due_date", LocalDate.class),
                            due,
                            paid,
                            waived,
                            due - paid - waived - rs.getLong("written_off_minor"),
                            rs.getObject("officer_user_id", UUID.class),
                            rs.getString("officer_name"));
                })
                .list();
    }

    /** FR-CLN-03: every active loan with DPD above 0, most overdue first. */
    List<ArrearsLine> arrears(List<UUID> branchIds, UUID officerId) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("""
                SELECT l.id, l.loan_no, l.branch_id, m.member_no, m.full_name, m.phone_e164, l.officer_user_id,
                       u.full_name AS officer_name, l.days_past_due, l.arrears_minor, l.penalties_outstanding_minor,
                       l.principal_outstanding_minor, l.last_repayment_on, la.action_type AS last_action_type,
                       la.performed_at AS last_action_at, op.promised_date, op.promised_amount_minor
                  FROM lending_loans l
                  JOIN lending_members m ON m.id = l.member_id
                  LEFT JOIN users u ON u.id = l.officer_user_id
                  LEFT JOIN LATERAL (SELECT a.action_type, a.performed_at FROM lending_collection_actions a
                                      WHERE a.loan_id = l.id ORDER BY a.performed_at DESC, a.id DESC LIMIT 1) la ON true
                  LEFT JOIN LATERAL (SELECT a.promised_date, a.promised_amount_minor FROM lending_collection_actions a
                                      WHERE a.loan_id = l.id AND a.promise_status = 'pending'
                                      ORDER BY a.promised_date, a.id LIMIT 1) op ON true
                 WHERE l.status = 'active' AND l.days_past_due > 0
                """);
        filters(sql, p, branchIds, officerId);
        sql.append(" ORDER BY l.days_past_due DESC, l.loan_no");
        return jdbc.sql(sql.toString())
                .params(p)
                .query((rs, n) -> new ArrearsLine(
                        rs.getObject("id", UUID.class),
                        rs.getString("loan_no"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("member_no"),
                        rs.getString("full_name"),
                        rs.getString("phone_e164"),
                        rs.getObject("officer_user_id", UUID.class),
                        rs.getString("officer_name"),
                        rs.getInt("days_past_due"),
                        ServicingApi.parBucket(rs.getInt("days_past_due")),
                        rs.getLong("arrears_minor"),
                        rs.getLong("penalties_outstanding_minor"),
                        rs.getLong("principal_outstanding_minor"),
                        rs.getObject("last_repayment_on", LocalDate.class),
                        rs.getString("last_action_type"),
                        instant(rs.getTimestamp("last_action_at")),
                        rs.getObject("promised_date", LocalDate.class),
                        rs.getObject("promised_amount_minor", Long.class)))
                .list();
    }

    /** Promises to pay with one status, earliest promised date first. */
    List<PromiseLine> promises(List<UUID> branchIds, UUID officerId, String status) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> p = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("""
                SELECT a.id, l.id AS loan_id, l.loan_no, l.branch_id, m.full_name, l.officer_user_id, a.promised_date,
                       a.promised_amount_minor, a.promise_status, a.performed_by, a.performed_at
                  FROM lending_collection_actions a
                  JOIN lending_loans l ON l.id = a.loan_id
                  JOIN lending_members m ON m.id = l.member_id
                 WHERE a.action_type = 'promise_to_pay' AND a.promise_status = :status
                """);
        p.put("status", status);
        filters(sql, p, branchIds, officerId);
        sql.append(" ORDER BY a.promised_date, l.loan_no, a.id");
        return jdbc.sql(sql.toString())
                .params(p)
                .query((rs, n) -> new PromiseLine(
                        rs.getObject("id", UUID.class),
                        rs.getObject("loan_id", UUID.class),
                        rs.getString("loan_no"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getObject("officer_user_id", UUID.class),
                        rs.getObject("promised_date", LocalDate.class),
                        rs.getLong("promised_amount_minor"),
                        rs.getString("promise_status"),
                        rs.getObject("performed_by", UUID.class),
                        instant(rs.getTimestamp("performed_at"))))
                .list();
    }

    /** A pending promise and what has been repaid on its loan from the day it was made to its date. */
    record OpenPromise(UUID id, LocalDate promisedDate, long promisedMinor, long paidMinor) {}

    /**
     * FR-CLN-05: every pending promise with its repayments (repayments and recoveries that are not
     * reversed, by value date) from the day it was made, in the tenant's zone, to the promised date.
     */
    List<OpenPromise> openPromises(String zone) {
        return jdbc.sql("""
                        SELECT a.id, a.promised_date, a.promised_amount_minor,
                               (SELECT coalesce(sum(t.amount_minor), 0) FROM lending_loan_transactions t
                                 WHERE t.loan_id = a.loan_id AND t.txn_type IN ('repayment', 'recovery')
                                   AND t.value_date BETWEEN (a.performed_at AT TIME ZONE ?)::date AND a.promised_date
                                   AND NOT EXISTS (SELECT 1 FROM lending_loan_transactions r
                                                    WHERE r.reverses_txn_id = t.id)) AS paid
                          FROM lending_collection_actions a
                         WHERE a.promise_status = 'pending'
                         ORDER BY a.promised_date, a.id
                        """)
                .param(zone)
                .query((rs, n) -> new OpenPromise(
                        rs.getObject("id", UUID.class),
                        rs.getObject("promised_date", LocalDate.class),
                        rs.getLong("promised_amount_minor"),
                        rs.getLong("paid")))
                .list();
    }

    void settlePromise(UUID id, String status) {
        jdbc.sql("UPDATE lending_collection_actions SET promise_status = ? WHERE id = ? AND promise_status = 'pending'")
                .params(status, id)
                .update();
    }

    private static void filters(StringBuilder sql, Map<String, Object> p, List<UUID> branchIds, UUID officerId) {
        if (branchIds != null) {
            sql.append(" AND l.branch_id IN (:branchIds)");
            p.put("branchIds", branchIds);
        }
        if (officerId != null) {
            sql.append(" AND l.officer_user_id = :officerId");
            p.put("officerId", officerId);
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
