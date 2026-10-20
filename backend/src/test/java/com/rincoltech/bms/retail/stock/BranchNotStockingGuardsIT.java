package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The guards behind "this branch keeps no goods" and "this branch is closed" (issue #329, ADR-041) that the main integration test
 * does not reach: the purchase order, the goods receipt, the price resolution, a return restocked into the branch, a stock-take
 * committed after the branch was marked, a transfer received there, and the race between an administrator's change and a stock
 * write, made deterministic by holding the branch row the way the administrator's change does. Fabricated names and amounts.
 */
class BranchNotStockingGuardsIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    static final String BUYER = ADMIN + ",retail.purchase.approve";
    static final String RETURNER = ADMIN + ",retail.sale.return,retail.cashbook.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;
    UUID product;
    UUID supplier;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("branch-guards", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
        product = api.product("GUARD-CABLE", 1_000, 1_500);
        supplier = RetailTestSupport.id(api.post("/suppliers", Map.of("name", "Test Supplier 01"), BUYER));
        for (String capability : List.of("purchasing", "returns", "price_lists", "transfer_receipt")) {
            capability(capability);
        }
    }

    void capability(String key) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of(key, true),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    void setFlag(UUID branch, boolean stocks) {
        TestDatabase.owner()
                .sql("UPDATE branches SET stocks_goods = ? WHERE id = ?")
                .params(stocks, branch)
                .update();
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    void assertNotStocking(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("branch_not_stocking");
    }

    ResponseEntity<JsonNode> keyed(String path, Object body, String perms) {
        return api.postKeyed(path, body, perms, "*", UUID.randomUUID().toString());
    }

    ResponseEntity<JsonNode> restock(UUID branch) {
        Map<String, Object> line = Map.of(
                "product_id",
                product,
                "cost_minor",
                1_000,
                "qty_by_branch",
                List.of(Map.of("branch_id", branch, "qty", "2")));
        return keyed(
                "/purchases",
                Map.of("purchased_on", "2026-01-15", "payment_method", "cash", "lines", List.of(line)),
                ADMIN);
    }

    // ---------------------------------------------------------------------------------------- the four paths

    /** A purchase order and a goods receipt for a branch that keeps no goods are refused. */
    @Test
    void aPurchaseOrderAndAGoodsReceiptAreRefused() {
        setFlag(hq, false);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("qty", "2");
        line.put("cost_minor", 1_000);
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("supplier_id", supplier);
        order.put("branch_id", hq);
        order.put("lines", List.of(line));
        assertNotStocking(keyed("/purchase-orders", order, BUYER));

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("supplier_id", supplier);
        receipt.put("branch_id", hq);
        receipt.put("lines", List.of(line));
        assertNotStocking(keyed("/goods-receipts", receipt, BUYER));
        assertThat(keyed(
                                "/goods-receipts",
                                Map.of("supplier_id", supplier, "branch_id", br2, "lines", List.of(line)),
                                BUYER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    /** Pricing a sale at a branch that keeps no goods is refused as the sale is. */
    @Test
    void thePriceResolutionIsRefused() {
        setFlag(hq, false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        assertNotStocking(api.post("/prices/resolve", body, ADMIN));
        body.put("branch_id", br2);
        assertThat(api.post("/prices/resolve", body, ADMIN).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** A return restocked into a branch that keeps no goods is the same top-level refusal; written off it is fine. */
    @Test
    void aReturnCannotRestockIntoIt() {
        api.stockUp(hq, product, "10");
        ResponseEntity<JsonNode> sold = api.sell(hq, "cash", product, "2");
        assertThat(sold.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID sale = RetailTestSupport.id(sold);
        UUID saleLine = UUID.fromString(api.get("/sales/" + sale, ADMIN)
                .getBody()
                .get("lines")
                .get(0)
                .get("id")
                .asString());
        setFlag(hq, false);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sale_line_id", saleLine);
        line.put("qty", "1");
        line.put("reason", "defective");
        line.put("disposition", "restock");
        ResponseEntity<JsonNode> refused =
                keyed("/sales/" + sale + "/returns", Map.of("lines", List.of(line), "refund_method", "cash"), RETURNER);
        assertNotStocking(refused);
        line.put("disposition", "write_off");
        assertThat(keyed(
                                "/sales/" + sale + "/returns",
                                Map.of("lines", List.of(line), "refund_method", "cash"),
                                RETURNER)
                        .getStatusCode())
                .as("a write-off puts nothing on the shelf")
                .isEqualTo(HttpStatus.CREATED);
    }

    /** A stock-take counted before the branch was marked cannot be committed into it afterwards. */
    @Test
    void aDraftStockTakeCannotBeCommittedIntoIt() {
        Map<String, Object> body =
                Map.of("branch_id", hq, "lines", List.of(Map.of("product_id", product, "counted_qty", "5")));
        UUID draft = RetailTestSupport.id(api.post("/stocktakes", body, ADMIN));
        setFlag(hq, false);
        assertNotStocking(api.post("/stocktakes/" + draft + "/commit", Map.of(), ADMIN));
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ?")
                        .params(t.tenantId(), hq)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** A transfer on the road to a branch that now keeps no goods is not received into it. */
    @Test
    void aTransferIsNotReceivedIntoIt() {
        api.stockUp(br2, product, "10");
        Map<String, Object> send = Map.of(
                "from_branch_id", br2, "to_branch_id", hq, "lines", List.of(Map.of("product_id", product, "qty", 3)));
        ResponseEntity<JsonNode> sent = keyed("/transfers", send, ADMIN);
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID transfer = RetailTestSupport.id(sent);
        setFlag(hq, false);
        ResponseEntity<JsonNode> received = keyed("/transfers/" + transfer + "/receive", Map.of(), ADMIN);
        assertNotStocking(received);
    }

    // ---------------------------------------------------------------------------------------- the race

    /**
     * An administrator's change holds the branch row until it commits. A restock that checks the branch in the meantime queues
     * behind it and, once it commits, sees the flag and is refused: goods cannot land in a branch that has just stopped keeping
     * them. Held here by an open transaction of the owner role, which does exactly what the administrator's update does.
     */
    @Test
    void aWriteQueuesBehindAnAdministratorsChangeAndSeesIt() throws Exception {
        try (Connection c = TestDatabase.ownerDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE branches SET stocks_goods = false WHERE id = '" + hq + "'");
            }
            CompletableFuture<ResponseEntity<JsonNode>> restock = CompletableFuture.supplyAsync(() -> restock(hq));
            assertThat(blockedOnABranchRow(restock))
                    .as("the restock waits for the branch row")
                    .isTrue();
            c.commit();
            assertNotStocking(restock.get(30, TimeUnit.SECONDS));
        }
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ?")
                        .params(t.tenantId(), hq)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** The same for a close: a sale that reaches a branch while it is being closed is refused, not recorded after. */
    @Test
    void aSaleQueuesBehindACloseAndIsRefused() throws Exception {
        api.stockUp(br2, product, "10");
        try (Connection c = TestDatabase.ownerDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE branches SET status = 'inactive' WHERE id = '" + br2 + "'");
            }
            CompletableFuture<ResponseEntity<JsonNode>> sale =
                    CompletableFuture.supplyAsync(() -> api.sell(br2, "cash", product, "1"));
            assertThat(blockedOnABranchRow(sale))
                    .as("the sale waits for the branch row")
                    .isTrue();
            c.commit();
            ResponseEntity<JsonNode> answer = sale.get(30, TimeUnit.SECONDS);
            assertThat(answer.getStatusCode()).as("%s", answer.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_sales WHERE tenant_id = ? AND branch_id = ?")
                        .params(t.tenantId(), br2)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /**
     * The other direction: a write that is already past its branch check holds the row FOR SHARE, so the administrator's change
     * of the flag waits for it instead of slipping in, and applies once the write has finished.
     */
    @Test
    void anAdministratorsChangeWaitsForAWriteAlreadyPastItsCheck() throws Exception {
        try (Connection c = TestDatabase.ownerDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT 1 FROM branches WHERE id = '" + hq + "' FOR SHARE");
            }
            CompletableFuture<ResponseEntity<JsonNode>> change = CompletableFuture.supplyAsync(() -> http.exchange(
                    "/api/v1/branches/" + hq,
                    HttpMethod.PATCH,
                    new HttpEntity<>(
                            Map.of("stocks_goods", false),
                            api.headers("core.branches.read,core.branches.manage", "*", Map.of("If-Match", "\"1\""))),
                    JsonNode.class));
            assertThat(blockedOnABranchRow(change))
                    .as("the change waits for the write's share lock")
                    .isTrue();
            c.commit();
            ResponseEntity<JsonNode> done = change.get(30, TimeUnit.SECONDS);
            assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(done.getBody().get("stocks_goods").asBoolean()).isFalse();
        }
    }

    /** A rename takes no explicit lock, so a write holding the row does not hold it up. */
    @Test
    void aRenameDoesNotWaitForAWriteHoldingTheRow() throws Exception {
        try (Connection c = TestDatabase.ownerDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT 1 FROM branches WHERE id = '" + br2 + "' FOR KEY SHARE");
            }
            ResponseEntity<JsonNode> renamed = http.exchange(
                    "/api/v1/branches/" + br2,
                    HttpMethod.PATCH,
                    new HttpEntity<>(
                            Map.of("name", "Test Branch Two Renamed"),
                            api.headers("core.branches.read,core.branches.manage", "*", Map.of("If-Match", "\"1\""))),
                    JsonNode.class);
            assertThat(renamed.getStatusCode()).as("%s", renamed.getBody()).isEqualTo(HttpStatus.OK);
            c.rollback();
        }
    }

    /** A sale held up longer than the request's lock timeout is told to try again, and nothing is saved. */
    @Test
    void aSaleHeldUpTooLongIsToldToTryAgain() throws Exception {
        api.stockUp(br2, product, "10");
        try (Connection c = TestDatabase.ownerDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("UPDATE branches SET name = name WHERE id = '" + br2 + "'");
            }
            ResponseEntity<JsonNode> held = api.sell(br2, "cash", product, "1");
            assertThat(held.getStatusCode()).as("%s", held.getBody()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(code(held)).isEqualTo("lock_timeout");
            c.rollback();
        }
        assertThat(api.sell(br2, "cash", product, "1").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** A void at a branch that keeps no goods is still allowed: the sale exists, and the goods go back on the shelf. */
    @Test
    void aVoidAtABranchThatKeepsNoGoodsIsStillAllowed() {
        api.stockUp(hq, product, "5");
        UUID sale = RetailTestSupport.id(api.sell(hq, "cash", product, "2"));
        setFlag(hq, false);
        ResponseEntity<JsonNode> voided =
                api.post("/sales/" + sale + "/void", Map.of("reason", "Test void after the flag was set"), ADMIN);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Waits (up to 30 seconds) until a database backend is waiting for a lock, then reports whether the
     * call is still unfinished: deterministic, with no fixed pause that a slow machine could outrun.
     */
    private boolean blockedOnABranchRow(CompletableFuture<?> call) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            // pg_locks is readable by every role; the only lock waits in this test are the ones it arranges.
            long waiting = TestDatabase.owner()
                    .sql("SELECT count(*) FROM pg_locks WHERE NOT granted")
                    .query(Long.class)
                    .single();
            if (waiting > 0) {
                return !call.isDone();
            }
            Thread.sleep(50);
        }
        return false;
    }
}
