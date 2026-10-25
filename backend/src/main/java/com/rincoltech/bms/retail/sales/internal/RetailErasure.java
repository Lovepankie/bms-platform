package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.kernel.PersonalDataEraser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The shop's part of removing a closed business (issue #571, ADR-090), through the definer function
 * {@code retail_erase_personal_data} of V102: customers, suppliers and cash book people become numbered placeholders without
 * contact details, and a buyer's name and contact on a sale, quotation, document, credit note or order is removed. Sales,
 * payments, purchases and the cash book are money records and keep their amounts and dates.
 */
@Component
class RetailErasure implements PersonalDataEraser {

    private final JdbcClient jdbc;

    RetailErasure(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String area() {
        return "retail";
    }

    @Override
    public int erase() {
        return jdbc.sql("SELECT retail_erase_personal_data()")
                .query(Integer.class)
                .single();
    }
}
