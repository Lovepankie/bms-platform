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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Member documents (FR-MEM-09) on top of the core documents module. Who may read them is
 * {@link MemberDocumentAccess}.
 */
@Service
class MemberDocumentService {

    static final String SUBJECT = "lending.member";
    static final Set<String> KINDS = Set.of("id_front", "id_back", "photo", "other");

    /** Active documents per member and kind; a newer upload supersedes the oldest beyond this. */
    static final int MAX_PER_KIND = 10;

    @Schema(name = "MemberDocument")
    record MemberDocument(
            UUID documentId, String docKind, String contentType, long sizeBytes, Instant createdAt, UUID uploadedBy) {}

    @Schema(name = "MemberDocumentList")
    record MemberDocumentList(List<MemberDocument> items) {}

    private final MemberRepository members;
    private final Documents documents;
    private final MemberService memberService;
    private final AuditLog audit;
    private final TransactionTemplate transactions;

    MemberDocumentService(
            MemberRepository members,
            Documents documents,
            MemberService memberService,
            AuditLog audit,
            PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.members = members;
        this.documents = documents;
        this.memberService = memberService;
        this.audit = audit;
    }

    /**
     * FR-MEM-09. The file is checked and re-encoded before any transaction opens (the wait for a
     * re-encode slot holds no database connection); then one transaction locks the member, stores
     * the file, links it, supersedes the oldest active document of the kind when the member already
     * has {@link #MAX_PER_KIND}, and rechecks KYC completeness (FR-MEM-05).
     */
    MemberDocument upload(UUID memberId, String docKind, byte[] bytes) {
        if (docKind == null || !KINDS.contains(docKind)) {
            throw ApiException.validation(
                    List.of(new FieldProblem("doc_kind", "invalid", "One of id_front, id_back, photo, other.")));
        }
        // Scope first, in a short read: nobody outside it makes the server decode an image.
        transactions.executeWithoutResult(status -> inScope(memberId, "lending.members.update"));
        Documents.Prepared prepared = documents.prepare(bytes);
        return transactions.execute(status -> store(memberId, docKind, prepared));
    }

    private MemberDocument store(UUID memberId, String docKind, Documents.Prepared prepared) {
        Principal principal = CurrentPrincipal.require();
        // The member row lock serialises this member's uploads, so the limit holds under concurrency.
        MemberResponse member = members.lockById(memberId)
                .filter(m -> principal.may("lending.members.update", m.branchId()))
                .orElseThrow(ApiException::notFound);
        List<UUID> active = members.activeDocumentLinks(memberId, docKind);
        StoredDocument stored = documents.store(prepared, SUBJECT, memberId, member.branchId());
        UUID linkId = UUID.randomUUID();
        members.insertDocument(linkId, memberId, docKind, stored.id(), principal.userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("doc_kind", docKind);
        after.put("document_id", stored.id());
        if (active.size() >= MAX_PER_KIND) {
            members.supersede(active.getFirst(), linkId);
            after.put("superseded_link_id", active.getFirst());
        }
        audit.record(AuditLog.Entry.created(
                "lending.member.document_added", "lending.member", memberId, member.branchId(), after));
        memberService.recheckKyc(memberId, member.branchId());
        return new MemberDocument(
                stored.id(), docKind, stored.contentType(), stored.sizeBytes(), stored.createdAt(), principal.userId());
    }

    /** Active documents only. ID images are listed only to those who may open them (verify_kyc). */
    @Transactional(readOnly = true)
    MemberDocumentList list(UUID memberId) {
        MemberResponse member = inScope(memberId, "lending.members.read");
        boolean seesIdImages = CurrentPrincipal.require().may("lending.members.verify_kyc", member.branchId());
        return new MemberDocumentList(members.documents(memberId).stream()
                .filter(r -> seesIdImages || !MemberDocumentAccess.ID_IMAGES.contains(r.docKind()))
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
