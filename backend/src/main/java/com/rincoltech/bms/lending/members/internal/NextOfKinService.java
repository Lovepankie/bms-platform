package com.rincoltech.bms.lending.members.internal;

import static com.rincoltech.bms.lending.members.internal.MemberService.blankToNull;
import static com.rincoltech.bms.lending.members.internal.MemberService.given;
import static com.rincoltech.bms.lending.members.internal.MemberService.invalidPhone;
import static com.rincoltech.bms.lending.members.internal.MemberService.invalidTransition;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.NationalIds;
import com.rincoltech.bms.kernel.PhoneNumbers;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.lending.members.internal.MemberApi.MemberResponse;
import com.rincoltech.bms.lending.members.internal.NextOfKinApi.CreateNextOfKinRequest;
import com.rincoltech.bms.lending.members.internal.NextOfKinApi.LinkDecisionRequest;
import com.rincoltech.bms.lending.members.internal.NextOfKinApi.NextOfKinList;
import com.rincoltech.bms.lending.members.internal.NextOfKinApi.NextOfKinResponse;
import com.rincoltech.bms.lending.members.internal.NextOfKinApi.Relationships;
import com.rincoltech.bms.lending.members.internal.NextOfKinApi.UpdateNextOfKinRequest;
import com.rincoltech.bms.lending.members.internal.NextOfKinRepository.Link;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Next of kin and the relationship panel (FR-MEM-06 to FR-MEM-08). Every route checks the owning
 * member's branch against the route's permission; a next of kin outside scope is a 404.
 */
@Service
class NextOfKinService {

    /** KYC states in which a member must keep at least one next of kin (FR-MEM-05, FR-MEM-06). */
    private static final Set<String> KYC_COMPLETE = Set.of("pending_verification", "verified");

    private final MemberRepository members;
    private final NextOfKinRepository kin;
    private final AuditLog audit;

