package com.rincoltech.bms.retail.purchasing;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * A buying pack's size per delivery (owner decision D23, issue #554; docs/specs/units-packs-and-costing.md section 3 and
 * section 9 step 2): a carton that held 30 where it usually holds 24 goes into stock as 30 and into the average per piece
 * (ADR-079) at its own cost, the size stays on the line, "use from now on" makes it the usual size, selling keeps the usual
 * size, a void or goods sent back take out the line's own size, a receipt in bigger cartons against an order is not an
 * over-receipt, and two deliveries at once both land. Every name and amount is fabricated.
 */
class RetailPackSizePerDeliveryIT extends LandedCostTestBase {

    UUID bulb;
    UUID carton;

    @Override
    @BeforeEach
    void setUp() {
        super.setUp();
        capability("units", true);
        bulb = api.product("PS-BULB", 750, 1_000);
        carton = api.unit("carton");
        ResponseEntity<JsonNode> pack =
                api.post("/products/" + bulb + "/units", Map.of("unit_id", carton, "factor", "24"), ADMIN);
        assertThat(pack.getStatusCode()).as("%s", pack.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    // ---- helpers ---------------------------------------------------------------------------

    Map<String, Object> restockLine(String qty, long cost, String packSize, boolean fromNowOn) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", bulb);
        line.put("cost_minor", cost);
        line.put("unit_id", carton);
        if (packSize != null) {
            line.put("pack_size", packSize);
        }
        if (fromNowOn) {
            line.put("use_from_now_on", true);
        }
        line.put("qty_by_branch", List.of(Map.of("branch_id", hq, "qty", qty)));
        return line;
    }

    ResponseEntity<JsonNode> restock(Map<String, Object> line, String perms) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", TODAY);
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        return api.postKeyed("/purchases", body, perms, "*", key());
    }

