/**
 * Retail: the catalogue (FR-RET-01, FR-RET-02, FR-RET-14; chapter 6 tables
 * {@code retail_categories}, {@code retail_units}, {@code retail_products},
 * {@code retail_price_history}; chapter 7 section 7.11.20). Copies the shape of
 * {@code lending.members}. A product's cost and sell price change only inside the event that
 * carries them, and every change writes an append-only history row in the same transaction
 * (ADR-020 decision 5). Retail never depends on a lending package.
 */
@ApplicationModule(
        id = "retail.catalogue",
        displayName = "Retail: Catalogue",
        allowedDependencies = {"kernel", "core.tenancy", "core.audit"})
package com.rincoltech.bms.retail.catalogue;

import org.springframework.modulith.ApplicationModule;
