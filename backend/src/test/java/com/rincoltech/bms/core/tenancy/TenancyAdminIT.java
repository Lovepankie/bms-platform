package com.rincoltech.bms.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Branch administration, tenant settings, plan limits and the suspended tenant (FR-BR-01,
 * FR-BR-04, FR-TEN-04, FR-TEN-06, FR-TEN-08, NFR-ISO-04). Fabricated tenants and people.
 */
class TenancyAdminIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    Session admin;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("tenancy", true);
        api = Api.tenant(http, t.slug());
        String email = Api.email("admin");
        admin = api.signIn(Api.staff(t, email, new Role("tenant_admin", null)), email, Api.PASSWORD, null);
    }

    Session staff(String prefix, Role... roles) {
        String email = Api.email(prefix);
        return api.signIn(Api.staff(t, email, roles), email, Api.PASSWORD, null);
    }

    List<String> codes(JsonNode list) {
        return StreamSupport.stream(list.get("items").spliterator(), false)
                .map(b -> b.get("code").asString())
                .toList();
    }

    long audits(String action, UUID entity) {
        return TestDatabase.owner()
                .sql(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND entity_id IS NOT DISTINCT FROM ?")
                .params(t.tenantId(), action, entity)
                .query(Long.class)
                .single();
    }

    /** FR-BR-01: create, rename with If-Match, deactivate; the head office stays. */
    @Test
    void branchesAreCreatedRenamedAndDeactivated() {
        ResponseEntity<JsonNode> created = api.post(
                "/api/v1/branches",
                Map.of("code", "EAST", "name", "Test Branch East", "location", "Test Town"),
                admin.accessToken());
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID east = UUID.fromString(created.getBody().get("id").asString());
        assertThat(created.getBody().get("status").asString()).isEqualTo("active");
        assertThat(audits("core.branch.created", east)).isEqualTo(1);

        ResponseEntity<JsonNode> duplicate =
                api.post("/api/v1/branches", Map.of("code", "EAST", "name", "Again"), admin.accessToken());
        assertThat(duplicate.getBody().get("code").asString()).isEqualTo("duplicate_branch_code");
        ResponseEntity<JsonNode> badCode =
                api.post("/api/v1/branches", Map.of("code", "e", "name", "Lower"), admin.accessToken());
        assertThat(badCode.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> noIfMatch =
                api.call(HttpMethod.PATCH, "/api/v1/branches/" + east, Map.of("name", "Renamed"), admin.accessToken());
        assertThat(noIfMatch.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        ResponseEntity<JsonNode> stale = api.call(
                HttpMethod.PATCH,
                "/api/v1/branches/" + east,
                Map.of("name", "Renamed"),
                admin.accessToken(),
                Map.of("If-Match", "\"7\""));
        assertThat(stale.getBody().get("code").asString()).isEqualTo("version_conflict");
        ResponseEntity<JsonNode> renamed = api.call(
                HttpMethod.PATCH,
                "/api/v1/branches/" + east,
                Map.of("name", "Test Branch East Renamed"),
                admin.accessToken(),
                Map.of("If-Match", "\"1\""));
        assertThat(renamed.getBody().get("name").asString()).isEqualTo("Test Branch East Renamed");
        assertThat(renamed.getHeaders().getETag()).isEqualTo("\"2\"");

        assertThat(api.post("/api/v1/branches/" + t.headOffice() + "/deactivate", null, admin.accessToken())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("head_office_required");
        ResponseEntity<JsonNode> deactivated =
                api.post("/api/v1/branches/" + east + "/deactivate", null, admin.accessToken());
        assertThat(deactivated.getBody().get("status").asString()).isEqualTo("inactive");
        assertThat(audits("core.branch.deactivated", east)).isEqualTo(1);
    }

    /** FR-BR-04, NFR-ISO-04: a scoped user lists only the branches of their scope. */
    @Test
    void aScopedUserSeesOnlyTheirBranches() {
        Session officer = staff("officer", new Role("loan_officer", t.secondBranch()));
        assertThat(codes(api.get("/api/v1/branches", officer.accessToken()).getBody()))
                .containsExactly("BR2");
        assertThat(api.get("/api/v1/branches/" + t.headOffice(), officer.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(codes(api.get("/api/v1/branches", admin.accessToken()).getBody()))
                .containsExactly("HQ", "BR2");

        // FR-BR-03: /me offers the branches to switch between, and whether "All branches" applies.
        JsonNode me = api.get("/api/v1/me", officer.accessToken()).getBody();
        assertThat(me.get("branches")).hasSize(1);
        assertThat(me.get("default_branch_id").asString())
                .isEqualTo(t.secondBranch().toString());
        assertThat(me.get("all_branches").asBoolean()).isFalse();
    }

    /** FR-TEN-04: creating past a plan limit fails with plan_limit_reached naming the limit. */
    @Test
    void planLimitsStopBranchesStaffAndMembers() {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO plans (id, code, name, max_branches, max_staff_users, max_active_members, allowed_modules)
                        VALUES (?, ?, 'Test Tight Plan', 2, 1, 0, '{lending}')
                        """)
                .params(UUID.randomUUID(), "tight-" + t.slug())
                .update();
        TestDatabase.owner()
                .sql("UPDATE tenants SET plan_id = (SELECT id FROM plans WHERE code = ?) WHERE id = ?")
                .params("tight-" + t.slug(), t.tenantId())
                .update();

        ResponseEntity<JsonNode> branch =
                api.post("/api/v1/branches", Map.of("code", "WEST", "name", "Test Branch West"), admin.accessToken());
        assertThat(branch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(branch.getBody().get("code").asString()).isEqualTo("plan_limit_reached");
        assertThat(branch.getBody().get("detail").asString()).contains("max_branches");

        ResponseEntity<JsonNode> user = api.post(
                "/api/v1/users",
                Map.of(
                        "full_name", "Test Staff Extra",
                        "email", Api.email("extra"),
                        "roles", List.of(Map.of("role_key", "auditor"))),
                admin.accessToken());
        assertThat(user.getBody().get("code").asString()).isEqualTo("plan_limit_reached");
        assertThat(user.getBody().get("detail").asString()).contains("max_staff_users");

        ResponseEntity<JsonNode> member = api.post(
                "/api/v1/lending/members",
                Map.of(
                        "branch_id", t.headOffice(),
                        "full_name", "Test Borrower 90",
                        "phone", "0700000090",
                        "id_type", "none"),
                admin.accessToken());
        assertThat(member.getBody().get("code").asString()).isEqualTo("plan_limit_reached");
        assertThat(member.getBody().get("detail").asString()).contains("max_active_members");
    }

    /** FR-TEN-08: defaults, validation, If-Match and an audit row with before and after. */
    @Test
    void settingsAreValidatedAndAuditedWithBeforeAndAfter() {
        ResponseEntity<JsonNode> current = api.get("/api/v1/settings", admin.accessToken());
        assertThat(current.getBody().get("sms_window_start").asString()).isEqualTo("08:00");
        assertThat(current.getBody().get("approval_validity_days").asInt()).isEqualTo(14);
        assertThat(current.getBody()
                        .get("appraisal_weights")
                        .get("repayment_history")
                        .asInt())
                .isEqualTo(40);
        String etag = current.getHeaders().getETag();

        ResponseEntity<JsonNode> badWeights = api.call(
                HttpMethod.PATCH,
                "/api/v1/settings",
                Map.of(
                        "appraisal_weights",
                        Map.of("repayment_history", 50, "affordability", 30, "collateral_cover", 20, "exposure", 10)),
                admin.accessToken(),
                Map.of("If-Match", etag));
        assertThat(badWeights.getBody().get("code").asString()).isEqualTo("validation_failed");
        ResponseEntity<JsonNode> badWindow = api.call(
                HttpMethod.PATCH,
                "/api/v1/settings",
                Map.of("sms_window_start", "21:00"),
                admin.accessToken(),
                Map.of("If-Match", etag));
        assertThat(badWindow.getBody().get("errors").get(0).get("field").asString())
                .isEqualTo("sms_window_end");
        ResponseEntity<JsonNode> badSender = api.call(
                HttpMethod.PATCH,
                "/api/v1/settings",
                Map.of("sms_sender_name", "Far Too Long Name"),
                admin.accessToken(),
                Map.of("If-Match", etag));
        assertThat(badSender.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> changed = api.call(
                HttpMethod.PATCH,
                "/api/v1/settings",
                Map.of("display_name", "Test Lender Display", "require_mfa_all_staff", true),
                admin.accessToken(),
                Map.of("If-Match", etag));
        assertThat(changed.getStatusCode()).as("%s", changed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().get("display_name").asString()).isEqualTo("Test Lender Display");
        assertThat(changed.getBody().get("require_mfa_all_staff").asBoolean()).isTrue();

        ResponseEntity<JsonNode> conflict = api.call(
                HttpMethod.PATCH,
                "/api/v1/settings",
                Map.of("receipt_footer", "Thank you"),
                admin.accessToken(),
                Map.of("If-Match", etag));
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        String data = TestDatabase.owner()
                .sql("SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'core.settings.updated'")
                .param(t.tenantId())
                .query(String.class)
                .single();
        assertThat(data).contains("\"before\"").contains("Test Lender Display").contains("require_mfa_all_staff");

        // FR-IAM-06: with the setting on, every staff user must enrol a second factor.
        String email = Api.email("cashier");
        Api.staff(t, email, new Role("cashier", t.headOffice()));
        assertThat(api.login(email, Api.PASSWORD).getBody().get("status").asString())
                .isEqualTo("mfa_enrolment_required");

        Session auditor = staff("auditor", new Role("auditor", null));
        assertThat(api.call(
                                HttpMethod.PATCH,
                                "/api/v1/settings",
                                Map.of("receipt_footer", "x"),
                                auditor.accessToken(),
                                Map.of("If-Match", "\"2\""))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * Issue #64: the retired key {@code retail_allow_negative_stock} may still be stored for an
     * existing tenant; reads and writes ignore it and it is no longer part of the contract.
     * JUSTIFICATION-A3: a new test case needs its own method; no existing test covers stored keys.
     */
    @Test
    void aRetiredStoredSettingsKeyIsIgnored() {
        TestDatabase.owner().sql("""
                        INSERT INTO tenant_settings (id, tenant_id, settings)
                        VALUES (?, ?, CAST('{"retail_allow_negative_stock": true}' AS jsonb))
                        ON CONFLICT (tenant_id) DO UPDATE SET settings = EXCLUDED.settings
                        """).params(UUID.randomUUID(), t.tenantId()).update();

        ResponseEntity<JsonNode> current = api.get("/api/v1/settings", admin.accessToken());
        assertThat(current.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(current.getBody().has("retail_allow_negative_stock")).isFalse();

        ResponseEntity<JsonNode> changed = api.call(
                HttpMethod.PATCH,
                "/api/v1/settings",
                Map.of("receipt_footer", "Thank you"),
                admin.accessToken(),
                Map.of("If-Match", current.getHeaders().getETag()));
        assertThat(changed.getStatusCode()).as("%s", changed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().has("retail_allow_negative_stock")).isFalse();
    }

    /** FR-TEN-06: a suspended tenant reads and signs in, but every write returns 423. */
    @Test
    void aSuspendedTenantIsReadOnly() {
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .param(t.tenantId())
                .update();
        assertThat(api.get("/api/v1/branches", admin.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> write =
                api.post("/api/v1/branches", Map.of("code", "NORTH", "name", "Test Branch North"), admin.accessToken());
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(write.getBody().get("code").asString()).isEqualTo("tenant_suspended");

        // Staff can still sign in to read and export.
        Session reader = staff("reader", new Role("auditor", null));
        assertThat(api.get("/api/v1/me", reader.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
        // Member portal sign-in is refused like any write (the portal arrives in phase 2).
        assertThat(api.post("/api/v1/auth/member/login", Map.of("phone", "0700000001", "pin", "12345"), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.LOCKED);

        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'active' WHERE id = ?")
                .param(t.tenantId())
                .update();
    }
}
