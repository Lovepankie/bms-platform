package com.rincoltech.bms.core.referrals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Referral links and commissions (issue #464, ADR-069): every business has a unique code and a link; the sign-up takes a
 * code, refuses one nobody has, and Activate makes it the business's referral; a business never refers itself; the operator
 * corrects a referrer with a reason, sets the rule (the default and an override), and the payment the operator records adds
 * the commission line worked out from the rule in force, which the operator marks paid or reverses, every change audited;
 * the owner and administrators see only their own referrals. Fabricated businesses, people and round figures only.
 */
class ReferralsIT extends IntegrationTest {

    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");
    private static final AtomicInteger ADDRESS = new AtomicInteger(1);

    @Autowired
    TestRestTemplate http;

    Api platform;
    Session operator;
    UUID operatorId;
    String ip;

    /** The platform default rule before the test; restored after it, so no test leaks its default into another. */
    UUID defaultRuleBefore;

    @BeforeEach
    void setUp() {
        defaultRuleBefore = (UUID) TestDatabase.owner()
                .sql("SELECT default_rule_id FROM referral_settings")
                .query()
                .singleRow()
                .get("default_rule_id");
        platform = Api.platform(http);
        String email = Api.email("ref-operator");
        operatorId = Api.platformUser(email);
        operator = platform.signIn(operatorId, email, Api.PASSWORD, null);
        int n = ADDRESS.getAndIncrement();
        ip = "203.0." + (n / 250) + "." + (n % 250 + 1);
    }

    @AfterEach
    void restoreTheDefaultRule() {
        TestDatabase.owner()
                .sql("UPDATE referral_settings SET default_rule_id = ?")
                .param(defaultRuleBefore)
                .update();
    }

    // ---- Helpers ----------------------------------------------------------------------------

    record Business(UUID id, String slug, String name) {}

    /** A business made through the platform API, so it has a subscription to pay. */
    Business business(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        String name = "Test Shop " + slug;
        ResponseEntity<JsonNode> created = platform.post(
                "/api/v1/platform/tenants",
                Map.of(
                        "name",
                        name,
                        "slug",
                        slug,
                        "plan_code",
                        "growth",
                        "modules",
                        List.of("retail"),
                        "head_office",
                        Map.of("code", "HQ", "name", "Head Office"),
                        "admin",
                        Map.of("full_name", "Test Owner 11", "email", Api.email("ref-owner"))),
                operator.accessToken());
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        return new Business(
                UUID.fromString(created.getBody().get("tenant").get("id").asString()), slug, name);
    }

    static String code(UUID tenantId) {
        return TestDatabase.owner()
                .sql("SELECT code FROM referral_codes WHERE tenant_id = ?")
                .param(tenantId)
                .query(String.class)
                .single();
    }

    /** A signed-in member of staff of a business with one role. */
    String staffToken(TestDatabase.Fixture t, String role) {
        String email = Api.email("ref-" + role.replace('_', '-'));
        UUID id = Api.staff(t, email, new Api.Role(role, role.equals("tenant_admin") ? null : t.headOffice()));
        return Api.tenant(http, t.slug()).signIn(id, email, Api.PASSWORD, null).accessToken();
    }

    ResponseEntity<JsonNode> mine(TestDatabase.Fixture t, String token) {
        return Api.tenant(http, t.slug()).get("/api/v1/referrals", token);
    }

    ResponseEntity<JsonNode> setReferrer(UUID tenantId, String code, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referrer_code", code);
        body.put("reason", reason);
        body.put("totp_code", totp());
        return platform.call(
                HttpMethod.PUT, "/api/v1/platform/tenants/" + tenantId + "/referrer", body, operator.accessToken());
    }

    ResponseEntity<JsonNode> pay(UUID tenantId, long amount, int months) {
        ResponseEntity<JsonNode> paid = pay(tenantId, amount, months, "ref-pay-" + UUID.randomUUID());
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        return paid;
    }

    ResponseEntity<JsonNode> pay(UUID tenantId, long amount, int months, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", amount);
        body.put("method", "mtn_momo");
        body.put("reference", "TEST-REF-" + idempotencyKey.substring(idempotencyKey.length() - 6));
        body.put("paid_on", LocalDate.now(KAMPALA).toString());
        body.put("months", months);
        body.put("reason", "Test: seen on the statement");
        body.put("confirm_unusual_amount", true);
        return platform.call(
                HttpMethod.POST,
                "/api/v1/platform/tenants/" + tenantId + "/payments",
                body,
                operator.accessToken(),
                Map.of("Idempotency-Key", idempotencyKey));
    }

    ResponseEntity<JsonNode> rule(String path, Map<String, Object> rule, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rule", rule);
        body.put("reason", reason);
        body.put("totp_code", totp());
        return platform.call(HttpMethod.PUT, path, body, operator.accessToken());
    }

    /** A fresh code from the operator's authenticator; the same time step may be used again in a test. */
    String totp() {
        Api.allowTotpReuse(operatorId);
        return Api.totp(operator.totpSecret(), Instant.now());
    }

    ResponseEntity<JsonNode> addLine(UUID referralId, long amount, String reason, String idempotencyKey) {
        return platform.call(
                HttpMethod.POST,
                "/api/v1/platform/referrals/" + referralId + "/commissions",
                Map.of("amount_minor", amount, "currency", "UGX", "reason", reason),
                operator.accessToken(),
                idempotencyKey == null ? Map.of() : Map.of("Idempotency-Key", idempotencyKey));
    }

    JsonNode detail(UUID referralId) {
        ResponseEntity<JsonNode> got = platform.get("/api/v1/platform/referrals/" + referralId, operator.accessToken());
        assertThat(got.getStatusCode()).as("%s", got.getBody()).isEqualTo(HttpStatus.OK);
        return got.getBody();
    }

    static List<Long> amounts(JsonNode detail) {
        List<Long> out = new ArrayList<>();
        detail.get("lines").forEach(l -> out.add(l.get("amount_minor").asLong()));
        return out;
    }

    long audits(String action) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM platform_audit_log WHERE action = ? AND platform_user_id = ?")
                .params(action, operatorId)
                .query(Long.class)
                .single();
    }

    UUID currentReferral(UUID referred) {
        return TestDatabase.owner()
                .sql("SELECT id FROM tenant_referrals WHERE referred_tenant_id = ? AND ended_at IS NULL")
                .param(referred)
                .query(UUID.class)
                .single();
    }

    Map<String, Object> signUpForm(String email, String business, String referralCode) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("business_name", business);
        form.put("contact_name", "Test Applicant 21");
        form.put("contact_email", email);
        form.put("contact_phone", "+2567000002" + String.format("%02d", ADDRESS.get() % 100));
        form.put("country", "UG");
        form.put("modules", List.of("retail"));
        form.put("term", "monthly");
        form.put("way_in", "trial");
        if (referralCode != null) {
            form.put("referral_code", referralCode);
        }
        return form;
    }

    ResponseEntity<JsonNode> apply(Map<String, Object> form) {
        return platform.call(
                HttpMethod.POST, "/api/v1/platform/sign-up/applications", form, null, Map.of("X-Forwarded-For", ip));
    }

    UUID application(String email) {
        return TestDatabase.owner()
                .sql("SELECT id FROM onboarding_applications WHERE contact_email = ?")
                .param(email)
                .query(UUID.class)
                .single();
    }

    /** Confirms the applicant's email straight in the database (the link itself is covered by OnboardingIT). */
    static void confirmEmail(UUID applicationId) {
        TestDatabase.owner()
                .sql("UPDATE onboarding_applications SET email_verified_at = now() WHERE id = ?")
                .param(applicationId)
                .update();
    }

    // ---- Codes ------------------------------------------------------------------------------

    /** Every business gets a code at creation: letters of its slug and characters read out on the phone, all different. */
    @Test
    void everyBusinessHasItsOwnCodeAndLink() {
        Set<String> codes = new HashSet<>();
        List<TestDatabase.Fixture> made = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            TestDatabase.Fixture t = TestDatabase.tenant("dodos", false);
            made.add(t);
            String code = code(t.tenantId());
            assertThat(code).matches("^DODOS[2-9A-HJ-NP-Z]{2}$");
            codes.add(code);
        }
        assertThat(codes).hasSize(40);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) = count(DISTINCT code) FROM referral_codes")
                        .query(Boolean.class)
                        .single())
                .isTrue();
        // A slug with too few letters gives eight random characters; after ten taken tries, four follow the letters.
        String alphabet = "[2-9A-HJ-NP-Z]";
        assertThat(candidate("x9-42", 0)).matches("^" + alphabet + "{8}$");
        assertThat(candidate("dodos-shop", 0)).matches("^DODOS" + alphabet + "{2}$");
        assertThat(candidate("dodos-shop", 10)).matches("^DODOS" + alphabet + "{4}$");
        assertThat(candidate("dodos-shop", 20)).matches("^" + alphabet + "{8}$");

        TestDatabase.Fixture t = made.getFirst();
        ResponseEntity<JsonNode> own = mine(t, staffToken(t, "tenant_admin"));
        assertThat(own.getStatusCode()).as("%s", own.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(own.getBody().get("code").asString()).isEqualTo(code(t.tenantId()));
        assertThat(own.getBody().get("link").asString()).endsWith("/sign-up?ref=" + code(t.tenantId()));
        assertThat(own.getBody().get("items")).isEmpty();
    }

    static String candidate(String slug, int attempt) {
        return TestDatabase.owner()
                .sql("SELECT bms_referral_code_candidate(?, ?)")
                .params(slug, attempt)
                .query(String.class)
                .single();
    }

    // ---- Sign-up and Activate -----------------------------------------------------------------

    /**
     * The sign-up with a code (any case, spaces at the ends) records the referrer, without one records none, and with a code
     * nobody has is refused in plain words and creates nothing. The open application shows to the referrer as applied, and
     * Activate turns it into the business's referral, which stays after the application is closed.
     */
    @Test
    void theSignUpTakesACodeAndActivateMakesTheReferral() {
        TestDatabase.Fixture referrer = TestDatabase.tenant("refsrc", false);
        String code = code(referrer.tenantId());

        String bad = Api.email("ref-bad");
        ResponseEntity<JsonNode> refused = apply(signUpForm(bad, "Test Bad Code Shop", "NOPE99ZZ"));
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("referral_code_unknown");
        assertThat(refused.getBody().toString()).contains("referral_code");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM onboarding_applications WHERE contact_email = ?")
                        .param(bad)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(apply(signUpForm(Api.email("ref-bad2"), "Test Bad Shop", "bad code!"))
                        .getStatusCode()
                        .value())
                .isEqualTo(422);

        String none = Api.email("ref-none");
        assertThat(apply(signUpForm(none, "Test Plain Shop", null)).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(TestDatabase.owner()
                        .sql("SELECT referrer_tenant_id FROM onboarding_applications WHERE contact_email = ?")
                        .param(none)
                        .query(UUID.class)
                        .optional())
                .isEmpty();

        String email = Api.email("ref-with");
        ResponseEntity<JsonNode> accepted =
                apply(signUpForm(email, "Test Referred Shop", " " + code.toLowerCase() + " "));
        assertThat(accepted.getStatusCode()).as("%s", accepted.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        UUID id = application(email);
        Map<String, Object> row = TestDatabase.owner()
                .sql("SELECT referral_code, referrer_tenant_id FROM onboarding_applications WHERE id = ?")
                .param(id)
                .query()
                .singleRow();
        assertThat(row.get("referral_code")).isEqualTo(code);
        assertThat(row.get("referrer_tenant_id")).isEqualTo(referrer.tenantId());

        String admin = staffToken(referrer, "tenant_admin");
        // Not shown before the applicant confirms the email: an unconfirmed application is nobody's yet.
        assertThat(mine(referrer, admin).getBody().get("items")).isEmpty();
        confirmEmail(id);
        JsonNode applied = mine(referrer, admin).getBody();
        assertThat(applied.get("items")).hasSize(1);
        assertThat(applied.get("items").get(0).get("referred_name").asString()).isEqualTo("Test Referred Shop");
        assertThat(applied.get("items").get(0).get("status").asString()).isEqualTo("applied");

        assertThat(platform.post("/api/v1/platform/applications/" + id + "/verify", Map.of(), operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode page = platform.get("/api/v1/platform/applications/" + id, operator.accessToken())
                .getBody();
        assertThat(page.get("referred_by").get("code").asString()).isEqualTo(code);
        assertThat(page.get("referred_by").get("tenant_id").asString())
                .isEqualTo(referrer.tenantId().toString());

        String slug = "ref-act-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> activation = new LinkedHashMap<>();
        activation.put("modules", List.of("retail"));
        activation.put("term", "monthly");
        activation.put("way_in", "trial");
        activation.put("slug", slug);
        ResponseEntity<JsonNode> activated =
                platform.post("/api/v1/platform/applications/" + id + "/activate", activation, operator.accessToken());
        assertThat(activated.getStatusCode()).as("%s", activated.getBody()).isEqualTo(HttpStatus.OK);
        UUID tenant = UUID.fromString(activated.getBody().get("tenant_id").asString());
        Map<String, Object> referral = TestDatabase.owner()
                .sql(
                        "SELECT tenant_id, code, source, application_id FROM tenant_referrals WHERE referred_tenant_id = ?")
                .param(tenant)
                .query()
                .singleRow();
        assertThat(referral.get("tenant_id")).isEqualTo(referrer.tenantId());
        assertThat(referral.get("code")).isEqualTo(code);
        assertThat(referral.get("source")).isEqualTo("sign_up");
        assertThat(referral.get("application_id")).isEqualTo(id);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM platform_audit_log WHERE action = 'platform.referral.recorded'"
                                + " AND tenant_id = ?")
                        .param(tenant)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        JsonNode active = mine(referrer, admin).getBody();
        assertThat(active.get("items")).hasSize(1);
        assertThat(active.get("items").get(0).get("status").asString()).isEqualTo("active");
        // A second Activate does nothing, and makes no second referral.
        platform.post("/api/v1/platform/applications/" + id + "/activate", activation, operator.accessToken());
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM tenant_referrals WHERE referred_tenant_id = ?")
                        .param(tenant)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /**
     * The Owner or an administrator of a business cannot bring its code: when their email (compared as the sign-up compares
     * it) or phone matches, the sign-up drops the code quietly and answers exactly as for anyone else, so the public form
     * never tells whose email or phone this is (FR-ONB-03); it is checked again at Activate, where no referral is stored and
     * the refusal is audited (decision 10).
     */
    @Test
    void theBusinesssOwnPeopleCannotUseItsCode() {
        TestDatabase.Fixture referrer = TestDatabase.tenant("refself", false);
        String code = code(referrer.tenantId());
        String adminEmail = Api.email("ref-self-admin");
        UUID admin = Api.staff(referrer, adminEmail, new Api.Role("tenant_admin", null));

        ResponseEntity<JsonNode> stranger = apply(signUpForm(Api.email("ref-self-stranger"), "Test Other Shop", code));
        assertThat(stranger.getStatusCode()).as("%s", stranger.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<JsonNode> byEmail =
                apply(signUpForm(adminEmail.toUpperCase(Locale.ROOT), "Test Own Shop", code));
        assertThat(byEmail.getStatusCode()).isEqualTo(stranger.getStatusCode());
        assertThat(byEmail.getBody()).isEqualTo(stranger.getBody());
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM onboarding_applications"
                                + " WHERE lower(contact_email) = lower(?) AND referrer_tenant_id IS NULL"
                                + " AND referral_code IS NULL")
                        .param(adminEmail)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        String phone = "+256700000299";
        TestDatabase.owner()
                .sql("UPDATE users SET phone_e164 = ? WHERE id = ?")
                .params(phone, admin)
                .update();
        String phoneEmail = Api.email("ref-self-phone");
        Map<String, Object> byPhone = signUpForm(phoneEmail, "Test Own Phone Shop", code);
        byPhone.put("contact_phone", phone);
        ResponseEntity<JsonNode> quiet = apply(byPhone);
        assertThat(quiet.getStatusCode()).isEqualTo(stranger.getStatusCode());
        assertThat(quiet.getBody()).isEqualTo(stranger.getBody());
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM onboarding_applications"
                                + " WHERE contact_email = ? AND referrer_tenant_id IS NULL")
                        .param(phoneEmail)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        // Accepted at the sign-up, then the applicant becomes an administrator of the referrer before Activate.
        String later = Api.email("ref-self-later");
        assertThat(apply(signUpForm(later, "Test Later Shop", code)).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        UUID id = application(later);
        confirmEmail(id);
        Api.staff(referrer, later, new Api.Role("tenant_admin", null));
        assertThat(platform.post("/api/v1/platform/applications/" + id + "/verify", Map.of(), operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> activation = new LinkedHashMap<>();
        activation.put("modules", List.of("retail"));
        activation.put("term", "monthly");
        activation.put("way_in", "trial");
        activation.put("slug", "ref-self-" + UUID.randomUUID().toString().substring(0, 8));
        ResponseEntity<JsonNode> activated =
                platform.post("/api/v1/platform/applications/" + id + "/activate", activation, operator.accessToken());
        assertThat(activated.getStatusCode()).as("%s", activated.getBody()).isEqualTo(HttpStatus.OK);
        UUID tenant = UUID.fromString(activated.getBody().get("tenant_id").asString());
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM tenant_referrals WHERE referred_tenant_id = ?")
                        .param(tenant)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(TestDatabase.owner()
                        .sql("SELECT data ->> 'referrer_tenant_id' FROM platform_audit_log"
                                + " WHERE action = 'platform.referral.refused_self' AND tenant_id = ?")
                        .param(tenant)
                        .query(String.class)
                        .single())
                .isEqualTo(referrer.tenantId().toString());
    }

    // ---- The operator ---------------------------------------------------------------------------

    /**
     * A business never refers itself; the operator sets, corrects and clears the referrer with a reason, each change audited
     * with before and after, the same referrer again changes nothing, and the old referral ends instead of being changed.
     */
    @Test
    void theOperatorCorrectsTheReferrerWithAReasonButNeverToTheBusinessItself() {
        Business x = business("ref-fix");
        TestDatabase.Fixture a = TestDatabase.tenant("refa", false);
        TestDatabase.Fixture b = TestDatabase.tenant("refb", false);

        ResponseEntity<JsonNode> self = setReferrer(x.id(), code(x.id()), "Test: own code typed");
        assertThat(self.getStatusCode().value()).isEqualTo(422);
        assertThat(self.getBody().get("code").asString()).isEqualTo("referral_self");
        assertThatDatabaseRefusesSelf(x.id());
        assertThat(setReferrer(x.id(), "NOPE99ZZ", "Test: unknown code")
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("referral_code_unknown");
        assertThat(setReferrer(x.id(), code(a.tenantId()), "x").getStatusCode().value())
                .isEqualTo(422);

        long before = audits("platform.referral.referrer_changed");
        ResponseEntity<JsonNode> set = setReferrer(x.id(), code(a.tenantId()), "Test: told us on the phone");
        assertThat(set.getStatusCode()).as("%s", set.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(set.getBody().get("referral").get("referrer_tenant_id").asString())
                .isEqualTo(a.tenantId().toString());
        assertThat(set.getBody().get("referral").get("source").asString()).isEqualTo("operator");
        UUID first = currentReferral(x.id());

        ResponseEntity<JsonNode> again = setReferrer(x.id(), code(a.tenantId()).toLowerCase(), "Test: same again");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audits("platform.referral.referrer_changed")).isEqualTo(before + 1);

        ResponseEntity<JsonNode> corrected = setReferrer(x.id(), code(b.tenantId()), "Test: it was the other shop");
        assertThat(corrected.getBody().get("referral").get("referrer_tenant_id").asString())
                .isEqualTo(b.tenantId().toString());
        assertThat(audits("platform.referral.referrer_changed")).isEqualTo(before + 2);
        Map<String, Object> audit =
                TestDatabase.owner().sql("""
                        SELECT data ->> 'before' AS before, data ->> 'after' AS after, data ->> 'reason' AS reason
                          FROM platform_audit_log WHERE action = 'platform.referral.referrer_changed' AND tenant_id = ?
                         ORDER BY occurred_at DESC LIMIT 1
                        """).param(x.id()).query().singleRow();
        assertThat(audit.get("before")).isEqualTo(a.tenantId().toString());
        assertThat(audit.get("after")).isEqualTo(b.tenantId().toString());
        assertThat(audit.get("reason")).isEqualTo("Test: it was the other shop");
        assertThat(TestDatabase.owner()
                        .sql("SELECT ended_at IS NOT NULL FROM tenant_referrals WHERE id = ?")
                        .param(first)
                        .query(Boolean.class)
                        .single())
                .isTrue();

        ResponseEntity<JsonNode> cleared = setReferrer(x.id(), null, "Test: nobody referred them");
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody().get("referral").isNull()).isTrue();
        assertThat(audits("platform.referral.referrer_changed")).isEqualTo(before + 3);
    }

    /** The table holds the rule too: no row where a business refers itself, even written by the owner role. */
    static void assertThatDatabaseRefusesSelf(UUID tenantId) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        TestDatabase.owner().sql("""
                                INSERT INTO tenant_referrals (id, tenant_id, referred_tenant_id, code, source)
                                VALUES (gen_random_uuid(), ?, ?, 'SELF22', 'operator')
                                """).params(tenantId, tenantId).update())
                .hasStackTraceContaining("check constraint");
    }

    /**
     * No rule, no line; the default rule set by the operator applies to payments from then on; an override on one referral
     * wins over it; each payment's line keeps the rule it used; a line is marked paid once and reversed once, never
     * deleted; a typed line can be added; every change is audited, and the owner's totals follow.
     */
    @Test
    void commissionsFollowTheRuleInForceAndAreMarkedPaidOrReversedWithAnAudit() {
        TestDatabase.Fixture a = TestDatabase.tenant("refpay", false);
        Business x = business("ref-pay-x");
        Business y = business("ref-pay-y");
        assertThat(setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(setReferrer(y.id(), code(a.tenantId()), "Test: referred by the shop")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        UUID rx = currentReferral(x.id());
        UUID ry = currentReferral(y.id());

        // Whatever an earlier test left as the default, start with none: nothing is worked out without a rule.
        assertThat(rule("/api/v1/platform/referrals/default-rule", null, "Test: no rule yet")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        pay(x.id(), 10000, 1);
        assertThat(detail(rx).get("lines")).isEmpty();
        assertThat(detail(rx).get("rule_source").asString()).isEqualTo("none");

        Map<String, Object> percent = new LinkedHashMap<>();
        percent.put("kind", "percent_of_payment");
        percent.put("percent_bp", 1000);
        percent.put("months", 2);
        ResponseEntity<JsonNode> badRule = rule(
                "/api/v1/platform/referrals/default-rule",
                Map.of("kind", "fixed_per_month", "percent_bp", 1000),
                "Test: wrong shape");
        assertThat(badRule.getStatusCode().value()).isEqualTo(422);
        ResponseEntity<JsonNode> set =
                rule("/api/v1/platform/referrals/default-rule", percent, "Test: the agreed share");
        assertThat(set.getStatusCode()).as("%s", set.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(set.getBody().get("rule").get("percent_bp").asInt()).isEqualTo(1000);

        // The rule covers the first two paid months: the first was paid before the rule and earns nothing, the second
        // earns ten percent, 1,000; after that the two months are used, so a three month payment earns nothing.
        pay(x.id(), 10000, 1);
        pay(x.id(), 30000, 3);
        assertThat(amounts(detail(rx))).containsExactly(1000L);
        JsonNode xDetail = detail(rx);
        assertThat(xDetail.get("rule_source").asString()).isEqualTo("default");
        assertThat(xDetail.get("lines")
                        .get(0)
                        .get("rule_snapshot")
                        .get("percent_bp")
                        .asInt())
                .isEqualTo(1000);
        assertThat(xDetail.get("lines")
                        .get(0)
                        .get("rule_snapshot")
                        .get("source")
                        .asString())
                .isEqualTo("default");

        // An override on Y: a fixed amount per paid month, no end.
        Map<String, Object> perMonth = new LinkedHashMap<>();
        perMonth.put("kind", "fixed_per_month");
        perMonth.put("amount_minor", 500);
        perMonth.put("currency", "UGX");
        ResponseEntity<JsonNode> override =
                rule("/api/v1/platform/referrals/" + ry + "/rule", perMonth, "Test: a deal for this shop");
        assertThat(override.getStatusCode()).as("%s", override.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(override.getBody().get("rule_source").asString()).isEqualTo("override");
        pay(y.id(), 30000, 3);
        JsonNode yDetail = detail(ry);
        assertThat(amounts(yDetail)).containsExactly(1500L);
        assertThat(yDetail.get("lines").get(0).get("months_counted").asInt()).isEqualTo(3);
        assertThat(yDetail.get("lines")
                        .get(0)
                        .get("rule_snapshot")
                        .get("source")
                        .asString())
                .isEqualTo("override");
        UUID line = UUID.fromString(yDetail.get("lines").get(0).get("id").asString());

        // Marked paid once, with a reason; the second time is refused.
        Map<String, Object> paid = new LinkedHashMap<>();
        paid.put("paid_on", LocalDate.now(KAMPALA).toString());
        paid.put("method", "mtn_momo");
        paid.put("reference", "TEST-PAYOUT-01");
        paid.put("reason", "Test: sent by mobile money");
        ResponseEntity<JsonNode> marked =
                platform.post("/api/v1/platform/referrals/commissions/" + line + "/paid", paid, operator.accessToken());
        assertThat(marked.getStatusCode()).as("%s", marked.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(marked.getBody().get("lines").get(0).get("paid_on").asString())
                .isEqualTo(LocalDate.now(KAMPALA).toString());
        assertThat(platform.post(
                                "/api/v1/platform/referrals/commissions/" + line + "/paid",
                                paid,
                                operator.accessToken())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("commission_already_paid");
        Map<String, Object> future = new LinkedHashMap<>(paid);
        future.put("paid_on", LocalDate.now(KAMPALA).plusDays(3).toString());
        UUID xLine = UUID.fromString(detail(rx).get("lines").get(0).get("id").asString());
        assertThat(platform.post(
                                "/api/v1/platform/referrals/commissions/" + xLine + "/paid",
                                future,
                                operator.accessToken())
                        .getStatusCode()
                        .value())
                .isEqualTo(422);

        // A typed line, then a reversal: never deleted, the opposite amount, once, and a reversal is not reversed.
        ResponseEntity<JsonNode> typed =
                addLine(ry, 700, "Test: a bonus agreed by phone", "ref-line-" + UUID.randomUUID());
        assertThat(typed.getStatusCode()).as("%s", typed.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(amounts(typed.getBody())).containsExactly(1500L, 700L);
        ResponseEntity<JsonNode> reversed = platform.post(
                "/api/v1/platform/referrals/commissions/" + line + "/reverse",
                Map.of("reason", "Test: the payment bounced"),
                operator.accessToken());
        assertThat(reversed.getStatusCode()).as("%s", reversed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(amounts(reversed.getBody())).containsExactly(1500L, 700L, -1500L);
        assertThat(platform.post(
                                "/api/v1/platform/referrals/commissions/" + line + "/reverse",
                                Map.of("reason", "Test: twice"),
                                operator.accessToken())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("commission_line_already_reversed");
        UUID reversal =
                UUID.fromString(reversed.getBody().get("lines").get(2).get("id").asString());
        assertThat(platform.post(
                                "/api/v1/platform/referrals/commissions/" + reversal + "/reverse",
                                Map.of("reason", "Test: reverse the reversal"),
                                operator.accessToken())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("commission_line_is_reversal");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("DELETE FROM referral_commission_lines WHERE id = ?")
                        .param(line)
                        .update())
                .hasStackTraceContaining("never deleted");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE referral_commission_lines SET amount_minor = 1 WHERE id = ?")
                        .param(line)
                        .update())
                .hasStackTraceContaining("only marked paid");

        // The owner's view: both businesses, their status, and the money earned, paid and due.
        String admin = staffToken(a, "tenant_admin");
        JsonNode own = mine(a, admin).getBody();
        assertThat(own.get("items")).hasSize(2);
        own.get("items").forEach(i -> assertThat(i.get("status").asString()).isEqualTo("paying"));
        JsonNode total = own.get("totals").get(0);
        assertThat(total.get("currency").asString()).isEqualTo("UGX");
        assertThat(total.get("earned_minor").asLong()).isEqualTo(1000 + 1500 + 700 - 1500);
        assertThat(total.get("paid_minor").asLong()).isEqualTo(1500);
        assertThat(total.get("due_minor").asLong()).isEqualTo(1000 + 700 - 1500);

        assertThat(audits("platform.referral.default_rule_changed")).isEqualTo(2);
        assertThat(audits("platform.referral.rule_changed")).isEqualTo(1);
        assertThat(audits("platform.referral.commission_added")).isEqualTo(3);
        assertThat(audits("platform.referral.commission_paid")).isEqualTo(1);
        assertThat(audits("platform.referral.commission_reversed")).isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT data ->> 'reason' FROM platform_audit_log
                                 WHERE action = 'platform.referral.commission_paid' AND platform_user_id = ?
                                """)
                        .param(operatorId)
                        .query(String.class)
                        .single())
                .isEqualTo("Test: sent by mobile money");
    }

    /** A first payment rule pays once, on the referred business's first payment only; a cap stops a percent rule. */
    @Test
    void aFirstPaymentRulePaysOnceAndACapStopsAPercentRule() {
        TestDatabase.Fixture a = TestDatabase.tenant("refonce", false);
        Business x = business("ref-once");
        Business z = business("ref-cap");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        setReferrer(z.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        UUID rz = currentReferral(z.id());
        Map<String, Object> once = new LinkedHashMap<>();
        once.put("kind", "fixed_first_payment");
        once.put("amount_minor", 2000);
        once.put("currency", "UGX");
        assertThat(rule("/api/v1/platform/referrals/" + rx + "/rule", once, "Test: one-off deal")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        pay(x.id(), 10000, 1);
        pay(x.id(), 10000, 1);
        assertThat(amounts(detail(rx))).containsExactly(2000L);

        Map<String, Object> capped = new LinkedHashMap<>();
        capped.put("kind", "percent_of_payment");
        capped.put("percent_bp", 5000);
        capped.put("currency", "UGX");
        capped.put("cap_minor", 7000);
        assertThat(rule("/api/v1/platform/referrals/" + rz + "/rule", capped, "Test: half, at most 7,000")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        pay(z.id(), 10000, 1);
        pay(z.id(), 10000, 1);
        pay(z.id(), 10000, 1);
        assertThat(amounts(detail(rz))).containsExactly(5000L, 2000L);
    }

    // ---- Who sees what --------------------------------------------------------------------------

    /**
     * The owner and administrators of a business see only the referrals it made, with the referred business's name and
     * nothing else of it; a role without the permission is refused; the operator routes refuse tenant staff and anonymous
     * callers.
     */
    @Test
    void aBusinessSeesOnlyItsOwnReferrals() {
        TestDatabase.Fixture a = TestDatabase.tenant("refown-a", false);
        TestDatabase.Fixture b = TestDatabase.tenant("refown-b", false);
        Business x = business("ref-own-x");
        Business y = business("ref-own-y");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by A");
        setReferrer(y.id(), code(b.tenantId()), "Test: referred by B");

        JsonNode ofA = mine(a, staffToken(a, "tenant_admin")).getBody();
        assertThat(ofA.get("items")).hasSize(1);
        assertThat(ofA.get("items").get(0).get("referred_name").asString()).isEqualTo(x.name());
        assertThat(ofA.toString()).doesNotContain(y.name()).doesNotContain(x.id().toString());
        JsonNode ofB = mine(b, staffToken(b, "tenant_admin")).getBody();
        assertThat(ofB.get("items")).hasSize(1);
        assertThat(ofB.get("items").get(0).get("referred_name").asString()).isEqualTo(y.name());
        assertThat(ofB.get("code").asString()).isEqualTo(code(b.tenantId()));

        assertThat(mine(a, staffToken(a, "cashier")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(mine(a, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String staff = staffToken(a, "tenant_admin");
        for (String path : List.of(
                "/api/v1/platform/referrals",
                "/api/v1/platform/referrals/default-rule",
                "/api/v1/platform/referrals/export")) {
            for (String token : new String[] {null, staff}) {
                assertThat(platform.get(path, token).getStatusCode())
                        .as("%s with %s", path, token == null ? "no token" : "staff")
                        .isEqualTo(HttpStatus.UNAUTHORIZED);
            }
        }
        assertThat(platform.call(
                                HttpMethod.PUT,
                                "/api/v1/platform/tenants/" + x.id() + "/referrer",
                                Map.of("referrer_code", code(b.tenantId()), "reason", "Test: staff try"),
                                staff)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** The operator lists referrals across the platform by referrer, status and commission, and exports them as CSV. */
    @Test
    void theOperatorListsWithFiltersAndExportsCsv() {
        TestDatabase.Fixture a = TestDatabase.tenant("reflist", false);
        rule("/api/v1/platform/referrals/default-rule", null, "Test: no default rule");
        Business x = business("ref-list-x");
        Business y = business("ref-list-y");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        setReferrer(y.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID ry = currentReferral(y.id());
        addLine(ry, 900, "Test: worked out by hand", "ref-line-" + UUID.randomUUID());
        pay(y.id(), 10000, 1);

        String base = "/api/v1/platform/referrals?referrer_tenant_id=" + a.tenantId();
        JsonNode all = platform.get(base, operator.accessToken()).getBody();
        assertThat(all.get("items")).hasSize(2);
        all.get("items")
                .forEach(i -> assertThat(i.get("referrer_tenant_id").asString())
                        .isEqualTo(a.tenantId().toString()));
        JsonNode paying =
                platform.get(base + "&status=paying", operator.accessToken()).getBody();
        assertThat(paying.get("items")).hasSize(1);
        assertThat(paying.get("items").get(0).get("referred_slug").asString()).isEqualTo(y.slug());
        JsonNode due =
                platform.get(base + "&commission=due", operator.accessToken()).getBody();
        assertThat(due.get("items")).hasSize(1);
        assertThat(platform.get(base + "&status=nonsense", operator.accessToken())
                        .getStatusCode()
                        .value())
                .isEqualTo(422);

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(operator.accessToken());
        ResponseEntity<String> csv = http.exchange(
                "/api/v1/platform/referrals/export?referrer_tenant_id=" + a.tenantId(),
                HttpMethod.GET,
                new HttpEntity<>(h),
                String.class);
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        String[] lines = csv.getBody().split("\r\n");
        assertThat(lines[0]).startsWith("referral_id,application_id,referrer_slug");
        assertThat(lines).hasSize(3);
        assertThat(csv.getBody()).contains(y.slug()).contains(",UGX,900,0,900");
        assertThat(audits("platform.referral.exported")).isEqualTo(1);
    }

    // ---- Money edge cases -----------------------------------------------------------------------

    /**
     * Two first payments recorded at the same moment are counted one after the other (the subscription lock orders them), so a
     * first payment rule pays once; a payment recorded before the referral existed does not use up the rule (decision 9).
     */
    @Test
    void paymentsRecordedAtOnceAreCountedInTurnAndEarlierPaymentsDoNotCount() throws Exception {
        TestDatabase.Fixture a = TestDatabase.tenant("refrace", false);
        Business x = business("ref-race");
        pay(x.id(), 10000, 1);
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        Map<String, Object> once = new LinkedHashMap<>();
        once.put("kind", "fixed_first_payment");
        once.put("amount_minor", 2000);
        once.put("currency", "UGX");
        rule("/api/v1/platform/referrals/" + rx + "/rule", once, "Test: one-off deal");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResponseEntity<JsonNode>>> both = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                both.add(pool.submit(() -> {
                    start.await();
                    return pay(x.id(), 10000, 1);
                }));
            }
            start.countDown();
            for (Future<ResponseEntity<JsonNode>> f : both) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(amounts(detail(rx))).containsExactly(2000L);
    }

    /**
     * A payment recorded while the operator changes the referrer waits for the change and lands on the referral it left
     * current: it is never left with no outcome row (review of #467). The change is held open in its own transaction until
     * the payment is seen waiting on a lock (within the 5 second lock_timeout of the payment's Idempotency-Key), then
     * committed.
     */
    @Test
    void aPaymentRecordedDuringAChangeOfReferrerLandsOnTheNewReferral() throws Exception {
        seededDefault();
        TestDatabase.Fixture a = TestDatabase.tenant("refchga", false);
        TestDatabase.Fixture b = TestDatabase.tenant("refchgb", false);
        Business x = business("ref-change-race");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID byA = currentReferral(x.id());

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection change = TestDatabase.ownerDataSource().getConnection()) {
            change.setAutoCommit(false);
            try (PreparedStatement ps =
                    change.prepareStatement("SELECT * FROM platform_referral_set_referrer(?, ?, ?, ?)")) {
                ps.setObject(1, x.id());
                ps.setObject(2, b.tenantId());
                ps.setObject(3, operatorId);
                ps.setTimestamp(4, Timestamp.from(Instant.now()));
                ps.executeQuery().close();
            }
            Future<ResponseEntity<JsonNode>> payment = pool.submit(() -> pay(x.id(), 60000, 1));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (waitingOnALock() == 0) {
                if (payment.isDone()) {
                    throw new AssertionError("the payment did not wait for the change: " + payment.get());
                }
                assertThat(System.nanoTime()).as("the payment is seen waiting").isLessThan(deadline);
                Thread.sleep(50);
            }
            change.commit();
            UUID paymentId = UUID.fromString(
                    payment.get(60, TimeUnit.SECONDS).getBody().get("id").asString());
            UUID byB = currentReferral(x.id());
            assertThat(byB).isNotEqualTo(byA);
            assertThat(TestDatabase.owner()
                            .sql("SELECT referral_id FROM referral_payment_outcomes WHERE payment_id = ?")
                            .param(paymentId)
                            .query(UUID.class)
                            .single())
                    .isEqualTo(byB);
            assertThat(amounts(detail(byB))).containsExactly(6000L);
            assertThat(amounts(detail(byA))).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    /** pg_locks, not pg_stat_activity: the owner role is not shown the wait events of the application role's sessions. */
    static long waitingOnALock() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM pg_locks WHERE NOT granted")
                .query(Long.class)
                .single();
    }

    /** A percent is rounded half up to whole minor units: ten percent of 333 is 33, and of 335 is 34 (decision 5). */
    @Test
    void aPercentIsRoundedHalfUp() {
        TestDatabase.Fixture a = TestDatabase.tenant("refround", false);
        Business x = business("ref-round");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        rule("/api/v1/platform/referrals/" + rx + "/rule", percent(1000, null), "Test: ten percent, no end");
        pay(x.id(), 333, 1);
        pay(x.id(), 335, 1);
        assertThat(amounts(detail(rx))).containsExactly(33L, 34L);
    }

    static Map<String, Object> percent(int bp, Integer months) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("kind", "percent_of_payment");
        rule.put("percent_bp", bp);
        rule.put("months", months);
        return rule;
    }

    /**
     * A typed line is behind the Idempotency-Key protocol: no key is refused, the same key again adds nothing and answers the
     * first line, and the same key with another amount is refused.
     */
    @Test
    void aTypedLineIsAddedOncePerIdempotencyKey() {
        TestDatabase.Fixture a = TestDatabase.tenant("reftyped", false);
        Business x = business("ref-typed");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());

        ResponseEntity<JsonNode> noKey = addLine(rx, 800, "Test: worked out by hand", null);
        assertThat(noKey.getStatusCode().value()).isEqualTo(422);
        assertThat(noKey.getBody().get("code").asString()).isEqualTo("idempotency_key_missing");

        String key = "ref-line-" + UUID.randomUUID();
        ResponseEntity<JsonNode> first = addLine(rx, 800, "Test: worked out by hand", key);
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> again = addLine(rx, 800, "Test: worked out by hand", key);
        assertThat(again.getStatusCode()).as("%s", again.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(amounts(again.getBody())).containsExactly(800L);
        assertThat(addLine(rx, 900, "Test: worked out by hand", key)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("idempotency_key_reused");
        assertThat(amounts(detail(rx))).containsExactly(800L);
        assertThat(audits("platform.referral.commission_added")).isEqualTo(1);
    }

    /** A payment submitted twice with the same Idempotency-Key is recorded once and adds exactly one line. */
    @Test
    void aRepeatedPaymentAddsOneLine() {
        TestDatabase.Fixture a = TestDatabase.tenant("refreplay", false);
        Business x = business("ref-replay");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        rule("/api/v1/platform/referrals/" + rx + "/rule", percent(1000, null), "Test: ten percent, no end");
        String key = "ref-pay-" + UUID.randomUUID();
        assertThat(pay(x.id(), 10000, 1, key).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(pay(x.id(), 10000, 1, key).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(amounts(detail(rx))).containsExactly(1000L);
        // The operator's read adds the payment's amount to the snapshot; the row the referrer can read never carries
        // it.
        assertThat(detail(rx)
                        .get("lines")
                        .get(0)
                        .get("rule_snapshot")
                        .get("payment_amount_minor")
                        .asLong())
                .isEqualTo(10000L);
        assertThat(TestDatabase.owner()
                        .sql("SELECT rule_snapshot -> 'payment_amount_minor' IS NOT NULL FROM referral_commission_lines"
                                + " WHERE referral_id = ?")
                        .param(rx)
                        .query(Boolean.class)
                        .single())
                .isFalse();
    }

    /**
     * A payment that straddles the end of the first N months earns only for the months still inside N, pro rata, and counts
     * only those; the next payment earns nothing (decision 5).
     */
    @Test
    void aPaymentAcrossTheEndOfTheMonthsEarnsForTheMonthsLeft() {
        TestDatabase.Fixture a = TestDatabase.tenant("refedge", false);
        Business x = business("ref-edge");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        rule("/api/v1/platform/referrals/" + rx + "/rule", percent(1000, 3), "Test: ten percent, three months");
        pay(x.id(), 20000, 2);
        // One month of three is left: ten percent of a third of 10,001 is 333.37, so 333.
        pay(x.id(), 10001, 3);
        pay(x.id(), 10000, 1);
        JsonNode lines = detail(rx).get("lines");
        assertThat(amounts(detail(rx))).containsExactly(2000L, 333L);
        assertThat(lines.get(0).get("months_counted").asInt()).isEqualTo(2);
        assertThat(lines.get(1).get("months_counted").asInt()).isEqualTo(1);
        assertThat(lines.get(1).get("rule_snapshot").get("paid_months_before").asInt())
                .isEqualTo(2);
    }

    /** A rule changed between two payments applies to the second only: the first line and its snapshot never change. */
    @Test
    void aRuleChangeLeavesEarlierLinesAsTheyWere() {
        TestDatabase.Fixture a = TestDatabase.tenant("refchange", false);
        Business x = business("ref-change");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        rule("/api/v1/platform/referrals/" + rx + "/rule", percent(1000, null), "Test: ten percent, no end");
        pay(x.id(), 10000, 1);
        JsonNode first = detail(rx).get("lines").get(0);

        Map<String, Object> perMonth = new LinkedHashMap<>();
        perMonth.put("kind", "fixed_per_month");
        perMonth.put("amount_minor", 700);
        perMonth.put("currency", "UGX");
        rule("/api/v1/platform/referrals/" + rx + "/rule", perMonth, "Test: a new deal");
        pay(x.id(), 10000, 1);

        JsonNode lines = detail(rx).get("lines");
        assertThat(amounts(detail(rx))).containsExactly(1000L, 700L);
        assertThat(lines.get(0)).isEqualTo(first);
        assertThat(lines.get(0).get("rule_snapshot").get("kind").asString()).isEqualTo("percent_of_payment");
        assertThat(lines.get(1).get("rule_snapshot").get("kind").asString()).isEqualTo("fixed_per_month");
        assertThat(lines.get(1).get("rule_snapshot").get("rule_id"))
                .isNotEqualTo(lines.get(0).get("rule_snapshot").get("rule_id"));
    }

    // ---- The default rule (ADR-069 decision 14) ----------------------------------------------

    /** The default rule V88 seeds: 10% of each payment for the first 12 paid months, no cap. */
    static final UUID SEEDED_DEFAULT = UUID.fromString("00000000-0000-4000-8000-000000000890");

    /** Puts the seeded default back in force, whatever an earlier test left. */
    static void seededDefault() {
        TestDatabase.owner()
                .sql("UPDATE referral_settings SET default_rule_id = ?")
                .param(SEEDED_DEFAULT)
                .update();
    }

    List<String> outcomes(UUID referralId) {
        return TestDatabase.owner()
                .sql("""
                        SELECT o.outcome FROM referral_payment_outcomes o JOIN subscription_payments p ON p.id = o.payment_id
                         WHERE o.referral_id = ? ORDER BY p.created_at
                        """)
                .param(referralId)
                .query(String.class)
                .list();
    }

    /** A Growing business paying 60,000 a month earns its referrer 6,000 on each of its first 12 payments, nothing on the 13th. */
    @Test
    void theDefaultEarnsTenPercentOfEachMonthlyPaymentForTwelveMonths() {
        seededDefault();
        TestDatabase.Fixture a = TestDatabase.tenant("refdefm", false);
        Business x = business("ref-def-month");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        for (int month = 1; month <= 13; month++) {
            pay(x.id(), 60000, 1);
        }
        JsonNode detail = detail(rx);
        assertThat(detail.get("rule_source").asString()).isEqualTo("default");
        assertThat(amounts(detail)).hasSize(12).containsOnly(6000L);
        assertThat(detail.get("lines")
                        .get(0)
                        .get("rule_snapshot")
                        .get("rule_id")
                        .asString())
                .isEqualTo(SEEDED_DEFAULT.toString());
        assertThat(outcomes(rx)).hasSize(13).endsWith("months_used");
    }

    /** A year paid upfront earns 10% of that payment once and uses up the 12 months, so the next payment earns nothing. */
    @Test
    void theDefaultOnAYearPaidUpfrontEarnsOnceAndUsesTheTwelveMonths() {
        seededDefault();
        TestDatabase.Fixture a = TestDatabase.tenant("refdefy", false);
        Business x = business("ref-def-year");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        pay(x.id(), 720000, 12);
        pay(x.id(), 60000, 1);
        JsonNode lines = detail(rx).get("lines");
        assertThat(amounts(detail(rx))).containsExactly(72000L);
        assertThat(lines.get(0).get("months_counted").asInt()).isEqualTo(12);
        assertThat(outcomes(rx)).containsExactly("added", "months_used");
    }

    ResponseEntity<JsonNode> reverse(UUID lineId, String reason, Boolean refunded) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        if (refunded != null) {
            body.put("refunded", refunded);
        }
        return platform.post(
                "/api/v1/platform/referrals/commissions/" + lineId + "/reverse", body, operator.accessToken());
    }

    /**
     * A year paid upfront and then refunded: the operator reverses its line saying the payment was refunded, so the payment no
     * longer uses up the 12 months and the next monthly payment earns. A reversal without refunded leaves the months used.
     */
    @Test
    void aRefundedPaymentDoesNotUseUpTheMonths() {
        seededDefault();
        TestDatabase.Fixture a = TestDatabase.tenant("refrefund", false);
        Business x = business("ref-refund");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        pay(x.id(), 720000, 12);
        UUID year = UUID.fromString(detail(rx).get("lines").get(0).get("id").asString());
        ResponseEntity<JsonNode> reversed = reverse(year, "Test: the year was refunded", true);
        assertThat(reversed.getStatusCode()).as("%s", reversed.getBody()).isEqualTo(HttpStatus.OK);
        pay(x.id(), 60000, 1);
        assertThat(amounts(detail(rx))).containsExactly(72000L, -72000L, 6000L);
        assertThat(outcomes(rx)).containsExactly("added", "added");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data ->> 'refunded' FROM platform_audit_log"
                                        + " WHERE action = 'platform.referral.commission_reversed' AND data ->> 'reverses_line_id' = ?")
                        .param(year.toString())
                        .query(String.class)
                        .single())
                .isEqualTo("true");

        // A typed line has no payment to refund; a reversal without refunded keeps the payment's months used.
        UUID typed = UUID.fromString(addLine(rx, 500, "Test: worked out by hand", "ref-line-" + UUID.randomUUID())
                .getBody()
                .get("lines")
                .get(3)
                .get("id")
                .asString());
        ResponseEntity<JsonNode> notPayment = reverse(typed, "Test: typed twice", true);
        assertThat(notPayment.getStatusCode().value()).isEqualTo(422);
        assertThat(notPayment.getBody().get("code").asString()).isEqualTo("commission_line_not_payment");

        Business y = business("ref-refund-y");
        setReferrer(y.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID ry = currentReferral(y.id());
        pay(y.id(), 720000, 12);
        UUID kept = UUID.fromString(detail(ry).get("lines").get(0).get("id").asString());
        assertThat(reverse(kept, "Test: commission withdrawn", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        pay(y.id(), 60000, 1);
        assertThat(amounts(detail(ry))).containsExactly(72000L, -72000L);
        assertThat(outcomes(ry)).containsExactly("added", "months_used");
    }

    /**
     * Clearing the referrer and setting the same one again does not restart the 12 months: the months are counted over every
     * referral of the referred business from its first one, so twelve monthly payments earn twelve lines in all.
     */
    @Test
    void aClearAndResetDoesNotRestartTheMonths() {
        seededDefault();
        TestDatabase.Fixture a = TestDatabase.tenant("refreset", false);
        Business x = business("ref-reset");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID first = currentReferral(x.id());
        for (int month = 1; month <= 8; month++) {
            pay(x.id(), 60000, 1);
        }
        assertThat(setReferrer(x.id(), null, "Test: cleared by mistake").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(setReferrer(x.id(), code(a.tenantId()), "Test: set again").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        UUID second = currentReferral(x.id());
        assertThat(second).isNotEqualTo(first);
        for (int month = 9; month <= 14; month++) {
            pay(x.id(), 60000, 1);
        }
        assertThat(amounts(detail(first))).hasSize(8).containsOnly(6000L);
        assertThat(amounts(detail(second))).hasSize(4).containsOnly(6000L);
        assertThat(outcomes(second)).containsExactly("added", "added", "added", "added", "months_used", "months_used");
    }

    /**
     * A correction to another referrer after the twelfth month gives the new referrer nothing: the months already used under
     * the first referral count, so the new one has none left. A correction at month 5 gives it the 7 months left.
     */
    @Test
    void aCorrectionGetsOnlyTheMonthsLeft() {
        seededDefault();
        TestDatabase.Fixture a = TestDatabase.tenant("refcorra", false);
        TestDatabase.Fixture b = TestDatabase.tenant("refcorrb", false);
        Business x = business("ref-corr-late");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID byA = currentReferral(x.id());
        for (int month = 1; month <= 12; month++) {
            pay(x.id(), 60000, 1);
        }
        setReferrer(x.id(), code(b.tenantId()), "Test: it was the other shop");
        UUID byB = currentReferral(x.id());
        pay(x.id(), 60000, 1);
        pay(x.id(), 720000, 12);
        assertThat(amounts(detail(byA))).hasSize(12).containsOnly(6000L);
        assertThat(detail(byB).get("lines")).isEmpty();
        assertThat(outcomes(byB)).containsExactly("months_used", "months_used");

        Business y = business("ref-corr-early");
        setReferrer(y.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID yByA = currentReferral(y.id());
        for (int month = 1; month <= 5; month++) {
            pay(y.id(), 60000, 1);
        }
        setReferrer(y.id(), code(b.tenantId()), "Test: it was the other shop");
        UUID yByB = currentReferral(y.id());
        pay(y.id(), 720000, 12);
        assertThat(amounts(detail(yByA))).hasSize(5).containsOnly(6000L);
        // 7 of the 12 months are left: 10% of 720,000, pro rata 7/12.
        assertThat(amounts(detail(yByB))).containsExactly(42000L);
        assertThat(detail(yByB).get("lines").get(0).get("months_counted").asInt())
                .isEqualTo(7);
    }

    /** Two businesses referred by the same shop each have their own 12 months. */
    @Test
    void eachReferredBusinessHasItsOwnTwelveMonths() {
        seededDefault();
        TestDatabase.Fixture a = TestDatabase.tenant("refdeft", false);
        Business x = business("ref-def-x");
        Business y = business("ref-def-y");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        pay(x.id(), 720000, 12);
        pay(x.id(), 60000, 1);
        // The second business is referred after the first used its months: its own 12 start with its own referral.
        setReferrer(y.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID ry = currentReferral(y.id());
        for (int month = 1; month <= 12; month++) {
            pay(y.id(), 60000, 1);
        }
        pay(y.id(), 60000, 1);
        assertThat(amounts(detail(rx))).containsExactly(72000L);
        assertThat(amounts(detail(ry))).hasSize(12).containsOnly(6000L);
    }

    /** A new default applies to later payments only: the lines worked out under the old one keep their amount and rule. */
    @Test
    void changingTheDefaultLeavesExistingLinesAsTheyWere() {
        seededDefault();
        TestDatabase.Fixture a = TestDatabase.tenant("refdefc", false);
        Business x = business("ref-def-change");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        pay(x.id(), 60000, 1);
        JsonNode first = detail(rx).get("lines").get(0);

        ResponseEntity<JsonNode> changed =
                rule("/api/v1/platform/referrals/default-rule", percent(500, 12), "Test: a new default");
        assertThat(changed.getStatusCode()).as("%s", changed.getBody()).isEqualTo(HttpStatus.OK);
        pay(x.id(), 60000, 1);

        JsonNode lines = detail(rx).get("lines");
        assertThat(amounts(detail(rx))).containsExactly(6000L, 3000L);
        assertThat(lines.get(0)).isEqualTo(first);
        assertThat(lines.get(0).get("rule_snapshot").get("rule_id").asString()).isEqualTo(SEEDED_DEFAULT.toString());
        assertThat(lines.get(1).get("rule_snapshot").get("percent_bp").asInt()).isEqualTo(500);
    }

    /**
     * A fixed rule in another currency than the payment's gives no line (currency_differs), and that payment never uses up a
     * later rule's months: under a first payment rule in the payment's currency, the next payment is the first (decision 9).
     */
    @Test
    void aFixedRuleInAnotherCurrencyGivesNoLine() {
        TestDatabase.Fixture a = TestDatabase.tenant("refcur", false);
        Business x = business("ref-cur");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        Map<String, Object> usd = new LinkedHashMap<>();
        usd.put("kind", "fixed_first_payment");
        usd.put("amount_minor", 500);
        usd.put("currency", "USD");
        rule("/api/v1/platform/referrals/" + rx + "/rule", usd, "Test: a deal in dollars");
        pay(x.id(), 10000, 1);
        assertThat(detail(rx).get("lines")).isEmpty();
        assertThat(audits("platform.referral.commission_added")).isZero();
        assertThat(TestDatabase.owner()
                        .sql("SELECT outcome FROM referral_payment_outcomes WHERE referral_id = ?")
                        .param(rx)
                        .query(String.class)
                        .single())
                .isEqualTo("currency_differs");

        Map<String, Object> ugx = new LinkedHashMap<>(usd);
        ugx.put("currency", "UGX");
        rule("/api/v1/platform/referrals/" + rx + "/rule", ugx, "Test: the deal in shillings");
        pay(x.id(), 10000, 1);
        pay(x.id(), 10000, 1);
        assertThat(amounts(detail(rx))).containsExactly(500L);
    }

    /**
     * Setting the referrer, the default rule or a referral's rule needs a current code from the operator's own authenticator:
     * none or a wrong one is refused and nothing changes.
     */
    @Test
    void theReferrerAndTheRulesNeedTheOperatorsCode() {
        TestDatabase.Fixture a = TestDatabase.tenant("reftotp", false);
        Business x = business("ref-totp");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        TestDatabase.Fixture b = TestDatabase.tenant("reftotp-b", false);

        Map<String, Object> referrer = new LinkedHashMap<>();
        referrer.put("referrer_code", code(b.tenantId()));
        referrer.put("reason", "Test: without a code");
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("rule", percent(1000, null));
        rule.put("reason", "Test: without a code");
        Map<String, Map<String, Object>> routes = new LinkedHashMap<>();
        routes.put("/api/v1/platform/tenants/" + x.id() + "/referrer", referrer);
        routes.put("/api/v1/platform/referrals/default-rule", rule);
        routes.put("/api/v1/platform/referrals/" + rx + "/rule", rule);
        for (Map.Entry<String, Map<String, Object>> route : routes.entrySet()) {
            ResponseEntity<JsonNode> none =
                    platform.call(HttpMethod.PUT, route.getKey(), route.getValue(), operator.accessToken());
            assertThat(none.getStatusCode().value())
                    .as("%s without a code", route.getKey())
                    .isEqualTo(422);
            Map<String, Object> wrong = new LinkedHashMap<>(route.getValue());
            wrong.put("totp_code", "000000".equals(totp()) ? "111111" : "000000");
            ResponseEntity<JsonNode> refused =
                    platform.call(HttpMethod.PUT, route.getKey(), wrong, operator.accessToken());
            assertThat(refused.getStatusCode().value())
                    .as("%s with a wrong code", route.getKey())
                    .isEqualTo(422);
            assertThat(refused.getBody().get("code").asString()).isEqualTo("invalid_mfa_code");
        }
        assertThat(currentReferral(x.id())).isEqualTo(rx);
        assertThat(detail(rx).get("referral").get("rule_override").asBoolean()).isFalse();
        assertThat(audits("platform.referral.default_rule_changed")).isZero();
        assertThat(audits("platform.referral.rule_changed")).isZero();
    }

    /**
     * A payment that earned no line (here no rule was in force) can still be recorded as refunded, on its own route, so it no
     * longer uses up the months: the next payment earns for all twelve. Recorded once, audited; a payment with no referral
     * outcome is 404.
     */
    @Test
    void aRefundedPaymentWithNoLineFreesItsMonths() {
        TestDatabase.Fixture a = TestDatabase.tenant("refrefund2", false);
        Business x = business("ref-refund-noline");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        TestDatabase.owner()
                .sql("UPDATE referral_settings SET default_rule_id = NULL")
                .update();
        UUID payment =
                UUID.fromString(pay(x.id(), 180000, 3).getBody().get("id").asString());
        assertThat(outcomes(rx)).containsExactly("no_rule");

        String path = "/api/v1/platform/referrals/payments/" + payment + "/refunded";
        ResponseEntity<JsonNode> refunded =
                platform.post(path, Map.of("reason", "Test: the payment was sent back"), operator.accessToken());
        assertThat(refunded.getStatusCode()).as("%s", refunded.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(platform.post(path, Map.of("reason", "Test: twice"), operator.accessToken())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("payment_already_refunded");
        assertThat(platform.post(
                                "/api/v1/platform/referrals/payments/" + UUID.randomUUID() + "/refunded",
                                Map.of("reason", "Test: no such payment"),
                                operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(audits("platform.referral.payment_refunded")).isEqualTo(1);

        seededDefault();
        pay(x.id(), 720000, 12);
        // All twelve months earn: the refunded three months are not counted.
        assertThat(amounts(detail(rx))).containsExactly(72000L);
    }

    /**
     * A payment made between a clear and a reset uses up months like any other, so it gets a no_referral outcome on the
     * referral that ended and can be marked refunded; once refunded it no longer counts (review of #467). A payment made
     * before the business had any referral gets no outcome and counts for nothing.
     */
    @Test
    void aPaymentWhileNoReferralIsCurrentCanBeMarkedRefunded() {
        seededDefault();
        TestDatabase.Fixture a = TestDatabase.tenant("refgap", false);
        Business x = business("ref-gap");
        UUID before = UUID.fromString(pay(x.id(), 60000, 1).getBody().get("id").asString());
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID first = currentReferral(x.id());
        pay(x.id(), 60000, 1);
        assertThat(setReferrer(x.id(), null, "Test: cleared by mistake").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        UUID gap = UUID.fromString(pay(x.id(), 180000, 3).getBody().get("id").asString());
        assertThat(outcomes(first)).containsExactly("added", "no_referral");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM referral_payment_outcomes WHERE payment_id = ?")
                        .param(before)
                        .query(Long.class)
                        .single())
                .isZero();

        ResponseEntity<JsonNode> refunded = platform.post(
                "/api/v1/platform/referrals/payments/" + gap + "/refunded",
                Map.of("reason", "Test: the payment was sent back"),
                operator.accessToken());
        assertThat(refunded.getStatusCode()).as("%s", refunded.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(refunded.getBody().get("referral").get("referral_id").asString())
                .isEqualTo(first.toString());

        assertThat(setReferrer(x.id(), code(a.tenantId()), "Test: set again").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        UUID second = currentReferral(x.id());
        pay(x.id(), 720000, 12);
        // One month used under the first referral; the refunded three are not counted, so eleven are left.
        assertThat(amounts(detail(second))).containsExactly(66000L);
    }

    /**
     * A year paid upfront earns a line; marking that payment refunded while its line stands is refused, so its months stay
     * used and the next monthly payment earns nothing. Reversing the line with refunded frees them: commission is never
     * due both on the refunded year and on the payment after it.
     */
    @Test
    void aRefundWhileItsLineStandsIsRefusedSoNoCommissionIsPaidTwice() {
        seededDefault();
        TestDatabase.Fixture a = TestDatabase.tenant("refrefund3", false);
        Business x = business("ref-refund-line");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        UUID year = UUID.fromString(pay(x.id(), 720000, 12).getBody().get("id").asString());
        assertThat(amounts(detail(rx))).containsExactly(72000L);

        long before = audits("platform.referral.payment_refunded");
        ResponseEntity<JsonNode> refused = platform.post(
                "/api/v1/platform/referrals/payments/" + year + "/refunded",
                Map.of("reason", "Test: the year was sent back"),
                operator.accessToken());
        assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("payment_line_not_reversed");
        assertThat(audits("platform.referral.payment_refunded")).isEqualTo(before);
        pay(x.id(), 60000, 1);
        assertThat(amounts(detail(rx))).containsExactly(72000L);
        assertThat(outcomes(rx)).containsExactly("added", "months_used");

        UUID line = UUID.fromString(detail(rx).get("lines").get(0).get("id").asString());
        assertThat(reverse(line, "Test: the year was refunded", true).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        pay(x.id(), 60000, 1);
        assertThat(amounts(detail(rx))).containsExactly(72000L, -72000L, 6000L);
        assertThat(detail(rx)
                        .get("referral")
                        .get("totals")
                        .get(0)
                        .get("due_minor")
                        .asLong())
                .isEqualTo(6000L);
    }

    /**
     * The reversal of a line that was never paid is never marked paid either: it would show money due that was never owed.
     * The reversal of a paid line is settled like any line.
     */
    @Test
    void aReversalIsMarkedPaidOnlyWhenItsLineWasPaid() {
        TestDatabase.Fixture a = TestDatabase.tenant("refrevpaid", false);
        Business x = business("ref-rev-paid");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        Map<String, Object> paid = new LinkedHashMap<>();
        paid.put("paid_on", LocalDate.now(KAMPALA).toString());
        paid.put("method", "cash");
        paid.put("reason", "Test: handed over");

        UUID unpaid = UUID.fromString(addLine(rx, 10000, "Test: worked out by hand", "ref-line-" + UUID.randomUUID())
                .getBody()
                .get("lines")
                .get(0)
                .get("id")
                .asString());
        UUID reversalOfUnpaid = UUID.fromString(reverse(unpaid, "Test: typed twice", null)
                .getBody()
                .get("lines")
                .get(1)
                .get("id")
                .asString());
        ResponseEntity<JsonNode> refused = platform.post(
                "/api/v1/platform/referrals/commissions/" + reversalOfUnpaid + "/paid", paid, operator.accessToken());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("commission_reversal_of_unpaid");
        JsonNode total = detail(rx).get("referral").get("totals").get(0);
        assertThat(total.get("due_minor").asLong()).isZero();
        assertThat(total.get("paid_minor").asLong()).isZero();

        UUID settled = UUID.fromString(addLine(rx, 700, "Test: a bonus", "ref-line-" + UUID.randomUUID())
                .getBody()
                .get("lines")
                .get(2)
                .get("id")
                .asString());
        assertThat(platform.post(
                                "/api/v1/platform/referrals/commissions/" + settled + "/paid",
                                paid,
                                operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        UUID reversalOfPaid = UUID.fromString(reverse(settled, "Test: the bonus was a mistake", null)
                .getBody()
                .get("lines")
                .get(3)
                .get("id")
                .asString());
        assertThat(platform.post(
                                "/api/v1/platform/referrals/commissions/" + reversalOfPaid + "/paid",
                                paid,
                                operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        total = detail(rx).get("referral").get("totals").get(0);
        assertThat(total.get("earned_minor").asLong()).isZero();
        assertThat(total.get("due_minor").asLong()).isZero();
    }

    /** Like the months, a percent rule's cap counts every referral of the referred business: a correction never restarts it. */
    @Test
    void aCorrectionDoesNotRestartTheCap() {
        TestDatabase.Fixture a = TestDatabase.tenant("refcapa", false);
        TestDatabase.Fixture b = TestDatabase.tenant("refcapb", false);
        Business x = business("ref-cap-fix");
        Map<String, Object> capped = new LinkedHashMap<>();
        capped.put("kind", "percent_of_payment");
        capped.put("percent_bp", 5000);
        capped.put("currency", "UGX");
        capped.put("cap_minor", 7000);
        assertThat(rule("/api/v1/platform/referrals/default-rule", capped, "Test: half, at most 7,000")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by A");
        UUID ra = currentReferral(x.id());
        pay(x.id(), 10000, 1);
        setReferrer(x.id(), code(b.tenantId()), "Test: it was B");
        UUID rb = currentReferral(x.id());
        pay(x.id(), 10000, 1);
        pay(x.id(), 10000, 1);
        assertThat(amounts(detail(ra))).containsExactly(5000L);
        assertThat(amounts(detail(rb))).containsExactly(2000L);
    }

    /** An unpaid line that was reversed is never marked paid (decision 11). */
    @Test
    void aReversedLineIsNeverMarkedPaid() {
        TestDatabase.Fixture a = TestDatabase.tenant("refrev", false);
        Business x = business("ref-rev");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        JsonNode added = addLine(rx, 600, "Test: worked out by hand", "ref-line-" + UUID.randomUUID())
                .getBody();
        UUID line = UUID.fromString(added.get("lines").get(0).get("id").asString());
        platform.post(
                "/api/v1/platform/referrals/commissions/" + line + "/reverse",
                Map.of("reason", "Test: typed twice"),
                operator.accessToken());

        Map<String, Object> paid = new LinkedHashMap<>();
        paid.put("paid_on", LocalDate.now(KAMPALA).toString());
        paid.put("method", "cash");
        paid.put("reason", "Test: handed over");
        ResponseEntity<JsonNode> refused =
                platform.post("/api/v1/platform/referrals/commissions/" + line + "/paid", paid, operator.accessToken());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("commission_line_reversed");
        assertThat(detail(rx).get("lines").get(0).get("paid_on").isNull()).isTrue();
        assertThat(audits("platform.referral.commission_paid")).isZero();
    }

    /**
     * A referred business whose subscription is suspended or cancelled shows so to the owner and the operator; a line worked out
     * while the referrer business is suspended or cancelled is still added and keeps that flag; and every line of a referrer
     * that is suspended or cancelled now, including one earned while it was active, shows flagged at read time and is not
     * marked paid unless the operator confirms it (decision 12).
     */
    @Test
    void suspendedAndCancelledBusinessesAreShownAndFlagged() {
        Business referrer = business("ref-inact");
        Business x = business("ref-inact-x");
        setReferrer(x.id(), code(referrer.id()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        rule("/api/v1/platform/referrals/" + rx + "/rule", percent(1000, null), "Test: ten percent, no end");
        pay(x.id(), 10000, 1);
        subscription(referrer.id(), "cancelled");
        pay(x.id(), 10000, 1);
        JsonNode lines = detail(rx).get("lines");
        assertThat(amounts(detail(rx))).containsExactly(1000L, 1000L);
        assertThat(lines.get(0).get("inactive_when_worked_out").asBoolean()).isFalse();
        assertThat(lines.get(1).get("inactive_when_worked_out").asBoolean()).isTrue();
        assertThat(lines.get(0).get("referrer_inactive").asBoolean()).isTrue();
        assertThat(lines.get(0).get("referrer_status").asString()).isEqualTo("cancelled");
        assertThat(detail(rx).get("referral").get("referrer_status").asString()).isEqualTo("cancelled");

        UUID earnedWhileActive = UUID.fromString(lines.get(0).get("id").asString());
        Map<String, Object> paid = new LinkedHashMap<>();
        paid.put("paid_on", LocalDate.now(KAMPALA).toString());
        paid.put("method", "cash");
        paid.put("reason", "Test: handed over");
        long paidBefore = audits("platform.referral.commission_paid");
        ResponseEntity<JsonNode> refused = platform.post(
                "/api/v1/platform/referrals/commissions/" + earnedWhileActive + "/paid", paid, operator.accessToken());
        assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("referrer_inactive");
        assertThat(detail(rx).get("lines").get(0).get("paid_on").isNull()).isTrue();
        assertThat(audits("platform.referral.commission_paid")).isEqualTo(paidBefore);
        paid.put("confirm_referrer_inactive", true);
        ResponseEntity<JsonNode> confirmed = platform.post(
                "/api/v1/platform/referrals/commissions/" + earnedWhileActive + "/paid", paid, operator.accessToken());
        assertThat(confirmed.getStatusCode()).as("%s", confirmed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody().get("lines").get(0).get("paid_on").isNull())
                .isFalse();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM platform_audit_log WHERE action = 'platform.referral.commission_paid'"
                                        + " AND data ->> 'line_id' = ? AND (data ->> 'confirmed_referrer_inactive')::boolean")
                        .param(earnedWhileActive.toString())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        subscription(referrer.id(), "active");
        assertThat(detail(rx).get("lines").get(1).get("referrer_inactive").asBoolean())
                .isFalse();

        TestDatabase.Fixture a = TestDatabase.tenant("refstatus", false);
        Business y = business("ref-status-y");
        Business z = business("ref-status-z");
        setReferrer(y.id(), code(a.tenantId()), "Test: referred by the shop");
        setReferrer(z.id(), code(a.tenantId()), "Test: referred by the shop");
        subscription(y.id(), "suspended");
        subscription(z.id(), "cancelled");
        Map<String, String> owner = new LinkedHashMap<>();
        mine(a, staffToken(a, "tenant_admin"))
                .getBody()
                .get("items")
                .forEach(i -> owner.put(
                        i.get("referred_name").asString(), i.get("status").asString()));
        assertThat(owner).containsEntry(y.name(), "suspended").containsEntry(z.name(), "cancelled");
        JsonNode suspended = platform.get(
                        "/api/v1/platform/referrals?referrer_tenant_id=" + a.tenantId() + "&status=suspended",
                        operator.accessToken())
                .getBody();
        assertThat(suspended.get("items")).hasSize(1);
        assertThat(suspended.get("items").get(0).get("referred_slug").asString())
                .isEqualTo(y.slug());
    }

    /**
     * Every state in which the referrer is not in use and paying counts as inactive: a suspended business, a suspended,
     * cancelled or past due subscription, and a free month that ended unpaid (review of #467). Each flags the line and
     * refuses an unconfirmed paid mark; an unexpired free month and a pilot stay active.
     */
    @Test
    void everyStateOfAReferrerThatIsNotPayingIsInactive() {
        Business referrer = business("ref-states");
        Business x = business("ref-states-x");
        setReferrer(x.id(), code(referrer.id()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        rule("/api/v1/platform/referrals/" + rx + "/rule", percent(1000, null), "Test: ten percent, no end");
        pay(x.id(), 10000, 1);
        String line = detail(rx).get("lines").get(0).get("id").asString();
        Map<String, Object> paid = new LinkedHashMap<>();
        paid.put("paid_on", LocalDate.now(KAMPALA).toString());
        paid.put("method", "cash");
        paid.put("reason", "Test: handed over");
        LocalDate today = LocalDate.now(KAMPALA);

        Map<String, String> states = new LinkedHashMap<>();
        states.put("UPDATE tenants SET status = 'suspended' WHERE id = ?", "suspended");
        states.put("UPDATE subscriptions SET status = 'suspended' WHERE tenant_id = ?", "suspended");
        states.put("UPDATE subscriptions SET status = 'cancelled' WHERE tenant_id = ?", "cancelled");
        states.put("UPDATE subscriptions SET status = 'past_due' WHERE tenant_id = ?", "past_due");
        states.put(
                "UPDATE subscriptions SET status = 'trial', is_pilot = false, trial_ends_on = DATE '"
                        + today.minusDays(2) + "' WHERE tenant_id = ?",
                "trial_lapsed");
        states.put(
                "UPDATE subscriptions SET status = 'trial', is_pilot = false, trial_ends_on = DATE '" + today
                        + "' WHERE tenant_id = ?",
                "active");
        states.put(
                "UPDATE subscriptions SET status = 'trial', is_pilot = true, trial_ends_on = NULL WHERE tenant_id = ?",
                "active");
        states.forEach((change, expected) -> {
            TestDatabase.owner().sql(change).param(referrer.id()).update();
            JsonNode l = detail(rx).get("lines").get(0);
            assertThat(l.get("referrer_status").asString()).as(change).isEqualTo(expected);
            assertThat(l.get("referrer_inactive").asBoolean()).as(change).isEqualTo(!expected.equals("active"));
            if (!expected.equals("active")) {
                ResponseEntity<JsonNode> refused = platform.post(
                        "/api/v1/platform/referrals/commissions/" + line + "/paid", paid, operator.accessToken());
                assertThat(refused.getStatusCode()).as(change).isEqualTo(HttpStatus.CONFLICT);
                assertThat(refused.getBody().get("code").asString()).isEqualTo("referrer_inactive");
            }
            TestDatabase.owner()
                    .sql("UPDATE tenants SET status = 'active' WHERE id = ?")
                    .param(referrer.id())
                    .update();
            TestDatabase.owner()
                    .sql("UPDATE subscriptions SET status = 'active', is_pilot = false WHERE tenant_id = ?")
                    .param(referrer.id())
                    .update();
        });
        assertThat(detail(rx).get("lines").get(0).get("referrer_inactive").asBoolean())
                .isFalse();
        assertThat(platform.post(
                                "/api/v1/platform/referrals/commissions/" + line + "/paid",
                                paid,
                                operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    static void subscription(UUID tenantId, String status) {
        TestDatabase.owner()
                .sql("UPDATE subscriptions SET status = ? WHERE tenant_id = ?")
                .params(status, tenantId)
                .update();
    }

    /**
     * A returning applicant's code goes on the open application while it has no referrer and is still submitted; a later code
     * never replaces it, and an application in review keeps none (decision 13).
     */
    @Test
    void aReturningApplicantsCodeGoesOnTheOpenApplication() {
        TestDatabase.Fixture first = TestDatabase.tenant("refback-a", false);
        TestDatabase.Fixture second = TestDatabase.tenant("refback-b", false);
        String email = Api.email("ref-back");
        assertThat(apply(signUpForm(email, "Test Returning Shop", null)).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        UUID id = application(email);
        assertThat(apply(signUpForm(email, "Test Returning Shop", code(first.tenantId())))
                        .getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(referrerOf(id)).isEqualTo(first.tenantId());
        assertThat(apply(signUpForm(email, "Test Returning Shop", code(second.tenantId())))
                        .getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(referrerOf(id)).isEqualTo(first.tenantId());

        String reviewed = Api.email("ref-back-review");
        apply(signUpForm(reviewed, "Test Reviewed Shop", null));
        UUID inReview = application(reviewed);
        confirmEmail(inReview);
        assertThat(platform.post(
                                "/api/v1/platform/applications/" + inReview + "/verify",
                                Map.of(),
                                operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        apply(signUpForm(reviewed, "Test Reviewed Shop", code(first.tenantId())));
        assertThat(referrerOf(inReview)).isNull();
    }

    static UUID referrerOf(UUID applicationId) {
        return (UUID) TestDatabase.owner()
                .sql("SELECT referrer_tenant_id FROM onboarding_applications WHERE id = ?")
                .param(applicationId)
                .query()
                .singleRow()
                .get("referrer_tenant_id");
    }

    // ---- Adversarial (test review of #467) ----------------------------------------------------

    ResponseEntity<JsonNode> markPaid(UUID lineId, Object confirm) {
        Map<String, Object> paid = new LinkedHashMap<>();
        paid.put("paid_on", LocalDate.now(KAMPALA).toString());
        paid.put("method", "cash");
        paid.put("reason", "Test: handed over");
        if (confirm != null) {
            paid.put("confirm_referrer_inactive", confirm);
        }
        return platform.post(
                "/api/v1/platform/referrals/commissions/" + lineId + "/paid", paid, operator.accessToken());
    }

    static JsonNode total(JsonNode detail) {
        return detail.get("referral").get("totals").get(0);
    }

    /**
     * A year paid upfront earns once and its line is paid; the year is refunded: the line is reversed once (a second reversal,
     * a reversal of the reversal and a second refund are all refused), the reversal is owed back until it is settled, and the
     * twelve months are freed once, so the next twelve monthly payments earn and the thirteenth does not.
     */
    @Test
    void aPaidYearUpfrontRefundedIsClawedBackOnceAndFreesTheMonthsOnce() {
        seededDefault();
        TestDatabase.Fixture a = TestDatabase.tenant("refadvy", false);
        Business x = business("ref-adv-year");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        UUID year = UUID.fromString(pay(x.id(), 720000, 12).getBody().get("id").asString());
        JsonNode line = detail(rx).get("lines").get(0);
        assertThat(line.get("amount_minor").asLong()).isEqualTo(72000L);
        assertThat(line.get("months_counted").asInt()).isEqualTo(12);
        UUID lineId = UUID.fromString(line.get("id").asString());
        assertThat(markPaid(lineId, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(total(detail(rx)).get("due_minor").asLong()).isZero();

        long reversals = audits("platform.referral.commission_reversed");
        ResponseEntity<JsonNode> reversed = reverse(lineId, "Test: the year was refunded", true);
        assertThat(reversed.getStatusCode()).as("%s", reversed.getBody()).isEqualTo(HttpStatus.OK);
        UUID reversal =
                UUID.fromString(reversed.getBody().get("lines").get(1).get("id").asString());
        JsonNode t = total(detail(rx));
        assertThat(t.get("earned_minor").asLong()).isZero();
        assertThat(t.get("paid_minor").asLong()).isEqualTo(72000L);
        assertThat(t.get("due_minor").asLong()).isEqualTo(-72000L);

        ResponseEntity<JsonNode> again = reverse(lineId, "Test: refunded twice", true);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("commission_line_already_reversed");
        ResponseEntity<JsonNode> ofReversal = reverse(reversal, "Test: undo the refund", null);
        assertThat(ofReversal.getStatusCode().value()).isEqualTo(422);
        assertThat(ofReversal.getBody().get("code").asString()).isEqualTo("commission_line_is_reversal");
        ResponseEntity<JsonNode> refundedAgain = platform.post(
                "/api/v1/platform/referrals/payments/" + year + "/refunded",
                Map.of("reason", "Test: the year was sent back"),
                operator.accessToken());
        assertThat(refundedAgain.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refundedAgain.getBody().get("code").asString()).isEqualTo("payment_already_refunded");
        assertThat(audits("platform.referral.commission_reversed")).isEqualTo(reversals + 1);
        assertThat(amounts(detail(rx))).containsExactly(72000L, -72000L);

        assertThat(markPaid(reversal, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> paidTwice = markPaid(lineId, null);
        assertThat(paidTwice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(paidTwice.getBody().get("code").asString()).isEqualTo("commission_already_paid");
        t = total(detail(rx));
        assertThat(t.get("paid_minor").asLong()).isZero();
        assertThat(t.get("due_minor").asLong()).isZero();

        for (int month = 1; month <= 13; month++) {
            pay(x.id(), 60000, 1);
        }
        List<Long> amounts = amounts(detail(rx));
        assertThat(amounts.subList(0, 2)).containsExactly(72000L, -72000L);
        assertThat(amounts.subList(2, amounts.size())).hasSize(12).containsOnly(6000L);
        assertThat(outcomes(rx)).hasSize(14).startsWith("added").endsWith("months_used");
        assertThat(total(detail(rx)).get("due_minor").asLong()).isEqualTo(72000L);
    }

    /**
     * An override changed twice after lines exist: every earlier line keeps its amount and its snapshot, the paid line stays
     * paid, the months already counted carry on under the new rule, and no statement, not even the owner's, rewrites a line's
     * amount or an existing rule.
     */
    @Test
    void anOverrideChangedAfterLinesExistNeverChangesThem() {
        seededDefault();
        TestDatabase.Fixture a = TestDatabase.tenant("refadvo", false);
        Business x = business("ref-adv-over");
        setReferrer(x.id(), code(a.tenantId()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        pay(x.id(), 60000, 1);
        UUID first = UUID.fromString(detail(rx).get("lines").get(0).get("id").asString());
        assertThat(markPaid(first, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rule("/api/v1/platform/referrals/" + rx + "/rule", percent(2000, 12), "Test: twenty percent")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        pay(x.id(), 60000, 1);
        JsonNode before = detail(rx).get("lines");
        assertThat(amounts(detail(rx))).containsExactly(6000L, 12000L);

        Map<String, Object> perMonth = new LinkedHashMap<>();
        perMonth.put("kind", "fixed_per_month");
        perMonth.put("amount_minor", 500);
        perMonth.put("currency", "UGX");
        perMonth.put("months", 3);
        assertThat(rule("/api/v1/platform/referrals/" + rx + "/rule", perMonth, "Test: three months fixed")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        pay(x.id(), 60000, 1);
        pay(x.id(), 60000, 1);

        JsonNode lines = detail(rx).get("lines");
        assertThat(amounts(detail(rx))).containsExactly(6000L, 12000L, 500L);
        assertThat(lines.get(0)).isEqualTo(before.get(0));
        assertThat(lines.get(1)).isEqualTo(before.get(1));
        assertThat(lines.get(0).get("paid_on").isNull()).isFalse();
        assertThat(lines.get(0).get("rule_snapshot").get("source").asString()).isEqualTo("default");
        assertThat(lines.get(1).get("rule_snapshot").get("percent_bp").asInt()).isEqualTo(2000);
        assertThat(lines.get(2).get("rule_snapshot").get("paid_months_before").asInt())
                .isEqualTo(2);
        assertThat(outcomes(rx)).containsExactly("added", "added", "added", "months_used");

        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE referral_commission_lines SET amount_minor = 1 WHERE id = ?")
                        .param(first)
                        .update())
                .hasStackTraceContaining("only marked paid, once");
        UUID override =
                UUID.fromString(lines.get(1).get("rule_snapshot").get("rule_id").asString());
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE referral_commission_rules SET percent_bp = 5000 WHERE id = ?")
                        .param(override)
                        .update())
                .hasStackTraceContaining("append-only");
        assertThat(amounts(detail(rx))).containsExactly(6000L, 12000L, 500L);
    }

    /**
     * A paid mark while the referrer is inactive is refused unless confirmed, however the confirmation is left out: missing,
     * false, null, the text "false" or not a boolean. Nothing is marked and nothing audited; the reversal of a paid line is held back the same
     * way; a confirmed mark goes through once.
     */
    @Test
    void aPaidMarkForAnInactiveReferrerIsRefusedUnlessConfirmed() {
        Business referrer = business("ref-adv-inact");
        Business x = business("ref-adv-inact-x");
        setReferrer(x.id(), code(referrer.id()), "Test: referred by the shop");
        UUID rx = currentReferral(x.id());
        rule("/api/v1/platform/referrals/" + rx + "/rule", percent(1000, null), "Test: ten percent, no end");
        pay(x.id(), 10000, 1);
        pay(x.id(), 10000, 1);
        UUID line = UUID.fromString(detail(rx).get("lines").get(0).get("id").asString());
        UUID other = UUID.fromString(detail(rx).get("lines").get(1).get("id").asString());
        assertThat(markPaid(other, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID reversal = UUID.fromString(reverse(other, "Test: paid by mistake", null)
                .getBody()
                .get("lines")
                .get(2)
                .get("id")
                .asString());

        // The business is suspended while its subscription stays active.
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .param(referrer.id())
                .update();
        long paidBefore = audits("platform.referral.commission_paid");
        for (Object confirm : java.util.Arrays.asList(null, false)) {
            for (UUID target : List.of(line, reversal)) {
                ResponseEntity<JsonNode> refused = markPaid(target, confirm);
                assertThat(refused.getStatusCode())
                        .as("%s %s", confirm, refused.getBody())
                        .isEqualTo(HttpStatus.CONFLICT);
                assertThat(refused.getBody().get("code").asString()).isEqualTo("referrer_inactive");
            }
        }
        Map<String, Object> nullConfirm = new LinkedHashMap<>();
        nullConfirm.put("paid_on", LocalDate.now(KAMPALA).toString());
        nullConfirm.put("method", "cash");
        nullConfirm.put("reason", "Test: handed over");
        nullConfirm.put("confirm_referrer_inactive", null);
        assertThat(platform.post(
                                "/api/v1/platform/referrals/commissions/" + line + "/paid",
                                nullConfirm,
                                operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(markPaid(line, "yes").getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(markPaid(line, "false").getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(audits("platform.referral.commission_paid")).isEqualTo(paidBefore);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM referral_commission_lines WHERE id IN (?, ?) AND paid_on IS NOT NULL")
                        .params(line, reversal)
                        .query(Long.class)
                        .single())
                .isZero();

        assertThat(markPaid(line, true).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> twice = markPaid(line, true);
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(twice.getBody().get("code").asString()).isEqualTo("commission_already_paid");
        assertThat(audits("platform.referral.commission_paid")).isEqualTo(paidBefore + 1);
    }
}
