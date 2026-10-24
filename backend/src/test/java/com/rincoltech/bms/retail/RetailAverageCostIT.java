package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Profit from the moving weighted average buying price per smallest unit, kept per branch (ADR-079, issue #523, owner decision
 * D22; docs/specs/units-packs-and-costing.md section 3): deliveries of different carton sizes and prices, sales in cartons and
 * pieces, a transfer that carries its cost, a void that re-averages or is refused, and two deliveries at once. Every name and
 * amount is fabricated.
 */
class RetailAverageCostIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-average", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
    }

    // ---- helpers ---------------------------------------------------------------------------

    void switchUnitsOn() {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("units", true),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<JsonNode> restock(UUID branch, UUID product, UUID unit, String qty, long cost) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", cost);
        if (unit != null) {
            line.put("unit_id", unit);
        }
        line.put("qty_by_branch", List.of(Map.of("branch_id", branch, "qty", qty)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", "2026-01-15");
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        return api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    UUID restocked(UUID branch, UUID product, UUID unit, String qty, long cost) {
        ResponseEntity<JsonNode> r = restock(branch, product, unit, qty, cost);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    JsonNode sell(UUID branch, UUID product, UUID unit, String qty, long price) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("qty", qty);
        if (unit != null) {
            line.put("unit_id", unit);
        }
        line.put("unit_price_minor", price);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    long average(UUID branch, UUID product) {
        return TestDatabase.owner()
                .sql(
                        "SELECT avg_cost_minor FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), branch, product)
                .query(Long.class)
                .single();
    }

    String stock(UUID branch, UUID product) {
        return TestDatabase.owner()
                .sql(
                        "SELECT qty::text FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), branch, product)
                .query(String.class)
                .single();
    }

    /** What a branch holds at its own average cost, over every product (the valuation ADR-079 decision 9 reads). */
    long valuation(UUID branch) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(round(qty * avg_cost_minor)), 0)::bigint FROM retail_stock_balances
                         WHERE tenant_id = ? AND branch_id = ?
                        """)
                .params(t.tenantId(), branch)
                .query(Long.class)
                .single();
    }

    /** Debits less credits of the account with this system key in one branch. */
    long account(String systemKey, UUID branch) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = ? AND e.branch_id = ?
                        """)
                .params(t.tenantId(), systemKey, branch)
                .query(Long.class)
                .single();
    }

    long lastPaid(UUID product) {
        return api.get("/products/" + product, ADMIN)
                .getBody()
                .get("cost_minor")
                .asLong();
    }

    JsonNode dailyProfit(String day) {
        return api.get("/reports/profit/daily?from=" + day + "&to=" + day, ADMIN)
                .getBody();
    }

    static long profit(JsonNode sale) {
        return sale.get("profit_minor").asLong();
    }

    static long lineCost(JsonNode sale) {
        return sale.get("lines").get(0).get("line_cost_minor").asLong();
    }

    // ---- the tests -------------------------------------------------------------------------

    /**
     * Spec section 3: two deliveries of cartons of different sizes and prices, then sales in cartons and in pieces. Each sale
     * is costed per piece at the average of the moment, so its profit is right whichever way it was sold; the last paid price
     * is kept apart; a later delivery leaves the earlier sales and the day's profit as they were.
     */
    @Test
    void twoDeliveriesOfDifferentCartonsThenSalesInCartonsAndPiecesGiveTheRightProfit() {
        switchUnitsOn();
        UUID bulb = api.product("AVG-BULB", 750, 1_000);
        UUID carton = api.unit("carton");
        ResponseEntity<JsonNode> pack =
                api.post("/products/" + bulb + "/units", Map.of("unit_id", carton, "factor", "24"), ADMIN);
        assertThat(pack.getStatusCode()).as("%s", pack.getBody()).isEqualTo(HttpStatus.CREATED);

        // Two cartons of 24 at 18,000 each: 48 pieces at 750.
        restocked(hq, bulb, carton, "2", 18_000);
        assertThat(average(hq, bulb)).isEqualTo(750);

        // The next supplier's carton holds 30 and costs 24,000: 800 a piece. (60,000 for 78 pieces is 769.23.)
        ResponseEntity<JsonNode> resized = api.call(
                HttpMethod.PATCH,
                "/products/" + bulb + "/units/" + carton,
                Map.of("factor", "30"),
                ADMIN,
                "*",
                Map.of("If-Match", pack.getHeaders().getETag()));
        assertThat(resized.getStatusCode()).as("%s", resized.getBody()).isEqualTo(HttpStatus.OK);
        UUID second = restocked(hq, bulb, carton, "1", 24_000);
        assertThat(stock(hq, bulb)).isEqualTo("78.000");
        assertThat(average(hq, bulb)).isEqualTo(769);
        assertThat(lastPaid(bulb)).isEqualTo(800);
        // What 769 does not carry (60,000 less 78 x 769 = 59,982) went to purchase rounding with the second delivery.
        assertThat(account("purchase_rounding", hq)).isEqualTo(18);
        assertThat(account("inventory", hq)).isEqualTo(valuation(hq)).isEqualTo(59_982);
        assertThat(second).isNotNull();

        // A carton (now 30 pieces) for 27,000 and five pieces at 1,000: each costed at 769 a piece.
        JsonNode cartonSale = sell(hq, bulb, carton, "1", 27_000);
        assertThat(lineCost(cartonSale)).isEqualTo(30 * 769);
        assertThat(profit(cartonSale)).isEqualTo(27_000 - 30 * 769);
        assertThat(cartonSale.get("lines").get(0).get("unit_cost_minor").asLong())
                .isEqualTo(769);
        JsonNode pieceSale = sell(hq, bulb, null, "5", 1_000);
        assertThat(lineCost(pieceSale)).isEqualTo(5 * 769);
        assertThat(profit(pieceSale)).isEqualTo(5_000 - 5 * 769);
        // The average is not moved by selling.
        assertThat(average(hq, bulb)).isEqualTo(769);
        assertThat(stock(hq, bulb)).isEqualTo("43.000");

        String day = cartonSale.get("sale_date").asString();
        JsonNode before = dailyProfit(day);
        assertThat(before.get("sales_minor").asLong()).isEqualTo(32_000);
        assertThat(before.get("cost_of_sales_minor").asLong()).isEqualTo(35 * 769);

        // A third delivery at a higher price moves the average for later sales only.
        restocked(hq, bulb, carton, "1", 27_000);
        assertThat(average(hq, bulb)).isEqualTo(Math.round((43 * 769 + 27_000) / 73.0));
        assertThat(api.get("/sales/" + cartonSale.get("id").asString(), ADMIN)
                        .getBody()
                        .get("profit_minor")
                        .asLong())
                .isEqualTo(27_000 - 30 * 769);
        JsonNode after = dailyProfit(day);
        assertThat(after.get("cost_of_sales_minor").asLong()).isEqualTo(35 * 769);
        assertThat(after.get("profit_minor").asLong())
                .isEqualTo(before.get("profit_minor").asLong());
        // The stock is still worth what the inventory account says.
        assertThat(account("inventory", hq)).isEqualTo(valuation(hq));
    }

    /** ADR-079 decision 4: a transfer leaves the source at its average and that cost re-averages the destination. */
    @Test
    void aTransferCarriesItsCostToTheDestinationsAverage() {
        UUID cable = api.product("AVG-CABLE", 1_000, 2_500);
        restocked(hq, cable, null, "10", 1_000);
        restocked(br2, cable, null, "10", 1_600);
        assertThat(average(br2, cable)).isEqualTo(1_600);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from_branch_id", hq);
        body.put("to_branch_id", br2);
        body.put("lines", List.of(Map.of("product_id", cable, "qty", "10")));
        ResponseEntity<JsonNode> moved =
                api.postKeyed("/transfers", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.CREATED);

        // 10 at 1,600 and 10 at 1,000: 1,300 a metre at the destination; the source keeps its 1,000.
        assertThat(average(br2, cable)).isEqualTo(1_300);
        assertThat(average(hq, cable)).isEqualTo(1_000);
        assertThat(lastPaid(cable)).isEqualTo(1_600);
        // The price floor is the average, not the last paid 1,600: 1,300 is refused, 1,400 is sold.
        Map<String, Object> atCost = new LinkedHashMap<>();
        atCost.put("branch_id", br2);
        atCost.put("payment_method", "cash");
        atCost.put("lines", List.of(Map.of("product_id", cable, "qty", "1", "unit_price_minor", 1_300)));
        ResponseEntity<JsonNode> floor =
                api.postKeyed("/sales", atCost, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(floor.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(floor.getBody().get("code").asString()).isEqualTo("price_below_cost");
        JsonNode sale = sell(br2, cable, null, "1", 1_400);
        assertThat(lineCost(sale)).isEqualTo(1_300);
        // Each branch's inventory account is its valuation at its own average.
        assertThat(account("inventory", br2)).isEqualTo(valuation(br2)).isEqualTo(19 * 1_300);
        assertThat(account("inventory", hq)).isEqualTo(valuation(hq)).isZero();
    }

    /**
     * ADR-079 decision 5: voiding a restock takes its units out at what they cost and re-averages what stays; one that would
     * leave the rest at a cost below zero is refused in plain words and changes nothing.
     */
    @Test
    void aVoidReaveragesWhatStaysOrIsRefused() {
        UUID soap = api.product("AVG-SOAP", 100, 400);
        restocked(hq, soap, null, "10", 100);
        UUID second = restocked(hq, soap, null, "10", 200);
        assertThat(average(hq, soap)).isEqualTo(150);
        assertThat(lineCost(sell(hq, soap, null, "5", 400))).isEqualTo(750);

        ResponseEntity<JsonNode> voided = api.postKeyed(
                "/purchases/" + second + "/void",
                Map.of("reason", "Typed the wrong delivery"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        // 15 at 150 is 2,250; less the 10 at 200 that go back: 250 for the 5 left, 50 each.
        assertThat(stock(hq, soap)).isEqualTo("5.000");
        assertThat(average(hq, soap)).isEqualTo(50);
        assertThat(account("inventory", hq)).isEqualTo(valuation(hq)).isEqualTo(250);

        UUID tape = api.product("AVG-TAPE", 100, 900);
        restocked(hq, tape, null, "10", 100);
        UUID dear = restocked(hq, tape, null, "10", 300);
        assertThat(average(hq, tape)).isEqualTo(200);
        sell(hq, tape, null, "8", 900);
        // 12 at 200 is 2,400; taking out 10 at 300 would leave 2 carrying -600.
        ResponseEntity<JsonNode> refused = api.postKeyed(
                "/purchases/" + dear + "/void",
                Map.of("reason", "Typed the wrong delivery"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("average_cost_negative");
        assertThat(refused.getBody().get("detail").asString())
                .contains("Test Product AVG-TAPE")
                .contains("cost below zero")
                .doesNotContain("200")
                .doesNotContain("300");
        assertThat(stock(hq, tape)).isEqualTo("12.000");
        assertThat(average(hq, tape)).isEqualTo(200);
        assertThat(account("inventory", hq)).isEqualTo(valuation(hq));
    }

    /** ADR-079 decision 1: two deliveries at once are averaged one after the other under the balance row's lock. */
    @Test
    void twoDeliveriesAtOnceAreBothAveraged() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                UUID nail = api.product("AVG-NAIL-" + round, 100, 500);
                restocked(hq, nail, null, "10", 100);
                CountDownLatch go = new CountDownLatch(1);
                Future<ResponseEntity<JsonNode>> a = pool.submit(() -> {
                    go.await();
                    return restock(hq, nail, null, "10", 200);
                });
                Future<ResponseEntity<JsonNode>> b = pool.submit(() -> {
                    go.await();
                    return restock(hq, nail, null, "20", 400);
                });
                go.countDown();
                assertThat(a.get(60, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(b.get(60, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
                // 1,000 + 2,000 + 8,000 over 40, in either order: 150 then 275, or 300 then 275.
                assertThat(stock(hq, nail)).isEqualTo("40.000");
                assertThat(average(hq, nail)).isEqualTo(275);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(account("inventory", hq)).isEqualTo(valuation(hq)).isEqualTo(5 * 11_000);
        assertThat(account("purchase_rounding", hq)).isZero();
    }

    void capabilities(Map<String, Object> flags) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                flags,
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    UUID transfer(UUID from, UUID to, UUID product, String qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from_branch_id", from);
        body.put("to_branch_id", to);
        body.put("lines", List.of(Map.of("product_id", product, "qty", qty)));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/transfers", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    /**
     * ADR-079 decision 1: a sale locks the balance row before it reads the average, so a delivery committing at the same
     * moment leaves it costed at the average before the delivery or the one after, never a mix, and the books agree.
     */
    @Test
    void aSaleRacingADeliveryIsCostedAtOneAverage() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                UUID fuse = api.product("AVG-FUSE-" + round, 100, 1_000);
                restocked(hq, fuse, null, "10", 100);
                CountDownLatch go = new CountDownLatch(1);
                Future<ResponseEntity<JsonNode>> delivery = pool.submit(() -> {
                    go.await();
                    return restock(hq, fuse, null, "10", 300);
                });
                Future<JsonNode> sale = pool.submit(() -> {
                    go.await();
                    return sell(hq, fuse, null, "4", 1_000);
                });
                go.countDown();
                assertThat(delivery.get(60, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
                // Sold first: 4 at 100. Delivered first: (1,000 + 3,000) / 20 = 200 a piece.
                long cost = lineCost(sale.get(60, TimeUnit.SECONDS));
                assertThat(cost).isIn(400L, 800L);
                assertThat(average(hq, fuse)).isEqualTo(cost == 400 ? (600 + 3_000) / 16 : 200);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(account("inventory", hq)).isEqualTo(valuation(hq));
    }

    /**
     * ADR-079 decision 4, two-step: goods on the road carry the source's average at dispatch, and the destination re-averages
     * with that cost when they arrive, whatever the source paid for a later delivery meanwhile.
     */
    @Test
    void aTwoStepTransferArrivesAtTheCostItLeftWith() {
        capabilities(Map.of("transfer_receipt", true));
        UUID lamp = api.product("AVG-LAMP", 100, 900);
        restocked(hq, lamp, null, "10", 100);
        restocked(br2, lamp, null, "5", 200);
        UUID moved = transfer(hq, br2, lamp, "5");
        // Head office buys dearer while the five are on the road: (500 + 3,000) / 15 = 233.
        restocked(hq, lamp, null, "10", 300);
        assertThat(average(hq, lamp)).isEqualTo(233);
        ResponseEntity<JsonNode> received = api.postKeyed(
                "/transfers/" + moved + "/receive",
                Map.of(),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(received.getStatusCode()).as("%s", received.getBody()).isEqualTo(HttpStatus.OK);
        // 5 at 200 and 5 at the 100 they left with: 150.
        assertThat(average(br2, lamp)).isEqualTo(150);
        assertThat(account("inventory", br2)).isEqualTo(valuation(br2)).isEqualTo(1_500);
        assertThat(account("inventory", hq)).isEqualTo(valuation(hq));
    }

    /** ADR-079 decision 3: the void of a transfer takes each side back at the transfer's cost, which re-averages both. */
    @Test
    void aTransferVoidReaveragesBothBranches() {
        UUID plug = api.product("AVG-PLUG", 100, 900);
        restocked(hq, plug, null, "10", 100);
        restocked(br2, plug, null, "10", 300);
        UUID moved = transfer(hq, br2, plug, "5");
        // (3,000 + 500) / 15 = 233.
        assertThat(average(br2, plug)).isEqualTo(233);
        ResponseEntity<JsonNode> voided =
                api.post("/transfers/" + moved + "/void", Map.of("reason", "Test wrong branch"), ADMIN);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        // Branch two: (15 x 233 less 5 at 100) / 10 = 299.5, carried as 300; head office: 10 at 100 again.
        assertThat(average(br2, plug)).isEqualTo(300);
        assertThat(average(hq, plug)).isEqualTo(100);
        assertThat(account("inventory", br2)).isEqualTo(valuation(br2)).isEqualTo(3_000);
        assertThat(account("inventory", hq)).isEqualTo(valuation(hq)).isEqualTo(1_000);
    }

    /** ADR-079 decision 3: a correction and a count move stock at the branch's average, which they leave as it is. */
    @Test
    void aCorrectionAndACountMoveStockAtTheAverage() {
        UUID wire = api.product("AVG-WIRE", 100, 900);
        restocked(hq, wire, null, "10", 100);
        restocked(hq, wire, null, "10", 200);
        assertThat(average(hq, wire)).isEqualTo(150);
        Map<String, Object> fix = new LinkedHashMap<>();
        fix.put("branch_id", hq);
        fix.put("product_id", wire);
        fix.put("new_qty", "18");
        fix.put("expected_qty", "20");
        fix.put("reason_code", "miscount");
        ResponseEntity<JsonNode> corrected = api.postKeyed(
                "/stock/corrections", fix, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(corrected.getStatusCode()).as("%s", corrected.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(account("stock_shrinkage", hq)).isEqualTo(300);
        api.stockUp(hq, wire, "21");
        assertThat(average(hq, wire)).isEqualTo(150);
        assertThat(account("stock_shrinkage", hq)).isEqualTo(300 - 450);
        assertThat(account("inventory", hq)).isEqualTo(valuation(hq)).isEqualTo(21 * 150);
        assertThat(account("purchase_rounding", hq)).isZero();
    }
}
