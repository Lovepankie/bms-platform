package com.rincoltech.bms.retail.stock.internal;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.ArrayList;
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
 * Stock-takes, the append-only movement ledger and the nightly reconciliation (#52; FR-RET-03,
 * FR-RET-08, FR-RET-11). All amounts fabricated.
 */
class RetailStockIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    TenantJobs tenantJobs;

    @Autowired
    StockReconciliation reconciliation;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-stock", false, true);
        api = new RetailTestSupport(http, t);
        product = api.product("SKT-13A", 400, 600);
    }

    /**
     * FR-RET-08, FR-RET-11: a count shows its variance against the balance when it was taken;
     * commit applies that variance as the adjustment, so a sale between count and commit stays in
     * the balance, and posts gains and losses at cost; a second commit is refused.
     */
    @Test
    void aStocktakeAdjustsToTheCountAndPostsTheVariance() {
        api.stockUp(t.headOffice(), product, "10");
        JsonNode first = journal("inventory");
        assertThat(first.get("debit").asLong()).isEqualTo(4_000);
        api.sell(t.headOffice(), "cash", product, "3");

        ResponseEntity<JsonNode> salesDraft = api.post(
                "/stocktakes",
                Map.of(
                        "branch_id",
                        t.headOffice(),
                        "lines",
                        List.of(Map.of("product_id", product, "counted_qty", "6"))),
                SALES);
        assertThat(salesDraft.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> draft = api.post(
                "/stocktakes",
                Map.of(
                        "branch_id",
                        t.headOffice(),
                        "note",
                        "Test count",
                        "lines",
                        List.of(Map.of("product_id", product, "counted_qty", "6"))),
                ADMIN);
        assertThat(draft.getStatusCode()).as("%s", draft.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode line = draft.getBody().get("lines").get(0);
        assertThat(line.get("expected_qty").asString()).isEqualTo("7.000");
        assertThat(line.get("variance_qty").asString()).isEqualTo("-1.000");
        assertThat(draft.getBody().get("status").asString()).isEqualTo("draft");
        UUID id = RetailTestSupport.id(draft);

        api.sell(t.headOffice(), "cash", product, "1");
        ResponseEntity<JsonNode> committed = api.post("/stocktakes/" + id + "/commit", Map.of(), ADMIN);
        assertThat(committed.getStatusCode()).as("%s", committed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(committed.getBody().get("status").asString()).isEqualTo("committed");
        assertThat(committed
                        .getBody()
                        .get("lines")
                        .get(0)
                        .get("committed_variance_qty")
                        .asString())
                .isEqualTo("-1.000");
        assertThat(qty()).isEqualTo("5.000");

        UUID second = RetailTestSupport.id(api.post(
                "/stocktakes",
                Map.of(
                        "branch_id",
                        t.headOffice(),
                        "lines",
                        List.of(Map.of("product_id", product, "counted_qty", "4.5"))),
                ADMIN));
        JsonNode done =
                api.post("/stocktakes/" + second + "/commit", Map.of(), ADMIN).getBody();
        assertThat(done.get("lines").get(0).get("committed_variance_qty").asString())
                .isEqualTo("-0.500");
        assertThat(qty()).isEqualTo("4.500");
        Map<String, Long> net = net(second);
        assertThat(net).containsEntry("stock_shrinkage", 200L).containsEntry("inventory", -200L);

        ResponseEntity<JsonNode> again = api.post("/stocktakes/" + second + "/commit", Map.of(), ADMIN);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("stocktake_committed");
        assertThat(api.get("/stocktakes/" + second, SALES)
                        .getBody()
                        .get("lines")
                        .get(0)
                        .has("unit_cost_minor"))
                .isFalse();
    }

    /**
     * FR-RET-08 (review F1): a sale between the count and the commit is not undone. The shelf held
     * what was counted, the cashier then sold from it, so the commit applies only the shortfall
     * measured at the count and posts no false gain.
     */
    @Test
    void aSaleBetweenDraftAndCommitStaysInTheBalance() {
        api.stockUp(t.headOffice(), product, "10");
        UUID id = RetailTestSupport.id(api.post(
                "/stocktakes",
                Map.of(
                        "branch_id",
                        t.headOffice(),
                        "lines",
                        List.of(Map.of("product_id", product, "counted_qty", "9"))),
                ADMIN));
        api.sell(t.headOffice(), "cash", product, "3");

        ResponseEntity<JsonNode> committed = api.post("/stocktakes/" + id + "/commit", Map.of(), ADMIN);
        assertThat(committed.getStatusCode()).as("%s", committed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(committed
                        .getBody()
                        .get("lines")
                        .get(0)
                        .get("committed_variance_qty")
                        .asString())
                .isEqualTo("-1.000");
        assertThat(qty()).isEqualTo("6.000");
        assertThat(net(id)).containsEntry("stock_shrinkage", 400L).containsEntry("inventory", -400L);
    }

    /**
     * FR-RET-08 (review F1): when the stock moved after the count so far that the count's variance
     * would take the balance below zero, the commit is refused and the lines are to be recounted.
     */
    @Test
    void aCommitThatWouldGoNegativeAfterLaterMovementsAsksForARecount() {
        api.stockUp(t.headOffice(), product, "10");
        UUID id = RetailTestSupport.id(api.post(
                "/stocktakes",
                Map.of(
                        "branch_id",
                        t.headOffice(),
                        "lines",
                        List.of(Map.of("product_id", product, "counted_qty", "2"))),
                ADMIN));
        api.sell(t.headOffice(), "cash", product, "5");

        ResponseEntity<JsonNode> refused =
                api.post("/stocktakes/" + id + "/commit", Map.of("acknowledged", List.of(product)), ADMIN);
        assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("stock_moved_since_count");
        assertThat(refused.getBody().get("detail").asString())
                .contains("SKT-13A")
                .contains("recount");
        assertThat(qty()).isEqualTo("5.000");
        assertThat(api.get("/stocktakes/" + id, ADMIN).getBody().get("status").asString())
                .isEqualTo("draft");
    }

    /**
     * Issue #636: a count that takes an item holding stock to 0, or to less than half of it, is committed only when its
     * line is acknowledged, so a stale draft from another tab or a direct call never writes it unasked; nothing moves on
     * the refusal.
     */
    @Test
    void aBigDropNotAcknowledgedIsRefused() {
        UUID other = api.product("SKT-14B", 400, 600);
        api.stockUp(t.headOffice(), product, "10");
        api.stockUp(t.headOffice(), other, "8");
        UUID id = count(
                Map.of("product_id", product, "counted_qty", "4.5"), Map.of("product_id", other, "counted_qty", "0"));

        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of(), Map.of("acknowledged", List.of()), Map.of("acknowledged", List.of(product)))) {
            ResponseEntity<JsonNode> refused = api.post("/stocktakes/" + id + "/commit", body, ADMIN);
            assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody().get("code").asString()).isEqualTo("big_drop_not_acknowledged");
            assertThat(refused.getBody().get("detail").asString())
                    .contains("Test Product SKT-14B")
                    .contains("more than half")
                    .contains("commit the count")
                    .doesNotContainIgnoringCase("finish");
        }
        assertThat(refused(id, Map.of()).get("detail").asString()).contains("SKT-13A");
        assertThat(qty()).isEqualTo("10.000");
        assertThat(api.get("/stocktakes/" + id, ADMIN).getBody().get("status").asString())
                .isEqualTo("draft");
    }

    /** Issue #656: a count with many big drops is refused naming the first five and counting the rest. */
    @Test
    void theBigDropRefusalNamesAtMostFiveItems() {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 7; i >= 1; i--) {
            UUID p = api.product("SKT-15" + i, 400, 600);
            api.stockUp(t.headOffice(), p, "6");
            lines.add(Map.of("product_id", p, "counted_qty", "0"));
        }
        UUID id = count(lines.toArray(Map<?, ?>[]::new));

        String detail = refused(id, Map.of()).get("detail").asString();
        assertThat(detail).contains(" and 2 more removes more than half");
        assertThat(detail.split("SKT-15", -1)).hasSize(6);
        // The first five by item code, the same on every refusal.
        assertThat(detail)
                .contains(
                        "SKT-151, Test Product SKT-152, Test Product SKT-153, Test Product SKT-154, Test Product SKT-155 and");
        assertThat(refused(id, Map.of()).get("detail").asString()).isEqualTo(detail);
        assertThat(api.get("/stocktakes/" + id, ADMIN).getBody().get("status").asString())
                .isEqualTo("draft");
    }

    /** Issue #636: with every big drop acknowledged the count is committed as before. */
    @Test
    void aBigDropAcknowledgedIsCommitted() {
        UUID other = api.product("SKT-14B", 400, 600);
        api.stockUp(t.headOffice(), product, "10");
        api.stockUp(t.headOffice(), other, "8");
        UUID id = count(
                Map.of("product_id", product, "counted_qty", "4.5"), Map.of("product_id", other, "counted_qty", "0"));

        ResponseEntity<JsonNode> committed =
                api.post("/stocktakes/" + id + "/commit", Map.of("acknowledged", List.of(other, product)), ADMIN);
        assertThat(committed.getStatusCode()).as("%s", committed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(committed.getBody().get("status").asString()).isEqualTo("committed");
        assertThat(qty()).isEqualTo("4.500");
    }

    /**
     * Issue #636: a count that keeps half or more, or adds, is not a big drop and needs no acknowledgement; neither does a
     * count of an item that held nothing.
     */
    @Test
    void aLineThatIsNotABigDropNeedsNoAcknowledgement() {
        UUID added = api.product("SKT-14B", 400, 600);
        UUID empty = api.product("SKT-14C", 400, 600);
        api.stockUp(t.headOffice(), product, "10");
        api.stockUp(t.headOffice(), added, "2");
        UUID id = count(
                Map.of("product_id", product, "counted_qty", "5"),
                Map.of("product_id", added, "counted_qty", "3"),
                Map.of("product_id", empty, "counted_qty", "0"));

        ResponseEntity<JsonNode> committed = api.post("/stocktakes/" + id + "/commit", Map.of(), ADMIN);
        assertThat(committed.getStatusCode()).as("%s", committed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(qty()).isEqualTo("5.000");
    }

    /**
     * Issue #656: a draft count is discarded without writing stock, by the person who made it or by anyone who may count at
     * its branch; a second discard returns it unchanged, it can no longer be committed, a committed count is refused, and
     * the discarded draft no longer blocks a correction or the closing of the branch.
     */
    @Test
    void aDraftCountIsDiscardedOnceAndNeverWritesStock() {
        api.stockUp(t.headOffice(), product, "10");
        UUID id = count(Map.of("product_id", product, "counted_qty", "0"));
        String elsewhere = t.secondBranch().toString();

        // Someone else who may not count at the branch finds no such count; the maker may discard it from anywhere.
        RetailTestSupport other = new RetailTestSupport(http, t);
        assertThat(other.call(HttpMethod.POST, "/stocktakes/" + id + "/discard", null, ADMIN, elsewhere, Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.post("/stocktakes/" + id + "/discard", null, SALES).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> discarded =
                api.call(HttpMethod.POST, "/stocktakes/" + id + "/discard", null, ADMIN, elsewhere, Map.of());
        assertThat(discarded.getStatusCode()).as("%s", discarded.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(discarded.getBody().get("status").asString()).isEqualTo("discarded");
        assertThat(qty()).isEqualTo("10.000");

        // Idempotent: the same count back, audited once.
        ResponseEntity<JsonNode> again = other.post("/stocktakes/" + id + "/discard", null, ADMIN);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("status").asString()).isEqualTo("discarded");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND entity_id = ?")
                        .params(t.tenantId(), "retail.stocktake.discarded", id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        JsonNode refused = refused(id, Map.of("acknowledged", List.of(product)));
        assertThat(refused.get("code").asString()).isEqualTo("stocktake_discarded");
        // Issue #664: the owner reads one word for it, cancelled.
        assertThat(refused.get("detail").asString()).contains("cancelled").doesNotContain("discarded");
        assertThat(qty()).isEqualTo("10.000");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_stocktakes WHERE status = 'draft' AND branch_id = ?")
                        .param(t.headOffice())
                        .query(Long.class)
                        .single())
                .isZero();

        UUID done = count(Map.of("product_id", product, "counted_qty", "9"));
        assertThat(api.post("/stocktakes/" + done + "/commit", Map.of(), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> late = api.post("/stocktakes/" + done + "/discard", null, ADMIN);
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody().get("code").asString()).isEqualTo("stocktake_committed");
        assertThat(api.get("/stocktakes/" + done, ADMIN).getBody().get("status").asString())
                .isEqualTo("committed");
    }

    /**
     * Issue #681: a person lists their own draft counts at a branch, newest first, with their lines; committed and discarded
     * counts and another person's drafts are left out, and someone who may not count there is refused.
     */
    @Test
    void aPersonListsOnlyTheirOwnDraftCountsAtABranch() {
        api.stockUp(t.headOffice(), product, "10");
        UUID older = count(Map.of("product_id", product, "counted_qty", "9"));
        UUID newer = count(Map.of("product_id", product, "counted_qty", "8"));
        UUID gone = count(Map.of("product_id", product, "counted_qty", "7"));
        api.post("/stocktakes/" + gone + "/discard", null, ADMIN);
        RetailTestSupport other = new RetailTestSupport(http, t);
        other.post(
                "/stocktakes",
                Map.of(
                        "branch_id",
                        t.headOffice(),
                        "lines",
                        List.of(Map.of("product_id", product, "counted_qty", "6"))),
                ADMIN);

        ResponseEntity<JsonNode> mine = api.get("/stocktakes?branch_id=" + t.headOffice(), ADMIN);
        assertThat(mine.getStatusCode()).as("%s", mine.getBody()).isEqualTo(HttpStatus.OK);
        List<String> ids = new ArrayList<>();
        mine.getBody().get("items").forEach(i -> ids.add(i.get("id").asString()));
        assertThat(ids).containsExactly(newer.toString(), older.toString());
        assertThat(mine.getBody()
                        .get("items")
                        .get(0)
                        .get("lines")
                        .get(0)
                        .get("counted_qty")
                        .asString())
                .isEqualTo("8.000");
        assertThat(api.get("/stocktakes?branch_id=" + t.headOffice(), SALES).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Issue #656: a discarded count is as final as a committed one, for the application and the owner alike. */
    @Test
    void aDiscardedCountCannotChange() {
        UUID id = count(Map.of("product_id", product, "counted_qty", "2"));
        api.post("/stocktakes/" + id + "/discard", null, ADMIN);
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_stocktakes SET status = 'draft', discarded_at = NULL, discarded_by = NULL"
                                + " WHERE id = ?")
                        .param(id)
                        .update())
                .rootCause()
                .hasMessageContaining("discarded stock-take is not allowed");
    }

    private UUID count(Map<?, ?>... lines) {
        return RetailTestSupport.id(
                api.post("/stocktakes", Map.of("branch_id", t.headOffice(), "lines", List.of(lines)), ADMIN));
    }

    private JsonNode refused(UUID id, Map<String, Object> body) {
        ResponseEntity<JsonNode> refused = api.post("/stocktakes/" + id + "/commit", body, ADMIN);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        return refused.getBody();
    }

    /** FR-RET-03: movements are append-only for the application and the owner alike. */
    @Test
    void movementsAreAppendOnly() {
        api.stockUp(t.headOffice(), product, "2");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_stock_movements SET qty = 99 WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .update())
                .rootCause()
                .hasMessageContaining("append-only");
        Long privileges = TestDatabase.owner().sql("""
                        SELECT count(*) FROM information_schema.role_table_grants
                         WHERE grantee = 'bms_app' AND table_name = 'retail_stock_movements'
                           AND privilege_type IN ('UPDATE', 'DELETE')
                        """).query(Long.class).single();
        assertThat(privileges).isZero();
    }

    /** FR-RET-03: a balance written outside the stock ledger is reported, not corrected. */
    @Test
    void theReconciliationReportsABalanceThatDiffersFromItsMovements() {
        api.stockUp(t.headOffice(), product, "5");
        List<Integer> clean = new ArrayList<>();
        tenantJobs.forEachActiveTenantWithModule("test.reconcile", "retail", tenantId -> {
            if (tenantId.equals(t.tenantId())) {
                clean.add(reconciliation.run().size());
            }
        });
        assertThat(clean).containsExactly(0);

        TestDatabase.owner()
                .sql("UPDATE retail_stock_balances SET qty = qty + 1 WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        List<Integer> found = new ArrayList<>();
        tenantJobs.forEachActiveTenantWithModule("test.reconcile", "retail", tenantId -> {
            if (tenantId.equals(t.tenantId())) {
                found.add(reconciliation.run().size());
            }
        });
        assertThat(found).containsExactly(1);
        String data = TestDatabase.owner()
                .sql(
                        "SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'retail.stock.reconciliation_mismatch'")
                .param(t.tenantId())
                .query(String.class)
                .single();
        assertThat(data).contains(product.toString()).contains("\"balance\": \"6.000\"");
        assertThat(qty()).isEqualTo("6.000");
    }

    String qty() {
        return api.get("/stock?branch_id=" + t.headOffice(), ADMIN)
                .getBody()
                .get("items")
                .get(0)
                .get("qty")
                .asString();
    }

    JsonNode journal(String systemKey) {
        Map<String, Long> row = TestDatabase.owner()
                .sql("""
                        SELECT l.debit, l.credit FROM journal_lines l JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = ? ORDER BY l.created_at LIMIT 1
                        """)
                .params(t.tenantId(), systemKey)
                .query((rs, n) -> Map.of("debit", rs.getLong(1), "credit", rs.getLong(2)))
                .single();
        return tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(row);
    }

    Map<String, Long> net(UUID sourceId) {
        Map<String, Long> net = new java.util.LinkedHashMap<>();
        TestDatabase.owner()
                .sql("""
                        SELECT a.system_key, l.debit - l.credit FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.source_id = ?
                        """)
                .param(sourceId)
                .query((rs, n) -> net.merge(rs.getString(1), rs.getLong(2), Long::sum))
                .list();
        return net;
    }
}
