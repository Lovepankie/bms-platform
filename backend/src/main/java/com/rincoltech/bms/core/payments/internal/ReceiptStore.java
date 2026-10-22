package com.rincoltech.bms.core.payments.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every SQL statement on {@code unallocated_receipts} beyond the insert that {@link UnallocatedReceipts} makes (migration
 * V59 and V84). Every method runs in the caller's transaction with the tenant bound. A receipt is settled once:
 * {@link #settle} is conditional on the status {@code open} and on the version the caller read under the row lock, so of two
 * writers exactly one updates a row, and the database's guard trigger refuses any other change.
 */
@Component
class ReceiptStore {

    static final String COLUMNS = """
            r.id, r.created_at, r.updated_at, r.branch_id, r.intent_id, r.purpose, r.subject_type, r.subject_id,
            r.amount_minor, r.currency, r.value_date, r.confirmation_code, r.refusal_code, r.status, r.journal_entry_id,
            r.version, r.requested_by, r.decided_by, r.approval_id, r.decided_at, r.note, r.allocated_purpose,
            r.allocated_subject_type, r.allocated_subject_id, r.allocated_txn_id, r.cleared_entry_id,
            r.refund_method_key, r.refund_reference,
            i.merchant_reference, i.provider_reference, i.reported_method, i.paid_at
            """;

    private static final String FROM =
            " FROM unallocated_receipts r JOIN payment_intents i ON i.tenant_id = r.tenant_id AND i.id = r.intent_id";

    /** One receipt with the facts of its order that staff match against the provider's statement. */
    record Receipt(
            UUID id,
            Instant createdAt,
            Instant updatedAt,
            UUID branchId,
            UUID intentId,
            String purpose,
            String subjectType,
            UUID subjectId,
            long amountMinor,
            String currency,
            LocalDate valueDate,
            String confirmationCode,
            String refusalCode,
            String status,
            UUID journalEntryId,
            int version,
            UUID requestedBy,
            UUID decidedBy,
            UUID approvalId,
            Instant decidedAt,
            String note,
            String allocatedPurpose,
            String allocatedSubjectType,
            UUID allocatedSubjectId,
            UUID allocatedTxnId,
            UUID clearedEntryId,
            String refundMethodKey,
            String refundReference,
            String merchantReference,
            String orderTrackingId,
            String reportedMethod,
            Instant paidAt) {

        boolean open() {
            return "open".equals(status);
        }
    }

    /** A pending approval request on a receipt: what is being asked and where to decide it. */
    record Pending(UUID receiptId, UUID approvalId, String actionType) {}

    private final JdbcClient jdbc;

    ReceiptStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Receipt map(ResultSet rs, int n) throws SQLException {
        return new Receipt(
                rs.getObject("id", UUID.class),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getObject("branch_id", UUID.class),
                rs.getObject("intent_id", UUID.class),
                rs.getString("purpose"),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getObject("value_date", LocalDate.class),
                rs.getString("confirmation_code"),
                rs.getString("refusal_code"),
                rs.getString("status"),
                rs.getObject("journal_entry_id", UUID.class),
                rs.getInt("version"),
                rs.getObject("requested_by", UUID.class),
                rs.getObject("decided_by", UUID.class),
                rs.getObject("approval_id", UUID.class),
                instant(rs, "decided_at"),
                rs.getString("note"),
                rs.getString("allocated_purpose"),
                rs.getString("allocated_subject_type"),
                rs.getObject("allocated_subject_id", UUID.class),
                rs.getObject("allocated_txn_id", UUID.class),
                rs.getObject("cleared_entry_id", UUID.class),
                rs.getString("refund_method_key"),
                rs.getString("refund_reference"),
                rs.getString("merchant_reference"),
                rs.getString("provider_reference"),
                rs.getString("reported_method"),
                instant(rs, "paid_at"));
    }

    Optional<Receipt> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + " WHERE r.id = ?")
                .param(id)
                .query(ReceiptStore::map)
                .optional();
    }

    /** Takes the row lock on the receipt only (not the intent): a second writer waits here and then sees what the first left. */
    Optional<Receipt> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + " WHERE r.id = ? FOR UPDATE OF r")
                .param(id)
                .query(ReceiptStore::map)
                .optional();
    }

    /** The receipt of an order, if its money ever went to the queue: one per intent. */
    Optional<Receipt> findByIntent(UUID intentId) {
        return jdbc.sql("SELECT " + COLUMNS + FROM + " WHERE r.intent_id = ?")
                .param(intentId)
                .query(ReceiptStore::map)
                .optional();
    }

    Optional<Integer> version(UUID id) {
        return jdbc.sql("SELECT version FROM unallocated_receipts WHERE id = ?")
                .param(id)
                .query(Integer.class)
                .optional();
    }

    /**
     * One page, oldest first, of the receipts in the given branches (null: every branch the tenant has) with the status
     * (null: any); after the cursor's (created_at, id).
     */
    List<Receipt> page(String status, List<UUID> branchIds, Instant afterCreatedAt, UUID afterId, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + FROM + " WHERE true");
        if (status != null) {
            sql.append(" AND r.status = :status");
            params.put("status", status);
        }
        if (branchIds != null) {
            sql.append(" AND r.branch_id IN (:branches)");
            params.put("branches", branchIds);
        }
        if (afterCreatedAt != null) {
            sql.append(" AND (r.created_at, r.id) > (:afterAt, :afterId)");
            params.put("afterAt", Timestamp.from(afterCreatedAt));
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY r.created_at, r.id LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(ReceiptStore::map).list();
    }

    /** The requests still waiting on these receipts, by receipt. */
    List<Pending> pending(List<UUID> receiptIds, Instant now) {
        if (receiptIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT subject_id, id, action_type FROM approval_requests
                         WHERE status = 'pending' AND expires_at > :now AND subject_id IN (:ids)
                           AND action_type IN ('unallocated_allocation', 'unallocated_refund')
                         ORDER BY requested_at
                        """)
                .param("now", Timestamp.from(now))
                .param("ids", receiptIds)
                .query((rs, n) ->
                        new Pending(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)))
                .list();
    }

    /** What a settlement writes: the same columns for both endings, the other kind's left null. */
    record Settlement(
            String status,
            UUID requestedBy,
            UUID decidedBy,
            UUID approvalId,
            Instant decidedAt,
            String note,
            String allocatedPurpose,
            String allocatedSubjectType,
            UUID allocatedSubjectId,
            UUID allocatedTxnId,
            UUID clearedEntryId,
            String refundMethodKey,
            String refundReference) {}

    /**
     * Ends an open receipt, once: false when it is not open or its version is not the one the caller read under the lock.
     */
    boolean settle(UUID id, int version, Instant now, Settlement s) {
        return jdbc.sql("""
                        UPDATE unallocated_receipts
                           SET status = ?, version = version + 1, updated_at = ?, requested_by = ?, decided_by = ?,
                               approval_id = ?, decided_at = ?, note = ?, allocated_purpose = ?, allocated_subject_type = ?,
                               allocated_subject_id = ?, allocated_txn_id = ?, cleared_entry_id = ?, refund_method_key = ?,
                               refund_reference = ?
                         WHERE id = ? AND status = 'open' AND version = ?
                        """)
                        .params(
                                s.status(),
                                Timestamp.from(now),
                                s.requestedBy(),
                                s.decidedBy(),
                                s.approvalId(),
                                Timestamp.from(s.decidedAt()),
                                s.note(),
                                s.allocatedPurpose(),
                                s.allocatedSubjectType(),
                                s.allocatedSubjectId(),
                                s.allocatedTxnId(),
                                s.clearedEntryId(),
                                s.refundMethodKey(),
                                s.refundReference(),
                                id,
                                version)
                        .update()
                == 1;
    }
}
