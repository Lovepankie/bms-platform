package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loan documents (chapter 7 section 7.11.13, FR-DOC-01, ADR-034). A receipt or voucher is issued
 * once and then downloaded through {@code POST /api/v1/documents/{id}/download-url} (FR-DOC-03);
 * the schedule and statement come back as the PDF itself, as at the business date.
 */
@RestController
@RequestMapping(path = "/api/v1/lending/loans/{loan_id}")
@Tag(name = "lending-loans")
class LoanDocumentController {

    @Schema(name = "LoanDocumentIssued")
    record IssuedResponse(
            UUID documentId,
            @Schema(description = "receipt or voucher") String docType,

            @Schema(description = "The transaction's RC- or VC- number")
            String docNumber,

            @Schema(description = "True when this request rendered and stored it")
            boolean created,

            @Schema(
                    description =
                            "The transaction was reversed. The stored copy is as issued and unchanged; show the document as reversed")
            boolean reversed,

            @Schema(description = "The value date of the reversal; null when not reversed")
            LocalDate reversedOn) {}

    @Schema(name = "LoanDocumentList", description = "The receipts and vouchers stored for a loan, newest first")
    record ListResponse(List<IssuedResponse> items) {}

    private final LoanDocuments documents;

    LoanDocumentController(LoanDocuments documents) {
        this.documents = documents;
    }

    @PostMapping(path = "/transactions/{txn_id}/document", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("lending.loans.read")
    @Operation(
            summary = "The receipt or voucher of a loan transaction, stored on first request (FR-DOC-01)",
            operationId = "issueLoanTransactionDocument")
    IssuedResponse issue(
            @PathVariable("loan_id") UUID loanId, @PathVariable("txn_id") UUID txnId, HttpServletResponse response) {
        NoStore.apply(response);
        return response(documents.issue(loanId, txnId));
    }

    @GetMapping(path = "/documents", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission("lending.loans.read")
    @Operation(
            summary = "The receipts and vouchers stored for a loan, each marked when its transaction was reversed",
            operationId = "listLoanDocuments")
    ListResponse list(@PathVariable("loan_id") UUID loanId, HttpServletResponse response) {
        NoStore.apply(response);
        return new ListResponse(documents.issuedDocuments(loanId).stream()
                .map(LoanDocumentController::response)
                .toList());
    }

    private static IssuedResponse response(LoanDocuments.Issued i) {
        return new IssuedResponse(
                i.documentId(), i.docType(), i.docNumber(), i.created(), i.reversed(), i.reversedOn());
    }

    @PostMapping(
            path = "/documents/{doc_type}",
            produces = {MediaType.APPLICATION_PDF_VALUE, MediaType.APPLICATION_JSON_VALUE})
    @RequiresPermission("lending.loans.read")
    @Operation(
            summary = "The repayment schedule (schedule) or loan statement (loan_statement) as a PDF, as at today",
            operationId = "renderLoanDocument")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "The PDF",
                content =
                        @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))),
        @ApiResponse(
                responseCode = "404",
                description = "No such loan in the caller's branches, or no such document type",
                content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))),
        @ApiResponse(
                responseCode = "409",
                description = "The loan is not disbursed yet (invalid_status_transition)",
                content = @Content(mediaType = "application/json", schema = @Schema(type = "object"))),
        @ApiResponse(
                responseCode = "503",
                description = "Too many documents are being produced; see Retry-After",
                content = @Content(mediaType = "application/json", schema = @Schema(type = "object")))
    })
    ResponseEntity<byte[]> render(@PathVariable("loan_id") UUID loanId, @PathVariable("doc_type") String docType) {
        byte[] pdf = switch (docType) {
            case "schedule" -> documents.schedule(loanId);
            case "loan_statement" -> documents.statement(loanId);
            default -> throw ApiException.notFound();
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(docType + ".pdf")
                                .build()
                                .toString())
                .body(pdf);
    }
}
