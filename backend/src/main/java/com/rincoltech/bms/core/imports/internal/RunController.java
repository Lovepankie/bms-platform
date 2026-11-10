package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportMappings.Mapping;
import com.rincoltech.bms.core.imports.ImportPreview;
import com.rincoltech.bms.core.imports.ImportPurges;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportRunView;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Batch;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/imports} (chapter 7 section 7.11.9, issue #141): the import screens of a business, run by the operator
 * signed in to it, over the services of #127 to #139. Every route needs {@code core.imports.manage}, but the raw row, which
 * needs {@code core.imports.view_raw} and is audited per row opened. Every route works on the business of the host only
 * (row-level security): a run, batch or issue of another business is 404. No answer but the raw row holds a value of a row.
 * The stop, resume, start, trading override, dump record and kill switch are the operator portal's
 * ({@code PlatformImportRunsController}).
 */
@RestController
@RequestMapping(path = "/api/v1/imports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "imports")
class RunController {

    private final ImportRuns runs;
    private final ImportUploads uploads;
    private final ImportMappings mappings;
    private final ImportValidation validation;
    private final ImportPreview preview;
    private final ImportPurges purges;
    private final ImportReview review;

    RunController(
            ImportRuns runs,
            ImportUploads uploads,
            ImportMappings mappings,
            ImportValidation validation,
            ImportPreview preview,
            ImportPurges purges,
            ImportReview review) {
        this.runs = runs;
        this.uploads = uploads;
        this.mappings = mappings;
        this.validation = validation;
        this.preview = preview;
        this.purges = purges;
        this.review = review;
    }

    @Schema(name = "ImportRunList")
    record RunList(List<ImportRunView> items) {}

    @Schema(name = "ImportFile", description = "One uploaded file of a run (a batch), without any value of a row")
    record FileResponse(
            UUID id,
            int seq,

            @Schema(description = "uploaded, parsed, failed, committed or cancelled")
            String status,

            String fileName,
            String fileType,
            long sizeBytes,
            Integer rowCount,
            Integer columnCount,
            List<String> warnings,

            @Schema(description = "Why the file could not be read, when failed: a code of the catalogue")
            String failureCode,

            String failureReason,
            UUID mappingId,
            String mappingSource,
            String templateKey,
            Instant uploadedAt) {

        static FileResponse of(Batch b) {
            return new FileResponse(
                    b.id(),
                    b.seq(),
                    b.status(),
                    b.fileName(),
                    b.fileType(),
                    b.sizeBytes(),
                    b.rowCount(),
                    b.columnCount(),
                    b.warnings(),
                    b.failureCode(),
                    b.failureReason(),
                    b.mappingId(),
                    b.mappingSource(),
                    b.templateKey(),
                    b.uploadedAt());
        }
    }

    @Schema(name = "ImportFileList")
    record FileList(List<FileResponse> items) {}

    @Schema(name = "ImportMappingSource", description = "One file or tab a mapping describes")
    record SourceResponse(String key, String file, String entitySet) {}

    @Schema(name = "ImportMappingChoice", description = "The newest version of one of the business's mappings")
    record MappingResponse(
            UUID id, String name, int version, String templateKey, int templateVersion, List<SourceResponse> sources) {

        static MappingResponse of(Mapping m) {
            List<SourceResponse> sources = new ArrayList<>();
            if (m.document().get("sources") instanceof Map<?, ?> all) {
                all.forEach((key, value) -> {
                    Map<?, ?> source = value instanceof Map<?, ?> v ? v : Map.of();
                    sources.add(new SourceResponse(
                            String.valueOf(key), text(source.get("file")), text(source.get("entity_set"))));
                });
            }
            return new MappingResponse(m.id(), m.name(), m.version(), m.templateKey(), m.templateVersion(), sources);
        }

        private static String text(Object value) {
            return value == null ? null : value.toString();
        }
    }

    @Schema(name = "ImportMappingChoiceList")
    record MappingList(List<MappingResponse> items) {}

    @Schema(name = "ImportCodeCount")
    record CodeCount(String code, int count) {}

    @Schema(name = "ImportFileCheck", description = "What the check found in one file")
    record FileCheck(UUID batchId, int seq, int rows, int blocking, int warning, int info, List<CodeCount> codes) {}

    @Schema(name = "ImportCheckResult", description = "The validation of a run: counts by severity and by code")
    record CheckResult(
            String templateKey, int templateVersion, int blocking, int warning, int info, List<FileCheck> files) {

        static CheckResult of(ImportValidation.RunReport r) {
            return new CheckResult(
                    r.templateKey(),
                    r.templateVersion(),
                    r.count("blocking"),
                    r.count("warning"),
                    r.count("info"),
                    r.batches().stream()
                            .map(b -> new FileCheck(
                                    b.batchId(),
                                    b.seq(),
                                    b.rows(),
                                    b.bySeverity().getOrDefault("blocking", 0),
                                    b.bySeverity().getOrDefault("warning", 0),
                                    b.bySeverity().getOrDefault("info", 0),
                                    b.byCode().entrySet().stream()
                                            .sorted(Map.Entry.comparingByKey())
                                            .map(e -> new CodeCount(e.getKey(), e.getValue()))
                                            .toList()))
                            .toList());
        }
    }

    @Schema(name = "ImportStockFigure", description = "Stock of one branch: quantity and value at cost in minor units")
    record StockFigure(String branch, BigDecimal qty, long valueMinor) {}

    @Schema(name = "ImportAmountFigure", description = "An amount of one branch in minor units")
    record AmountFigure(String branch, long amountMinor) {}

    @Schema(name = "ImportArrearsFigure")
    record ArrearsFigure(String branch, String bucket, int loans, long principalMinor) {}

    @Schema(name = "ImportJournalFigure", description = "One opening journal: its debit and credit in minor units")
    record JournalFigure(String branch, String kind, long debitMinor, long creditMinor) {}

    @Schema(name = "ImportBalances", description = "The balances the import gives, per branch")
    record Balances(
            List<StockFigure> stock,
            List<AmountFigure> receivables,
            List<AmountFigure> loanPrincipal,
            List<ArrearsFigure> arrears,
            List<JournalFigure> journals) {

        static Balances of(ImportForecast.Balances b) {
            return new Balances(
                    b.stock().stream()
                            .map(s -> new StockFigure(s.branch(), s.qty(), s.valueMinor()))
                            .toList(),
                    amounts(b.receivables()),
                    amounts(b.loanPrincipal()),
                    b.arrears().stream()
                            .map(a -> new ArrearsFigure(a.branch(), a.bucket(), a.loans(), a.principalMinor()))
                            .toList(),
                    b.journals().stream()
                            .map(j -> new JournalFigure(j.branch(), j.kind(), j.debitMinor(), j.creditMinor()))
                            .toList());
        }

        private static List<AmountFigure> amounts(List<ImportForecast.Amount> amounts) {
            return amounts.stream()
                    .map(a -> new AmountFigure(a.branch(), a.amountMinor()))
                    .toList();
        }
    }

    @Schema(name = "ImportPreviewFile", description = "What one file's rows come to")
    record PreviewFile(
            UUID batchId,
            int seq,
            String file,
            String entitySet,
            int rows,
            int write,
            int excluded,
            int blank,
            int blocked) {}

    @Schema(name = "ImportRecordCount")
    record RecordCount(String type, int count) {}

    @Schema(name = "ImportPreview", description = "What the import will create, from the staged rows only (FR-IMP-06)")
    record PreviewResponse(
            String templateKey,
            Integer templateVersion,
            List<RecordCount> records,
            List<PreviewFile> files,
            Balances balances) {

        static PreviewResponse of(ImportPreview.Preview p) {
            return new PreviewResponse(
                    p.templateKey(),
                    p.templateVersion(),
                    p.recordsByType().entrySet().stream()
                            .map(e -> new RecordCount(e.getKey(), e.getValue()))
                            .toList(),
                    p.batches().stream()
                            .map(b -> new PreviewFile(
                                    b.batchId(),
                                    b.seq(),
                                    b.file(),
                                    b.entitySet(),
                                    b.rows(),
                                    b.write(),
                                    b.excluded(),
                                    b.blank(),
                                    b.blocked()))
                            .toList(),
                    Balances.of(p.balances()));
        }
    }

    @Schema(name = "ImportReportSource", description = "One line of the report: a file and its rows by outcome")
    record ReportSource(String name, int read, int written, int existing, int skipped) {}

    @Schema(name = "ImportReportAnomaly", description = "One anomaly with its file and line, never a value of a row")
    record ReportAnomaly(String file, int line, String message) {}

    @Schema(name = "ImportDryRun", description = "The stored dry run: the commit rolled back, and its report")
    record DryRunResponse(
            Instant at,

            @Schema(description = "The dry run's balances equal the preview's")
            boolean previewMatches,

            @Schema(description = "False once anything of the run changed after it; run it again")
            boolean current,

            @Schema(description = "Which file failed and the kind of failure, or null")
            String failure,

            String checksum,
            List<ReportSource> sources,
            List<ReportAnomaly> anomalies,
            Balances balances) {

        static DryRunResponse of(ImportRuns.DryRun d) {
            CommitReport r = d.report();
            return new DryRunResponse(
                    d.at(),
                    d.previewMatches(),
                    d.current(),
                    r.failure(),
                    r.checksum(),
                    r.sources().stream()
                            .map(s -> new ReportSource(s.name(), s.read(), s.written(), s.existing(), s.skipped()))
                            .toList(),
                    r.anomalies().stream()
                            .map(a -> new ReportAnomaly(a.file(), a.line(), a.message()))
                            .toList(),
                    Balances.of(r.balances()));
        }
    }

    @Schema(name = "ImportCommitRequest", description = "The pending approval request of import_commit")
    record CommitRequested(UUID approvalRequestId) {}

    @Schema(name = "ImportDeletionCertificate", description = "The certificate of deletion of a purged run")
    record CertificateResponse(
            UUID id,
            int version,
            Instant issuedAt,

            @Schema(description = "operator, signed_off_30_days or inactive_90_days")
            String reason,

            boolean dumpConfirmed,

            @Schema(description = "The certificate as the plain text sent to the customer")
            String document,

            String sha256) {}

    @Schema(name = "ImportRawCell", description = "One cell as uploaded")
    record RawCellResponse(String column, String value, String type) {}

    @Schema(name = "ImportRawRow", description = "The row of an issue as uploaded; audited per row opened")
    record RawRowResponse(
            UUID issueId,
            UUID rowId,
            String file,
            int line,
            String field,

            @Schema(description = "The value the issue is about, as uploaded")
            String originalValue,

            List<RawCellResponse> cells) {}

    @GetMapping("/runs")
    @RequiresPermission("core.imports.manage")
    @Operation(summary = "The business's import runs, newest first, at most 50", operationId = "listImportRuns")
    RunList list() {
        return new RunList(runs.list().stream().map(ImportRunView::of).toList());
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("core.imports.manage")
    @Operation(summary = "Start a new import run, in draft; audited", operationId = "openImportRun")
    ImportRunView open() {
        return ImportRunView.of(runs.open(CurrentPrincipal.require().userId()));
    }

    @GetMapping("/runs/{run_id}")
    @RequiresPermission("core.imports.manage")
    @Operation(summary = "One import run and where it stands", operationId = "getImportRun")
    ImportRunView get(@PathVariable("run_id") UUID runId) {
        return ImportRunView.of(runs.get(runId));
    }

    @GetMapping("/runs/{run_id}/files")
    @RequiresPermission("core.imports.manage")
    @Operation(summary = "The files of an import run, in commit order", operationId = "listImportFiles")
    FileList files(@PathVariable("run_id") UUID runId) {
        runs.get(runId);
        return new FileList(
                uploads.batches(runId).stream().map(FileResponse::of).toList());
    }

    @PostMapping(path = "/runs/{run_id}/files", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("core.imports.manage")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                            schema = @Schema(type = "string", format = "binary")))
    @Operation(
            summary = "Upload one file into the run's quarantine, read with one source of a mapping; the body is the"
                    + " file itself, streamed; audited",
            operationId = "uploadImportFile")
    FileResponse upload(
            @PathVariable("run_id") UUID runId,
            @RequestParam("file_name") String fileName,
            @RequestParam("mapping_id") UUID mappingId,
            @RequestParam("source") String source,
            HttpServletRequest request) {
        ImportUploads.ParseOptions options = mappings.parseOptions(mappingId, source);
        try (InputStream body = request.getInputStream()) {
            return FileResponse.of(uploads.upload(
                    runId,
                    new ImportUploads.Upload(
                            fileName,
                            body,
                            request.getContentLengthLong(),
                            CurrentPrincipal.require().userId(),
                            options)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @PostMapping("/files/{batch_id}/cancel")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary = "Take a file out of the run: its rows and its quarantined file are deleted; audited",
            operationId = "cancelImportFile")
    FileResponse cancelFile(@PathVariable("batch_id") UUID batchId) {
        return FileResponse.of(uploads.cancelBatch(batchId));
    }

    @GetMapping("/mappings")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary = "The newest version of each of the business's mappings, with the sources each describes",
            operationId = "listImportMappings")
    MappingList mappings() {
        return new MappingList(
                mappings.newest().stream().map(MappingResponse::of).toList());
    }

    @PostMapping("/runs/{run_id}/check")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary = "Check every file of the run against its template; the run moves to in review; audited",
            operationId = "checkImportRun")
    CheckResult check(@PathVariable("run_id") UUID runId) {
        return CheckResult.of(validation.validate(runId));
    }

    @GetMapping("/runs/{run_id}/preview")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary = "What the import will create, from the staged rows only, with the predicted balances (FR-IMP-06)",
            operationId = "previewImportRun")
    PreviewResponse preview(@PathVariable("run_id") UUID runId) {
        return PreviewResponse.of(preview.preview(runId));
    }

    @PostMapping("/runs/{run_id}/dry-run")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary = "Run the commit inside one transaction that is rolled back, and store its report; audited",
            operationId = "dryRunImport")
    DryRunResponse dryRun(@PathVariable("run_id") UUID runId) {
        return DryRunResponse.of(runs.dryRun(runId, CurrentPrincipal.require().userId()));
    }

    @GetMapping("/runs/{run_id}/dry-run")
    @RequiresPermission("core.imports.manage")
    @Operation(summary = "The run's stored dry run; 404 while none ran", operationId = "getImportDryRun")
    DryRunResponse dryRunReport(@PathVariable("run_id") UUID runId) {
        return DryRunResponse.of(runs.dryRunReport(runId).orElseThrow(ApiException::notFound));
    }

    @PostMapping("/runs/{run_id}/ready")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary =
                    "Mark the run ready: no open blocking or warning issue and a dry run that still counts and matched"
                            + " the preview",
            operationId = "markImportRunReady")
    ImportRunView ready(@PathVariable("run_id") UUID runId) {
        return ImportRunView.of(runs.transition(runId, RunStates.READY));
    }

    @PostMapping("/runs/{run_id}/commit-request")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary = "Ask a second person to approve the import (import_commit, maker and checker)",
            operationId = "requestImportCommit")
    CommitRequested requestCommit(@PathVariable("run_id") UUID runId) {
        return new CommitRequested(runs.requestCommit(runId));
    }

    @GetMapping("/runs/{run_id}/certificate")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary = "The newest certificate of deletion of a purged run; 404 until it is purged",
            operationId = "getImportDeletionCertificate")
    CertificateResponse certificate(@PathVariable("run_id") UUID runId) {
        ImportPurges.Certificate c = purges.certificate(runId).orElseThrow(ApiException::notFound);
        return new CertificateResponse(
                c.id(), c.version(), c.issuedAt(), c.reason(), c.dumpConfirmed(), c.document(), c.sha256());
    }

    @GetMapping("/issues/{issue_id}/raw")
    @RequiresPermission("core.imports.view_raw")
    @Operation(
            summary = "The row of an issue as uploaded, with the issue's original value; audited per row opened (spec"
                    + " section 6.3)",
            operationId = "getImportRawRow")
    RawRowResponse raw(@PathVariable("issue_id") UUID issueId) {
        ImportReview.RawRow r = review.raw(issueId);
        return new RawRowResponse(
                r.issueId(),
                r.rowId(),
                r.file(),
                r.line(),
                r.field(),
                r.originalValue(),
                r.cells().stream()
                        .map(c -> new RawCellResponse(c.column(), c.value(), c.type()))
                        .toList());
    }
}
