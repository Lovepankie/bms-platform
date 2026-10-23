package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/platform/billing} (issue #480, ADR-075): the grace period and the words on how to pay, and the businesses due
 * for suspension. Platform operators only. The list suspends nothing: the operator suspends from the Business page with the
 * existing route, with a reason.
 */
@RestController
@RequestMapping(path = "/api/v1/platform/billing", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class BillingAdminController {

    private final BillingService billing;

    BillingAdminController(BillingService billing) {
        this.billing = billing;
    }

    @Schema(name = "BillingSettings")
    record SettingsResponse(
            @Schema(description = "Days an overdue business keeps working before it is listed for suspension")
            int graceDays,

            @Schema(description = "How to pay, in the operator's words, shown in reminders and on the Owner's page")
            String howToPay,

            Instant updatedAt,
            String updatedByName) {}

    @Schema(name = "BillingSettingsRequest")
    record SettingsRequest(
            @NotNull @Min(0) @Max(60) Integer graceDays,

            @Size(max = 1000) @Schema(description = "Left out: kept as it is; empty: cleared")
            String howToPay,

            @NotBlank @Size(min = 5, max = 300) @Schema(description = "Why; kept in the platform audit log")
            String reason) {}

    @Schema(name = "DueForSuspensionItem")
    record DueItem(
            UUID tenantId,
            String name,
            String slug,
            String planName,

            @Schema(description = "The last day paid for, or of the free month")
            LocalDate dueOn,

            LocalDate overdueSince,

            @Schema(description = "The last day of the grace period, passed")
            LocalDate graceEndedOn,

            long daysOverdue,

            @Schema(description = "The plan's monthly price today, or null when none was entered")
            Long amountDueMinor,

            String currency,
            String ownerName,
            String ownerEmail,
            Instant lastSignInAt) {}

    @Schema(name = "DueForSuspension")
    record DueForSuspension(
            int graceDays,
            @Schema(description = "Most overdue first") List<DueItem> items) {}

    @GetMapping("/settings")
    @RequiresPermission("platform.tenants.read")
    @Operation(summary = "The grace period and how to pay (FR-SUB-09)", operationId = "platformBillingSettings")
    SettingsResponse settings() {
        return billing.settingsResponse();
    }

    @PutMapping("/settings")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Change the grace period or how to pay, with a reason; unchanged values change nothing (FR-SUB-09)",
            operationId = "platformSaveBillingSettings")
    SettingsResponse save(@Valid @RequestBody SettingsRequest request) {
        return billing.saveSettings(request);
    }

    @GetMapping("/due-for-suspension")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary =
                    "Overdue businesses past the grace period, with days overdue and the amount; nothing is suspended automatically (FR-SUB-10)",
            operationId = "platformDueForSuspension")
    DueForSuspension dueForSuspension() {
        return billing.dueForSuspension();
    }
}
