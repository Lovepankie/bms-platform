package com.rincoltech.bms.lending.members.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.Documents.StoredDocument;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.lending.members.internal.MemberApi.MemberResponse;
import com.rincoltech.bms.lending.members.internal.MemberRepository.MemberDocumentRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Member documents (FR-MEM-09) on top of the core documents module. Who may read them is
 * {@link MemberDocumentAccess}.
 */
@Service
class MemberDocumentService {

    static final String SUBJECT = "lending.member";
    static final Set<String> KINDS = Set.of("id_front", "id_back", "photo", "other");

    @Schema(name = "MemberDocument")
    record MemberDocument(
            UUID documentId, String docKind, String contentType, long sizeBytes, Instant createdAt, UUID uploadedBy) {}

    @Schema(name = "MemberDocumentList")
    record MemberDocumentList(List<MemberDocument> items) {}

    private final MemberRepository members;
    private final Documents documents;
    private final MemberService memberService;
    private final AuditLog audit;

    MemberDocumentService(MemberRepository members, Documents documents, MemberService memberService, AuditLog audit) {
        this.members = members;
        this.documents = documents;
        this.memberService = memberService;
        this.audit = audit;
    }

    /** Stores the file, links it to the member and rechecks KYC completeness (FR-MEM-05), in one transaction. */
    @Transactional
    MemberDocument upload(UUID memberId, String docKind, byte[] bytes) {
        if (docKind == null || !KINDS.contains(docKind)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("doc_kind", "invalid", "One of id_front, id_back, photo, other.")));
        }
        MemberResponse member = inScope(memberId, "lending.members.update");
        StoredDocument stored = documents.upload(new Documents.Upload(SUBJECT, memberId, member.branchId(), bytes));
        UUID uploader = CurrentPrincipal.require().userId();
        members.insertDocument(UUID.randomUUID(), memberId, docKind, stored.id(), uploader);
        audit.record(AuditLog.Entry.created(
                "lending.member.document_added",
                "lending.member",
                memberId,
                member.branchId(),
                Map.of("doc_kind", docKind, "document_id", stored.id())));
        memberService.recheckKyc(memberId, member.branchId());
        return new MemberDocument(
                stored.id(), docKind, stored.contentType(), stored.sizeBytes(), stored.createdAt(), uploader);
    }

    @Transactional(readOnly = true)
    MemberDocumentList list(UUID memberId) {
        inScope(memberId, "lending.members.read");
        return new MemberDocumentList(members.documents(memberId).stream()
                .map(MemberDocumentService::of)
                .toList());
    }

    private MemberResponse inScope(UUID memberId, String permission) {
        Principal principal = CurrentPrincipal.require();
        return members.findById(memberId)
                .filter(m -> principal.may(permission, m.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    private static MemberDocument of(MemberDocumentRow r) {
        return new MemberDocument(
                r.documentId(), r.docKind(), r.contentType(), r.sizeBytes(), r.createdAt(), r.uploadedBy());
    }
}
