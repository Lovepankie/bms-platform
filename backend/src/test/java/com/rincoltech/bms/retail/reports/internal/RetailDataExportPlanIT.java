package com.rincoltech.bms.retail.reports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Restocks sheet of "Download my data" (ADR-074) at the size of a busy shop: 4,000 restock lines, each stocked into
 * the head office and every tenth split across both branches. The Branch column is a subquery per line over the purchase
 * movements; it must find them through the {@code retail_stock_movements_source} index by the line's purchase, not walk
 * every purchase movement of the business once per line (which ran a shop with tens of thousands of lines into the 30
 * minute limit). Read as {@code bms_app} under row-level security, as the runner reads it. Names and figures are
 * fabricated.
 */
class RetailDataExportPlanIT extends IntegrationTest {

    static final int PURCHASES = 2000;

    @Autowired
    TestRestTemplate http;

    @Test
    void theBranchOfEachRestockLineIsFoundByItsPurchaseThroughTheIndex() throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("dxplan", false, true);
        RetailTestSupport api = new RetailTestSupport(http, t);
        UUID product = api.product("DXP1", 600, 1000);
        var owner = TestDatabase.owner();
        owner.sql("""
                        INSERT INTO retail_purchases (id, tenant_id, purchase_no, purchased_on, payment_method, currency,
                                                      total_minor, created_by)
                        SELECT gen_random_uuid(), ?, 'P' || lpad(n::text, 6, '0'), DATE '2026-01-01' + (n % 250), 'cash',
                               'UGX', 12000, ?
                          FROM generate_series(1, ?) n
                        """).params(t.tenantId(), api.userId(), PURCHASES).update();
        owner.sql("""
                        INSERT INTO retail_purchase_lines (id, tenant_id, purchase_id, line_no, product_id, cost_minor,
                                                           sell_minor, qty_total, line_total_minor)
                        SELECT gen_random_uuid(), pu.tenant_id, pu.id, n, ?, 600, 1000, 10, 6000
                          FROM retail_purchases pu, generate_series(1, 2) n
                         WHERE pu.tenant_id = ?
                        """).params(product, t.tenantId()).update();
        owner.sql("""
                        INSERT INTO retail_stock_movements (id, tenant_id, occurred_at, business_date, branch_id, product_id,
                                                            kind, qty, unit_cost_minor, source_type, source_id, source_line_id)
                        SELECT gen_random_uuid(), l.tenant_id, now(), current_date, b.branch, l.product_id, 'purchase', b.qty, 600,
                               'retail.purchase', l.purchase_id, l.id
                          FROM (SELECT l.*, row_number() OVER (ORDER BY l.id) AS k
                                  FROM retail_purchase_lines l WHERE l.tenant_id = ?) l
                         CROSS JOIN LATERAL (VALUES (?::uuid, CASE WHEN l.k % 10 = 0 THEN 4 ELSE 10 END),
                                                    (?::uuid, CASE WHEN l.k % 10 = 0 THEN 6 END)) b (branch, qty)
                         WHERE b.qty IS NOT NULL
                        """).params(t.tenantId(), t.headOffice(), t.secondBranch()).update();
        owner.sql("ANALYZE retail_purchases, retail_purchase_lines, retail_stock_movements")
                .update();

        JsonNode plan = asApp(t.tenantId(), c -> {
            try (PreparedStatement ps = c.prepareStatement("EXPLAIN (FORMAT JSON) " + RetailDataExport.RESTOCKS);
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                return JsonMapper.builder().build().readTree(rs.getString(1));
            }
        });
        // Each read of the movements, as "node type | index | index condition | filter".
        List<JsonNode> nodes = new ArrayList<>();
        nodes(plan, nodes);
        List<String> movements = nodes.stream()
                .filter(n -> n.path("Relation Name").asString().equals("retail_stock_movements")
                        || n.path("Index Name").asString().startsWith("retail_stock_movements"))
                .filter(n -> !n.path("Node Type").asString().equals("Bitmap Heap Scan"))
                .map(n -> String.join(
                        " | ",
                        n.path("Node Type").asString(),
                        n.path("Index Name").asString(),
                        n.path("Index Cond").asString(),
                        n.path("Filter").asString()))
                .toList();
        assertThat(movements)
                .isNotEmpty()
                .allSatisfy(
                        m -> assertThat(m).contains("| retail_stock_movements_source |", "source_id = l.purchase_id"));

        Map<String, Integer> branches = new HashMap<>();
        int lines = asApp(t.tenantId(), c -> {
            int n = 0;
            try (PreparedStatement ps = c.prepareStatement(RetailDataExport.RESTOCKS);
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    n++;
                    branches.merge(rs.getString("branch"), 1, Integer::sum);
                }
            }
            return n;
        });
        assertThat(lines).isEqualTo(2 * PURCHASES);
        assertThat(branches)
                .containsOnly(
                        Map.entry("Head Office", 2 * PURCHASES - 2 * PURCHASES / 10),
                        Map.entry("Test Branch Two (6), Head Office (4)", 2 * PURCHASES / 10));
    }

    /** Every node of the plan, at any depth. */
    static void nodes(JsonNode node, List<JsonNode> found) {
        if (node.has("Node Type")) {
            found.add(node);
        }
        for (JsonNode child : node) {
            nodes(child, found);
        }
    }

    /** A bms_app transaction bound to the tenant, as the transaction manager opens it. */
    static <T> T asApp(UUID tenantId, SqlWork<T> work) throws Exception {
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, tenantId.toString());
                ps.execute();
            }
            try {
                return work.run(c);
            } finally {
                c.rollback();
            }
        }
    }

    interface SqlWork<T> {
        T run(Connection c) throws Exception;
    }
}
