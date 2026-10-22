package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.reporting.internal.ReportsApi.RunView;
import com.rincoltech.bms.core.reporting.internal.RunService.Requested;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/data-export} (chapter 7 section 7.11.8, issue #334 step 1, ADR-074): "Download my data" in Business set-up.
 * The workbook is a report run of {@code core.data_export}; its file is fetched with
 * {@code POST /documents/{document_id}/download-url} like any report file. Asking for one is allowed while the business is
 * read only (a suspended tenant, FR-TEN-06): it changes none of the business's data. Nothing is cached.
 */
@RestController
@RequestMapping(path = "/api/v1/data-export", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "reports")
class DataExportController {

    private final DataExportService exports;

    DataExportController(DataExportService exports) {
        this.exports = exports;
    }

    @GetMapping
    @RequiresPermission(DataExportReport.PERMISSION)
    @Operation(
            summary =
                    "The data export card: the weekly email switch, the last export and email, the caller's newest workbook",
            operationId = "getDataExport")
    DataExportApi.Status status(HttpServletResponse response) {
        NoStore.apply(response);
        return exports.status();
    }

    @PostMapping("/runs")
    @RequiresPermission(DataExportReport.PERMISSION)
    @Operation(
            summary =
                    "Ask for a workbook of the whole business, made in the background; an identical one still queued or running is returned (202 new, 200 existing)",
            operationId = "createDataExport")
    ResponseEntity<RunView> request(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletResponse response) {
        NoStore.apply(response);
        Requested made = exports.request(idempotencyKey);
        return ResponseEntity.status(made.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(made.run());
    }

    @PutMapping(path = "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(DataExportReport.PERMISSION)
    @Operation(
            summary =
                    "Switch the weekly email of the workbook to the Owner on or off, audited; the Owner only (403 owner_only)",
            operationId = "updateDataExportSettings")
    DataExportApi.Status settings(
            @Valid @RequestBody DataExportApi.SettingsRequest request, HttpServletResponse response) {
        NoStore.apply(response);
        return exports.setWeeklyEmail(request.weeklyEmail());
    }
}
