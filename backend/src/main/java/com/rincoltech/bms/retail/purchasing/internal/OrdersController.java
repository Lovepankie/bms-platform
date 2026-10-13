package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.purchasing.internal.OrdersApi.CancelRequest;
import com.rincoltech.bms.retail.purchasing.internal.OrdersApi.Order;
import com.rincoltech.bms.retail.purchasing.internal.OrdersApi.OrderList;
import com.rincoltech.bms.retail.purchasing.internal.OrdersApi.OrderOutcome;
import com.rincoltech.bms.retail.purchasing.internal.OrdersApi.OrderRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/retail/purchase-orders} (purchasing capability; ADR-033, issue #198; chapter 7 section 7.11.20). Each
 * answers 404 {@code capability_not_enabled} while the capability is off.
 */
@RestController
@RequestMapping(path = "/api/v1/retail/purchase-orders", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-purchasing")
class OrdersController {

    private final OrdersService service;

    OrdersController(OrdersService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresPermission("retail.purchase.create")
    @Operation(summary = "Raise a purchase order as a draft (issue #198); M", operationId = "createRetailPurchaseOrder")
    ResponseEntity<Order> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderRequest request) {
        Outcome<Order> outcome = service.create(idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(URI.create(
                        "/api/v1/retail/purchase-orders/" + outcome.body().id()))
                .eTag(String.valueOf(outcome.body().version()));
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @GetMapping
    @RequiresPermission("retail.purchase.create")
    @Operation(
            summary = "Purchase orders in the caller's branch scope, newest first, without lines (issue #198)",
            operationId = "listRetailPurchaseOrders")
    OrderList list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "supplier_id", required = false) UUID supplierId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(branchIds, supplierId, status, from, to, q, limit, cursor);
    }

    @GetMapping("/{order_id}")
    @RequiresPermission("retail.purchase.create")
    @Operation(
            summary = "One purchase order with its lines, what was received and billed, and its receipts (issue #198)",
            operationId = "getRetailPurchaseOrder")
    ResponseEntity<Order> get(@PathVariable("order_id") UUID id) {
        Order o = service.get(id);
        return ResponseEntity.ok().eTag(String.valueOf(o.version())).body(o);
    }

    @PutMapping(path = "/{order_id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("retail.purchase.create")
    @Operation(
            summary =
                    "Replace a draft order's supplier, dates, note and lines under If-Match (issue #198); a sent order is not edited",
            operationId = "updateRetailPurchaseOrder")
    ResponseEntity<Order> update(
            @PathVariable("order_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody OrderRequest request) {
        Order o = service.update(id, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(o.version())).body(o);
    }

    @PostMapping("/{order_id}/send")
    @RequiresPermission("retail.purchase.create")
    @Operation(
            summary =
                    "Send a draft order to the supplier (issue #198); M. 200 when sent, 202 when it waits for a checker",
            operationId = "sendRetailPurchaseOrder")
    ResponseEntity<OrderOutcome> send(
            @PathVariable("order_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        Outcome<OrderOutcome> outcome = service.send(idempotencyKey, id);
        ResponseEntity.BodyBuilder response =
                outcome.body().executed() ? ResponseEntity.ok() : ResponseEntity.status(HttpStatus.ACCEPTED);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @PostMapping("/{order_id}/cancel")
    @RequiresPermission("retail.purchase.create")
    @Operation(
            summary = "Cancel a draft, sent or partly received order, with a reason (issue #198); M",
            operationId = "cancelRetailPurchaseOrder")
    ResponseEntity<Order> cancel(
            @PathVariable("order_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CancelRequest request) {
        Outcome<Order> outcome = service.cancel(idempotencyKey, id, request);
        ResponseEntity.BodyBuilder response =
                ResponseEntity.ok().eTag(String.valueOf(outcome.body().version()));
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }
}
