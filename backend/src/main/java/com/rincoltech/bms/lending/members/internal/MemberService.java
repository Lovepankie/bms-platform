package com.rincoltech.bms.lending.members.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.PlanLimits;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.Masking;
import com.rincoltech.bms.kernel.NationalIds;
import com.rincoltech.bms.kernel.PhoneNumbers;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.Versions;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.internal.MemberApi.BlacklistRequest;
import com.rincoltech.bms.lending.members.internal.MemberApi.CreateMemberRequest;
import com.rincoltech.bms.lending.members.internal.MemberApi.DuplicateCandidate;
import com.rincoltech.bms.lending.members.internal.MemberApi.DuplicateCheckRequest;
import com.rincoltech.bms.lending.members.internal.MemberApi.DuplicateCheckResponse;
import com.rincoltech.bms.lending.members.internal.MemberApi.KycDecisionRequest;
import com.rincoltech.bms.lending.members.internal.MemberApi.MemberListItem;
import com.rincoltech.bms.lending.members.internal.MemberApi.MemberPage;
import com.rincoltech.bms.lending.members.internal.MemberApi.MemberResponse;
import com.rincoltech.bms.lending.members.internal.MemberApi.UpdateMemberRequest;
import com.rincoltech.bms.lending.members.internal.MemberRepository.Candidate;
import com.rincoltech.bms.lending.members.internal.MemberRepository.NewMember;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MemberService implements MemberLookup {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final MemberRepository members;
    private final Branches branches;
    private final CurrentTenant currentTenant;
    private final TenantSequences sequences;
    private final AuditLog audit;
    private final PlanLimits planLimits;
    private final NextOfKinRepository kin;

    MemberService(
            MemberRepository members,
            Branches branches,
            CurrentTenant currentTenant,
            TenantSequences sequences,
            AuditLog audit,
            PlanLimits planLimits,
            NextOfKinRepository kin) {
        this.members = members;
        this.branches = branches;
        this.currentTenant = currentTenant;
        this.sequences = sequences;
        this.audit = audit;
        this.planLimits = planLimits;
        this.kin = kin;
    }

    /** FR-MEM-01, FR-MEM-02, FR-MEM-03, FR-TEN-04, FR-AUD-01. One transaction: number, row and audit row. */
    @Transactional
    MemberResponse create(CreateMemberRequest request) {
        Principal principal = CurrentPrincipal.require();
        if (!principal.may("lending.members.create", request.branchId())
                || branches.findActive(request.branchId()).isEmpty()) {
            throw ApiException.validation(
                    List.of(new FieldProblem("branch_id", "unknown_branch", "No such active branch in your scope.")));
        }
        String phone = PhoneNumbers.normaliseUganda(request.phone()).orElseThrow(() -> invalidPhone("phone"));
        String altPhone = request.altPhone() == null || request.altPhone().isBlank()
                ? null
                : PhoneNumbers.normaliseUganda(request.altPhone()).orElseThrow(() -> invalidPhone("alt_phone"));
        String nin = checkIdentity(request.idType(), request.nationalId(), request.otherIdNumber(), null);
        checkPhoneShared(phone, null, request.confirmedNotDuplicate());
        planLimits.checkRoomFor(PlanLimits.MAX_ACTIVE_MEMBERS, members.countActive());

        UUID id = UUID.randomUUID();
        String memberNo = "M%06d".formatted(sequences.next("member_no"));
        String currency = request.currency() != null
                ? request.currency()
                : currentTenant.profile().currency();
        members.insert(new NewMember(
                id,
                request.branchId(),
                memberNo,
                request.fullName().trim(),
                request.firstName(),
                request.lastName(),
                phone,
                altPhone,
                request.idType(),
                nin,
                request.otherIdNumber(),
                request.dateOfBirth(),
                request.gender(),
                request.maritalStatus() == null ? "unknown" : request.maritalStatus(),
                request.district(),
                request.subCounty(),
                request.village(),
                request.location(),
                request.occupation(),
                request.otherIncomeSource(),
                request.monthlyIncomeMinor(),
                currency,
                request.officerUserId(),
                principal.userId()));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("member_no", memberNo);
        after.put("full_name", request.fullName().trim());
        after.put("phone_e164", phone);
        after.put("national_id", nin);
        after.put("branch_id", request.branchId());
        if (Boolean.TRUE.equals(request.confirmedNotDuplicate())) {
            after.put("confirmed_not_duplicate", true);
        }
        audit.record(AuditLog.Entry.created("lending.member.created", "lending.member", id, request.branchId(), after));
        linkKin(id, request.branchId(), nin, phone);

        return members.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    MemberPage list(List<UUID> requestedBranches, List<String> statuses, String q, Integer limit, String cursor) {
        Principal principal = CurrentPrincipal.require();
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        // Branch scope is authorisation (ADR-003), applied per permission (chapter 8 section 8.3.1).
        List<UUID> branchFilter = principal.branchFilter("lending.members.read", requestedBranches);
        String after = Cursor.decode(cursor).orElse(null);
        List<MemberListItem> rows = members.page(branchFilter, statuses, q, after, size + 1);
        boolean more = rows.size() > size;
        List<MemberListItem> items = more ? rows.subList(0, size) : rows;
        String next = more ? Cursor.encode(items.getLast().memberNo()) : null;
        return new MemberPage(List.copyOf(items), next);
    }

    /** A member outside the principal's branch scope is indistinguishable from a missing one. */
    @Transactional(readOnly = true)
    MemberResponse get(UUID memberId) {
        Principal principal = CurrentPrincipal.require();
        return members.findById(memberId)
                .filter(m -> principal.may("lending.members.read", m.branchId()))
                .orElseThrow(ApiException::notFound);
    }

    /**
     * FR-MEM-01 to FR-MEM-04 on edit, FR-MEM-10, FR-AUD-01. Omitted fields are unchanged; the audit
     * row carries only the fields that changed. Exiting a member will also require no open loan,
     * savings or investment account (FR-MEM-10); none exists before increment 4, so that check
     * arrives with the accounts.
     */
    @Transactional
    MemberResponse update(UUID memberId, String ifMatch, UpdateMemberRequest r) {
        MemberResponse before = lockForChange(memberId, "lending.members.update", ifMatch);
        String phone = r.phone() == null
                ? before.phoneE164()
                : PhoneNumbers.normaliseUganda(r.phone()).orElseThrow(() -> invalidPhone("phone"));
        String altPhone = r.altPhone() == null
                ? before.altPhoneE164()
                : r.altPhone().isBlank()
                        ? null
                        : PhoneNumbers.normaliseUganda(r.altPhone()).orElseThrow(() -> invalidPhone("alt_phone"));
        String idType = given(r.idType(), before.idType());
        String otherId = given(r.otherIdNumber(), before.otherIdNumber());
        boolean identityEdited = r.idType() != null || r.nationalId() != null || r.otherIdNumber() != null;
        String nin = identityEdited
                ? checkIdentity(idType, given(r.nationalId(), before.nationalId()), otherId, memberId)
                : before.nationalId();
        if (!phone.equals(before.phoneE164())) {
            checkPhoneShared(phone, memberId, r.confirmedNotDuplicate());
        }
        MemberResponse after = new MemberResponse(
                before.id(),
                before.branchId(),
                before.memberNo(),
                r.fullName() == null ? before.fullName() : r.fullName().trim(),
                given(r.firstName(), before.firstName()),
                given(r.lastName(), before.lastName()),
                phone,
                altPhone,
                idType,
                nin,
                otherId,
                given(r.dateOfBirth(), before.dateOfBirth()),
                given(r.gender(), before.gender()),
                given(r.maritalStatus(), before.maritalStatus()),
                given(r.district(), before.district()),
                given(r.subCounty(), before.subCounty()),
                given(r.village(), before.village()),
                given(r.location(), before.location()),
                given(r.occupation(), before.occupation()),
                given(r.otherIncomeSource(), before.otherIncomeSource()),
                given(r.monthlyIncomeMinor(), before.monthlyIncomeMinor()),
                before.currency(),
                before.kycStatus(),
                before.kycVerifiedBy(),
                before.kycVerifiedAt(),
                given(r.status(), before.status()),
                before.isBlacklisted(),
                before.blacklistReason(),
                given(r.officerUserId(), before.officerUserId()),
                before.source(),
                before.createdAt(),
                before.updatedAt(),
                before.version());
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        changes(before, after, was, now);
        if (now.isEmpty()) {
            return before;
        }
        if (Boolean.TRUE.equals(r.confirmedNotDuplicate()) && now.containsKey("phone_e164")) {
            now.put("confirmed_not_duplicate", true);
        }
        members.update(after);
        recheckKyc(memberId, before.branchId());
        if (now.containsKey("national_id") || now.containsKey("phone_e164")) {
            linkKin(
                    memberId,
                    before.branchId(),
                    now.containsKey("national_id") ? nin : null,
                    now.containsKey("phone_e164") ? phone : null);
        }
        audit.record(
                new AuditLog.Entry("lending.member.updated", "lending.member", memberId, before.branchId(), was, now));
        return members.findById(memberId).orElseThrow();
    }

    /**
     * FR-MEM-04: likely duplicates across the tenant. A match outside the caller's read scope
     * shows only its member number and reasons, the same disclosure as {@code duplicate_nin}.
     */
    @Transactional(readOnly = true)
    DuplicateCheckResponse duplicateCheck(DuplicateCheckRequest r) {
        String name = blankToNull(r.fullName());
        String phone = blankToNull(r.phone()) == null
                ? null
                : PhoneNumbers.normaliseUganda(r.phone()).orElseThrow(() -> invalidPhone("phone"));
        String nin = blankToNull(r.nationalId()) == null
                ? null
                : NationalIds.normaliseNin(r.nationalId()).orElseThrow(MemberService::invalidNin);
        if (name == null && phone == null && nin == null) {
            throw required("full_name", "Give at least one of full_name, phone or national_id.");
        }
        Principal principal = CurrentPrincipal.require();
        return new DuplicateCheckResponse(members.duplicateCandidates(name, phone, nin, null).stream()
                .map(c -> principal.may("lending.members.read", c.branchId())
                        ? new DuplicateCandidate(
                                c.id(),
                                c.memberNo(),
                                c.fullName(),
                                c.branchId(),
                                Masking.lastFour(c.phoneE164()),
                                Masking.lastFour(c.nationalId()),
                                c.reasons(),
                                true)
                        : new DuplicateCandidate(null, c.memberNo(), null, null, null, null, c.reasons(), false))
                .toList());
    }

    /** FR-MEM-05: a member pending verification is verified or rejected (a note is required to reject). */
    @Transactional
    MemberResponse decideKyc(UUID memberId, String ifMatch, KycDecisionRequest r) {
        boolean verified = "verified".equals(r.decision());
        String note = blankToNull(r.note());
        if (!verified && note == null) {
            throw required("note", "A note is required to reject.");
        }
        MemberResponse before = lockForChange(memberId, "lending.members.verify_kyc", ifMatch);
        if (!"pending_verification".equals(before.kycStatus())) {
            throw invalidTransition(
                    "KYC is " + before.kycStatus() + "; only a member pending verification can be decided.");
        }
        members.decideKyc(memberId, verified, CurrentPrincipal.require().userId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("kyc_status", r.decision());
        after.put("note", note);
        audit.record(new AuditLog.Entry(
                "lending.member.kyc_" + r.decision(),
                "lending.member",
                memberId,
                before.branchId(),
                Map.of("kyc_status", before.kycStatus()),
                after));
        return members.findById(memberId).orElseThrow();
    }

    /** FR-MEM-13. Lifting the flag clears the stored reason; the audit row keeps both. */
    @Transactional
    MemberResponse setBlacklist(UUID memberId, String ifMatch, BlacklistRequest r) {
        String reason = blankToNull(r.reason());
        if (r.isBlacklisted() && reason == null) {
            throw required("reason", "A reason is required to blacklist a member.");
        }
        MemberResponse before = lockForChange(memberId, "lending.members.blacklist", ifMatch);
        if (before.isBlacklisted() == r.isBlacklisted()) {
            throw invalidTransition(
                    "The member is already " + (before.isBlacklisted() ? "blacklisted." : "not blacklisted."));
        }
        members.setBlacklist(memberId, r.isBlacklisted(), r.isBlacklisted() ? reason : null);
        Map<String, Object> was = new LinkedHashMap<>();
        was.put("is_blacklisted", before.isBlacklisted());
        was.put("blacklist_reason", before.blacklistReason());
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("is_blacklisted", r.isBlacklisted());
        now.put("reason", reason);
        audit.record(new AuditLog.Entry(
                r.isBlacklisted() ? "lending.member.blacklisted" : "lending.member.blacklist_lifted",
                "lending.member",
                memberId,
                before.branchId(),
                was,
                now));
        return members.findById(memberId).orElseThrow();
    }

    /** Locks the member for a change: If-Match first, then scope (404 outside it), then version. */
    private MemberResponse lockForChange(UUID memberId, String permission, String ifMatch) {
        int expected = Versions.fromIfMatch(ifMatch);
        Principal principal = CurrentPrincipal.require();
        MemberResponse member = members.lockById(memberId)
                .filter(m -> principal.may(permission, m.branchId()))
                .orElseThrow(ApiException::notFound);
        if (member.version() != expected) {
            throw Versions.conflict(member.version());
        }
        return member;
    }

    /**
     * FR-MEM-07 backward: next of kin that other members named and that match this member's NIN
     * or phone are linked or suggested; one audit row on this member lists them.
     */
    private void linkKin(UUID memberId, UUID branchId, String nin, String phone) {
        List<UUID> touched = kin.linkToMember(memberId, nin, phone);
        if (!touched.isEmpty()) {
            audit.record(AuditLog.Entry.created(
                    "lending.member.kin_links_found",
                    "lending.member",
                    memberId,
                    branchId,
                    Map.of("next_of_kin_ids", touched)));
        }
    }

    /**
     * FR-MEM-05: moves an incomplete member, or a rejected one resubmitting, to
     * {@code pending_verification} once every input is present. Called after each change that can complete it (member edit, next of kin, ID image).
     */
    void recheckKyc(UUID memberId, UUID branchId) {
        members.markKycCompleteIfReady(memberId)
                .ifPresent(from -> audit.record(new AuditLog.Entry(
                        // A rejected member's new edit or document is a resubmission, audited as such.
                        "rejected".equals(from) ? "lending.member.kyc_resubmitted" : "lending.member.kyc_complete",
                        "lending.member",
                        memberId,
                        branchId,
                        Map.of("kyc_status", from),
                        Map.of("kyc_status", "pending_verification"))));
    }

    /** The fields of two versions of a record that differ, snake_case keys, for an audit row (FR-AUD-01). */
    static <R extends Record> void changes(R before, R after, Map<String, Object> was, Map<String, Object> now) {
        for (RecordComponent c : before.getClass().getRecordComponents()) {
            try {
                Object x = c.getAccessor().invoke(before);
                Object y = c.getAccessor().invoke(after);
                if (!Objects.equals(x, y)) {
                    String key =
                            c.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
                    was.put(key, x);
                    now.put(key, y);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberSummary> find(UUID memberId) {
        return members.findById(memberId)
                .map(m -> new MemberSummary(
                        m.id(),
                        m.branchId(),
                        m.memberNo(),
                        m.fullName(),
                        m.kycStatus(),
                        m.status(),
                        m.isBlacklisted(),
                        m.createdAt(),
                        m.monthlyIncomeMinor()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> linkedMembers(UUID memberId) {
        return members.linkedMembers(memberId);
    }

    /** The normalised NIN for {@code nin}, else null. {@code excludeId} is the member being edited. */
    private String checkIdentity(String idType, String nationalId, String otherIdNumber, UUID excludeId) {
        if (!"nin".equals(idType)) {
            if (!"none".equals(idType) && (otherIdNumber == null || otherIdNumber.isBlank())) {
                throw required("other_id_number", "Required for this id_type.");
            }
            return null;
        }
        String nin = NationalIds.normaliseNin(nationalId).orElseThrow(MemberService::invalidNin);
        members.memberNoByNationalId(nin, excludeId).ifPresent(existing -> {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "duplicate_nin",
                    "Duplicate NIN",
                    "Member " + existing + " already has this NIN.");
        });
        return nin;
    }

    /** FR-MEM-04: a phone already on another member needs the user's word that this is a different person. */
    private void checkPhoneShared(String phone, UUID excludeId, Boolean confirmed) {
        if (Boolean.TRUE.equals(confirmed)) {
            return;
        }
        List<String> holders = members.duplicateCandidates(null, phone, null, excludeId).stream()
                .map(Candidate::memberNo)
                .toList();
        if (!holders.isEmpty()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "duplicate_phone",
                    "Phone already registered",
                    "Member " + String.join(", ", holders)
                            + " already has this phone. Confirm this is a different person to continue.");
        }
    }

    static ApiException required(String field, String message) {
        return ApiException.validation(List.of(new FieldProblem(field, "required", message)));
    }

    static ApiException invalidTransition(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "invalid_status_transition", "Invalid status transition", detail);
    }

    static ApiException invalidNin() {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "invalid_nin",
                "Invalid national ID number",
                "The NIN must match C[MF] followed by 12 letters or digits.",
                List.of(new FieldProblem("national_id", "invalid_nin", "Not a valid NIN.")));
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static <T> T given(T value, T current) {
        return value == null ? current : value;
    }

    static ApiException invalidPhone(String field) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "invalid_phone",
                "Invalid phone number",
                "Use a Uganda mobile number such as 07XXXXXXXX or +2567XXXXXXXX.",
                List.of(new FieldProblem(field, "invalid_phone", "Not a valid phone number.")));
    }
}
