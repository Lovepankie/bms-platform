package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/branches} (chapter 7 section 7.11.4, FR-BR-01). */
@RestController
@RequestMapping(path = "/api/v1/branches", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "branches")
class BranchController {

    private final BranchService service;

    BranchController(BranchService service) {
        this.service = service;
    }

    @Schema(name = "CreateBranchRequest")
    record CreateBranchRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9]{2,10}$") String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String location,

            @Schema(
                    description =
                            "false creates a Store: it holds stock but does not sell (ADR-048). Omitted means a shop.")
            Boolean sellsGoods) {}

    @Schema(name = "UpdateBranchRequest", description = "Omitted fields are unchanged. The code never changes.")
    record UpdateBranchRequest(
            @Size(min = 1, max = 200) String name,
            @Size(max = 200) String location,

            @Schema(
                    description = "false marks a branch that keeps no goods (a head office): no sales, restocks, "
                            + "transfers or stock adjustments there (422 branch_not_stocking). Refused with "
                            + "branch_still_has_stock while goods or a transfer on its way remain.")
            Boolean stocksGoods,

            @Schema(
                    description =
                            "false marks a Store: it holds stock, receives purchases and takes transfers in and out, but a "
                                    + "sale, a payment taken or a refund paid there is refused (422 branch_does_not_sell). A Store must keep "
                                    + "goods (422 store_must_stock). Refused with branch_has_open_credit_sales while a credit sale made there "
                                    + "still has a balance owing.")
            Boolean sellsGoods) {}

    @Schema(
            name = "CloseBranchRequest",
            description = "The reason is required only when the branch still holds stock "
                    + "or has transfers on their way (422 branch_close_needs_reason says which).")
    record CloseBranchRequest(@Size(max = 500) String reason) {}

    @Schema(name = "Branch")
    record BranchResponse(
            UUID id,
            String code,
            String name,
            String location,
            boolean isHeadOffice,

            @Schema(description = "active, or inactive for a closed branch (the owner's word is closed, ADR-041)")
            String status,

            @Schema(description = "false for a branch that keeps no goods, such as a head office (ADR-041)")
            boolean stocksGoods,

            @Schema(description = "false for a Store: it holds stock and takes transfers but does not sell (ADR-048)")
            boolean sellsGoods,

            @Schema(description = "when the branch was closed; null while it is open")
            Instant closedAt,

            @Schema(description = "why it was closed, when it had to be confirmed; null otherwise")
            String closedReason,

            @Schema(description = "items still on the shelves of a closed branch; null for an open branch or when none")
            Integer stockItems,

            @Schema(description = "their value at current cost, in minor units; null for an open branch or when none")
            Long stockValueMinor,

            Instant createdAt,
            Instant updatedAt,
            int version) {}

    @Schema(name = "BranchList")
    record BranchList(List<BranchResponse> items) {}

    @GetMapping
    @RequiresPermission("core.branches.read")
    @Operation(summary = "Branches in the caller's scope (FR-BR-04)", operationId = "listBranches")
    BranchList list() {
        return new BranchList(service.list());
    }

    @PostMapping
    @RequiresPermission("core.branches.manage")
    @Operation(summary = "Create a branch (FR-BR-01, FR-TEN-04)", operationId = "createBranch")
    ResponseEntity<BranchResponse> create(@Valid @RequestBody CreateBranchRequest request) {
        BranchResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/branches/" + created.id()))
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @GetMapping("/{branch_id}")
    @RequiresPermission("core.branches.read")
    @Operation(summary = "Get one branch", operationId = "getBranch")
    ResponseEntity<BranchResponse> get(@PathVariable("branch_id") UUID branchId) {
        BranchResponse branch = service.get(branchId);
        return ResponseEntity.ok().eTag(String.valueOf(branch.version())).body(branch);
    }

    @PatchMapping("/{branch_id}")
    @RequiresPermission("core.branches.manage")
    @Operation(
            summary = "Rename a branch, change its location or mark it as not stocking (FR-BR-01, ADR-041)",
            operationId = "updateBranch")
    ResponseEntity<BranchResponse> update(
            @PathVariable("branch_id") UUID branchId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateBranchRequest request) {
        BranchResponse branch = service.update(branchId, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(branch.version())).body(branch);
    }

    @PostMapping("/{branch_id}/close")
    @RequiresPermission("core.branches.manage")
    @Operation(
            summary = "Close a branch and keep its history (FR-BR-01, ADR-041); 422 branch_close_needs_reason while "
                    + "stock or transfers on their way remain and no reason is sent",
            operationId = "closeBranch")
    ResponseEntity<BranchResponse> close(
            @PathVariable("branch_id") UUID branchId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody(required = false) CloseBranchRequest request) {
        BranchResponse branch = service.close(branchId, ifMatch, request == null ? null : request.reason());
        return ResponseEntity.ok().eTag(String.valueOf(branch.version())).body(branch);
    }

    @PostMapping("/{branch_id}/reopen")
    @RequiresPermission("core.branches.manage")
    @Operation(summary = "Reopen a closed branch (FR-BR-01, ADR-041)", operationId = "reopenBranch")
    ResponseEntity<BranchResponse> reopen(
            @PathVariable("branch_id") UUID branchId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        BranchResponse branch = service.reopen(branchId, ifMatch);
        return ResponseEntity.ok().eTag(String.valueOf(branch.version())).body(branch);
    }
}
