package com.rincoltech.bms.lending.members;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Members and next of kin from a loan register imported by the template {@code lending_loans_v1} (issue #136, chapter 13
 * sections 13.9 and 13.10): the business's members a borrower is matched to, by NIN first and then by phone, and the
 * members and next of kin the import creates. Every call runs in the caller's transaction, bound to the business, so it
 * reads and writes only the business's own rows (row-level security) and a refused batch leaves nothing behind. An
 * existing member's fields are never changed by an import.
 */
public interface MemberImports {

    /** The business's member with this NIN (exactly as stored, {@code ^C[MF][A-Z0-9]{12}$}), if any. */
    Optional<Held> byNin(String nationalId);

    /** The business's members whose primary phone is this E.164 number, oldest first. */
    List<Held> byPhone(String phoneE164);

    /** Whether the business holds a member with this id: the target of the review's {@code link_member}. */
    boolean exists(UUID memberId);

    /**
     * Creates a member of {@code source = import} with KYC {@code incomplete}, numbered from the business's member
     * sequence, within the plan's member limit (422 {@code plan_limit_reached}), and audits it as
     * {@code lending.member.imported}. Returns its id.
     */
    UUID create(NewMember member);

    /** Creates one next of kin of a member, linked to another member when {@code linkedMemberId} is set. Returns its id. */
    UUID addKin(NewKin kin);

    /**
     * Whether the member already has this next of kin (chapter 13 section 13.9: the same NIN, or the same phone, or the same
     * name ignoring case when neither is given), and whether the member has a primary next of kin, so a duplicate collapses
     * to the record already there.
     */
    KinHeld kinOf(UUID memberId, String nationalId, String phoneE164, String fullName);

    /** {@link #kinOf}: the matching record if any, and whether the member has a primary one. */
    record KinHeld(UUID kinId, boolean hasPrimary) {}

    /** A member the business holds, as an import matches a borrower to it. The name is compared, never shown. */
    record Held(UUID id, UUID branchId, String nationalId, String phoneE164, String fullName) {}

    /**
     * A member as the register gives it (chapter 13 section 13.8 rows 2 to 6, 12 and 13): {@code idType} {@code nin}
     * with {@code nationalId}, {@code other} with {@code otherIdNumber}, or {@code none}.
     */
    record NewMember(
            UUID branchId,
            String fullName,
            String phoneE164,
            String altPhoneE164,
            String idType,
            String nationalId,
            String otherIdNumber,
            String maritalStatus,
            String location,
            String occupation,
            String otherIncomeSource,
            String currency,
            UUID officerUserId,
            UUID createdBy) {}

    /**
     * A next of kin as the register gives it (13.8 rows 7 to 11): {@code linkMethod} {@code nin} or {@code phone} and
     * {@code linkStatus} {@code confirmed} or {@code suggested} when it is linked to a member, else both null and
     * {@code none}.
     */
    record NewKin(
            UUID memberId,
            String fullName,
            String phoneE164,
            String nationalId,
            String relationship,
            String relationshipText,
            String location,
            boolean primary,
            UUID linkedMemberId,
            String linkMethod,
            String linkStatus,
            UUID createdBy) {}
}
