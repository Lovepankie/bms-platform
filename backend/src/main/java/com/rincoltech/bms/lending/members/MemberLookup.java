package com.rincoltech.bms.lending.members;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What other lending sub-domains (loans, savings, collateral) may ask the members module. */
public interface MemberLookup {

    Optional<MemberSummary> find(UUID memberId);

    /**
     * {@link #find}, with the member's row locked until the caller's transaction ends. Serialises
     * per-member rules that count rows of another module, such as FR-ORG-07's loan limit.
     */
    Optional<MemberSummary> lock(UUID memberId);

    /**
     * The member's primary phone in E.164, for a transaction receipt SMS (chapter 11 section 11.3).
     * Personal data: never logged, never returned in a response of the calling module.
     */
    Optional<String> smsPhone(UUID memberId);

    /**
     * Members linked to this one through next of kin (the relationship view of chapter 6 section
     * 6.7), both directions; used for the exposure rule of chapter 3 section 3.18.1.
     */
    List<UUID> linkedMembers(UUID memberId);

    record MemberSummary(
            UUID id,
            UUID branchId,
            String memberNo,
            String fullName,
            String kycStatus,
            String status,
            boolean blacklisted,
            Instant createdAt,
            Long monthlyIncomeMinor) {}
}
