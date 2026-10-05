package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Valuation cases from the adversarial review (#68): business dates (F4) and branches that hold no
 * stock but whose inventory account is not zero (F3). Figures fabricated and worked in comments.
 */
class RetailValuationReviewIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID c;
    LocalDate today;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-val", false, true);
        api = new RetailTestSupport(http, t);
        c = api.product("VAL-C", 100, 200);
        today = LocalDate.now(ZoneId.of("Africa/Kampala"));
    }

    /**
     * F4: a restock of 10 at 100 dated three days ago and a sale of 3 dated yesterday. Two days ago
     * the branch held 10 worth 1,000 at cost, and so did the inventory account; yesterday 7 and 700.
     * Four days ago it held nothing. The quantity follows the business date, as the journals do, so
     * the revaluation difference stays zero.
     */
    @Test
    void asOfFollowsTheBusinessDateOfEachMovement() {
        restock(c, t.headOffice(), "10", 100, today.minusDays(3));
        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("branch_id", t.headOffice());
        sale.put("payment_method", "cash");
        sale.put("sale_date", today.minusDays(1).toString());
        sale.put("lines", List.of(Map.of("product_id", c, "qty", "3")));
        assertThat(api.postKeyed("/sales", sale, ADMIN, "*", UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        JsonNode twoDaysAgo = valuation("?as_of=" + today.minusDays(2));
        assertThat(twoDaysAgo.get("rows")).hasSize(1);
        assertThat(twoDaysAgo.get("rows").get(0).get("qty").asString()).isEqualTo("10.000");
        JsonNode branch = twoDaysAgo.get("branches").get(0);
        assertThat(branch.get("value_at_cost_minor").asLong()).isEqualTo(1_000);
        assertThat(branch.get("inventory_account_minor").asLong()).isEqualTo(1_000);
        assertThat(branch.get("revaluation_difference_minor").asLong()).isZero();

        JsonNode yesterday = valuation("?as_of=" + today.minusDays(1));
        assertThat(yesterday.get("rows").get(0).get("qty").asString()).isEqualTo("7.000");
        assertThat(yesterday
                        .get("branches")
                        .get(0)
                        .get("revaluation_difference_minor")
                        .asLong())
                .isZero();

        JsonNode before = valuation("?as_of=" + today.minusDays(4));
        assertThat(before.get("rows")).isEmpty();
        assertThat(before.get("branches")).isEmpty();
    }

    /**
     * F3: restock 10 at 100 at each branch (inventory 1,000 each), edit the cost to 50 and sell all
     * 10 at head office (cost relieved at 500). Head office holds nothing but its inventory account
     * still holds 500, so it is reported with value 0 and difference -500. A caller filtered to
     * branch two sees only branch two.
     */
    @Test
    void aBranchWithNoStockButAnInventoryBalanceIsReported() {
        restock(c, t.headOffice(), "10", 100, today);
        restock(c, t.secondBranch(), "10", 100, today);
        assertThat(api.post("/products/" + c + "/prices", Map.of("cost_minor", 50, "reason", "Test edit"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.sell(t.headOffice(), "cash", c, "10").getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode all = valuation("");
        Map<String, JsonNode> branches = new LinkedHashMap<>();
        all.get("branches").forEach(b -> branches.put(b.get("branch_id").asString(), b));
        JsonNode hq = branches.get(t.headOffice().toString());
        assertThat(hq).as("%s", all).isNotNull();
        assertThat(hq.get("expected_sales_minor").asLong()).isZero();
        assertThat(hq.get("value_at_cost_minor").asLong()).isZero();
        assertThat(hq.get("inventory_account_minor").asLong()).isEqualTo(500);
        assertThat(hq.get("revaluation_difference_minor").asLong()).isEqualTo(-500);
        JsonNode two = branches.get(t.secondBranch().toString());
        assertThat(two.get("value_at_cost_minor").asLong()).isEqualTo(500);
        assertThat(two.get("inventory_account_minor").asLong()).isEqualTo(1_000);
        assertThat(two.get("revaluation_difference_minor").asLong()).isEqualTo(-500);

        JsonNode scoped = api.call(
                        org.springframework.http.HttpMethod.GET,
                        "/reports/valuation",
                        null,
                        ADMIN,
                        t.secondBranch().toString(),
                        Map.of())
                .getBody();
        assertThat(scoped.get("branches")).hasSize(1);
        assertThat(scoped.get("branches").get(0).get("branch_id").asString())
                .isEqualTo(t.secondBranch().toString());
    }

    JsonNode valuation(String query) {
        ResponseEntity<JsonNode> r = api.get("/reports/valuation" + query, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    void restock(UUID product, UUID branch, String qty, long cost, LocalDate on) {
        Map<String, Object> line = Map.of(
                "product_id",
                product,
                "cost_minor",
                cost,
                "qty_by_branch",
                List.of(Map.of("branch_id", branch, "qty", qty)));
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/purchases",
                Map.of("purchased_on", on.toString(), "payment_method", "cash", "lines", List.of(line)),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }
}
