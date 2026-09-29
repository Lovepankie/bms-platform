package com.rincoltech.bms.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.notifications.Notifier;
import com.rincoltech.bms.core.notifications.internal.RecordingNotifier;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Staff sign-in, second factor, sessions and invitations through HTTP (FR-IAM-01, FR-IAM-04 to
 * FR-IAM-08, FR-IAM-11, FR-IAM-12, FR-AUD-03). All users and contacts are fabricated.
 */
class StaffAuthIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    RecordingNotifier notifier;

    TestDatabase.Fixture t;
    Api api;
    UUID admin;
    String adminEmail;
    Session adminSession;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("auth", true);
        api = Api.tenant(http, t.slug());
        adminEmail = Api.email("admin");
        admin = Api.staff(t, adminEmail, new Role("tenant_admin", null));
        adminSession = api.signIn(admin, adminEmail, Api.PASSWORD, null);
    }

    long audits(String action, UUID entityId) {
        return TestDatabase.owner()
                .sql(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND entity_id IS NOT DISTINCT FROM ?")
                .params(t.tenantId(), action, entityId)
                .query(Long.class)
                .single();
    }

    Map<String, Object> invite(String email, String role, UUID branch) {
        Map<String, Object> r = new java.util.HashMap<>();
        r.put("role_key", role);
        r.put("branch_id", branch);
        return Map.of("full_name", "Test Staff Invitee", "email", email, "roles", List.of(r));
    }

    String tokenOf(JsonNode invited) {
        String url = invited.get("invitation").get("url").asString();
        return url.substring(url.indexOf("#token=") + 7);
    }

    /** FR-IAM-01 as amended (PR #8 item 1): the admin sees the link once, it is audited and sent. */
    @Test
    void anInvitationLinkIsShownToTheAdminAuditedAndSentThroughThePort() {
        String email = Api.email("cashier");
        ResponseEntity<JsonNode> invited =
                api.post("/api/v1/users", invite(email, "cashier", t.headOffice()), adminSession.accessToken());

        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode user = invited.getBody().get("user");
        UUID userId = UUID.fromString(user.get("id").asString());
        assertThat(user.get("status").asString()).isEqualTo("invited");
        String url = invited.getBody().get("invitation").get("url").asString();
        assertThat(url).startsWith("https://" + t.slug() + "-bms-staging.rincoltech.test/accept-invitation#token=");
        Instant expires = Instant.parse(
                invited.getBody().get("invitation").get("expires_at").asString());
        assertThat(expires)
                .isBetween(Instant.now().plusSeconds(71 * 3600), Instant.now().plusSeconds(73 * 3600));
        assertThat(audits("core.invitation.link_revealed", userId)).isEqualTo(1);
        assertThat(audits("core.user.invited", userId)).isEqualTo(1);
        List<Notifier.Message> sent =
                notifier.sent().stream().filter(m -> m.to().equals(email)).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().templateKey()).isEqualTo("core.staff_invitation");
        assertThat(sent.getFirst().params().get("link")).isEqualTo(url);
        // The token is stored only as a hash and never reaches the audit log.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND data::text LIKE ?")
                        .params(t.tenantId(), "%" + tokenOf(invited.getBody()) + "%")
                        .query(Long.class)
                        .single())
                .isZero();

        // Until accepted the user cannot sign in.
        assertThat(api.login(email, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<JsonNode> accepted = api.post(
                "/api/v1/auth/staff/invitations/accept",
                Map.of("token", tokenOf(invited.getBody()), "password", Api.PASSWORD),
                null);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Session cashier = api.signIn(userId, email, Api.PASSWORD, null);
        JsonNode me = api.get("/api/v1/me", cashier.accessToken()).getBody();
        assertThat(me.get("permissions").toString()).contains("lending.repayments.create");
        assertThat(me.get("all_branches").asBoolean()).isFalse();
        assertThat(me.get("default_branch_id").asString())
                .isEqualTo(t.headOffice().toString());

        // A link works once.
        ResponseEntity<JsonNode> again = api.post(
                "/api/v1/auth/staff/invitations/accept",
                Map.of("token", tokenOf(invited.getBody()), "password", Api.PASSWORD),
                null);
        assertThat(again.getBody().get("code").asString()).isEqualTo("invitation_invalid");
    }

    /** FR-IAM-01: the link is valid for 72 hours; a reissued link replaces the old one. */
    @Test
    void anExpiredOrReplacedInvitationIsRefused() {
        ResponseEntity<JsonNode> invited = api.post(
                "/api/v1/users",
                invite(Api.email("officer"), "loan_officer", t.headOffice()),
                adminSession.accessToken());
        UUID userId = UUID.fromString(invited.getBody().get("user").get("id").asString());
        TestDatabase.owner()
                .sql("UPDATE user_invitations SET expires_at = now() - interval '1 minute' WHERE user_id = ?")
                .param(userId)
                .update();
        ResponseEntity<JsonNode> expired = api.post(
                "/api/v1/auth/staff/invitations/accept",
                Map.of("token", tokenOf(invited.getBody()), "password", Api.PASSWORD),
                null);
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(expired.getBody().get("code").asString()).isEqualTo("invitation_expired");

        ResponseEntity<JsonNode> reissued =
                api.post("/api/v1/users/" + userId + "/invitation", null, adminSession.accessToken());
        assertThat(reissued.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newUrl = reissued.getBody().get("url").asString();
        String newToken = newUrl.substring(newUrl.indexOf("#token=") + 7);
        assertThat(audits("core.invitation.link_revealed", userId)).isEqualTo(2);

        ResponseEntity<JsonNode> weak = api.post(
                "/api/v1/auth/staff/invitations/accept", Map.of("token", newToken, "password", "password123"), null);
        assertThat(weak.getBody().get("code").asString()).isEqualTo("weak_password");
        ResponseEntity<JsonNode> tooShort = api.post(
                "/api/v1/auth/staff/invitations/accept", Map.of("token", newToken, "password", "Short1!"), null);
        assertThat(tooShort.getBody().get("code").asString()).isEqualTo("weak_password");
        assertThat(api.post(
                                "/api/v1/auth/staff/invitations/accept",
                                Map.of("token", newToken, "password", Api.PASSWORD),
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** FR-IAM-04: the same generic error whether or not the account exists; FR-AUD-03. */
    @Test
    void wrongCredentialsGetOneGenericErrorAndAreAudited() {
        ResponseEntity<JsonNode> wrongPassword = api.login(adminEmail, "Not-The-Password-1");
        ResponseEntity<JsonNode> noSuchUser = api.login(Api.email("nobody"), "Not-The-Password-1");
        assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(noSuchUser.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrongPassword.getBody().get("code").asString()).isEqualTo("invalid_credentials");
        assertThat(noSuchUser.getBody().get("code").asString()).isEqualTo("invalid_credentials");
        assertThat(wrongPassword.getBody().get("detail").asString())
                .isEqualTo(noSuchUser.getBody().get("detail").asString());
        assertThat(audits("core.auth.sign_in_failed", admin)).isEqualTo(1);
        assertThat(audits("core.auth.sign_in_failed", null)).isEqualTo(1);
        assertThat(audits("core.auth.signed_in", admin)).isEqualTo(1);
    }

    /** FR-IAM-05: five failures lock for 15 minutes; the sixth attempt fails even when right. */
    @Test
    void fiveFailuresLockTheAccount() {
        String email = Api.email("teller");
        UUID cashier = Api.staff(t, email, new Role("cashier", t.headOffice()));
        for (int i = 0; i < 5; i++) {
            assertThat(api.login(email, "Wrong-Password-" + i).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<JsonNode> sixth = api.login(email, Api.PASSWORD);
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(sixth.getBody().get("code").asString()).isEqualTo("account_locked");
        assertThat(audits("core.auth.account_locked", cashier)).isEqualTo(1);
        assertThat(audits("core.auth.sign_in_refused_locked", cashier)).isEqualTo(1);

        TestDatabase.owner()
                .sql("UPDATE users SET locked_until = now() - interval '1 second' WHERE id = ?")
                .param(cashier)
                .update();
        assertThat(api.login(email, Api.PASSWORD).getBody().get("status").asString())
                .isEqualTo("signed_in");
    }

    /** FR-IAM-06: a tenant admin enrols TOTP at first sign-in, then always needs a code. */
    @Test
    void aTenantAdminIsForcedToEnrolAndThenNeedsTheSecondFactor() {
        assertThat(adminSession.totpSecret()).isNotBlank();
        assertThat(adminSession.recoveryCodes()).hasSize(10).doesNotHaveDuplicates();
        assertThat(audits("core.mfa.enrolled", admin)).isEqualTo(1);
        // The secret is stored encrypted, never as the base32 text the user saw.
        byte[] stored = TestDatabase.owner()
                .sql("SELECT totp_secret_enc FROM user_credentials WHERE user_id = ?")
                .param(admin)
                .query(byte[].class)
                .single();
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain(adminSession.totpSecret());

        ResponseEntity<JsonNode> login = api.login(adminEmail, Api.PASSWORD);
        assertThat(login.getBody().get("status").asString()).isEqualTo("mfa_required");
        assertThat(login.getBody().get("access_token").isNull()).isTrue();
        String mfaToken = login.getBody().get("mfa_token").asString();

        ResponseEntity<JsonNode> wrong =
                api.post("/api/v1/auth/staff/mfa/verify", Map.of("mfa_token", mfaToken, "code", "000000"), null);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(wrong.getBody().get("code").asString()).isEqualTo("invalid_mfa_code");
        assertThat(audits("core.mfa.failed", admin)).isEqualTo(1);

        Api.allowTotpReuse(admin);
        String code = Api.totp(adminSession.totpSecret(), Instant.now());
        ResponseEntity<JsonNode> ok =
                api.post("/api/v1/auth/staff/mfa/verify", Map.of("mfa_token", mfaToken, "code", code), null);
        assertThat(ok.getBody().get("status").asString()).isEqualTo("signed_in");

        // The same code is accepted once.
        String second =
                api.login(adminEmail, Api.PASSWORD).getBody().get("mfa_token").asString();
        ResponseEntity<JsonNode> replay =
                api.post("/api/v1/auth/staff/mfa/verify", Map.of("mfa_token", second, "code", code), null);
        assertThat(replay.getBody().get("code").asString()).isEqualTo("invalid_mfa_code");
    }

    /** FR-IAM-11 (PR #8 item 2): a recovery code signs in once; regenerating replaces them all. */
    @Test
    void aRecoveryCodeWorksExactlyOnce() {
        String code = adminSession.recoveryCodes().getFirst();
        String mfaToken =
                api.login(adminEmail, Api.PASSWORD).getBody().get("mfa_token").asString();
        ResponseEntity<JsonNode> used = api.post(
                "/api/v1/auth/staff/mfa/verify", Map.of("mfa_token", mfaToken, "code", code.toLowerCase()), null);
        assertThat(used.getBody().get("status").asString()).isEqualTo("signed_in");
        assertThat(audits("core.mfa.recovery_code_used", admin)).isEqualTo(1);

        String again =
                api.login(adminEmail, Api.PASSWORD).getBody().get("mfa_token").asString();
        ResponseEntity<JsonNode> reused =
                api.post("/api/v1/auth/staff/mfa/verify", Map.of("mfa_token", again, "code", code), null);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reused.getBody().get("code").asString()).isEqualTo("invalid_mfa_code");
        assertThat(api.get("/api/v1/me", used.getBody().get("access_token").asString())
                        .getBody()
                        .get("unused_recovery_codes")
                        .asInt())
                .isEqualTo(9);

        Api.allowTotpReuse(admin);
        ResponseEntity<JsonNode> regenerated = api.post(
                "/api/v1/auth/staff/mfa/recovery-codes",
                Map.of("code", Api.totp(adminSession.totpSecret(), Instant.now())),
                adminSession.accessToken());
        assertThat(regenerated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(regenerated.getBody().get("recovery_codes")).hasSize(10);
        String old = adminSession.recoveryCodes().get(1);
        String third =
                api.login(adminEmail, Api.PASSWORD).getBody().get("mfa_token").asString();
        assertThat(api.post("/api/v1/auth/staff/mfa/verify", Map.of("mfa_token", third, "code", old), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        String fresh = regenerated.getBody().get("recovery_codes").get(0).asString();
        assertThat(api.post("/api/v1/auth/staff/mfa/verify", Map.of("mfa_token", third, "code", fresh), null)
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("signed_in");
    }

    /** FR-IAM-12 (PR #8 item 2): another tenant admin resets a lost factor; sessions end. */
    @Test
    void anotherTenantAdminResetsALostSecondFactor() {
        String otherEmail = Api.email("admin2");
        UUID other = Api.staff(t, otherEmail, new Role("tenant_admin", null));
        Session otherSession = api.signIn(other, otherEmail, Api.PASSWORD, null);

        ResponseEntity<JsonNode> self =
                api.post("/api/v1/users/" + admin + "/mfa/reset", null, adminSession.accessToken());
        assertThat(self.getBody().get("code").asString()).isEqualTo("cannot_reset_own_mfa");

        ResponseEntity<JsonNode> reset =
                api.post("/api/v1/users/" + admin + "/mfa/reset", null, otherSession.accessToken());
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getBody().get("mfa_enabled").asBoolean()).isFalse();
        assertThat(audits("core.user.mfa_reset", admin)).isEqualTo(1);
        // The reset user's sessions end at once, and the next sign-in enrols again.
        ResponseEntity<JsonNode> revoked = api.get("/api/v1/me", adminSession.accessToken());
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revoked.getBody().get("code").asString()).isEqualTo("session_revoked");
        assertThat(api.login(adminEmail, Api.PASSWORD).getBody().get("status").asString())
                .isEqualTo("mfa_enrolment_required");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM user_recovery_codes WHERE user_id = ?")
                        .param(admin)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** FR-IAM-07: rotation, reuse detection revoking the family, sign-out. */
    @Test
    void refreshTokensRotateAndReuseRevokesTheFamily() {
        String email = Api.email("officer");
        UUID officer = Api.staff(t, email, new Role("loan_officer", t.headOffice()));
        Session first = api.signIn(officer, email, Api.PASSWORD, null);

        ResponseEntity<JsonNode> rotated = api.refresh(first.refreshToken());
        assertThat(rotated.getStatusCode()).isEqualTo(HttpStatus.OK);
        String second = api.refreshCookie(rotated);
        assertThat(second).isNotEqualTo(first.refreshToken());
        String access = rotated.getBody().get("access_token").asString();
        assertThat(api.get("/api/v1/me", access).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Presenting the rotated token again is a reuse: the whole family ends.
        ResponseEntity<JsonNode> reuse = api.refresh(first.refreshToken());
        assertThat(reuse.getBody().get("code").asString()).isEqualTo("session_revoked");
        assertThat(audits("core.auth.refresh_token_reused", officer)).isEqualTo(1);
        assertThat(api.refresh(second).getBody().get("code").asString()).isEqualTo("session_revoked");
        assertThat(api.get("/api/v1/me", access).getBody().get("code").asString())
                .isEqualTo("session_revoked");

        Session fresh = api.signIn(officer, email, Api.PASSWORD, null);
        assertThat(api.post("/api/v1/auth/logout", null, fresh.accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(api.get("/api/v1/me", fresh.accessToken())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("session_revoked");
        assertThat(api.refresh(fresh.refreshToken()).getBody().get("code").asString())
                .isEqualTo("session_revoked");
    }

    /** FR-IAM-07: a refresh token past its idle expiry signs nobody in. */
    @Test
    void anIdleSessionExpires() {
        String email = Api.email("idle");
        UUID officer = Api.staff(t, email, new Role("loan_officer", t.headOffice()));
        Session session = api.signIn(officer, email, Api.PASSWORD, null);
        TestDatabase.owner()
                .sql("UPDATE auth_sessions SET idle_expires_at = now() - interval '1 minute' WHERE user_id = ?")
                .param(officer)
                .update();
        assertThat(api.refresh(session.refreshToken()).getBody().get("code").asString())
                .isEqualTo("session_expired");
    }

    /** FR-IAM-08: deactivation refuses the next request of a still-valid access token. */
    @Test
    void deactivationRevokesSessionsImmediately() {
        String email = Api.email("leaver");
        UUID leaver = Api.staff(t, email, new Role("cashier", t.headOffice()));
        Session session = api.signIn(leaver, email, Api.PASSWORD, null);
        assertThat(api.get("/api/v1/me", session.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> deactivated =
                api.post("/api/v1/users/" + leaver + "/deactivate", null, adminSession.accessToken());
        assertThat(deactivated.getBody().get("status").asString()).isEqualTo("deactivated");

        ResponseEntity<JsonNode> next = api.get("/api/v1/me", session.accessToken());
        assertThat(next.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(next.getBody().get("code").asString()).isEqualTo("session_revoked");
        assertThat(api.login(email, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(audits("core.user.deactivated", leaver)).isEqualTo(1);

        // The last tenant admin can neither deactivate themself nor lose the role.
        assertThat(api.post("/api/v1/users/" + admin + "/deactivate", null, adminSession.accessToken())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("cannot_deactivate_self");
        ResponseEntity<JsonNode> demote = api.call(
                org.springframework.http.HttpMethod.PUT,
                "/api/v1/users/" + admin + "/roles",
                List.of(Map.of("role_key", "auditor")),
                adminSession.accessToken());
        assertThat(demote.getBody().get("code").asString()).isEqualTo("last_tenant_admin");
    }

    /** Chapter 7 section 7.4.2 step 3: a token of tenant A is refused on tenant B's host. */
    @Test
    void aTokenIsRefusedOnAnotherTenantsHost() {
        TestDatabase.Fixture other = TestDatabase.tenant("auth-other", true);
        ResponseEntity<JsonNode> response =
                Api.tenant(http, other.slug()).get("/api/v1/me", adminSession.accessToken());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().get("code").asString()).isEqualTo("tenant_mismatch");

        ResponseEntity<JsonNode> forged = api.get("/api/v1/me", adminSession.accessToken() + "x");
        assertThat(forged.getBody().get("code").asString()).isEqualTo("unauthenticated");
    }

    /** FR-AUD-05: an invited user's phone is masked in the audit payload. */
    @Test
    void phoneNumbersAreMaskedInAuditRows() {
        Map<String, Object> body = Map.of(
                "full_name",
                "Test Staff Phone",
                "phone",
                "0700000077",
                "roles",
                List.of(Map.of(
                        "role_key", "cashier", "branch_id", t.headOffice().toString())));
        ResponseEntity<JsonNode> invited = api.post("/api/v1/users", body, adminSession.accessToken());
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(invited.getBody().get("user").get("phone_e164").asString()).isEqualTo("+256700000077");
        String data = TestDatabase.owner()
                .sql(
                        "SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'core.user.invited' AND entity_id = ?::uuid")
                .params(t.tenantId(), invited.getBody().get("user").get("id").asString())
                .query(String.class)
                .single();
        assertThat(data).contains("0077").doesNotContain("+256700000077");
        // Without an email the invitation goes by SMS through the same port.
        assertThat(notifier.sent().stream()
                        .anyMatch(m ->
                                m.to().equals("+256700000077") && m.channel().equals("sms")))
                .isTrue();
    }
}
