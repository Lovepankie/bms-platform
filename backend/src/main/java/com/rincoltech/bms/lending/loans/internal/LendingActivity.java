package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.kernel.LiveRecordProbe;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Lending's answer to the trading tenant guard of onboarding (issue #127, spec section 5.4): the business trades when it has
 * a loan transaction or a lending journal (loans, savings and investments all post as {@code lending}). Since issue #136 the
 * historic transactions of a loan the framework imported ({@code lending_loans_v1}, registered in {@code import_refs} as
 * {@code lending.loan}) are not trading, as retail's imported sales are not, so a resumed or later run of the register is
 * not refused for what an earlier one wrote. Every other one counts, those of the existing loans import (ADR-071)
 * included: importing into a business that already keeps its loans here is the deliberate top-up the override is for.
 */
@Component
class LendingActivity implements LiveRecordProbe {

    private final JdbcClient jdbc;

    LendingActivity(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String area() {
        return "lending";
    }

    @Override
    public boolean hasLiveRecords() {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM lending_loan_transactions t
                                        WHERE NOT (t.is_historic AND t.source = 'import'
                                                   AND EXISTS (SELECT 1 FROM import_refs r
                                                                WHERE r.target_id = t.loan_id
                                                                  AND r.target_type = 'lending.loan')))
                            OR EXISTS (SELECT 1 FROM journal_entries WHERE source_module = 'lending')
                        """).query(Boolean.class).single();
    }
}
