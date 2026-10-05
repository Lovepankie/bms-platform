/**
 * Retail: valuation and profit reports (FR-RET-09, FR-RET-10; chapter 7 section 7.11.20). A read
 * model over the retail tables: stock value at cost and expected sales at the current prices per
 * branch and product (ADR-020 decisions 6 and 8), and daily profit per branch from the sale line
 * snapshots less usage and damage at cost. Cost, valuation at cost and profit leave this module
 * only for a caller holding {@code retail.profit.read}.
 */
@ApplicationModule(
        id = "retail.reports",
        displayName = "Retail: Reports",
        allowedDependencies = {"kernel", "core.tenancy", "core.ledger", "retail.stock"})
package com.rincoltech.bms.retail.reports;

import org.springframework.modulith.ApplicationModule;
