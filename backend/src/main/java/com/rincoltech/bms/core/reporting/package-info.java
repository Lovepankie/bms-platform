/**
 * Core: report runs (extras slice D1, issue #214; ADR-038; {@code docs/specs/client-extras.md} section 6;
 * chapter 14 section 14.10). A run is a row and a job: {@code POST /report-runs} validates the request and
 * queues a {@code report_runs} row, the {@code core.reporting.report-runner} job claims it, runs the report's
 * queries in one read-only {@code REPEATABLE READ} transaction into a spool file on disk, commits, then renders
 * the output from the spool and stores it as a core document of type {@code report}; a nightly job deletes the
 * files after 30 days.
 *
 * <p>The core never names a vertical (chapter 5 section 5.4.3): a vertical registers its reports by exposing a
 * bean of {@link com.rincoltech.bms.core.reporting.ReportDefinition}, which declares the module that must be on,
 * the permission to run it, its parameters and its formats, and writes its rows to a
 * {@link com.rincoltech.bms.core.reporting.ReportOutput}. The framework owns the queue, the snapshot, the caps,
 * the files, the audit and the screens' API; a definition owns only its figures.
 */
@ApplicationModule(
        id = "core.reporting",
        displayName = "Core: Reporting",
        allowedDependencies = {"kernel", "core.audit", "core.documents", "core.jobs", "core.operations", "core.tenancy"
        })
package com.rincoltech.bms.core.reporting;

import org.springframework.modulith.ApplicationModule;
