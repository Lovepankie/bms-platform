package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.kernel.AuthenticatedEndpoint;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/plan}: a business's own plan and subscription on its own host (issue #472, ADR-073). Administrators see the
 * plan card; only the Owner (ADR-062) asks for a module, stops one, cancels, or undoes a change still to come. Stopping a
 * module and cancelling need the Owner's step-up (the authenticator code, else the password). Every member of staff reads
 * the end date for the banner and the read-only notice.
 */
@RestController
@RequestMapping(path = "/api/v1/plan", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "plan")
class PlanController {

    private final PlanService plans;

    PlanController(PlanService plans) {
        this.plans = plans;
    }

    @Schema(name = "PlanModuleRequest", description = "A module the Owner asked for, and the operator's decision")
    record ModuleRequestResponse(
            UUID id,
            String moduleKey,

            @Schema(description = "pending, approved or declined")
            String status,

            String requestedByName,
            Instant createdAt,
            Instant decidedAt,

            @Schema(description = "True when the plan already included the module, so it was switched on at once")
            boolean decidedAutomatically,

            @Schema(description = "The operator's reason, shown to the Owner when declined")
            String decisionReason) {}

    @Schema(name = "PlanScheduledChange", description = "A module stopped or the subscription cancelled, from a date")
    record ChangeResponse(
            UUID id,

            @Schema(description = "stop_module or cancel") String kind,

            String moduleKey,

            @Schema(description = "The first day the change is in force")
            LocalDate effectiveOn,

            @Schema(description = "scheduled, applied or undone")
            String status,

            String requestedByName,
            Instant createdAt,
            Instant appliedAt) {}

    @Schema(name = "BusinessPlan")
    record PlanResponse(
            String planCode,
            String planName,

            @Schema(
                    description =
                            "trial, active, past_due, suspended or cancelled; null when the business has none yet")
            String subscriptionStatus,

            LocalDate trialEndsOn,

            @Schema(description = "The last day paid for, or null")
            LocalDate paidUntil,

            boolean pilot,
            List<String> modules,

            @Schema(description = "Modules the business does not use and may ask for")
            List<String> modulesAvailable,

            @Schema(description = "Modules the plan includes: asking for one of these switches it on at once")
            List<String> modulesInPlan,

            @Schema(description = "The day a change asked for now would take effect")
            LocalDate changesTakeEffectOn,

            @Schema(description = "Whether a change asked for now takes effect at once, as nothing is paid ahead")
            boolean appliesAtOnce,

            @Schema(description = "Today in the business's own time zone")
            LocalDate today,

            @Schema(description = "While a cancellation is scheduled: the first day the business is read only")
            LocalDate endsOn,

            @Schema(description = "When the subscription has ended: the day it ended")
            LocalDate endedOn,

            @Schema(description = "Whether the signed-in user is the Owner, who alone may change the plan")
            boolean canChange,

            @Schema(description = "The latest module requests, newest first")
            List<ModuleRequestResponse> requests,

            @Schema(description = "Changes still to come, soonest first, then the latest done or undone")
            List<ChangeResponse> changes) {}

    @Schema(
            name = "BusinessPlanStatus",
            description = "For the banner and the read-only notice every member of staff sees")
    record PlanStatusResponse(
            @Schema(description = "While a cancellation is scheduled: the first day the business is read only")
            LocalDate endsOn,

            @Schema(description = "When the subscription has ended: the day it ended; the business is read only")
            LocalDate endedOn) {}

    @Schema(
            name = "PlanModuleAsk",
            description =
                    "The module, with the Owner's step-up: the authenticator code when the Owner has two-step sign-in, else the password")
    record AskRequest(
            @Pattern(regexp = "^[a-z]{2,30}$") String module,
            @Size(max = 20) String totpCode,
            @Size(max = 200) String password) {}

    @Schema(
            name = "PlanStepUp",
            description = "The authenticator code when the Owner has two-step sign-in, else the password")
    record StepUpRequest(
            @Size(max = 20) String totpCode,
            @Size(max = 200) String password) {}

    @Schema(name = "PlatformModuleRequest")
    record QueuedRequest(
            UUID id,
            UUID tenantId,
            String tenantSlug,
            String tenantName,
            String planCode,

            @Schema(description = "Whether the business's plan already includes the module")
            boolean planIncludes,

            String moduleKey,
            String status,
            String requestedByName,
            Instant createdAt,
            Instant decidedAt,
            boolean decidedAutomatically,
            String decisionReason) {}

    @Schema(name = "PlatformScheduledChange")
    record QueuedChange(
            UUID id,
            UUID tenantId,
            String tenantSlug,
            String tenantName,
            String kind,
            String moduleKey,
            LocalDate effectiveOn,
            String requestedByName,
            Instant createdAt) {}

    @GetMapping
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary =
                    "The plan card: the plan, the modules on, the paid-until date, module requests and changes still to come (ADR-073)",
            operationId = "getPlan")
    PlanResponse plan() {
        return plans.plan();
    }

    @GetMapping("/status")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(
            summary = "When the subscription ends or ended, for the banner and the read-only notice (ADR-073)",
            operationId = "getPlanStatus")
    PlanStatusResponse status() {
        return plans.status();
    }

    @PostMapping(path = "/module-requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary =
                    "Owner only, after the step-up: ask for a module; switched on at once when the plan includes it, otherwise the platform operator decides (ADR-073)",
            operationId = "requestPlanModule")
    ResponseEntity<ModuleRequestResponse> ask(@Valid @RequestBody AskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(plans.requestModule(request.module(), request.totpCode(), request.password()));
    }

    @PostMapping(path = "/modules/{module}/stop", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary =
                    "Owner only, with the step-up: stop using a module at the end of the paid period (at once when nothing is paid ahead); records are kept (ADR-073)",
            operationId = "stopPlanModule")
    ResponseEntity<ChangeResponse> stop(
            @PathVariable("module") String module, @Valid @RequestBody StepUpRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(plans.stopModule(module, request.totpCode(), request.password()));
    }

    @PostMapping(path = "/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary =
                    "Owner only, with the step-up: cancel the subscription at the end of the paid period; the business is read only after it, data kept (ADR-073)",
            operationId = "cancelPlan")
    ResponseEntity<ChangeResponse> cancel(@Valid @RequestBody StepUpRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(plans.cancel(request.totpCode(), request.password()));
    }

    @PostMapping("/changes/{change_id}/undo")
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Owner only: undo a module stop or the cancellation before its day (ADR-073)",
            operationId = "undoPlanChange")
    ChangeResponse undo(@PathVariable("change_id") UUID changeId) {
        return plans.undo(changeId);
    }
}
