package com.rincoltech.bms.core.onboarding.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/platform/applications}: the operator portal's applications queue (spec section
 * 8, chapter 7 section 7.11.3). Platform operators only: the platform permissions that no tenant
 * role holds, on the platform host only.
 */
@RestController
@RequestMapping(path = "/api/v1/platform/applications", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class ApplicationsController {

    private final ApplicationService service;

    ApplicationsController(ApplicationService service) {
        this.service = service;
    }

    @Schema(name = "Application")
    record ApplicationResponse(
            UUID id,
            String reference,

            @Schema(description = "submitted, needs_info, verified, activated, rejected or expired")
            String status,

            String businessName,
            String contactName,
            String contactEmail,
            String contactPhone,
            String country,
            List<String> modules,
            String term,
            String wayIn,
            String agentCode,
            String message,
            Instant emailVerifiedAt,
            String operatorNote,
            String applicantReply,
            String rejectReason,
            UUID decidedBy,
            Instant decidedAt,
            UUID activatedBy,
            Instant activatedAt,
            UUID activatedTenantId,
            String activatedTenantSlug,
            String activationWay,
            String activationTerm,
            String activationNote,
            Instant createdAt,
            Instant updatedAt) {}

    @Schema(name = "ApplicationList")
    record ApplicationList(
            List<ApplicationResponse> items,

            @Schema(description = "Applications in the queue per status, whatever the filter")
            Map<String, Long> counts) {}

    @Schema(name = "PossibleDuplicate", description = "Another application or tenant that may be the same business")
    record Duplicate(
            @Schema(description = "application or tenant") String kind,
            UUID id,

            @Schema(description = "The application reference or the tenant slug")
            String reference,

            String name,
            String status,

            @Schema(description = "email, phone, business_name")
            List<String> matchedOn) {}

    @Schema(name = "ApplicationDetail")
    record ApplicationDetail(
            ApplicationResponse application,
            List<Duplicate> possibleDuplicates,

            @Schema(description = "A free slug from the business name (FR-TEN-02); editable at Activate")
            String suggestedSlug) {}

    @Schema(name = "ApplicationNoteRequest")
    record NoteRequest(@NotBlank @Size(max = 1000) String note) {}

    @Schema(name = "ActivateApplicationRequest")
    record ActivateRequest(
            @NotEmpty @Size(max = 5) List<@Pattern(regexp = "^[a-z]{2,30}$") String> modules,
            @NotBlank @Pattern(regexp = "monthly|annual") String term,

            @NotBlank
            @Pattern(regexp = "trial|paid")
            @Schema(description = "trial: one month free; paid: payment received")
            String wayIn,

            @Size(max = 500) @Schema(description = "Required when paid: a free text note of the payment received")
            String paymentNote,

            @NotBlank @Size(max = 63) @Schema(description = "FR-TEN-02 rules; cannot change later")
            String slug,

            @Pattern(regexp = "^[a-z0-9_-]{1,40}$") @Schema(description = "Default starter")
            String planCode,

            @Size(max = 40) @Schema(description = "Ignored in this release: agents arrive later")
            String agentCode) {}

    @Schema(name = "ActivationResult")
    record ActivationResult(
            ApplicationResponse application,

            @Schema(description = "False when the application was already activated: nothing was done")
            boolean activatedNow,

            UUID tenantId,
            String tenantSlug,

            @Schema(description = "The admin's one-time link, shown once (also emailed); null on a repeat")
            String adminInvitationUrl,

            Instant adminInvitationExpiresAt) {}

    @GetMapping
    @RequiresPermission("platform.tenants.read")
    @Operation(summary = "The applications queue with counts (FR-ONB-05)", operationId = "platformListApplications")
    ApplicationList list(
            @RequestParam(name = "status", required = false)
                    List<@Pattern(regexp = "submitted|needs_info|verified|activated|rejected|expired") String> status) {
        return service.list(status);
    }

    @GetMapping("/{application_id}")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "One application with the possible duplicates (FR-ONB-05)",
            operationId = "platformGetApplication")
    ApplicationDetail get(@PathVariable("application_id") UUID id) {
        return service.detail(id);
    }

    @PostMapping("/{application_id}/verify")
    @RequiresPermission("platform.tenants.manage")
    @Operation(summary = "The operator verified the business (FR-ONB-06)", operationId = "platformVerifyApplication")
    ApplicationDetail verify(@PathVariable("application_id") UUID id) {
        return service.decide(id, "verified", null);
    }

    @PostMapping("/{application_id}/needs-info")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Ask the applicant for more information; they see the note (FR-ONB-06)",
            operationId = "platformApplicationNeedsInfo")
    ApplicationDetail needsInfo(@PathVariable("application_id") UUID id, @Valid @RequestBody NoteRequest request) {
        return service.decide(id, "needs_info", request.note());
    }

    @PostMapping("/{application_id}/reject")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Reject with a reason the applicant sees (FR-ONB-06)",
            operationId = "platformRejectApplication")
    ApplicationDetail reject(@PathVariable("application_id") UUID id, @Valid @RequestBody NoteRequest request) {
        return service.decide(id, "rejected", request.note());
    }

    @PostMapping("/{application_id}/activate")
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary = "Create the tenant and send the activation link; a repeat does nothing (FR-ONB-07)",
            operationId = "platformActivateApplication")
    ActivationResult activate(@PathVariable("application_id") UUID id, @Valid @RequestBody ActivateRequest request) {
        return service.activate(id, request);
    }
}
