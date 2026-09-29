package com.rincoltech.bms.lending.members;

import java.util.Optional;
import java.util.UUID;

/** What other lending sub-domains (loans, savings, collateral) may ask the members module. */
public interface MemberLookup {

    Optional<MemberSummary> find(UUID memberId);

    record MemberSummary(
            UUID id,
            UUID branchId,
            String memberNo,
            String fullName,
            String kycStatus,
            String status,
            boolean blacklisted) {}
}
