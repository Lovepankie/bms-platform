package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
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
 * The retail parity pass (#145, #144): categories on the stock views, expected profit, the
 * all-branches stock, low and out of stock, and the sales filters. Fabricated figures.
 */
class RetailParityIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID cable;
    UUID bulb;

    /** Two products, each in its own category "Test Category CODE". */
    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-par", false, true);
        api = new RetailTestSupport(http, t);
        cable = api.product("PAR-CABLE", 1_000, 1_500);
        bulb = api.product("PAR-BULB", 200, 300);
        api.stockUp(t.headOffice(), cable, "10");
        api.stockUp(t.headOffice(), bulb, "4");
    }

    JsonNode ok(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void stockRowsCarryTheCategoryAndFilterByIt() {
        JsonNode all = ok(api.get("/stock?branch_id=" + t.headOffice(), ADMIN)).get("items");
        assertThat(all).hasSize(2);
        assertThat(all.get(0).get("category").asString()).isEqualTo("Test Category PAR-BULB");
        assertThat(all.get(0).has("category_id")).isTrue();

        UUID categoryId = UUID.fromString(all.get(1).get("category_id").asString());
        JsonNode one = ok(api.get("/stock?branch_id=" + t.headOffice() + "&category_id=" + categoryId, ADMIN))
                .get("items");
        assertThat(one).hasSize(1);
        assertThat(one.get(0).get("code").asString()).isEqualTo("PAR-CABLE");
    }

    @Test
    void searchMatchesTheCategoryTextOnStockAndProducts() {
        JsonNode stock = ok(api.get("/stock?branch_id=" + t.headOffice() + "&query=category par-cab", ADMIN))
                .get("items");
        assertThat(stock).hasSize(1);
        assertThat(stock.get(0).get("code").asString()).isEqualTo("PAR-CABLE");
        JsonNode products = ok(api.get("/products?query=Category PAR-BULB&branch_id=" + t.headOffice(), ADMIN))
                .get("items");
        assertThat(products).hasSize(1);
        assertThat(products.get(0).get("code").asString()).isEqualTo("PAR-BULB");
        assertThat(products.get(0).get("category").asString()).isEqualTo("Test Category PAR-BULB");
    }

    // ---- B. Expected profit on Stock value --------------------------------------------------

    /**
     * Worked by hand. Head office holds 10 cable (cost 1,000, sells 1,500), 4 bulb (200, 300) and
     * 3 odd item (300, 400); branch two holds 5 cable. Head office: at price 15,000 + 1,200 + 1,200 =
     * 17,400, at cost 10,000 + 800 + 900 = 11,700, profit 5,700, which is 4,872 basis points of cost
     * (5,700 over 11,700 is 48.72 percent). Branch two: 7,500, 5,000, 2,500, 5,000 basis points.
     * Total: 24,900, 16,700, 8,200, 4,910 basis points (49.10 percent).
     */
    void stockForProfit() {
        UUID odd = api.product("PAR-ODD", 300, 400);
        api.stockUp(t.headOffice(), odd, "3");
        api.stockUp(t.secondBranch(), cable, "5");
    }

    @Test
    void valuationShowsExpectedProfitPerRowBranchCategoryAndTotal() {
        stockForProfit();
        JsonNode v = ok(api.get("/reports/valuation", ADMIN));
        JsonNode cableRow = null;
        for (JsonNode r : v.get("rows")) {
            if (r.get("code").asString().equals("PAR-ODD")) {
                assertThat(r.get("expected_sales_minor").asLong()).isEqualTo(1_200);
                assertThat(r.get("value_at_cost_minor").asLong()).isEqualTo(900);
                assertThat(r.get("expected_profit_minor").asLong()).isEqualTo(300);
                assertThat(r.get("expected_profit_bp").asLong()).isEqualTo(3_333);
                assertThat(r.get("category").asString()).isEqualTo("Test Category PAR-ODD");
            }
            if (r.get("code").asString().equals("PAR-CABLE")
                    && r.get("branch_id").asString().equals(t.secondBranch().toString())) {
                cableRow = r;
            }
        }
        assertThat(cableRow).isNotNull();
        assertThat(cableRow.get("expected_profit_minor").asLong()).isEqualTo(2_500);

        for (JsonNode b : v.get("branches")) {
            boolean hq = b.get("branch_id").asString().equals(t.headOffice().toString());
            assertThat(b.get("expected_profit_minor").asLong()).isEqualTo(hq ? 5_700 : 2_500);
            assertThat(b.get("expected_profit_bp").asLong()).isEqualTo(hq ? 4_872 : 5_000);
        }
        assertThat(v.get("expected_sales_minor").asLong()).isEqualTo(24_900);
        assertThat(v.get("value_at_cost_minor").asLong()).isEqualTo(16_700);
        assertThat(v.get("expected_profit_minor").asLong()).isEqualTo(8_200);
        assertThat(v.get("expected_profit_bp").asLong()).isEqualTo(4_910);

        assertThat(v.get("categories")).hasSize(3);
        JsonNode cat = null;
        for (JsonNode c : v.get("categories")) {
            if (c.get("category").asString().equals("Test Category PAR-CABLE")) {
                cat = c;
            }
        }
        assertThat(cat).isNotNull();
        assertThat(cat.get("expected_sales_minor").asLong()).isEqualTo(22_500);
        assertThat(cat.get("value_at_cost_minor").asLong()).isEqualTo(15_000);
        assertThat(cat.get("expected_profit_minor").asLong()).isEqualTo(7_500);
        assertThat(cat.get("expected_profit_bp").asLong()).isEqualTo(5_000);
    }

    @Test
    void aCallerWithoutProfitReadSeesNoCostOrProfitAnywhereInTheValuation() {
        stockForProfit();
        ResponseEntity<JsonNode> r = api.get("/reports/valuation", "retail.stock.read");
        String body = ok(r).toString();
        assertThat(body).doesNotContain("cost").doesNotContain("profit").doesNotContain("inventory");
        assertThat(body).contains("expected_sales_minor").contains("\"categories\"");
        assertThat(ok(r).get("categories").get(0).has("category")).isTrue();
    }

    @Test
    void profitIsShownOnlyForBranchesWhereItIsHeld() {
        stockForProfit();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.GET,
                "/reports/valuation",
                null,
                "retail.stock.read",
                "*",
                Map.of("X-Dev-Scopes", "retail.profit.read=" + t.headOffice()));
        JsonNode v = ok(r);
        for (JsonNode row : v.get("rows")) {
            boolean hq = row.get("branch_id").asString().equals(t.headOffice().toString());
            assertThat(row.has("expected_profit_minor")).as("%s", row).isEqualTo(hq);
        }
        for (JsonNode b : v.get("branches")) {
            boolean hq = b.get("branch_id").asString().equals(t.headOffice().toString());
            assertThat(b.has("expected_profit_minor")).isEqualTo(hq);
        }
        assertThat(v.has("expected_profit_minor")).isFalse();
        assertThat(v.has("expected_profit_bp")).isFalse();
        for (JsonNode c : v.get("categories")) {
            // The cable category spans both branches, so it shows no profit; the others sit at head office.
            boolean cable = c.get("category").asString().endsWith("CABLE");
            assertThat(c.has("expected_profit_minor")).as("%s", c).isEqualTo(!cable);
        }
    }

    @Test
    void noPercentWhenTheValueAtCostIsZero() {
        UUID free = api.product("PAR-FREE", 0, 500);
        api.stockUp(t.headOffice(), free, "2");
        JsonNode v = ok(api.get("/reports/valuation?branch_id=" + t.headOffice(), ADMIN));
        for (JsonNode r : v.get("rows")) {
            if (r.get("code").asString().equals("PAR-FREE")) {
                assertThat(r.get("expected_profit_minor").asLong()).isEqualTo(1_000);
                assertThat(r.has("expected_profit_bp")).isFalse();
            }
        }
    }

    // ---- C. All branches (#144) -------------------------------------------------------------

    /** Head office: 10 cable, 4 bulb. Branch two: 5 cable and, left by imported history, minus 2 bulb. */
    void stockInTwoBranches() {
        api.stockUp(t.secondBranch(), cable, "5");
        api.importedBalance(t.secondBranch(), bulb, "-2");
    }

    JsonNode cell(JsonNode row, UUID branch) {
        for (JsonNode b : row.get("balances")) {
            if (b.get("branch_id").asString().equals(branch.toString())) {
                return b;
            }
        }
        throw new AssertionError("no cell for " + branch + " in " + row);
    }

    JsonNode row(JsonNode page, String code) {
        for (JsonNode r : page.get("items")) {
            if (r.get("code").asString().equals(code)) {
                return r;
            }
        }
        throw new AssertionError("no row " + code + " in " + page);
    }

    @Test
    void allBranchesShowsOneRowPerProductWithAColumnPerBranchAndATotal() {
        stockInTwoBranches();
        JsonNode page = ok(api.get("/stock/all-branches", ADMIN));
        assertThat(page.get("branches")).hasSize(2);
        assertThat(page.get("branches").get(0).get("id").asString())
                .isEqualTo(t.headOffice().toString());
        JsonNode c = row(page, "PAR-CABLE");
        assertThat(c.get("total_qty").asString()).isEqualTo("15.000");
        assertThat(cell(c, t.headOffice()).get("qty").asString()).isEqualTo("10.000");
        assertThat(cell(c, t.secondBranch()).get("qty").asString()).isEqualTo("5.000");
        assertThat(c.get("negative").asBoolean()).isFalse();
        assertThat(c.get("category").asString()).isEqualTo("Test Category PAR-CABLE");
        assertThat(c.get("cost_minor").asLong()).isEqualTo(1_000);
        JsonNode b = row(page, "PAR-BULB");
        assertThat(b.get("total_qty").asString()).isEqualTo("2.000");
        assertThat(b.get("negative").asBoolean()).isTrue();
        assertThat(cell(b, t.headOffice()).get("negative").asBoolean()).isFalse();
        assertThat(cell(b, t.secondBranch()).get("negative").asBoolean()).isTrue();
        assertThat(cell(b, t.secondBranch()).get("qty").asString()).isEqualTo("-2.000");
    }

    @Test
    void allBranchesSearchesFiltersByCategoryAndShowsOnlyNegativeStock() {
        stockInTwoBranches();
        assertThat(ok(api.get("/stock/all-branches?negative_only=true", ADMIN)).get("items"))
                .hasSize(1);
        assertThat(ok(api.get("/stock/all-branches?query=cable", ADMIN)).get("items"))
                .hasSize(1);
        assertThat(ok(api.get("/stock/all-branches?query=Category PAR-BULB", ADMIN))
                        .get("items"))
                .hasSize(1);
        UUID category = UUID.fromString(row(ok(api.get("/stock/all-branches", ADMIN)), "PAR-CABLE")
                .get("category_id")
                .asString());
        JsonNode one = ok(api.get("/stock/all-branches?category_id=" + category, ADMIN))
                .get("items");
        assertThat(one).hasSize(1);
        assertThat(one.get(0).get("code").asString()).isEqualTo("PAR-CABLE");
    }

    @Test
    void allBranchesPagesByCode() {
        stockInTwoBranches();
        JsonNode first = ok(api.get("/stock/all-branches?limit=1", ADMIN));
        assertThat(first.get("items")).hasSize(1);
        assertThat(first.get("next_cursor").asString()).isNotBlank();
        JsonNode second = ok(api.get(
                "/stock/all-branches?limit=1&cursor=" + first.get("next_cursor").asString(), ADMIN));
        assertThat(second.get("items").get(0).get("code").asString()).isEqualTo("PAR-CABLE");
        assertThat(second.get("next_cursor").isNull()).isTrue();
    }

    @Test
    void allBranchesStaysInsideTheCallersBranchScope() {
        stockInTwoBranches();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.GET,
                "/stock/all-branches",
                null,
                "retail.stock.read",
                t.headOffice().toString(),
                Map.of());
        JsonNode page = ok(r);
        assertThat(page.get("branches")).hasSize(1);
        JsonNode c = row(page, "PAR-CABLE");
        assertThat(c.get("total_qty").asString()).isEqualTo("10.000");
        assertThat(c.get("balances")).hasSize(1);
        assertThat(page.toString()).doesNotContain(t.secondBranch().toString());
        assertThat(row(page, "PAR-BULB").get("negative").asBoolean()).isFalse();
    }

    @Test
    void allBranchesShowsCostOnlyWhereProfitIsHeldInEveryBranch() {
        stockInTwoBranches();
        assertThat(row(ok(api.get("/stock/all-branches", "retail.stock.read")), "PAR-CABLE")
                        .has("cost_minor"))
                .isFalse();
        ResponseEntity<JsonNode> partial = api.call(
                HttpMethod.GET,
                "/stock/all-branches",
                null,
                "retail.stock.read",
                "*",
                Map.of("X-Dev-Scopes", "retail.profit.read=" + t.headOffice()));
        assertThat(row(ok(partial), "PAR-CABLE").has("cost_minor")).isFalse();
        assertThat(ok(partial).toString()).doesNotContain("cost");
    }

    @Test
    void aCallerCoveringManyBranchesMustStillNameOneForTheSingleBranchStockRead() {
        ResponseEntity<JsonNode> r = api.get("/stock", ADMIN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("branch_required");
    }

    @Test
    void valuationAndDailyProfitAcceptNoBranchAndSplitPerBranch() {
        stockInTwoBranches();
        assertThat(api.sell(t.headOffice(), "cash", cable, "1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(api.sell(t.secondBranch(), "cash", cable, "2").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode v = ok(api.get("/reports/valuation", ADMIN));
        assertThat(v.get("branches")).hasSize(2);
        JsonNode profit = ok(api.get("/reports/profit/daily", ADMIN));
        assertThat(profit.get("rows")).hasSize(2);
        assertThat(profit.get("sales_minor").asLong()).isEqualTo(4_500);
        assertThat(profit.get("profit_minor").asLong()).isEqualTo(1_500);
    }

    // ---- D. Low stock and out of stock ------------------------------------------------------

    /** Head office holds 10 cable and 4 bulb (setUp), plus 6 high, 5 five, 0 zero and minus 1 owed. */
    void stockLevels() {
        UUID high = api.product("PAR-HIGH", 100, 150);
        UUID five = api.product("PAR-FIVE", 100, 150);
        api.product("PAR-ZERO", 100, 150);
        UUID owed = api.product("PAR-OWED", 100, 150);
        api.stockUp(t.headOffice(), high, "6");
        api.stockUp(t.headOffice(), five, "5");
        api.importedBalance(t.headOffice(), owed, "-1");
    }

    java.util.List<String> codes(JsonNode page) {
        java.util.List<String> out = new java.util.ArrayList<>();
        page.get("items").forEach(r -> out.add(r.get("code").asString()));
        return out;
    }

    @Test
    void outOfStockIsZeroOrLessAndLowStockIsAtOrBelowTheThreshold() {
        stockLevels();
        JsonNode out = ok(api.get("/stock?branch_id=" + t.headOffice() + "&stock_level=out", ADMIN));
        assertThat(codes(out)).containsExactly("PAR-OWED", "PAR-ZERO");
        JsonNode low = ok(api.get("/stock?branch_id=" + t.headOffice() + "&stock_level=low", ADMIN));
        assertThat(codes(low)).containsExactly("PAR-BULB", "PAR-FIVE", "PAR-OWED", "PAR-ZERO");
        assertThat(low.get("low_stock_threshold").asString()).isEqualTo("5.000");
        JsonNode all = ok(api.get("/stock?branch_id=" + t.headOffice(), ADMIN));
        assertThat(codes(all)).hasSize(6);
        assertThat(all.get("low_stock_threshold").asString()).isEqualTo("5.000");
    }

    @Test
    void theLevelFilterCombinesWithCategoryAndSearch() {
        stockLevels();
        JsonNode low = ok(api.get("/stock?branch_id=" + t.headOffice() + "&stock_level=low&query=five", ADMIN));
        assertThat(codes(low)).containsExactly("PAR-FIVE");
    }

    @Test
    void anUnknownLevelIsAValidationError() {
        ResponseEntity<JsonNode> r = api.get("/stock?branch_id=" + t.headOffice() + "&stock_level=empty", ADMIN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("stock_level");
    }

    @Test
    void allBranchesJudgesTheLevelOnTheTotalAcrossBranches() {
        stockLevels();
        api.stockUp(t.secondBranch(), cable, "1");
        api.stockUp(t.secondBranch(), bulb, "3");
        // Bulb totals 7 across branches and is no longer low; five stays at 5; cable stays at 11.
        JsonNode low = ok(api.get("/stock/all-branches?stock_level=low", ADMIN));
        assertThat(codes(low)).containsExactly("PAR-FIVE", "PAR-OWED", "PAR-ZERO");
        assertThat(low.get("low_stock_threshold").asString()).isEqualTo("5.000");
        assertThat(codes(ok(api.get("/stock/all-branches?stock_level=out", ADMIN))))
                .containsExactly("PAR-OWED", "PAR-ZERO");
    }
}
