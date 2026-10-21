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
 * Changing the items of an open customer order without cancelling it (issue #393, ADR-052 amendment 1): lines added, removed,
 * made larger and smaller, the stock kept aside following in the same transaction, a rise refused when not enough is free, a
 * change racing the delivery, the agreed prices kept and new lines priced as on a new order, the history, idempotency, and the
 * permissions and branch scope. After every change the stock kept equals what the open orders hold. Names and amounts are
 * fabricated.
 */
class RetailOrderItemChangesIT extends IntegrationTest {

    static final String ORDERS = ADMIN + ",retail.order.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID romper;
    UUID bib;
    UUID cap;
    UUID hq;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("orderitems", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        romper = api.product("OIC-ROMPER", 6_000, 10_000);
        bib = api.product("OIC-BIB", 1_000, 2_500);
        cap = api.product("OIC-CAP", 2_000, 4_000);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    static Map<String, Object> line(UUID product, String qty) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("qty", qty);
        return l;
    }

    UUID placed(List<Map<String, Object>> lines) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", hq);
        b.put("source", "whatsapp");
        b.put("customer_name", "Test Customer 02");
        b.put("customer_phone", "+256700000022");
        b.put("delivery_date", LocalDate.now(ZoneId.of("Africa/Kampala")).toString());
        b.put("delivery_fee_minor", 3_000);
        b.put("lines", lines);
        ResponseEntity<JsonNode> r =
                api.postKeyed("/orders", b, ORDERS, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    String etag(UUID order) {
        return api.get("/orders/" + order, ORDERS).getHeaders().getETag();
    }

    ResponseEntity<JsonNode> change(UUID order, List<Map<String, Object>> lines) {
        return change(order, lines, ORDERS, "*", UUID.randomUUID().toString(), etag(order));
    }

    ResponseEntity<JsonNode> change(
            UUID order, List<Map<String, Object>> lines, String permissions, String branches, String key, String etag) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (key != null) {
            headers.put("Idempotency-Key", key);
        }
        if (etag != null) {
            headers.put("If-Match", etag);
        }
        return api.call(
                HttpMethod.POST, "/orders/" + order + "/items", Map.of("lines", lines), permissions, branches, headers);
    }

    BigDecimal kept(UUID item) {
        return TestDatabase.owner()
                .sql(
                        "SELECT reserved_qty FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), hq, item)
                .query(BigDecimal.class)
                .optional()
                .orElse(BigDecimal.ZERO);
    }

    /** reserved_qty of every balance row equals the base quantities of the current lines of the open orders. */
    void assertKeptMatchesOpenOrders() {
        long wrong = TestDatabase.owner()
                .sql("""
                        SELECT count(*) FROM retail_stock_balances b
                          LEFT JOIN (SELECT o.branch_id, l.product_id, sum(l.base_qty) AS q
                                       FROM retail_customer_orders o
                                       JOIN retail_customer_order_lines l ON l.order_id = o.id AND l.revision = o.items_revision
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

    static List<String> lines(JsonNode order) {
        List<String> out = new ArrayList<>();
        order.get("lines")
                .forEach(l ->
                        out.add(l.get("code").asString() + " x" + l.get("qty").asString() + " @"
                                + l.get("unit_price_minor").asLong()));
        return out;
    }

    static JsonNode last(JsonNode order) {
        JsonNode history = order.get("history");
        return history.get(history.size() - 1);
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    // ---- the changes ---------------------------------------------------------------------------------

    /**
     * A line added, one removed, one made larger, then smaller: the order keeps its number, the stock kept follows each time,
     * the history says what changed and who changed it, and the delivery sells the items as they are now.
     */
    @Test
    void linesAreAddedRemovedMadeLargerAndSmaller() {
        api.stockUp(hq, romper, "10");
        api.stockUp(hq, bib, "10");
        api.stockUp(hq, cap, "10");
        UUID id = placed(List.of(line(romper, "2"), line(bib, "1")));
        String number =
                api.get("/orders/" + id, ORDERS).getBody().get("order_no").asString();
        assertKeptMatchesOpenOrders();

        // Romper 2 to 3, the bib taken off, a cap added.
        ResponseEntity<JsonNode> first = change(id, List.of(line(romper, "3"), line(cap, "1")));
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode o = first.getBody();
        assertThat(o.get("order_no").asString()).isEqualTo(number);
        assertThat(o.get("status").asString()).isEqualTo("ordered");
        assertThat(lines(o)).containsExactly("OIC-ROMPER x3 @10000", "OIC-CAP x1 @4000");
        assertThat(o.get("items_total_minor").asLong()).isEqualTo(34_000);
        assertThat(o.get("total_minor").asLong()).isEqualTo(37_000);
        assertThat(kept(romper)).isEqualByComparingTo("3");
        assertThat(kept(bib)).isEqualByComparingTo("0");
        assertThat(kept(cap)).isEqualByComparingTo("1");
        assertKeptMatchesOpenOrders();
        JsonNode event = last(o);
        assertThat(event.get("step").asString()).isEqualTo("items_changed");
        assertThat(event.get("by_user").asString()).isEqualTo(api.userId().toString());
        assertThat(event.get("at").asString()).isNotBlank();
        assertThat(event.get("note").asString())
                .contains("2 to 3")
                .contains("removed")
                .contains("added 1");

        // Romper 3 to 1.
        ResponseEntity<JsonNode> second = change(id, List.of(line(romper, "1"), line(cap, "1")));
        assertThat(second.getStatusCode()).as("%s", second.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(lines(second.getBody())).containsExactly("OIC-ROMPER x1 @10000", "OIC-CAP x1 @4000");
        assertThat(kept(romper)).isEqualByComparingTo("1");
        assertThat(last(second.getBody()).get("note").asString()).contains("3 to 1");
        assertKeptMatchesOpenOrders();

        // The same items again change nothing and write no history.
        int events = second.getBody().get("history").size();
        ResponseEntity<JsonNode> same = change(id, List.of(line(cap, "1"), line(romper, "1")));
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(same.getBody().get("history").size()).isEqualTo(events);
        assertThat(same.getBody().get("version").asInt())
                .isEqualTo(second.getBody().get("version").asInt());

        // Every earlier revision is still there: the lines are never rewritten.
        long written = TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_customer_order_lines WHERE order_id = ?")
                .param(id)
                .query(Long.class)
                .single();
        assertThat(written).isEqualTo(6);

        ResponseEntity<JsonNode> done = api.postKeyed(
                "/orders/" + id + "/deliver",
                Map.of("payment_method", "cash"),
                ORDERS,
                "*",
                UUID.randomUUID().toString());
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode sale = api.get("/sales/" + done.getBody().get("sale_id").asString(), ADMIN)
                .getBody();
        assertThat(sale.get("total_minor").asLong()).isEqualTo(14_000);
        assertThat(sale.get("lines")).hasSize(2);
        assertThat(kept(romper)).isEqualByComparingTo("0");
        assertThat(kept(cap)).isEqualByComparingTo("0");
        assertKeptMatchesOpenOrders();
    }

    /** A rise larger than what is free is refused and nothing changes: the lines, the stock kept and the version. */
    @Test
    void aRiseLargerThanTheFreeStockIsRefused() {
        api.stockUp(hq, romper, "4");
        UUID id = placed(List.of(line(romper, "2")));
        placed(List.of(line(romper, "1")));
        String before = etag(id);
        ResponseEntity<JsonNode> refused = change(id, List.of(line(romper, "4")));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("insufficient_stock");
        assertThat(refused.getBody().get("detail").asString()).contains("cannot keep 2 more");
        assertThat(kept(romper)).isEqualByComparingTo("3");
        assertThat(etag(id)).isEqualTo(before);
        JsonNode o = api.get("/orders/" + id, ORDERS).getBody();
        assertThat(lines(o)).containsExactly("OIC-ROMPER x2 @10000");
        assertThat(last(o).get("step").asString()).isEqualTo("ordered");
        assertKeptMatchesOpenOrders();
        // What is free can still be added.
        assertThat(change(id, List.of(line(romper, "3"))).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(kept(romper)).isEqualByComparingTo("4");
        assertKeptMatchesOpenOrders();
    }

    /** A packed order may change, and the history says to pack it again; once it is out, delivered or cancelled it may not. */
    @Test
    void onlyAnOrderedOrPackedOrderChanges() {
        api.stockUp(hq, romper, "10");
        UUID id = placed(List.of(line(romper, "1")));
        assertThat(api.post("/orders/" + id + "/pack", Map.of(), ORDERS).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> packed = change(id, List.of(line(romper, "2")));
        assertThat(packed.getStatusCode()).as("%s", packed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(packed.getBody().get("status").asString()).isEqualTo("packed");
        assertThat(last(packed.getBody()).get("note").asString()).contains("pack it again");

        assertThat(api.post("/orders/" + id + "/send", Map.of("delivered_by", "Test Rider 02"), ORDERS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> out = change(id, List.of(line(romper, "3")));
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(out)).isEqualTo("order_step_not_allowed");
        assertThat(out.getBody().get("detail").asString()).contains("out for delivery");

        UUID cancelled = placed(List.of(line(romper, "1")));
        assertThat(api.post("/orders/" + cancelled + "/cancel", Map.of("reason", "Test reason"), ORDERS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> gone = change(cancelled, List.of(line(romper, "2")));
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(gone.getBody().get("detail").asString()).contains("was cancelled");
        assertThat(kept(romper)).isEqualByComparingTo("2");
        assertKeptMatchesOpenOrders();
    }

    /**
     * A change and the delivery at the same moment are serialised on the order row: either the change lands first and the sale
     * sells the new items, or the delivery does and the change is refused. Either way nothing stays kept and the shelf lost
     * exactly what was sold.
     */
    @Test
    void aChangeRacingTheDeliveryIsSerialised() throws Exception {
        api.stockUp(hq, romper, "10");
        UUID id = placed(List.of(line(romper, "2")));
        String etag = etag(id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        ResponseEntity<JsonNode> changed;
        ResponseEntity<JsonNode> delivered;
        try {
            Future<ResponseEntity<JsonNode>> c = pool.submit(() -> {
                go.await();
                return change(
                        id,
                        List.of(line(romper, "5")),
                        ORDERS,
                        "*",
                        UUID.randomUUID().toString(),
                        etag);
            });
            Future<ResponseEntity<JsonNode>> d = pool.submit(() -> {
                go.await();
                return api.postKeyed(
                        "/orders/" + id + "/deliver",
                        Map.of("payment_method", "cash"),
                        ORDERS,
                        "*",
                        UUID.randomUUID().toString());
            });
            go.countDown();
            changed = c.get(60, TimeUnit.SECONDS);
            delivered = d.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(delivered.getStatusCode()).as("%s", delivered.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode sale = api.get("/sales/" + delivered.getBody().get("sale_id").asString(), ADMIN)
                .getBody();
        BigDecimal sold = new BigDecimal(sale.get("lines").get(0).get("qty").asString());
        if (changed.getStatusCode().equals(HttpStatus.OK)) {
            assertThat(sold).isEqualByComparingTo("5");
        } else {
            assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(sold).isEqualByComparingTo("2");
        }
        BigDecimal shelf = TestDatabase.owner()
                .sql("SELECT qty FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), hq, romper)
                .query(BigDecimal.class)
                .single();
        assertThat(shelf).isEqualByComparingTo(BigDecimal.TEN.subtract(sold));
        assertThat(kept(romper)).isEqualByComparingTo("0");
        assertKeptMatchesOpenOrders();
    }

    /**
     * A line kept keeps the price agreed, even after the price list changed; a new line is priced today; a typed price on a new
     * line is an override that needs the permission and a reason, and is kept as the override of the person who changed it.
     */
    @Test
    void keptLinesKeepTheAgreedPriceAndNewLinesArePricedToday() {
        api.stockUp(hq, romper, "10");
        api.stockUp(hq, cap, "10");
        UUID id = placed(List.of(line(romper, "1")));
        api.editPrices(romper, Map.of("cost_minor", 6_000, "sell_minor", 12_000, "reason", "Test new price"), ADMIN);
        api.editPrices(cap, Map.of("cost_minor", 2_000, "sell_minor", 4_500, "reason", "Test new price"), ADMIN);
        ResponseEntity<JsonNode> r = change(id, List.of(line(romper, "2"), line(cap, "1")));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(lines(r.getBody())).containsExactly("OIC-ROMPER x2 @10000", "OIC-CAP x1 @4500");

        String etag = api.get("/orders/" + id, ORDERS).getHeaders().getETag();
        api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("price_lists", true),
                "core.settings.manage",
                "*",
                Map.of(
                        "If-Match",
                        api.get("/settings/capabilities", "core.settings.manage")
                                .getHeaders()
                                .getETag()));
        Map<String, Object> cheaper = line(cap, "1");
        cheaper.put("unit_price_minor", 4_000);
        cheaper.put("price_override_reason", "Test regular customer price");
        ResponseEntity<JsonNode> denied = change(
                id,
                List.of(line(romper, "2"), cheaper),
                ORDERS,
                "*",
                UUID.randomUUID().toString(),
                etag);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> noReason = line(cap, "1");
        noReason.put("unit_price_minor", 4_000);
        ResponseEntity<JsonNode> unexplained = change(
                id,
                List.of(line(romper, "2"), noReason),
                ORDERS + ",retail.price.override",
                "*",
                UUID.randomUUID().toString(),
                etag);
        assertThat(unexplained.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(unexplained)).isEqualTo("override_reason_required");
        ResponseEntity<JsonNode> allowed = change(
                id,
                List.of(line(romper, "2"), cheaper),
                ORDERS + ",retail.price.override",
                "*",
                UUID.randomUUID().toString(),
                etag);
        assertThat(allowed.getStatusCode()).as("%s", allowed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(lines(allowed.getBody())).containsExactly("OIC-ROMPER x2 @10000", "OIC-CAP x1 @4000");
        assertThat(last(allowed.getBody()).get("note").asString()).contains("price of");
        Map<String, Object> stored =
                TestDatabase.owner().sql("""
                        SELECT l.price_source, l.price_override_reason, l.overridden_by
                          FROM retail_customer_order_lines l JOIN retail_customer_orders o ON o.id = l.order_id
                         WHERE o.id = ? AND l.revision = o.items_revision AND l.product_id = ?
                        """).params(id, cap).query().singleRow();
        assertThat(stored.get("price_source")).isEqualTo("override");
        assertThat(stored.get("price_override_reason")).isEqualTo("Test regular customer price");
        assertThat(stored.get("overridden_by")).isEqualTo(api.userId());
        assertKeptMatchesOpenOrders();
    }

    /** One change per Idempotency-Key: a double tap keeps the stock once and writes one history line. */
    @Test
    void aRepeatedChangeWithTheSameKeyChangesOnce() {
        api.stockUp(hq, romper, "10");
        UUID id = placed(List.of(line(romper, "1")));
        String etag = etag(id);
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = change(id, List.of(line(romper, "3")), ORDERS, "*", key, etag);
        ResponseEntity<JsonNode> second = change(id, List.of(line(romper, "3")), ORDERS, "*", key, etag);
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(kept(romper)).isEqualByComparingTo("3");
        long changes = TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_customer_order_events WHERE order_id = ? AND step = 'items_changed'")
                .param(id)
                .query(Long.class)
                .single();
        assertThat(changes).isEqualTo(1);
        // Without a key or without the version read last, nothing changes.
        assertThat(change(id, List.of(line(romper, "2")), ORDERS, "*", null, etag(id))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(change(
                                id,
                                List.of(line(romper, "2")),
                                ORDERS,
                                "*",
                                UUID.randomUUID().toString(),
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        ResponseEntity<JsonNode> stale = change(
                id, List.of(line(romper, "2")), ORDERS, "*", UUID.randomUUID().toString(), etag);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(stale)).isEqualTo("version_conflict");
        assertThat(kept(romper)).isEqualByComparingTo("3");
        assertKeptMatchesOpenOrders();
    }

    /** Without retail.order.manage: 403. With it at another branch only: the order is not found. */
    @Test
    void thePermissionAndTheBranchAreChecked() {
        api.stockUp(hq, romper, "10");
        UUID id = placed(List.of(line(romper, "1")));
        String etag = etag(id);
        ResponseEntity<JsonNode> none = change(
                id, List.of(line(romper, "2")), ADMIN, "*", UUID.randomUUID().toString(), etag);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> elsewhere = change(
                id,
                List.of(line(romper, "2")),
                ORDERS,
                t.secondBranch().toString(),
                UUID.randomUUID().toString(),
                etag);
        assertThat(elsewhere.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(kept(romper)).isEqualByComparingTo("1");
        assertThat(etag(id)).isEqualTo(etag);
        assertKeptMatchesOpenOrders();
    }
}
