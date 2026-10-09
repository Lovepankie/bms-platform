package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The analytics reports at the edges of the calendar (issue #149), under a fixed clock: it is 00:30 on Monday
 * 2026-11-02 in Africa/Kampala, still Sunday 2026-11-01 in UTC. Sales on Saturday 2026-10-31 (the last day of a
 * month), Sunday 2026-11-01 (the 1st, the end of an ISO week) and Monday 2026-11-02 (the start of the next week)
 * must land in different month and week buckets, and the dashboard's "today" must be the Kampala day. Fabricated
 * names and amounts. ANA-2 sells at 300 each: 300, 600 and 900.
 */
@Import(RetailAnalyticsCalendarIT.FixedClock.class)
class RetailAnalyticsCalendarIT extends IntegrationTest {

    static final Instant NOW = Instant.parse("2026-11-01T21:30:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    AnalyticsData d;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-cal", false, true);
        api = new RetailTestSupport(http, t);
        d = new AnalyticsData(t, api);
        d.products();
        d.stock();
        sell("2026-10-31", "1");
        sell("2026-11-01", "2");
        sell("2026-11-02", "3");
    }

    void sell(String date, String qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("sale_date", date);
        body.put("payment_method", "cash");
        body.put("lines", List.of(Map.of("product_id", d.p2, "qty", qty)));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isTrue();
    }

    JsonNode analysis(String query, String permissions) {
        ResponseEntity<JsonNode> r = api.get("/reports/sales-analysis" + query, permissions);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void aMonthEndSaleAndAFirstOfTheMonthSaleLandInDifferentMonths() {
        JsonNode series = analysis("?group=month", ADMIN).get("series");
        assertThat(series).hasSize(2);
        assertThat(series.get(0).get("period_start").asString()).isEqualTo("2026-10-01");
        assertThat(series.get(0).get("sales_minor").asLong()).isEqualTo(300);
        assertThat(series.get(1).get("period_start").asString()).isEqualTo("2026-11-01");
        assertThat(series.get(1).get("sales_minor").asLong()).isEqualTo(1_500);
    }

    @Test
    void sundayEndsAnIsoWeekAndMondayStartsTheNext() {
        JsonNode series = analysis("?group=week", ADMIN).get("series");
        assertThat(series).hasSize(2);
        assertThat(series.get(0).get("period_start").asString()).isEqualTo("2026-10-26");
        assertThat(series.get(0).get("sales_minor").asLong()).isEqualTo(900);
        assertThat(series.get(1).get("period_start").asString()).isEqualTo("2026-11-02");
        assertThat(series.get(1).get("sales_minor").asLong()).isEqualTo(900);
    }

    @Test
    void theDashboardTodayIsTheKampalaDayNotTheUtcDay() {
        ResponseEntity<JsonNode> r = api.get("/reports/dashboard", ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = r.getBody();
        assertThat(body.get("date").asString()).isEqualTo("2026-11-02");
        assertThat(body.get("today").get("sales_minor").asLong()).isEqualTo(900);
        JsonNode days = body.get("days");
        assertThat(days.get(29).get("date").asString()).isEqualTo("2026-11-02");
        assertThat(days.get(28).get("sales_minor").asLong()).isEqualTo(600);
        assertThat(days.get(27).get("sales_minor").asLong()).isEqualTo(300);
        assertThat(body.get("last7_sales_minor").asLong()).isEqualTo(1_800);
    }

    @Test
    void aShopOutsideTheSalesScopeCarriesStockButNoInventedSalesZeros() {
        Map<String, String> scopes = Map.of("X-Dev-Scopes", "retail.sale.read=" + t.headOffice());
        ResponseEntity<JsonNode> r =
                api.call(org.springframework.http.HttpMethod.GET, "/reports/dashboard", null, ADMIN, "*", scopes);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode other = null;
        for (JsonNode s : r.getBody().get("shops")) {
            if (s.get("branch_id").asString().equals(t.secondBranch().toString())) {
                other = s;
            }
        }
        assertThat(other).as("the second branch is in the stock scope").isNotNull();
        assertThat(other.has("today_sales_minor")).isFalse();
        assertThat(other.has("last7_sales_minor")).isFalse();
        assertThat(other.has("last30_sales_minor")).isFalse();
        assertThat(other.has("stock_at_price_minor")).isTrue();
    }

    @Test
    void aSaleOnlyCallerGetsNeitherStockedListNorAnyStockQuantity() {
        JsonNode a = analysis("", "retail.sale.read");
        assertThat(a.get("totals").get("sales_minor").asLong()).isEqualTo(1_800);
        assertThat(a.has("slow_movers")).isFalse();
        assertThat(a.has("slow_movers_total")).isFalse();
        assertThat(a.has("no_sales")).isFalse();
        assertThat(a.has("no_sales_total")).isFalse();
        assertThat(AnalyticsData.hasKey(a, "qty_on_hand")).isFalse();
        assertThat(AnalyticsData.hasKey(a, "stock_at_price")).isFalse();
    }

    @Test
    void aCallerWhoReadsStockOnlyAtHeadOfficeSeesNothingOfTheOtherBranch() {
        // Sale read everywhere, stock read at head office only: the lists cover head office alone.
        Map<String, String> scopes = Map.of("X-Dev-Scopes", "retail.stock.read=" + t.headOffice());
        ResponseEntity<JsonNode> r =
                api.call(org.springframework.http.HttpMethod.GET, "/reports/sales-analysis", null, ADMIN, "*", scopes);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        // ANA-3 (4 at head office) never sold; ANA-1 (20 at head office, 10 at branch two) never sold either.
        JsonNode slow = r.getBody().get("slow_movers");
        assertThat(slow).isNotNull();
        String qty = null;
        for (JsonNode item : slow) {
            if (item.get("code").asString().equals("ANA-1")) {
                qty = item.get("qty_on_hand").asString();
            }
        }
        assertThat(qty).isEqualTo("20.000");
    }
}
