package com.rincoltech.bms.lending.collateral;

import java.util.UUID;

/**
 * Registered by the module that pledges collateral to loans (chapter 5 section 5.4.3), so the
 * register can refuse to release an item that still secures an open loan (FR-COL-04) without
 * depending on that module. With no registration nothing is pledged.
 */
public interface CollateralPledges {

    /**
     * True while an unreleased pledge holds the item. A pledge is released when its loan is
     * cancelled, rejected or closed; a written-off loan keeps its collateral for recovery. The
     * caller holds the item's row lock (the release paths lock it before they ask).
     */
    boolean securesOpenLoan(UUID collateralId);
}
