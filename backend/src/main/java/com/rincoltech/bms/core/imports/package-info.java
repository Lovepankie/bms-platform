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
 * file's headers, recorded on each batch it parses. Validation, review and the operator screens follow in #130 to #141.
 * Public API: {@link com.rincoltech.bms.core.imports.ImportRuns}, {@link com.rincoltech.bms.core.imports.ImportUploads},
 * {@link com.rincoltech.bms.core.imports.ImportTemplates}, {@link com.rincoltech.bms.core.imports.ImportMappings}, and the
 * SPI {@link com.rincoltech.bms.core.imports.ImportTemplate} with {@link com.rincoltech.bms.core.imports.BatchWriter}.
 */
@ApplicationModule(
        id = "core.imports",
        displayName = "Core: Imports",
        allowedDependencies = {"kernel", "core.audit", "core.documents", "core.jobs"})
package com.rincoltech.bms.core.imports;

import org.springframework.modulith.ApplicationModule;
