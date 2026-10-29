/**
 * Core: customer data onboarding (issue #125, ADR-027, spec {@code docs/specs/customer-data-onboarding.md}, SDD chapter 13
 * section 13.14). The one pipeline every business's data enters through. This first part (issue #127) is the run, the
 * recoverable unit of the commit, and the guards around it: the run states of spec section 3.11 enforced by the service
 * ({@code import_runs}, migration V114); the commit of a run's batches in order, one transaction each, under an advisory
 * lock on the business's import key, with at most one committing run per business; the platform kill switch
 * ({@code platform_import_settings}) and the per-run halt, checked before each batch and every 1,000 rows, after which the
 * run stays {@code committing} and resumes from its first uncommitted batch; and the trading tenant guard, which asks
 * every {@link com.rincoltech.bms.kernel.LiveRecordProbe} and refuses a commit into a business that already trades unless
 * one platform operator set the override with a reason and a different one approved it. Templates write their batches
 * through {@link com.rincoltech.bms.core.imports.BatchWriter}; upload, templates, review and the operator screens follow
 * in #128 to #141. Public API: {@link com.rincoltech.bms.core.imports.ImportRuns}.
 */
@ApplicationModule(
        id = "core.imports",
        displayName = "Core: Imports",
        allowedDependencies = {"kernel", "core.audit"})
package com.rincoltech.bms.core.imports;

import org.springframework.modulith.ApplicationModule;
