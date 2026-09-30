/**
 * Lending: the collateral register (FR-COL-01 to FR-COL-05; chapter 6 section 6.7, chapter 7
 * section 7.11.14). Items belong to a member and sit in the member's home branch; custody changes
 * are an append-only timeline; release is a maker-checker action. Links to loans
 * ({@code lending_loan_collateral}) arrive with increment 4.
 */
@ApplicationModule(
        id = "lending.collateral",
        displayName = "Lending: Collateral",
        allowedDependencies = {
            "kernel",
            "core.tenancy",
            "core.audit",
            "core.documents",
            "core.approvals",
            "lending.members"
        })
package com.rincoltech.bms.lending.collateral;

import org.springframework.modulith.ApplicationModule;
