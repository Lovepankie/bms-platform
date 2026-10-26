package com.rincoltech.bms.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The per-tenant two-step sign-in policy (FR-IAM-06, FR-IAM-16, issue #348, ADR-046): optional by default, required for everyone or
 * for chosen roles by a tenant administrator, with a grace period, and the forgotten-password rules of ADR-042 intact. Fabricated
 * tenants and people.
 */
class TwoStepPolicyIT extends IntegrationTest {

    private static final AtomicInteger ADDRESS = new AtomicInteger();

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    Session admin;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("twostep", true);
        api = Api.tenant(http, t.slug());
        admin = signedIn("tenant_admin");
    }

    String email(String prefix, String role) {
        String email = Api.email(prefix);
        Api.staff(t, email, new Role(role, role.equals("tenant_admin") ? null : t.headOffice()));
        return email;
    }

    Session signedIn(String role) {
        String email = Api.email(role);
        UUID id = Api.staff(t, email, new Role(role, role.equals("tenant_admin") ? null : t.headOffice()));
        return api.signIn(id, email, Api.PASSWORD, null);
    }

    String status(String email) {
        ResponseEntity<JsonNode> login = api.login(email, Api.PASSWORD);
        assertThat(login.getStatusCode()).as("%s", login.getBody()).isEqualTo(HttpStatus.OK);
        return login.getBody().get("status").asString();
    }

    /** Sends what the screen sends: only the keys given, plus the administrator's own password as the proof a relaxing change needs. */
    ResponseEntity<JsonNode> patch(Map<String, Object> body) {
        Map<String, Object> withProof = new java.util.HashMap<>(body);
        withProof.put("password", Api.PASSWORD);
        return patchRaw(withProof);
    }

    ResponseEntity<JsonNode> patchRaw(Map<String, Object> body) {
        String etag =
                api.get("/api/v1/settings", admin.accessToken()).getHeaders().getETag();
        return api.call(HttpMethod.PATCH, "/api/v1/settings", body, admin.accessToken(), Map.of("If-Match", etag));
    }

    /** The first instant of the day after today plus these days, in Kampala: "until the end of that day". */
    static Instant endOfDayAfter(int days) {
        ZoneId kampala = ZoneId.of("Africa/Kampala");
        return LocalDate.now(kampala).plusDays(days + 1L).atStartOfDay(kampala).toInstant();
    }

    JsonNode settings() {
        return api.get("/api/v1/settings", admin.accessToken()).getBody();
    }

    JsonNode me(String token) {
        return api.get("/api/v1/me", token).getBody();
    }

    void deadlineWas(Instant at) {
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = jsonb_set(settings, '{mfa_enforce_from}',"
                        + " to_jsonb(?::text)) WHERE tenant_id = ?")
                .params(at.toString(), t.tenantId())
                .update();
    }

    String mailboxLink(String email) {
        ResponseEntity<JsonNode> asked = api.call(
                HttpMethod.POST,
                "/api/v1/auth/staff/password/forgot",
                Map.of("login", email),
                null,
                Map.of("X-Forwarded-For", "203.0.113." + (ADDRESS.incrementAndGet() % 250 + 1)));
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
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

    /** FR-IAM-06, issue #348: nothing is required by default, tenant administrators included, on a database made by the migrations. */
    @Test
    void twoStepSignInIsOptionalByDefaultEvenForATenantAdmin() {
        assertThat(TestDatabase.owner()
                        .sql("SELECT mfa_required FROM roles WHERE key = 'tenant_admin'")
                        .query(Boolean.class)
                        .single())
                .as("V67 clears the seed's flag")
                .isFalse();
        JsonNode current = settings();
        assertThat(current.get("require_mfa_all_staff").asBoolean()).isFalse();
        assertThat(current.get("require_mfa_roles")).isEmpty();
        assertThat(current.get("mfa_grace_days").isNull()).isTrue();
        assertThat(current.get("mfa_enforce_from").isNull()).isTrue();

        // The administrator of setUp has a session without ever enrolling, and signs in again the same way.
        assertThat(admin.totpSecret()).isNull();
        JsonNode profile = me(admin.accessToken());
        assertThat(profile.get("mfa_enabled").asBoolean()).isFalse();
        assertThat(profile.get("mfa_required").asBoolean()).isFalse();
        assertThat(profile.get("mfa_due_by").isNull()).isTrue();
        assertThat(profile.get("two_step_policy").asString()).isEqualTo("off");
        assertThat(status(email("cashier", "cashier"))).isEqualTo("signed_in");
    }

    /** FR-IAM-16: for everyone with a grace period, nobody is locked out until the date, then sign-in goes to enrolment. */
    @Test
    void requiringItForEveryoneStartsAGracePeriodAndThenForcesEnrolment() {
        String cashier = Api.email("cashier");
        UUID cashierId = Api.staff(t, cashier, new Role("cashier", t.headOffice()));

        ResponseEntity<JsonNode> on = patch(Map.of("require_mfa_all_staff", true, "mfa_grace_days", 14));

        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody().get("mfa_grace_days").asInt()).isEqualTo(14);
        Instant deadline = Instant.parse(on.getBody().get("mfa_enforce_from").asString());
        // "By 3 Nov" means the whole of 3 Nov in Kampala: the stored instant is the start of the day after.
        assertThat(deadline).isEqualTo(endOfDayAfter(14));
        assertThat(deadline.atZone(ZoneId.of("Africa/Kampala")).toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
        // Inside the grace period a covered user without a factor signs in as before and is told the date.
        Session inGrace = api.signIn(cashierId, cashier, Api.PASSWORD, null);
        JsonNode reminder = me(inGrace.accessToken());
        assertThat(reminder.get("mfa_required").asBoolean()).isFalse();
        assertThat(Instant.parse(reminder.get("mfa_due_by").asString())).isEqualTo(deadline);
        assertThat(reminder.get("two_step_policy").asString()).isEqualTo("everyone");

        deadlineWas(Instant.now().minusSeconds(60));

        assertThat(status(cashier)).isEqualTo("mfa_enrolment_required");
        JsonNode after = me(inGrace.accessToken());
        assertThat(after.get("mfa_required").asBoolean()).isTrue();
        assertThat(after.get("mfa_due_by").isNull()).isTrue();
    }

    /** FR-IAM-16: chosen roles only, at once with no grace, and only staff roles may be chosen. */
    @Test
    void requiringItForChosenRolesCoversOnlyTheirHolders() {
        String cashier = email("cashier", "cashier");
        String officer = email("officer", "loan_officer");

        ResponseEntity<JsonNode> on = patch(Map.of("require_mfa_roles", List.of("cashier", "cashier")));

        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody().get("require_mfa_roles")).hasSize(1);
        assertThat(on.getBody().get("mfa_enforce_from").isNull())
                .as("no grace means at once")
                .isTrue();
        assertThat(status(cashier)).isEqualTo("mfa_enrolment_required");
        assertThat(status(officer)).isEqualTo("signed_in");
        assertThat(me(admin.accessToken()).get("two_step_policy").asString()).isEqualTo("roles");

        for (List<String> bad : List.of(List.of("member"), List.of("no_such_role"), List.of("Cashier"))) {
            ResponseEntity<JsonNode> refused = patch(Map.of("require_mfa_roles", bad));
            assertThat(refused.getStatusCode()).as("%s", bad).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(refused.getBody().get("code").asString()).isEqualTo("validation_failed");
        }
        assertThat(patch(Map.of("mfa_grace_days", 91)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        // An empty list clears the choice.
        assertThat(patch(Map.of("require_mfa_roles", List.of())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(status(cashier)).isEqualTo("signed_in");
    }

    /** ADR-046 decision 3: the deadline moves only when a grace period is sent; narrowing, widening and other saves leave it. */
    @Test
    void theDeadlineMovesOnlyWhenAGracePeriodIsSentAndOffClearsIt() {
        assertThat(patch(Map.of("require_mfa_roles", List.of("cashier"), "mfa_grace_days", 7))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(Instant.parse(settings().get("mfa_enforce_from").asString())).isEqualTo(endOfDayAfter(7));
        Instant settled = Instant.now().minus(Duration.ofDays(3));
        deadlineWas(settled);

        // The same roles, an unrelated change, and widening WITHOUT a grace period leave a passed date where it is.
        assertThat(patch(Map.of("require_mfa_roles", List.of("cashier"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(patch(Map.of("receipt_footer", "Thank you")).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patch(Map.of("require_mfa_roles", List.of("accountant", "cashier")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(Instant.parse(settings().get("mfa_enforce_from").asString())).isEqualTo(settled);

        // Narrowing, as the screen sends it (roles only, no grace), does not re-open a passed deadline either.
        assertThat(patch(Map.of("require_mfa_roles", List.of("accountant"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(Instant.parse(settings().get("mfa_enforce_from").asString())).isEqualTo(settled);

        // An explicit grace period moves it, later or earlier, and 0 means at once.
        assertThat(patch(Map.of("mfa_grace_days", 30)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Instant.parse(settings().get("mfa_enforce_from").asString())).isEqualTo(endOfDayAfter(30));
        assertThat(patch(Map.of("mfa_grace_days", 7)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Instant.parse(settings().get("mfa_enforce_from").asString())).isEqualTo(endOfDayAfter(7));
        assertThat(patch(Map.of("mfa_grace_days", 0)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(settings().get("mfa_enforce_from").isNull()).isTrue();

        // Turning everything off clears it.
        assertThat(patch(Map.of("require_mfa_roles", List.of())).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(settings().get("mfa_enforce_from").isNull()).isTrue();
        assertThat(me(admin.accessToken()).get("two_step_policy").asString()).isEqualTo("off");
    }

    /** ADR-046 decision 6: turning it off or narrowing it needs the proof of the reset of a sign-in, and tells the administrators. */
    @Test
    void relaxingThePolicyNeedsTheAdminsProofAndEmailsTheAdministrators() {
        assertThat(patch(Map.of("require_mfa_all_staff", true, "mfa_grace_days", 14))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Turning it off with no proof, or a wrong password, changes nothing.
        ResponseEntity<JsonNode> none = patchRaw(Map.of("require_mfa_all_staff", false));
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(none.getBody().get("code").asString()).isEqualTo("password_required");
        ResponseEntity<JsonNode> wrong =
                patchRaw(Map.of("require_mfa_all_staff", false, "password", "Not-The-Password-1"));
        assertThat(wrong.getBody().get("code").asString()).isEqualTo("invalid_password");
        assertThat(settings().get("require_mfa_all_staff").asBoolean()).isTrue();
        assertThat(relaxedMails()).isZero();

        // Putting the date back (30 days is later than the 14 set) is a relaxation too: no proof, no change.
        ResponseEntity<JsonNode> postponed = patchRaw(Map.of("mfa_grace_days", 30));
        assertThat(postponed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(postponed.getBody().get("code").asString()).isEqualTo("password_required");
        assertThat(Instant.parse(settings().get("mfa_enforce_from").asString())).isEqualTo(endOfDayAfter(14));
        assertThat(patch(Map.of("mfa_grace_days", 30)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(Instant.parse(settings().get("mfa_enforce_from").asString())).isEqualTo(endOfDayAfter(30));
        // Bringing it forward needs no proof.
        assertThat(patchRaw(Map.of("mfa_grace_days", 7)).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Narrowing from everyone to chosen roles needs it, and so does dropping a role.
        assertThat(patchRaw(Map.of("require_mfa_all_staff", false, "require_mfa_roles", List.of("cashier", "auditor")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(patch(Map.of("require_mfa_all_staff", false, "require_mfa_roles", List.of("cashier", "auditor")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(patchRaw(Map.of("require_mfa_roles", List.of("cashier"))).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(patch(Map.of("require_mfa_roles", List.of("cashier"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(patch(Map.of("require_mfa_roles", List.of())).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // Each relaxation told the administrator, once, with no value in it.
        assertThat(relaxedMails()).isEqualTo(4);
        String params = TestDatabase.owner()
                .sql("SELECT params::text FROM notification_outbox WHERE tenant_id = ? AND template_key = ? LIMIT 1")
                .params(t.tenantId(), "identity.two_step_policy_weakened")
                .query(String.class)
                .single();
        assertThat(params).contains("change").doesNotContain(Api.PASSWORD);
    }

    long relaxedMails() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM notification_outbox WHERE tenant_id = ? AND template_key = ?")
                .params(t.tenantId(), "identity.two_step_policy_weakened")
                .query(Long.class)
                .single();
    }

    /** ADR-046 decision 3: a session with no second factor is not renewed once the person is required to have one. */
    @Test
    void aSessionIsNotRenewedAfterTheDeadlineAndThePersonIsSentToEnrolment() {
        String cashier = Api.email("cashier");
        UUID cashierId = Api.staff(t, cashier, new Role("cashier", t.headOffice()));
        assertThat(patch(Map.of("require_mfa_all_staff", true, "mfa_grace_days", 14))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Session inGrace = api.signIn(cashierId, cashier, Api.PASSWORD, null);
        ResponseEntity<JsonNode> before = api.refresh(inGrace.refreshToken());
        assertThat(before.getBody().get("status").asString()).isEqualTo("signed_in");
        String next = api.refreshCookie(before);

        deadlineWas(Instant.now().minusSeconds(60));

        ResponseEntity<JsonNode> after = api.refresh(next);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
        // No MFA token comes from a refresh cookie: a stolen cookie must not be able to enrol the thief's
        // authenticator.
        assertThat(after.getBody().get("status").asString()).isEqualTo("sign_in_required");
        assertThat(after.getBody().get("mfa_token").isNull()).isTrue();
        assertThat(after.getBody().get("access_token").isNull()).isTrue();
        assertThat(after.getHeaders().get("Set-Cookie")).isNull();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL")
                        .param(cashierId)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT DISTINCT revoked_reason FROM auth_sessions WHERE user_id = ? AND revoked_at IS NOT NULL")
                        .param(cashierId)
                        .query(String.class)
                        .list())
                .contains("two_step_required");
        // The family is gone: the same cookie is not accepted again.
        assertThat(api.refresh(next).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** ADR-046 decision 3: a day's end is the business's own, not Kampala's. */
    @Test
    void theDeadlineIsTheEndOfADayInTheBusinessOwnTimeZone() {
        TestDatabase.Fixture other = TestDatabase.tenant("twostep-tz", true);
        ZoneId zone = ZoneId.of("America/Los_Angeles");
        TestDatabase.owner()
                .sql("UPDATE tenants SET timezone = ? WHERE id = ?")
                .params(zone.getId(), other.tenantId())
                .update();
        Api there = Api.tenant(http, other.slug());
        String email = Api.email("admin");
        UUID id = Api.staff(other, email, new Role("tenant_admin", null));
        Session session = there.signIn(id, email, Api.PASSWORD, null);
        String etag = there.get("/api/v1/settings", session.accessToken())
                .getHeaders()
                .getETag();

        ResponseEntity<JsonNode> on = there.call(
                HttpMethod.PATCH,
                "/api/v1/settings",
                Map.of("require_mfa_all_staff", true, "mfa_grace_days", 14),
                session.accessToken(),
                Map.of("If-Match", etag));

        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        Instant expected = LocalDate.now(zone).plusDays(15).atStartOfDay(zone).toInstant();
        assertThat(Instant.parse(on.getBody().get("mfa_enforce_from").asString()))
                .isEqualTo(expected);
        assertThat(on.getBody().get("timezone").asString()).isEqualTo(zone.getId());
        assertThat(there.get("/api/v1/me", session.accessToken())
                        .getBody()
                        .get("tenant_timezone")
                        .asString())
                .isEqualTo(zone.getId());
    }

    /** Issue #289: /me names the business's currency, so a lending form types money in it before the record has one. */
    @Test
    void meNamesTheBusinessCurrency() {
        TestDatabase.Fixture other = TestDatabase.tenant("me-currency", true);
        TestDatabase.owner()
                .sql("UPDATE tenants SET currency = 'KES' WHERE id = ?")
                .param(other.tenantId())
                .update();
        Api there = Api.tenant(http, other.slug());
        String email = Api.email("officer");
        UUID id = Api.staff(other, email, new Role("loan_officer", null));
        Session session = there.signIn(id, email, Api.PASSWORD, null);

        assertThat(there.get("/api/v1/me", session.accessToken())
                        .getBody()
                        .get("tenant_currency")
                        .asString())
                .isEqualTo("KES");
    }

    /** ADR-042 with ADR-046: a link an administrator hands over still works after the deadline, and leads to enrolment. */
    @Test
    void anAdminResetLinkAfterTheDeadlineLeadsToEnrolmentNotToASession() {
        String cashier = Api.email("cashier");
        UUID cashierId = Api.staff(t, cashier, new Role("cashier", t.headOffice()));
        assertThat(patch(Map.of("require_mfa_all_staff", true, "mfa_grace_days", 14))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        deadlineWas(Instant.now().minusSeconds(60));

        ResponseEntity<JsonNode> issued = api.post(
                "/api/v1/users/" + cashierId + "/reset-sign-in", Map.of("password", Api.PASSWORD), admin.accessToken());
        assertThat(issued.getStatusCode()).as("%s", issued.getBody()).isEqualTo(HttpStatus.OK);
        String url = issued.getBody().get("url").asString();
        ResponseEntity<JsonNode> done = api.call(
                HttpMethod.POST,
                "/api/v1/auth/staff/password/reset",
                Map.of("token", url.substring(url.indexOf("#token=") + 7), "password", "Test-Another-Password-77"),
                null,
                Map.of("X-Forwarded-For", "203.0.113." + (ADDRESS.incrementAndGet() % 250 + 1)));

        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("status").asString()).isEqualTo("mfa_enrolment_required");
        assertThat(done.getBody().get("access_token").isNull()).isTrue();
    }

    /** ADR-046 decision 3: a person given a covered role after the deadline has no grace. */
    @Test
    void aRoleGivenAfterTheDeadlineHasNoGrace() {
        String officer = Api.email("officer");
        UUID officerId = Api.staff(t, officer, new Role("loan_officer", t.headOffice()));
        Session session = api.signIn(officerId, officer, Api.PASSWORD, null);
        assertThat(patch(Map.of("require_mfa_roles", List.of("cashier"), "mfa_grace_days", 14))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        deadlineWas(Instant.now().minusSeconds(60));
        assertThat(api.refresh(session.refreshToken()).getBody().get("status").asString())
                .as("not covered yet")
                .isEqualTo("signed_in");

        TestDatabase.owner()
                .sql("""
                        INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, branch_id, granted_by)
                        VALUES (?, ?, ?, 'cashier', ?, ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), officerId, t.headOffice(), officerId)
                .update();

        assertThat(status(officer)).isEqualTo("mfa_enrolment_required");
    }

    /** ADR-046 decision 7: starting your own set-up from a session asks for the password first. */
    @Test
    void ownSetUpFromASessionAsksForThePassword() {
        ResponseEntity<JsonNode> none = api.post("/api/v1/auth/staff/mfa/enrol", Map.of(), admin.accessToken());
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(none.getBody().get("code").asString()).isEqualTo("password_required");
        ResponseEntity<JsonNode> wrong =
                api.post("/api/v1/auth/staff/mfa/enrol", Map.of("password", "Not-The-Password-1"), admin.accessToken());
        assertThat(wrong.getBody().get("code").asString()).isEqualTo("invalid_password");
        ResponseEntity<JsonNode> ok =
                api.post("/api/v1/auth/staff/mfa/enrol", Map.of("password", Api.PASSWORD), admin.accessToken());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("secret").asString()).isNotBlank();
    }

    /** The change is audited with before and after like every settings change, and only a settings manager may make it. */
    @Test
    void theChangeIsAuditedAndOnlyASettingsManagerMakesIt() {
        assertThat(patch(Map.of("require_mfa_roles", List.of("cashier"), "mfa_grace_days", 5))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String data = TestDatabase.owner()
                .sql("SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'core.settings.updated'")
                .param(t.tenantId())
                .query(String.class)
                .single();
        assertThat(data)
                .contains("require_mfa_roles")
                .contains("mfa_grace_days")
                .contains("mfa_enforce_from");

        Session auditor = signedIn("auditor");
        ResponseEntity<JsonNode> refused = api.call(
                HttpMethod.PATCH,
                "/api/v1/settings",
                Map.of("require_mfa_roles", List.of()),
                auditor.accessToken(),
                Map.of("If-Match", "\"1\""));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** ADR-042 decision 7 with ADR-046 decision 4: the mailbox reset works inside the grace period and is refused once enforced. */
    @Test
    void theMailboxResetWorksDuringTheGracePeriodAndIsRefusedOnceEnforced() {
        String cashier = email("cashier", "cashier");
        assertThat(patch(Map.of("require_mfa_all_staff", true, "mfa_grace_days", 10))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(mailboxLink(cashier)).as("inside the grace period").isNotNull();

        deadlineWas(Instant.now().minusSeconds(60));
        String other = email("cashier2", "cashier");

        assertThat(mailboxLink(other))
                .as("once enforced, a user with no factor is helped by an administrator")
                .isNull();
    }

    /** ADR-042 decision 9(c): the admin reset of a sign-in still takes the password of an admin with no authenticator. */
    @Test
    void anAdminWithNoSecondFactorStillResetsAColleaguesSignInWithTheirPassword() {
        String cashier = Api.email("cashier");
        UUID target = Api.staff(t, cashier, new Role("cashier", t.headOffice()));

        ResponseEntity<JsonNode> without =
                api.post("/api/v1/users/" + target + "/reset-sign-in", Map.of(), admin.accessToken());
        assertThat(without.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(without.getBody().get("code").asString()).isEqualTo("password_required");

        ResponseEntity<JsonNode> issued = api.post(
                "/api/v1/users/" + target + "/reset-sign-in", Map.of("password", Api.PASSWORD), admin.accessToken());
        assertThat(issued.getStatusCode()).as("%s", issued.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(issued.getBody().get("url").asString()).contains("/reset-password#token=");
    }
}
