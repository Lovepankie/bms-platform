/**
 * Background jobs and scheduling on PostgreSQL with db-scheduler (ADR-008). Modules declare
 * db-scheduler task beans; {@link com.rincoltech.bms.core.jobs.TenantJobs} runs a unit of work
 * once per active tenant, one transaction per tenant, with the tenant bound (chapter 5 section
 * 5.4.4).
 */
@ApplicationModule(
        id = "core.jobs",
        displayName = "Core: Jobs",
        allowedDependencies = {"kernel"})
package com.rincoltech.bms.core.jobs;

import org.springframework.modulith.ApplicationModule;
