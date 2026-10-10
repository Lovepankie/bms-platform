/**
 * Lending: savings (FR-SAV-01 to FR-SAV-07; chapter 6 tables {@code lending_savings_products},
 * {@code lending_savings_accounts}, {@code lending_savings_transactions},
 * {@code lending_savings_daily_balances}, {@code lending_savings_interest_postings}; chapter 7
 * section 7.11.15; ADR-032). Products, member accounts (any number per member), deposits,
 * withdrawals with the {@code savings_withdrawal} maker-checker action above the tenant's threshold,
 * reversals through {@code savings_reversal}, freeze, dormancy, closure, the nightly end of day
 * (end-of-day balances, interest posting per product period, dormancy), statements and the two
 * savings reports of chapter 14 section 14.5. Every movement posts a balanced journal through
 * {@code post_entry} in the same transaction (member savings are a liability, account 2010).
 * {@link com.rincoltech.bms.lending.savings.SavingsServicing} is the port for callers without a
 * request principal (the fabricated seed, later the import), and
 * {@link com.rincoltech.bms.lending.savings.SavingsMetrics} the figures the insights page reads.
 */
@ApplicationModule(
        id = "lending.savings",
        displayName = "Lending: Savings",
        allowedDependencies = {
            "kernel",
            "core.tenancy",
            "core.audit",
            "core.jobs",
            "core.ledger",
            "core.operations",
            "core.approvals",
            "core.notifications",
            "lending.members"
        })
package com.rincoltech.bms.lending.savings;

import org.springframework.modulith.ApplicationModule;
