package com.rincoltech.bms.lending.products.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.products.internal.ProductApi.CreateProductRequest;
import com.rincoltech.bms.lending.products.internal.ProductApi.Preview;
import com.rincoltech.bms.lending.products.internal.ProductApi.PreviewRequest;
import com.rincoltech.bms.lending.products.internal.ProductApi.Product;
import com.rincoltech.bms.lending.products.internal.ProductApi.ProductList;
import com.rincoltech.bms.lending.products.internal.ProductApi.Terms;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/lending/loan-products} (chapter 7 section 7.11.12; FR-PRD-01 to FR-PRD-05). */
@RestController
@RequestMapping(path = "/api/v1/lending/loan-products", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-products")
class ProductController {

    private final ProductService service;

    ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("lending.products.read")
    @Operation(summary = "List loan products with their current version", operationId = "listLoanProducts")
    ProductList list() {
        return service.list();
    }

    @PostMapping
    @RequiresPermission("lending.products.manage")
    @Operation(summary = "Create a loan product and its version 1 (FR-PRD-01)", operationId = "createLoanProduct")
    ResponseEntity<Product> create(@Valid @RequestBody CreateProductRequest request) {
        Product created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/lending/loan-products/" + created.id()))
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @GetMapping("/{product_id}")
    @RequiresPermission("lending.products.read")
    @Operation(summary = "A product with its current version and history", operationId = "getLoanProduct")
    ResponseEntity<Product> get(@PathVariable("product_id") UUID id) {
        return withETag(service.get(id));
    }

    @PostMapping("/{product_id}/versions")
    @RequiresPermission("lending.products.manage")
    @Operation(
            summary = "Edit a product by writing a new version (FR-PRD-04)",
            operationId = "createLoanProductVersion")
    ResponseEntity<Product> newVersion(
            @PathVariable("product_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody Terms terms) {
        return withETag(service.newVersion(id, ifMatch, terms));
    }

    @PostMapping("/{product_id}/archive")
    @RequiresPermission("lending.products.manage")
    @Operation(summary = "Archive a product (FR-PRD-05)", operationId = "archiveLoanProduct")
    ResponseEntity<Product> archive(
            @PathVariable("product_id") UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return withETag(service.archive(id, ifMatch));
    }

    @PostMapping("/schedule-preview")
    @RequiresPermission("lending.products.read")
    @Operation(summary = "Preview a schedule for terms, saved or not (FR-PRD-03)", operationId = "previewLoanSchedule")
    Preview preview(@Valid @RequestBody PreviewRequest request) {
        return service.preview(request);
    }

    private static ResponseEntity<Product> withETag(Product p) {
        return ResponseEntity.ok().eTag(String.valueOf(p.version())).body(p);
    }
}
