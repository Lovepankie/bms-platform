package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.PlanLimits;
import com.rincoltech.bms.core.tenancy.TenantModules;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JDBC implementation of the tenancy module's public interfaces. */
@Service
class JdbcTenancy implements Branches, CurrentTenant, TenantModules, TenantSequences, PlanLimits {

    private final JdbcClient jdbc;

    JdbcTenancy(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Branch> findActive(UUID branchId) {
        return jdbc.sql(
                        "SELECT id, code, name, is_head_office, status FROM branches WHERE id = ? AND status = 'active'")
                .param(branchId)
                .query(JdbcTenancy::branch)
                .optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Branch> all() {
        return jdbc.sql(
                        "SELECT id, code, name, is_head_office, status FROM branches ORDER BY is_head_office DESC, code")
                .query(JdbcTenancy::branch)
                .list();
    }

    private static Branch branch(ResultSet rs, int n) throws SQLException {
        return new Branch(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("is_head_office"),
                "active".equals(rs.getString("status")));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void checkRoomFor(String limitKey, long currentCount) {
        if (!List.of(MAX_BRANCHES, MAX_STAFF_USERS, MAX_ACTIVE_MEMBERS).contains(limitKey)) {
            throw new IllegalArgumentException("unknown plan limit " + limitKey);
        }
        // The column name comes from the fixed list above, never from a caller's input.
        Integer limit = jdbc.sql("SELECT p." + limitKey + " FROM tenants t JOIN plans p ON p.id = t.plan_id")
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (limit != null && currentCount >= limit) {
            throw ApiException.rule(
                    "plan_limit_reached",
                    "The plan allows at most " + limit + " (" + limitKey + "); the limit has been reached.");
        }
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
    @Transactional(readOnly = true)
    public List<String> enabledKeys() {
        return jdbc.sql("SELECT module_key FROM tenant_modules ORDER BY module_key")
                .query(String.class)
                .list();
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
