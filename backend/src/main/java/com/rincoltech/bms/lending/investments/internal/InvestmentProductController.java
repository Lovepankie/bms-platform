package com.rincoltech.bms.lending.investments.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.CreateProductRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Preview;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.PreviewRequest;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.Product;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.ProductList;
import com.rincoltech.bms.lending.investments.internal.InvestmentApi.UpdateProductRequest;
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

/** {@code /api/v1/lending/investment-products} (chapter 7 section 7.11.16; FR-INV-01, FR-INV-08). */
@RestController
@RequestMapping(path = "/api/v1/lending/investment-products", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "lending-investments")
class InvestmentProductController {

    private final InvestmentProductService service;

    InvestmentProductController(InvestmentProductService service) {
        this.service = service;
    }

    @GetMapping
    @RequiresPermission("lending.investments.read")
    @Operation(summary = "List investment products", operationId = "listInvestmentProducts")
    ProductList list() {
        return service.list();
    }

    @PostMapping
    @RequiresPermission("lending.investment_products.manage")
    @Operation(summary = "Create an investment product (FR-INV-01)", operationId = "createInvestmentProduct")
    ResponseEntity<Product> create(@Valid @RequestBody CreateProductRequest request) {
        Product created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/lending/investment-products/" + created.id()))
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @GetMapping("/{product_id}")
    @RequiresPermission("lending.investments.read")
    @Operation(summary = "An investment product", operationId = "getInvestmentProduct")
    ResponseEntity<Product> get(@PathVariable("product_id") UUID id) {
        return withETag(service.get(id));
    }

    @PutMapping("/{product_id}")
    @RequiresPermission("lending.investment_products.manage")
    @Operation(
            summary = "Edit an investment product; investments already opened keep their terms",
            operationId = "updateInvestmentProduct")
    ResponseEntity<Product> update(
            @PathVariable("product_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateProductRequest request) {
        return withETag(service.update(id, ifMatch, request));
    }

    @PostMapping("/{product_id}/archive")
    @RequiresPermission("lending.investment_products.manage")
    @Operation(summary = "Archive an investment product", operationId = "archiveInvestmentProduct")
    ResponseEntity<Product> archive(
            @PathVariable("product_id") UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return withETag(service.archive(id, ifMatch));
    }

    @PostMapping("/return-preview")
    @RequiresPermission("lending.investments.read")
    @Operation(
            summary = "The return schedule an amount and term would get on a product (R-INV-1 to R-INV-4)",
            operationId = "previewInvestmentReturn")
    Preview preview(@Valid @RequestBody PreviewRequest request) {
        return service.preview(request);
    }

    private static ResponseEntity<Product> withETag(Product p) {
        return ResponseEntity.ok().eTag(String.valueOf(p.version())).body(p);
    }
}
