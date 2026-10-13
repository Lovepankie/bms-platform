package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.kernel.NoStore;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.RequiresPermission;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/retail/returns/{return_id}/document}: the credit note of a return as a PDF under the return's own number
 * (documents and returns capabilities; ADR-033 amendment, issue #197). It is idempotent by return, so it takes no key, and the
 * stored PDF is downloaded with {@code POST /api/v1/documents/{id}/download-url} (FR-DOC-03).
 */
@RestController
@RequestMapping(path = "/api/v1/retail/returns", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "retail-documents")
class CreditNotesController {

    @Schema(name = "RetailCreditNoteDocument")
    record CreditNoteDocument(
            UUID returnId,

            @Schema(description = "The return's own number: one credit note, one number")
            String creditNoteNo,

            @Schema(
                    description =
                            "issued, or void when the return was voided: only a copy with the VOID banner is served")
            String status,

            @Schema(description = "The stored PDF; download it with POST /documents/{id}/download-url")
            UUID pdfDocumentId,

            @Schema(description = "True when this request stored the PDF")
            boolean created) {}

    private final CreditNotes service;

    CreditNotesController(CreditNotes service) {
        this.service = service;
    }

    @PostMapping("/{return_id}/document")
    @RequiresPermission("retail.document.issue")
    @Operation(
            summary =
                    "The credit note PDF of a return, under the return's number, stored once; a voided return is marked"
                            + " void (issue #197)",
            operationId = "renderRetailCreditNote")
    CreditNoteDocument render(@PathVariable("return_id") UUID returnId, HttpServletResponse response) {
        NoStore.apply(response);
        CreditNotes.Issued i = service.issue(returnId);
        return new CreditNoteDocument(i.returnId(), i.creditNoteNo(), i.status(), i.pdfDocumentId(), i.created());
    }

    /**
     * FR-DOC-03 for the credit note PDF: whoever may issue documents and may read returns in the return's branch, while both
     * capabilities are on; the copy issued before a return was voided is not served.
     */
    @Component
    static class Access implements DocumentAccess {

        private final JdbcClient jdbc;
        private final ObjectProvider<RetailCapabilities> capabilities;

        Access(JdbcClient jdbc, ObjectProvider<RetailCapabilities> capabilities) {
            this.jdbc = jdbc;
            this.capabilities = capabilities;
        }

        @Override
        public String subjectType() {
            return CreditNotes.SUBJECT;
        }

        @Override
        public boolean canRead(Principal principal, UUID returnId) {
            return status(principal, returnId) != null;
        }

        @Override
        public boolean canRead(Principal principal, UUID returnId, UUID storedDocumentId) {
            String status = status(principal, returnId);
            if (status == null) {
                return false;
            }
            if (!status.equals("voided")) {
                return true;
            }
            return jdbc.sql("SELECT doc_number FROM documents WHERE id = ? AND subject_type = ?")
                    .params(storedDocumentId, CreditNotes.SUBJECT)
                    .query(String.class)
                    .optional()
                    .map(n -> n.endsWith(DocumentsService.VOID_SUFFIX))
                    .orElse(false);
        }

        /** The return's status when the principal may read its credit note, else null. */
        private String status(Principal principal, UUID returnId) {
            RetailCapabilities caps = capabilities.getObject();
            if (!caps.enabled(RetailCapabilities.DOCUMENTS) || !caps.enabled(RetailCapabilities.RETURNS)) {
                return null;
            }
            return jdbc.sql("SELECT branch_id, status FROM retail_sale_returns WHERE id = ?")
                    .param(returnId)
                    .query((rs, n) -> principal.may(DocumentsService.ISSUE, rs.getObject("branch_id", UUID.class))
                                    && principal.may("retail.sale.return", rs.getObject("branch_id", UUID.class))
                            ? rs.getString("status")
                            : "-")
                    .optional()
                    .filter(s -> !s.equals("-"))
                    .orElse(null);
        }
    }
}
