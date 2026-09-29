package com.rincoltech.bms.lending.members.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.identity.CurrentPrincipal;
import com.rincoltech.bms.core.identity.Principal;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.NationalIds;
import com.rincoltech.bms.kernel.PhoneNumbers;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.lending.members.internal.MemberApi.CreateMemberRequest;
import com.rincoltech.bms.lending.members.internal.MemberApi.MemberListItem;
import com.rincoltech.bms.lending.members.internal.MemberApi.MemberPage;
import com.rincoltech.bms.lending.members.internal.MemberApi.MemberResponse;
import com.rincoltech.bms.lending.members.internal.MemberRepository.NewMember;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    MemberService(
            MemberRepository members,
            Branches branches,
            CurrentTenant currentTenant,
            TenantSequences sequences,
            AuditLog audit) {
        this.members = members;
        this.branches = branches;
        this.currentTenant = currentTenant;
        this.sequences = sequences;
        this.audit = audit;
    }

    /** FR-MEM-01, FR-MEM-02, FR-MEM-03, FR-AUD-01. One transaction: number, row and audit row. */
    @Transactional
    MemberResponse create(CreateMemberRequest request) {
        Principal principal = CurrentPrincipal.require();
        if (!principal.canSeeBranch(request.branchId())
                || branches.findActive(request.branchId()).isEmpty()) {
            throw ApiException.validation(
                    List.of(new FieldProblem("branch_id", "unknown_branch", "No such active branch in your scope.")));
        }
        String phone = PhoneNumbers.normaliseUganda(request.phone()).orElseThrow(() -> invalidPhone("phone"));
        String altPhone = request.altPhone() == null || request.altPhone().isBlank()
                ? null
                : PhoneNumbers.normaliseUganda(request.altPhone()).orElseThrow(() -> invalidPhone("alt_phone"));
        String nin = checkIdentity(request);

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
        audit.record(AuditLog.Entry.created("lending.member.created", "lending.member", id, request.branchId(), after));

        return members.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    MemberPage list(List<UUID> requestedBranches, List<String> statuses, String q, Integer limit, String cursor) {
        Principal principal = CurrentPrincipal.require();
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        List<UUID> branchFilter = branchFilter(principal, requestedBranches);
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
                .filter(m -> principal.canSeeBranch(m.branchId()))
                .orElseThrow(ApiException::notFound);
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
                        m.isBlacklisted()));
    }

    /**
     * Branch scope is authorisation (ADR-003): an all-branch principal gets what it asked for
     * (everything when it asked for nothing); a scoped principal gets the intersection.
     */
    static List<UUID> branchFilter(Principal principal, List<UUID> requested) {
        List<UUID> asked = requested == null ? List.of() : requested;
        if (principal.allBranches()) {
            return asked.isEmpty() ? null : asked;
        }
        if (asked.isEmpty()) {
            return List.copyOf(principal.branchIds());
        }
        return asked.stream().filter(principal::canSeeBranch).toList();
    }

    private String checkIdentity(CreateMemberRequest request) {
        if (!"nin".equals(request.idType())) {
            if (!"none".equals(request.idType())
                    && (request.otherIdNumber() == null
                            || request.otherIdNumber().isBlank())) {
                throw ApiException.validation(
                        List.of(new FieldProblem("other_id_number", "required", "Required for this id_type.")));
            }
            return null;
        }
        String nin = NationalIds.normaliseNin(request.nationalId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "invalid_nin",
                        "Invalid national ID number",
                        "The NIN must match C[MF] followed by 12 letters or digits.",
                        List.of(new FieldProblem("national_id", "invalid_nin", "Not a valid NIN."))));
        members.memberNoByNationalId(nin).ifPresent(existing -> {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "duplicate_nin",
                    "Duplicate NIN",
                    "Member " + existing + " already has this NIN.");
        });
        return nin;
    }

    private static ApiException invalidPhone(String field) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "invalid_phone",
                "Invalid phone number",
                "Use a Uganda mobile number such as 07XXXXXXXX or +2567XXXXXXXX.",
                List.of(new FieldProblem(field, "invalid_phone", "Not a valid phone number.")));
    }
}
