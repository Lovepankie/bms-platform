package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.AddVariantsRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.CreateFamilyRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Family;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.JoinRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.MakeFamilyRequest;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.UpdateFamilyRequest;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/retail} routes of items with sizes and colours (issue #384, ADR-051; chapter 7 section 7.11.20;
 * FR-RET-51). A variant is a product, so its prices, packs, stock and history keep the product routes.
 */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-catalogue")
class FamilyController {

    private final FamilyService service;

    FamilyController(FamilyService service) {
        this.service = service;
    }

    @PostMapping("/product-families")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Create an item with sizes and colours: every combination becomes a variant (issue #384)",
            operationId = "createRetailProductFamily")
    ResponseEntity<Family> create(@Valid @RequestBody CreateFamilyRequest request) {
        Family created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/retail/product-families/" + created.id()))
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @GetMapping("/product-families/{family_id}")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary =
                    "An item with sizes and colours, its variants and its stock over the caller's branches (issue #384)",
            operationId = "getRetailProductFamily")
    ResponseEntity<Family> get(@PathVariable("family_id") UUID id) {
        Family f = service.get(id);
        return ResponseEntity.ok().eTag(String.valueOf(f.version())).body(f);
    }

    @PatchMapping("/product-families/{family_id}")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Rename an item with sizes and colours or change its category, under If-Match (issue #384)",
            operationId = "updateRetailProductFamily")
    ResponseEntity<Family> update(
            @PathVariable("family_id") UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateFamilyRequest request) {
        Family f = service.update(id, ifMatch, request);
        return ResponseEntity.ok().eTag(String.valueOf(f.version())).body(f);
    }

    @PostMapping("/product-families/{family_id}/variants")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Add sizes or colours to an item; a combination it has is skipped (issue #384)",
            operationId = "addRetailProductVariants")
    ResponseEntity<Family> addVariants(
            @PathVariable("family_id") UUID id, @Valid @RequestBody AddVariantsRequest request) {
        Family f = service.addVariants(id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(String.valueOf(f.version()))
                .body(f);
    }

    // New routes of the join action (issue #384 review): a new item from items kept apart, or more sizes for one.
    @PostMapping("/product-families/join")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary =
                    "Make items kept apart (one per size) the sizes and colours of one new item; each keeps its stock and"
                            + " history (issue #384)",
            operationId = "joinRetailProductsAsFamily")
    ResponseEntity<Family> join(@Valid @RequestBody JoinRequest request) {
        Family created = service.join(null, request);
        return ResponseEntity.created(URI.create("/api/v1/retail/product-families/" + created.id()))
                .eTag(String.valueOf(created.version()))
                .body(created);
    }

    @PostMapping("/product-families/{family_id}/join")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary =
                    "Add items kept apart to an item as more sizes and colours; each keeps its stock and history (issue #384)",
            operationId = "joinRetailProductsToFamily")
    ResponseEntity<Family> joinInto(@PathVariable("family_id") UUID id, @Valid @RequestBody JoinRequest request) {
        Family f = service.join(id, request);
        return ResponseEntity.ok().eTag(String.valueOf(f.version())).body(f);
    }

    @PostMapping("/products/{product_id}/family")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary =
                    "Give an item without variants sizes and colours; it keeps its stock and history as one of them (issue #384)",
            operationId = "makeRetailProductFamily")
    ResponseEntity<Family> makeFamily(
            @PathVariable("product_id") UUID id, @Valid @RequestBody MakeFamilyRequest request) {
        Family created = service.makeFamily(id, request);
        return ResponseEntity.created(URI.create("/api/v1/retail/product-families/" + created.id()))
                .eTag(String.valueOf(created.version()))
                .body(created);
    }
}
