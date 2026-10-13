package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.CancelRequest;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.ConvertRequest;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.Converted;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.Quotation;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.QuotationPage;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.QuotationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
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

/**
 * {@code /api/v1/retail/quotations} (documents capability; ADR-033, issue #197). Converting a quotation also needs
 * {@code retail.sale.create} at its branch, which the sale it makes checks as any sale does.
 */
@RestController
@RequestMapping(path = "/api/v1/retail/quotations", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-documents")
class QuotationsController {

    private final QuotationsService service;

    QuotationsController(QuotationsService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresPermission("retail.document.issue")
    @Operation(
            summary =
                    "Make a quotation: priced like a sale, numbered, its PDF stored; reserves no stock; M (issue #197)",
            operationId = "createRetailQuotation")
    ResponseEntity<Quotation> create(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody QuotationRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        Outcome<Quotation> outcome = service.create(idempotencyKey, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.created(
                URI.create("/api/v1/retail/quotations/" + outcome.body().id()));
        if (outcome.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(outcome.body());
    }

    @GetMapping
    @RequiresPermission("retail.document.issue")
    @Operation(
            summary = "Quotations in the caller's branch scope, newest first (issue #197)",
            operationId = "listRetailQuotations")
    QuotationPage list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            HttpServletResponse response) {
        NoStore.apply(response);
        return service.page(branchIds, status, query, limit, cursor);
    }

    @GetMapping("/{quotation_id}")
    @RequiresPermission("retail.document.issue")
    @Operation(summary = "A quotation with its lines (issue #197)", operationId = "getRetailQuotation")
    Quotation get(@PathVariable("quotation_id") UUID id, HttpServletResponse response) {
        NoStore.apply(response);
        return service.get(id);
    }

    @PostMapping("/{quotation_id}/cancel")
    @RequiresPermission("retail.document.issue")
    @Operation(summary = "Cancel an open quotation (issue #197)", operationId = "cancelRetailQuotation")
    Quotation cancel(
            @PathVariable("quotation_id") UUID id,
            @Valid @RequestBody CancelRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        return service.cancel(id, request);
    }

    @PostMapping("/{quotation_id}/convert")
    @RequiresPermission("retail.document.issue")
    @Operation(
            summary = "Convert an open, unexpired quotation to a sale through the ordinary sale path, and optionally to"
                    + " its invoice; M (issue #197)",
            operationId = "convertRetailQuotation")
    ResponseEntity<Converted> convert(
            @PathVariable("quotation_id") UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ConvertRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        Outcome<Converted> outcome = service.convert(idempotencyKey, id, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.OK);
        if (outcome.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(outcome.body());
    }
}
