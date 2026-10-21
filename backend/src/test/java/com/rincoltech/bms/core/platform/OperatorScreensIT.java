package com.rincoltech.bms.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * The operator screens of issue #407 (ADR-056, ADR-024, FR-TEN-04, FR-TEN-05, FR-TEN-06, FR-SUB-01, FR-SPM-01): the
 * businesses list with search and filters, the business page, the plan and dates of a tenant, suspend and resume with a
 * reason, manual payments behind the Idempotency-Key, the price book and the trial clock. Every route is for platform
 * operators only: tenant staff and anonymous callers are refused, every change writes a platform audit row with the operator
 * and the reason, a repeat changes nothing, and nothing of one tenant shows on another's page. Fabricated data only.
 */
class OperatorScreensIT extends IntegrationTest {

    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");

    @Autowired
    TestRestTemplate http;

    Api platform;
    Session operator;
    UUID operatorId;
    String staffToken;

    @BeforeEach
    void setUp() {
        platform = Api.platform(http);
        String email = Api.email("operator");
        operatorId = Api.platformUser(email);
        operator = platform.signIn(operatorId, email, Api.PASSWORD, null);
        TestDatabase.Fixture t = TestDatabase.tenant("ops-staff", true);
        String staffEmail = Api.email("staff");
        UUID staff = Api.staff(t, staffEmail, new Api.Role("tenant_admin", null));
        staffToken = Api.tenant(http, t.slug())
                .signIn(staff, staffEmail, Api.PASSWORD, null)
                .accessToken();
    }

    record Created(UUID id, String slug, UUID adminId, String adminEmail, String invitationUrl) {}

