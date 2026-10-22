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
import java.util.Map;
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
        // A password reset link (the forgotten staff password, FR-IAM-15, ADR-042). JUSTIFICATION-A3: one factory row
        // per tenant table.
        owner.sql("INSERT INTO user_password_resets (id, tenant_id, user_id, token_hash, expires_at, origin)"
                        + " VALUES (?, ?, ?, ?, now() + interval '1 hour', 'self')")
                .params(UUID.randomUUID(), t.tenantId(), user, randomHash())
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
        UUID openingMovement = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_stock_movements (id, tenant_id, occurred_at, business_date, branch_id, product_id, kind, qty,
                                                            unit_cost_minor, source_type)
                        VALUES (?, ?, now(), current_date, ?, ?, 'opening', 2, 100, 'test')
                        """)
                .params(openingMovement, t.tenantId(), t.headOffice(), retailProduct)
                .update();
        // A quantity correction (migration V63, issue #351).
        owner.sql("""
                        INSERT INTO retail_stock_corrections (id, tenant_id, branch_id, product_id, movement_id, old_qty, new_qty,
                                                              reason_code, unit_cost_minor, created_by)
                        VALUES (?, ?, ?, ?, ?, 2, 1, 'miscount', 100, ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), t.headOffice(), retailProduct, openingMovement, user)
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
        // A restock voided (migration V63, issue #351).
        owner.sql("""
                        INSERT INTO retail_purchase_voids (id, tenant_id, purchase_id, voided_on, reason, voided_by)
                        VALUES (?, ?, ?, DATE '2026-01-16', 'Test void', ?)
                        """).params(UUID.randomUUID(), t.tenantId(), purchase, user).update();
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
        // SMS delivery log and opt-out (migration V50, ADR-036).
        owner.sql("""
                        INSERT INTO sms_deliveries (id, tenant_id, outbox_id, template_key, category, recipient_masked,
                                                    recipient_hash, provider, status)
                        VALUES (?, ?, ?, 'savings.deposit', 'transactional', '+256*****0001', repeat('a', 64), 'sink', 'sunk')
                        """)
                .params(UUID.randomUUID(), t.tenantId(), UUID.randomUUID())
                .update();
        owner.sql("""
                        INSERT INTO sms_opt_outs (id, tenant_id, member_id, recipient_hash, scope, source)
                        VALUES (?, ?, ?, repeat('b', 64), 'reminders', 'staff')
                        """).params(UUID.randomUUID(), t.tenantId(), kinMember).update();
        // The tenant's own SMS wording (migration V52).
        owner.sql("""
                        INSERT INTO sms_template_overrides (id, tenant_id, event_key, body)
                        VALUES (?, ?, 'savings.deposit', 'Paid {amount} on {account_no}')
                        """).params(UUID.randomUUID(), t.tenantId()).update();
        // A device that receives notifications (migration V68, ADR-050).
        owner.sql("""
                        INSERT INTO push_subscriptions (id, tenant_id, user_id, endpoint, endpoint_hash, p256dh, auth)
                        VALUES (?, ?, ?, 'https://fcm.googleapis.com/fcm/send/test-rls', repeat('c', 64), repeat('A', 87), repeat('B', 22))
                        """).params(UUID.randomUUID(), t.tenantId(), user).update();
        // A subscription payment the operator recorded (migration V75, issue #407), by a fabricated operator.
        UUID recorder = UUID.randomUUID();
        owner.sql("INSERT INTO platform_users (id, email, full_name) VALUES (?, ?, 'Test Operator 09')")
                .params(recorder, "rls-op-" + recorder + "@example.test")
                .update();
        owner.sql("""
                        INSERT INTO subscription_payments (id, tenant_id, subscription_id, plan_id, amount_minor, currency, method,
                                                           paid_on, months, period_start, period_end, recorded_by_platform_user_id)
                        SELECT ?, s.tenant_id, s.id, s.plan_id, 1000, 'UGX', 'other', DATE '2026-01-01', 1, DATE '2026-01-01',
                               DATE '2026-01-31', ?
                          FROM subscriptions s WHERE s.tenant_id = ?
                        """).params(UUID.randomUUID(), recorder, t.tenantId()).update();
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
        UUID salePayment = UUID.randomUUID();
        owner.sql("INSERT INTO payment_methods (id, tenant_id, method_key, gl_account_id) VALUES (?, ?, 'cash', ?)")
                .params(UUID.randomUUID(), t.tenantId(), t.account("cash_on_hand"))
                .update();
        owner.sql("""
                        INSERT INTO retail_sale_payments (id, tenant_id, sale_id, amount_minor, currency, method, paid_on,
                                                          journal_entry_id, created_by)
                        SELECT ?, ?, id, 50, 'UGX', 'cash', DATE '2026-01-15', ?, ?
                          FROM retail_sales WHERE tenant_id = ? LIMIT 1
                        """)
                .params(salePayment, t.tenantId(), entry, user, t.tenantId())
                .update();
        // A payment on account and its allocation (ADR-033, #196): the receipt of the fixture payment above.
        // JUSTIFICATION-A3: a fixture row for each new table; the isolation test needs one row per tenant table.
        UUID receipt = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_account_payments (id, tenant_id, receipt_no, customer_id, branch_id, amount_minor,
                                                             currency, method, paid_on, allocation, journal_entry_id, created_by)
                        SELECT ?, ?, 'RC99999999', s.customer_id, s.branch_id, 50, 'UGX', 'cash', DATE '2026-01-15',
                               'oldest_first', ?, ?
                          FROM retail_sales s WHERE s.tenant_id = ? AND s.customer_id IS NOT NULL LIMIT 1
                        """).params(receipt, t.tenantId(), entry, user, t.tenantId()).update();
        owner.sql("""
                        INSERT INTO retail_account_payment_allocations (id, tenant_id, receipt_id, sale_id, sale_payment_id,
                                                                        amount_minor)
                        SELECT gen_random_uuid(), ?, ?, p.sale_id, p.id, 50 FROM retail_sale_payments p WHERE p.id = ?
                        """).params(t.tenantId(), receipt, salePayment).update();
        // A customer return and one of its lines (ADR-033, #201): a return of the fixture sale's first line.
        // JUSTIFICATION-A3: a fixture row for each new table; the isolation test needs one row per tenant table.
        UUID saleReturn = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_sale_returns (id, tenant_id, credit_note_no, branch_seq, sale_id, branch_id, refund_branch_id,
                                                         returned_on, currency, total_minor, cost_minor, refund_minor,
                                                         refund_method, refund_choice, journal_entry_id, created_by)
                        SELECT ?, ?, 'CNRLS-000001', 1, s.id, s.branch_id, s.branch_id, DATE '2026-01-15', 'UGX', 10, 5, 10, 'cash', 'cash',
                               ?, ?
                          FROM retail_sales s WHERE s.tenant_id = ? LIMIT 1
                        """)
                .params(saleReturn, t.tenantId(), entry, user, t.tenantId())
                .update();
        owner.sql("""
                        INSERT INTO retail_sale_return_lines (id, tenant_id, return_id, line_no, sale_line_id, product_id, qty,
                                                              unit_cost_minor, line_total_minor, line_cost_minor, reason,
                                                              disposition, restock_branch_id)
                        SELECT gen_random_uuid(), ?, ?, 1, l.id, l.product_id, 1, 5, 10, 5, 'other', 'restock', s.branch_id
                          FROM retail_sale_lines l JOIN retail_sales s ON s.id = l.sale_id
                         WHERE l.tenant_id = ? LIMIT 1
                        """).params(t.tenantId(), saleReturn, t.tenantId()).update();
        // A write-off of a credit sale (ADR-043, #340): one row, against the fixture sale.
        // JUSTIFICATION-A3: a fixture row for each new table; the isolation test needs one row per tenant table.
        owner.sql("""
                        INSERT INTO retail_sale_writeoffs (id, tenant_id, sale_id, branch_id, written_off_on, currency,
                                                           unpaid_minor, loss_minor, cost_share_minor, recognition, reason,
                                                           journal_entry_id, created_by)
                        SELECT gen_random_uuid(), ?, s.id, s.branch_id, DATE '2026-01-15', 'UGX', 10, 10, 5, 'on_sale',
                               'Test write-off', ?, ?
                          FROM retail_sales s WHERE s.tenant_id = ? LIMIT 1
                        """).params(t.tenantId(), entry, user, t.tenantId()).update();
        // Optional purchasing (ADR-033, #198): an order and its line, a receipt against it and its line, a supplier
        // invoice
        // with its line and entry, a payment with its allocation, and the tolerances.
        // JUSTIFICATION-A3: a fixture row for each new table; the isolation test needs one row per tenant table.
        UUID order = UUID.randomUUID();
        UUID orderLine = UUID.randomUUID();
        UUID goodsReceipt = UUID.randomUUID();
        UUID receiptLine = UUID.randomUUID();
        UUID bill = UUID.randomUUID();
        UUID supplierPayment = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_purchase_orders (id, tenant_id, order_no, branch_seq, branch_id, supplier_id, ordered_on,
                                                            currency, total_minor, created_by)
                        VALUES (?, ?, 'PORLS-000001', 1, ?, ?, DATE '2026-01-15', 'UGX', 200, ?)
                        """)
                .params(order, t.tenantId(), t.headOffice(), supplier, user)
                .update();
        owner.sql("""
                        INSERT INTO retail_purchase_order_lines (id, tenant_id, order_id, line_no, product_id, qty, cost_minor,
                                                                 line_total_minor)
                        VALUES (?, ?, ?, 1, ?, 2, 100, 200)
                        """).params(orderLine, t.tenantId(), order, retailProduct).update();
        owner.sql("""
                        INSERT INTO retail_goods_receipts (id, tenant_id, receipt_no, branch_seq, branch_id, supplier_id, order_id,
                                                           received_on, currency, total_minor, journal_entry_id, created_by)
                        VALUES (?, ?, 'GRRLS-000001', 1, ?, ?, ?, DATE '2026-01-15', 'UGX', 200, ?, ?)
                        """)
                .params(goodsReceipt, t.tenantId(), t.headOffice(), supplier, order, entry, user)
                .update();
        owner.sql("""
                        INSERT INTO retail_goods_receipt_lines (id, tenant_id, receipt_id, line_no, order_line_id, product_id, qty,
                                                                entered_cost_minor, cost_minor, line_total_minor, stocked_minor)
                        VALUES (?, ?, ?, 1, ?, ?, 2, 100, 100, 200, 200)
                        """)
                .params(receiptLine, t.tenantId(), goodsReceipt, orderLine, retailProduct)
                .update();
        owner.sql("""
                        INSERT INTO retail_supplier_bills (id, tenant_id, bill_no, branch_seq, branch_id, supplier_id, supplier_ref,
                                                           bill_date, due_date, currency, total_minor, cleared_minor, variance_minor,
                                                           created_by)
                        VALUES (?, ?, 'SBRLS-000001', 1, ?, ?, 'INV-RLS-1', DATE '2026-01-15', DATE '2026-02-15', 'UGX', 200, 200, 0, ?)
                        """)
                .params(bill, t.tenantId(), t.headOffice(), supplier, user)
                .update();
        UUID billLine = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_supplier_bill_lines (id, tenant_id, bill_id, line_no, receipt_line_id, qty,
                                                                unit_price_minor, line_total_minor, cleared_minor, variance_minor)
                        VALUES (?, ?, ?, 1, ?, 2, 100, 200, 200, 0)
                        """).params(billLine, t.tenantId(), bill, receiptLine).update();
        owner.sql(
                        "INSERT INTO retail_supplier_bill_entries (tenant_id, bill_id, branch_id, entry_id) VALUES (?, ?, ?, ?)")
                .params(t.tenantId(), bill, t.headOffice(), entry)
                .update();
        owner.sql("""
                        INSERT INTO retail_supplier_payments (id, tenant_id, payment_no, branch_seq, branch_id, supplier_id, paid_on,
                                                              currency, method, amount_minor, allocation, journal_entry_id, created_by)
                        VALUES (?, ?, 'SPRLS-000001', 1, ?, ?, DATE '2026-01-15', 'UGX', 'cash', 100, 'manual', ?, ?)
                        """)
                .params(supplierPayment, t.tenantId(), t.headOffice(), supplier, entry, user)
                .update();
        owner.sql("""
                        INSERT INTO retail_supplier_payment_allocations (id, tenant_id, payment_id, line_no, bill_id, branch_id,
                                                                         amount_minor)
                        VALUES (gen_random_uuid(), ?, ?, 1, ?, ?, 100)
                        """)
                .params(t.tenantId(), supplierPayment, bill, t.headOffice())
                .update();
        owner.sql(
                        "INSERT INTO retail_supplier_payment_entries (tenant_id, payment_id, branch_id, entry_id) VALUES (?, ?, ?, ?)")
                .params(t.tenantId(), supplierPayment, t.secondBranch(), entry)
                .update();
        owner.sql("INSERT INTO retail_purchasing_settings (tenant_id, over_receipt_bp) VALUES (?, 500)")
                .param(t.tenantId())
                .update();
        // Landed cost, supplier credit notes and the hold on an uncosted receipt (ADR-033, #199): a landed cost with
        // its payee's
        // invoice, one charge, one allocated line and its entry; a hold; a credit note with its line and entry. The
        // landed
        // cost, its charge and its line go in one statement, because the parts must add up at the commit of the
        // statement.
        // JUSTIFICATION-A3: a fixture row for each new table; the isolation test needs one row per tenant table.
        UUID landedBill = UUID.randomUUID();
        UUID landed = UUID.randomUUID();
        UUID credit = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_supplier_bills (id, tenant_id, bill_no, branch_seq, branch_id, supplier_id, supplier_ref,
                                                           bill_date, due_date, currency, kind, total_minor, cleared_minor,
                                                           variance_minor, created_by)
                        VALUES (?, ?, 'SBRLS-000002', 2, ?, ?, 'FWD-RLS-1', DATE '2026-01-16', DATE '2026-02-16', 'UGX', 'landed_cost',
                                50, 50, 0, ?)
                        """)
                .params(landedBill, t.tenantId(), t.headOffice(), supplier, user)
                .update();
        owner.sql("""
                        WITH doc AS (
                            INSERT INTO retail_landed_costs (id, tenant_id, landed_no, branch_seq, branch_id, supplier_id, bill_id,
                                                             method, cost_date, currency, total_minor, created_by)
                            VALUES (:landed, :tenant, 'LCRLS-000001', 1, :branch, :supplier, :bill, 'value', DATE '2026-01-16',
                                    'UGX', 50, :user)
                        ), charge AS (
                            INSERT INTO retail_landed_cost_charges (id, tenant_id, landed_cost_id, line_no, kind, amount_minor)
                            VALUES (gen_random_uuid(), :tenant, :landed, 1, 'freight', 50)
                        )
                        INSERT INTO retail_landed_cost_lines (id, tenant_id, landed_cost_id, line_no, receipt_line_id, branch_id,
                                                              product_id, basis, allocated_minor, line_qty, on_hand_qty,
                                                              to_inventory_minor, to_cogs_minor, cost_before_minor,
                                                              cost_after_minor, cost_applied)
                        VALUES (gen_random_uuid(), :tenant, :landed, 1, :receiptLine, :branch, :product, 200, 50, 2, 2, 50, 0,
                                100, 125, true)
                        """)
                .param("landed", landed)
                .param("tenant", t.tenantId())
                .param("branch", t.headOffice())
                .param("supplier", supplier)
                .param("bill", landedBill)
                .param("user", user)
                .param("receiptLine", receiptLine)
                .param("product", retailProduct)
                .update();
        owner.sql(
                        "INSERT INTO retail_landed_cost_entries (tenant_id, landed_cost_id, branch_id, entry_id) VALUES (?, ?, ?, ?)")
                .params(t.tenantId(), landed, t.headOffice(), entry)
                .update();
        owner.sql("INSERT INTO retail_receipt_holds (tenant_id, receipt_id, held_by) VALUES (?, ?, ?)")
                .params(t.tenantId(), goodsReceipt, user)
                .update();
        owner.sql("""
                        INSERT INTO retail_supplier_credits (id, tenant_id, credit_no, branch_seq, branch_id, supplier_id, bill_id,
                                                             supplier_ref, credit_date, currency, kind, total_minor, reason, created_by)
                        VALUES (?, ?, 'SCRLS-000001', 1, ?, ?, ?, 'CN-RLS-1', DATE '2026-01-17', 'UGX', 'goods', 100,
                                'Test goods damaged', ?)
                        """)
                .params(credit, t.tenantId(), t.headOffice(), supplier, bill, user)
                .update();
        owner.sql("""
                        INSERT INTO retail_supplier_credit_lines (id, tenant_id, credit_id, line_no, bill_line_id, receipt_line_id,
                                                                  branch_id, product_id, qty, unit_credit_minor, line_total_minor,
                                                                  to_inventory_minor, to_cogs_minor, variance_minor,
                                                                  cost_before_minor, cost_after_minor, cost_applied)
                        VALUES (gen_random_uuid(), ?, ?, 1, ?, ?, ?, ?, 1, 100, 100, 100, 0, 0, 100, 100, false)
                        """)
                .params(t.tenantId(), credit, billLine, receiptLine, t.headOffice(), retailProduct)
                .update();
        owner.sql(
                        "INSERT INTO retail_supplier_credit_entries (tenant_id, credit_id, branch_id, entry_id) VALUES (?, ?, ?, ?)")
                .params(t.tenantId(), credit, t.headOffice(), entry)
                .update();
        // The facts of a credit note PDF (ADR-033, #197): issuer, day and buyer as named, for the fixture return.
        // JUSTIFICATION-A3: a fixture row for the new table; the isolation test needs one row per tenant table.
        owner.sql("""
                        INSERT INTO retail_credit_note_issues (tenant_id, return_id, issued_by, issued_on, buyer_name)
                        VALUES (?, ?, ?, DATE '2026-01-16', 'Test Buyer 01')
                        """).params(t.tenantId(), saleReturn, user).update();
        // The retail import's source references (migration V20, #55).
        owner.sql("""
                        INSERT INTO retail_import_refs (tenant_id, source_file, source_ref, target_type, source_user)
                        VALUES (?, 'sales', 'SAL-TEST-01', 'retail.sale', 'test.sales01')
                        """).param(t.tenantId()).update();
        // Stock transfers between branches (migration V22, #84).
        UUID transfer = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_transfers (id, tenant_id, from_branch_id, to_branch_id, transfer_date, currency,
                                                      cost_total_minor, status, created_by)
                        VALUES (?, ?, ?, ?, DATE '2026-01-15', 'UGX', 100, 'completed', ?)
                        """)
                .params(transfer, t.tenantId(), t.headOffice(), t.secondBranch(), user)
                .update();
        owner.sql("""
                        INSERT INTO retail_transfer_lines (id, tenant_id, transfer_id, line_no, product_id, qty, unit_cost_minor,
                                                           line_cost_minor)
                        SELECT ?, ?, ?, 1, id, 1, 100, 100 FROM retail_products WHERE tenant_id = ? LIMIT 1
                        """)
                .params(UUID.randomUUID(), t.tenantId(), transfer, t.tenantId())
                .update();
        // Retail branch roles (ADR-033, #191).
        owner.sql("INSERT INTO retail_branch_roles (tenant_id, branch_id, role) VALUES (?, ?, 'store')")
                .params(t.tenantId(), t.secondBranch())
                .update();
        // Retail optional documents (ADR-033, #197): a prefix, a counter, a quotation with a line and its document.
        // JUSTIFICATION-A3: a fixture row for each new table; the isolation test needs one row per tenant table.
        owner.sql(
                        "INSERT INTO retail_document_prefixes (tenant_id, type, prefix, changed_by) VALUES (?, 'invoice', 'TAX', ?)")
                .params(t.tenantId(), user)
                .update();
        owner.sql(
                        "INSERT INTO retail_document_series (tenant_id, type, branch_id, last_no) VALUES (?, 'invoice', ?, 1)")
                .params(t.tenantId(), t.headOffice())
                .update();
        UUID quotation = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_quotations (id, tenant_id, created_by, branch_id, quote_date, valid_until, buyer_name,
                                                       currency, total_minor)
                        VALUES (?, ?, ?, ?, DATE '2026-01-15', DATE '2026-02-15', 'Test Buyer 01', 'UGX', 150)
                        """).params(quotation, t.tenantId(), user, t.headOffice()).update();
        owner.sql("""
                        INSERT INTO retail_quotation_lines (id, tenant_id, quotation_id, line_no, product_id, qty, unit_price_minor,
                                                            line_total_minor)
                        VALUES (?, ?, ?, 1, ?, 1, 150, 150)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), quotation, retailProduct)
                .update();
        owner.sql("""
                        INSERT INTO retail_documents (id, tenant_id, created_by, type, number, seq_no, branch_id, issue_date,
                                                      sale_id, buyer_name, currency, total_minor, snapshot)
                        VALUES (?, ?, ?, 'invoice', 'TAX-HQ-000001', 1, ?, DATE '2026-01-15', ?, 'Test Buyer 01', 'UGX', 150,
                                '{"v": 1}')
                        """)
                .params(UUID.randomUUID(), t.tenantId(), user, t.headOffice(), sale)
                .update();
        // Retail pack sizes (ADR-033, #194): a pack of a unit other than the product's own.
        // JUSTIFICATION-A3: a fixture row for the new table; the isolation test needs one row per tenant table.
        owner.sql("""
                        WITH u AS (INSERT INTO retail_units (id, tenant_id, name) VALUES (gen_random_uuid(), ?, 'rls-carton')
                                   RETURNING id)
                        INSERT INTO retail_product_units (id, tenant_id, product_id, unit_id, factor)
                        SELECT gen_random_uuid(), ?, (SELECT id FROM retail_products WHERE tenant_id = ? LIMIT 1), u.id, 12
                          FROM u
                        """).params(t.tenantId(), t.tenantId(), t.tenantId()).update();
        // An item with sizes and colours (issue #384, ADR-051), with one variant: a second product of the tenant's.
        // JUSTIFICATION-A3: a fixture row for the new table; the isolation test needs one row per tenant table.
        UUID family = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_product_families (id, tenant_id, name, base_code, category_id, unit_id)
                        VALUES (?, ?, 'rls-romper', 'RLS-ROMP', ?, ?)
                        """).params(family, t.tenantId(), retailCategory, retailUnit).update();
        owner.sql("""
                        INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id, cost_minor,
                                                     sell_minor, currency, family_id, variant_size, variant_sort)
                        VALUES (?, ?, 'RLS-ROMP-S', 'rls-romper (S)', ?, ?, 1, 2, 'UGX', ?, 'S', 0)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), retailCategory, retailUnit, family)
                .update();
        // Retail price lists, their items and customer groups (ADR-033, #195).
        // JUSTIFICATION-A3: a fixture row for each new table; the isolation test needs one row per tenant table.
        owner.sql("""
                        WITH l AS (INSERT INTO retail_price_lists (id, tenant_id, name, currency)
                                   VALUES (gen_random_uuid(), ?, 'rls-wholesale', 'UGX') RETURNING id),
                             g AS (INSERT INTO retail_customer_groups (id, tenant_id, name, price_list_id)
                                   SELECT gen_random_uuid(), ?, 'rls-group', l.id FROM l RETURNING id)
                        INSERT INTO retail_price_list_items (id, tenant_id, price_list_id, product_id, unit_id, min_qty,
                                                             price_minor, valid_from)
                        SELECT gen_random_uuid(), ?, l.id, p.id, p.unit_id, 0, 90, DATE '2026-01-15'
                          FROM l, (SELECT id, unit_id FROM retail_products WHERE tenant_id = ? LIMIT 1) p
                        """)
                .params(t.tenantId(), t.tenantId(), t.tenantId(), t.tenantId())
                .update();
        // Loan servicing (increment 5, #108): a schedule item, a repayment on the fixture journal, its allocation.
        UUID item = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO lending_schedule_items (id, tenant_id, loan_id, item_no, due_date, principal_due_minor,
                                                            interest_due_minor, status)
                        VALUES (?, ?, ?, 1, DATE '2026-02-15', 100000, 10000, 'pending')
                        """).params(item, t.tenantId(), loan).update();
        UUID repayment = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO lending_loan_transactions (id, tenant_id, branch_id, loan_id, txn_type, amount_minor,
                                                               currency, value_date, payment_method_key, receipt_no,
                                                               journal_entry_id, source)
                        VALUES (?, ?, ?, ?, 'repayment', 1000, 'UGX', DATE '2026-01-15', 'cash', 'RC-HQ-999999', ?, 'staff')
                        """)
                .params(repayment, t.tenantId(), t.headOffice(), loan, entry)
                .update();
        owner.sql("""
                        INSERT INTO lending_repayment_allocations (id, tenant_id, transaction_id, applies_to_txn_id,
                                                                   schedule_item_id, component, amount_minor)
                        VALUES (?, ?, ?, ?, ?, 'principal', 1000)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), repayment, repayment, item)
                .update();
        // Savings (increment 9, #151): a product, an account, a deposit on the fixture journal, an
        // end-of-day balance and an interest posting.
        UUID savingsProduct = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO lending_savings_products (id, tenant_id, code, name, currency, interest_rate_bp,
                                                              interest_calc, interest_posting)
                        VALUES (?, ?, 'SAVE', 'Test savings', 'UGX', 500, 'daily_balance', 'monthly')
                        """).params(savingsProduct, t.tenantId()).update();
        UUID savingsAccount = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO lending_savings_accounts (id, tenant_id, branch_id, account_no, member_id, product_id,
                                                              currency, status, balance_minor, opened_on, txn_count)
                        VALUES (?, ?, ?, 'SV999999', ?, ?, 'UGX', 'active', 1000, DATE '2026-01-15', 1)
                        """)
                .params(savingsAccount, t.tenantId(), t.headOffice(), kinMember, savingsProduct)
                .update();
        owner.sql("""
                        INSERT INTO lending_savings_transactions (id, tenant_id, branch_id, account_id, seq, txn_type,
                            amount_minor, is_credit, currency, balance_after_minor, value_date, payment_method_key,
                            receipt_no, journal_entry_id, source)
                        VALUES (?, ?, ?, ?, 1, 'deposit', 1000, true, 'UGX', 1000, DATE '2026-01-15', 'cash',
                                'RC-HQ-999998', ?, 'staff')
                        """)
                .params(UUID.randomUUID(), t.tenantId(), t.headOffice(), savingsAccount, entry)
                .update();
        owner.sql("""
                        INSERT INTO lending_savings_daily_balances (tenant_id, account_id, business_date,
                                                                    closing_balance_minor)
                        VALUES (?, ?, DATE '2026-01-15', 1000)
                        """).params(t.tenantId(), savingsAccount).update();
        owner.sql("""
                        INSERT INTO lending_savings_interest_postings (id, tenant_id, account_id, period_start, period_end,
                                                                       interest_minor)
                        VALUES (?, ?, ?, DATE '2026-01-15', DATE '2026-01-31', 0)
                        """).params(UUID.randomUUID(), t.tenantId(), savingsAccount).update();
        // Investments (increment 10, #152): a product, an investment, a schedule period and its accrual.
        UUID investmentProduct = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO lending_investment_products (id, tenant_id, code, name, currency, product_type,
                            allowed_terms_months, return_rate_bp, return_method, payout_frequency, min_amount_minor, status)
                        VALUES (?, ?, 'RLS', 'Test Investment Product', 'UGX', 'fixed_term', '{6}', 1200, 'flat',
                                'monthly', 100000, 'active')
                        """).params(investmentProduct, t.tenantId()).update();
        UUID investment = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO lending_investments (id, tenant_id, branch_id, account_no, member_id, product_id,
                            currency, status, principal_minor, return_rate_bp, return_method, term_months,
                            payout_frequency, product_type, early_withdrawal_allowed, start_date, maturity_date,
                            agreed_return_minor, principal_held_minor, channel)
                        SELECT ?, ?, ?, 'IV999999', id, ?, 'UGX', 'active', 1000000, 1200, 'flat', 6, 'monthly',
                               'fixed_term', false, DATE '2026-01-15', DATE '2026-07-15', 60000, 1000000, 'staff'
                          FROM lending_members WHERE tenant_id = ? LIMIT 1
                        """)
                .params(investment, t.tenantId(), t.headOffice(), investmentProduct, t.tenantId())
                .update();
        UUID accrual = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO lending_investment_transactions (id, tenant_id, branch_id, investment_id, txn_type,
                            amount_minor, return_minor, currency, value_date, period_no, journal_entry_id, source)
                        VALUES (?, ?, ?, ?, 'return_accrual', 10000, 10000, 'UGX', DATE '2026-02-15', 1, ?, 'system')
                        """)
                .params(accrual, t.tenantId(), t.headOffice(), investment, entry)
                .update();
        owner.sql("""
                        INSERT INTO lending_investment_schedule_items (id, tenant_id, investment_id, period_no,
                            period_start, period_end, return_minor, opening_balance_minor, is_payout, status,
                            accrued_txn_id)
                        VALUES (?, ?, ?, 1, DATE '2026-01-15', DATE '2026-02-15', 10000, 1000000, true, 'accrued', ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), investment, accrual)
                .update();
        // Lending insights (#153): a daily snapshot row and the digest settings.
        owner.sql("""
                        INSERT INTO lending_loan_daily_snapshots (tenant_id, business_date, loan_id, branch_id,
                            officer_user_id, product_id, principal_outstanding_minor, interest_outstanding_minor,
                            arrears_minor, days_past_due, par_bucket)
                        VALUES (?, DATE '2026-01-15', ?, ?, ?, ?, 100000, 10000, 0, 0, 'current')
                        """).params(t.tenantId(), loan, t.headOffice(), user, product).update();
        owner.sql("INSERT INTO lending_insights_digest_settings (id, tenant_id) VALUES (?, ?)")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        // The retail cash book (migration V34, #147).
        UUID category = UUID.randomUUID();
        UUID expenseItem = UUID.randomUUID();
        UUID party = UUID.randomUUID();
        UUID advance = UUID.randomUUID();
        owner.sql("INSERT INTO retail_expense_categories (id, tenant_id, name) VALUES (?, ?, 'Test Category 01')")
                .params(category, t.tenantId())
                .update();
        owner.sql(
                        "INSERT INTO retail_expense_items (id, tenant_id, category_id, name) VALUES (?, ?, ?, 'Test Item 01')")
                .params(expenseItem, t.tenantId(), category)
                .update();
        owner.sql("INSERT INTO retail_cash_parties (id, tenant_id, name, kind) VALUES (?, ?, 'Test Owner 01', 'owner')")
                .params(party, t.tenantId())
                .update();
        owner.sql("""
                        INSERT INTO retail_daily_savings (id, tenant_id, branch_id, business_date, amount_minor, currency,
                                                          occurred_at, recorded_by)
                        VALUES (?, ?, ?, DATE '2026-01-15', 100, 'UGX', now(), ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), t.headOffice(), user)
                .update();
        owner.sql("""
                        INSERT INTO retail_cash_bankings (id, tenant_id, branch_id, business_date, amount_minor, currency,
                                                          expected_minor, banked_at, recorded_by)
                        VALUES (?, ?, ?, DATE '2026-01-15', 100, 'UGX', 100, now(), ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), t.headOffice(), user)
                .update();
        owner.sql("""
                        INSERT INTO retail_cash_withdrawals (id, tenant_id, branch_id, business_date, amount_minor, currency,
                                                             withdrawn_at, recorded_by)
                        VALUES (?, ?, ?, DATE '2026-01-15', 100, 'UGX', now(), ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), t.headOffice(), user)
                .update();
        owner.sql("""
                        INSERT INTO retail_expenses (id, tenant_id, branch_id, business_date, category_id, item_id,
                                                     category_name, item_name, amount_minor, currency, occurred_at, recorded_by)
                        VALUES (?, ?, ?, DATE '2026-01-15', ?, ?, 'Test Category 01', 'Test Item 01', 100, 'UGX', now(), ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), t.headOffice(), category, expenseItem, user)
                .update();
        // One statement, so the deferred repaid_minor check sees the advance and its repayment together.
        owner.sql("""
                        WITH a AS (INSERT INTO retail_advances (id, tenant_id, advance_no, branch_id, party_id, principal_minor,
                                                                currency, business_date, repaid_minor, occurred_at, recorded_by)
                                   VALUES (?, ?, 'RA00000001', ?, ?, 100, 'UGX', DATE '2026-01-15', 40, now(), ?) RETURNING id)
                        INSERT INTO retail_advance_repayments (id, tenant_id, advance_id, branch_id, amount_minor, currency,
                                                               method, paid_on, recorded_by)
                        SELECT ?, ?, a.id, ?, 40, 'UGX', 'cash', DATE '2026-01-16', ? FROM a
                        """)
                .params(
                        advance,
                        t.tenantId(),
                        t.headOffice(),
                        party,
                        user,
                        UUID.randomUUID(),
                        t.tenantId(),
                        t.headOffice(),
                        user)
                .update();
        // A report run (migration V49, extras slice D1). Cancelled, so the runner of another test in the same database
        // never finds it queued and tries to execute a run with an empty scope.
        owner.sql("""
                        INSERT INTO report_runs (id, tenant_id, report_key, params, params_hash, scope, format, status,
                                                 requested_by, as_of, finished_at, row_cap, size_cap_bytes)
                        VALUES (?, ?, 'lending.par', '{}', repeat('a', 64), '{}', 'json', 'cancelled', ?,
                                DATE '2026-01-15', now(), 1000, 1000000)
                        """).params(UUID.randomUUID(), t.tenantId(), user).update();
        // The language model call log (migration V51, #220): a finished call by the fixture user, no text.
        // JUSTIFICATION-A3: a fixture row for the new table; the isolation test needs one row per tenant table.
        owner.sql("""
                        INSERT INTO ai_calls (id, tenant_id, finished_at, purpose, user_id, model, request_hash, outcome,
                                              input_tokens, output_tokens, tokens_counted, estimated_cost_micro,
                                              cost_counted_micro, latency_ms, attempts)
                        VALUES (?, ?, now(), 'brief', ?, 'test-model', repeat('a', 64), 'ok', 10, 20, 30, 5, 5, 120, 1)
                        """).params(UUID.randomUUID(), t.tenantId(), user).update();
        // The stored daily AI brief (migration V55, #226): the rendered text and the facts-only fallback, never model
        // text.
        // JUSTIFICATION-A3: a fixture row for the new table; the isolation test needs one row per tenant table.
        owner.sql("""
                        INSERT INTO lending_ai_briefs (tenant_id, brief_date, source, reason, summary, points, chips,
                                                       facts_as_of, generated_at)
                        VALUES (?, current_date, 'fallback', 'validation', 'Summary for a fabricated day.', '[]'::jsonb,
                                '[]'::jsonb, current_date, now())
                        """).params(t.tenantId()).update();
        // A payment intent and the notification that named it (migration V54, #213). JUSTIFICATION-A3: one factory row
        // per tenant table.
        UUID intent = UUID.randomUUID();
        String hex = intent.toString().replace("-", "");
        owner.sql("""
                        INSERT INTO payment_intents (id, tenant_id, branch_id, purpose, subject_type, subject_id, payer_phone_e164,
                                                     amount_minor, currency, merchant_reference, status, initiated_by,
                                                     idempotency_key, expires_at)
                        VALUES (?, ?, ?, 'test.payment', 'test_subject', ?, '+256700000001', 1500, 'UGX', ?, 'created', ?,
                                'rls-factory-key', now() + interval '30 minutes')
                        """)
                .params(intent, t.tenantId(), t.headOffice(), UUID.randomUUID(), "BMS-DV-" + hex, user)
                .update();
        owner.sql("""
                        INSERT INTO payment_callbacks (id, tenant_id, intent_id, provider, kind, merchant_reference)
                        VALUES (?, ?, ?, 'pesapal', 'ipn', ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), intent, "BMS-DV-" + hex)
                .update();
        // A lender's own payment account (migration V56, #233). JUSTIFICATION-A3: one factory row per tenant table. The
        // two
        // boxes are filler bytes of the right length; nothing here is a credential.
        owner.sql("""
                        INSERT INTO payment_gateway_accounts (id, tenant_id, consumer_key_enc, consumer_secret_enc,
                                                              consumer_key_hmac, key_hint, data_key_id, status, updated_by)
                        VALUES (?, ?, ?, ?, ?, 'abcd', 'k1', 'saved', ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), new byte[40], new byte[40], hex64(t.tenantId()), user)
                .update();
        // Money a purpose handler refused, held for a cashier (migration V59, #216). JUSTIFICATION-A3: one factory row
        // per
        // tenant table; it points at the fixture intent and the fixture journal entry above.
        owner.sql("""
                        INSERT INTO unallocated_receipts (id, tenant_id, branch_id, intent_id, purpose, subject_type, subject_id,
                                                          amount_minor, currency, value_date, confirmation_code, refusal_code,
                                                          journal_entry_id)
                        VALUES (?, ?, ?, ?, 'test.payment', 'test_subject', ?, 1500, 'UGX', DATE '2026-01-15', 'RLS-CONF-1',
                                'loan_closed', ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), t.headOffice(), intent, UUID.randomUUID(), entry)
                .update();
        // Customer orders with delivery (migration V70, #385): an order, its line and its history row.
        // JUSTIFICATION-A3: a fixture row for each new table; the isolation test needs one row per tenant table.
        UUID customerOrder = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO retail_customer_orders (id, tenant_id, created_by, order_no, branch_id, ordered_on, source,
                                                            customer_name, customer_phone, currency, items_total_minor)
                        VALUES (?, ?, ?, 'OR000001', ?, DATE '2026-01-15', 'whatsapp', 'Test Customer 01', '+256700000012',
                                'UGX', 150)
                        """).params(customerOrder, t.tenantId(), user, t.headOffice()).update();
        owner.sql("""
                        INSERT INTO retail_customer_order_lines (id, tenant_id, order_id, line_no, product_id, qty, base_qty,
                                                                 unit_price_minor, line_total_minor)
                        VALUES (?, ?, ?, 1, ?, 1, 1, 150, 150)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), customerOrder, retailProduct)
                .update();
        owner.sql("""
                        INSERT INTO retail_customer_order_events (id, tenant_id, order_id, step, by_user)
                        VALUES (?, ?, ?, 'ordered', ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), customerOrder, user)
                .update();
        // The low-stock level of an item and the record of the daily notices (migration V76, #412).
        owner.sql("""
                        INSERT INTO retail_stock_minimums (id, tenant_id, product_id, branch_id, min_qty, updated_by)
                        VALUES (?, ?, ?, ?, 5, ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), retailProduct, t.headOffice(), user)
                .update();
        owner.sql(
                        "INSERT INTO retail_notice_runs (tenant_id, kind, run_on) VALUES (?, 'daily_summary', DATE '2026-01-15')")
                .param(t.tenantId())
                .update();
        // The merchant statement, its line, a reconciliation run and one of its findings (migration V85, #317).
        // JUSTIFICATION-A3: one factory row per tenant table; they point at the fixture user, intent and journal entry
        // above.
        UUID statement = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO payment_statements (id, tenant_id, uploaded_by, file_name, content_sha256, row_count,
                            period_from, period_to, currency, matched_count, different_count, unmatched_count, fees_posted_minor)
                        VALUES (?, ?, ?, 'statement.csv', ?, 1, DATE '2026-01-15', DATE '2026-01-15', 'UGX', 1, 0, 0, 0)
                        """).params(statement, t.tenantId(), user, hex64(statement)).update();
        UUID statementRow = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO payment_statement_rows (id, tenant_id, statement_id, row_no, order_tracking_id, amount_minor,
                            fee_minor, currency, paid_on, match_status, intent_id)
                        VALUES (?, ?, ?, 1, ?, 1500, 0, 'UGX', DATE '2026-01-15', 'matched', ?)
                        """)
                .params(statementRow, t.tenantId(), statement, UUID.randomUUID().toString(), intent)
                .update();
        // The confirmer of a review is a second person (ADR-035 decision 66).
        UUID confirmer = UUID.randomUUID();
        owner.sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', 'Test Staff 02', ?, 'active')")
                .params(confirmer, t.tenantId(), "confirmer-" + confirmer + "@example.test")
                .update();
        owner.sql("""
                        INSERT INTO payment_statement_reviews (id, tenant_id, row_id, reviewed_by, confirmed_by, note)
                        VALUES (?, ?, ?, ?, ?, 'Sale at the shop')
                        """)
                .params(UUID.randomUUID(), t.tenantId(), statementRow, user, confirmer)
                .update();
        UUID run = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO payment_reconciliation_runs (id, tenant_id, ran_on, trigger, window_from, intents_examined,
                            statements_used, finding_count)
                        VALUES (?, ?, DATE '2026-01-15', 'job', DATE '2025-09-17', 1, 1, 1)
                        """).params(run, t.tenantId()).update();
        owner.sql(
                        "INSERT INTO payment_reconciliation_findings (id, tenant_id, run_id, kind, intent_id) VALUES (?, ?, ?, 'pending_over_24h', ?)")
                .params(UUID.randomUUID(), t.tenantId(), run, intent)
                .update();
    }

    /**
     * Customer orders (V70, #385): bound to A, no order, line or history row of B is read, changed or written stamped with B, and
     * the lines and the history are append-only.
     */
    @Test
    void customerOrdersAreIsolatedPerTenant() throws SQLException {
        for (String table :
                List.of("retail_customer_orders", "retail_customer_order_lines", "retail_customer_order_events")) {
            long ofB = asApp(
                    a.tenantId(),
                    c -> count(c, "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", b.tenantId()));
            long ofA = asApp(a.tenantId(), c -> count(c, "SELECT count(*) FROM " + table));
            assertThat(ofB).as("rows of B in %s", table).isZero();
            assertThat(ofA).as("rows of A in %s", table).isPositive();
        }
        int changed = asApp(a.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE retail_customer_orders SET notes = 'x', version = version + 1 WHERE tenant_id = ?")) {
                ps.setObject(1, b.tenantId());
                return ps.executeUpdate();
            }
        });
        assertThat(changed).isZero();
        assertThatThrownBy(() -> asApp(a.tenantId(), c -> {
                    try (PreparedStatement ps = c.prepareStatement("""
                            INSERT INTO retail_customer_order_events (id, tenant_id, order_id, step, by_user)
                            SELECT gen_random_uuid(), ?, id, 'changed', gen_random_uuid() FROM retail_customer_orders LIMIT 1
                            """)) {
                        ps.setObject(1, b.tenantId());
                        return ps.executeUpdate();
                    }
                }))
                .hasStackTraceContaining("row-level security");
        for (String table : List.of("retail_customer_order_lines", "retail_customer_order_events")) {
            assertThatThrownBy(() -> asApp(
                            a.tenantId(),
                            c -> count(c, "WITH d AS (DELETE FROM " + table + " RETURNING 1) SELECT count(*) FROM d")))
                    .as(table)
                    .hasStackTraceContaining("permission denied");
        }
    }

    /** The two platform payment tables have no grant for the application role: only the definer functions reach them (V54). */
    @Test
    void paymentPlatformTablesAreClosedToTheApplicationRole() throws SQLException {
        for (String table : List.of("payment_unmatched_callbacks", "payment_callback_counters")) {
            assertThatThrownBy(() -> asApp(a.tenantId(), c -> count(c, "SELECT count(*) FROM " + table)))
                    .as("SELECT on %s", table)
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> asApp(
                            a.tenantId(),
                            c -> count(c, "WITH d AS (DELETE FROM " + table + " RETURNING 1) SELECT count(*) FROM d")))
                    .as("DELETE on %s", table)
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> asApp(a.tenantId(), c -> {
                        try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET hits = 0")) {
                            ps.execute();
                            return null;
                        }
                    }))
                    .as("UPDATE on %s", table)
                    .hasStackTraceContaining("permission denied");
        }
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
                           -- Platform tables (chapter 6 section 6.4) carry no tenant policy by design. The outbox
                           -- is one: it gained a tenant_id for the SMS channel (ADR-036) and stays reachable
                           -- only through definer functions; smsPlatformTablesAreClosedToTheApplicationRole
                           -- proves it.
                           AND c.relname NOT LIKE 'platform\\_%'
                           AND c.relname <> 'notification_outbox'
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

    /**
     * ADR-036, FR-NTF-13: the SMS delivery log is tenant isolated like any tenant table: tenant A
     * reads none of B's rows and cannot write a row stamped with B.
     */
    @Test
    void theSmsDeliveryLogAndOptOutsAreIsolatedPerTenant() throws SQLException {
        for (String table : List.of("sms_deliveries", "sms_opt_outs", "sms_template_overrides")) {
            long seesOwn = asApp(a.tenantId(), c -> count(c, "SELECT count(*) FROM " + table));
            long seesOthers = asApp(
                    a.tenantId(),
                    c -> count(c, "SELECT count(*) FROM " + table + " WHERE tenant_id = ?", b.tenantId()));
            assertThat(seesOwn).as("own rows in %s", table).isPositive();
            assertThat(seesOthers).as("rows of B in %s", table).isZero();
        }
        assertThatThrownBy(() -> asApp(a.tenantId(), c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO sms_deliveries (id, tenant_id, outbox_id, template_key, category, recipient_masked,"
                                    + " recipient_hash, provider, status) VALUES (?, ?, ?, 'x', 'transactional', '+256*****0001',"
                                    + " repeat('c', 64), 'sink', 'sunk')")) {
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, b.tenantId());
                        ps.setObject(3, UUID.randomUUID());
                        return ps.executeUpdate();
                    }
                }))
                .hasStackTraceContaining("violates row-level security policy");
        // The delivery log is kept 13 months by a definer function; the application role has no DELETE.
        assertThatThrownBy(() -> asApp(
                        a.tenantId(),
                        c -> count(c, "WITH d AS (DELETE FROM sms_deliveries RETURNING 1) SELECT count(*) FROM d")))
                .hasStackTraceContaining("permission denied");
    }

    /**
     * ADR-036 decision 2 and 13: the outbox (now with a tenant_id), the operator's per tenant controls
     * and the channel state are platform tables. The application role has no privilege on them, so a
     * tenant session reaches them only through the definer functions, and the tenant is read from the
     * bound transaction, never a parameter.
     */
    @Test
    void smsPlatformTablesAreClosedToTheApplicationRole() throws SQLException {
        for (String table : List.of(
                "notification_outbox", "platform_sms_controls", "sms_channel_state", "platform_sms_sender_ids")) {
            assertThatThrownBy(() -> asApp(a.tenantId(), c -> count(c, "SELECT count(*) FROM " + table)))
                    .as("SELECT on %s", table)
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> asApp(
                            a.tenantId(),
                            c -> count(c, "WITH d AS (DELETE FROM " + table + " RETURNING 1) SELECT count(*) FROM d")))
                    .as("DELETE on %s", table)
                    .hasStackTraceContaining("permission denied");
        }
        // The enqueue overload takes no tenant parameter: it stamps the bound tenant, or none.
        String bound = "rls.sms.outbox:" + UUID.randomUUID();
        String unbound = "rls.sms.outbox:" + UUID.randomUUID();
        enqueueAsApp(a.tenantId(), bound);
        enqueueAsApp(null, unbound);
        assertThat(TestDatabase.owner()
                        .sql("SELECT tenant_id FROM notification_outbox WHERE idempotency_key = ?")
                        .param(bound)
                        .query(UUID.class)
                        .single())
                .isEqualTo(a.tenantId());
        assertThat(TestDatabase.owner()
                        .sql("SELECT tenant_id FROM notification_outbox WHERE idempotency_key = ?")
                        .param(unbound)
                        .query(UUID.class)
                        .optional())
                .isEmpty();
        // A tenant transaction cannot change the operator's SMS controls (platform only).
        assertThatThrownBy(() -> asApp(a.tenantId(), c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement("SELECT sms_tenant_control_set(?, true, 100000, NULL, now())")) {
                        ps.setObject(1, a.tenantId());
                        ps.execute();
                        return null;
                    }
                }))
                .hasStackTraceContaining("platform operator only");
    }

    /**
     * Every SMS and tenant lookup function of V50 is a definer function owned by bms_owner, executable
     * by bms_app and not by PUBLIC (ADR-016): they are the only way into the platform SMS tables.
     */
    @Test
    void theSmsFunctionsAreDefinerFunctionsNotExecutableByPublic() {
        List<Map<String, Object>> functions =
                TestDatabase.owner().sql("""
                        SELECT p.oid::text AS oid, p.proname FROM pg_proc p
                         WHERE p.pronamespace = 'public'::regnamespace
                           AND (p.proname LIKE 'sms\\_%' OR p.proname = 'app_tenant_is_active')
                        """).query().listOfRows();
        assertThat(functions)
                .extracting(f -> f.get("proname"))
                .contains(
                        "sms_tenant_control_current",
                        "sms_tenant_control_get",
                        "sms_tenant_control_set",
                        "sms_channel_mode_changed",
                        "sms_platform_count_since",
                        "sms_tenant_count_since",
                        "sms_deliveries_purge",
                        "app_tenant_is_active");
        for (Map<String, Object> f : functions) {
            Map<String, Object> row =
                    TestDatabase.owner().sql("""
                            SELECT p.prosecdef, pg_get_userbyid(p.proowner) AS owner,
                                   has_function_privilege('bms_app', p.oid, 'EXECUTE') AS app,
                                   coalesce(array_to_string(p.proacl, ','), '') AS acl
                              FROM pg_proc p WHERE p.oid = ?::oid
                            """).param(f.get("oid")).query().singleRow();
            assertThat(row.get("prosecdef"))
                    .as("%s is SECURITY DEFINER", f.get("proname"))
                    .isEqualTo(true);
            assertThat(row.get("owner")).as("%s owner", f.get("proname")).isEqualTo("bms_owner");
            assertThat(row.get("app"))
                    .as("%s executable by bms_app", f.get("proname"))
                    .isEqualTo(true);
            assertThat(List.of(((String) row.get("acl")).split(",")))
                    .as("%s not executable by PUBLIC", f.get("proname"))
                    .noneMatch(entry -> entry.startsWith("="));
        }
    }

    /** Calls the enqueue overload as bms_app and commits, so the owner can read the row back. */
    static void enqueueAsApp(UUID tenantId, String key) throws SQLException {
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            if (tenantId != null) {
                try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    ps.setString(1, tenantId.toString());
                    ps.execute();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT notification_outbox_enqueue(?, 'telegram', 'operator', 'sms.cap_alert', '{}'::jsonb, ?, NULL, now() - interval '1 hour',"
                            + " now(), 'transactional', NULL, NULL, NULL)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setString(2, key);
                ps.execute();
            }
            c.commit();
        }
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
        // The loan servicing ledgers are append-only too (#108).
        // The savings ledgers too (#151).
        for (String table : List.of(
                "lending_loan_transactions",
                "lending_repayment_allocations",
                "lending_savings_transactions",
                "lending_savings_daily_balances",
                "lending_savings_interest_postings",
                "lending_investment_transactions")) {
            assertThatThrownBy(() -> asApp(
                            a.tenantId(),
                            c -> count(c, "WITH d AS (DELETE FROM " + table + " RETURNING 1) SELECT count(*) FROM d")))
                    .as(table)
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> TestDatabase.owner()
                            .sql("DELETE FROM " + table + " WHERE tenant_id = ?")
                            .param(a.tenantId())
                            .update())
                    .as(table)
                    .hasStackTraceContaining("append-only");
        }
        // Even the owner is refused unless a migration explicitly opts in.
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("DELETE FROM audit_log WHERE tenant_id = ?")
                        .param(a.tenantId())
                        .update())
                .hasStackTraceContaining("append-only");
    }

    /** A 64 character lower case hex string that differs per tenant (the unique digest column of a fixture). */
    private static String hex64(UUID id) {
        return (id.toString().replace("-", "") + id.toString().replace("-", "")).substring(0, 64);
    }
}
