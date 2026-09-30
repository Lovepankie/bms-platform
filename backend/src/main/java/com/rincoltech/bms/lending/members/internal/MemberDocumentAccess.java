package com.rincoltech.bms.lending.members.internal;

import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.kernel.Principal;
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
}
