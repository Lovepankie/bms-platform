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
