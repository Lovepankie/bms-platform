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
     * For a one-off command run against one tenant (the retail import): binds the active tenant
     * with this slug that has the module switched on, then runs the work outside any transaction,
     * so the work opens its own transactions and each binds {@code app.tenant_id} as a request's
     * would. An unknown, suspended or closed tenant, or one without the module, is refused with
     * {@link IllegalArgumentException} before anything runs.
     */
    <T> T callAsTenant(String slug, String moduleKey, Supplier<T> work);
}
