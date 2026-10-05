package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

/**
 * Valuation and daily profit (#54; FR-RET-09, FR-RET-10; ADR-020 decisions 6, 8 and 10). The
 * figures are fabricated and worked by hand in the comments.
 */
class RetailReportsIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID a;
    UUID b;

    /**
     * Product A costs 1,000 and sells at 1,500; B costs 200 and sells at 300. A restock puts 10 A at
     * head office and 5 at branch two, and 3 B at head office. Head office sells 2 A and 3 B (B goes
     * to 0), uses 1 A, and sells and voids 1 A.
     */
    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-rep", false, true);
        api = new RetailTestSupport(http, t);
        a = api.product("RPT-A", 1_000, 1_500);
        b = api.product("RPT-B", 200, 300);
        Map<String, Object> line = Map.of(
                "product_id",
                a,
                "cost_minor",
                1_000,
                "qty_by_branch",
                List.of(
                        Map.of("branch_id", t.headOffice(), "qty", "10"),
                        Map.of("branch_id", t.secondBranch(), "qty", "5")));
        Map<String, Object> lineB = Map.of(
                "product_id",
                b,
                "cost_minor",
                200,
                "qty_by_branch",
                List.of(Map.of("branch_id", t.headOffice(), "qty", "3")));
        String today = LocalDate.now(ZoneId.of("Africa/Kampala")).toString();
        assertThat(api.postKeyed(
                                "/purchases",
                                Map.of("purchased_on", today, "payment_method", "cash", "lines", List.of(line, lineB)),
                                ADMIN,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        api.sell(t.headOffice(), "cash", a, "2");
        api.sell(t.headOffice(), "cash", b, "3");
        api.postKeyed(
                "/usage",
                Map.of(
                        "branch_id",
                        t.headOffice(),
                        "kind",
                        "used",
                        "reason",
                        "Test display",
                        "lines",
                        List.of(Map.of("product_id", a, "qty", "1"))),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        UUID voided = RetailTestSupport.id(api.sell(t.headOffice(), "cash", a, "1"));
        api.post("/sales/" + voided + "/void", Map.of("reason", "Test void"), ADMIN);
    }

    /**
     * FR-RET-09: head office A 7 (value 7,000, expected 10,500), B 0 (no row); branch two A 5 (5,000,
     * 7,500); totals 12,000 at cost and 18,000 expected. The inventory account equals the valuation
     * until a price changes; then the difference is reported (ADR-020 decision 8). A negative
     * balance, which only imported history can leave (ADR-020 decision 4), is flagged.
     */
    @Test
    void valuationAtCostAndAtPriceWithTotals() {
        JsonNode v = api.get("/reports/valuation", ADMIN).getBody();
        Map<String, JsonNode> rows = new HashMap<>();
        v.get("rows")
                .forEach(r -> rows.put(
                        r.get("branch_id").asString() + "/" + r.get("code").asString(), r));
        JsonNode hqA = rows.get(t.headOffice() + "/RPT-A");
        assertThat(hqA.get("qty").asString()).isEqualTo("7.000");
        assertThat(hqA.get("value_at_cost_minor").asLong()).isEqualTo(7_000);
        assertThat(hqA.get("expected_sales_minor").asLong()).isEqualTo(10_500);
        assertThat(rows).as("a zero balance has no row").doesNotContainKey(t.headOffice() + "/RPT-B");
        assertThat(rows.get(t.secondBranch() + "/RPT-A")
                        .get("value_at_cost_minor")
                        .asLong())
                .isEqualTo(5_000);
        assertThat(v.get("value_at_cost_minor").asLong()).isEqualTo(12_000);
        assertThat(v.get("expected_sales_minor").asLong()).isEqualTo(18_000);
        for (JsonNode branch : v.get("branches")) {
            assertThat(branch.get("revaluation_difference_minor").asLong()).isZero();
        }

        api.post("/products/" + a + "/prices", Map.of("cost_minor", 1_100, "reason", "Test revalue"), ADMIN);
        JsonNode after = api.get("/reports/valuation", ADMIN).getBody();
        Map<String, Long> difference = new HashMap<>();
        after.get("branches")
                .forEach(br -> difference.put(
                        br.get("branch_id").asString(),
                        br.get("revaluation_difference_minor").asLong()));
        assertThat(difference)
                .containsEntry(t.headOffice().toString(), 700L)
                .containsEntry(t.secondBranch().toString(), 500L);

        String yesterday =
                LocalDate.now(ZoneId.of("Africa/Kampala")).minusDays(1).toString();
        assertThat(api.get("/reports/valuation?as_of=" + yesterday, ADMIN)
                        .getBody()
                        .get("rows"))
                .isEmpty();

        api.importedBalance(t.headOffice(), b, "-3");
        JsonNode imported = null;
        for (JsonNode r : api.get("/reports/valuation", ADMIN).getBody().get("rows")) {
            if (r.get("branch_id").asString().equals(t.headOffice().toString())
                    && r.get("code").asString().equals("RPT-B")) {
                imported = r;
            }
        }
        assertThat(imported.get("negative").asBoolean()).isTrue();
        assertThat(imported.get("value_at_cost_minor").asLong()).isEqualTo(-600);
    }

    /**
     * FR-RET-10: today at head office sales 3,900 (2 A at 1,500 and 3 B at 300) less cost 2,600
     * less usage 1,000 is 300; the voided sale counts for nothing; branch two has no row.
     */
    @Test
    void dailyProfitPerBranchAndDay() {
        JsonNode p = api.get("/reports/profit/daily", ADMIN).getBody();
        assertThat(p.get("rows")).hasSize(1);
        JsonNode row = p.get("rows").get(0);
        assertThat(row.get("branch_id").asString()).isEqualTo(t.headOffice().toString());
        assertThat(row.get("sales_minor").asLong()).isEqualTo(3_900);
        assertThat(row.get("cost_of_sales_minor").asLong()).isEqualTo(2_600);
        assertThat(row.get("gross_profit_minor").asLong()).isEqualTo(1_300);
        assertThat(row.get("usage_cost_minor").asLong()).isEqualTo(1_000);
        assertThat(row.get("profit_minor").asLong()).isEqualTo(300);
        assertThat(p.get("profit_minor").asLong()).isEqualTo(300);

        api.post("/products/" + a + "/prices", Map.of("cost_minor", 5_000, "reason", "Test later"), ADMIN);
        assertThat(api.get("/reports/profit/daily", ADMIN)
                        .getBody()
                        .get("profit_minor")
                        .asLong())
                .as("profit uses the snapshot, not today's cost")
                .isEqualTo(300);
        assertThat(api.get("/reports/profit/daily?from=2026-02-01&to=2026-01-01", ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /**
     * ADR-020 decision 10, FR-RET-13: a user signed in with the sales role at head office gets the
     * valuation of that branch with no cost, valuation at cost or inventory field present at all,
     * and the profit report refuses them.
     */
    @Test
    void theSalesRoleGetsNoCostAndNoProfit() {
        String email = Api.email("shop");
        UUID user = Api.staff(t, email, new Role("retail_sales", t.headOffice()));
        Api signedIn = Api.tenant(http, t.slug());
        Session session = signedIn.signIn(user, email, Api.PASSWORD, null);

        JsonNode v = signedIn.get("/api/v1/retail/reports/valuation", session.accessToken())
                .getBody();
        assertThat(v.has("expected_sales_minor")).isTrue();
        assertThat(v.has("value_at_cost_minor")).isFalse();
        assertThat(v.get("rows")).isNotEmpty();
        for (JsonNode r : v.get("rows")) {
            assertThat(r.get("branch_id").asString()).isEqualTo(t.headOffice().toString());
            assertThat(r.has("cost_minor")).isFalse();
            assertThat(r.has("value_at_cost_minor")).isFalse();
            assertThat(r.has("expected_sales_minor")).isTrue();
        }
        for (JsonNode br : v.get("branches")) {
            assertThat(br.has("value_at_cost_minor")).isFalse();
            assertThat(br.has("inventory_account_minor")).isFalse();
            assertThat(br.has("revaluation_difference_minor")).isFalse();
        }
        assertThat(v.toString()).doesNotContain("cost");

        var profit = signedIn.get("/api/v1/retail/reports/profit/daily", session.accessToken());
        assertThat(profit.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(profit.getBody().get("code").asString()).isEqualTo("permission_denied");
    }
}
