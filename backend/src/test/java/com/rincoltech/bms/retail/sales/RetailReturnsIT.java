package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Customer returns and sales credit notes (ADR-033, issue #201; docs/specs/trade-capabilities.md sections 5.3 and 13):
 * the limits, stock and cost restored at the original snapshot, exact pack conversions, the price actually charged, the
 * refund by money or against a credit buyer's balance (and the guard that binds a lowering of the paid amount to a voided
 * return), numbering, idempotency, the cash book and the daily profit on the right day, maker-checker above a threshold,
 * the void by reversal, and "capability off equals today". Every name and amount is fabricated. The clock is pinned at
 * 22:30 UTC, which is already 01:30 the next day in Africa/Kampala, so every test runs at the edge where the UTC date and
 * the business date differ and none depends on the day it runs.
 */
@Import(RetailReturnsIT.FixedClock.class)
class RetailReturnsIT extends IntegrationTest {

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
    /** The administrator column after migration V45: retail.sale.return is the administrator's only. */
    static final String RETURNER = ADMIN + ",retail.sale.return,retail.cashbook.read";

    static final String CLERK = RetailTestSupport.SALES;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;
    /** Cost 30, sell 100 per piece. */
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-ret", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
        product = api.product("RT-1", 30, 100);
        api.stockUp(hq, product, "1000");
        api.stockUp(br2, product, "1000");
        capability("returns", true);
        capability("customer_accounts", true);
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

    ResponseEntity<JsonNode> sellRaw(
            UUID branch, String method, UUID customer, UUID prod, String qty, Long price, String date) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", prod);
        line.put("qty", qty);
        if (price != null) {
            line.put("unit_price_minor", price);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", method);
        if (customer != null) {
            body.put("customer_id", customer);
        }
        if (date != null) {
            body.put("sale_date", date);
        }
        body.put("lines", List.of(line));
        return api.postKeyed(
                "/sales",
                body,
                ADMIN + ",retail.credit.override",
                "*",
                UUID.randomUUID().toString());
    }

    UUID sold(UUID branch, String method, UUID customer, UUID prod, String qty, Long price, String date) {
        ResponseEntity<JsonNode> r = sellRaw(branch, method, customer, prod, qty, price, date);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    UUID cashSale(UUID branch, String qty, String date) {
        return sold(branch, "cash", null, product, qty, null, date);
    }

    UUID customer(String name) {
        return RetailTestSupport.id(api.post("/customers", Map.of("name", name), CLERK));
    }

    UUID creditSale(UUID branch, UUID customer, String qty, String date) {
        return sold(branch, "credit", customer, product, qty, null, date);
    }

    JsonNode sale(UUID id) {
        return api.get("/sales/" + id, ADMIN).getBody();
    }

    UUID lineOf(UUID sale, int index) {
        return UUID.fromString(sale(sale).get("lines").get(index).get("id").asString());
    }

    static Map<String, Object> line(UUID saleLine, String qty, String disposition) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sale_line_id", saleLine);
        m.put("qty", qty);
        m.put("reason", "defective");
        m.put("disposition", disposition);
        return m;
    }

    static Map<String, Object> restockAt(Map<String, Object> line, UUID branch) {
        line.put("restock_branch_id", branch);
        return line;
    }

    ResponseEntity<JsonNode> ret(
            UUID sale,
            List<Map<String, Object>> lines,
            String refund,
            String money,
            String perms,
            String scope,
            String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", lines);
        body.put("refund_method", refund);
        if (money != null) {
            body.put("money_method", money);
        }
        return api.postKeyed("/sales/" + sale + "/returns", body, perms, scope, key);
    }

    ResponseEntity<JsonNode> ret(UUID sale, List<Map<String, Object>> lines, String refund) {
        return ret(sale, lines, refund, null, RETURNER, "*", UUID.randomUUID().toString());
    }

    /** A return that must be recorded; the credit note. */
    JsonNode back(UUID sale, List<Map<String, Object>> lines, String refund, String money) {
        ResponseEntity<JsonNode> r =
                ret(sale, lines, refund, money, RETURNER, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("executed").asBoolean()).isTrue();
        return r.getBody().get("sale_return");
    }

    JsonNode back(UUID sale, List<Map<String, Object>> lines, String refund) {
        return back(sale, lines, refund, null);
    }

    static UUID uuid(JsonNode n, String field) {
        return UUID.fromString(n.get(field).asString());
    }

    ResponseEntity<JsonNode> voidReturn(UUID id, String reason) {
        return api.postKeyed(
                "/returns/" + id + "/void",
                Map.of("reason", reason),
                RETURNER,
                "*",
                UUID.randomUUID().toString());
    }

