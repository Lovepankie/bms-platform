package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.Purchase;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.PurchasePage;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.PurchaseRequest;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.Supplier;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.SupplierList;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.SupplierRequest;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.UpdateSupplierRequest;
import com.rincoltech.bms.retail.stock.RetailIdempotency.Outcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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

/** {@code /api/v1/retail} purchasing routes (chapter 7 section 7.11.20; FR-RET-06). */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-purchasing")
class PurchasingController {

    private final PurchasingService service;

    PurchasingController(PurchasingService service) {
        this.service = service;
    }

    @GetMapping("/suppliers")
    @RequiresPermission("retail.purchase.create")
    @Operation(summary = "Suppliers", operationId = "listRetailSuppliers")
    SupplierList suppliers() {
        return service.suppliers();
    }

    @PostMapping("/suppliers")
    @RequiresPermission("retail.purchase.create")
    @Operation(summary = "Add a supplier (FR-RET-06)", operationId = "createRetailSupplier")
    ResponseEntity<Supplier> createSupplier(@Valid @RequestBody SupplierRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createSupplier(request));
    }

    @PatchMapping("/suppliers/{supplier_id}")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(summary = "Edit or deactivate a supplier (#146); never deleted", operationId = "updateRetailSupplier")
    ResponseEntity<Supplier> updateSupplier(
            @PathVariable("supplier_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateSupplierRequest request) {
        Supplier s = service.updateSupplier(id, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(s.version())).body(s);
    }

    @PostMapping("/purchases")
    @RequiresPermission("retail.purchase.create")
    @Operation(
            summary = "Record a restock: prices, history, movements and journals in one transaction (FR-RET-06); M",
            operationId = "createRetailPurchase")
    ResponseEntity<Purchase> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PurchaseRequest request) {
        Outcome<Purchase> outcome = service.create(idempotencyKey, request);
        ResponseEntity.BodyBuilder response = ResponseEntity.created(
                URI.create("/api/v1/retail/purchases/" + outcome.body().id()));
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.body());
    }

    @GetMapping("/purchases")
    @RequiresPermission("retail.purchase.create")
    @Operation(summary = "Purchases", operationId = "listRetailPurchases")
    PurchasePage list(
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "supplier_id", required = false) UUID supplierId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(from, to, supplierId, limit, cursor);
    }
}
