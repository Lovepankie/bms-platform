/**
 * Retail: sales and credit buyers (FR-RET-04, FR-RET-05, FR-RET-11, FR-RET-14; chapter 6 tables
 * {@code retail_customers}, {@code retail_sales}, {@code retail_sale_lines}; chapter 7 section
 * 7.11.20). A sale snapshots each line's unit cost and unit price, moves stock through
 * {@code StockLedger} and posts its revenue and its cost of goods sold as two entries for its
 * branch, all in one transaction; a void reverses the movements and both entries.
 */
@ApplicationModule(
        id = "retail.sales",
        displayName = "Retail: Sales",
        allowedDependencies = {"kernel", "core.tenancy", "core.audit", "core.ledger", "retail.catalogue", "retail.stock"
        })
package com.rincoltech.bms.retail.sales;

import org.springframework.modulith.ApplicationModule;
