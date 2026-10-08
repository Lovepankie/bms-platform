/**
 * Lending: the fabricated seed (issue #108; ADR-026; {@code docs/runbooks/seed-lending.md}). The
 * {@code seed-lending} command fills an empty lending tenant on staging with fabricated members,
 * products, applications and serviced loans, savings products and accounts with twelve months of
 * movements (#151), and investments with twelve months of history (#152), so a pilot tenant can try
 * the screens with fake data.
 * It writes the reference rows (members, products, applications) directly, as the import does, and
 * every money event through {@link com.rincoltech.bms.lending.loans.LoanServicing},
 * {@link com.rincoltech.bms.lending.savings.SavingsServicing} and
 * {@link com.rincoltech.bms.lending.investments.InvestmentServicing}, so the ledger and
 * the subledger agree exactly as they do for staff. It is a command, never part of the application's
 * startup, and it refuses a production environment and any tenant that already holds lending data.
 */
@ApplicationModule(
        id = "lending.seed",
        displayName = "Lending: Fabricated Seed",
        allowedDependencies = {
            "kernel",
            "core.tenancy",
            "core.audit",
            "core.jobs",
            "lending.loans",
            "lending.savings",
            "lending.investments"
        })
package com.rincoltech.bms.lending.seed;

import org.springframework.modulith.ApplicationModule;
