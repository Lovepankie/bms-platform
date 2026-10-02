package com.rincoltech.bms.lending.members.internal;

import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.kernel.Principal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * This module's registration as the reader of {@code lending.member} documents (FR-DOC-03):
 * whoever may read the member, in the member's branch, may read the member's documents. Kept
 * apart from {@link MemberDocumentService}, which uses the documents module, so the registry
 * that the documents module collects has no dependency back on it.
 */
@Component
class MemberDocumentAccess implements DocumentAccess {

    static final Set<String> ID_IMAGES = Set.of("id_front", "id_back");

    private final MemberRepository members;

    MemberDocumentAccess(MemberRepository members) {
        this.members = members;
    }

    @Override
    public String subjectType() {
        return MemberDocumentService.SUBJECT;
    }

    @Override
    public boolean canRead(Principal principal, UUID subjectId) {
        return members.findById(subjectId)
                .filter(m -> principal.may("lending.members.read", m.branchId()))
                .isPresent();
    }

    /**
     * ID images (id_front, id_back) need lending.members.verify_kyc in the member's branch, the
     * people who verify KYC; photo and other documents need lending.members.read (chapter 8 section
     * 8.3.2). A document with no member link row has no known kind and is denied: fail closed.
     */
    @Override
    public boolean canRead(Principal principal, UUID subjectId, UUID documentId) {
        Optional<String> kind = members.documentKind(documentId);
        if (kind.isEmpty()) {
            return false;
        }
        String permission = ID_IMAGES.contains(kind.get()) ? "lending.members.verify_kyc" : "lending.members.read";
        return members.findById(subjectId)
                .filter(m -> principal.may(permission, m.branchId()))
                .isPresent();
    }
}
