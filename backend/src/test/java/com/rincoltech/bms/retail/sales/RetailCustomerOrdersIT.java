package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * Customer orders with delivery (issue #385, ADR-052): each step and its history, the stock kept aside and given back, two
 * orders for the last unit, the till refusing a unit kept for an order, delivery becoming an ordinary sale (paid in full, part
 * payment, all on credit) with the delivery fee posted on its own, cancellation with a reason, the source and the reports, the
 * quick customer lookup by phone, idempotency, and the permissions and branch scope. Names, phones and amounts are fabricated.
 */
class RetailCustomerOrdersIT extends IntegrationTest {

    static final String ORDERS = ADMIN + ",retail.order.manage";
    /** Takes and moves orders, but may not record a sale. */
    static final String ORDERS_ONLY = "retail.order.manage,retail.stock.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;
    UUID other;
    UUID hq;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("orders", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        product = api.product("ORD-ROMPER", 6_000, 10_000);
        other = api.product("ORD-BIB", 1_000, 2_500);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    static Map<String, Object> line(UUID product, String qty) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("qty", qty);
        return l;
    }

    Map<String, Object> body(UUID branch, String source, long fee, List<Map<String, Object>> lines) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", branch);
        b.put("source", source);
        b.put("customer_name", "Test Customer 01");
        b.put("customer_phone", "+256700000011");
        b.put("delivery_place", "Test Place, Plot 1");
        b.put("delivery_date", today().toString());
        b.put("delivery_fee_minor", fee);
        b.put("lines", lines);
        return b;
    }

    LocalDate today() {
        return LocalDate.now(ZoneId.of("Africa/Kampala"));
    }

    ResponseEntity<JsonNode> order(Map<String, Object> body) {
        return api.postKeyed("/orders", body, ORDERS, "*", UUID.randomUUID().toString());
    }

    UUID placed(String qty, long fee) {
        ResponseEntity<JsonNode> r = order(body(hq, "whatsapp", fee, List.of(line(product, qty))));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    ResponseEntity<JsonNode> deliver(UUID order, Map<String, Object> body) {
        return api.postKeyed(
                "/orders/" + order + "/deliver",
                body,
                ORDERS,
                "*",
                UUID.randomUUID().toString());
    }

    BigDecimal[] balance(UUID branch, UUID item) {
        return TestDatabase.owner()
                .sql("""
                        SELECT qty, reserved_qty FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?
                        """)
                .params(t.tenantId(), branch, item)
                .query((rs, n) -> new BigDecimal[] {rs.getBigDecimal("qty"), rs.getBigDecimal("reserved_qty")})
                .single();
    }

    long account(String systemKey, boolean debit) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(%s), 0) FROM journal_lines l JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = ?
                        """.formatted(debit ? "l.debit - l.credit" : "l.credit - l.debit"))
                .params(t.tenantId(), systemKey)
                .query(Long.class)
                .single();
    }

    /** reserved_qty of every balance row equals the base quantities of the open orders of that branch and product. */
    void assertKeptMatchesOpenOrders() {
        long wrong = TestDatabase.owner()
                .sql("""
                        SELECT count(*) FROM retail_stock_balances b
                          LEFT JOIN (SELECT o.branch_id, l.product_id, sum(l.base_qty) AS q
                                       FROM retail_customer_orders o JOIN retail_customer_order_lines l ON l.order_id = o.id
                                      WHERE o.tenant_id = ? AND o.status IN ('ordered', 'packed', 'out_for_delivery')
                                      GROUP BY 1, 2) k ON k.branch_id = b.branch_id AND k.product_id = b.product_id
                         WHERE b.tenant_id = ? AND b.reserved_qty <> coalesce(k.q, 0)
                        """)
                .params(t.tenantId(), t.tenantId())
                .query(Long.class)
                .single();
        assertThat(wrong)
                .as("balance rows whose kept quantity differs from the open orders")
                .isZero();
    }

    /** The legs of an order's delivery fee entry, as account:D or C:amount, debits first. */
    List<String> feeLegs(UUID order) {
        return TestDatabase.owner().sql("""
                        SELECT a.system_key || ':' || CASE WHEN l.debit > 0 THEN 'D:' || l.debit ELSE 'C:' || l.credit END
                          FROM retail_customer_orders o JOIN journal_lines l ON l.entry_id = o.fee_entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE o.id = ?
                         ORDER BY l.debit DESC, a.system_key
                        """).param(order).query(String.class).list();
    }

    void capability(String key) {
        String etag = api.get("/settings/capabilities", "core.settings.manage")
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of(key, true),
                "core.settings.manage",
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** Every debit (or every credit) posted to an account of the tenant. */
    long side(String systemKey, boolean debit) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(%s), 0) FROM journal_lines l JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = ?
                        """.formatted(debit ? "l.debit" : "l.credit"))
                .params(t.tenantId(), systemKey)
                .query(Long.class)
                .single();
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    // ---- the steps -----------------------------------------------------------------------------------

    /** FR-RET-53, FR-RET-55, FR-RET-56: ordered, packed, out for delivery, delivered and paid in cash; the history says who did what, when. */
    @Test
    void anOrderMovesThroughEveryStepAndBecomesASale() {
        api.stockUp(hq, product, "10");
        ResponseEntity<JsonNode> made = order(body(hq, "whatsapp", 5_000, List.of(line(product, "2"))));
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode o = made.getBody();
        UUID id = RetailTestSupport.id(made);
        assertThat(o.get("status").asString()).isEqualTo("ordered");
        assertThat(o.get("order_no").asString()).matches("OR\\d{6}");
        assertThat(o.get("source").asString()).isEqualTo("whatsapp");
        assertThat(o.get("items_total_minor").asLong()).isEqualTo(20_000);
        assertThat(o.get("delivery_fee_minor").asLong()).isEqualTo(5_000);
        assertThat(o.get("total_minor").asLong()).isEqualTo(25_000);
        assertThat(o.has("sale_id")).isFalse();
        // Nothing moved yet; two units are kept aside.
        assertThat(balance(hq, product)[0]).isEqualByComparingTo("10");
        assertThat(balance(hq, product)[1]).isEqualByComparingTo("2");

        ResponseEntity<JsonNode> packed = api.post("/orders/" + id + "/pack", Map.of("note", "In a blue bag"), ORDERS);
        assertThat(packed.getStatusCode()).as("%s", packed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(packed.getBody().get("status").asString()).isEqualTo("packed");
        assertKeptMatchesOpenOrders();
        // A retry of a step already done answers the order as it is, and writes nothing again.
        ResponseEntity<JsonNode> again = api.post("/orders/" + id + "/pack", Map.of(), ORDERS);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("status").asString()).isEqualTo("packed");

        ResponseEntity<JsonNode> noRider = api.post("/orders/" + id + "/send", Map.of(), ORDERS);
        assertThat(noRider.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> sent =
                api.post("/orders/" + id + "/send", Map.of("delivered_by", "Test Rider 01"), ORDERS);
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(sent.getBody().get("status").asString()).isEqualTo("out_for_delivery");
        assertThat(sent.getBody().get("delivered_by").asString()).isEqualTo("Test Rider 01");
        assertThat(api.post("/orders/" + id + "/send", Map.of("delivered_by", "Test Rider 01"), ORDERS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertKeptMatchesOpenOrders();

        long cashBefore = account("cash_on_hand", true);
        ResponseEntity<JsonNode> done = deliver(id, Map.of("payment_method", "cash"));
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("paid_now_minor").asLong()).isEqualTo(25_000);
        assertThat(done.getBody().get("owed_minor").asLong()).isZero();
        JsonNode after = done.getBody().get("order");
        assertThat(after.get("status").asString()).isEqualTo("delivered");
        assertThat(after.get("fee_method").asString()).isEqualTo("cash");
        UUID sale = UUID.fromString(done.getBody().get("sale_id").asString());
        assertThat(after.get("sale_no").asString())
                .isEqualTo(done.getBody().get("sale_no").asString());

        // An ordinary sale of the items: the fee is not in it, and stock left the shelf through the sale.
        JsonNode s = api.get("/sales/" + sale, ADMIN).getBody();
        assertThat(s.get("payment_method").asString()).isEqualTo("cash");
        assertThat(s.get("total_minor").asLong()).isEqualTo(20_000);
        assertThat(s.get("buyer_name").asString()).isEqualTo("Test Customer 01");
        assertThat(balance(hq, product)[0]).isEqualByComparingTo("8");
        assertThat(balance(hq, product)[1]).isEqualByComparingTo("0");
        // The fee is posted on its own: delivery fees income, and the cash of the items and the fee.
        assertThat(account("delivery_income", false)).isEqualTo(5_000);
        assertThat(account("cash_on_hand", true) - cashBefore).isEqualTo(25_000);
        assertThat(feeLegs(id)).containsExactly("cash_on_hand:D:5000", "delivery_income:C:5000");
        assertKeptMatchesOpenOrders();

        JsonNode history = api.get("/orders/" + id, ORDERS).getBody().get("history");
        List<String> steps = new ArrayList<>();
        history.forEach(e -> steps.add(e.get("step").asString()));
        assertThat(steps).containsExactly("ordered", "packed", "out_for_delivery", "delivered");
        assertThat(history.get(1).get("note").asString()).isEqualTo("In a blue bag");
        assertThat(history.get(2).get("note").asString()).contains("Test Rider 01");
        assertThat(history.get(3).get("note").asString())
                .contains(done.getBody().get("sale_no").asString());
        history.forEach(e ->
                assertThat(e.get("by_user").asString()).isEqualTo(api.userId().toString()));

        // A delivered order changes no more.
        assertThat(api.post("/orders/" + id + "/cancel", Map.of("reason", "Too late"), ORDERS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(deliver(id, Map.of("payment_method", "cash")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    /** A customer who collects at the shop: delivered straight from ordered, with no place and no fee. */
    @Test
    void anOrderCanBeCollectedWithoutPackingOrAFee() {
        api.stockUp(hq, product, "3");
        Map<String, Object> b = body(hq, "walk_in", 0, List.of(line(product, "1")));
        b.remove("delivery_place");
        ResponseEntity<JsonNode> made = order(b);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(made.getBody().has("delivery_place")).isFalse();
        ResponseEntity<JsonNode> done = deliver(RetailTestSupport.id(made), Map.of("payment_method", "mobile_money"));
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("order").has("fee_method")).isFalse();
        assertThat(account("delivery_income", false)).isZero();
    }

    /** The delivery details and the fee change while the order is open, under If-Match, and the history says so. */
    @Test
    void theDeliveryDetailsChangeUnderIfMatch() {
        api.stockUp(hq, product, "3");
        UUID id = placed("1", 3_000);
        ResponseEntity<JsonNode> read = api.get("/orders/" + id, ORDERS);
        String etag = read.getHeaders().getETag();
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("delivery_fee_minor", 4_000);
        change.put("delivery_place", "Test Place, Plot 2");
        ResponseEntity<JsonNode> changed =
                api.call(HttpMethod.PATCH, "/orders/" + id, change, ORDERS, "*", Map.of("If-Match", etag));
        assertThat(changed.getStatusCode()).as("%s", changed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().get("delivery_fee_minor").asLong()).isEqualTo(4_000);
        assertThat(changed.getBody().get("total_minor").asLong()).isEqualTo(14_000);
        ResponseEntity<JsonNode> stale =
                api.call(HttpMethod.PATCH, "/orders/" + id, change, ORDERS, "*", Map.of("If-Match", etag));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode history = changed.getBody().get("history");
        assertThat(history.get(history.size() - 1).get("step").asString()).isEqualTo("changed");
        assertThat(history.get(history.size() - 1).get("note").asString()).contains("delivery fee");
    }

    // ---- stock kept aside ------------------------------------------------------------------------------

    /** FR-RET-54: a unit kept for an order is not sold at the till; what is free still is; a cancel gives it back. */
    @Test
    void theTillCannotSellAUnitKeptForAnOrder() {
        api.stockUp(hq, product, "3");
        UUID id = placed("2", 0);
        ResponseEntity<JsonNode> refused = api.sell(hq, "cash", product, "2");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("insufficient_stock");
        assertThat(refused.getBody().get("detail").asString()).contains("kept for customer orders");
        assertThat(api.sell(hq, "cash", product, "1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Another order cannot keep what the first one keeps.
        ResponseEntity<JsonNode> second = order(body(hq, "phone", 0, List.of(line(product, "1"))));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(second)).isEqualTo("insufficient_stock");

        ResponseEntity<JsonNode> cancelled =
                api.post("/orders/" + id + "/cancel", Map.of("reason", "Customer changed her mind"), ORDERS);
        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status").asString()).isEqualTo("cancelled");
        assertThat(cancelled.getBody().get("cancel_reason").asString()).isEqualTo("Customer changed her mind");
        assertThat(balance(hq, product)[0]).isEqualByComparingTo("2");
        assertThat(balance(hq, product)[1]).isEqualByComparingTo("0");
        assertThat(api.sell(hq, "cash", product, "2").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** FR-RET-54: two orders for the last unit at the same moment: one keeps it, the other is refused, and one unit is kept. */
    @Test
    void twoOrdersForTheLastUnitAtOnceKeepItOnce() throws Exception {
        api.stockUp(hq, product, "1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<JsonNode>>> both = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                both.add(pool.submit(() -> {
                    go.await();
                    return order(body(hq, "instagram", 0, List.of(line(product, "1"))));
                }));
            }
            go.countDown();
            List<HttpStatus> codes = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> f : both) {
                codes.add(HttpStatus.valueOf(
                        f.get(60, TimeUnit.SECONDS).getStatusCode().value()));
            }
            assertThat(codes).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.UNPROCESSABLE_CONTENT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(balance(hq, product)[1]).isEqualByComparingTo("1");
    }

    /** FR-RET-54: an order and a till sale of the last unit at the same moment: exactly one of them gets it. */
    @Test
    void anOrderAndATillSaleOfTheLastUnitAtOnceTakeItOnce() throws Exception {
        api.stockUp(hq, product, "1");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<ResponseEntity<JsonNode>> ordered = pool.submit(() -> {
                go.await();
                return order(body(hq, "facebook", 0, List.of(line(product, "1"))));
            });
            Future<ResponseEntity<JsonNode>> sold = pool.submit(() -> {
                go.await();
                return api.sell(hq, "cash", product, "1");
            });
            go.countDown();
            boolean orderWon = ordered.get(60, TimeUnit.SECONDS).getStatusCode().equals(HttpStatus.CREATED);
            boolean saleWon = sold.get(60, TimeUnit.SECONDS).getStatusCode().equals(HttpStatus.CREATED);
            assertThat(orderWon ^ saleWon).as("exactly one takes the last unit").isTrue();
        } finally {
            pool.shutdownNow();
        }
        BigDecimal[] b = balance(hq, product);
        assertThat(b[0].subtract(b[1])).isEqualByComparingTo("0");
    }

    // ---- delivery and payment -----------------------------------------------------------------------

    /** FR-RET-55: part payment, the fee first, the rest of the money on the credit sale, the remainder owed. */
    @Test
    void aPartPaymentPaysTheFeeFirstAndLeavesTheRestOnCredit() {
        api.stockUp(hq, product, "5");
        UUID id = placed("2", 5_000);
        long momoBefore = account("mobile_money", true);
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("payment_method", "credit");
        pay.put("paid_now_minor", 8_000);
        pay.put("paid_now_method", "mobile_money");
        pay.put("due_date", today().plusDays(7).toString());
        ResponseEntity<JsonNode> done = deliver(id, pay);
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("paid_now_minor").asLong()).isEqualTo(8_000);
        assertThat(done.getBody().get("owed_minor").asLong()).isEqualTo(17_000);
        JsonNode s = api.get("/sales/" + done.getBody().get("sale_id").asString(), ADMIN)
                .getBody();
        assertThat(s.get("payment_method").asString()).isEqualTo("credit");
        assertThat(s.get("total_minor").asLong()).isEqualTo(20_000);
        assertThat(s.get("paid_minor").asLong()).isEqualTo(3_000);
        assertThat(s.get("balance_minor").asLong()).isEqualTo(17_000);
        assertThat(s.get("due_date").asString()).isEqualTo(today().plusDays(7).toString());
        assertThat(account("mobile_money", true) - momoBefore).isEqualTo(8_000);
        assertThat(account("delivery_income", false)).isEqualTo(5_000);
        assertThat(account("trade_debtors", true)).isEqualTo(17_000);
    }

    /** FR-RET-55: all on credit with no fee; a fee is never put on credit; everything paid is not credit. */
    @Test
    void creditTakesAtLeastTheFeeAndNeverEverything() {
        api.stockUp(hq, product, "5");
        UUID withFee = placed("1", 2_000);
        ResponseEntity<JsonNode> noFeePaid = deliver(withFee, Map.of("payment_method", "credit"));
        assertThat(noFeePaid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(noFeePaid)).isEqualTo("delivery_fee_unpaid");
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("payment_method", "credit");
        all.put("paid_now_minor", 12_000);
        all.put("paid_now_method", "cash");
        ResponseEntity<JsonNode> everything = deliver(withFee, all);
        assertThat(everything.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(everything)).isEqualTo("paid_in_full");
        // The refusals changed nothing: the order is open and still keeps its unit.
        assertThat(api.get("/orders/" + withFee, ORDERS).getBody().get("status").asString())
                .isEqualTo("ordered");
        assertThat(balance(hq, product)[1]).isEqualByComparingTo("1");

        UUID noFee = placed("1", 0);
        ResponseEntity<JsonNode> done = deliver(noFee, Map.of("payment_method", "credit"));
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("owed_minor").asLong()).isEqualTo(10_000);
        JsonNode s = api.get("/sales/" + done.getBody().get("sale_id").asString(), ADMIN)
                .getBody();
        assertThat(s.get("payment_method").asString()).isEqualTo("credit");
        assertThat(s.get("paid_minor").asLong()).isZero();
    }

    /** The same key replays the delivery: one sale, one fee. */
    @Test
    void aRepeatedDeliveryWithTheSameKeyMakesOneSale() {
        api.stockUp(hq, product, "5");
        UUID id = placed("1", 1_000);
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first =
                api.postKeyed("/orders/" + id + "/deliver", Map.of("payment_method", "cash"), ORDERS, "*", key);
        ResponseEntity<JsonNode> second =
                api.postKeyed("/orders/" + id + "/deliver", Map.of("payment_method", "cash"), ORDERS, "*", key);
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("sale_id").asString())
                .isEqualTo(first.getBody().get("sale_id").asString());
        assertThat(account("delivery_income", false)).isEqualTo(1_000);
        assertThat(balance(hq, product)[0]).isEqualByComparingTo("4");
    }

    /** The order's sale is an ordinary sale: it is voided like any other and its stock comes back. */
    @Test
    void theSaleOfADeliveredOrderVoidsLikeAnyOther() {
        api.stockUp(hq, product, "5");
        UUID id = placed("2", 0);
        ResponseEntity<JsonNode> done = deliver(id, Map.of("payment_method", "cash"));
        String sale = done.getBody().get("sale_id").asString();
        ResponseEntity<JsonNode> voided =
                api.post("/sales/" + sale + "/void", Map.of("reason", "Test void reason"), ADMIN);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(balance(hq, product)[0]).isEqualByComparingTo("5");
    }

    // ---- lists, lookup and reports -------------------------------------------------------------------

    /** FR-RET-56: the list by step and today's deliveries, the board's counts, and the search by name, number or phone. */
    @Test
    void theListAndTheBoardShowEachStepAndTodaysDeliveries() {
        api.stockUp(hq, product, "10");
        UUID a = placed("1", 0);
        UUID b = placed("1", 0);
        Map<String, Object> later = body(hq, "phone", 0, List.of(line(product, "1")));
        later.put("delivery_date", today().plusDays(2).toString());
        later.put("customer_name", "Test Customer 02");
        later.put("customer_phone", "0700000022");
        UUID c = RetailTestSupport.id(order(later));
        api.post("/orders/" + b + "/pack", Map.of(), ORDERS);
        deliver(a, Map.of("payment_method", "cash"));

        JsonNode board = api.get("/orders/board", ORDERS).getBody();
        assertThat(board.get("ordered").asLong()).isEqualTo(1);
        assertThat(board.get("packed").asLong()).isEqualTo(1);
        assertThat(board.get("due_today").asLong()).isEqualTo(1);
        assertThat(board.get("delivered_today").asLong()).isEqualTo(1);

        assertThat(ids(api.get("/orders?status=open", ORDERS))).containsExactlyInAnyOrder(b, c);
        assertThat(ids(api.get("/orders?status=packed", ORDERS))).containsExactly(b);
        assertThat(ids(api.get("/orders?due=today", ORDERS))).containsExactly(b);
        assertThat(ids(api.get("/orders?source=phone", ORDERS))).containsExactly(c);
        String number =
                api.get("/orders/" + c, ORDERS).getBody().get("order_no").asString();
        assertThat(ids(api.get("/orders?query=" + number, ORDERS))).containsExactly(c);
        assertThat(ids(api.get("/orders?query=0700000022", ORDERS))).containsExactly(c);
        assertThat(api.get("/orders?status=lost", ORDERS).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    List<UUID> ids(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        List<UUID> out = new ArrayList<>();
        r.getBody()
                .get("items")
                .forEach(i -> out.add(UUID.fromString(i.get("id").asString())));
        return out;
    }

    /** FR-RET-56: an order copied from a chat: the phone finds the last order's name, place and fee, in any of its usual forms. */
    @Test
    void thePhoneFindsTheCustomerOfTheLastOrder() {
        api.stockUp(hq, product, "5");
        placed("1", 4_000);
        JsonNode found =
                api.get("/orders/customer-lookup?phone=0700000011", ORDERS).getBody();
        assertThat(found.get("found").asBoolean()).isTrue();
        assertThat(found.get("customer_name").asString()).isEqualTo("Test Customer 01");
        assertThat(found.get("delivery_place").asString()).isEqualTo("Test Place, Plot 1");
        assertThat(found.get("delivery_fee_minor").asLong()).isEqualTo(4_000);
        assertThat(found.get("orders").asLong()).isEqualTo(1);
        assertThat(api.get("/orders/customer-lookup?phone=0700000099", ORDERS)
                        .getBody()
                        .get("found")
                        .asBoolean())
                .isFalse();
        // A credit buyer on the list with that number is offered too.
        UUID buyer = RetailTestSupport.id(
                api.post("/customers", Map.of("name", "Test Buyer 03", "contact", "+256 700 000 033"), ADMIN));
        JsonNode credit =
                api.get("/orders/customer-lookup?phone=0700000033", ORDERS).getBody();
        assertThat(credit.get("found").asBoolean()).isTrue();
        assertThat(credit.get("customer_id").asString()).isEqualTo(buyer.toString());
        assertThat(credit.get("customer_name").asString()).isEqualTo("Test Buyer 03");
        assertThat(api.get("/orders/customer-lookup?phone=12", ORDERS).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** FR-RET-57: delivery fees by source and by rider, and the cancelled orders with their reasons. */
    @Test
    void theReportsGroupFeesAndCancellationsBySource() {
        api.stockUp(hq, product, "10");
        UUID a = placed("1", 3_000);
        api.post("/orders/" + a + "/send", Map.of("delivered_by", "Test Rider 02"), ORDERS);
        deliver(a, Map.of("payment_method", "cash"));
        api.stockUp(hq, other, "4");
        UUID b = RetailTestSupport.id(order(body(hq, "instagram", 2_000, List.of(line(other, "1")))));
        deliver(b, Map.of("payment_method", "bank"));
        UUID c = placed("1", 1_000);
        api.post("/orders/" + c + "/cancel", Map.of("reason", "No answer on the phone"), ORDERS);

        JsonNode bySource =
                api.get("/orders/reports/delivery-fees?group_by=source", ORDERS).getBody();
        assertThat(bySource.get("fees_minor").asLong()).isEqualTo(5_000);
        assertThat(bySource.get("orders").asLong()).isEqualTo(2);
        Map<String, Long> fees = new LinkedHashMap<>();
        bySource.get("rows")
                .forEach(r ->
                        fees.put(r.get("label").asString(), r.get("fees_minor").asLong()));
        assertThat(fees).containsEntry("WhatsApp", 3_000L).containsEntry("Instagram", 2_000L);
        JsonNode byRider =
                api.get("/orders/reports/delivery-fees?group_by=rider", ORDERS).getBody();
        List<String> riders = new ArrayList<>();
        byRider.get("rows").forEach(r -> riders.add(r.get("label").asString()));
        assertThat(riders).containsExactlyInAnyOrder("Test Rider 02", "Collected at the shop");

        JsonNode cancelled = api.get("/orders/reports/cancellations", ORDERS).getBody();
        assertThat(cancelled.get("orders").asLong()).isEqualTo(1);
        assertThat(cancelled.get("items").get(0).get("reason").asString()).isEqualTo("No answer on the phone");
        assertThat(cancelled.get("by_source").get(0).get("label").asString()).isEqualTo("WhatsApp");
    }

    /** FR-RET-55: a cash fee comes into the till on the day of the delivery, so the cash book agrees with the cash account. */
    @Test
    void aCashFeeCountsInTheDaysTakings() {
        api.stockUp(hq, product, "5");
        UUID id = placed("1", 3_000);
        deliver(id, Map.of("payment_method", "cash"));
        ResponseEntity<JsonNode> daily = api.get(
                "/reports/cash/daily?branch_id=" + hq + "&from=" + today() + "&to=" + today(),
                "retail.cashbook.read,retail.till.close");
        assertThat(daily.getStatusCode()).as("%s", daily.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode day = daily.getBody().get("items").get(0);
        assertThat(day.get("cash_takings_minor").asLong()).isEqualTo(13_000);
        // The ledger's cash on hand moved by the same amount, so nothing is left unexplained.
        JsonNode other = day.get("other_movements_minor");
        assertThat(other == null ? 0 : other.asLong()).isZero();
    }

    // ---- refusals, permissions and scope -------------------------------------------------------------

    /** A store does not sell, so it takes no order; an unknown source is refused. */
    @Test
    void aStoreTakesNoOrderAndTheSourceIsChecked() {
        TestDatabase.owner()
                .sql("UPDATE branches SET sells_goods = false WHERE id = ?")
                .param(t.secondBranch())
                .update();
        api.stockUp(t.secondBranch(), product, "5");
        ResponseEntity<JsonNode> store = order(body(t.secondBranch(), "whatsapp", 0, List.of(line(product, "1"))));
        assertThat(store.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(store)).isEqualTo("branch_does_not_sell");
        ResponseEntity<JsonNode> source = order(body(hq, "tiktok", 0, List.of(line(product, "1"))));
        assertThat(source.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** No order permission: 403. The order permission without the sale permission moves an order but cannot deliver it. */
    @Test
    void theOrderAndSalePermissionsAreBothChecked() {
        api.stockUp(hq, product, "5");
        ResponseEntity<JsonNode> none = api.postKeyed(
                "/orders",
                body(hq, "phone", 0, List.of(line(product, "1"))),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        UUID id = placed("1", 0);
        assertThat(api.get("/orders/" + id, ADMIN).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> packed =
                api.call(HttpMethod.POST, "/orders/" + id + "/pack", Map.of(), ORDERS_ONLY, "*", Map.of());
        assertThat(packed.getStatusCode()).as("%s", packed.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> refused = api.postKeyed(
                "/orders/" + id + "/deliver",
                Map.of("payment_method", "cash"),
                ORDERS_ONLY,
                "*",
                UUID.randomUUID().toString());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(balance(hq, product)[1]).isEqualByComparingTo("1");
    }

    /** A person whose role covers one branch sees and moves only that branch's orders. */
    @Test
    void anOrderOfAnotherBranchIsNotFound() {
        api.stockUp(hq, product, "5");
        UUID id = placed("1", 0);
        String second = t.secondBranch().toString();
        ResponseEntity<JsonNode> read = api.call(HttpMethod.GET, "/orders/" + id, null, ORDERS, second, Map.of());
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<JsonNode> cancel = api.call(
                HttpMethod.POST, "/orders/" + id + "/cancel", Map.of("reason", "Not mine"), ORDERS, second, Map.of());
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<JsonNode> list = api.call(HttpMethod.GET, "/orders", null, ORDERS, second, Map.of());
        assertThat(list.getBody().get("items")).isEmpty();
    }

    /** An order is taken once per key: a double tap on Save keeps the stock once. */
    @Test
    void aRepeatedOrderWithTheSameKeyIsTakenOnce() {
        api.stockUp(hq, product, "5");
        String key = UUID.randomUUID().toString();
        Map<String, Object> b = body(hq, "whatsapp", 0, List.of(line(product, "2")));
        ResponseEntity<JsonNode> first = api.postKeyed("/orders", b, ORDERS, "*", key);
        ResponseEntity<JsonNode> second = api.postKeyed("/orders", b, ORDERS, "*", key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(balance(hq, product)[1]).isEqualByComparingTo("2");
    }

    // ---- review fixes: voids, the agreed prices, branches, holds, fees on account -----------------------

    /** FR-RET-55: voiding the sale of a delivered order reverses its fee too, and the cash book takes the fee out that day. */
    @Test
    void voidingTheSaleOfADeliveredOrderReversesItsDeliveryFee() {
        api.stockUp(hq, product, "5");
        UUID id = placed("1", 3_000);
        String sale = deliver(id, Map.of("payment_method", "cash"))
                .getBody()
                .get("sale_id")
                .asString();
        assertThat(account("delivery_income", false)).isEqualTo(3_000);
        ResponseEntity<JsonNode> voided =
                api.post("/sales/" + sale + "/void", Map.of("reason", "Test void reason"), ADMIN);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(account("delivery_income", false)).isZero();
        JsonNode day = api.get(
                        "/reports/cash/daily?branch_id=" + hq + "&from=" + today() + "&to=" + today(),
                        "retail.cashbook.read,retail.till.close")
                .getBody()
                .get("items")
                .get(0);
        assertThat(day.get("cash_takings_minor").asLong()).isEqualTo(13_000);
        assertThat(day.get("cash_sale_voids_minor").asLong()).isEqualTo(13_000);
        JsonNode history = api.get("/orders/" + id, ORDERS).getBody().get("history");
        assertThat(history.get(history.size() - 1).get("step").asString()).isEqualTo("sale_voided");
        assertThat(api.get("/orders/" + id, ORDERS).getBody().get("sale_voided").asBoolean())
                .isTrue();
        // The delivery fee report leaves the order out: its fee was reversed with the sale.
        JsonNode fees = api.get("/orders/reports/delivery-fees", ORDERS).getBody();
        assertThat(fees.get("orders").asLong()).isZero();
        assertThat(fees.get("fees_minor").asLong()).isZero();
    }

    /**
     * FR-RET-55: an order is delivered at the prices agreed even when the price list now gives another price, the cost rose
     * above the agreed price, and the person delivering may not change prices; the sale line says the price was agreed.
     */
    @Test
    void anOrderIsDeliveredAtTheAgreedPriceWhateverChangedSince() {
        api.stockUp(hq, product, "5");
        UUID id = placed("1", 0);
        capability("price_lists");
        api.editPrices(product, Map.of("cost_minor", 11_000, "sell_minor", 13_000, "reason", "Test new prices"), ADMIN);
        ResponseEntity<JsonNode> done = deliver(id, Map.of("payment_method", "cash"));
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode line = api.get("/sales/" + done.getBody().get("sale_id").asString(), ADMIN)
                .getBody()
                .get("lines")
                .get(0);
        assertThat(line.get("unit_price_minor").asLong()).isEqualTo(10_000);
        // Kept as the agreed price, not a new change of price (the Controls page does not count it).
        assertThat(line.get("price").get("source").asString()).isEqualTo("order_agreed");
        assertThat(line.get("price").get("override_reason").asString()).startsWith("Price agreed on order OR");
        // The till still refuses that price to a seller who may not change it.
        Map<String, Object> till = new LinkedHashMap<>();
        till.put("branch_id", hq);
        till.put("payment_method", "cash");
        till.put("lines", List.of(Map.of("product_id", product, "qty", "1", "unit_price_minor", 10_000)));
        assertThat(api.postKeyed("/sales", till, ORDERS, "*", UUID.randomUUID().toString())
                        .getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** FR-RET-55: an item switched off after the order was taken is still delivered; a new order for it is refused. */
    @Test
    void anItemSwitchedOffAfterTheOrderIsStillDelivered() {
        api.stockUp(hq, product, "5");
        UUID id = placed("2", 0);
        String etag = api.get("/products/" + product, ADMIN).getHeaders().getETag();
        assertThat(api.call(
                                HttpMethod.PATCH,
                                "/products/" + product,
                                Map.of("active", false),
                                ADMIN,
                                "*",
                                Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(order(body(hq, "phone", 0, List.of(line(product, "1")))).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> done = deliver(id, Map.of("payment_method", "cash"));
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(balance(hq, product)[0]).isEqualByComparingTo("3");
        assertKeptMatchesOpenOrders();
    }

    /** FR-RET-54: usage, damage and a transfer out may not take units kept for an order. */
    @Test
    void usageDamageAndTransfersCannotTakeUnitsKeptForAnOrder() {
        api.stockUp(hq, product, "3");
        placed("2", 0);
        for (String kind : List.of("used", "damaged")) {
            ResponseEntity<JsonNode> r = api.postKeyed(
                    "/usage",
                    Map.of(
                            "branch_id",
                            hq,
                            "kind",
                            kind,
                            "reason",
                            "Test reason " + kind,
                            "lines",
                            List.of(Map.of("product_id", product, "qty", "2"))),
                    ADMIN,
                    "*",
                    UUID.randomUUID().toString());
            assertThat(r.getStatusCode()).as("%s %s", kind, r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(code(r)).isEqualTo("insufficient_stock");
        }
        Map<String, Object> move = new LinkedHashMap<>();
        move.put("from_branch_id", hq);
        move.put("to_branch_id", t.secondBranch());
        move.put("lines", List.of(Map.of("product_id", product, "qty", "2")));
        ResponseEntity<JsonNode> moved =
                api.postKeyed("/transfers", move, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(moved)).isEqualTo("insufficient_stock");
        // The one free unit still moves.
        move.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        assertThat(api.postKeyed(
                                "/transfers",
                                move,
                                ADMIN,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertKeptMatchesOpenOrders();
    }

    /** FR-RET-54: a count below what is kept is recorded; the order then cannot be delivered until stock arrives. */
    @Test
    void aStockTakeBelowWhatIsKeptStopsTheDeliveryUntilStockArrives() {
        api.stockUp(hq, product, "3");
        UUID id = placed("2", 0);
        api.stockUp(hq, product, "1");
        ResponseEntity<JsonNode> refused = deliver(id, Map.of("payment_method", "cash"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("insufficient_stock");
        assertThat(api.get("/orders/" + id, ORDERS).getBody().get("status").asString())
                .isEqualTo("ordered");
        assertKeptMatchesOpenOrders();
        api.stockUp(hq, product, "4");
        assertThat(deliver(id, Map.of("payment_method", "cash")).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertKeptMatchesOpenOrders();
    }

    /** A branch with an open order is neither closed nor made a store; once the order is done it can be. */
    @Test
    void aBranchWithOpenOrdersIsNotClosed() {
        api.stockUp(t.secondBranch(), product, "3");
        UUID id = RetailTestSupport.id(order(body(t.secondBranch(), "phone", 0, List.of(line(product, "1")))));
        String perms = "core.branches.manage,core.branches.read";
        String etag = http.exchange(
                        "/api/v1/branches/" + t.secondBranch(),
                        HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(api.headers(perms, "*", Map.of())),
                        JsonNode.class)
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> closed = http.exchange(
                "/api/v1/branches/" + t.secondBranch() + "/close",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        Map.of("reason", "Test shop shut"), api.headers(perms, "*", Map.of("If-Match", etag))),
                JsonNode.class);
        assertThat(closed.getStatusCode()).as("%s", closed.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(closed)).isEqualTo("branch_has_open_orders");
        assertThat(closed.getBody().get("open_orders").asInt()).isEqualTo(1);
        api.post("/orders/" + id + "/cancel", Map.of("reason", "Test shop shutting"), ORDERS);
        ResponseEntity<JsonNode> again = http.exchange(
                "/api/v1/branches/" + t.secondBranch() + "/close",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        Map.of("reason", "Test shop shut"), api.headers(perms, "*", Map.of("If-Match", etag))),
                JsonNode.class);
        assertThat(again.getStatusCode()).as("%s", again.getBody()).isEqualTo(HttpStatus.OK);
    }

    /**
     * FR-RET-55: a credit buyer's delivery fee goes on the account (trade debtors, against the sale) and is paid later; the
     * credit limit counts what actually goes on credit; a sale whose fee was paid later is not voided here.
     */
    @Test
    void aCreditBuyerCanHaveTheFeeOnTheAccountAndPayItLater() {
        api.stockUp(hq, product, "5");
        capability("customer_accounts");
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 05"), ADMIN));
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/customers/" + buyer + "/account",
                                Map.of("credit_limit_minor", 15_000, "clear_terms_days", true),
                                ADMIN + ",retail.credit.manage",
                                "*",
                                Map.of("If-Match", "1"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> b = body(hq, "whatsapp", 5_000, List.of(line(product, "2")));
        b.put("customer_id", buyer);
        UUID id = RetailTestSupport.id(order(b));
        // 20000 of items and 5000 of fee, 8000 taken now: 17000 would go on credit, above the limit of 15000.
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("payment_method", "credit");
        pay.put("paid_now_minor", 8_000);
        pay.put("paid_now_method", "cash");
        ResponseEntity<JsonNode> over = deliver(id, pay);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(over)).isEqualTo("credit_limit_exceeded");
        // 10000 taken now pays the fee and 5000 of the items: 15000 on credit, at the limit.
        pay.put("paid_now_minor", 10_000);
        assertThat(deliver(id, pay).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(account("trade_debtors", true)).isEqualTo(15_000);

        // Another order with the fee on the account and nothing paid now.
        Map<String, Object> b2 = body(hq, "phone", 2_000, List.of(line(other, "1")));
        b2.put("customer_id", buyer);
        api.stockUp(hq, other, "3");
        UUID second = RetailTestSupport.id(order(b2));
        Map<String, Object> onAccount = new LinkedHashMap<>();
        onAccount.put("payment_method", "credit");
        onAccount.put("fee_on_account", true);
        onAccount.put("credit_override_reason", "Test regular customer");
        // 4500 more goes on credit (the item and the fee), above the limit: delivered by someone who may go above it.
        ResponseEntity<JsonNode> done = api.postKeyed(
                "/orders/" + second + "/deliver",
                onAccount,
                ORDERS + ",retail.credit.override",
                "*",
                UUID.randomUUID().toString());
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("order").get("fee_method").asString()).isEqualTo("account");
        assertThat(feeLegs(second)).containsExactly("trade_debtors:D:2000", "delivery_income:C:2000");
        assertThat(account("trade_debtors", true)).isEqualTo(15_000 + 2_500 + 2_000);

        ResponseEntity<JsonNode> paid = api.postKeyed(
                "/orders/" + second + "/fee-payment",
                Map.of("method", "cash"),
                ORDERS,
                "*",
                UUID.randomUUID().toString());
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(paid.getBody().get("fee_paid_method").asString()).isEqualTo("cash");
        assertThat(account("trade_debtors", true)).isEqualTo(15_000 + 2_500);
        assertThat(api.postKeyed(
                                "/orders/" + second + "/fee-payment",
                                Map.of("method", "cash"),
                                ORDERS,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<JsonNode> noVoid = api.post(
                "/sales/" + done.getBody().get("sale_id").asString() + "/void",
                Map.of("reason", "Test void reason"),
                ADMIN);
        assertThat(noVoid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(noVoid)).isEqualTo("order_fee_paid");
        // A cash customer's fee is still paid at the door.
        UUID third = placed("1", 1_000);
        Map<String, Object> cashCustomer = new LinkedHashMap<>();
        cashCustomer.put("payment_method", "credit");
        cashCustomer.put("fee_on_account", true);
        assertThat(code(deliver(third, cashCustomer))).isEqualTo("fee_on_account_not_allowed");
    }

    /** Minor rules: a delivery day can be cleared, and paid in full names no method for money taken now. */
    @Test
    void aDeliveryDayCanBeClearedAndPaidInFullNamesNoSecondMethod() {
        api.stockUp(hq, product, "3");
        UUID id = placed("1", 0);
        String etag = api.get("/orders/" + id, ORDERS).getHeaders().getETag();
        ResponseEntity<JsonNode> cleared = api.call(
                HttpMethod.PATCH,
                "/orders/" + id,
                Map.of("clear_delivery_date", true),
                ORDERS,
                "*",
                Map.of("If-Match", etag));
        assertThat(cleared.getStatusCode()).as("%s", cleared.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody().has("delivery_date")).isFalse();
        ResponseEntity<JsonNode> refused =
                deliver(id, Map.of("payment_method", "cash", "paid_now_method", "mobile_money"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("validation_failed");
        api.post("/orders/" + id + "/cancel", Map.of("reason", "Test changed mind"), ORDERS);
        ResponseEntity<JsonNode> late = api.call(
                HttpMethod.PATCH,
                "/orders/" + id,
                Map.of("notes", "x"),
                ORDERS,
                "*",
                Map.of("If-Match", api.get("/orders/" + id, ORDERS).getHeaders().getETag()));
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody().get("detail").asString()).endsWith("so it cannot be changed.");
        // Cancelling again answers the cancelled order.
        assertThat(api.post("/orders/" + id + "/cancel", Map.of("reason", "Test changed mind"), ORDERS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---- delta review: a fee on account is owed everywhere, prices agreed on the order and the Controls page -----

    UUID creditBuyer(String name) {
        capability("customer_accounts");
        return RetailTestSupport.id(api.post("/customers", Map.of("name", name), ADMIN));
    }

    ResponseEntity<JsonNode> payFee(UUID order, String key, String perms) {
        return api.postKeyed("/orders/" + order + "/fee-payment", Map.of("method", "cash"), perms, "*", key);
    }

    /**
     * FR-RET-55: items paid in full at the door and the fee on the account: the buyer owes the fee on the buyer list, the account,
     * the customer ageing and the statement; the shop cannot be made a store while it is owed; paid later in cash, it counts in the
     * cash book on the day it is paid; a replay of the payment with the same key pays once; a holder of the sale permission alone
     * collects it.
     */
    @Test
    void aFeeOnTheAccountIsOwedEverywhereUntilItIsPaid() {
        api.stockUp(hq, product, "5");
        UUID buyer = creditBuyer("Test Buyer 06");
        Map<String, Object> b = body(hq, "whatsapp", 2_000, List.of(line(product, "1")));
        b.put("customer_id", buyer);
        UUID id = RetailTestSupport.id(order(b));
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("payment_method", "credit");
        pay.put("fee_on_account", true);
        pay.put("paid_now_minor", 10_000);
        pay.put("paid_now_method", "cash");
        ResponseEntity<JsonNode> done = deliver(id, pay);
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("owed_minor").asLong()).isEqualTo(2_000);

        // The buyer list, the account and the ageing say the buyer owes the fee.
        JsonNode listed = null;
        for (JsonNode c : api.get("/customers", ADMIN).getBody().get("items")) {
            if (c.get("id").asString().equals(buyer.toString())) {
                listed = c;
            }
        }
        assertThat(listed).isNotNull();
        assertThat(listed.get("balance_minor").asLong()).isEqualTo(2_000);
        JsonNode owing = api.get("/customers/" + buyer + "/balance", ADMIN).getBody();
        assertThat(owing.get("balance_minor").asLong()).isZero();
        assertThat(owing.get("delivery_fees_owed_minor").asLong()).isEqualTo(2_000);
        JsonNode accountPage =
                api.get("/customers/" + buyer + "/account", ADMIN).getBody();
        assertThat(accountPage.get("delivery_fees").get(0).get("order_id").asString())
                .isEqualTo(id.toString());
        String ageing = api.get("/reports/customer-ageing", ADMIN + ",retail.credit.manage")
                .getBody()
                .toString();
        assertThat(ageing).contains("\"owed_minor\":2000");
        JsonNode statement =
                api.get("/customers/" + buyer + "/statement", ADMIN).getBody();
        List<String> kinds = new ArrayList<>();
        statement.get("lines").forEach(l -> kinds.add(l.get("kind").asString()));
        assertThat(kinds).contains("delivery_fee");
        assertThat(statement.get("closing_minor").asLong()).isEqualTo(2_000);

        // The shop is not made a store while the fee is owed there.
        String perms = "core.branches.manage,core.branches.read";
        String etag = http.exchange(
                        "/api/v1/branches/" + hq,
                        HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(api.headers(perms, "*", Map.of())),
                        JsonNode.class)
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> store = http.exchange(
                "/api/v1/branches/" + hq,
                HttpMethod.PATCH,
                new org.springframework.http.HttpEntity<>(
                        Map.of("sells_goods", false), api.headers(perms, "*", Map.of("If-Match", etag))),
                JsonNode.class);
        assertThat(store.getStatusCode()).as("%s", store.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(store)).isEqualTo("branch_has_open_credit_sales");

        // Paid later in cash, by someone who may take payments but not manage orders; a replay with the same key pays
        // once.
        String key = UUID.randomUUID().toString();
        String seller = "retail.sale.create,retail.sale.read";
        ResponseEntity<JsonNode> paid = payFee(id, key, seller);
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> replay = payFee(id, key, seller);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        // Paid once: the sale and the fee are both settled in trade debtors.
        assertThat(account("trade_debtors", true)).isZero();
        assertThat(side("trade_debtors", false)).isEqualTo(12_000);
        assertThat(api.get("/customers/" + buyer + "/balance", ADMIN).getBody().has("delivery_fees_owed_minor"))
                .isFalse();
        JsonNode day = api.get(
                        "/reports/cash/daily?branch_id=" + hq + "&from=" + today() + "&to=" + today(),
                        "retail.cashbook.read,retail.till.close")
                .getBody()
                .get("items")
                .get(0);
        assertThat(day.get("cash_takings_minor").asLong()).isEqualTo(12_000);
    }

    /** FR-RET-55: voiding the sale of an order whose fee is still on the account reverses the fee: Dr 4120, Cr trade debtors. */
    @Test
    void voidingASaleWithAnUnpaidFeeOnTheAccountReversesTheFee() {
        api.stockUp(hq, product, "5");
        UUID buyer = creditBuyer("Test Buyer 08");
        Map<String, Object> b = body(hq, "phone", 2_000, List.of(line(product, "1")));
        b.put("customer_id", buyer);
        UUID id = RetailTestSupport.id(order(b));
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("payment_method", "credit");
        pay.put("fee_on_account", true);
        String sale = deliver(id, pay).getBody().get("sale_id").asString();
        assertThat(account("trade_debtors", true)).isEqualTo(12_000);
        ResponseEntity<JsonNode> voided =
                api.post("/sales/" + sale + "/void", Map.of("reason", "Test void reason"), ADMIN);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(account("trade_debtors", true)).isZero();
        assertThat(account("delivery_income", false)).isZero();
        // The fee's reversal is the only debit of delivery fees and the only credit of trade debtors besides the sale's
        // void.
        assertThat(side("delivery_income", true)).isEqualTo(2_000);
        assertThat(side("trade_debtors", false)).isEqualTo(12_000);
        assertThat(api.get("/customers/" + buyer + "/balance", ADMIN).getBody().has("delivery_fees_owed_minor"))
                .isFalse();
        assertThat(payFee(id, UUID.randomUUID().toString(), ORDERS).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /**
     * FR-RET-53, FR-RET-55: a price changed when the order was taken is one changed price on the Controls page, for the person
     * who took the order and not for the one who delivered it; a price agreed on the order that today's list prices otherwise is
     * not counted at all.
     */
    @Test
    void aPriceChangedOnTheOrderCountsOnceForWhoeverChangedIt() {
        api.stockUp(hq, product, "5");
        capability("price_lists");
        Map<String, Object> changed = line(product, "1");
        changed.put("unit_price_minor", 9_000);
        changed.put("price_override_reason", "Test regular customer price");
        UUID a = RetailTestSupport.id(api.postKeyed(
                "/orders",
                body(hq, "whatsapp", 0, List.of(changed)),
                ORDERS + ",retail.price.override",
                "*",
                UUID.randomUUID().toString()));
        UUID b = placed("1", 0);
        api.editPrices(product, Map.of("cost_minor", 6_000, "sell_minor", 12_000, "reason", "Test new price"), ADMIN);
        RetailTestSupport deliverer = new RetailTestSupport(http, t);
        String saleA = deliverer
                .postKeyed(
                        "/orders/" + a + "/deliver",
                        Map.of("payment_method", "cash"),
                        ORDERS,
                        "*",
                        UUID.randomUUID().toString())
                .getBody()
                .get("sale_id")
                .asString();
        String saleB = deliverer
                .postKeyed(
                        "/orders/" + b + "/deliver",
                        Map.of("payment_method", "cash"),
                        ORDERS,
                        "*",
                        UUID.randomUUID().toString())
                .getBody()
                .get("sale_id")
                .asString();
        JsonNode lineA =
                api.get("/sales/" + saleA, ADMIN).getBody().get("lines").get(0).get("price");
        assertThat(lineA.get("source").asString()).isEqualTo("override");
        assertThat(lineA.get("override_reason").asString()).isEqualTo("Test regular customer price");
        JsonNode lineB =
                api.get("/sales/" + saleB, ADMIN).getBody().get("lines").get(0).get("price");
        assertThat(lineB.get("source").asString()).isEqualTo("order_agreed");
        String controls = ADMIN + ",retail.controls.read";
        assertThat(api.get("/controls/records?metric=price_overrides&user_id=" + api.userId(), controls)
                        .getBody()
                        .get("items"))
                .hasSize(1);
        assertThat(api.get("/controls/records?metric=price_overrides&user_id=" + deliverer.userId(), controls)
                        .getBody()
                        .get("items"))
                .isEmpty();
        assertThat(api.get("/controls/records?metric=price_overrides", controls)
                        .getBody()
                        .get("items"))
                .hasSize(1);
    }

    /**
     * FR-RET-53, FR-RET-54 with item variants (#384, ADR-051): an order line is a size and colour of an item (a variant is a
     * product), keeps that variant's stock aside and not the others', and is sold as that variant on delivery.
     */
    @Test
    void anOrderLineIsOneSizeAndColourOfAnItem() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "Test Romper");
        item.put("base_code", "ROMP");
        item.put("category_id", api.category("Test Baby Clothes"));
        item.put("unit_id", api.unit("u-romper"));
        item.put("cost_minor", 4_000);
        item.put("sell_minor", 9_000);
        item.put("sizes", List.of("0-3m", "3-6m"));
        item.put("colours", List.of("Blue", "Pink"));
        ResponseEntity<JsonNode> made = api.post("/product-families", item, ADMIN);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID blue = null;
        UUID pink = null;
        for (JsonNode v : made.getBody().get("variants")) {
            JsonNode of = v.get("variant");
            if (of.get("size").asString().equals("0-3m")
                    && of.get("colour").asString().equals("Blue")) {
                blue = UUID.fromString(v.get("id").asString());
            }
            if (of.get("size").asString().equals("3-6m")
                    && of.get("colour").asString().equals("Pink")) {
                pink = UUID.fromString(v.get("id").asString());
            }
        }
        api.stockUp(hq, blue, "2");
        api.stockUp(hq, pink, "2");
        ResponseEntity<JsonNode> ordered = order(body(hq, "instagram", 0, List.of(line(blue, "2"))));
        assertThat(ordered.getStatusCode()).as("%s", ordered.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(ordered.getBody().get("lines").get(0).get("description").asString())
                .isEqualTo("Test Romper (0-3m, Blue)");
        // The blue romper in 0-3m is kept for the order; the pink one in 3-6m is still free at the till.
        assertThat(balance(hq, blue)[1]).isEqualByComparingTo("2");
        assertThat(balance(hq, pink)[1]).isEqualByComparingTo("0");
        assertThat(api.sell(hq, "cash", blue, "1").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.sell(hq, "cash", pink, "1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> done = deliver(RetailTestSupport.id(ordered), Map.of("payment_method", "cash"));
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode sold = api.get("/sales/" + done.getBody().get("sale_id").asString(), ADMIN)
                .getBody()
                .get("lines")
                .get(0);
        assertThat(sold.get("product_id").asString()).isEqualTo(blue.toString());
        assertThat(sold.get("description").asString()).isEqualTo("Test Romper (0-3m, Blue)");
        assertThat(balance(hq, blue)[0]).isEqualByComparingTo("0");
        assertKeptMatchesOpenOrders();
    }

    /**
     * FR-RET-55: a payment on account for the balance the account page shows is taken, oldest first or on chosen sales: it pays
     * the sales; the fee on the account is shown apart and paid on its order, never taken by such a payment.
     */
    @Test
    void aPaymentOnAccountPaysTheSalesAndTheFeeIsPaidOnItsOrder() {
        api.stockUp(hq, product, "5");
        UUID buyer = creditBuyer("Test Buyer 09");
        UUID[] orders = new UUID[2];
        String[] sales = new String[2];
        for (int n = 0; n < 2; n++) {
            Map<String, Object> b = body(hq, "phone", 1_000, List.of(line(product, "1")));
            b.put("customer_id", buyer);
            orders[n] = RetailTestSupport.id(order(b));
            Map<String, Object> pay = new LinkedHashMap<>();
            pay.put("payment_method", "credit");
            pay.put("fee_on_account", true);
            sales[n] = deliver(orders[n], pay).getBody().get("sale_id").asString();
        }
        JsonNode account = api.get("/customers/" + buyer + "/account", ADMIN).getBody();
        assertThat(account.get("balance_minor").asLong()).isEqualTo(20_000);
        assertThat(account.get("delivery_fees_owed_minor").asLong()).isEqualTo(2_000);
        // Oldest first, the balance shown: taken.
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("branch_id", hq);
        first.put("amount_minor", 10_000);
        first.put("method", "cash");
        ResponseEntity<JsonNode> oldest = api.postKeyed(
                "/customers/" + buyer + "/payments",
                first,
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(oldest.getStatusCode()).as("%s", oldest.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(oldest.getBody().get("allocations").get(0).get("sale_id").asString())
                .isEqualTo(sales[0]);
        // On a chosen sale: taken.
        Map<String, Object> chosen = new LinkedHashMap<>(first);
        chosen.put("allocations", List.of(Map.of("sale_id", sales[1], "amount_minor", 10_000)));
        ResponseEntity<JsonNode> manual = api.postKeyed(
                "/customers/" + buyer + "/payments",
                chosen,
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(manual.getStatusCode()).as("%s", manual.getBody()).isEqualTo(HttpStatus.CREATED);
        // Nothing is left for a payment on account: the fees are not taken by one.
        ResponseEntity<JsonNode> more = api.postKeyed(
                "/customers/" + buyer + "/payments",
                Map.of("branch_id", hq, "amount_minor", 2_000, "method", "cash"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(more.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(more)).isEqualTo("payment_exceeds_allocatable");
        JsonNode after = api.get("/customers/" + buyer + "/account", ADMIN).getBody();
        assertThat(after.get("balance_minor").asLong()).isZero();
        assertThat(after.get("delivery_fees_owed_minor").asLong()).isEqualTo(2_000);
        for (UUID o : orders) {
            assertThat(payFee(o, UUID.randomUUID().toString(), ORDERS).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        assertThat(api.get("/customers/" + buyer + "/account", ADMIN).getBody().has("delivery_fees_owed_minor"))
                .isFalse();
        assertThat(account("trade_debtors", true)).isZero();
    }

    /**
     * FR-RET-55: writing off a credit sale writes off its fee on the account too (Dr bad debt loss, Cr trade debtors), the fee is
     * settled, and the shop can then be closed.
     */
    @Test
    void writingOffASaleWritesOffItsFeeAndFreesTheBranch() {
        UUID shop = t.secondBranch();
        api.stockUp(shop, product, "5");
        UUID buyer = creditBuyer("Test Buyer 10");
        Map<String, Object> b = body(shop, "phone", 2_000, List.of(line(product, "1")));
        b.put("customer_id", buyer);
        UUID id = RetailTestSupport.id(order(b));
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("payment_method", "credit");
        pay.put("fee_on_account", true);
        pay.put("paid_now_minor", 10_000);
        pay.put("paid_now_method", "cash");
        String sale = deliver(id, pay).getBody().get("sale_id").asString();
        assertThat(account("trade_debtors", true)).isEqualTo(2_000);

        String perms = "core.branches.manage,core.branches.read";
        java.util.function.Supplier<ResponseEntity<JsonNode>> close = () -> {
            String etag = http.exchange(
                            "/api/v1/branches/" + shop,
                            HttpMethod.GET,
                            new org.springframework.http.HttpEntity<>(api.headers(perms, "*", Map.of())),
                            JsonNode.class)
                    .getHeaders()
                    .getETag();
            return http.exchange(
                    "/api/v1/branches/" + shop + "/close",
                    HttpMethod.POST,
                    new org.springframework.http.HttpEntity<>(
                            Map.of("reason", "Test shop shut"), api.headers(perms, "*", Map.of("If-Match", etag))),
                    JsonNode.class);
        };
        ResponseEntity<JsonNode> refused = close.get();
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("branch_has_open_orders");

        ResponseEntity<JsonNode> written = api.postKeyed(
                "/sales/" + sale + "/write-off",
                Map.of("reason", "Test buyer left town"),
                ADMIN + ",retail.credit.write_off,retail.credit.manage",
                "*",
                UUID.randomUUID().toString());
        assertThat(written.getStatusCode()).as("%s", written.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(written.getBody().get("write_off").get("unpaid_minor").asLong())
                .isEqualTo(2_000);
        assertThat(account("trade_debtors", true)).isZero();
        assertThat(side("bad_debt_loss", true)).isEqualTo(2_000);
        assertThat(api.get("/customers/" + buyer + "/balance", ADMIN).getBody().has("delivery_fees_owed_minor"))
                .isFalse();
        JsonNode history = api.get("/orders/" + id, ORDERS).getBody().get("history");
        assertThat(history.get(history.size() - 1).get("note").asString()).contains("written off");
        assertThat(close.get().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    static final String WRITER = ADMIN + ",retail.credit.write_off,retail.credit.manage";

    /** A credit sale of one order with its fee of 2,000 on the account and nothing paid; the order and the sale. */
    UUID[] feeOnAccountSale(String buyerName) {
        api.stockUp(hq, product, "5");
        UUID buyer = creditBuyer(buyerName);
        Map<String, Object> b = body(hq, "phone", 2_000, List.of(line(product, "1")));
        b.put("customer_id", buyer);
        UUID order = RetailTestSupport.id(order(b));
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("payment_method", "credit");
        pay.put("fee_on_account", true);
        UUID sale = UUID.fromString(deliver(order, pay).getBody().get("sale_id").asString());
        return new UUID[] {order, sale, buyer};
    }

    ResponseEntity<JsonNode> writeOff(UUID sale) {
        return api.postKeyed(
                "/sales/" + sale + "/write-off",
                Map.of("reason", "Test buyer left town"),
                WRITER,
                "*",
                UUID.randomUUID().toString());
    }

    long todaysBadDebt() {
        JsonNode report = api.get("/reports/profit/daily?from=" + today() + "&to=" + today(), ADMIN)
                .getBody();
        return report.get("bad_debt_minor").asLong();
    }

    /**
     * FR-RET-55: the items and the fee are written off together: the sale shows the fee owed before and the fee written off
     * after, the statement has the sale, the fee and the write-off and closes at nothing, and the daily profit of goods counts
     * the items only as bad debt.
     */
    @Test
    void theItemsAndTheFeeAreWrittenOffTogether() {
        UUID[] ids = feeOnAccountSale("Test Buyer 11");
        JsonNode before = api.get("/sales/" + ids[1], ADMIN).getBody();
        assertThat(before.get("balance_minor").asLong()).isEqualTo(10_000);
        assertThat(before.get("delivery_fee_owed_minor").asLong()).isEqualTo(2_000);
        ResponseEntity<JsonNode> written = writeOff(ids[1]);
        assertThat(written.getStatusCode()).as("%s", written.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(written.getBody().get("write_off").get("unpaid_minor").asLong())
                .isEqualTo(12_000);
        JsonNode after = api.get("/sales/" + ids[1], ADMIN).getBody();
        assertThat(after.has("delivery_fee_owed_minor")).isFalse();
        assertThat(after.get("write_off").get("delivery_fee_minor").asLong()).isEqualTo(2_000);
        JsonNode statement =
                api.get("/customers/" + ids[2] + "/statement", ADMIN).getBody();
        List<String> kinds = new ArrayList<>();
        statement.get("lines").forEach(l -> kinds.add(l.get("kind").asString()));
        assertThat(kinds).contains("sale", "delivery_fee", "write_off").doesNotContain("delivery_fee_paid");
        assertThat(statement.get("closing_minor").asLong()).isZero();
        assertThat(account("trade_debtors", true)).isZero();
        assertThat(side("bad_debt_loss", true)).isEqualTo(12_000);
        assertThat(todaysBadDebt()).isEqualTo(10_000);
        JsonNode history = api.get("/orders/" + ids[0], ORDERS).getBody().get("history");
        assertThat(history.get(history.size() - 1).get("step").asString()).isEqualTo("fee_written_off");
    }

    /** FR-RET-55: under "counted when the buyer pays" the goods lose their cost share and the fee all of itself. */
    @Test
    void aFeeOnTheAccountOfASaleCountedWhenPaidIsWrittenOffWhole() {
        String etag = api.get("/settings/capabilities", "core.settings.manage")
                .getHeaders()
                .getETag();
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/settings/capabilities",
                                Map.of("credit_sale_recognition", "on_payment"),
                                "core.settings.manage",
                                "*",
                                etag == null ? Map.of() : Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = jsonb_set(settings,"
                        + " '{module_groups,retail,credit_recognition_effective_from}', '\"1970-01-01\"'::jsonb)"
                        + " WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        UUID[] ids = feeOnAccountSale("Test Buyer 12");
        ResponseEntity<JsonNode> written = writeOff(ids[1]);
        assertThat(written.getStatusCode()).as("%s", written.getBody()).isEqualTo(HttpStatus.CREATED);
        // The goods: their cost (6,000) is the loss and the 4,000 of profit never counted leaves sales revenue; the
        // fee: 2,000.
        assertThat(side("bad_debt_loss", true)).isEqualTo(8_000);
        assertThat(side("sales_revenue", true)).isEqualTo(4_000);
        assertThat(account("trade_debtors", true)).isZero();
        assertThat(todaysBadDebt()).isEqualTo(6_000);
    }

    /** FR-RET-55: a fee payment and a write-off at the same moment settle the fee once, whichever comes first. */
    @Test
    void aFeePaymentRacingAWriteOffSettlesTheFeeOnce() throws Exception {
        UUID[] ids = feeOnAccountSale("Test Buyer 13");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        ResponseEntity<JsonNode> paid;
        ResponseEntity<JsonNode> written;
        try {
            Future<ResponseEntity<JsonNode>> p = pool.submit(() -> {
                go.await();
                return payFee(ids[0], UUID.randomUUID().toString(), ORDERS);
            });
            Future<ResponseEntity<JsonNode>> w = pool.submit(() -> {
                go.await();
                return writeOff(ids[1]);
            });
            go.countDown();
            paid = p.get(60, TimeUnit.SECONDS);
            written = w.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(written.getStatusCode()).as("%s", written.getBody()).isEqualTo(HttpStatus.CREATED);
        long cleared = written.getBody().get("write_off").get("unpaid_minor").asLong();
        if (paid.getStatusCode().equals(HttpStatus.OK)) {
            assertThat(cleared).isEqualTo(10_000);
        } else {
            assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(cleared).isEqualTo(12_000);
        }
        assertThat(account("trade_debtors", true)).isZero();
    }

    /** A tenant administrator who counts for the second-person rule of a write-off (ADR-043). */
    void tenantAdmin(UUID user, int i) {
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', ?, ?, 'active')")
                .params(user, t.tenantId(), "Test Admin 0" + i, "test.admin" + i + "." + user + "@example.invalid")
                .update();
        TestDatabase.owner()
                .sql("INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, granted_by)"
                        + " VALUES (?, ?, ?, 'tenant_admin', ?)")
                .params(UUID.randomUUID(), t.tenantId(), user, user)
                .update();
    }

    /**
     * FR-RET-55: a write-off waiting for a second administrator, whose fee is paid meanwhile, is not approved for the old amount:
     * the request is superseded (stale), the sale is free again, and a fresh request for what is owed now is accepted.
     */
    @Test
    void aWriteOffWhoseFeeIsPaidMeanwhileIsSupersededAndCanBeAskedAgain() {
        UUID[] ids = feeOnAccountSale("Test Buyer 14");
        RetailTestSupport checker = new RetailTestSupport(http, t);
        tenantAdmin(api.userId(), 0);
        tenantAdmin(checker.userId(), 1);
        ResponseEntity<JsonNode> asked = writeOff(ids[1]);
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String approval = asked.getBody().get("approval_id").asString();
        assertThat(payFee(ids[0], UUID.randomUUID().toString(), ORDERS).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> approved = http.exchange(
                "/api/v1/approvals/" + approval + "/approve",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        Map.of("note", "Checked the debt"),
                        checker.headers(WRITER + ",core.approvals.read", "*", Map.of())),
                JsonNode.class);
        assertThat(approved.getStatusCode()).as("%s", approved.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(approved)).isEqualTo("write_off_changed");
        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM approval_requests WHERE id = ?::uuid")
                        .param(approval)
                        .query(String.class)
                        .single())
                .isEqualTo("stale");
        assertThat(api.get("/sales/" + ids[1], ADMIN).getBody().has("write_off"))
                .isFalse();

        ResponseEntity<JsonNode> again = writeOff(ids[1]);
        assertThat(again.getStatusCode()).as("%s", again.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(again.getBody().get("approval_id").asString()).isNotEqualTo(approval);
    }
}
