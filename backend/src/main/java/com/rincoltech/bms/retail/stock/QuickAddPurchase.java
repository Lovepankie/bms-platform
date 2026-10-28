package com.rincoltech.bms.retail.stock;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Goods just bought, added through Quick add and paid from the branch's cash or mobile money (issue #646, ADR-101 amendment of
 * 28 Oct 2026). Stock cannot depend on purchasing, so purchasing supplies the restock here as a port: one purchase of one line,
 * dated today, with no supplier, recorded exactly as Buy stock records it (its movement, its journal entry against the
 * payment's account, the item's buying price and the cash book). Runs in Quick add's transaction and throws an
 * {@code ApiException} to refuse.
 */
public interface QuickAddPurchase {

    /**
     * @param branchId where the goods are, already checked for {@code retail.purchase.create}
     * @param qty how many, in the item's base unit, more than 0
     * @param costMinor what one cost, more than 0
     * @param method {@code cash} or {@code mobile_money}
     */
    Bought buy(UUID branchId, UUID productId, BigDecimal qty, long costMinor, String method);

    /** The purchase recorded and the money that left the account. */
    record Bought(UUID purchaseId, String purchaseNo, long totalMinor) {}
}
