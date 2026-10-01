package com.rincoltech.bms.lending.collateral;

import java.util.Optional;
import java.util.UUID;

/** What the loans module may ask of the collateral register (pledges, cover). */
public interface CollateralLookup {

    Optional<CollateralSummary> find(UUID collateralId);

    /**
     * @param collateralValueMinor FR-COL-02: the latest forced sale value, else the estimate; null
     *     when neither is known
     */
    record CollateralSummary(
            UUID id,
            UUID memberId,
            UUID branchId,
            String collateralType,
            String custodyStatus,
            Long collateralValueMinor,
            String currency) {}
}
