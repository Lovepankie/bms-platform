package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
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
 * The business future evaluation (issue #149, step 5), worked by hand on the data of {@link AnalyticsData}:
 * head office holds 18 ANA-1 (cost 1,000, price 1,500), 17 ANA-2 (200, 300) and 4 ANA-3 (400, 700): at price
 * 27,000 + 5,100 + 2,800 = 34,900, at cost 18,000 + 3,400 + 1,600 = 23,000, expected profit 11,900 (5,174 basis
 * points over cost). Branch two holds 9 ANA-1: 13,500 at price, 9,000 at cost, 4,500 (5,000). In all 48,400,
 * 32,000, 16,400 (5,125). Over 30 days head office sold 3,900 (profit 1,300) and branch two 1,500 (500).
 */
class RetailEvaluationIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    AnalyticsData d;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-evl", false, true);
        api = new RetailTestSupport(http, t);
        d = new AnalyticsData(t, api);
        d.products();
        d.stock();
        d.sales();
    }

    JsonNode evaluate(String query) {
        ResponseEntity<JsonNode> r = api.get("/reports/business-evaluation" + query, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode branch(JsonNode report, UUID id) {
        for (JsonNode b : report.get("branches")) {
            if (b.get("branch_id").asString().equals(id.toString())) {
                return b;
            }
        }
        throw new AssertionError("no branch " + id);
    }

    @Test
    void profitToDateExpectedProfitOfStockAndTheRunRate() {
        JsonNode r = evaluate("");
        assertThat(r.get("note").asString()).contains("not a forecast");
        JsonNode total = r.get("total");
        assertThat(total.get("period").get("sales_minor").asLong()).isEqualTo(5_400);
        assertThat(total.get("period").get("profit_minor").asLong()).isEqualTo(1_800);
        assertThat(total.get("stock").get("at_price_minor").asLong()).isEqualTo(48_400);
        assertThat(total.get("stock").get("at_cost_minor").asLong()).isEqualTo(32_000);
        assertThat(total.get("stock").get("expected_profit_minor").asLong()).isEqualTo(16_400);
        assertThat(total.get("stock").get("over_cost_bp").asLong()).isEqualTo(5_125);
        assertThat(total.get("run_rate").get("avg_daily_sales_minor").asLong()).isEqualTo(180);
        assertThat(total.get("run_rate").get("avg_daily_profit_minor").asLong()).isEqualTo(60);
        assertThat(total.get("run_rate").get("days_of_stock").asString()).isEqualTo("268.9");

        JsonNode hq = branch(r, t.headOffice());
        assertThat(hq.get("period").get("profit_minor").asLong()).isEqualTo(1_300);
        assertThat(hq.get("stock").get("at_price_minor").asLong()).isEqualTo(34_900);
        assertThat(hq.get("stock").get("expected_profit_minor").asLong()).isEqualTo(11_900);
        assertThat(hq.get("stock").get("over_cost_bp").asLong()).isEqualTo(5_174);
        assertThat(hq.get("run_rate").get("avg_daily_sales_minor").asLong()).isEqualTo(130);
        assertThat(hq.get("run_rate").get("avg_daily_profit_minor").asLong()).isEqualTo(43);
        assertThat(hq.get("run_rate").get("days_of_stock").asString()).isEqualTo("268.5");
        JsonNode b2 = branch(r, t.secondBranch());
        assertThat(b2.get("stock").get("over_cost_bp").asLong()).isEqualTo(5_000);
        assertThat(b2.get("run_rate").get("days_of_stock").asString()).isEqualTo("270.0");

        // Grouped by category then item.
        assertThat(hq.get("categories")).hasSize(3);
        JsonNode first = hq.get("categories").get(0);
        assertThat(first.get("category").asString()).isEqualTo("Test Category ANA-1");
        assertThat(first.get("products")).hasSize(1);
        assertThat(first.get("products").get(0).get("code").asString()).isEqualTo("ANA-1");
        assertThat(first.get("products").get(0).get("qty_on_hand").asString()).isEqualTo("18.000");
        assertThat(first.get("products")
                        .get(0)
                        .get("stock")
                        .get("expected_profit_minor")
                        .asLong())
                .isEqualTo(9_000);
        assertThat(first.get("products")
                        .get(0)
                        .get("period")
                        .get("profit_minor")
                        .asLong())
                .isEqualTo(1_000);
    }

    @Test
    void thePeriodChoosesTheProfitToDateButNotTheRunRate() {
        // The last five days hold only ANA-1: 3,000 yesterday (profit 1,000) and 1,500 at branch two (500).
        JsonNode r = evaluate("?from=" + AnalyticsData.today().minusDays(5) + "&to=" + AnalyticsData.today());
        assertThat(r.get("total").get("period").get("sales_minor").asLong()).isEqualTo(4_500);
        assertThat(branch(r, t.headOffice()).get("period").get("profit_minor").asLong())
                .isEqualTo(1_000);
        assertThat(r.get("total").get("run_rate").get("sales_minor").asLong()).isEqualTo(5_400);
        // A period long ago still shows the stock and the recent pace.
        JsonNode old = evaluate("?from=" + AnalyticsData.today().minusDays(300) + "&to="
                + AnalyticsData.today().minusDays(200));
        assertThat(old.get("total").get("period").get("sales_minor").asLong()).isZero();
        assertThat(old.get("total").get("stock").get("at_price_minor").asLong()).isEqualTo(48_400);
        assertThat(old.get("total").get("run_rate").get("sales_minor").asLong()).isEqualTo(5_400);
    }

    @Test
    void theRowLimitAppliesToItemsOfACategoryNotToItsTotals() {
        JsonNode cat = api.get("/products/" + d.p1, ADMIN).getBody();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "ANA-7");
        body.put("description", "Test Product ANA-7");
        body.put("category_id", cat.get("category_id").asString());
        body.put("unit_id", cat.get("unit_id").asString());
        body.put("cost_minor", 100);
        body.put("sell_minor", 200);
        UUID p7 = RetailTestSupport.id(api.post("/products", body, ADMIN));
        api.stockUp(t.headOffice(), p7, "3");
        JsonNode hq = branch(evaluate("?top=1"), t.headOffice());
        JsonNode category = hq.get("categories").get(0);
        assertThat(category.get("products_total").asInt()).isEqualTo(2);
        assertThat(category.get("products")).hasSize(1);
        assertThat(category.get("products").get(0).get("code").asString()).isEqualTo("ANA-1");
        // 27,000 + 600 at price, 18,000 + 300 at cost.
        assertThat(category.get("stock").get("at_price_minor").asLong()).isEqualTo(27_600);
        assertThat(category.get("stock").get("at_cost_minor").asLong()).isEqualTo(18_300);
        assertThat(branch(evaluate("?top=1"), t.headOffice())
                        .get("stock")
                        .get("at_price_minor")
                        .asLong())
                .isEqualTo(35_500);
    }

    @Test
    void onlyAProfitReaderMayReadItAndNothingLeaksToOthers() {
        long audit = AnalyticsData.auditRows();
        for (String permissions : new String[] {SALES, "retail.stock.read", "retail.sale.read,retail.stock.read"}) {
            ResponseEntity<JsonNode> denied = api.get("/reports/business-evaluation", permissions);
            assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(denied.getBody().toString()).doesNotContain("48400", "16400", "32000");
        }
        assertThat(AnalyticsData.auditRows()).isEqualTo(audit);
        ResponseEntity<JsonNode> only = api.get("/reports/business-evaluation", "retail.profit.read");
        assertThat(only.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void branchScopeAndInputRules() {
        ResponseEntity<JsonNode> scoped = api.call(
                HttpMethod.GET,
                "/reports/business-evaluation",
                null,
                ADMIN,
                t.secondBranch().toString(),
                Map.of());
        assertThat(scoped.getBody().get("branches")).hasSize(1);
        assertThat(scoped.getBody()
                        .get("total")
                        .get("stock")
                        .get("at_price_minor")
                        .asLong())
                .isEqualTo(13_500);
        assertThat(scoped.getBody()
                        .get("total")
                        .get("period")
                        .get("sales_minor")
                        .asLong())
                .isEqualTo(1_500);
        Map<String, Integer> sizes = new HashMap<>();
        sizes.put("branches", scoped.getBody().get("branches").size());
        assertThat(sizes.get("branches")).isEqualTo(1);
        assertThat(api.get(
                                "/reports/business-evaluation?from="
                                        + AnalyticsData.today().minusDays(366) + "&to=" + AnalyticsData.today(),
                                ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
