package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.reporting.internal.ReportsApi.CatalogueEntry;
import com.rincoltech.bms.core.reporting.internal.ReportsApi.RunRequest;
import com.rincoltech.bms.core.reporting.internal.ReportsApi.RunView;
import com.rincoltech.bms.core.reporting.internal.RunService.Requested;
import com.rincoltech.bms.kernel.AuthenticatedEndpoint;
import com.rincoltech.bms.kernel.NoStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/reports} and {@code /api/v1/report-runs} (chapter 7 section 7.11.8). A route here is open to any
 * signed-in staff user; what a user may run is decided per report, from the report's own permission, in
 * {@link RunService} (the same split as the document routes: the owner of the subject decides). Nothing is cached.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "reports")
class ReportRunsController {

    private final RunService runs;

    ReportRunsController(RunService runs) {
        this.runs = runs;
    }

    @GetMapping("/reports")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary =
                    "The reports this user may run: those of the modules the tenant has on, under a permission the user holds (FR-RPT-01)",
            operationId = "listReports")
    List<CatalogueEntry> catalogue(HttpServletResponse response) {
        NoStore.apply(response);
        return runs.catalogue();
    }

    @PostMapping(path = "/report-runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary =
                    "Queue a report run; an identical request by the same user while one is queued or running returns that run (202 new, 200 existing)",
            operationId = "createReportRun")
    ResponseEntity<RunView> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RunRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        Requested made = runs.request(idempotencyKey, request);
        return ResponseEntity.status(made.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(made.run());
    }

    @GetMapping("/report-runs")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(summary = "The caller's own runs, newest first", operationId = "listReportRuns")
    List<RunView> list(
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit,
            HttpServletResponse response) {
        NoStore.apply(response);
        return runs.list(limit);
    }

    @GetMapping("/report-runs/{run_id}")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary = "One run: its status, parameters, dates and file; the requester, or a holder of core.audit.read",
            operationId = "getReportRun")
    RunView get(@PathVariable("run_id") UUID runId, HttpServletResponse response) {
        NoStore.apply(response);
        return runs.get(runId);
    }

    @PostMapping("/report-runs/{run_id}/cancel")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary = "Cancel a run: at once when queued, at the next checkpoint when running; the requester only",
            operationId = "cancelReportRun")
    ResponseEntity<RunView> cancel(@PathVariable("run_id") UUID runId, HttpServletResponse response) {
        NoStore.apply(response);
        RunView view = runs.cancel(runId);
        return ResponseEntity.status(view.status().equals("running") ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(view);
    }
}
