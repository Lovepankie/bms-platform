package com.rincoltech.bms.retail.catalogue;

import java.util.Optional;
import java.util.UUID;

/**
 * The catalogue's public API for the other retail modules: a product's current prices, read in the
 * caller's transaction. A sale snapshots {@code costMinor} and {@code sellMinor} from here
 * (ADR-020 decisions 5 and 6).
 */
public interface RetailCatalogue {

    Optional<ProductSnapshot> find(UUID productId);

    /**
     * Takes the product's row lock for the rest of the caller's transaction. A caller that changes
     * several products locks them in id order first, so concurrent events cannot deadlock.
     */
    Optional<ProductSnapshot> lock(UUID productId);

    /**
     * FR-RET-06, ADR-020 decision 5: a restock line's prices become the product's current prices in
     * the caller's transaction, with a {@code purchase} history row naming the purchase and an audit
     * row. A null sell price keeps the current one. Nothing is written when neither price changes.
     *
     * @return the product as it is now
     */
    ProductSnapshot applyPurchasePrices(UUID productId, long costMinor, Long sellMinor, UUID purchaseId);

    record ProductSnapshot(
            UUID id,
            String code,
            String description,
            String unit,
            long costMinor,
            long sellMinor,
            String currency,
            boolean active) {}
}
