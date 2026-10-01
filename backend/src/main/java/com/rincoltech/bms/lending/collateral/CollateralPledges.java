package com.rincoltech.bms.lending.collateral;

import java.util.UUID;

/**
 * Registered by the module that pledges collateral to loans (chapter 5 section 5.4.3), so the
 * register can refuse to release an item that still secures an open loan (FR-COL-04) without
 * depending on that module. With no registration nothing is pledged.
 */
public interface CollateralPledges {

    /** True while a loan that is not closed, cancelled, rejected or written off holds the item. */
    boolean securesOpenLoan(UUID collateralId);
}
