package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
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
 * The stock list and the restocks list grouped by a chain of levels (issue #350): counts, quantities and values per
 * group computed by the server, voided restocks counted apart, cost only with the cost permission, the branch scope
 * kept, and the restocks list narrowed by the group that was opened. Fabricated data.
 */
class RetailGroupedStockPurchasesIT extends IntegrationTest {

    static final String NIL = "00000000-0000-0000-0000-000000000000";
    /** A caller who sells and reads stock but may not read cost or profit. */
    static final String NO_COST = "retail.sale.read,retail.stock.read,retail.purchase.create";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;
    /** Cost 1,000, sell 1,500. */
    UUID p1;
    /** Cost 2,000, sell 3,000. */
    UUID p2;

    UUID supplier;
    UUID voided;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-grouped", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
        p1 = api.product("GP-1", 1_000, 1_500);
        p2 = api.product("GP-2", 2_000, 3_000);
        supplier = RetailTestSupport.id(api.post("/suppliers", Map.of("name", "Test Supplier 01"), ADMIN));
        buy("cash", "2026-10-05", supplier, p1, 1_000, hq, "10");
        buy("credit", "2026-09-20", supplier, p2, 2_000, br2, "5");
        voided = buy("cash", "2026-10-05", null, p1, 1_000, hq, "4");
        ResponseEntity<JsonNode> v = api.postKeyed(
                "/purchases/" + voided + "/void",
                Map.of("reason", "Entered in error"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.OK);
    }

    UUID buy(String method, String on, UUID who, UUID product, long cost, UUID branch, String qty) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", cost);
        line.put("qty_by_branch", List.of(Map.of("branch_id", branch, "qty", qty)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", on);
        body.put("payment_method", method);
        if (who != null) {
            body.put("supplier_id", who);
        }
        body.put("lines", List.of(line));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    JsonNode body(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode group(JsonNode summary, String key) {
        for (JsonNode g : summary.get("items")) {
            if (g.get("key").asString().equals(key)) {
                return g;
            }
        }
        throw new AssertionError("no group " + key + " in " + summary);
    }

    // ---- restocks --------------------------------------------------------------------------

    @Test
    void restocksAreCountedPerYearWithTheVoidedOneApart() {
        JsonNode years = body(api.get("/purchases/summary?group_by=year,month,supplier,day,category,item", ADMIN));
        assertThat(years.get("group_by").asString()).isEqualTo("year");
        assertThat(years.get("next_level").asString()).isEqualTo("month");
        JsonNode y = group(years, "2026");
        assertThat(y.get("purchases_count").asLong()).isEqualTo(2);
        assertThat(y.get("voided_count").asLong()).isEqualTo(1);
        // 10 at 1,000 and 5 at 2,000; the voided restock is not in it.
        assertThat(y.get("total_minor").asLong()).isEqualTo(20_000);
        assertThat(y.get("from").asString()).isEqualTo("2026-01-01");

        JsonNode months = body(api.get("/purchases/summary?group_by=year,month&level=month&from=2026-01-01", ADMIN));
        assertThat(group(months, "2026-10").get("total_minor").asLong()).isEqualTo(10_000);
        assertThat(group(months, "2026-09").get("total_minor").asLong()).isEqualTo(10_000);
    }

    @Test
    void restocksGroupBySupplierCategoryAndItem() {
        JsonNode suppliers = body(api.get("/purchases/summary?group_by=supplier,item", ADMIN));
        assertThat(group(suppliers, supplier.toString()).get("purchases_count").asLong())
                .isEqualTo(2);
        JsonNode none = group(suppliers, NIL);
        assertThat(none.get("label").asString()).isEqualTo("No supplier");
        assertThat(none.get("purchases_count").asLong()).isZero();
        assertThat(none.get("voided_count").asLong()).isEqualTo(1);

        JsonNode items = body(api.get(
                "/purchases/summary?group_by=supplier,item&level=item&supplier_id=" + supplier + "&branch_id=" + hq,
                ADMIN));
        JsonNode one = group(items, p1.toString());
        assertThat(one.get("quantity").asString()).isEqualTo("10");
        assertThat(one.get("unit").asString()).isEqualTo("u-gp-1");
        assertThat(items.get("items")).hasSize(1);

        UUID category = UUID.fromString(RetailTestSupport.idOf(api.get("/products/" + p2, ADMIN), "category_id"));
        JsonNode cats = body(api.get("/purchases/summary?group_by=category&category_id=" + category, ADMIN));
        assertThat(cats.get("items")).hasSize(1);
        assertThat(cats.get("items").get(0).get("total_minor").asLong()).isEqualTo(10_000);
    }

    @Test
    void costIsLeftOutWithoutTheCostPermissionAndTheBranchScopeHolds() {
        JsonNode plain =
                body(api.call(HttpMethod.GET, "/purchases/summary?group_by=year", null, NO_COST, "*", Map.of()));
        assertThat(group(plain, "2026").has("total_minor")).isFalse();
        assertThat(group(plain, "2026").get("purchases_count").asLong()).isEqualTo(2);

        JsonNode scoped = body(
                api.call(HttpMethod.GET, "/purchases/summary?group_by=branch", null, ADMIN, br2.toString(), Map.of()));
        assertThat(scoped.get("items")).hasSize(1);
        assertThat(scoped.get("items").get(0).get("key").asString()).isEqualTo(br2.toString());
    }

    @Test
    void restocksListNarrowsToTheOpenedGroup() {
        assertThat(body(api.get("/purchases?supplier_id=" + NIL, ADMIN)).get("items"))
                .hasSize(1);
        assertThat(body(api.get("/purchases?product_id=" + p2, ADMIN)).get("items"))
                .hasSize(1);
        assertThat(body(api.get("/purchases?branch_id=" + hq + "&product_id=" + p2, ADMIN))
                        .get("items"))
                .isEmpty();
    }

    @Test
    void badChainsAreRefused() {
        assertThat(api.get("/purchases/summary?group_by=year,year", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/purchases/summary?group_by=seller", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // ---- the rows under a group, the pack lines, the status filter -------------------------

    Map<String, Object> lineOf(UUID product, long cost, Map<UUID, String> qty, UUID unit) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", cost);
        if (unit != null) {
            line.put("unit_id", unit);
        }
        line.put(
                "qty_by_branch",
                qty.entrySet().stream()
                        .map(e -> Map.of("branch_id", e.getKey(), "qty", e.getValue()))
                        .toList());
        return line;
    }

    JsonNode restock(String on, List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", on);
        body.put("payment_method", "cash");
        body.put("lines", lines);
        ResponseEntity<JsonNode> r =
                api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    @Test
    void anAllBranchAdminOpeningAShopGroupStillSeesAndVoidsTheWholeRestock() {
        JsonNode both = restock("2026-10-06", List.of(lineOf(p1, 1_000, Map.of(hq, "5", br2, "5"), null)));
        JsonNode listed = body(api.get("/purchases?branch_id=" + hq + "&product_id=" + p1, ADMIN));
        JsonNode card = null;
        for (JsonNode item : listed.get("items")) {
            if (item.get("id").asString().equals(both.get("id").asString())) {
                card = item;
            }
        }
        assertThat(card).as("the restock is listed under the shop").isNotNull();
        assertThat(card.get("lines").get(0).get("qty_by_branch")).hasSize(2);
        assertThat(card.path("partial").asBoolean(false)).isFalse();
        ResponseEntity<JsonNode> v = api.postKeyed(
                "/purchases/" + both.get("id").asString() + "/void",
                Map.of("reason", "Entered in error"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theBranchTheCategoryAndTheItemAreTestedOnTheSameMovement() {
        // One restock: the first item went to the head office, the second to the other branch.
        JsonNode x = restock(
                "2026-10-07",
                List.of(lineOf(p1, 1_000, Map.of(hq, "3"), null), lineOf(p2, 2_000, Map.of(br2, "2"), null)));
        String id = x.get("id").asString();
        assertThat(body(api.get("/purchases?branch_id=" + hq + "&product_id=" + p2, ADMIN))
                        .get("items")
                        .toString())
                .doesNotContain(id);
        assertThat(body(api.get("/purchases?branch_id=" + br2 + "&product_id=" + p2, ADMIN))
                        .get("items")
                        .toString())
                .contains(id);
        UUID cat2 = UUID.fromString(RetailTestSupport.idOf(api.get("/products/" + p2, ADMIN), "category_id"));
        assertThat(body(api.get("/purchases?branch_id=" + hq + "&category_id=" + cat2, ADMIN))
                        .get("items")
                        .toString())
                .doesNotContain(id);
        // A caller who manages only the other branch cannot learn that the first item went to the head office.
        ResponseEntity<JsonNode> probe =
                api.call(HttpMethod.GET, "/purchases?product_id=" + p1, null, ADMIN, br2.toString(), Map.of());
        assertThat(body(probe).get("items").toString()).doesNotContain(id);
        // The restock has a line in two categories: each category counts it once, with its own value.
        JsonNode cats = body(api.get("/purchases/summary?group_by=category&from=2026-10-07&to=2026-10-07", ADMIN));
        UUID cat1 = UUID.fromString(RetailTestSupport.idOf(api.get("/products/" + p1, ADMIN), "category_id"));
        assertThat(group(cats, cat1.toString()).get("purchases_count").asLong()).isEqualTo(1);
        assertThat(group(cats, cat1.toString()).get("total_minor").asLong()).isEqualTo(3_000);
        assertThat(group(cats, cat2.toString()).get("purchases_count").asLong()).isEqualTo(1);
        assertThat(group(cats, cat2.toString()).get("total_minor").asLong()).isEqualTo(4_000);
    }

    @Test
    void aPackLineIsValuedAsTheCardShowsIt() {
        String etag = api.get("/settings/capabilities", "core.settings.manage")
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> on = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("units", true),
                "core.settings.manage",
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        UUID carton = api.unit("carton");
        ResponseEntity<JsonNode> pack =
                api.post("/products/" + p1 + "/units", Map.of("unit_id", carton, "factor", "3"), ADMIN);
        assertThat(pack.getStatusCode()).as("%s", pack.getBody()).isEqualTo(HttpStatus.CREATED);
        // One carton of three at 1,000 a carton: the card says 1,000, not 3 x round(1,000 / 3) = 999.
        JsonNode bought = restock("2026-10-08", List.of(lineOf(p1, 1_000, Map.of(hq, "1"), carton)));
        assertThat(bought.get("total_minor").asLong()).isEqualTo(1_000);
        JsonNode days = body(api.get("/purchases/summary?group_by=day&from=2026-10-08&to=2026-10-08", ADMIN));
        assertThat(group(days, "2026-10-08").get("total_minor").asLong()).isEqualTo(1_000);
    }

    @Test
    void theSummaryHonoursTheStatusFilterOfTheList() {
        JsonNode posted = body(api.get("/purchases/summary?group_by=year&status=posted", ADMIN));
        assertThat(group(posted, "2026").get("purchases_count").asLong()).isEqualTo(2);
        assertThat(group(posted, "2026").get("voided_count").asLong()).isZero();
        JsonNode gone = body(api.get("/purchases/summary?group_by=year&status=voided", ADMIN));
        assertThat(group(gone, "2026").get("purchases_count").asLong()).isZero();
        assertThat(group(gone, "2026").get("voided_count").asLong()).isEqualTo(1);
        assertThat(api.get("/purchases/summary?group_by=year&status=other", ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // ---- stock -----------------------------------------------------------------------------

    @Test
    void stockGroupsByShopThenCategoryWithCountsAndValue() {
        JsonNode shops = body(api.get("/stock/summary?group_by=branch,category", ADMIN));
        assertThat(shops.get("next_level").asString()).isEqualTo("category");
        JsonNode a = group(shops, hq.toString());
        // Both items are listed at both shops; the voided restock left 10 of the first at the head office.
        assertThat(a.get("items_count").asLong()).isEqualTo(2);
        assertThat(a.get("value_minor").asLong()).isEqualTo(15_000);
        assertThat(a.get("cost_minor").asLong()).isEqualTo(10_000);
        assertThat(group(shops, br2.toString()).get("value_minor").asLong()).isEqualTo(15_000);

        JsonNode cats = body(api.get("/stock/summary?group_by=branch,category&level=category&branch_id=" + hq, ADMIN));
        assertThat(cats.get("items")).hasSize(2);

        JsonNode out = body(api.get(
                "/stock/summary?group_by=branch,category&level=category&branch_id=" + hq + "&stock_level=out", ADMIN));
        assertThat(out.get("items")).hasSize(1);
        assertThat(out.get("items").get(0).get("value_minor").asLong()).isZero();
    }

    @Test
    void stockCostIsLeftOutWithoutTheCostPermission() {
        JsonNode shops = body(api.call(HttpMethod.GET, "/stock/summary?group_by=branch", null, NO_COST, "*", Map.of()));
        assertThat(group(shops, hq.toString()).has("cost_minor")).isFalse();
        assertThat(api.get("/stock/summary?group_by=item", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
