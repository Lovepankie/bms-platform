package com.rincoltech.bms.core.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.tenancy.RlsIsolationIT;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Closing a business (issue #571, ADR-090, FR-TEN-09) through HTTP: only the Owner asks, after the step-up, and only the Owner
 * cancels; the request is audited in both logs, queues a full data export for the Owner and emails the Owner; the business is
 * read only at once (writes refused with {@code business_closing}, reads, exports and the cancel allowed) and works again
 * after the cancel; the operator sees the request, cannot remove it during the cooling-off period nor without their own
 * code, and once it is over removes it: personal data deleted or anonymised, money records kept, the business closed,
 * another business untouched. The removal bypass is refused to the business itself. Fabricated businesses and people only.
 */
class BusinessClosureIT extends IntegrationTest {

    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");

    @Autowired
    TestRestTemplate http;

    @Autowired
    ClosureService closures;

    LocalDate today;

    record Business(
            TestDatabase.Fixture t,
            Api api,
            UUID owner,
            Session ownerSession,
            Session adminSession,
            Session cashierSession,
            String ownerEmail,
            String adminEmail) {}

    record Operator(Api api, UUID id, Session session) {}

    @BeforeEach
    void setUp() {
        http = Api.withoutRetries(http);
        today = LocalDate.now(KAMPALA);
    }

    Business business() {
        TestDatabase.Fixture t = TestDatabase.tenant("close", true, true);
        TestDatabase.owner()
                .sql("""
                        INSERT INTO subscriptions (id, tenant_id, plan_id, status, current_period_start, current_period_end)
                        SELECT ?, ?, t.plan_id, 'active', ?, ? FROM tenants t WHERE t.id = ?
                        """)
                .params(UUID.randomUUID(), t.tenantId(), today.minusDays(5), today.plusDays(25), t.tenantId())
                .update();
        Api api = Api.tenant(http, t.slug());
        String ownerEmail = Api.email("close-owner");
        String adminEmail = Api.email("close-admin");
        String cashierEmail = Api.email("close-cashier");
        UUID owner = Api.staff(t, ownerEmail, new Role("tenant_admin", null));
        UUID admin = Api.staff(t, adminEmail, new Role("tenant_admin", null));
        UUID cashier = Api.staff(t, cashierEmail, new Role("cashier", t.headOffice()));
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = true WHERE tenant_id = ? AND id = ?")
                .params(t.tenantId(), owner)
                .update();
        return new Business(
                t,
                api,
                owner,
                api.signIn(owner, ownerEmail, Api.PASSWORD, null),
                api.signIn(admin, adminEmail, Api.PASSWORD, null),
                api.signIn(cashier, cashierEmail, Api.PASSWORD, null),
                ownerEmail,
                adminEmail);
    }

    Operator operator() {
        Api platform = Api.platform(http);
        String email = Api.email("close-op");
        UUID id = Api.platformUser(email);
        return new Operator(platform, id, platform.signIn(id, email, Api.PASSWORD, null));
    }

    /** Fabricated personal data the removal must take away, and a credit sale whose amounts it must keep. */
    void personalData(Business b) {
        var db = TestDatabase.owner();
        db.sql("""
                        INSERT INTO lending_members (id, tenant_id, branch_id, member_no, full_name, phone_e164, id_type,
                            national_id, date_of_birth, village, currency, kyc_status, status, source)
                        VALUES (?, ?, ?, 'M000001', 'Test Borrower 01', '+256700000001', 'nin', 'CMTEST0000001A',
                            DATE '1990-01-01', 'Test Village', 'UGX', 'verified', 'active', 'staff')
                        """)
                .params(UUID.randomUUID(), b.t().tenantId(), b.t().headOffice())
                .update();
        db.sql("""
                        INSERT INTO retail_customers (id, tenant_id, name, contact)
                        VALUES (?, ?, 'Test Customer 01', '+256700000002')
                        """).params(UUID.randomUUID(), b.t().tenantId()).update();
        db.sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, buyer_name,
                            buyer_contact, currency, total_minor, cost_total_minor, paid_minor, status, historical,
                            created_by)
                        VALUES (gen_random_uuid(), ?, ?, 'RSCLOSE001', ?, 'credit', 'Test Buyer 01', '+256700000003', 'UGX',
                            50000, 30000, 0, 'completed', true, '00000000-0000-0000-0000-000000000000')
                        """)
                .params(b.t().tenantId(), b.t().headOffice(), today.minusDays(3))
                .update();
    }

    static Map<String, Object> password() {
        return Map.of("password", Api.PASSWORD);
    }

    static void assertRefused(ResponseEntity<JsonNode> r, HttpStatus status, String code) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(status);
        assertThat(r.getBody().get("code").asString()).isEqualTo(code);
    }

    static long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    static long platformAudits(String action, UUID tenantId) {
        return count("SELECT count(*) FROM platform_audit_log WHERE action = ? AND tenant_id = ?", action, tenantId);
    }

    static long tenantAudits(String action, UUID tenantId) {
        return count("SELECT count(*) FROM audit_log WHERE action = ? AND tenant_id = ?", action, tenantId);
    }

    ResponseEntity<JsonNode> request(Business b) {
        return b.api().post("/api/v1/closure", password(), b.ownerSession().accessToken());
    }

    /** A write on an open business: the Owner names a billing contact. */
    ResponseEntity<JsonNode> write(Business b) {
        return b.api()
                .call(
                        HttpMethod.PUT,
                        "/api/v1/billing/contact",
                        Map.of("full_name", "Test Accountant", "email", Api.email("close-acct")),
                        b.ownerSession().accessToken());
    }

    /** The Owner (or anyone) sets the month the financial year ends in, through the settings with their version. */
    ResponseEntity<JsonNode> yearEnd(Business b, Session by, int month) {
        String version = b.api()
                .get("/api/v1/settings", by.accessToken())
                .getBody()
                .get("version")
                .asString();
        return b.api()
                .call(
                        HttpMethod.PATCH,
                        "/api/v1/settings",
                        Map.of("financial_year_end_month", month),
                        by.accessToken(),
                        Map.of("If-Match", "\"" + version + "\""));
    }

    void coolingOffOver(UUID closureId) {
        TestDatabase.owner()
                .sql("UPDATE tenant_closures SET remove_after = ? WHERE id = ?")
                .params(today.minusDays(1), closureId)
                .update();
    }

    ResponseEntity<JsonNode> remove(Operator op, UUID closureId, String code) {
        return op.api()
                .post(
                        "/api/v1/platform/closures/" + closureId + "/remove",
                        Map.of("totp_code", code, "reason", "Test: the owner asked and the cooling-off is over"),
                        op.session().accessToken());
    }

    String code(Operator op) {
        Api.allowTotpReuse(op.id());
        return Api.totp(op.session().totpSecret(), Instant.now());
    }

    /** FR-TEN-09: the Owner alone asks, with the step-up; administrators see the card, other staff do not. */
    @Test
    void frTen09_onlyTheOwnerAsksAfterTheStepUp() {
        Business b = business();

        ResponseEntity<JsonNode> card =
                b.api().get("/api/v1/closure", b.adminSession().accessToken());
        assertThat(card.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(card.getBody().get("status").asString()).isEqualTo("none");
        assertThat(card.getBody().get("can_change").asBoolean()).isFalse();
        assertThat(card.getBody().get("cooling_off_days").asInt()).isEqualTo(30);
        assertThat(b.api()
                        .get("/api/v1/closure", b.cashierSession().accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertRefused(
                b.api().post("/api/v1/closure", password(), b.adminSession().accessToken()),
                HttpStatus.FORBIDDEN,
                "owner_only");
        assertRefused(
                b.api().post("/api/v1/closure", Map.of(), b.ownerSession().accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "password_required");
        ResponseEntity<JsonNode> wrong = b.api()
                .post(
                        "/api/v1/closure",
                        Map.of("password", "not-the-password"),
                        b.ownerSession().accessToken());
        assertThat(wrong.getStatusCode().is4xxClientError()).isTrue();
        assertThat(count("SELECT count(*) FROM tenant_closures WHERE tenant_id = ?", b.t().tenantId()))
                .isZero();

        ResponseEntity<JsonNode> made = request(b);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(made.getBody().get("status").asString()).isEqualTo("requested");
        assertThat(made.getBody().get("remove_after").asString())
                .isEqualTo(today.plusDays(30).toString());
        assertThat(made.getBody().get("can_change").asBoolean()).isTrue();

        UUID exportRun = UUID.fromString(made.getBody().get("export_run_id").asString());
        assertThat(count(
                        "SELECT count(*) FROM report_runs WHERE id = ? AND tenant_id = ? AND report_key = 'core.data_export'"
                                + " AND requested_by = ?",
                        exportRun,
                        b.t().tenantId(),
                        b.owner()))
                .as("a full data export queued for the Owner")
                .isEqualTo(1);
        assertThat(tenantAudits("core.business.closure_requested", b.t().tenantId()))
                .isEqualTo(1);
        assertThat(platformAudits("platform.tenant.closure_requested", b.t().tenantId()))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM notification_outbox WHERE tenant_id = ? AND template_key = ? AND recipient = ?",
                        b.t().tenantId(),
                        ClosureService.REQUESTED,
                        b.ownerEmail()))
                .isEqualTo(1);
    }

    /** FR-TEN-09: read only at once, reads, exports and the cancel still allowed; the cancel brings writes back. */
    @Test
    void frTen09_readOnlyAtOnceAndTheOwnerCancels() {
        Business b = business();
        assertThat(write(b).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(b).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertRefused(write(b), HttpStatus.LOCKED, "business_closing");
        assertRefused(
                b.api().post("/api/v1/closure", password(), b.ownerSession().accessToken()),
                HttpStatus.LOCKED,
                "business_closing");
        assertThat(b.api()
                        .get("/api/v1/billing", b.adminSession().accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> status =
                b.api().get("/api/v1/plan/status", b.cashierSession().accessToken());
        assertThat(status.getBody().get("closing_remove_after").asString())
                .isEqualTo(today.plusDays(30).toString());
        ResponseEntity<JsonNode> export = b.api()
                .post("/api/v1/data-export/runs", Map.of(), b.adminSession().accessToken());
        assertThat(export.getStatusCode().is2xxSuccessful())
                .as("%s", export.getBody())
                .isTrue();
        assertThat(b.api()
                        .post("/api/v1/auth/refresh", null, null)
                        .getStatusCode()
                        .value())
                .as("the sign-in routes are not the read-only lock")
                .isNotEqualTo(HttpStatus.LOCKED.value());
        ResponseEntity<JsonNode> login = b.api().login(b.adminEmail(), Api.PASSWORD);
        assertThat(login.getStatusCode())
                .as("staff still sign in: %s", login.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(jobsSee(b)).as("no job runs for a closing business").isFalse();

        assertRefused(
                b.api().post("/api/v1/closure/cancel", null, b.adminSession().accessToken()),
                HttpStatus.FORBIDDEN,
                "owner_only");
        ResponseEntity<JsonNode> cancelled =
                b.api().post("/api/v1/closure/cancel", null, b.ownerSession().accessToken());
        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status").asString()).isEqualTo("none");
        assertThat(write(b).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jobsSee(b)).as("the jobs run for it again").isTrue();
        assertThat(b.api()
                        .get("/api/v1/plan/status", b.cashierSession().accessToken())
                        .getBody()
                        .get("closing_remove_after")
                        .isNull())
                .isTrue();
        assertThat(tenantAudits("core.business.closure_cancelled", b.t().tenantId()))
                .isEqualTo(1);
        assertThat(platformAudits("platform.tenant.closure_cancelled", b.t().tenantId()))
                .isEqualTo(1);
        assertRefused(
                b.api().post("/api/v1/closure/cancel", null, b.ownerSession().accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "no_closure_request");

        // A cancelled request is never removed.
        UUID closureId = UUID.fromString(lastClosure(b));
        coolingOffOver(closureId);
        Operator op = operator();
        assertRefused(remove(op, closureId, code(op)), HttpStatus.CONFLICT, "closure_not_open");
    }

    /**
     * Whether the scheduled jobs see the business: the lists they iterate (the lending arrears run and its penalties, interest,
     * the daily notices) and the check the outbox's SMS and push channels make. A closing business is in none of them.
     */
    static boolean jobsSee(Business b) {
        JdbcClient app = JdbcClient.create(TestDatabase.appDataSource());
        boolean all = app.sql("SELECT app_list_active_tenants()")
                .query(UUID.class)
                .list()
                .contains(b.t().tenantId());
        boolean lending = app.sql("SELECT app_list_active_tenants_with_module('lending')")
                .query(UUID.class)
                .list()
                .contains(b.t().tenantId());
        boolean outbox = Boolean.TRUE.equals(app.sql("SELECT app_tenant_is_active(?)")
                .param(b.t().tenantId())
                .query(Boolean.class)
                .single());
        assertThat(lending).isEqualTo(all);
        assertThat(outbox).isEqualTo(all);
        return all;
    }

    String lastClosure(Business b) {
        return TestDatabase.owner()
                .sql("SELECT id FROM tenant_closures WHERE tenant_id = ? ORDER BY requested_at DESC LIMIT 1")
                .param(b.t().tenantId())
                .query(UUID.class)
                .single()
                .toString();
    }

    /**
     * FR-TEN-09: the operator sees the request and cannot remove it during the cooling-off period or without their code; after
     * it, the removal deletes or anonymises the personal data, keeps the money records and closes the business, and another
     * business is untouched.
     */
    @Test
    void frTen09_theOperatorRemovesAfterTheCoolingOffAndPersonalDataGoes() {
        Business b = business();
        Business other = business();
        personalData(b);
        personalData(other);
        assertThat(write(b).getStatusCode()).isEqualTo(HttpStatus.OK);
        // D46: the clock is the business's own financial year; only the Owner sets where it ends.
        assertRefused(yearEnd(b, b.adminSession(), 12), HttpStatus.FORBIDDEN, "owner_only");
        ResponseEntity<JsonNode> yearEnd = yearEnd(b, b.ownerSession(), 12);
        assertThat(yearEnd.getStatusCode()).as("%s", yearEnd.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(yearEnd.getBody().get("financial_year_end_month").asInt()).isEqualTo(12);
        ResponseEntity<JsonNode> made = request(b);
        UUID closureId = UUID.fromString(made.getBody().get("id").asString());
        Operator op = operator();

        ResponseEntity<JsonNode> list =
                op.api().get("/api/v1/platform/closures", op.session().accessToken());
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode mine = null;
        for (JsonNode row : list.getBody()) {
            if (row.get("id").asString().equals(closureId.toString())) {
                mine = row;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("status").asString()).isEqualTo("requested");
        assertThat(mine.get("removable").asBoolean()).isFalse();
        assertThat(b.api()
                        .get("/api/v1/platform/closures", b.ownerSession().accessToken())
                        .getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED);

        assertRefused(remove(op, closureId, code(op)), HttpStatus.UNPROCESSABLE_CONTENT, "cooling_off");
        coolingOffOver(closureId);
        ResponseEntity<JsonNode> noCode = remove(op, closureId, "000000");
        assertThat(noCode.getStatusCode().is4xxClientError())
                .as("%s", noCode.getBody())
                .isTrue();
        assertThat(count("SELECT count(*) FROM tenant_closures WHERE id = ? AND status = 'requested'", closureId))
                .isEqualTo(1);

        long saleTotal = count(
                "SELECT total_minor FROM retail_sales WHERE tenant_id = ? AND sale_no = 'RSCLOSE001'",
                b.t().tenantId());
        ResponseEntity<JsonNode> removed = remove(op, closureId, code(op));
        assertThat(removed.getStatusCode()).as("%s", removed.getBody()).isEqualTo(HttpStatus.OK);
        LocalDate keepUntil =
                LocalDate.parse(removed.getBody().get("keep_until").asString());
        assertThat(keepUntil)
                .as("the end of the calendar financial year of the request, plus five years")
                .isEqualTo(ClosureService.keepUntil(today, 12, 5));

        UUID id = b.t().tenantId();
        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM tenants WHERE id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("closed");
        assertThat(count("SELECT count(*) FROM tenant_closures WHERE id = ? AND status = 'removed'", closureId))
                .isEqualTo(1);
        assertThat(platformAudits("platform.tenant.removed", id)).isEqualTo(1);
        assertThat(b.api()
                        .get("/api/v1/plan/status", b.ownerSession().accessToken())
                        .getStatusCode())
                .as("the host resolves nothing")
                .isIn(HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED);

        // Staff: names, emails and phones gone, no credential or session left, nobody the Owner.
        assertThat(count(
                        "SELECT count(*) FROM users WHERE tenant_id = ? AND (lower(email) IN (lower(?), lower(?)) OR is_owner"
                                + " OR status <> 'deactivated' OR full_name <> 'Removed staff' OR phone_e164 IS NOT NULL)",
                        id,
                        b.ownerEmail(),
                        b.adminEmail()))
                .isZero();
        assertThat(count("SELECT count(*) FROM user_credentials WHERE tenant_id = ?", id))
                .isZero();
        assertThat(count("SELECT count(*) FROM auth_sessions WHERE tenant_id = ?", id))
                .isZero();
        // Members, customers, buyers: anonymised; the money record keeps its amounts.
        assertThat(count(
                        "SELECT count(*) FROM lending_members WHERE tenant_id = ? AND (full_name <> 'Member M000001'"
                                + " OR national_id IS NOT NULL OR date_of_birth IS NOT NULL OR village IS NOT NULL"
                                + " OR phone_e164 = '+256700000001')",
                        id))
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM retail_customers WHERE tenant_id = ? AND (name = 'Test Customer 01'"
                                + " OR contact IS NOT NULL)",
                        id))
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM retail_sales WHERE tenant_id = ? AND sale_no = 'RSCLOSE001'"
                                + " AND buyer_name = 'Buyer' AND buyer_contact IS NULL AND total_minor = ?",
                        id,
                        saleTotal))
                .isEqualTo(1);
        // The audit trails keep what happened (amounts, dates, ids, codes) and lose who it was about.
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND (data::text ~ ? OR ip IS NOT NULL)",
                        id,
                        PERSONAL))
                .isZero();
        assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND data <> '{}'::jsonb", id))
                .as("the details that name nobody stay")
                .isPositive();
        assertThat(count(
                        "SELECT count(*) FROM platform_audit_log WHERE tenant_id = ? AND (data::text ~ ? OR lower(data::text) LIKE ?)",
                        id,
                        PERSONAL,
                        "%" + b.ownerEmail().toLowerCase() + "%"))
                .as("the platform's log of the business names nobody")
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM platform_audit_log WHERE tenant_id = ? AND action = 'platform.tenant.removed'"
                                + " AND data ->> 'reason' = 'Test: the owner asked and the cooling-off is over'",
                        id))
                .as("the operator's reason is kept in the platform's log")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification_outbox WHERE tenant_id = ? AND recipient <> 'removed'", id))
                .isZero();
        assertThat(count("SELECT count(*) FROM billing_contacts WHERE tenant_id = ?", id))
                .isZero();

        // Another business keeps everything.
        UUID kept = other.t().tenantId();
        assertThat(count(
                        "SELECT count(*) FROM lending_members WHERE tenant_id = ? AND full_name = 'Test Borrower 01'"
                                + " AND national_id = 'CMTEST0000001A'",
                        kept))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM retail_sales WHERE tenant_id = ? AND buyer_name = 'Test Buyer 01'", kept))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM users WHERE tenant_id = ? AND lower(email) = lower(?)",
                        kept,
                        other.ownerEmail()))
                .isEqualTo(1);
        assertThat(other.api()
                        .get("/api/v1/closure", other.ownerSession().accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertRefused(remove(op, closureId, code(op)), HttpStatus.CONFLICT, "closure_not_open");
    }

    /** The removal bypass is the operator's alone: the business itself can neither start a removal nor run an eraser. */
    @Test
    void frTen09_theBusinessCannotRemoveItself() throws SQLException {
        Business b = business();
        ResponseEntity<JsonNode> made = request(b);
        assertThat(made.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID closure = UUID.fromString(made.getBody().get("id").asString());
        coolingOffOver(closure);
        UUID operator = operator().id();
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + b.t().tenantId() + "', true)");
                s.execute("SELECT set_config('bms.erasing_tenant', '" + b.t().tenantId() + "', true)");
                assertThatThrownBy(() ->
                                s.execute("UPDATE tenant_closures SET status = 'removing' WHERE status = 'requested'"))
                        .hasMessageContaining("tenant_closures");
            }
            c.rollback();
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + b.t().tenantId() + "', true)");
                assertThatThrownBy(() -> s.execute("SELECT platform_begin_tenant_removal('" + closure + "', '"
                                + operator + "', '" + UUID.randomUUID() + "', 'Test: from the business')"))
                        .as("begun from a business's transaction")
                        .hasMessageContaining("never from a business");
            }
            c.rollback();
            try (Statement s = c.createStatement()) {
                assertThatThrownBy(() -> s.execute("SELECT platform_begin_tenant_removal('" + closure + "', '"
                                + operator + "', '" + UUID.randomUUID() + "', 'Test: no session, no code')"))
                        .as("an operator's id alone, with no session and no code just used")
                        .hasMessageContaining("operator_not_verified");
            }
            c.rollback();
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + b.t().tenantId() + "', true)");
                assertThatThrownBy(() -> s.execute("SELECT app_mark_tenant_removal('" + closure + "')"))
                        .as("a request the operator did not begin")
                        .hasMessageContaining("erased only during a removal");
            }
            c.rollback();
            for (String eraser : List.of(
                    "app_erase_core_personal_data()",
                    "lending_erase_personal_data()",
                    "retail_erase_personal_data()")) {
                try (Statement s = c.createStatement()) {
                    s.execute("SELECT set_config('app.tenant_id', '" + b.t().tenantId() + "', true)");
                    s.execute("SELECT set_config('bms.erasing_tenant', '" + b.t().tenantId() + "', true)");
                    assertThatThrownBy(() -> s.execute("SELECT " + eraser))
                            .as(eraser)
                            .hasMessageContaining("erased only during a removal");
                }
                c.rollback();
            }
        }
        assertThat(count(
                        "SELECT count(*) FROM users WHERE tenant_id = ? AND lower(email) = lower(?)",
                        b.t().tenantId(),
                        b.ownerEmail()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM tenant_closures WHERE id = ? AND status = 'requested'", closure))
                .isEqualTo(1);
    }

    /**
     * D46, ADR-090: money records are kept the set years (5) after the end of the financial year in which the business closed
     * (the request): June, the Uganda tax year, unless the business set its own year end.
     */
    @Test
    void frTen09_moneyRecordsAreKeptFromTheEndOfTheFinancialYear() {
        assertThat(ClosureService.keepUntil(LocalDate.of(2026, 10, 25), 6, 5)).isEqualTo(LocalDate.of(2032, 6, 30));
        assertThat(ClosureService.keepUntil(LocalDate.of(2026, 6, 30), 6, 5)).isEqualTo(LocalDate.of(2031, 6, 30));
        assertThat(ClosureService.keepUntil(LocalDate.of(2027, 1, 1), 6, 5)).isEqualTo(LocalDate.of(2032, 6, 30));
        assertThat(ClosureService.keepUntil(LocalDate.of(2026, 11, 15), 12, 5))
                .as("a calendar financial year")
                .isEqualTo(LocalDate.of(2031, 12, 31));
        assertThat(ClosureService.keepUntil(LocalDate.of(2026, 3, 15), 2, 5))
                .as("a year ending in February, a leap year's end")
                .isEqualTo(LocalDate.of(2032, 2, 29));
        assertThat(ClosureService.keepUntil(LocalDate.of(2026, 10, 25), 6, 7))
                .as("BMS_CLOSED_RETENTION_YEARS")
                .isEqualTo(LocalDate.of(2034, 6, 30));
    }

    /**
     * FR-TEN-09 on a business with a row in every tenant table (the isolation suite's factory): the removal passes every guard
     * and append-only trigger, and afterwards no table of the business holds a phone number, a national ID, an email other
     * than the placeholders, or one of the factory's names. Every table is read from the catalogue, so a table added later
     * with personal data fails here until its module's eraser covers it.
     */
    @Test
    void frTen09_theRemovalLeavesNoPersonalDataInAnyTable() {
        TestDatabase.Fixture t = TestDatabase.tenant("close-all", true, true);
        RlsIsolationIT.seedOneRowInEveryTable(t);
        // Free text people typed on money records (D46: erased too): a purchase note, a journal memo, a stock movement
        // note.
        TestDatabase.owner()
                .sql("DO $$ BEGIN PERFORM set_config('bms.allow_mutation', 'on', true);"
                        + " UPDATE retail_purchases SET note = 'Paid Test Borrower 01 on +256700000005' WHERE tenant_id = '"
                        + t.tenantId()
                        + "'; UPDATE journal_entries SET memo = 'For Test Customer 01' WHERE tenant_id = '"
                        + t.tenantId()
                        + "'; UPDATE retail_stock_movements SET note = 'Given to Test Kin 01' WHERE tenant_id = '"
                        + t.tenantId() + "'; END $$")
                .update();
        TestDatabase.owner()
                .sql("INSERT INTO retail_customers (id, tenant_id, name, contact) VALUES (?, ?, 'Test Customer 01', ?)")
                .params(UUID.randomUUID(), t.tenantId(), "+256700000002")
                .update();
        Api api = Api.tenant(http, t.slug());
        String ownerEmail = Api.email("close-all-owner");
        UUID owner = Api.staff(t, ownerEmail, new Role("tenant_admin", null));
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = true WHERE id = ?")
                .param(owner)
                .update();
        Session session = api.signIn(owner, ownerEmail, Api.PASSWORD, null);
        ResponseEntity<JsonNode> made = api.post("/api/v1/closure", password(), session.accessToken());
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID closureId = UUID.fromString(made.getBody().get("id").asString());
        coolingOffOver(closureId);
        Operator op = operator();

        assertThat(withPersonalData(t.tenantId()))
                .as("the scan finds the factory's personal data before the removal")
                .contains(
                        "users",
                        "lending_members",
                        "retail_customers",
                        "retail_quotations",
                        "retail_documents",
                        "payment_intents",
                        "billing_contacts");

        ResponseEntity<JsonNode> removed = remove(op, closureId, code(op));
        assertThat(removed.getStatusCode()).as("%s", removed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(removed.getBody().get("erased").get("core").asInt()).isPositive();
        assertThat(withPersonalData(t.tenantId()))
                .as("tables still holding personal data")
                .isEmpty();
        assertThat(count(
                        "SELECT count(*) FROM retail_documents WHERE tenant_id = ? AND snapshot -> 'buyer' ->> 'tin' = '1000000001'"
                                + " AND snapshot -> 'buyer' ->> 'name' = 'Buyer' AND snapshot -> 'buyer' -> 'contact' = 'null'::jsonb",
                        t.tenantId()))
                .as("D46: the buyer's TIN stays on the kept tax invoice")
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM retail_purchases WHERE tenant_id = ? AND note IS NOT NULL AND note <> 'Removed'",
                        t.tenantId()))
                .isZero();
        assertThat(count("SELECT count(*) FROM retail_sales WHERE tenant_id = ?", t.tenantId()))
                .as("the money records stay until keep_until")
                .isPositive();

        // Owner decision D46: the day after keep_until the daily job deletes everything the business still holds,
        // except its
        // tenants row, its anonymised users and its closure rows, which the platform's history points at.
        assertThat(closures.purgeDue()).as("nothing is due before keep_until").isZero();
        assertThat(count("SELECT count(*) FROM retail_sales WHERE tenant_id = ?", t.tenantId()))
                .isPositive();
        TestDatabase.owner()
                .sql("UPDATE tenant_closures SET keep_until = ? WHERE id = ?")
                .params(today.minusDays(1), closureId)
                .update();
        assertThat(closures.purgeDue()).isGreaterThanOrEqualTo(1);
        List<String> kept = new ArrayList<>();
        for (String table : RlsIsolationIT.tenantOwnedTables()) {
            if (List.of("tenants", "users", "tenant_closures").contains(table)) {
                continue;
            }
            if (count("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", t.tenantId()) > 0) {
                kept.add(table);
            }
        }
        assertThat(kept).as("tables still holding rows after the purge").isEmpty();
        assertThat(count(
                        "SELECT count(*) FROM tenant_closures WHERE id = ? AND status = 'purged' AND purged_at IS NOT NULL",
                        closureId))
                .isEqualTo(1);
        assertThat(platformAudits("platform.tenant.purged", t.tenantId())).isEqualTo(1);
        assertThat(withPersonalData(t.tenantId())).isEmpty();
        assertThat(closures.purgeDue())
                .as("a purged request is never purged again")
                .isZero();
    }

    static List<String> withPersonalData(UUID tenantId) {
        List<String> found = new ArrayList<>();
        for (String table : RlsIsolationIT.tenantOwnedTables()) {
            String key = table.equals("tenants") ? "id" : "tenant_id";
            if (count("SELECT count(*) FROM " + table + " x WHERE " + key + " = ? AND x::text ~ ?", tenantId, PERSONAL)
                    > 0) {
                found.add(table);
            }
        }
        return found;
    }

    /** A Ugandan phone number, a national ID, an email that is not a placeholder, or a name the factories write. */
    static final String PERSONAL =
            "\\+2567[0-9]{8}|C[MF][A-Z0-9]{12}|[A-Za-z0-9._%+-]+@(?!removed\\.invalid)[A-Za-z0-9.-]+"
                    + "|Test (Borrower|Staff|Kin|Customer|Buyer|Owner|Accountant)";
}
