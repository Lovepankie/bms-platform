package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Category;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.CategoryList;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.CategoryRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.CreateProductRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceEditRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceHistory;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Product;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.ProductPage;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Unit;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UnitList;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UnitRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UpdateCategoryRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UpdateProductRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UpdateUnitRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
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

/** {@code /api/v1/retail} catalogue routes (chapter 7 section 7.11.20; FR-RET-01, FR-RET-02). */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-catalogue")
class CatalogueController {

    private final CatalogueService service;

    CatalogueController(CatalogueService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    @RequiresPermission("retail.stock.read")
    @Operation(summary = "List product categories", operationId = "listRetailCategories")
    CategoryList categories() {
        return service.categories();
    }

    @PostMapping("/categories")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(summary = "Create a product category (FR-RET-01)", operationId = "createRetailCategory")
    ResponseEntity<Category> createCategory(@Valid @RequestBody CategoryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCategory(request));
    }

    @PatchMapping("/categories/{category_id}")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(summary = "Rename or deactivate a category (#146); never deleted", operationId = "updateRetailCategory")
    Category updateCategory(@PathVariable("category_id") UUID id, @Valid @RequestBody UpdateCategoryRequest request) {
        return service.updateCategory(id, request);
    }

    @PatchMapping("/units/{unit_id}")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Rename or deactivate a unit of measure (#146); never deleted",
            operationId = "updateRetailUnit")
    Unit updateUnit(@PathVariable("unit_id") UUID id, @Valid @RequestBody UpdateUnitRequest request) {
        return service.updateUnit(id, request);
    }

    @GetMapping("/units")
    @RequiresPermission("retail.stock.read")
    @Operation(summary = "List units of measure", operationId = "listRetailUnits")
    UnitList units() {
        return service.units();
    }

    @PostMapping("/units")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(summary = "Create a unit of measure (FR-RET-01)", operationId = "createRetailUnit")
    ResponseEntity<Unit> createUnit(@Valid @RequestBody UnitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createUnit(request));
    }

    @GetMapping("/products")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary = "Search products by code or description; with branch_id each row has that branch's balance",
            operationId = "listRetailProducts")
    ProductPage products(
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "category_id", required = false) UUID categoryId,
            @RequestParam(name = "active", required = false) Boolean active,
            @RequestParam(name = "branch_id", required = false) UUID branchId,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return service.list(query, categoryId, active, branchId, limit, cursor);
    }

    @PostMapping("/products")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(summary = "Create a product with its first prices (FR-RET-01)", operationId = "createRetailProduct")
    ResponseEntity<Product> create(@Valid @RequestBody CreateProductRequest request) {
        Product created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/retail/products/" + created.id()))
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @GetMapping("/products/{product_id}")
    @RequiresPermission("retail.stock.read")
    @Operation(summary = "A product", operationId = "getRetailProduct")
    ResponseEntity<Product> get(@PathVariable("product_id") UUID id) {
        Product p = service.get(id);
        return ResponseEntity.ok().eTag(String.valueOf(p.version())).body(p);
    }

    @PatchMapping("/products/{product_id}")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(summary = "Edit a product's non-price fields", operationId = "updateRetailProduct")
    ResponseEntity<Product> update(
            @PathVariable("product_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateProductRequest request) {
        Product p = service.update(id, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(p.version())).body(p);
    }

    @PostMapping("/products/{product_id}/prices")
    @RequiresPermission("retail.price.edit")
    @Operation(
            summary = "Edit prices by hand under If-Match, with a history row (FR-RET-02)",
            operationId = "editRetailPrices")
    ResponseEntity<Product> editPrices(
            @PathVariable("product_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody PriceEditRequest request) {
        Product p = service.editPrices(id, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(p.version())).body(p);
    }

    @GetMapping("/products/{product_id}/price-history")
    @RequiresPermission("retail.stock.read")
    @Operation(summary = "Every price change of a product (FR-RET-02)", operationId = "getRetailPriceHistory")
    PriceHistory history(@PathVariable("product_id") UUID id) {
        return service.history(id);
    }
}
