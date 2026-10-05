/**
 * Retail: purchasing (FR-RET-06, FR-RET-11, FR-RET-14; chapter 6 tables {@code retail_suppliers},
 * {@code retail_purchases}, {@code retail_purchase_lines}; chapter 7 section 7.11.20). A restock
 * sets each product's cost and sell price, with a history row, in the same transaction as its
 * stock movements and its journal entries (ADR-020 decision 5): the price can no longer fail to
 * reach the product, the bug class ADR-020 was written to prevent.
 */
@ApplicationModule(
        id = "retail.purchasing",
        displayName = "Retail: Purchasing",
        allowedDependencies = {"kernel", "core.tenancy", "core.audit", "core.ledger", "retail.catalogue", "retail.stock"
        })
package com.rincoltech.bms.retail.purchasing;

import org.springframework.modulith.ApplicationModule;
