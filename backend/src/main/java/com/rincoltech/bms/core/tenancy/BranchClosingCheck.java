package com.rincoltech.bms.core.tenancy;

import java.util.List;
import java.util.UUID;

/**
 * Lets a module that keeps goods tell the tenancy module what still hangs on a branch before it is closed or marked as
 * not stocking (issue #329, ADR-041): the stock it still holds and the transfers still on their way. The core iterates the
 * beans of this type and never names a vertical. Nothing here moves or posts anything; it only reports.
 */
public interface BranchClosingCheck {

    /** What one module found on the branch; empty when it holds nothing. */
    List<Finding> findings(UUID branchId);

    /**
     * @param kind {@code stock}, {@code transfers_in_flight} or {@code open_stocktakes}
     * @param count products with a balance (stock), transfers on their way, or stock-takes started and not committed
     * @param valueMinor the value at the products' current cost, in minor units of the tenant's currency
     */
    record Finding(String kind, int count, long valueMinor) {}
}