    BigDecimal stockAt(UUID branch, UUID prod) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE branch_id = ? AND product_id = ?")
                .params(branch, prod)
                .query(BigDecimal.class)
                .single();
    }

    /** The movements of a stock source: sum of quantities, for a kind. */
    BigDecimal moved(UUID sourceId, String kind) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(qty), 0) FROM retail_stock_movements WHERE source_id = ? AND kind = ?")
                .params(sourceId, kind)
                .query(BigDecimal.class)
                .single();
    }

    long movementUnitCost(UUID returnId) {
        return TestDatabase.owner()
                .sql("SELECT unit_cost_minor FROM retail_stock_movements WHERE source_id = ? AND kind = 'return'")
                .param(returnId)
                .query(Long.class)
                .single();
    }

    /** What the entries of a return posted: system key to {debit, credit}, over every entry of the return. */
    Map<String, long[]> posted(UUID returnId) {
        Map<String, long[]> out = new TreeMap<>();
        TestDatabase.owner()
                .sql("""
                        SELECT a.system_key, sum(l.debit) AS debit, sum(l.credit) AS credit FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.tenant_id = ? AND e.source_type = 'retail.sale_return' AND e.source_id = ?
                         GROUP BY a.system_key
                        """)
                .params(t.tenantId(), returnId)
                .query((rs, n) -> {
                    out.put(rs.getString("system_key"), new long[] {rs.getLong("debit"), rs.getLong("credit")});
                    return 0;
                })
                .list();
        return out;
    }

    /** Net debit of an account over the branch's entries. */
    long net(String systemKey, UUID branch) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.tenant_id = ? AND e.branch_id = ? AND a.system_key = ?
                        """)
                .params(t.tenantId(), branch, systemKey)
                .query(Long.class)
                .single();
    }

    long ledgerDebtors(UUID customer) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = 'trade_debtors'
                           AND l.subledger_id IN (SELECT id FROM retail_sales WHERE customer_id = ?)
                        """)
                .params(t.tenantId(), customer)
                .query(Long.class)
                .single();
    }

    JsonNode cashDays(UUID branch, String from, String to) {
        ResponseEntity<JsonNode> r =
                api.get("/reports/cash/daily?branch_id=" + branch + "&from=" + from + "&to=" + to, RETURNER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static JsonNode dayRow(JsonNode report, String day) {
        for (JsonNode r : report.get("items")) {
            if (r.get("business_date").asString().equals(day)) {
                return r;
            }
        }
        throw new AssertionError("no row for " + day + " in " + report);
    }

    JsonNode profitDay(String day) {
        JsonNode p = api.get("/reports/profit/daily?from=" + day + "&to=" + day, RETURNER)
                .getBody();
        for (JsonNode r : p.get("rows")) {
            if (r.get("branch_id").asString().equals(hq.toString())) {
                return r;
            }
        }
        throw new AssertionError("no profit row for hq on " + day + " in " + p);
    }

    // ---- capability off equals today -------------------------------------------------------

    @Test
    void withTheCapabilityOffEveryRouteIsAbsentAndTheSaleIsAsBefore() {
        UUID s = cashSale(hq, "5", null);
        UUID l = lineOf(s, 0);
        capability("returns", false);
        ResponseEntity<JsonNode> post = ret(s, List.of(line(l, "1", "restock")), "cash");
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(post)).isEqualTo("capability_not_enabled");
        for (String path : List.of("/sales/" + s + "/returns", "/returns", "/returns/" + UUID.randomUUID())) {
            ResponseEntity<JsonNode> r = api.get(path, RETURNER);
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(code(r)).isEqualTo("capability_not_enabled");
        }
        assertThat(api.postKeyed(
                                "/returns/" + UUID.randomUUID() + "/void",
                                Map.of("reason", "Test reason"),
                                RETURNER,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // The sale reads and voids exactly as it did before returns existed.
        assertThat(sale(s).has("returns")).isFalse();
        assertThat(api.postKeyed(
                                "/sales/" + s + "/void",
                                Map.of("reason", "Test void"),
                                ADMIN,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(stockAt(hq, product)).isEqualByComparingTo("1000");
    }

    @Test
    void theSalesRoleCannotTakeGoodsBack() {
        UUID s = cashSale(hq, "5", null);
        ResponseEntity<JsonNode> r = ret(
                s,
                List.of(line(lineOf(s, 0), "1", "restock")),
                "cash",
                null,
                CLERK,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // The administrator's plain sale permissions are not enough either: retail.sale.return is its own permission.
        assertThat(ret(
                                s,
                                List.of(line(lineOf(s, 0), "1", "restock")),
                                "cash",
                                null,
                                ADMIN,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- stock, cost and the postings --------------------------------------------------------

    @Test
    void aReturnRestoresStockAndCostAtTheSaleSnapshotAndPostsBalancedEntries() {
        UUID s = cashSale(hq, "10", null);
        // The product's cost changes after the sale: the return still reverses the cost the sale took.
        api.editPrices(product, Map.of("cost_minor", 50, "reason", "Test later"), ADMIN);
        JsonNode r = back(s, List.of(line(lineOf(s, 0), "4", "restock")), "cash");
        UUID id = uuid(r, "id");
        assertThat(r.get("credit_note_no").asString()).isEqualTo("CNHQ-000001");
        assertThat(r.get("returned_on").asString()).isEqualTo(TODAY);
        assertThat(r.get("total_minor").asLong()).isEqualTo(400);
        assertThat(r.get("cost_minor").asLong()).isEqualTo(120);
        assertThat(r.get("refund_minor").asLong()).isEqualTo(400);
        assertThat(r.get("account_credit_minor").asLong()).isZero();
        assertThat(r.get("lines").get(0).get("line_total_minor").asLong()).isEqualTo(400);
        assertThat(r.get("lines").get(0).get("unit_cost_minor").asLong()).isEqualTo(30);
        assertThat(stockAt(hq, product)).isEqualByComparingTo("994");
        assertThat(moved(id, "return")).isEqualByComparingTo("4");
        Map<String, long[]> p = posted(id);
        assertThat(p.get("sales_returns")).containsExactly(400, 0);
        assertThat(p.get("cash_on_hand")).containsExactly(0, 400);
        assertThat(p.get("inventory")).containsExactly(120, 0);
        assertThat(p.get("cost_of_goods_sold")).containsExactly(0, 120);
        assertThat(p.values().stream().mapToLong(a -> a[0]).sum())
                .isEqualTo(p.values().stream().mapToLong(a -> a[1]).sum());
        // The sale itself is not edited: it still reads what it sold.
        assertThat(sale(s).get("total_minor").asLong()).isEqualTo(1_000);
        // Read back, listed under the sale and in the credit note list.
        assertThat(api.get("/returns/" + id, RETURNER)
                        .getBody()
                        .get("credit_note_no")
                        .asString())
                .isEqualTo("CNHQ-000001");
        assertThat(api.get("/sales/" + s + "/returns", RETURNER).getBody()).hasSize(1);
        assertThat(api.get("/returns?q=CNHQ-000001", RETURNER).getBody().get("items"))
                .hasSize(1);
    }

    @Test
    void creditNotesAreNumberedPerBranchWithoutGapsAndAVoidKeepsItsNumber() {
        UUID a = cashSale(hq, "10", null);
        UUID b = cashSale(br2, "10", null);
        JsonNode first = back(a, List.of(line(lineOf(a, 0), "1", "restock")), "cash");
        JsonNode other = back(b, List.of(line(lineOf(b, 0), "1", "restock")), "cash");
        JsonNode second = back(a, List.of(line(lineOf(a, 0), "1", "restock")), "cash");
        assertThat(first.get("credit_note_no").asString()).isEqualTo("CNHQ-000001");
        assertThat(other.get("credit_note_no").asString()).isEqualTo("CNBR2-000001");
        assertThat(second.get("credit_note_no").asString()).isEqualTo("CNHQ-000002");
        // A refused return takes no number.
        assertThat(ret(a, List.of(line(lineOf(a, 0), "50", "restock")), "cash").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(voidReturn(uuid(second, "id"), "Wrong item").getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode third = back(a, List.of(line(lineOf(a, 0), "1", "restock")), "cash");
        assertThat(third.get("credit_note_no").asString()).isEqualTo("CNHQ-000003");
        assertThat(api.get("/returns/" + uuid(second, "id"), RETURNER)
                        .getBody()
                        .get("credit_note_no")
                        .asString())
                .isEqualTo("CNHQ-000002");
    }

    @Test
    void aReturnCannotExceedWhatWasSoldLessWhatCameBack() {
        UUID s = cashSale(hq, "10", null);
        UUID l = lineOf(s, 0);
        back(s, List.of(line(l, "6", "restock")), "cash");
        ResponseEntity<JsonNode> over = ret(s, List.of(line(l, "4.001", "restock")), "cash");
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(over)).isEqualTo("return_exceeds_sold");
        // Two lines of one request that name the same sale line count together.
        ResponseEntity<JsonNode> together = ret(s, List.of(line(l, "3", "restock"), line(l, "2", "restock")), "cash");
        assertThat(code(together)).isEqualTo("return_exceeds_sold");
        // The exact remainder is accepted, and then nothing more.
        JsonNode rest = back(s, List.of(line(l, "4", "restock")), "cash");
        assertThat(rest.get("total_minor").asLong()).isEqualTo(400);
        assertThat(code(ret(s, List.of(line(l, "0.001", "restock")), "cash"))).isEqualTo("return_exceeds_sold");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("1000");
        // Quantities with more than three decimals are not accepted; a line of another sale is not on this one.
        UUID other = cashSale(hq, "1", null);
        assertThat(ret(s, List.of(line(lineOf(other, 0), "1", "restock")), "cash")
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aVoidedSaleCannotBeReturnedAndASaleWithReturnsCannotBeVoided() {
        UUID s = cashSale(hq, "10", null);
        JsonNode r = back(s, List.of(line(lineOf(s, 0), "2", "restock")), "cash");
        ResponseEntity<JsonNode> voidSale = api.postKeyed(
                "/sales/" + s + "/void",
                Map.of("reason", "Test void"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(voidSale.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(voidSale)).isEqualTo("sale_has_returns");
        assertThat(voidReturn(uuid(r, "id"), "Taken back by mistake").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.postKeyed(
                                "/sales/" + s + "/void",
                                Map.of("reason", "Test void"),
                                ADMIN,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> again = ret(s, List.of(line(lineOf(s, 0), "1", "restock")), "cash");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(again)).isEqualTo("sale_voided");
    }

    @Test
    void damagedGoodsAreWrittenOffAndNeverEnterStock() {
        UUID s = cashSale(hq, "5", null);
        long shrinkageBefore = net("stock_shrinkage", hq);
        JsonNode r = back(s, List.of(line(lineOf(s, 0), "2", "write_off")), "cash");
        UUID id = uuid(r, "id");
        assertThat(net("stock_shrinkage", hq) - shrinkageBefore).isEqualTo(60);
        assertThat(stockAt(hq, product)).isEqualByComparingTo("995");
        assertThat(moved(id, "return")).isZero();
        Map<String, long[]> p = posted(id);
        assertThat(p.get("sales_returns")).containsExactly(200, 0);
        assertThat(p.get("cash_on_hand")).containsExactly(0, 200);
        assertThat(p.get("cost_of_goods_sold")).containsExactly(0, 60);
        assertThat(p.get("stock_shrinkage")).containsExactly(60, 0);
        assertThat(p).doesNotContainKey("inventory");
        // A written-off line names no branch.
        ResponseEntity<JsonNode> bad = ret(s, List.of(restockAt(line(lineOf(s, 0), "1", "write_off"), br2)), "cash");
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // Voided, nothing is left over.
        assertThat(voidReturn(id, "Counted again").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(net("stock_shrinkage", hq)).isEqualTo(shrinkageBefore);
        assertThat(stockAt(hq, product)).isEqualByComparingTo("995");
    }

    @Test
    void goodsRestockedInAnotherBranchGoThroughClearingAndLeaveItBalanced() {
        UUID s = cashSale(hq, "10", null);
        JsonNode r = back(s, List.of(restockAt(line(lineOf(s, 0), "3", "restock"), br2)), "cash");
        UUID id = uuid(r, "id");
        assertThat(stockAt(hq, product)).isEqualByComparingTo("990");
        assertThat(stockAt(br2, product)).isEqualByComparingTo("1003");
        assertThat(net("interbranch_clearing", hq)).isEqualTo(90);
        assertThat(net("interbranch_clearing", br2)).isEqualTo(-90);
        Map<String, long[]> p = posted(id);
        assertThat(p.get("inventory")).containsExactly(90, 0);
        assertThat(p.get("interbranch_clearing")).containsExactly(90, 90);
        assertThat(p.get("cost_of_goods_sold")).containsExactly(0, 90);
        // A caller who may take goods back only at the sale's branch cannot choose another branch.
        UUID s2 = cashSale(hq, "5", null);
        ResponseEntity<JsonNode> scoped = ret(
                s2,
                List.of(restockAt(line(lineOf(s2, 0), "1", "restock"), br2)),
                "cash",
                null,
                RETURNER,
                hq.toString(),
                UUID.randomUUID().toString());
        assertThat(scoped.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // Voided, both branches are as they were.
        assertThat(voidReturn(id, "Wrong shelf").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stockAt(br2, product)).isEqualByComparingTo("1000");
        assertThat(net("interbranch_clearing", hq)).isZero();
        assertThat(net("interbranch_clearing", br2)).isZero();
    }

    @Test
    void aCallerWithoutScopeOfTheSaleBranchDoesNotSeeTheSale() {
        UUID s = cashSale(br2, "5", null);
        ResponseEntity<JsonNode> r = ret(
                s,
                List.of(line(lineOf(s, 0), "1", "restock")),
                "cash",
                null,
                RETURNER,
                hq.toString(),
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.call(HttpMethod.GET, "/sales/" + s + "/returns", null, RETURNER, hq.toString(), Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- units and the price actually charged --------------------------------------------------

    @Test
    void packQuantitiesConvertExactlyAndThePiecesAddUpToTheLine() {
        capability("units", true);
        UUID unit = api.unit("carton");
        assertThat(api.post("/products/" + product + "/units", Map.of("unit_id", unit, "factor", "12"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // Two cartons at 1,000 each: 24 pieces, 2,000, cost 720.
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("product_id", product);
        pack.put("qty", "2");
        pack.put("unit_id", unit);
        pack.put("unit_price_minor", 1_000);
        ResponseEntity<JsonNode> made = api.postKeyed(
                "/sales",
                Map.of("branch_id", hq, "payment_method", "cash", "lines", List.of(pack)),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID s = RetailTestSupport.id(made);
        UUID l = lineOf(s, 0);
        assertThat(sale(s).get("lines").get(0).get("qty").asString()).isEqualTo("24.000");

        Map<String, Object> oneCarton = line(l, "1", "restock");
        oneCarton.put("unit_id", unit);
        JsonNode c1 = back(s, List.of(oneCarton), "cash");
        assertThat(c1.get("lines").get(0).get("qty").asString()).isEqualTo("12.000");
        assertThat(c1.get("lines").get(0).get("unit_qty").asString()).isEqualTo("1.000");
        assertThat(c1.get("total_minor").asLong()).isEqualTo(1_000);
        assertThat(c1.get("cost_minor").asLong()).isEqualTo(360);

        // Five pieces of the same line, in the base unit: 2,000 x 5 / 24 = 416.67, rounded half up once.
        JsonNode c2 = back(s, List.of(line(l, "5", "restock")), "cash");
        assertThat(c2.get("total_minor").asLong()).isEqualTo(417);
        assertThat(c2.get("cost_minor").asLong()).isEqualTo(150);

        // The rest takes the remainder, so the three credits add up to the line exactly.
        JsonNode c3 = back(s, List.of(line(l, "7", "restock")), "cash");
        assertThat(c3.get("total_minor").asLong()).isEqualTo(583);
        assertThat(c3.get("cost_minor").asLong()).isEqualTo(210);
        // The stock movements are valued from the same rounded share of the line's cost the journal took, per piece.
        assertThat(movementUnitCost(uuid(c2, "id"))).isEqualTo(30);
        assertThat(movementUnitCost(uuid(c3, "id"))).isEqualTo(30);
        assertThat(c1.get("total_minor").asLong()
                        + c2.get("total_minor").asLong()
                        + c3.get("total_minor").asLong())
                .isEqualTo(2_000);
        assertThat(c1.get("cost_minor").asLong()
                        + c2.get("cost_minor").asLong()
                        + c3.get("cost_minor").asLong())
                .isEqualTo(720);
        assertThat(stockAt(hq, product)).isEqualByComparingTo("1000");
        assertThat(net("cost_of_goods_sold", hq)).isEqualTo(0);

        // A quantity that does not convert exactly is refused, and a unit the product does not have is not accepted.
        UUID unit2 = api.unit("tray");
        assertThat(api.post("/products/" + product + "/units", Map.of("unit_id", unit2, "factor", "1.5"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        UUID s2 = cashSale(hq, "10", null);
        Map<String, Object> trays = line(lineOf(s2, 0), "1.001", "restock");
        trays.put("unit_id", unit2);
        ResponseEntity<JsonNode> inexact = ret(s2, List.of(trays), "cash");
        assertThat(inexact.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(inexact)).isEqualTo("quantity_not_representable");
        Map<String, Object> strange = line(lineOf(s2, 0), "1", "restock");
        strange.put("unit_id", UUID.randomUUID());
        assertThat(ret(s2, List.of(strange), "cash").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        Map<String, Object> goodTray = line(lineOf(s2, 0), "2", "restock");
        goodTray.put("unit_id", unit2);
        JsonNode ok = back(s2, List.of(goodTray), "cash");
        assertThat(ok.get("lines").get(0).get("qty").asString()).isEqualTo("3.000");
        assertThat(ok.get("total_minor").asLong()).isEqualTo(300);
    }

    /** A weighed item: the stock movement is valued per kilogram from the same rounded share of the line's cost the journal took. */
    @Test
    void aFractionalQuantityReturnIsValuedFromTheSameRoundedShareOfTheLineCostAsTheJournal() {
        UUID kg = api.product("RT-KG", 83, 120);
        api.stockUp(hq, kg, "100");
        // 2.5 kg at 120 is 300; its cost is 2.5 x 83 = 207.5, which the sale rounds to 208.
        UUID s = sold(hq, "cash", null, kg, "2.500", null, null);
        UUID l = lineOf(s, 0);
        assertThat(sale(s).get("lines").get(0).get("line_cost_minor").asLong()).isEqualTo(208);
        // 1.5 kg back: the credit is 180 and the cost 208 x 1.5 / 2.5 = 124.8, rounded once to 125.
        JsonNode first = back(s, List.of(line(l, "1.500", "restock")), "cash");
        UUID id = uuid(first, "id");
        assertThat(first.get("total_minor").asLong()).isEqualTo(180);
        assertThat(first.get("cost_minor").asLong()).isEqualTo(125);
        assertThat(posted(id).get("inventory")).containsExactly(125, 0);
        // The movement carries a whole price per kilogram (125 / 1.5 = 83.3, so 83): worth 124.5, within half a minor
        // unit of
        // the 125 the journal holds, and never further than that.
        long perKg = movementUnitCost(id);
        assertThat(perKg).isEqualTo(83);
        assertThat(Math.abs(perKg * 1500 - 125 * 1000)).isLessThanOrEqualTo(500);
        // The last kilogram takes the remainder, so the two returns add up to the line exactly.
        JsonNode rest = back(s, List.of(line(l, "1.000", "restock")), "cash");
        assertThat(first.get("cost_minor").asLong() + rest.get("cost_minor").asLong())
                .isEqualTo(208);
        assertThat(first.get("total_minor").asLong() + rest.get("total_minor").asLong())
                .isEqualTo(300);
        assertThat(movementUnitCost(uuid(rest, "id"))).isEqualTo(83);
    }

    @Test
    void aReturnCreditsThePriceActuallyChargedNotThePriceNow() {
        UUID s = sold(hq, "cash", null, product, "10", 70L, null);
        api.editPrices(product, Map.of("sell_minor", 150, "reason", "Test later"), ADMIN);
        JsonNode r = back(s, List.of(line(lineOf(s, 0), "3", "restock")), "cash");
        assertThat(r.get("total_minor").asLong()).isEqualTo(210);
        assertThat(r.get("lines").get(0).get("line_total_minor").asLong()).isEqualTo(210);
    }

    // ---- refunds ---------------------------------------------------------------------------------

    @Test
    void anAccountRefundTakesTheValueOffAWhatTheBuyerOwesAndNeverBelowZero() {
        UUID c = customer("Test Buyer 01");
        UUID s = creditSale(hq, c, "10", "2026-01-05");
        JsonNode r = back(s, List.of(line(lineOf(s, 0), "4", "restock")), "account");
        UUID id = uuid(r, "id");
        assertThat(r.get("account_credit_minor").asLong()).isEqualTo(400);
        assertThat(r.get("refund_minor").asLong()).isZero();
        assertThat(r.has("refund_method")).isFalse();
        assertThat(sale(s).get("paid_minor").asLong()).isEqualTo(400);
        assertThat(sale(s).get("balance_minor").asLong()).isEqualTo(600);
        assertThat(api.get("/customers/" + c + "/balance", ADMIN)
                        .getBody()
                        .get("balance_minor")
                        .asLong())
                .isEqualTo(600);
        assertThat(ledgerDebtors(c)).isEqualTo(600);
        Map<String, long[]> p = posted(id);
        assertThat(p.get("sales_returns")).containsExactly(400, 0);
        assertThat(p.get("trade_debtors")).containsExactly(0, 400);
        assertThat(p).doesNotContainKey("cash_on_hand");
        // The statement lists the credit note and closes at the ledger and at the balance.
        JsonNode st = api.get("/customers/" + c + "/statement?from=2026-01-01&to=" + TODAY, ADMIN)
                .getBody();
        assertThat(st.get("closing_minor").asLong()).isEqualTo(600);
        assertThat(st.get("balance_minor").asLong()).isEqualTo(600);
        JsonNode credit = null;
        for (JsonNode l : st.get("lines")) {
            if (l.get("kind").asString().equals("return")) {
                credit = l;
            }
        }
        assertThat(credit).isNotNull();
        assertThat(credit.get("reference").asString()).isEqualTo("CNHQ-000001");
        assertThat(credit.get("credit_minor").asLong()).isEqualTo(400);
        assertThat(credit.get("date").asString()).isEqualTo(TODAY);
    }

    @Test
    void aReturnWorthMoreThanTheOpenBalanceCreditsTheBalanceAndPaysOutTheRest() {
        UUID c = customer("Test Buyer 02");
        UUID s = creditSale(hq, c, "10", "2026-01-05");
        assertThat(api.postKeyed(
                                "/sales/" + s + "/payments",
                                Map.of("amount_minor", 800, "method", "cash", "paid_on", "2026-01-10"),
                                RETURNER,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // 500 comes back; only 200 is owed. The rest is paid out, and the caller must say how.
        List<Map<String, Object>> five = List.of(line(lineOf(s, 0), "5", "restock"));
        ResponseEntity<JsonNode> missing = ret(s, five, "account");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(missing)).isEqualTo("refund_remainder_method_required");
        JsonNode r = back(s, five, "account", "mobile_money");
        assertThat(r.get("account_credit_minor").asLong()).isEqualTo(200);
        assertThat(r.get("refund_minor").asLong()).isEqualTo(300);
        assertThat(r.get("refund_method").asString()).isEqualTo("mobile_money");
        assertThat(sale(s).get("balance_minor").asLong()).isZero();
        assertThat(ledgerDebtors(c)).isZero();
        Map<String, long[]> p = posted(uuid(r, "id"));
        assertThat(p.get("trade_debtors")).containsExactly(0, 200);
        assertThat(p.get("mobile_money")).containsExactly(0, 300);
        // With nothing left owing there is no balance to take it off.
        ResponseEntity<JsonNode> none = ret(s, List.of(line(lineOf(s, 0), "1", "restock")), "account");
        assertThat(code(none)).isEqualTo("account_refund_unavailable");
    }

    @Test
    void anAccountRefundIsRefusedWhereThereIsNoBalanceToCredit() {
        UUID cash = cashSale(hq, "5", null);
        ResponseEntity<JsonNode> r = ret(cash, List.of(line(lineOf(cash, 0), "1", "restock")), "account");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("account_refund_unavailable");
        // An imported credit sale (issue #96 is undecided) can be returned for stock and money, never against the
        // account.
        UUID c = customer("Test Buyer 03");
        UUID imported = UUID.randomUUID();
        TestDatabase.owner().sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, customer_id,
                            currency, total_minor, cost_total_minor, paid_minor, status, historical, created_by)
                        VALUES (?, ?, ?, 'RS9IMPORT1', DATE '2025-11-01', 'credit', ?, 'UGX', 300, 150, 100, 'completed', true,
                            gen_random_uuid())
                        """).params(imported, t.tenantId(), hq, c).update();
        TestDatabase.owner().sql("""
                        INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor,
                            unit_cost_minor, line_total_minor, line_cost_minor)
                        VALUES (gen_random_uuid(), ?, ?, 1, ?, 3, 100, 50, 300, 150)
                        """).params(t.tenantId(), imported, product).update();
        UUID il = lineOf(imported, 0);
        ResponseEntity<JsonNode> hist = ret(imported, List.of(line(il, "1", "restock")), "account");
        assertThat(code(hist)).isEqualTo("account_refund_historical");
        JsonNode money = back(imported, List.of(line(il, "1", "restock")), "cash");
        assertThat(money.get("refund_minor").asLong()).isEqualTo(100);
    }

    @Test
    void moneyGoesBackToACreditBuyerOnlyUpToTheMoneyThatCameIn() {
        UUID c = customer("Test Buyer 04");
        UUID s = creditSale(hq, c, "10", "2026-01-05");
        // Nothing was paid: a cash refund would hand out money that never came in.
        ResponseEntity<JsonNode> r = ret(s, List.of(line(lineOf(s, 0), "1", "restock")), "cash");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("refund_exceeds_paid");
        api.postKeyed(
                "/sales/" + s + "/payments",
                Map.of("amount_minor", 300, "method", "cash", "paid_on", "2026-01-10"),
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        // 300 was paid: 300 can go back, 301 cannot, and what went back is not available twice.
        assertThat(code(ret(s, List.of(line(lineOf(s, 0), "4", "restock")), "cash")))
                .isEqualTo("refund_exceeds_paid");
        back(s, List.of(line(lineOf(s, 0), "3", "restock")), "cash");
        assertThat(code(ret(s, List.of(line(lineOf(s, 0), "1", "restock")), "cash")))
                .isEqualTo("refund_exceeds_paid");
        // The debtors did not move for money refunded to a buyer who had paid, and the statement lists no line for it.
        assertThat(ledgerDebtors(c)).isEqualTo(700);
        JsonNode st = api.get("/customers/" + c + "/statement?from=2026-01-01&to=" + TODAY, ADMIN)
                .getBody();
        st.get("lines").forEach(l -> assertThat(l.get("kind").asString()).isNotIn("return", "return_void"));
        assertThat(api.get("/customers/" + c + "/balance", ADMIN)
                        .getBody()
                        .get("balance_minor")
                        .asLong())
                .isEqualTo(700);
    }

    @Test
    void voidingAnAccountReturnPutsTheBalanceBackAndTheGuardOnPaidAmountHolds() throws Exception {
        UUID c = customer("Test Buyer 05");
        UUID s = creditSale(hq, c, "10", "2026-01-05");
        UUID other = creditSale(hq, c, "10", "2026-01-06");
        JsonNode r = back(s, List.of(line(lineOf(s, 0), "4", "restock")), "account");
        UUID id = uuid(r, "id");
        JsonNode otherReturn = back(other, List.of(line(lineOf(other, 0), "1", "restock")), "account");

        // The application role cannot lower paid_minor on its own, with the return's id or without it, with the id of
        // a return that is not voided, or with the id of a return of another sale.
        assertThatThrownBy(() -> updatePaid(s, 0, null)).hasStackTraceContaining("only the paid amount");
        assertThatThrownBy(() -> updatePaid(s, 0, id.toString())).hasStackTraceContaining("only the paid amount");
        assertThatThrownBy(() -> updatePaid(s, 300, id.toString())).hasStackTraceContaining("only the paid amount");
        assertThatThrownBy(() -> updatePaid(s, 0, uuid(otherReturn, "id").toString()))
                .hasStackTraceContaining("only the paid amount");
        assertThatThrownBy(() -> updatePaidAs(s, 0, "bms.reverse_account_payment", id.toString()))
                .hasStackTraceContaining("only the paid amount");
        assertThat(sale(s).get("paid_minor").asLong()).isEqualTo(400);

        ResponseEntity<JsonNode> voided = voidReturn(id, "Customer changed their mind");
        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(voided.getBody().get("executed").asBoolean()).isTrue();
        assertThat(voided.getBody().get("sale_return").get("status").asString()).isEqualTo("voided");
        assertThat(voided.getBody().get("sale_return").get("voided_on").asString())
                .isEqualTo(TODAY);
        assertThat(sale(s).get("paid_minor").asLong()).isZero();
        assertThat(sale(s).get("balance_minor").asLong()).isEqualTo(1_000);
        assertThat(ledgerDebtors(c)).isEqualTo(1_900);
        assertThat(stockAt(hq, product)).isEqualByComparingTo("981");
        // Once: the second void is refused, and once a new return has settled the same amount the id of the voided one
        // opens
        // nothing, because the paid amount may not fall below what the returns that stand settled.
        assertThat(voidReturn(id, "Again for luck").getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        back(s, List.of(line(lineOf(s, 0), "4", "restock")), "account");
        assertThat(sale(s).get("paid_minor").asLong()).isEqualTo(400);
        assertThatThrownBy(() -> updatePaid(s, 0, id.toString())).hasStackTraceContaining("only the paid amount");
        assertThat(sale(s).get("paid_minor").asLong()).isEqualTo(400);
        assertThat(stockAt(hq, product)).isEqualByComparingTo("985");
        // The statement shows the credit and the void; the closing agrees with the ledger.
        JsonNode st = api.get("/customers/" + c + "/statement?from=2026-01-01&to=" + TODAY, ADMIN)
                .getBody();
        assertThat(st.get("closing_minor").asLong()).isEqualTo(1_500);
        List<String> kinds = new ArrayList<>();
        st.get("lines").forEach(l -> kinds.add(l.get("kind").asString()));
        assertThat(kinds).contains("return", "return_void");
        // The statement closes at the ledger after the new return too.
        assertThat(ledgerDebtors(c)).isEqualTo(1_500);
    }

    /** One UPDATE of a sale's paid amount as the application role, with the void setting raised for {@code ret} or not. */
    void updatePaid(UUID sale, long paid, String ret) throws Exception {
        updatePaidAs(sale, paid, "bms.void_sale_return", ret);
    }

    void updatePaidAs(UUID sale, long paid, String setting, String value) throws Exception {
        try (Connection conn = TestDatabase.appDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT set_config('app.tenant_id', '" + t.tenantId() + "', true)");
                if (value != null) {
                    st.execute("SELECT set_config('" + setting + "', '" + value + "', true)");
                }
                st.executeUpdate("UPDATE retail_sales SET paid_minor = " + paid + " WHERE id = '" + sale + "'");
            }
            conn.commit();
        }
    }

    @Test
    void aReversedPaymentOnAccountCannotBeUsedToLowerWhatAReturnSettled() {
        UUID c = customer("Test Buyer 06");
        UUID s = creditSale(hq, c, "10", "2026-01-05");
        back(s, List.of(line(lineOf(s, 0), "4", "restock")), "account");
        // 600 is left owing; a payment on account pays it, then reversing it gives the 600 back and no more.
        ResponseEntity<JsonNode> paid = api.postKeyed(
                "/customers/" + c + "/payments",
                Map.of("branch_id", hq, "amount_minor", 600, "method", "cash", "paid_on", "2026-01-10"),
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(sale(s).get("paid_minor").asLong()).isEqualTo(1_000);
        ResponseEntity<JsonNode> back = api.postKeyed(
                "/customers/" + c + "/payments/" + paid.getBody().get("id").asString() + "/reverse",
                Map.of("reason", "Wrong buyer"),
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(back.getStatusCode()).as("%s", back.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(sale(s).get("paid_minor").asLong()).isEqualTo(400);
        assertThat(ledgerDebtors(c)).isEqualTo(600);
    }

    @Test
    void aPaymentOnAccountWhoseSaleHasMoneyRefundedCannotBeReversed() {
        UUID c = customer("Test Buyer 07");
        UUID s = creditSale(hq, c, "10", "2026-01-05");
        ResponseEntity<JsonNode> paid = api.postKeyed(
                "/customers/" + c + "/payments",
                Map.of("branch_id", hq, "amount_minor", 1_000, "method", "cash", "paid_on", "2026-01-10"),
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode r = back(s, List.of(line(lineOf(s, 0), "2", "restock")), "cash");
        ResponseEntity<JsonNode> reversal = api.postKeyed(
                "/customers/" + c + "/payments/" + paid.getBody().get("id").asString() + "/reverse",
                Map.of("reason", "Wrong buyer"),
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(reversal.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(reversal)).isEqualTo("payment_not_reversible");
        // Once the return is voided the payment can be reversed again.
        assertThat(voidReturn(uuid(r, "id"), "Refunded in error").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.postKeyed(
                                "/customers/" + c + "/payments/"
                                        + paid.getBody().get("id").asString() + "/reverse",
                                Map.of("reason", "Wrong buyer"),
                                RETURNER,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---- the void ----------------------------------------------------------------------------------

    @Test
    void aVoidReversesEveryEntryAndMovementAndRefusesWhenTheGoodsHaveBeenSoldSince() {
        UUID p2 = api.product("RT-2", 30, 100);
        api.stockUp(hq, p2, "3");
        UUID s = sold(hq, "cash", null, p2, "3", null, null);
        JsonNode r = back(s, List.of(restockAt(line(lineOf(s, 0), "3", "restock"), br2)), "cash");
        UUID id = uuid(r, "id");
        assertThat(stockAt(br2, p2)).isEqualByComparingTo("3");
        // The goods are sold again from the branch they were put back in; the return cannot be undone.
        UUID again = sold(br2, "cash", null, p2, "2", null, null);
        ResponseEntity<JsonNode> refused = voidReturn(id, "Mistake");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("return_stock_moved");
        assertThat(api.get("/returns/" + id, RETURNER).getBody().get("status").asString())
                .isEqualTo("completed");
        assertThat(api.postKeyed(
                                "/sales/" + again + "/void",
                                Map.of("reason", "Test void"),
                                ADMIN,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(voidReturn(id, "ab").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(voidReturn(id, "Mistake").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stockAt(br2, p2)).isEqualByComparingTo("0");
        // Every entry of the return is reversed: the accounts it touched net to nothing again.
        assertThat(net("sales_returns", hq)).isZero();
        assertThat(net("interbranch_clearing", hq)).isZero();
        assertThat(net("interbranch_clearing", br2)).isZero();
        JsonNode done = api.get("/returns/" + id, RETURNER).getBody();
        assertThat(done.get("status").asString()).isEqualTo("voided");
        assertThat(done.get("void_reason").asString()).isEqualTo("Mistake");
    }

    @Test
    void theCreditNoteListFiltersByStatusSaleAndBranchScopeAndPagesNewestFirst() {
        UUID a = cashSale(hq, "10", null);
        UUID b = cashSale(br2, "10", null);
        JsonNode first = back(a, List.of(line(lineOf(a, 0), "1", "restock")), "cash");
        JsonNode second = back(a, List.of(line(lineOf(a, 0), "2", "restock")), "cash");
        back(b, List.of(line(lineOf(b, 0), "3", "restock")), "cash");
        assertThat(voidReturn(uuid(first, "id"), "Entered twice").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode all = api.get("/returns", RETURNER).getBody().get("items");
        assertThat(all).hasSize(3);
        assertThat(all.get(0).get("credit_note_no").asString()).isEqualTo("CNBR2-000001");
        assertThat(api.get("/returns?status=voided", RETURNER).getBody().get("items"))
                .hasSize(1);
        assertThat(api.get("/returns?status=completed&sale_id=" + a, RETURNER)
                        .getBody()
                        .get("items"))
                .hasSize(1);
        assertThat(api.get("/returns?status=nonsense", RETURNER).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // A caller scoped to head office sees head office's credit notes only, and cannot open the other branch's.
        ResponseEntity<JsonNode> scoped = api.call(HttpMethod.GET, "/returns", null, RETURNER, hq.toString(), Map.of());
        assertThat(scoped.getBody().get("items")).hasSize(2);
        assertThat(api.call(
                                HttpMethod.GET,
                                "/returns/"
                                        + uuid(
                                                api.get("/returns?q=CNBR2", RETURNER)
                                                        .getBody()
                                                        .get("items")
                                                        .get(0),
                                                "id"),
                                null,
                                RETURNER,
                                hq.toString(),
                                Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // One page at a time, newest first, with a cursor that leads to the rest.
        JsonNode page = api.get("/returns?limit=2", RETURNER).getBody();
        assertThat(page.get("items")).hasSize(2);
        JsonNode rest = api.get(
                        "/returns?limit=2&cursor=" + page.get("next_cursor").asString(), RETURNER)
                .getBody();
        assertThat(rest.get("items")).hasSize(1);
        assertThat(rest.get("items").get(0).get("id").asString())
                .isEqualTo(first.get("id").asString());
        assertThat(second.get("credit_note_no").asString()).isEqualTo("CNHQ-000002");
    }

    @Test
    void aReturnWritesAnAuditRowAndAVoidAnother() {
        UUID s = cashSale(hq, "5", null);
        JsonNode r = back(s, List.of(line(lineOf(s, 0), "1", "restock")), "cash");
        assertThat(voidReturn(uuid(r, "id"), "Mistake").getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> actions = TestDatabase.owner()
                .sql("SELECT action FROM audit_log WHERE tenant_id = ? AND entity_id = ? ORDER BY created_at, id")
                .params(t.tenantId(), uuid(r, "id"))
                .query(String.class)
                .list();
        assertThat(actions).containsExactly("retail.sale_return.created", "retail.sale_return.voided");
    }

    @Test
    void twoReturnsAtOnceCannotTakeBackMoreThanWasSold() throws Exception {
        UUID s = cashSale(hq, "10", null);
        UUID l = lineOf(s, 0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<JsonNode>>> both = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                both.add(pool.submit(() -> {
                    go.await();
                    return ret(s, List.of(line(l, "6", "restock")), "cash");
                }));
            }
            go.countDown();
            List<HttpStatus> codes = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> f : both) {
                codes.add(HttpStatus.valueOf(
                        f.get(60, TimeUnit.SECONDS).getStatusCode().value()));
            }
            assertThat(codes).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.UNPROCESSABLE_CONTENT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(stockAt(hq, product)).isEqualByComparingTo("996");
        assertThat(api.get("/sales/" + s + "/returns", RETURNER).getBody()).hasSize(1);
    }

    // ---- idempotency -------------------------------------------------------------------------------

    @Test
    void theSameKeyReplaysTheSameCreditNoteAndAnotherBodyIsRefused() {
        UUID s = cashSale(hq, "10", null);
        UUID l = lineOf(s, 0);
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = ret(s, List.of(line(l, "2", "restock")), "cash", null, RETURNER, "*", key);
        ResponseEntity<JsonNode> replay = ret(s, List.of(line(l, "2", "restock")), "cash", null, RETURNER, "*", key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("sale_return").get("id").asString())
                .isEqualTo(first.getBody().get("sale_return").get("id").asString());
        assertThat(stockAt(hq, product)).isEqualByComparingTo("992");
        assertThat(api.get("/sales/" + s + "/returns", RETURNER).getBody()).hasSize(1);
        ResponseEntity<JsonNode> other = ret(s, List.of(line(l, "3", "restock")), "cash", null, RETURNER, "*", key);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(other)).isEqualTo("idempotency_key_reused");
        // A write without a key is refused.
        ResponseEntity<JsonNode> keyless = api.call(
                HttpMethod.POST,
                "/sales/" + s + "/returns",
                Map.of("lines", List.of(line(l, "1", "restock")), "refund_method", "cash"),
                RETURNER,
                "*",
                Map.of());
        assertThat(code(keyless)).isEqualTo("idempotency_key_missing");
    }

    // ---- the cash book and the daily profit ---------------------------------------------------------

    @Test
    void aCashRefundLeavesTheTillOnTheDayItIsPaidNotTheDayOfTheSaleAndTheCashSummaryAgreesWithTheLedger() {
        UUID s = cashSale(hq, "10", "2026-01-05");
        JsonNode r = back(s, List.of(line(lineOf(s, 0), "4", "restock")), "cash");
        JsonNode days = cashDays(hq, "2026-01-05", TODAY);
        // The sale day keeps its takings untouched; the refund is the day of the return, which at 22:30 UTC is already
        // the
        // next business day in Kampala.
        assertThat(dayRow(days, "2026-01-05").get("cash_takings_minor").asLong())
                .isEqualTo(1_000);
        assertThat(dayRow(days, "2026-01-05").get("cash_sale_voids_minor").asLong())
                .isZero();
        assertThat(dayRow(days, TODAY).get("cash_sale_voids_minor").asLong()).isEqualTo(400);
        assertThat(dayRow(days, TODAY).get("cash_takings_minor").asLong()).isZero();
        for (JsonNode row : days.get("items")) {
            assertThat(row.get("other_movements_minor").asLong())
                    .as(row.get("business_date").asString())
                    .isZero();
        }
        // A mobile money refund is not cash at all.
        UUID s2 = cashSale(hq, "5", "2026-01-06");
        back(s2, List.of(line(lineOf(s2, 0), "2", "restock")), "mobile_money");
        assertThat(dayRow(cashDays(hq, "2026-01-05", TODAY), TODAY)
                        .get("cash_sale_voids_minor")
                        .asLong())
                .isEqualTo(400);
        // Voided, the cash comes back on the day of the void, and the day it went out is not rewritten.
        assertThat(voidReturn(uuid(r, "id"), "Refund in error").getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode after = cashDays(hq, "2026-01-05", TODAY);
        assertThat(dayRow(after, TODAY).get("cash_sale_voids_minor").asLong()).isEqualTo(400);
        assertThat(dayRow(after, TODAY).get("cash_takings_minor").asLong()).isEqualTo(400);
        for (JsonNode row : after.get("items")) {
            assertThat(row.get("other_movements_minor").asLong()).isZero();
        }
    }

    @Test
    void theDailyProfitFallsByTheMarginOfRestockedGoodsAndByTheWholeCreditOfWrittenOffGoodsOnTheReturnDay() {
        UUID s = cashSale(hq, "10", "2026-01-05");
        UUID l = lineOf(s, 0);
        back(s, List.of(line(l, "4", "restock")), "cash");
        // Sale day: untouched. Return day: sales 400 and cost 120 come off, so the profit is minus the margin of 280.
        JsonNode saleDay = profitDay("2026-01-05");
        assertThat(saleDay.get("sales_minor").asLong()).isEqualTo(1_000);
        assertThat(saleDay.get("profit_minor").asLong()).isEqualTo(700);
        JsonNode returnDay = profitDay(TODAY);
        assertThat(returnDay.get("sales_minor").asLong()).isEqualTo(-400);
        assertThat(returnDay.get("cost_of_sales_minor").asLong()).isEqualTo(-120);
        assertThat(figure(returnDay)).isEqualTo(-280);
        back(s, List.of(line(l, "2", "write_off")), "cash");
        JsonNode both = profitDay(TODAY);
        assertThat(both.get("sales_minor").asLong()).isEqualTo(-600);
        assertThat(both.get("usage_cost_minor").asLong()).isEqualTo(60);
        // Restocked: -(400 - 120) = -280. Written off: -(200 - 60) - 60 = -200. Together -480.
        assertThat(figure(both)).isEqualTo(-480);
        // The ledger agrees: revenue 1,000 less returns 600; cost of sales 300 less 180 reversed; 60 of it shrinkage.
        assertThat(-net("sales_revenue", hq)).isEqualTo(1_000);
        assertThat(net("sales_returns", hq)).isEqualTo(600);
        assertThat(net("cost_of_goods_sold", hq)).isEqualTo(120);
    }

    /** What the sales, the cost of sales and the usage of a profit row come to: the part of the profit returns can move. */
    static long figure(JsonNode row) {
        return row.get("sales_minor").asLong()
                - row.get("cost_of_sales_minor").asLong()
                - row.get("usage_cost_minor").asLong();
    }

    @Test
    void theSalesAnalysisNetsReturnsByTheDayOfTheReturnAndKeepsTheSaleCount() {
        UUID s = cashSale(hq, "10", "2026-01-05");
        UUID l = lineOf(s, 0);
        back(s, List.of(line(l, "4", "restock")), "cash");
        back(s, List.of(line(l, "2", "write_off")), "cash");
        JsonNode a = api.get("/reports/sales-analysis?from=2026-01-01&to=" + TODAY, RETURNER)
                .getBody();
        // 1,000 sold less 600 credited. Gross profit: 700 on the sale day; restocked goods give back their margin of
        // 280 and
        // goods written off give back their whole credit of 200, because their cost stands.
        assertThat(a.get("totals").get("sales_minor").asLong()).isEqualTo(400);
        assertThat(a.get("totals").get("gross_profit_minor").asLong()).isEqualTo(220);
        assertThat(a.get("totals").get("sale_count").asLong()).isEqualTo(1);
        JsonNode series = a.get("series");
        assertThat(series).hasSize(2);
        assertThat(series.get(0).get("sales_minor").asLong()).isEqualTo(1_000);
        assertThat(series.get(1).get("sales_minor").asLong()).isEqualTo(-600);
        assertThat(series.get(1).get("gross_profit_minor").asLong()).isEqualTo(-480);
        assertThat(a.get("by_product").get(0).get("qty").asString()).isEqualTo("4.000");
        assertThat(a.get("by_seller").get(0).get("sales_minor").asLong()).isEqualTo(400);
        // A return of a sale from before the window still shows in the window it was made in.
        JsonNode today = api.get("/reports/sales-analysis?from=" + TODAY + "&to=" + TODAY, RETURNER)
                .getBody();
        assertThat(today.get("totals").get("sales_minor").asLong()).isEqualTo(-600);
        assertThat(today.get("totals").get("sale_count").asLong()).isZero();
    }

    // ---- maker-checker above a threshold --------------------------------------------------------

    void threshold(long amount) {
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = settings || CAST(? AS jsonb) WHERE tenant_id = ?")
                .params("{\"approval_thresholds_minor\": {\"retail_sale_return\": " + amount + "}}", t.tenantId())
                .update();
    }

    ResponseEntity<JsonNode> decide(String approval, RetailTestSupport who, String perms) {
        return http.exchange(
                "/api/v1/approvals/" + approval + "/approve",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        Map.of("note", "Checked the goods"), who.headers(perms, "*", Map.of())),
                JsonNode.class);
    }

    @Test
    void aReturnAtOrAboveTheThresholdWaitsForASecondAdministrator() {
        UUID s = cashSale(hq, "20", null);
        UUID l = lineOf(s, 0);
        threshold(500);
        // Below the threshold: recorded at once.
        JsonNode small = back(s, List.of(line(l, "4", "restock")), "cash");
        assertThat(small.get("total_minor").asLong()).isEqualTo(400);
        // At the threshold: 202, nothing moves.
        ResponseEntity<JsonNode> big = ret(s, List.of(line(l, "5", "restock")), "cash");
        assertThat(big.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(big.getBody().get("executed").asBoolean()).isFalse();
        String approval = big.getBody().get("approval_id").asString();
        assertThat(stockAt(hq, product)).isEqualByComparingTo("984");
        assertThat(api.get("/sales/" + s + "/returns", RETURNER).getBody()).hasSize(1);
        // The maker cannot decide it; another administrator can, and the return is made as the maker's, approved by the
        // checker.
        String checkerPerms = RETURNER + ",core.approvals.read";
        assertThat(decide(approval, api, checkerPerms).getStatusCode().is2xxSuccessful())
                .isFalse();
        RetailTestSupport checker = new RetailTestSupport(http, t);
        ResponseEntity<JsonNode> decided = decide(approval, checker, checkerPerms);
        assertThat(decided.getStatusCode()).as("%s", decided.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode list = api.get("/sales/" + s + "/returns", RETURNER).getBody();
        assertThat(list).hasSize(2);
        JsonNode made = list.get(1);
        assertThat(made.get("total_minor").asLong()).isEqualTo(500);
        assertThat(made.get("created_by").asString()).isEqualTo(api.userId().toString());
        assertThat(made.get("approved_by").asString())
                .isEqualTo(checker.userId().toString());
        assertThat(made.get("approval_id").asString()).isEqualTo(approval);
        assertThat(stockAt(hq, product)).isEqualByComparingTo("989");
    }

    @Test
    void aWaitingReturnGoesStaleWhenAnotherReturnMovesTheSale() {
        UUID s = cashSale(hq, "20", null);
        UUID l = lineOf(s, 0);
        threshold(500);
        ResponseEntity<JsonNode> big = ret(s, List.of(line(l, "6", "restock")), "cash");
        assertThat(big.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        back(s, List.of(line(l, "1", "restock")), "cash");
        RetailTestSupport checker = new RetailTestSupport(http, t);
        ResponseEntity<JsonNode> decided =
                decide(big.getBody().get("approval_id").asString(), checker, RETURNER + ",core.approvals.read");
        assertThat(decided.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(api.get("/sales/" + s + "/returns", RETURNER).getBody()).hasSize(1);
    }

    // ---- the sale path with returns off in the settings group ----------------------------------

    // ---- review of #251 ---------------------------------------------------------------------------

    /** The reversal of a payment on account waits on the sale's lock; a refund that commits meanwhile must be seen by it. */
    @Test
    void aPaymentReversalWaitingOnTheSaleSeesAMoneyRefundThatCommittedMeanwhile() throws Exception {
        UUID c = customer("Test Buyer 08");
        UUID s = creditSale(hq, c, "10", "2026-01-05");
        ResponseEntity<JsonNode> paid = api.postKeyed(
                "/customers/" + c + "/payments",
                Map.of("branch_id", hq, "amount_minor", 1_000, "method", "cash", "paid_on", "2026-01-10"),
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(paid.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection conn = TestDatabase.ownerDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                // Hold the sale, as a return that is refunding money would.
                st.execute("SELECT 1 FROM retail_sales WHERE id = '" + s + "' FOR UPDATE");
                // The transaction id the row lock belongs to: a backend waiting for the sale's row waits for exactly
                // this one.
                String holder;
                try (java.sql.ResultSet rs = st.executeQuery("SELECT txid_current() % 4294967296")) {
                    rs.next();
                    holder = rs.getString(1);
                }
                Future<ResponseEntity<JsonNode>> reversal = pool.submit(() -> api.postKeyed(
                        "/customers/" + c + "/payments/"
                                + paid.getBody().get("id").asString() + "/reverse",
                        Map.of("reason", "Wrong buyer"),
                        RETURNER,
                        "*",
                        UUID.randomUUID().toString()));
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                long waiting = 0;
                while (System.nanoTime() < until && waiting == 0) {
                    waiting = TestDatabase.owner()
                            .sql("SELECT count(*) FROM pg_locks WHERE NOT granted AND locktype = 'transactionid'"
                                    + " AND transactionid::text = ?")
                            .param(holder)
                            .query(Long.class)
                            .single();
                    if (waiting == 0) {
                        Thread.sleep(50);
                    }
                }
                assertThat(waiting)
                        .as("the reversal is waiting for the sale's row")
                        .isEqualTo(1);
                // The refund commits while the reversal waits: a completed return that paid 100 out.
                st.execute("""
                        INSERT INTO retail_sale_returns (id, tenant_id, credit_note_no, branch_seq, sale_id, branch_id,
                            refund_branch_id, returned_on, currency, total_minor, cost_minor, refund_minor, refund_method,
                            refund_choice, created_by)
                        VALUES (gen_random_uuid(), '%s', 'CNHQ-009999', 9999, '%s', '%s', '%s', DATE '2026-10-08', 'UGX', 100, 0,
                            100, 'cash', 'cash', gen_random_uuid())
                        """.formatted(t.tenantId(), s, hq, hq));
                conn.commit();
                ResponseEntity<JsonNode> r = reversal.get(60, TimeUnit.SECONDS);
                assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(code(r)).isEqualTo("payment_not_reversible");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(sale(s).get("paid_minor").asLong()).isEqualTo(1_000);
    }

    @Test
    void moneyCanLeaveAnotherBranchsTillAndBothTillsReconcileWithTheLedger() {
        UUID s = cashSale(hq, "10", "2026-01-05");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(line(lineOf(s, 0), "4", "restock")));
        body.put("refund_method", "cash");
        body.put("refund_branch_id", br2);
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + s + "/returns",
                body,
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode note = r.getBody().get("sale_return");
        UUID id = uuid(note, "id");
        assertThat(note.get("refund_branch_id").asString()).isEqualTo(br2.toString());
        // The credit note belongs to the sale's branch; the money, in clearing between the two, left the other till.
        assertThat(note.get("credit_note_no").asString()).isEqualTo("CNHQ-000001");
        Map<String, long[]> p = posted(id);
        assertThat(p.get("cash_on_hand")).containsExactly(0, 400);
        assertThat(p.get("interbranch_clearing")).containsExactly(400, 400);
        JsonNode br2Days = cashDays(br2, "2026-01-05", TODAY);
        assertThat(dayRow(br2Days, TODAY).get("cash_sale_voids_minor").asLong()).isEqualTo(400);
        JsonNode hqDays = cashDays(hq, "2026-01-05", TODAY);
        for (JsonNode row : hqDays.get("items")) {
            if (row.get("business_date").asString().equals(TODAY)) {
                assertThat(row.get("cash_sale_voids_minor").asLong()).isZero();
            }
        }
        for (JsonNode days : List.of(br2Days, hqDays)) {
            for (JsonNode row : days.get("items")) {
                assertThat(row.get("other_movements_minor").asLong())
                        .as(row.get("business_date").asString())
                        .isZero();
            }
        }
        // A till outside the caller's scope is not offered.
        Map<String, Object> other = new LinkedHashMap<>(body);
        other.put("lines", List.of(line(lineOf(s, 0), "1", "restock")));
        ResponseEntity<JsonNode> out = api.postKeyed(
                "/sales/" + s + "/returns",
                other,
                RETURNER,
                hq.toString(),
                UUID.randomUUID().toString());
        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // The void gives the money back to the same till, on the day of the void, and clearing is empty again.
        assertThat(voidReturn(id, "Paid at the wrong till").getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode after = cashDays(br2, "2026-01-05", TODAY);
        assertThat(dayRow(after, TODAY).get("cash_takings_minor").asLong()).isEqualTo(400);
        for (JsonNode row : after.get("items")) {
            assertThat(row.get("other_movements_minor").asLong()).isZero();
        }
        assertThat(net("interbranch_clearing", hq)).isZero();
        assertThat(net("interbranch_clearing", br2)).isZero();
    }

    @Test
    void splittingAReturnIntoPartsDoesNotGetRoundTheThreshold() {
        UUID s = cashSale(hq, "20", null);
        UUID l = lineOf(s, 0);
        threshold(500);
        back(s, List.of(line(l, "3", "restock")), "cash");
        // 300 and 300 come to 600 on this sale: the second part waits, though it is small by itself.
        ResponseEntity<JsonNode> second = ret(s, List.of(line(l, "3", "restock")), "cash");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        // A void gives room back: with the first one voided, 300 is a return like any other.
        UUID first = UUID.fromString(api.get("/sales/" + s + "/returns", RETURNER)
                .getBody()
                .get(0)
                .get("id")
                .asString());
        assertThat(voidReturn(first, "Entered twice").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void voidingAReturnThatNeededApprovalNeedsApprovalAndASmallOneVoidsAtOnce() {
        UUID s = cashSale(hq, "20", null);
        UUID l = lineOf(s, 0);
        threshold(500);
        JsonNode small = back(s, List.of(line(l, "2", "restock")), "cash");
        ResponseEntity<JsonNode> big = ret(s, List.of(line(l, "5", "restock")), "cash");
        assertThat(big.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        RetailTestSupport checker = new RetailTestSupport(http, t);
        String perms = RETURNER + ",core.approvals.read";
        assertThat(decide(big.getBody().get("approval_id").asString(), checker, perms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        UUID approved = null;
        for (JsonNode r : api.get("/sales/" + s + "/returns", RETURNER).getBody()) {
            if (r.get("total_minor").asLong() == 500) {
                approved = uuid(r, "id");
            }
        }
        assertThat(approved).isNotNull();
        BigDecimal onShelf = stockAt(hq, product);
        // Its void is not done at once: a request waits for a second administrator.
        ResponseEntity<JsonNode> ask = voidReturn(approved, "Customer changed their mind");
        assertThat(ask.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(ask.getBody().get("executed").asBoolean()).isFalse();
        assertThat(api.get("/returns/" + approved, RETURNER)
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("completed");
        assertThat(stockAt(hq, product)).isEqualByComparingTo(onShelf);
        assertThat(decide(ask.getBody().get("approval_id").asString(), api, perms)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isFalse();
        assertThat(decide(ask.getBody().get("approval_id").asString(), checker, perms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.get("/returns/" + approved, RETURNER)
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("voided");
        assertThat(stockAt(hq, product)).isEqualByComparingTo(onShelf.subtract(new BigDecimal("5")));
        // The small one, made without a second person, is voided without one.
        assertThat(voidReturn(uuid(small, "id"), "Entered twice").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // A return worth the threshold by itself needs approval to void even if it was made before the threshold was
        // set.
        threshold(0);
        JsonNode early = back(s, List.of(line(l, "6", "restock")), "cash");
        threshold(500);
        assertThat(voidReturn(uuid(early, "id"), "Entered twice").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void theCapabilitiesAnswerSaysWhetherAReturnApprovalThresholdIsSet() {
        assertThat(api.get("/settings/capabilities", SETTINGS).getBody().has("returns_approval_threshold_minor"))
                .isFalse();
        threshold(500);
        assertThat(api.get("/settings/capabilities", SETTINGS)
                        .getBody()
                        .get("returns_approval_threshold_minor")
                        .asLong())
                .isEqualTo(500);
    }

    @Test
    void anItemWhoseOnlyActivityInTheWindowIsAReturnDidNotSell() {
        UUID p2 = api.product("RT-2", 30, 100);
        api.stockUp(hq, p2, "5");
        UUID s = sold(hq, "cash", null, p2, "2", null, "2026-01-05");
        back(s, List.of(line(lineOf(s, 0), "1", "restock")), "cash");
        JsonNode a = api.get("/reports/sales-analysis?from=" + TODAY + "&to=" + TODAY + "&slow_days=7", RETURNER)
                .getBody();
        // Its sales are not in the window, and its return is: the sales show the return, the lists do not call it sold.
        assertThat(a.get("totals").get("sales_minor").asLong()).isEqualTo(-100);
        List<String> slow = new ArrayList<>();
        a.get("slow_movers").forEach(r -> slow.add(r.get("code").asString()));
        List<String> none = new ArrayList<>();
        a.get("no_sales").forEach(r -> none.add(r.get("code").asString()));
        assertThat(slow).contains("RT-2");
        assertThat(none).contains("RT-2");
    }

    @Test
    void aPackThatIsNoLongerSoldCannotBeReturnedIn() {
        capability("units", true);
        UUID tray = api.unit("tray");
        assertThat(api.post("/products/" + product + "/units", Map.of("unit_id", tray, "factor", "2"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        UUID s = cashSale(hq, "10", null);
        TestDatabase.owner()
                .sql("UPDATE retail_product_units SET active = false WHERE product_id = ? AND unit_id = ?")
                .params(product, tray)
                .update();
        Map<String, Object> inTrays = line(lineOf(s, 0), "1", "restock");
        inTrays.put("unit_id", tray);
        ResponseEntity<JsonNode> r = ret(s, List.of(inTrays), "cash");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("unknown_pack_unit");
    }

    @Test
    void aReturnCannotBeInsertedAlreadyVoided() throws Exception {
        UUID s = cashSale(hq, "5", null);
        try (Connection conn = TestDatabase.appDataSource().getConnection()) {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.execute("SELECT set_config('app.tenant_id', '" + t.tenantId() + "', true)");
                assertThatThrownBy(() -> st.execute("""
                                INSERT INTO retail_sale_returns (id, tenant_id, credit_note_no, branch_seq, sale_id, branch_id,
                                    refund_branch_id, returned_on, currency, total_minor, cost_minor, refund_minor, refund_method,
                                    refund_choice, status, voided_at, voided_on, voided_by, void_reason, created_by)
                                VALUES (gen_random_uuid(), '%s', 'CNHQ-009998', 9998, '%s', '%s', '%s', DATE '2026-10-08', 'UGX',
                                    100, 0, 100, 'cash', 'cash', 'voided', now(), DATE '2026-10-08', gen_random_uuid(), 'Made up',
                                    gen_random_uuid())
                                """.formatted(t.tenantId(), s, hq, hq)))
                        .hasMessageContaining("recorded completed");
            }
            conn.rollback();
        }
    }

    @Test
    void reportsKeepTheReturnsOfATenantThatSwitchedTheCapabilityOffAgain() {
        UUID s = cashSale(hq, "10", "2026-01-05");
        back(s, List.of(line(lineOf(s, 0), "4", "restock")), "cash");
        capability("returns", false);
        // The data is kept and still counts where the ledger counts it, so the reports agree with the books.
        assertThat(profitDay(TODAY).get("sales_minor").asLong()).isEqualTo(-400);
        for (JsonNode row : cashDays(hq, "2026-01-05", TODAY).get("items")) {
            assertThat(row.get("other_movements_minor").asLong()).isZero();
        }
        assertThat(api.get("/returns", RETURNER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Marks a product inactive, as the Items screen does (issue #342). */
    void switchOff(UUID id) {
        String etag = api.get("/products/" + id, ADMIN).getHeaders().getETag();
        assertThat(api.call(
                                HttpMethod.PATCH,
                                "/products/" + id,
                                Map.of("active", false),
                                ADMIN,
                                "*",
                                Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** ADR-045: a return corrects a sale already made, so it works for an item switched off since. */
    @Test
    void aReturnOfAnItemMadeInactiveAfterTheSaleWorks() {
        UUID s = cashSale(hq, "5", null);
        UUID l = lineOf(s, 0);
        switchOff(product);
        ResponseEntity<JsonNode> r = ret(s, List.of(line(l, "1", "restock")), "cash");
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }
}
