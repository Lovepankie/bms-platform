package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.kernel.LiveRecordProbe;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Lending's answer to the trading tenant guard of onboarding (issue #127, spec section 5.4): the business trades when it has
 * a loan transaction or a lending journal (loans, savings and investments all post as {@code lending}). No lending record
 * is created by the onboarding pipeline yet (the lending template is #136), so every one counts, those of the existing
 * loans import included: importing into a business that already keeps its loans here is the deliberate top-up the
 * override is for.
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
                        SELECT EXISTS (SELECT 1 FROM lending_loan_transactions)
                            OR EXISTS (SELECT 1 FROM journal_entries WHERE source_module = 'lending')
                        """).query(Boolean.class).single();
    }
}
