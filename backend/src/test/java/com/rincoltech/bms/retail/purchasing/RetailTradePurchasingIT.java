package com.rincoltech.bms.retail.purchasing;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Optional purchasing (ADR-033, issue #198; docs/specs/trade-capabilities.md sections 7, 12.3 and 13): orders, goods
 * received, supplier invoices with the three-way match, supplier payments and the payables report. The posting rules (goods
 * received not invoiced cleared to zero by the invoice, the variance, trade creditors paid down), exact conversions, numbering
 * per branch, maker-checker above a threshold, the guards, idempotency, concurrency and "capability off equals today". Every
 * name and amount is fabricated. The clock is pinned at 22:30 UTC, which is already 01:30 the next day in Africa/Kampala, so
 * every test runs at the edge where the UTC date and the business date differ and none depends on the day it runs.
 */
@Import(RetailTradePurchasingIT.FixedClock.class)
class RetailTradePurchasingIT extends IntegrationTest {

    /** 2026-10-08 01:30 in Kampala, 2026-10-07 22:30 in UTC. */
    static final Instant NOW = Instant.parse("2026-10-07T22:30:00Z");

    static final String TODAY = "2026-10-08";
    static final String UTC_DAY = "2026-10-07";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    static final String SETTINGS = "core.settings.manage";
    /** Orders and receipts, and the checker's permission. */
    static final String BUYER = ADMIN + ",retail.purchase.approve";
    /** The administrator column after migration V47: the invoices, the payments and the reports are the administrator's only. */
    static final String PAYER = BUYER + ",retail.payable.manage,retail.cashbook.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;
    UUID supplier;
    /** Cost 100, sell 150. */
    UUID product;
    /** Cost 250, sell 400. */
    UUID product2;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-trade-buy", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
        product = api.product("TB-1", 100, 150);
        product2 = api.product("TB-2", 250, 400);
        supplier = supplier("Test Supplier 01");
        capability("purchasing", true);
    }

    // ---- helpers ---------------------------------------------------------------------------

    void capability(String key, boolean on) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of(key, on),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    static UUID uuid(JsonNode n, String field) {
        return UUID.fromString(n.get(field).asString());
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    UUID supplier(String name) {
        return RetailTestSupport.id(api.post("/suppliers", Map.of("name", name), BUYER));
    }

    ResponseEntity<JsonNode> keyed(String path, Object body, String perms, String scope) {
        return api.postKeyed(path, body, perms, scope, key());
    }

    static Map<String, Object> orderLine(UUID product, String qty, long cost) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("qty", qty);
        l.put("cost_minor", cost);
        return l;
    }

    @SafeVarargs
    final Map<String, Object> orderBody(UUID supplierId, UUID branch, Map<String, Object>... lines) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supplier_id", supplierId);
        if (branch != null) {
            b.put("branch_id", branch);
        }
        b.put("lines", List.of(lines));
        return b;
    }

    ResponseEntity<JsonNode> orderRaw(Map<String, Object> body) {
        return keyed("/purchase-orders", body, BUYER, "*");
    }

    /** A draft order that must be created. */
    JsonNode draft(UUID branch, Map<String, Object>... lines) {
        ResponseEntity<JsonNode> r = orderRaw(orderBody(supplier, branch, lines));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    ResponseEntity<JsonNode> sendRaw(UUID order) {
        return keyed("/purchase-orders/" + order + "/send", Map.of(), BUYER, "*");
    }

    /** An order that is sent. */
    JsonNode sent(UUID branch, Map<String, Object>... lines) {
        JsonNode d = draft(branch, lines);
        ResponseEntity<JsonNode> r = sendRaw(uuid(d, "id"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("order").get("status").asString()).isEqualTo("sent");
        return r.getBody().get("order");
    }

    JsonNode order(UUID id) {
        return api.get("/purchase-orders/" + id, PAYER).getBody();
    }

    static Map<String, Object> rline(JsonNode orderLine, String qty) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("order_line_id", uuid(orderLine, "id"));
        l.put("qty", qty);
        return l;
    }

    ResponseEntity<JsonNode> receiveRaw(UUID orderId, UUID branch, List<Map<String, Object>> lines) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (orderId != null) {
            b.put("order_id", orderId);
        }
        b.put("branch_id", branch);
        b.put("lines", lines);
        return keyed("/goods-receipts", b, BUYER, "*");
    }

    /** A receipt that must be posted. */
    JsonNode received(UUID orderId, UUID branch, List<Map<String, Object>> lines) {
        ResponseEntity<JsonNode> r = receiveRaw(orderId, branch, lines);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    /** Receives qty of product blind at a branch, at a cost per unit; the receipt. */
    JsonNode blind(UUID branch, UUID prod, String qty, long cost) {
        return blind(branch, prod, qty, cost, null);
    }

    JsonNode blind(UUID branch, UUID prod, String qty, long cost, String receivedOn) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", prod);
        l.put("qty", qty);
        l.put("cost_minor", cost);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supplier_id", supplier);
        b.put("branch_id", branch);
        if (receivedOn != null) {
            b.put("received_on", receivedOn);
        }
        b.put("lines", List.of(l));
        ResponseEntity<JsonNode> r = keyed("/goods-receipts", b, BUYER, "*");
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    static Map<String, Object> bline(JsonNode receiptLine, String qty, long price) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("receipt_line_id", uuid(receiptLine, "id"));
        l.put("qty", qty);
        l.put("unit_price_minor", price);
        return l;
    }

    Map<String, Object> billBody(String ref, long total, String due, String reason, List<Map<String, Object>> lines) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supplier_id", supplier);
        b.put("supplier_ref", ref);
        b.put("bill_date", due.compareTo(TODAY) < 0 ? due : TODAY);
        b.put("due_date", due);
        b.put("total_minor", total);
        if (reason != null) {
            b.put("variance_reason", reason);
        }
        b.put("lines", lines);
        return b;
    }

    ResponseEntity<JsonNode> billRaw(
            String ref, long total, String due, String reason, List<Map<String, Object>> lines) {
        return keyed("/supplier-bills", billBody(ref, total, due, reason, lines), PAYER, "*");
    }

    JsonNode billed(String ref, long total, String due, List<Map<String, Object>> lines) {
        ResponseEntity<JsonNode> r = billRaw(ref, total, due, null, lines);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    ResponseEntity<JsonNode> payRaw(UUID branch, String method, long amount, List<Map<String, Object>> allocations) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supplier_id", supplier);
        b.put("branch_id", branch);
        b.put("method", method);
        b.put("amount_minor", amount);
        if (allocations != null) {
            b.put("allocation", "manual");
            b.put("allocations", allocations);
        }
        return keyed("/supplier-payments", b, PAYER, "*");
    }

    JsonNode paid(UUID branch, String method, long amount) {
        ResponseEntity<JsonNode> r = payRaw(branch, method, amount, null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    static Map<String, Object> alloc(String field, UUID id, long amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(field, id);
        m.put("amount_minor", amount);
        return m;
    }

    JsonNode bill(UUID id) {
        return api.get("/supplier-bills/" + id, PAYER).getBody();
    }

    /** Debits less credits of the account with this system key over the tenant's journal lines. */
    long balance(String systemKey) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = ?
                        """)
                .params(t.tenantId(), systemKey)
                .query(Long.class)
                .single();
    }

    /** As {@link #balance(String)}, over the entries of one branch. */
    long balance(String systemKey, UUID branch) {
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

    /** What the entries of one source posted: system key to {debit, credit}. */
    Map<String, long[]> posted(String sourceType, UUID sourceId) {
        Map<String, long[]> out = new TreeMap<>();
        TestDatabase.owner().sql("""
                        SELECT a.system_key, sum(l.debit) AS debit, sum(l.credit) AS credit FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.tenant_id = ? AND e.source_type = ? AND e.source_id = ?
                         GROUP BY a.system_key
                        """).params(t.tenantId(), sourceType, sourceId).query(rs -> {
            out.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)});
        });
        return out;
    }

    /** The posted legs as comparable text. */
    static Map<String, String> flat(Map<String, long[]> posted) {
        Map<String, String> out = new TreeMap<>();
        posted.forEach((k, v) -> out.put(k, v[0] + "/" + v[1]));
        return out;
    }

    BigDecimal stockAt(UUID branch, UUID prod) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE branch_id = ? AND product_id = ?")
                .params(branch, prod)
                .query(BigDecimal.class)
                .single();
    }

    void threshold(long amount) {
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = settings || CAST(? AS jsonb) WHERE tenant_id = ?")
                .params("{\"approval_thresholds_minor\": {\"retail_purchase_order\": " + amount + "}}", t.tenantId())
                .update();
    }

    /** ADR-091: the switch an administrator saves on the approval rules, written as the store writes it. */
    void switched(boolean on) {
        TestDatabase.owner().sql("""
                        UPDATE tenant_settings SET settings = settings || jsonb_build_object('approval_switches',
                               coalesce(settings->'approval_switches', '{}'::jsonb) || jsonb_build_object('retail_purchase_order', ?))
                         WHERE tenant_id = ?
                        """).params(on, t.tenantId()).update();
    }

    ResponseEntity<JsonNode> decide(String approval, RetailTestSupport who, String perms) {
        return http.exchange(
                "/api/v1/approvals/" + approval + "/approve",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        Map.of("note", "Checked the order"), who.headers(perms, "*", Map.of())),
                JsonNode.class);
    }

    long scalar(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    // ---- the capability off ------------------------------------------------------------------

    @Test
    void withTheCapabilityOffEveryRouteIsNotFoundAndTheRestockIsUnchanged() {
        capability("purchasing", false);
        // Supplier payments, what is owed and the payables report stay open (issue #557, ADR-084): RetailBuyOnCreditIT.
        for (String path : List.of(
                "/purchase-orders",
                "/goods-receipts",
                "/supplier-bills",
                "/purchasing/settings",
                "/suppliers/" + supplier + "/unbilled-receipts")) {
            ResponseEntity<JsonNode> r = api.get(path, PAYER + "," + SETTINGS);
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(code(r)).as(path).isEqualTo("capability_not_enabled");
        }
        ResponseEntity<JsonNode> create = orderRaw(orderBody(supplier, hq, orderLine(product, "1", 100)));
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(create)).isEqualTo("capability_not_enabled");
        assertThat(api.get("/settings/capabilities", SETTINGS)
                        .getBody()
                        .get("purchasing")
                        .asBoolean())
                .isFalse();
        // The plain restock is as it was: stock, price, inventory against creditors, no goods received not invoiced.
        UUID purchase = RetailTestSupport.id(restock("credit"));
        assertThat(posted("retail.purchase", purchase))
                .containsOnlyKeys("inventory", "trade_creditors")
                .containsEntry("inventory", new long[] {500, 0})
                .containsEntry("trade_creditors", new long[] {0, 500});
        assertThat(stockAt(hq, product)).isEqualByComparingTo("5");
    }

    ResponseEntity<JsonNode> restock(String method) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("cost_minor", 100);
        l.put("qty_by_branch", List.of(Map.of("branch_id", hq, "qty", "5")));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("purchased_on", TODAY);
        b.put("payment_method", method);
        b.put("supplier_id", supplier);
        b.put("lines", List.of(l));
        ResponseEntity<JsonNode> r = api.postKeyed("/purchases", b, BUYER, "*", key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r;
    }

    @Test
    void thePlainRestockPostsTheSameWithTheCapabilityOnOrOff() {
        capability("purchasing", false);
        UUID off = RetailTestSupport.id(restock("credit"));
        capability("purchasing", true);
        UUID on = RetailTestSupport.id(restock("credit"));
        assertThat(flat(posted("retail.purchase", on))).isEqualTo(flat(posted("retail.purchase", off)));
        assertThat(stockAt(hq, product)).isEqualByComparingTo("10");
        assertThat(scalar("SELECT count(*) FROM retail_purchase_orders WHERE tenant_id = ?", t.tenantId()))
                .isZero();
    }

    @Test
    void theCapabilityGatesThePermissionsToo() {
        // retail.payable.manage is the administrator's: the sales role, and a buyer without it, are refused.
        assertThat(api.get("/purchase-orders", SALES).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/supplier-bills", BUYER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/supplier-payments", BUYER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/reports/supplier-payables", BUYER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/purchasing/settings", PAYER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/purchase-orders", BUYER).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- orders ------------------------------------------------------------------------------

    @Test
    void ordersAreNumberedPerTenantAndBranchAndTotalledFromTheirLines() {
        JsonNode a = draft(hq, orderLine(product, "10", 100), orderLine(product2, "2.5", 101));
        JsonNode b = draft(hq, orderLine(product, "1", 100));
        JsonNode c = draft(br2, orderLine(product, "1", 100));
        assertThat(a.get("order_no").asString()).isEqualTo("POHQ-000001");
        assertThat(b.get("order_no").asString()).isEqualTo("POHQ-000002");
        assertThat(c.get("order_no").asString()).isEqualTo("POBR2-000001");
        // 10 x 100 plus 2.5 x 101 = 252.5 rounded half up once.
        assertThat(order(uuid(a, "id")).get("total_minor").asLong()).isEqualTo(1_253);
        assertThat(a.get("status").asString()).isEqualTo("draft");
        assertThat(a.get("ordered_on").asString()).isEqualTo(TODAY);
        assertThat(api.get("/purchase-orders", BUYER).getBody().get("items")).hasSize(3);
        // A list carries no lines; the order does.
        assertThat(api.get("/purchase-orders", BUYER)
                        .getBody()
                        .get("items")
                        .get(0)
                        .has("lines"))
                .isFalse();
        assertThat(order(uuid(a, "id")).get("lines")).hasSize(2);
        // Nothing moved: an order posts nothing and moves no stock.
        assertThat(stockAt(hq, product)).isEqualByComparingTo("0");
        assertThat(balance("goods_received_not_invoiced")).isZero();
    }

    @Test
    void aDraftIsEditedUnderIfMatchAndASentOrderIsNot() {
        JsonNode d = draft(hq, orderLine(product, "10", 100));
        UUID id = uuid(d, "id");
        String etag = api.get("/purchase-orders/" + id, BUYER).getHeaders().getETag();
        Map<String, Object> edit = orderBody(supplier, hq, orderLine(product, "20", 100));
        // No If-Match: 428. A stale one: 409. The right one: replaced and the version moves.
        assertThat(api.call(HttpMethod.PUT, "/purchase-orders/" + id, edit, BUYER, "*", Map.of())
                        .getStatusCode()
                        .value())
                .isEqualTo(428);
        ResponseEntity<JsonNode> stale =
                api.call(HttpMethod.PUT, "/purchase-orders/" + id, edit, BUYER, "*", Map.of("If-Match", "99"));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(stale)).isEqualTo("version_conflict");
        ResponseEntity<JsonNode> ok =
                api.call(HttpMethod.PUT, "/purchase-orders/" + id, edit, BUYER, "*", Map.of("If-Match", etag));
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("total_minor").asLong()).isEqualTo(2_000);
        assertThat(ok.getBody().get("version").asInt())
                .isGreaterThan(d.get("version").asInt());
        assertThat(ok.getBody().get("lines")).hasSize(1);
        // The branch of an order is fixed.
        ResponseEntity<JsonNode> move = api.call(
                HttpMethod.PUT,
                "/purchase-orders/" + id,
                orderBody(supplier, br2, orderLine(product, "20", 100)),
                BUYER,
                "*",
                Map.of("If-Match", ok.getHeaders().getETag()));
        assertThat(move.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // Sent: no longer edited, in the API and in the database.
        assertThat(sendRaw(id).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> late = api.call(
                HttpMethod.PUT,
                "/purchase-orders/" + id,
                edit,
                BUYER,
                "*",
                Map.of(
                        "If-Match",
                        api.get("/purchase-orders/" + id, BUYER).getHeaders().getETag()));
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(late)).isEqualTo("order_not_draft");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_purchase_orders SET total_minor = 1 WHERE id = ?")
                        .param(id)
                        .update())
                .rootCause()
                .hasMessageContaining("not edited");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("DELETE FROM retail_purchase_order_lines WHERE order_id = ?")
                        .param(id)
                        .update())
                .rootCause()
                .hasMessageContaining("do not change");
        assertThat(sendRaw(id).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anOrderValidatesItsSupplierDatesAndLines() {
        UUID off = supplier("Test Supplier Off");
        api.call(HttpMethod.PATCH, "/suppliers/" + off, Map.of("active", false), ADMIN, "*", Map.of("If-Match", "1"));
        assertThat(code(orderRaw(orderBody(off, hq, orderLine(product, "1", 100)))))
                .isEqualTo("validation_failed");
        Map<String, Object> body = orderBody(supplier, hq, orderLine(product, "1", 100));
        body.put("ordered_on", "2026-10-09");
        assertThat(orderRaw(body).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        body.put("ordered_on", TODAY);
        body.put("expected_on", UTC_DAY);
        assertThat(orderRaw(body).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        body.put("expected_on", "2026-10-20");
        assertThat(orderRaw(body).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(orderRaw(orderBody(supplier, hq, orderLine(UUID.randomUUID(), "1", 100)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(orderRaw(orderBody(supplier, hq, orderLine(product, "1.0005", 100)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aLargeOrderWaitsForAnotherAdministratorAndASmallOneDoesNot() {
        threshold(1_000);
        JsonNode small = draft(hq, orderLine(product, "9", 100));
        assertThat(sendRaw(uuid(small, "id")).getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode big = draft(hq, orderLine(product, "10", 100));
        UUID id = uuid(big, "id");
        ResponseEntity<JsonNode> asked = sendRaw(id);
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(asked.getBody().get("executed").asBoolean()).isFalse();
        String approval = asked.getBody().get("approval_id").asString();
        assertThat(order(id).get("status").asString()).isEqualTo("draft");
        // Goods cannot be received against it yet.
        ResponseEntity<JsonNode> early =
                receiveRaw(id, hq, List.of(rline(order(id).get("lines").get(0), "1")));
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(early)).isEqualTo("order_not_sent");
        // The maker cannot decide it; another holder of retail.purchase.approve can.
        String checkerPerms = BUYER + ",core.approvals.read";
        assertThat(decide(approval, api, checkerPerms).getStatusCode().is2xxSuccessful())
                .isFalse();
        RetailTestSupport checker = new RetailTestSupport(http, t);
        ResponseEntity<JsonNode> decided = decide(approval, checker, checkerPerms);
        assertThat(decided.getStatusCode()).as("%s", decided.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode now = order(id);
        assertThat(now.get("status").asString()).isEqualTo("sent");
        assertThat(now.get("sent_by").asString()).isEqualTo(api.userId().toString());
        assertThat(now.get("approved_by").asString()).isEqualTo(checker.userId().toString());
        assertThat(now.get("approval_id").asString()).isEqualTo(approval);
        // With no threshold there is no checker: the default is open.
        threshold(0);
        assertThat(sendRaw(uuid(draft(hq, orderLine(product, "100", 100)), "id"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** ADR-091: the switch on the approval rules decides; the amount only counts while it is on. */
    @Test
    void theApprovalRulesSwitchDecidesWhetherAnOrderWaits() {
        // Switched off with an amount set: a large order is sent at once, and a large delivery needs no order.
        threshold(1_000);
        switched(false);
        assertThat(sendRaw(uuid(draft(hq, orderLine(product, "100", 100)), "id"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        blind(hq, product, "50", 100);
        // Switched on with no amount: every order waits, and every delivery needs an order.
        threshold(0);
        switched(true);
        ResponseEntity<JsonNode> asked = sendRaw(uuid(draft(hq, orderLine(product, "1", 100)), "id"));
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(asked.getBody().get("executed").asBoolean()).isFalse();
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("qty", "1");
        l.put("cost_minor", 100);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supplier_id", supplier);
        b.put("branch_id", hq);
        b.put("lines", List.of(l));
        ResponseEntity<JsonNode> blindOne = keyed("/goods-receipts", b, BUYER, "*");
        assertThat(blindOne.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(blindOne)).isEqualTo("blind_receipt_over_limit");
    }

    @Test
    void aWaitingOrderGoesStaleWhenItIsEditedMeanwhile() {
        threshold(1_000);
        JsonNode big = draft(hq, orderLine(product, "10", 100));
        UUID id = uuid(big, "id");
        ResponseEntity<JsonNode> asked = sendRaw(id);
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<JsonNode> edited = api.call(
                HttpMethod.PUT,
                "/purchase-orders/" + id,
                orderBody(supplier, hq, orderLine(product, "11", 100)),
                BUYER,
                "*",
                Map.of(
                        "If-Match",
                        api.get("/purchase-orders/" + id, BUYER).getHeaders().getETag()));
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        RetailTestSupport checker = new RetailTestSupport(http, t);
        ResponseEntity<JsonNode> decided =
                decide(asked.getBody().get("approval_id").asString(), checker, BUYER + ",core.approvals.read");
        assertThat(decided.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(order(id).get("status").asString()).isEqualTo("draft");
    }

    @Test
    void anOrderIsCancelledWithAReasonAndItsReceiptsStand() {
        JsonNode o = sent(hq, orderLine(product, "10", 100));
        UUID id = uuid(o, "id");
        received(id, hq, List.of(rline(order(id).get("lines").get(0), "4")));
        assertThat(order(id).get("status").asString()).isEqualTo("partly_received");
        assertThat(keyed("/purchase-orders/" + id + "/cancel", Map.of("reason", "no"), BUYER, "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> cancelled = keyed(
                "/purchase-orders/" + id + "/cancel", Map.of("reason", "Supplier cannot deliver the rest"), BUYER, "*");
        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status").asString()).isEqualTo("cancelled");
        // The receipt stands, the stock stays, and nothing more is received against it.
        assertThat(stockAt(hq, product)).isEqualByComparingTo("4");
        ResponseEntity<JsonNode> more =
                receiveRaw(id, hq, List.of(rline(order(id).get("lines").get(0), "1")));
        assertThat(more.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(more)).isEqualTo("order_closed");
        assertThat(keyed("/purchase-orders/" + id + "/cancel", Map.of("reason", "Again, by mistake"), BUYER, "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void anOrderInsideAnotherBranchesScopeIsNotFoundAndNotListed() {
        JsonNode atBr2 = draft(br2, orderLine(product, "1", 100));
        draft(hq, orderLine(product, "1", 100));
        String scope = hq.toString();
        ResponseEntity<JsonNode> r =
                api.call(HttpMethod.GET, "/purchase-orders/" + uuid(atBr2, "id"), null, BUYER, scope, Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<JsonNode> list = api.call(HttpMethod.GET, "/purchase-orders", null, BUYER, scope, Map.of());
        assertThat(list.getBody().get("items")).hasSize(1);
        ResponseEntity<JsonNode> create = api.postKeyed(
                "/purchase-orders", orderBody(supplier, br2, orderLine(product, "1", 100)), BUYER, scope, key());
        assertThat(create.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // ---- goods received --------------------------------------------------------------------------

    @Test
    void partialReceiptsMoveTheOrderAndPostInventoryAgainstGoodsReceivedNotInvoiced() {
        JsonNode o = sent(hq, orderLine(product, "10", 100));
        UUID id = uuid(o, "id");
        JsonNode line = order(id).get("lines").get(0);
        JsonNode first = received(id, hq, List.of(rline(line, "4")));
        assertThat(first.get("receipt_no").asString()).isEqualTo("GRHQ-000001");
        assertThat(first.get("received_on").asString()).isEqualTo(TODAY);
        assertThat(first.get("total_minor").asLong()).isEqualTo(400);
        assertThat(order(id).get("status").asString()).isEqualTo("partly_received");
        assertThat(order(id).get("lines").get(0).get("received_qty").asString()).isEqualTo("4.000");
        assertThat(posted("retail.goods_receipt", uuid(first, "id")))
                .containsOnlyKeys("inventory", "goods_received_not_invoiced")
                .containsEntry("inventory", new long[] {400, 0})
                .containsEntry("goods_received_not_invoiced", new long[] {0, 400});
        assertThat(stockAt(hq, product)).isEqualByComparingTo("4");
        JsonNode second = received(id, hq, List.of(rline(line, "6")));
        assertThat(second.get("receipt_no").asString()).isEqualTo("GRHQ-000002");
        assertThat(order(id).get("status").asString()).isEqualTo("received");
        assertThat(order(id).get("receipts")).hasSize(2);
        assertThat(balance("goods_received_not_invoiced")).isEqualTo(-1_000);
        assertThat(balance("inventory")).isEqualTo(1_000);
        // Received in full: nothing more against it.
        ResponseEntity<JsonNode> more = receiveRaw(id, hq, List.of(rline(line, "1")));
        assertThat(code(more)).isEqualTo("order_closed");
    }

    @Test
    void aReceiptMayNotExceedTheOrderUnlessTheToleranceAllowsIt() {
        JsonNode o = sent(hq, orderLine(product, "10", 100));
        UUID id = uuid(o, "id");
        JsonNode line = order(id).get("lines").get(0);
        ResponseEntity<JsonNode> over = receiveRaw(id, hq, List.of(rline(line, "10.001")));
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(over)).isEqualTo("receipt_exceeds_order");
        received(id, hq, List.of(rline(line, "9")));
        // 10 percent over is allowed once the tenant says so; 11 in all is the limit.
        String etag = api.get("/purchasing/settings", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> set = api.call(
                HttpMethod.PUT,
                "/purchasing/settings",
                Map.of("over_receipt_bp", 1000),
                SETTINGS,
                "*",
                Map.of("If-Match", etag));
        assertThat(set.getStatusCode()).as("%s", set.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(code(receiveRaw(id, hq, List.of(rline(line, "2.001"))))).isEqualTo("receipt_exceeds_order");
        received(id, hq, List.of(rline(line, "2")));
        assertThat(order(id).get("status").asString()).isEqualTo("received");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("11");
    }

    @Test
    void goodsGoIntoTheBranchOrStoreChosenAtTheCostReceivedAndSetTheProductPrices() {
        JsonNode o = sent(hq, orderLine(product, "10", 100));
        UUID id = uuid(o, "id");
        JsonNode line = order(id).get("lines").get(0);
        Map<String, Object> l = rline(line, "10");
        l.put("cost_minor", 120);
        l.put("sell_minor", 200);
        JsonNode r = received(id, br2, List.of(l));
        assertThat(r.get("branch_id").asString()).isEqualTo(br2.toString());
        assertThat(r.get("receipt_no").asString()).isEqualTo("GRBR2-000001");
        assertThat(stockAt(br2, product)).isEqualByComparingTo("10");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("0");
        JsonNode p = api.get("/products/" + product, ADMIN).getBody();
        assertThat(p.get("cost_minor").asLong()).isEqualTo(120);
        assertThat(p.get("sell_minor").asLong()).isEqualTo(200);
        assertThat(scalar(
                        "SELECT count(*) FROM retail_price_history WHERE tenant_id = ? AND source = 'purchase' AND source_id = ?",
                        t.tenantId(),
                        uuid(r, "id")))
                .isEqualTo(1);
        assertThat(scalar(
                        "SELECT unit_cost_minor FROM retail_stock_movements WHERE source_type = 'retail.goods_receipt' AND source_id = ?",
                        uuid(r, "id")))
                .isEqualTo(120);
        // The entry is in the branch the goods went into.
        assertThat(scalar(
                        "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_id = ? AND branch_id = ?",
                        t.tenantId(),
                        uuid(r, "id"),
                        br2))
                .isEqualTo(1);
    }

    @Test
    void aBlindReceiptNeedsASupplierAndTheProductAndCost() {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("qty", "3");
        l.put("cost_minor", 100);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", hq);
        b.put("lines", List.of(l));
        ResponseEntity<JsonNode> noSupplier = keyed("/goods-receipts", b, BUYER, "*");
        assertThat(noSupplier.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        l.remove("cost_minor");
        b.put("supplier_id", supplier);
        assertThat(keyed("/goods-receipts", b, BUYER, "*").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        l.put("cost_minor", 100);
        l.put("order_line_id", UUID.randomUUID());
        assertThat(keyed("/goods-receipts", b, BUYER, "*").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        l.remove("order_line_id");
        ResponseEntity<JsonNode> ok = keyed("/goods-receipts", b, BUYER, "*");
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody().has("order_id")).isFalse();
        assertThat(stockAt(hq, product)).isEqualByComparingTo("3");
        // A line of another order, or of none, is refused on a receipt against an order.
        JsonNode o = sent(hq, orderLine(product, "5", 100));
        JsonNode other = sent(hq, orderLine(product, "5", 100));
        JsonNode otherLine = order(uuid(other, "id")).get("lines").get(0);
        ResponseEntity<JsonNode> wrong = receiveRaw(uuid(o, "id"), hq, List.of(rline(otherLine, "1")));
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void packsAreReceivedInPackUnitsWithTheCostPerBaseUnitRoundedOnceAndTheRemainderInPurchaseRounding() {
        capability("units", true);
        UUID pen = api.product("TB-PEN", 83, 100);
        UUID piece = uuid(api.get("/products/" + pen, ADMIN).getBody(), "unit_id");
        UUID carton = api.unit("carton");
        assertThat(api.post("/products/" + pen + "/units", Map.of("unit_id", carton, "factor", "12"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> ol = orderLine(pen, "2", 1_000);
        ol.put("unit_id", carton);
        JsonNode o = sent(hq, ol);
        JsonNode line = order(uuid(o, "id")).get("lines").get(0);
        assertThat(line.get("qty").asString()).isEqualTo("24.000");
        assertThat(line.get("unit_qty").asString()).isEqualTo("2.000");
        assertThat(line.get("cost_minor").asLong()).isEqualTo(83);
        assertThat(line.get("unit_cost_minor").asLong()).isEqualTo(1_000);
        assertThat(o.get("total_minor").asLong()).isEqualTo(2_000);
        JsonNode r = received(uuid(o, "id"), hq, List.of(rline(line, "2")));
        assertThat(stockAt(hq, pen)).isEqualByComparingTo("24");
        assertThat(r.get("total_minor").asLong()).isEqualTo(2_000);
        // 24 x 83 = 1,992 goes to inventory; the 8 that does not divide exactly is purchase rounding.
        assertThat(posted("retail.goods_receipt", uuid(r, "id")))
                .containsEntry("inventory", new long[] {1_992, 0})
                .containsEntry("goods_received_not_invoiced", new long[] {0, 2_000})
                .containsEntry("purchase_rounding", new long[] {8, 0});
        // With the capability off a pack unit is refused, whatever the product has.
        capability("units", false);
        Map<String, Object> blindPack = new LinkedHashMap<>();
        blindPack.put("product_id", pen);
        blindPack.put("unit_id", carton);
        blindPack.put("qty", "1");
        blindPack.put("cost_minor", 1_000);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supplier_id", supplier);
        body.put("branch_id", hq);
        body.put("lines", List.of(blindPack));
        assertThat(keyed("/goods-receipts", body, BUYER, "*").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(piece).isNotNull();
    }

    @Test
    void aReceiptIsVoidedByReversalUnlessTheGoodsAreGoneOrAnInvoiceBillsIt() {
        JsonNode o = sent(hq, orderLine(product, "10", 100));
        UUID id = uuid(o, "id");
        JsonNode line = order(id).get("lines").get(0);
        JsonNode r = received(id, hq, List.of(rline(line, "4")));
        UUID rid = uuid(r, "id");
        // Some of it is sold: the receipt cannot be voided.
        assertThat(api.sell(hq, "cash", product, "3").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> gone =
                keyed("/goods-receipts/" + rid + "/void", Map.of("reason", "Wrong supplier"), BUYER, "*");
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(gone)).isEqualTo("receipt_stock_moved");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("1");
        // A second receipt that is still on the shelf is voided: stock back, entry reversed, the order goes back.
        JsonNode r2 = received(id, hq, List.of(rline(line, "6")));
        assertThat(order(id).get("status").asString()).isEqualTo("received");
        ResponseEntity<JsonNode> voided =
                keyed("/goods-receipts/" + uuid(r2, "id") + "/void", Map.of("reason", "Counted wrongly"), BUYER, "*");
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(voided.getBody().get("status").asString()).isEqualTo("voided");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("1");
        assertThat(order(id).get("status").asString()).isEqualTo("partly_received");
        assertThat(balance("goods_received_not_invoiced")).isEqualTo(-400);
        assertThat(keyed("/goods-receipts/" + uuid(r2, "id") + "/void", Map.of("reason", "Counted wrongly"), BUYER, "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        // Billed: the invoice goes first.
        JsonNode bl = api.get("/goods-receipts/" + uuid(received(id, hq, List.of(rline(line, "2"))), "id"), PAYER)
                .getBody();
        billed("INV-V-1", 200, "2026-11-01", List.of(bline(bl.get("lines").get(0), "2", 100)));
        ResponseEntity<JsonNode> billedVoid =
                keyed("/goods-receipts/" + uuid(bl, "id") + "/void", Map.of("reason", "Billed already"), BUYER, "*");
        assertThat(billedVoid.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(billedVoid)).isEqualTo("receipt_billed");
    }

    // ---- supplier invoices ------------------------------------------------------------------------

    @Test
    void anInvoiceAtTheReceivedPriceClearsGoodsReceivedNotInvoicedToZero() {
        JsonNode r = blind(hq, product, "10", 100);
        JsonNode rl = r.get("lines").get(0);
        ResponseEntity<JsonNode> unbilled = api.get("/suppliers/" + supplier + "/unbilled-receipts", PAYER);
        assertThat(unbilled.getBody().get("items")).hasSize(1);
        assertThat(unbilled.getBody().get("total_minor").asLong()).isEqualTo(1_000);
        assertThat(unbilled.getBody().get("items").get(0).get("unbilled_qty").asString())
                .isEqualTo("10.000");
        JsonNode b = billed("INV-100", 1_000, "2026-11-07", List.of(bline(rl, "10", 100)));
        assertThat(b.get("bill_no").asString()).isEqualTo("SBHQ-000001");
        assertThat(b.get("total_minor").asLong()).isEqualTo(1_000);
        assertThat(b.get("cleared_minor").asLong()).isEqualTo(1_000);
        assertThat(b.get("variance_minor").asLong()).isZero();
        assertThat(b.get("open_minor").asLong()).isEqualTo(1_000);
        assertThat(posted("retail.supplier_bill", uuid(b, "id")))
                .containsOnlyKeys("goods_received_not_invoiced", "trade_creditors")
                .containsEntry("goods_received_not_invoiced", new long[] {1_000, 0})
                .containsEntry("trade_creditors", new long[] {0, 1_000});
        assertThat(balance("goods_received_not_invoiced")).isZero();
        assertThat(balance("trade_creditors")).isEqualTo(-1_000);
        // The creditor leg carries the supplier as its subledger.
        assertThat(scalar("""
                        SELECT count(*) FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                         WHERE e.source_id = ? AND l.subledger_type = 'retail.supplier' AND l.subledger_id = ?
                        """, uuid(b, "id"), supplier)).isEqualTo(1);
        assertThat(api.get("/suppliers/" + supplier + "/unbilled-receipts", PAYER)
                        .getBody()
                        .get("items"))
                .isEmpty();
    }

    @Test
    void aPriceDifferenceIsPostedToPurchasePriceVarianceAndAboveTheToleranceNeedsAReason() {
        JsonNode r = blind(hq, product, "10", 100);
        JsonNode rl = r.get("lines").get(0);
        ResponseEntity<JsonNode> bare = billRaw("INV-V", 1_100, "2026-11-07", null, List.of(bline(rl, "10", 110)));
        assertThat(bare.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(bare)).isEqualTo("variance_reason_required");
        // The dry run says so too, and records nothing.
        ResponseEntity<JsonNode> dry = keyed(
                "/supplier-bills/match",
                billBody("INV-V", 1_100, "2026-11-07", null, List.of(bline(rl, "10", 110))),
                PAYER,
                "*");
        assertThat(dry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dry.getBody().get("reason_required").asBoolean()).isTrue();
        assertThat(dry.getBody().get("variance_minor").asLong()).isEqualTo(100);
        assertThat(dry.getBody().get("tolerance_minor").asLong()).isZero();
        assertThat(dry.getBody().get("total_mismatch").asBoolean()).isFalse();
        assertThat(scalar("SELECT count(*) FROM retail_supplier_bills WHERE tenant_id = ?", t.tenantId()))
                .isZero();
        assertThat(billRaw("INV-V", 1_100, "2026-11-07", "ok", List.of(bline(rl, "10", 110)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> ok =
                billRaw("INV-V", 1_100, "2026-11-07", "Supplier raised the price", List.of(bline(rl, "10", 110)));
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody().get("variance_minor").asLong()).isEqualTo(100);
        assertThat(ok.getBody().get("variance_reason").asString()).isEqualTo("Supplier raised the price");
        assertThat(posted("retail.supplier_bill", uuid(ok.getBody(), "id")))
                .containsEntry("goods_received_not_invoiced", new long[] {1_000, 0})
                .containsEntry("purchase_price_variance", new long[] {100, 0})
                .containsEntry("trade_creditors", new long[] {0, 1_100});
        // A lower price is a credit to the variance account.
        JsonNode r2 = blind(hq, product, "10", 100);
        ResponseEntity<JsonNode> cheaper = billRaw(
                "INV-W",
                900,
                "2026-11-07",
                "Discount agreed on the phone",
                List.of(bline(r2.get("lines").get(0), "10", 90)));
        assertThat(cheaper.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(posted("retail.supplier_bill", uuid(cheaper.getBody(), "id")))
                .containsEntry("purchase_price_variance", new long[] {0, 100});
        assertThat(balance("purchase_price_variance")).isZero();
    }

    @Test
    void theToleranceIsInBasisPointsOfTheValueClearedAndEqualIsAllowed() {
        String etag = api.get("/purchasing/settings", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> set = api.call(
                HttpMethod.PUT,
                "/purchasing/settings",
                Map.of("bill_tolerance_bp", 1000),
                SETTINGS,
                "*",
                Map.of("If-Match", etag));
        assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode a = blind(hq, product, "10", 100).get("lines").get(0);
        // 10 percent of 1,000 is 100: a difference of exactly 100 needs no reason, 110 does.
        assertThat(billRaw("INV-T1", 1_100, "2026-11-07", null, List.of(bline(a, "10", 110)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode b = blind(hq, product, "10", 100).get("lines").get(0);
        ResponseEntity<JsonNode> over = billRaw("INV-T2", 1_110, "2026-11-07", null, List.of(bline(b, "10", 111)));
        assertThat(code(over)).isEqualTo("variance_reason_required");
        assertThat(billRaw("INV-T2", 1_110, "2026-11-07", "Freight added by supplier", List.of(bline(b, "10", 111)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void partialBillingClearsTheReceiptLineExactlyTheLastPieceTakingTheRemainder() {
        // 2.5 at 101 is 252.5, rounded half up once to 253.
        JsonNode r = blind(hq, product, "2.5", 101);
        assertThat(r.get("total_minor").asLong()).isEqualTo(253);
        JsonNode rl = r.get("lines").get(0);
        JsonNode one = billed("INV-P1", 101, "2026-11-07", List.of(bline(rl, "1", 101)));
        JsonNode two = billed("INV-P2", 101, "2026-11-07", List.of(bline(rl, "1", 101)));
        JsonNode three = billed("INV-P3", 51, "2026-11-07", List.of(bline(rl, "0.5", 101)));
        long cleared = one.get("cleared_minor").asLong()
                + two.get("cleared_minor").asLong()
                + three.get("cleared_minor").asLong();
        assertThat(cleared).isEqualTo(253);
        assertThat(one.get("cleared_minor").asLong()).isEqualTo(101);
        assertThat(three.get("cleared_minor").asLong()).isEqualTo(51);
        assertThat(balance("goods_received_not_invoiced")).isZero();
        // All billed: any more is more than was received.
        ResponseEntity<JsonNode> more = billRaw("INV-P4", 101, "2026-11-07", null, List.of(bline(rl, "1", 101)));
        assertThat(more.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(more)).isEqualTo("bill_exceeds_received");
    }

    @Test
    void anInvoiceMayNotBillMoreThanWasReceivedAndTheTotalMustMatchItsLines() {
        JsonNode rl = blind(hq, product, "10", 100).get("lines").get(0);
        ResponseEntity<JsonNode> more = billRaw("INV-X", 1_100, "2026-11-07", null, List.of(bline(rl, "11", 100)));
        assertThat(code(more)).isEqualTo("bill_exceeds_received");
        ResponseEntity<JsonNode> mismatch = billRaw("INV-X", 999, "2026-11-07", null, List.of(bline(rl, "10", 100)));
        assertThat(mismatch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(mismatch)).isEqualTo("bill_total_mismatch");
        ResponseEntity<JsonNode> dry = keyed(
                "/supplier-bills/match",
                billBody("INV-X", 999, "2026-11-07", null, List.of(bline(rl, "10", 100))),
                PAYER,
                "*");
        assertThat(dry.getBody().get("total_mismatch").asBoolean()).isTrue();
        // A voided receipt, a date in the future and a due date before the invoice are refused.
        Map<String, Object> early = billBody("INV-X", 1_000, "2026-11-07", null, List.of(bline(rl, "10", 100)));
        early.put("due_date", UTC_DAY);
        assertThat(keyed("/supplier-bills", early, PAYER, "*").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        Map<String, Object> future = billBody("INV-X", 1_000, "2026-11-07", null, List.of(bline(rl, "10", 100)));
        future.put("bill_date", "2026-10-09");
        assertThat(keyed("/supplier-bills", future, PAYER, "*").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(billRaw("INV-X", 1_000, "2026-11-07", null, List.of(bline(rl, "10", 100)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aSuppliersInvoiceNumberIsRecordedOnceUntilItIsVoided() {
        JsonNode rl = blind(hq, product, "10", 100).get("lines").get(0);
        JsonNode first = billed("INV-D", 500, "2026-11-07", List.of(bline(rl, "5", 100)));
        ResponseEntity<JsonNode> dup = billRaw("inv-d", 500, "2026-11-07", null, List.of(bline(rl, "5", 100)));
        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(dup)).isEqualTo("duplicate_supplier_bill");
        ResponseEntity<JsonNode> voided = keyed(
                "/supplier-bills/" + uuid(first, "id") + "/void",
                Map.of("reason", "Entered against the wrong lines"),
                PAYER,
                "*");
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(voided.getBody().get("status").asString()).isEqualTo("voided");
        assertThat(voided.getBody().get("open_minor").asLong()).isZero();
        assertThat(balance("goods_received_not_invoiced")).isEqualTo(-1_000);
        assertThat(balance("trade_creditors")).isZero();
        assertThat(billRaw("INV-D", 1_000, "2026-11-07", null, List.of(bline(rl, "10", 100)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(keyed(
                                "/supplier-bills/" + uuid(first, "id") + "/void",
                                Map.of("reason", "Again and again"),
                                PAYER,
                                "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void oneInvoiceForReceiptsInTwoBranchesPostsOneEntryInEach() {
        JsonNode a = blind(hq, product, "10", 100).get("lines").get(0);
        JsonNode b = blind(br2, product, "5", 100).get("lines").get(0);
        JsonNode bill = billed("INV-M", 1_500, "2026-11-07", List.of(bline(a, "10", 100), bline(b, "5", 100)));
        assertThat(bill.get("branch_id").asString()).isEqualTo(hq.toString());
        assertThat(scalar(
                        "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_id = ?",
                        t.tenantId(),
                        uuid(bill, "id")))
                .isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM retail_supplier_bill_entries WHERE bill_id = ?", uuid(bill, "id")))
                .isEqualTo(2);
        assertThat(balance("goods_received_not_invoiced")).isZero();
        assertThat(balance("trade_creditors")).isEqualTo(-1_500);
        // A caller scoped to one branch cannot bill the other's receipts.
        JsonNode c = blind(br2, product, "5", 100).get("lines").get(0);
        ResponseEntity<JsonNode> scoped = api.postKeyed(
                "/supplier-bills",
                billBody("INV-M2", 500, "2026-11-07", null, List.of(bline(c, "5", 100))),
                PAYER,
                hq.toString(),
                key());
        assertThat(scoped.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // Voiding reverses both entries.
        assertThat(keyed(
                                "/supplier-bills/" + uuid(bill, "id") + "/void",
                                Map.of("reason", "Wrong supplier invoice"),
                                PAYER,
                                "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(balance("trade_creditors")).isZero();
    }

    @Test
    void theOrderShowsOrderedReceivedAndBilledSideBySide() {
        JsonNode o = sent(hq, orderLine(product, "10", 100));
        UUID id = uuid(o, "id");
        JsonNode line = order(id).get("lines").get(0);
        JsonNode r = received(id, hq, List.of(rline(line, "6")));
        billed("INV-3W", 300, "2026-11-07", List.of(bline(r.get("lines").get(0), "3", 100)));
        JsonNode now = order(id).get("lines").get(0);
        assertThat(now.get("qty").asString()).isEqualTo("10.000");
        assertThat(now.get("received_qty").asString()).isEqualTo("6.000");
        assertThat(now.get("billed_qty").asString()).isEqualTo("3.000");
        assertThat(now.get("billed_minor").asLong()).isEqualTo(300);
        // Without the payables permission the billed figures are not sent.
        JsonNode plain =
                api.get("/purchase-orders/" + id, BUYER).getBody().get("lines").get(0);
        assertThat(plain.has("billed_qty")).isFalse();
        assertThat(plain.get("received_qty").asString()).isEqualTo("6.000");
    }

    // ---- supplier payments ------------------------------------------------------------------------

    JsonNode billOf(String ref, long amount, String due) {
        // The goods are received on the invoice's date, which is the due date when the invoice is already overdue.
        JsonNode rl = blind(hq, product, "1", amount, due.compareTo(TODAY) < 0 ? due : TODAY)
                .get("lines")
                .get(0);
        return billed(ref, amount, due, List.of(bline(rl, "1", amount)));
    }

    @Test
    void aPartPaymentLeavesTheRestOpenAndPostsAgainstTradeCreditors() {
        JsonNode b = billOf("INV-1", 1_000, "2026-11-07");
        JsonNode p = paid(hq, "cash", 400);
        assertThat(p.get("payment_no").asString()).isEqualTo("SPHQ-000001");
        assertThat(p.get("paid_on").asString()).isEqualTo(TODAY);
        assertThat(p.get("allocations")).hasSize(1);
        assertThat(p.get("allocations").get(0).get("bill_id").asString())
                .isEqualTo(b.get("id").asString());
        assertThat(p.get("allocations").get(0).get("amount_minor").asLong()).isEqualTo(400);
        assertThat(bill(uuid(b, "id")).get("paid_minor").asLong()).isEqualTo(400);
        assertThat(bill(uuid(b, "id")).get("open_minor").asLong()).isEqualTo(600);
        assertThat(posted("retail.supplier_payment", uuid(p, "id")))
                .containsEntry("trade_creditors", new long[] {400, 0})
                .containsEntry("cash_on_hand", new long[] {0, 400});
        assertThat(balance("trade_creditors")).isEqualTo(-600);
        JsonNode bank = paid(hq, "bank", 600);
        assertThat(posted("retail.supplier_payment", uuid(bank, "id"))).containsEntry("bank", new long[] {0, 600});
        assertThat(bill(uuid(b, "id")).get("open_minor").asLong()).isZero();
        assertThat(balance("trade_creditors")).isZero();
        // Nothing is owed, so nothing can be paid.
        ResponseEntity<JsonNode> more = payRaw(hq, "cash", 1, null);
        assertThat(code(more)).isEqualTo("payment_exceeds_open");
    }

    @Test
    void aPaymentFollowsTheOldestDueDateFirstAndSettlesSeveralInvoices() {
        JsonNode later = billOf("INV-A", 500, "2026-11-20");
        JsonNode sooner = billOf("INV-B", 700, "2026-10-10");
        JsonNode p = paid(hq, "mobile_money", 800);
        JsonNode allocations = p.get("allocations");
        assertThat(allocations).hasSize(2);
        assertThat(allocations.get(0).get("bill_id").asString())
                .isEqualTo(sooner.get("id").asString());
        assertThat(allocations.get(0).get("amount_minor").asLong()).isEqualTo(700);
        assertThat(allocations.get(1).get("bill_id").asString())
                .isEqualTo(later.get("id").asString());
        assertThat(allocations.get(1).get("amount_minor").asLong()).isEqualTo(100);
        assertThat(bill(uuid(sooner, "id")).get("open_minor").asLong()).isZero();
        assertThat(bill(uuid(later, "id")).get("open_minor").asLong()).isEqualTo(400);
        assertThat(posted("retail.supplier_payment", uuid(p, "id"))).containsEntry("mobile_money", new long[] {0, 800});
    }

    @Test
    void aManualAllocationMustAddUpAndStayInsideWhatIsOpen() {
        JsonNode a = billOf("INV-A", 500, "2026-11-20");
        JsonNode b = billOf("INV-B", 700, "2026-10-10");
        ResponseEntity<JsonNode> short_ = payRaw(hq, "cash", 600, List.of(alloc("bill_id", uuid(a, "id"), 500)));
        assertThat(code(short_)).isEqualTo("allocation_total_mismatch");
        ResponseEntity<JsonNode> over = payRaw(hq, "cash", 600, List.of(alloc("bill_id", uuid(a, "id"), 600)));
        assertThat(code(over)).isEqualTo("allocation_exceeds_open");
        ResponseEntity<JsonNode> both = payRaw(
                hq,
                "cash",
                100,
                List.of(Map.of("bill_id", uuid(a, "id"), "purchase_id", UUID.randomUUID(), "amount_minor", 100)));
        assertThat(both.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> stranger = payRaw(hq, "cash", 100, List.of(alloc("bill_id", UUID.randomUUID(), 100)));
        assertThat(stranger.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> ok = payRaw(
                hq, "cash", 600, List.of(alloc("bill_id", uuid(a, "id"), 200), alloc("bill_id", uuid(b, "id"), 400)));
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(bill(uuid(a, "id")).get("open_minor").asLong()).isEqualTo(300);
        assertThat(bill(uuid(b, "id")).get("open_minor").asLong()).isEqualTo(300);
        // Allocations on a payment that says oldest first are refused.
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("supplier_id", supplier);
        mixed.put("branch_id", hq);
        mixed.put("method", "cash");
        mixed.put("amount_minor", 100);
        mixed.put("allocations", List.of(alloc("bill_id", uuid(a, "id"), 100)));
        assertThat(keyed("/supplier-payments", mixed, PAYER, "*").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aCreditRestockIsAnOpenItemThatAPaymentCanSettle() {
        UUID purchase = RetailTestSupport.id(restock("credit"));
        JsonNode items =
                api.get("/suppliers/" + supplier + "/open-items", PAYER).getBody();
        assertThat(items.get("items")).hasSize(1);
        assertThat(items.get("items").get(0).get("kind").asString()).isEqualTo("restock");
        assertThat(items.get("items").get(0).get("open_minor").asLong()).isEqualTo(500);
        assertThat(items.get("items").get(0).get("due_date").asString()).isEqualTo(TODAY);
        JsonNode p = paid(hq, "cash", 250);
        assertThat(p.get("allocations").get(0).get("purchase_id").asString()).isEqualTo(purchase.toString());
        assertThat(api.get("/suppliers/" + supplier + "/open-items", PAYER)
                        .getBody()
                        .get("total_open_minor")
                        .asLong())
                .isEqualTo(250);
        assertThat(balance("trade_creditors")).isEqualTo(-250);
        // A restock paid in cash at the time is not a payable.
        restock("cash");
        assertThat(api.get("/suppliers/" + supplier + "/open-items", PAYER)
                        .getBody()
                        .get("items"))
                .hasSize(1);
    }

    @Test
    void aPaymentIsReversedAsAWholeOnceAndItsAllocationsStopCounting() {
        JsonNode b = billOf("INV-R", 1_000, "2026-11-07");
        JsonNode p = paid(hq, "cash", 1_000);
        assertThat(bill(uuid(b, "id")).get("open_minor").asLong()).isZero();
        // A bill with a payment standing cannot be voided.
        ResponseEntity<JsonNode> blocked = keyed(
                "/supplier-bills/" + uuid(b, "id") + "/void", Map.of("reason", "Entered twice by mistake"), PAYER, "*");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(blocked)).isEqualTo("bill_paid");
        assertThat(keyed("/supplier-payments/" + uuid(p, "id") + "/reverse", Map.of("reason", "x"), PAYER, "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> reversed = keyed(
                "/supplier-payments/" + uuid(p, "id") + "/reverse",
                Map.of("reason", "Bounced at the bank"),
                PAYER,
                "*");
        assertThat(reversed.getStatusCode()).as("%s", reversed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(reversed.getBody().get("status").asString()).isEqualTo("reversed");
        assertThat(reversed.getBody().get("reversed_on").asString()).isEqualTo(TODAY);
        assertThat(bill(uuid(b, "id")).get("open_minor").asLong()).isEqualTo(1_000);
        assertThat(balance("trade_creditors")).isEqualTo(-1_000);
        assertThat(balance("cash_on_hand")).isZero();
        ResponseEntity<JsonNode> again = keyed(
                "/supplier-payments/" + uuid(p, "id") + "/reverse", Map.of("reason", "Once more please"), PAYER, "*");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(again)).isEqualTo("payment_reversed");
        // The bill is open again, and can be paid again.
        paid(hq, "bank", 1_000);
        assertThat(bill(uuid(b, "id")).get("open_minor").asLong()).isZero();
    }

    @Test
    void aCashPaymentToASupplierIsACashOutOfTheTillThatDayAndAReversalPutsItBack() {
        billOf("INV-C", 1_000, "2026-11-07");
        JsonNode cash = paid(hq, "cash", 400);
        paid(hq, "bank", 200);
        JsonNode day = dailyRow(TODAY);
        assertThat(day.get("cash_purchases_minor").asLong()).isEqualTo(400);
        long expected = day.get("expected_to_bank_minor").asLong();
        assertThat(expected).isEqualTo(-400);
        keyed(
                "/supplier-payments/" + uuid(cash, "id") + "/reverse",
                Map.of("reason", "Paid the wrong supplier"),
                PAYER,
                "*");
        assertThat(dailyRow(TODAY).get("cash_purchases_minor").asLong()).isZero();
    }

    JsonNode dailyRow(String day) {
        ResponseEntity<JsonNode> r =
                api.get("/reports/cash/daily?branch_id=" + hq + "&from=" + day + "&to=" + day, PAYER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        for (JsonNode row : r.getBody().get("items")) {
            if (row.get("business_date").asString().equals(day)) {
                return row;
            }
        }
        throw new AssertionError("no row for " + day + " in " + r.getBody());
    }

    @Test
    void thePaymentRoutesAreIdempotent() {
        billOf("INV-I", 1_000, "2026-11-07");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supplier_id", supplier);
        body.put("branch_id", hq);
        body.put("method", "cash");
        body.put("amount_minor", 300);
        String k = key();
        ResponseEntity<JsonNode> first = api.postKeyed("/supplier-payments", body, PAYER, "*", k);
        ResponseEntity<JsonNode> replay = api.postKeyed("/supplier-payments", body, PAYER, "*", k);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("id").asString())
                .isEqualTo(first.getBody().get("id").asString());
        assertThat(scalar("SELECT count(*) FROM retail_supplier_payments WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
        body.put("amount_minor", 301);
        ResponseEntity<JsonNode> reused = api.postKeyed("/supplier-payments", body, PAYER, "*", k);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(reused)).isEqualTo("idempotency_key_reused");
        // The same for an order and a receipt.
        Map<String, Object> ob = orderBody(supplier, hq, orderLine(product, "1", 100));
        String ok = key();
        UUID a = uuid(api.postKeyed("/purchase-orders", ob, BUYER, "*", ok).getBody(), "id");
        UUID b = uuid(api.postKeyed("/purchase-orders", ob, BUYER, "*", ok).getBody(), "id");
        assertThat(a).isEqualTo(b);
        assertThat(api.call(HttpMethod.POST, "/purchase-orders", ob, BUYER, "*", Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // ---- concurrency ------------------------------------------------------------------------------

    <T> List<T> together(List<Callable<T>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(work.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> w : work) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return w.call();
                }));
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoPaymentsCannotSpendTheSameBalance() throws Exception {
        JsonNode b = billOf("INV-K", 1_000, "2026-11-07");
        List<ResponseEntity<JsonNode>> results =
                together(List.of(() -> payRaw(hq, "cash", 600, null), () -> payRaw(hq, "bank", 600, null)));
        long created = results.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.CREATED)
                .count();
        assertThat(created).isEqualTo(1);
        assertThat(results.stream()
                        .filter(r -> r.getStatusCode() != HttpStatus.CREATED)
                        .findFirst()
                        .orElseThrow()
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("payment_exceeds_open");
        assertThat(bill(uuid(b, "id")).get("open_minor").asLong()).isEqualTo(400);
    }

    @Test
    void twoInvoicesCannotBillTheSameReceivedGoods() throws Exception {
        JsonNode rl = blind(hq, product, "10", 100).get("lines").get(0);
        List<ResponseEntity<JsonNode>> results = together(List.of(
                () -> billRaw("INV-Q1", 1_000, "2026-11-07", null, List.of(bline(rl, "10", 100))),
                () -> billRaw("INV-Q2", 1_000, "2026-11-07", null, List.of(bline(rl, "10", 100)))));
        assertThat(results.stream()
                        .filter(r -> r.getStatusCode() == HttpStatus.CREATED)
                        .count())
                .isEqualTo(1);
        assertThat(balance("goods_received_not_invoiced")).isZero();
        assertThat(scalar(
                        "SELECT count(*) FROM retail_supplier_bills WHERE tenant_id = ? AND status = 'posted'",
                        t.tenantId()))
                .isEqualTo(1);
    }

    @Test
    void twoReceiptsCannotTogetherExceedTheOrder() throws Exception {
        JsonNode o = sent(hq, orderLine(product, "10", 100));
        UUID id = uuid(o, "id");
        JsonNode line = order(id).get("lines").get(0);
        List<ResponseEntity<JsonNode>> results = together(List.of(
                () -> receiveRaw(id, hq, List.of(rline(line, "7"))),
                () -> receiveRaw(id, br2, List.of(rline(line, "7")))));
        assertThat(results.stream()
                        .filter(r -> r.getStatusCode() == HttpStatus.CREATED)
                        .count())
                .isEqualTo(1);
        assertThat(stockAt(hq, product).add(stockAt(br2, product))).isEqualByComparingTo("7");
    }

    // ---- the payables report, on the business day -----------------------------------------------------

    @Test
    void theReportAgesWhatIsOwedByDaysPastDueOnTheBusinessDayAndShowsWhatIsNotInvoiced() {
        // Business today is 2026-10-08 in Kampala (2026-10-07 in UTC).
        billOf("INV-NOW", 100, TODAY);
        billOf("INV-1D", 200, UTC_DAY);
        billOf("INV-40D", 400, "2026-08-29");
        billOf("INV-70D", 800, "2026-07-30");
        billOf("INV-130D", 1_600, "2026-05-31");
        restock("credit");
        blind(hq, product2, "3", 250);
        JsonNode report = api.get("/reports/supplier-payables", PAYER).getBody();
        assertThat(report.get("as_of").asString()).isEqualTo(TODAY);
        JsonNode row = report.get("suppliers").get(0);
        assertThat(row.get("supplier_name").asString()).isEqualTo("Test Supplier 01");
        // Not yet due: the invoice due today (0 days) and the restock bought today.
        assertThat(row.get("not_due_minor").asLong()).isEqualTo(100 + 500);
        // One day past due on the business day, though still the same UTC day.
        assertThat(row.get("days1_to30_minor").asLong()).isEqualTo(200);
        assertThat(row.get("days31_to60_minor").asLong()).isEqualTo(400);
        assertThat(row.get("days61_to90_minor").asLong()).isEqualTo(800);
        assertThat(row.get("over90_minor").asLong()).isEqualTo(1_600);
        assertThat(row.get("owed_minor").asLong()).isEqualTo(100 + 500 + 200 + 400 + 800 + 1_600);
        assertThat(row.get("unbilled_receipts_minor").asLong()).isEqualTo(750);
        assertThat(report.get("totals").get("owed_minor").asLong())
                .isEqualTo(row.get("owed_minor").asLong());
        // After a payment the oldest is settled first.
        paid(hq, "bank", 1_600);
        JsonNode after = api.get("/reports/supplier-payables", PAYER)
                .getBody()
                .get("suppliers")
                .get(0);
        assertThat(after.get("over90_minor").asLong()).isZero();
        // The listing tools see the unbilled receipt only in their scope.
        JsonNode scoped = api.call(HttpMethod.GET, "/reports/supplier-payables", null, PAYER, br2.toString(), Map.of())
                .getBody();
        assertThat(scoped.get("suppliers")).isEmpty();
    }

    // ---- settings ------------------------------------------------------------------------------

    @Test
    void theTolerancesStartAtZeroAndAreChangedUnderIfMatchAndAudited() {
        ResponseEntity<JsonNode> read = api.get("/purchasing/settings", SETTINGS);
        assertThat(read.getBody().get("over_receipt_bp").asInt()).isZero();
        assertThat(read.getBody().get("bill_tolerance_bp").asInt()).isZero();
        assertThat(read.getBody().has("order_approval_threshold_minor")).isFalse();
        String etag = read.getHeaders().getETag();
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/purchasing/settings",
                                Map.of("bill_tolerance_bp", 50),
                                SETTINGS,
                                "*",
                                Map.of())
                        .getStatusCode()
                        .value())
                .isEqualTo(428);
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/purchasing/settings",
                                Map.of("bill_tolerance_bp", 10_001),
                                SETTINGS,
                                "*",
                                Map.of("If-Match", etag))
                        .getStatusCode()
                        .is4xxClientError())
                .isTrue();
        ResponseEntity<JsonNode> ok = api.call(
                HttpMethod.PUT,
                "/purchasing/settings",
                Map.of("bill_tolerance_bp", 50),
                SETTINGS,
                "*",
                Map.of("If-Match", etag));
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody().get("bill_tolerance_bp").asInt()).isEqualTo(50);
        assertThat(ok.getBody().get("version").asInt()).isGreaterThan(Integer.parseInt(etag.replace("\"", "")));
        ResponseEntity<JsonNode> stale = api.call(
                HttpMethod.PUT,
                "/purchasing/settings",
                Map.of("bill_tolerance_bp", 60),
                SETTINGS,
                "*",
                Map.of("If-Match", etag));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(scalar(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.purchasing.settings_changed'",
                        t.tenantId()))
                .isEqualTo(1);
        threshold(5_000);
        assertThat(api.get("/purchasing/settings", SETTINGS)
                        .getBody()
                        .get("order_approval_threshold_minor")
                        .asLong())
                .isEqualTo(5_000);
    }

    // ---- the database guards ------------------------------------------------------------------------

    @Test
    void receiptsBillsAndPaymentsCanOnlyBeVoidedOrReversedOnceAndTheirLinesNeverChange() {
        JsonNode r = blind(hq, product, "10", 100);
        JsonNode b = billed(
                "INV-G", 1_000, "2026-11-07", List.of(bline(r.get("lines").get(0), "10", 100)));
        JsonNode p = paid(hq, "cash", 500);
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_goods_receipts SET total_minor = 1 WHERE id = ?")
                        .param(uuid(r, "id"))
                        .update())
                .rootCause()
                .hasMessageContaining("can only be voided");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_goods_receipt_lines SET qty = 1 WHERE receipt_id = ?")
                        .param(uuid(r, "id"))
                        .update())
                .rootCause()
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_supplier_bills SET total_minor = 1 WHERE id = ?")
                        .param(uuid(b, "id"))
                        .update())
                .rootCause()
                .hasMessageContaining("can only be voided");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("DELETE FROM retail_supplier_bill_lines WHERE bill_id = ?")
                        .param(uuid(b, "id"))
                        .update())
                .rootCause()
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_supplier_payments SET amount_minor = 1 WHERE id = ?")
                        .param(uuid(p, "id"))
                        .update())
                .rootCause()
                .hasMessageContaining("can only be reversed");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("DELETE FROM retail_supplier_payment_allocations WHERE payment_id = ?")
                        .param(uuid(p, "id"))
                        .update())
                .rootCause()
                .hasMessageContaining("append-only");
        // A row cannot be inserted already voided or reversed.
        assertThatThrownBy(
                        () -> TestDatabase.owner().sql("""
                                INSERT INTO retail_supplier_payments (id, tenant_id, payment_no, branch_seq, branch_id, supplier_id,
                                    paid_on, currency, method, amount_minor, allocation, journal_entry_id, created_by, status,
                                    reversed_at, reversed_on, reversed_by, reverse_reason, reversal_entry_id)
                                SELECT gen_random_uuid(), tenant_id, 'SPX-1', 99, branch_id, supplier_id, paid_on, currency, method,
                                       amount_minor, allocation, journal_entry_id, created_by, 'reversed', now(), paid_on,
                                       created_by, 'x', journal_entry_id FROM retail_supplier_payments WHERE id = ?
                                """).param(uuid(p, "id")).update())
                .rootCause()
                .hasMessageContaining("recorded posted");
    }

    @Test
    void anotherTenantWithPurchasingOnSeesNoneOfOurDocuments() {
        TestDatabase.Fixture other = TestDatabase.tenant("retail-trade-buy-other", false, true);
        RetailTestSupport theirs = new RetailTestSupport(http, other);
        String etag =
                theirs.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        assertThat(theirs.call(
                                HttpMethod.PUT,
                                "/settings/capabilities",
                                Map.of("purchasing", true),
                                SETTINGS,
                                "*",
                                etag == null ? Map.of() : Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode mine = sent(hq, orderLine(product, "1", 100));
        JsonNode receipt = blind(hq, product, "1", 100);
        JsonNode bill = billed(
                "INV-ISO", 100, "2026-11-07", List.of(bline(receipt.get("lines").get(0), "1", 100)));
        JsonNode payment = paid(hq, "cash", 100);
        assertThat(theirs.get("/purchase-orders", PAYER).getBody().get("items")).isEmpty();
        assertThat(theirs.get("/purchase-orders/" + uuid(mine, "id"), PAYER).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(theirs.get("/goods-receipts/" + uuid(receipt, "id"), PAYER).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(theirs.get("/supplier-bills/" + uuid(bill, "id"), PAYER).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(theirs.get("/supplier-payments/" + uuid(payment, "id"), PAYER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(theirs.get("/reports/supplier-payables", PAYER).getBody().get("suppliers"))
                .isEmpty();
    }

    @Test
    void withTheCapabilityOffEveryWriteIsNotFoundToo() {
        capability("purchasing", false);
        UUID any = UUID.randomUUID();
        Map<String, Object> reason = Map.of("reason", "Switched off, so nothing");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("supplier_id", any);
        receipt.put("branch_id", hq);
        receipt.put("lines", List.of(Map.of("product_id", any, "qty", "1", "cost_minor", 1)));
        Map<String, Object> bill = billBody(
                "INV-OFF",
                1,
                "2026-11-07",
                null,
                List.of(Map.of("receipt_line_id", any, "qty", "1", "unit_price_minor", 1)));
        bill.put("supplier_id", any);
        Map<String, Map<String, Object>> writes = new LinkedHashMap<>();
        writes.put("/goods-receipts", receipt);
        writes.put("/supplier-bills", bill);
        writes.put("/supplier-bills/match", bill);
        writes.put("/purchase-orders/" + any + "/send", Map.of());
        writes.put("/purchase-orders/" + any + "/cancel", reason);
        writes.put("/goods-receipts/" + any + "/void", reason);
        writes.put("/supplier-bills/" + any + "/void", reason);
        writes.forEach((path, body) -> {
            ResponseEntity<JsonNode> r = keyed(path, body, PAYER, "*");
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(code(r)).as(path).isEqualTo("capability_not_enabled");
        });
        ResponseEntity<JsonNode> put = api.call(
                HttpMethod.PUT,
                "/purchase-orders/" + any,
                orderBody(supplier, hq, orderLine(product, "1", 100)),
                PAYER,
                "*",
                Map.of("If-Match", "1"));
        assertThat(code(put)).isEqualTo("capability_not_enabled");
    }

    @Test
    void anOrderOfAnotherBranchIsNotReceivedAgainstByAScopedBuyer() {
        JsonNode o = sent(br2, orderLine(product, "5", 100));
        JsonNode line = order(uuid(o, "id")).get("lines").get(0);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("order_id", uuid(o, "id"));
        b.put("branch_id", hq);
        b.put("lines", List.of(rline(line, "99")));
        ResponseEntity<JsonNode> r = api.postKeyed("/goods-receipts", b, BUYER, hq.toString(), key());
        // Not found, and nothing of the other branch's order (its number, its quantities) in the answer.
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().toString()).doesNotContain("POBR2");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("0");
    }

    @Test
    void anInvoiceIsNotDatedBeforeTheGoodsItBills() {
        JsonNode rl = blind(hq, product, "1", 100).get("lines").get(0);
        Map<String, Object> b = billBody("INV-EARLY", 100, "2026-11-07", null, List.of(bline(rl, "1", 100)));
        b.put("bill_date", UTC_DAY);
        ResponseEntity<JsonNode> r = keyed("/supplier-bills", b, PAYER, "*");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("bill_before_receipt");
    }

    @Test
    void theDebtSitsInTheBranchThatOwesItAndAPaymentFromAnotherBranchClearsThroughInterBranchClearing() {
        // One invoice for goods received in both branches: the debt is the bill branch's (the first receipt's).
        JsonNode a = blind(hq, product, "10", 100).get("lines").get(0);
        JsonNode c = blind(br2, product, "5", 100).get("lines").get(0);
        JsonNode both = billed("INV-BR", 1_500, "2026-11-07", List.of(bline(a, "10", 100), bline(c, "5", 100)));
        assertThat(balance("trade_creditors", hq)).isEqualTo(-1_500);
        assertThat(balance("trade_creditors", br2)).isZero();
        assertThat(balance("interbranch_clearing", hq)).isEqualTo(500);
        assertThat(balance("interbranch_clearing", br2)).isEqualTo(-500);
        assertThat(balance("goods_received_not_invoiced", hq)).isZero();
        assertThat(balance("goods_received_not_invoiced", br2)).isZero();
        // An invoice of the second branch alone, paid from head office: the debt is cleared where it sat.
        JsonNode d = blind(br2, product, "6", 100).get("lines").get(0);
        billed("INV-B2", 600, "2026-11-08", List.of(bline(d, "6", 100)));
        assertThat(balance("trade_creditors", br2)).isEqualTo(-600);
        JsonNode p = paid(hq, "bank", 2_100);
        assertThat(balance("trade_creditors", hq)).isZero();
        assertThat(balance("trade_creditors", br2)).isZero();
        assertThat(balance("bank", hq)).isEqualTo(-2_100);
        assertThat(balance("interbranch_clearing")).isZero();
        // The reversal puts every branch back.
        assertThat(keyed(
                                "/supplier-payments/" + uuid(p, "id") + "/reverse",
                                Map.of("reason", "Wrong account used"),
                                PAYER,
                                "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(balance("trade_creditors", hq)).isEqualTo(-1_500);
        assertThat(balance("trade_creditors", br2)).isEqualTo(-600);
        assertThat(balance("bank", hq)).isZero();
        assertThat(balance("interbranch_clearing")).isZero();
        assertThat(bill(uuid(both, "id")).get("open_minor").asLong()).isEqualTo(1_500);
    }

    @Test
    void aReceiptBeingPostedAndAnotherBeingVoidedOnTheSameOrderDoNotDeadlock() throws Exception {
        for (int i = 0; i < 4; i++) {
            JsonNode o = sent(hq, orderLine(product, "100", 100));
            UUID id = uuid(o, "id");
            JsonNode line = order(id).get("lines").get(0);
            JsonNode first = received(id, hq, List.of(rline(line, "10")));
            List<ResponseEntity<JsonNode>> results = together(List.of(
                    () -> keyed(
                            "/goods-receipts/" + uuid(first, "id") + "/void",
                            Map.of("reason", "Counted wrongly"),
                            BUYER,
                            "*"),
                    () -> receiveRaw(id, hq, List.of(rline(line, "10")))));
            assertThat(results.get(0).getStatusCode())
                    .as("%s", results.get(0).getBody())
                    .isEqualTo(HttpStatus.OK);
            assertThat(results.get(1).getStatusCode())
                    .as("%s", results.get(1).getBody())
                    .isEqualTo(HttpStatus.CREATED);
        }
    }

    // ---- search ------------------------------------------------------------------------------------

    @Test
    void listsAreSearchedByNumberAndBySupplierName() {
        JsonNode a = draft(hq, orderLine(product, "1", 100));
        UUID other = supplier("Test Wholesaler Zed");
        orderRaw(orderBody(other, hq, orderLine(product, "1", 100)));
        JsonNode byNumber =
                api.get("/purchase-orders?q=pohq-000001", BUYER).getBody().get("items");
        assertThat(byNumber).hasSize(1);
        assertThat(byNumber.get(0).get("id").asString()).isEqualTo(a.get("id").asString());
        assertThat(api.get("/purchase-orders?q=zed", BUYER).getBody().get("items"))
                .hasSize(1);
        assertThat(api.get("/purchase-orders?status=sent", BUYER).getBody().get("items"))
                .isEmpty();
        assertThat(api.get("/purchase-orders?status=bogus", BUYER).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        blind(hq, product, "1", 100);
        assertThat(api.get("/goods-receipts?q=grhq", BUYER).getBody().get("items"))
                .hasSize(1);
        assertThat(api.get("/goods-receipts?q=supplier", BUYER).getBody().get("items"))
                .hasSize(1);
    }

    // ---- the whole cycle reconciles ---------------------------------------------------------------------

    @Test
    void aWholeCycleLeavesGoodsReceivedNotInvoicedAndTradeCreditorsAtZeroAndInventoryAtStockValue() {
        JsonNode o = sent(hq, orderLine(product, "10", 100), orderLine(product2, "4", 250));
        UUID id = uuid(o, "id");
        JsonNode ls = order(id).get("lines");
        JsonNode r1 = received(id, hq, List.of(rline(ls.get(0), "10"), rline(ls.get(1), "1")));
        JsonNode r2 = received(id, br2, List.of(rline(ls.get(1), "3")));
        assertThat(order(id).get("status").asString()).isEqualTo("received");
        billed(
                "INV-ALL",
                2_000,
                "2026-11-07",
                List.of(
                        bline(r1.get("lines").get(0), "10", 100),
                        bline(r1.get("lines").get(1), "1", 250),
                        bline(r2.get("lines").get(0), "3", 250)));
        assertThat(balance("goods_received_not_invoiced")).isZero();
        assertThat(balance("trade_creditors")).isEqualTo(-2_000);
        paid(hq, "cash", 1_200);
        paid(br2, "bank", 800);
        assertThat(balance("trade_creditors")).isZero();
        assertThat(balance("inventory")).isEqualTo(2_000);
        assertThat(balance("purchase_price_variance")).isZero();
        assertThat(balance("purchase_rounding")).isZero();
        assertThat(api.get("/reports/supplier-payables", PAYER).getBody().get("suppliers"))
                .isEmpty();
        // Every entry balanced (the deferred trigger), so debits equal credits over the whole tenant.
        assertThat(scalar(
                        "SELECT coalesce(sum(debit - credit), 0) FROM journal_lines WHERE tenant_id = ?", t.tenantId()))
                .isZero();
    }

    // ---- review of #255: credit restocks across branches, maker-checker, receipt limits
    // ---------------------------------

    /** A credit restock of the product into both branches: 10 at head office and 5 at the second branch, at 100. */
    UUID restockBothBranches() {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("cost_minor", 100);
        l.put("qty_by_branch", List.of(Map.of("branch_id", hq, "qty", "10"), Map.of("branch_id", br2, "qty", "5")));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("purchased_on", TODAY);
        b.put("payment_method", "credit");
        b.put("supplier_id", supplier);
        b.put("lines", List.of(l));
        ResponseEntity<JsonNode> r = api.postKeyed("/purchases", b, BUYER, "*", key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    @Test
    void aMultiBranchCreditRestockIsPaidDownBranchByBranchInProportionAndExactly() {
        UUID purchase = restockBothBranches();
        assertThat(balance("trade_creditors", hq)).isEqualTo(-1_000);
        assertThat(balance("trade_creditors", br2)).isEqualTo(-500);
        // A part payment from head office: 700 splits 467 and 233 (the remainder to the larger fraction).
        JsonNode part = paid(hq, "cash", 700);
        assertThat(part.get("allocations")).hasSize(1);
        assertThat(part.get("allocations").get(0).get("amount_minor").asLong()).isEqualTo(700);
        assertThat(balance("trade_creditors", hq)).isEqualTo(-533);
        assertThat(balance("trade_creditors", br2)).isEqualTo(-267);
        assertThat(balance("interbranch_clearing")).isZero();
        assertThat(balance("cash_on_hand", hq)).isEqualTo(-700);
        // What is left, paid from the second branch, settles both exactly.
        JsonNode rest = paid(br2, "bank", 800);
        assertThat(rest.get("allocations").get(0).get("purchase_id").asString()).isEqualTo(purchase.toString());
        assertThat(balance("trade_creditors", hq)).isZero();
        assertThat(balance("trade_creditors", br2)).isZero();
        assertThat(balance("interbranch_clearing")).isZero();
        assertThat(api.get("/suppliers/" + supplier + "/open-items", PAYER)
                        .getBody()
                        .get("items"))
                .isEmpty();
        // Reversing the second payment puts back exactly what it paid in each branch.
        keyed(
                "/supplier-payments/" + uuid(rest, "id") + "/reverse",
                Map.of("reason", "Paid from the wrong till"),
                PAYER,
                "*");
        assertThat(balance("trade_creditors", hq)).isEqualTo(-533);
        assertThat(balance("trade_creditors", br2)).isEqualTo(-267);
        assertThat(balance("interbranch_clearing")).isZero();
    }

    @Test
    void thePayablesReportAgreesWithTheTradeCreditorsOverallAndBranchByBranch() {
        // A credit restock into one branch, an invoice of the other and a part payment of each.
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("cost_minor", 100);
        l.put("qty_by_branch", List.of(Map.of("branch_id", br2, "qty", "4")));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("purchased_on", TODAY);
        b.put("payment_method", "credit");
        b.put("supplier_id", supplier);
        b.put("lines", List.of(l));
        assertThat(api.postKeyed("/purchases", b, BUYER, "*", key()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        billOf("INV-REC", 900, "2026-11-07");
        paid(hq, "cash", 300);
        paid(br2, "bank", 150);
        long owed = api.get("/reports/supplier-payables", PAYER)
                .getBody()
                .get("totals")
                .get("owed_minor")
                .asLong();
        assertThat(owed).isEqualTo(-balance("trade_creditors")).isEqualTo(850);
        for (UUID branch : List.of(hq, br2)) {
            ResponseEntity<JsonNode> r =
                    api.call(HttpMethod.GET, "/reports/supplier-payables", null, PAYER, branch.toString(), Map.of());
            assertThat(r.getBody().get("totals").get("owed_minor").asLong())
                    .as("owed in %s", branch)
                    .isEqualTo(-balance("trade_creditors", branch));
        }
        // With a restock across both branches in the mix, the whole still agrees and each branch holds what it owes.
        restockBothBranches();
        paid(hq, "bank", 400);
        long all = api.get("/reports/supplier-payables", PAYER)
                .getBody()
                .get("totals")
                .get("owed_minor")
                .asLong();
        assertThat(all).isEqualTo(-balance("trade_creditors"));
        assertThat(-balance("trade_creditors", hq) + -balance("trade_creditors", br2))
                .isEqualTo(all);
    }

    @Test
    void whoWroteOrEditedAnOrderCannotApproveItEvenWhenAColleagueSendsIt() {
        threshold(1_000);
        RetailTestSupport editor = new RetailTestSupport(http, t);
        RetailTestSupport third = new RetailTestSupport(http, t);
        String perms = BUYER + ",core.approvals.read";
        // The first user writes the draft; a second edits it and sends it.
        JsonNode d = draft(hq, orderLine(product, "10", 100));
        UUID id = uuid(d, "id");
        String etag = api.get("/purchase-orders/" + id, BUYER).getHeaders().getETag();
        ResponseEntity<JsonNode> edited = editor.call(
                HttpMethod.PUT,
                "/purchase-orders/" + id,
                orderBody(supplier, hq, orderLine(product, "11", 100)),
                BUYER,
                "*",
                Map.of("If-Match", etag));
        assertThat(edited.getStatusCode()).as("%s", edited.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().get("created_by").asString())
                .isEqualTo(api.userId().toString());
        assertThat(edited.getBody().get("updated_by").asString())
                .isEqualTo(editor.userId().toString());
        ResponseEntity<JsonNode> asked =
                editor.postKeyed("/purchase-orders/" + id + "/send", Map.of(), BUYER, "*", key());
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String approval = asked.getBody().get("approval_id").asString();
        // Neither the author, nor the editor who sent it, can decide; a third person can.
        assertThat(decide(approval, api, perms).getStatusCode().is2xxSuccessful())
                .isFalse();
        assertThat(decide(approval, editor, perms).getStatusCode().is2xxSuccessful())
                .isFalse();
        assertThat(order(id).get("status").asString()).isEqualTo("draft");
        assertThat(decide(approval, third, perms).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(order(id).get("status").asString()).isEqualTo("sent");
        // The author alone is a maker too: when someone who only sent it asks, the author still cannot approve.
        JsonNode d2 = draft(hq, orderLine(product, "20", 100));
        ResponseEntity<JsonNode> asked2 =
                editor.postKeyed("/purchase-orders/" + uuid(d2, "id") + "/send", Map.of(), BUYER, "*", key());
        assertThat(asked2.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(decide(asked2.getBody().get("approval_id").asString(), api, perms)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isFalse();
    }

    @Test
    void withAThresholdADeliveryWithNoOrderOrPastTheOrdersValueIsRefused() {
        // No threshold: a blind receipt of any size is as before.
        blind(hq, product, "50", 100);
        threshold(1_000);
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("qty", "10");
        l.put("cost_minor", 100);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supplier_id", supplier);
        b.put("branch_id", hq);
        b.put("lines", List.of(l));
        ResponseEntity<JsonNode> big = keyed("/goods-receipts", b, BUYER, "*");
        assertThat(big.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(big)).isEqualTo("blind_receipt_over_limit");
        l.put("qty", "9");
        assertThat(keyed("/goods-receipts", b, BUYER, "*").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Against an order authorised for 900: a higher price is refused, the order's price is not.
        JsonNode o = sent(hq, orderLine(product, "9", 100));
        UUID id = uuid(o, "id");
        JsonNode line = order(id).get("lines").get(0);
        Map<String, Object> dear = rline(line, "9");
        dear.put("cost_minor", 120);
        ResponseEntity<JsonNode> over = receiveRaw(id, hq, List.of(dear));
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(over)).isEqualTo("receipt_value_exceeds_order");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("59");
        received(id, hq, List.of(rline(line, "9")));
        assertThat(order(id).get("status").asString()).isEqualTo("received");
        // The tolerance applies to value as well: 25 percent over lets a dearer delivery through.
        String etag = api.get("/purchasing/settings", SETTINGS).getHeaders().getETag();
        api.call(
                HttpMethod.PUT,
                "/purchasing/settings",
                Map.of("over_receipt_bp", 2500),
                SETTINGS,
                "*",
                Map.of("If-Match", etag));
        JsonNode o2 = sent(hq, orderLine(product, "9", 100));
        Map<String, Object> dearer = rline(order(uuid(o2, "id")).get("lines").get(0), "9");
        dearer.put("cost_minor", 120);
        assertThat(receiveRaw(uuid(o2, "id"), hq, List.of(dearer)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aReceiptIsNotDatedBeforeItsOrder() {
        JsonNode o = sent(hq, orderLine(product, "5", 100));
        JsonNode line = order(uuid(o, "id")).get("lines").get(0);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("order_id", uuid(o, "id"));
        b.put("branch_id", hq);
        b.put("received_on", UTC_DAY);
        b.put("lines", List.of(rline(line, "5")));
        ResponseEntity<JsonNode> r = keyed("/goods-receipts", b, BUYER, "*");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("before_order");
        b.put("received_on", TODAY);
        assertThat(keyed("/goods-receipts", b, BUYER, "*").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void anInvoiceOfSeveralBranchesIsVoidedOnlyByWhoseScopeCoversThemAll() {
        JsonNode a = blind(hq, product, "10", 100).get("lines").get(0);
        JsonNode c = blind(br2, product, "5", 100).get("lines").get(0);
        JsonNode bill = billed("INV-SCOPE", 1_500, "2026-11-07", List.of(bline(a, "10", 100), bline(c, "5", 100)));
        ResponseEntity<JsonNode> scoped = api.postKeyed(
                "/supplier-bills/" + uuid(bill, "id") + "/void",
                Map.of("reason", "Wrong invoice entered"),
                PAYER,
                hq.toString(),
                key());
        assertThat(scoped.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(bill(uuid(bill, "id")).get("status").asString()).isEqualTo("posted");
        assertThat(keyed(
                                "/supplier-bills/" + uuid(bill, "id") + "/void",
                                Map.of("reason", "Wrong invoice entered"),
                                PAYER,
                                "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** Marks a product inactive, as the Items screen does (issue #342). */
    void switchOff(UUID id) {
        setActive(id, false);
    }

    void switchOn(UUID id) {
        setActive(id, true);
    }

    void setActive(UUID id, boolean active) {
        String etag = api.get("/products/" + id, ADMIN).getHeaders().getETag();
        assertThat(api.call(
                                HttpMethod.PATCH,
                                "/products/" + id,
                                Map.of("active", active),
                                ADMIN,
                                "*",
                                Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** ADR-045: goods already on the way are received even if the item was switched off after the order was sent. */
    @Test
    void goodsAgainstASentOrderAreReceivedForAnItemSwitchedOffSinceButABlindReceiptIsRefused() {
        JsonNode order = sent(hq, orderLine(product, "10", 100));
        UUID id = uuid(order, "id");
        switchOff(product);
        JsonNode receipt = received(id, hq, List.of(rline(order(id).get("lines").get(0), "10")));
        assertThat(receipt.get("id").asString()).isNotEmpty();
        ResponseEntity<JsonNode> blind = receiveRaw(
                null, hq, List.of(Map.<String, Object>of("product_id", product, "qty", "1", "cost_minor", 100)));
        assertThat(blind.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(blind.getBody().toString()).contains("product_inactive");
    }

    /** ADR-094 (issue #376): a draft holding an item switched off since is not sent, nor sent later by its approval. */
    @Test
    void aDraftWithAnItemSwitchedOffSinceIsNotSentNorSentByItsApproval() {
        UUID id = uuid(draft(hq, orderLine(product, "1", 100)), "id");
        switchOff(product);
        ResponseEntity<JsonNode> refused = sendRaw(id);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("product_inactive");
        assertThat(order(id).get("status").asString()).isEqualTo("draft");
        switchOn(product);

        threshold(1_000);
        UUID big = uuid(draft(hq, orderLine(product, "10", 100)), "id");
        ResponseEntity<JsonNode> asked = sendRaw(big);
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        switchOff(product);
        RetailTestSupport checker = new RetailTestSupport(http, t);
        String approval = asked.getBody().get("approval_id").asString();
        ResponseEntity<JsonNode> decided = decide(approval, checker, BUYER + ",core.approvals.read");
        assertThat(decided.getStatusCode()).as("%s", decided.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(decided)).isEqualTo("product_inactive");
        assertThat(order(big).get("status").asString()).isEqualTo("draft");
        // ADR-091: a refusal ends the request as stale with the reason, so it is never approved later as it stood; once
        // the item is switched on again the maker sends the order again and a new request is asked.
        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM approval_requests WHERE id = ?::uuid")
                        .param(approval)
                        .query(String.class)
                        .single())
                .isEqualTo("stale");
        switchOn(product);
        ResponseEntity<JsonNode> redecided = decide(approval, checker, BUYER + ",core.approvals.read");
        assertThat(redecided.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(redecided)).isEqualTo("invalid_status_transition");
        assertThat(order(big).get("status").asString()).isEqualTo("draft");
        ResponseEntity<JsonNode> again = sendRaw(big);
        assertThat(again.getStatusCode()).as("%s", again.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(again.getBody().get("approval_id").asString()).isNotEqualTo(approval);
        ResponseEntity<JsonNode> approved =
                decide(again.getBody().get("approval_id").asString(), checker, BUYER + ",core.approvals.read");
        assertThat(approved.getStatusCode()).as("%s", approved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(order(big).get("status").asString()).isEqualTo("sent");
    }
}
