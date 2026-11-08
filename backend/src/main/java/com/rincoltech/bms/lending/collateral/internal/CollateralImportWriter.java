package com.rincoltech.bms.lending.collateral.internal;

import com.rincoltech.bms.lending.collateral.CollateralImports;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link CollateralImports} over {@code lending_collateral_items} (issue #136), in the caller's transaction. */
@Component
class CollateralImportWriter implements CollateralImports {

    /** The note every imported item carries (chapter 13 section 13.6.8). */
    static final String NOTE = "imported; verify custody and value";

    private final JdbcClient jdbc;

    CollateralImportWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean referenceHeld(String collateralType, String referenceNormalised) {
        if (referenceNormalised == null) {
            return false;
        }
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM lending_collateral_items
                                        WHERE tenant_id = current_setting('app.tenant_id')::uuid
                                          AND collateral_type = ? AND reference_no_normalised = ?
                                          AND custody_status NOT IN ('released', 'disposed'))
                        """)
                .params(collateralType, referenceNormalised)
                .query(Boolean.class)
                .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID create(
            UUID branchId,
            UUID memberId,
            String collateralType,
            String description,
            String reference,
            String currency,
            boolean released,
            UUID createdBy) {
        String normalised = CollateralImports.normalise(reference);
        String type = collateralType;
        String text = description;
        if (!released && referenceHeld(type, normalised)) {
            // One active item per type and reference (the index lending_collateral_items_one_active): kept without it.
            text = description + " (" + reference + ")";
            reference = null;
            normalised = null;
            if ("vehicle".equals(type)) {
                type = "other";
            }
        }
        UUID id = UUID.randomUUID();
        String full = text + "; " + NOTE;
        jdbc.sql("""
                        INSERT INTO lending_collateral_items (id, tenant_id, branch_id, member_id, collateral_type,
                            description, reference_no, reference_no_normalised, owner_relationship,
                            estimated_value_minor, currency, custody_status, created_by)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, 'self', NULL, ?, ?, ?)
                        """)
                .params(
                        id,
                        branchId,
                        memberId,
                        type,
                        full.length() > 300 ? full.substring(0, 300) : full,
                        reference,
                        normalised,
                        currency,
                        released ? "released" : "pledged",
                        createdBy)
                .update();
        return id;
    }
}
