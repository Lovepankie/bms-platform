package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.Board;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.CancelRequest;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.CancellationsReport;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.ChangeRequest;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.DeliverRequest;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.Delivered;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.FeePaymentRequest;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.FeesReport;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.Lookup;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.Order;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.OrderPage;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.OrderRequest;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.SendRequest;
import com.rincoltech.bms.retail.sales.internal.CustomerOrdersApi.StepRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/retail/orders}: customer orders with delivery (issue #385, ADR-052). Delivering an order also needs
 * {@code retail.sale.create} at its branch, which the sale it makes checks as any sale does.
 */
@RestController
@RequestMapping(path = "/api/v1/retail/orders", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-orders")
class CustomerOrdersController {

    private final CustomerOrdersService service;

    CustomerOrdersController(CustomerOrdersService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresPermission(CustomerOrdersService.MANAGE)
    @Operation(
            summary = "Take a customer order: priced, numbered, its stock kept aside, nothing paid yet (issue #385)",
            operationId = "createRetailOrder")
    ResponseEntity<Order> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        Outcome<Order> outcome = service.create(idempotencyKey, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.created(
                URI.create("/api/v1/retail/orders/" + outcome.body().id()));
        if (outcome.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(outcome.body());
    }

    @GetMapping
    @RequiresPermission(CustomerOrdersService.MANAGE)
    @Operation(
            summary =
                    "Orders in the caller's branch scope, newest first, by step, delivery day and source (issue #385)",
            operationId = "listRetailOrders")
    OrderPage list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "due", required = false) String due,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            HttpServletResponse response) {
        NoStore.apply(response);
        return service.page(branchIds, status, due, source, query, limit, cursor);
    }

    @GetMapping("/board")
    @RequiresPermission(CustomerOrdersService.MANAGE)
    @Operation(
            summary = "How many open orders are at each step, and today's deliveries (issue #385)",
            operationId = "getRetailOrderBoard")
    Board board(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds, HttpServletResponse response) {
        NoStore.apply(response);
        return service.board(branchIds);
    }

    @GetMapping("/customer-lookup")
    @RequiresPermission(CustomerOrdersService.MANAGE)
    @Operation(
            summary = "The customer of a phone number, from earlier orders and the credit buyers, to fill an order in"
                    + " (issue #385)",
            operationId = "lookUpRetailOrderCustomer")
    Lookup lookup(@RequestParam(name = "phone") String phone, HttpServletResponse response) {
        NoStore.apply(response);
        return service.lookup(phone);
    }

    @GetMapping("/reports/delivery-fees")
    @RequiresPermission(CustomerOrdersService.MANAGE)
    @Operation(
            summary = "Delivered orders and the delivery fees taken, by day, source or rider (issue #385)",
            operationId = "getRetailOrderFeesReport",
            description =
                    "ADR-055: it adds up everybody's orders, so the caller also needs retail.sale.all_sellers in every branch it covers, else 403 permission_denied")
    FeesReport fees(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "group_by", required = false) String groupBy,
            HttpServletResponse response) {
        NoStore.apply(response);
        return service.fees(branchIds, from, to, groupBy);
    }

    @GetMapping("/reports/cancellations")
    @RequiresPermission(CustomerOrdersService.MANAGE)
    @Operation(
            summary = "Orders cancelled and why, by source (issue #385)",
            operationId = "getRetailOrderCancellationsReport",
            description =
                    "ADR-055: it adds up everybody's orders, so the caller also needs retail.sale.all_sellers in every branch it covers, else 403 permission_denied")
    CancellationsReport cancellations(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            HttpServletResponse response) {
        NoStore.apply(response);
        return service.cancellations(branchIds, from, to);
    }

    @GetMapping("/{order_id}")
    @RequiresPermission(CustomerOrdersService.MANAGE)
    @Operation(summary = "An order with its lines and its history (issue #385)", operationId = "getRetailOrder")
    ResponseEntity<Order> get(@PathVariable("order_id") UUID id, HttpServletResponse response) {
        NoStore.apply(response);
        Order o = service.get(id);
        return ResponseEntity.ok().eTag(String.valueOf(o.version())).body(o);
    }

    @PatchMapping("/{order_id}")
    @RequiresPermission(CustomerOrdersService.MANAGE)
    @Operation(
            summary = "Change the customer's name, phone, delivery place, day or fee, or the notes, of an open order"
                    + " (issue #385)",
            operationId = "updateRetailOrder")
    ResponseEntity<Order> change(
            @PathVariable("order_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ChangeRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        Order o = service.change(id, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(o.version())).body(o);
    }

    @PostMapping("/{order_id}/pack")
    @RequiresPermission(CustomerOrdersService.MANAGE)
    @Operation(summary = "Mark an order packed (issue #385)", operationId = "packRetailOrder")
    Order pack(
            @PathVariable("order_id") UUID id,
            @Valid @RequestBody(required = false) StepRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        return service.pack(id, request);
    }

    @PostMapping("/{order_id}/send")
    @RequiresPermission(CustomerOrdersService.MANAGE)
    @Operation(
            summary = "Mark an order out for delivery, with who takes it (issue #385)",
            operationId = "sendRetailOrder")
    Order send(
            @PathVariable("order_id") UUID id, @Valid @RequestBody SendRequest request, HttpServletResponse response) {
        NoStore.apply(response);
        return service.send(id, request);
    }

    @PostMapping("/{order_id}/deliver")
    @RequiresPermission(CustomerOrdersService.MANAGE)
    @Operation(
            summary = "Mark an order delivered and paid: it becomes a sale through the ordinary sale path, the delivery"
                    + " fee posted on its own; M (issue #385)",
            operationId = "deliverRetailOrder")
    ResponseEntity<Delivered> deliver(
            @PathVariable("order_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DeliverRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        Outcome<Delivered> outcome = service.deliver(idempotencyKey, id, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (outcome.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(outcome.body());
    }

    @PostMapping("/{order_id}/fee-payment")
    @RequiresPermission(CustomerOrdersService.FEE_PAYMENT)
    @Operation(
            summary = "Take the payment of a delivery fee left on a credit buyer's account; M (issue #385)",
            operationId = "payRetailOrderFee")
    ResponseEntity<Order> payFee(
            @PathVariable("order_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody FeePaymentRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        Outcome<Order> outcome = service.payFee(idempotencyKey, id, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (outcome.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(outcome.body());
    }

    @PostMapping("/{order_id}/cancel")
    @RequiresPermission(CustomerOrdersService.MANAGE)
    @Operation(
            summary = "Cancel an open order with a reason; its stock goes back (issue #385)",
            operationId = "cancelRetailOrder")
    Order cancel(
            @PathVariable("order_id") UUID id,
            @Valid @RequestBody CancelRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        return service.cancel(id, request);
    }
}
