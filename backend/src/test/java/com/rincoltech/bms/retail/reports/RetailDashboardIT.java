package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.HashMap;
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
 * The owner dashboard (issue #149, step 6). The data of {@link AnalyticsData} plus, today: head office sells
 * 2 ANA-2 for cash (600, cost 400) and 1 ANA-1 on credit (1,500, cost 1,000); branch two sold 1 ANA-1 for
 * cash (1,500, cost 1,000). Today: 3,600, of which cash 2,100 and credit 1,500; profit 1,200. Yesterday 3,000;
 * ten days ago 900. Stock: head office 17 ANA-1, 15 ANA-2, 4 ANA-3 and 5 ANA-5 (cost 100, price 150): 33,550
 * at price, 22,100 at cost; branch two 9 ANA-1 and 0 ANA-4: 13,500 and 9,000. The counts follow the stock list
 * rule: an active item with no balance row counts as zero, low includes out of stock, and All branches judges each
 * item on its summed balance. Head office: ANA-4 out (never stocked there), ANA-3 and ANA-5 low, so 1 out and 3 low;
 * branch two: only ANA-1 in stock, so 4 out and 4 low; all branches: ANA-4 totals 0, ANA-3 4 and ANA-5 5, so 1 out
 * and 3 low.
 */
class RetailDashboardIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    AnalyticsData d;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-dsh", false, true);
        api = new RetailTestSupport(http, t);
        d = new AnalyticsData(t, api);
        d.products();
        d.stock();
        d.sales();
        UUID p5 = api.product("ANA-5", 100, 150);
        api.stockUp(t.headOffice(), p5, "5");
        api.stockUp(t.secondBranch(), d.p4, "2");
        api.stockUp(t.secondBranch(), d.p4, "0");
        d.sell(t.headOffice(), "cash", d.p2, "2", 0);
        d.sell(t.headOffice(), "credit", d.p1, "1", 0);
    }

    JsonNode dashboard(String permissions) {
        ResponseEntity<JsonNode> r = api.get("/reports/dashboard", permissions);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode shop(JsonNode report, UUID id) {
        for (JsonNode s : report.get("shops")) {
            if (s.get("branch_id").asString().equals(id.toString())) {
                return s;
            }
        }
        throw new AssertionError("no shop " + id);
    }

    @Test
    void todayCashAndCreditSparklinesAndStockForAProfitReader() {
        JsonNode r = dashboard(ADMIN);
        assertThat(r.get("date").asString()).isEqualTo(AnalyticsData.today().toString());
        JsonNode today = r.get("today");
        assertThat(today.get("sales_minor").asLong()).isEqualTo(3_600);
        assertThat(today.get("sale_count").asLong()).isEqualTo(3);
        assertThat(today.get("cash_minor").asLong()).isEqualTo(2_100);
        assertThat(today.get("credit_minor").asLong()).isEqualTo(1_500);
        assertThat(today.get("gross_profit_minor").asLong()).isEqualTo(1_200);
        assertThat(r.get("last7_sales_minor").asLong()).isEqualTo(6_600);
        assertThat(r.get("last30_sales_minor").asLong()).isEqualTo(7_500);
        assertThat(r.get("last7_gross_profit_minor").asLong()).isEqualTo(2_200);
        assertThat(r.get("last30_gross_profit_minor").asLong()).isEqualTo(2_500);

        JsonNode days = r.get("days");
        assertThat(days).hasSize(30);
        assertThat(days.get(29).get("date").asString())
                .isEqualTo(AnalyticsData.today().toString());
        assertThat(days.get(29).get("sales_minor").asLong()).isEqualTo(3_600);
        assertThat(days.get(28).get("sales_minor").asLong()).isEqualTo(3_000);
        assertThat(days.get(19).get("sales_minor").asLong()).isEqualTo(900);
        assertThat(days.get(0).get("sales_minor").asLong()).isZero();

        JsonNode stock = r.get("stock");
        assertThat(stock.get("value_at_price_minor").asLong()).isEqualTo(47_050);
        assertThat(stock.get("value_at_cost_minor").asLong()).isEqualTo(31_100);
        assertThat(stock.get("out_of_stock").asInt()).isEqualTo(1);
        assertThat(stock.get("low_stock").asInt()).isEqualTo(3);
        assertThat(stock.get("low_stock_threshold").asString()).isEqualTo("5.000");

        JsonNode hq = shop(r, t.headOffice());
        assertThat(hq.get("today_sales_minor").asLong()).isEqualTo(2_100);
        assertThat(hq.get("last7_sales_minor").asLong()).isEqualTo(5_100);
        assertThat(hq.get("last30_sales_minor").asLong()).isEqualTo(6_000);
        assertThat(hq.get("today_gross_profit_minor").asLong()).isEqualTo(700);
        assertThat(hq.get("stock_at_price_minor").asLong()).isEqualTo(33_550);
        assertThat(hq.get("stock_at_cost_minor").asLong()).isEqualTo(22_100);
        assertThat(hq.get("out_of_stock").asInt()).isEqualTo(1);
        assertThat(hq.get("low_stock").asInt()).isEqualTo(3);
        JsonNode b2 = shop(r, t.secondBranch());
        assertThat(b2.get("today_sales_minor").asLong()).isEqualTo(1_500);
        assertThat(b2.get("out_of_stock").asInt()).isEqualTo(4);
        assertThat(b2.get("low_stock").asInt()).isEqualTo(4);
        assertThat(b2.get("stock_at_price_minor").asLong()).isEqualTo(13_500);
    }

    @Test
    void theLowStockThresholdIsTheOneOfTheStockLists() {
        JsonNode stockList =
                api.get("/stock?branch_id=" + t.headOffice(), ADMIN).getBody();
        assertThat(dashboard(ADMIN).get("stock").get("low_stock_threshold").asString())
                .isEqualTo(stockList.get("low_stock_threshold").asString());
    }

    @Test
    void withoutProfitReadTheSalesAndStockPartsStayAndEveryCostAndProfitFigureGoes() {
        long audit = AnalyticsData.auditRows();
        JsonNode r = dashboard(SALES);
        assertThat(r.get("profit_visible").asBoolean()).isFalse();
        assertThat(r.get("today").get("sales_minor").asLong()).isEqualTo(3_600);
        assertThat(r.get("stock").get("value_at_price_minor").asLong()).isEqualTo(47_050);
        assertThat(AnalyticsData.hasKey(r, "cost")).isFalse();
        assertThat(AnalyticsData.hasKey(r, "gross")).isFalse();
        assertThat(AnalyticsData.hasKey(r, "profit_minor")).isFalse();
        assertThat(r.toString()).doesNotContain("31100", "2500", "1200");
        assertThat(AnalyticsData.auditRows()).isEqualTo(audit);
    }

    @Test
    void withoutStockReadOnlyTheSalesPartsAreShown() {
        JsonNode r = dashboard("retail.sale.read");
        assertThat(r.has("stock")).isFalse();
        assertThat(r.get("today").get("sales_minor").asLong()).isEqualTo(3_600);
        JsonNode hq = shop(r, t.headOffice());
        assertThat(hq.has("stock_at_price_minor")).isFalse();
        assertThat(hq.has("out_of_stock")).isFalse();
        assertThat(AnalyticsData.hasKey(r, "stock")).isFalse();
        assertThat(AnalyticsData.hasKey(r, "cost")).isFalse();
    }

    @Test
    void permissionAndBranchScope() {
        ResponseEntity<JsonNode> denied = api.get("/reports/dashboard", "retail.stock.read,retail.profit.read");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().toString()).doesNotContain("3600", "47050");
        ResponseEntity<JsonNode> scoped = api.call(
                HttpMethod.GET,
                "/reports/dashboard",
                null,
                ADMIN,
                t.secondBranch().toString(),
                Map.of());
        JsonNode body = scoped.getBody();
        assertThat(body.get("today").get("sales_minor").asLong()).isEqualTo(1_500);
        assertThat(body.get("shops")).hasSize(1);
        assertThat(body.get("stock").get("value_at_price_minor").asLong()).isEqualTo(13_500);
        Map<String, Integer> counts = new HashMap<>();
        counts.put("out", body.get("stock").get("out_of_stock").asInt());
        assertThat(counts.get("out")).isEqualTo(4);
        assertThat(body.get("stock").get("low_stock").asInt()).isEqualTo(4);
    }

    @Test
    void theCountsEqualWhatTheStockListsShowForOneBranchAndForAllBranches() {
        // One branch: the stock list of branch two, then the dashboard scoped to branch two.
        for (String level : new String[] {"out", "low"}) {
            int listed = api.get("/stock?branch_id=" + t.secondBranch() + "&stock_level=" + level + "&limit=100", ADMIN)
                    .getBody()
                    .get("items")
                    .size();
            ResponseEntity<JsonNode> scoped = api.call(
                    HttpMethod.GET,
                    "/reports/dashboard",
                    null,
                    ADMIN,
                    t.secondBranch().toString(),
                    Map.of());
            assertThat(scoped.getBody()
                            .get("stock")
                            .get(level.equals("out") ? "out_of_stock" : "low_stock")
                            .asInt())
                    .as("branch two, %s", level)
                    .isEqualTo(listed);
        }
        // All branches: the all-branches list judges each item on its total.
        for (String level : new String[] {"out", "low"}) {
            int listed = api.get("/stock/all-branches?stock_level=" + level + "&limit=100", ADMIN)
                    .getBody()
                    .get("items")
                    .size();
            assertThat(dashboard(ADMIN)
                            .get("stock")
                            .get(level.equals("out") ? "out_of_stock" : "low_stock")
                            .asInt())
                    .as("all branches, %s", level)
                    .isEqualTo(listed);
        }
    }
}
