package com.rincoltech.bms.core.referrals;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
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

    @BeforeEach
    void setUp() {
        platform = Api.platform(http);
        String email = Api.email("ref-operator");
        operatorId = Api.platformUser(email);
        operator = platform.signIn(operatorId, email, Api.PASSWORD, null);
        int n = ADDRESS.getAndIncrement();
        ip = "203.0." + (n / 250) + "." + (n % 250 + 1);
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
        return platform.call(
                HttpMethod.PUT, "/api/v1/platform/tenants/" + tenantId + "/referrer", body, operator.accessToken());
    }

    ResponseEntity<JsonNode> pay(UUID tenantId, long amount, int months) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", amount);
        body.put("method", "mtn_momo");
        body.put("reference", "TEST-REF-" + UUID.randomUUID().toString().substring(0, 6));
        body.put("paid_on", LocalDate.now(KAMPALA).toString());
        body.put("months", months);
        body.put("reason", "Test: seen on the statement");
        body.put("confirm_unusual_amount", true);
        ResponseEntity<JsonNode> paid = platform.call(
                HttpMethod.POST,
                "/api/v1/platform/tenants/" + tenantId + "/payments",
                body,
                operator.accessToken(),
                Map.of("Idempotency-Key", "ref-pay-" + UUID.randomUUID()));
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        return paid;
    }

    ResponseEntity<JsonNode> rule(String path, Map<String, Object> rule, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rule", rule);
        body.put("reason", reason);
        return platform.call(HttpMethod.PUT, path, body, operator.accessToken());
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
     * The Owner or an administrator of a business cannot bring its code: their email (compared as the sign-up compares it) or
     * phone is refused at the sign-up with 422 referral_self and nothing is created; it is checked again at Activate, where no
     * referral is stored and the refusal is audited (decision 10).
     */
    @Test
    void theBusinesssOwnPeopleCannotUseItsCode() {
        TestDatabase.Fixture referrer = TestDatabase.tenant("refself", false);
        String code = code(referrer.tenantId());
        String adminEmail = Api.email("ref-self-admin");
        UUID admin = Api.staff(referrer, adminEmail, new Api.Role("tenant_admin", null));

        ResponseEntity<JsonNode> byEmail =
                apply(signUpForm(adminEmail.toUpperCase(Locale.ROOT), "Test Own Shop", code));
        assertThat(byEmail.getStatusCode().value()).as("%s", byEmail.getBody()).isEqualTo(422);
        assertThat(byEmail.getBody().get("code").asString()).isEqualTo("referral_self");
        assertThat(byEmail.getBody().toString()).contains("referral_code");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM onboarding_applications WHERE lower(contact_email) = lower(?)")
                        .param(adminEmail)
                        .query(Long.class)
                        .single())
                .isZero();

        String phone = "+256700000299";
        TestDatabase.owner()
                .sql("UPDATE users SET phone_e164 = ? WHERE id = ?")
                .params(phone, admin)
                .update();
        Map<String, Object> byPhone = signUpForm(Api.email("ref-self-phone"), "Test Own Phone Shop", code);
        byPhone.put("contact_phone", phone);
        ResponseEntity<JsonNode> refused = apply(byPhone);
        assertThat(refused.getStatusCode().value()).as("%s", refused.getBody()).isEqualTo(422);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("referral_self");

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
        ResponseEntity<JsonNode> typed = platform.post(
                "/api/v1/platform/referrals/" + ry + "/commissions",
                Map.of("amount_minor", 700, "currency", "UGX", "reason", "Test: a bonus agreed by phone"),
                operator.accessToken());
        assertThat(typed.getStatusCode()).as("%s", typed.getBody()).isEqualTo(HttpStatus.OK);
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

        // Back to no default, so later tests start from an empty rule.
        rule("/api/v1/platform/referrals/default-rule", null, "Test: clean up");
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
        platform.post(
                "/api/v1/platform/referrals/" + ry + "/commissions",
                Map.of("amount_minor", 900, "currency", "UGX", "reason", "Test: worked out by hand"),
                operator.accessToken());
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
}
