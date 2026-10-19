package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.LinkedHashMap;
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
 * The sales list grouped by year, month and day (issue #328): totals, the payment-method breakdown, voided sales kept
 * out, profit only for a holder of {@code retail.profit.read}, branch scope, the period bounds and tenant isolation.
 * Names and amounts are fabricated; one unit costs 1,500 and sells for 2,000, so every unit sold earns 500.
 */
class RetailSalesSummaryIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-sum", false, true);
        api = new RetailTestSupport(http, t);
        product = api.product("SUM-1", 1_500, 2_000);
        api.stockUp(t.headOffice(), product, "200");
        api.stockUp(t.secondBranch(), product, "200");
    }

    ResponseEntity<JsonNode> sale(RetailTestSupport as, UUID branch, String method, String date, String qty, UUID p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", method);
        body.put("sale_date", date);
        if (method.equals("credit")) {
            body.put("buyer_name", "Test Buyer 01");
        }
        body.put("lines", List.of(Map.of("product_id", p, "qty", qty)));
        ResponseEntity<JsonNode> r =
                as.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r;
    }

    /** Six completed sales over two years and two branches, and one voided sale on the busiest day. */
    void arrange() {
        sale(api, t.headOffice(), "cash", "2026-10-19", "1", product);
        sale(api, t.headOffice(), "mobile_money", "2026-10-19", "2", product);
        sale(api, t.headOffice(), "credit", "2026-10-18", "1", product);
        sale(api, t.headOffice(), "bank", "2026-09-30", "1", product);
        sale(api, t.headOffice(), "cash", "2025-12-31", "3", product);
        sale(api, t.secondBranch(), "cash", "2026-10-19", "1", product);
        UUID voided = RetailTestSupport.id(sale(api, t.headOffice(), "cash", "2026-10-19", "5", product));
        ResponseEntity<JsonNode> v = api.post("/sales/" + voided + "/void", Map.of("reason", "Test wrong item"), ADMIN);
        assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<JsonNode> summary(RetailTestSupport as, String query, String permissions, String branches) {
        return as.call(HttpMethod.GET, "/sales/summary?" + query, null, permissions, branches, Map.of());
    }

    JsonNode items(String query, String permissions) {
        ResponseEntity<JsonNode> r = summary(api, query, permissions, "*");
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody().get("items");
    }

    static long total(JsonNode group, String method) {
        return group.get("by_method").get(method).get("total_minor").asLong();
    }

    static long count(JsonNode group, String method) {
        return group.get("by_method").get(method).get("count").asLong();
    }

    @Test
    void yearsCarryTheTotalsProfitAndPaymentMethodBreakdownNewestFirst() {
        arrange();
        JsonNode years = items("group_by=year", ADMIN);
        assertThat(years).hasSize(2);
        JsonNode y26 = years.get(0);
        assertThat(y26.get("period").asString()).isEqualTo("2026");
        assertThat(y26.get("from").asString()).isEqualTo("2026-01-01");
        assertThat(y26.get("to").asString()).isEqualTo("2026-12-31");
        assertThat(y26.get("sales_count").asLong()).isEqualTo(5);
        assertThat(y26.get("total_minor").asLong()).isEqualTo(12_000);
        assertThat(y26.get("profit_minor").asLong()).isEqualTo(3_000);
        assertThat(y26.get("voided_count").asLong()).isEqualTo(1);
        assertThat(count(y26, "cash")).isEqualTo(2);
        assertThat(total(y26, "cash")).isEqualTo(4_000);
        assertThat(count(y26, "mobile_money")).isEqualTo(1);
        assertThat(total(y26, "mobile_money")).isEqualTo(4_000);
        assertThat(total(y26, "bank")).isEqualTo(2_000);
        assertThat(total(y26, "credit")).isEqualTo(2_000);
        JsonNode y25 = years.get(1);
        assertThat(y25.get("period").asString()).isEqualTo("2025");
        assertThat(y25.get("sales_count").asLong()).isEqualTo(1);
        assertThat(y25.get("total_minor").asLong()).isEqualTo(6_000);
        assertThat(y25.get("profit_minor").asLong()).isEqualTo(1_500);
        assertThat(y25.get("voided_count").asLong()).isZero();
        assertThat(count(y25, "credit")).isZero();
    }

    @Test
    void monthsAndDaysAgreeWithTheYearAboveThem() {
        arrange();
        JsonNode months = items("group_by=month&from=2026-01-01&to=2026-12-31", ADMIN);
        assertThat(months).hasSize(2);
        assertThat(months.get(0).get("period").asString()).isEqualTo("2026-10");
        assertThat(months.get(0).get("total_minor").asLong()).isEqualTo(10_000);
        assertThat(months.get(1).get("period").asString()).isEqualTo("2026-09");
        assertThat(months.get(1).get("to").asString()).isEqualTo("2026-09-30");
        assertThat(months.get(0).get("total_minor").asLong()
                        + months.get(1).get("total_minor").asLong())
                .isEqualTo(12_000);

        JsonNode days = items("group_by=day&from=2026-10-01&to=2026-10-31", ADMIN);
        assertThat(days).hasSize(2);
        JsonNode nineteenth = days.get(0);
        assertThat(nineteenth.get("period").asString()).isEqualTo("2026-10-19");
        assertThat(nineteenth.get("from").asString())
                .isEqualTo(nineteenth.get("to").asString());
        assertThat(nineteenth.get("sales_count").asLong()).isEqualTo(3);
        assertThat(nineteenth.get("total_minor").asLong()).isEqualTo(8_000);
        assertThat(nineteenth.get("profit_minor").asLong()).isEqualTo(2_000);
        assertThat(days.get(1).get("period").asString()).isEqualTo("2026-10-18");
        assertThat(total(days.get(1), "credit")).isEqualTo(2_000);
    }

    @Test
    void aVoidedSaleIsCountedApartAndLeavesEveryOtherFigureAlone() {
        arrange();
        JsonNode day =
                items("group_by=day&from=2026-10-19&to=2026-10-19", ADMIN).get(0);
        assertThat(day.get("voided_count").asLong()).isEqualTo(1);
        assertThat(day.get("sales_count").asLong()).isEqualTo(3);
        assertThat(day.get("total_minor").asLong()).isEqualTo(8_000);
        assertThat(total(day, "cash"))
                .as("the voided five units are not in cash")
                .isEqualTo(4_000);
    }

    @Test
    void aCallerWithoutProfitReadGetsNoProfitField() {
        arrange();
        for (String level : List.of("year", "month", "day")) {
            JsonNode groups = items("group_by=" + level, SALES);
            assertThat(groups).isNotEmpty();
            for (JsonNode g : groups) {
                assertThat(g.has("profit_minor")).as("%s %s", level, g).isFalse();
                assertThat(g.get("total_minor").asLong()).isPositive();
            }
        }
        assertThat(items("group_by=year", SALES).get(0).get("total_minor").asLong())
                .isEqualTo(12_000);
    }

    @Test
    void theBranchScopeAndTheBranchFilterNarrowTheFigures() {
        arrange();
        ResponseEntity<JsonNode> second =
                summary(api, "group_by=year", ADMIN, t.secondBranch().toString());
        JsonNode only = second.getBody().get("items");
        assertThat(only).hasSize(1);
        assertThat(only.get(0).get("sales_count").asLong()).isEqualTo(1);
        assertThat(only.get(0).get("total_minor").asLong()).isEqualTo(2_000);
        assertThat(only.get(0).get("profit_minor").asLong()).isEqualTo(500);

        ResponseEntity<JsonNode> outside = summary(
                api,
                "group_by=year&branch_id=" + t.headOffice(),
                ADMIN,
                t.secondBranch().toString());
        assertThat(outside.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(outside.getBody().get("items")).isEmpty();

        JsonNode hq = items("group_by=year&branch_id=" + t.headOffice(), ADMIN).get(0);
        assertThat(hq.get("sales_count").asLong()).isEqualTo(4);
        assertThat(hq.get("total_minor").asLong()).isEqualTo(10_000);
    }

    @Test
    void thePaymentMethodFilterOfTheListAppliesToTheGroups() {
        arrange();
        JsonNode credit = items("group_by=year&payment_method=credit", ADMIN);
        assertThat(credit).hasSize(1);
        assertThat(credit.get(0).get("sales_count").asLong()).isEqualTo(1);
        assertThat(credit.get(0).get("total_minor").asLong()).isEqualTo(2_000);
        assertThat(count(credit.get(0), "cash")).isZero();
    }

    @Test
    void aPeriodIsBoundedAndTheRestIsReachedByNextTo() {
        UUID p = api.product("SUM-2", 100, 200);
        api.stockUp(t.headOffice(), p, "50");
        for (int m = 10; m <= 12; m++) {
            sale(api, t.headOffice(), "cash", "2025-" + m + "-15", "1", p);
        }
        for (int m = 1; m <= 10; m++) {
            sale(api, t.headOffice(), "cash", String.format("2026-%02d-15", m), "1", p);
        }
        ResponseEntity<JsonNode> page = summary(api, "group_by=month", ADMIN, "*");
        assertThat(page.getStatusCode()).as("%s", page.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody().get("items")).hasSize(12);
        assertThat(page.getBody().get("has_more").asBoolean()).isTrue();
        assertThat(page.getBody().get("items").get(0).get("period").asString()).isEqualTo("2026-10");
        assertThat(page.getBody().get("next_to").asString()).isEqualTo("2025-10-31");

        JsonNode rest = summary(api, "group_by=month&to=2025-10-31", ADMIN, "*").getBody();
        assertThat(rest.get("items")).hasSize(1);
        assertThat(rest.get("items").get(0).get("period").asString()).isEqualTo("2025-10");
        assertThat(rest.get("has_more").asBoolean()).isFalse();
        assertThat(rest.has("next_to")).isFalse();
    }

    @Test
    void anotherTenantsSalesNeverReachTheFigures() {
        arrange();
        TestDatabase.Fixture other = TestDatabase.tenant("retail-sum-other", false, true);
        RetailTestSupport otherApi = new RetailTestSupport(http, other);
        UUID theirs = otherApi.product("SUM-9", 1_500, 2_000);
        otherApi.stockUp(other.headOffice(), theirs, "50");
        sale(otherApi, other.headOffice(), "cash", "2026-10-19", "9", theirs);

        JsonNode mine = items("group_by=year", ADMIN);
        assertThat(mine.get(0).get("total_minor").asLong()).isEqualTo(12_000);
        JsonNode theirsOnly =
                summary(otherApi, "group_by=year", ADMIN, "*").getBody().get("items");
        assertThat(theirsOnly).hasSize(1);
        assertThat(theirsOnly.get(0).get("total_minor").asLong()).isEqualTo(18_000);
        assertThat(theirsOnly.get(0).get("sales_count").asLong()).isEqualTo(1);
    }

    @Test
    void anUnknownLevelAndAnUpsideDownRangeAreRefused() {
        assertThat(summary(api, "group_by=week", ADMIN, "*").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(summary(api, "group_by=day&from=2026-10-19&to=2026-10-01", ADMIN, "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(summary(api, "", ADMIN, "*").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        for (String bad : List.of("owing=sometimes", "payment_method=cheque")) {
            ResponseEntity<JsonNode> r = summary(api, "group_by=year&" + bad, ADMIN, "*");
            assertThat(r.getStatusCode()).as(bad).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(api.get("/sales?" + bad, ADMIN).getStatusCode())
                    .as("the list refuses it the same way")
                    .isEqualTo(r.getStatusCode());
        }
    }

    // JUSTIFICATION-A3: the review asked for tests of the profit scope, the shared filters and the bounds; each is one
    // test method.
    /** The profit of a sum is shown only when it is readable in every branch the sum covers (ADR-017). */
    @Test
    void profitNeedsProfitReadInEveryBranchTheFiguresCover() {
        arrange();
        Map<String, String> scoped = Map.of("X-Dev-Scopes", "retail.profit.read=" + t.headOffice());
        java.util.function.Function<String, JsonNode> year = query -> {
            ResponseEntity<JsonNode> r =
                    api.call(HttpMethod.GET, "/sales/summary?group_by=year" + query, null, ADMIN, "*", scoped);
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
            return r.getBody().get("items").get(0);
        };
        JsonNode everywhere = year.apply("");
        assertThat(everywhere.get("total_minor").asLong()).isEqualTo(12_000);
        assertThat(everywhere.has("profit_minor"))
                .as("profit is readable at one branch only, the figures cover all")
                .isFalse();
        assertThat(year.apply("&branch_id=" + t.headOffice() + "&branch_id=" + t.secondBranch())
                        .has("profit_minor"))
                .isFalse();
        assertThat(year.apply("&branch_id=" + t.secondBranch()).has("profit_minor"))
                .isFalse();
        JsonNode headOffice = year.apply("&branch_id=" + t.headOffice());
        assertThat(headOffice.get("profit_minor").asLong()).isEqualTo(2_500);
        assertThat(headOffice.get("total_minor").asLong()).isEqualTo(10_000);
    }

    /** The summary and the list share their filters: for each one, the sums equal the completed rows of the list. */
    @Test
    void everyFilterGivesTheSameSalesAsTheListDoes() {
        arrange();
        UUID other = api.product("SUM-3", 1_500, 2_000);
        api.stockUp(t.headOffice(), other, "50");
        sale(api, t.headOffice(), "cash", "2026-10-17", "2", other);
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Credit Buyer 03"), ADMIN));
        creditSale("2026-10-16", null, buyer, "2026-10-17");
        UUID paid = RetailTestSupport.id(creditSale("2026-10-15", "Test Debtor Zed", null, null));
        ResponseEntity<JsonNode> payment = api.postKeyed(
                "/sales/" + paid + "/payments",
                Map.of("amount_minor", 2_000, "method", "cash"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(payment.getStatusCode()).as("%s", payment.getBody()).isEqualTo(HttpStatus.CREATED);

        agree("", 9);
        agree("buyer=Zed", 1);
        agree("product_id=" + other, 1);
        agree("customer_id=" + buyer, 1);
        agree("owing=owing", 2);
        agree("owing=overdue", 1);
        agree("owing=paid", 1);
        agree("payment_method=credit", 3);
        agree("payment_method=cash&branch_id=" + t.secondBranch(), 1);
        agree("buyer=Test+Buyer&owing=owing&payment_method=credit&from=2026-10-17&to=2026-10-31", 1);
    }

    ResponseEntity<JsonNode> creditSale(String date, String buyerName, UUID customerId, String due) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "credit");
        body.put("sale_date", date);
        if (buyerName != null) {
            body.put("buyer_name", buyerName);
        }
        if (customerId != null) {
            body.put("customer_id", customerId);
        }
        if (due != null) {
            body.put("due_date", due);
        }
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r;
    }

    /** Compares the year groups with the completed rows of the list under one query, and with the count expected. */
    void agree(String query, long expected) {
        String more = query.isEmpty() ? "" : "&" + query;
        long groupCount = 0;
        long groupTotal = 0;
        for (JsonNode g : items("group_by=year" + more, ADMIN)) {
            groupCount += g.get("sales_count").asLong();
            groupTotal += g.get("total_minor").asLong();
        }
        ResponseEntity<JsonNode> list = api.get("/sales?status=completed&limit=200" + more, ADMIN);
        assertThat(list.getStatusCode()).as("%s", list.getBody()).isEqualTo(HttpStatus.OK);
        long rows = 0;
        long total = 0;
        for (JsonNode s : list.getBody().get("items")) {
            rows++;
            total += s.get("total_minor").asLong();
        }
        assertThat(rows).as("rows of the list under '%s'", query).isEqualTo(expected);
        assertThat(groupCount).as("count under '%s'", query).isEqualTo(rows);
        assertThat(groupTotal).as("total under '%s'", query).isEqualTo(total);
    }

    /** Years 20, months 12 (above), days 31: the newest come first and the rest is reached by next_to. */
    @Test
    void daysAndYearsAreBoundedToo() {
        UUID cheap = api.product("SUM-4", 100, 200);
        api.stockUp(t.headOffice(), cheap, "100");
        for (int i = 0; i < 32; i++) {
            sale(
                    api,
                    t.headOffice(),
                    "cash",
                    java.time.LocalDate.parse("2026-09-01").plusDays(i).toString(),
                    "1",
                    cheap);
        }
        for (int y = 2006; y <= 2026; y++) {
            sale(api, t.headOffice(), "cash", y + "-06-15", "1", cheap);
        }
        JsonNode days = summary(api, "group_by=day&from=2026-09-01", ADMIN, "*").getBody();
        assertThat(days.get("items")).hasSize(31);
        assertThat(days.get("items").get(0).get("period").asString()).isEqualTo("2026-10-02");
        assertThat(days.get("has_more").asBoolean()).isTrue();
        assertThat(days.get("next_to").asString()).isEqualTo("2026-09-01");
        JsonNode rest = summary(api, "group_by=day&from=2026-09-01&to=2026-09-01", ADMIN, "*")
                .getBody();
        assertThat(rest.get("items")).hasSize(1);
        assertThat(rest.get("has_more").asBoolean()).isFalse();

        JsonNode years = summary(api, "group_by=year", ADMIN, "*").getBody();
        assertThat(years.get("items")).hasSize(20);
        assertThat(years.get("items").get(0).get("period").asString()).isEqualTo("2026");
        assertThat(years.get("has_more").asBoolean()).isTrue();
        assertThat(years.get("next_to").asString()).isEqualTo("2006-12-31");
        JsonNode oldest =
                summary(api, "group_by=year&to=2006-12-31", ADMIN, "*").getBody();
        assertThat(oldest.get("items")).hasSize(1);
        assertThat(oldest.get("items").get(0).get("period").asString()).isEqualTo("2006");
    }
}
