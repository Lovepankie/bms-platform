package com.rincoltech.bms.lending.members.internal;

import com.rincoltech.bms.kernel.PersonalDataEraser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Lending's part of removing a closed business (issue #571, ADR-090), through the definer function
 * {@code lending_erase_personal_data} of V102: members keep their member number and lose their name, phones, ID, birth date,
 * place and income; next of kin are deleted; collateral, valuations and collection notes lose the names and notes that
 * identify a person. Loans, repayments, savings and investments are money records and stay as they are.
 */
@Component
class LendingErasure implements PersonalDataEraser {

    private final JdbcClient jdbc;

    LendingErasure(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String area() {
        return "lending";
    }

    @Override
    public int erase() {
        return jdbc.sql("SELECT lending_erase_personal_data()")
                .query(Integer.class)
                .single();
    }
}
