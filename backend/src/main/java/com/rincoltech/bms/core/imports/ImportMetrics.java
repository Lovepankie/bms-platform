package com.rincoltech.bms.core.imports;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The metrics of a run and the defect log of its hypercare (issue #140, spec section 8, ADR-027, migration V148).
 *
 * <p>Metrics are computed from what the run stored, never from logs, by the database function {@code import_run_metrics}:
 * the stage stamps of spec section 2, the time each pipeline step took (stored where it ended), the rows by outcome per batch
 * and per template, the issues by code and severity, the resolutions by kind, the questions to the customer and the days
 * they took to answer, the mapping versions, the rehearsals against the cutover, and the defects. Every figure comes from a
 * column the purge of V147 keeps, so a purged run has the metrics it had before.
 *
 * <p>A defect is logged against an imported run (committed, signed off or purged) during hypercare, with a description that
 * names nobody, the issue code it should have had and, when it closes, the fix's reference. Both are audited. The
 * description is refused when it names a personal value of the run's staged rows, an email address, an ID number or a
 * phone number; it becomes its hash when the run is purged, and a defect logged against a purged run is stored hashed.
 *
 * <p>Every method but the platform's works for the business bound to the thread, in its own transaction; a run or defect of
 * another business is 404. The platform report reads across businesses, unbound, through the definer function
 * {@code platform_import_runs_report}, which names each business by its slug only.
 */
public interface ImportMetrics {

    /** The metrics of a run of the business; 404 for none. */
    Metrics metrics(UUID runId);

    /** The defects logged against a run, oldest first; 404 for no such run. */
    List<Defect> defects(UUID runId);

    /**
     * Logs a defect against a run: 409 {@code run_not_imported} unless it is committed, signed off or purged; 422
     * {@code expected_code_unknown} for a code outside the issue-code catalogue of the run's template (none given: a new
     * code is needed, proposed in the description); 422 {@code description_names_personal_data} for a description that
     * names a personal value; audited {@code core.import_run.defect_logged} with the code and never the description.
     */
    Defect logDefect(UUID runId, NewDefect defect, UUID by);

    /**
     * Closes a defect with its fix's reference, once: 409 {@code version_conflict} for another version, 409
     * {@code defect_closed} for one already closed; audited {@code core.import_run.defect_closed} with the reference.
     */
    Defect closeDefect(UUID defectId, int version, String fixRef, UUID by);

    /** The platform report: every business's runs created since the moment (all when null), newest first, at most limit. */
    List<PlatformRun> platformReport(Instant since, int limit);

    /** The same as CSV, at most 500 runs, each business by its slug only; recorded in the platform audit log. */
    String platformReportCsv(Instant since, UUID operatorId);

    /** A defect as the operator writes it; a null or blank code means a new code is needed. */
    record NewDefect(String description, String expectedCode) {}

    @Schema(name = "ImportDefect", description = "A defect found after the cutover, logged against the run")
    record Defect(
            UUID id,
            UUID runId,
            Instant loggedAt,
            UUID loggedBy,

            @Schema(description = "What went wrong, naming nobody; its hash once the run is purged (open or closed)")
            String description,

            @Schema(description = "The catalogue code the row should have had; null when a new code is needed")
            String expectedCode,

            @Schema(description = "True when no catalogue code fits; the code proposed is in the description")
            boolean newCodeNeeded,

            @Schema(description = "open or closed") String status,

            @Schema(description = "The fix: #123, a commit hash or a GitHub link; null while open")
            String fixRef,

            UUID closedBy,
            Instant closedAt,
            int version) {}

    @Schema(
            name = "ImportRunMetrics",
            description = "The metrics of an import run, from its stored data (spec section 8)")
    record Metrics(
            UUID runId,
            String status,

            @Schema(description = "framework, import_retail or retail_import_refs")
            String origin,

            boolean fabricated,
            Stamps stamps,

            @Schema(description = "The ten stages of spec section 2, in order")
            List<Stage> stages,

            @Schema(description = "The time each pipeline step took, as stored where it ended")
            List<Step> steps,

            @Schema(description = "The newest committed rehearsal's timings, by step")
            List<Timing> rehearsalTimings,

            List<BatchRows> batches,
            List<TemplateRows> templates,
            List<IssueCount> issues,
            List<ResolutionCount> resolutions,
            Questions questions,

            @Schema(description = "The mapping versions the run's batches were read with")
            List<MappingVersion> mappings,

            @Schema(description = "Versions of those mappings saved while the run was open")
            long mappingVersionsMade,

            List<Rehearsal> rehearsals,

            @Schema(
                    description =
                            "Per source, each count of the newest committed rehearsal that differs from the cutover")
            List<Difference> rehearsalDifferences,

            Defects defects) {}

    @Schema(name = "ImportRunStamps", description = "The run's stamps; null for a stage not reached")
    record Stamps(
            Instant createdAt,
            Instant uploadedAt,
            Instant inReviewAt,
            Instant validatedAt,
            Instant readyAt,
            Instant dryRunAt,
            Instant committingAt,
            Instant committedAt,
            Instant reconciledAt,
            Instant signedOffAt,
            Instant purgedAt,
            Instant cancelledAt) {}

    @Schema(name = "ImportRunStage", description = "One stage of spec section 2")
    record Stage(
            int number,

            @Schema(
                    description = "discovery, field_mapping, cleaning_and_review, rehearsal, dry_run, cutover,"
                            + " reconciliation, sign_off, source_deletion or hypercare")
            String key,

            Instant startedAt,
            Instant endedAt,

            @Schema(description = "Null unless both ends are recorded")
            Long seconds) {}

    @Schema(name = "ImportRunStep", description = "One pipeline step")
    record Step(
            @Schema(description = "upload, parse, validate, dry_run or commit")
            String step,

            @Schema(description = "The batch, or null for a step of the whole run")
            Integer batchSeq,

            long millis) {}

    @Schema(name = "ImportRunTiming")
    record Timing(String step, long millis) {}

    @Schema(
            name = "ImportBatchRows",
            description = "Rows by outcome of one batch; the commit's counts null until committed")
    record BatchRows(
            int seq,
            String status,
            String templateKey,
            Integer templateVersion,
            String mappingSource,
            Long rows,

            @Schema(description = "Rows left out in the review")
            long excluded,

            Long read,
            Long written,
            Long existing,
            Long skipped) {}

    @Schema(
            name = "ImportTemplateRows",
            description = "Rows by outcome of a template's batches, not failed or cancelled")
    record TemplateRows(
            String templateKey,
            int templateVersion,
            long batches,
            Long rows,
            Long excluded,
            Long read,
            Long written,
            Long existing,
            Long skipped) {}

    @Schema(name = "ImportIssueCount")
    record IssueCount(String code, String severity, long total, long open) {}

    @Schema(name = "ImportResolutionCount")
    record ResolutionCount(
            @Schema(
                    description =
                            "edit_value, exclude_row, accept_suggestion, link_row, link_member, override, map_value"
                                    + " or ask_customer")
            String kind,

            long count) {}

    @Schema(name = "ImportQuestions", description = "Questions to the customer and the days they took to answer")
    record Questions(long asked, long answered, long open, BigDecimal medianDays, BigDecimal maxDays) {}

    @Schema(name = "ImportMappingVersion")
    record MappingVersion(String name, int version, String templateKey, int templateVersion) {}

    @Schema(name = "ImportRehearsalSummary")
    record Rehearsal(Instant at, String outcome, Boolean matchesDryRun) {}

    @Schema(name = "ImportRehearsalDifference")
    record Difference(
            String source,

            @Schema(description = "read, written, existing or skipped")
            String measure,

            Long rehearsal,
            Long cutover) {}

    @Schema(name = "ImportDefectCounts")
    record Defects(
            long open,
            long closed,

            @Schema(description = "Defects that need a code the catalogue does not have yet")
            long newCodesNeeded,

            @Schema(description = "By catalogue code") List<CodeCount> codes) {}

    @Schema(name = "ImportDefectCodeCount")
    record CodeCount(String code, long count) {}

    @Schema(name = "PlatformImportRun", description = "One run on the platform report; the business by its slug only")
    record PlatformRun(String tenantSlug, Metrics metrics) {}
}
