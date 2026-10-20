package com.rincoltech.bms.retail.catalogue;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
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
     * with these prices. An existing one is left exactly as it is, prices included, and no history
     * row is written (issue #73): {@code changed} only says its prices differ from these.
     */
    Ensured ensureProduct(
            String code,
            String description,
            UUID categoryId,
            UUID unitId,
            long costMinor,
            long sellMinor,
            boolean active);

    /**
     * Issue #352: the code of another product of the tenant with the same name (folded to lower case, spaces and
     * punctuation collapsed) in the same unit, for the importer to report as an anomaly. The product with the given
     * code never counts. Empty when the name is not repeated.
     */
    Optional<String> otherWithSameName(String code, String description, UUID unitId);

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
