package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportMetrics;
import com.rincoltech.bms.core.imports.ImportMetrics.PlatformRun;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/platform/imports/report} (chapter 7 section 7.11.3, chapter 14 section 14.6, issue #140): the runs of every
 * business with their metrics, for platform operators only, on the platform host. It reads across businesses through the
 * definer function {@code platform_import_runs_report}, which refuses a call bound to a business and names each business by
 * its slug, never its name. The CSV is recorded in the platform audit log.
 */
@RestController
@RequestMapping(path = "/api/v1/platform/imports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class PlatformImportReportController {

    private final ImportMetrics metrics;

    PlatformImportReportController(ImportMetrics metrics) {
        this.metrics = metrics;
    }

    @Schema(name = "PlatformImportRunList")
    record RunList(List<PlatformRun> items) {}

    @GetMapping("/report")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "The import runs of every business with their metrics, newest first; since (a moment) and limit"
                    + " (1 to 500, default 100)",
            operationId = "platformImportRunsReport")
    RunList report(
            @RequestParam(name = "since", required = false) Instant since,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return new RunList(metrics.platformReport(since, limit == null ? 100 : limit));
    }

    @GetMapping(path = "/report.csv", produces = "text/csv")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "The same as CSV, one row per run, at most 500, each business by its slug only; audited",
            operationId = "platformExportImportRunsReport")
    ResponseEntity<String> csv(@RequestParam(name = "since", required = false) Instant since) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"import-runs.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(metrics.platformReportCsv(
                        since, CurrentPrincipal.require().userId()));
    }
}
