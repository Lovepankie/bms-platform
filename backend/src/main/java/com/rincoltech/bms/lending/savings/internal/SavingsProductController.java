package com.rincoltech.bms.lending.savings.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.CreateProductRequest;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.Product;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.ProductList;
import com.rincoltech.bms.lending.savings.internal.SavingsApi.UpdateProductRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/lending/savings-products} (chapter 7 section 7.11.15; FR-SAV-01). */
@RestController
@RequestMapping(path = "/api/v1/lending/savings-products", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-savings")
class SavingsProductController {

    private final SavingsService service;

    SavingsProductController(SavingsService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("lending.savings.read")
    @Operation(summary = "List savings products", operationId = "listSavingsProducts")
    ProductList list() {
        return service.products();
    }

    @PostMapping
    @RequiresPermission("lending.savings_products.manage")
    @Operation(summary = "Create a savings product (FR-SAV-01)", operationId = "createSavingsProduct")
    ResponseEntity<Product> create(@Valid @RequestBody CreateProductRequest request) {
        Product created = service.createProduct(request);
        return ResponseEntity.created(URI.create("/api/v1/lending/savings-products/" + created.id()))
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @GetMapping("/{product_id}")
    @RequiresPermission("lending.savings.read")
    @Operation(summary = "A savings product", operationId = "getSavingsProduct")
    ResponseEntity<Product> get(@PathVariable("product_id") UUID id) {
        Product p = service.product(id);
        return ResponseEntity.ok().eTag(String.valueOf(p.version())).body(p);
    }

    @PutMapping("/{product_id}")
    @RequiresPermission("lending.savings_products.manage")
    @Operation(
            summary = "Update or archive a savings product; its interest terms are locked once an account uses it",
            operationId = "updateSavingsProduct")
    ResponseEntity<Product> update(
            @PathVariable("product_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateProductRequest request) {
        Product p = service.updateProduct(id, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(p.version())).body(p);
    }
}
