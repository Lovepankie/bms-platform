package com.rincoltech.bms.retail.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Issue #630 (ADR-033 amendment): a business that gets the retail module, when it is created or when retail is switched on
 * later, starts with packs on and every other switch off. Fabricated operators and tenants.
 */
class RetailPacksOnAtStartIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    Api platform;
    Session operator;

    @BeforeEach
    void setUp() {
        platform = Api.platform(http);
        String email = Api.email("operator");
        operator = platform.signIn(Api.platformUser(email), email, Api.PASSWORD, null);
    }

    UUID create(List<String> modules) {
        String slug = "packs-" + UUID.randomUUID().toString().substring(0, 8);
        ResponseEntity<JsonNode> created = platform.post(
                "/api/v1/platform/tenants",
                Map.of(
                        "name",
                        "Test Shop " + slug,
                        "slug",
                        slug,
                        "plan_code",
                        "growth",
                        "modules",
                        modules,
                        "head_office",
                        Map.of("code", "HQ", "name", "Head Office"),
                        "admin",
                        Map.of("full_name", "Test Owner 630", "email", Api.email("owner"))),
                operator.accessToken());
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(created.getBody().get("tenant").get("id").asString());
    }

    void switchModules(UUID tenantId, List<String> modules) {
        ResponseEntity<JsonNode> switched = platform.call(
                HttpMethod.PUT,
                "/api/v1/platform/tenants/" + tenantId + "/modules",
                Map.of("modules", modules, "reason", "Test: modules for a test"),
                operator.accessToken());
        assertThat(switched.getStatusCode()).as("%s", switched.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** The retail switches as stored; an empty object when the business never saved the group. */
    static JsonNode stored(UUID tenantId) {
        String json = TestDatabase.owner()
                .sql("SELECT coalesce(settings -> 'module_groups' -> 'retail', '{}'::jsonb)::text FROM tenant_settings"
                        + " WHERE tenant_id = ?")
                .param(tenantId)
                .query(String.class)
                .optional()
                .orElse("{}");
        return JsonMapper.builder().build().readTree(json);
    }

    static long changes(UUID tenantId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.capabilities.changed'")
                .param(tenantId)
                .query(Long.class)
                .single();
    }

    @Test
    void aNewRetailBusinessStartsWithPacksOnAndEverythingElseOff() {
        UUID tenantId = create(List.of("retail"));
        JsonNode retail = stored(tenantId);

        assertThat(retail.path("units").asBoolean()).isTrue();
        for (String off : List.of(
                "branch_roles",
                "price_lists",
                "customer_accounts",
                "documents",
                "purchasing",
                "transfer_receipt",
                "returns")) {
            assertThat(retail.path(off).asBoolean()).as(off).isFalse();
        }
        assertThat(retail.path("profile").asString()).isEqualTo("shops");
        assertThat(changes(tenantId)).isEqualTo(1);
    }

    @Test
    void aBusinessWithoutRetailGetsNoRetailSwitches() {
        assertThat(stored(create(List.of("lending"))).isEmpty()).isTrue();
    }

    @Test
    void switchingRetailOnLaterTurnsPacksOn() {
        UUID tenantId = create(List.of("lending"));

        switchModules(tenantId, List.of("lending", "retail"));

        assertThat(stored(tenantId).path("units").asBoolean()).isTrue();
        assertThat(changes(tenantId)).isEqualTo(1);
    }

    /** A modules change that keeps retail on is no switch-on: packs the owner turned off stay off. */
    @Test
    void aModulesChangeWithRetailAlreadyOnLeavesPacksAsTheOwnerSetThem() {
        UUID tenantId = create(List.of("retail"));
        TestDatabase.owner()
                .sql(
                        "UPDATE tenant_settings SET settings = jsonb_set(settings, '{module_groups,retail,units}', 'false')"
                                + " WHERE tenant_id = ?")
                .param(tenantId)
                .update();

        switchModules(tenantId, List.of("lending", "retail"));
        switchModules(tenantId, List.of("retail"));

        assertThat(stored(tenantId).path("units").asBoolean()).isFalse();
        assertThat(changes(tenantId)).isEqualTo(1);
    }
}
