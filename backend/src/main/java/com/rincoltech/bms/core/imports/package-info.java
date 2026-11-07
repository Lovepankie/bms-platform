/**
 * Core: customer data onboarding (issue #125, ADR-027, spec {@code docs/specs/customer-data-onboarding.md}, SDD chapter 13
 * section 13.14). The one pipeline every business's data enters through. This first part (issue #127) is the run, the
 * recoverable unit of the commit, and the guards around it: the run states of spec section 3.11 enforced by the service
 * ({@code import_runs}, migration V127); the commit of a run's batches in order, one transaction each, under an advisory
 * lock on the business's import key, with at most one committing run per business; the platform kill switch
 * ({@code platform_import_settings}) and the per-run halt, checked before each batch and every 1,000 rows, after which the
 * run stays {@code committing} and resumes from its first uncommitted batch; and the trading tenant guard, which asks
 * every {@link com.rincoltech.bms.kernel.LiveRecordProbe} and refuses a commit into a business that already trades unless
 * one platform operator set the override with a reason and a different one approved it. Templates write their batches
 * through {@link com.rincoltech.bms.core.imports.BatchWriter}. The second part (issue #128) is the quarantined upload
 * ({@link com.rincoltech.bms.core.imports.ImportUploads}, migration V129): a file streamed once into a copy encrypted with
 * the run's data key, stored under the quarantine prefix of {@code core.documents}, within the size, row and column limits,
 * and parsed by a streaming reader for CSV, XLSX or JSON Lines into {@code import_rows} 1,000 rows at a time, with the
 * mapping's ignored columns dropped; and the daily cleanup of the files of abandoned uploads. The third part (issue #129,
 * migration V139) is the canonical template contract ({@link com.rincoltech.bms.core.imports.ImportTemplate}): entity sets,
 * typed fields, required flags, normalisers, row rules, an issue catalogue, a loader and a version, which a vertical
 * registers by declaring a bean, read through the registry {@link com.rincoltech.bms.core.imports.ImportTemplates} (so the
 * core never depends on a vertical), with a JSON Schema per template version; and a business's append-only mapping versions
 * ({@link com.rincoltech.bms.core.imports.ImportMappings}, {@code import_mappings}), validated against that schema and the
 * file's headers, recorded on each batch it parses. The fourth part (issue #130, migration V140) is the validation
 * ({@link com.rincoltech.bms.core.imports.ImportValidation}): every staged row of a run against its template and mapping
 * version (normalisers, types, required fields, ranges, references across the run's batches and to the business's own
 * records, duplicates, cross-field rules), each issue a stable code of the template's catalogue with a message that never
 * holds a personal value, stored in {@code import_issues} with the rows' typed values. The fifth (issue #131, migration
 * V141) is the review queue ({@link com.rincoltech.bms.core.imports.ImportReview}). The sixth (issue #132, migration V142)
 * is the preview ({@link com.rincoltech.bms.core.imports.ImportPreview}: rows by outcome, records by type and the predicted
 * balances of the template's {@link com.rincoltech.bms.core.imports.ImportForecast}) and the dry run
 * ({@link com.rincoltech.bms.core.imports.ImportRuns#dryRun}: the commit's batch code inside one outer transaction that is
 * rolled back, its {@link com.rincoltech.bms.core.imports.CommitReport} stored on the run). The seventh (issue #133,
 * migration V143) is the commit itself: the {@code import_commit} approval (a maker requests it, a different user approves
 * it, through {@code core.approvals}), the batches in the template's dependency order with a statement timeout of 10
 * minutes each, {@code import_refs} by {@link com.rincoltech.bms.core.imports.ImportRefs#sourceRef} with
 * {@code CHANGED_SINCE_IMPORT}, the opening journals through {@code core.ledger} against {@code opening_balance_equity}, the
 * policy decisions of {@link com.rincoltech.bms.core.imports.ImportTemplate.Policy}, the verified dump, and no message, task
 * or approval from an imported record. The eighth (issue #134, migration V144) is the reconciliation
 * ({@link com.rincoltech.bms.core.imports.ImportReconciliation}): made by the commit as it finishes, from the staged rows
 * measured by the template's forecast and the books (the ledger's port and the template's
 * {@link com.rincoltech.bms.core.imports.ImportBooks}), every difference itemised, stored append-only, and signed in the
 * app by the tenant admin, which moves the run to {@code signed_off}. The operator screens follow in #141. Public API:
 * {@link com.rincoltech.bms.core.imports.ImportRuns}, {@link com.rincoltech.bms.core.imports.ImportRefs},
 * {@link com.rincoltech.bms.core.imports.ImportUploads}, {@link com.rincoltech.bms.core.imports.ImportTemplates},
 * {@link com.rincoltech.bms.core.imports.ImportMappings}, {@link com.rincoltech.bms.core.imports.ImportValidation},
 * {@link com.rincoltech.bms.core.imports.ImportReview}, {@link com.rincoltech.bms.core.imports.ImportPreview},
 * {@link com.rincoltech.bms.core.imports.ImportReconciliation} and {@link com.rincoltech.bms.core.imports.CommitReport}, and
 * the SPI {@link com.rincoltech.bms.core.imports.ImportTemplate} with {@link com.rincoltech.bms.core.imports.BatchWriter},
 * {@link com.rincoltech.bms.core.imports.ImportForecast} and {@link com.rincoltech.bms.core.imports.ImportBooks}.
 */
@ApplicationModule(
        id = "core.imports",
        displayName = "Core: Imports",
        allowedDependencies = {
            "kernel",
            "core.audit",
            "core.documents",
            "core.jobs",
            "core.approvals",
            "core.ledger",
            "core.tenancy"
        })
package com.rincoltech.bms.core.imports;

import org.springframework.modulith.ApplicationModule;
