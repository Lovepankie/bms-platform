package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 * The low-stock minimum of an item (issue #412, ADR-057): set for every branch by a catalogue manager, a branch's own level
 * also by whoever may commit a stock-take there (the storekeeper's permission) and by nobody else; the branch's own level
 * wins; and the Stock health page's reorder list judges by both rules of {@link LowStockRule}, whichever trips first, saying
 * which one did. Fabricated items and quantities.
 */
class RetailStockMinimumsIT extends IntegrationTest {

    /** A storekeeper's permissions: counts, moves and reads stock, no catalogue. */
    static final String COUNTS = "retail.stock.read,retail.stock.transfer,retail.stocktake.commit,retail.usage.report";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("stockmin", false, true);
        api = new RetailTestSupport(http, t);
    }

    ResponseEntity<JsonNode> put(UUID product, UUID branch, String qty, String permissions, String branches) {
        String path = "/products/" + product + "/stock-minimum" + (branch == null ? "" : "/branches/" + branch);
        Map<String, Object> body = new HashMap<>();
        body.put("min_qty", qty);
        return api.call(HttpMethod.PUT, path, body, permissions, branches, Map.of());
    }

    JsonNode row(JsonNode list, UUID product, UUID branch) {
        for (JsonNode r : list.get("items")) {
            if (r.get("product_id").asString().equals(product.toString())
                    && r.get("branch_id").asString().equals(branch.toString())) {
                return r;
            }
        }
        return null;
    }

    @Test
    void anAdministratorSetsTheLevelForEveryBranchAndABranchsOwnLevelWins() {
        UUID product = api.product("MIN-1", 600, 1_000);
        assertThat(put(product, null, "10", ADMIN, "*").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> own = put(product, t.headOffice(), "4", ADMIN, "*");
        assertThat(own.getStatusCode()).as("%s", own.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode view = own.getBody();
        assertThat(view.get("min_qty").asString()).isEqualTo("10.000");
        assertThat(view.get("can_set_every_branch").asBoolean()).isTrue();
        assertThat(view.get("lead_days").asInt()).isEqualTo(14);
        Map<String, JsonNode> byBranch = new LinkedHashMap<>();
        view.get("branches").forEach(b -> byBranch.put(b.get("branch_id").asString(), b));
        assertThat(byBranch.get(t.headOffice().toString()).get("applies_qty").asString())
                .isEqualTo("4.000");
        assertThat(byBranch.get(t.secondBranch().toString()).get("applies_qty").asString())
                .isEqualTo("10.000");
        assertThat(byBranch.get(t.secondBranch().toString()).has("min_qty")).isFalse();
        // Removing the branch's own level brings back the level for every branch.
        JsonNode removed = put(product, t.headOffice(), null, ADMIN, "*").getBody();
        removed.get("branches")
                .forEach(b -> assertThat(b.get("applies_qty").asString()).isEqualTo("10.000"));
    }

    @Test
    void whoMaySetALevel() {
        UUID product = api.product("MIN-2", 600, 1_000);
        // A seller may read the level but set none.
        assertThat(api.call(HttpMethod.GET, "/products/" + product + "/stock-minimum", null, SALES, "*", Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(put(product, null, "5", SALES, "*").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(product, t.headOffice(), "5", SALES, "*").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // Whoever counts the stock at a branch sets that branch's own level, not the level for every branch, and not
        // another
        // branch's.
        String hq = t.headOffice().toString();
        assertThat(put(product, null, "5", COUNTS, hq).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(put(product, t.headOffice(), "5", COUNTS, hq).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(put(product, t.secondBranch(), "5", COUNTS, hq).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        JsonNode view = api.call(HttpMethod.GET, "/products/" + product + "/stock-minimum", null, COUNTS, hq, Map.of())
                .getBody();
        assertThat(view.get("can_set_every_branch").asBoolean()).isFalse();
        assertThat(view.get("branches")).hasSize(1);
        assertThat(view.get("branches").get(0).get("can_set").asBoolean()).isTrue();
    }

    @Test
    void aCatalogueManagerOfOneBranchCannotSetTheLevelForEveryBranch() {
        UUID product = api.product("MIN-5", 600, 1_000);
        String hq = t.headOffice().toString();
        assertThat(put(product, null, "5", ADMIN, hq).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Their own branch's level, yes.
        assertThat(put(product, t.headOffice(), "5", ADMIN, hq).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    long auditRows() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.stock_minimum.changed'")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    @Test
    void removingALevelThatNeverExistedOrSavingTheSameOneWritesNoAuditRow() {
        UUID product = api.product("MIN-6", 600, 1_000);
        assertThat(put(product, null, null, ADMIN, "*").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put(product, t.headOffice(), null, ADMIN, "*").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(auditRows()).isZero();
        put(product, null, "5", ADMIN, "*");
        put(product, null, "5.000", ADMIN, "*");
        assertThat(auditRows()).isEqualTo(1);
        put(product, null, null, ADMIN, "*");
        assertThat(auditRows()).isEqualTo(2);
    }

    @Test
    void aLevelIsAboveZeroWithAtMostThreePlaces() {
        UUID product = api.product("MIN-3", 600, 1_000);
        assertThat(put(product, null, "0", ADMIN, "*").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(put(product, null, "1.2345", ADMIN, "*").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(put(UUID.randomUUID(), null, "2", ADMIN, "*").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void stockHealthReordersByTheMinimumOrThePaceWhicheverTripsFirst() {
        UUID byMinimum = api.product("MIN-A", 600, 1_000);
        UUID byPace = api.product("MIN-B", 600, 1_000);
        UUID neverSold = api.product("MIN-C", 600, 1_000);
        UUID fine = api.product("MIN-D", 600, 1_000);
        for (UUID p : new UUID[] {byMinimum, byPace, neverSold, fine}) {
            api.stockUp(t.headOffice(), p, "10");
        }
        put(byMinimum, null, "8", ADMIN, "*");
        put(neverSold, null, "20", ADMIN, "*");
        put(fine, null, "2", ADMIN, "*");
        assertThat(api.sell(t.headOffice(), "cash", byMinimum, "3").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(api.sell(t.headOffice(), "cash", byPace, "9").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(api.sell(t.headOffice(), "cash", fine, "1").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode reorder = api.get("/reports/stock-health", ADMIN).getBody().get("reorder");
        // MIN-A: 7 left, under its 8 (the pace alone would not say so: 7 x 30 is more than 14 x 3).
        JsonNode a = row(reorder, byMinimum, t.headOffice());
        assertThat(a.get("low_by").asString()).isEqualTo("minimum");
        assertThat(a.get("low_words").asString()).isEqualTo("below your minimum of 8");
        assertThat(a.get("min_qty").asString()).isEqualTo("8.000");
        // MIN-B: no minimum; 1 left after 9 sold in 30 days is about 3 days.
        JsonNode b = row(reorder, byPace, t.headOffice());
        assertThat(b.get("low_by").asString()).isEqualTo("pace");
        assertThat(b.get("low_words").asString()).isEqualTo("about 3 days from running out at this pace");
        assertThat(b.has("min_qty")).isFalse();
        // MIN-C never sold: its minimum alone puts it on the list, and the suggestion reaches the minimum.
        JsonNode c = row(reorder, neverSold, t.headOffice());
        assertThat(c.get("low_by").asString()).isEqualTo("minimum");
        assertThat(new BigDecimal(c.get("suggested_qty").asString())).isEqualByComparingTo("10");
        // MIN-D: 9 left, above its 2 and plenty at its pace.
        assertThat(row(reorder, fine, t.headOffice())).isNull();
        assertThat(reorder.get("total").asInt()).isEqualTo(3);
        // The most urgent first: MIN-B has about 3 of 14 days, MIN-C half its level, MIN-A seven eighths.
        assertThat(reorder.get("items").get(0).get("code").asString()).isEqualTo("MIN-B");
    }
}
