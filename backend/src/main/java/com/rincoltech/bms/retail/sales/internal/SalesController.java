package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Customer;
import com.rincoltech.bms.retail.sales.internal.SalesApi.CustomerBalance;
import com.rincoltech.bms.retail.sales.internal.SalesApi.CustomerList;
import com.rincoltech.bms.retail.sales.internal.SalesApi.CustomerRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.PaymentList;
import com.rincoltech.bms.retail.sales.internal.SalesApi.PaymentRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.PaymentResult;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Sale;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SalePage;
import com.rincoltech.bms.retail.sales.internal.SalesApi.SaleRequest;
import com.rincoltech.bms.retail.sales.internal.SalesApi.VoidRequest;
import com.rincoltech.bms.retail.stock.RetailIdempotency.Outcome;
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

/** {@code /api/v1/retail} sales and customer routes (chapter 7 section 7.11.20; FR-RET-04, FR-RET-05). */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-sales")
class SalesController {

    private final SalesService service;

    SalesController(SalesService service) {
        this.service = service;
    }

    @PostMapping("/sales")
    @RequiresPermission("retail.sale.create")
    @Operation(
            summary = "Record a sale: snapshots, stock and journals in one transaction (FR-RET-04); M",
            operationId = "createRetailSale")
    ResponseEntity<Sale> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SaleRequest request) {
        Outcome<Sale> outcome = service.create(idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(
                URI.create("/api/v1/retail/sales/" + outcome.body().id()));
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @GetMapping("/sales")
    @RequiresPermission("retail.sale.read")
    @Operation(summary = "Sales in the caller's branch scope", operationId = "listRetailSales")
    SalePage list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "customer_id", required = false) UUID customerId,
            @RequestParam(name = "payment_method", required = false) String paymentMethod,
            @RequestParam(name = "product_id", required = false) UUID productId,
            @RequestParam(name = "buyer", required = false) String buyer,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "owing", required = false) String owing,
            @RequestParam(name = "newest_first", required = false, defaultValue = "false") boolean newestFirst,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(
                branchIds,
                from,
                to,
                customerId,
                paymentMethod,
                productId,
                buyer,
                status,
                owing,
                newestFirst,
                limit,
                cursor);
    }

    @GetMapping("/sales/{sale_id}")
    @RequiresPermission("retail.sale.read")
    @Operation(summary = "A sale with its lines", operationId = "getRetailSale")
    Sale get(@PathVariable("sale_id") UUID id) {
        return service.get(id);
    }

    @PostMapping("/sales/{sale_id}/void")
    @RequiresPermission("retail.sale.void")
    @Operation(summary = "Void a sale by reversal (FR-RET-04)", operationId = "voidRetailSale")
    Sale voidSale(@PathVariable("sale_id") UUID id, @Valid @RequestBody VoidRequest request) {
        return service.voidSale(id, request);
    }

    @PostMapping("/sales/{sale_id}/payments")
    @RequiresPermission("retail.sale.create")
    @Operation(
            summary = "Record a payment against a credit sale, partial allowed (FR-RET-05); M",
            operationId = "payRetailSale")
    ResponseEntity<PaymentResult> pay(
            @PathVariable("sale_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        Outcome<PaymentResult> outcome = service.pay(idempotencyKey, id, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @GetMapping("/sales/{sale_id}/payments")
    @RequiresPermission("retail.sale.read")
    @Operation(summary = "Payments against a sale", operationId = "listRetailSalePayments")
    PaymentList payments(@PathVariable("sale_id") UUID id) {
        return service.payments(id);
    }

    @GetMapping("/customers")
    @RequiresPermission("retail.sale.read")
    @Operation(summary = "Credit buyers", operationId = "listRetailCustomers")
    CustomerList customers(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return service.customers(query, limit);
    }

    @PostMapping("/customers")
    @RequiresPermission("retail.customer.manage")
    @Operation(summary = "Add a credit buyer (FR-RET-05)", operationId = "createRetailCustomer")
    ResponseEntity<Customer> createCustomer(@Valid @RequestBody CustomerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCustomer(request));
    }

    @GetMapping("/customers/{customer_id}/balance")
    @RequiresPermission("retail.sale.read")
    @Operation(summary = "What a credit buyer owes (FR-RET-05)", operationId = "getRetailCustomerBalance")
    CustomerBalance balance(@PathVariable("customer_id") UUID id) {
        return service.balance(id);
    }
}
