package com.rincoltech.bms.retail.stock.internal;

import com.rincoltech.bms.core.approvals.ApprovalWaiting;
import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.stock.internal.StockApi.AllBranchesPage;
import com.rincoltech.bms.retail.stock.internal.StockApi.Correction;
import com.rincoltech.bms.retail.stock.internal.StockApi.CorrectionRequest;
import com.rincoltech.bms.retail.stock.internal.StockApi.MovementPage;
import com.rincoltech.bms.retail.stock.internal.StockApi.QuickAdd;
import com.rincoltech.bms.retail.stock.internal.StockApi.QuickAddRequest;
import com.rincoltech.bms.retail.stock.internal.StockApi.StockPage;
import com.rincoltech.bms.retail.stock.internal.StockApi.Stocktake;
import com.rincoltech.bms.retail.stock.internal.StockApi.StocktakeCommitRequest;
import com.rincoltech.bms.retail.stock.internal.StockApi.StocktakeRequest;
import com.rincoltech.bms.retail.stock.internal.StockSummaryApi.Summary;
import com.rincoltech.bms.retail.stock.internal.UsageApi.UsageReport;
import com.rincoltech.bms.retail.stock.internal.UsageApi.UsageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
    private final StockCorrectionService corrections;
    private final StockSummaryService summaries;
    private final QuickAddService quickAdd;

    StockController(
            StockService service,
            UsageService usage,
            StockCorrectionService corrections,
            StockSummaryService summaries,
            QuickAddService quickAdd) {
        this.service = service;
        this.usage = usage;
        this.corrections = corrections;
        this.summaries = summaries;
        this.quickAdd = quickAdd;
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
            @RequestParam(name = "include_inactive", required = false, defaultValue = "false") boolean includeInactive,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.stock(branchId, query, categoryId, negativeOnly, level, includeInactive, limit, cursor);
    }

    @GetMapping("/stock/all-branches")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary =
                    "Every product with its balance in each open branch the caller may read (#144); closed branches only with include_closed (#329)",
            operationId = "listRetailStockAllBranches")
    AllBranchesPage allBranches(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "category_id", required = false) UUID categoryId,
            @RequestParam(name = "negative_only", required = false, defaultValue = "false") boolean negativeOnly,
            @RequestParam(name = "stock_level", required = false) String level,
            @RequestParam(name = "include_closed", required = false, defaultValue = "false") boolean includeClosed,
            @RequestParam(name = "include_inactive", required = false, defaultValue = "false") boolean includeInactive,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.allBranches(
                query, categoryId, negativeOnly, level, includeClosed, includeInactive, limit, cursor);
    }

    // JUSTIFICATION-A3: a new read route is one handler; the grouped query lives in its own service (issue #350).
    @GetMapping("/stock/summary")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary = "Stock grouped by shop and category with the number of items and their value (issue #350)",
            operationId = "summariseRetailStock")
    Summary summary(
            @RequestParam(name = "group_by") String groupBy,
            @RequestParam(name = "level", required = false) String level,
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "category_id", required = false) UUID categoryId,
            @RequestParam(name = "negative_only", required = false, defaultValue = "false") boolean negativeOnly,
            @RequestParam(name = "stock_level", required = false) String stockLevel,
            @RequestParam(name = "include_closed", required = false, defaultValue = "false") boolean includeClosed,
            @RequestParam(name = "include_inactive", required = false, defaultValue = "false") boolean includeInactive,
            @RequestParam(name = "offset", required = false) Integer offset) {
        return summaries.summary(new StockSummaryService.Query(
                groupBy,
                level,
                branchIds,
                query,
                categoryId,
                negativeOnly,
                stockLevel,
                includeClosed,
                includeInactive,
                offset));
    }

    @GetMapping("/stock/movements")
    @RequiresPermission("retail.stock.read")
    @Operation(summary = "Stock movements in the caller's branch scope", operationId = "listRetailStockMovements")
    MovementPage movements(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "product_id", required = false) UUID productId,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "newest_first", required = false, defaultValue = "false") boolean newestFirst,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.movements(branchIds, productId, from, to, newestFirst, limit, cursor);
    }

    @PostMapping("/stock/corrections")
    @RequiresPermission("retail.stocktake.commit")
    @Operation(
            summary =
                    "Correct the quantity of an item at a branch with a reason; an adjustment that is not shrinkage (issue #351); M",
            operationId = "correctRetailStockQuantity")
    @ApiResponse(
            responseCode = "201",
            description = "The correction, made",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = Correction.class)))
    @ApiResponse(
            responseCode = "202",
            description =
                    "Stock corrections ask for approval and the value of the difference reaches the amount (ADR-091): the"
                            + " correction waits for an administrator and the quantity is unchanged",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApprovalWaiting.class)))
    ResponseEntity<?> correct(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CorrectionRequest request) {
        Outcome<StockCorrectionService.CorrectionOutcome> outcome = corrections.correct(idempotencyKey, request);
        StockCorrectionService.CorrectionOutcome body = outcome.body();
        ResponseEntity.BodyBuilder response =
                ResponseEntity.status(body.waiting() != null ? HttpStatus.ACCEPTED : HttpStatus.CREATED);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return body.waiting() != null ? response.body(body.waiting()) : response.body(body.correction());
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

    @PostMapping("/quick-add")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary =
                    "Quick add: a new item (or one already in the list) and stock added at a branch as opening stock, in one"
                            + " transaction; the stock also needs retail.stocktake.commit there (issue #632, ADR-101); M",
            operationId = "quickAddRetailItem")
    @ApiResponse(
            responseCode = "201",
            description = "The item, and the stock added when some was",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = QuickAdd.class)))
    ResponseEntity<QuickAdd> quickAdd(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody QuickAddRequest request) {
        Outcome<QuickAdd> outcome = quickAdd.add(idempotencyKey, request);
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
            summary = "Write the adjustment movements and post the variance (FR-RET-08); every big drop must be"
                    + " acknowledged (issue #636)",
            operationId = "commitRetailStocktake")
    Stocktake commit(
            @PathVariable("stocktake_id") UUID id,
            @Valid @RequestBody(required = false) StocktakeCommitRequest request) {
        return service.commit(
                id, request == null || request.acknowledged() == null ? List.of() : request.acknowledged());
    }

    @PostMapping("/stocktakes/{stocktake_id}/discard")
    @RequiresPermission("retail.stocktake.commit")
    @Operation(
            summary = "End a draft count without writing stock; idempotent, a committed count is refused (issue #656)",
            operationId = "discardRetailStocktake")
    Stocktake discard(@PathVariable("stocktake_id") UUID id) {
        return service.discard(id);
    }
}
