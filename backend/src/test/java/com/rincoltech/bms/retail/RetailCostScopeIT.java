package com.rincoltech.bms.retail;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
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
 * Review F5 (ADR-017): a user reads stock and sales at every branch but holds
 * {@code retail.profit.read} at head office only, which per-permission scope allows. Cost, cost
 * snapshots, profit and the inventory account show on head office rows only. Amounts fabricated.
 */
class RetailCostScopeIT extends IntegrationTest {

    static final String READER = "retail.stock.read,retail.sale.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;
    UUID saleHq;
    UUID saleTwo;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-scope", false, true);
        api = new RetailTestSupport(http, t);
        product = api.product("SCP-1", 400, 700);
        api.stockUp(t.headOffice(), product, "10");
        api.stockUp(t.secondBranch(), product, "10");
        saleHq = RetailTestSupport.id(api.sell(t.headOffice(), "cash", product, "1"));
        saleTwo = RetailTestSupport.id(api.sell(t.secondBranch(), "cash", product, "1"));
    }

    ResponseEntity<JsonNode> get(String path) {
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.GET,
                path,
                null,
                READER,
                "*",
                Map.of("X-Dev-Scopes", "retail.profit.read=" + t.headOffice()));
        assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.OK);
        return r;
    }

    @Test
    void stockShowsCostOnlyWhereProfitIsHeld() {
        assertThat(get("/stock?branch_id=" + t.headOffice())
                        .getBody()
                        .get("items")
                        .get(0)
                        .has("cost_minor"))
                .isTrue();
        assertThat(get("/stock?branch_id=" + t.secondBranch())
                        .getBody()
                        .get("items")
                        .get(0)
                        .has("cost_minor"))
                .isFalse();
    }

    @Test
    void movementsShowCostPerRow() {
        JsonNode items = get("/stock/movements").getBody().get("items");
        assertThat(items).isNotEmpty();
        for (JsonNode m : items) {
            boolean hq = m.get("branch_id").asString().equals(t.headOffice().toString());
            assertThat(m.has("unit_cost_minor")).as("%s", m).isEqualTo(hq);
        }
    }

    @Test
    void salesShowProfitPerBranch() {
        assertThat(get("/sales/" + saleHq).getBody().has("profit_minor")).isTrue();
        JsonNode two = get("/sales/" + saleTwo).getBody();
        assertThat(two.has("profit_minor")).isFalse();
        assertThat(two.has("cost_total_minor")).isFalse();
        assertThat(two.get("lines").get(0).has("unit_cost_minor")).isFalse();
        for (JsonNode s : get("/sales").getBody().get("items")) {
            boolean hq = s.get("branch_id").asString().equals(t.headOffice().toString());
            assertThat(s.has("profit_minor")).as("%s", s).isEqualTo(hq);
        }
    }

    @Test
    void valuationShowsCostAndTheInventoryAccountPerBranch() {
        JsonNode v = get("/reports/valuation").getBody();
        for (JsonNode row : v.get("rows")) {
            boolean hq = row.get("branch_id").asString().equals(t.headOffice().toString());
            assertThat(row.has("value_at_cost_minor")).as("%s", row).isEqualTo(hq);
        }
        for (JsonNode b : v.get("branches")) {
            boolean hq = b.get("branch_id").asString().equals(t.headOffice().toString());
            assertThat(b.has("inventory_account_minor")).as("%s", b).isEqualTo(hq);
            assertThat(b.has("revaluation_difference_minor")).as("%s", b).isEqualTo(hq);
        }
        assertThat(v.has("value_at_cost_minor"))
                .as("a total over branches with hidden cost")
                .isFalse();
        JsonNode hqOnly = get("/reports/valuation?branch_id=" + t.headOffice()).getBody();
        assertThat(hqOnly.get("value_at_cost_minor").asLong()).isEqualTo(3_600);
        assertThat(List.of(hqOnly.get("branches").size())).containsExactly(1);
    }
}
