package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
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
        assertThat(refused.getBody().get("detail").asString())
                .contains("cannot keep 2 more")
                .contains("Test Product OIC-ROMPER")
                .doesNotContain(romper.toString());
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

    /**
     * A packed order may change: it goes back to ordered with its packing cleared, and the history says to pack it again. Once it
     * is out, delivered or cancelled it may not.
     */
    @Test
    void onlyAnOrderedOrPackedOrderChanges() {
        api.stockUp(hq, romper, "10");
        UUID id = placed(List.of(line(romper, "1")));
        assertThat(api.post("/orders/" + id + "/pack", Map.of(), ORDERS).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> packed = change(id, List.of(line(romper, "2")));
        assertThat(packed.getStatusCode()).as("%s", packed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(packed.getBody().get("status").asString()).isEqualTo("ordered");
        assertThat(last(packed.getBody()).get("step").asString()).isEqualTo("items_changed");
        assertThat(last(packed.getBody()).get("note").asString())
                .contains("back to ordered")
                .contains("pack it again");
        Map<String, Object> packing = TestDatabase.owner()
                .sql("SELECT status, packed_at, packed_by FROM retail_customer_orders WHERE id = ?")
                .param(id)
                .query()
                .singleRow();
        assertThat(packing.get("status")).isEqualTo("ordered");
        assertThat(packing.get("packed_at")).isNull();
        assertThat(packing.get("packed_by")).isNull();
        // Packed again, it moves on as before.
        assertThat(api.post("/orders/" + id + "/pack", Map.of(), ORDERS).getStatusCode())
                .isEqualTo(HttpStatus.OK);

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
     * A line left as it was keeps the price agreed, even after the price changed, the item was switched off or its cost rose
     * above that price; a line whose quantity changed and a new line are priced today; a typed price on a new line is an override
     * that needs the permission and a reason, and is kept as the override of the person who changed it.
     */
    @Test
    void keptLinesKeepTheAgreedPriceAndNewLinesArePricedToday() {
        api.stockUp(hq, romper, "10");
        api.stockUp(hq, bib, "10");
        api.stockUp(hq, cap, "10");
        UUID id = placed(List.of(line(romper, "1"), line(bib, "1")));
        api.editPrices(romper, Map.of("cost_minor", 6_000, "sell_minor", 12_000, "reason", "Test new price"), ADMIN);
        api.editPrices(cap, Map.of("cost_minor", 2_000, "sell_minor", 4_500, "reason", "Test new price"), ADMIN);
        // The bib now costs more than the 2500 agreed, and is switched off.
        api.editPrices(bib, Map.of("cost_minor", 3_000, "sell_minor", 3_500, "reason", "Test new price"), ADMIN);
        String bibTag = api.get("/products/" + bib, ADMIN).getHeaders().getETag();
        assertThat(api.call(
                                HttpMethod.PATCH,
                                "/products/" + bib,
                                Map.of("active", false),
                                ADMIN,
                                "*",
                                Map.of("If-Match", bibTag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> added = change(id, List.of(line(romper, "1"), line(bib, "1"), line(cap, "1")));
        assertThat(added.getStatusCode()).as("%s", added.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(lines(added.getBody()))
                .containsExactly("OIC-ROMPER x1 @10000", "OIC-BIB x1 @2500", "OIC-CAP x1 @4500");
        // The romper's quantity changed, so it is priced again as on a new order; the bib left as it was keeps its
        // price.
        ResponseEntity<JsonNode> r = change(id, List.of(line(romper, "2"), line(bib, "1"), line(cap, "1")));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(lines(r.getBody())).containsExactly("OIC-ROMPER x2 @12000", "OIC-BIB x1 @2500", "OIC-CAP x1 @4500");
        // A new quantity of the switched-off bib is a new line, refused as on a new order.
        ResponseEntity<JsonNode> off = change(id, List.of(line(romper, "2"), line(bib, "2"), line(cap, "1")));
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(off.getBody().toString()).contains("product_inactive");

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
        assertThat(lines(allowed.getBody())).containsExactly("OIC-ROMPER x2 @12000", "OIC-CAP x1 @4000");
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

    // ---- more helpers --------------------------------------------------------------------------------
    // JUSTIFICATION-A3: new test cases the review of #421 asked for (races, roles, units, price breaks, overrides, the
    // release invariant); the anchor is only the insertion point at the end of the class.

    static final String PRICES = ADMIN + ",retail.pricelist.manage,retail.pricelist.use";

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

    UUID placedAs(String permissions, UUID customer, List<Map<String, Object>> lines) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", hq);
        b.put("source", "whatsapp");
        b.put("customer_name", "Test Customer 03");
        if (customer != null) {
            b.put("customer_id", customer);
        }
        b.put("lines", lines);
        ResponseEntity<JsonNode> r =
                api.postKeyed("/orders", b, permissions, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    ResponseEntity<JsonNode> changeAs(
            RetailTestSupport who, UUID order, List<Map<String, Object>> lines, String permissions) {
        return who.call(
                HttpMethod.POST,
                "/orders/" + order + "/items",
                Map.of("lines", lines),
                permissions,
                "*",
                Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", etag(order)));
    }

    static Map<String, Object> priced(UUID product, String qty, long price, String reason) {
        Map<String, Object> l = line(product, qty);
        l.put("unit_price_minor", price);
        if (reason != null) {
            l.put("price_override_reason", reason);
        }
        return l;
    }

    /** How the current line of an order for an item was priced, as stored. */
    Map<String, Object> storedLine(UUID order, UUID product) {
        return TestDatabase.owner().sql("""
                        SELECT l.unit_price_minor, l.price_source, l.price_break_min_qty, l.resolved_unit_price_minor,
                               l.price_override_reason, l.overridden_by
                          FROM retail_customer_order_lines l JOIN retail_customer_orders o ON o.id = l.order_id
                         WHERE o.id = ? AND l.revision = o.items_revision AND l.product_id = ?
                        """).params(order, product).query().singleRow();
    }

    BigDecimal shelf(UUID item) {
        return TestDatabase.owner()
                .sql("SELECT qty FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), hq, item)
                .query(BigDecimal.class)
                .single();
    }

    /** What is kept is never below zero nor above the shelf, and equals what the open orders hold. */
    void assertKeptIsSound(UUID item) {
        assertThat(kept(item)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(kept(item)).isLessThanOrEqualTo(shelf(item));
        assertKeptMatchesOpenOrders();
    }

    String signIn(String name, Api.Role... roles) {
        String email = Api.email(name);
        UUID id = Api.staff(t, email, roles);
        return Api.tenant(http, t.slug()).signIn(id, email, Api.PASSWORD, null).accessToken();
    }

    ResponseEntity<JsonNode> changeSignedIn(String token, UUID order, List<Map<String, Object>> lines) {
        return Api.tenant(http, t.slug())
                .call(
                        HttpMethod.POST,
                        RetailTestSupport.BASE + "/orders/" + order + "/items",
                        Map.of("lines", lines),
                        token,
                        Map.of("Idempotency-Key", UUID.randomUUID().toString(), "If-Match", etag(order)));
    }

    // ---- races ---------------------------------------------------------------------------------------

    /**
     * A change and a cancel at the same moment are serialised on the order row: either the change lands and the cancel gives
     * back the new items, or the cancel lands and the change is refused. Either way nothing stays kept.
     */
    @Test
    void aChangeRacingTheCancelIsSerialised() throws Exception {
        api.stockUp(hq, romper, "10");
        UUID id = placed(List.of(line(romper, "2")));
        String etag = etag(id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        ResponseEntity<JsonNode> changed;
        ResponseEntity<JsonNode> cancelled;
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
            Future<ResponseEntity<JsonNode>> x = pool.submit(() -> {
                go.await();
                return api.post("/orders/" + id + "/cancel", Map.of("reason", "Test customer changed mind"), ORDERS);
            });
            go.countDown();
            changed = c.get(60, TimeUnit.SECONDS);
            cancelled = x.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.OK);
        if (!changed.getStatusCode().equals(HttpStatus.OK)) {
            assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(code(changed)).isIn("order_step_not_allowed", "version_conflict");
        }
        assertThat(kept(romper)).isEqualByComparingTo("0");
        assertThat(shelf(romper)).isEqualByComparingTo("10");
        assertKeptMatchesOpenOrders();
    }

    /**
     * A change that keeps one more unit and a till sale of the last free unit at the same moment: exactly one of them gets it, and
     * the shelf never holds less than is kept.
     */
    @Test
    void aChangeRacingATillSaleOfTheSameItemTakesTheLastUnitOnce() throws Exception {
        api.stockUp(hq, romper, "3");
        UUID id = placed(List.of(line(romper, "2")));
        String etag = etag(id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        ResponseEntity<JsonNode> changed;
        ResponseEntity<JsonNode> sold;
        try {
            Future<ResponseEntity<JsonNode>> c = pool.submit(() -> {
                go.await();
                return change(
                        id,
                        List.of(line(romper, "3")),
                        ORDERS,
                        "*",
                        UUID.randomUUID().toString(),
                        etag);
            });
            Future<ResponseEntity<JsonNode>> s = pool.submit(() -> {
                go.await();
                return api.sell(hq, "cash", romper, "1");
            });
            go.countDown();
            changed = c.get(60, TimeUnit.SECONDS);
            sold = s.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        boolean changeWon = changed.getStatusCode().equals(HttpStatus.OK);
        boolean saleWon = sold.getStatusCode().equals(HttpStatus.CREATED);
        assertThat(changeWon ^ saleWon)
                .as("exactly one takes the last unit: %s / %s", changed.getBody(), sold.getBody())
                .isTrue();
        if (changeWon) {
            assertThat(code(sold)).isEqualTo("insufficient_stock");
            assertThat(kept(romper)).isEqualByComparingTo("3");
            assertThat(shelf(romper)).isEqualByComparingTo("3");
        } else {
            assertThat(code(changed)).isEqualTo("insufficient_stock");
            assertThat(kept(romper)).isEqualByComparingTo("2");
            assertThat(shelf(romper)).isEqualByComparingTo("2");
        }
        assertKeptIsSound(romper);
    }

    // ---- who may change ------------------------------------------------------------------------------

    /** A Storekeeper and Purchasing hold no order permission, so they may not change an order's items. */
    @Test
    void aStorekeeperAndPurchasingMayNotChangeTheItems() {
        api.stockUp(hq, romper, "10");
        UUID id = placed(List.of(line(romper, "1")));
        String keeper = signIn("oickeeper", new Api.Role("retail_storekeeper", hq));
        String buyer = signIn("oicbuyer", new Api.Role("retail_purchasing", hq));
        for (String token : List.of(keeper, buyer)) {
            ResponseEntity<JsonNode> r = changeSignedIn(token, id, List.of(line(romper, "3")));
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(code(r)).isEqualTo("permission_denied");
        }
        assertThat(kept(romper)).isEqualByComparingTo("1");
        assertKeptMatchesOpenOrders();
    }

    /**
     * A seller who sees only their own figures (ADR-055) changes an order a colleague took: the change is made, and the history
     * names the seller's own step but not the colleague's.
     */
    @Test
    void aSellerWhoSeesOnlyTheirOwnFiguresChangesAColleaguesOrder() {
        api.stockUp(hq, romper, "10");
        UUID id = placed(List.of(line(romper, "1")));
        String email = Api.email("oicseller");
        UUID seller = Api.staff(t, email, new Api.Role("retail_sales", hq));
        String token = Api.tenant(http, t.slug())
                .signIn(seller, email, Api.PASSWORD, null)
                .accessToken();
        ResponseEntity<JsonNode> r = changeSignedIn(token, id, List.of(line(romper, "2")));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode history = r.getBody().get("history");
        JsonNode taken = history.get(0).get("by_user");
        assertThat(taken == null || taken.isNull())
                .as("the colleague who took the order is not named")
                .isTrue();
        assertThat(last(r.getBody()).get("step").asString()).isEqualTo("items_changed");
        assertThat(last(r.getBody()).get("by_user").asString()).isEqualTo(seller.toString());
        assertThat(kept(romper)).isEqualByComparingTo("2");
        assertKeptMatchesOpenOrders();
    }

    /** An order at a branch made a store since it was taken cannot change: a store never sells. */
    @Test
    void aStoreRefusesTheChange() {
        api.stockUp(hq, romper, "10");
        UUID id = placed(List.of(line(romper, "1")));
        TestDatabase.owner()
                .sql("UPDATE branches SET sells_goods = false WHERE id = ?")
                .param(hq)
                .update();
        ResponseEntity<JsonNode> r = change(id, List.of(line(romper, "2")));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("branch_does_not_sell");
        assertThat(kept(romper)).isEqualByComparingTo("1");
        assertKeptMatchesOpenOrders();
    }

    // ---- units, price breaks and overrides -----------------------------------------------------------

    /**
     * Lines in a pack keep and give back stock in base units: a carton of six, then two, then a carton and three pieces as two
     * lines of the same item, then everything given back on cancel.
     */
    @Test
    void packLinesKeepAndGiveBackBaseUnits() {
        capability("units");
        UUID carton = api.unit("oic-carton");
        ResponseEntity<JsonNode> pack =
                api.post("/products/" + romper + "/units", Map.of("unit_id", carton, "factor", "6"), ADMIN);
        assertThat(pack.getStatusCode()).as("%s", pack.getBody()).isEqualTo(HttpStatus.CREATED);
        api.stockUp(hq, romper, "30");
        Map<String, Object> oneCarton = line(romper, "1");
        oneCarton.put("unit_id", carton);
        UUID id = placed(List.of(oneCarton));
        assertThat(kept(romper)).isEqualByComparingTo("6");

        Map<String, Object> twoCartons = line(romper, "2");
        twoCartons.put("unit_id", carton);
        ResponseEntity<JsonNode> bigger = change(id, List.of(twoCartons));
        assertThat(bigger.getStatusCode()).as("%s", bigger.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(lines(bigger.getBody())).containsExactly("OIC-ROMPER x2 @60000");
        assertThat(kept(romper)).isEqualByComparingTo("12");
        assertKeptIsSound(romper);

        ResponseEntity<JsonNode> mixed = change(id, List.of(oneCarton, line(romper, "3")));
        assertThat(mixed.getStatusCode()).as("%s", mixed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(lines(mixed.getBody())).containsExactly("OIC-ROMPER x1 @60000", "OIC-ROMPER x3 @10000");
        assertThat(kept(romper)).isEqualByComparingTo("9");
        assertKeptIsSound(romper);

        assertThat(api.post("/orders/" + id + "/cancel", Map.of("reason", "Test reason"), ORDERS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(kept(romper)).isEqualByComparingTo("0");
        assertKeptIsSound(romper);
    }

    /**
     * With price lists, a line whose quantity changed finds its price break again, and so does a new line: the break and the
     * resolved price stored are those of the new quantity, never the ones agreed for the old one.
     */
    @Test
    void priceBreaksAreFoundAgainForANewQuantityAndForNewLines() {
        api.stockUp(hq, romper, "20");
        api.stockUp(hq, bib, "20");
        capability("price_lists");
        UUID list = RetailTestSupport.id(
                api.post("/price-lists", Map.of("name", "Test Wholesale", "strict", false), PRICES));
        for (Object[] p : List.of(
                new Object[] {romper, "0", 9_000},
                new Object[] {romper, "3", 8_000},
                new Object[] {bib, "0", 2_200},
                new Object[] {bib, "5", 2_000})) {
            UUID product = (UUID) p[0];
            UUID unit = UUID.fromString(api.get("/products/" + product, ADMIN)
                    .getBody()
                    .get("unit_id")
                    .asString());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("product_id", product);
            item.put("unit_id", unit);
            if (!"0".equals(p[1])) {
                item.put("min_qty", p[1]);
            }
            item.put("price_minor", p[2]);
            ResponseEntity<JsonNode> added = api.post("/price-lists/" + list + "/items", item, PRICES);
            assertThat(added.getStatusCode()).as("%s", added.getBody()).isEqualTo(HttpStatus.CREATED);
        }
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Customer 05"), ADMIN));
        int version = 0;
        for (JsonNode c : api.get("/customers", ADMIN).getBody().get("items")) {
            if (c.get("id").asString().equals(buyer.toString())) {
                version = c.get("version").asInt();
            }
        }
        ResponseEntity<JsonNode> onList = api.call(
                HttpMethod.PUT,
                "/customers/" + buyer + "/pricing",
                Map.of("price_list_id", list),
                PRICES,
                "*",
                Map.of("If-Match", String.valueOf(version)));
        assertThat(onList.getStatusCode()).as("%s", onList.getBody()).isEqualTo(HttpStatus.OK);

        UUID id = placedAs(ORDERS, buyer, List.of(line(romper, "2")));
        assertThat(lines(api.get("/orders/" + id, ORDERS).getBody())).containsExactly("OIC-ROMPER x2 @9000");

        ResponseEntity<JsonNode> r = change(id, List.of(line(romper, "3"), line(bib, "5")));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(lines(r.getBody())).containsExactly("OIC-ROMPER x3 @8000", "OIC-BIB x5 @2000");
        Map<String, Object> rompers = storedLine(id, romper);
        assertThat(rompers.get("price_source")).isEqualTo("list");
        assertThat((BigDecimal) rompers.get("price_break_min_qty")).isEqualByComparingTo("3");
        assertThat(((Number) rompers.get("resolved_unit_price_minor")).longValue())
                .isEqualTo(8_000);
        Map<String, Object> bibs = storedLine(id, bib);
        assertThat((BigDecimal) bibs.get("price_break_min_qty")).isEqualByComparingTo("5");
        assertThat(((Number) bibs.get("resolved_unit_price_minor")).longValue()).isEqualTo(2_000);

        // Back under the break, the romper is priced at the lower break again.
        ResponseEntity<JsonNode> back = change(id, List.of(line(romper, "2"), line(bib, "5")));
        assertThat(back.getStatusCode()).as("%s", back.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(lines(back.getBody())).containsExactly("OIC-ROMPER x2 @9000", "OIC-BIB x5 @2000");
        assertThat((BigDecimal) storedLine(id, romper).get("price_break_min_qty"))
                .isEqualByComparingTo("0");
        assertKeptMatchesOpenOrders();
    }

    /**
     * A price changed when the order was taken stays that person's override while the line is left as it was, even when someone
     * without the permission changes other items. Its quantity raised at the same price is a new override: refused without
     * retail.price.override, refused without a reason, and once allowed it is credited to the person who changed it now.
     */
    @Test
    void anOverrideWhoseQuantityRisesNeedsThePermissionAndAReasonAgain() {
        api.stockUp(hq, cap, "10");
        api.stockUp(hq, bib, "10");
        capability("price_lists");
        String taker = "Test regular customer price";
        UUID id = placedAs(ORDERS + ",retail.price.override", null, List.of(priced(cap, "1", 3_500, taker)));
        RetailTestSupport colleague = new RetailTestSupport(http, t);

        ResponseEntity<JsonNode> other =
                changeAs(colleague, id, List.of(priced(cap, "1", 3_500, null), line(bib, "1")), ORDERS);
        assertThat(other.getStatusCode()).as("%s", other.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> agreed = storedLine(id, cap);
        assertThat(agreed.get("price_source")).isEqualTo("override");
        assertThat(agreed.get("price_override_reason")).isEqualTo(taker);
        assertThat(agreed.get("overridden_by")).isEqualTo(api.userId());

        List<Map<String, Object>> rise = List.of(priced(cap, "2", 3_500, null), line(bib, "1"));
        ResponseEntity<JsonNode> denied = changeAs(colleague, id, rise, ORDERS);
        assertThat(denied.getStatusCode()).as("%s", denied.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> unexplained = changeAs(colleague, id, rise, ORDERS + ",retail.price.override");
        assertThat(unexplained.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(unexplained)).isEqualTo("override_reason_required");
        assertThat(kept(cap)).isEqualByComparingTo("1");

        String why = "Test two at the regular price";
        ResponseEntity<JsonNode> allowed = changeAs(
                colleague,
                id,
                List.of(priced(cap, "2", 3_500, why), line(bib, "1")),
                ORDERS + ",retail.price.override");
        assertThat(allowed.getStatusCode()).as("%s", allowed.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> now = storedLine(id, cap);
        assertThat(now.get("price_source")).isEqualTo("override");
        assertThat(now.get("price_override_reason")).isEqualTo(why);
        assertThat(now.get("overridden_by")).isEqualTo(colleague.userId());
        assertThat(kept(cap)).isEqualByComparingTo("2");

        // Without a price, a new quantity takes today's price and is no override at all.
        ResponseEntity<JsonNode> plain = changeAs(colleague, id, List.of(line(cap, "3"), line(bib, "1")), ORDERS);
        assertThat(plain.getStatusCode()).as("%s", plain.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(storedLine(id, cap).get("price_source")).isNotEqualTo("override");
        assertThat(((Number) storedLine(id, cap).get("unit_price_minor")).longValue())
                .isEqualTo(4_000);
        assertKeptMatchesOpenOrders();
    }

    /**
     * Over a run of changes to two orders for the same item (smaller, larger, the item taken off and put back, a cancel and a
     * delivery), what is given back never exceeds what was kept: the kept quantity is never below zero, never above the shelf,
     * and always what the open orders hold, ending at zero.
     */
    @Test
    void whatIsGivenBackNeverExceedsWhatWasKept() {
        api.stockUp(hq, romper, "10");
        api.stockUp(hq, cap, "10");
        UUID a = placed(List.of(line(romper, "3")));
        UUID b = placed(List.of(line(romper, "2"), line(cap, "1")));
        assertKeptIsSound(romper);
        List<UUID> orders = List.of(a, b, a, a, b);
        List<List<Map<String, Object>>> changes = List.of(
                List.of(line(romper, "1")),
                List.of(line(romper, "4"), line(cap, "1")),
                List.of(line(cap, "2")),
                List.of(line(cap, "1"), line(romper, "5")),
                List.of(line(cap, "3")));
        for (int i = 0; i < orders.size(); i++) {
            ResponseEntity<JsonNode> r = change(orders.get(i), changes.get(i));
            assertThat(r.getStatusCode()).as("step %s: %s", i, r.getBody()).isEqualTo(HttpStatus.OK);
            assertKeptIsSound(romper);
            assertKeptIsSound(cap);
        }
        assertThat(kept(romper)).isEqualByComparingTo("5");
        assertThat(kept(cap)).isEqualByComparingTo("4");
        assertThat(api.post("/orders/" + b + "/cancel", Map.of("reason", "Test reason"), ORDERS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> done = api.postKeyed(
                "/orders/" + a + "/deliver",
                Map.of("payment_method", "cash"),
                ORDERS,
                "*",
                UUID.randomUUID().toString());
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(kept(romper)).isEqualByComparingTo("0");
        assertThat(kept(cap)).isEqualByComparingTo("0");
        assertThat(shelf(romper)).isEqualByComparingTo("5");
        assertThat(shelf(cap)).isEqualByComparingTo("9");
        assertKeptMatchesOpenOrders();
    }
}
