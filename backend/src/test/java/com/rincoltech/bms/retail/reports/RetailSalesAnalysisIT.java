package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** The sales analysis (issue #149, step 1): figures worked by hand in {@link AnalyticsData}. */
class RetailSalesAnalysisIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    AnalyticsData d;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-ana", false, true);
        api = new RetailTestSupport(http, t);
        d = new AnalyticsData(t, api);
        d.products();
        d.stock();
        d.sales();
    }

    JsonNode analysis(String query, String permissions) {
        ResponseEntity<JsonNode> r = api.get("/reports/sales-analysis" + query, permissions);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void totalsSeriesAndRankingsWithProfitForAProfitReader() {
        JsonNode a = analysis("", ADMIN);
        assertThat(a.get("profit_visible").asBoolean()).isTrue();
        JsonNode totals = a.get("totals");
        assertThat(totals.get("sales_minor").asLong()).isEqualTo(5_400);
        assertThat(totals.get("sale_count").asLong()).isEqualTo(3);
        assertThat(totals.get("gross_profit_minor").asLong()).isEqualTo(1_800);
        assertThat(totals.get("margin_bp").asLong()).isEqualTo(3_333);

        // 10 days ago 900, yesterday 3,000, today 1,500 (the voided sale does not count).
        JsonNode series = a.get("series");
        assertThat(series).hasSize(3);
        assertThat(series.get(0).get("period_start").asString())
                .isEqualTo(AnalyticsData.today().minusDays(10).toString());
        assertThat(series.get(0).get("sales_minor").asLong()).isEqualTo(900);
        assertThat(series.get(1).get("sales_minor").asLong()).isEqualTo(3_000);
        assertThat(series.get(2).get("sales_minor").asLong()).isEqualTo(1_500);
        assertThat(series.get(2).get("gross_profit_minor").asLong()).isEqualTo(500);

        Map<String, JsonNode> branches = new java.util.HashMap<>();
        a.get("by_branch").forEach(r -> branches.put(r.get("id").asString(), r));
        assertThat(branches.get(t.headOffice().toString()).get("sales_minor").asLong())
                .isEqualTo(3_900);
        assertThat(branches.get(t.headOffice().toString())
                        .get("gross_profit_minor")
                        .asLong())
                .isEqualTo(1_300);
        assertThat(branches.get(t.secondBranch().toString()).get("sales_minor").asLong())
                .isEqualTo(1_500);

        JsonNode byProduct = a.get("by_product");
        assertThat(byProduct.get(0).get("code").asString()).isEqualTo("ANA-1");
        assertThat(byProduct.get(0).get("sales_minor").asLong()).isEqualTo(4_500);
        assertThat(byProduct.get(0).get("qty").asString()).isEqualTo("3.000");
        assertThat(byProduct.get(0).get("margin_bp").asLong()).isEqualTo(3_333);
        assertThat(byProduct.get(1).get("code").asString()).isEqualTo("ANA-2");
        assertThat(a.get("top_by_quantity").get(0).get("code").asString()).isEqualTo("ANA-1");
        assertThat(a.get("by_category")).hasSize(2);
        assertThat(a.get("by_seller")).hasSize(1);
        assertThat(a.get("by_seller").get(0).get("id").asString())
                .isEqualTo(api.userId().toString());
        assertThat(a.get("by_seller").get(0).get("sales_minor").asLong()).isEqualTo(5_400);
    }

    @Test
    void weeklyAndMonthlyBucketsAddUpToTheTotal() {
        for (String group : new String[] {"week", "month"}) {
            JsonNode a = analysis("?group=" + group, ADMIN);
            long sum = 0;
            for (JsonNode p : a.get("series")) {
                sum += p.get("sales_minor").asLong();
            }
            assertThat(sum).as(group).isEqualTo(5_400);
            assertThat(a.get("series").size()).isLessThanOrEqualTo(3);
        }
    }

    @Test
    void slowMoversAndItemsWithNoSalesAtAll() {
        // ANA-2 last sold 10 days ago: not slow over 30 days, slow over 7. ANA-3 was never sold.
        JsonNode thirty = analysis("?slow_days=30", ADMIN);
        assertThat(thirty.get("slow_movers_total").asInt()).isEqualTo(1);
        assertThat(thirty.get("slow_movers").get(0).get("code").asString()).isEqualTo("ANA-3");
        assertThat(thirty.get("slow_movers").get(0).get("qty_on_hand").asString())
                .isEqualTo("4.000");
        assertThat(thirty.get("slow_movers").get(0).get("stock_at_price_minor").asLong())
                .isEqualTo(2_800);
        JsonNode seven = analysis("?slow_days=7", ADMIN);
        assertThat(seven.get("slow_movers_total").asInt()).isEqualTo(2);

        // Over the last five days only ANA-1 sold: ANA-2, ANA-3 and the unstocked ANA-4 had no sale.
        JsonNode five = analysis("?from=" + AnalyticsData.today().minusDays(5) + "&to=" + AnalyticsData.today(), ADMIN);
        assertThat(five.get("no_sales_total").asInt()).isEqualTo(3);
        assertThat(five.get("no_sales").get(0).get("code").asString()).isEqualTo("ANA-2");
        assertThat(five.get("no_sales").get(2).get("code").asString()).isEqualTo("ANA-4");
        assertThat(five.get("no_sales").get(2).get("qty_on_hand").asString()).isEqualTo("0.000");
        assertThat(five.get("totals").get("sales_minor").asLong()).isEqualTo(4_500);
    }

    @Test
    void theStockedListsNeedStockReadAndAreOtherwiseLeftOut() {
        JsonNode saleOnly = analysis("", "retail.sale.read");
        assertThat(saleOnly.get("totals").get("sales_minor").asLong()).isEqualTo(5_400);
        assertThat(saleOnly.has("slow_movers")).isFalse();
        assertThat(saleOnly.has("no_sales")).isFalse();
        assertThat(AnalyticsData.hasKey(saleOnly, "qty_on_hand")).isFalse();
        assertThat(AnalyticsData.hasKey(saleOnly, "stock_at_price")).isFalse();
        // A seller with stock read keeps them.
        assertThat(analysis("", SALES).has("slow_movers")).isTrue();
    }

    @Test
    void theRowLimitAppliesToListsButNotToTotals() {
        JsonNode a = analysis("?top=1", ADMIN);
        assertThat(a.get("by_product")).hasSize(1);
        assertThat(a.get("top_by_quantity")).hasSize(1);
        assertThat(a.get("totals").get("sales_minor").asLong()).isEqualTo(5_400);
    }

    @Test
    void aCallerWithoutProfitReadNeverSeesCostOrProfitAndWritesNoAudit() {
        long audit = AnalyticsData.auditRows();
        JsonNode a = analysis("", SALES);
        assertThat(a.get("profit_visible").asBoolean()).isFalse();
        assertThat(a.get("totals").get("sales_minor").asLong()).isEqualTo(5_400);
        for (String fragment : new String[] {"cost", "profit_minor", "margin", "gross"}) {
            assertThat(AnalyticsData.hasKey(a, fragment)).as(fragment).isFalse();
        }
        assertThat(a.toString()).doesNotContain("3333", "1800");
        assertThat(AnalyticsData.auditRows()).isEqualTo(audit);
    }

    @Test
    void aProfitReaderScopedToOneBranchSeesOnlyThatBranch() {
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.GET,
                "/reports/sales-analysis",
                null,
                ADMIN,
                t.headOffice().toString(),
                Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("totals").get("sales_minor").asLong()).isEqualTo(3_900);
        // Asking for the other branch returns nothing: the scope wins.
        ResponseEntity<JsonNode> other = api.call(
                HttpMethod.GET,
                "/reports/sales-analysis?branch_id=" + t.secondBranch(),
                null,
                ADMIN,
                t.headOffice().toString(),
                Map.of());
        assertThat(other.getBody().get("totals").get("sales_minor").asLong()).isZero();
        assertThat(other.getBody().get("by_product")).isEmpty();
    }

    @Test
    void permissionAndInputRules() {
        assertThat(api.get("/reports/sales-analysis", "retail.stock.read").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        long audit = AnalyticsData.auditRows();
        ResponseEntity<JsonNode> denied = api.get("/reports/sales-analysis", "retail.stock.read");
        assertThat(denied.getBody().toString()).doesNotContain("1800", "3600", "5400");
        assertThat(AnalyticsData.auditRows()).isEqualTo(audit);
        String today = AnalyticsData.today().toString();
        assertThat(api.get(
                                "/reports/sales-analysis?from="
                                        + AnalyticsData.today().minusDays(366) + "&to=" + today,
                                ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get(
                                "/reports/sales-analysis?from="
                                        + AnalyticsData.today().minusDays(365) + "&to=" + today,
                                ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.get("/reports/sales-analysis?group=year", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/reports/sales-analysis?slow_days=6", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/reports/sales-analysis?slow_days=181", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void profitHeldAtOneBranchOnlyShowsForThatBranchAndNeverInAMixedFigure() {
        Map<String, String> scopes = Map.of("X-Dev-Scopes", "retail.profit.read=" + t.headOffice());
        ResponseEntity<JsonNode> mixed = api.call(HttpMethod.GET, "/reports/sales-analysis", null, ADMIN, "*", scopes);
        assertThat(mixed.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = mixed.getBody();
        assertThat(body.get("profit_visible").asBoolean()).isFalse();
        assertThat(AnalyticsData.hasKey(body.get("totals"), "profit")).isFalse();
        assertThat(AnalyticsData.hasKey(body.get("by_product"), "profit")).isFalse();
        assertThat(AnalyticsData.hasKey(body.get("series"), "profit")).isFalse();
        for (JsonNode row : body.get("by_branch")) {
            boolean headOffice = row.get("id").asString().equals(t.headOffice().toString());
            assertThat(row.has("gross_profit_minor")).as("branch row %s", row).isEqualTo(headOffice);
        }
        assertThat(body.toString()).doesNotContain("1800");

        // Asking for head office alone is a report over a branch where the permission is held.
        ResponseEntity<JsonNode> one = api.call(
                HttpMethod.GET, "/reports/sales-analysis?branch_id=" + t.headOffice(), null, ADMIN, "*", scopes);
        assertThat(one.getBody().get("profit_visible").asBoolean()).isTrue();
        assertThat(one.getBody().get("totals").get("gross_profit_minor").asLong())
                .isEqualTo(1_300);
        // And for branch two alone, none, though the sales are the caller's to read.
        ResponseEntity<JsonNode> other = api.call(
                HttpMethod.GET, "/reports/sales-analysis?branch_id=" + t.secondBranch(), null, ADMIN, "*", scopes);
        assertThat(other.getBody().get("totals").get("sales_minor").asLong()).isEqualTo(1_500);
        assertThat(AnalyticsData.hasKey(other.getBody(), "gross")).isFalse();
    }
}