    Created create(String prefix, List<String> modules, String plan) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        String adminEmail = Api.email("owner");
        ResponseEntity<JsonNode> created = platform.post(
                "/api/v1/platform/tenants",
                Map.of(
                        "name",
                        "Test Shop " + slug,
                        "slug",
                        slug,
                        "plan_code",
                        plan,
                        "modules",
                        modules,
                        "head_office",
                        Map.of("code", "HQ", "name", "Head Office"),
                        "admin",
                        Map.of("full_name", "Test Owner 07", "email", adminEmail)),
                operator.accessToken());
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode body = created.getBody();
        return new Created(
                UUID.fromString(body.get("tenant").get("id").asString()),
                slug,
                UUID.fromString(body.get("admin_invitation").get("user_id").asString()),
                adminEmail,
                body.get("admin_invitation").get("url").asString());
    }

    /** The owner accepts the invitation, which signs them in: the first staff sign-in. */
    Session ownerSignsIn(Created c) {
        Api tenant = Api.tenant(http, c.slug());
        tenant.post(
                "/api/v1/auth/staff/invitations/accept",
                Map.of(
                        "token",
                        c.invitationUrl().substring(c.invitationUrl().indexOf("#token=") + 7),
                        "password",
                        Api.PASSWORD),
                null);
        return tenant.signIn(c.adminId(), c.adminEmail(), Api.PASSWORD, null);
    }

    long audits(String action, UUID tenantId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM platform_audit_log WHERE action = ? AND tenant_id IS NOT DISTINCT FROM ?")
                .params(action, tenantId)
                .query(Long.class)
                .single();
    }

    String lastReason(String action, UUID tenantId) {
        return TestDatabase.owner()
                .sql("""
                        SELECT data ->> 'reason' FROM platform_audit_log WHERE action = ? AND tenant_id = ?
                         ORDER BY occurred_at DESC LIMIT 1
                        """)
                .params(action, tenantId)
                .query(String.class)
                .single();
    }

    UUID lastOperator(String action, UUID tenantId) {
        return TestDatabase.owner()
                .sql("""
                        SELECT platform_user_id FROM platform_audit_log WHERE action = ? AND tenant_id IS NOT DISTINCT FROM ?
                         ORDER BY occurred_at DESC LIMIT 1
                        """)
                .params(action, tenantId)
                .query(UUID.class)
                .single();
    }

    List<String> slugs(ResponseEntity<JsonNode> list) {
        assertThat(list.getStatusCode()).as("%s", list.getBody()).isEqualTo(HttpStatus.OK);
        List<String> out = new ArrayList<>();
        list.getBody().get("items").forEach(i -> out.add(i.get("slug").asString()));
        return out;
    }

    /** Tenant staff (a tenant admin's token) and anonymous callers are refused on every route of the issue. */
    void refusedToEveryoneButOperators(HttpMethod method, String path, Object body) {
        for (String token : new String[] {null, staffToken}) {
            ResponseEntity<JsonNode> refused =
                    platform.call(method, path, body, token, Map.of("Idempotency-Key", "k-refused-1"));
            assertThat(refused.getStatusCode())
                    .as("%s %s with %s", method, path, token == null ? "no token" : "staff")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void onlyOperatorsReachTheOperatorRoutes() {
        Created c = create("ops-auth", List.of("retail"), "growth");
        String t = "/api/v1/platform/tenants/" + c.id();
        refusedToEveryoneButOperators(HttpMethod.GET, "/api/v1/platform/tenants?q=x", null);
        refusedToEveryoneButOperators(HttpMethod.GET, t + "/overview", null);
        refusedToEveryoneButOperators(
                HttpMethod.PUT, t + "/plan", Map.of("plan_code", "starter", "reason", "Test reason"));
        refusedToEveryoneButOperators(
                HttpMethod.PUT,
                t + "/subscription-dates",
                Map.of("trial_ends_on", "2026-12-31", "reason", "Test reason"));
        refusedToEveryoneButOperators(HttpMethod.POST, t + "/suspend", Map.of("reason", "Test reason"));
        refusedToEveryoneButOperators(HttpMethod.POST, t + "/resume", Map.of("reason", "Test reason"));
        refusedToEveryoneButOperators(
                HttpMethod.POST,
                t + "/payments",
                Map.of("amount_minor", 1000, "method", "other", "paid_on", "2026-10-01", "months", 1));
        refusedToEveryoneButOperators(HttpMethod.GET, "/api/v1/platform/plans", null);
        refusedToEveryoneButOperators(HttpMethod.GET, "/api/v1/platform/plans/starter", null);
        refusedToEveryoneButOperators(
                HttpMethod.PUT, "/api/v1/platform/plans/ops-refused", plan("Refused", List.of("retail")));
        refusedToEveryoneButOperators(
                HttpMethod.POST,
                "/api/v1/platform/plans/starter/prices",
                Map.of("monthly_minor", 1000, "annual_months", 11, "reason", "Test reason"));
        refusedToEveryoneButOperators(
                HttpMethod.PUT,
                t + "/modules",
                Map.of("modules", List.of("retail", "lending"), "reason", "Test reason"));
        refusedToEveryoneButOperators(HttpMethod.PUT, t + "/pilot", Map.of("pilot", true, "reason", "Test reason"));
        // Nothing was changed by any refused call.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM platform_audit_log WHERE tenant_id = ? AND action <> 'platform.tenant.created'")
                        .param(c.id())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM plans WHERE code = 'ops-refused'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // JUSTIFICATION-A3: the review of #413 asked for this test of the tenant host.
    /**
     * A tenant admin on the business's own host, with its own valid token, reaches none of the operator routes: the platform
     * API is not served on a tenant host (404), and nothing changes.
     */
    @Test
    void aTenantAdminOnItsOwnHostReachesNoOperatorRoute() {
        Created c = create("ops-own", List.of("retail"), "growth");
        String own = ownerSignsIn(c).accessToken();
        Api onOwnHost = Api.platform(http).onHost(c.slug() + "-bms-staging.rincoltech.test");
        String t = "/api/v1/platform/tenants/" + c.id();
        Map<String, String> key = Map.of("Idempotency-Key", "k-own-host-1");
        List<ResponseEntity<JsonNode>> calls = List.of(
                onOwnHost.call(HttpMethod.GET, "/api/v1/platform/tenants", null, own, key),
                onOwnHost.call(HttpMethod.GET, t + "/overview", null, own, key),
                onOwnHost.call(HttpMethod.POST, t + "/resume", Map.of("reason", "Test reason"), own, key),
                onOwnHost.call(HttpMethod.PUT, t + "/pilot", Map.of("pilot", true, "reason", "Test reason"), own, key),
                onOwnHost.call(
                        HttpMethod.PUT,
                        t + "/subscription-dates",
                        Map.of("trial_ends_on", "2026-12-31", "reason", "Test reason"),
                        own,
                        key),
                onOwnHost.call(
                        HttpMethod.POST,
                        t + "/payments",
                        Map.of("amount_minor", 1000, "method", "other", "paid_on", "2026-10-01", "months", 1),
                        own,
                        key),
                onOwnHost.call(HttpMethod.GET, "/api/v1/platform/plans", null, own, key),
                onOwnHost.call(
                        HttpMethod.POST,
                        "/api/v1/platform/plans/starter/prices",
                        Map.of("monthly_minor", 1000, "annual_months", 11, "reason", "Test reason"),
                        own,
                        key));
        for (ResponseEntity<JsonNode> call : calls) {
            assertThat(call.getStatusCode()).as("%s", call.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM subscription_payments WHERE tenant_id = ?")
                        .param(c.id())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(audits("platform.tenant.pilot_changed", c.id())).isZero();
    }

    /** FR-TEN-05: the list shows plan, status, owner, address and last sign-in; search and the two filters narrow it. */
    @Test
    void theBusinessesListSearchesAndFilters() {
        Created retail = create("ops-list-r", List.of("retail"), "growth");
        Created lending = create("ops-list-l", List.of("lending"), "growth");
        ownerSignsIn(retail);

        ResponseEntity<JsonNode> all = platform.get("/api/v1/platform/tenants", operator.accessToken());
        assertThat(slugs(all)).contains(retail.slug(), lending.slug());
        JsonNode row = null;
        for (JsonNode i : all.getBody().get("items")) {
            if (i.get("slug").asString().equals(retail.slug())) {
                row = i;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("owner_email").asString()).isEqualTo(retail.adminEmail());
        assertThat(row.get("owner_name").asString()).isEqualTo("Test Owner 07");
        assertThat(row.get("plan_code").asString()).isEqualTo("growth");
        assertThat(row.get("plan_name").asString()).isEqualTo("Growth");
        assertThat(row.get("subscription_status").asString()).isEqualTo("trial");
        assertThat(row.get("last_sign_in_at").isNull()).isFalse();
        assertThat(row.get("address").asString())
                .isEqualTo("https://" + retail.slug() + "-bms-staging.rincoltech.test");

        assertThat(slugs(platform.get("/api/v1/platform/tenants?q=" + retail.slug(), operator.accessToken())))
                .containsExactly(retail.slug());
        assertThat(slugs(platform.get(
                        "/api/v1/platform/tenants?q=" + retail.adminEmail().toUpperCase(), operator.accessToken())))
                .containsExactly(retail.slug());
        assertThat(slugs(platform.get("/api/v1/platform/tenants?q=%25", operator.accessToken())))
                .as("a percent sign is a character, not a wildcard")
                .doesNotContain(retail.slug(), lending.slug());
        assertThat(slugs(platform.get("/api/v1/platform/tenants?module=lending", operator.accessToken())))
                .contains(lending.slug())
                .doesNotContain(retail.slug());

        platform.post(
                "/api/v1/platform/tenants/" + lending.id() + "/suspend",
                Map.of("reason", "Test suspension for the filter"),
                operator.accessToken());
        assertThat(slugs(platform.get("/api/v1/platform/tenants?status=suspended", operator.accessToken())))
                .contains(lending.slug())
                .doesNotContain(retail.slug());
        assertThat(slugs(platform.get("/api/v1/platform/tenants?status=trial&status=active", operator.accessToken())))
                .contains(retail.slug())
                .doesNotContain(lending.slug());
        assertThat(platform.get("/api/v1/platform/tenants?status=paused", operator.accessToken())
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
    }

    /** Suspended means read only, never deleted; suspend and resume need a reason, are audited with it, and repeat as no-ops. */
    @Test
    void suspendAndResumeTakeAReasonAndRepeatHarmlessly() {
        Created c = create("ops-susp", List.of("retail"), "growth");
        String t = "/api/v1/platform/tenants/" + c.id();
        LocalDate trialEnd = LocalDate.now(KAMPALA).plusDays(12);
        TestDatabase.owner()
                .sql("UPDATE subscriptions SET trial_ends_on = ? WHERE tenant_id = ?")
                .params(trialEnd, c.id())
                .update();
        ResponseEntity<JsonNode> noReason = platform.post(t + "/suspend", Map.of(), operator.accessToken());
        assertThat(noReason.getStatusCode().value()).isIn(400, 422);
        assertThat(audits("platform.tenant.subscription_changed", c.id())).isZero();

        ResponseEntity<JsonNode> suspended =
                platform.post(t + "/suspend", Map.of("reason", "Test: unpaid after the trial"), operator.accessToken());
        assertThat(suspended.getStatusCode()).as("%s", suspended.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(suspended.getBody().get("status").asString()).isEqualTo("suspended");
        assertThat(suspended.getBody().get("subscription_status").asString()).isEqualTo("suspended");
        assertThat(platform.post(t + "/suspend", Map.of("reason", "Test: pressed twice"), operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(audits("platform.tenant.subscription_changed", c.id())).isEqualTo(1);
        assertThat(lastReason("platform.tenant.subscription_changed", c.id()))
                .isEqualTo("Test: unpaid after the trial");
        assertThat(lastOperator("platform.tenant.subscription_changed", c.id())).isEqualTo(operatorId);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM branches WHERE tenant_id = ?")
                        .param(c.id())
                        .query(Long.class)
                        .single())
                .as("nothing is deleted")
                .isEqualTo(1);

        ResponseEntity<JsonNode> resumed =
                platform.post(t + "/resume", Map.of("reason", "Test: paid by mobile money"), operator.accessToken());
        assertThat(resumed.getBody().get("status").asString()).isEqualTo("active");
        assertThat(resumed.getBody().get("subscription_status").asString())
                .as("suspended in its free month, it comes back to its free month")
                .isEqualTo("trial");
        assertThat(resumed.getBody().get("trial_ends_on").asString()).isEqualTo(trialEnd.toString());
        assertThat(resumed.getBody().get("suspended_from").isNull()).isTrue();
        platform.post(t + "/resume", Map.of("reason", "Test: pressed twice"), operator.accessToken());
        assertThat(audits("platform.tenant.subscription_changed", c.id())).isEqualTo(2);

        JsonNode history =
                platform.get(t + "/overview", operator.accessToken()).getBody().get("history");
        assertThat(history.toString()).contains("Test: paid by mobile money", "Test: unpaid after the trial");
    }

    /** FR-TEN-04: a plan change is refused when the business would not fit; allowed and audited otherwise; a repeat is a no-op. */
    @Test
    void thePlanOfABusinessChangesOnlyToOneItFits() {
        String code = "ops-one-shop-" + UUID.randomUUID().toString().substring(0, 6);
        assertThat(platform.call(
                                HttpMethod.PUT,
                                "/api/v1/platform/plans/" + code,
                                planWith("Test One Shop", List.of("retail"), 1, 3),
                                operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        String lendingOnly = "ops-lend-" + UUID.randomUUID().toString().substring(0, 6);
        platform.call(
                HttpMethod.PUT,
                "/api/v1/platform/plans/" + lendingOnly,
                plan("Test Lending", List.of("lending")),
                operator.accessToken());

        Created c = create("ops-plan", List.of("retail"), "growth");
        String path = "/api/v1/platform/tenants/" + c.id() + "/plan";
        ResponseEntity<JsonNode> modules = platform.call(
                HttpMethod.PUT, path, Map.of("plan_code", lendingOnly, "reason", "Test move"), operator.accessToken());
        assertThat(modules.getStatusCode().value()).isEqualTo(422);
        assertThat(modules.getBody().get("code").asString()).isEqualTo("plan_modules_not_allowed");

        TestDatabase.owner()
                .sql("INSERT INTO branches (id, tenant_id, code, name) VALUES (?, ?, 'S2', 'Test Shop Two')")
                .params(UUID.randomUUID(), c.id())
                .update();
        ResponseEntity<JsonNode> branches = platform.call(
                HttpMethod.PUT, path, Map.of("plan_code", code, "reason", "Test move"), operator.accessToken());
        assertThat(branches.getBody().get("code").asString()).isEqualTo("plan_too_small_branches");
        assertThat(audits("platform.tenant.plan_changed", c.id())).isZero();

        TestDatabase.owner()
                .sql("UPDATE branches SET status = 'inactive' WHERE tenant_id = ? AND code = 'S2'")
                .param(c.id())
                .update();
        ResponseEntity<JsonNode> moved = platform.call(
                HttpMethod.PUT,
                path,
                Map.of("plan_code", code, "reason", "Test: one shop only"),
                operator.accessToken());
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(moved.getBody().get("plan_code").asString()).isEqualTo(code);
        platform.call(HttpMethod.PUT, path, Map.of("plan_code", code, "reason", "Test again"), operator.accessToken());
        assertThat(audits("platform.tenant.plan_changed", c.id())).isEqualTo(1);
        assertThat(lastReason("platform.tenant.plan_changed", c.id())).isEqualTo("Test: one shop only");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT p.code FROM subscriptions s JOIN plans p ON p.id = s.plan_id WHERE s.tenant_id = ?")
                        .param(c.id())
                        .query(String.class)
                        .single())
                .isEqualTo(code);
    }

    /** Spec section 4.2: the free month starts at the first staff sign-in, once, and the operator can move its end with a reason. */
    @Test
    void theTrialClockStartsAtTheFirstSignInAndCanBeMovedByHand() {
        Created c = create("ops-trial", List.of("retail"), "growth");
        String t = "/api/v1/platform/tenants/" + c.id();
        assertThat(platform.get(t, operator.accessToken())
                        .getBody()
                        .get("trial_ends_on")
                        .isNull())
                .as("no clock before anyone signs in")
                .isTrue();
        ownerSignsIn(c);
        LocalDate today = LocalDate.now(KAMPALA);
        LocalDate expected = today.plusMonths(1).minusDays(1);
        assertThat(LocalDate.parse(platform.get(t, operator.accessToken())
                        .getBody()
                        .get("trial_ends_on")
                        .asString()))
                .isIn(expected, expected.plusDays(1));
        ownerSignsIn(c);
        assertThat(audits("platform.tenant.trial_started", c.id())).isEqualTo(1);

        String dates = t + "/subscription-dates";
        assertThat(platform.call(HttpMethod.PUT, dates, Map.of("reason", "Test nothing"), operator.accessToken())
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        ResponseEntity<JsonNode> moved = platform.call(
                HttpMethod.PUT,
                dates,
                Map.of("trial_ends_on", today.plusDays(60).toString(), "reason", "Test: two more weeks agreed"),
                operator.accessToken());
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(moved.getBody().get("trial_ends_on").asString())
                .isEqualTo(today.plusDays(60).toString());
        platform.call(
                HttpMethod.PUT,
                dates,
                Map.of("trial_ends_on", today.plusDays(60).toString(), "reason", "Test again"),
                operator.accessToken());
        assertThat(audits("platform.tenant.subscription_dates_changed", c.id())).isEqualTo(1);
        assertThat(lastReason("platform.tenant.subscription_dates_changed", c.id()))
                .isEqualTo("Test: two more weeks agreed");
    }

    Map<String, Object> payment(long amount, int months, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", amount);
        body.put("method", "mtn_momo");
        body.put("reference", reference);
        body.put("paid_on", LocalDate.now(KAMPALA).toString());
        body.put("months", months);
        body.put("reason", "Test: seen on the statement");
        return body;
    }

    /**
     * ADR-024 decision 4: a payment needs an Idempotency-Key, starts after the free month, makes the subscription active,
     * is audited, and a repeat with the same key records nothing. It is the tenant's own row: another tenant's page never
     * shows it.
     */
    @Test
    void aPaymentExtendsWhatIsPaidOnceAndStaysWithItsTenant() {
        String plan = pricedPlan();
        Created a = create("ops-pay-a", List.of("retail"), plan);
        Created b = create("ops-pay-b", List.of("retail"), plan);
        LocalDate today = LocalDate.now(KAMPALA);
        TestDatabase.owner()
                .sql("UPDATE subscriptions SET trial_ends_on = ? WHERE tenant_id = ?")
                .params(today.plusDays(9), a.id())
                .update();
        String path = "/api/v1/platform/tenants/" + a.id() + "/payments";

        ResponseEntity<JsonNode> noKey = platform.post(path, payment(10000, 1, "TEST-REF-01"), operator.accessToken());
        assertThat(noKey.getBody().get("code").asString()).isEqualTo("idempotency_key_missing");

        String key = "pay-" + UUID.randomUUID();
        ResponseEntity<JsonNode> first = platform.call(
                HttpMethod.POST,
                path,
                payment(10000, 1, "TEST-REF-01"),
                operator.accessToken(),
                Map.of("Idempotency-Key", key));
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("period_start").asString())
                .isEqualTo(today.plusDays(10).toString());
        assertThat(first.getBody().get("period_end").asString())
                .isEqualTo(today.plusDays(10).plusMonths(1).minusDays(1).toString());
        assertThat(first.getBody().get("currency").asString()).isEqualTo("UGX");
        assertThat(first.getBody().get("status").asString()).isEqualTo("confirmed");

        ResponseEntity<JsonNode> again = platform.call(
                HttpMethod.POST,
                path,
                payment(10000, 1, "TEST-REF-01"),
                operator.accessToken(),
                Map.of("Idempotency-Key", key));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("id").asString())
                .isEqualTo(first.getBody().get("id").asString());
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM subscription_payments WHERE tenant_id = ?")
                        .param(a.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(audits("platform.tenant.payment_recorded", a.id())).isEqualTo(1);
        assertThat(lastOperator("platform.tenant.payment_recorded", a.id())).isEqualTo(operatorId);

        ResponseEntity<JsonNode> year = platform.call(
                HttpMethod.POST,
                path,
                payment(100000, 12, "TEST-REF-02"),
                operator.accessToken(),
                Map.of("Idempotency-Key", "pay-" + UUID.randomUUID()));
        assertThat(year.getBody().get("period_start").asString())
                .isEqualTo(today.plusDays(10).plusMonths(1).toString());

        JsonNode pageA = platform.get("/api/v1/platform/tenants/" + a.id() + "/overview", operator.accessToken())
                .getBody();
        assertThat(pageA.get("tenant").get("subscription_status").asString()).isEqualTo("active");
        assertThat(pageA.get("tenant").get("paid_until").asString())
                .isEqualTo(year.getBody().get("period_end").asString());
        assertThat(pageA.get("payments")).hasSize(2);
        JsonNode pageB = platform.get("/api/v1/platform/tenants/" + b.id() + "/overview", operator.accessToken())
                .getBody();
        assertThat(pageB.get("payments")).isEmpty();
        assertThat(pageB.get("tenant").get("subscription_status").asString()).isEqualTo("trial");

        Map<String, Object> future = payment(10000, 1, "TEST-REF-03");
        future.put("paid_on", today.plusDays(1).toString());
        assertThat(platform.call(
                                HttpMethod.POST,
                                path,
                                future,
                                operator.accessToken(),
                                Map.of("Idempotency-Key", "pay-" + UUID.randomUUID()))
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
    }

    /** The business page: the owner for the sign-in rescue, the messages and the history of that business only. */
    @Test
    void theBusinessPageShowsItsOwnAdminsAndMessagesOnly() {
        Created a = create("ops-page-a", List.of("retail"), "growth");
        Created b = create("ops-page-b", List.of("retail"), "growth");
        TestDatabase.owner()
                .sql("""
                        INSERT INTO notification_outbox (id, channel, recipient, template_key, idempotency_key, tenant_id, category)
                        VALUES (?, 'email', 'owner-a@example.test', 'identity.password_reset_issued', ?, ?, 'transactional')
                        """)
                .params(UUID.randomUUID(), "ops-page:" + UUID.randomUUID(), a.id())
                .update();

        ResponseEntity<JsonNode> page =
                platform.get("/api/v1/platform/tenants/" + a.id() + "/overview", operator.accessToken());
        assertThat(page.getStatusCode()).as("%s", page.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode admins = page.getBody().get("admins");
        assertThat(admins).hasSize(1);
        assertThat(admins.get(0).get("id").asString()).isEqualTo(a.adminId().toString());
        assertThat(admins.get(0).get("email").asString()).isEqualTo(a.adminEmail());
        JsonNode messages = page.getBody().get("messages");
        assertThat(messages.toString())
                .contains("identity.password_reset_issued")
                .doesNotContain("owner-a@example.test");
        assertThat(page.getBody().get("history").toString()).contains("platform.tenant.created");

        JsonNode other = platform.get("/api/v1/platform/tenants/" + b.id() + "/overview", operator.accessToken())
                .getBody();
        assertThat(other.get("admins").get(0).get("id").asString())
                .isEqualTo(b.adminId().toString());
        assertThat(other.get("messages").toString()).doesNotContain("identity.password_reset_issued");

        assertThat(platform.get("/api/v1/platform/tenants/" + UUID.randomUUID() + "/overview", operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    Map<String, Object> plan(String name, List<String> modules) {
        return planWith(name, modules, null, null);
    }

    Map<String, Object> planWith(String name, List<String> modules, Integer branches, Integer staff) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", "Test plan");
        body.put("max_branches", branches);
        body.put("max_staff_users", staff);
        body.put("allowed_modules", modules);
        body.put("active", true);
        body.put("sort_order", 5);
        body.put("reason", "Test: a plan for a test");
        return body;
    }

    /** A retail plan with limits and a price of 10,000 a month, a year at 11 months: invented figures. */
    String pricedPlan() {
        String code = "ops-priced-" + UUID.randomUUID().toString().substring(0, 6);
        ResponseEntity<JsonNode> made = platform.call(
                HttpMethod.PUT,
                "/api/v1/platform/plans/" + code,
                planWith("Test Priced", List.of("retail"), null, null),
                operator.accessToken());
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> priced = platform.post(
                "/api/v1/platform/plans/" + code + "/prices",
                Map.of("monthly_minor", 10000, "annual_months", 11, "reason", "Test price"),
                operator.accessToken());
        assertThat(priced.getStatusCode()).as("%s", priced.getBody()).isEqualTo(HttpStatus.OK);
        return code;
    }

    ResponseEntity<JsonNode> pay(Created c, Map<String, Object> body) {
        return platform.call(
                HttpMethod.POST,
                "/api/v1/platform/tenants/" + c.id() + "/payments",
                body,
                operator.accessToken(),
                Map.of("Idempotency-Key", "pay-" + UUID.randomUUID()));
    }

    /**
     * ADR-056: a business that pays before anyone has signed in keeps its free month. The free month starts at the
     * payment and the paid months follow it; the start is audited once and a later first sign-in changes nothing.
     */
    @Test
    void aPaymentBeforeTheFirstSignInStartsTheFreeMonthAndPaysAfterIt() {
        Created c = create("ops-early", List.of("retail"), pricedPlan());
        LocalDate today = LocalDate.now(KAMPALA);
        ResponseEntity<JsonNode> paid = pay(c, payment(10000, 1, "TEST-REF-11"));
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        LocalDate freeEnds = today.plusMonths(1).minusDays(1);
        assertThat(paid.getBody().get("period_start").asString())
                .isEqualTo(freeEnds.plusDays(1).toString());
        assertThat(paid.getBody().get("period_end").asString())
                .isEqualTo(freeEnds.plusDays(1).plusMonths(1).minusDays(1).toString());
        JsonNode tenant = platform.get("/api/v1/platform/tenants/" + c.id(), operator.accessToken())
                .getBody();
        assertThat(tenant.get("trial_ends_on").asString()).isEqualTo(freeEnds.toString());
        assertThat(tenant.get("subscription_status").asString()).isEqualTo("active");
        assertThat(audits("platform.tenant.trial_started", c.id())).isEqualTo(1);
        ownerSignsIn(c);
        assertThat(platform.get("/api/v1/platform/tenants/" + c.id(), operator.accessToken())
                        .getBody()
                        .get("trial_ends_on")
                        .asString())
                .isEqualTo(freeEnds.toString());
        assertThat(audits("platform.tenant.trial_started", c.id())).isEqualTo(1);
    }

    /**
     * ADR-056: an amount far from the plan's price for those months, or on a plan with no price, needs the operator's
     * explicit confirmation; an amount above the cap is never taken.
     */
    @Test
    void anUnusualPaymentNeedsAConfirmationAndTheCapHolds() {
        Created priced = create("ops-odd", List.of("retail"), pricedPlan());
        ResponseEntity<JsonNode> odd = pay(priced, payment(100000, 1, "TEST-REF-21"));
        assertThat(odd.getStatusCode().value()).isEqualTo(422);
        assertThat(odd.getBody().get("code").asString()).isEqualTo("payment_amount_unusual");
        assertThat(odd.getBody().get("detail").asString())
                .contains("UGX 10,000", "UGX 100,000")
                .doesNotContain("minor units");
        Map<String, Object> confirmed = payment(100000, 1, "TEST-REF-21");
        confirmed.put("confirm_unusual_amount", true);
        assertThat(pay(priced, confirmed).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(pay(priced, payment(12000, 1, "TEST-REF-22")).getStatusCode())
                .as("within a quarter of the price")
                .isEqualTo(HttpStatus.CREATED);

        Map<String, Object> overCap = payment(50_000_001L, 1, "TEST-REF-23");
        overCap.put("confirm_unusual_amount", true);
        assertThat(pay(priced, overCap).getStatusCode().value()).isIn(400, 422);

        Created unpriced = create("ops-noprice", List.of("retail"), "growth");
        assertThat(pay(unpriced, payment(10000, 1, "TEST-REF-24"))
                        .getBody()
                        .get("code")
                        .asString())
                .as("no price to check against")
                .isEqualTo("payment_amount_unusual");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM subscription_payments WHERE tenant_id = ?")
                        .param(unpriced.id())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** An active business suspended and resumed comes back active; a cancelled one keeps what it was before the suspend. */
    @Test
    void resumeBringsBackTheStatusFromBeforeTheSuspend() {
        Created c = create("ops-back", List.of("retail"), "growth");
        String t = "/api/v1/platform/tenants/" + c.id();
        platform.post(
                t + "/subscription",
                Map.of("status", "past_due", "reason", "Test: late payment"),
                operator.accessToken());
        platform.post(t + "/suspend", Map.of("reason", "Test: still late"), operator.accessToken());
        ResponseEntity<JsonNode> cancelled = platform.post(
                t + "/subscription", Map.of("status", "cancelled", "reason", "Test: closing"), operator.accessToken());
        assertThat(cancelled.getBody().get("suspended_from").asString()).isEqualTo("past_due");
        ResponseEntity<JsonNode> back =
                platform.post(t + "/resume", Map.of("reason", "Test: came back"), operator.accessToken());
        assertThat(back.getBody().get("subscription_status").asString()).isEqualTo("past_due");
        assertThat(back.getBody().get("status").asString()).isEqualTo("active");
    }

    /** ADR-024 decision 6: a pilot has no free month end and no clock; the mark is audited with its reason. */
    @Test
    void aPilotHasNoFreeMonthEnd() {
        Created c = create("ops-pilot", List.of("retail"), "growth");
        String t = "/api/v1/platform/tenants/" + c.id();
        ResponseEntity<JsonNode> noReason =
                platform.call(HttpMethod.PUT, t + "/pilot", Map.of("pilot", true), operator.accessToken());
        assertThat(noReason.getStatusCode().value()).isIn(400, 422);
        ResponseEntity<JsonNode> marked = platform.call(
                HttpMethod.PUT,
                t + "/pilot",
                Map.of("pilot", true, "reason", "Test: pilot business"),
                operator.accessToken());
        assertThat(marked.getStatusCode()).as("%s", marked.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(marked.getBody().get("pilot").asBoolean()).isTrue();
        platform.call(
                HttpMethod.PUT, t + "/pilot", Map.of("pilot", true, "reason", "Test: again"), operator.accessToken());
        assertThat(audits("platform.tenant.pilot_changed", c.id())).isEqualTo(1);
        assertThat(lastReason("platform.tenant.pilot_changed", c.id())).isEqualTo("Test: pilot business");
        ownerSignsIn(c);
        assertThat(platform.get(t, operator.accessToken())
                        .getBody()
                        .get("trial_ends_on")
                        .isNull())
                .as("a pilot's first sign-in starts no clock")
                .isTrue();
        assertThat(audits("platform.tenant.trial_started", c.id())).isZero();
    }

    /** The dates are bounded (from 2026-01-01, at most five years ahead) and can be cleared, with a reason. */
    @Test
    void theDatesAreBoundedAndCanBeCleared() {
        Created c = create("ops-dates", List.of("retail"), "growth");
        String dates = "/api/v1/platform/tenants/" + c.id() + "/subscription-dates";
        LocalDate today = LocalDate.now(KAMPALA);
        for (String day : List.of("2025-12-31", today.plusYears(5).plusDays(1).toString())) {
            ResponseEntity<JsonNode> refused = platform.call(
                    HttpMethod.PUT,
                    dates,
                    Map.of("trial_ends_on", day, "reason", "Test bound"),
                    operator.accessToken());
            assertThat(refused.getStatusCode().value()).as(day).isEqualTo(422);
        }
        platform.call(
                HttpMethod.PUT,
                dates,
                Map.of("trial_ends_on", today.plusDays(5).toString(), "reason", "Test set"),
                operator.accessToken());
        ResponseEntity<JsonNode> cleared = platform.call(
                HttpMethod.PUT,
                dates,
                Map.of("clear_trial_ends_on", true, "reason", "Test: start again at sign-in"),
                operator.accessToken());
        assertThat(cleared.getStatusCode()).as("%s", cleared.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody().get("trial_ends_on").isNull()).isTrue();
        assertThat(audits("platform.tenant.subscription_dates_changed", c.id())).isEqualTo(2);
    }

    /** A sender's error text is shown with any address in it masked. */
    @Test
    void anErrorTextOnTheBusinessPageHasItsAddressesMasked() {
        Created c = create("ops-mask", List.of("retail"), "growth");
        TestDatabase.owner()
                .sql("""
                        INSERT INTO notification_outbox (id, channel, recipient, template_key, idempotency_key, tenant_id,
                                                         category, status, last_error, failed_at)
                        VALUES (?, 'email', 'owner-z@example.test', 'onboarding.activation', ?, ?, 'transactional', 'failed',
                                '550 mailbox owner-z@example.test unknown', now())
                        """)
                .params(UUID.randomUUID(), "ops-mask:" + UUID.randomUUID(), c.id())
                .update();
        String messages = platform.get("/api/v1/platform/tenants/" + c.id() + "/overview", operator.accessToken())
                .getBody()
                .get("messages")
                .toString();
        assertThat(messages).contains("550 mailbox").doesNotContain("owner-z@example.test");
    }

    /** FR-SUB-01: a plan is created and changed by its code, a price is added from a date, and repeats change nothing. */
    @Test
    void thePriceBookIsEnteredByTheOperatorAndNeverRewritesThePast() {
        String code = "ops-plan-" + UUID.randomUUID().toString().substring(0, 6);
        String path = "/api/v1/platform/plans/" + code;
        ResponseEntity<JsonNode> created = platform.call(
                HttpMethod.PUT, path, planWith("Test Starter", List.of("retail"), 1, 3), operator.accessToken());
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("plan").get("current_price").isNull()).isTrue();
        assertThat(platform.call(
                                HttpMethod.PUT,
                                path,
                                planWith("Test Starter", List.of("retail"), 1, 3),
                                operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(audits("platform.plan.created", null)).isPositive();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM platform_audit_log WHERE action IN ('platform.plan.created', 'platform.plan.changed') AND data ->> 'plan_code' = ?")
                        .param(code)
                        .query(Long.class)
                        .single())
                .as("the repeat wrote nothing")
                .isEqualTo(1);
        assertThat(lastOperator("platform.plan.created", null)).isEqualTo(operatorId);

        String prices = path + "/prices";
        ResponseEntity<JsonNode> priced = platform.post(
                prices,
                Map.of("monthly_minor", 10000, "annual_months", 11, "reason", "Test price"),
                operator.accessToken());
        assertThat(priced.getStatusCode()).as("%s", priced.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode current = priced.getBody().get("plan").get("current_price");
        assertThat(current.get("monthly_minor").asLong()).isEqualTo(10000);
        assertThat(current.get("annual_minor").asLong()).isEqualTo(110000);
        platform.post(
                prices,
                Map.of("monthly_minor", 10000, "annual_months", 11, "reason", "Test same price"),
                operator.accessToken());
        ResponseEntity<JsonNode> noReason =
                platform.post(prices, Map.of("monthly_minor", 12000, "annual_months", 11), operator.accessToken());
        assertThat(noReason.getStatusCode().value())
                .as("a price needs a reason")
                .isIn(400, 422);
        ResponseEntity<JsonNode> tooHigh = platform.post(
                prices,
                Map.of("monthly_minor", 50_000_001L, "annual_months", 11, "reason", "Test typing slip"),
                operator.accessToken());
        assertThat(tooHigh.getStatusCode().value()).as("over the cap").isIn(400, 422);
        LocalDate today = LocalDate.now(KAMPALA);
        ResponseEntity<JsonNode> past = platform.post(
                prices,
                Map.of(
                        "monthly_minor",
                        1000,
                        "annual_months",
                        10,
                        "effective_from",
                        today.minusDays(2).toString(),
                        "reason",
                        "Test past price"),
                operator.accessToken());
        assertThat(past.getStatusCode().value()).isEqualTo(422);
        Map<String, Object> laterPrice = new LinkedHashMap<>();
        laterPrice.put("monthly_minor", 15000);
        laterPrice.put("annual_months", 10);
        laterPrice.put("effective_from", today.plusDays(30).toString());
        laterPrice.put("reason", "Test later price");
        ResponseEntity<JsonNode> unconfirmed = platform.post(prices, laterPrice, operator.accessToken());
        assertThat(unconfirmed.getStatusCode().value())
                .as("half again the price in force needs a confirmation")
                .isEqualTo(422);
        assertThat(unconfirmed.getBody().get("code").asString()).isEqualTo("price_change_unusual");
        assertThat(unconfirmed.getBody().get("detail").asString()).contains("UGX 10,000", "UGX 15,000");
        laterPrice.put("confirm_large_change", true);
        ResponseEntity<JsonNode> later = platform.post(prices, laterPrice, operator.accessToken());
        assertThat(later.getStatusCode()).as("%s", later.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(later.getBody()
                        .get("plan")
                        .get("current_price")
                        .get("monthly_minor")
                        .asLong())
                .isEqualTo(10000);
        assertThat(later.getBody()
                        .get("plan")
                        .get("next_price")
                        .get("monthly_minor")
                        .asLong())
                .isEqualTo(15000);
        assertThat(later.getBody().get("prices")).hasSize(2);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM plan_prices pp JOIN plans p ON p.id = pp.plan_id WHERE p.code = ?")
                        .param(code)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);

        Created c = create("ops-price", List.of("retail"), code);
        assertThat(platform.get("/api/v1/platform/tenants/" + c.id() + "/overview", operator.accessToken())
                        .getBody()
                        .get("plan_price")
                        .get("monthly_minor")
                        .asLong())
                .isEqualTo(10000);
        ResponseEntity<JsonNode> inUse = platform.call(
                HttpMethod.PUT, path, planWith("Test Starter", List.of("lending"), 1, 3), operator.accessToken());
        assertThat(inUse.getBody().get("code").asString()).isEqualTo("plan_module_in_use");

        List<String> active = new ArrayList<>();
        platform.get("/api/v1/platform/plans", operator.accessToken())
                .getBody()
                .get("items")
                .forEach(p -> active.add(p.get("code").asString()));
        assertThat(active).contains(code);
        Map<String, Object> retired = planWith("Test Starter", List.of("retail"), 1, 3);
        retired.put("active", false);
        platform.call(HttpMethod.PUT, path, retired, operator.accessToken());
        List<String> after = new ArrayList<>();
        platform.get("/api/v1/platform/plans", operator.accessToken())
                .getBody()
                .get("items")
                .forEach(p -> after.add(p.get("code").asString()));
        assertThat(after).doesNotContain(code);
        assertThat(platform.get("/api/v1/platform/plans?include_inactive=true", operator.accessToken())
                        .getBody()
                        .toString())
                .contains(code);
    }

    /**
     * Review of #413, must fix 1: a payment on a suspended business sets what a resume brings back to active. A business
     * suspended past due, or in its free month, that pays and is resumed is active, not past due or on trial.
     */
    @Test
    void aBusinessThatPaysWhileSuspendedComesBackActive() {
        String plan = pricedPlan();
        for (String before : List.of("past_due", "trial")) {
            Created c = create("ops-paysusp", List.of("retail"), plan);
            String t = "/api/v1/platform/tenants/" + c.id();
            if (!before.equals("trial")) {
                platform.post(
                        t + "/subscription",
                        Map.of("status", before, "reason", "Test: late payment"),
                        operator.accessToken());
            }
            ResponseEntity<JsonNode> suspended =
                    platform.post(t + "/suspend", Map.of("reason", "Test: unpaid"), operator.accessToken());
            assertThat(suspended.getBody().get("suspended_from").asString()).isEqualTo(before);
            ResponseEntity<JsonNode> paid = pay(c, payment(10000, 1, "TEST-REF-31"));
            assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
            JsonNode after = platform.get(t, operator.accessToken()).getBody();
            assertThat(after.get("subscription_status").asString())
                    .as("still suspended until the operator resumes it")
                    .isEqualTo("suspended");
            assertThat(after.get("suspended_from").asString()).isEqualTo("active");
            ResponseEntity<JsonNode> back =
                    platform.post(t + "/resume", Map.of("reason", "Test: paid, back on"), operator.accessToken());
            assertThat(back.getBody().get("subscription_status").asString())
                    .as("suspended from %s, paid, resumed", before)
                    .isEqualTo("active");
            assertThat(back.getBody().get("status").asString()).isEqualTo("active");
        }
    }

    /** Review of #413, must fix 3: a payment needs a reason (5 to 300 characters), and the audit keeps it. */
    @Test
    void aPaymentNeedsAReasonAndTheAuditKeepsIt() {
        Created c = create("ops-payreason", List.of("retail"), pricedPlan());
        for (Object reason : new Object[] {null, "abc"}) {
            Map<String, Object> body = payment(10000, 1, "TEST-REF-41");
            body.put("reason", reason);
            assertThat(pay(c, body).getStatusCode().value())
                    .as("reason %s", reason)
                    .isIn(400, 422);
        }
        assertThat(audits("platform.tenant.payment_recorded", c.id())).isZero();
        Map<String, Object> body = payment(10000, 1, "TEST-REF-42");
        body.put("reason", "Test: owner sent the receipt");
        assertThat(pay(c, body).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lastReason("platform.tenant.payment_recorded", c.id())).isEqualTo("Test: owner sent the receipt");
    }

    /** Review of #413, must fix 2: a plan move counts the active lending members against the plan's member limit. */
    @Test
    void aPlanMoveChecksTheActiveLendingMembers() {
        String small = "ops-lend-small-" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> body = plan("Test Small Lending", List.of("lending"));
        body.put("max_active_members", 1);
        assertThat(platform.call(HttpMethod.PUT, "/api/v1/platform/plans/" + small, body, operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        Created c = create("ops-members", List.of("lending"), "growth");
        UUID branch = TestDatabase.owner()
                .sql("SELECT id FROM branches WHERE tenant_id = ? LIMIT 1")
                .param(c.id())
                .query(UUID.class)
                .single();
        for (int i = 1; i <= 2; i++) {
            TestDatabase.owner()
                    .sql("""
                            INSERT INTO lending_members (id, tenant_id, branch_id, member_no, full_name, phone_e164, id_type,
                                                         national_id, currency, kyc_status, status, source)
                            VALUES (?, ?, ?, ?, ?, '+256700000001', 'nin', ?, 'UGX', 'incomplete', 'active', 'staff')
                            """)
                    .params(
                            UUID.randomUUID(),
                            c.id(),
                            branch,
                            "M" + i,
                            "Test Member " + i,
                            "CM"
                                    + UUID.randomUUID()
                                            .toString()
                                            .replace("-", "")
                                            .substring(0, 12)
                                            .toUpperCase())
                    .update();
        }
        String path = "/api/v1/platform/tenants/" + c.id() + "/plan";
        ResponseEntity<JsonNode> refused = platform.call(
                HttpMethod.PUT, path, Map.of("plan_code", small, "reason", "Test move"), operator.accessToken());
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("plan_too_small_members");
        assertThat(refused.getBody().get("detail").asString()).contains("active lending members");
        assertThat(audits("platform.tenant.plan_changed", c.id())).isZero();

        TestDatabase.owner()
                .sql("UPDATE lending_members SET status = 'exited' WHERE tenant_id = ? AND member_no = 'M2'")
                .param(c.id())
                .update();
        ResponseEntity<JsonNode> moved = platform.call(
                HttpMethod.PUT,
                path,
                Map.of("plan_code", small, "reason", "Test: one member now"),
                operator.accessToken());
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** Marking a pilot clears a scheduled status change, and the dates screen refuses a free month end on a pilot. */
    @Test
    void aPilotHasNoScheduledChangeAndNoFreeMonthEndByHand() {
        Created c = create("ops-pilot2", List.of("retail"), "growth");
        String t = "/api/v1/platform/tenants/" + c.id();
        LocalDate later = LocalDate.now(KAMPALA).plusDays(20);
        platform.post(
                t + "/subscription",
                Map.of("status", "trial", "next_status_change_on", later.toString(), "reason", "Test: review later"),
                operator.accessToken());
        assertThat(platform.get(t, operator.accessToken())
                        .getBody()
                        .get("next_status_change_on")
                        .asString())
                .isEqualTo(later.toString());
        ResponseEntity<JsonNode> marked = platform.call(
                HttpMethod.PUT,
                t + "/pilot",
                Map.of("pilot", true, "reason", "Test: pilot business"),
                operator.accessToken());
        assertThat(marked.getBody().get("next_status_change_on").isNull()).isTrue();
        ResponseEntity<JsonNode> dated = platform.call(
                HttpMethod.PUT,
                t + "/subscription-dates",
                Map.of("trial_ends_on", later.toString(), "reason", "Test: end the free month"),
                operator.accessToken());
        assertThat(dated.getStatusCode().value()).isEqualTo(422);
        assertThat(dated.getBody().toString()).contains("pilot");
        assertThat(audits("platform.tenant.subscription_dates_changed", c.id())).isZero();
    }

    /** Lowering a plan's shop or staff limit below what a business on it uses is refused until the operator confirms it. */
    @Test
    void loweringALimitBelowWhatABusinessUsesNeedsAConfirmation() {
        String code = "ops-lower-" + UUID.randomUUID().toString().substring(0, 6);
        String path = "/api/v1/platform/plans/" + code;
        platform.call(HttpMethod.PUT, path, planWith("Test Lower", List.of("retail"), 3, 10), operator.accessToken());
        Created c = create("ops-lower", List.of("retail"), code);
        TestDatabase.owner()
                .sql("INSERT INTO branches (id, tenant_id, code, name) VALUES (?, ?, 'S2', 'Test Shop Two')")
                .params(UUID.randomUUID(), c.id())
                .update();

        Map<String, Object> lowered = planWith("Test Lower", List.of("retail"), 1, 10);
        ResponseEntity<JsonNode> refused = platform.call(HttpMethod.PUT, path, lowered, operator.accessToken());
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("plan_limit_below_use");
        assertThat(refused.getBody().get("detail").asString()).contains("1 business has more open shops and stores");
        assertThat(platform.call(
                                HttpMethod.PUT,
                                path,
                                planWith("Test Lower", List.of("retail"), 2, 10),
                                operator.accessToken())
                        .getStatusCode())
                .as("a limit the business still fits needs no confirmation")
                .isEqualTo(HttpStatus.OK);
        lowered.put("confirm_below_use", true);
        ResponseEntity<JsonNode> confirmed = platform.call(HttpMethod.PUT, path, lowered, operator.accessToken());
        assertThat(confirmed.getStatusCode()).as("%s", confirmed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(TestDatabase.owner().sql("""
                                SELECT data -> 'businesses_over_limit' ->> 'branches' FROM platform_audit_log
                                 WHERE action = 'platform.plan.changed' AND data ->> 'plan_code' = ?
                                 ORDER BY occurred_at DESC LIMIT 1
                                """).param(code).query(String.class).single())
                .isEqualTo("1");
    }
}
