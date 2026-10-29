package com.rincoltech.bms.retail.imports.internal;

import com.rincoltech.bms.kernel.LiveRecordProbe;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Retail's answer to the trading tenant guard of onboarding (issue #127, spec section 5.4): the business trades when it has
 * a sale, or a retail journal, that the {@code import-retail} command did not write. The command registers every sale it
 * imports and both of its opening journals in {@code retail_import_refs}, and its history posts no other journal, so
 * anything outside those refs was made in the app.
 */
@Component
class RetailActivity implements LiveRecordProbe {

    private final JdbcClient jdbc;

    RetailActivity(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String area() {
        return "retail";
    }

    @Override
    public boolean hasLiveRecords() {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM retail_sales s
                                        WHERE NOT EXISTS (SELECT 1 FROM retail_import_refs r WHERE r.target_id = s.id))
                            OR EXISTS (SELECT 1 FROM journal_entries e
                                        WHERE e.source_module = 'retail'
                                          AND NOT EXISTS (SELECT 1 FROM retail_import_refs r WHERE r.target_id = e.id))
                        """).query(Boolean.class).single();
    }
}
