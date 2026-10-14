package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsApi.Receipt;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsApi.ReceiptList;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsApi.ReceiptRequest;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsApi.ReleaseRequest;
import com.rincoltech.bms.retail.purchasing.internal.ReceiptsApi.VoidRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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

/**
 * {@code /api/v1/retail/goods-receipts} (purchasing capability; ADR-033, issue #198; chapter 7 section 7.11.20). Each answers
 * 404 {@code capability_not_enabled} while the capability is off.
 */
@RestController
@RequestMapping(path = "/api/v1/retail/goods-receipts", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-purchasing")
class ReceiptsController {

    private final ReceiptsService service;

    ReceiptsController(ReceiptsService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresPermission("retail.purchase.create")
    @Operation(
            summary =
                    "Receive goods into a branch or store, against an order or blind: stock, cost, price and goods received"
                            + " not invoiced in one transaction (issue #198); M",
            operationId = "createRetailGoodsReceipt")
    ResponseEntity<Receipt> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReceiptRequest request) {
        Outcome<Receipt> outcome = service.create(idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(
                URI.create("/api/v1/retail/goods-receipts/" + outcome.body().id()));
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @GetMapping
    @RequiresPermission("retail.purchase.create")
    @Operation(
            summary = "Goods received notes in the caller's branch scope, newest first, without lines (issue #198)",
            operationId = "listRetailGoodsReceipts")
    ReceiptList list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "supplier_id", required = false) UUID supplierId,
            @RequestParam(name = "order_id", required = false) UUID orderId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(branchIds, supplierId, orderId, status, from, to, q, limit, cursor);
    }

    @GetMapping("/{receipt_id}")
    @RequiresPermission("retail.purchase.create")
    @Operation(summary = "One goods received note with its lines (issue #198)", operationId = "getRetailGoodsReceipt")
    Receipt get(@PathVariable("receipt_id") UUID id) {
        return service.get(id);
    }

    @PostMapping("/{receipt_id}/void")
    @RequiresPermission("retail.purchase.create")
    @Operation(
            summary =
                    "Void a receipt as a whole by reversal, once; refused while an invoice bills it or its stock has moved (issue #198); M",
            operationId = "voidRetailGoodsReceipt")
    ResponseEntity<Receipt> voidReceipt(
            @PathVariable("receipt_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody VoidRequest request) {
        Outcome<Receipt> outcome = service.voidReceipt(idempotencyKey, id, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @PostMapping("/{receipt_id}/release-hold")
    @RequiresPermission("retail.payable.manage")
    @Operation(
            summary =
                    "Release a receipt held as received and not costed without a landed cost, with a reason (issue #199); M",
            operationId = "releaseRetailGoodsReceiptHold")
    ResponseEntity<Receipt> releaseHold(
            @PathVariable("receipt_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReleaseRequest request) {
        Outcome<Receipt> outcome = service.releaseHold(idempotencyKey, id, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }
}
