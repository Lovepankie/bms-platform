/**
 * Lending: loans (FR-ORG, FR-DIS, FR-REP, FR-LCL, later FR-ARR; chapter 6 tables
 * {@code lending_loans}, {@code lending_schedule_items}, {@code lending_loan_transactions},
 * {@code lending_repayment_allocations}; chapter 7 section 7.11.13). One row from application to
 * closure; the status table of chapter 3 section 3.18 is the only way a loan moves, and every move
 * writes {@code lending_loan_status_history}. Every money event posts through {@code post_entry}
 * (ADR-004) and the maker-checker actions are registered here (ADR-015, ADR-026).
 * {@link com.rincoltech.bms.lending.loans.LoanServicing} is the port for callers without a request
 * principal (the fabricated seed, later the import).
 */
@ApplicationModule(
        id = "lending.loans",
        displayName = "Lending: Loans",
        allowedDependencies = {
            "kernel",
            "core.tenancy",
            "core.audit",
            "core.jobs",
            "core.ledger",
            "core.approvals",
            "core.operations",
            "lending.members",
            "lending.products",
            "lending.collateral"
        })
package com.rincoltech.bms.lending.loans;

import org.springframework.modulith.ApplicationModule;
