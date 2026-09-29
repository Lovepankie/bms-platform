package com.rincoltech.bms.core.identity.internal;

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

    /** Locks the row for the rest of the transaction. */
    Optional<Account> byIdForUpdate(UUID id);

    void recordFailure(UUID id, int failedCount, Instant lockedUntil);

    void recordSuccess(UUID id, Instant at);

    void setPendingTotp(UUID id, byte[] sealedSecret);

    void enableTotp(UUID id, byte[] sealedSecret, long usedStep);

    void setTotpStep(UUID id, long usedStep);

    void replaceRecoveryCodes(UUID id, List<String> codeHashes);

    /** Marks one unused code used; false when no unused code has this hash. */
    boolean useRecoveryCode(UUID id, String codeHash, Instant at);

    int unusedRecoveryCodes(UUID id);

    /** FR-IAM-06: whether this account must have a second factor before it may sign in. */
    boolean mfaRequired(UUID id);

    /** One audit row for a security event (FR-AUD-03); {@code userId} null when unknown. */
    void audit(String action, UUID userId, Map<String, Object> data);

    SessionStore sessions();

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
