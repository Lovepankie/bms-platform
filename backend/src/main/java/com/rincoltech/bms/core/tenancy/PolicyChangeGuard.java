package com.rincoltech.bms.core.tenancy;

/**
 * What relaxing the tenant's two-step sign-in policy needs from identity (issue #348, ADR-046): the acting administrator proves they
 * are still themselves, and the administrators are told. Implemented by core.identity (which depends on tenancy, not the reverse).
 */
public interface PolicyChangeGuard {

    /**
     * The step-up of the admin reset of a sign-in: a fresh authenticator code when the signed-in user has one enrolled, otherwise their
     * own current password. 422 {@code totp_required} or {@code password_required} when it is missing; a wrong one is refused and
     * counted toward the lock. Must be called before the transaction of the change.
     */
    void requireProof(String totpCode, String password);

    /** Emails every administrator who may change the settings, the actor included, what was relaxed, by whom and when. */
    void notifyRelaxed(String change);

    /**
     * A setting only the Owner of the business changes (ADR-062), such as the go-live date (ADR-071): 403 {@code owner_only}
     * for anyone else, and for everyone while the business has no Owner (then only the platform operator changes it). In the
     * caller's transaction.
     */
    void requireOwner(String what);
}
