package com.rincoltech.bms.lending.members;

import com.rincoltech.bms.lending.members.MemberLookup.MemberSummary;
import java.util.Optional;
import java.util.UUID;

/**
 * Adding a member from a line of a loan register that the existing loans import brings in (issue #626, ADR-102): the name
 * and phone the register gives, and no ID (the member's KYC is completed later, as for any member). Every rule of the
 * member form holds: the caller's scope in the branch, a phone in a Uganda form that no other member holds, and the plan's
 * member limit; the audit row is the form's. Runs in the caller's transaction, so a refused import adds no member.
 */
public interface MemberIntake {

    MemberSummary addFromRegister(UUID branchId, String fullName, String phone);

    /**
     * The rules {@link #addFromRegister} applies, other than whether the phone is held already, run without writing: the
     * caller's scope in an active branch, the name the member form takes and a Uganda phone. The problem in plain words that
     * name no member, or empty when the member could be added. Never throws for a refusal, so a check can go on.
     */
    Optional<String> problemAdding(UUID branchId, String fullName, String phone);

    /** The member {@link #addFromRegister} would make, before it is made: the same starting KYC and status, no number yet. */
    MemberSummary preview(UUID branchId, String fullName, String phone);

    /**
     * Refuses with 422 {@code plan_limit_reached} when {@code newMembers} more active members would pass the plan's member
     * limit, so a check of an import says what its commit would: read-only, the limit {@link #addFromRegister} applies, in a
     * transaction of its own so the caller may catch the refusal and go on.
     */
    void checkRoomFor(int newMembers);
}
