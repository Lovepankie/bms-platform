/**
 * Lending: loans (FR-ORG, later FR-DIS, FR-REP, FR-ARR, FR-LCL; chapter 6 table
 * {@code lending_loans}, chapter 7 section 7.11.13). One row from application to closure; the
 * status table of chapter 3 section 3.18 is the only way a loan moves, and every move writes
 * {@code lending_loan_status_history}.
 */
@ApplicationModule(
        id = "lending.loans",
        displayName = "Lending: Loans",
        allowedDependencies = {
            "kernel",
            "core.tenancy",
            "core.audit",
            "core.jobs",
            "lending.members",
            "lending.products",
            "lending.collateral"
        })
package com.rincoltech.bms.lending.loans;

import org.springframework.modulith.ApplicationModule;
