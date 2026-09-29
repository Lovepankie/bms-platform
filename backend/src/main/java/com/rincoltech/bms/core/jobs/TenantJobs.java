package com.rincoltech.bms.core.jobs;

import java.util.UUID;
import java.util.function.Consumer;

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
}
