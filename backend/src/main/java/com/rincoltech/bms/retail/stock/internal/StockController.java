package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.stock.internal.StockApi.AllBranchesPage;
import com.rincoltech.bms.retail.stock.internal.StockApi.MovementPage;
import com.rincoltech.bms.retail.stock.internal.StockApi.StockPage;
import com.rincoltech.bms.retail.stock.internal.StockApi.Stocktake;
import com.rincoltech.bms.retail.stock.internal.StockApi.StocktakeRequest;
import com.rincoltech.bms.retail.stock.internal.UsageApi.UsageReport;
import com.rincoltech.bms.retail.stock.internal.UsageApi.UsageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/retail} stock routes (chapter 7 section 7.11.20; FR-RET-03, FR-RET-08). */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-stock")
class StockController {

    private final StockService service;
    private final UsageService usage;

    StockController(StockService service, UsageService usage) {
        this.service = service;
        this.usage = usage;
    }

    @GetMapping("/stock")
    @RequiresPermission("retail.stock.read")
    @Operation(summary = "A branch's stock balances, negatives flagged (FR-RET-03)", operationId = "listRetailStock")
    StockPage stock(
            @RequestParam(name = "branch_id", required = false) UUID branchId,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "category_id", required = false) UUID categoryId,
            @RequestParam(name = "negative_only", required = false, defaultValue = "false") boolean negativeOnly,
            @RequestParam(name = "stock_level", required = false) String level,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.stock(branchId, query, categoryId, negativeOnly, level, limit, cursor);
    }

    @GetMapping("/stock/all-branches")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary = "Every product with its balance in each branch the caller may read (#144)",
            operationId = "listRetailStockAllBranches")
    AllBranchesPage allBranches(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "category_id", required = false) UUID categoryId,
            @RequestParam(name = "negative_only", required = false, defaultValue = "false") boolean negativeOnly,
            @RequestParam(name = "stock_level", required = false) String level,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.allBranches(query, categoryId, negativeOnly, level, limit, cursor);
    }

    @GetMapping("/stock/movements")
    @RequiresPermission("retail.stock.read")
    @Operation(summary = "Stock movements in the caller's branch scope", operationId = "listRetailStockMovements")
    MovementPage movements(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "product_id", required = false) UUID productId,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.movements(branchIds, productId, from, to, limit, cursor);
    }

    @PostMapping("/usage")
    @RequiresPermission("retail.usage.report")
    @Operation(
            summary = "Report stock used or damaged, valued at cost (FR-RET-07); M",
            operationId = "reportRetailUsage")
    ResponseEntity<UsageReport> usage(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody UsageRequest request) {
        Outcome<UsageReport> outcome = usage.report(idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @PostMapping("/stocktakes")
    @RequiresPermission("retail.stocktake.commit")
    @Operation(
            summary = "Record a count; returns the variance per line (FR-RET-08)",
            operationId = "createRetailStocktake")
    ResponseEntity<Stocktake> create(@Valid @RequestBody StocktakeRequest request) {
        Stocktake s = service.createStocktake(request);
        return ResponseEntity.created(URI.create("/api/v1/retail/stocktakes/" + s.id()))
                .body(s);
    }

    @GetMapping("/stocktakes/{stocktake_id}")
    @RequiresPermission("retail.stock.read")
    @Operation(summary = "A stock-take, draft or committed", operationId = "getRetailStocktake")
    Stocktake get(@PathVariable("stocktake_id") UUID id) {
        return service.stocktake(id);
    }

    @PostMapping("/stocktakes/{stocktake_id}/commit")
    @RequiresPermission("retail.stocktake.commit")
    @Operation(
            summary = "Write the adjustment movements and post the variance (FR-RET-08)",
            operationId = "commitRetailStocktake")
    Stocktake commit(@PathVariable("stocktake_id") UUID id) {
        return service.commit(id);
    }
}
