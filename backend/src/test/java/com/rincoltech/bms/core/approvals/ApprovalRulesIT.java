package com.rincoltech.bms.core.approvals;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.util.HashMap;
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
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The approval rules (issue #343, ADR-091): the list of a business's approvable actions read from the registry, the defaults that
 * keep what every business had, the permission, the administrator's proof, the audit row, the refusals and the notice to the other
 * administrators when a rule is loosened. Fabricated tenants and people.
 */
@TestPropertySource(properties = {"bms.push.ephemeral-keys=true", "db-scheduler.enabled=false"})
class ApprovalRulesIT extends IntegrationTest {

    static final String PATH = "/api/v1/settings/approvals";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    Session admin;
    UUID adminId;
    UUID other;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("approval-rules", true, true);
        api = Api.tenant(http, t.slug());
        String email = Api.email("rules-admin");
        adminId = Api.staff(t, email, new Role("tenant_admin", null));
        admin = api.signIn(adminId, email, Api.PASSWORD, null);
        other = Api.staff(t, Api.email("rules-other"), new Role("tenant_admin", null));
        device(adminId);
        device(other);
    }

    // ---- helpers -------------------------------------------------------------------------------

    void device(UUID user) {
        String endpoint = "https://fcm.googleapis.com/fcm/send/test-" + UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO push_subscriptions (id, tenant_id, user_id, endpoint, endpoint_hash, p256dh, auth, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, now())
                        """)
                .params(UUID.randomUUID(), t.tenantId(), user, endpoint, hash(endpoint), "A".repeat(87), "A".repeat(22))
                .update();
    }

    static String hash(String endpoint) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(endpoint.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    void stored(String json) {
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = settings || CAST(? AS jsonb) WHERE tenant_id = ?")
                .params(json, t.tenantId())
                .update();
    }

    ResponseEntity<JsonNode> read(Session who) {
        return api.get(PATH, who.accessToken());
    }

    Map<String, JsonNode> rules() {
        ResponseEntity<JsonNode> r = read(admin);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, JsonNode> out = new LinkedHashMap<>();
        r.getBody().get("rules").forEach(n -> out.put(n.get("action_type").asString(), n));
        return out;
    }

    static Map<String, Object> change(String action, boolean on, Long amount) {
        Map<String, Object> m = new HashMap<>();
        m.put("action_type", action);
        m.put("on", on);
        if (amount != null) {
            m.put("threshold_minor", amount);
        }
        return m;
    }

    ResponseEntity<JsonNode> save(Session who, String password, List<Map<String, Object>> changes) {
        String etag = read(admin).getHeaders().getETag();
        Map<String, Object> body = new HashMap<>();
        body.put("rules", changes);
        if (password != null) {
            body.put("password", password);
        }
        return api.call(HttpMethod.PUT, PATH, body, who.accessToken(), Map.of("If-Match", etag));
    }

    ResponseEntity<JsonNode> saveOk(List<Map<String, Object>> changes) {
        ResponseEntity<JsonNode> r = save(admin, Api.PASSWORD, changes);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r;
    }

    List<String> told() {
        return TestDatabase.owner()
                .sql("""
                        SELECT recipient FROM notification_outbox
                         WHERE tenant_id = ? AND channel = 'push' AND idempotency_key LIKE 'push:core.approval_policy_weakened:%'
                        """)
                .param(t.tenantId())
                .query(String.class)
                .list();
    }

    // ---- the list and its defaults ----------------------------------------------------------------

    @Test
    void aNewBusinessReadsTodaysRulesLendingAlwaysOnRetailOffButWriteOffs() {
        Map<String, JsonNode> rules = rules();
        for (String lending : List.of(
                "loan_disbursement",
                "charge_waiver",
                "savings_withdrawal",
                "investment_funding",
                "loan_write_off",
                "repayment_reversal",
                "collateral_release",
                "loan_bring_in",
                "loan_bring_in_reversal",
                "savings_reversal",
                "investment_early_withdrawal",
                "investment_reversal")) {
            JsonNode r = rules.get(lending);
            assertThat(r).as(lending).isNotNull();
            assertThat(r.get("module").asString()).isEqualTo("lending");
            assertThat(r.get("on").asBoolean()).as(lending).isTrue();
            assertThat(r.get("switchable").asBoolean()).as(lending).isFalse();
        }
        assertThat(rules.get("loan_disbursement").get("has_amount").asBoolean()).isTrue();
        assertThat(rules.get("loan_disbursement").get("threshold_minor").asLong())
                .isZero();
        assertThat(rules.get("loan_write_off").get("has_amount").asBoolean()).isFalse();
        assertThat(rules.get("loan_write_off").get("threshold_minor").isNull()).isTrue();

        for (String off : List.of("retail_sale_void", "retail_stock_correction", "retail_expense")) {
            assertThat(rules.get(off).get("on").asBoolean()).as(off).isFalse();
            assertThat(rules.get(off).get("switchable").asBoolean()).as(off).isTrue();
            assertThat(rules.get(off).get("module").asString()).isEqualTo("retail");
        }
        assertThat(rules.get("retail_sale_write_off").get("on").asBoolean())
                .as("ADR-043's second administrator stays")
                .isTrue();
        assertThat(rules.get("retail_sale_void").get("label").asString()).isEqualTo("Sale voids");
        // Returns and purchase orders are listed only while the business uses them; a return's void follows returns.
        assertThat(rules).doesNotContainKeys("retail_sale_return", "retail_purchase_order", "retail_sale_return_void");
        assertThat(read(admin).getBody().get("currency").asString()).isEqualTo("UGX");
    }

    @Test
    void aBusinessThatSetAnAmountBeforeKeepsExactlyWhatItHad() {
        stored("""
                {"module_groups": {"retail": {"returns": true, "purchasing": true}},
                 "approval_thresholds_minor": {"retail_sale_return": 500, "retail_purchase_order": 0,
                                               "loan_disbursement": 5000000}}
                """);
        Map<String, JsonNode> rules = rules();
        assertThat(rules.get("retail_sale_return").get("on").asBoolean()).isTrue();
        assertThat(rules.get("retail_sale_return").get("threshold_minor").asLong())
                .isEqualTo(500);
        assertThat(rules.get("retail_purchase_order").get("on").asBoolean()).isFalse();
        assertThat(rules.get("loan_disbursement").get("threshold_minor").asLong())
                .isEqualTo(5_000_000);
        assertThat(rules.get("loan_disbursement").get("on").asBoolean()).isTrue();
    }

    // ---- saving: permission, proof, audit, refusals --------------------------------------------------

    @Test
    void savingNeedsTheSettingsPermissionAndTheAdministratorsOwnPassword() {
        String email = Api.email("rules-manager");
        UUID managerId = Api.staff(t, email, new Role("branch_manager", t.headOffice()));
        Session manager = api.signIn(managerId, email, Api.PASSWORD, null);
        List<Map<String, Object>> on = List.of(change("retail_expense", true, 10_000L));
        assertThat(save(manager, Api.PASSWORD, on).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> none = save(admin, null, on);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(none.getBody().get("code").asString()).isEqualTo("password_required");
        ResponseEntity<JsonNode> wrong = save(admin, "Not-The-Password-1", on);
        assertThat(wrong.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(rules().get("retail_expense").get("on").asBoolean()).isFalse();

        ResponseEntity<JsonNode> saved = saveOk(on);
        assertThat(saved.getHeaders().getETag()).isNotNull();
        assertThat(rules().get("retail_expense").get("on").asBoolean()).isTrue();
        assertThat(rules().get("retail_expense").get("threshold_minor").asLong())
                .isEqualTo(10_000);
        // The audit row has the old and new rule of the action that changed, and nothing of the proof.
        JsonNode audit = TestDatabase.owner()
                .sql("""
                        SELECT data::text FROM audit_log
                         WHERE tenant_id = ? AND action = 'core.settings.approval_rules_changed' AND actor_user_id = ?
                        """)
                .params(t.tenantId(), adminId)
                .query((rs, n) ->
                        tools.jackson.databind.json.JsonMapper.builder().build().readTree(rs.getString(1)))
                .single();
        assertThat(audit.get("before").get("retail_expense").get("on").asBoolean())
                .isFalse();
        assertThat(audit.get("after").get("retail_expense").get("on").asBoolean())
                .isTrue();
        assertThat(audit.get("after")
                        .get("retail_expense")
                        .get("threshold_minor")
                        .asLong())
                .isEqualTo(10_000);
        assertThat(audit.toString()).doesNotContain(Api.PASSWORD);
        // Tightening a rule tells nobody.
        assertThat(told()).isEmpty();
    }

    @Test
    void loosenedRulesAreToldToTheOtherAdministratorsAndTightenedOnesAreNot() {
        saveOk(List.of(change("retail_sale_void", true, 5_000L)));
        assertThat(told()).isEmpty();
        saveOk(List.of(change("retail_sale_void", true, 9_000L)));
        assertThat(told()).containsExactly("user:" + other);
        saveOk(List.of(change("retail_sale_write_off", false, null)));
        assertThat(told()).containsOnly("user:" + other).hasSize(2);
    }

    @Test
    void lendingCannotBeSwitchedOffAndOnlyListedActionsWithAnAmountTakeOne() {
        ResponseEntity<JsonNode> off = save(admin, Api.PASSWORD, List.of(change("loan_disbursement", false, null)));
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(off.getBody().get("code").asString()).isEqualTo("approval_always_on");
        assertThat(save(admin, Api.PASSWORD, List.of(change("loan_write_off", true, 1_000L)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(save(admin, Api.PASSWORD, List.of(change("retail_sale_return", true, 1_000L)))
                        .getStatusCode())
                .as("returns are not used, so not listed")
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(save(admin, Api.PASSWORD, List.of(change("no_such_action", true, null)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(save(admin, Api.PASSWORD, List.of(change("retail_expense", true, -1L)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        // A lending amount can be set; it stays on.
        saveOk(List.of(change("loan_disbursement", true, 2_000_000L)));
        assertThat(rules().get("loan_disbursement").get("threshold_minor").asLong())
                .isEqualTo(2_000_000);
        assertThat(api.get("/api/v1/settings", admin.accessToken())
                        .getBody()
                        .get("approval_thresholds_minor")
                        .get("loan_disbursement")
                        .asLong())
                .isEqualTo(2_000_000);
    }

    @Test
    void aStaleVersionIsRefused() {
        String etag = read(admin).getHeaders().getETag();
        saveOk(List.of(change("retail_expense", true, 1L)));
        ResponseEntity<JsonNode> late = api.call(
                HttpMethod.PUT,
                PATH,
                Map.of("rules", List.of(change("retail_expense", false, null)), "password", Api.PASSWORD),
                admin.accessToken(),
                Map.of("If-Match", etag));
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rules().get("retail_expense").get("on").asBoolean()).isTrue();
    }
}
