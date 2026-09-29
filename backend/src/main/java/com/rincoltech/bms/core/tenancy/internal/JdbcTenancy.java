package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantModules;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JDBC implementation of the tenancy module's public interfaces. */
@Service
class JdbcTenancy implements Branches, CurrentTenant, TenantModules, TenantSequences {

    private final JdbcClient jdbc;

    JdbcTenancy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Branch> findActive(UUID branchId) {
        return jdbc.sql("SELECT id, code, name, is_head_office FROM branches WHERE id = ? AND status = 'active'")
                .param(branchId)
                .query((rs, n) -> new Branch(
                        rs.getObject("id", UUID.class),
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getBoolean("is_head_office")))
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public Profile profile() {
        // RLS on tenants lets the application role see only the bound tenant's own row.
        return jdbc.sql("SELECT id, slug, name, currency, timezone FROM tenants")
                .query((rs, n) -> new Profile(
                        rs.getObject("id", UUID.class),
                        rs.getString("slug"),
                        rs.getString("name"),
                        rs.getString("currency"),
                        ZoneId.of(rs.getString("timezone"))))
                .single();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEnabled(String moduleKey) {
        return jdbc.sql("SELECT count(*) FROM tenant_modules WHERE module_key = ?")
                        .param(moduleKey)
                        .query(Long.class)
                        .single()
                > 0;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long next(String sequenceKey) {
        // One statement, row-locked by the upsert: concurrent callers queue on the row, and a
        // rolled back transaction releases its number, so numbers are gap-free (FR-DOC-04).
        return jdbc.sql("""
                        INSERT INTO tenant_sequences (tenant_id, sequence_key, next_value)
                        VALUES (current_setting('app.tenant_id')::uuid, ?, 2)
                        ON CONFLICT (tenant_id, sequence_key)
                        DO UPDATE SET next_value = tenant_sequences.next_value + 1
                        RETURNING next_value - 1
                        """).param(sequenceKey).query(Long.class).single();
    }
}
