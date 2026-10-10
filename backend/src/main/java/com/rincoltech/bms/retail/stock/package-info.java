/**
 * Retail: stock (FR-RET-03, FR-RET-08, FR-RET-11; chapter 6 tables {@code retail_stock_movements},
 * {@code retail_stock_balances}, {@code retail_stocktakes}, {@code retail_stocktake_lines}; chapter
 * 7 section 7.11.20). Stock is a ledger of append-only movements (ADR-020 decision 3);
 * {@link com.rincoltech.bms.retail.stock.StockLedger} is the only writer of movements and
 * balances, keeping both in one transaction under the balance row's lock. The module's public
 * API also carries what every retail money event shares: {@link
 * com.rincoltech.bms.retail.stock.RetailBooks} (posting by {@code system_key}),
 * {@link com.rincoltech.bms.retail.stock.RetailBranchContext} and
 * {@link com.rincoltech.bms.retail.stock.Quantities}. The {@code Idempotency-Key} protocol
 * (chapter 7 section 7.8) is the core's {@link com.rincoltech.bms.core.operations.Idempotency}.
 */
@ApplicationModule(
        id = "retail.stock",
        displayName = "Retail: Stock",
        allowedDependencies = {
            "kernel",
            "core.tenancy",
            "core.audit",
            "core.ledger",
            "core.jobs",
            "core.operations",
            "retail.catalogue"
        })
package com.rincoltech.bms.retail.stock;

import org.springframework.modulith.ApplicationModule;
