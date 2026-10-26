package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.catalogue.internal.EntryChecksApi.Confirmation;
import com.rincoltech.bms.retail.catalogue.internal.EntryChecksApi.EntryNorms;
import com.rincoltech.bms.retail.catalogue.internal.EntryChecksApi.NameHints;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/retail} entry warning routes (chapter 7 section 7.11.20; issue #353, ADR-093). Rule based, never a refusal. */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-catalogue")
class EntryChecksController {

    private final EntryChecksService service;

    EntryChecksController(EntryChecksService service) {
        this.service = service;
    }

    @GetMapping("/products/{product_id}/entry-norms")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary =
                    "An item's recent quantities, usual units and last selling price, for the entry warnings (issue #353)",
            operationId = "getRetailEntryNorms")
    EntryNorms norms(@PathVariable("product_id") UUID productId) {
        return service.norms(productId);
    }

    @GetMapping("/products/name-hints")
    @RequiresPermission("retail.catalogue.manage")
    @Operation(
            summary = "Did you mean, and the category of similar items, while a new item's name is typed (issue #353)",
            operationId = "getRetailNameHints")
    NameHints nameHints(@RequestParam(name = "description") String description) {
        return service.nameHints(description);
    }

    @PostMapping("/entry-warnings/confirmations")
    @RequiresPermission("retail.stock.read")
    @Operation(
            summary = "Record that the person confirmed an entry warning and saved anyway (issue #353)",
            operationId = "confirmRetailEntryWarning")
    ResponseEntity<Void> confirm(@Valid @RequestBody Confirmation confirmation) {
        service.confirm(confirmation);
        return ResponseEntity.noContent().build();
    }
}
