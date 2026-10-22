package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Barcode;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.BarcodeList;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.BarcodeMatch;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.BarcodeRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/retail} barcode routes (ADR-070, issue #345; FR-RET-68; chapter 7 section 7.11.20). */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-catalogue")
class BarcodeController {

    private final BarcodeService service;

    BarcodeController(BarcodeService service) {
        this.service = service;
    }

    @Schema(name = "RetailBarcodeGenerateRequest", description = "Issue #345: the items to make a label code for")
    record GenerateRequest(
            @NotNull @Size(min = 1, max = BarcodeService.MAX_GENERATE)
            List<@NotNull UUID> productIds) {}

    @GetMapping("/products/by-barcode/{code}")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary = "The active item a scanned barcode belongs to; with branch_id, with that branch's balance (#345)",
            operationId = "findRetailProductByBarcode")
    BarcodeMatch byBarcode(
            @PathVariable("code") String code, @RequestParam(name = "branch_id", required = false) UUID branchId) {
        return service.lookup(code, branchId);
    }

    @GetMapping("/products/{product_id}/barcodes")
    @RequiresPermission("retail.stock.read")
    @Operation(summary = "The barcodes of an item (#345)", operationId = "listRetailProductBarcodes")
    BarcodeList list(@PathVariable("product_id") UUID productId) {
        return service.list(productId);
    }

    @PostMapping("/products/{product_id}/barcodes")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Add a barcode to an item or one of its pack sizes; check digits are checked (#345)",
            operationId = "addRetailProductBarcode")
    ResponseEntity<Barcode> add(
            @PathVariable("product_id") UUID productId, @Valid @RequestBody BarcodeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.add(productId, request));
    }

    @DeleteMapping("/products/{product_id}/barcodes/{barcode_id}")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(summary = "Remove a barcode from an item (#345)", operationId = "removeRetailProductBarcode")
    ResponseEntity<Void> remove(
            @PathVariable("product_id") UUID productId, @PathVariable("barcode_id") UUID barcodeId) {
        service.remove(productId, barcodeId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/products/barcodes/generate")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Make a Code 128 label code for each item that has no made code yet; idempotent (#345)",
            operationId = "generateRetailBarcodes")
    BarcodeList generate(@Valid @RequestBody GenerateRequest request) {
        return service.generate(request.productIds());
    }
}
