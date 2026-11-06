package com.rincoltech.bms.core.ledger.internal;

import com.rincoltech.bms.kernel.LiveRecordProbe;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The ledger's answer to the trading tenant guard of onboarding (issue #127, spec section 5.4): the business trades when it
 * has a journal posted by a core module (today the payments module's receipts, {@code core.payments}). The verticals answer
 * for their own journals, {@code retail} and {@code lending}, since only they know which of theirs an import wrote.
 */
@Component
class CoreJournalActivity implements LiveRecordProbe {

    private final JdbcClient jdbc;

    CoreJournalActivity(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String area() {
        return "ledger";
    }

    @Override
    public boolean hasLiveRecords() {
        // The opening journals an import posts (source core.imports, issue #133) are not trading: spec section 5.4
        // counts "a journal not posted by an import".
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM journal_entries"
                        + " WHERE source_module LIKE 'core.%' AND source_module <> 'core.imports')")
                .query(Boolean.class)
                .single();
    }
}
