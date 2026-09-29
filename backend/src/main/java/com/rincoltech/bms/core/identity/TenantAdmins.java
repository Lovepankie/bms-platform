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

    record Invitation(UUID userId, String url, Instant expiresAt) {}
}
