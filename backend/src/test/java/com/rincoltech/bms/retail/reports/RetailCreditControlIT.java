package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.LocalDate;
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
 * The credit control report (issue #149, step 4). One cable costs 1,000 and sells at 1,500. Credit buyer Zed
 * owes 2,000 on a sale 95 days past due (3,000 less 1,000 paid) and 1,500 on one 35 days past due. Test Buyer 01
 * owes 4,500 five days past due at head office, 1,500 not yet due at branch two and 1,500 with no due date.
 * Test Buyer 03 paid in full. A cash sale is not credit. Owed in all: 11,000.
 */
class RetailCreditControlIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID cable;
    UUID zed;
    UUID saleA;
    UUID saleB;
    UUID saleC;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-crd", false, true);
        api = new RetailTestSupport(http, t);
        cable = api.product("CRD-1", 1_000, 1_500);
        api.stockUp(t.headOffice(), cable, "100");
        api.stockUp(t.secondBranch(), cable, "50");
        zed = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Customer Zed"), ADMIN));
        saleA = credit(t.headOffice(), "2", 100, -95, zed, null);
        saleB = credit(t.headOffice(), "1", 40, -35, zed, null);
        saleC = credit(t.headOffice(), "3", 10, -5, null, "Test Buyer 01");
        credit(t.secondBranch(), "1", 5, 10, null, "Test Buyer 01");
        credit(t.headOffice(), "1", 3, null, null, "test buyer 01");
        UUID paid = credit(t.headOffice(), "1", 2, 20, null, "Test Buyer 03");
        pay(saleA, 1_000, "cash");
        pay(paid, 1_500, "mobile_money");
        api.sell(t.headOffice(), "cash", cable, "1");
    }

    /** A credit sale {@code age} days old, due {@code dueIn} days from today (negative: past), or with no due date. */
    UUID credit(UUID branch, String qty, int age, Integer dueIn, UUID customer, String buyer) {
        LocalDate today = AnalyticsData.today();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("sale_date", today.minusDays(age).toString());
        body.put("payment_method", "credit");
        if (dueIn != null) {
            body.put("due_date", today.plusDays(dueIn).toString());
        }
        if (customer != null) {
            body.put("customer_id", customer);
        } else {
            body.put("buyer_name", buyer);
        }
        body.put("lines", List.of(Map.of("product_id", cable, "qty", qty)));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    void pay(UUID sale, long amount, String method) {
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + sale + "/payments",
                Map.of("amount_minor", amount, "method", method),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    JsonNode report(String query) {
        ResponseEntity<JsonNode> r = api.get("/reports/credit-control" + query, SALES);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void outstandingByBuyerWithAgeingBuckets() {
        JsonNode r = report("");
        JsonNode totals = r.get("totals");
        assertThat(totals.get("owed_minor").asLong()).isEqualTo(11_000);
        assertThat(totals.get("over90_minor").asLong()).isEqualTo(2_000);
        assertThat(totals.get("days61_to90_minor").asLong()).isZero();
        assertThat(totals.get("days31_to60_minor").asLong()).isEqualTo(1_500);
        assertThat(totals.get("days1_to30_minor").asLong()).isEqualTo(4_500);
        assertThat(totals.get("not_due_minor").asLong()).isEqualTo(3_000);
        assertThat(r.get("buyer_count").asInt()).isEqualTo(2);
        JsonNode buyers = r.get("buyers");
        // The name typed in with the sale is one buyer whatever its case.
        assertThat(buyers.get(0).get("name").asString()).isEqualTo("Test Buyer 01");
        assertThat(buyers.get(0).get("sale_count").asInt()).isEqualTo(3);
        assertThat(buyers.get(0).get("ageing").get("owed_minor").asLong()).isEqualTo(7_500);
        assertThat(buyers.get(0).get("oldest_overdue_days").asInt()).isEqualTo(5);
        assertThat(buyers.get(0).has("customer_id")).isFalse();
        assertThat(buyers.get(1).get("name").asString()).isEqualTo("Test Customer Zed");
        assertThat(buyers.get(1).get("customer_id").asString()).isEqualTo(zed.toString());
        assertThat(buyers.get(1).get("ageing").get("over90_minor").asLong()).isEqualTo(2_000);
        assertThat(buyers.get(1).get("oldest_overdue_days").asInt()).isEqualTo(95);
        assertThat(report("?top=1").get("buyers")).hasSize(1);
        assertThat(report("?top=1").get("totals").get("owed_minor").asLong()).isEqualTo(11_000);
    }

    @Test
    void theOverdueListSortedByAmountOrByAge() {
        JsonNode byAmount = report("");
        assertThat(byAmount.get("overdue_count").asInt()).isEqualTo(3);
        List<String> ids = new java.util.ArrayList<>();
        byAmount.get("overdue").forEach(o -> ids.add(o.get("sale_id").asString()));
        assertThat(ids).containsExactly(saleC.toString(), saleA.toString(), saleB.toString());
        JsonNode a = byAmount.get("overdue").get(1);
        assertThat(a.get("days_overdue").asInt()).isEqualTo(95);
        assertThat(a.get("outstanding_minor").asLong()).isEqualTo(2_000);
        assertThat(a.get("total_minor").asLong()).isEqualTo(3_000);
        List<String> byAge = new java.util.ArrayList<>();
        report("?sort=age")
                .get("overdue")
                .forEach(o -> byAge.add(o.get("sale_id").asString()));
        assertThat(byAge).containsExactly(saleA.toString(), saleB.toString(), saleC.toString());
        assertThat(report("?top=2").get("overdue")).hasSize(2);
        assertThat(report("?top=2").get("overdue_count").asInt()).isEqualTo(3);
    }

    @Test
    void agreesWithTheOwingFiltersOfTheSalesList() {
        JsonNode owing = api.get("/sales?owing=owing&limit=100", SALES).getBody();
        long owed = 0;
        for (JsonNode s : owing.get("items")) {
            owed += s.get("balance_minor").asLong();
        }
        assertThat(owed).isEqualTo(report("").get("totals").get("owed_minor").asLong());
        assertThat(api.get("/sales?owing=overdue&limit=100", SALES).getBody().get("items"))
                .hasSize(report("").get("overdue_count").asInt());
    }

    @Test
    void paymentsReceivedByDayAndMethodInARange() {
        JsonNode r = report("");
        String today = AnalyticsData.today().toString();
        assertThat(r.get("payments_minor").asLong()).isEqualTo(2_500);
        Map<String, JsonNode> byMethod = new java.util.HashMap<>();
        r.get("payments_by_method").forEach(m -> byMethod.put(m.get("method").asString(), m));
        assertThat(byMethod.get("cash").get("amount_minor").asLong()).isEqualTo(1_000);
        assertThat(byMethod.get("mobile_money").get("amount_minor").asLong()).isEqualTo(1_500);
        assertThat(r.get("payments")).hasSize(2);
        assertThat(r.get("payments").get(0).get("date").asString()).isEqualTo(today);
        // A range that ends yesterday holds none.
        String yesterday = AnalyticsData.today().minusDays(1).toString();
        JsonNode earlier = report("?from=" + AnalyticsData.today().minusDays(10) + "&to=" + yesterday);
        assertThat(earlier.get("payments")).isEmpty();
        assertThat(earlier.get("payments_minor").asLong()).isZero();
        // The ageing is as of today whatever the range.
        assertThat(earlier.get("totals").get("owed_minor").asLong()).isEqualTo(11_000);
    }

    @Test
    void branchScopeNarrowsEverySection() {
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.GET,
                "/reports/credit-control",
                null,
                SALES,
                t.headOffice().toString(),
                Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Head office only: the 1,500 owed at branch two is out.
        assertThat(r.getBody().get("totals").get("owed_minor").asLong()).isEqualTo(9_500);
        ResponseEntity<JsonNode> other = api.call(
                HttpMethod.GET,
                "/reports/credit-control",
                null,
                SALES,
                t.secondBranch().toString(),
                Map.of());
        assertThat(other.getBody().get("totals").get("owed_minor").asLong()).isEqualTo(1_500);
        assertThat(other.getBody().get("payments")).isEmpty();
        assertThat(other.getBody().get("overdue")).isEmpty();
    }

    @Test
    void permissionsInputsAndNoProfitAnywhere() {
        long audit = AnalyticsData.auditRows();
        JsonNode r = report("");
        assertThat(AnalyticsData.hasKey(r, "cost")).isFalse();
        assertThat(AnalyticsData.hasKey(r, "profit")).isFalse();
        assertThat(AnalyticsData.hasKey(r, "margin")).isFalse();
        ResponseEntity<JsonNode> denied = api.get("/reports/credit-control", "retail.stock.read");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(denied.getBody().toString()).doesNotContain("11000", "Test Buyer");
        assertThat(AnalyticsData.auditRows()).isEqualTo(audit);
        assertThat(api.get("/reports/credit-control?sort=name", SALES).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get(
                                "/reports/credit-control?from="
                                        + AnalyticsData.today().minusDays(366) + "&to=" + AnalyticsData.today(),
                                SALES)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
