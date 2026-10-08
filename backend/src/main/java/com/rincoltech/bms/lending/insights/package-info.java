/**
 * Lending: insights (issue #153, expectation 7 of the pilot brief; ADR-030; chapter 14 section
 * 14.9; {@code docs/specs/lending-insights-metrics.md}). A read model over the loan tables and the
 * ledger: the morning brief, the loan portfolio, revenue from posted journal lines, member
 * activity, drill-down tables with CSV export, the nightly per-loan snapshot of chapter 6
 * ({@code lending_loan_daily_snapshots}, until the arrears job of increment 6 takes it over) and
 * the owner's daily digest through the notification outbox.
 *
 * <p>It reads other lending modules' tables with plain SQL, read-only, under row-level security
 * (ADR-030 decision 1); it never writes them. Savings and investments contribute their panels
 * through {@link com.rincoltech.bms.lending.insights.InsightsPanel}: adapters here over
 * {@code SavingsMetrics} and {@code InvestmentMetrics}, shown only to a tenant with savings
 * accounts or investments.
 */
@ApplicationModule(
        id = "lending.insights",
        displayName = "Lending: Insights",
        allowedDependencies = {
            "kernel",
            "core.tenancy",
            "core.audit",
            "core.jobs",
            "core.notifications",
            "lending.savings",
            "lending.investments"
        })
package com.rincoltech.bms.lending.insights;

import org.springframework.modulith.ApplicationModule;
