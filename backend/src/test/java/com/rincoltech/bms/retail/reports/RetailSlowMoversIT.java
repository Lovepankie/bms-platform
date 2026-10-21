package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.ArrayList;
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
 * The slow movers section of the stock health report (issue #414). Head office: SLO-1 (cost 100) 40 left after 2
 * sold 20 days ago and 1 sold 10 days ago; CAP-1 (cost 1) 1,000 left after 1 sold; MID-1 (cost 10) 12 left after
 * 9 sold; FIN-1 (cost 10) 2 left after 10 sold; DED-1 (cost 10) 5 never sold. Branch two: SLO-1 2 left after 30
 * sold 5 days ago; SLO-2 (cost 50) 20 left after 1 sold. Everything was bought 100 days ago, so each pace is
 * over the full 90 days.
 */
class RetailSlowMoversIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    AnalyticsData d;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-slow", false, true);
        api = new RetailTestSupport(http, t);
        d = new AnalyticsData(t, api);
        UUID slo1 = api.product("SLO-1", 100, 200);
        UUID slo2 = api.product("SLO-2", 50, 80);
        UUID cap1 = api.product("CAP-1", 1, 2);
        UUID mid1 = api.product("MID-1", 10, 20);
        UUID fin1 = api.product("FIN-1", 10, 20);
        UUID ded1 = api.product("DED-1", 10, 20);
        buy(t.headOffice(), slo1, 100, "43", 100);
        buy(t.headOffice(), cap1, 1, "1001", 100);
        buy(t.headOffice(), mid1, 10, "21", 100);
        buy(t.headOffice(), fin1, 10, "12", 100);
        buy(t.headOffice(), ded1, 10, "5", 100);
        buy(t.secondBranch(), slo1, 100, "32", 100);
        buy(t.secondBranch(), slo2, 50, "21", 100);
        d.sell(t.headOffice(), "cash", slo1, "2", 20);
        d.sell(t.headOffice(), "cash", slo1, "1", 10);
        d.sell(t.headOffice(), "cash", cap1, "1", 30);
        d.sell(t.headOffice(), "cash", mid1, "9", 40);
        d.sell(t.headOffice(), "cash", fin1, "10", 2);
        d.sell(t.secondBranch(), "cash", slo1, "30", 5);
        d.sell(t.secondBranch(), "cash", slo2, "1", 60);
    }

    /** A cash restock {@code daysAgo} days back at the item's own cost, so its stock movement carries that day. */
    void buy(UUID branch, UUID product, long cost, String qty, int daysAgo) {
        Map<String, Object> line = Map.of(
                "product_id",
                product,
                "cost_minor",
                cost,
                "qty_by_branch",
                List.of(Map.of("branch_id", branch, "qty", qty)));
        Map<String, Object> body = Map.of(
                "purchased_on",
                AnalyticsData.today().minusDays(daysAgo).toString(),
                "payment_method",
                "cash",
                "lines",
                List.of(line));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    static void owner(String sql, Object... args) {
        TestDatabase.owner().sql(sql).params(args).update();
    }

    JsonNode slow(String permissions, String branches, Map<String, String> extra) {
        ResponseEntity<JsonNode> r =
                api.call(HttpMethod.GET, "/reports/stock-health", null, permissions, branches, extra);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    Map<String, JsonNode> byKey(JsonNode slowMovers, List<String> order) {
        Map<String, JsonNode> out = new HashMap<>();
        slowMovers.get("items").forEach(i -> {
            String key = i.get("code").asString() + "@"
                    + (i.get("branch_id").asString().equals(t.headOffice().toString()) ? "hq" : "b2");
            order.add(key);
            out.put(key, i);
        });
        return out;
    }

    ResponseEntity<JsonNode> setting(Map<String, Object> body) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        return api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                body,
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
    }

    @Test
    void slowIsSeparateFromDeadAndFromItemsThatSellAtAFinePace() {
        JsonNode r = slow(ADMIN, "*", Map.of());
        JsonNode s = r.get("slow_movers");
        assertThat(s.get("days").asInt()).isEqualTo(90);
        assertThat(s.get("slow_months").asInt()).isEqualTo(4);
        assertThat(s.get("min_sold").asInt()).isEqualTo(0);
        List<String> order = new ArrayList<>();
        byKey(s, order);
        // The longest to sell first. MID-1 lasts exactly 4.0 months, which is not more than 4.
        assertThat(order).containsExactly("CAP-1@hq", "SLO-2@b2", "SLO-1@hq");
        assertThat(s.get("total").asInt()).isEqualTo(3);
        // DED-1 never sold: it is dead stock and never a slow mover.
        List<String> dead = new ArrayList<>();
        r.get("dead_stock").get("items").forEach(i -> dead.add(i.get("code").asString()));
        assertThat(dead).containsExactly("DED-1");
        assertThat(slow(ADMIN, "*", Map.of()).get("slow_movers").get("items").toString())
                .doesNotContain("DED-1", "FIN-1", "MID-1");
        assertThat(api.get("/reports/stock-health?top=1", ADMIN)
                        .getBody()
                        .get("slow_movers")
                        .get("total")
                        .asInt())
                .isEqualTo(3);
    }

    @Test
    void monthsToSellAtThePaceOfTheLastNinetyDays() {
        List<String> order = new ArrayList<>();
        Map<String, JsonNode> rows = byKey(slow(ADMIN, "*", Map.of()).get("slow_movers"), order);
        JsonNode slo1 = rows.get("SLO-1@hq");
        // 40 left, 3 sold in 90 days, 1 a month: 40.0 months.
        assertThat(slo1.get("qty_on_hand").asString()).isEqualTo("40.000");
        assertThat(slo1.get("sold_last90").asString()).isEqualTo("3.000");
        assertThat(slo1.get("months_of_stock").asString()).isEqualTo("40.0");
        assertThat(slo1.get("capped").asBoolean()).isFalse();
        assertThat(slo1.get("last_sold_on").asString())
                .isEqualTo(AnalyticsData.today().minusDays(10).toString());
        assertThat(rows.get("SLO-2@b2").get("months_of_stock").asString()).isEqualTo("60.0");
        // 1,000 left at 1 in 90 days is 3,000 months: shown as 120.0 and capped.
        assertThat(rows.get("CAP-1@hq").get("months_of_stock").asString()).isEqualTo("120.0");
        assertThat(rows.get("CAP-1@hq").get("capped").asBoolean()).isTrue();
    }

    @Test
    void theBusinessSettingsMoveTheLine() {
        // Three months: MID-1 (4.0 months) is now slow.
        assertThat(setting(Map.of("slow_mover_months", 3)).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> order = new ArrayList<>();
        byKey(slow(ADMIN, "*", Map.of()).get("slow_movers"), order);
        assertThat(order).contains("MID-1@hq").doesNotContain("FIN-1@hq");
        // Fewer than 20 sold in 90 days: FIN-1 (10 sold, 0.6 months) is slow too; SLO-1 at branch two (30) is not.
        ResponseEntity<JsonNode> saved = setting(Map.of("slow_mover_min_sold", 20));
        assertThat(saved.getBody().get("slow_mover_months").asInt()).isEqualTo(3);
        assertThat(saved.getBody().get("slow_mover_min_sold").asInt()).isEqualTo(20);
        JsonNode s = slow(ADMIN, "*", Map.of()).get("slow_movers");
        assertThat(s.get("slow_months").asInt()).isEqualTo(3);
        assertThat(s.get("min_sold").asInt()).isEqualTo(20);
        List<String> after = new ArrayList<>();
        byKey(s, after);
        assertThat(after).contains("FIN-1@hq", "MID-1@hq").doesNotContain("SLO-1@b2", "DED-1@hq");
        // Out of range or not a whole number is refused and the stored value stays.
        assertThat(setting(Map.of("slow_mover_months", 0)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(setting(Map.of("slow_mover_months", 25)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(setting(Map.of("slow_mover_min_sold", -1)).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/settings/capabilities", SETTINGS)
                        .getBody()
                        .get("slow_mover_months")
                        .asInt())
                .isEqualTo(3);
    }

    @Test
    void moneyTiedUpOnlyWithProfitReadAndTheTotalOnlyForTheWholeBusiness() {
        JsonNode admin = slow(ADMIN, "*", Map.of()).get("slow_movers");
        List<String> order = new ArrayList<>();
        Map<String, JsonNode> rows = byKey(admin, order);
        assertThat(rows.get("SLO-1@hq").get("value_at_cost_minor").asLong()).isEqualTo(4_000);
        assertThat(rows.get("SLO-2@b2").get("value_at_cost_minor").asLong()).isEqualTo(1_000);
        assertThat(rows.get("CAP-1@hq").get("value_at_cost_minor").asLong()).isEqualTo(1_000);
        assertThat(admin.get("value_at_cost_minor").asLong()).isEqualTo(6_000);

        // Control above: the figures exist. Without retail.profit.read not one cost field is sent.
        JsonNode sales = slow(SALES, "*", Map.of()).get("slow_movers");
        assertThat(sales.get("items")).hasSize(3);
        assertThat(AnalyticsData.hasKey(sales, "cost")).isFalse();
        assertThat(AnalyticsData.figures(sales)).doesNotContain("4000", "6000");

        // Profit read at head office only: its rows carry cost, branch two's do not, and there is no total.
        JsonNode partial = slow(ADMIN, "*", Map.of("X-Dev-Scopes", "retail.profit.read=" + t.headOffice()))
                .get("slow_movers");
        for (JsonNode i : partial.get("items")) {
            boolean hq = i.get("branch_id").asString().equals(t.headOffice().toString());
            assertThat(i.has("value_at_cost_minor")).isEqualTo(hq);
        }
        assertThat(partial.has("value_at_cost_minor")).isFalse();
    }

    @Test
    void aCallerSeesOnlyTheirBranches() {
        List<String> order = new ArrayList<>();
        byKey(slow(ADMIN, t.secondBranch().toString(), Map.of()).get("slow_movers"), order);
        assertThat(order).containsExactly("SLO-2@b2");
        List<String> hq = new ArrayList<>();
        Map<String, JsonNode> rows =
                byKey(slow(SALES, t.headOffice().toString(), Map.of()).get("slow_movers"), hq);
        assertThat(hq).containsExactly("CAP-1@hq", "SLO-1@hq");
        // Branch two is outside the caller's scope, so it is never named as a better place.
        assertThat(rows.get("SLO-1@hq").has("better_at")).isFalse();
    }

    @Test
    void sellsBetterAtSuggestsAQuantityToMove() {
        List<String> order = new ArrayList<>();
        Map<String, JsonNode> rows = byKey(slow(ADMIN, "*", Map.of()).get("slow_movers"), order);
        JsonNode better = rows.get("SLO-1@hq").get("better_at");
        assertThat(better.get("branch_id").asString())
                .isEqualTo(t.secondBranch().toString());
        assertThat(better.get("sold_last90").asString()).isEqualTo("30.000");
        // Branch two sells 30 in 90 days: 10 for 30 days of cover, less the 2 it holds, is 8. Head office keeps
        // 4 months of its own pace (4) and could give 36, so 8 it is.
        assertThat(better.get("suggested_qty").asString()).isEqualTo("8.000");
        // A longer cover asks for more: 60 days is 20, less 2, is 18.
        JsonNode wider = api.get("/reports/stock-health?cover_days=60", ADMIN).getBody();
        for (JsonNode i : wider.get("slow_movers").get("items")) {
            if (i.get("code").asString().equals("SLO-1")) {
                assertThat(i.get("better_at").get("suggested_qty").asString()).isEqualTo("18.000");
            }
        }
        assertThat(rows.get("CAP-1@hq").has("better_at")).isFalse();
        assertThat(rows.get("SLO-2@b2").has("better_at")).isFalse();
        // Asking for head office alone still looks at branch two, which the caller may read.
        JsonNode hqOnly = api.get("/reports/stock-health?branch_id=" + t.headOffice(), ADMIN)
                .getBody();
        boolean found = false;
        for (JsonNode i : hqOnly.get("slow_movers").get("items")) {
            if (i.get("code").asString().equals("SLO-1")) {
                found = i.get("better_at")
                        .get("branch_id")
                        .asString()
                        .equals(t.secondBranch().toString());
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void aStorekeeperSeesNoCostOrPriceKeyEvenWithABetterBranch() {
        // The Storekeeper role's permissions (V73) at both branches, so head office's SLO-1 names branch two.
        String keeper = "retail.stock.read,retail.stock.transfer,retail.stocktake.commit,retail.usage.report";
        JsonNode r = slow(keeper, t.headOffice() + "," + t.secondBranch(), Map.of());
        List<String> order = new ArrayList<>();
        Map<String, JsonNode> rows = byKey(r.get("slow_movers"), order);
        assertThat(rows.get("SLO-1@hq").get("better_at").get("branch_id").asString())
                .isEqualTo(t.secondBranch().toString());
        // The same guard as the Storekeeper check of RetailRolesIT, over every key of the whole report.
        List<String> keys = new ArrayList<>();
        keysOf(r, keys);
        assertThat(keys)
                .filteredOn(k -> !k.endsWith("_visible"))
                .noneMatch(k -> k.contains("cost")
                        || k.contains("profit")
                        || k.contains("margin")
                        || k.contains("value")
                        || k.contains("expected_sales")
                        || k.contains("sell")
                        || k.contains("price"));
    }

    static void keysOf(JsonNode node, List<String> out) {
        if (node.isObject()) {
            node.properties().forEach(e -> {
                out.add(e.getKey());
                keysOf(e.getValue(), out);
            });
        } else if (node.isArray()) {
            node.forEach(n -> keysOf(n, out));
        }
    }

    @Test
    void aClosedBranchOrOneWithoutGoodsIsNeverTheBetterPlace() {
        List<String> order = new ArrayList<>();
        // Control: branch two is named while it is open and stocks goods.
        assertThat(byKey(slow(ADMIN, "*", Map.of()).get("slow_movers"), order)
                        .get("SLO-1@hq")
                        .has("better_at"))
                .isTrue();
        owner("UPDATE branches SET status = 'inactive', closed_at = now() WHERE id = ?", t.secondBranch());
        Map<String, JsonNode> closed = byKey(slow(ADMIN, "*", Map.of()).get("slow_movers"), order);
        assertThat(closed.get("SLO-1@hq").has("better_at")).isFalse();
        owner(
                "UPDATE branches SET status = 'active', closed_at = NULL, stocks_goods = false WHERE id = ?",
                t.secondBranch());
        Map<String, JsonNode> noGoods = byKey(slow(ADMIN, "*", Map.of()).get("slow_movers"), order);
        assertThat(noGoods.get("SLO-1@hq").has("better_at")).isFalse();
    }

    @Test
    void aNewItemIsJudgedOnTheDaysItHasBeenThere() {
        // NEW-1: 20 bought 9 days ago, 3 sold 5 days ago: 17 over 3 in 10 days is 1.9 months, not slow (over 90
        // days it would read 17.0 months). NEW-2: 50 bought 29 days ago, 1 sold 20 days ago: 49 over 1 in 30 days
        // is 49.0 months, slow, and its row says the pace is of 30 days.
        UUID new1 = api.product("NEW-1", 10, 20);
        UUID new2 = api.product("NEW-2", 10, 20);
        buy(t.headOffice(), new1, 10, "20", 9);
        buy(t.headOffice(), new2, 10, "50", 29);
        d.sell(t.headOffice(), "cash", new1, "3", 5);
        d.sell(t.headOffice(), "cash", new2, "1", 20);
        List<String> order = new ArrayList<>();
        Map<String, JsonNode> rows = byKey(slow(ADMIN, "*", Map.of()).get("slow_movers"), order);
        assertThat(order).doesNotContain("NEW-1@hq");
        JsonNode n2 = rows.get("NEW-2@hq");
        assertThat(n2.get("days_counted").asInt()).isEqualTo(30);
        assertThat(n2.get("months_of_stock").asString()).isEqualTo("49.0");
        assertThat(n2.get("capped").asBoolean()).isFalse();
        // The older items keep the full 90 days.
        assertThat(rows.get("SLO-1@hq").get("days_counted").asInt()).isEqualTo(90);
        // Ordered by months of stock: CAP-1 (capped), SLO-2 (60.0), NEW-2 (49.0), SLO-1 (40.0).
        assertThat(order).containsExactly("CAP-1@hq", "SLO-2@b2", "NEW-2@hq", "SLO-1@hq");
    }

    @Test
    void rowsCaughtOnlyByTheMinimumSoldFollowInMonthsOrder() {
        // Fewer than 20 sold: MID-1 (4.0 months, 9 sold) and FIN-1 (0.6 months, 10 sold) join, after the rows
        // over the month limit, each in its months order; SLO-1 at branch two (30 sold) stays out.
        assertThat(setting(Map.of("slow_mover_min_sold", 20)).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> order = new ArrayList<>();
        Map<String, JsonNode> rows = byKey(slow(ADMIN, "*", Map.of()).get("slow_movers"), order);
        assertThat(order).containsExactly("CAP-1@hq", "SLO-2@b2", "SLO-1@hq", "MID-1@hq", "FIN-1@hq");
        assertThat(rows.get("FIN-1@hq").get("months_of_stock").asString()).isEqualTo("0.6");
    }

    @Test
    void anInactiveItemIsLeftOutEvenWithSales() {
        List<String> order = new ArrayList<>();
        byKey(slow(ADMIN, "*", Map.of()).get("slow_movers"), order);
        assertThat(order).contains("SLO-2@b2");
        owner("UPDATE retail_products SET active = false WHERE tenant_id = ? AND code = 'SLO-2'", t.tenantId());
        List<String> after = new ArrayList<>();
        JsonNode s = slow(ADMIN, "*", Map.of()).get("slow_movers");
        byKey(s, after);
        assertThat(after).containsExactly("CAP-1@hq", "SLO-1@hq");
        assertThat(s.get("total").asInt()).isEqualTo(2);
    }
}
