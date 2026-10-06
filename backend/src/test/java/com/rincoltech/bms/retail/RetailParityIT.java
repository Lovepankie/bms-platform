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
}
