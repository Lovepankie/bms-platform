package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.kernel.PersonalDataEraser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Removing a closed business (issue #571, ADR-090) clears the details of its suppliers (issue #820, ADR-125): an email, an
 * address, a website, a bank account number and the notes can name or reach a person, and no money record needs them. The
 * name and the contact are made placeholders by {@code retail_erase_personal_data} of V102.
 */
@Component
class SupplierDetailsErasure implements PersonalDataEraser {

    private final JdbcClient jdbc;

    SupplierDetailsErasure(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String area() {
        return "retail_supplier_details";
    }

    @Override
    public int erase() {
        // The tenant predicate as well as row-level security, so a run bound wrongly or as the owner never reaches
        // another business.
        return jdbc.sql("""
                        UPDATE retail_suppliers
                           SET email = NULL, address = NULL, country = NULL, website = NULL, payment_terms = NULL,
                               bank_account = NULL, supplier_type = NULL, notes = NULL, updated_at = now()
                         WHERE tenant_id = current_setting('app.tenant_id')::uuid
                           AND (email IS NOT NULL OR address IS NOT NULL OR country IS NOT NULL OR website IS NOT NULL
                                OR payment_terms IS NOT NULL OR bank_account IS NOT NULL OR supplier_type IS NOT NULL
                                OR notes IS NOT NULL)
                        """).update();
    }
}
