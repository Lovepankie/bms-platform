package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.HashMap;
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
 * The stock health report (issue #149, step 3). The shared data of {@link AnalyticsData} leaves head
 * office with 18 of ANA-1, 17 of ANA-2 and 4 of ANA-3, and branch two with 9 of ANA-1. On top of it:
 * ANA-5 (cost 100, sells at 150): 6 stocked at head office, 5 sold today. ANA-6 (cost 10): 100 stocked,
 * 1 sold. ANA-4 (cost 50, sells at 100): 6 stocked at branch two, 1 sold 100 days ago. Head office uses
 * 1 ANA-1 (cost 1,000), damages 2 ANA-2 (cost 200 each), and a count finds ANA-3 at 3, not 4.
 */
class RetailStockHealthIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    AnalyticsData d;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-sth", false, true);
        api = new RetailTestSupport(http, t);
        d = new AnalyticsData(t, api);
        d.products();
        d.stock();
        d.sales();
        UUID p5 = api.product("ANA-5", 100, 150);
        UUID p6 = api.product("ANA-6", 10, 20);
        api.stockUp(t.headOffice(), p5, "6");
        api.stockUp(t.headOffice(), p6, "100");
        api.stockUp(t.secondBranch(), d.p4, "6");
        d.sell(t.headOffice(), "cash", p5, "5", 0);
        d.sell(t.headOffice(), "cash", p6, "1", 3);
        d.sell(t.secondBranch(), "cash", d.p4, "1", 100);
        usage("used", d.p1, "1");
        usage("damaged", d.p2, "2");
        api.stockUp(t.headOffice(), d.p3, "3");
    }

    void usage(String kind, UUID product, String qty) {
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/usage",
                Map.of(
                        "branch_id",
                        t.headOffice(),
                        "kind",
                        kind,
                        "reason",
                        "Test " + kind,
                        "lines",
                        List.of(Map.of("product_id", product, "qty", qty))),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    JsonNode report(String query, String permissions) {
        ResponseEntity<JsonNode> r = api.get("/reports/stock-health" + query, permissions);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void daysOfCoverFromTheLastThirtyDaysOfSales() {
        // Head office: ANA-5 1 left, 5 sold: 6.0 days. ANA-2 15 left, 3 sold: 150.0. ANA-1 17 left, 2 sold: 255.0.
        // Branch two: ANA-1 9 left, 1 sold: 270.0. ANA-6 99 left, 1 sold: 2,970.0, shown as 365.0 and capped.
        JsonNode items = report("", ADMIN).get("cover").get("items");
        List<String> order = new java.util.ArrayList<>();
        Map<String, JsonNode> byKey = new HashMap<>();
        items.forEach(i -> {
            String key = i.get("code").asString() + "@"
                    + (i.get("branch_id").asString().equals(t.headOffice().toString()) ? "hq" : "b2");
            order.add(key);
            byKey.put(key, i);
        });
        assertThat(order).containsExactly("ANA-5@hq", "ANA-2@hq", "ANA-1@hq", "ANA-1@b2", "ANA-6@hq");
        assertThat(byKey.get("ANA-5@hq").get("days_of_cover").asString()).isEqualTo("6.0");
        assertThat(byKey.get("ANA-5@hq").get("avg_daily").asString()).isEqualTo("0.167");
        assertThat(byKey.get("ANA-5@hq").get("sold_last30").asString()).isEqualTo("5.000");
        assertThat(byKey.get("ANA-2@hq").get("days_of_cover").asString()).isEqualTo("150.0");
        assertThat(byKey.get("ANA-1@hq").get("days_of_cover").asString()).isEqualTo("255.0");
        assertThat(byKey.get("ANA-1@b2").get("days_of_cover").asString()).isEqualTo("270.0");
        assertThat(byKey.get("ANA-6@hq").get("days_of_cover").asString()).isEqualTo("365.0");
        assertThat(byKey.get("ANA-6@hq").get("capped").asBoolean()).isTrue();
        assertThat(byKey.get("ANA-1@hq").get("capped").asBoolean()).isFalse();
        // ANA-3 and ANA-4 did not sell in the last 30 days, so they have no pace and no row.
        assertThat(order).noneMatch(k -> k.startsWith("ANA-3") || k.startsWith("ANA-4"));
    }

    @Test
    void reorderSuggestionsReachTheTargetCover() {
        JsonNode reorder = report("", ADMIN).get("reorder");
        assertThat(reorder.get("total").asInt()).isEqualTo(1);
        JsonNode first = reorder.get("items").get(0);
        assertThat(first.get("code").asString()).isEqualTo("ANA-5");
        // 30 days at 5 sold in 30 days is 5 units, less the 1 on the shelf.
        assertThat(first.get("suggested_qty").asString()).isEqualTo("4.000");
        JsonNode wide = report("?lead_days=200&cover_days=200", ADMIN).get("reorder");
        assertThat(wide.get("total").asInt()).isEqualTo(2);
        // ANA-2: 200 days at 3 in 30 days is 20 units, less the 15 on the shelf.
        assertThat(wide.get("items").get(1).get("code").asString()).isEqualTo("ANA-2");
        assertThat(wide.get("items").get(1).get("suggested_qty").asString()).isEqualTo("5.000");
        assertThat(report("?top=1", ADMIN).get("cover").get("items")).hasSize(1);
        assertThat(report("?top=1", ADMIN).get("cover").get("total").asInt()).isEqualTo(5);
    }

    @Test
    void deadStockPerBranchValuedAtCostOnlyForAProfitReader() {
        // Head office: ANA-3 (3 left) did not sell in 90 days: 3 x 700 at price, 3 x 400 at cost.
        // Branch two: ANA-4 (5 left) last sold 100 days ago: 5 x 100 at price, 5 x 50 at cost.
        JsonNode dead = report("", ADMIN).get("dead_stock");
        assertThat(dead.get("days").asInt()).isEqualTo(90);
        assertThat(dead.get("items_total").asInt()).isEqualTo(2);
        assertThat(dead.get("value_at_price_minor").asLong()).isEqualTo(2_600);
        assertThat(dead.get("value_at_cost_minor").asLong()).isEqualTo(1_450);
        Map<String, JsonNode> branches = new HashMap<>();
        dead.get("branches").forEach(b -> branches.put(b.get("branch_id").asString(), b));
        assertThat(branches.get(t.headOffice().toString())
                        .get("value_at_cost_minor")
                        .asLong())
                .isEqualTo(1_200);
        assertThat(branches.get(t.secondBranch().toString())
                        .get("value_at_cost_minor")
                        .asLong())
                .isEqualTo(250);
        assertThat(dead.get("items").get(0).get("code").asString()).isEqualTo("ANA-3");

        JsonNode sales = report("", SALES).get("dead_stock");
        assertThat(sales.get("value_at_price_minor").asLong()).isEqualTo(2_600);
        assertThat(AnalyticsData.hasKey(sales, "cost")).isFalse();
    }

    @Test
    void shrinkageAndDamageCostPerBranch() {
        // Head office: 1 ANA-1 used (1,000), 2 ANA-2 damaged (400); a count found 1 ANA-3 missing (400); the
        // first counts of ANA-1, ANA-2, ANA-3, ANA-5 and ANA-6 found stock the books did not hold: gains of
        // 20,000 + 4,000 + 1,600 + 600 + 1,000 at cost.
        Map<String, JsonNode> byBranch = new HashMap<>();
        report("", ADMIN)
                .get("shrinkage")
                .forEach(s -> byBranch.put(s.get("branch_id").asString(), s));
        JsonNode hq = byBranch.get(t.headOffice().toString());
        assertThat(hq.get("used_reports").asInt()).isEqualTo(1);
        assertThat(hq.get("damaged_reports").asInt()).isEqualTo(1);
        assertThat(hq.get("used_cost_minor").asLong()).isEqualTo(1_000);
        assertThat(hq.get("damaged_cost_minor").asLong()).isEqualTo(400);
        assertThat(hq.get("short_lines").asInt()).isEqualTo(1);
        assertThat(hq.get("stocktake_loss_minor").asLong()).isEqualTo(400);
        assertThat(hq.get("stocktake_gain_minor").asLong()).isEqualTo(27_200);
        assertThat(hq.get("net_cost_minor").asLong()).isEqualTo(1_000 + 400 + 400 - 27_200);

        // Without profit read the counts stay and every cost figure goes.
        JsonNode sales = report("", SALES);
        JsonNode plain = null;
        for (JsonNode s : sales.get("shrinkage")) {
            if (s.get("branch_id").asString().equals(t.headOffice().toString())) {
                plain = s;
            }
        }
        assertThat(plain.get("used_reports").asInt()).isEqualTo(1);
        assertThat(plain.get("short_lines").asInt()).isEqualTo(1);
        assertThat(AnalyticsData.hasKey(plain, "cost")).isFalse();
        assertThat(AnalyticsData.hasKey(plain, "loss")).isFalse();
        assertThat(AnalyticsData.hasKey(plain, "gain")).isFalse();
    }

    @Test
    void aCallerWithoutProfitReadNeverSeesCostAnywhereAndAuditIsUntouched() {
        long audit = AnalyticsData.auditRows();
        JsonNode r = report("", SALES);
        assertThat(r.get("profit_visible").asBoolean()).isFalse();
        assertThat(AnalyticsData.hasKey(r, "cost")).isFalse();
        assertThat(AnalyticsData.hasKey(r, "profit_minor")).isFalse();
        assertThat(AnalyticsData.hasKey(r, "net")).isFalse();
        assertThat(r.toString()).doesNotContain("1450", "27200");
        assertThat(AnalyticsData.auditRows()).isEqualTo(audit);
    }

    @Test
    void permissionBranchScopeAndInputRules() {
        ResponseEntity<JsonNode> denied = api.get("/reports/stock-health", "retail.sale.read");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().toString()).doesNotContain("1450", "255.0");
        ResponseEntity<JsonNode> scoped = api.call(
                HttpMethod.GET,
                "/reports/stock-health",
                null,
                ADMIN,
                t.secondBranch().toString(),
                Map.of());
        JsonNode cover = scoped.getBody().get("cover").get("items");
        assertThat(cover).hasSize(1);
        assertThat(cover.get(0).get("branch_id").asString())
                .isEqualTo(t.secondBranch().toString());
        assertThat(scoped.getBody().get("dead_stock").get("items_total").asInt())
                .isEqualTo(1);
        assertThat(api.get("/reports/stock-health?lead_days=0", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/reports/stock-health?lead_days=30&cover_days=20", ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/reports/stock-health?cover_days=366", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void costShowsPerBranchWhereProfitReadIsHeldAndTheOverallCostOnlyWhenEverywhere() {
        Map<String, String> scopes = Map.of("X-Dev-Scopes", "retail.profit.read=" + t.headOffice());
        JsonNode r = api.call(HttpMethod.GET, "/reports/stock-health", null, ADMIN, "*", scopes)
                .getBody();
        assertThat(r.get("profit_visible").asBoolean()).isFalse();
        JsonNode dead = r.get("dead_stock");
        assertThat(dead.has("value_at_cost_minor")).isFalse();
        for (JsonNode b : dead.get("branches")) {
            boolean headOffice =
                    b.get("branch_id").asString().equals(t.headOffice().toString());
            assertThat(b.has("value_at_cost_minor")).isEqualTo(headOffice);
        }
        for (JsonNode s : r.get("shrinkage")) {
            boolean headOffice =
                    s.get("branch_id").asString().equals(t.headOffice().toString());
            assertThat(s.has("net_cost_minor")).isEqualTo(headOffice);
        }
    }
}
