package com.rincoltech.bms.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.TestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The isolation suite of chapter 15 section 15.4 (NFR-ISO-01 to NFR-ISO-03), run connected as
 * bms_app, never as the owner. Tables are enumerated from the catalogue, so a tenant-owned table
 * added by a later migration is covered without touching this file.
 */
class RlsIsolationIT {

    static TestDatabase.Fixture a;
    static TestDatabase.Fixture b;
    static UUID memberOfB;

    @BeforeAll
    static void seed() {
        a = TestDatabase.tenant("iso-a", true);
        b = TestDatabase.tenant("iso-b", true);
        insertMember(a, UUID.randomUUID(), "Test Borrower 01", "CMTEST0000101A");
        memberOfB = UUID.randomUUID();
        insertMember(b, memberOfB, "Test Borrower 02", "CMTEST0000102A");
        seedOneRowInEveryTable(a);
        seedOneRowInEveryTable(b);
    }

    /**
     * A factory row per tenant-owned table (chapter 15 section 15.4 item 2). A policy is only
     * evaluated against rows, so an empty table would pass the fail-closed test vacuously;
     * {@link #everyTenantOwnedTableHasAFactoryRow} fails when a new table is added without one.
     */
    static void seedOneRowInEveryTable(TestDatabase.Fixture t) {
        JdbcClient owner = TestDatabase.owner();
        UUID user = UUID.randomUUID();
        owner.sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, phone_e164, status) VALUES (?, ?, 'staff', 'Test Staff 01', '+256700000090', 'active')")
                .params(user, t.tenantId())
                .update();
        owner.sql("INSERT INTO tenant_sequences (tenant_id, sequence_key, next_value) VALUES (?, 'rls_fixture', 1)")
                .param(t.tenantId())
                .update();
        owner.sql(
                        "INSERT INTO audit_log (id, tenant_id, actor_kind, action, entity_type) VALUES (?, ?, 'system', 'test.fixture.created', 'test')")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        owner.sql("INSERT INTO idempotency_keys (tenant_id, principal_id, key, method, path, request_hash, status)"
                        + " VALUES (?, ?, 'rls-fixture-key', 'POST', '/x', repeat('0', 64), 'completed')")
                .params(t.tenantId(), user)
                .update();
        UUID period = UUID.randomUUID();
        owner.sql("INSERT INTO gl_periods (id, tenant_id, year, month, status) VALUES (?, ?, 2026, 1, 'open')")
                .params(period, t.tenantId())
                .update();
        // One statement, so the deferred balance trigger sees the entry and both lines together.
        UUID entry = UUID.randomUUID();
        owner.sql(
                        "WITH e AS (INSERT INTO journal_entries (id, tenant_id, branch_id, entry_no, entry_date, period_id,"
                                + " reference, source_module, source_type) VALUES (?, ?, ?, 'JE99999999', DATE '2026-01-15', ?,"
                                + " 'RLS-FIXTURE', 'test', 'fixture') RETURNING id)"
                                + " INSERT INTO journal_lines (id, tenant_id, entry_id, line_no, account_id, debit, credit, currency)"
                                + " SELECT ?, ?, e.id, 1, ?, 1000, 0, 'UGX' FROM e UNION ALL SELECT ?, ?, e.id, 2, ?, 0, 1000, 'UGX' FROM e")
                .params(
                        entry,
                        t.tenantId(),
                        t.headOffice(),
                        period,
                        UUID.randomUUID(),
                        t.tenantId(),
                        t.account("loans_receivable"),
                        UUID.randomUUID(),
                        t.tenantId(),
                        t.account("cash_on_hand"))
                .update();
    }

    static void insertMember(TestDatabase.Fixture t, UUID id, String name, String nin) {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO lending_members (id, tenant_id, branch_id, member_no, full_name, phone_e164, id_type,
                                                     national_id, currency, kyc_status, status, source)
                        VALUES (?, ?, ?, ?, ?, '+256700000001', 'nin', ?, 'UGX', 'incomplete', 'active', 'staff')
                        """)
                .params(id, t.tenantId(), t.headOffice(), "M9" + id.toString().substring(0, 5), name, nin)
                .update();
    }

    static List<String> tenantOwnedTables() {
        return TestDatabase.owner().sql("""
                        SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'public' AND c.relkind = 'r'
                           AND (c.relname = 'tenants' OR EXISTS (
                                SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'tenant_id'
                                   AND NOT a.attisdropped))
                         ORDER BY 1
                        """).query(String.class).list();
    }

    /** Opens a bms_app transaction bound to the tenant, exactly as the transaction manager does. */
    static <T> T asApp(UUID tenantId, SqlWork<T> work) throws SQLException {
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            if (tenantId != null) {
                try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    ps.setString(1, tenantId.toString());
                    ps.execute();
                }
            }
            try {
                return work.run(c);
            } finally {
                c.rollback();
            }
        }
    }

    interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    static long count(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 15.4 item 1, NFR-ISO-01: forced RLS and the standard policy on every tenant-owned table. */
    @Test
    void everyTenantOwnedTableHasForcedRlsAndTheStandardPolicy() {
        List<String> tables = tenantOwnedTables();
        assertThat(tables).contains("tenants", "branches", "audit_log", "journal_lines", "lending_members");
        JdbcClient owner = TestDatabase.owner();
        List<String> missing = new ArrayList<>();
        for (String table : tables) {
            boolean ok = owner.sql("""
                            SELECT c.relrowsecurity AND c.relforcerowsecurity AND EXISTS (
                                     SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid AND p.polname = 'tenant_isolation'
                                        AND pg_get_expr(p.polqual, p.polrelid) LIKE '%current_setting(''app.tenant_id''::text)%'
                                        AND pg_get_expr(p.polwithcheck, p.polrelid) LIKE '%current_setting(''app.tenant_id''::text)%')
                              FROM pg_class c WHERE c.relname = ? AND c.relnamespace = 'public'::regnamespace
                            """).param(table).query(Boolean.class).single();
            if (!ok) {
                missing.add(table);
            }
        }
        assertThat(missing)
                .as("tables without forced RLS and the tenant_isolation policy")
                .isEmpty();
    }

    @Test
    void everyTenantOwnedTableHasAFactoryRow() {
        for (String table : tenantOwnedTables()) {
            String key = table.equals("tenants") ? "id" : "tenant_id";
            long rows = TestDatabase.owner()
                    .sql("SELECT count(*) FROM " + table + " WHERE " + key + " = ?")
                    .param(b.tenantId())
                    .query(Long.class)
                    .single();
            assertThat(rows)
                    .as("add a factory row for %s in seedOneRowInEveryTable", table)
                    .isPositive();
        }
    }

    /** 15.4 item 2, NFR-ISO-02: bound to A, no row of B is visible in any tenant-owned table. */
    @Test
    void tenantACannotReadTenantBRowsInAnyTable() throws SQLException {
        for (String table : tenantOwnedTables()) {
            String key = table.equals("tenants") ? "id" : "tenant_id";
            long visible = asApp(
                    a.tenantId(),
                    c -> count(c, "SELECT count(*) FROM " + table + " WHERE " + key + " = ?", b.tenantId()));
            assertThat(visible).as("rows of tenant B visible in %s", table).isZero();
        }
        long own = asApp(a.tenantId(), c -> count(c, "SELECT count(*) FROM lending_members"));
        long total = TestDatabase.owner()
                .sql("SELECT count(*) FROM lending_members WHERE tenant_id = ?")
                .param(a.tenantId())
                .query(Long.class)
                .single();
        assertThat(own).isEqualTo(total).isPositive();
    }

    @Test
    void tenantACannotUpdateOrDeleteTenantBRows() throws SQLException {
        int updated = asApp(a.tenantId(), c -> {
            try (PreparedStatement ps =
                    c.prepareStatement("UPDATE lending_members SET full_name = 'Changed' WHERE id = ?")) {
                ps.setObject(1, memberOfB);
                return ps.executeUpdate();
            }
        });
        assertThat(updated).isZero();

        TestDatabase.owner()
                .sql("INSERT INTO idempotency_keys (tenant_id, principal_id, key, method, path, request_hash, status)"
                        + " VALUES (?, ?, 'key-of-b-0001', 'POST', '/x', repeat('0', 64), 'completed')")
                .params(b.tenantId(), UUID.randomUUID())
                .update();
        int deleted = asApp(a.tenantId(), c -> {
            try (PreparedStatement ps =
                    c.prepareStatement("DELETE FROM idempotency_keys WHERE key = 'key-of-b-0001'")) {
                return ps.executeUpdate();
            }
        });
        assertThat(deleted).isZero();
    }

    @Test
    void tenantACannotInsertARowStampedWithTenantB() {
        assertThatThrownBy(() -> asApp(a.tenantId(), c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO branches (id, tenant_id, code, name) VALUES (?, ?, 'ZZ', 'Smuggled')")) {
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, b.tenantId());
                        return ps.executeUpdate();
                    }
                }))
                .hasStackTraceContaining("violates row-level security policy");
    }

    /** 15.4 item 3, NFR-ISO-03: with no tenant bound, every tenant-owned table raises. */
    @Test
    void anUnboundSessionFailsClosedOnEveryTable() throws SQLException {
        for (String table : tenantOwnedTables()) {
            assertThatThrownBy(() -> asApp(null, c -> count(c, "SELECT count(*) FROM " + table)))
                    .as("unbound SELECT on %s", table)
                    .hasStackTraceContaining("app.tenant_id");
        }
    }

    /** 15.4 item 5: composite keys stop a cross-tenant reference even for the owner, who bypasses RLS. */
    @Test
    void compositeKeysForbidCrossTenantReferencesEvenWithoutRls() {
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("""
                                INSERT INTO lending_members (id, tenant_id, branch_id, member_no, full_name, phone_e164, id_type,
                                                             currency, kyc_status, status, source)
                                VALUES (?, ?, ?, 'M999999', 'Test Borrower 03', '+256700000003', 'none', 'UGX',
                                        'incomplete', 'active', 'staff')
                                """)
                        .params(UUID.randomUUID(), a.tenantId(), b.headOffice())
                        .update())
                .hasStackTraceContaining("violates foreign key constraint");
    }

    /** 15.4 item 9: the resolvers return ids only, and only for active tenants. */
    @Test
    void resolverFunctionsReturnOnlyActiveTenantIds() throws SQLException {
        TestDatabase.Fixture suspended = TestDatabase.tenant("iso-suspended", false);
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .param(suspended.tenantId())
                .update();
        JdbcClient app = JdbcClient.create(TestDatabase.appDataSource());
        assertThat(app.sql("SELECT app_resolve_tenant(?)")
                        .param(a.slug())
                        .query(UUID.class)
                        .single())
                .isEqualTo(a.tenantId());
        assertThat(app.sql("SELECT app_resolve_tenant(?)")
                        .param(suspended.slug())
                        .query(UUID.class)
                        .optional())
                .isEmpty();
        assertThat(app.sql("SELECT app_resolve_tenant('no-such-tenant')")
                        .query(UUID.class)
                        .optional())
                .isEmpty();
        List<UUID> active =
                app.sql("SELECT app_list_active_tenants()").query(UUID.class).list();
        assertThat(active).contains(a.tenantId(), b.tenantId()).doesNotContain(suspended.tenantId());
    }

    /** FR-AUD-02 and chapter 6 section 6.2.4: append-only tables refuse UPDATE and DELETE. */
    @Test
    void appendOnlyTablesRefuseUpdateAndDelete() {
        assertThatThrownBy(() -> asApp(
                        a.tenantId(),
                        c -> count(
                                c, "WITH u AS (UPDATE audit_log SET action = 'x' RETURNING 1) SELECT count(*) FROM u")))
                .hasStackTraceContaining("permission denied");
        TestDatabase.owner()
                .sql(
                        "INSERT INTO audit_log (id, tenant_id, actor_kind, action, entity_type) VALUES (?, ?, 'system', 'test.fixture.created', 'test')")
                .params(UUID.randomUUID(), a.tenantId())
                .update();
        // Even the owner is refused unless a migration explicitly opts in.
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("DELETE FROM audit_log WHERE tenant_id = ?")
                        .param(a.tenantId())
                        .update())
                .hasStackTraceContaining("append-only");
    }
}
