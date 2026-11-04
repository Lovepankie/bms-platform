package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
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
 * Issue #821 (ADR-126): a restock read by a holder of {@code retail.profit.read} carries what its goods should sell for and
 * the expected profit, on the delivery and on Past purchases; a buyer without that permission gets neither field. Every
 * price and quantity here is fabricated.
 */
class RetailDeliveryWorthIT extends IntegrationTest {

    /** Buys stock, with no profit permission and no permission to change prices. */
    static final String BUYER = "retail.purchase.create,retail.stock.read";

    static final String SETTINGS = "core.settings.manage";
    static final String CAPABILITIES = "/settings/capabilities";
    static final HttpMethod PUT = HttpMethod.PUT;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-delivery-worth", false, true);
        api = new RetailTestSupport(http, t);
    }

    Map<String, Object> line(UUID product, long cost, Long sell, String qty) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("cost_minor", cost);
        if (sell != null) {
            l.put("sell_minor", sell);
        }
        l.put("qty_by_branch", List.of(Map.of("branch_id", t.headOffice(), "qty", qty)));
        return l;
    }

    ResponseEntity<JsonNode> buy(String permissions, List<Map<String, Object>> lines) {
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/purchases",
                Map.of("payment_method", "cash", "lines", lines),
                permissions,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r;
    }

    JsonNode listed(UUID purchase, String permissions) {
        ResponseEntity<JsonNode> page = api.get("/purchases?limit=50", permissions);
        assertThat(page.getStatusCode()).as("%s", page.getBody()).isEqualTo(HttpStatus.OK);
        for (JsonNode p : page.getBody().get("items")) {
            if (p.get("id").asString().equals(purchase.toString())) {
                return p;
            }
        }
        throw new AssertionError("purchase " + purchase + " not listed in " + page.getBody());
    }

    static void hasWorth(JsonNode p, long worth, long profit) {
        assertThat(p.get("sell_worth_minor").asLong()).as("%s", p).isEqualTo(worth);
        assertThat(p.get("expected_profit_minor").asLong()).as("%s", p).isEqualTo(profit);
    }

    static void hasNoWorth(JsonNode p) {
        assertThat(p.has("sell_worth_minor")).as("%s", p).isFalse();
        assertThat(p.has("expected_profit_minor")).as("%s", p).isFalse();
    }

    @Test
    void aProfitReaderSeesTheWorthWithTheDeliverysPriceOrElseTheCurrentOne() {
        UUID a = api.product("DW-1", 400, 700);
        UUID b = api.product("DW-2", 1_000, 1_500);
        // Line a sets a new selling price; line b sets none, so the item's current price counts.
        JsonNode made = buy(ADMIN, List.of(line(a, 450, 800L, "10"), line(b, 1_000, null, "4")))
                .getBody();
        UUID id = RetailTestSupport.id(ResponseEntity.ok(made));
        // Paid 10 x 450 + 4 x 1,000 = 8,500; worth 10 x 800 + 4 x 1,500 = 14,000.
        assertThat(made.get("total_minor").asLong()).isEqualTo(8_500);
        hasWorth(made, 14_000, 5_500);
        hasWorth(api.get("/purchases/" + id, ADMIN).getBody(), 14_000, 5_500);
        hasWorth(listed(id, ADMIN), 14_000, 5_500);

        // The line without a price of its own follows the item's price as it is now: 4 x 1,600.
        assertThat(api.editPrices(b, Map.of("sell_minor", 1_600, "reason", "Test new price"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        hasWorth(api.get("/purchases/" + id, ADMIN).getBody(), 14_400, 5_900);
        hasWorth(listed(id, ADMIN), 14_400, 5_900);
    }

    @Test
    void aDeliverySoldBelowWhatWasPaidShowsANegativeProfit() {
        UUID a = api.product("DW-3", 900, 1_000);
        JsonNode made = buy(ADMIN, List.of(line(a, 1_200, 1_100L, "2.5"))).getBody();
        // Paid 2.5 x 1,200 = 3,000; worth 2.5 x 1,100 = 2,750.
        hasWorth(made, 2_750, -250);
    }

    // JUSTIFICATION-A3: the review of #850 asks for voids, replaces, replays and pack lines to be proven.
    @Test
    void aVoidedDeliveryHasNoWorthOnTheVoidTheDeliveryOrTheList() {
        UUID a = api.product("DW-6", 400, 600);
        UUID id = RetailTestSupport.id(buy(ADMIN, List.of(line(a, 400, null, "5"))));
        ResponseEntity<JsonNode> voided = api.postKeyed(
                "/purchases/" + id + "/void",
                Map.of("reason", "Test wrong delivery"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(voided.getBody().get("status").asString()).isEqualTo("voided");
        hasNoWorth(voided.getBody());
        hasNoWorth(api.get("/purchases/" + id, ADMIN).getBody());
        hasNoWorth(listed(id, ADMIN));
    }

    @Test
    void aBuyersVoidAndReplaceAnswersCarryNeitherFieldAndTheOwnersReplaceDoes() {
        UUID a = api.product("DW-7", 100, 150);
        UUID first = RetailTestSupport.id(buy(BUYER, List.of(line(a, 100, null, "4"))));
        ResponseEntity<JsonNode> replaced = api.postKeyed(
                "/purchases/" + first + "/replace",
                Map.of("payment_method", "cash", "lines", List.of(line(a, 100, null, "6"))),
                BUYER,
                "*",
                UUID.randomUUID().toString());
        assertThat(replaced.getStatusCode()).as("%s", replaced.getBody()).isEqualTo(HttpStatus.CREATED);
        hasNoWorth(replaced.getBody());
        UUID second = RetailTestSupport.id(replaced);
        ResponseEntity<JsonNode> voided = api.postKeyed(
                "/purchases/" + second + "/void",
                Map.of("reason", "Test wrong delivery"),
                BUYER,
                "*",
                UUID.randomUUID().toString());
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        hasNoWorth(voided.getBody());

        UUID third = RetailTestSupport.id(buy(ADMIN, List.of(line(a, 100, null, "2"))));
        ResponseEntity<JsonNode> owners = api.postKeyed(
                "/purchases/" + third + "/replace",
                Map.of("payment_method", "cash", "lines", List.of(line(a, 100, null, "3"))),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(owners.getStatusCode()).as("%s", owners.getBody()).isEqualTo(HttpStatus.CREATED);
        // The new delivery: 3 x 150 against 3 x 100.
        hasWorth(owners.getBody(), 450, 150);
    }

    @Test
    void theStoredAnswerHasNoWorthSoAReplayShowsItOnlyToAProfitReader() {
        UUID a = api.product("DW-8", 250, 400);
        Map<String, Object> body = Map.of("payment_method", "cash", "lines", List.of(line(a, 250, null, "4")));
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> made = api.postKeyed("/purchases", body, ADMIN, "*", key);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        hasWorth(made.getBody(), 1_600, 600);

        String stored = TestDatabase.owner()
                .sql("SELECT response_body::text FROM idempotency_keys WHERE tenant_id = ? AND key = ?")
                .params(t.tenantId(), key)
                .query(String.class)
                .single();
        assertThat(stored).contains(RetailTestSupport.id(made).toString());
        assertThat(stored).doesNotContain("sell_worth", "expected_profit");

        ResponseEntity<JsonNode> again = api.postKeyed("/purchases", body, ADMIN, "*", key);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        hasWorth(again.getBody(), 1_600, 600);
        ResponseEntity<JsonNode> asBuyer = api.postKeyed("/purchases", body, BUYER, "*", key);
        assertThat(asBuyer.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(RetailTestSupport.id(asBuyer)).isEqualTo(RetailTestSupport.id(made));
        hasNoWorth(asBuyer.getBody());
    }

    @Test
    void aPackLineCountsItsBaseQuantityWithThisDeliverysPackSize() {
        String etag = api.get(CAPABILITIES, SETTINGS).getHeaders().getETag();
        Map<String, String> ifMatch = etag == null ? Map.of() : Map.of("If-Match", etag);
        ResponseEntity<JsonNode> on = api.call(PUT, CAPABILITIES, Map.of("units", true), SETTINGS, "*", ifMatch);
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        UUID soap = api.product("DW-9", 300, 500);
        UUID box = api.unit("dw-box");
        assertThat(api.post("/products/" + soap + "/units", Map.of("unit_id", box, "factor", "12"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> packed = line(soap, 4_000, null, "2");
        packed.put("unit_id", box);
        // This delivery's boxes held 10, not the usual 12.
        packed.put("pack_size", "10");
        JsonNode made = buy(ADMIN, List.of(packed)).getBody();
        // Paid 2 x 4,000 = 8,000; 20 bars at 500 = 10,000.
        assertThat(made.get("lines").get(0).get("qty_total").asString()).isEqualTo("20.000");
        hasWorth(made, 10_000, 2_000);
        hasWorth(listed(RetailTestSupport.id(ResponseEntity.ok(made)), ADMIN), 10_000, 2_000);

        // With a price per bar set on the line: 20 x 450.
        Map<String, Object> priced = line(soap, 4_000, 450L, "2");
        priced.put("unit_id", box);
        priced.put("pack_size", "10");
        hasWorth(buy(ADMIN, List.of(priced)).getBody(), 9_000, 1_000);
    }

    // JUSTIFICATION-A3: the lead's decision 6 on the review of #850, proven on the delivery and the list.
    @Test
    void anItemWithNoSellingPriceYetLeavesTheWorthOutAndSaysSo() {
        UUID priced = api.product("DW-10", 200, 300);
        UUID unpriced = api.product("DW-11", 200, 300);
        TestDatabase.owner()
                .sql("UPDATE retail_products SET sell_minor = 0 WHERE id = ?")
                .params(unpriced)
                .update();
        List<Map<String, Object>> lines = List.of(line(priced, 200, null, "2"), line(unpriced, 200, null, "3"));
        UUID id = RetailTestSupport.id(buy(ADMIN, lines));
        for (JsonNode p : List.of(api.get("/purchases/" + id, ADMIN).getBody(), listed(id, ADMIN))) {
            hasNoWorth(p);
            assertThat(p.get("has_unpriced_items").asBoolean()).as("%s", p).isTrue();
        }
        JsonNode asBuyer = api.get("/purchases/" + id, BUYER).getBody();
        hasNoWorth(asBuyer);
        assertThat(asBuyer.has("has_unpriced_items")).as("%s", asBuyer).isFalse();
    }

    // JUSTIFICATION-A3: one more test case of the branch rule of ADR-017 for the new fields.
    /** ADR-017: profit read at one shop does not show the worth of a delivery that also stocked another. */
    @Test
    void profitReadAtOneOfTheDeliverysBranchesIsNotEnough() {
        UUID a = api.product("DW-5", 200, 300);
        Map<String, Object> split = line(a, 200, null, "1");
        split.put(
                "qty_by_branch",
                List.of(
                        Map.of("branch_id", t.headOffice(), "qty", "3"),
                        Map.of("branch_id", t.secondBranch(), "qty", "2")));
        UUID id = RetailTestSupport.id(buy(ADMIN, List.of(split)));
        Map<String, String> oneShop = Map.of("X-Dev-Scopes", "retail.profit.read=" + t.headOffice());
        ResponseEntity<JsonNode> scoped =
                api.call(org.springframework.http.HttpMethod.GET, "/purchases/" + id, null, ADMIN, "*", oneShop);
        assertThat(scoped.getStatusCode()).as("%s", scoped.getBody()).isEqualTo(HttpStatus.OK);
        hasNoWorth(scoped.getBody());
        // At both: 5 x 300 against 5 x 200.
        hasWorth(api.get("/purchases/" + id, ADMIN).getBody(), 1_500, 500);
    }

    @Test
    void aBuyerWithoutProfitReadGetsNeitherField() {
        UUID a = api.product("DW-4", 300, 500);
        ResponseEntity<JsonNode> made = buy(BUYER, List.of(line(a, 320, null, "6")));
        UUID id = RetailTestSupport.id(made);
        hasNoWorth(made.getBody());
        ResponseEntity<JsonNode> one = api.get("/purchases/" + id, BUYER);
        assertThat(one.getStatusCode()).as("%s", one.getBody()).isEqualTo(HttpStatus.OK);
        hasNoWorth(one.getBody());
        hasNoWorth(listed(id, BUYER));
        // The same delivery read by the owner carries both.
        hasWorth(api.get("/purchases/" + id, ADMIN).getBody(), 3_000, 1_080);
    }
}
