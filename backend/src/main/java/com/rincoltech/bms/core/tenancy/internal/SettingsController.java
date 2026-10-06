package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** {@code /api/v1/settings} (chapter 7 section 7.11.4, FR-TEN-08). */
@RestController
@RequestMapping(path = "/api/v1/settings", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "settings")
class SettingsController {

    private final SettingsService service;
    private final BrandingService branding;

    SettingsController(SettingsService service, BrandingService branding) {
        this.service = service;
        this.branding = branding;
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

            @Schema(
                    description =
                            "The current logo (a core document of this tenant), or null; set through PUT /settings/logo")
            UUID logoDocumentId,

            @Schema(description = "Brand colour #RRGGBB, or null for the platform look (FR-TEN-08)")
            String themePrimary,

            @Schema(description = "True once the admin has saved a business name of their own")
            boolean displayNameSet,

            @Schema(description = "True once the business set-up checklist has been dismissed")
            boolean setupDismissed,

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

            @Pattern(regexp = "^(#[0-9A-Fa-f]{6})?$")
            @Schema(
                    description =
                            "#RRGGBB, or an empty string to clear it; white or near-black text on it must reach contrast 4.5 (FR-TEN-08)")
            String themePrimary,

            Boolean setupDismissed) {}

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

    @PutMapping(path = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Upload or replace the business logo: PNG, JPEG or WebP, 1 MB, 128 px or more (FR-TEN-08)",
            operationId = "uploadLogo")
    @ApiResponse(responseCode = "413", description = "Over 1 MB (file_too_large)")
    @ApiResponse(responseCode = "415", description = "Not PNG, JPEG or WebP, or not decodable (unsupported_file_type)")
    @ApiResponse(
            responseCode = "422",
            description = "svg_not_allowed, image_too_small, image_too_large (over 4 megapixels)")
    ResponseEntity<SettingsResponse> uploadLogo(@RequestPart("file") MultipartFile file) {
        if (file.getSize() > BrandingService.LOGO.maxBytes()) {
            throw new ApiException(
                    HttpStatus.CONTENT_TOO_LARGE, "file_too_large", "File too large", "The file is larger than 1 MB.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "The upload could not be read.");
        }
        SettingsResponse settings = branding.uploadLogo(bytes);
        return ResponseEntity.ok().eTag(String.valueOf(settings.version())).body(settings);
    }

    @DeleteMapping("/logo")
    @RequiresPermission("core.settings.manage")
    @Operation(summary = "Remove the business logo; the file is kept (FR-TEN-08)", operationId = "removeLogo")
    ResponseEntity<SettingsResponse> removeLogo() {
        SettingsResponse settings = service.removeLogo();
        return ResponseEntity.ok().eTag(String.valueOf(settings.version())).body(settings);
    }
}
