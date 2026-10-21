package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.stock.internal.StockMinimumService.SetRequest;
import com.rincoltech.bms.retail.stock.internal.StockMinimumService.View;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/retail/products/{product_id}/stock-minimum}: the low-stock level of an item (issue #412, ADR-057). */
@RestController
@RequestMapping(
        path = "/api/v1/retail/products/{product_id}/stock-minimum",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-stock")
class StockMinimumController {

    private final StockMinimumService service;

    StockMinimumController(StockMinimumService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary =
                    "Warn me when stock is below: the item's level for every branch and each branch's own (issue #412)",
            operationId = "getRetailStockMinimum")
    View get(@PathVariable("product_id") UUID productId) {
        return service.get(productId);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Set or remove the item's low-stock level for every shop and store (issue #412)",
            operationId = "setRetailStockMinimum")
    View set(@PathVariable("product_id") UUID productId, @Valid @RequestBody SetRequest request) {
        return service.setEveryBranch(productId, request);
    }

    @PutMapping(path = "/branches/{branch_id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary =
                    "Set or remove one branch's own low-stock level; retail.catalogue.manage or retail.stocktake.commit at that branch (issue #412)",
            operationId = "setRetailBranchStockMinimum")
    View setBranch(
            @PathVariable("product_id") UUID productId,
            @PathVariable("branch_id") UUID branchId,
            @Valid @RequestBody SetRequest request) {
        return service.setBranch(productId, branchId, request);
    }
}
