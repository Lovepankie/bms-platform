package com.rincoltech.bms.core.payments.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every SQL statement on {@code payment_gateway_accounts} (migration V56): the lender's own merchant account, one row per
 * tenant. Every method runs in the caller's transaction with the tenant bound, so row-level security is what keeps one
 * lender's row from another; no statement takes a tenant id from its caller. The credentials are only ever the sealed
 * boxes the caller hands in; this class never sees a plaintext key or secret.
 */
@Component
class AccountStore {

    static final String PROVIDER = "pesapal";

    /** One account row. {@link #toString} names no value: a box is binary, but it is not printed either. */
    record Account(
            UUID id,
            UUID tenantId,
            UUID merchantId,
            Instant updatedAt,
            byte[] consumerKeyBox,
            byte[] consumerSecretBox,
            String keyHmac,
            String keyHint,
            String dataKeyId,
            String status,
            String ipnId,
            Instant ipnRegisteredAt,
            Instant lastCheckedAt,
            String failureReason) {

        @Override
        public String toString() {
            return "Account[status=" + status + "]";
        }
    }

    private static final String COLUMNS =
            "id, tenant_id, merchant_id, updated_at, consumer_key_enc, consumer_secret_enc, consumer_key_hmac, key_hint,"
                    + " data_key_id, status, ipn_id, ipn_registered_at, last_checked_at, failure_reason";

    private final JdbcClient jdbc;

    AccountStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The bound tenant's account, if it has saved one. */
    Optional<Account> find() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment_gateway_accounts WHERE provider = ?")
                .param(PROVIDER)
                .query(AccountStore::map)
                .optional();
    }

    /** The row, locked for the rest of the transaction. */
    Optional<Account> lock() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment_gateway_accounts WHERE provider = ? FOR UPDATE")
                .param(PROVIDER)
                .query(AccountStore::map)
                .optional();
    }

    /**
     * Saves credentials: a new row, or the existing one with the old registration and result wiped (a changed credential
     * has none of them; it must be tested again).
     */
    void save(
            UUID id,
            UUID tenantId,
            UUID merchantId,
            Instant now,
            byte[] keyBox,
            byte[] secretBox,
            String keyHmac,
            String keyHint,
            String dataKeyId,
            UUID updatedBy) {
        jdbc.sql("""
                        INSERT INTO payment_gateway_accounts (id, tenant_id, created_at, updated_at, provider, consumer_key_enc,
                                                              consumer_secret_enc, consumer_key_hmac, key_hint, data_key_id, status, updated_by, merchant_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'saved', ?, ?)
                        ON CONFLICT (tenant_id, provider) DO UPDATE
                           SET updated_at = EXCLUDED.updated_at, consumer_key_enc = EXCLUDED.consumer_key_enc,
                               consumer_secret_enc = EXCLUDED.consumer_secret_enc, consumer_key_hmac = EXCLUDED.consumer_key_hmac,
                               key_hint = EXCLUDED.key_hint,
                               data_key_id = EXCLUDED.data_key_id, status = 'saved',
                               -- The same merchant account keeps the notification address its orders were made with (a new secret
                               -- for the same key); another merchant account has none yet.
                               ipn_id = CASE WHEN payment_gateway_accounts.merchant_id = EXCLUDED.merchant_id
                                             THEN payment_gateway_accounts.ipn_id END,
                               ipn_token_hash = CASE WHEN payment_gateway_accounts.merchant_id = EXCLUDED.merchant_id
                                                     THEN payment_gateway_accounts.ipn_token_hash END,
                               ipn_registered_at = CASE WHEN payment_gateway_accounts.merchant_id = EXCLUDED.merchant_id
                                                        THEN payment_gateway_accounts.ipn_registered_at END,
                               last_checked_at = NULL, failure_reason = NULL,
                               updated_by = EXCLUDED.updated_by, merchant_id = EXCLUDED.merchant_id
                        """)
                .params(
                        id,
                        tenantId,
                        IntentStore.ts(now),
                        IntentStore.ts(now),
                        PROVIDER,
                        keyBox,
                        secretBox,
                        keyHmac,
                        keyHint,
                        dataKeyId,
                        updatedBy,
                        merchantId)
                .update();
    }

    /** The test passed: the notification url is registered; only the digest of its token is kept. */
    void markVerified(UUID id, Instant now, String ipnId, String tokenHash, UUID updatedBy) {
        jdbc.sql("""
                        UPDATE payment_gateway_accounts
                           SET status = 'verified', ipn_id = ?, ipn_token_hash = ?, ipn_registered_at = ?, last_checked_at = ?,
                               failure_reason = NULL, updated_at = ?, updated_by = ?
                         WHERE id = ?
                        """)
                .params(ipnId, tokenHash, IntentStore.ts(now), IntentStore.ts(now), IntentStore.ts(now), updatedBy, id)
                .update();
    }

    /**
     * The credentials work again and the registration made earlier still stands: the account is verified with the same
     * notification address, so orders made before keep notifying a live one.
     */
    int markChecked(UUID id, Instant now, UUID updatedBy) {
        return jdbc.sql("""
                        UPDATE payment_gateway_accounts
                           SET status = 'verified', last_checked_at = ?, failure_reason = NULL, updated_at = ?, updated_by = ?
                         WHERE id = ? AND ipn_id IS NOT NULL AND ipn_token_hash IS NOT NULL
                        """)
                .params(IntentStore.ts(now), IntentStore.ts(now), updatedBy, id)
                .update();
    }

    /** The provider refused the credentials or the registration. An earlier registration is kept for orders in flight. */
    void markFailed(UUID id, Instant now, String reason, UUID updatedBy) {
        jdbc.sql("""
                        UPDATE payment_gateway_accounts
                           SET status = 'failed', failure_reason = ?, last_checked_at = ?, updated_at = ?, updated_by = ?
                         WHERE id = ?
                        """)
                .params(reason, IntentStore.ts(now), IntentStore.ts(now), updatedBy, id)
                .update();
    }

    void delete(UUID id) {
        jdbc.sql("DELETE FROM payment_gateway_accounts WHERE id = ?").param(id).update();
    }

    /** True when an intent still needs the credentials: an order to check, a payment to book or a review to make. */
    boolean hasWorkInFlight(UUID merchantId) {
        // Only an intent still scheduled for a check needs the provider credentials: a paid one is booked from what was
        // stored, and one that needs a person (needs_attention) is never re-checked, so neither blocks a change. Nor
        // does an
        // intent made under another merchant account: these credentials could not check it (it was retired when this
        // one
        // was connected).
        return jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM payment_intents WHERE next_check_at IS NOT NULL AND (merchant_id IS NULL OR merchant_id = ?))")
                .param(merchantId)
                .query(Boolean.class)
                .single();
    }

    /**
     * The merchant account has changed: what was made under another one can never be checked with these credentials, so it
     * is handed to a person (needs_attention, code merchant_changed) instead of waiting for ever, and stops being scheduled.
     * An order not yet made is failed, a booked payment's daily review is just stopped. Returns the number of intents touched.
     */
    int retireIntentsOfOtherMerchants(UUID merchantId, Instant now) {
        Object[] p = {IntentStore.ts(now), merchantId};
        int a = jdbc.sql("""
                        UPDATE payment_intents SET status = 'needs_attention', refusal_code = 'merchant_changed',
                               next_check_at = NULL, check_requested_at = NULL, updated_at = ?
                         WHERE merchant_id IS NOT NULL AND merchant_id <> ?
                           AND status IN ('pending', 'expired', 'failed', 'cancelled') AND next_check_at IS NOT NULL
                        """).params(p).update();
        int b = jdbc.sql("""
                        UPDATE payment_intents SET status = 'failed', failure_reason = 'merchant_changed',
                               next_check_at = NULL, creation_lease_until = NULL, updated_at = ?
                         WHERE merchant_id IS NOT NULL AND merchant_id <> ? AND status = 'created'
                        """).params(p).update();
        int c = jdbc.sql("""
                        UPDATE payment_intents SET next_check_at = NULL, check_requested_at = NULL, updated_at = ?
                         WHERE merchant_id IS NOT NULL AND merchant_id <> ? AND next_check_at IS NOT NULL
                           AND status IN ('booked', 'booked_unallocated', 'reversed_pending_review')
                        """).params(p).update();
        return a + b + c;
    }

    /** Rows sealed with a key other than the current one (the bound tenant's). */
    List<Account> sealedWithOtherKey(String currentKeyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payment_gateway_accounts WHERE data_key_id <> ? FOR UPDATE")
                .param(currentKeyId)
                .query(AccountStore::map)
                .list();
    }

    void reseal(UUID id, Instant now, byte[] keyBox, byte[] secretBox, String keyHmac, String dataKeyId) {
        jdbc.sql("""
                        UPDATE payment_gateway_accounts
                           SET consumer_key_enc = ?, consumer_secret_enc = ?, consumer_key_hmac = ?, data_key_id = ?,
                               updated_at = ?
                         WHERE id = ?
                        """)
                .params(keyBox, secretBox, keyHmac, dataKeyId, IntentStore.ts(now), id)
                .update();
    }

    /** True when another tenant is already connected to the merchant account with this consumer key (by its digest). */
    boolean keyInUseElsewhere(java.util.List<String> keyHmacs, UUID tenantId) {
        return jdbc.sql("SELECT app_payment_account_key_in_use(?, CAST(? AS text[]), ?)")
                .params(PROVIDER, keyHmacs.toArray(new String[0]), tenantId)
                .query(Boolean.class)
                .single();
    }

    private static Account map(ResultSet rs, int n) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                instant(rs, "updated_at"),
                rs.getBytes("consumer_key_enc"),
                rs.getBytes("consumer_secret_enc"),
                rs.getString("consumer_key_hmac"),
                rs.getString("key_hint"),
                rs.getString("data_key_id"),
                rs.getString("status"),
                rs.getString("ipn_id"),
                instant(rs, "ipn_registered_at"),
                instant(rs, "last_checked_at"),
                rs.getString("failure_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
