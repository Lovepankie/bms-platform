package com.rincoltech.bms.core.jobs;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs work for every active tenant (from {@code app_list_active_tenants()}), each in its own
 * transaction with that tenant bound, so row-level security applies exactly as it does to a
 * request. One tenant's failure is logged and does not stop the others; the run then fails as a
 * whole so the scheduler records and retries it.
 */
public interface TenantJobs {

    /** @return the number of tenants the work ran for */
    int forEachActiveTenant(String jobName, Consumer<UUID> work);

    /**
     * As {@link #forEachActiveTenant}, for a vertical's job: only active tenants that have the
     * module switched on (FR-TEN-03). A tenant with the module disabled keeps its data and is
     * skipped.
     */
    int forEachActiveTenantWithModule(String jobName, String moduleKey, Consumer<UUID> work);

    /**
     * Runs work as one tenant that a job has just found (for example from a platform function that lists
     * the tenants with queued report runs), outside any transaction: the work opens its own, and each binds
     * {@code app.tenant_id} as a request's would. The id must come from the platform's own listing of active
     * tenants, never from a request. Not for use inside an open transaction.
     */
    <T> T callAs(UUID tenantId, Supplier<T> work);

    /**
     * For a one-off command run against one tenant (the retail import): binds the active tenant
     * with this slug that has the module switched on, then runs the work outside any transaction,
     * so the work opens its own transactions and each binds {@code app.tenant_id} as a request's
     * would. An unknown, suspended or closed tenant, or one without the module, is refused with
     * {@link IllegalArgumentException} before anything runs.
     */
    <T> T callAsTenant(String slug, String moduleKey, Supplier<T> work);

    /**
     * For work tied to one tenant by a row the platform stored when that tenant's own transaction caused it
     * (an outbox row's tenant, ADR-036): binds that tenant and runs the work in one short transaction of its
     * own, so row-level security applies as it does to a request. The id is the one the cause bound, read
     * from the stored row; it is never a request's input. An unknown, suspended or closed tenant is refused
     * with {@link InactiveTenantException} before anything runs.
     */
    <T> T callForTenant(UUID tenantId, Supplier<T> work);

    /** The tenant is unknown, suspended or closed: nothing ran. */
    final class InactiveTenantException extends RuntimeException {

        public InactiveTenantException() {
            super("The tenant is not active.", null, false, false);
        }
    }
}
