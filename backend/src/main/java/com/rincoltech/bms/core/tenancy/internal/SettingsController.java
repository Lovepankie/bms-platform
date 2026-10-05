package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/settings} (chapter 7 section 7.11.4, FR-TEN-08). */
@RestController
@RequestMapping(path = "/api/v1/settings", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "settings")
class SettingsController {

    private final SettingsService service;

    SettingsController(SettingsService service) {
        this.service = service;
    }

    /** Every key of chapter 6 table {@code tenant_settings}, with its default applied. */
    @Schema(name = "TenantSettings")
    record SettingsResponse(
            String displayName,
            String receiptFooter,
            String smsSenderName,
            String smsWindowStart,
            String smsWindowEnd,
            Map<String, Long> approvalThresholdsMinor,
            int approvalValidityDays,
            boolean allowLoansBeforeKycVerified,
            Integer maxActiveLoansPerMember,
            Map<String, Integer> appraisalWeights,
            List<String> disabledCollateralTypes,
            boolean requireMfaAllStaff,
            boolean retailAllowNegativeStock,
            int version) {}

    /** Omitted (or null) fields are unchanged (chapter 7 section 7.5). */
    @Schema(name = "UpdateTenantSettingsRequest")
    record UpdateSettingsRequest(
            @Size(min = 1, max = 100) String displayName,
            @Size(max = 500) String receiptFooter,

            @Pattern(regexp = "^[A-Za-z0-9 ]{3,11}$") @Schema(description = "Subject to aggregator approval")
            String smsSenderName,

            @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$")
            String smsWindowStart,

            @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$")
            String smsWindowEnd,

            @Schema(description = "Action type to amount in minor units; below it no checker is needed (FR-APR-04)")
            Map<String, Long> approvalThresholdsMinor,

            @Min(1) @Max(90) Integer approvalValidityDays,
            Boolean allowLoansBeforeKycVerified,
            @Min(1) @Max(100) Integer maxActiveLoansPerMember,

            @Schema(description = "repayment_history, affordability, collateral_cover, exposure; summing to 100")
            Map<String, Integer> appraisalWeights,

            List<@Pattern(regexp = "^[a-z_]{2,40}$") String> disabledCollateralTypes,
            Boolean requireMfaAllStaff,

            @Schema(description = "FR-RET-03: whether a retail sale may take stock below zero (default true)")
            Boolean retailAllowNegativeStock) {}

    @GetMapping
    @RequiresPermission("core.settings.read")
    @Operation(summary = "Tenant settings", operationId = "getSettings")
    ResponseEntity<SettingsResponse> get() {
        SettingsResponse settings = service.current();
        return ResponseEntity.ok().eTag(String.valueOf(settings.version())).body(settings);
    }

    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Change tenant settings, audited with before and after (FR-TEN-08)",
            operationId = "updateSettings")
    ResponseEntity<SettingsResponse> update(
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateSettingsRequest request) {
        SettingsResponse settings = service.update(ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(settings.version())).body(settings);
    }
}
