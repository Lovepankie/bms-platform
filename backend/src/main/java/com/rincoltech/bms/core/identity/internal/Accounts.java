package com.rincoltech.bms.core.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The credential store {@link AuthFlow} signs people in against. Two implementations: tenant staff
 * ({@code users}, under the tenant policy) and platform operators ({@code platform_users}). Every
 * method runs inside the caller's transaction.
 */
interface Accounts {

    /** The principal kind and the {@code knd} token claim: {@code staff} or {@code platform}. */
    String kind();

    /** The tenant the accounts belong to, or {@code null} for platform operators. */
    UUID tenantId();

    /** Shown by authenticator apps next to the account. */
    String totpIssuer();

    Duration idleTtl();

    Duration absoluteTtl();

    Optional<Account> byLogin(String login);

    /**
     * Locks the account and its credentials for the rest of the transaction, so a concurrent
     * caller waits and then reads the committed factor state.
     */
    Optional<Account> byIdForUpdate(UUID id);

    /**
     * Counts one more consecutive failure in a single statement, so parallel failures are all
     * counted (FR-IAM-05). An expired lock starts a new count; the count reaching
     * {@code maxFailures} sets {@code lockUntil}; a failure while locked keeps the current lock.
     */
    Failures recordFailure(UUID id, Instant now, int maxFailures, Instant lockUntil);

    void recordSuccess(UUID id, Instant at);

    void setPendingTotp(UUID id, byte[] sealedSecret);

    void enableTotp(UUID id, byte[] sealedSecret, long usedStep);

    /**
     * Records a used TOTP step only when it is later than the last one; false when another
     * request already used this step or a later one (the code is then refused).
     */
    boolean advanceTotpStep(UUID id, long usedStep);

    void replaceRecoveryCodes(UUID id, List<String> codeHashes);

    /** Marks one unused code used; false when no unused code has this hash. */
    boolean useRecoveryCode(UUID id, String codeHash, Instant at);

    int unusedRecoveryCodes(UUID id);

    /** FR-IAM-06: whether this account must have a second factor before it may sign in. */
    boolean mfaRequired(UUID id);

    /** One audit row for a security event (FR-AUD-03); {@code userId} null when unknown. */
    void audit(String action, UUID userId, Map<String, Object> data);

    SessionStore sessions();

    /**
     * The {@link #recordFailure} statement for {@code table}; parameters: now, now, the maximum
     * failures, the lock end, the id. Every expression reads the row being updated, so a waiting
     * statement re-evaluates against the committed count.
     */
    static String failureSql(String table) {
        return """
                UPDATE %s SET
                       failed_login_count = CASE WHEN locked_until <= ? THEN 1 ELSE failed_login_count + 1 END,
                       locked_until = CASE WHEN locked_until > ? THEN locked_until
                                           WHEN locked_until IS NULL AND failed_login_count + 1 >= ? THEN ?
                                           ELSE NULL END
                 WHERE id = ?
                RETURNING failed_login_count, locked_until
                """.formatted(table);
    }

    static Failures failures(ResultSet rs, int n) throws SQLException {
        return new Failures(rs.getInt("failed_login_count"), SessionStore.instant(rs.getTimestamp("locked_until")));
    }

    /** The count and lock after {@link #recordFailure}. */
    record Failures(int consecutive, Instant lockedUntil) {}

    record Account(
            UUID id,
            String login,
            String status,
            String passwordHash,
            boolean mfaEnabled,
            byte[] totpSecret,
            byte[] totpPendingSecret,
            Long totpLastStep,
            int failedLoginCount,
            Instant lockedUntil) {

        boolean active() {
            return "active".equals(status);
        }

        boolean lockedAt(Instant now) {
            return lockedUntil != null && now.isBefore(lockedUntil);
        }
    }
}
