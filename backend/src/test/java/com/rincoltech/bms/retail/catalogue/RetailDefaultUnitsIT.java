package com.rincoltech.bms.retail.catalogue;

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

/**
 * Issue #618 (ADR-098): a business that gets the retail module, when it is created or when retail is switched on later,
 * starts with the default units, each name once, and a name it already has is kept and not added again. Fabricated
 * operators and tenants.
 */
class RetailDefaultUnitsIT extends IntegrationTest {

    static final List<String> DEFAULTS = List.of("Box", "Kg", "Litre", "Metre", "Pack", "Pair", "Piece", "Set");

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
        String slug = "units-" + UUID.randomUUID().toString().substring(0, 8);
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
                        Map.of("full_name", "Test Owner 618", "email", Api.email("owner"))),
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

    static List<String> units(UUID tenantId) {
        return TestDatabase.owner()
                .sql("SELECT name FROM retail_units WHERE tenant_id = ? ORDER BY name")
                .param(tenantId)
                .query(String.class)
                .list();
    }

    static long systemUnitAudits(UUID tenantId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.unit.created'"
                        + " AND actor_kind = 'system'")
                .param(tenantId)
                .query(Long.class)
                .single();
    }

    @Test
    void aNewRetailBusinessStartsWithTheDefaultUnits() {
        UUID tenantId = create(List.of("retail"));

        assertThat(units(tenantId)).containsExactlyElementsOf(DEFAULTS);
        assertThat(systemUnitAudits(tenantId)).isEqualTo(DEFAULTS.size());
    }

    @Test
    void aBusinessWithoutRetailGetsNoUnits() {
        assertThat(units(create(List.of("lending")))).isEmpty();
    }

    @Test
    void switchingRetailOnLaterAddsOnlyTheNamesTheBusinessLacks() {
        UUID tenantId = create(List.of("lending"));
        // A unit the owner switched off still holds its name.
        TestDatabase.owner()
                .sql("INSERT INTO retail_units (id, tenant_id, name, active) VALUES (?, ?, 'PIECE', false)")
                .params(UUID.randomUUID(), tenantId)
                .update();

        switchModules(tenantId, List.of("lending", "retail"));

        List<String> names = units(tenantId);
        assertThat(names).hasSize(DEFAULTS.size()).contains("PIECE").doesNotContain("Piece");
        assertThat(names.stream().map(String::toLowerCase).distinct()).hasSize(DEFAULTS.size());
        assertThat(systemUnitAudits(tenantId)).isEqualTo(DEFAULTS.size() - 1);

        switchModules(tenantId, List.of("lending"));
        switchModules(tenantId, List.of("lending", "retail"));

        assertThat(units(tenantId)).isEqualTo(names);
        assertThat(systemUnitAudits(tenantId)).isEqualTo(DEFAULTS.size() - 1);
    }
}
