/**
 * Lending: investments (issue #152, increment 10; FR-INV-01 to FR-INV-12; chapter 3 section
 * 3.25.1 R-INV; chapter 6 tables {@code lending_investment_products}, {@code lending_investments},
 * {@code lending_investment_schedule_items}, {@code lending_investment_transactions}; chapter 7
 * section 7.11.16; ADR-031). Products with return rules, member investments, funding with a
 * checker above the tenant's threshold, the accrual and payout schedule written at funding, the
 * nightly job that accrues returns, makes them due and processes maturity (payout or rollover),
 * early withdrawal with a penalty, reversals, certificates and statements, and the maturity ladder.
 * Every money event posts through {@code post_entry} (ADR-004): investor funds are a liability,
 * returns an expense. {@link com.rincoltech.bms.lending.investments.InvestmentServicing} is the
 * port for callers without a request principal (the fabricated seed);
 * {@link com.rincoltech.bms.lending.investments.InvestmentMetrics} is what insights reads.
 */
@ApplicationModule(
        id = "lending.investments",
        displayName = "Lending: Investments",
        allowedDependencies = {
            "kernel",
            "core.tenancy",
            "core.audit",
            "core.jobs",
            "core.ledger",
            "core.approvals",
            "lending.members"
        })
package com.rincoltech.bms.lending.investments;

import org.springframework.modulith.ApplicationModule;
