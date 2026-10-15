package com.rincoltech.bms.core.payments.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every SQL statement on {@code payment_intents} and {@code payment_callbacks} (migration V55), so the transitions are in
 * one place. Every method runs in the caller's transaction with the tenant bound, and every write that changes a status
 * is conditional on the status the caller read ({@code WHERE status = :from}): of two writers racing for one
 * transition exactly one updates a row and the other gets zero, and the database's own guard trigger refuses any
 * transition the state machine does not allow. Times are passed in from the kernel clock, never read from the database.
 */
@Component
class IntentStore {

    static final String COLUMNS = "id, tenant_id, created_at, updated_at, branch_id, purpose, subject_type, subject_id,"
            + " payer_phone_e164, amount_minor, currency, scope, merchant_reference, provider_reference, redirect_url, status,"
            + " failure_reason, refusal_code, initiated_by, expires_at, paid_amount_minor, paid_currency, confirmation_code,"
            + " reported_method, paid_at, verified_at, booked_subject_txn_id, booked_at, last_checked_at, next_check_at,"
            + " check_requested_at, check_failures, technical_failures, creation_lease_until, creation_attempts,"
            + " order_verified_at";

    /** The columns {@link #update} may set: the ones that move with the intent's life, never the request. */
    private static final Set<String> SETTABLE = Set.of(
            "provider_reference",
            "redirect_url",
            "failure_reason",
            "refusal_code",
            "paid_amount_minor",
            "paid_currency",
            "confirmation_code",
            "reported_method",
            "paid_at",
            "verified_at",
            "booked_subject_txn_id",
            "booked_at",
            "last_checked_at",
            "next_check_at",
            "check_requested_at",
            "check_failures",
            "technical_failures",
            "creation_lease_until",
            "order_verified_at");

    /** The statuses a notification may ask a re-check of; a final one or one a person must look at is left alone. */
    static final Set<String> CHECKABLE =
            Set.of("created", "pending", "expired", "failed", "cancelled", "paid", "booked", "booked_unallocated");

    /** One row, as the checker, the creator and the views see it. */
    record Intent(
            UUID id,
            UUID tenantId,
            Instant createdAt,
            UUID branchId,
            String purpose,
            String subjectType,
            UUID subjectId,
            String payerPhone,
            long amountMinor,
            String currency,
            String scope,
            String merchantReference,
            String providerReference,
            String redirectUrl,
            String status,
            String failureReason,
            String refusalCode,
            UUID initiatedBy,
            Instant expiresAt,
            Long paidAmountMinor,
            String paidCurrency,
            String confirmationCode,
            String reportedMethod,
            Instant paidAt,
            Instant verifiedAt,
            UUID bookedTxnId,
            Instant bookedAt,
            Instant lastCheckedAt,
            Instant nextCheckAt,
            Instant checkRequestedAt,
            int checkFailures,
            int technicalFailures,
            Instant creationLeaseUntil,
            int creationAttempts,
            Instant orderVerifiedAt) {

        /** A new intent as the first transaction writes it: created, nothing yet known from the provider. */
        static Intent created(
                UUID id,
                UUID tenantId,
                Instant now,
                UUID branchId,
                String purpose,
                String subjectType,
                UUID subjectId,
                String payerPhone,
                long amountMinor,
                String currency,
                String scope,
                String merchantReference,
                UUID initiatedBy,
                Instant expiresAt) {
            return new Intent(
                    id,
                    tenantId,
                    now,
                    branchId,
                    purpose,
                    subjectType,
                    subjectId,
                    payerPhone,
                    amountMinor,
                    currency,
                    scope,
                    merchantReference,
                    null,
                    null,
                    "created",
                    null,
                    null,
                    initiatedBy,
                    expiresAt,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    0,
                    null,
                    0,
                    null);
        }

        @Override
        public String toString() {
            return "Intent[id=" + id + ", status=" + status + "]";
        }
    }

    private final JdbcClient jdbc;

    IntentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Intent map(ResultSet rs, int n) throws SQLException {
        return new Intent(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                instant(rs, "created_at"),
                rs.getObject("branch_id", UUID.class),
                rs.getString("purpose"),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getString("payer_phone_e164"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getString("scope"),
                rs.getString("merchant_reference"),
                rs.getString("provider_reference"),
                rs.getString("redirect_url"),
                rs.getString("status"),
                rs.getString("failure_reason"),
                rs.getString("refusal_code"),
                rs.getObject("initiated_by", UUID.class),
                instant(rs, "expires_at"),
                (Long) rs.getObject("paid_amount_minor"),
                rs.getString("paid_currency"),
                rs.getString("confirmation_code"),
                rs.getString("reported_method"),
                instant(rs, "paid_at"),
                instant(rs, "verified_at"),
                rs.getObject("booked_subject_txn_id", UUID.class),
                instant(rs, "booked_at"),
                instant(rs, "last_checked_at"),
                instant(rs, "next_check_at"),
                instant(rs, "check_requested_at"),
                rs.getInt("check_failures"),
                rs.getInt("technical_failures"),
                instant(rs, "creation_lease_until"),
                rs.getInt("creation_attempts"),
                instant(rs, "order_verified_at"));
    }

    // ---- reads ----------------------------------------------------------------------------------

    Optional<Intent> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment_intents WHERE id = ?")
                .param(id)
                .query(IntentStore::map)
                .optional();
    }

