package com.rincoltech.bms.core.platform;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant creation for other platform modules (FR-TEN-01, ADR-024 decision 2): the same path as
 * {@code POST /api/v1/platform/tenants}, so onboarding never duplicates it. Creation runs in one
 * transaction bound to the new tenant; {@link Steps} lets the caller add its own work to that
 * transaction, before the tenant exists (to lock and check its own record) and after (to record
 * the result and queue messages), so everything commits or rolls back together.
 */
public interface TenantProvisioning {

    /**
     * Creates the tenant unless {@link Steps#before()} returns false, in which case nothing is
     * created and the result is empty. Validation failures throw the same problems as the
     * platform API ({@code validation_failed}, {@code duplicate_slug}, {@code invalid_tenant}).
     */
    Optional<Created> create(NewTenant tenant, Steps steps);

    /** FR-TEN-02: the slug is valid and no tenant has it yet. */
    boolean slugAvailable(String slug);

    /**
     * @param currency null for UGX
     * @param timezone null for Africa/Kampala
     * @param subscriptionStatus {@code trial} (the default of a new tenant) or {@code active}
     */
    record NewTenant(
            String name,
            String slug,
            String planCode,
            String currency,
            String timezone,
            List<String> modules,
            String headOfficeCode,
            String headOfficeName,
            String adminFullName,
            String adminEmail,
            String adminPhone,
            String subscriptionStatus) {}

    /** The new tenant and the first admin's one-time invitation link (72 hours, FR-IAM-01). */
    record Created(
            UUID tenantId,
            String slug,
            UUID headOfficeBranchId,
            UUID adminUserId,
            String invitationUrl,
            Instant invitationExpiresAt) {}

    /** Work of the caller inside the creating transaction. */
    interface Steps {

        /** Runs first; false stops the creation and rolls nothing forward. */
        boolean before();

        /** Runs after the tenant, its admin and the audit rows are written. */
        void after(Created created);
    }
}
