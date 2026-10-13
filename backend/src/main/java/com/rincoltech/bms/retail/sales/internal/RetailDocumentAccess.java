package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * FR-DOC-03: the stored PDF of an invoice, delivery note or quotation is readable by whoever may issue documents in
 * the branch of the document, and only while the documents capability is on (a tenant that switches it off sees none
 * of it; what was issued is kept and reads again when it is switched back on). The copy issued before a document became
 * void (its sale voided, its stock move cancelled, its quotation cancelled) is no longer served: it carries no VOID
 * mark, so only the version stored with the banner is readable, and it is made on the first request after the void.
 */
@Component
class RetailDocumentAccess implements DocumentAccess {

    private final DocumentsRepository documents;
    // Resolved on use: the settings service reads logos through core.documents, which collects the DocumentAccess
    // beans.
    private final ObjectProvider<RetailCapabilities> capabilities;

    RetailDocumentAccess(DocumentsRepository documents, ObjectProvider<RetailCapabilities> capabilities) {
        this.documents = documents;
        this.capabilities = capabilities;
    }

    @Override
    public String subjectType() {
        return DocumentsRepository.SUBJECT;
    }

    @Override
    public boolean canRead(Principal principal, UUID documentId) {
        return capabilities.getObject().enabled(RetailCapabilities.DOCUMENTS)
                && documents
                        .find(documentId)
                        .map(d -> principal.may(DocumentsService.ISSUE, d.branchId()))
                        .orElse(false);
    }

    @Override
    public boolean canRead(Principal principal, UUID subjectId, UUID storedDocumentId) {
        if (!canRead(principal, subjectId)) {
            return false;
        }
        return documents
                .find(subjectId)
                .map(d -> {
                    if (!d.status().equals("void")) {
                        return true;
                    }
                    return documents
                            .storedNumber(storedDocumentId)
                            .map(n -> n.endsWith(DocumentsService.VOID_SUFFIX))
                            .orElse(false);
                })
                .orElse(false);
    }
}
