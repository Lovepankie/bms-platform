package com.rincoltech.bms.retail;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Issue #399, ADR-055, slice 2: a seller sees only their own figures. Two sellers of one shop and the owner, signed in for real:
 * each seller's sales list, grouped summary, one sale and its payments are their own; a colleague's sale is not found; the
 * reports that add up everybody's sales (the owner dashboard, the sales analysis, the delivery fees) are refused; and no
 * response a seller gets, across sales, stock, the catalogue, the valuation and stock health, carries a cost, a profit, a margin
 * or the value of the stock. The owner, with no branch assignment, sees every sale and every figure. Names and amounts are
 * fabricated.
 */
class RetailSellerFiguresIT extends IntegrationTest {

    static final String R = "/api/v1/retail";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    UUID product;
    UUID sellerA;
    UUID sellerB;
    String tokenA;
    String tokenB;
    String owner;
    UUID saleA;
    UUID saleB;
    UUID saleOwner;
    UUID saleOwnerTwo;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-own", false, true);
        RetailTestSupport retail = new RetailTestSupport(http, t);
        api = Api.tenant(http, t.slug());
        product = retail.product("OWN-1", 4_000, 7_000);
        retail.stockUp(t.headOffice(), product, "20");
        retail.stockUp(t.secondBranch(), product, "20");
        String emailA = Api.email("seller-a");
        String emailB = Api.email("seller-b");
        String emailOwner = Api.email("owner");
        sellerA = Api.staff(t, emailA, new Role("retail_sales", t.headOffice()));
        sellerB = Api.staff(t, emailB, new Role("retail_sales", t.headOffice()));
        UUID ownerId = Api.staff(t, emailOwner, new Role("tenant_admin", null));
        tokenA = api.signIn(sellerA, emailA, Api.PASSWORD, null).accessToken();
        tokenB = api.signIn(sellerB, emailB, Api.PASSWORD, null).accessToken();
        owner = api.signIn(ownerId, emailOwner, Api.PASSWORD, null).accessToken();
        saleA = sell(tokenA, t.headOffice(), "1");
        saleB = sell(tokenB, t.headOffice(), "2");
        saleOwner = sell(owner, t.headOffice(), "3");
        saleOwnerTwo = sell(owner, t.secondBranch(), "4");
    }

    static LocalDate today() {
        return LocalDate.now(BusinessClock.DEFAULT_ZONE);
    }

    UUID sell(String token, UUID branch, String qty) {
        Map<String, Object> body = Map.of(
                "branch_id",
                branch,
                "payment_method",
                "cash",
                "lines",
                List.of(Map.of("product_id", product, "qty", qty)));
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.POST,
                R + "/sales",
                body,
                token,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    ResponseEntity<JsonNode> get(String path, String token) {
        return api.get(R + path, token);
    }

    JsonNode ok(String path, String token) {
        ResponseEntity<JsonNode> r = get(path, token);
        assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static Set<String> ids(JsonNode items) {
        Set<String> out = new TreeSet<>();
        items.forEach(i -> out.add(i.get("id").asString()));
        return out;
    }

    static long totalOf(JsonNode summary) {
        long total = 0;
        for (JsonNode g : summary.get("items")) {
            total += g.get("total_minor").asLong();
        }
        return total;
    }

    static void collectKeys(JsonNode node, List<String> keys) {
        if (node.isObject()) {
            node.properties().forEach(e -> {
                keys.add(e.getKey());
                collectKeys(e.getValue(), keys);
            });
        } else if (node.isArray()) {
            node.forEach(n -> collectKeys(n, keys));
        }
    }

    /**
     * No cost, profit, margin or stock value under any key of a seller's response. A flag that says the figure is hidden
     * (profit_visible: false) is not a figure, and is checked to say so.
     */
    static void noOwnerFigures(JsonNode body, String what) {
        List<String> keys = new ArrayList<>();
        collectKeys(body, keys);
        if (body.has("profit_visible")) {
            assertThat(body.get("profit_visible").asBoolean()).as(what).isFalse();
        }
        assertThat(keys)
                .as(what)
                .filteredOn(k -> !k.endsWith("_visible"))
                .noneMatch(k -> k.contains("cost")
                        || k.contains("profit")
                        || k.contains("margin")
                        || k.contains("expected_sales")
                        || k.contains("value"));
    }

    @Test
    void aSellerSeesOnlyTheirOwnSalesAndTotals() {
        JsonNode mine = ok("/sales?limit=100", tokenA);
        assertThat(ids(mine.get("items"))).containsExactly(saleA.toString());
        assertThat(ids(ok("/sales?limit=100", tokenB).get("items"))).containsExactly(saleB.toString());
        // The grouped totals are their own: one unit at 7,000.
        JsonNode summary = ok("/sales/summary?group_by=day&from=" + today() + "&to=" + today(), tokenA);
        assertThat(totalOf(summary)).isEqualTo(7_000);
        assertThat(summary.get("available_levels").toString()).doesNotContain("seller");
        // Their own sale opens; a colleague's or the owner's is not found, and neither are its payments.
        assertThat(get("/sales/" + saleA, tokenA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/sales/" + saleB, tokenA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/sales/" + saleOwner, tokenA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get("/sales/" + saleB + "/payments", tokenA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Naming a colleague, or grouping by person, is refused; naming oneself is allowed.
        assertThat(get("/sales?seller_id=" + sellerB, tokenA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/sales?seller_id=" + sellerA, tokenA).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/sales/summary?group_by=seller", tokenA).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // A search finds only their own sales too.
        // The list's own date filter finds only their own sale of today.
        assertThat(ok("/sales?limit=100&from=" + today(), tokenA).get("items").size())
                .isEqualTo(1);
    }

    /**
     * A debt is the buyer's, so whoever is at the till finds it and takes the payment; the paid sale stays its seller's own.
     * JUSTIFICATION-A3: a new test case for the debts exception; the anchor is only the insertion point.
     */
    @Test
    void aSellerFindsAColleaguesUnpaidCreditSaleToTakeThePayment() {
        Map<String, Object> body = Map.of(
                "branch_id",
                t.headOffice(),
                "payment_method",
                "credit",
                "buyer_name",
                "Test Buyer 05",
                "lines",
                List.of(Map.of("product_id", product, "qty", "1")));
        ResponseEntity<JsonNode> credit = api.call(
                HttpMethod.POST,
                R + "/sales",
                body,
                tokenB,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(credit.getStatusCode()).as("%s", credit.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID debt = RetailTestSupport.id(credit);

        assertThat(ids(ok("/sales?limit=100&owing=owing", tokenA).get("items"))).containsExactly(debt.toString());
        assertThat(ids(ok("/sales?limit=100", tokenA).get("items"))).doesNotContain(debt.toString());
        assertThat(get("/sales/" + debt, tokenA).getStatusCode()).isEqualTo(HttpStatus.OK);
        // The colleague's debt is listed for the till, never summed: the grouped debts are the seller's own (none).
        JsonNode owingSummary = ok("/sales/summary?group_by=day&owing=owing", tokenA);
        assertThat(totalOf(owingSummary)).isZero();
        assertThat(totalOf(ok("/sales/summary?group_by=day&owing=owing", tokenB)))
                .isEqualTo(7_000);
        // A search is no way round it: by buyer name or by any text, a seller finds no colleague's sale outside the
        // debts.
        String byName = "/sales?limit=100&buyer="
                + java.net.URLEncoder.encode("Test Buyer 05", java.nio.charset.StandardCharsets.UTF_8);
        assertThat(ok(byName, tokenA).get("items")).isEmpty();
        assertThat(ids(ok(byName, tokenB).get("items"))).containsExactly(debt.toString());
        assertThat(ok("/sales?limit=100&query=Test&buyer=Test", tokenA).get("items"))
                .isEmpty();
        // On the debts list a colleague's row does not say who made it.
        for (JsonNode row : ok("/sales?limit=100&owing=owing", tokenA).get("items")) {
            for (String who : List.of("created_by", "voided_by")) {
                JsonNode v = row.get(who);
                assertThat(v == null || v.isNull())
                        .as("%s of a colleague's debt", who)
                        .isTrue();
            }
        }
        ResponseEntity<JsonNode> paid = api.call(
                HttpMethod.POST,
                R + "/sales/" + debt + "/payments",
                Map.of("amount_minor", 7_000, "method", "cash"),
                tokenA,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        // Paid in full, it is no longer a debt: it is the colleague's sale again and is not found.
        assertThat(get("/sales/" + debt, tokenA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        noOwnerFigures(ok("/sales?limit=100&owing=owing", tokenA), "debts list");
        assertThat(get("/sales?owing=owing&seller_id=" + sellerA, tokenA).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void theReportsThatAddUpEverybodysSalesAreRefusedToASeller() {
        for (String path : List.of(
                "/reports/dashboard",
                "/reports/dashboard?period=30d",
                "/reports/dashboard?period=all",
                "/reports/sales-analysis",
                "/orders/reports/delivery-fees",
                "/orders/reports/cancellations",
                "/reports/profit/daily?from=" + today() + "&to=" + today(),
                "/reports/margins",
                "/reports/business-evaluation",
                "/controls/summary")) {
            ResponseEntity<JsonNode> r = get(path, tokenA);
            assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
            // The random request_id is left out: an id like 30ed2140008b... holds "14000" (issue #621).
            ObjectNode problem = (ObjectNode) r.getBody().deepCopy();
            problem.remove("request_id");
            assertThat(problem.toString()).as(path).doesNotContain("7000", "14000", "21000");
        }
    }

    @Test
    void noSellerResponseCarriesCostProfitMarginOrStockValue() {
        noOwnerFigures(ok("/sales?limit=100", tokenA), "sales list");
        noOwnerFigures(ok("/sales/" + saleA, tokenA), "a sale");
        noOwnerFigures(ok("/sales/" + saleA + "/payments", tokenA), "its payments");
        noOwnerFigures(ok("/sales/summary?group_by=branch,day", tokenA), "sales summary");
        // Issue #428: the seller's range on the Retail home reads the summary by day, month and year.
        for (String level : List.of("day", "month", "year")) {
            noOwnerFigures(
                    ok(
                            "/sales/summary?group_by=" + level + "&from=" + today().minusDays(59) + "&to=" + today(),
                            tokenA),
                    "sales summary by " + level);
        }
        noOwnerFigures(ok("/stock?branch_id=" + t.headOffice(), tokenA), "stock list");
        noOwnerFigures(ok("/stock/movements", tokenA), "stock movements");
        noOwnerFigures(ok("/stock/summary?group_by=branch", tokenA), "stock summary");
        noOwnerFigures(ok("/products", tokenA), "items");
        noOwnerFigures(ok("/products/" + product, tokenA), "one item");
        noOwnerFigures(ok("/products/" + product + "/price-history", tokenA), "price history");
        noOwnerFigures(ok("/reports/valuation", tokenA), "valuation");
        noOwnerFigures(ok("/reports/stock-health", tokenA), "stock health");
        JsonNode valuation = ok("/reports/valuation", tokenA);
        assertThat(valuation.get("rows")).as("the quantities stay").isNotEmpty();
    }

    @Test
    void theOwnerWithNoBranchAssignmentSeesEverySaleAndEveryFigure() {
        JsonNode all = ok("/sales?limit=100", owner);
        assertThat(ids(all.get("items")))
                .containsExactlyInAnyOrder(
                        saleA.toString(), saleB.toString(), saleOwner.toString(), saleOwnerTwo.toString());
        all.get("items").forEach(s -> assertThat(s.has("profit_minor")).isTrue());
        JsonNode summary = ok("/sales/summary?group_by=day&from=" + today() + "&to=" + today(), owner);
        assertThat(totalOf(summary)).isEqualTo(70_000);
        assertThat(ok("/sales/summary?group_by=seller", owner).get("items").size())
                .isGreaterThanOrEqualTo(3);
        assertThat(ok("/sales/" + saleB, owner).has("profit_minor")).isTrue();
        assertThat(ok("/reports/dashboard", owner)
                        .get("today")
                        .get("sales_minor")
                        .asLong())
                .isEqualTo(70_000);
        assertThat(ok("/reports/sales-analysis", owner)
                        .get("totals")
                        .get("sales_minor")
                        .asLong())
                .isEqualTo(70_000);
        JsonNode valuation = ok("/reports/valuation", owner);
        assertThat(valuation.has("expected_sales_minor")).isTrue();
        assertThat(valuation.has("value_at_cost_minor")).isTrue();
        assertThat(ok("/stock/summary?group_by=branch", owner)
                        .get("items")
                        .get(0)
                        .has("value_minor"))
                .isTrue();
        assertThat(ok("/products/" + product, owner).has("cost_minor")).isTrue();
        assertThat(get("/orders/reports/delivery-fees", owner).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/orders/reports/cancellations", owner).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
