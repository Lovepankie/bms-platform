package com.rincoltech.bms.core.platform.internal;

import com.rincoltech.bms.core.identity.OperatorSecurity;
import com.rincoltech.bms.core.platform.internal.PlanController.ModuleRequestResponse;
import com.rincoltech.bms.core.platform.internal.PlanController.QueuedChange;
import com.rincoltech.bms.core.platform.internal.PlanController.QueuedRequest;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
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
 * The operator's side of the Owners' plan changes (issue #472, ADR-073): the module requests of every business, the
 * approval (with the operator's own authenticator code, a reason and optionally a plan move) or the decline (with the
 * code and a reason), and the module stops and cancellations still to come.
 */
@RestController
@RequestMapping(path = "/api/v1/platform", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "platform")
class PlanQueueController {

    private final PlanService plans;
    private final OperatorSecurity security;

    PlanQueueController(PlanService plans, OperatorSecurity security) {
        this.plans = plans;
        this.security = security;
    }

    @Schema(name = "PlatformModuleRequestList")
    record RequestList(List<QueuedRequest> items) {}

    @Schema(name = "PlatformScheduledChangeList")
    record ChangeList(List<QueuedChange> items) {}

    @Schema(name = "PlatformModuleApproval")
    record ApproveRequest(
            @NotBlank
            @Size(max = 20)
            @Schema(description = "A current 6 digit code from the operator's own authenticator app")
            String totpCode,

            @NotBlank @Size(min = 5, max = 300) @Schema(description = "Why; kept in the platform audit log")
            String reason,

            @Pattern(regexp = PriceBookService.PLAN_CODE)
            @Schema(description = "Move the business to this plan first, when its plan does not include the module")
            String planCode) {}

    @Schema(name = "PlatformModuleDecline")
    record DeclineRequest(
            @NotBlank
            @Size(max = 20)
            @Schema(description = "A current 6 digit code from the operator's own authenticator app")
            String totpCode,

            @NotBlank
            @Size(min = 5, max = 300)
            @Schema(description = "Why; shown to the Owner and kept in the audit log")
            String reason) {}

    @GetMapping("/module-requests")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "Module requests of every business, newest first; pending by default (ADR-073)",
            operationId = "platformListModuleRequests")
    RequestList requests(@RequestParam(name = "status", required = false, defaultValue = "pending") String status) {
        if (!List.of("pending", "approved", "declined", "all").contains(status)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("status", "invalid", "Choose pending, approved, declined or all.")));
        }
        return new RequestList(plans.moduleRequests("all".equals(status) ? null : status));
    }

    @PostMapping(path = "/module-requests/{request_id}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Approve a module request with a reason, optionally moving the plan first; switches the module on, data kept; needs a current code from the operator's own authenticator (ADR-073)",
            operationId = "platformApproveModuleRequest")
    ModuleRequestResponse approve(
            @PathVariable("request_id") UUID requestId, @Valid @RequestBody ApproveRequest request) {
        security.requireTotp(request.totpCode());
        return plans.decideRequest(requestId, true, request.planCode(), request.reason());
    }

    @PostMapping(path = "/module-requests/{request_id}/decline", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("platform.tenants.manage")
    @Operation(
            summary =
                    "Decline a module request with a reason the Owner sees; needs a current code from the operator's own authenticator (ADR-073)",
            operationId = "platformDeclineModuleRequest")
    ModuleRequestResponse decline(
            @PathVariable("request_id") UUID requestId, @Valid @RequestBody DeclineRequest request) {
        security.requireTotp(request.totpCode());
        return plans.decideRequest(requestId, false, null, request.reason());
    }

    @GetMapping("/scheduled-changes")
    @RequiresPermission("platform.tenants.read")
    @Operation(
            summary = "Module stops and cancellations still to come, soonest first (ADR-073)",
            operationId = "platformListScheduledChanges")
    ChangeList changes() {
        return new ChangeList(plans.scheduledChanges());
    }
}
