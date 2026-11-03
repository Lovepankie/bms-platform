package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.stock.internal.ItemPageService.ItemStock;
import com.rincoltech.bms.retail.stock.internal.StockApi.MovementPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The item page routes (issue #813, ADR-121, chapter 7 section 7.11.20). */
@RestController
@RequestMapping(path = "/api/v1/retail/stock/items/{product_id}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-stock")
class ItemPageController {

    private final ItemPageService service;

    ItemPageController(ItemPageService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary = "One item's stock in each shop the caller may read (issue #813)",
            operationId = "getRetailItemStock")
    ItemStock item(@PathVariable("product_id") UUID productId) {
        return service.item(productId);
    }

    @GetMapping("/movements")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary = "Every movement of one item newest first, with the shop's balance after each (issue #813); a"
                    + " branch outside the caller's scope is 404",
            operationId = "listRetailItemMovements")
    MovementPage movements(
            @PathVariable("product_id") UUID productId,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.movements(productId, branchIds, from, to, limit, cursor);
    }
}
