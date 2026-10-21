package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.LocalDate;
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
 * The dashboard range of issue #428. The sales of {@link AnalyticsData} plus, as in {@link RetailDashboardIT}, today at head
 * office 2 ANA-2 for cash (600, cost 400) and 1 ANA-1 on credit (1,500, cost 1,000); and 1 ANA-1 for cash at head office 8 days
 * ago (1,500, cost 1,000), 1 ANA-1 for cash at branch two 35 days ago (1,500, cost 1,000) and 1 ANA-2 for cash at head office 400
 * days ago (300, cost 200). Every sale by one user, every amount fabricated. {@link #SALES_BY_DAY} lists them.
 */
class RetailDashboardRangeIT extends IntegrationTest {

    /** Days ago, branch (0 head office, 1 branch two), sales, cost: every completed sale of the fixture. */
    static final long[][] SALES_BY_DAY = {
        {0, 0, 600, 400},
        {0, 0, 1_500, 1_000},
        {0, 1, 1_500, 1_000},
        {1, 0, 3_000, 2_000},
        {8, 0, 1_500, 1_000},
        {10, 0, 900, 600},
        {35, 1, 1_500, 1_000},
        {400, 0, 300, 200}
    };

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    AnalyticsData d;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-dsr", false, true);
        api = new RetailTestSupport(http, t);
        d = new AnalyticsData(t, api);
        d.products();
        d.stock();
        d.sales();
        d.sell(t.headOffice(), "cash", d.p2, "2", 0);
        d.sell(t.headOffice(), "credit", d.p1, "1", 0);
        d.sell(t.headOffice(), "cash", d.p1, "1", 8);
        d.sell(t.secondBranch(), "cash", d.p1, "1", 35);
        d.sell(t.headOffice(), "cash", d.p2, "1", 400);
    }

    static LocalDate today() {
        return AnalyticsData.today();
    }

    JsonNode range(String query, String permissions) {
        ResponseEntity<JsonNode> r = api.get("/reports/dashboard?" + query, permissions);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    /** Sales, count and profit of the fixture between two days, both counted. */
    static long[] expected(LocalDate from, LocalDate to) {
        long[] f = new long[3];
        for (long[] s : SALES_BY_DAY) {
            LocalDate day = today().minusDays(s[0]);
            if (!day.isBefore(from) && !day.isAfter(to)) {
                f[0] += s[2];
                f[1]++;
                f[2] += s[2] - s[3];
            }
        }
        return f;
    }

    static void figures(JsonNode node, long sales, long count, long profit) {
        assertThat(node.get("sales_minor").asLong()).isEqualTo(sales);
        assertThat(node.get("sale_count").asLong()).isEqualTo(count);
        assertThat(node.get("gross_profit_minor").asLong()).isEqualTo(profit);
        if (count == 0) {
            assertThat(node.has("average_sale_minor")).isFalse();
        } else {
            assertThat(node.get("average_sale_minor").asLong()).isEqualTo(Math.round((double) sales / count));
        }
    }

    @Test
    void todayIsSetAgainstYesterday() {
        JsonNode r = range("period=today", ADMIN).get("range");
        assertThat(r.get("period").asString()).isEqualTo("today");
        assertThat(r.get("from").asString()).isEqualTo(today().toString());
        assertThat(r.get("previous_from").asString())
                .isEqualTo(today().minusDays(1).toString());
        assertThat(r.get("group").asString()).isEqualTo("day");
        figures(r.get("current"), 3_600, 3, 1_200);
        figures(r.get("previous"), 3_000, 1, 1_000);
        assertThat(r.get("series")).hasSize(1);
        assertThat(r.get("series").get(0).get("sales_minor").asLong()).isEqualTo(3_600);
        assertThat(r.get("series").get(0).get("previous_sales_minor").asLong()).isEqualTo(3_000);
        assertThat(r.get("busiest_day").get("sales_minor").asLong()).isEqualTo(3_600);
        assertThat(r.get("busiest_day").get("previous_date").asString())
                .isEqualTo(today().minusDays(1).toString());
        // Head office 2,100 against branch two 1,500; head office sold 3,000 yesterday.
        assertThat(r.get("best_shop").get("id").asString())
                .isEqualTo(t.headOffice().toString());
        assertThat(r.get("best_shop").get("sales_minor").asLong()).isEqualTo(2_100);
        assertThat(r.get("best_shop").get("sale_count").asLong()).isEqualTo(2);
        assertThat(r.get("best_shop").get("previous_sales_minor").asLong()).isEqualTo(3_000);
        // ANA-1 sold 3,000 today in both shops, and 3,000 yesterday.
        assertThat(r.get("best_seller").get("id").asString()).isEqualTo(d.p1.toString());
        assertThat(r.get("best_seller").get("code").asString()).isEqualTo("ANA-1");
        assertThat(r.get("best_seller").get("sales_minor").asLong()).isEqualTo(3_000);
        assertThat(r.get("best_seller").get("previous_sales_minor").asLong()).isEqualTo(3_000);
        assertThat(r.get("top_salesperson").get("sales_minor").asLong()).isEqualTo(3_600);
    }

    @Test
    void sevenDaysHasSevenDailyStepsLinedUpWithTheSevenDaysBefore() {
        JsonNode body = range("period=7d", ADMIN);
        JsonNode r = body.get("range");
        figures(r.get("current"), 6_600, 4, 2_200);
        figures(r.get("previous"), 2_400, 2, 800);
        assertThat(r.get("previous_from").asString())
                .isEqualTo(today().minusDays(13).toString());
        assertThat(r.get("previous_to").asString())
                .isEqualTo(today().minusDays(7).toString());
        JsonNode series = r.get("series");
        assertThat(series).hasSize(7);
        assertThat(series.get(0).get("start").asString())
                .isEqualTo(today().minusDays(6).toString());
        assertThat(series.get(6).get("sales_minor").asLong()).isEqualTo(3_600);
        assertThat(series.get(5).get("sales_minor").asLong()).isEqualTo(3_000);
        assertThat(series.get(6).get("gross_profit_minor").asLong()).isEqualTo(1_200);
        // 8 days ago is the sixth day of the seven before, 10 days ago the fourth.
        assertThat(series.get(5).get("previous_sales_minor").asLong()).isEqualTo(1_500);
        assertThat(series.get(3).get("previous_sales_minor").asLong()).isEqualTo(900);
        assertThat(series.get(0).get("previous_sales_minor").asLong()).isZero();
        assertThat(r.get("busiest_day").get("date").asString()).isEqualTo(today().toString());
        assertThat(r.get("busiest_day").get("previous_date").asString())
                .isEqualTo(today().minusDays(8).toString());
        assertThat(r.get("busiest_day").get("previous_sales_minor").asLong()).isEqualTo(1_500);
        assertThat(r.get("best_shop").get("sales_minor").asLong()).isEqualTo(5_100);
        assertThat(r.get("best_shop").get("previous_sales_minor").asLong()).isEqualTo(2_400);
        // The shops follow the range; the fixed 7 and 30 day figures and the 30 days list are left out.
        assertThat(body.has("days")).isFalse();
        JsonNode hq = shop(body, t.headOffice());
        assertThat(hq.get("range_sales_minor").asLong()).isEqualTo(5_100);
        assertThat(hq.get("previous_sales_minor").asLong()).isEqualTo(2_400);
        assertThat(hq.get("range_gross_profit_minor").asLong()).isEqualTo(1_700);
        assertThat(hq.has("last7_sales_minor")).isFalse();
        JsonNode two = shop(body, t.secondBranch());
        assertThat(two.get("range_sales_minor").asLong()).isEqualTo(1_500);
        assertThat(two.get("previous_sales_minor").asLong()).isZero();
    }

    @Test
    void thirtyDaysAndTheThirtyBefore() {
        JsonNode r = range("period=30d", ADMIN).get("range");
        figures(r.get("current"), 9_000, 6, 3_000);
        figures(r.get("previous"), 1_500, 1, 500);
        assertThat(r.get("series")).hasSize(30);
        assertThat(r.get("best_shop").get("id").asString())
                .isEqualTo(t.headOffice().toString());
        assertThat(r.get("best_shop").get("previous_sales_minor").asLong()).isZero();
    }

    @Test
    void thisYearIsSetAgainstTheSameDaysOfLastYear() {
        JsonNode r = range("period=ytd", ADMIN).get("range");
        LocalDate jan1 = today().withDayOfYear(1);
        assertThat(r.get("from").asString()).isEqualTo(jan1.toString());
        assertThat(r.get("previous_from").asString())
                .isEqualTo(jan1.minusYears(1).toString());
        assertThat(r.get("previous_to").asString())
                .isEqualTo(today().minusYears(1).toString());
        long[] cur = expected(jan1, today());
        long[] prev = expected(jan1.minusYears(1), today().minusYears(1));
        figures(r.get("current"), cur[0], cur[1], cur[2]);
        figures(r.get("previous"), prev[0], prev[1], prev[2]);
        long charted = 0;
        for (JsonNode p : r.get("series")) {
            charted += p.get("sales_minor").asLong();
        }
        assertThat(charted).isEqualTo(cur[0]);
    }

    @Test
    void oneYearInWeeksAgainstTheYearBefore() {
        JsonNode r = range("period=1y", ADMIN).get("range");
        figures(r.get("current"), 10_500, 7, 3_500);
        figures(r.get("previous"), 300, 1, 100);
        assertThat(r.get("group").asString()).isEqualTo("week");
        JsonNode series = r.get("series");
        assertThat(series.get(1).get("start").asString())
                .isEqualTo(LocalDate.parse(r.get("from").asString()).plusDays(7).toString());
        long charted = 0;
        long before = 0;
        for (JsonNode p : series) {
            charted += p.get("sales_minor").asLong();
            before += p.get("previous_sales_minor").asLong();
        }
        assertThat(charted).isEqualTo(10_500);
        assertThat(before).isEqualTo(300);
    }

    @Test
    void allStartsAtTheFirstSaleInMonthsWithNothingBefore() {
        JsonNode r = range("period=all", ADMIN).get("range");
        assertThat(r.get("from").asString()).isEqualTo(today().minusDays(400).toString());
        assertThat(r.get("group").asString()).isEqualTo("month");
        assertThat(r.has("previous")).isFalse();
        assertThat(r.has("previous_from")).isFalse();
        figures(r.get("current"), 10_800, 8, 3_600);
        assertThat(r.get("series").get(0).has("previous_sales_minor")).isFalse();
        assertThat(r.get("best_seller").has("previous_sales_minor")).isFalse();
        // Scoped to branch two, All starts at its own first sale.
        ResponseEntity<JsonNode> two = api.call(
                HttpMethod.GET,
                "/reports/dashboard?period=all",
                null,
                ADMIN,
                t.secondBranch().toString(),
                Map.of());
        assertThat(two.getBody().get("range").get("from").asString())
                .isEqualTo(today().minusDays(35).toString());
    }

    @Test
    void aCustomRangeIsSetAgainstAsManyDaysBefore() {
        String q = "period=custom&from=" + today().minusDays(10) + "&to=" + today().minusDays(8);
        JsonNode r = range(q, ADMIN).get("range");
        figures(r.get("current"), 2_400, 2, 800);
        figures(r.get("previous"), 0, 0, 0);
        assertThat(r.get("previous_from").asString())
                .isEqualTo(today().minusDays(13).toString());
        assertThat(r.get("busiest_day").get("date").asString())
                .isEqualTo(today().minusDays(8).toString());
        assertThat(r.get("busiest_day").has("previous_date")).isFalse();
        // Dates alone are a custom range too.
        JsonNode same = range("from=" + today().minusDays(10) + "&to=" + today().minusDays(8), ADMIN)
                .get("range");
        assertThat(same.get("period").asString()).isEqualTo("custom");
        assertThat(same.get("current").get("sales_minor").asLong()).isEqualTo(2_400);
    }

    @Test
    void aWrongRangeIsRefusedAndNoRangeKeepsTheDashboardAsItWas() {
        for (String q : List.of(
                "period=5y",
                "period=7d&from=" + today(),
                "period=custom&from=" + today(),
                "period=custom&from=" + today().minusDays(400) + "&to=" + today(),
                "period=custom&from=" + today() + "&to=" + today().minusDays(1))) {
            assertThat(api.get("/reports/dashboard?" + q, ADMIN).getStatusCode().value())
                    .as(q)
                    .isEqualTo(422);
        }
        ResponseEntity<JsonNode> plain = api.get("/reports/dashboard", ADMIN);
        assertThat(plain.getBody().has("range")).isFalse();
        assertThat(plain.getBody().get("days")).hasSize(30);
        assertThat(plain.getBody().get("last30_sales_minor").asLong()).isEqualTo(9_000);
        assertThat(shop(plain.getBody(), t.headOffice()).has("range_sales_minor"))
                .isFalse();
        // The range leaves the Today card and the fixed windows as they were.
        JsonNode ranged = range("period=1y", ADMIN);
        assertThat(ranged.get("today").get("sales_minor").asLong()).isEqualTo(3_600);
        assertThat(ranged.get("last7_sales_minor").asLong()).isEqualTo(6_600);
        assertThat(ranged.get("last30_sales_minor").asLong()).isEqualTo(9_000);
    }

    /** ADR-055: without retail.profit.read there is no cost, profit, margin or stock value, and no ranking of colleagues. */
    @Test
    void withoutProfitReadTheRangeHasNoProfitAndNoTopSalesperson() {
        JsonNode body = range("period=30d", SALES + ",retail.sale.all_sellers");
        JsonNode r = body.get("range");
        assertThat(r.get("current").get("sales_minor").asLong()).isEqualTo(9_000);
        assertThat(r.get("best_seller").get("sales_minor").asLong()).isGreaterThan(0);
        assertThat(r.has("top_salesperson")).isFalse();
        assertThat(AnalyticsData.hasKey(body, "cost")).isFalse();
        assertThat(AnalyticsData.hasKey(body, "gross")).isFalse();
        assertThat(AnalyticsData.hasKey(body, "profit_minor")).isFalse();
        assertThat(AnalyticsData.hasKey(body, "margin")).isFalse();
        assertThat(AnalyticsData.hasKey(body, "value_at")).isFalse();
        assertThat(AnalyticsData.hasKey(body, "stock_at")).isFalse();
        // A seller, who does not see every seller's sales, is refused with a range as without one.
        ResponseEntity<JsonNode> seller = api.get("/reports/dashboard?period=30d", SALES);
        assertThat(seller.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(AnalyticsData.figures(seller.getBody())).doesNotContain("9000", "3000");
    }

    @Test
    void theBranchScopeHoldsForEveryRangeFigure() {
        ResponseEntity<JsonNode> scoped = api.call(
                HttpMethod.GET,
                "/reports/dashboard?period=7d",
                null,
                ADMIN,
                t.secondBranch().toString(),
                Map.of());
        JsonNode r = scoped.getBody().get("range");
        figures(r.get("current"), 1_500, 1, 500);
        figures(r.get("previous"), 0, 0, 0);
        assertThat(r.get("best_shop").get("id").asString())
                .isEqualTo(t.secondBranch().toString());
        assertThat(r.get("best_seller").get("sales_minor").asLong()).isEqualTo(1_500);
        assertThat(scoped.getBody().get("shops")).hasSize(1);
        assertThat(scoped.getBody().toString()).doesNotContain(t.headOffice().toString());
    }

    /** A tenant that has sold nothing: every range answers with zeros, none of them is a 500 (min() over no sale is a NULL row). */
    @Test
    void aScopeWithNoCompletedSaleAnswersEveryRangeWithZeros() {
        TestDatabase.Fixture empty = TestDatabase.tenant("retail-dsr-none", false, true);
        RetailTestSupport none = new RetailTestSupport(http, empty);
        String first = empty.headOffice().toString();
        for (String q : List.of(
                "period=all",
                "period=all&branch_id=" + first,
                "period=all&branch_id=" + empty.secondBranch(),
                "period=7d",
                "period=today",
                "period=ytd",
                "period=1y",
                "period=custom&from=" + today().minusDays(20) + "&to=" + today().minusDays(10))) {
            ResponseEntity<JsonNode> r = none.get("/reports/dashboard?" + q, ADMIN);
            assertThat(r.getStatusCode()).as("%s: %s", q, r.getBody()).isEqualTo(HttpStatus.OK);
            JsonNode range = r.getBody().get("range");
            figures(range.get("current"), 0, 0, 0);
            assertThat(range.has("busiest_day")).as(q).isFalse();
            assertThat(range.has("best_shop")).as(q).isFalse();
            assertThat(range.has("best_seller")).as(q).isFalse();
            assertThat(range.has("top_salesperson")).as(q).isFalse();
        }
        JsonNode all =
                none.get("/reports/dashboard?period=all", ADMIN).getBody().get("range");
        assertThat(all.get("from").asString()).isEqualTo(today().toString());
        assertThat(all.has("previous")).isFalse();
    }

    /** A branch with no sale in a tenant that has sales elsewhere: All for it is the single day today, not an error. */
    @Test
    void allForABranchWithNoSaleIsNotAnError() {
        TestDatabase.Fixture one = TestDatabase.tenant("retail-dsr-one", false, true);
        RetailTestSupport mine = new RetailTestSupport(http, one);
        AnalyticsData data = new AnalyticsData(one, mine);
        data.products();
        data.stock();
        data.sell(one.headOffice(), "cash", data.p1, "1", 2);
        ResponseEntity<JsonNode> r = mine.call(
                HttpMethod.GET,
                "/reports/dashboard?period=all",
                null,
                ADMIN,
                one.secondBranch().toString(),
                Map.of());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        figures(r.getBody().get("range").get("current"), 0, 0, 0);
    }

    /** A range far in the past is read for its own days and the days before it; today's card and the fixed windows are unchanged. */
    @Test
    void anOldCustomRangeReadsItsOwnDaysAndKeepsTodayAsItWas() {
        JsonNode body = range("period=custom&from=" + today().minusDays(410) + "&to=" + today().minusDays(390), ADMIN);
        figures(body.get("range").get("current"), 300, 1, 100);
        figures(body.get("range").get("previous"), 0, 0, 0);
        assertThat(body.get("today").get("sales_minor").asLong()).isEqualTo(3_600);
        assertThat(body.get("last30_sales_minor").asLong()).isEqualTo(9_000);
        // The shop of today is still listed beside the shop of the range.
        assertThat(shop(body, t.headOffice()).get("today_sales_minor").asLong()).isEqualTo(2_100);
        assertThat(shop(body, t.headOffice()).get("range_sales_minor").asLong()).isEqualTo(300);
    }

    @Test
    void aRangeEndingAfterTodayIsRefused() {
        for (String q : List.of(
                "period=custom&from=" + today().plusDays(30) + "&to=" + today().plusDays(60),
                "period=custom&from=" + today().minusDays(2) + "&to=" + today().plusDays(1))) {
            ResponseEntity<JsonNode> r = api.get("/reports/dashboard?" + q, ADMIN);
            assertThat(r.getStatusCode().value()).as("%s: %s", q, r.getBody()).isEqualTo(422);
        }
    }

    /** A Storekeeper reads stock, not sales: the dashboard is refused with a range as without one. */
    @Test
    void aStorekeeperIsRefusedWithARangeToo() {
        String storekeeper = "retail.stock.read,retail.stock.transfer,retail.stocktake.commit,retail.usage.report";
        assertThat(api.get("/reports/dashboard", storekeeper).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        for (String q :
                List.of("period=7d", "period=all", "period=custom&from=" + today().minusDays(3) + "&to=" + today())) {
            ResponseEntity<JsonNode> r = api.get("/reports/dashboard?" + q, storekeeper);
            assertThat(r.getStatusCode()).as("%s: %s", q, r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    JsonNode shop(JsonNode report, UUID id) {
        for (JsonNode s : report.get("shops")) {
            if (s.get("branch_id").asString().equals(id.toString())) {
                return s;
            }
        }
        throw new AssertionError("no shop " + id);
    }
}