    JsonNode restocked(String qty, long cost, String packSize, boolean fromNowOn) {
        ResponseEntity<JsonNode> r = restock(restockLine(qty, cost, packSize, fromNowOn), ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    /** The usual size of the carton, as the item's packs show it. */
    String usual() {
        for (JsonNode k :
                api.get("/products/" + bulb + "/units", ADMIN).getBody().get("items")) {
            if (k.get("unit_id").asString().equals(carton.toString())) {
                return k.get("factor").asString();
            }
        }
        throw new AssertionError("no carton pack");
    }

    long average(UUID branch) {
        return TestDatabase.owner()
                .sql("SELECT avg_cost_minor FROM retail_stock_balances WHERE branch_id = ? AND product_id = ?")
                .params(branch, bulb)
                .query(Long.class)
                .single();
    }

    JsonNode sell(UUID unit, String qty, long price) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", bulb);
        line.put("qty", qty);
        if (unit != null) {
            line.put("unit_id", unit);
        }
        line.put("unit_price_minor", price);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        ResponseEntity<JsonNode> r = api.postKeyed("/sales", body, ADMIN, "*", key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    long audits(String sourceId) {
        return scalar(
                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.product_unit.updated'"
                        + " AND data -> 'after' ->> 'source_id' = ?",
                t.tenantId(),
                sourceId);
    }

    Map<String, Object> receiptLine(UUID orderLine, String qty, long cost, String packSize) {
        Map<String, Object> l = new LinkedHashMap<>();
        if (orderLine != null) {
            l.put("order_line_id", orderLine);
        } else {
            l.put("product_id", bulb);
            l.put("unit_id", carton);
        }
        l.put("qty", qty);
        l.put("cost_minor", cost);
        if (packSize != null) {
            l.put("pack_size", packSize);
        }
        return l;
    }

    JsonNode receipt(UUID order, Map<String, Object> line) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (order != null) {
            b.put("order_id", order);
        } else {
            b.put("supplier_id", supplier);
        }
        b.put("branch_id", hq);
        b.put("lines", List.of(line));
        ResponseEntity<JsonNode> r = keyed("/goods-receipts", b, BUYER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    // ---- the tests -------------------------------------------------------------------------

    /**
     * Spec section 3: the usual carton holds 24; this delivery's held 30. Stock goes up by cartons times 30, the average per
     * piece takes 800 a piece for those 30, the line keeps 30, and the item's carton, which selling uses, stays at 24.
     */
    @Test
    void aCartonOfThirtyWhereTwentyFourIsUsualGoesInAsThirtyAndAveragesPerPiece() {
        restocked("2", 18_000, null, false);
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("48");
        assertThat(average(hq)).isEqualTo(750);

        JsonNode bought = restocked("1", 24_000, "30", false);
        JsonNode line = bought.get("lines").get(0);
        assertThat(line.get("unit_factor").asString()).isEqualTo("30.000");
        assertThat(line.get("unit_qty_total").asString()).isEqualTo("1.000");
        assertThat(line.get("qty_total").asString()).isEqualTo("30.000");
        assertThat(line.get("cost_minor").asLong()).isEqualTo(800);
        assertThat(line.get("line_total_minor").asLong()).isEqualTo(24_000);
        // 48 at 750 and 30 at 800: 60,000 for 78 pieces, 769 a piece.
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("78");
        assertThat(average(hq)).isEqualTo(769);
        assertThat(costOf(bulb)).isEqualTo(800);
        assertThat(usual()).isEqualTo("24.000");
        assertThat(audits(bought.get("id").asString())).isZero();
        assertThat(balance("inventory", hq)).isEqualTo(valuation(hq, bulb));

        // A carton sold is the usual 24, costed at the average per piece.
        JsonNode sale = sell(carton, "1", 24_000);
        assertThat(sale.get("lines").get(0).get("qty").asString()).isEqualTo("24.000");
        assertThat(sale.get("lines").get(0).get("line_cost_minor").asLong()).isEqualTo(24 * 769);
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("54");
        assertBooksBalance();
    }

    /** "Use 30 from now on" makes 30 the usual size, with an audit row naming the delivery; selling then uses 30. */
    @Test
    void useFromNowOnMakesTheDeliverysSizeTheUsualOne() {
        JsonNode bought = restocked("1", 24_000, "30", true);
        assertThat(usual()).isEqualTo("30.000");
        assertThat(audits(bought.get("id").asString())).isEqualTo(1);
        assertThat(sell(carton, "1", 30_000).get("lines").get(0).get("qty").asString())
                .isEqualTo("30.000");

        // The usual size again changes nothing and writes no audit row.
        JsonNode same = restocked("1", 24_000, "30", true);
        assertThat(audits(same.get("id").asString())).isZero();
    }

    /** Only someone who manages items changes the usual size; a size on a line not in a pack, or below one, is refused. */
    @Test
    void theUsualSizeNeedsTheItemPermissionAndASizeNeedsAPack() {
        String buyer = ADMIN.replace("retail.catalogue.manage,", "");
        ResponseEntity<JsonNode> refused = restock(restockLine("1", 24_000, "30", true), buyer);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("detail").asString()).contains("Use from now on");
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("0");
        assertThat(usual()).isEqualTo("24.000");
        // Without the tick the same person records the delivery's own size.
        assertThat(restock(restockLine("1", 24_000, "30", false), buyer).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("30");

        Map<String, Object> pieces = restockLine("5", 750, "30", false);
        pieces.remove("unit_id");
        ResponseEntity<JsonNode> noPack = restock(pieces, ADMIN);
        assertThat(noPack.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(noPack.getBody().toString()).contains("lines[0].pack_size");
        ResponseEntity<JsonNode> tooSmall = restock(restockLine("1", 24_000, "0.5", false), ADMIN);
        assertThat(tooSmall.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(tooSmall.getBody().toString()).contains("lines[0].pack_size");
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("30");
    }

    /** The void of a restock and of a goods receipt takes out what the line put in, at its own size, after the usual changed. */
    @Test
    void aVoidTakesOutTheLinesOwnSize() {
        restocked("1", 18_000, null, false);
        JsonNode bought = restocked("2", 48_000, "30", false);
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("84");
        // The usual size moves to 20 afterwards; the void still takes out the 60 the line put in.
        restocked("1", 20_000, "20", true);
        assertThat(usual()).isEqualTo("20.000");
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("104");
        ResponseEntity<JsonNode> voided = keyed(
                "/purchases/" + bought.get("id").asString() + "/void", Map.of("reason", "Test typed twice"), ADMIN);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("44");
        assertThat(balance("inventory", hq)).isEqualTo(valuation(hq, bulb));

        JsonNode received = receipt(null, receiptLine(null, "2", 36_000, "30"));
        assertThat(received.get("lines").get(0).get("unit_factor").asString()).isEqualTo("30.000");
        assertThat(received.get("lines").get(0).get("qty").asString()).isEqualTo("60.000");
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("104");
        ResponseEntity<JsonNode> undone = keyed(
                "/goods-receipts/" + received.get("id").asString() + "/void",
                Map.of("reason", "Test wrong supplier"),
                BUYER);
        assertThat(undone.getStatusCode()).as("%s", undone.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("44");
        assertThat(balance("inventory", hq)).isEqualTo(valuation(hq, bulb));
        assertBooksBalance();
    }

    /**
     * Goods received against an order of ten cartons, in cartons of 30: ten cartons arrived, so the order is received and not
     * over-received; the stock is 300 pieces; one carton sent back to the supplier takes out 30, the line's size.
     */
    @Test
    void biggerCartonsAgainstAnOrderAreNotAnOverReceiptAndGoBackAtTheLinesSize() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("product_id", bulb);
        ordered.put("unit_id", carton);
        ordered.put("qty", "10");
        ordered.put("cost_minor", 18_000);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supplier_id", supplier);
        body.put("branch_id", hq);
        body.put("lines", List.of(ordered));
        ResponseEntity<JsonNode> draft = keyed("/purchase-orders", body, BUYER);
        assertThat(draft.getStatusCode()).as("%s", draft.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID order = uuid(draft.getBody(), "id");
        ResponseEntity<JsonNode> sent = keyed("/purchase-orders/" + order + "/send", Map.of(), BUYER);
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.OK);
        UUID orderLine = uuid(sent.getBody().get("order").get("lines").get(0), "id");

        // Six cartons of 30 first: 180 pieces, more than 6 x 24 but six of the ten cartons.
        receipt(order, receiptLine(orderLine, "6", 18_000, "30"));
        JsonNode partly = api.get("/purchase-orders/" + order, PAYER).getBody();
        assertThat(partly.get("status").asString()).isEqualTo("partly_received");
        assertThat(partly.get("lines").get(0).get("received_unit_qty").asString())
                .isEqualTo("6.000");
        assertThat(partly.get("lines").get(0).get("received_qty").asString()).isEqualTo("180.000");
        // Four more: 300 pieces in all, the ten cartons ordered, so received in full and within the order.
        JsonNode rest = receipt(order, receiptLine(orderLine, "4", 18_000, "30"));
        JsonNode full = api.get("/purchase-orders/" + order, PAYER).getBody();
        assertThat(full.get("status").asString()).isEqualTo("received");
        assertThat(full.get("lines").get(0).get("received_unit_qty").asString()).isEqualTo("10.000");
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("300");
        // 18,000 a carton of 30 is 600 a piece.
        assertThat(average(hq)).isEqualTo(600);
        assertThat(usual()).isEqualTo("24.000");

        // The supplier bills the four cartons, and one is sent back: 30 pieces leave, at what they came in at.
        JsonNode restLine = rest.get("lines").get(0);
        Map<String, Object> billLine = new LinkedHashMap<>();
        billLine.put("receipt_line_id", uuid(restLine, "id"));
        billLine.put("qty", "4");
        billLine.put("unit_price_minor", 18_000);
        Map<String, Object> bill = new LinkedHashMap<>();
        bill.put("supplier_id", supplier);
        bill.put("supplier_ref", "INV-PS-1");
        bill.put("bill_date", TODAY);
        bill.put("due_date", DUE);
        bill.put("total_minor", 72_000);
        bill.put("lines", List.of(billLine));
        ResponseEntity<JsonNode> billed = keyed("/supplier-bills", bill, PAYER);
        assertThat(billed.getStatusCode()).as("%s", billed.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> back = new LinkedHashMap<>();
        back.put("bill_line_id", uuid(billed.getBody().get("lines").get(0), "id"));
        back.put("qty", "1");
        Map<String, Object> credit = new LinkedHashMap<>();
        credit.put("bill_id", uuid(billed.getBody(), "id"));
        credit.put("kind", "goods");
        credit.put("supplier_ref", "CN-PS-1");
        credit.put("credit_date", TODAY);
        credit.put("total_minor", 18_000);
        credit.put("reason", "Test carton crushed on delivery");
        credit.put("lines", List.of(back));
        ResponseEntity<JsonNode> credited = keyed("/supplier-credits", credit, PAYER);
        assertThat(credited.getStatusCode()).as("%s", credited.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("270");
        assertThat(credited.getBody()
                        .get("lines")
                        .get(0)
                        .get("to_inventory_minor")
                        .asLong())
                .isEqualTo(18_000);
        assertThat(balance("inventory", hq)).isEqualTo(valuation(hq, bulb));
        assertBooksBalance();
    }

    /**
     * ADR-079 lock order: two deliveries of the same item at once, each in its own carton size and each making it the usual
     * size, both land; stock and the average are right in either order and the usual size is the one committed last.
     */
    @Test
    void twoDeliveriesAtOnceWithTheirOwnSizesBothLand() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                UUID item = api.product("PS-RACE-" + round, 750, 1_000);
                ResponseEntity<JsonNode> pack =
                        api.post("/products/" + item + "/units", Map.of("unit_id", carton, "factor", "24"), ADMIN);
                assertThat(pack.getStatusCode()).as("%s", pack.getBody()).isEqualTo(HttpStatus.CREATED);
                bulb = item;
                restocked("1", 18_000, null, false);
                CountDownLatch go = new CountDownLatch(1);
                Future<ResponseEntity<JsonNode>> a = pool.submit(() -> {
                    go.await();
                    return restock(restockLine("1", 24_000, "30", true), ADMIN);
                });
                Future<ResponseEntity<JsonNode>> b = pool.submit(() -> {
                    go.await();
                    return restock(restockLine("2", 20_000, "20", true), ADMIN);
                });
                go.countDown();
                ResponseEntity<JsonNode> first = a.get(60, TimeUnit.SECONDS);
                ResponseEntity<JsonNode> second = b.get(60, TimeUnit.SECONDS);
                assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
                assertThat(second.getStatusCode()).as("%s", second.getBody()).isEqualTo(HttpStatus.CREATED);
                // 24 at 750, 30 at 800 and 40 at 1,000, in either order: 872 a piece over 94.
                assertThat(stockAt(hq, item)).isEqualByComparingTo("94");
                assertThat(average(hq)).isEqualTo(872);
                // Each delivery changed the usual size once, one after the other under the product's lock.
                assertThat(usual()).isIn("30.000", "20.000");
                assertThat(audits(first.getBody().get("id").asString())
                                + audits(second.getBody().get("id").asString()))
                        .isEqualTo(2);
                assertThat(scalar(
                                "SELECT version FROM retail_product_units WHERE product_id = ? AND unit_id = ?",
                                item,
                                carton))
                        .isEqualTo(3);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(balance("inventory", hq))
                .isEqualTo(scalar(
                        "SELECT coalesce(sum(round(qty * avg_cost_minor)), 0)::bigint FROM retail_stock_balances WHERE branch_id = ?",
                        hq));
        assertBooksBalance();
    }

    /**
     * Review of #555: the exact conversion and the size of the columns are judged with the delivery's own size. A non-integer
     * size with a quantity that converts exactly is taken; one that does not, or that comes to more than the columns hold, is
     * a 422 in plain words and writes nothing, on Buy stock and on goods received.
     */
    @Test
    void aDeliverysSizeIsCheckedForExactAndTooLargeQuantities() {
        // 0.5 of a carton of 24.5 is 12.25 pieces: exact, taken.
        JsonNode half = restocked("0.5", 12_250, "24.5", false);
        assertThat(half.get("lines").get(0).get("qty_total").asString()).isEqualTo("12.250");
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("12.25");

        // 0.333 of a carton of 30.5 is 10.1565: not exact in thousandths.
        ResponseEntity<JsonNode> inexact = restock(restockLine("0.333", 30_500, "30.5", false), ADMIN);
        assertThat(inexact.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(inexact)).isEqualTo("quantity_not_representable");
        Map<String, Object> blindInexact = receiptLine(null, "0.333", 30_500, "30.5");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supplier_id", supplier);
        b.put("branch_id", hq);
        b.put("lines", List.of(blindInexact));
        ResponseEntity<JsonNode> received = keyed("/goods-receipts", b, BUYER);
        assertThat(received.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(received)).isEqualTo("quantity_not_representable");

        // Two cartons of 99,999,999,999 is more than the quantity columns hold.
        ResponseEntity<JsonNode> huge = restock(restockLine("2", 0, "99999999999", false), ADMIN);
        assertThat(huge.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(huge)).isEqualTo("quantity_out_of_range");
        assertThat(huge.getBody().get("detail").asString()).contains("more than can be recorded");
        b.put("lines", List.of(receiptLine(null, "2", 0, "99999999999")));
        ResponseEntity<JsonNode> hugeReceipt = keyed("/goods-receipts", b, BUYER);
        assertThat(hugeReceipt.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(hugeReceipt)).isEqualTo("quantity_out_of_range");

        // Each branch fits on its own, the line's total does not.
        Map<String, Object> twoBranches = restockLine("1", 0, "60000000000", false);
        twoBranches.put(
                "qty_by_branch", List.of(Map.of("branch_id", hq, "qty", "1"), Map.of("branch_id", br2, "qty", "1")));
        ResponseEntity<JsonNode> summed = restock(twoBranches, ADMIN);
        assertThat(summed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(summed)).isEqualTo("quantity_out_of_range");

        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("12.25");
        assertThat(stockAt(br2, bulb)).isEqualByComparingTo("0");
        assertThat(usual()).isEqualTo("24.000");
        assertBooksBalance();
    }

    /** Review of #555: two items in one delivery, each with its own size and Use from now on, each changes its own pack. */
    @Test
    void twoItemsInOneDeliveryEachChangeTheirOwnUsualSize() {
        UUID lamp = api.product("PS-LAMP", 900, 1_500);
        ResponseEntity<JsonNode> pack =
                api.post("/products/" + lamp + "/units", Map.of("unit_id", carton, "factor", "12"), ADMIN);
        assertThat(pack.getStatusCode()).as("%s", pack.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> lampLine = restockLine("1", 9_000, "10", true);
        lampLine.put("product_id", lamp);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", TODAY);
        body.put("payment_method", "cash");
        // The bulb's line ticks nothing; the lamp's, second, ticks Use 10 from now on.
        body.put("lines", List.of(restockLine("1", 24_000, "30", false), lampLine));
        ResponseEntity<JsonNode> r = api.postKeyed("/purchases", body, ADMIN, "*", key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(usual()).isEqualTo("24.000");
        assertThat(api.get("/products/" + lamp + "/units", ADMIN)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("factor")
                        .asString())
                .isEqualTo("10.000");
        assertThat(stockAt(hq, bulb)).isEqualByComparingTo("30");
        assertThat(stockAt(hq, lamp)).isEqualByComparingTo("10");
        assertThat(audits(r.getBody().get("id").asString())).isEqualTo(1);
    }

    /**
     * ADR-082 decision 4: a delivery's Use from now on and a pack edit on the Items screen at once. Both take the product's row
     * lock, so the delivery always lands, the edit lands or is told the pack changed (409), and the usual size is the one
     * written last.
     */
    @Test
    void useFromNowOnRacingAPackEditNeverLosesEither() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                String etag = api.get("/products/" + bulb + "/units", ADMIN)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("version")
                        .asString();
                String usualBefore = usual();
                CountDownLatch go = new CountDownLatch(1);
                Future<ResponseEntity<JsonNode>> delivery = pool.submit(() -> {
                    go.await();
                    return restock(restockLine("1", 24_000, "30".equals(trim(usualBefore)) ? "20" : "30", true), ADMIN);
                });
                Future<ResponseEntity<JsonNode>> edit = pool.submit(() -> {
                    go.await();
                    return api.call(
                            org.springframework.http.HttpMethod.PATCH,
                            "/products/" + bulb + "/units/" + carton,
                            Map.of("factor", "36"),
                            ADMIN,
                            "*",
                            Map.of("If-Match", "\"" + etag + "\""));
                });
                go.countDown();
                ResponseEntity<JsonNode> d = delivery.get(60, TimeUnit.SECONDS);
                ResponseEntity<JsonNode> e = edit.get(60, TimeUnit.SECONDS);
                assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.CREATED);
                assertThat(e.getStatusCode()).as("%s", e.getBody()).isIn(HttpStatus.OK, HttpStatus.CONFLICT);
                String delivered =
                        d.getBody().get("lines").get(0).get("unit_factor").asString();
                long version = scalar(
                        "SELECT version FROM retail_product_units WHERE product_id = ? AND unit_id = ?", bulb, carton);
                if (e.getStatusCode() == HttpStatus.OK) {
                    // The edit read the old version, so it went first and the delivery's change came after it.
                    assertThat(usual()).isEqualTo(delivered);
                    assertThat(version).isEqualTo(Long.parseLong(etag) + 2);
                } else {
                    assertThat(usual()).isEqualTo(delivered);
                    assertThat(version).isEqualTo(Long.parseLong(etag) + 1);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertBooksBalance();
    }

    static String trim(String factor) {
        return new java.math.BigDecimal(factor).stripTrailingZeros().toPlainString();
    }
}
