package com.rincoltech.bms.core.identity;

import java.time.Instant;
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
     * @param twoStepRequired whether the new admin must turn on two-step sign-in (a role that requires it, or the tenant's
     *     policy, ADR-046); the activation email says so only then (issue #396)
     */
    record Invitation(UUID userId, String url, Instant expiresAt, boolean twoStepRequired) {}

    record ResetLink(String url, Instant expiresAt) {}
}
