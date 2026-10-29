package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.stock.internal.BranchRoleService.Item;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/retail/branch-roles} (ADR-033; capability {@code branch_roles}). */
@RestController
@RequestMapping(path = "/api/v1/retail/branch-roles", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-stock")
class BranchRoleController {

    private final BranchRoleService service;

    BranchRoleController(BranchRoleService service) {
        this.service = service;
    }

    @Schema(name = "RetailBranchRole")
    record BranchRoleItem(UUID branchId, String code, String name, String role, boolean canSell) {
        static BranchRoleItem of(Item i) {
            return new BranchRoleItem(i.branchId(), i.code(), i.name(), i.role(), i.canSell());
        }
    }

    @Schema(name = "RetailBranchRoleList")
    record BranchRoleList(List<BranchRoleItem> items) {}

    @Schema(name = "SetRetailBranchRoleRequest")
    record SetRoleRequest(
            @NotBlank @Schema(allowableValues = {"shop", "both"})
            String role) {}

    @GetMapping
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary =
                    "The active branches in the caller's stock scope with their roles; 404 capability_not_enabled when off",
            operationId = "listRetailBranchRoles")
    BranchRoleList list() {
        return new BranchRoleList(
                service.list().stream().map(BranchRoleItem::of).toList());
    }

    @PutMapping(path = "/{branch_id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(BranchRoleService.PERMISSION)
    @Operation(
            summary =
                    "Set a selling branch's role (shop or both; a store is sells_goods false on PATCH /branches/{id}); audited;"
                            + " 422 branch_is_store for a store, branch_has_open_work while a transfer is unreceived",
            operationId = "setRetailBranchRole")
    BranchRoleItem set(@PathVariable("branch_id") UUID branchId, @Valid @RequestBody SetRoleRequest request) {
        return BranchRoleItem.of(service.change(branchId, request.role()));
    }
}
