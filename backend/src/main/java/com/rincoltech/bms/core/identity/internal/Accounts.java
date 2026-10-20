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

    /**
     * A right password proved again by a signed-in user (the step-up of ADR-042): the consecutive failure count starts again, as at
     * sign-in, but a lock that is set is left alone (nothing here lifts a lock) and no sign-in is recorded.
     */
    void clearFailedLogins(UUID id);

    /**
     * The same atomic count-and-lock as {@link #recordFailure}, on the separate step-up counter (issue #309): wrong codes
     * entered by a signed-in session. Never touches the password counter.
     */
    Failures recordStepUpFailure(UUID id, Instant now, int maxFailures, Instant lockUntil);

    /** Whether step-up is locked for this account at {@code now}. Read under the row lock {@link #byIdForUpdate} took. */
    boolean stepUpLockedAt(UUID id, Instant now);

    /** A correct step-up code: the step-up count starts again. */
    void clearStepUpFailures(UUID id);

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
        return failureSql(table, "failed_login_count", "locked_until");
    }

    /** The same statement over another pair of columns: the step-up counter and lock. */
    static String failureSql(String table, String count, String lock) {
        return """
                UPDATE %1$s SET
                       %2$s = CASE WHEN %3$s <= ? THEN 1 ELSE %2$s + 1 END,
                       %3$s = CASE WHEN %3$s > ? THEN %3$s
                                           WHEN %3$s IS NULL AND %2$s + 1 >= ? THEN ?
                                           ELSE NULL END
                 WHERE id = ?
                RETURNING %2$s AS failed_login_count, %3$s AS locked_until
                """.formatted(table, count, lock);
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
