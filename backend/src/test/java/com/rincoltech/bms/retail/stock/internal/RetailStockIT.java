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
     * FR-RET-08, FR-RET-11: a count shows its variance; commit writes the adjustment against the
     * balance at that moment and posts gains and losses at cost; a second commit is refused.
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
                .isEqualTo("0.000");
        assertThat(qty()).isEqualTo("6.000");

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
                .isEqualTo("-1.500");
        assertThat(qty()).isEqualTo("4.500");
        Map<String, Long> net = net(second);
        assertThat(net).containsEntry("stock_shrinkage", 600L).containsEntry("inventory", -600L);

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
