package com.rincoltech.bms.retail.imports.internal;

import com.rincoltech.bms.kernel.LiveRecordProbe;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Retail's answer to the trading tenant guard of onboarding (issue #127, spec section 5.4): the business trades when it has
 * a sale, or a retail journal, that no import wrote. Every import registers each sale it writes and each journal it posts
 * in {@code import_refs} (since issue #135 the {@code import-retail} command too, and V145 copied what the command wrote
 * before into it from {@code retail_import_refs}), and history posts no other journal, so anything outside those refs was
 * made in the app.
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
                                        WHERE NOT EXISTS (SELECT 1 FROM import_refs r WHERE r.target_id = s.id)
                                          AND NOT EXISTS (SELECT 1 FROM retail_import_refs o WHERE o.target_id = s.id))
                            OR EXISTS (SELECT 1 FROM journal_entries e
                                        WHERE e.source_module = 'retail'
                                          AND NOT EXISTS (SELECT 1 FROM import_refs r WHERE r.target_id = e.id)
                                          AND NOT EXISTS (SELECT 1 FROM retail_import_refs o WHERE o.target_id = e.id))
                        """).query(Boolean.class).single();
    }
}
