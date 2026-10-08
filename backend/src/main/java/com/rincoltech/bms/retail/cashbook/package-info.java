/**
 * Retail: the cash book (FR-RET-17 to FR-RET-32; ADR-022; chapter 6 section 6.11.5 tables
 * {@code retail_expense_categories}, {@code retail_expense_items}, {@code retail_cash_parties},
 * {@code retail_daily_savings}, {@code retail_cash_bankings}, {@code retail_cash_withdrawals},
 * {@code retail_expenses}, {@code retail_advances}, {@code retail_advance_repayments}; chapter 7
 * section 7.11.21). Daily savings, cash banked, withdrawals from the bank, expenses, and advances to
 * the owner or related parties with their repayments. Each event posts one balanced entry in its
 * own branch in its own transaction; a void posts the reversal on the void date; nothing is edited
 * or deleted. Savings amounts, the suggestion and every figure that embeds savings or cash
 * purchases leave this module only for a caller holding {@code retail.profit.read} (ADR-022
 * decision 13). It never depends on {@code lending.*}: an advance is not a loan (ADR-022 decision 2).
 * Only {@code retail.imports} depends on it, for the pilot's history.
 */
@ApplicationModule(
        id = "retail.cashbook",
        displayName = "Retail: Cash book",
        allowedDependencies = {
            "kernel",
            "core.tenancy",
            "core.audit",
            "core.ledger",
            "core.documents",
            "retail.sales",
            "retail.purchasing",
            "retail.reports",
            "retail.stock"
        })
package com.rincoltech.bms.retail.cashbook;

import org.springframework.modulith.ApplicationModule;
