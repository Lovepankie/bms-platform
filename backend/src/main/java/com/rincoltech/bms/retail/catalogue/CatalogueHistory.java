package com.rincoltech.bms.retail.catalogue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The catalogue side of the retail import (ADR-020 decision 9, FR-RET-12), in the caller's
 * transaction. Names and codes are matched ignoring case, so a re-run finds what the first run
 * created.
 */
public interface CatalogueHistory {

    Ensured ensureCategory(String name);

    Ensured ensureUnit(String name);

    /**
     * The product with this code ignoring case. A new product gets an {@code initial} history row
     * with these prices. An existing one whose prices differ takes them with an {@code import}
     * history row ({@code changed}); its other fields are left as they are.
     */
    Ensured ensureProduct(
            String code,
            String description,
            UUID categoryId,
            UUID unitId,
            long costMinor,
            long sellMinor,
            boolean active);

    /** Every product of the tenant by its code, lower case. */
    Map<String, Product> productsByCode();

    /**
     * An {@code import} history row for a price change the source recorded between two restocks,
     * dated when it happened. The product's current prices are not touched.
     *
     * @param purchaseId the historical purchase that carried the new prices
     */
    void importPriceChange(
            UUID productId,
            long oldCostMinor,
            long newCostMinor,
            long oldSellMinor,
            long newSellMinor,
            UUID purchaseId,
            Instant changedAt,
            String reason);

    record Ensured(UUID id, boolean created, boolean changed) {}

    record Product(UUID id, String code, long costMinor, long sellMinor) {}
}
