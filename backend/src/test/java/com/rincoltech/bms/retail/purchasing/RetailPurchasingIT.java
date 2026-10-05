package com.rincoltech.bms.retail.purchasing;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
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
 * Restocks (#53; FR-RET-06, FR-RET-11, FR-RET-14; ADR-020 decision 5). The price written by a
 * restock is the exact bug class ADR-020 exists to prevent, so these tests check that the product,
 * its history, the movements and the journals move together or not at all. Amounts fabricated.
 */
class RetailPurchasingIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-buy", false, true);
        api = new RetailTestSupport(http, t);
        product = api.product("MCB-32A", 1_000, 1_500);
    }

    static Map<String, Object> line(UUID product, Long cost, Long sell, Map<UUID, String> qtyByBranch) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("cost_minor", cost);
        if (sell != null) {
            l.put("sell_minor", sell);
        }
        l.put(
                "qty_by_branch",
                qtyByBranch.entrySet().stream()
                        .map(e -> Map.of("branch_id", e.getKey(), "qty", e.getValue()))
                        .toList());
        return l;
    }

    static Map<String, Object> purchase(String method, UUID supplier, List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", "2026-01-15");
        body.put("payment_method", method);
        if (supplier != null) {
            body.put("supplier_id", supplier);
        }
        body.put("lines", lines);
        return body;
    }

    ResponseEntity<JsonNode> buy(Map<String, Object> body) {
        return api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    /**
     * FR-RET-06, FR-RET-11: a restock at a new price changes the product's prices at once, with a
     * history row naming the purchase, moves each branch, and posts one entry per branch.
     */
    @Test
    void aRestockAtANewPriceChangesThePriceWithAHistoryRowAtOnce() {
        Map<UUID, String> qty = new LinkedHashMap<>();
        qty.put(t.headOffice(), "10");
        qty.put(t.secondBranch(), "4");
        ResponseEntity<JsonNode> bought = buy(purchase("cash", null, List.of(line(product, 1_200L, 1_800L, qty))));
        assertThat(bought.getStatusCode()).as("%s", bought.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID id = RetailTestSupport.id(bought);
        assertThat(bought.getBody().get("total_minor").asLong()).isEqualTo(16_800);
        assertThat(bought.getBody().get("lines").get(0).get("qty_by_branch")).hasSize(2);

        JsonNode p = api.get("/products/" + product, ADMIN).getBody();
        assertThat(p.get("cost_minor").asLong()).isEqualTo(1_200);
        assertThat(p.get("sell_minor").asLong()).isEqualTo(1_800);
        JsonNode history = api.get("/products/" + product + "/price-history", ADMIN)
                .getBody()
                .get("items");
        assertThat(history).hasSize(2);
        assertThat(history.get(1).get("source").asString()).isEqualTo("purchase");
        assertThat(history.get(1).get("source_id").asString()).isEqualTo(id.toString());
        assertThat(history.get(1).get("old_cost_minor").asLong()).isEqualTo(1_000);
        assertThat(history.get(1).get("new_sell_minor").asLong()).isEqualTo(1_800);

        assertThat(qty(t.headOffice())).isEqualTo("10.000");
        assertThat(qty(t.secondBranch())).isEqualTo("4.000");
        List<Map<String, Object>> entries = TestDatabase.owner()
                .sql("""
                        SELECT e.branch_id, sum(l.debit) AS debit, sum(l.credit) AS credit,
                               sum(l.debit) FILTER (WHERE a.system_key = 'inventory') AS inventory,
                               sum(l.credit) FILTER (WHERE a.system_key = 'cash_on_hand') AS cash
                          FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.source_id = ? GROUP BY e.id, e.branch_id
                        """)
                .param(id)
                .query((rs, n) -> Map.<String, Object>of(
                        "branch",
                        rs.getObject(1, UUID.class),
                        "debit",
                        rs.getLong(2),
                        "credit",
                        rs.getLong(3),
                        "inventory",
                        rs.getLong(4),
                        "cash",
                        rs.getLong(5)))
                .list();
        assertThat(entries).hasSize(2);
        assertThat(entries).allMatch(e -> e.get("debit").equals(e.get("credit")));
        assertThat(entries)
                .extracting(e -> e.get("branch") + " " + e.get("inventory") + " " + e.get("cash"))
                .containsExactlyInAnyOrder(t.headOffice() + " 12000 12000", t.secondBranch() + " 4800 4800");

        JsonNode sale = api.sell(t.headOffice(), "cash", product, "1").getBody();
        assertThat(sale.get("lines").get(0).get("unit_cost_minor").asLong()).isEqualTo(1_200);
        assertThat(sale.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(1_800);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'retail.purchase.created'")
                        .param(id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /**
     * FR-RET-06: two lines for one product in one request, then a second purchase; the latest line
     * wins each time, a history row per change, none when nothing changes.
     */
    @Test
    void theLatestRestockLineWinsIncludingTwoLinesInOneRequest() {
        ResponseEntity<JsonNode> bought = buy(purchase(
                "bank",
                null,
                List.of(
                        line(product, 1_100L, 1_600L, Map.of(t.headOffice(), "1")),
                        line(product, 1_300L, null, Map.of(t.secondBranch(), "2")))));
        assertThat(bought.getStatusCode()).as("%s", bought.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode p = api.get("/products/" + product, ADMIN).getBody();
        assertThat(p.get("cost_minor").asLong()).isEqualTo(1_300);
        assertThat(p.get("sell_minor").asLong()).isEqualTo(1_600);
        JsonNode history = api.get("/products/" + product + "/price-history", ADMIN)
                .getBody()
                .get("items");
        assertThat(history).hasSize(3);
        assertThat(history.get(1).get("new_cost_minor").asLong()).isEqualTo(1_100);
        assertThat(history.get(2).get("old_cost_minor").asLong()).isEqualTo(1_100);
        assertThat(history.get(2).get("new_cost_minor").asLong()).isEqualTo(1_300);
        assertThat(history.get(2).get("old_sell_minor").asLong()).isEqualTo(1_600);
        assertThat(history.get(2).get("new_sell_minor").asLong()).isEqualTo(1_600);

        buy(purchase("cash", null, List.of(line(product, 1_250L, 1_700L, Map.of(t.headOffice(), "1")))));
        JsonNode after = api.get("/products/" + product, ADMIN).getBody();
        assertThat(after.get("cost_minor").asLong()).isEqualTo(1_250);
        assertThat(after.get("sell_minor").asLong()).isEqualTo(1_700);

        buy(purchase("cash", null, List.of(line(product, 1_250L, null, Map.of(t.headOffice(), "1")))));
        assertThat(api.get("/products/" + product + "/price-history", ADMIN)
                        .getBody()
                        .get("items"))
                .hasSize(4);
        assertThat(qty(t.headOffice())).isEqualTo("3.000");
    }

    /**
     * ADR-020 decision 5: a restock that fails after its prices were applied leaves no price, no
     * history row, no movement and no purchase behind. The failure is forced in the journal step,
     * which runs after the prices are written.
     */
    @Test
    void aFailedRestockChangesNoPrice() {
        TestDatabase.owner()
                .sql("UPDATE gl_accounts SET is_active = false WHERE tenant_id = ? AND system_key = 'inventory'")
                .param(t.tenantId())
                .update();
        ResponseEntity<JsonNode> failed =
                buy(purchase("cash", null, List.of(line(product, 9_000L, 9_900L, Map.of(t.headOffice(), "5")))));
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(failed.getBody().get("code").asString()).isEqualTo("retail_chart_missing");
        JsonNode p = api.get("/products/" + product, ADMIN).getBody();
        assertThat(p.get("cost_minor").asLong()).isEqualTo(1_000);
        assertThat(p.get("sell_minor").asLong()).isEqualTo(1_500);
        assertThat(api.get("/products/" + product + "/price-history", ADMIN)
                        .getBody()
                        .get("items"))
                .hasSize(1);
        for (String table : List.of("retail_purchases", "retail_stock_movements", "idempotency_keys")) {
            assertThat(TestDatabase.owner()
                            .sql("SELECT count(*) FROM " + table + " WHERE tenant_id = ?")
                            .param(t.tenantId())
                            .query(Long.class)
                            .single())
                    .as(table)
                    .isZero();
        }
    }

    /** FR-RET-06: a credit restock names its supplier and credits trade creditors; only admins restock. */
    @Test
    void aCreditRestockIsOwedToItsSupplier() {
        ResponseEntity<JsonNode> noSupplier =
                buy(purchase("credit", null, List.of(line(product, 1_000L, null, Map.of(t.headOffice(), "2")))));
        assertThat(noSupplier.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        UUID supplier = RetailTestSupport.id(
                api.post("/suppliers", Map.of("name", "Test Supplier 01", "contact", "+256700000003"), ADMIN));
        assertThat(api.post("/suppliers", Map.of("name", "test supplier 01"), ADMIN)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("duplicate_supplier");
        Map<String, Object> body =
                purchase("credit", supplier, List.of(line(product, 1_000L, null, Map.of(t.headOffice(), "2"))));
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> bought = api.postKeyed("/purchases", body, ADMIN, "*", key);
        assertThat(bought.getStatusCode()).as("%s", bought.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> replay = api.postKeyed("/purchases", body, ADMIN, "*", key);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(qty(t.headOffice())).isEqualTo("2.000");
        Long creditors = TestDatabase.owner()
                .sql("""
                        SELECT sum(l.credit - l.debit) FROM journal_lines l JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = 'trade_creditors' AND l.subledger_id = ?
                        """)
                .params(t.tenantId(), supplier)
                .query(Long.class)
                .single();
        assertThat(creditors).isEqualTo(2_000);
        assertThat(api.postKeyed(
                                "/purchases",
                                body,
                                SALES,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/purchases?supplier_id=" + supplier, ADMIN)
                        .getBody()
                        .get("items"))
                .hasSize(1);
    }

    /**
     * ADR-020 decision 5 under concurrency: restocks at different prices race on one product. The
     * product row lock serialises them, so the history is one unbroken chain (each row's old prices
     * are the previous row's new prices) ending at the product's current prices; nothing is lost.
     */
    @Test
    void concurrentRestocksKeepAnUnbrokenPriceChain() throws Exception {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        List<org.springframework.http.HttpStatusCode> statuses = com.rincoltech.bms.testsupport.Api.race(6, () -> {
            long cost = 2_000L + 10L * n.incrementAndGet();
            return buy(purchase("cash", null, List.of(line(product, cost, cost + 500, Map.of(t.headOffice(), "1")))));
        });
        assertThat(com.rincoltech.bms.testsupport.Api.count(statuses, HttpStatus.CREATED))
                .isEqualTo(6);
        JsonNode history = api.get("/products/" + product + "/price-history", ADMIN)
                .getBody()
                .get("items");
        assertThat(history).hasSize(7);
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).get("old_cost_minor").asLong())
                    .isEqualTo(history.get(i - 1).get("new_cost_minor").asLong());
            assertThat(history.get(i).get("old_sell_minor").asLong())
                    .isEqualTo(history.get(i - 1).get("new_sell_minor").asLong());
        }
        JsonNode p = api.get("/products/" + product, ADMIN).getBody();
        assertThat(p.get("cost_minor").asLong())
                .isEqualTo(history.get(6).get("new_cost_minor").asLong());
        assertThat(p.get("sell_minor").asLong())
                .isEqualTo(history.get(6).get("new_sell_minor").asLong());
        assertThat(qty(t.headOffice())).isEqualTo("6.000");
    }

    /**
     * Review F11: a purchase splits 10 to head office and 40 to branch two at 1,000. A buyer scoped
     * to head office sees the purchase with head office's line only: qty 10 and 10,000, not 50 and
     * 50,000; an all-branch buyer sees the whole document.
     */
    @Test
    void aBranchScopedBuyerSeesOnlyTheirBranchesInThePurchaseList() {
        Map<UUID, String> qty = new LinkedHashMap<>();
        qty.put(t.headOffice(), "10");
        qty.put(t.secondBranch(), "40");
        assertThat(buy(purchase("cash", null, List.of(line(product, 1_000L, null, qty))))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        JsonNode scoped = api.call(
                        org.springframework.http.HttpMethod.GET,
                        "/purchases",
                        null,
                        "retail.purchase.create",
                        t.headOffice().toString(),
                        Map.of())
                .getBody()
                .get("items")
                .get(0);
        assertThat(scoped.get("total_minor").asLong()).isEqualTo(10_000);
        JsonNode l = scoped.get("lines").get(0);
        assertThat(l.get("qty_total").asString()).isEqualTo("10.000");
        assertThat(l.get("line_total_minor").asLong()).isEqualTo(10_000);
        assertThat(l.get("qty_by_branch")).hasSize(1);
        assertThat(l.get("qty_by_branch").get(0).get("branch_id").asString())
                .isEqualTo(t.headOffice().toString());

        JsonNode all = api.get("/purchases", ADMIN).getBody().get("items").get(0);
        assertThat(all.get("total_minor").asLong()).isEqualTo(50_000);
        assertThat(all.get("lines").get(0).get("qty_by_branch")).hasSize(2);
    }

    String qty(UUID branch) {
        return api.get("/stock?branch_id=" + branch, ADMIN)
                .getBody()
                .get("items")
                .get(0)
                .get("qty")
                .asString();
    }
}
