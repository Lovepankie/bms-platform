package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.kernel.PersonalDataEraser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The imports' part of removing a closed business (issue #571, ADR-090; review of #873), through the definer function
 * {@code imports_erase_personal_data} of V144: the name a signer typed on a sign-off becomes "Removed". The signed
 * reconciliation holds no personal value and goes with the purge. JUSTIFICATION-A2: the removal finds its erasers as beans.
 */
@Component
class ImportsErasure implements PersonalDataEraser {

    private final JdbcClient jdbc;

    ImportsErasure(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String area() {
        return "imports";
    }

    @Override
    public int erase() {
        return jdbc.sql("SELECT imports_erase_personal_data()")
                .query(Integer.class)
                .single();
    }
}
