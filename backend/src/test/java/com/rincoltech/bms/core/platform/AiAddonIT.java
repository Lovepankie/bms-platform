package com.rincoltech.bms.core.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.ai.AiSettings;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * The AI assistant add-on (issue #423, ADR-112): the operator enters its monthly price (nothing is seeded) and switches it on or
 * off per business with a reason, audited; the switch is what lets a business use the assistant; and a charged add-on is added
 * to what the business owes, while one marked no charge, or on a pilot, is not. Every business starts with it off. The figures are
 * invented.
 */
class AiAddonIT extends IntegrationTest {

    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");
    /** The add-on's invented price, in whole shillings: never the real one (review of #743). */
    static final long ADDON = 7_300;
    /** The plan's invented price. */
    static final long PLAN = 40_000;

    @Autowired
    TestRestTemplate http;

    @Autowired
    AiSettings ai;

    Api platform;
    Session operator;
    UUID operatorId;

    @BeforeEach
    void setUp() {
        platform = Api.platform(http);
        String email = Api.email("operator");
        operatorId = Api.platformUser(email);
        operator = platform.signIn(operatorId, email, Api.PASSWORD, null);
        ResponseEntity<JsonNode> priced = platform.post(
                "/api/v1/platform/addons/ai_assistant/prices",
                Map.of("monthly_minor", ADDON, "reason", "Test: the add-on price"),
                operator.accessToken());
        assertThat(priced.getStatusCode()).as("%s", priced.getBody()).isEqualTo(HttpStatus.OK);
    }

    UUID business(String plan) {
        String slug = "addon-" + UUID.randomUUID().toString().substring(0, 8);
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
                        List.of("retail"),
                        "head_office",
                        Map.of("code", "HQ", "name", "Head Office"),
                        "admin",
                        Map.of("full_name", "Test Owner 09", "email", Api.email("owner"))),
                operator.accessToken());
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(created.getBody().get("tenant").get("id").asString());
    }

    String pricedPlan() {
        String code = "addon-plan-" + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("name", "Test Addon Plan");
        plan.put("allowed_modules", List.of("retail"));
        plan.put("active", true);
        plan.put("reason", "Test: a plan for a test");
        assertThat(platform.call(HttpMethod.PUT, "/api/v1/platform/plans/" + code, plan, operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(platform.post(
                                "/api/v1/platform/plans/" + code + "/prices",
                                Map.of("monthly_minor", PLAN, "annual_months", 12, "reason", "Test price"),
                                operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        return code;
    }

    ResponseEntity<JsonNode> setAddon(UUID tenant, boolean on, boolean noCharge) {
        return platform.call(
                HttpMethod.PUT,
                "/api/v1/platform/tenants/" + tenant + "/ai-addon",
                Map.of("on", on, "no_charge", noCharge, "reason", "Test: the owner asked for it"),
                operator.accessToken());
    }

    boolean allowed(UUID tenant) {
        return TenantContext.callAs(tenant, () -> ai.status().allowed());
    }

    ResponseEntity<JsonNode> pay(UUID tenant, long amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", amount);
        body.put("method", "mtn_momo");
        body.put("reference", "TEST-REF-" + UUID.randomUUID().toString().substring(0, 6));
        body.put("paid_on", LocalDate.now(KAMPALA).toString());
        body.put("months", 1);
        body.put("reason", "Test: seen on the statement");
        return platform.call(
                HttpMethod.POST,
                "/api/v1/platform/tenants/" + tenant + "/payments",
                body,
                operator.accessToken(),
                Map.of("Idempotency-Key", "pay-" + UUID.randomUUID()));
    }

    long audits(UUID tenant) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM platform_audit_log WHERE action = 'platform.tenant.ai_addon_changed'"
                        + " AND tenant_id = ?")
                .param(tenant)
                .query(Long.class)
                .single();
    }

    @Test
    void theOperatorEntersThePriceAndItIsNeverSeeded() {
        JsonNode detail = platform.get("/api/v1/platform/addons/ai_assistant", operator.accessToken())
                .getBody();
        assertThat(detail.get("price_today").get("monthly_minor").asLong()).isEqualTo(ADDON);
        assertThat(detail.get("prices").size()).isPositive();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM addon_prices WHERE created_by_platform_user_id IS NULL")
                        .query(Long.class)
                        .single())
                .isZero();
        // A price far from the one in force needs a confirmation (it is set for a later day, so today's stays).
        Map<String, Object> far = new LinkedHashMap<>();
        far.put("monthly_minor", ADDON * 5);
        far.put("effective_from", LocalDate.now(KAMPALA).plusYears(3).toString());
        far.put("reason", "Test: a later price");
        ResponseEntity<JsonNode> refused =
                platform.post("/api/v1/platform/addons/ai_assistant/prices", far, operator.accessToken());
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(refused.getBody().get("detail").asString()).contains("UGX 7,300", "UGX 36,500");
    }

    @Test
    void theAddonIsOffForEveryNewBusinessAndTheOperatorsSwitchIsTheSourceOfTruth() {
        UUID tenant = business("growth");
        assertThat(allowed(tenant)).as("off by default").isFalse();
        JsonNode page = platform.get("/api/v1/platform/tenants/" + tenant + "/overview", operator.accessToken())
                .getBody();
        assertThat(page.get("ai_addon").get("on").asBoolean()).isFalse();

        ResponseEntity<JsonNode> on = setAddon(tenant, true, false);
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(on.getBody().get("on").asBoolean()).isTrue();
        assertThat(on.getBody().get("monthly_minor").asLong()).isEqualTo(ADDON);
        assertThat(allowed(tenant)).isTrue();
        assertThat(audits(tenant)).isEqualTo(1);
        // The same state again writes nothing.
        setAddon(tenant, true, false);
        assertThat(audits(tenant)).isEqualTo(1);

        setAddon(tenant, false, false);
        assertThat(allowed(tenant)).as("off stops it at once").isFalse();
        assertThat(audits(tenant)).isEqualTo(2);
    }

    @Test
    void aChargedAddonIsAddedToWhatTheBusinessOwesAndANoChargeOneIsNot() {
        UUID charged = business(pricedPlan());
        setAddon(charged, true, false);
        // The plan and the add-on together. The plan alone is within a quarter of it, so it is named and needs a
        // confirmation:
        // the add-on is never left out of a payment silently.
        assertThat(pay(charged, PLAN + ADDON).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> planOnly = pay(charged, PLAN);
        assertThat(planOnly.getStatusCode().value()).isEqualTo(422);
        assertThat(planOnly.getBody().get("detail").asString())
                .contains("UGX 40,000", "without the AI assistant add-on", "UGX 47,300");
        ResponseEntity<JsonNode> farOff = pay(charged, PLAN - ADDON * 2);
        assertThat(farOff.getStatusCode().value()).isEqualTo(422);
        assertThat(farOff.getBody().get("detail").asString()).contains("UGX 47,300", "more than a quarter away");

        UUID free = business(pricedPlan());
        ResponseEntity<JsonNode> demo = setAddon(free, true, true);
        assertThat(demo.getBody().get("charged").asBoolean()).isFalse();
        assertThat(demo.getBody().get("no_charge").asBoolean()).isTrue();
        assertThat(allowed(free)).isTrue();
        assertThat(pay(free, PLAN).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /**
     * The SQL state of {@code sql} run as the application bound to {@code bound} (null: unbound), rolled back; null on success.
     * JUSTIFICATION-A3: BillingAutomationIT's helper is package-private in another package; this is the review's new test.
     */
    static String asApp(UUID bound, String sql, Object... params) {
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                if (bound != null) {
                    try (PreparedStatement bind = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                        bind.setString(1, bound.toString());
                        bind.execute();
                    }
                }
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    for (int i = 0; i < params.length; i++) {
                        s.setObject(i + 1, params[i]);
                    }
                    s.execute();
                }
                return null;
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    /**
     * Review of #743 (ADR-016, as V88 and V105): code bound to a business never switches the add-on, prices it or reads another
     * business's marks, and a platform user who is not active never switches it. The Owner's page reads its own business.
     */
    @Test
    void theDefinerFunctionsRefuseACallerBoundToABusiness() {
        UUID mine = business("growth");
        UUID other = business("growth");
        String set = "SELECT * FROM platform_set_ai_addon(?, true, true, ?)";
        assertThat(asApp(mine, set, mine, operatorId)).as("bound: refused").isEqualTo("42501");
        assertThat(asApp(mine, set, other, operatorId))
                .as("bound, another business: refused")
                .isEqualTo("42501");
        assertThat(asApp(null, set, mine, UUID.randomUUID()))
                .as("not a platform user: refused")
                .isEqualTo("42501");
        assertThat(asApp(null, set, mine, operatorId))
                .as("the operator, unbound")
                .isNull();
        assertThat(asApp(
                        mine,
                        "SELECT platform_add_addon_price(?, 'ai_assistant', 'UGX', 1, current_date, null, ?)",
                        UUID.randomUUID(),
                        operatorId))
                .isEqualTo("42501");
        assertThat(asApp(mine, "SELECT platform_ai_addon_on_count()")).isEqualTo("42501");
        assertThat(asApp(mine, "SELECT * FROM platform_tenant_ai_addon(?)", other))
                .isEqualTo("42501");
        assertThat(asApp(mine, "SELECT * FROM platform_tenant_ai_addon(?)", mine))
                .as("its own business")
                .isNull();
        assertThat(allowed(mine)).isFalse();
        assertThat(allowed(other)).isFalse();
    }

    @Test
    void onlyAnOperatorMaySwitchIt() {
        UUID tenant = business("growth");
        ResponseEntity<JsonNode> anonymous = http.exchange(
                "/api/v1/platform/tenants/" + tenant + "/ai-addon",
                HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(Map.of("on", true, "reason", "Test: no one")),
                JsonNode.class);
        assertThat(anonymous.getStatusCode().value()).isIn(401, 403, 404);
        assertThat(allowed(tenant)).isFalse();
    }
}
