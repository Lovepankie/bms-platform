package com.rincoltech.bms.retail.purchasing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Imported suppliers and restocks (ADR-020 decision 9, FR-RET-12), in the caller's transaction. A
 * historical purchase is flagged {@code historical}, moves stock through the stock ledger's
 * historical path and posts no journal, so it creates no supplier payable. It does not change the
 * product's prices: the import sets those from the product master and writes the price history.
 */
public interface PurchaseHistory {

    /** The supplier with this name ignoring case, created when there is none. */
    Ensured ensureSupplier(String name);

    /**
     * One purchase of one line, recorded as paid in cash because no payment is known.
     *
     * @param qtyByBranch positive quantities, in branch order
     * @return the purchase's id
     */
    UUID importPurchase(
            UUID supplierId,
            UUID productId,
            long costMinor,
            Long sellMinor,
            Map<UUID, BigDecimal> qtyByBranch,
            LocalDate purchasedOn,
            Instant purchasedAt,
            String note);

    record Ensured(UUID id, boolean created) {}
}
