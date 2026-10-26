package com.rincoltech.bms.core.help.internal;

import com.rincoltech.bms.kernel.PersonalDataEraser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Ask BMS's part of removing a closed business (issue #571, ADR-090, ADR-096), through the definer function
 * {@code help_erase_personal_data} of V108: the cached answers and the limit tickets are deleted. The question log and the
 * spend rows hold no text and no personal data beyond the (anonymised) user's id, and go with the purge.
 */
@Component
class HelpErasure implements PersonalDataEraser {

    private final JdbcClient jdbc;

    HelpErasure(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String area() {
        return "help";
    }

    @Override
    public int erase() {
        return jdbc.sql("SELECT help_erase_personal_data()")
                .query(Integer.class)
                .single();
    }
}
