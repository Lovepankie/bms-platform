package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportMetrics;
import com.rincoltech.bms.core.imports.ImportMetrics.Defect;
import com.rincoltech.bms.core.imports.ImportMetrics.Metrics;
import com.rincoltech.bms.core.imports.ImportMetrics.NewDefect;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/imports} (chapter 7 section 7.11.9, issue #140, spec section 8): the metrics of a run, from its stored data,
 * and the defect log of its hypercare. Every route needs {@code core.imports.manage} and works on the business of the host
 * only (another business's run or defect is 404). No answer holds a value of a row: codes, counts, times and the operator's
 * description, which names nobody.
 */
@RestController
@RequestMapping(path = "/api/v1/imports", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "imports")
class MetricsController {

    /** The forms of a fix's reference, as the CHECK of import_defects.fix_ref holds them (V148). */
    static final String FIX_REF = "#[0-9]{1,7}|[0-9a-f]{7,40}"
            + "|https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/(pull|issues|commit)/[0-9a-f]{1,40}";

    private final ImportMetrics metrics;

    MetricsController(ImportMetrics metrics) {
        this.metrics = metrics;
    }

    @Schema(name = "ImportDefectList")
    record DefectList(List<Defect> items) {}

    @Schema(name = "ImportDefectRequest", description = "A defect found after the cutover")
    record DefectRequest(
            @NotBlank
            @Size(min = 10, max = 500)
            @Schema(
                    description = "What went wrong, by file, line, field and code; never a person's name, phone, ID"
                            + " number or email")
            String description,

            @Pattern(regexp = "[A-Z][A-Z0-9_]{1,59}")
            @Schema(
                    description =
                            "The code of the issue-code catalogue the row should have had; leave it out when a new"
                                    + " code is needed and propose that code in the description")
            String expectedCode) {}

    @Schema(name = "ImportDefectCloseRequest")
    record CloseRequest(
            @NotNull @Schema(description = "The defect's version as read")
            Integer version,

            @NotBlank
            @Size(max = 200)
            @Pattern(regexp = FIX_REF)
            @Schema(description = "The fix: an issue or pull request number (#123), a commit hash or a GitHub link")
            String fixRef) {}

    @GetMapping("/runs/{run_id}/metrics")
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary = "The metrics of an import run, computed from its stored data (spec section 8); the same after the"
                    + " run is purged",
            operationId = "getImportRunMetrics")
    Metrics metrics(@PathVariable("run_id") UUID runId) {
        return metrics.metrics(runId);
    }

    @GetMapping("/runs/{run_id}/defects")
    @RequiresPermission("core.imports.manage")
    @Operation(summary = "The defects logged against an import run, oldest first", operationId = "listImportDefects")
    DefectList defects(@PathVariable("run_id") UUID runId) {
        return new DefectList(metrics.defects(runId));
    }

    @PostMapping("/runs/{run_id}/defects")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission("core.imports.manage")
    @Operation(
            summary = "Log a defect found after the cutover against an imported run, with the code it should have had;"
                    + " audited",
            operationId = "logImportDefect")
    Defect log(@PathVariable("run_id") UUID runId, @Valid @RequestBody DefectRequest request) {
        return metrics.logDefect(
                runId,
                new NewDefect(request.description(), request.expectedCode()),
                CurrentPrincipal.require().userId());
    }

    @PostMapping("/defects/{defect_id}/close")
    @RequiresPermission("core.imports.manage")
    @Operation(summary = "Close a defect with its fix's reference, once; audited", operationId = "closeImportDefect")
    Defect close(@PathVariable("defect_id") UUID defectId, @Valid @RequestBody CloseRequest request) {
        return metrics.closeDefect(
                defectId,
                request.version(),
                request.fixRef(),
                CurrentPrincipal.require().userId());
    }
}
