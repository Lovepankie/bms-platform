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

    // ---- E. Sales filters -------------------------------------------------------------------

    /** Head office: a cash sale of cable and a credit sale of bulb; branch two: a credit sale of cable. */
    UUID[] threeSales() {
        api.stockUp(t.secondBranch(), cable, "5");
        UUID s1 = RetailTestSupport.id(api.sell(t.headOffice(), "cash", cable, "1"));
        UUID s2 = RetailTestSupport.id(api.sell(t.headOffice(), "credit", bulb, "2"));
        UUID s3 = RetailTestSupport.id(api.sell(t.secondBranch(), "credit", cable, "1"));
        return new UUID[] {s1, s2, s3};
    }

    int count(String query) {
        return ok(api.get("/sales" + query, ADMIN)).get("items").size();
    }

    @Test
    void salesFilterByPaymentMethodProductBuyerBranchAndStatus() {
        UUID[] s = threeSales();
        assertThat(count("")).isEqualTo(3);
        assertThat(count("?payment_method=credit")).isEqualTo(2);
        assertThat(count("?payment_method=cash")).isEqualTo(1);
        assertThat(count("?product_id=" + bulb)).isEqualTo(1);
        assertThat(count("?product_id=" + cable)).isEqualTo(2);
        assertThat(count("?buyer=buyer 01")).isEqualTo(2);
        assertThat(count("?buyer=nobody")).isZero();
        assertThat(count("?branch_id=" + t.secondBranch())).isEqualTo(1);
        assertThat(count("?payment_method=credit&product_id=" + cable)).isEqualTo(1);
        assertThat(count("?to=2000-01-01")).isZero();

        assertThat(api.post("/sales/" + s[0] + "/void", Map.of("reason", "Test void"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(count("?status=voided")).isEqualTo(1);
        assertThat(count("?status=completed")).isEqualTo(2);
    }

    @Test
    void salesRefuseAnUnknownPaymentMethodOrStatus() {
        assertThat(api.get("/sales?payment_method=barter", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/sales?status=lost", ADMIN).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void salesListNewestFirstAndPageWithoutRepeats() {
        UUID[] s = threeSales();
        JsonNode newest = ok(api.get("/sales?newest_first=true", ADMIN)).get("items");
        assertThat(newest.get(0).get("id").asString()).isEqualTo(s[2].toString());
        assertThat(newest.get(2).get("id").asString()).isEqualTo(s[0].toString());
        JsonNode oldest = ok(api.get("/sales", ADMIN)).get("items");
        assertThat(oldest.get(0).get("id").asString()).isEqualTo(s[0].toString());

        JsonNode first = ok(api.get("/sales?newest_first=true&limit=2", ADMIN));
        assertThat(first.get("items")).hasSize(2);
        JsonNode rest = ok(api.get(
                "/sales?newest_first=true&limit=2&cursor="
                        + first.get("next_cursor").asString(),
                ADMIN));
        assertThat(rest.get("items")).hasSize(1);
        assertThat(rest.get("items").get(0).get("id").asString()).isEqualTo(s[0].toString());
    }

    @Test
    void salesFiltersStayInsideTheBranchScopeAndHideProfitFromTheSalesRole() {
        threeSales();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.GET,
                "/sales?payment_method=credit",
                null,
                RetailTestSupport.SALES,
                t.headOffice().toString(),
                Map.of());
        JsonNode items = ok(r).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("branch_id").asString())
                .isEqualTo(t.headOffice().toString());
        assertThat(ok(r).toString()).doesNotContain("profit").doesNotContain("cost");
    }

    // ---- F. The owing filter, buyer search and cursor direction (review of #154) -----------------

    /** A credit sale of one cable; {@code days} is the sale's age and {@code dueInDays} may be negative. */
    UUID creditSale(int days, int dueInDays, UUID customerId, String buyerName) {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("sale_date", today.minusDays(days).toString());
        body.put("payment_method", "credit");
        body.put("due_date", today.plusDays(dueInDays).toString());
        if (customerId != null) {
            body.put("customer_id", customerId);
        }
        if (buyerName != null) {
            body.put("buyer_name", buyerName);
        }
        body.put("lines", java.util.List.of(Map.of("product_id", cable, "qty", "1")));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    java.util.Set<String> ids(JsonNode page) {
        java.util.Set<String> out = new java.util.HashSet<>();
        page.get("items").forEach(r -> out.add(r.get("id").asString()));
        return out;
    }

    @Test
    void theOwingFilterRunsOnTheServerAcrossMoreThanOnePageOfCreditSales() {
        api.stockUp(t.headOffice(), cable, "100");
        // The oldest three are overdue (one of them paid off); 52 newer ones are not yet due.
        UUID overdueA = creditSale(60, -20, null, "Test Buyer 01");
        UUID overdueB = creditSale(59, -19, null, "Test Buyer 01");
        UUID overduePaid = creditSale(58, -18, null, "Test Buyer 01");
        for (int i = 0; i < 52; i++) {
            creditSale(10, 30, null, "Test Buyer 02");
        }
        assertThat(api.postKeyed(
                                "/sales/" + overduePaid + "/payments",
                                Map.of("amount_minor", 1_500, "method", "cash"),
                                ADMIN,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // The first page of the plain list (50 rows) holds none of the three: the old client filter missed them.
        JsonNode firstPage = ok(api.get("/sales?payment_method=credit&newest_first=true&limit=50", ADMIN));
        assertThat(ids(firstPage)).doesNotContain(overdueA.toString(), overdueB.toString());

        JsonNode overdue = ok(api.get("/sales?payment_method=credit&owing=overdue&limit=50", ADMIN));
        assertThat(ids(overdue)).containsExactlyInAnyOrder(overdueA.toString(), overdueB.toString());
        JsonNode owing = ok(api.get("/sales?owing=owing&limit=100", ADMIN));
        assertThat(owing.get("items")).hasSize(54);
        assertThat(ids(owing)).doesNotContain(overduePaid.toString());
        JsonNode paid = ok(api.get("/sales?owing=paid", ADMIN));
        assertThat(ids(paid)).containsExactly(overduePaid.toString());
    }

    @Test
    void aVoidedCreditSaleIsNeverOwingOrOverdue() {
        api.stockUp(t.headOffice(), cable, "100");
        UUID s = creditSale(30, -5, null, "Test Buyer 01");
        assertThat(api.post("/sales/" + s + "/void", Map.of("reason", "Test void"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(count("?owing=overdue")).isZero();
        assertThat(count("?owing=owing")).isZero();
        assertThat(count("?owing=paid")).isZero();
    }

    @Test
    void anUnknownOwingValueIsAValidationError() {
        ResponseEntity<JsonNode> r = api.get("/sales?owing=late", ADMIN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("owing");
    }

    @Test
    void buyerSearchAlsoMatchesTheLinkedCustomersName() {
        api.stockUp(t.headOffice(), cable, "100");
        UUID customer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Customer Zed"), ADMIN));
        UUID linked = creditSale(1, 10, customer, null);
        creditSale(1, 10, null, "Test Buyer 01");
        assertThat(ids(ok(api.get("/sales?buyer=customer zed", ADMIN)))).containsExactly(linked.toString());
        assertThat(count("?buyer=test")).isEqualTo(2);
    }

    @Test
    void aCursorIsRefusedForTheOtherDirection() {
        threeSales();
        JsonNode newest = ok(api.get("/sales?newest_first=true&limit=1", ADMIN));
        String cursor = newest.get("next_cursor").asString();
        ResponseEntity<JsonNode> wrong = api.get("/sales?newest_first=false&limit=1&cursor=" + cursor, ADMIN);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(wrong.getBody().toString()).contains("cursor");
        JsonNode oldest = ok(api.get("/sales?limit=1", ADMIN));
        ResponseEntity<JsonNode> wrong2 = api.get(
                "/sales?newest_first=true&limit=1&cursor="
                        + oldest.get("next_cursor").asString(),
                ADMIN);
        assertThat(wrong2.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // ---- G. Inactive branches in All branches (review of #154) ------------------------------

    @Test
    void allBranchesKeepsAnInactiveBranchThatStillHoldsStockAndDropsAnEmptyOne() {
        stockInTwoBranches();
        TestDatabase.owner()
                .sql("UPDATE branches SET status = 'inactive' WHERE id = ?")
                .param(t.secondBranch())
                .update();
        JsonNode page = ok(api.get("/stock/all-branches", ADMIN));
        assertThat(page.get("branches")).hasSize(2);
        assertThat(row(page, "PAR-BULB").get("negative").asBoolean()).isTrue();
        assertThat(row(page, "PAR-CABLE").get("total_qty").asString()).isEqualTo("15.000");

        // Once the closed branch is emptied it is no longer a column.
        TestDatabase.owner()
                .sql("UPDATE retail_stock_balances SET qty = 0 WHERE branch_id = ?")
                .param(t.secondBranch())
                .update();
        JsonNode after = ok(api.get("/stock/all-branches", ADMIN));
        assertThat(after.get("branches")).hasSize(1);
        assertThat(after.toString()).doesNotContain(t.secondBranch().toString());
    }

    // ---- H. Isolation, escaping and partial scope (review of #154) --------------------------

    @Test
    void anotherTenantsCategoryIdFindsNothingAndNeverLeaks() {
        TestDatabase.Fixture other = TestDatabase.tenant("retail-par-other", false, true);
        RetailTestSupport theirs = new RetailTestSupport(http, other);
        UUID foreign = theirs.category("Test Foreign Category");
        UUID theirProduct = theirs.product("FOREIGN-1", 100, 200);
        theirs.stockUp(other.headOffice(), theirProduct, "3");
        assertThat(ok(api.get("/stock?branch_id=" + t.headOffice() + "&category_id=" + foreign, ADMIN))
                        .get("items"))
                .isEmpty();
        assertThat(ok(api.get("/stock/all-branches?category_id=" + foreign, ADMIN))
                        .get("items"))
                .isEmpty();
        assertThat(ok(api.get("/products?category_id=" + foreign, ADMIN)).get("items"))
                .isEmpty();
        // The valuation has no category filter; it still lists only this tenant's categories.
        JsonNode v = ok(api.get("/reports/valuation", ADMIN));
        assertThat(v.toString()).doesNotContain(foreign.toString()).doesNotContain("FOREIGN-1");
    }

    @Test
    void aLiteralPercentOrUnderscoreInASearchIsNotAWildcard() {
        api.stockUp(t.headOffice(), cable, "100");
        creditSale(1, 10, null, "Test Buyer A_1");
        creditSale(1, 10, null, "Test Buyer AX1");
        assertThat(count("?buyer=%")).isZero();
        assertThat(count("?buyer=_")).isEqualTo(1);
        assertThat(count("?buyer=x_")).isZero();
        assertThat(count("?buyer=buyer a_1")).isEqualTo(1);
        creditSale(1, 10, null, "Test 50% Buyer");
        assertThat(count("?buyer=%")).isEqualTo(1);
        assertThat(count("?buyer=50%")).isEqualTo(1);

        for (String q : new String[] {"%", "_"}) {
            assertThat(ok(api.get("/stock?branch_id=" + t.headOffice() + "&query=" + q, ADMIN))
                            .get("items"))
                    .as("stock query %s", q)
                    .isEmpty();
            assertThat(ok(api.get("/stock/all-branches?query=" + q, ADMIN)).get("items"))
                    .as("all-branches query %s", q)
                    .isEmpty();
            assertThat(ok(api.get("/products?query=" + q, ADMIN)).get("items"))
                    .as("products query %s", q)
                    .isEmpty();
        }
    }

    @Test
    void aCallerHoldingStockAndProfitAtHeadOfficeOnlySeesCostForThatColumnAlone() {
        stockInTwoBranches();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.GET,
                "/stock/all-branches",
                null,
                "retail.stock.read,retail.profit.read",
                t.headOffice().toString(),
                Map.of());
        JsonNode page = ok(r);
        assertThat(page.get("branches")).hasSize(1);
        JsonNode c = row(page, "PAR-CABLE");
        assertThat(c.get("cost_minor").asLong()).isEqualTo(1_000);
        assertThat(c.get("balances")).hasSize(1);
        assertThat(c.get("total_qty").asString()).isEqualTo("10.000");
        assertThat(page.toString()).doesNotContain(t.secondBranch().toString());
        // The same caller reading the valuation sees profit for head office rows and no other branch.
        JsonNode v = ok(api.call(
                HttpMethod.GET,
                "/reports/valuation",
                null,
                "retail.stock.read,retail.profit.read",
                t.headOffice().toString(),
                Map.of()));
        assertThat(v.has("expected_profit_minor")).isTrue();
        assertThat(v.toString()).doesNotContain(t.secondBranch().toString());
    }
}
