package com.rincoltech.bms.retail.catalogue;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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
     * Issue #698 (ADR-109): the locks of these units' keys for the rest of the transaction, taken in key order before any
     * row, as the CSV import takes them, so two imports never wait on each other in a circle.
     */
    void lockUnits(java.util.Collection<String> names);

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
     * Issue #880: creates these products, each with its {@code initial} history row, one statement batch per table, so a
     * page of an import costs two round trips and no lookup. The caller read the tenant's products
     * ({@link #productsByCode}) and found none with any of these codes ignoring case; the unique index on the code refuses
     * one made meanwhile, and with it the caller's transaction.
     */
    void createProducts(List<NewProduct> products);

    /**
     * Issue #352, for a page of products in one query (issue #880): for each product given whose name (folded to lower
     * case, spaces and punctuation collapsed) and unit other products of the tenant share, those others in code order, for
     * the importer to report as an anomaly. A product never counts as its own twin; one without twins is absent.
     */
    Map<UUID, List<Product>> sameName(Collection<UUID> productIds);

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

    /** A product {@link #createProducts} makes: the code as {@link RetailCatalogue#normaliseCode} leaves it. */
    record NewProduct(
            UUID id,
            String code,
            String description,
            UUID categoryId,
            UUID unitId,
            long costMinor,
            long sellMinor,
            boolean active) {}
}
