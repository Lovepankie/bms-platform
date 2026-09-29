package com.rincoltech.bms.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
import tools.jackson.databind.JsonNode;

/**
 * The platform console API (FR-TEN-01, FR-TEN-03, FR-TEN-05, FR-TEN-06, FR-IAM-12, chapter 7
 * section 7.11.3): operator setup and sign-in with mandatory TOTP, tenant creation, module
 * switching, subscription moves and the host rules. Fabricated operators and tenants.
 */
class PlatformIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    Api platform;
    Session operator;
    UUID operatorId;

    @BeforeEach
    void setUp() {
        platform = Api.platform(http);
        String email = Api.email("operator");
        operatorId = Api.platformUser(email);
        operator = platform.signIn(operatorId, email, Api.PASSWORD, null);
    }

    static String sha256(String s) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    ResponseEntity<JsonNode> create(String slug, List<String> modules) {
        return platform.post(
                "/api/v1/platform/tenants",
                Map.of(
                        "name",
                        "Test Lender " + slug,
                        "slug",
                        slug,
                        "plan_code",
                        "growth",
                        "modules",
                        modules,
                        "head_office",
                        Map.of("code", "HQ", "name", "Head Office"),
                        "admin",
                        Map.of("full_name", "Test Owner 02", "email", Api.email("owner"))),
                operator.accessToken());
    }

    String slug() {
        return "plat-" + UUID.randomUUID().toString().substring(0, 8);
    }

    long platformAudits(String action, UUID tenantId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM platform_audit_log WHERE action = ? AND tenant_id IS NOT DISTINCT FROM ?")
                .params(action, tenantId)
                .query(Long.class)
                .single();
    }

    /** An operator sets the first password with the setup token, then must enrol TOTP. */
    @Test
    void anOperatorSetsAPasswordAndEnrolsTotpAtFirstSignIn() throws Exception {
        String email = Api.email("newop");
        UUID id = UUID.randomUUID();
        String token = "fabricated-setup-token-" + id;
        TestDatabase.owner()
                .sql("INSERT INTO platform_users (id, email, full_name, setup_token_hash, setup_token_expires_at)"
                        + " VALUES (?, ?, 'Test Operator 02', ?, now() + interval '1 day')")
                .params(id, email, sha256(token))
                .update();
        assertThat(platform.login(email, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(platform.post("/api/v1/platform/auth/setup", Map.of("token", token, "password", "qwertyuiop"), null)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("weak_password");
        assertThat(platform.post("/api/v1/platform/auth/setup", Map.of("token", token, "password", Api.PASSWORD), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(platform.post("/api/v1/platform/auth/setup", Map.of("token", token, "password", Api.PASSWORD), null)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("invitation_invalid");

        assertThat(platform.login(email, Api.PASSWORD).getBody().get("status").asString())
                .isEqualTo("mfa_enrolment_required");
        Session session = platform.signIn(id, email, Api.PASSWORD, null);
        JsonNode me = platform.get("/api/v1/platform/me", session.accessToken()).getBody();
        assertThat(me.get("kind").asString()).isEqualTo("platform");
        assertThat(me.get("permissions").toString()).contains("platform.tenants.manage");
        assertThat(platformAudits("core.auth.signed_in", null)).isPositive();
    }

    /** FR-TEN-01: one call creates the tenant, its head office, modules, chart and first admin. */
    @Test
    void aTenantIsCreatedWithEverythingItNeeds() {
        String slug = slug();
        ResponseEntity<JsonNode> created = create(slug, List.of("lending"));
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode tenant = created.getBody().get("tenant");
        UUID tenantId = UUID.fromString(tenant.get("id").asString());
        assertThat(tenant.get("slug").asString()).isEqualTo(slug);
        assertThat(tenant.get("currency").asString()).isEqualTo("UGX");
        assertThat(tenant.get("timezone").asString()).isEqualTo("Africa/Kampala");
        assertThat(tenant.get("subscription_status").asString()).isEqualTo("trial");
        assertThat(tenant.get("modules").toString()).contains("lending");
        assertThat(created.getBody().get("admin_invitation").get("url").asString())
                .startsWith("https://" + slug + "-bms-staging.rincoltech.test/accept-invitation#token=");

        var owner = TestDatabase.owner();
        assertThat(owner.sql("SELECT count(*) FROM gl_accounts WHERE tenant_id = ?")
                        .param(tenantId)
                        .query(Long.class)
                        .single())
                .isEqualTo(30);
        assertThat(owner.sql("SELECT count(*) FROM branches WHERE tenant_id = ? AND is_head_office")
                        .param(tenantId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(owner.sql("SELECT count(*) FROM tenant_settings WHERE tenant_id = ?")
                        .param(tenantId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(owner.sql("""
                                SELECT count(*) FROM users u JOIN user_role_assignments a ON a.user_id = u.id
                                 WHERE u.tenant_id = ? AND u.status = 'invited' AND a.role_key = 'tenant_admin' AND a.branch_id IS NULL
                                """).param(tenantId).query(Long.class).single()).isEqualTo(1);
        assertThat(owner.sql("SELECT actor_kind FROM audit_log WHERE tenant_id = ? AND action = 'core.tenant.created'")
                        .param(tenantId)
                        .query(String.class)
                        .single())
                .isEqualTo("platform");
        assertThat(platformAudits("platform.tenant.created", tenantId)).isEqualTo(1);

        // The tenant is reachable at its host under the tenant host pattern.
        ResponseEntity<JsonNode> onHost =
                Api.platform(http).onHost(slug + "-bms-staging.rincoltech.test").get("/api/v1/me", null);
        assertThat(onHost.getBody().get("code").asString()).isEqualTo("unauthenticated");

        assertThat(platform.get("/api/v1/platform/tenants", operator.accessToken())
                        .getBody()
                        .get("items")
                        .toString())
                .contains(slug);
    }

    /** FR-TEN-02 and input rules: bad or reserved slugs, duplicates, unknown modules. */
    @Test
    void invalidTenantsAreRefused() {
        assertThat(create("admin", List.of()).getBody().get("code").asString()).isEqualTo("validation_failed");
        assertThat(create("-bad-", List.of()).getBody().get("code").asString()).isEqualTo("validation_failed");
        assertThat(create(slug(), List.of("retail")).getBody().get("code").asString())
                .isEqualTo("validation_failed");
        String slug = slug();
        assertThat(create(slug, List.of()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> again = create(slug, List.of());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("duplicate_slug");
    }

    /** FR-TEN-03: disabling lending answers 404 module_not_enabled; data is kept. */
    @Test
    void modulesAreSwitchedOffAndOnAgain() {
        ResponseEntity<JsonNode> created = create(slug(), List.of("lending"));
        String tenantId = created.getBody().get("tenant").get("id").asString();
        String slug = created.getBody().get("tenant").get("slug").asString();
        Api devTenant = Api.tenant(http, slug);
        Map<String, String> dev = Map.of(
                "X-Dev-User-Id", UUID.randomUUID().toString(),
                "X-Dev-Permissions", "lending.members.read",
                "X-Dev-Branch-Ids", "*");
        assertThat(devTenant
                        .call(HttpMethod.GET, "/api/v1/lending/members", null, null, dev)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> off = platform.call(
                HttpMethod.PUT,
                "/api/v1/platform/tenants/" + tenantId + "/modules",
                Map.of("modules", List.of()),
                operator.accessToken());
        assertThat(off.getBody().get("modules")).isEmpty();
        ResponseEntity<JsonNode> refused = devTenant.call(HttpMethod.GET, "/api/v1/lending/members", null, null, dev);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("module_not_enabled");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM gl_accounts WHERE tenant_id = ?::uuid")
                        .param(tenantId)
                        .query(Long.class)
                        .single())
                .isEqualTo(30);
        assertThat(JdbcCheck.moduleTenants("lending")).doesNotContain(UUID.fromString(tenantId));

        platform.call(
                HttpMethod.PUT,
                "/api/v1/platform/tenants/" + tenantId + "/modules",
                Map.of("modules", List.of("lending")),
                operator.accessToken());
        assertThat(devTenant
                        .call(HttpMethod.GET, "/api/v1/lending/members", null, null, dev)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(JdbcCheck.moduleTenants("lending")).contains(UUID.fromString(tenantId));
        assertThat(platformAudits("platform.tenant.modules_changed", UUID.fromString(tenantId)))
                .isEqualTo(2);
    }

    /** FR-TEN-05, FR-TEN-06: subscription moves are audited; suspension makes the tenant read only. */
    @Test
    void subscriptionMovesAndSuspension() {
        ResponseEntity<JsonNode> created = create(slug(), List.of("lending"));
        String tenantId = created.getBody().get("tenant").get("id").asString();
        String slug = created.getBody().get("tenant").get("slug").asString();

        ResponseEntity<JsonNode> pastDue = platform.post(
                "/api/v1/platform/tenants/" + tenantId + "/subscription",
                Map.of("status", "past_due", "next_status_change_on", "2026-11-01"),
                operator.accessToken());
        assertThat(pastDue.getBody().get("subscription_status").asString()).isEqualTo("past_due");
        assertThat(pastDue.getBody().get("status").asString()).isEqualTo("active");
        assertThat(pastDue.getBody().get("next_status_change_on").asString()).isEqualTo("2026-11-01");

        ResponseEntity<JsonNode> suspended =
                platform.post("/api/v1/platform/tenants/" + tenantId + "/suspend", null, operator.accessToken());
        assertThat(suspended.getBody().get("status").asString()).isEqualTo("suspended");
        Map<String, String> dev = Map.of(
                "X-Dev-User-Id", UUID.randomUUID().toString(),
                "X-Dev-Permissions", "core.branches.read,core.branches.manage",
                "X-Dev-Branch-Ids", "*");
        Api tenant = Api.tenant(http, slug);
        assertThat(tenant.call(HttpMethod.GET, "/api/v1/branches", null, null, dev)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(tenant.call(HttpMethod.POST, "/api/v1/branches", Map.of("code", "S1", "name", "Test S"), null, dev)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("tenant_suspended");

        platform.post("/api/v1/platform/tenants/" + tenantId + "/resume", null, operator.accessToken());
        assertThat(tenant.call(HttpMethod.POST, "/api/v1/branches", Map.of("code", "S1", "name", "Test S"), null, dev)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(platformAudits("platform.tenant.subscription_changed", UUID.fromString(tenantId)))
                .isEqualTo(3);
    }

    /** FR-IAM-12, platform path: the operator resets the only tenant admin's second factor. */
    @Test
    void theOperatorResetsATenantAdminsSecondFactor() {
        ResponseEntity<JsonNode> created = create(slug(), List.of("lending"));
        String tenantId = created.getBody().get("tenant").get("id").asString();
        String slug = created.getBody().get("tenant").get("slug").asString();
        JsonNode invitation = created.getBody().get("admin_invitation");
        UUID adminId = UUID.fromString(invitation.get("user_id").asString());
        String url = invitation.get("url").asString();
        Api tenant = Api.tenant(http, slug);
        tenant.post(
                "/api/v1/auth/staff/invitations/accept",
                Map.of("token", url.substring(url.indexOf("#token=") + 7), "password", Api.PASSWORD),
                null);
        String email = TestDatabase.owner()
                .sql("SELECT email FROM users WHERE id = ?")
                .param(adminId)
                .query(String.class)
                .single();
        Session admin = tenant.signIn(adminId, email, Api.PASSWORD, null);
        assertThat(tenant.login(email, Api.PASSWORD).getBody().get("status").asString())
                .isEqualTo("mfa_required");

        ResponseEntity<JsonNode> reset = platform.post(
                "/api/v1/platform/tenants/" + tenantId + "/users/" + adminId + "/mfa/reset",
                null,
                operator.accessToken());
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(tenant.login(email, Api.PASSWORD).getBody().get("status").asString())
                .isEqualTo("mfa_enrolment_required");
        assertThat(tenant.get("/api/v1/me", admin.accessToken())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("session_revoked");
        assertThat(platformAudits("platform.tenant_admin.mfa_reset", UUID.fromString(tenantId)))
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT actor_kind FROM audit_log WHERE entity_id = ? AND action = 'core.user.mfa_reset'")
                        .param(adminId)
                        .query(String.class)
                        .single())
                .isEqualTo("platform");
    }

    /** Chapter 7 section 7.2: the platform API is not served on a tenant host; tokens do not cross. */
    @Test
    void platformAndTenantTokensAndHostsDoNotMix() {
        String slug =
                create(slug(), List.of()).getBody().get("tenant").get("slug").asString();
        ResponseEntity<JsonNode> onTenantHost = Api.platform(http)
                .onHost(slug + "-bms-staging.rincoltech.test")
                .get("/api/v1/platform/tenants", operator.accessToken());
        assertThat(onTenantHost.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        for (String lookAlike : List.of("localhost.attacker.test", "bms-staging.rincoltech.test", "evil-localhost")) {
            assertThat(Api.platform(http)
                            .onHost(lookAlike)
                            .get("/api/v1/platform/tenants", operator.accessToken())
                            .getStatusCode())
                    .as(lookAlike)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(platform.get("/api/v1/platform/tenants", null)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("unauthenticated");

        ResponseEntity<JsonNode> platformTokenOnTenant =
                Api.tenant(http, slug).get("/api/v1/branches", operator.accessToken());
        assertThat(platformTokenOnTenant.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        TestDatabase.Fixture t = TestDatabase.tenant("plat-staff", true);
        String email = Api.email("staff");
        UUID staff = Api.staff(t, email, new Api.Role("auditor", null));
        Session session = Api.tenant(http, t.slug()).signIn(staff, email, Api.PASSWORD, null);
        assertThat(platform.get("/api/v1/platform/tenants", session.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** The job tenant list of FR-TEN-03, read the way a vertical's job reads it. */
    static final class JdbcCheck {
        static List<UUID> moduleTenants(String module) {
            return org.springframework.jdbc.core.simple.JdbcClient.create(TestDatabase.appDataSource())
                    .sql("SELECT app_list_active_tenants_with_module(?)")
                    .param(module)
                    .query(UUID.class)
                    .list();
        }
    }
}
