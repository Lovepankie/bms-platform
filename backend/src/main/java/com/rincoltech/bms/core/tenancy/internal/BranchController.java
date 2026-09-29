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
            @Size(max = 200) String location) {}

    @Schema(name = "UpdateBranchRequest", description = "Omitted fields are unchanged. The code never changes.")
    record UpdateBranchRequest(
            @Size(min = 1, max = 200) String name,
            @Size(max = 200) String location) {}

    @Schema(name = "Branch")
    record BranchResponse(
            UUID id,
            String code,
            String name,
            String location,
            boolean isHeadOffice,
            String status,
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
    @Operation(summary = "Rename a branch or change its location (FR-BR-01)", operationId = "updateBranch")
    ResponseEntity<BranchResponse> update(
            @PathVariable("branch_id") UUID branchId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateBranchRequest request) {
        BranchResponse branch = service.update(branchId, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(branch.version())).body(branch);
    }

    @PostMapping("/{branch_id}/deactivate")
    @RequiresPermission("core.branches.manage")
    @Operation(summary = "Deactivate a branch (FR-BR-01)", operationId = "deactivateBranch")
    BranchResponse deactivate(@PathVariable("branch_id") UUID branchId) {
        return service.deactivate(branchId);
    }
}
