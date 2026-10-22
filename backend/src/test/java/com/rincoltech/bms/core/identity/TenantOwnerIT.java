package com.rincoltech.bms.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.time.Instant;
import java.util.HashMap;
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
 * The Owner of a business (FR-IAM-18, ADR-062) through HTTP: one Owner per business, who alone adds, removes, deactivates,
 * demotes or promotes an administrator; nobody inside the business removes, deactivates or demotes the Owner; the Owner hands
 * ownership to another active administrator with a password or authenticator step-up; a platform operator makes an active
 * administrator the Owner with a reason. Everyday powers are unchanged: an administrator still manages every staff member who
 * is not an administrator. All users, addresses and passwords are fabricated.
 */
class TenantOwnerIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    UUID owner;
    UUID adminA;
    UUID adminB;
    UUID cashier;
    Session ownerSession;
    Session adminSession;

    @BeforeEach
    void setUp() {
        http = Api.withoutRetries(http);
        t = TestDatabase.tenant("owner", true);
        api = Api.tenant(http, t.slug());
        String ownerEmail = Api.email("owner");
        String adminEmail = Api.email("admin-a");
        owner = Api.staff(t, ownerEmail, new Role("tenant_admin", null));
        adminA = Api.staff(t, adminEmail, new Role("tenant_admin", null));
        adminB = Api.staff(t, Api.email("admin-b"), new Role("tenant_admin", null));
        cashier = Api.staff(t, Api.email("cashier"), new Role("cashier", t.headOffice()));
        makeOwner(t.tenantId(), owner);
        ownerSession = api.signIn(owner, ownerEmail, Api.PASSWORD, null);
        adminSession = api.signIn(adminA, adminEmail, Api.PASSWORD, null);
    }

    static void makeOwner(UUID tenantId, UUID userId) {
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = true WHERE tenant_id = ? AND id = ?")
                .params(tenantId, userId)
                .update();
    }

    static List<UUID> owners(UUID tenantId) {
        return TestDatabase.owner()
                .sql("SELECT id FROM users WHERE tenant_id = ? AND is_owner")
                .param(tenantId)
                .query(UUID.class)
                .list();
    }

    long audits(String action, UUID entityId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND entity_id = ?")
                .params(t.tenantId(), action, entityId)
                .query(Long.class)
                .single();
    }

    static Map<String, Object> role(String key, UUID branch) {
        Map<String, Object> r = new HashMap<>();
        r.put("role_key", key);
        r.put("branch_id", branch);
        return r;
    }

    ResponseEntity<JsonNode> invite(String roleKey, UUID branch, Session by) {
        return api.post(
                "/api/v1/users",
                Map.of(
                        "full_name",
                        "Test Invitee",
                        "email",
                        Api.email("invitee"),
                        "roles",
                        List.of(role(roleKey, branch))),
                by.accessToken());
    }

    ResponseEntity<JsonNode> setRoles(UUID userId, List<Map<String, Object>> roles, Session by) {
        return api.call(HttpMethod.PUT, "/api/v1/users/" + userId + "/roles", roles, by.accessToken());
    }

    ResponseEntity<JsonNode> patchEmail(UUID userId, Session by) {
        return patchEmail(userId, by, Map.of());
    }

    ResponseEntity<JsonNode> patchEmail(UUID userId, Session by, Map<String, String> proof) {
        String etag = api.get("/api/v1/users/" + userId, by.accessToken())
                .getHeaders()
                .getETag();
        Map<String, String> body = new HashMap<>(proof);
        body.put("email", Api.email("changed"));
        return api.call(HttpMethod.PATCH, "/api/v1/users/" + userId, body, by.accessToken(), Map.of("If-Match", etag));
    }

    ResponseEntity<JsonNode> transfer(UUID to, Session by, String password) {
        Map<String, Object> body = new HashMap<>();
        if (password != null) {
            body.put("password", password);
        }
        return api.post("/api/v1/users/" + to + "/transfer-ownership", body, by.accessToken());
    }

    static void assertRefused(ResponseEntity<JsonNode> r, HttpStatus status, String code) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(status);
        assertThat(r.getBody().get("code").asString()).isEqualTo(code);
    }

    /** FR-IAM-18: an administrator cannot touch the Owner, nor add, remove, promote or demote another administrator. */
    @Test
    void anAdministratorCannotChangeTheOwnerOrAnotherAdministrator() {
        String ownerBase = "/api/v1/users/" + owner;
        assertRefused(
                api.post(ownerBase + "/deactivate", null, adminSession.accessToken()),
                HttpStatus.FORBIDDEN,
                "owner_protected");
        assertRefused(
                setRoles(owner, List.of(role("cashier", t.headOffice())), adminSession),
                HttpStatus.FORBIDDEN,
                "owner_protected");
        assertRefused(
                api.post(ownerBase + "/mfa/reset", null, adminSession.accessToken()),
                HttpStatus.FORBIDDEN,
                "owner_protected");
        assertRefused(patchEmail(owner, adminSession), HttpStatus.FORBIDDEN, "owner_protected");

        assertRefused(invite("tenant_admin", null, adminSession), HttpStatus.FORBIDDEN, "owner_only");
        assertRefused(
                api.post("/api/v1/users/" + adminB + "/deactivate", null, adminSession.accessToken()),
                HttpStatus.FORBIDDEN,
                "owner_only");
        assertRefused(
                setRoles(adminB, List.of(role("cashier", t.headOffice())), adminSession),
                HttpStatus.FORBIDDEN,
                "owner_only");
        assertRefused(
                setRoles(cashier, List.of(role("tenant_admin", null)), adminSession),
                HttpStatus.FORBIDDEN,
                "owner_only");
        assertRefused(patchEmail(adminB, adminSession), HttpStatus.FORBIDDEN, "owner_only");

        // Nothing changed: the Owner is still the one Owner and an active administrator, B is still an administrator.
        assertThat(owners(t.tenantId())).containsExactly(owner);
        assertThat(api.get(ownerBase, ownerSession.accessToken())
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("active");
        assertThat(api.get("/api/v1/users/" + adminB, adminSession.accessToken())
                        .getBody()
                        .get("roles")
                        .get(0)
                        .get("role_key")
                        .asString())
                .isEqualTo("tenant_admin");

        // Everyday powers are unchanged: the administrator manages every staff member who is not an administrator.
        assertThat(invite("cashier", t.headOffice(), adminSession).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(setRoles(cashier, List.of(role("loan_officer", t.headOffice())), adminSession)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.post("/api/v1/users/" + cashier + "/deactivate", null, adminSession.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** FR-IAM-18: the Owner invites, promotes, demotes and deactivates administrators, and cannot demote themselves. */
    @Test
    void theOwnerManagesTheAdministratorsAndMustTransferBeforeStepping() {
        assertThat(invite("tenant_admin", null, ownerSession).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(setRoles(cashier, List.of(role("tenant_admin", null)), ownerSession)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(setRoles(adminB, List.of(role("cashier", t.headOffice())), ownerSession)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(patchEmail(adminA, ownerSession).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.post("/api/v1/users/" + adminA + "/deactivate", null, ownerSession.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertRefused(
                setRoles(owner, List.of(role("cashier", t.headOffice())), ownerSession),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "owner_must_transfer_first");
        assertRefused(
                api.post("/api/v1/users/" + owner + "/deactivate", null, ownerSession.accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "cannot_deactivate_self");
        // The Owner may keep their administrator role and change their own contact, with the step-up.
        assertThat(patchEmail(owner, ownerSession, Map.of("password", Api.PASSWORD))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode me = api.get("/api/v1/me", ownerSession.accessToken()).getBody();
        assertThat(me.get("is_owner").asBoolean()).isTrue();
        JsonNode listed = api.get("/api/v1/users?limit=200", ownerSession.accessToken())
                .getBody()
                .get("items");
        long ownersListed = 0;
        for (JsonNode u : listed) {
            if (u.get("is_owner").asBoolean()) {
                ownersListed++;
                assertThat(u.get("id").asString()).isEqualTo(owner.toString());
            }
        }
        assertThat(ownersListed).isEqualTo(1);
    }

    /** FR-IAM-18: the Owner hands ownership to an active administrator with their password; exactly one Owner remains. */
    @Test
    void theOwnerTransfersOwnershipToAnActiveAdministrator() {
        assertRefused(transfer(adminA, ownerSession, null), HttpStatus.UNPROCESSABLE_CONTENT, "password_required");
        assertRefused(
                transfer(adminA, ownerSession, "Not-The-Password-1"),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "invalid_password");
        assertRefused(
                transfer(cashier, ownerSession, Api.PASSWORD),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "owner_must_be_active_admin");
        UUID gone = Api.staff(t, Api.email("gone"), new Role("tenant_admin", null));
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'deactivated' WHERE id = ?")
                .param(gone)
                .update();
        assertRefused(
                transfer(gone, ownerSession, Api.PASSWORD),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "owner_must_be_active_admin");
        TestDatabase.Fixture other = TestDatabase.tenant("owner-other", true);
        UUID stranger = Api.staff(other, Api.email("stranger"), new Role("tenant_admin", null));
        assertThat(transfer(stranger, ownerSession, Api.PASSWORD).getStatusCode())
                .as("a user of another business is not found here")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertRefused(transfer(adminB, adminSession, Api.PASSWORD), HttpStatus.FORBIDDEN, "owner_only");
        assertRefused(transfer(owner, ownerSession, Api.PASSWORD), HttpStatus.UNPROCESSABLE_CONTENT, "already_owner");
        assertThat(owners(t.tenantId())).containsExactly(owner);
        assertThat(owners(other.tenantId())).isEmpty();

        ResponseEntity<JsonNode> done = transfer(adminA, ownerSession, Api.PASSWORD);
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("is_owner").asBoolean()).isTrue();
        assertThat(owners(t.tenantId())).containsExactly(adminA);
        assertThat(audits("core.user.ownership_transferred", adminA)).isEqualTo(1);
        // The old Owner stays an administrator, now under the new Owner's rules.
        assertThat(api.get("/api/v1/users/" + owner, adminSession.accessToken())
                        .getBody()
                        .get("roles")
                        .get(0)
                        .get("role_key")
                        .asString())
                .isEqualTo("tenant_admin");
        assertRefused(invite("tenant_admin", null, ownerSession), HttpStatus.FORBIDDEN, "owner_only");
        assertThat(setRoles(owner, List.of(role("cashier", t.headOffice())), adminSession)
                        .getStatusCode())
                .as("the new Owner may demote the old one")
                .isEqualTo(HttpStatus.OK);
    }

    /** FR-IAM-18: an Owner with two-step sign-in proves it with a fresh authenticator code, not the password. */
    @Test
    void anOwnerWithTwoStepSignInTransfersWithACode() {
        TestDatabase.Fixture f = TestDatabase.tenant("owner-totp", true);
        Api.requireTwoStepForAdmins(f);
        Api on = Api.tenant(http, f.slug());
        String email = Api.email("owner-totp");
        UUID first = Api.staff(f, email, new Role("tenant_admin", null));
        UUID next = Api.staff(f, Api.email("next"), new Role("tenant_admin", null));
        makeOwner(f.tenantId(), first);
        Session s = on.signIn(first, email, Api.PASSWORD, null);
        // Review round 2: setting up two-step sign-in starts the 24 hour wait, as a password change does.
        Api.allowTotpReuse(first);
        assertRefused(
                on.post(
                        "/api/v1/users/" + next + "/transfer-ownership",
                        Map.of("totp_code", Api.totp(s.totpSecret(), Instant.now())),
                        s.accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "owner_recently_changed");
        backdate("users", "second_factor_changed_at", first, "25 hours");
        ResponseEntity<JsonNode> noCode = on.post(
                "/api/v1/users/" + next + "/transfer-ownership", Map.of("password", Api.PASSWORD), s.accessToken());
        assertRefused(noCode, HttpStatus.UNPROCESSABLE_CONTENT, "totp_required");
        Api.allowTotpReuse(first);
        ResponseEntity<JsonNode> done = on.post(
                "/api/v1/users/" + next + "/transfer-ownership",
                Map.of("totp_code", Api.totp(s.totpSecret(), Instant.now())),
                s.accessToken());
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(owners(f.tenantId())).containsExactly(next);
    }

    /** FR-IAM-18: the operator makes an active administrator the Owner with a reason and a code, audited on both sides. */
    @Test
    void theOperatorMakesAnActiveAdministratorTheOwnerWithAReason() {
        Api platform = Api.platform(http);
        String operatorEmail = Api.email("operator");
        UUID operatorId = Api.platformUser(operatorEmail);
        Session operator = platform.signIn(operatorId, operatorEmail, Api.PASSWORD, null);
        String base = "/api/v1/platform/tenants/" + t.tenantId() + "/users/";

        Api.allowTotpReuse(operatorId);
        ResponseEntity<JsonNode> noReason = platform.post(
                base + adminB + "/make-owner",
                Map.of("totp_code", Api.totp(operator.totpSecret(), Instant.now()), "reason", "abc"),
                operator.accessToken());
        assertRefused(noReason, HttpStatus.UNPROCESSABLE_CONTENT, "validation_failed");
        Api.allowTotpReuse(operatorId);
        ResponseEntity<JsonNode> notAdmin = platform.post(
                base + cashier + "/make-owner",
                Map.of(
                        "totp_code",
                        Api.totp(operator.totpSecret(), Instant.now()),
                        "reason",
                        "Test: the owner lost access"),
                operator.accessToken());
        assertRefused(notAdmin, HttpStatus.UNPROCESSABLE_CONTENT, "owner_must_be_active_admin");
        assertThat(owners(t.tenantId())).containsExactly(owner);

        Api.allowTotpReuse(operatorId);
        ResponseEntity<JsonNode> done = platform.post(
                base + adminB + "/make-owner",
                Map.of(
                        "totp_code",
                        Api.totp(operator.totpSecret(), Instant.now()),
                        "reason",
                        "Test: the owner lost access"),
                operator.accessToken());
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(owners(t.tenantId())).containsExactly(adminB);
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT data ->> 'reason' FROM platform_audit_log
                                 WHERE action = 'platform.tenant.owner_transferred' AND tenant_id = ?
                                """)
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo("Test: the owner lost access");
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT actor_kind || ':' || (data -> 'after' ->> 'reason') FROM audit_log
                                 WHERE tenant_id = ? AND action = 'core.user.ownership_transferred' AND entity_id = ?
                                """)
                        .params(t.tenantId(), adminB)
                        .query(String.class)
                        .single())
                .isEqualTo("platform:Test: the owner lost access");
        JsonNode overview = platform.get(
                        "/api/v1/platform/tenants/" + t.tenantId() + "/overview", operator.accessToken())
                .getBody();
        assertThat(overview.get("admins").get(0).get("id").asString()).isEqualTo(adminB.toString());
        assertThat(overview.get("admins").get(0).get("is_owner").asBoolean()).isTrue();
        assertThat(overview.get("tenant").get("owner_name").asString()).startsWith("Test Staff admin-b");
    }

    /** ADR-062: a business with no Owner yet keeps the rules of before until the operator assigns one. */
    @Test
    void aBusinessWithNoOwnerKeepsTheRulesOfBefore() {
        TestDatabase.Fixture f = TestDatabase.tenant("ownerless", true);
        Api on = Api.tenant(http, f.slug());
        String email = Api.email("admin");
        UUID admin = Api.staff(f, email, new Role("tenant_admin", null));
        UUID second = Api.staff(f, Api.email("second"), new Role("tenant_admin", null));
        Session s = on.signIn(admin, email, Api.PASSWORD, null);
        assertThat(on.post(
                                "/api/v1/users",
                                Map.of(
                                        "full_name",
                                        "Test Invitee",
                                        "email",
                                        Api.email("invitee"),
                                        "roles",
                                        List.of(role("tenant_admin", null))),
                                s.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(on.post("/api/v1/users/" + second + "/deactivate", null, s.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertRefused(
                on.post(
                        "/api/v1/users/" + admin + "/transfer-ownership",
                        Map.of("password", Api.PASSWORD),
                        s.accessToken()),
                HttpStatus.CONFLICT,
                "no_owner");
    }

    /** ADR-062: the database holds one Owner per business and keeps the Owner an administrator, whatever the code does. */
    @Test
    void theDatabaseKeepsOneOwnerWhoStaysAnAdministrator() {
        assertThatThrownBy(() -> makeOwner(t.tenantId(), adminA)).hasStackTraceContaining("users_one_owner");
        assertThatThrownBy(() -> TestDatabase.owner().sql("""
                                UPDATE user_role_assignments SET revoked_at = now()
                                 WHERE user_id = ? AND role_key = 'tenant_admin' AND revoked_at IS NULL
                                """).param(owner).update())
                .hasStackTraceContaining("user_role_assignments_owner_keeps_admin");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE users SET status = 'deactivated' WHERE id = ?")
                        .param(owner)
                        .update())
                .hasStackTraceContaining("users_owner_is_staff");
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = false WHERE id = ?")
                .param(owner)
                .update();
        assertThatThrownBy(() -> makeOwner(t.tenantId(), cashier)).hasStackTraceContaining("users_owner_is_admin");
        // Each business has its own Owner: another business's Owner does not count against this one.
        TestDatabase.Fixture other = TestDatabase.tenant("owner-second", true);
        UUID otherAdmin = Api.staff(other, Api.email("other"), new Role("tenant_admin", null));
        makeOwner(other.tenantId(), otherAdmin);
        makeOwner(t.tenantId(), owner);
        assertThat(owners(t.tenantId())).containsExactly(owner);
        assertThat(owners(other.tenantId())).containsExactly(otherAdmin);
    }

    /** NFR-ISO-01: the Owner of one business cannot see, change or hand ownership to the staff of another. */
    @Test
    void theOwnerRulesNeverReachAnotherBusiness() {
        TestDatabase.Fixture other = TestDatabase.tenant("owner-iso", true);
        UUID otherAdmin = Api.staff(other, Api.email("iso"), new Role("tenant_admin", null));
        makeOwner(other.tenantId(), otherAdmin);
        String path = "/api/v1/users/" + otherAdmin;
        assertThat(api.get(path, ownerSession.accessToken()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.post(path + "/deactivate", null, ownerSession.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(setRoles(otherAdmin, List.of(role("cashier", t.headOffice())), ownerSession)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(transfer(otherAdmin, ownerSession, Api.PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(owners(other.tenantId())).containsExactly(otherAdmin);
        assertThat(owners(t.tenantId())).containsExactly(owner);
    }

    // ---- Review round 1 of #443: the step-up and cooling period, the locks, and the paths that make an Owner
    // -------------

    /** Two calls released at the same moment; their answers in order. */
    static List<ResponseEntity<JsonNode>> together(
            java.util.function.Supplier<ResponseEntity<JsonNode>> a,
            java.util.function.Supplier<ResponseEntity<JsonNode>> b)
            throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Future<ResponseEntity<JsonNode>> first = pool.submit(() -> {
                go.await();
                return a.get();
            });
            java.util.concurrent.Future<ResponseEntity<JsonNode>> second = pool.submit(() -> {
                go.await();
                return b.get();
            });
            go.countDown();
            return List.of(
                    first.get(60, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(60, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getStatusCode().is2xxSuccessful()
                ? "ok"
                : r.getBody().get("code").asString();
    }

    /** The Owner is exactly one active administrator of the business. */
    void assertOneActiveAdminOwner(UUID tenantId) {
        List<UUID> found = owners(tenantId);
        assertThat(found).hasSize(1);
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT u.status = 'active' AND EXISTS (SELECT 1 FROM user_role_assignments a
                                        WHERE a.user_id = u.id AND a.role_key = 'tenant_admin' AND a.revoked_at IS NULL)
                                  FROM users u WHERE u.id = ?
                                """)
                        .param(found.getFirst())
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    void backdate(String table, String column, UUID userId, String interval) {
        TestDatabase.owner()
                .sql("UPDATE " + table + " SET " + column + " = now() - CAST(? AS interval) WHERE "
                        + (table.equals("users") ? "id" : "user_id") + " = ?")
                .params(interval, userId)
                .update();
    }

    /**
     * Review finding 1: the Owner's own email or phone change needs the step-up, tells the old address, and starts a 24 hour
     * cooling period on the transfer; so does a password change.
     */
    @Test
    void theOwnersOwnContactChangeNeedsTheStepUpAndCoolsTheTransfer() {
        String oldEmail = TestDatabase.owner()
                .sql("SELECT email FROM users WHERE id = ?")
                .param(owner)
                .query(String.class)
                .single();
        assertRefused(patchEmail(owner, ownerSession), HttpStatus.UNPROCESSABLE_CONTENT, "password_required");
        assertRefused(
                patchEmail(owner, ownerSession, Map.of("password", "Not-The-Password-1")),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "invalid_password");
        // A change of the name alone needs nothing.
        String etag = api.get("/api/v1/users/" + owner, ownerSession.accessToken())
                .getHeaders()
                .getETag();
        assertThat(api.call(
                                HttpMethod.PATCH,
                                "/api/v1/users/" + owner,
                                Map.of("full_name", "Test Owner Renamed"),
                                ownerSession.accessToken(),
                                Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(TestDatabase.owner()
                        .sql("SELECT contact_changed_at IS NULL FROM users WHERE id = ?")
                        .param(owner)
                        .query(Boolean.class)
                        .single())
                .isTrue();

        assertThat(patchEmail(owner, ownerSession, Map.of("password", Api.PASSWORD))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox WHERE template_key = 'identity.contact_changed'"
                                + " AND recipient = ?")
                        .param(oldEmail)
                        .query(Long.class)
                        .single())
                .as("the old address is told")
                .isEqualTo(1);
        assertRefused(
                transfer(adminA, ownerSession, Api.PASSWORD),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "owner_recently_changed");
        backdate("users", "contact_changed_at", owner, "25 hours");
        assertThat(transfer(adminA, ownerSession, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aRecentPasswordChangeCoolsTheTransferForADay() {
        backdate("user_credentials", "password_changed_at", owner, "1 hour");
        assertRefused(
                transfer(adminA, ownerSession, Api.PASSWORD),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "owner_recently_changed");
        backdate("user_credentials", "password_changed_at", owner, "25 hours");
        ResponseEntity<JsonNode> done = api.post(
                "/api/v1/users/" + adminA + "/transfer-ownership",
                Map.of("password", Api.PASSWORD, "reason", "Test: handing over to the new manager"),
                ownerSession.accessToken());
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT data -> 'after' ->> 'reason' FROM audit_log
                                 WHERE tenant_id = ? AND action = 'core.user.ownership_transferred'
                                """)
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo("Test: handing over to the new manager");
    }

    /** Review finding 3: two transfers at once end with exactly one Owner; the loser is told it is no longer the Owner. */
    @Test
    void twoTransfersAtOnceLeaveExactlyOneOwner() throws Exception {
        List<ResponseEntity<JsonNode>> r = together(
                () -> transfer(adminA, ownerSession, Api.PASSWORD), () -> transfer(adminB, ownerSession, Api.PASSWORD));
        assertThat(r.stream().map(TenantOwnerIT::code).sorted().toList()).containsExactly("ok", "owner_only");
        assertOneActiveAdminOwner(t.tenantId());
        assertThat(owners(t.tenantId()).getFirst()).isIn(adminA, adminB);
    }

    @Test
    void aTransferRacingTheDeactivationOfItsTargetNeverLeavesAnInactiveOwner() throws Exception {
        List<ResponseEntity<JsonNode>> r = together(
                () -> transfer(adminA, ownerSession, Api.PASSWORD),
                () -> api.post("/api/v1/users/" + adminA + "/deactivate", null, ownerSession.accessToken()));
        assertThat(List.of(code(r.get(0)), code(r.get(1))))
                .isIn(List.of("ok", "owner_protected"), List.of("owner_must_be_active_admin", "ok"));
        assertOneActiveAdminOwner(t.tenantId());
    }

    @Test
    void aTransferRacingTheDemotionOfItsTargetNeverLeavesANonAdministratorOwner() throws Exception {
        List<ResponseEntity<JsonNode>> r = together(
                () -> transfer(adminA, ownerSession, Api.PASSWORD),
                () -> setRoles(adminA, List.of(role("cashier", t.headOffice())), ownerSession));
        assertThat(List.of(code(r.get(0)), code(r.get(1))))
                .isIn(List.of("ok", "owner_protected"), List.of("owner_must_be_active_admin", "ok"));
        assertOneActiveAdminOwner(t.tenantId());
    }

    @Test
    void theOperatorsRescueRacingTheOwnersTransferLeavesExactlyOneOwner() throws Exception {
        Api platform = Api.platform(http);
        String operatorEmail = Api.email("operator");
        UUID operatorId = Api.platformUser(operatorEmail);
        Session operator = platform.signIn(operatorId, operatorEmail, Api.PASSWORD, null);
        Api.allowTotpReuse(operatorId);
        Map<String, String> body = Map.of(
                "totp_code", Api.totp(operator.totpSecret(), Instant.now()), "reason", "Test: the owner lost access");
        List<ResponseEntity<JsonNode>> r = together(
                () -> transfer(adminA, ownerSession, Api.PASSWORD),
                () -> platform.post(
                        "/api/v1/platform/tenants/" + t.tenantId() + "/users/" + adminB + "/make-owner",
                        body,
                        operator.accessToken()));
        assertThat(r.get(1).getStatusCode()).as("%s", r.get(1).getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(code(r.get(0))).isIn("ok", "owner_only");
        assertOneActiveAdminOwner(t.tenantId());
        assertThat(owners(t.tenantId()).getFirst()).isIn(adminA, adminB);
    }

    /** A new business: the first administrator the operator invites is its Owner at once, and stays so on joining. */
    @Test
    void theFirstAdministratorOfANewBusinessIsItsOwner() {
        Api platform = Api.platform(http);
        String operatorEmail = Api.email("operator");
        UUID operatorId = Api.platformUser(operatorEmail);
        Session operator = platform.signIn(operatorId, operatorEmail, Api.PASSWORD, null);
        String slug = "own-" + UUID.randomUUID().toString().substring(0, 8);
        String firstEmail = Api.email("first");
        ResponseEntity<JsonNode> created = platform.post(
                "/api/v1/platform/tenants",
                Map.of(
                        "name",
                        "Test Owner Business",
                        "slug",
                        slug,
                        "plan_code",
                        "starter",
                        "modules",
                        List.of("retail"),
                        "head_office",
                        Map.of("code", "HQ", "name", "Head Office"),
                        "admin",
                        Map.of("full_name", "Test First Admin", "email", firstEmail)),
                operator.accessToken());
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID tenantId =
                UUID.fromString(created.getBody().get("tenant").get("id").asString());
        JsonNode invitation = created.getBody().get("admin_invitation");
        UUID first = UUID.fromString(invitation.get("user_id").asString());
        assertThat(owners(tenantId)).containsExactly(first);

        // Review finding 4: the link expired before the first administrator joined; the operator sends a new one.
        TestDatabase.owner()
                .sql("UPDATE user_invitations SET expires_at = now() - interval '1 minute' WHERE user_id = ?")
                .param(first)
                .update();
        String path = "/api/v1/platform/tenants/" + tenantId + "/users/" + first + "/invitation";
        Api.allowTotpReuse(operatorId);
        ResponseEntity<JsonNode> noReason = platform.post(
                path,
                Map.of("totp_code", Api.totp(operator.totpSecret(), Instant.now()), "reason", "abc"),
                operator.accessToken());
        assertRefused(noReason, HttpStatus.UNPROCESSABLE_CONTENT, "validation_failed");
        Api.allowTotpReuse(operatorId);
        ResponseEntity<JsonNode> again = platform.post(
                path,
                Map.of(
                        "totp_code",
                        Api.totp(operator.totpSecret(), Instant.now()),
                        "reason",
                        "Test: the first link expired"),
                operator.accessToken());
        assertThat(again.getStatusCode()).as("%s", again.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(again.getHeaders().getCacheControl()).contains("no-store");
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT data ->> 'reason' FROM platform_audit_log
                                 WHERE action = 'platform.tenant_owner.invitation_reissued' AND tenant_id = ?
                                """)
                        .param(tenantId)
                        .query(String.class)
                        .single())
                .isEqualTo("Test: the first link expired");
        String url = again.getBody().get("url").asString();
        Api tenant = Api.tenant(http, slug);
        ResponseEntity<JsonNode> accepted = tenant.post(
                "/api/v1/auth/staff/invitations/accept",
                Map.of("token", url.substring(url.indexOf("#token=") + 7), "password", Api.PASSWORD),
                null);
        assertThat(accepted.getStatusCode()).as("%s", accepted.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(owners(tenantId)).containsExactly(first);
        assertThat(tenant.get(
                                "/api/v1/me",
                                accepted.getBody().get("access_token").asString())
                        .getBody()
                        .get("is_owner")
                        .asBoolean())
                .isTrue();

        // Once the Owner has joined the operator's route refuses: the business rescues itself, or make-owner does.
        Api.allowTotpReuse(operatorId);
        assertRefused(
                platform.post(
                        path,
                        Map.of(
                                "totp_code",
                                Api.totp(operator.totpSecret(), Instant.now()),
                                "reason",
                                "Test: not for a joined owner"),
                        operator.accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "not_an_invited_owner");
    }

    /** A business with no Owner: the administrator who joins while nobody else is an active administrator becomes its Owner. */
    @Test
    void theOnlyAdministratorToJoinABusinessWithNoOwnerBecomesItsOwner() {
        TestDatabase.Fixture f = TestDatabase.tenant("owner-claim", true);
        Api on = Api.tenant(http, f.slug());
        String email = Api.email("leaving");
        UUID leaving = Api.staff(f, email, new Role("tenant_admin", null));
        Session s = on.signIn(leaving, email, Api.PASSWORD, null);
        ResponseEntity<JsonNode> invited = on.post(
                "/api/v1/users",
                Map.of(
                        "full_name",
                        "Test Joiner",
                        "email",
                        Api.email("joiner"),
                        "roles",
                        List.of(role("tenant_admin", null))),
                s.accessToken());
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID joiner = UUID.fromString(invited.getBody().get("user").get("id").asString());
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'deactivated' WHERE id = ?")
                .param(leaving)
                .update();
        String url = invited.getBody().get("invitation").get("url").asString();
        assertThat(on.post(
                                "/api/v1/auth/staff/invitations/accept",
                                Map.of("token", url.substring(url.indexOf("#token=") + 7), "password", Api.PASSWORD),
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(owners(f.tenantId())).containsExactly(joiner);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.user.owner_assigned'")
                        .param(f.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /** The reissue of an invitation: for an invited administrator, the Owner's alone. */
    @Test
    void onlyTheOwnerSendsAnInvitedAdministratorANewLink() {
        ResponseEntity<JsonNode> invited = invite("tenant_admin", null, ownerSession);
        UUID pending = UUID.fromString(invited.getBody().get("user").get("id").asString());
        String path = "/api/v1/users/" + pending + "/invitation";
        assertRefused(api.post(path, null, adminSession.accessToken()), HttpStatus.FORBIDDEN, "owner_only");
        assertThat(api.post(path, null, ownerSession.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // A new link for anyone else stays every administrator's.
        ResponseEntity<JsonNode> seller = invite("cashier", t.headOffice(), adminSession);
        UUID sellerId = UUID.fromString(seller.getBody().get("user").get("id").asString());
        assertThat(api.post("/api/v1/users/" + sellerId + "/invitation", null, adminSession.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** The database refuses deleting the Owner's administrator row, as it refuses revoking it. */
    @Test
    void theDatabaseRefusesDeletingTheOwnersAdministratorRole() {
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("DELETE FROM user_role_assignments WHERE user_id = ? AND role_key = 'tenant_admin'")
                        .param(owner)
                        .update())
                .hasStackTraceContaining("user_role_assignments_owner_keeps_admin");
    }

    // ---- Review round 2 of #443
    // -------------------------------------------------------------------------------------------

    /** The operator's reset of the Owner's two-step sign-in starts the 24 hour wait too. */
    @Test
    void anOperatorResetOfTheOwnersTwoStepSignInCoolsTheTransfer() {
        Api platform = Api.platform(http);
        String operatorEmail = Api.email("operator");
        UUID operatorId = Api.platformUser(operatorEmail);
        Session operator = platform.signIn(operatorId, operatorEmail, Api.PASSWORD, null);
        Api.allowTotpReuse(operatorId);
        assertThat(platform.post(
                                "/api/v1/platform/tenants/" + t.tenantId() + "/users/" + owner + "/mfa/reset",
                                Map.of(
                                        "totp_code",
                                        Api.totp(operator.totpSecret(), Instant.now()),
                                        "reason",
                                        "Test: the owner lost the phone"),
                                operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        // The reset ends the Owner's sessions; signing in again with the password alone is enough here.
        String ownerEmail = TestDatabase.owner()
                .sql("SELECT email FROM users WHERE id = ?")
                .param(owner)
                .query(String.class)
                .single();
        Session again = api.signIn(owner, ownerEmail, Api.PASSWORD, null);
        assertRefused(
                transfer(adminA, again, Api.PASSWORD), HttpStatus.UNPROCESSABLE_CONTENT, "owner_recently_changed");
        backdate("users", "second_factor_changed_at", owner, "25 hours");
        assertThat(transfer(adminA, again, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** An Owner with no email is told of a contact change by SMS on the old phone, where the business's SMS is on. */
    @Test
    void anOwnerWithNoEmailIsToldOnTheOldPhone() {
        TestDatabase.Fixture f = TestDatabase.tenant("owner-sms", true);
        Api on = Api.tenant(http, f.slug());
        String email = Api.email("phone-owner");
        UUID phoneOwner = Api.staff(f, email, new Role("tenant_admin", null));
        makeOwner(f.tenantId(), phoneOwner);
        Session s = on.signIn(phoneOwner, email, Api.PASSWORD, null);
        TestDatabase.owner()
                .sql("UPDATE users SET email = NULL, phone_e164 = '+256700000091' WHERE id = ?")
                .param(phoneOwner)
                .update();
        com.rincoltech.bms.testsupport.SmsTenants.operator(f.tenantId(), true, 100);
        com.rincoltech.bms.testsupport.SmsTenants.enable(f.tenantId());
        String etag = on.get("/api/v1/users/" + phoneOwner, s.accessToken())
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> changed = on.call(
                HttpMethod.PATCH,
                "/api/v1/users/" + phoneOwner,
                Map.of("phone", "0700000092", "password", Api.PASSWORD),
                s.accessToken(),
                Map.of("If-Match", etag));
        assertThat(changed.getStatusCode()).as("%s", changed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT count(*) FROM notification_outbox
                                 WHERE template_key = 'identity.contact_changed' AND channel = 'sms' AND recipient = ?
                                """)
                        .param("+256700000091")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /** The shared lock: the Owner's deactivation of an administrator racing the operator making that administrator the Owner. */
    @Test
    void theOwnersDeactivationRacingTheOperatorsRescueNeverLeavesADeactivatedOwner() throws Exception {
        Api platform = Api.platform(http);
        String operatorEmail = Api.email("operator");
        UUID operatorId = Api.platformUser(operatorEmail);
        Session operator = platform.signIn(operatorId, operatorEmail, Api.PASSWORD, null);
        Api.allowTotpReuse(operatorId);
        Map<String, String> body = Map.of(
                "totp_code",
                Api.totp(operator.totpSecret(), Instant.now()),
                "reason",
                "Test: the owner turned hostile");
        List<ResponseEntity<JsonNode>> r = together(
                () -> api.post("/api/v1/users/" + adminB + "/deactivate", null, ownerSession.accessToken()),
                () -> platform.post(
                        "/api/v1/platform/tenants/" + t.tenantId() + "/users/" + adminB + "/make-owner",
                        body,
                        operator.accessToken()));
        assertThat(List.of(code(r.get(0)), code(r.get(1))))
                .isIn(List.of("ok", "owner_must_be_active_admin"), List.of("owner_protected", "ok"));
        assertOneActiveAdminOwner(t.tenantId());
    }

    /** The original finding: two invited administrators join a business with no Owner at once; one Owner, no 500. */
    @Test
    void twoAdministratorsJoiningABusinessWithNoOwnerAtOnceMakeOneOwner() throws Exception {
        TestDatabase.Fixture f = TestDatabase.tenant("owner-join", true);
        Api on = Api.tenant(http, f.slug());
        String email = Api.email("leaving");
        UUID leaving = Api.staff(f, email, new Role("tenant_admin", null));
        Session s = on.signIn(leaving, email, Api.PASSWORD, null);
        List<String> tokens = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            ResponseEntity<JsonNode> invited = on.post(
                    "/api/v1/users",
                    Map.of(
                            "full_name",
                            "Test Joiner " + i,
                            "email",
                            Api.email("joiner"),
                            "roles",
                            List.of(role("tenant_admin", null))),
                    s.accessToken());
            assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String url = invited.getBody().get("invitation").get("url").asString();
            tokens.add(url.substring(url.indexOf("#token=") + 7));
        }
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'deactivated' WHERE id = ?")
                .param(leaving)
                .update();
        List<ResponseEntity<JsonNode>> r = together(
                () -> on.post(
                        "/api/v1/auth/staff/invitations/accept",
                        Map.of("token", tokens.get(0), "password", Api.PASSWORD),
                        null),
                () -> on.post(
                        "/api/v1/auth/staff/invitations/accept",
                        Map.of("token", tokens.get(1), "password", Api.PASSWORD),
                        null));
        // Both join (the second simply joins as an administrator); never a 500.
        assertThat(r).allSatisfy(x -> assertThat(x.getStatusCode()).isEqualTo(HttpStatus.OK));
        assertOneActiveAdminOwner(f.tenantId());
    }
}
