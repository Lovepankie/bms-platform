package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.operations.Idempotency.Outcome;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.PublicEndpoint;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.DocumentList;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.DocumentPage;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.IssueRequest;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.IssuedDocument;
import com.rincoltech.bms.retail.sales.internal.DocumentsApi.SaleReceipt;
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
 * route answers 404 {@code capability_not_enabled} while the capability is off, except the receipt of a sale (issue
 * #439, ADR-061), which is never stored and is a seller's to share. A document is downloaded through
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

    // JUSTIFICATION-A3: the facts of the receipt to share; the same check as its PDF below.
    @GetMapping("/sales/{sale_id}/receipt")
    @RequiresPermission("retail.sale.read")
    @Operation(
            summary =
                    "The facts of a sale's receipt to share, as its PDF prints them: number, business name, buyer and"
                            + " number, paid, balance, returns and net; own sales only without retail.sale.all_sellers (issue #439)",
            operationId = "getRetailSaleReceipt")
    SaleReceipt receipt(@PathVariable("sale_id") UUID saleId, HttpServletResponse response) {
        NoStore.apply(response);
        return service.receiptFacts(saleId);
    }

    // JUSTIFICATION-A3: the printed receipt for the screen's preview, apart so the facts above stay a cheap read.
    @GetMapping("/sales/{sale_id}/receipt/printed")
    @RequiresPermission("retail.sale.read")
    @Operation(
            summary = "What a sale's receipt PDF prints, laid out for the screen's preview and asked for only when View"
                    + " receipt is tapped; the same check as the PDF, and without printed when it cannot be laid out"
                    + " (issue #500)",
            operationId = "getRetailSaleReceiptPrinted")
    DocumentsApi.ReceiptPreview receiptPrinted(@PathVariable("sale_id") UUID saleId, HttpServletResponse response) {
        NoStore.apply(response);
        return service.receiptPreview(saleId);
    }

    // JUSTIFICATION-A3: one new read route; the same PDF response shape as the statement route above.
    @GetMapping(
            path = "/sales/{sale_id}/receipt/pdf",
            produces = {MediaType.APPLICATION_PDF_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @RequiresPermission("retail.sale.read")
    @Operation(
            summary = "The receipt of a sale as a PDF to share from the seller's phone, rendered now and never stored;"
                    + " a seller who sees only their own figures gets their own sales only (issue #439)",
            operationId = "renderRetailSaleReceipt")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "The PDF",
                content =
                        @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(
                responseCode = "404",
                description = "No such sale in the caller's scope",
                content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))),
        @ApiResponse(
                responseCode = "422",
                description = "The sale was voided or written off, or every item of it was taken back",
                content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))),
        @ApiResponse(
                responseCode = "503",
                description = "Too many documents are being produced; see Retry-After",
                content = @Content(mediaType = "application/json", schema = @Schema(type = "object")))
    })
    ResponseEntity<byte[]> receiptPdf(@PathVariable("sale_id") UUID saleId) {
        byte[] pdf = service.receiptPdf(saleId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("receipt.pdf")
                                .build()
                                .toString())
                .body(pdf);
    }

    // JUSTIFICATION-A3: the receipt link of issue #760 (ADR-116): one route makes it, one public route opens it.
    @Schema(name = "RetailReceiptLink")
    record ReceiptLink(
            @Schema(description = "The link with https://, for the WhatsApp message")
            String url,

            @Schema(description = "As a person reads it, for example rincoltech.com/<token>")
            String link,

            @Schema(description = "When the link stops working, 90 days on")
            java.time.Instant expiresAt) {}

    @PostMapping("/sales/{sale_id}/receipt/link")
    @RequiresPermission("retail.sale.read")
    @Operation(
            summary = "A short link to the sale's receipt PDF for a message to a typed WhatsApp number: read only, no"
                    + " sign-in, valid for 90 days; the same check as the receipt PDF (issue #760)",
            operationId = "createRetailSaleReceiptLink")
    ReceiptLink receiptLink(@PathVariable("sale_id") UUID saleId, HttpServletResponse response) {
        NoStore.apply(response);
        ReceiptLinks.Minted m = service.receiptLink(saleId);
        return new ReceiptLink(m.url(), m.link(), m.expiresAt());
    }

    @GetMapping(
            path = "/receipt-links/{token}",
            produces = {MediaType.APPLICATION_PDF_VALUE, MediaType.TEXT_HTML_VALUE})
    @PublicEndpoint
    @Operation(
            summary = "The receipt PDF a receipt link opens, with no sign-in; a wrong or expired link is a plain page"
                    + " (issue #760)",
            operationId = "openRetailReceiptLink")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "The PDF",
                content =
                        @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(
                responseCode = "404",
                description = "This receipt link has expired: a plain page",
                content = @Content(mediaType = "text/html", schema = @Schema(type = "string"))),
        @ApiResponse(
                responseCode = "429",
                description = "Too many opens of this link or from this address: a plain page",
                content = @Content(mediaType = "text/html", schema = @Schema(type = "string")))
    })
    ResponseEntity<byte[]> openReceiptLink(@PathVariable("token") String token) {
        if (!service.mayOpenLink(token)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.RETRY_AFTER, "600")
                    .body(ReceiptLinks.BUSY_PAGE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return service.linkedReceiptPdf(token)
                .map(pdf -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .header("X-Robots-Tag", "noindex")
                        .header(
                                HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.inline()
                                        .filename("receipt.pdf")
                                        .build()
                                        .toString())
                        .body(pdf))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                        .header(HttpHeaders.CACHE_CONTROL, "no-store")
                        .body(ReceiptLinks.GONE_PAGE.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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
