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
        // Increment 1 tables (migration V2).
        owner.sql(
                        "INSERT INTO subscriptions (id, tenant_id, plan_id, status) VALUES (?, ?, '00000000-0000-4000-8000-000000000001', 'trial')")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        owner.sql(
                        "INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?, '{\"display_name\": \"Test Display\"}')")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        owner.sql("INSERT INTO user_credentials (user_id, tenant_id, password_hash) VALUES (?, ?, 'not-a-real-hash')")
                .params(user, t.tenantId())
                .update();
        owner.sql(
                        "INSERT INTO user_recovery_codes (id, tenant_id, user_id, code_hash) VALUES (?, ?, ?, repeat('1', 64))")
                .params(UUID.randomUUID(), t.tenantId(), user)
                .update();
        owner.sql("INSERT INTO user_invitations (id, tenant_id, user_id, token_hash, expires_at, invited_by)"
                        + " VALUES (?, ?, ?, ?, now() + interval '72 hours', ?)")
                .params(UUID.randomUUID(), t.tenantId(), user, randomHash(), user)
                .update();
        owner.sql(
                        "INSERT INTO auth_sessions (id, tenant_id, user_id, family_id, refresh_token_hash, expires_at, idle_expires_at)"
                                + " VALUES (?, ?, ?, ?, ?, now() + interval '7 days', now() + interval '12 hours')")
                .params(UUID.randomUUID(), t.tenantId(), user, UUID.randomUUID(), randomHash())
                .update();
        owner.sql("INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, branch_id, granted_by)"
                        + " VALUES (?, ?, ?, 'cashier', ?, ?)")
                .params(UUID.randomUUID(), t.tenantId(), user, t.headOffice(), user)
                .update();
        owner.sql(
                        "INSERT INTO approval_requests (id, tenant_id, branch_id, action_type, subject_type, subject_id, payload,"
                                + " status, requested_by, requested_at, expires_at)"
                                + " VALUES (?, ?, ?, 'test_action', 'test.subject', ?, '{}', 'pending', ?, now(), now() + interval '7 days')")
                .params(UUID.randomUUID(), t.tenantId(), t.headOffice(), UUID.randomUUID(), user)
                .update();
        owner.sql("INSERT INTO tenant_sequences (tenant_id, sequence_key, next_value) VALUES (?, 'rls_fixture', 1)")
                .param(t.tenantId())
                .update();
        // A next of kin linked to a second member, so lending_member_links_v has rows too.
        UUID kinMember = UUID.randomUUID();
        insertMember(t, kinMember, "Test Kin 01", null);
        owner.sql("""
                        INSERT INTO lending_next_of_kin (id, tenant_id, member_id, full_name, relationship,
                                                         linked_member_id, link_method, link_status)
                        SELECT ?, tenant_id, id, 'Test Kin 01', 'sibling', ?, 'nin', 'confirmed'
                          FROM lending_members WHERE tenant_id = ? AND id <> ? LIMIT 1
                        """)
                .params(UUID.randomUUID(), kinMember, t.tenantId(), kinMember)
                .update();
        UUID document = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO documents (id, tenant_id, doc_type, subject_type, subject_id, object_key, content_type,
                                               size_bytes, sha256)
                        VALUES (?, ?, 'upload', 'lending.member', ?, ?, 'application/pdf', 5, repeat('0', 64))
                        """)
                .params(
                        document,
                        t.tenantId(),
                        kinMember,
                        "tenants/" + t.tenantId() + "/upload/rls/" + document + ".pdf")
                .update();
        // A tenant logo (FR-TEN-08): a document whose subject is the tenant itself.
        UUID logo = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO documents (id, tenant_id, doc_type, subject_type, subject_id, object_key, content_type,
                                               size_bytes, sha256)
                        VALUES (?, ?, 'upload', 'core.tenant', ?, ?, 'image/png', 5, repeat('1', 64))
                        """)
                .params(logo, t.tenantId(), t.tenantId(), "tenants/" + t.tenantId() + "/upload/rls/" + logo + ".png")
                .update();
        owner.sql("""
                        INSERT INTO lending_member_documents (id, tenant_id, member_id, doc_kind, document_id, uploaded_by)
                        VALUES (?, ?, ?, 'other', ?, ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), kinMember, document, user)
                .update();
        UUID collateral = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO lending_collateral_items (id, tenant_id, branch_id, member_id, collateral_type,
                                                              description, currency, custody_status)
                        VALUES (?, ?, ?, ?, 'household_item', 'Test fridge', 'UGX', 'pledged')
                        """)
                .params(collateral, t.tenantId(), t.headOffice(), kinMember)
                .update();
        owner.sql("""
                        INSERT INTO lending_collateral_valuations (id, tenant_id, collateral_id, valued_on,
                                                                   market_value_minor, recorded_by)
                        VALUES (?, ?, ?, DATE '2026-01-15', 100000, ?)
                        """).params(UUID.randomUUID(), t.tenantId(), collateral, user).update();
        owner.sql("""
                        INSERT INTO lending_collateral_events (id, tenant_id, collateral_id, event_type, to_status,
                                                               occurred_at, recorded_by)
                        VALUES (?, ?, ?, 'registered', 'pledged', now(), ?)
                        """).params(UUID.randomUUID(), t.tenantId(), collateral, user).update();
        owner.sql("INSERT INTO lending_collateral_documents (tenant_id, collateral_id, document_id) VALUES (?, ?, ?)")
                .params(t.tenantId(), collateral, document)
                .update();
        UUID product = UUID.randomUUID();
        UUID productVersion = UUID.randomUUID();
        owner.sql(
                        "INSERT INTO lending_loan_products (id, tenant_id, code, name, status) VALUES (?, ?, 'RLS', 'Test Product', 'active')")
                .params(product, t.tenantId())
                .update();
        owner.sql("""
                        INSERT INTO lending_loan_product_versions (id, tenant_id, product_id, version_no, currency,
                            interest_method, interest_rate_bp, rate_unit, term_unit, min_term_count, max_term_count,
                            default_term_count, repayment_pattern, min_principal_minor, max_principal_minor, created_by)
                        VALUES (?, ?, ?, 1, 'UGX', 'flat', 1000, 'per_term', 'month', 1, 1, 1, 'bullet', 1000, 100000, ?)
                        """).params(productVersion, t.tenantId(), product, user).update();
        owner.sql("""
                        INSERT INTO lending_loan_product_fees (id, tenant_id, product_version_id, name, fee_type,
                            calc_method, amount_minor, timing)
                        VALUES (?, ?, ?, 'Test fee', 'processing', 'flat', 1000, 'paid_upfront')
                        """).params(UUID.randomUUID(), t.tenantId(), productVersion).update();
        UUID loan = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO lending_loans (id, tenant_id, branch_id, loan_no, member_id, product_version_id,
                            officer_user_id, status, channel, purpose_category, currency, requested_principal_minor,
                            requested_term_count, term_unit, interest_method, interest_rate_bp, rate_unit, repayment_pattern)
                        VALUES (?, ?, ?, 'LN999999', ?, ?, ?, 'draft', 'staff', 'business', 'UGX', 100000, 1, 'month',
                                'flat', 1000, 'per_term', 'bullet')
                        """)
                .params(loan, t.tenantId(), t.headOffice(), kinMember, productVersion, user)
                .update();
        owner.sql(
                        "INSERT INTO lending_loan_status_history (id, tenant_id, loan_id, to_status) VALUES (?, ?, ?, 'draft')")
                .params(UUID.randomUUID(), t.tenantId(), loan)
                .update();
        UUID guarantor = UUID.randomUUID();
        insertMember(t, guarantor, "Test Guarantor 01", null);
        owner.sql("""
                        INSERT INTO lending_loan_guarantors (id, tenant_id, loan_id, guarantor_member_id,
                            guaranteed_amount_minor, status)
                        VALUES (?, ?, ?, ?, 50000, 'active')
                        """).params(UUID.randomUUID(), t.tenantId(), loan, guarantor).update();
        owner.sql("""
                        INSERT INTO lending_loan_collateral (id, tenant_id, loan_id, collateral_id, pledged_value_minor)
                        VALUES (?, ?, ?, ?, 50000)
                        """).params(UUID.randomUUID(), t.tenantId(), loan, collateral).update();
        owner.sql("""
                        INSERT INTO lending_loan_appraisals (id, tenant_id, loan_id, appraised_by, score, band,
                            components, flags, exposure, weights, recommendation)
                        VALUES (?, ?, ?, ?, 50, 'C', '{}', '{}', '{}', '{}', 'review')
                        """).params(UUID.randomUUID(), t.tenantId(), loan, user).update();
        // Retail tables (migration V10, ADR-020).
        UUID retailCategory = UUID.randomUUID();
        UUID retailUnit = UUID.randomUUID();
        UUID retailProduct = UUID.randomUUID();
        owner.sql("INSERT INTO retail_categories (id, tenant_id, name) VALUES (?, ?, 'Test Category')")
                .params(retailCategory, t.tenantId())
                .update();
        owner.sql("INSERT INTO retail_units (id, tenant_id, name) VALUES (?, ?, 'pcs')")
                .params(retailUnit, t.tenantId())
                .update();
        owner.sql("""
                        INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, cost_minor,
                                                     sell_minor, currency)
                        VALUES (?, ?, 'RLS-1', 'Test Product', ?, ?, 100, 150, 'UGX')
                        """)
                .params(retailProduct, t.tenantId(), retailCategory, retailUnit)
                .update();
        owner.sql("""
                        INSERT INTO retail_price_history (id, tenant_id, product_id, source, new_cost_minor, new_sell_minor,
                                                          currency)
                        VALUES (?, ?, ?, 'initial', 100, 150, 'UGX')
                        """).params(UUID.randomUUID(), t.tenantId(), retailProduct).update();
        // Retail tables (migration V11, ADR-020).
        owner.sql("""
                        INSERT INTO retail_stock_movements (id, tenant_id, occurred_at, business_date, branch_id, product_id, kind, qty,
                                                            unit_cost_minor, source_type)
                        VALUES (?, ?, now(), current_date, ?, ?, 'opening', 2, 100, 'test')
                        """)
                .params(UUID.randomUUID(), t.tenantId(), t.headOffice(), retailProduct)
                .update();
        owner.sql("INSERT INTO retail_stock_balances (tenant_id, branch_id, product_id, qty) VALUES (?, ?, ?, 2)")
                .params(t.tenantId(), t.headOffice(), retailProduct)
                .update();
        UUID stocktake = UUID.randomUUID();
        owner.sql(
                        "INSERT INTO retail_stocktakes (id, tenant_id, branch_id, status, created_by) VALUES (?, ?, ?, 'draft', ?)")
                .params(stocktake, t.tenantId(), t.headOffice(), user)
                .update();
        owner.sql("""
                        INSERT INTO retail_stocktake_lines (id, tenant_id, stocktake_id, product_id, counted_qty, expected_qty)
                        VALUES (?, ?, ?, ?, 2, 2)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), stocktake, retailProduct)
                .update();
        UUID customer = UUID.randomUUID();
        owner.sql("INSERT INTO retail_customers (id, tenant_id, name) VALUES (?, ?, 'Test Buyer 01')")
                .params(customer, t.tenantId())
                .update();
        UUID sale = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, customer_id,
                                                  currency, total_minor, cost_total_minor, paid_minor, status, created_by)
                        VALUES (?, ?, ?, 'RS99999999', DATE '2026-01-15', 'credit', ?, 'UGX', 150, 100, 0, 'completed', ?)
                        """)
                .params(sale, t.tenantId(), t.headOffice(), customer, user)
                .update();
        owner.sql("""
                        INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor,
                                                       unit_cost_minor, line_total_minor, line_cost_minor)
                        VALUES (?, ?, ?, 1, ?, 1, 150, 100, 150, 100)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), sale, retailProduct)
                .update();
        // Retail tables (migration V12, ADR-020).
        UUID supplier = UUID.randomUUID();
        owner.sql("INSERT INTO retail_suppliers (id, tenant_id, name) VALUES (?, ?, 'Test Supplier 01')")
                .params(supplier, t.tenantId())
                .update();
        UUID purchase = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_purchases (id, tenant_id, purchase_no, supplier_id, purchased_on, payment_method,
                                                      currency, total_minor, created_by)
                        VALUES (?, ?, 'RP99999999', ?, DATE '2026-01-15', 'credit', 'UGX', 200, ?)
                        """).params(purchase, t.tenantId(), supplier, user).update();
        owner.sql("""
                        INSERT INTO retail_purchase_lines (id, tenant_id, purchase_id, line_no, product_id, cost_minor,
                                                           qty_total, line_total_minor)
                        VALUES (?, ?, ?, 1, ?, 100, 2, 200)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), purchase, retailProduct)
                .update();
        UUID usage = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_usage_reports (id, tenant_id, branch_id, kind, reason, occurred_on, currency,
                                                          cost_total_minor, created_by)
                        VALUES (?, ?, ?, 'used', 'Test use', DATE '2026-01-15', 'UGX', 100, ?)
                        """).params(usage, t.tenantId(), t.headOffice(), user).update();
        owner.sql("""
                        INSERT INTO retail_usage_lines (id, tenant_id, report_id, line_no, product_id, qty, unit_cost_minor,
                                                        line_cost_minor)
                        VALUES (?, ?, ?, 1, ?, 1, 100, 100)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), usage, retailProduct)
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
        owner.sql("""
                        INSERT INTO retail_sale_payments (id, tenant_id, sale_id, amount_minor, currency, method, paid_on,
                                                          journal_entry_id, created_by)
                        SELECT ?, ?, id, 50, 'UGX', 'cash', DATE '2026-01-15', ?, ?
                          FROM retail_sales WHERE tenant_id = ? LIMIT 1
                        """)
                .params(UUID.randomUUID(), t.tenantId(), entry, user, t.tenantId())
                .update();
        // The retail import's source references (migration V20, #55).
        owner.sql("""
                        INSERT INTO retail_import_refs (tenant_id, source_file, source_ref, target_type, source_user)
                        VALUES (?, 'sales', 'SAL-TEST-01', 'retail.sale', 'test.sales01')
                        """).param(t.tenantId()).update();
    }

    static String randomHash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
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
                           -- Platform tables (chapter 6 section 6.4) carry no tenant policy by design.
                           AND c.relname NOT LIKE 'platform\\_%'
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
        assertThat(tables)
                .contains(
                        "tenants",
                        "branches",
                        "audit_log",
                        "journal_lines",
                        "lending_members",
                        "subscriptions",
                        "tenant_settings",
                        "user_credentials",
                        "user_recovery_codes",
                        "user_invitations",
                        "auth_sessions",
                        "user_role_assignments",
                        "approval_requests");
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

    /**
     * NFR-ISO-02 for views: the catalogue lists tables only, so each view over tenant data is
     * checked here. A view without security_invoker runs as its owner and would leak.
     */
    @Test
    void theRelationshipViewShowsOnlyTheBoundTenant() throws SQLException {
        long ofB = asApp(
                a.tenantId(),
                c -> count(c, "SELECT count(*) FROM lending_member_links_v WHERE tenant_id = ?", b.tenantId()));
        long ofA = asApp(
                a.tenantId(),
                c -> count(c, "SELECT count(*) FROM lending_member_links_v WHERE tenant_id = ?", a.tenantId()));
        assertThat(ofB).isZero();
        assertThat(ofA).isPositive();
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

    /** FR-TEN-08: the tenant logo document (subject core.tenant) is visible to its own tenant only. */
    @Test
    void aTenantLogoDocumentIsVisibleToItsOwnTenantOnly() throws SQLException {
        String sql = "SELECT count(*) FROM documents WHERE subject_type = 'core.tenant' AND subject_id = ?";
        long aSeesB = asApp(a.tenantId(), c -> count(c, sql, b.tenantId()));
        long bSeesB = asApp(b.tenantId(), c -> count(c, sql, b.tenantId()));
        long aSeesA = asApp(a.tenantId(), c -> count(c, sql, a.tenantId()));
        assertThat(aSeesB).isZero();
        assertThat(bSeesB).isEqualTo(1);
        assertThat(aSeesA).isEqualTo(1);
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
