package com.rincoltech.bms.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The forgotten staff password and the admin reset of sign-in through HTTP (FR-IAM-15, FR-IAM-03, FR-IAM-05, FR-IAM-07,
 * FR-IAM-08, FR-AUD-03, NFR-ISO-01; ADR-042). All users, addresses and passwords are fabricated.
 */
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetIT extends IntegrationTest {

    static final String REQUEST = "/api/v1/auth/staff/password/forgot";
    static final String COMPLETE = "/api/v1/auth/staff/password/reset";
    static final String NEW_PASSWORD = "Another-Fabricated-Pass-27";
    /** Every request has its own documentation address (RFC 5737), so the per-address limit never crosses tests. */
    static final AtomicInteger ADDRESS = new AtomicInteger(10);

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    UUID admin;
    Session adminSession;

    @BeforeEach
    void setUp() {
        // The limits answer the real refill time as Retry-After; the shared client would sleep through it and retry.
        http = Api.withoutRetries(http);
        t = TestDatabase.tenant("reset", true);
        api = Api.tenant(http, t.slug());
        // Two-step sign-in is optional by default (issue #348, ADR-046); most cases here are about a business that
        // requires it of its admins, so setUp turns that on for this tenant and the few cases about the optional state
        // turn it off.
        Api.requireTwoStepForAdmins(t);
        String adminEmail = Api.email("admin");
        admin = Api.staff(t, adminEmail, new Role("tenant_admin", null));
        adminSession = api.signIn(admin, adminEmail, Api.PASSWORD, null);
    }

    /** Whether this tenant requires two-step sign-in of its tenant admins (the tenant setting, ADR-046). */
    void adminsNeedTwoStep(boolean on) {
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = settings || CAST(? AS jsonb) WHERE tenant_id = ?")
                .params("{\"require_mfa_roles\": " + (on ? "[\"tenant_admin\"]" : "[]") + "}", t.tenantId())
                .update();
    }

    static Map<String, String> from(String address) {
        return Map.of("X-Forwarded-For", address);
    }

    static String freshAddress() {
        return "198.51.100." + (ADDRESS.incrementAndGet() % 250 + 1);
    }

    ResponseEntity<JsonNode> request(Api on, String login, String address) {
        return on.call(HttpMethod.POST, REQUEST, Map.of("login", login), null, from(address));
    }

    ResponseEntity<JsonNode> request(String login) {
        return request(api, login, freshAddress());
    }

    ResponseEntity<JsonNode> complete(Api on, String token, String password) {
        return on.call(
                HttpMethod.POST, COMPLETE, Map.of("token", token, "password", password), null, from(freshAddress()));
    }

    ResponseEntity<JsonNode> complete(Api on, String token, String password, String address) {
        return on.call(HttpMethod.POST, COMPLETE, Map.of("token", token, "password", password), null, from(address));
    }

    ResponseEntity<JsonNode> complete(String token, String password) {
        return complete(api, token, password);
    }

    UUID user(String email, String... roleKeys) {
        Role[] roles = roleKeys.length == 0
                ? new Role[] {new Role("cashier", t.headOffice())}
                : java.util.Arrays.stream(roleKeys)
                        .map(k -> new Role(k, k.equals("tenant_admin") ? null : t.headOffice()))
                        .toArray(Role[]::new);
        return Api.staff(t, email, roles);
    }

    /** The newest reset email of an address, read as the owner. */
    String emailedLink(String email) {
        return TestDatabase.owner()
                .sql("""
                        SELECT params->>'link' FROM notification_outbox
                         WHERE template_key = 'identity.password_reset' AND recipient = ? ORDER BY created_at DESC LIMIT 1
                        """)
                .param(email)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    long emails(String template, String email) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM notification_outbox WHERE template_key = ? AND recipient = ?")
                .params(template, email)
                .query(Long.class)
                .single();
    }

    static String tokenOf(String url) {
        return url.substring(url.indexOf("#token=") + 7);
    }

    /** The admin path as the PWA calls it: with a fresh code from the admin's own authenticator when they have one. */
    ResponseEntity<JsonNode> issue(Api on, Session by, UUID userId) {
        Api.allowTotpReuse(by.userId());
        Map<String, String> body =
                by.totpSecret() == null ? null : Map.of("totp_code", Api.totp(by.totpSecret(), Instant.now()));
        return on.post("/api/v1/users/" + userId + "/reset-sign-in", body, by.accessToken());
    }

    long idempotencyKeys() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM idempotency_keys WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    long audits(String action, UUID entityId) {
        return TestDatabase.owner()
                .sql(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND entity_id IS NOT DISTINCT FROM ?")
                .params(t.tenantId(), action, entityId)
                .query(Long.class)
                .single();
    }

    long auditsMentioning(String text) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND data::text LIKE ?")
                .params(t.tenantId(), "%" + text + "%")
                .query(Long.class)
                .single();
    }

    long openLinks(UUID userId) {
        return TestDatabase.owner()
                .sql(
                        "SELECT count(*) FROM user_password_resets WHERE user_id = ? AND used_at IS NULL AND revoked_at IS NULL")
                .param(userId)
                .query(Long.class)
                .single();
    }

    static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes()));
    }

    /** FR-IAM-15: the whole self-service path, with what is stored and what is sent. */
    @Test
    void aUserWhoForgotThePasswordGetsALinkSetsANewPasswordAndEverySessionEnds(CapturedOutput output) throws Exception {
        String email = Api.email("cashier");
        UUID userId = user(email);
        Session before = api.signIn(userId, email, Api.PASSWORD, null);
        Session beforeToo = api.signIn(userId, email, Api.PASSWORD, null);

        // The Host header and X-Forwarded-Host a client sends never reach the link.
        ResponseEntity<JsonNode> asked = api.call(
                HttpMethod.POST,
                REQUEST,
                Map.of("login", email),
                null,
                Map.of("X-Forwarded-For", freshAddress(), "X-Forwarded-Host", "evil.example"));
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        String url = emailedLink(email);
        assertThat(url).startsWith("https://" + t.slug() + "-bms-staging.rincoltech.test/reset-password#token=");
        String token = tokenOf(url);
        assertThat(emails("identity.password_reset", email)).isEqualTo(1);
        // Stored as a hash only, one hour, from the self-service path.
        var row = TestDatabase.owner()
                .sql(
                        "SELECT token_hash, origin, requested_by, expires_at, created_at FROM user_password_resets WHERE user_id = ?")
                .param(userId)
                .query()
                .singleRow();
        assertThat(row.get("token_hash")).isEqualTo(sha256(token));
        assertThat(row.get("origin")).isEqualTo("self");
        assertThat(row.get("requested_by")).isNull();
        assertThat(((java.sql.Timestamp) row.get("expires_at")).toInstant())
                .isBetween(Instant.now().plusSeconds(59 * 60), Instant.now().plusSeconds(61 * 60));
        assertThat(audits("core.password_reset.requested", userId)).isEqualTo(1);
        // The sessions still work until the link is used.
        assertThat(api.get("/api/v1/me", before.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> done = complete(token, NEW_PASSWORD);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("status").asString()).isEqualTo("signed_in");
        assertThat(done.getHeaders().getFirst("Set-Cookie")).startsWith("__Host-bms_rt=");
        String fresh = done.getBody().get("access_token").asString();
        assertThat(api.get("/api/v1/me", fresh).getBody().get("user_id").asString())
                .isEqualTo(userId.toString());

        // Every earlier session is gone, with its own reason, and the refresh tokens are dead too.
        assertThat(api.get("/api/v1/me", before.accessToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.get("/api/v1/me", beforeToo.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.refresh(before.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_reason = 'password_reset'")
                        .param(userId)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);

        // The old password is dead, the new one signs in.
        assertThat(api.login(email, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Single use.
        ResponseEntity<JsonNode> again = complete(token, "Yet-Another-Fabricated-Pass-3");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("reset_link_invalid");
        assertThat(api.login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Audited without the token; the owner of the mailbox is told, without a link.
        assertThat(audits("core.password_reset.used", userId)).isEqualTo(1);
        assertThat(auditsMentioning(token)).isZero();
        assertThat(emails("identity.password_changed", email)).isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT params::text FROM notification_outbox WHERE template_key = 'identity.password_changed' AND recipient = ?")
                        .param(email)
                        .query(String.class)
                        .single())
                .doesNotContain("token");
        // Neither the token nor a password reaches the log.
        assertThat(output.getAll()).doesNotContain(token).doesNotContain(NEW_PASSWORD);
    }

    /** FR-IAM-04, FR-IAM-15: the same answer for an account, no account, a deactivated one, an invited one and a phone. */
    @Test
    void theAnswerIsTheSameWhetherOrNotAnAccountMatches() {
        String known = Api.email("known");
        user(known);
        String deactivated = Api.email("gone");
        UUID gone = user(deactivated);
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'deactivated' WHERE id = ?")
                .param(gone)
                .update();
        String invited = Api.email("invited");
        UUID waiting = user(invited);
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'invited' WHERE id = ?")
                .param(waiting)
                .update();
        String phoneOnly = Api.email("phone");
        UUID phoneUser = user(phoneOnly);
        TestDatabase.owner()
                .sql("UPDATE users SET email = NULL, phone_e164 = '+256700000401' WHERE id = ?")
                .param(phoneUser)
                .update();

        List<String> logins = List.of(
                known,
                Api.email("nobody"),
                deactivated,
                invited,
                "+256700000401",
                "0700000402",
                "not an address or a number");
        JsonNode first = null;
        for (String login : logins) {
            long started = System.nanoTime();
            ResponseEntity<JsonNode> response = request(login);
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertThat(response.getStatusCode()).as(login).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getHeaders().getFirst("Set-Cookie")).as(login).isNull();
            // Nothing in the time of the answer says what happened inside: a floor, not a measurement of the work.
            assertThat(millis).as(login).isGreaterThanOrEqualTo(350);
            if (first == null) {
                first = response.getBody();
            }
            assertThat(response.getBody()).as(login).isEqualTo(first);
        }
        // Only the active account with an email got a link.
        assertThat(emailedLink(known)).isNotNull();
        for (String silent : List.of(deactivated, invited, phoneOnly)) {
            assertThat(emailedLink(silent)).as(silent).isNull();
        }
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM user_password_resets WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // The refusals of known accounts are visible to the operator, not to the caller.
        assertThat(audits("core.password_reset.refused", gone)).isEqualTo(1);
        assertThat(audits("core.password_reset.refused", waiting)).isEqualTo(1);
        assertThat(audits("core.password_reset.refused", phoneUser)).isEqualTo(1);
    }

    /** A request with no login at all is a plain validation error, the same for everyone. */
    @Test
    void aBlankLoginIsRefusedWithoutLookingAnyoneUp() {
        ResponseEntity<JsonNode> response = request("");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** FR-IAM-15: a newer link revokes the older ones. */
    @Test
    void aNewRequestRevokesTheOlderLink() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        String first = tokenOf(emailedLink(email));
        // Created a moment apart so "newest" is well defined.
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET created_at = created_at - interval '1 minute' WHERE recipient = ?")
                .param(email)
                .update();
        request(email);
        String second = tokenOf(emailedLink(email));
        assertThat(second).isNotEqualTo(first);
        assertThat(openLinks(userId)).isEqualTo(1);

        ResponseEntity<JsonNode> stale = complete(first, NEW_PASSWORD);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("reset_link_invalid");
        assertThat(api.login(email, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(complete(second, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** FR-IAM-15: a link lives one hour. */
    @Test
    void anExpiredLinkIsRefusedAndChangesNothing() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        String token = tokenOf(emailedLink(email));
        TestDatabase.owner()
                .sql("UPDATE user_password_resets SET expires_at = now() - interval '1 second' WHERE user_id = ?")
                .param(userId)
                .update();

        ResponseEntity<JsonNode> late = complete(token, NEW_PASSWORD);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(late.getBody().get("code").asString()).isEqualTo("reset_link_expired");
        assertThat(api.login(email, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audits("core.password_reset.used", userId)).isZero();
    }

    /** FR-IAM-15: of several uses of one link at the same moment, one wins. */
    @Test
    void aLinkUsedManyTimesAtOnceWorksOnce() throws Exception {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        String token = tokenOf(emailedLink(email));

        List<org.springframework.http.HttpStatusCode> statuses = Api.race(4, () -> complete(token, NEW_PASSWORD));

        assertThat(Api.count(statuses, HttpStatus.OK)).isEqualTo(1);
        assertThat(Api.count(statuses, HttpStatus.UNPROCESSABLE_CONTENT)).isEqualTo(3);
        assertThat(audits("core.password_reset.used", userId)).isEqualTo(1);
        assertThat(emails("identity.password_changed", email)).isEqualTo(1);
    }

    /** FR-IAM-04: the link sets a password under the same rules as an invitation, and a refusal keeps the link. */
    @Test
    void aWeakPasswordIsRefusedAndTheLinkStillWorks() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        String token = tokenOf(emailedLink(email));

        for (String weak : List.of("short", "password123", email)) {
            ResponseEntity<JsonNode> refused = complete(token, weak);
            assertThat(refused.getStatusCode()).as(weak).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(refused.getBody().get("code").asString()).as(weak).isEqualTo("weak_password");
        }
        assertThat(openLinks(userId)).isEqualTo(1);
        assertThat(complete(token, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** FR-IAM-06, FR-IAM-15: a reset never disables or skips the second factor. */
    @Test
    void aResetKeepsTheSecondFactor() {
        // The tenant admin of setUp enrolled TOTP at the first sign-in.
        String adminEmail = TestDatabase.owner()
                .sql("SELECT email FROM users WHERE id = ?")
                .param(admin)
                .query(String.class)
                .single();
        assertThat(adminSession.totpSecret()).isNotNull();
        request(adminEmail);
        String token = tokenOf(emailedLink(adminEmail));

        ResponseEntity<JsonNode> done = complete(token, NEW_PASSWORD);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("status").asString()).isEqualTo("mfa_required");
        assertThat(done.getBody().path("access_token").isMissingNode()
                        || done.getBody().path("access_token").isNull())
                .isTrue();
        assertThat(done.getHeaders().getFirst("Set-Cookie")).isNull();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT mfa_enabled AND EXISTS (SELECT 1 FROM user_credentials c WHERE c.user_id = users.id"
                                        + " AND c.totp_secret_enc IS NOT NULL) FROM users WHERE id = ?")
                        .param(admin)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM user_recovery_codes WHERE user_id = ? AND used_at IS NULL")
                        .param(admin)
                        .query(Long.class)
                        .single())
                .isEqualTo(10);

        // A wrong code does not sign in; the right one does.
        String mfaToken = done.getBody().get("mfa_token").asString();
        ResponseEntity<JsonNode> wrong =
                api.post("/api/v1/auth/staff/mfa/verify", Map.of("mfa_token", mfaToken, "code", "000000"), null);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Api.allowTotpReuse(admin);
        ResponseEntity<JsonNode> verified = api.post(
                "/api/v1/auth/staff/mfa/verify",
                Map.of("mfa_token", mfaToken, "code", Api.totp(adminSession.totpSecret(), Instant.now())),
                null);
        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody().get("status").asString()).isEqualTo("signed_in");
        // And the old session of the admin ended with the reset.
        assertThat(api.get("/api/v1/me", adminSession.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * ADR-059 (review of #433, finding 2): the link from the owner's own mailbox lifts the lock a stranger's wrong passwords
     * set, audited, and the owner signs in at once; nothing else can keep them out for good.
     */
    @Test
    void aCompletedResetLiftsTheLockAndSignsIn() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        String token = tokenOf(emailedLink(email));
        TestDatabase.owner()
                .sql(
                        "UPDATE users SET failed_login_count = 5, locked_until = now() + interval '10 minutes' WHERE id = ?")
                .param(userId)
                .update();
        assertThat(api.login(email, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.LOCKED);

        ResponseEntity<JsonNode> done = complete(token, NEW_PASSWORD);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("status").asString()).isEqualTo("signed_in");
        Map<String, Object> row = TestDatabase.owner()
                .sql("SELECT failed_login_count, locked_until FROM users WHERE id = ?")
                .param(userId)
                .query()
                .singleRow();
        assertThat(((Number) row.get("failed_login_count")).intValue()).isZero();
        assertThat(row.get("locked_until")).isNull();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE action = 'core.auth.unlocked' AND entity_id = ?"
                                + " AND data -> 'after' ->> 'by' = 'password_reset'")
                        .param(userId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(api.login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** FR-IAM-08: a user deactivated after asking cannot use the link, and deactivation revokes it. */
    @Test
    void aDeactivatedUserCannotUseAnOpenLink() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        String token = tokenOf(emailedLink(email));
        ResponseEntity<JsonNode> off =
                api.post("/api/v1/users/" + userId + "/deactivate", null, adminSession.accessToken());
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(openLinks(userId)).isZero();

        ResponseEntity<JsonNode> refused = complete(token, NEW_PASSWORD);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("reset_link_invalid");
        assertThat(api.login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // And the admin cannot issue one for the deactivated user, nor for one still invited.
        ResponseEntity<JsonNode> issue = issue(api, adminSession, userId);
        assertThat(issue.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(issue.getBody().get("code").asString()).isEqualTo("invalid_status_transition");
    }

    /** FR-IAM-15: three links an hour for one account; the rest are answered and ignored. */
    @Test
    void anAccountCannotBeFloodedWithLinks() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        for (int i = 0; i < 5; i++) {
            assertThat(request(email).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        assertThat(emails("identity.password_reset", email)).isEqualTo(3);
        assertThat(audits("core.password_reset.refused", userId)).isEqualTo(2);
        assertThat(openLinks(userId)).isEqualTo(1);
    }

    /** NFR-SEC-05 style bounds: an address that asks too often is answered 429, whatever it asks for. */
    @Test
    void anAddressThatAsksTooOftenIsLimited() {
        String address = freshAddress();
        String email = Api.email("cashier");
        user(email);
        for (int i = 0; i < 20; i++) {
            assertThat(request(api, Api.email("nobody"), address).getStatusCode())
                    .isEqualTo(HttpStatus.ACCEPTED);
        }
        ResponseEntity<JsonNode> limited = request(api, email, address);
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody().get("code").asString()).isEqualTo("rate_limited");
        assertThat(emailedLink(email)).isNull();
    }

    /** Guesses at the link are limited per address too. */
    @Test
    void anAddressThatGuessesTokensIsLimited() {
        String address = freshAddress();
        for (int i = 0; i < 30; i++) {
            ResponseEntity<JsonNode> response = api.call(
                    HttpMethod.POST,
                    COMPLETE,
                    Map.of("token", "guess-" + i, "password", NEW_PASSWORD),
                    null,
                    from(address));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        ResponseEntity<JsonNode> limited = api.call(
                HttpMethod.POST, COMPLETE, Map.of("token", "guess-31", "password", NEW_PASSWORD), null, from(address));
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** NFR-ISO-01: a link and a login belong to one tenant. */
    @Test
    void aLinkOfOneTenantDoesNothingInAnother() {
        TestDatabase.Fixture other = TestDatabase.tenant("reset-b", true);
        // The other tenant requires two-step sign-in of its admins too, so its admin gives a code, as before.
        Api.requireTwoStepForAdmins(other);
        Api otherApi = Api.tenant(http, other.slug());
        String email = Api.email("cashier");
        UUID userId = user(email);
        // The same address exists in the other tenant, with its own password.
        UUID twin = Api.staff(other, email, new Role("cashier", other.headOffice()));

        request(email);
        String token = tokenOf(emailedLink(email));
        // Used on the other tenant's host the link is simply not found.
        ResponseEntity<JsonNode> wrongHost = complete(otherApi, token, NEW_PASSWORD);
        assertThat(wrongHost.getBody().get("code").asString()).isEqualTo("reset_link_invalid");
        assertThat(otherApi.login(email, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(openLinks(userId)).isEqualTo(1);

        // Asking on the other tenant's host reaches the twin, never this tenant's user.
        request(otherApi, email, freshAddress());
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM user_password_resets WHERE user_id = ?")
                        .param(twin)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(openLinks(userId)).isEqualTo(1);
        assertThat(complete(token, NEW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        // The twin's password is untouched.
        assertThat(otherApi.login(email, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);

        // An admin of the other tenant cannot issue a link for this tenant's user.
        String otherAdminEmail = Api.email("admin");
        UUID otherAdmin = Api.staff(other, otherAdminEmail, new Role("tenant_admin", null));
        Session otherSession = otherApi.signIn(otherAdmin, otherAdminEmail, Api.PASSWORD, null);
        assertThat(issue(otherApi, otherSession, userId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** FR-IAM-15: a tenant admin issues a link, sees it once, and the user uses it. */
    @Test
    void anAdminResetShowsTheLinkOnceAndItWorksOnce() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        Session before = api.signIn(userId, email, Api.PASSWORD, null);

        long keysBefore = idempotencyKeys();
        // Sent with an Idempotency-Key, as a retrying client might: the answer holds a credential, so it is never kept
        // for a
        // replay (issue #369).
        Api.allowTotpReuse(adminSession.userId());
        ResponseEntity<JsonNode> issued = api.call(
                HttpMethod.POST,
                "/api/v1/users/" + userId + "/reset-sign-in",
                Map.of("totp_code", Api.totp(adminSession.totpSecret(), Instant.now())),
                adminSession.accessToken(),
                Map.of("Idempotency-Key", "reset-" + UUID.randomUUID()));
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        String url = issued.getBody().get("url").asString();
        assertThat(url).startsWith("https://" + t.slug() + "-bms-staging.rincoltech.test/reset-password#token=");
        assertThat(Instant.parse(issued.getBody().get("expires_at").asString()))
                .isBetween(Instant.now().plusSeconds(59 * 60), Instant.now().plusSeconds(61 * 60));
        String token = tokenOf(url);

        // Audited as the admin's act, without the token; not emailed.
        assertThat(audits("core.password_reset.requested", userId)).isEqualTo(1);
        assertThat(audits("core.password_reset.link_revealed", userId)).isEqualTo(1);
        assertThat(auditsMentioning(token)).isZero();
        assertThat(emails("identity.password_reset", email)).isZero();
        assertThat(idempotencyKeys()).isEqualTo(keysBefore);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM idempotency_keys WHERE response_body::text LIKE ?")
                        .param("%" + token + "%")
                        .query(Long.class)
                        .single())
                .isZero();
        var row = TestDatabase.owner()
                .sql("SELECT origin, requested_by FROM user_password_resets WHERE user_id = ?")
                .param(userId)
                .query()
                .singleRow();
        assertThat(row.get("origin")).isEqualTo("admin");
        assertThat(row.get("requested_by")).isEqualTo(admin);
        // Issuing does not sign anyone out: that happens when the link is used.
        assertThat(api.get("/api/v1/me", before.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        // A second link replaces the first.
        ResponseEntity<JsonNode> second = issue(api, adminSession, userId);
        String secondToken = tokenOf(second.getBody().get("url").asString());
        assertThat(complete(token, NEW_PASSWORD).getBody().get("code").asString())
                .isEqualTo("reset_link_invalid");
        ResponseEntity<JsonNode> done = complete(secondToken, NEW_PASSWORD);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("status").asString()).isEqualTo("signed_in");
        assertThat(api.get("/api/v1/me", before.accessToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(audits("core.password_reset.used", userId)).isEqualTo(1);
        assertThat(complete(secondToken, NEW_PASSWORD).getBody().get("code").asString())
                .isEqualTo("reset_link_invalid");
    }

    /** FR-IAM-03, FR-IAM-01: who may issue a link, and for whom. */
    @Test
    void onlyAHolderOfUsersManageMayIssueALinkForAnActiveUser() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        Session cashier = api.signIn(userId, email, Api.PASSWORD, null);
        String path = "/api/v1/users/" + userId + "/reset-sign-in";

        assertThat(api.post(path, null, cashier.accessToken()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.post(path, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(issue(api, adminSession, UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // A user who has not accepted the invitation keeps the invitation path.
        UUID waiting = user(Api.email("waiting"));
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'invited' WHERE id = ?")
                .param(waiting)
                .update();
        ResponseEntity<JsonNode> invited = issue(api, adminSession, waiting);
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(invited.getBody().get("code").asString()).isEqualTo("invalid_status_transition");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM user_password_resets WHERE user_id = ?")
                        .param(waiting)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** ADR-017: an admin whose permission covers some branches resets only users inside them. */
    @Test
    void aBranchScopedAdminResetsOnlyUsersInTheirBranches() {
        UUID inBranch = user(Api.email("branch2"));
        TestDatabase.owner()
                .sql("UPDATE user_role_assignments SET branch_id = ? WHERE user_id = ?")
                .params(t.secondBranch(), inBranch)
                .update();
        UUID headOfficeUser = user(Api.email("hq"));
        UUID everywhere = user(Api.email("everywhere"), "tenant_admin");
        UUID noRole = user(Api.email("norole"));
        TestDatabase.owner()
                .sql("UPDATE user_role_assignments SET revoked_at = now() WHERE user_id = ?")
                .param(noRole)
                .update();
        // The caller is the real admin, so the authenticator code is theirs; the scope is narrowed by the header.
        Map<String, String> scoped = Map.of(
                "X-Dev-User-Id", admin.toString(),
                "X-Dev-Permissions", "core.users.manage",
                "X-Dev-Branch-Ids", t.secondBranch().toString());

        assertThat(scopedIssue(scoped, inBranch).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(scopedIssue(scoped, headOfficeUser).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(scopedIssue(scoped, everywhere).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(scopedIssue(scoped, noRole).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(openLinks(headOfficeUser) + openLinks(everywhere) + openLinks(noRole))
                .isZero();
    }

    ResponseEntity<JsonNode> scopedIssue(Map<String, String> headers, UUID userId) {
        Api.allowTotpReuse(admin);
        return api.call(
                HttpMethod.POST,
                "/api/v1/users/" + userId + "/reset-sign-in",
                Map.of("totp_code", Api.totp(adminSession.totpSecret(), Instant.now())),
                null,
                headers);
    }

    /** MAJOR 1 of the review of #322: an admin never resets themselves or another tenant admin through the tenant route. */
    @Test
    void anAdminCannotResetTheirOwnSignInOrAnotherAdmins() {
        ResponseEntity<JsonNode> own = issue(api, adminSession, admin);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(own.getBody().get("code").asString()).isEqualTo("cannot_reset_own_sign_in");
        assertThat(own.getBody().get("detail").asString()).contains("Forgot password");

        UUID otherAdmin = user(Api.email("admin2"), "tenant_admin");
        ResponseEntity<JsonNode> other = issue(api, adminSession, otherAdmin);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(other.getBody().get("code").asString()).isEqualTo("admin_reset_by_operator");
        assertThat(other.getBody().get("detail").asString()).contains("platform operator");

        assertThat(openLinks(admin) + openLinks(otherAdmin)).isZero();
        assertThat(audits("core.password_reset.link_revealed", otherAdmin)).isZero();
    }

    /** MAJOR 1: an admin with two-step sign-in proves it with a fresh code; a code works once. */
    @Test
    void anAdminWithTwoStepSignInMustGiveAFreshCode() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        String path = "/api/v1/users/" + userId + "/reset-sign-in";

        ResponseEntity<JsonNode> none = api.post(path, null, adminSession.accessToken());
        // No body at all is a 400; a body with no code, or a blank one, is asked for the code.
        assertThat(none.getStatusCode().is4xxClientError()).isTrue();
        for (Map<String, String> body : List.of(Map.<String, String>of(), Map.of("totp_code", " "))) {
            ResponseEntity<JsonNode> missing = api.post(path, body, adminSession.accessToken());
            assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(missing.getBody().get("code").asString()).isEqualTo("totp_required");
        }
        // A stolen session knows no code, and an admin who has one cannot swap it for the password.
        ResponseEntity<JsonNode> password =
                api.post(path, Map.of("password", Api.PASSWORD), adminSession.accessToken());
        assertThat(password.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(password.getBody().get("code").asString()).isEqualTo("totp_required");
        ResponseEntity<JsonNode> wrong = api.post(path, Map.of("totp_code", "000000"), adminSession.accessToken());
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(wrong.getBody().get("code").asString()).isEqualTo("invalid_mfa_code");
        assertThat(openLinks(userId)).isZero();
        assertThat(emails("identity.password_reset_issued", email)).isZero();

        Api.allowTotpReuse(admin);
        String code = Api.totp(adminSession.totpSecret(), Instant.now());
        ResponseEntity<JsonNode> ok = api.post(path, Map.of("totp_code", code), adminSession.accessToken());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(openLinks(userId)).isEqualTo(1);
        // The same code cannot be used again.
        ResponseEntity<JsonNode> replay = api.post(path, Map.of("totp_code", code), adminSession.accessToken());
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(replay.getBody().get("code").asString()).isEqualTo("invalid_mfa_code");
    }

    /** A caller with no staff account at all cannot prove anything: no password to check, so nothing is issued. */
    @Test
    void aCallerWithNoAccountCannotProveAPassword() {
        UUID userId = user(Api.email("cashier"));
        Map<String, String> noAccount = Map.of(
                "X-Dev-User-Id", UUID.randomUUID().toString(),
                "X-Dev-Permissions", "core.users.manage",
                "X-Dev-Branch-Ids", "*");

        ResponseEntity<JsonNode> response = api.call(
                HttpMethod.POST,
                "/api/v1/users/" + userId + "/reset-sign-in",
                Map.of("password", Api.PASSWORD),
                null,
                noAccount);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(openLinks(userId)).isZero();
        assertThat(emails("identity.password_reset_issued", userEmail(userId))).isZero();
    }

    /** Takes the admin of setUp back to an admin with no second factor (two-step sign-in is optional); the session lives on. */
    void dropTheAdminsSecondFactor() {
        TestDatabase.owner()
                .sql("UPDATE user_credentials SET totp_secret_enc = NULL, totp_last_step = NULL WHERE user_id = ?")
                .param(admin)
                .update();
        TestDatabase.owner()
                .sql("UPDATE users SET mfa_enabled = false WHERE id = ?")
                .param(admin)
                .update();
    }

    /**
     * Two-step sign-in is optional (Hillary, 20 Oct 2026): an admin with no authenticator proves themselves with their own current
     * password and is not locked out of the route; a stolen session or token has no password.
     */
    @Test
    void anAdminWithNoSecondFactorProvesThemselvesWithTheirPassword() {
        UUID userId = user(Api.email("cashier"));
        String path = "/api/v1/users/" + userId + "/reset-sign-in";
        dropTheAdminsSecondFactor();

        // The session alone, a blank password, and a code they do not have: nothing is issued.
        for (Map<String, String> body :
                List.of(Map.<String, String>of(), Map.of("password", ""), Map.of("totp_code", "123456"))) {
            ResponseEntity<JsonNode> refused = api.post(path, body, adminSession.accessToken());
            assertThat(refused.getStatusCode()).as(body.toString()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(refused.getBody().get("code").asString()).isEqualTo("password_required");
        }
        assertThat(openLinks(userId)).isZero();

        // A wrong password is refused, in plain words, counted on the sign-in counter, and the password is nowhere in
        // the answer.
        ResponseEntity<JsonNode> wrong =
                api.post(path, Map.of("password", "Not-The-Password-1"), adminSession.accessToken());
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(wrong.getBody().get("code").asString()).isEqualTo("invalid_password");
        assertThat(wrong.getBody().toString()).doesNotContain("Not-The-Password-1");
        assertThat(failedLogins(admin)).isEqualTo(1);
        assertThat(openLinks(userId)).isZero();
        // Three more wrong ones, then the right one: the count starts again, as at sign-in.
        for (int i = 0; i < 3; i++) {
            api.post(path, Map.of("password", "Not-The-Password-" + i), adminSession.accessToken());
        }
        assertThat(failedLogins(admin)).isEqualTo(4);

        // The right password works, and the link is made as for an admin with a code.
        ResponseEntity<JsonNode> ok = api.post(path, Map.of("password", Api.PASSWORD), adminSession.accessToken());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failedLogins(admin)).isZero();
        assertThat(ok.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(ok.getBody().get("url").asString()).contains("/reset-password#token=");
        assertThat(openLinks(userId)).isEqualTo(1);
        assertThat(audits("core.password_reset.link_revealed", userId)).isEqualTo(1);
        // The password reaches no audit row.
        assertThat(auditsMentioning("Not-The-Password-1")).isZero();
        assertThat(auditsMentioning(Api.PASSWORD)).isZero();
    }

    long failedLogins(UUID userId) {
        return TestDatabase.owner()
                .sql("SELECT failed_login_count FROM users WHERE id = ?")
                .param(userId)
                .query(Long.class)
                .single();
    }

    /**
     * Round 4, item 1: wrong passwords here share the sign-in lock (the fifth locks the account), and the fifth also ends the
     * CALLING session, so a stolen token cannot go on guessing from a live session; the admin's other session is not touched.
     */
    @Test
    void wrongPasswordsOnTheRouteLockTheAccountLikeSignInAndTheFifthEndsTheCallingSession() {
        UUID userId = user(Api.email("cashier"));
        String path = "/api/v1/users/" + userId + "/reset-sign-in";
        // A second device of the same admin, signed in while the admin still had an authenticator.
        Session otherDevice = api.signIn(admin, userEmail(admin), Api.PASSWORD, adminSession.totpSecret());
        dropTheAdminsSecondFactor();

        for (int i = 0; i < 4; i++) {
            ResponseEntity<JsonNode> wrong =
                    api.post(path, Map.of("password", "Not-The-Password-" + i), adminSession.accessToken());
            assertThat(wrong.getBody().get("code").asString()).isEqualTo("invalid_password");
            // Four wrong passwords end nothing: the session still works.
            assertThat(api.get("/api/v1/me", adminSession.accessToken()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<JsonNode> fifth =
                api.post(path, Map.of("password", "Not-The-Password-4"), adminSession.accessToken());
        assertThat(fifth.getBody().get("code").asString()).isEqualTo("invalid_password");
        assertThat(audits("core.auth.account_locked", admin)).isEqualTo(1);
        assertThat(audits("core.auth.step_up_session_ended", admin)).isEqualTo(1);

        // The calling session is over: its next call is a 401, whatever the call.
        assertThat(api.get("/api/v1/me", adminSession.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.post(path, Map.of("password", Api.PASSWORD), adminSession.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.refresh(adminSession.refreshToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // The admin's other device is another family and is not logged out by a stranger's or a thief's guesses.
        assertThat(api.get("/api/v1/me", otherDevice.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // Nothing was issued, and the sign-in lock holds too: the right password is refused at sign-in.
        assertThat(openLinks(userId)).isZero();
        assertThat(api.login(userEmail(admin), Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        // While the lock holds, the other device is refused at this route as well (423), without being logged out.
        ResponseEntity<JsonNode> locked = api.post(path, Map.of("password", Api.PASSWORD), otherDevice.accessToken());
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(locked.getBody().get("code").asString()).isEqualTo("account_locked");
        assertThat(api.get("/api/v1/me", otherDevice.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** A lock column that was never set is NULL, and NULL > now() is NULL: read it as "not locked". */
    boolean stepUpLocked(UUID userId) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(step_up_locked_until > now(), false) FROM users WHERE id = ?")
                .param(userId)
                .query(Boolean.class)
                .single();
    }

    boolean signInLocked(UUID userId) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(locked_until > now(), false) FROM users WHERE id = ?")
                .param(userId)
                .query(Boolean.class)
                .single();
    }

    long stepUpFailures(UUID userId) {
        return TestDatabase.owner()
                .sql("SELECT step_up_failed_count FROM users WHERE id = ?")
                .param(userId)
                .query(Long.class)
                .single();
    }

    /**
     * Final review, item 1: the step-up lock holds on its own. A thief with two stolen sessions sends three wrong passwords; the
     * real admin signs in elsewhere (which starts the sign-in count again); the thief sends two more. The step-up count reaches
     * five and ends the first session, while the sign-in count is only two, so only the step-up lock can refuse the second stolen
     * session, for 15 minutes.
     */
    @Test
    void theStepUpLockHoldsOnItsOwnAfterTheSignInCountWasReset() {
        UUID userId = user(Api.email("cashier"));
        String path = "/api/v1/users/" + userId + "/reset-sign-in";
        String adminEmail = userEmail(admin);
        Session secondStolen = api.signIn(admin, adminEmail, Api.PASSWORD, adminSession.totpSecret());
        dropTheAdminsSecondFactor();
        var owner = TestDatabase.owner();
        // The real admin must be able to sign in without a second factor for this scenario (the requirement is data).
        adminsNeedTwoStep(false);
        try {
            for (int i = 0; i < 3; i++) {
                api.post(path, Map.of("password", "Not-The-Password-" + i), adminSession.accessToken());
            }
            assertThat(failedLogins(admin)).isEqualTo(3);
            assertThat(stepUpFailures(admin)).isEqualTo(3);

            // The real admin signs in elsewhere: the sign-in count starts again, the step-up count does not.
            ResponseEntity<JsonNode> signedIn = api.login(adminEmail, Api.PASSWORD);
            assertThat(signedIn.getBody().get("status").asString()).isEqualTo("signed_in");
            assertThat(failedLogins(admin)).isZero();
            assertThat(stepUpFailures(admin)).isEqualTo(3);

            for (int i = 3; i < 5; i++) {
                api.post(path, Map.of("password", "Not-The-Password-" + i), adminSession.accessToken());
            }
            assertThat(stepUpFailures(admin)).isEqualTo(5);
            assertThat(failedLogins(admin)).isEqualTo(2);
            assertThat(signInLocked(admin)).isFalse();
            assertThat(stepUpLocked(admin)).isTrue();
            assertThat(audits("core.auth.account_locked", admin)).isZero();
            assertThat(audits("core.auth.step_up_session_ended", admin)).isEqualTo(1);
            assertThat(api.get("/api/v1/me", adminSession.accessToken()).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            // The second stolen session, with the right password, is refused by the step-up lock alone.
            ResponseEntity<JsonNode> locked =
                    api.post(path, Map.of("password", Api.PASSWORD), secondStolen.accessToken());
            assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
            assertThat(locked.getBody().get("code").asString()).isEqualTo("account_locked");
            assertThat(openLinks(userId)).isZero();

            // Fifteen minutes later (the lock's end moved into the past) the same call goes through.
            owner.sql("UPDATE users SET step_up_locked_until = now() - interval '1 minute' WHERE id = ?")
                    .param(admin)
                    .update();
            ResponseEntity<JsonNode> later =
                    api.post(path, Map.of("password", Api.PASSWORD), secondStolen.accessToken());
            assertThat(later.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(stepUpFailures(admin)).isZero();
        } finally {
            adminsNeedTwoStep(true);
        }
    }

    /**
     * Final review, item 8: the step-up count is shared by wrong passwords and wrong codes, so enrolling an authenticator starts it
     * again: four wrong passwords and then an enrolment leave the next wrong code the first, not the fifth.
     */
    @Test
    void enrollingAnAuthenticatorStartsTheStepUpCountAgain() {
        UUID userId = user(Api.email("cashier"));
        String path = "/api/v1/users/" + userId + "/reset-sign-in";
        dropTheAdminsSecondFactor();
        for (int i = 0; i < 4; i++) {
            api.post(path, Map.of("password", "Not-The-Password-" + i), adminSession.accessToken());
        }
        assertThat(stepUpFailures(admin)).isEqualTo(4);

        // The role requires a second factor, so signing in again walks through the enrolment.
        Session enrolled = api.signIn(admin, userEmail(admin), Api.PASSWORD, null);

        assertThat(enrolled.totpSecret()).isNotNull();
        assertThat(stepUpFailures(admin)).isZero();
        // One wrong code now is the first, and ends nothing: the older session still lives.
        ResponseEntity<JsonNode> wrong = api.post(path, Map.of("totp_code", "000000"), enrolled.accessToken());
        assertThat(wrong.getBody().get("code").asString()).isEqualTo("invalid_mfa_code");
        assertThat(stepUpFailures(admin)).isEqualTo(1);
        assertThat(api.get("/api/v1/me", adminSession.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** Round 4, item 1: a stranger's wrong sign-ins do not log the admin out; only the admin's own session's failures do. */
    @Test
    void aStrangersWrongSignInsDoNotEndTheAdminsSessionEvenWithOneSlipHere() {
        UUID userId = user(Api.email("cashier"));
        String path = "/api/v1/users/" + userId + "/reset-sign-in";
        dropTheAdminsSecondFactor();
        String adminEmail = userEmail(admin);

        for (int i = 0; i < 4; i++) {
            assertThat(api.login(adminEmail, "Not-The-Password-" + i).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        // One slip of the admin's own on this route reaches the sign-in lock (the count is shared) but not the step-up
        // one.
        ResponseEntity<JsonNode> slip =
                api.post(path, Map.of("password", "Not-The-Password-X"), adminSession.accessToken());
        assertThat(slip.getBody().get("code").asString()).isEqualTo("invalid_password");
        assertThat(audits("core.auth.account_locked", admin)).isEqualTo(1);
        assertThat(audits("core.auth.step_up_session_ended", admin)).isZero();
        assertThat(api.get("/api/v1/me", adminSession.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** Round 4, item 3: the password step-up needs an active account (a dev principal reaches it with no session check). */
    @Test
    void aDeactivatedAccountCannotProveItsPasswordHere() {
        UUID userId = user(Api.email("cashier"));
        UUID gone = user(Api.email("gone"), "tenant_admin");
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'deactivated' WHERE id = ?")
                .param(gone)
                .update();
        Map<String, String> asGone = Map.of(
                "X-Dev-User-Id", gone.toString(),
                "X-Dev-Permissions", "core.users.manage",
                "X-Dev-Branch-Ids", "*");

        ResponseEntity<JsonNode> response = api.call(
                HttpMethod.POST,
                "/api/v1/users/" + userId + "/reset-sign-in",
                Map.of("password", Api.PASSWORD),
                null,
                asGone);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code").asString()).isEqualTo("invalid_credentials");
        assertThat(openLinks(userId)).isZero();
    }

    /**
     * Round 4, item 5: the tenant-wide switch refuses the mailbox reset for a user with no factor even when their role needs
     * none; the setting is put back afterwards.
     */
    @Test
    void theTenantWideRequirementRefusesTheMailboxResetForAUserWithNoFactor() {
        var owner = TestDatabase.owner();
        owner.sql("INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?, CAST(? AS jsonb))"
                        + " ON CONFLICT (tenant_id) DO UPDATE SET settings = EXCLUDED.settings")
                .params(UUID.randomUUID(), t.tenantId(), "{\"require_mfa_all_staff\": true}")
                .update();
        try {
            String email = Api.email("cashier");
            UUID userId = user(email);

            ResponseEntity<JsonNode> response = request(email);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(emailedLink(email)).isNull();
            assertThat(audits("core.password_reset.refused", userId)).isEqualTo(1);
            assertThat(owner.sql("SELECT data->'after'->>'reason' FROM audit_log WHERE entity_id = ?"
                                    + " AND action = 'core.password_reset.refused'")
                            .param(userId)
                            .query(String.class)
                            .single())
                    .isEqualTo("mfa_not_enrolled");
        } finally {
            owner.sql("UPDATE tenant_settings SET settings = '{}'::jsonb WHERE tenant_id = ?")
                    .param(t.tenantId())
                    .update();
        }
    }

    /** A refused target does not make the password count either: it is checked before the credential. */
    @Test
    void aRefusedTargetDoesNotCountAWrongPassword() {
        dropTheAdminsSecondFactor();
        ResponseEntity<JsonNode> own = api.post(
                "/api/v1/users/" + admin + "/reset-sign-in",
                Map.of("password", "Not-The-Password-1"),
                adminSession.accessToken());
        assertThat(own.getBody().get("code").asString()).isEqualTo("cannot_reset_own_sign_in");
        assertThat(failedLogins(admin)).isZero();
    }

    /**
     * Two-step sign-in is optional: a user whom neither a role flag nor the tenant's policy covers resets by mailbox as before,
     * decided by the data (the tenant setting) and not by a name. The tenant's requirement is switched off for this test only.
     */
    @Test
    void aRoleThatDoesNotRequireASecondFactorResetsByMailbox() {
        adminsNeedTwoStep(false);
        try {
            String email = Api.email("optional");
            UUID userId = user(email, "tenant_admin");

            ResponseEntity<JsonNode> response = request(email);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(emailedLink(email)).isNotNull();
            assertThat(audits("core.password_reset.refused", userId)).isZero();
            // And it works end to end: a user with no factor is signed in, as before.
            ResponseEntity<JsonNode> done = complete(tokenOf(emailedLink(email)), NEW_PASSWORD);
            assertThat(done.getBody().get("status").asString()).isEqualTo("signed_in");
        } finally {
            adminsNeedTwoStep(true);
        }
    }

    /** Round 3, item 1: the platform mfa/reset checks its target before it spends the operator's code, like the sign-in route. */
    @Test
    void thePlatformMfaResetDoesNotSpendTheCodeOnARefusedTarget() {
        Api platform = Api.platform(http);
        String operatorEmail = Api.email("operator");
        UUID operatorId = Api.platformUser(operatorEmail);
        Session operator = platform.signIn(operatorId, operatorEmail, Api.PASSWORD, null);
        UUID cashier = user(Api.email("cashier"));
        UUID target = user(Api.email("lockedout"), "tenant_admin");
        String base = "/api/v1/platform/tenants/" + t.tenantId() + "/users/";
        String code = operatorCode(operator);

        ResponseEntity<JsonNode> notAdmin =
                platform.post(base + cashier + "/mfa/reset", stepUp(code), operator.accessToken());
        assertThat(notAdmin.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(notAdmin.getBody().get("code").asString()).isEqualTo("not_a_tenant_admin");
        ResponseEntity<JsonNode> missing =
                platform.post(base + UUID.randomUUID() + "/mfa/reset", stepUp(code), operator.accessToken());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // The same code still works for a real tenant admin, and the reason is audited.
        assertThat(platform.post(base + target + "/mfa/reset", stepUp(code), operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data ->> 'reason' FROM platform_audit_log WHERE action = 'platform.tenant_admin.mfa_reset' AND tenant_id = ?")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo("Test: the owner is locked out");
    }

    String userEmail(UUID userId) {
        return TestDatabase.owner()
                .sql("SELECT email FROM users WHERE id = ?")
                .param(userId)
                .query(String.class)
                .single();
    }

    /** Round 2, m7: a refused target does not spend the admin's code. */
    @Test
    void aRefusedTargetDoesNotSpendTheCode() {
        UUID userId = user(Api.email("cashier"));
        Api.allowTotpReuse(admin);
        String code = Api.totp(adminSession.totpSecret(), Instant.now());
        Map<String, String> body = Map.of("totp_code", code);

        ResponseEntity<JsonNode> own =
                api.post("/api/v1/users/" + admin + "/reset-sign-in", body, adminSession.accessToken());
        assertThat(own.getBody().get("code").asString()).isEqualTo("cannot_reset_own_sign_in");
        ResponseEntity<JsonNode> missing =
                api.post("/api/v1/users/" + UUID.randomUUID() + "/reset-sign-in", body, adminSession.accessToken());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        // The same code still works for a valid target.
        ResponseEntity<JsonNode> ok =
                api.post("/api/v1/users/" + userId + "/reset-sign-in", body, adminSession.accessToken());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** MAJOR 1 (d): the user is told, without the link, when a reset is issued for them. */
    @Test
    void theUserIsToldWhenAResetIsIssuedForThem() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        ResponseEntity<JsonNode> issued = issue(api, adminSession, userId);
        String token = tokenOf(issued.getBody().get("url").asString());

        assertThat(emails("identity.password_reset_issued", email)).isEqualTo(1);
        String params = TestDatabase.owner()
                .sql(
                        "SELECT params::text FROM notification_outbox WHERE template_key = 'identity.password_reset_issued' AND recipient = ?")
                .param(email)
                .query(String.class)
                .single();
        assertThat(params)
                .doesNotContain(token)
                .doesNotContain("reset-password")
                .doesNotContain("http");
        assertThat(params).contains("\"who\": \"An administrator\"");
        // The link itself is never emailed on this path.
        assertThat(emails("identity.password_reset", email)).isZero();
    }

    /** MAJOR 2: a role that requires a second factor, not yet set up, gets no link by email. */
    @Test
    void aUserWhoseRoleNeedsASecondFactorThatIsNotSetUpGetsNoLinkByEmail() {
        String email = Api.email("newadmin");
        UUID userId = user(email, "tenant_admin");

        ResponseEntity<JsonNode> response = request(email);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(emailedLink(email)).isNull();
        assertThat(audits("core.password_reset.refused", userId)).isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data->'after'->>'reason' FROM audit_log WHERE entity_id = ? AND action = 'core.password_reset.refused'")
                        .param(userId)
                        .query(String.class)
                        .single())
                .isEqualTo("mfa_not_enrolled");
        assertThat(openLinks(userId)).isZero();
    }

    /** MAJOR 2: a link asked for before the role gained the requirement cannot be used either. */
    @Test
    void aLinkAskedForBeforeTheRoleNeededASecondFactorIsRefused() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        String token = tokenOf(emailedLink(email));
        TestDatabase.owner()
                .sql("INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, branch_id, granted_by)"
                        + " VALUES (?, ?, ?, 'tenant_admin', NULL, ?)")
                .params(UUID.randomUUID(), t.tenantId(), userId, userId)
                .update();

        ResponseEntity<JsonNode> done = complete(token, NEW_PASSWORD);

        assertThat(done.getBody().get("code").asString()).isEqualTo("reset_link_invalid");
        assertThat(api.login(email, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** The body of a platform reset route: the operator's code and the reason the audit keeps (issue #407). */
    static Map<String, String> stepUp(String code) {
        return Map.of("totp_code", code, "reason", "Test: the owner is locked out");
    }

    String operatorCode(Session operator) {
        Api.allowTotpReuse(operator.userId());
        return Api.totp(operator.totpSecret(), Instant.now());
    }

    /** The platform operator's path for a tenant admin: link once, no store, audited both sides, enrolment still forced. */
    @Test
    void thePlatformOperatorIssuesALinkForATenantAdminWhoStillMustEnrol() {
        Api platform = Api.platform(http);
        String operatorEmail = Api.email("operator");
        UUID operatorId = Api.platformUser(operatorEmail);
        Session operator = platform.signIn(operatorId, operatorEmail, Api.PASSWORD, null);
        String adminEmail = Api.email("lockedout");
        UUID locked = user(adminEmail, "tenant_admin");
        String path = "/api/v1/platform/tenants/" + t.tenantId() + "/users/" + locked + "/reset-sign-in";

        ResponseEntity<JsonNode> issued = platform.post(path, stepUp(operatorCode(operator)), operator.accessToken());

        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(issued.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        String url = issued.getBody().get("url").asString();
        assertThat(url).startsWith("https://" + t.slug() + "-bms-staging.rincoltech.test/reset-password#token=");
        assertThat(audits("core.password_reset.link_revealed", locked)).isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT actor_kind FROM audit_log WHERE entity_id = ? AND action = 'core.password_reset.link_revealed'")
                        .param(locked)
                        .query(String.class)
                        .single())
                .isEqualTo("platform");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM platform_audit_log WHERE action = 'platform.tenant_admin.sign_in_reset' AND tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data ->> 'reason' FROM platform_audit_log WHERE action = 'platform.tenant_admin.sign_in_reset' AND tenant_id = ?")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .as("the operator's reason is kept in the platform audit log")
                .isEqualTo("Test: the owner is locked out");
        assertThat(emails("identity.password_reset_issued", adminEmail)).isEqualTo(1);
        // Round 2, m6: the notice names who issued it, and here that is the platform, not "an administrator".
        String who = TestDatabase.owner()
                .sql(
                        "SELECT params->>'who' FROM notification_outbox WHERE template_key = 'identity.password_reset_issued' AND recipient = ?")
                .param(adminEmail)
                .query(String.class)
                .single();
        assertThat(who).isEqualTo("The platform operator");
        assertThat(auditsMentioning(tokenOf(url))).isZero();

        // The role requires a second factor and the admin has none yet: after the reset the enrolment is forced, no
        // session.
        ResponseEntity<JsonNode> done = complete(tokenOf(url), NEW_PASSWORD);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("status").asString()).isEqualTo("mfa_enrolment_required");
        assertThat(done.getBody().get("mfa_token").asString()).isNotBlank();
        assertThat(done.getHeaders().getFirst("Set-Cookie")).isNull();

        // Only a tenant admin, only an active one, only with platform authority; and a refusal does not spend the code.
        UUID cashier = user(Api.email("cashier"));
        String code = operatorCode(operator);
        ResponseEntity<JsonNode> notAdmin = platform.post(
                "/api/v1/platform/tenants/" + t.tenantId() + "/users/" + cashier + "/reset-sign-in",
                stepUp(code),
                operator.accessToken());
        assertThat(notAdmin.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(notAdmin.getBody().get("code").asString()).isEqualTo("not_a_tenant_admin");
        UUID second = user(Api.email("lockedout2"), "tenant_admin");
        assertThat(platform.post(
                                "/api/v1/platform/tenants/" + t.tenantId() + "/users/" + second + "/reset-sign-in",
                                stepUp(code),
                                operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, String> tenantToken = Map.of("totp_code", "123456");
        assertThat(platform.post(path, tenantToken, adminSession.accessToken())
                        .getStatusCode()
                        .is2xxSuccessful())
                .isFalse();
        assertThat(platform.post(path, tenantToken, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Round 2, M2: a stolen operator token alone cannot reset an admin's second factor or sign-in: a fresh code is needed. */
    @Test
    void bothPlatformResetRoutesNeedTheOperatorsOwnFreshCode() {
        Api platform = Api.platform(http);
        String operatorEmail = Api.email("operator");
        UUID operatorId = Api.platformUser(operatorEmail);
        Session operator = platform.signIn(operatorId, operatorEmail, Api.PASSWORD, null);
        UUID target = user(Api.email("lockedout"), "tenant_admin");
        String base = "/api/v1/platform/tenants/" + t.tenantId() + "/users/" + target;

        for (String route : List.of(base + "/reset-sign-in", base + "/mfa/reset")) {
            // No code, a blank one, a wrong one: refused, nothing done.
            assertThat(platform.post(route, null, operator.accessToken())
                            .getStatusCode()
                            .is4xxClientError())
                    .as(route)
                    .isTrue();
            assertThat(platform.post(route, stepUp(" "), operator.accessToken()).getStatusCode())
                    .as(route)
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            ResponseEntity<JsonNode> wrong = platform.post(route, stepUp("000000"), operator.accessToken());
            assertThat(wrong.getStatusCode()).as(route).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(wrong.getBody().get("code").asString()).as(route).isEqualTo("invalid_mfa_code");
        }
        assertThat(openLinks(target)).isZero();
        assertThat(emails("identity.password_reset_issued", userEmail(target))).isZero();

        // Issue #407: a reason (5 to 300 characters) is required on both routes; without one nothing is done and the
        // code
        // is not spent.
        String code = operatorCode(operator);
        for (String route : List.of(base + "/reset-sign-in", base + "/mfa/reset")) {
            for (Map<String, String> body :
                    List.of(Map.of("totp_code", code), Map.of("totp_code", code, "reason", "abc"))) {
                ResponseEntity<JsonNode> noReason = platform.post(route, body, operator.accessToken());
                assertThat(noReason.getStatusCode().value()).as(route).isIn(400, 422);
                assertThat(String.valueOf(noReason.getBody())).as(route).doesNotContain("invalid_mfa_code");
            }
        }
        assertThat(openLinks(target)).isZero();

        // A right code works once; the same code a second time is refused (single use, as on the tenant route).
        assertThat(platform.post(base + "/reset-sign-in", stepUp(code), operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> replay = platform.post(base + "/mfa/reset", stepUp(code), operator.accessToken());
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(replay.getBody().get("code").asString()).isEqualTo("invalid_mfa_code");
        // With a fresh code the second route works too.
        assertThat(platform.post(base + "/mfa/reset", stepUp(operatorCode(operator)), operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** MAJOR 1 review item 9: the check on the status inside complete() is what stops a deactivated user, not the revocation. */
    @Test
    void aUserDeactivatedBehindTheApplicationsBackCannotUseAnOpenLink() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        String token = tokenOf(emailedLink(email));
        String before = TestDatabase.owner()
                .sql("SELECT password_hash FROM user_credentials WHERE user_id = ?")
                .param(userId)
                .query(String.class)
                .single();
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'deactivated' WHERE id = ?")
                .param(userId)
                .update();

        ResponseEntity<JsonNode> refused = complete(token, NEW_PASSWORD);

        assertThat(refused.getBody().get("code").asString()).isEqualTo("reset_link_invalid");
        assertThat(TestDatabase.owner()
                        .sql("SELECT password_hash FROM user_credentials WHERE user_id = ?")
                        .param(userId)
                        .query(String.class)
                        .single())
                .isEqualTo(before);
        assertThat(openLinks(userId)).isEqualTo(1);
    }

    /** Review minor 4: nobody is the actor of an unauthenticated request, the address is in the row, an unknown login leaves a trace. */
    @Test
    void auditRowsOfTheFormHaveNoActorAndTheClientAddress() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        String address = freshAddress();
        request(api, email, address);
        String stranger = freshAddress();
        request(api, Api.email("nobody"), stranger);

        var requested = TestDatabase.owner()
                .sql(
                        "SELECT actor_user_id, data->'after'->>'ip' AS ip, host(ip) AS column_ip FROM audit_log WHERE entity_id = ? AND action = 'core.password_reset.requested'")
                .param(userId)
                .query()
                .singleRow();
        assertThat(requested.get("actor_user_id")).isNull();
        assertThat(requested.get("ip")).isEqualTo(address);
        assertThat(requested.get("column_ip")).isEqualTo(address);
        var unknown = TestDatabase.owner()
                .sql(
                        "SELECT actor_user_id, entity_id FROM audit_log WHERE tenant_id = ? AND action = 'core.password_reset.unknown_login' AND data->'after'->>'ip' = ?")
                .params(t.tenantId(), stranger)
                .query()
                .singleRow();
        assertThat(unknown.get("actor_user_id")).isNull();
        assertThat(unknown.get("entity_id")).isNull();

        String token = tokenOf(emailedLink(email));
        String finisher = freshAddress();
        complete(api, token, NEW_PASSWORD, finisher);
        var used = TestDatabase.owner()
                .sql(
                        "SELECT actor_user_id, data->'after'->>'ip' AS ip FROM audit_log WHERE entity_id = ? AND action = 'core.password_reset.used'")
                .param(userId)
                .query()
                .singleRow();
        assertThat(used.get("actor_user_id")).isNull();
        assertThat(used.get("ip")).isEqualTo(finisher);
    }

    /** Review minor 3: the link is stamped by the application's clock. */
    @Test
    void theLinkIsStampedByTheApplicationsClock() {
        String email = Api.email("cashier");
        UUID userId = user(email);
        request(email);
        Instant created = TestDatabase.owner()
                .sql("SELECT created_at FROM user_password_resets WHERE user_id = ?")
                .param(userId)
                .query(java.sql.Timestamp.class)
                .single()
                .toInstant();
        Instant expires = TestDatabase.owner()
                .sql("SELECT expires_at FROM user_password_resets WHERE user_id = ?")
                .param(userId)
                .query(java.sql.Timestamp.class)
                .single()
                .toInstant();
        assertThat(java.time.Duration.between(created, expires)).isEqualTo(java.time.Duration.ofHours(1));
        assertThat(created)
                .isBetween(Instant.now().minusSeconds(60), Instant.now().plusSeconds(5));
    }

    /** Review minor 8: the nightly purge deletes old rows, through a function the application role may call. */
    @Test
    void theNightlyPurgeDeletesRowsOlderThanThirtyDays() {
        UUID userId = user(Api.email("cashier"));
        var owner = TestDatabase.owner();
        for (String age : List.of("40 days", "1 hour")) {
            owner.sql(
                            "INSERT INTO user_password_resets (id, tenant_id, created_at, user_id, token_hash, expires_at, origin)"
                                    + " VALUES (?, ?, now() - CAST(? AS interval), ?, ?, now() - CAST(? AS interval) + interval '1 hour', 'self')")
                    .params(UUID.randomUUID(), t.tenantId(), age, userId, sha256Random(), age)
                    .update();
        }

        int deleted = org.springframework.jdbc.core.simple.JdbcClient.create(TestDatabase.appDataSource())
                .sql("SELECT user_password_resets_purge(now() - interval '30 days')")
                .query(Integer.class)
                .single();

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(owner.sql("SELECT count(*) FROM user_password_resets WHERE user_id = ?")
                        .param(userId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /** Round 2, m2: whatever cutoff the function is given, a row younger than 30 days survives. */
    @Test
    void thePurgeCannotBeToldToDeleteRecentRows() {
        UUID userId = user(Api.email("cashier"));
        var owner = TestDatabase.owner();
        owner.sql(
                        "INSERT INTO user_password_resets (id, tenant_id, created_at, user_id, token_hash, expires_at, origin)"
                                + " VALUES (?, ?, now() - interval '2 days', ?, ?, now() - interval '2 days' + interval '1 hour', 'self')")
                .params(UUID.randomUUID(), t.tenantId(), userId, sha256Random())
                .update();
        var app = org.springframework.jdbc.core.simple.JdbcClient.create(TestDatabase.appDataSource());

        app.sql("SELECT user_password_resets_purge('infinity'::timestamptz)")
                .query(Integer.class)
                .single();

        assertThat(owner.sql("SELECT count(*) FROM user_password_resets WHERE user_id = ?")
                        .param(userId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    static String sha256Random() {
        try {
            return sha256(UUID.randomUUID().toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