    NextOfKinService(MemberRepository members, NextOfKinRepository kin, AuditLog audit) {
        this.members = members;
        this.kin = kin;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    NextOfKinList list(UUID memberId) {
        memberInScope(memberId, "lending.members.read");
        return new NextOfKinList(kin.forMember(memberId));
    }

    /** FR-MEM-06, FR-MEM-07: a NIN match links at once; a phone match is suggested. */
    @Transactional
    NextOfKinResponse create(UUID memberId, CreateNextOfKinRequest r) {
        MemberResponse member = memberInScope(memberId, "lending.members.update");
        String phone = phone(r.phone());
        String nin = nin(r.nationalId());
        Link link = kin.resolve(memberId, nin, phone);
        boolean primary = Boolean.TRUE.equals(r.isPrimary());
        if (primary) {
            kin.clearPrimary(memberId, null);
        }
        NextOfKinResponse k = new NextOfKinResponse(
                UUID.randomUUID(),
                memberId,
                r.fullName().trim(),
                phone,
                nin,
                r.relationship(),
                r.relationshipText(),
                r.location(),
                primary,
                link.memberId(),
                null,
                link.method(),
                link.status(),
                null,
                null,
                1);
        kin.insert(k, CurrentPrincipal.require().userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("member_id", memberId);
        after.put("full_name", k.fullName());
        after.put("relationship", k.relationship());
        after.put("phone_e164", phone);
        after.put("national_id", nin);
        after.put("linked_member_id", link.memberId());
        after.put("link_status", link.status());
        audit.record(AuditLog.Entry.created(
                "lending.next_of_kin.created", "lending.next_of_kin", k.id(), member.branchId(), after));
        return kin.find(k.id()).orElseThrow();
    }

    /** Omitted fields are unchanged. A new NIN or phone re-resolves the link (FR-MEM-07). */
    @Transactional
    NextOfKinResponse update(UUID kinId, String ifMatch, UpdateNextOfKinRequest r) {
        Locked locked = lockForChange(kinId, ifMatch);
        NextOfKinResponse before = locked.kin();
        String phone = r.phone() == null ? before.phoneE164() : phone(r.phone());
        String nin = r.nationalId() == null ? before.nationalId() : nin(r.nationalId());
        Link link = Objects.equals(phone, before.phoneE164()) && Objects.equals(nin, before.nationalId())
                ? new Link(before.linkedMemberId(), before.linkMethod(), before.linkStatus())
                : kin.resolve(before.memberId(), nin, phone);
        boolean primary = given(r.isPrimary(), before.isPrimary());
        if (primary && !before.isPrimary()) {
            kin.clearPrimary(before.memberId(), kinId);
        }
        NextOfKinResponse after = new NextOfKinResponse(
                kinId,
                before.memberId(),
                r.fullName() == null ? before.fullName() : r.fullName().trim(),
                phone,
                nin,
                given(r.relationship(), before.relationship()),
                given(r.relationshipText(), before.relationshipText()),
                given(r.location(), before.location()),
                primary,
                link.memberId(),
                before.linkedMemberNo(),
                link.method(),
                link.status(),
                before.createdAt(),
                before.updatedAt(),
                before.version());
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        MemberService.changes(before, after, was, now);
        was.remove("linked_member_no");
        now.remove("linked_member_no");
        if (now.isEmpty()) {
            return before;
        }
        kin.update(after);
        audit.record(new AuditLog.Entry(
                "lending.next_of_kin.updated", "lending.next_of_kin", kinId, locked.branchId(), was, now));
        return kin.find(kinId).orElseThrow();
    }

    /** A member whose KYC is complete keeps at least one next of kin (chapter 7 section 7.11.11). */
    @Transactional
    void delete(UUID kinId, String ifMatch) {
        Locked locked = lockForChange(kinId, ifMatch);
        NextOfKinResponse before = locked.kin();
        // The member row lock serialises two concurrent deletes of the last two next of kin.
        MemberResponse member = members.lockById(before.memberId()).orElseThrow();
        if (KYC_COMPLETE.contains(member.kycStatus()) && kin.countForMember(member.id()) <= 1) {
            throw ApiException.rule(
                    "next_of_kin_required", "A member whose KYC is complete keeps at least one next of kin.");
        }
        kin.delete(kinId);
        Map<String, Object> was = new LinkedHashMap<>();
        was.put("member_id", before.memberId());
        was.put("full_name", before.fullName());
        was.put("relationship", before.relationship());
        was.put("phone_e164", before.phoneE164());
        was.put("national_id", before.nationalId());
        audit.record(new AuditLog.Entry(
                "lending.next_of_kin.deleted", "lending.next_of_kin", kinId, locked.branchId(), was, Map.of()));
    }

    /** FR-MEM-07: a suggested (phone) link is confirmed or rejected by a user. */
    @Transactional
    NextOfKinResponse decideLink(UUID kinId, String ifMatch, LinkDecisionRequest r) {
        Locked locked = lockForChange(kinId, ifMatch);
        NextOfKinResponse before = locked.kin();
        if (!"suggested".equals(before.linkStatus())) {
            throw invalidTransition(
                    "Only a suggested link can be confirmed or rejected; this one is " + before.linkStatus() + ".");
        }
        String status = "confirm".equals(r.decision()) ? "confirmed" : "rejected";
        kin.update(new NextOfKinResponse(
                before.id(),
                before.memberId(),
                before.fullName(),
                before.phoneE164(),
                before.nationalId(),
                before.relationship(),
                before.relationshipText(),
                before.location(),
                before.isPrimary(),
                before.linkedMemberId(),
                before.linkedMemberNo(),
                before.linkMethod(),
                status,
                before.createdAt(),
                before.updatedAt(),
                before.version()));
        audit.record(new AuditLog.Entry(
                "lending.next_of_kin.link_" + status,
                "lending.next_of_kin",
                kinId,
                locked.branchId(),
                Map.of("link_status", "suggested"),
                Map.of("link_status", status, "linked_member_id", before.linkedMemberId())));
        return kin.find(kinId).orElseThrow();
    }

    /**
     * FR-MEM-08: whom this member names, and who names this member, through the relationship
     * view. A member outside the caller's read scope shows only the member number.
     */
    @Transactional(readOnly = true)
    Relationships relationships(UUID memberId) {
        memberInScope(memberId, "lending.members.read");
        Principal principal = CurrentPrincipal.require();
        return new Relationships(
                memberId,
                kin.forMember(memberId),
                kin.namedAsKinBy(memberId).stream()
                        .map(n -> n.visible(principal.may("lending.members.read", n.branchId())))
                        .toList());
    }

    private record Locked(NextOfKinResponse kin, UUID branchId) {}

    /** If-Match first, then scope through the owning member (404 outside it), then version. */
    private Locked lockForChange(UUID kinId, String ifMatch) {
        int expected = Versions.fromIfMatch(ifMatch);
        NextOfKinResponse k = kin.lock(kinId).orElseThrow(ApiException::notFound);
        MemberResponse member = memberInScope(k.memberId(), "lending.members.update");
        if (k.version() != expected) {
            throw Versions.conflict(k.version());
        }
        return new Locked(k, member.branchId());
    }

    private MemberResponse memberInScope(UUID memberId, String permission) {
        Principal principal = CurrentPrincipal.require();
        return members.findById(memberId)
                .filter(m -> principal.may(permission, m.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    private static String phone(String raw) {
        return blankToNull(raw) == null
                ? null
                : PhoneNumbers.normaliseUganda(raw).orElseThrow(() -> invalidPhone("phone"));
    }

    private static String nin(String raw) {
        return blankToNull(raw) == null ? null : NationalIds.normaliseNin(raw).orElseThrow(MemberService::invalidNin);
    }
}
