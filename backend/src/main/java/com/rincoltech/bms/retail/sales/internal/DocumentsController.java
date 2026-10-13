package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.DocumentList;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.DocumentPage;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.IssueRequest;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.IssuedDocument;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.SeriesList;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.SeriesRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/retail} document routes (documents capability; ADR-033, issue #197; chapter 7 section 7.11.20). Every
 * route answers 404 {@code capability_not_enabled} while the capability is off. A document is downloaded through
 * {@code POST /api/v1/documents/{id}/download-url} with the {@code pdf_document_id} these routes return (FR-DOC-03).
 */
@RestController
@RequestMapping(path = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-documents")
class DocumentsController {

    private final DocumentsService service;

    DocumentsController(DocumentsService service) {
        this.service = service;
    }

    @PostMapping("/sales/{sale_id}/documents")
    @RequiresPermission("retail.document.issue")
    @Operation(
            summary =
                    "Issue the invoice or delivery note of a sale, numbered in its branch's series; idempotent by sale"
                            + " and type; M (issue #197)",
            operationId = "issueRetailSaleDocument")
    ResponseEntity<IssuedDocument> issueForSale(
            @PathVariable("sale_id") UUID saleId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody IssueRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        return created(service.issueForSale(idempotencyKey, saleId, request));
    }

    @GetMapping("/sales/{sale_id}/documents")
    @RequiresPermission("retail.document.issue")
    @Operation(summary = "The documents issued for a sale (issue #197)", operationId = "listRetailSaleDocuments")
    DocumentList forSale(@PathVariable("sale_id") UUID saleId, HttpServletResponse response) {
        NoStore.apply(response);
        return new DocumentList(service.forSale(saleId));
    }

    @PostMapping("/transfers/{transfer_id}/documents")
    @RequiresPermission("retail.document.issue")
    @Operation(
            summary =
                    "Issue the delivery note of a stock move: quantities only, never prices; idempotent; M (issue #197)",
            operationId = "issueRetailTransferDocument")
    ResponseEntity<IssuedDocument> issueForTransfer(
            @PathVariable("transfer_id") UUID transferId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody IssueRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        return created(service.issueForTransfer(idempotencyKey, transferId, request));
    }

    @GetMapping("/documents")
    @RequiresPermission("retail.document.issue")
    @Operation(
            summary = "Issued documents in the caller's branch scope, newest first (issue #197)",
            operationId = "listRetailDocuments")
    DocumentPage list(
            @RequestParam(name = "branch_id", required = false) List<UUID> branchIds,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            HttpServletResponse response) {
        NoStore.apply(response);
        return service.list(branchIds, type, from, to, query, limit, cursor);
    }

    @GetMapping("/documents/{document_id}")
    @RequiresPermission("retail.document.issue")
    @Operation(summary = "An issued document (issue #197)", operationId = "getRetailDocument")
    IssuedDocument get(@PathVariable("document_id") UUID id, HttpServletResponse response) {
        NoStore.apply(response);
        return service.get(id);
    }

    @PostMapping("/documents/{document_id}/pdf")
    @RequiresPermission("retail.document.issue")
    @Operation(
            summary = "The stored PDF of an issued document, rendered from its snapshot now if it was never stored"
                    + " (issue #197)",
            operationId = "renderRetailDocument")
    IssuedDocument pdf(@PathVariable("document_id") UUID id, HttpServletResponse response) {
        NoStore.apply(response);
        return service.pdf(id);
    }

    @GetMapping("/document-series")
    @RequiresPermission("retail.document.issue")
    @Operation(
            summary = "The numbered series: prefix per document type and the next number in each branch (issue #197)",
            operationId = "listRetailDocumentSeries")
    SeriesList series(HttpServletResponse response) {
        NoStore.apply(response);
        return service.series();
    }

    @PutMapping("/document-series/{type}")
    @RequiresPermission("core.settings.manage")
    @Operation(
            summary = "Change the prefix of a document type; later documents take it, issued numbers never change"
                    + " (issue #197)",
            operationId = "setRetailDocumentPrefix")
    SeriesList setPrefix(
            @PathVariable("type") String type,
            @Valid @RequestBody SeriesRequest request,
            HttpServletResponse response) {
        NoStore.apply(response);
        return service.setPrefix(type, request.prefix());
    }

    @GetMapping(
            path = "/customers/{customer_id}/statement/pdf",
            produces = {MediaType.APPLICATION_PDF_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @RequiresPermission("retail.document.issue")
    @Operation(
            summary = "A credit buyer's statement as a PDF, as at today and never stored (issue #197)",
            operationId = "renderRetailCustomerStatement")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "The PDF",
                content =
                        @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(
                responseCode = "404",
                description = "No such buyer in the caller's scope, or a capability is off",
                content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))),
        @ApiResponse(
                responseCode = "503",
                description = "Too many documents are being produced; see Retry-After",
                content = @Content(mediaType = "application/json", schema = @Schema(type = "object")))
    })
    ResponseEntity<byte[]> statementPdf(
            @PathVariable("customer_id") UUID customerId,
            @RequestParam(name = "from", required = false) LocalDate from,
            @RequestParam(name = "to", required = false) LocalDate to) {
        byte[] pdf = service.statementPdf(customerId, from, to);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("statement.pdf")
                                .build()
                                .toString())
                .body(pdf);
    }

    private static ResponseEntity<IssuedDocument> created(Outcome<IssuedDocument> outcome) {
        IssuedDocument d = outcome.body();
        ResponseEntity.BodyBuilder response = d.created() && !outcome.replayed()
                ? ResponseEntity.created(URI.create("/api/v1/retail/documents/" + d.id()))
                : ResponseEntity.status(HttpStatus.OK);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(d);
    }
}
