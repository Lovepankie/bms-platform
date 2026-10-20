package com.rincoltech.bms.retail.sales.internal;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for write-offs of credit sales (ADR-043). Row-level security supplies the tenant predicate (ADR-003). */
@Repository
class WriteOffsRepository {

    private final JdbcClient jdbc;

    WriteOffsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A write-off as stored. */
    record Row(
            UUID id,
            UUID saleId,
            String saleNo,
            UUID branchId,
            LocalDate writtenOffOn,
            String currency,
            long unpaidMinor,
            long lossMinor,
            long costShareMinor,
            String recognition,
            String reason,
            UUID journalEntryId,
            UUID approvalId,
            UUID approvedBy,
            UUID createdBy,
            Instant createdAt) {}

    void insert(Row w) {
        jdbc.sql("""
                        INSERT INTO retail_sale_writeoffs (id, tenant_id, sale_id, branch_id, written_off_on, currency,
                            unpaid_minor, loss_minor, cost_share_minor, recognition, reason, journal_entry_id, approval_id,
                            approved_by, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        w.id(),
                        w.saleId(),
                        w.branchId(),
                        Date.valueOf(w.writtenOffOn()),
                        w.currency(),
                        w.unpaidMinor(),
                        w.lossMinor(),
                        w.costShareMinor(),
                        w.recognition(),
                        w.reason(),
                        w.journalEntryId(),
                        w.approvalId(),
                        w.approvedBy(),
                        w.createdBy())
                .update();
    }

    /** What a sale holds now, for the share of its cost that was never recognised: all in minor units. */
    record Standing(long paid, long returned, long returnedCost, long refunded) {}

    /**
     * Payments received less payments reversed, and the returns that stand (value, cost and money given back), as
     * {@code CreditRecognition} in the reports reads them.
     */
    Standing standing(UUID saleId) {
        return jdbc.sql("""
                        SELECT
                          (SELECT coalesce(sum(p.amount_minor), 0) FROM retail_sale_payments p WHERE p.sale_id = :sale)
                          - (SELECT coalesce(sum(al.amount_minor), 0)
                               FROM retail_account_payment_allocations al
                               JOIN retail_account_payments ap ON ap.id = al.receipt_id
                              WHERE al.sale_id = :sale AND ap.status = 'reversed') AS paid,
                          (SELECT coalesce(sum(r.total_minor), 0) FROM retail_sale_returns r
                            WHERE r.sale_id = :sale AND r.status = 'completed') AS returned,
                          (SELECT coalesce(sum(r.cost_minor), 0) FROM retail_sale_returns r
                            WHERE r.sale_id = :sale AND r.status = 'completed') AS returned_cost,
                          (SELECT coalesce(sum(r.refund_minor), 0) FROM retail_sale_returns r
                            WHERE r.sale_id = :sale AND r.status = 'completed') AS refunded
                        """)
                .param("sale", saleId)
                .query((rs, n) -> new Standing(
                        rs.getLong("paid"),
                        rs.getLong("returned"),
                        rs.getLong("returned_cost"),
                        rs.getLong("refunded")))
                .single();
    }

    /** An administrator who counts for the second-person rule, and the address to tell when a debt is written off alone. */
    record Admin(UUID userId, String email, boolean active) {}

    /**
     * The tenant administrators who count as active for the second-person rule (ADR-043): active ones, and ones whose role was
     * revoked or who were deactivated within the last {@code since} window, so an administrator cannot be removed first to write a
     * debt off alone. A locked-out administrator still counts. Row-level security supplies the tenant.
     */
    List<Admin> recentAdmins(Instant since) {
        return jdbc.sql("""
                        SELECT u.id, u.email, bool_or(u.status = 'active' AND a.revoked_at IS NULL) AS active
                          FROM users u JOIN user_role_assignments a ON a.user_id = u.id
                         WHERE u.kind = 'staff' AND a.role_key = 'tenant_admin'
                           AND (a.revoked_at IS NULL OR a.revoked_at > :since)
                           AND (u.status = 'active' OR (u.status = 'deactivated' AND u.updated_at > :since))
                         GROUP BY u.id, u.email
                        """)
                .param("since", Timestamp.from(since))
                .query((rs, n) ->
                        new Admin(rs.getObject("id", UUID.class), rs.getString("email"), rs.getBoolean("active")))
                .list();
    }

    Optional<Row> forSale(UUID saleId) {
        return jdbc.sql("""
                        SELECT w.id, w.sale_id, s.sale_no, w.branch_id, w.written_off_on, w.currency, w.unpaid_minor,
                               w.loss_minor, w.cost_share_minor, w.recognition, w.reason, w.journal_entry_id, w.approval_id, w.approved_by,
                               w.created_by, w.created_at
                          FROM retail_sale_writeoffs w JOIN retail_sales s ON s.id = w.sale_id
                         WHERE w.sale_id = ?
                        """)
                .param(saleId)
                .query((rs, n) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("sale_id", UUID.class),
                        rs.getString("sale_no"),
                        rs.getObject("branch_id", UUID.class),
                        rs.getDate("written_off_on").toLocalDate(),
                        rs.getString("currency"),
                        rs.getLong("unpaid_minor"),
                        rs.getLong("loss_minor"),
                        rs.getLong("cost_share_minor"),
                        rs.getString("recognition"),
                        rs.getString("reason"),
                        rs.getObject("journal_entry_id", UUID.class),
                        rs.getObject("approval_id", UUID.class),
                        rs.getObject("approved_by", UUID.class),
                        rs.getObject("created_by", UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }
}
