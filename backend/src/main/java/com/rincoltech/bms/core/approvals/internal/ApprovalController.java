package com.rincoltech.bms.core.approvals.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * {@code /api/v1/approvals} (chapter 7 section 7.11.5). Every route declares
 * {@code core.approvals.read}; approving and rejecting then need the action's checker permission
 * in the request's branch, and cancelling needs to be its maker (chapter 8 section 8.4).
 */
@RestController
@RequestMapping(path = "/api/v1/approvals", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "approvals")
class ApprovalController {

    private final ApprovalService service;

    ApprovalController(ApprovalService service) {
        this.service = service;
    }

    @Schema(name = "Approval")
    record ApprovalResponse(
            UUID id,
            String actionType,
            String subjectType,
            UUID subjectId,
            UUID branchId,
            Long amountMinor,
            String currency,

            @Schema(description = "pending, approved, rejected, cancelled, expired or stale")
            String status,

            UUID requestedBy,
            String requestedByName,
            Instant requestedAt,
            Instant expiresAt,
            UUID decidedBy,
            Instant decidedAt,
            String decisionNote,
            String executionError,

            @Schema(description = "The snapshot executed on approval; detail only (FR-APR-08)")
            Map<String, Object> payload,

            boolean canDecide,
            boolean canCancel) {}

    @Schema(name = "ApprovalPage")
    record ApprovalPage(List<ApprovalResponse> items, String nextCursor) {}

    @Schema(name = "ApprovalDecisionRequest")
    record DecisionRequest(
            @Size(max = 1000) @Schema(description = "Required to reject")
            String note) {}

    @GetMapping
    @RequiresPermission("core.approvals.read")
    @Operation(summary = "Requests the caller may decide or made (FR-APR-05)", operationId = "listApprovals")
    ApprovalPage list(
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(name = "action_type", required = false) List<String> actionTypes,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(statuses, actionTypes, branchIds, limit, cursor);
    }

    @GetMapping("/{approval_id}")
    @RequiresPermission("core.approvals.read")
    @Operation(summary = "One request with its payload snapshot", operationId = "getApproval")
    ApprovalResponse get(@PathVariable("approval_id") UUID approvalId) {
        return service.get(approvalId);
    }

    @PostMapping("/{approval_id}/approve")
    @RequiresPermission("core.approvals.read")
    @Operation(
            summary = "Approve and execute in the same transaction (FR-APR-02, FR-APR-06, FR-APR-08)",
            operationId = "approveApproval")
    ApprovalResponse approve(
            @PathVariable("approval_id") UUID approvalId,
            @Valid @RequestBody(required = false) DecisionRequest request) {
        return service.approve(approvalId, request == null ? null : request.note());
    }

    @PostMapping("/{approval_id}/reject")
    @RequiresPermission("core.approvals.read")
    @Operation(summary = "Reject with a required note (FR-APR-06)", operationId = "rejectApproval")
    ApprovalResponse reject(@PathVariable("approval_id") UUID approvalId, @Valid @RequestBody DecisionRequest request) {
        return service.reject(approvalId, request.note());
    }

    @PostMapping("/{approval_id}/cancel")
    @RequiresPermission("core.approvals.read")
    @Operation(summary = "The maker cancels their own pending request (FR-APR-07)", operationId = "cancelApproval")
    ApprovalResponse cancel(@PathVariable("approval_id") UUID approvalId) {
        return service.cancel(approvalId);
    }
}
