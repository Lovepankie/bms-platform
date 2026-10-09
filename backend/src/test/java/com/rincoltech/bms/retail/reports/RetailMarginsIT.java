package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
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
 * The margin report (issue #149, step 2). The shared sales of {@link AnalyticsData} plus ANA-5 (cost 900,
 * sells at 1,000, one sold today): sales 6,400, cost 4,500, profit 1,900, margin 2,969 basis points.
 */
class RetailMarginsIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    AnalyticsData d;
    UUID p5;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-mar", false, true);
        api = new RetailTestSupport(http, t);
        d = new AnalyticsData(t, api);
        d.products();
        d.stock();
        d.sales();
        p5 = api.product("ANA-5", 900, 1_000);
        api.stockUp(t.headOffice(), p5, "5");
        d.sell(t.headOffice(), "cash", p5, "1", 0);
    }

    JsonNode margins(String query) {
        ResponseEntity<JsonNode> r = api.get("/reports/margins" + query, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void profitAndMarginByItemAndCategory() {
        JsonNode m = margins("");
        assertThat(m.get("target_bp").asInt()).isEqualTo(2_000);
        JsonNode totals = m.get("totals");
        assertThat(totals.get("sales_minor").asLong()).isEqualTo(6_400);
        assertThat(totals.get("cost_minor").asLong()).isEqualTo(4_500);
        assertThat(totals.get("profit_minor").asLong()).isEqualTo(1_900);
        assertThat(totals.get("margin_bp").asLong()).isEqualTo(2_969);
        JsonNode items = m.get("by_product");
        assertThat(items.get(0).get("code").asString()).isEqualTo("ANA-1");
        assertThat(items.get(0).get("profit_minor").asLong()).isEqualTo(1_500);
        assertThat(items.get(0).get("margin_bp").asLong()).isEqualTo(3_333);
        assertThat(items.get(2).get("code").asString()).isEqualTo("ANA-5");
        assertThat(items.get(2).get("margin_bp").asLong()).isEqualTo(1_000);
        assertThat(m.get("by_category")).hasSize(3);
    }

    @Test
    void itemsSoldBelowTheTargetLowestMarginFirst() {
        JsonNode byDefault = margins("").get("below_target");
        assertThat(byDefault.get("total").asInt()).isEqualTo(1);
        assertThat(byDefault.get("items").get(0).get("code").asString()).isEqualTo("ANA-5");
        JsonNode wide = margins("?target_bp=3400").get("below_target");
        assertThat(wide.get("total").asInt()).isEqualTo(3);
        assertThat(wide.get("items").get(0).get("code").asString()).isEqualTo("ANA-5");
        assertThat(wide.get("items").get(1).get("code").asString()).isEqualTo("ANA-1");
        assertThat(margins("?target_bp=3300").get("below_target").get("total").asInt())
                .isEqualTo(1);
        assertThat(margins("?target_bp=0").get("below_target").get("total").asInt())
                .isZero();
        assertThat(margins("?target_bp=3400&top=1").get("below_target").get("items"))
                .hasSize(1);
        assertThat(margins("?target_bp=3400&top=1")
                        .get("below_target")
                        .get("total")
                        .asInt())
                .isEqualTo(3);
    }

    @Test
    void priceChangeImpactUsesTheSnapshotsAndNeverTheCurrentCost() {
        assertThat(margins("").get("price_changes").get("total").asInt()).isZero();
        // ANA-1 goes from 1,500 to 2,000 and its cost from 1,000 to 1,800 today; one more is sold at the new price.
        assertThat(api.editPrices(
                                d.p1, Map.of("cost_minor", 1_800, "sell_minor", 2_000, "reason", "Test change"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        d.sell(t.headOffice(), "cash", d.p1, "1", 0);

        JsonNode changes = margins("").get("price_changes");
        assertThat(changes.get("total").asInt()).isEqualTo(1);
        JsonNode c = changes.get("items").get(0);
        assertThat(c.get("code").asString()).isEqualTo("ANA-1");
        assertThat(c.get("changed_on").asString())
                .isEqualTo(AnalyticsData.today().toString());
        assertThat(c.get("old_sell_minor").asLong()).isEqualTo(1_500);
        assertThat(c.get("new_sell_minor").asLong()).isEqualTo(2_000);
        // Before today: 2 sold at 1,500 over cost 2,000. Today: 1 at 1,500 (branch two) and 1 at 2,000.
        assertThat(c.get("units_before").asString()).isEqualTo("2.000");
        assertThat(c.get("units_after").asString()).isEqualTo("2.000");
        assertThat(c.get("margin_before_bp").asLong()).isEqualTo(3_333);
        // Sales 3,500, cost 1,000 + 1,800: profit 700 is 2,000 basis points.
        assertThat(c.get("sales_after_minor").asLong()).isEqualTo(3_500);
        assertThat(c.get("margin_after_bp").asLong()).isEqualTo(2_000);
        assertThat(c.get("days_before").asInt() + c.get("days_after").asInt()).isEqualTo(30);

        // The whole item's cost is the sum of the snapshots, not units times the new cost.
        JsonNode ana1 = margins("").get("by_product").get(0);
        assertThat(ana1.get("code").asString()).isEqualTo("ANA-1");
        assertThat(ana1.get("cost_minor").asLong()).isEqualTo(2_000 + 1_000 + 1_800);
    }

    @Test
    void onlyAProfitReaderMayReadItAndNothingLeaksToOthers() {
        long audit = AnalyticsData.auditRows();
        ResponseEntity<JsonNode> denied = api.get("/reports/margins", SALES);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().toString()).doesNotContain("6400", "4500", "1900", "2969");
        assertThat(api.get("/reports/margins", "retail.stock.read").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(AnalyticsData.auditRows()).isEqualTo(audit);
    }

    @Test
    void branchScopeAndInputRules() {
        ResponseEntity<JsonNode> scoped = api.call(
                HttpMethod.GET, "/reports/margins", null, ADMIN, t.headOffice().toString(), Map.of());
        assertThat(scoped.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Head office only: 3,000 + 900 + 1,000.
        assertThat(scoped.getBody().get("totals").get("sales_minor").asLong()).isEqualTo(4_900);
        assertThat(api.get("/reports/margins?target_bp=10001", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/reports/margins?target_bp=-1", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get(
                                "/reports/margins?from=" + AnalyticsData.today().minusDays(366) + "&to="
                                        + AnalyticsData.today(),
                                ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
