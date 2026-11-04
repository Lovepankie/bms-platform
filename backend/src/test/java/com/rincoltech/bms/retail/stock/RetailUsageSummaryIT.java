package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.retail.cashbook.CashbookTestSupport;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.MovableClockConfig;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Past usage and damage grouped by shop, month, category and item with totals (issue #816): a fabricated set across two
 * shops and two months, the totals at each level the sum of the rows the list shows, the cost only with
 * retail.profit.read, the counts and quantities for a seller who may read the list. Fabricated items and costs.
 */
@Import(MovableClockConfig.class)
@ResourceLock(MovableClockConfig.LOCK)
class RetailUsageSummaryIT extends IntegrationTest {

    /** 12:00 in Kampala on 10 September and 12 October 2026. */
    static final Instant SEPTEMBER = Instant.parse("2026-09-10T09:00:00Z");

    static final Instant OCTOBER = Instant.parse("2026-10-12T09:00:00Z");
    static final String CHAIN = "group_by=branch,month,category,item";

    @Autowired
    TestRestTemplate http;

    @Autowired
    MovableClock clock;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;
    /** Cost 300. */
    UUID p1;
    /** Cost 1,000. */
    UUID p2;

    UUID cat1;

    @BeforeEach
    void setUp() {
        clock.set(SEPTEMBER);
        t = TestDatabase.tenant("retail-usage-sum", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
        p1 = api.product("US-1", 300, 500);
        p2 = api.product("US-2", 1_000, 1_500);
        cat1 = UUID.fromString(RetailTestSupport.idOf(api.get("/products/" + p1, ADMIN), "category_id"));
        for (UUID b : List.of(hq, br2)) {
            for (UUID p : List.of(p1, p2)) {
                api.stockUp(b, p, "20");
            }
        }
        // September: 2 of p1 used at head office (600), 1 of p2 damaged at the second shop (1,000).
        usage(hq, "used", p1, "2");
        usage(br2, "damaged", p2, "1");
        // October: 1.5 of p1 damaged (450) and 1 of p2 used (1,000) at head office, 3 of p1 at the second shop (900).
        clock.set(OCTOBER);
        usage(hq, "damaged", p1, "1.5");
        usage(hq, "used", p2, "1");
        usage(br2, "used", p1, "3");
    }

    void usage(UUID branch, String kind, UUID product, String qty) {
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/usage",
                Map.of(
                        "branch_id",
                        branch,
                        "kind",
                        kind,
                        "reason",
                        "Test reason",
                        "lines",
                        List.of(Map.of("product_id", product, "qty", qty))),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
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

    @Test
    void totalsAtEveryLevelAddUpForTwoShopsAndTwoMonths() {
        JsonNode shops = body(api.get("/usage/summary?" + CHAIN, ADMIN));
        assertThat(shops.get("next_level").asString()).isEqualTo("month");
        JsonNode head = group(shops, hq.toString());
        assertThat(head.get("records_count").asLong()).isEqualTo(3);
        assertThat(head.get("used_count").asLong()).isEqualTo(2);
        assertThat(head.get("damaged_count").asLong()).isEqualTo(1);
        assertThat(head.get("cost_minor").asLong()).isEqualTo(2_050);
        assertThat(group(shops, br2.toString()).get("cost_minor").asLong()).isEqualTo(1_900);

        JsonNode months = body(api.get("/usage/summary?" + CHAIN + "&level=month&branch_id=" + hq, ADMIN));
        JsonNode october = group(months, "2026-10");
        assertThat(october.get("from").asString()).isEqualTo("2026-10-01");
        assertThat(october.get("to").asString()).isEqualTo("2026-10-31");
        assertThat(october.get("records_count").asLong()).isEqualTo(2);
        assertThat(october.get("cost_minor").asLong()).isEqualTo(1_450);
        assertThat(group(months, "2026-09").get("cost_minor").asLong()).isEqualTo(600);
        assertThat(months.get("items").get(0).get("key").asString())
                .as("newest first")
                .isEqualTo("2026-10");

        String oct = "&branch_id=" + hq + "&from=2026-10-01&to=2026-10-31";
        JsonNode cats = body(api.get("/usage/summary?" + CHAIN + "&level=category" + oct, ADMIN));
        assertThat(cats.get("items")).hasSize(2);
        assertThat(group(cats, cat1.toString()).get("cost_minor").asLong()).isEqualTo(450);

        JsonNode items = body(api.get("/usage/summary?" + CHAIN + "&level=item&category_id=" + cat1 + oct, ADMIN));
        JsonNode item = group(items, p1.toString());
        assertThat(item.get("quantity").asString()).isEqualTo("1.5");
        assertThat(item.get("unit").asString()).isEqualTo("u-us-1");
        assertThat(item.get("cost_minor").asLong()).isEqualTo(450);
        assertThat(item.get("damaged_count").asLong()).isEqualTo(1);

        // Every month across both shops.
        JsonNode all = body(api.get("/usage/summary?group_by=month", ADMIN));
        assertThat(group(all, "2026-10").get("cost_minor").asLong()).isEqualTo(2_350);
        assertThat(group(all, "2026-09").get("cost_minor").asLong()).isEqualTo(1_600);
    }

    @Test
    void aGroupIsTheSumOfTheRowsTheListShows() {
        JsonNode rows = body(api.get(
                "/stock/movements?kind=usage&kind=damage&branch_id=" + hq + "&from=2026-10-01&to=2026-10-31", ADMIN));
        long cost = 0;
        for (JsonNode m : rows.get("items")) {
            cost += m.get("line_cost_minor").asLong();
        }
        JsonNode months = body(api.get("/usage/summary?" + CHAIN + "&level=month&branch_id=" + hq, ADMIN));
        assertThat(group(months, "2026-10").get("cost_minor").asLong()).isEqualTo(cost);
        assertThat(group(months, "2026-10").get("records_count").asLong())
                .isEqualTo(rows.get("items").size());
    }

    @Test
    void aSellerWithoutTheProfitPermissionSeesCountsAndQuantitiesButNoCost() {
        JsonNode shops = body(api.get("/usage/summary?" + CHAIN, SALES));
        for (JsonNode g : shops.get("items")) {
            assertThat(g.has("cost_minor")).as("%s", g).isFalse();
        }
        assertThat(group(shops, hq.toString()).get("records_count").asLong()).isEqualTo(3);
        JsonNode items = body(api.get(
                "/usage/summary?" + CHAIN + "&level=item&branch_id=" + br2 + "&from=2026-10-01&to=2026-10-31", SALES));
        JsonNode item = group(items, p1.toString());
        assertThat(item.get("quantity").asString()).isEqualTo("3");
        assertThat(item.has("cost_minor")).isFalse();
    }

    @Test
    void aSellerOfOneShopSeesOnlyThatShop() {
        JsonNode shops =
                body(api.call(HttpMethod.GET, "/usage/summary?" + CHAIN, null, SALES, br2.toString(), Map.of()));
        assertThat(shops.get("items")).hasSize(1);
        assertThat(shops.get("items").get(0).get("key").asString()).isEqualTo(br2.toString());
    }

    // JUSTIFICATION-A3: the review of 178bacf asked for these cases (profit at one branch, rounding, paging, refusals).
    @Test
    void costNeedsTheProfitPermissionInEveryBranchCovered() {
        Map<String, String> oneBranch = Map.of("X-Dev-Scopes", "retail.profit.read=" + hq);
        JsonNode all = body(api.call(HttpMethod.GET, "/usage/summary?group_by=month", null, ADMIN, "*", oneBranch));
        for (JsonNode g : all.get("items")) {
            assertThat(g.has("cost_minor"))
                    .as("all shops, profit at one: %s", g)
                    .isFalse();
        }
        JsonNode head = body(
                api.call(HttpMethod.GET, "/usage/summary?group_by=month&branch_id=" + hq, null, ADMIN, "*", oneBranch));
        assertThat(group(head, "2026-10").get("cost_minor").asLong()).isEqualTo(1_450);
    }

    // JUSTIFICATION-A3: the lead's note on the review of 178bacf asked for the uncategorised case; this proves it
    // cannot occur.
    /**
     * There is no "no category" group to make: an item and an expense always name a category (NOT NULL, V10 and V34), so
     * not even the owner can write one without.
     */
    @Test
    void noItemOrExpenseCanBeWithoutACategory() {
        // Both columns are there, and neither takes a NULL (the review of 44e1389: an absent column must fail too).
        List<String> columns = TestDatabase.owner().sql("""
                        SELECT table_name || ' ' || is_nullable FROM information_schema.columns
                         WHERE column_name = 'category_id' AND table_schema = current_schema()
                           AND table_name IN ('retail_products', 'retail_expenses')
                        """).query(String.class).list();
        assertThat(columns).containsExactlyInAnyOrder("retail_products NO", "retail_expenses NO");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_products SET category_id = NULL WHERE id = ?")
                        .param(p1)
                        .update())
                .rootCause()
                .hasMessageContaining("null value in column \"category_id\"");
        // An expense row is changed only by its void (a guard trigger), so the probe writes a copy of a real one
        // without its category: refused by the column itself.
        CashbookTestSupport cash = new CashbookTestSupport(http, t);
        UUID[] fuel = cash.categoryWithItem("Test Fuel", "Test item 01", false);
        ResponseEntity<JsonNode> spent = cash.expense(hq, fuel[0], fuel[1], 1_000, null);
        assertThat(spent.getStatusCode()).as("%s", spent.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("""
                                INSERT INTO retail_expenses
                                SELECT (jsonb_populate_record(e, jsonb_build_object('id', gen_random_uuid(), 'category_id', NULL))).*
                                  FROM retail_expenses e WHERE e.id = ?
                                """)
                        .param(RetailTestSupport.id(spent))
                        .update())
                .rootCause()
                .hasMessageContaining("null value in column \"category_id\"");
    }

    @Test
    void aRecordsCostIsRoundedHalfUpAsTheRowShowsIt() {
        // 0.005 of a 300 item is 1.5, shown as 2 on the row and summed as 2.
        usage(br2, "used", p1, "0.005");
        String oct = "&branch_id=" + br2 + "&from=2026-10-01&to=2026-10-31";
        JsonNode rows = body(api.get("/stock/movements?kind=usage&kind=damage" + oct, ADMIN));
        long listed = 0;
        for (JsonNode m : rows.get("items")) {
            listed += m.get("line_cost_minor").asLong();
        }
        assertThat(listed).isEqualTo(902);
        JsonNode items = body(api.get("/usage/summary?" + CHAIN + "&level=item" + oct, ADMIN));
        assertThat(group(items, p1.toString()).get("cost_minor").asLong()).isEqualTo(902);
        assertThat(group(items, p1.toString()).get("quantity").asString()).isEqualTo("3.005");
    }

    @Test
    void monthsComeTwelveAPageWithTheNextToOfTheRest() {
        // Thirteen more months at head office, one record each: fifteen months in all.
        for (int i = 2; i <= 14; i++) {
            clock.set(OCTOBER.atZone(ZoneOffset.UTC).minusMonths(i).toInstant());
            usage(hq, "used", p1, "0.1");
        }
        JsonNode first = body(api.get("/usage/summary?group_by=month&branch_id=" + hq, ADMIN));
        assertThat(first.get("items")).hasSize(12);
        assertThat(first.get("has_more").asBoolean()).isTrue();
        assertThat(first.get("next_to").asString()).isEqualTo("2025-10-31");
        JsonNode rest = body(api.get(
                "/usage/summary?group_by=month&branch_id=" + hq + "&to="
                        + first.get("next_to").asString(),
                ADMIN));
        assertThat(rest.get("items")).hasSize(3);
        assertThat(rest.get("has_more").asBoolean()).isFalse();
        assertThat(rest.get("items").get(0).get("key").asString()).isEqualTo("2025-10");
    }

    @Test
    void aBadChainOrLevelIsRefused() {
        assertThat(api.get("/usage/summary?group_by=month&from=2026-10-02&to=2026-10-01", ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/usage/summary?group_by=branch&offset=-1", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/usage/summary?group_by=branch,branch", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/usage/summary?group_by=branch&level=item", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/usage/summary?group_by=supplier", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
