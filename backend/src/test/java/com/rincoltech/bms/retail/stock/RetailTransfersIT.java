package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Stock transfers between branches (#84; FR-RET-16, FR-RET-03, FR-RET-11, FR-RET-14). Names and
 * amounts fabricated.
 */
class RetailTransfersIT extends IntegrationTest {

    /** A transfer clerk without profit read: may move stock and read it, sees no cost. */
    static final String MOVER = "retail.stock.transfer,retail.stock.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;
    UUID other;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-transfer", false, true);
        api = new RetailTestSupport(http, t);
        product = api.product("CABLE-2MM", 1_000, 1_500);
        other = api.product("SOCKET-13A", 2_000, 3_000);
    }

    Map<String, Object> body(UUID from, UUID to, Object... productQty) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < productQty.length; i += 2) {
            lines.add(Map.of("product_id", productQty[i], "qty", productQty[i + 1]));
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("from_branch_id", from);
        b.put("to_branch_id", to);
        b.put("lines", lines);
        return b;
    }

    ResponseEntity<JsonNode> transfer(Map<String, Object> body, String perms, String branches) {
        return api.postKeyed(
                "/transfers", body, perms, branches, UUID.randomUUID().toString());
    }

    String qty(UUID branch, UUID p) {
        return TestDatabase.owner()
                .sql(
                        "SELECT qty::text FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), branch, p)
                .query(String.class)
                .optional()
                .orElse("0.000");
    }

    long value(JsonNode valuation) {
        return valuation.get("value_at_cost_minor").asLong();
    }

    JsonNode branchTotal(JsonNode valuation, UUID branch) {
        for (JsonNode b : valuation.get("branches")) {
            if (b.get("branch_id").asString().equals(branch.toString())) {
                return b;
            }
        }
        throw new AssertionError("no branch " + branch + " in " + valuation);
    }

    /** Per branch: debits, credits and the inventory movement of the transfer's journal entries. */
    List<Map<String, Object>> entries(UUID transfer) {
        return TestDatabase.owner()
                .sql("""
                        SELECT e.branch_id, sum(l.debit) AS debit, sum(l.credit) AS credit,
                               coalesce(sum(l.debit - l.credit) FILTER (WHERE a.system_key = 'inventory'), 0) AS inventory,
                               coalesce(sum(l.debit - l.credit) FILTER (WHERE a.system_key = 'interbranch_clearing'), 0)
                                   AS clearing
                          FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.tenant_id = ? AND e.source_type = 'retail.transfer' AND e.source_id = ?
                         GROUP BY e.branch_id
                        """)
                .params(t.tenantId(), transfer)
                .query((rs, n) -> Map.<String, Object>of(
                        "branch", rs.getObject("branch_id", UUID.class),
                        "debit", rs.getLong("debit"),
                        "credit", rs.getLong("credit"),
                        "inventory", rs.getLong("inventory"),
                        "clearing", rs.getLong("clearing")))
                .list();
    }

    /**
     * FR-RET-16: four units move from head office to branch two in one transaction at the source's
     * cost: a linked transfer_out and transfer_in, one balanced entry per branch through
     * inter-branch clearing, the tenant's valuation at cost unchanged and each branch's moved.
     */
    @Test
    void aTransferMovesStockAndPostsOneBalancedEntryPerBranch() {
        api.stockUp(t.headOffice(), product, "10");
        JsonNode before = api.get("/reports/valuation", ADMIN).getBody();

        Map<String, Object> b = body(t.headOffice(), t.secondBranch(), product, "4");
        b.put("note", "Test restock of branch two");
        ResponseEntity<JsonNode> created = transfer(b, ADMIN, "*");

        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode tr = created.getBody();
        UUID id = RetailTestSupport.id(created);
        assertThat(created.getHeaders().getLocation()).hasToString("/api/v1/retail/transfers/" + id);
        assertThat(tr.get("status").asString()).isEqualTo("completed");
        assertThat(tr.get("cost_total_minor").asLong()).isEqualTo(4_000);
        assertThat(tr.get("lines").get(0).get("qty").asString()).isEqualTo("4.000");
        assertThat(tr.get("lines").get(0).get("unit_cost_minor").asLong()).isEqualTo(1_000);
        assertThat(qty(t.headOffice(), product)).isEqualTo("6.000");
        assertThat(qty(t.secondBranch(), product)).isEqualTo("4.000");

        List<Map<String, Object>> movements = TestDatabase.owner()
                .sql("""
                        SELECT branch_id, kind, qty::text AS qty, unit_cost_minor, transfer_id FROM retail_stock_movements
                         WHERE tenant_id = ? AND source_id = ? ORDER BY kind
                        """)
                .params(t.tenantId(), id)
                .query((rs, n) -> Map.<String, Object>of(
                        "branch", rs.getObject("branch_id", UUID.class),
                        "kind", rs.getString("kind"),
                        "qty", rs.getString("qty"),
                        "cost", rs.getLong("unit_cost_minor"),
                        "transfer", rs.getObject("transfer_id", UUID.class)))
                .list();
        assertThat(movements)
                .containsExactly(
                        Map.of(
                                "branch",
                                t.secondBranch(),
                                "kind",
                                "transfer_in",
                                "qty",
                                "4.000",
                                "cost",
                                1_000L,
                                "transfer",
                                id),
                        Map.of(
                                "branch",
                                t.headOffice(),
                                "kind",
                                "transfer_out",
                                "qty",
                                "-4.000",
                                "cost",
                                1_000L,
                                "transfer",
                                id));
        JsonNode history = api.get("/stock/movements?branch_id=" + t.secondBranch(), ADMIN)
                .getBody()
                .get("items");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("kind").asString()).isEqualTo("transfer_in");
        assertThat(history.get(0).get("source_id").asString()).isEqualTo(id.toString());

        List<Map<String, Object>> entries = entries(id);
        assertThat(entries).hasSize(2);
        for (Map<String, Object> e : entries) {
            assertThat(e.get("debit")).isEqualTo(4_000L);
            assertThat(e.get("credit")).isEqualTo(4_000L);
            boolean source = e.get("branch").equals(t.headOffice());
            assertThat(e.get("inventory")).isEqualTo(source ? -4_000L : 4_000L);
            assertThat(e.get("clearing")).isEqualTo(source ? 4_000L : -4_000L);
        }

        JsonNode after = api.get("/reports/valuation", ADMIN).getBody();
        assertThat(value(after)).isEqualTo(value(before)).isEqualTo(10_000);
        assertThat(after.get("expected_sales_minor").asLong())
                .isEqualTo(before.get("expected_sales_minor").asLong());
        JsonNode ho = branchTotal(after, t.headOffice());
        JsonNode two = branchTotal(after, t.secondBranch());
        assertThat(ho.get("value_at_cost_minor").asLong()).isEqualTo(6_000);
        assertThat(two.get("value_at_cost_minor").asLong()).isEqualTo(4_000);
        assertThat(ho.get("revaluation_difference_minor").asLong()).isZero();
        assertThat(two.get("revaluation_difference_minor").asLong()).isZero();

        String audit = TestDatabase.owner()
                .sql("SELECT data::text FROM audit_log WHERE entity_id = ? AND action = 'retail.transfer.created'")
                .param(id)
                .query(String.class)
                .single();
        assertThat(audit).contains("\"lines\": 1").doesNotContain("cost");

        JsonNode got = api.get("/transfers/" + id, ADMIN).getBody();
        assertThat(got.get("note").asString()).isEqualTo("Test restock of branch two");
        assertThat(got.get("cost_total_minor").asLong()).isEqualTo(4_000);
    }

    /** FR-RET-03, FR-RET-16: a transfer above the source's balance is refused, and nothing moves. */
    @Test
    void aTransferAboveTheSourceBalanceIsRefused() {
        api.stockUp(t.headOffice(), product, "3");
        api.stockUp(t.headOffice(), other, "10");

        ResponseEntity<JsonNode> refused =
                transfer(body(t.headOffice(), t.secondBranch(), other, "2", product, "3.001"), ADMIN, "*");

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("insufficient_stock");
        assertThat(qty(t.headOffice(), product)).isEqualTo("3.000");
        assertThat(qty(t.headOffice(), other)).isEqualTo("10.000");
        assertThat(qty(t.secondBranch(), other)).isEqualTo("0.000");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_transfers WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** FR-RET-16: same branch, unknown destination, future date, duplicate product: field problems. */
    @Test
    void invalidTransfersAreRefusedWithFieldProblems() {
        api.stockUp(t.headOffice(), product, "5");
        ResponseEntity<JsonNode> same = transfer(body(t.headOffice(), t.headOffice(), product, "1"), ADMIN, "*");
        assertThat(same.getBody().get("errors").get(0).get("code").asString()).isEqualTo("same_branch");
        ResponseEntity<JsonNode> unknown = transfer(body(t.headOffice(), UUID.randomUUID(), product, "1"), ADMIN, "*");
        assertThat(unknown.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("unknown_branch");
        Map<String, Object> future = body(t.headOffice(), t.secondBranch(), product, "1");
        future.put("transfer_date", "2999-01-01");
        assertThat(transfer(future, ADMIN, "*")
                        .getBody()
                        .get("errors")
                        .get(0)
                        .get("field")
                        .asString())
                .isEqualTo("transfer_date");
        ResponseEntity<JsonNode> twice =
                transfer(body(t.headOffice(), t.secondBranch(), product, "1", product, "1"), ADMIN, "*");
        assertThat(twice.getBody().get("errors").get(0).get("code").asString()).isEqualTo("duplicate");
        ResponseEntity<JsonNode> zero = transfer(body(t.headOffice(), t.secondBranch(), product, "0"), ADMIN, "*");
        assertThat(zero.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(qty(t.headOffice(), product)).isEqualTo("5.000");
    }

    /**
     * Opposite transfers of the same two products race (head office to branch two with the lines in
     * one order, branch two to head office in the other). The balance locks are taken in branch and
     * product order, so none deadlocks; each is accepted or refused for stock, no balance goes below
     * zero, and the total held is unchanged.
     */
    @Test
    void oppositeTransfersNeitherDeadlockNorOversell() throws Exception {
        api.stockUp(t.headOffice(), product, "2");
        api.stockUp(t.headOffice(), other, "2");
        api.stockUp(t.secondBranch(), product, "2");
        api.stockUp(t.secondBranch(), other, "2");
        AtomicInteger n = new AtomicInteger();

        List<HttpStatusCode> statuses = Api.race(
                12,
                () -> n.incrementAndGet() % 2 == 0
                        ? transfer(body(t.headOffice(), t.secondBranch(), product, "1", other, "1"), ADMIN, "*")
                        : transfer(body(t.secondBranch(), t.headOffice(), other, "1", product, "1"), ADMIN, "*"));

        assertThat(statuses).allMatch(s -> s.value() == 201 || s.value() == 422);
        assertThat(Api.count(statuses, HttpStatus.CREATED)).isPositive();
        for (UUID p : List.of(product, other)) {
            assertThat(Double.parseDouble(qty(t.headOffice(), p))).isGreaterThanOrEqualTo(0);
            assertThat(Double.parseDouble(qty(t.secondBranch(), p))).isGreaterThanOrEqualTo(0);
            assertThat(new java.math.BigDecimal(qty(t.headOffice(), p))
                            .add(new java.math.BigDecimal(qty(t.secondBranch(), p))))
                    .isEqualByComparingTo("4");
        }
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT count(*) FROM retail_stock_balances b
                                 WHERE b.tenant_id = ? AND b.qty <> (SELECT coalesce(sum(m.qty), 0) FROM retail_stock_movements m
                                       WHERE m.tenant_id = b.tenant_id AND m.branch_id = b.branch_id AND m.product_id = b.product_id)
                                """)
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** Chapter 7 section 7.8: a replayed key returns the first response and moves nothing again. */
    @Test
    void aReplayedKeyReturnsTheFirstTransfer() {
        api.stockUp(t.headOffice(), product, "10");
        String key = UUID.randomUUID().toString();
        Map<String, Object> b = body(t.headOffice(), t.secondBranch(), product, "3");

        ResponseEntity<JsonNode> first = api.postKeyed("/transfers", b, ADMIN, "*", key);
        ResponseEntity<JsonNode> again = api.postKeyed("/transfers", b, ADMIN, "*", key);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(RetailTestSupport.id(again)).isEqualTo(RetailTestSupport.id(first));
        assertThat(qty(t.headOffice(), product)).isEqualTo("7.000");
        assertThat(qty(t.secondBranch(), product)).isEqualTo("3.000");
        ResponseEntity<JsonNode> reused =
                api.postKeyed("/transfers", body(t.headOffice(), t.secondBranch(), product, "4"), ADMIN, "*", key);
        assertThat(reused.getBody().get("code").asString()).isEqualTo("idempotency_key_reused");
        ResponseEntity<JsonNode> missing = api.post("/transfers", b, ADMIN);
        assertThat(missing.getBody().get("code").asString()).isEqualTo("idempotency_key_missing");
    }

    /**
     * FR-RET-16: a void moves the stock back at the transfer's cost and reverses both entries, and
     * only once.
     */
    @Test
    void aVoidMovesTheStockBackAndReversesBothEntries() {
        api.stockUp(t.headOffice(), product, "10");
        UUID id = RetailTestSupport.id(transfer(body(t.headOffice(), t.secondBranch(), product, "4"), ADMIN, "*"));

        ResponseEntity<JsonNode> voided =
                api.post("/transfers/" + id + "/void", Map.of("reason", "Test wrong branch"), ADMIN);

        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(voided.getBody().get("status").asString()).isEqualTo("voided");
        assertThat(voided.getBody().get("void_reason").asString()).isEqualTo("Test wrong branch");
        assertThat(qty(t.headOffice(), product)).isEqualTo("10.000");
        assertThat(qty(t.secondBranch(), product)).isEqualTo("0.000");
        for (Map<String, Object> e : entries(id)) {
            assertThat(e.get("debit")).isEqualTo(8_000L);
            assertThat(e.get("inventory")).isEqualTo(0L);
            assertThat(e.get("clearing")).isEqualTo(0L);
        }
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT count(*) FROM retail_stock_movements
                                 WHERE tenant_id = ? AND transfer_id = ? AND reverses_movement_id IS NOT NULL
                                """)
                        .params(t.tenantId(), id)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        ResponseEntity<JsonNode> again = api.post("/transfers/" + id + "/void", Map.of("reason", "Test"), ADMIN);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("transfer_voided");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data::text FROM audit_log WHERE entity_id = ? AND action = 'retail.transfer.voided'")
                        .param(id)
                        .query(String.class)
                        .single())
                .doesNotContain("cost");
    }

    /** FR-RET-16: once the destination has sold part of what arrived, the void is refused plainly. */
    @Test
    void aVoidIsRefusedWhenTheDestinationNoLongerHoldsTheStock() {
        api.stockUp(t.headOffice(), product, "10");
        UUID id = RetailTestSupport.id(transfer(body(t.headOffice(), t.secondBranch(), product, "4"), ADMIN, "*"));
        assertThat(api.sell(t.secondBranch(), "cash", product, "1").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> refused = api.post("/transfers/" + id + "/void", Map.of("reason", "Test"), ADMIN);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("transfer_stock_moved");
        assertThat(refused.getBody().get("detail").asString()).contains("BR2").contains("CABLE-2MM");
        assertThat(qty(t.headOffice(), product)).isEqualTo("6.000");
        assertThat(qty(t.secondBranch(), product)).isEqualTo("3.000");
        assertThat(api.get("/transfers/" + id, ADMIN).getBody().get("status").asString())
                .isEqualTo("completed");
    }

    /**
     * FR-RET-13, FR-RET-16: the sales role and an admin without the permission get 403 on both
     * writes; a mover without retail.profit.read sees no cost in any transfer response.
     */
    @Test
    void thePermissionIsRequiredAndCostIsHiddenWithoutProfitRead() {
        api.stockUp(t.headOffice(), product, "10");
        Map<String, Object> b = body(t.headOffice(), t.secondBranch(), product, "1");
        assertThat(transfer(b, SALES, "*").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        String adminWithout = ADMIN.replace("retail.stock.transfer,", "");
        assertThat(transfer(b, adminWithout, "*").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> created = transfer(b, MOVER, "*");
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID id = RetailTestSupport.id(created);
        assertThat(created.getBody().toString()).doesNotContain("cost");
        assertThat(api.get("/transfers/" + id, MOVER).getBody().toString()).doesNotContain("cost");
        assertThat(api.get("/transfers/" + id, SALES).getBody().toString()).doesNotContain("cost");
        assertThat(api.get("/transfers", SALES).getBody().toString()).doesNotContain("cost");
        assertThat(api.post("/transfers/" + id + "/void", Map.of("reason", "Test"), SALES)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/transfers/" + id, ADMIN)
                        .getBody()
                        .get("cost_total_minor")
                        .asLong())
                .isEqualTo(1_000);
    }

    /**
     * ADR-017: the caller acts on the source branch only; the destination may be outside its scope.
     * Readers see transfers from or to their branches only.
     */
    @Test
    void theCallerMustBeScopedToTheSourceBranch() {
        UUID third = UUID.randomUUID();
        TestDatabase.owner()
                .sql("INSERT INTO branches (id, tenant_id, code, name) VALUES (?, ?, 'BR3', 'Test Branch Three')")
                .params(third, t.tenantId())
                .update();
        api.stockUp(t.headOffice(), product, "10");
        api.stockUp(t.secondBranch(), product, "10");
        String two = t.secondBranch().toString();

        ResponseEntity<JsonNode> outside = transfer(body(t.headOffice(), third, product, "1"), ADMIN, two);
        assertThat(outside.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(outside.getBody().get("errors").get(0).get("field").asString())
                .isEqualTo("from_branch_id");
        assertThat(qty(t.headOffice(), product)).isEqualTo("10.000");

        Map<String, Object> defaulted = body(null, third, product, "2");
        defaulted.remove("from_branch_id");
        ResponseEntity<JsonNode> mine = transfer(defaulted, ADMIN, two);
        assertThat(mine.getStatusCode()).as("%s", mine.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(mine.getBody().get("from_branch_id").asString()).isEqualTo(two);
        assertThat(qty(third, product)).isEqualTo("2.000");
        UUID id = RetailTestSupport.id(mine);

        ResponseEntity<JsonNode> headOfficeOnly = api.call(
                HttpMethod.GET, "/transfers/" + id, null, ADMIN, t.headOffice().toString(), Map.of());
        assertThat(headOfficeOnly.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode atHeadOffice = api.call(
                        HttpMethod.GET,
                        "/transfers",
                        null,
                        ADMIN,
                        t.headOffice().toString(),
                        Map.of())
                .getBody();
        assertThat(atHeadOffice.get("items")).isEmpty();
        JsonNode atThird = api.call(HttpMethod.GET, "/transfers", null, ADMIN, third.toString(), Map.of())
                .getBody();
        assertThat(atThird.get("items")).hasSize(1);
        assertThat(api.call(
                                HttpMethod.POST,
                                "/transfers/" + id + "/void",
                                Map.of("reason", "Test"),
                                ADMIN,
                                third.toString(),
                                Map.of())
                        .getStatusCode())
                .as("the destination's scope does not void")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** The list filters by branch, product and date and pages newest first with a cursor. */
    @Test
    void theListFiltersAndPages() {
        api.stockUp(t.headOffice(), product, "10");
        api.stockUp(t.headOffice(), other, "10");
        UUID a = RetailTestSupport.id(transfer(body(t.headOffice(), t.secondBranch(), product, "1"), ADMIN, "*"));
        UUID b = RetailTestSupport.id(transfer(body(t.headOffice(), t.secondBranch(), other, "1"), ADMIN, "*"));
        UUID c = RetailTestSupport.id(transfer(body(t.headOffice(), t.secondBranch(), product, "1"), ADMIN, "*"));

        JsonNode page = api.get("/transfers?limit=2", ADMIN).getBody();
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("items").get(0).get("id").asString()).isEqualTo(c.toString());
        assertThat(page.get("items").get(1).get("id").asString()).isEqualTo(b.toString());
        JsonNode rest = api.get(
                        "/transfers?limit=2&cursor=" + page.get("next_cursor").asString(), ADMIN)
                .getBody();
        assertThat(rest.get("items")).hasSize(1);
        assertThat(rest.get("items").get(0).get("id").asString()).isEqualTo(a.toString());
        assertThat(rest.get("next_cursor").isNull()).isTrue();

        assertThat(api.get("/transfers?product_id=" + other, ADMIN).getBody().get("items"))
                .hasSize(1);
        assertThat(api.get("/transfers?branch_id=" + t.secondBranch(), ADMIN)
                        .getBody()
                        .get("items"))
                .hasSize(3);
        assertThat(api.get("/transfers?from=2999-01-01", ADMIN).getBody().get("items"))
                .isEmpty();
    }
}
