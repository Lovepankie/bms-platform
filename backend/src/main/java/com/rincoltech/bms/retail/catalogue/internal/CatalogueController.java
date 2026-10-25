package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Category;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.CategoryList;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.CategoryRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.CreateProductRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.ImportRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.ImportResult;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Pack;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PackList;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PackRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceEditRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.PriceHistory;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Product;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.ProductPage;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.SimilarProducts;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Unit;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UnitList;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UnitRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UpdateCategoryRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UpdatePackRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UpdateProductRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UpdateUnitRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
    private final ProductImportService importer;

    CatalogueController(CatalogueService service, ProductImportService importer) {
        this.service = service;
        this.importer = importer;
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
        Category created = service.createCategory(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @GetMapping("/categories/prefix-suggestion")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "The code start a new category of this name would get, free in the business (issue #493)",
            operationId = "suggestRetailCategoryPrefix")
    CatalogueApi.PrefixSuggestion suggestPrefix(@RequestParam(name = "name") String name) {
        return service.suggestPrefix(name);
    }

    @PatchMapping("/categories/{category_id}")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Rename, change the code start of, or deactivate a category (#146, #493); never deleted",
            operationId = "updateRetailCategory")
    ResponseEntity<Category> updateCategory(
            @PathVariable("category_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateCategoryRequest request) {
        Category c = service.updateCategory(id, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(c.version())).body(c);
    }

    @PatchMapping("/units/{unit_id}")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Rename or deactivate a unit of measure (#146); never deleted",
            operationId = "updateRetailUnit")
    ResponseEntity<Unit> updateUnit(
            @PathVariable("unit_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateUnitRequest request) {
        Unit u = service.updateUnit(id, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(u.version())).body(u);
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
        Unit created = service.createUnit(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(String.valueOf(created.version()))
                .body(created);
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

    @PostMapping("/products/import")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Import items from CSV for a new client: dry run first, then apply; administrators only (#146)",
            operationId = "importRetailProducts")
    ImportResult importProducts(
            @RequestParam(name = "dry_run", required = false, defaultValue = "true") boolean dryRun,
            @Parameter(
                            description =
                                    "Let the app make the codes (owner decision D45, issue #562): every row gets its category's"
                                            + " next code and the file's code column is not read. Off by default: a row's code is kept"
                                            + " and a blank one is made")
                    @RequestParam(name = "make_codes", required = false, defaultValue = "false")
                    boolean makeCodes,
            @Valid @RequestBody ImportRequest request) {
        return importer.run(request.csv(), dryRun, makeCodes);
    }

    @GetMapping("/products/similar")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Products with this name or a close one, before saving a new or renamed item (issue #352)",
            operationId = "findSimilarRetailProducts")
    SimilarProducts similar(
            @RequestParam(name = "description") String description,
            @RequestParam(name = "unit_id", required = false) UUID unitId,
            @RequestParam(name = "exclude_id", required = false) UUID excludeId) {
        return service.similar(description, unitId, excludeId);
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

    // ---- Pack sizes (ADR-033, issue #194): 404 capability_not_enabled while units is off ------------

    @GetMapping("/products/{product_id}/units")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary = "The pack sizes of a product; the units capability (issue #194)",
            operationId = "listRetailProductUnits")
    PackList packs(@PathVariable("product_id") UUID id) {
        return service.packs(id);
    }

    @PostMapping("/products/{product_id}/units")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Add a pack size to a product: a unit and the base units in one pack (issue #194)",
            operationId = "createRetailProductUnit")
    ResponseEntity<Pack> addPack(@PathVariable("product_id") UUID id, @Valid @RequestBody PackRequest request) {
        Pack created = service.addPack(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @PatchMapping("/products/{product_id}/units/{unit_id}")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Change a pack's factor or switch it off under If-Match; never deleted (issue #194)",
            operationId = "updateRetailProductUnit")
    ResponseEntity<Pack> updatePack(
            @PathVariable("product_id") UUID id,
            @PathVariable("unit_id") UUID unitId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdatePackRequest request) {
        Pack k = service.updatePack(id, unitId, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(k.version())).body(k);
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