    /** Takes the row lock: a second writer waits here, then sees the status the first one left. */
    Optional<Intent> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment_intents WHERE id = ? FOR UPDATE")
                .param(id)
                .query(IntentStore::map)
                .optional();
    }

    /** The intent already written for this initiator and key, if any (the database's unique key backs the protocol). */
    Optional<UUID> findByKey(UUID initiatedBy, String idempotencyKey) {
        return jdbc.sql("SELECT id FROM payment_intents WHERE initiated_by = ? AND idempotency_key = ?")
                .params(initiatedBy, idempotencyKey)
                .query(UUID.class)
                .optional();
    }

    /** The sum the day's ceiling counts: what was asked today (the tenant's day) and has not failed, expired or been cancelled. */
    long askedBetween(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT coalesce(sum(amount_minor), 0) FROM payment_intents
                         WHERE created_at >= ? AND created_at < ?
                           AND status IN ('created', 'pending', 'paid', 'booked', 'booked_unallocated', 'needs_attention')
                        """).params(ts(from), ts(to)).query(Long.class).single();
    }

    // ---- writes ---------------------------------------------------------------------------------

    void insert(Intent i, String idempotencyKey) {
        jdbc.sql("""
                        INSERT INTO payment_intents (id, tenant_id, created_at, updated_at, branch_id, purpose, subject_type,
                               subject_id, payer_phone_e164, amount_minor, currency, provider, scope, merchant_reference,
                               status, initiated_by, idempotency_key, expires_at, next_check_at)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pesapal', ?, ?,
                                'created', ?, ?, ?, ?)
                        """)
                .params(
                        i.id(),
                        ts(i.createdAt()),
                        ts(i.createdAt()),
                        i.branchId(),
                        i.purpose(),
                        i.subjectType(),
                        i.subjectId(),
                        i.payerPhone(),
                        i.amountMinor(),
                        i.currency(),
                        i.scope(),
                        i.merchantReference(),
                        i.initiatedBy(),
                        idempotencyKey,
                        ts(i.expiresAt()),
                        // The creation job looks at a created intent a minute later if the request itself did not
                        // finish it.
                        ts(i.createdAt().plusSeconds(60)))
                .update();
    }

    /**
     * Sets columns and, when {@code to} is given, moves the status, only if the row still has status {@code from}
     * ({@code from} null means any). Returns whether a row was updated: the loser of a race gets false and does nothing.
     */
    boolean update(UUID id, String from, String to, Instant now, Map<String, Object> columns) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE payment_intents SET updated_at = ?");
        params.add(ts(now));
        if (to != null) {
            sql.append(", status = ?");
            params.add(to);
        }
        for (Map.Entry<String, Object> c : columns.entrySet()) {
            if (!SETTABLE.contains(c.getKey())) {
                throw new IllegalArgumentException("not a settable column: " + c.getKey());
            }
            sql.append(", ").append(c.getKey()).append(" = ?");
            Object v = c.getValue();
            params.add(v instanceof Instant t ? ts(t) : v);
        }
        sql.append(" WHERE id = ?");
        params.add(id);
        if (from != null) {
            sql.append(" AND status = ?");
            params.add(from);
        }
        return jdbc.sql(sql.toString()).params(params).update() == 1;
    }

    /**
     * Takes the creation lease: one creator at a time per intent (spec 3.5). A worker that finds the row locked by
     * another skips it, a worker that finds a live lease skips it, and the winner holds the lease until it ends or lapses.
     */
    boolean claimCreation(UUID id, Instant now, Instant leaseUntil) {
        return jdbc.sql("""
                        UPDATE payment_intents SET creation_lease_until = ?, creation_attempts = creation_attempts + 1,
                               updated_at = ?
                         WHERE id = (SELECT id FROM payment_intents
                                      WHERE id = ? AND status = 'created'
                                        AND (creation_lease_until IS NULL OR creation_lease_until <= ?)
                                        FOR UPDATE SKIP LOCKED)
                        """).params(ts(leaseUntil), ts(now), id, ts(now)).update() == 1;
    }

    /** Takes up to {@code limit} due intents, oldest due first, and pushes their next check to the lease so no other worker takes them. */
    List<UUID> claimDue(Instant now, int limit, Instant leaseUntil) {
        return jdbc.sql("""
                        UPDATE payment_intents SET next_check_at = ?, updated_at = ?
                         WHERE id IN (SELECT id FROM payment_intents
                                       WHERE next_check_at IS NOT NULL AND next_check_at <= ?
                                       ORDER BY next_check_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
                        RETURNING id
                        """)
                .params(ts(leaseUntil), ts(now), ts(now), limit)
                .query(UUID.class)
                .list();
    }

    /** Moves the next check of a notified intent no later than {@code earliest}, and records the request. */
    boolean requestCheck(UUID id, Instant now, Instant earliest) {
        return jdbc.sql("""
                        UPDATE payment_intents
                           SET check_requested_at = ?, updated_at = ?,
                               next_check_at = CASE WHEN next_check_at IS NULL OR next_check_at > ? THEN ? ELSE next_check_at END
                         WHERE id = ?
                        """)
                        .params(ts(now), ts(now), ts(earliest), ts(earliest), id)
                        .update()
                == 1;
    }

    // ---- notifications --------------------------------------------------------------------------

    void insertCallback(
            UUID id,
            UUID intentId,
            Instant now,
            String kind,
            String notificationType,
            String trackingId,
            String merchantReference,
            String outcome) {
        jdbc.sql("""
                        INSERT INTO payment_callbacks (id, tenant_id, created_at, intent_id, provider, kind, notification_type,
                               order_tracking_id, merchant_reference, outcome)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, 'pesapal', ?, ?, ?, ?, ?)
                        """)
                .params(id, ts(now), intentId, kind, notificationType, trackingId, merchantReference, outcome)
                .update();
    }

    /** Finishes the notifications that were waiting for a check that began at {@code startedAt}; later ones wait for the next. */
    int finishCallbacks(UUID intentId, Instant startedAt, Instant now, String outcome, String resultJson) {
        return jdbc.sql("""
                        UPDATE payment_callbacks SET outcome = ?, status_query_result = CAST(? AS jsonb), checked_at = ?
                         WHERE intent_id = ? AND outcome = 'queued' AND created_at <= ?
                        """)
                .params(outcome, resultJson, ts(now), intentId, ts(startedAt))
                .update();
    }
}
