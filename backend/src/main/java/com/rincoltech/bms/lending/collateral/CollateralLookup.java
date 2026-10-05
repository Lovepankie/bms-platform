package com.rincoltech.bms.lending.collateral;

import java.util.Optional;
import java.util.UUID;

/** What the loans module may ask of the collateral register (pledges, cover). */
public interface CollateralLookup {

    Optional<CollateralSummary> find(UUID collateralId);

    /**
     * As {@link #find}, holding the item's row lock until the caller's transaction ends. Pledging
     * and release both take this lock before they check, so one item cannot be pledged to two
     * loans at once, nor pledged while it is being released (FR-COL-01, FR-COL-04).
     */
    Optional<CollateralSummary> lockForPledge(UUID collateralId);

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
