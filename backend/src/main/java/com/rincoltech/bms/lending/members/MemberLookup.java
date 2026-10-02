package com.rincoltech.bms.lending.members;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What other lending sub-domains (loans, savings, collateral) may ask the members module. */
public interface MemberLookup {

    Optional<MemberSummary> find(UUID memberId);

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
