package com.rincoltech.bms.retail.purchasing;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.BeforeEach;
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
 * What the landed cost and supplier credit tests share (ADR-033, issue #199 amendment): a fabricated retail tenant with
 * purchasing on, two branches, a supplier and a forwarder, and the calls and ledger reads the tests make. The clock is pinned
 * at 22:30 UTC, which is already 01:30 the next day in Africa/Kampala, so no test depends on the day it runs.
 */
@Import(LandedCostTestBase.FixedClock.class)
abstract class LandedCostTestBase extends IntegrationTest {

    /** 2026-10-08 01:30 in Kampala, 2026-10-07 22:30 in UTC. */
    static final Instant NOW = Instant.parse("2026-10-07T22:30:00Z");

    static final String TODAY = "2026-10-08";
    static final String DUE = "2026-11-08";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    static final String SETTINGS = "core.settings.manage";
    static final String BUYER = ADMIN + ",retail.purchase.approve";
    static final String PAYER = BUYER + ",retail.payable.manage,retail.cashbook.read";
    /** Everything the payer holds except the right to see costs. */
    static final String NO_COSTS = PAYER.replace(",retail.profit.read", "");

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;
    UUID supplier;
    UUID forwarder;
    /** Cost 100, sell 400. */
    UUID product;
    /** Cost 150, sell 600. */
    UUID product2;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-landed", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
        product = api.product("LC-1", 100, 400);
        product2 = api.product("LC-2", 150, 600);
        supplier = RetailTestSupport.id(api.post("/suppliers", Map.of("name", "Test Supplier 01"), BUYER));
        forwarder = RetailTestSupport.id(api.post("/suppliers", Map.of("name", "Test Forwarder 01"), BUYER));
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

    ResponseEntity<JsonNode> keyed(String path, Object body, String perms) {
        return api.postKeyed(path, body, perms, "*", key());
    }

    /** Receives qty of a product blind into a branch at a cost per unit; the receipt. */
    JsonNode receive(UUID branch, UUID prod, String qty, long cost) {
        return receive(branch, prod, qty, cost, false);
    }

    JsonNode receive(UUID branch, UUID prod, String qty, long cost, boolean hold) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", prod);
        l.put("qty", qty);
        l.put("cost_minor", cost);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supplier_id", supplier);
        b.put("branch_id", branch);
        if (hold) {
            b.put("hold_for_costing", true);
        }
        b.put("lines", List.of(l));
        ResponseEntity<JsonNode> r = keyed("/goods-receipts", b, BUYER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    static UUID lineId(JsonNode receipt) {
        return uuid(receipt.get("lines").get(0), "id");
    }

    static Map<String, Object> charge(String kind, long amount) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("kind", kind);
        c.put("amount_minor", amount);
        return c;
    }

    static Map<String, Object> target(UUID receiptLine) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("receipt_line_id", receiptLine);
        return l;
    }

    static Map<String, Object> target(UUID receiptLine, String weight, Long amount) {
        Map<String, Object> l = target(receiptLine);
        if (weight != null) {
            l.put("weight", weight);
        }
        if (amount != null) {
            l.put("amount_minor", amount);
        }
        return l;
    }

    Map<String, Object> landedBody(
            String ref, long total, String method, List<Map<String, Object>> charges, List<Map<String, Object>> lines) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supplier_id", forwarder);
        b.put("supplier_ref", ref);
        b.put("cost_date", TODAY);
        b.put("due_date", DUE);
        if (method != null) {
            b.put("method", method);
        }
        b.put("total_minor", total);
        b.put("charges", charges);
        b.put("lines", lines);
        return b;
    }

    /** One freight charge of the total over the lines, by value. */
    Map<String, Object> freight(String ref, long total, UUID... receiptLines) {
        List<Map<String, Object>> ls = new ArrayList<>();
        for (UUID id : receiptLines) {
            ls.add(target(id));
        }
        return landedBody(ref, total, null, List.of(charge("freight", total)), ls);
    }

    ResponseEntity<JsonNode> landedRaw(Map<String, Object> body) {
        return keyed("/landed-costs", body, PAYER);
    }

    JsonNode landed(Map<String, Object> body) {
        ResponseEntity<JsonNode> r = landedRaw(body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    JsonNode landedCost(UUID id) {
        return api.get("/landed-costs/" + id, PAYER).getBody();
    }

    ResponseEntity<JsonNode> voidLanded(UUID id) {
        return keyed("/landed-costs/" + id + "/void", Map.of("reason", "Test wrong invoice"), PAYER);
    }

    long costOf(UUID prod) {
        return api.get("/products/" + prod, ADMIN).getBody().get("cost_minor").asLong();
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

    long scalar(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    ResponseEntity<JsonNode> transfer(UUID from, UUID to, UUID prod, String qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from_branch_id", from);
        body.put("to_branch_id", to);
        body.put("lines", List.of(Map.of("product_id", prod, "qty", qty)));
        return api.postKeyed("/transfers", body, ADMIN, "*", key());
    }

    /** The cost of sales the daily profit report shows for the head office on a day. */
    long costOfSalesOn(String day) {
        JsonNode rows = api.get("/reports/profit/daily?from=" + day + "&to=" + day, ADMIN)
                .getBody()
                .get("rows");
        for (JsonNode r : rows) {
            if (r.get("branch_id").asString().equals(hq.toString())) {
                return r.get("cost_of_sales_minor").asLong();
            }
        }
        return 0;
    }

    /** The stock valuation of a product in a branch: what it holds times the product's cost now. */
    long valuation(UUID branch, UUID prod) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce((SELECT sum(b.qty) FROM retail_stock_balances b
                                          WHERE b.branch_id = ? AND b.product_id = ?), 0) * p.cost_minor
                          FROM retail_products p WHERE p.id = ?
                        """)
                .params(branch, prod, prod)
                .query(java.math.BigDecimal.class)
                .single()
                .longValueExact();
    }

    /** Every trial balance line of the tenant adds to zero: debits equal credits over the whole journal. */
    void assertBooksBalance() {
        assertThat(scalar(
                        "SELECT coalesce(sum(debit - credit), 0) FROM journal_lines WHERE tenant_id = ?", t.tenantId()))
                .isZero();
    }
}
