package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/closure}: closing the business on its own host (issue #571, ADR-090). Administrators see the card; only the
 * Owner asks to close, after the step-up, and only the Owner cancels the request. From the request the business is read only,
 * the Owner gets a full data export, and the platform operator may remove it once the cooling-off period is over.
 */
@RestController
@RequestMapping(path = "/api/v1/closure", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "closure")
class ClosureController {

    private final ClosureService closures;

    ClosureController(ClosureService closures) {
        this.closures = closures;
    }

    @Schema(name = "BusinessClosure", description = "The request to close the business, if one is open")
    record ClosureView(
            @Schema(description = "none or requested") String status,

            UUID id,
            Instant requestedAt,
            String requestedByName,

            @Schema(description = "The first day the platform operator may remove the business")
            LocalDate removeAfter,

            @Schema(description = "The full data export made for the Owner with the request, or null")
            UUID exportRunId,

            @Schema(description = "The days between the request and the first day of removal")
            int coolingOffDays,

            @Schema(description = "Whether the caller is the Owner, who alone asks to close and cancels")
            boolean canChange) {}

    @Schema(
            name = "ClosureStepUp",
            description = "The authenticator code when the Owner has two-step sign-in, else the password")
    record StepUpRequest(
            @Size(max = 20) String totpCode,
            @Size(max = 200) String password) {}

    @GetMapping
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "The open request to close the business, if any, and whether the caller may change it (ADR-090)",
            operationId = "getClosure")
    ClosureView view() {
        return closures.view();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary =
                    "Owner only, with the step-up: ask to close the business; read only at once, a full data export for the Owner, removal by the platform operator after the cooling-off (ADR-090)",
            operationId = "requestClosure")
    ResponseEntity<ClosureView> request(@Valid @RequestBody StepUpRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(closures.request(request.totpCode(), request.password()));
    }

    @PostMapping("/cancel")
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Owner only: cancel the request to close the business before it is removed (ADR-090)",
            operationId = "cancelClosure")
    ClosureView cancel() {
        return closures.cancel();
    }
}
