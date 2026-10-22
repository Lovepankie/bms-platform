package com.rincoltech.bms.core.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What the platform console may do with a tenant's staff (FR-TEN-01, FR-IAM-12). Every method
 * runs in the caller's transaction, which must already be bound to that tenant; none takes a
 * tenant id.
 */
public interface TenantAdmins {

    /**
     * Creates the first tenant admin as an invited user with the {@code tenant_admin} role on
     * every branch, and returns the one-time link (valid 72 hours) for the platform operator to
     * pass on; the link is also sent through the notification port.
     */
    Invitation inviteFirstAdmin(String fullName, String email, String phone, UUID platformUserId);

    /**
     * Clears the second factor of one tenant admin and ends their sessions, for a tenant whose
     * only admin lost their device (FR-IAM-12). Refuses a user who is not a tenant admin.
     */
    void resetAdminMfa(UUID userId, UUID platformUserId);

    /**
     * Issues a one-time link (one hour, shown once to the platform operator, who hands it over) for a tenant admin who forgot
     * the password and cannot be reset by anyone in the tenant (FR-IAM-15, ADR-042). Refuses a user who is not an active
     * tenant admin. The admin is told by email that a reset was issued, without the link.
     */
    ResetLink resetAdminSignIn(UUID userId, UUID platformUserId);

    /**
     * Everything {@link #resetAdminSignIn} would refuse (not found, not a tenant admin, not active), read-only and without a
     * lock, so the platform operator's authenticator code is not spent on a call that cannot succeed.
     */
    void requireResettableAdmin(UUID userId);

    /**
     * Everything {@link #resetAdminMfa} would refuse (not found, not a tenant admin), read-only and without a lock, so the platform
     * operator's authenticator code is not spent on a call that cannot succeed.
     */
    void requireTenantAdmin(UUID userId);

    /**
     * Lifts the sign-in lock of one staff user of the tenant, any role, for a business locked out by a stranger's wrong
     * passwords (ADR-059). Audited in the tenant's log as the operator's act, with the reason; changes no credential. A platform operator is
     * not a tenant user, so this can never unlock an operator.
     */
    void unlockSignIn(UUID userId, UUID platformUserId, String reason);

    /**
     * Everything {@link #transferOwnership} would refuse about the new Owner (not a staff user of the tenant, not an active
     * administrator, already the Owner), read-only and without a lock, so the operator's authenticator code is not spent on a
     * call that cannot succeed (ADR-062).
     */
    void requireOwnerCandidate(UUID userId);

    /**
     * Makes an active administrator of the tenant its Owner, for a business whose Owner lost access or that has none (ADR-062,
     * FR-IAM-18). The previous Owner stays an administrator. Audited in the tenant's log as the operator's act, with the
     * reason. Returns the previous Owner, or null when the business had none. Never makes a non-administrator the Owner.
     */
    UUID transferOwnership(UUID userId, UUID platformUserId, String reason);

    /**
     * Everything {@link #reissueFirstAdminInvitation} would refuse, read-only, before the operator's code is spent (ADR-062).
     */
    void requireInvitedFirstAdmin(UUID userId);

    /**
     * A new one-time invitation link (72 hours) for a business's first administrator who never joined: the invited Owner, or an
     * invited administrator of a business with no Owner and no active administrator (ADR-062). The earlier link stops working;
     * the link is also sent through the notification port. Refuses anyone else with 422 {@code not_an_invited_owner}.
     */
    Invitation reissueFirstAdminInvitation(UUID userId, UUID platformUserId);

    /**
     * @param twoStepRequired whether the new admin must turn on two-step sign-in (a role that requires it, or the tenant's
     *     policy, ADR-046); the activation email says so only then (issue #396)
     */
    record Invitation(UUID userId, String url, Instant expiresAt, boolean twoStepRequired) {}

    /**
     * The Owner of the request's business (ADR-062) with the name and email a notice goes to, or empty while it has none. For
     * an act only the Owner may do outside identity (the plan and the subscription, ADR-073).
     */
    Optional<Owner> owner();

    record Owner(UUID userId, String fullName, String email) {}

    record ResetLink(String url, Instant expiresAt) {}
}
