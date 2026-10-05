package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Sales, voids, credit buyers and their postings through HTTP (#52; FR-RET-03, FR-RET-04, FR-RET-05,
 * FR-RET-11, FR-RET-14; ADR-020 decisions 4, 5 and 7; chapter 7 section 7.8). All names and amounts
 * are fabricated.
 */
class RetailSalesIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-sales", false, true);
        api = new RetailTestSupport(http, t);
        product = api.product("CBL-1.5", 1_500, 2_000);
    }

    /**
     * FR-RET-04, FR-RET-11: a cash sale moves only its branch, snapshots cost and price, and posts
     * revenue and cost of goods sold as two balanced entries for that branch.
     */
    @Test
    void aCashSaleMovesOnlyItsBranchAndPostsTwoBalancedEntries() {
        api.stockUp(t.headOffice(), product, "10");
        ResponseEntity<JsonNode> created = api.sell(t.headOffice(), "cash", product, "2.5");
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode sale = created.getBody();
        assertThat(sale.get("sale_no").asString()).startsWith("RS");
        assertThat(sale.get("total_minor").asLong()).isEqualTo(5_000);
        assertThat(sale.get("cost_total_minor").asLong()).isEqualTo(3_750);
        assertThat(sale.get("profit_minor").asLong()).isEqualTo(1_250);
        assertThat(sale.get("paid_minor").asLong()).isEqualTo(5_000);
        assertThat(sale.get("balance_minor").asLong()).isZero();
        JsonNode line = sale.get("lines").get(0);
        assertThat(line.get("qty").asString()).isEqualTo("2.500");
        assertThat(line.get("unit_price_minor").asLong()).isEqualTo(2_000);
        assertThat(line.get("unit_cost_minor").asLong()).isEqualTo(1_500);

        JsonNode hq = api.get("/stock?branch_id=" + t.headOffice(), ADMIN).getBody();
        assertThat(hq.get("items").get(0).get("qty").asString()).isEqualTo("7.500");
        assertThat(hq.get("items").get(0).get("negative").asBoolean()).isFalse();
        JsonNode br2 = api.get("/stock?branch_id=" + t.secondBranch(), ADMIN).getBody();
        assertThat(br2.get("items").get(0).get("qty").asString()).isEqualTo("0.000");
        assertThat(api.get("/stock?branch_id=" + t.headOffice() + "&negative_only=true", ADMIN)
                        .getBody()
                        .get("items"))
                .isEmpty();
        JsonNode searched = api.get("/products?branch_id=" + t.headOffice(), ADMIN)
                .getBody()
                .get("items");
        assertThat(searched.get(0).get("qty").asString()).isEqualTo("7.500");

        // ADR-020 decision 4: only imported history can leave a balance below zero; it is flagged.
        api.importedBalance(t.secondBranch(), product, "-2.5");
        assertThat(api.get("/stock?branch_id=" + t.secondBranch() + "&negative_only=true", ADMIN)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("negative")
                        .asBoolean())
                .isTrue();

        UUID id = RetailTestSupport.id(created);
        List<Map<String, Object>> lines = journalLines(id);
        assertThat(lines).hasSize(4);
        assertThat(lines)
                .extracting(l -> l.get("system_key") + " " + l.get("debit") + "/" + l.get("credit"))
                .containsExactlyInAnyOrder(
                        "cash_on_hand 5000/0", "sales_revenue 0/5000", "cost_of_goods_sold 3750/0", "inventory 0/3750");
        assertThat(lines).allMatch(l -> l.get("branch_id").equals(t.headOffice()));
        assertEntriesBalance(id);
        assertThat(audits(id)).containsExactly("retail.sale.created");
    }

    /** ADR-020 decision 5: profit comes from the snapshot, never from today's price. */
    @Test
    void aLaterPriceChangeLeavesTheSaleAlone() {
        api.stockUp(t.headOffice(), product, "2");
        UUID id = RetailTestSupport.id(api.sell(t.headOffice(), "cash", product, "1"));
        api.post(
                "/products/" + product + "/prices",
                Map.of("cost_minor", 1_800, "sell_minor", 2_400, "reason", "Test change"),
                ADMIN);
        JsonNode sale = api.get("/sales/" + id, ADMIN).getBody();
        assertThat(sale.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(2_000);
        assertThat(sale.get("lines").get(0).get("unit_cost_minor").asLong()).isEqualTo(1_500);
        assertThat(sale.get("profit_minor").asLong()).isEqualTo(500);
        JsonNode next = api.sell(t.headOffice(), "cash", product, "1").getBody();
        assertThat(next.get("profit_minor").asLong()).isEqualTo(600);
    }

    /** Chapter 7 section 7.8: a key is required, a retry replays, a reused key with another body is refused. */
    @Test
    void aSaleIsIdempotentOnItsKey() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "mobile_money");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        api.stockUp(t.headOffice(), product, "5");
        ResponseEntity<JsonNode> missing = api.post("/sales", body, ADMIN);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(missing.getBody().get("code").asString()).isEqualTo("idempotency_key_missing");

        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = api.postKeyed("/sales", body, ADMIN, "*", key);
        ResponseEntity<JsonNode> again = api.postKeyed("/sales", body, ADMIN, "*", key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(count("SELECT count(*) FROM retail_sales WHERE tenant_id = ?"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND kind = 'sale'"))
                .isEqualTo(1);

        body.put("payment_method", "cash");
        ResponseEntity<JsonNode> reused = api.postKeyed("/sales", body, ADMIN, "*", key);
        assertThat(reused.getBody().get("code").asString()).isEqualTo("idempotency_key_reused");
    }

    /**
     * FR-RET-04, FR-RET-11: a void writes a return movement per line and a reversing entry per
     * entry; a second void is refused; the sales role cannot void.
     */
    @Test
    void aVoidReversesTheMovementsAndTheJournals() {
        api.stockUp(t.headOffice(), product, "3");
        UUID id = RetailTestSupport.id(api.sell(t.headOffice(), "bank", product, "3"));
        ResponseEntity<JsonNode> denied = api.post("/sales/" + id + "/void", Map.of("reason", "Test"), SALES);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> voided = api.post("/sales/" + id + "/void", Map.of("reason", "Test mistake"), ADMIN);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(voided.getBody().get("status").asString()).isEqualTo("voided");
        assertThat(voided.getBody().get("void_reason").asString()).isEqualTo("Test mistake");
        assertThat(api.get("/stock?branch_id=" + t.headOffice(), ADMIN)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("qty")
                        .asString())
                .isEqualTo("3.000");
        JsonNode movements = api.get("/stock/movements?product_id=" + product, ADMIN)
                .getBody()
                .get("items");
        assertThat(movements).hasSize(3);
        assertThat(movements.get(0).get("kind").asString()).isEqualTo("adjustment");
        assertThat(movements.get(2).get("kind").asString()).isEqualTo("return");
        assertThat(movements.get(2).get("reverses_movement_id").asString())
                .isEqualTo(movements.get(1).get("id").asString());

        long reversals = TestDatabase.owner()
                .sql(
                        "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_id = ? AND reverses_entry_id IS NOT NULL")
                .params(t.tenantId(), id)
                .query(Long.class)
                .single();
        assertThat(reversals).isEqualTo(2);
        assertEntriesBalance(id);
        assertThat(netBySystemKey(id))
                .allSatisfy((key, net) -> assertThat(net).as(key).isZero());

        ResponseEntity<JsonNode> twice = api.post("/sales/" + id + "/void", Map.of("reason", "Test again"), ADMIN);
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(twice.getBody().get("code").asString()).isEqualTo("sale_voided");
        assertThat(audits(id)).containsExactly("retail.sale.created", "retail.sale.voided");
    }

    /** FR-RET-05: a credit sale names its buyer, debits trade debtors and shows on the buyer's balance. */
    @Test
    void aCreditSaleIsOwedByItsBuyer() {
        UUID buyer = RetailTestSupport.id(
                api.post("/customers", Map.of("name", "Test Buyer 02", "contact", "+256700000002"), SALES));
        api.stockUp(t.headOffice(), product, "4");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "credit");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "4")));
        ResponseEntity<JsonNode> anonymous =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        body.put("customer_id", buyer);
        body.put("due_date", "2099-01-31");
        ResponseEntity<JsonNode> sale =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(sale.getStatusCode()).as("%s", sale.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(sale.getBody().get("balance_minor").asLong()).isEqualTo(8_000);
        assertThat(sale.getBody().get("paid_minor").asLong()).isZero();
        assertThat(netBySystemKey(RetailTestSupport.id(sale))).containsEntry("trade_debtors", 8_000L);

        JsonNode balance = api.get("/customers/" + buyer + "/balance", SALES).getBody();
        assertThat(balance.get("balance_minor").asLong()).isEqualTo(8_000);
        assertThat(balance.get("open_sales")).hasSize(1);

        Map<String, Object> cash = new LinkedHashMap<>(body);
        cash.put("payment_method", "cash");
        ResponseEntity<JsonNode> withDue =
                api.postKeyed("/sales", cash, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(withDue.getBody().get("errors").get(0).get("field").asString())
                .isEqualTo("due_date");
    }

    /**
     * FR-RET-03, ADR-020 decision 4: a sale past the branch's stock is always refused and leaves
     * nothing behind; there is no setting to allow it.
     */
    @Test
    void overSellingIsAlwaysRefused() {
        assertThat(api.sell(t.headOffice(), "cash", product, "1")
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("insufficient_stock");
        api.stockUp(t.headOffice(), product, "1");
        ResponseEntity<JsonNode> refused = api.sell(t.headOffice(), "cash", product, "1.5");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("insufficient_stock");
        assertThat(count("SELECT count(*) FROM retail_sales WHERE tenant_id = ?"))
                .isZero();
        assertThat(count("SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_type = 'retail.sale'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM idempotency_keys WHERE tenant_id = ?"))
                .isZero();
        assertThat(api.sell(t.headOffice(), "cash", product, "1").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(balance()).isEqualByComparingTo("0");
    }

    /**
     * FR-RET-03: two sales of the last unit race; the balance row lock serialises them, so exactly
     * one succeeds, the other is refused, and the balance equals the sum of the movements.
     */
    @Test
    void twoSalesOfTheLastUnitAreSerialised() throws Exception {
        for (int round = 0; round < 3; round++) {
            api.stockUp(t.headOffice(), product, "1");
            List<HttpStatusCode> statuses = Api.race(2, () -> api.sell(t.headOffice(), "cash", product, "1"));
            assertThat(Api.count(statuses, HttpStatus.CREATED)).isEqualTo(1);
            assertThat(Api.count(statuses, HttpStatus.UNPROCESSABLE_CONTENT)).isEqualTo(1);
            assertThat(balance()).isEqualByComparingTo("0");
            assertThat(movementSum()).isEqualByComparingTo(balance());
        }
    }

    /** A sale line at a price; as an admin unless other permissions are given. */
    ResponseEntity<JsonNode> sellAt(long unitPriceMinor, String permissions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "cash");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1", "unit_price_minor", unitPriceMinor)));
        return api.postKeyed("/sales", body, permissions, "*", UUID.randomUUID().toString());
    }

    /**
     * Issue #64, ADR-020 decision 5: a unit price at or below the product's cost (1 500) is refused
     * with price_below_cost, even for the admin, and the refusal never carries the cost.
     */
    @Test
    void aPriceNotAboveCostIsRefusedWithoutRevealingTheCost() {
        api.stockUp(t.headOffice(), product, "10");
        for (long price : new long[] {1_499, 1_500}) {
            for (String permissions : List.of(ADMIN, SALES)) {
                ResponseEntity<JsonNode> refused = sellAt(price, permissions);
                assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                assertThat(refused.getBody().get("code").asString()).isEqualTo("price_below_cost");
                assertThat(refused.getBody().toString()).doesNotContainPattern("\\b1[ ,.]?500\\b");
            }
        }
        assertThat(count("SELECT count(*) FROM retail_sales WHERE tenant_id = ?"))
                .isZero();
        assertThat(balance()).isEqualByComparingTo("10");

        ResponseEntity<JsonNode> above = sellAt(1_501, SALES);
        assertThat(above.getStatusCode()).as("%s", above.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(above.getBody().get("lines").get(0).get("unit_price_minor").asLong())
                .isEqualTo(1_501);
    }

    /** Issue #64: a custom role holding retail.price.below_cost may sell at or below cost. */
    @Test
    void theBelowCostPermissionAllowsALowPrice() {
        api.stockUp(t.headOffice(), product, "2");
        String custom = SALES + ",retail.price.below_cost";
        ResponseEntity<JsonNode> below = sellAt(1_000, custom);
        assertThat(below.getStatusCode()).as("%s", below.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(below.getBody().get("total_minor").asLong()).isEqualTo(1_000);
        assertThat(sellAt(1_500, custom).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balance()).isEqualByComparingTo("0");
    }

    /** ADR-017: a sale happens in the caller's branch; another branch's sale is invisible. */
    @Test
    void salesFollowTheBranchScope() {
        api.stockUp(t.headOffice(), product, "1");
        api.stockUp(t.secondBranch(), product, "1");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payment_method", "cash");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        ResponseEntity<JsonNode> unnamed =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(unnamed.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("branch_required");

        ResponseEntity<JsonNode> own = api.postKeyed(
                "/sales",
                body,
                SALES,
                t.secondBranch().toString(),
                UUID.randomUUID().toString());
        assertThat(own.getStatusCode()).as("%s", own.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(own.getBody().get("branch_id").asString())
                .isEqualTo(t.secondBranch().toString());

        body.put("branch_id", t.headOffice());
        ResponseEntity<JsonNode> other = api.postKeyed(
                "/sales",
                body,
                SALES,
                t.secondBranch().toString(),
                UUID.randomUUID().toString());
        assertThat(other.getBody().get("errors").get(0).get("code").asString()).isEqualTo("unknown_branch");

        UUID hqSale = RetailTestSupport.id(api.sell(t.headOffice(), "cash", product, "1"));
        assertThat(api.call(
                                org.springframework.http.HttpMethod.GET,
                                "/sales/" + hqSale,
                                null,
                                SALES,
                                t.secondBranch().toString(),
                                Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode listed = api.call(
                        org.springframework.http.HttpMethod.GET,
                        "/sales",
                        null,
                        SALES,
                        t.secondBranch().toString(),
                        Map.of())
                .getBody()
                .get("items");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("branch_id").asString())
                .isEqualTo(t.secondBranch().toString());
    }

    /** ADR-020 decision 10: the sales role receives no cost snapshot or profit field. */
    @Test
    void theSalesRoleReceivesNoCostFields() {
        api.stockUp(t.headOffice(), product, "1");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "cash");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        JsonNode sale = api.postKeyed(
                        "/sales", body, SALES, "*", UUID.randomUUID().toString())
                .getBody();
        assertThat(sale.has("total_minor")).isTrue();
        assertThat(sale.has("cost_total_minor")).isFalse();
        assertThat(sale.has("profit_minor")).isFalse();
        assertThat(sale.get("lines").get(0).has("unit_cost_minor")).isFalse();
        assertThat(sale.get("lines").get(0).has("line_cost_minor")).isFalse();
        JsonNode stock = api.get("/stock?branch_id=" + t.headOffice(), SALES)
                .getBody()
                .get("items")
                .get(0);
        assertThat(stock.has("cost_minor")).isFalse();
        JsonNode movement =
                api.get("/stock/movements", SALES).getBody().get("items").get(0);
        assertThat(movement.has("unit_cost_minor")).isFalse();
    }

    BigDecimal balance() {
        return TestDatabase.owner()
                .sql("SELECT qty FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), t.headOffice(), product)
                .query(BigDecimal.class)
                .single();
    }

    BigDecimal movementSum() {
        return TestDatabase.owner()
                .sql(
                        "SELECT sum(qty) FROM retail_stock_movements WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), t.headOffice(), product)
                .query(BigDecimal.class)
                .single();
    }

    long count(String sql) {
        return TestDatabase.owner()
                .sql(sql)
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    List<String> audits(UUID entity) {
        return TestDatabase.owner()
                .sql("SELECT action FROM audit_log WHERE entity_id = ? ORDER BY created_at, action")
                .param(entity)
                .query(String.class)
                .list();
    }

    static List<Map<String, Object>> journalLines(UUID sourceId) {
        return TestDatabase.owner()
                .sql("""
                        SELECT a.system_key, l.debit, l.credit, e.branch_id
                          FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.source_id = ? ORDER BY e.entry_no, l.line_no
                        """)
                .param(sourceId)
                .query((rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("system_key", rs.getString(1));
                    m.put("debit", rs.getLong(2));
                    m.put("credit", rs.getLong(3));
                    m.put("branch_id", rs.getObject(4, UUID.class));
                    return m;
                })
                .list();
    }

    static Map<String, Long> netBySystemKey(UUID sourceId) {
        Map<String, Long> net = new LinkedHashMap<>();
        journalLines(sourceId)
                .forEach(l -> net.merge(
                        (String) l.get("system_key"), (Long) l.get("debit") - (Long) l.get("credit"), Long::sum));
        return net;
    }

    static void assertEntriesBalance(UUID sourceId) {
        List<Long> off =
                TestDatabase.owner().sql("""
                        SELECT sum(l.debit) - sum(l.credit) FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                         WHERE e.source_id = ? GROUP BY e.id
                        """).param(sourceId).query(Long.class).list();
        assertThat(off).isNotEmpty().allMatch(d -> d == 0);
    }
}
