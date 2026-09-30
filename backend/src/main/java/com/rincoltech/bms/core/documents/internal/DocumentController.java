package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.core.documents.Documents.StoredDocument;
import com.rincoltech.bms.core.documents.internal.DocumentService.DownloadUrl;
import com.rincoltech.bms.kernel.AuthenticatedEndpoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/documents} (chapter 7 section 7.11.7). The permission is on the subject, decided
 * by the owning module through {@code DocumentAccess}, so these routes are "authenticated" rather
 * than carrying one matrix permission; uploads go through the subject's own route.
 */
@RestController
@RequestMapping(path = "/api/v1/documents", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "documents")
class DocumentController {

    private final DocumentService service;

    DocumentController(DocumentService service) {
        this.service = service;
    }

    @GetMapping("/{document_id}")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(summary = "Document metadata (permission on the subject)", operationId = "getDocument")
    StoredDocument get(@PathVariable("document_id") UUID documentId) {
        return service.metadata(documentId);
    }

    @PostMapping("/{document_id}/download-url")
    @AuthenticatedEndpoint(kind = "staff")
    @Operation(summary = "A 5 minute signed download URL (FR-DOC-03)", operationId = "createDocumentDownloadUrl")
    DownloadUrl downloadUrl(@PathVariable("document_id") UUID documentId) {
        return service.downloadUrl(documentId);
    }
}
