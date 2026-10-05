package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
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
 * Payments against credit sales and usage and damage reports (#53; FR-RET-05, FR-RET-07,
 * FR-RET-11, FR-RET-14). Amounts fabricated.
 */
class RetailPaymentsAndUsageIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-pay", false, true);
        api = new RetailTestSupport(http, t);
        product = api.product("WIRE-1MM", 300, 500);
    }

    ResponseEntity<JsonNode> pay(UUID sale, long amount, String perms) {
        return api.postKeyed(
                "/sales/" + sale + "/payments",
                Map.of("amount_minor", amount, "method", "mobile_money"),
                perms,
                "*",
                UUID.randomUUID().toString());
    }

    /** FR-RET-05: partial payments reduce the balance and trade debtors; never past the total. */
    @Test
    void partialPaymentsSettleACreditSale() {
        UUID sale = RetailTestSupport.id(api.sell(t.headOffice(), "credit", product, "10"));
        ResponseEntity<JsonNode> first = pay(sale, 2_000, SALES);
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("sale_balance_minor").asLong()).isEqualTo(3_000);
        ResponseEntity<JsonNode> tooMuch = pay(sale, 3_001, ADMIN);
        assertThat(tooMuch.getBody().get("code").asString()).isEqualTo("payment_exceeds_balance");
        assertThat(pay(sale, 3_000, ADMIN).getBody().get("sale_balance_minor").asLong())
                .isZero();

        JsonNode s = api.get("/sales/" + sale, ADMIN).getBody();
        assertThat(s.get("paid_minor").asLong()).isEqualTo(5_000);
        assertThat(s.get("balance_minor").asLong()).isZero();
        assertThat(api.get("/sales/" + sale + "/payments", SALES).getBody().get("items"))
                .hasSize(2);
        Long debtors = TestDatabase.owner()
                .sql("""
                        SELECT sum(l.debit - l.credit) FROM journal_lines l JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = 'trade_debtors' AND l.subledger_id = ?
                        """)
                .params(t.tenantId(), sale)
                .query(Long.class)
                .single();
        assertThat(debtors).isZero();
        Long mobile = TestDatabase.owner()
                .sql("""
                        SELECT sum(l.debit - l.credit) FROM journal_lines l JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = 'mobile_money'
                        """)
                .param(t.tenantId())
                .query(Long.class)
                .single();
        assertThat(mobile).isEqualTo(5_000);

        ResponseEntity<JsonNode> voided = api.post("/sales/" + sale + "/void", Map.of("reason", "Test"), ADMIN);
        assertThat(voided.getBody().get("code").asString()).isEqualTo("sale_has_payments");
        UUID cash = RetailTestSupport.id(api.sell(t.headOffice(), "cash", product, "1"));
        assertThat(pay(cash, 100, ADMIN).getBody().get("code").asString()).isEqualTo("sale_not_payable");
    }

    /**
     * FR-RET-07, FR-RET-11: usage and damage reduce stock, are valued at the cost at the time and
     * post to stock shrinkage; the sales role may report but sees no cost.
     */
    @Test
    void usageAndDamageAreValuedAtCost() {
        api.stockUp(t.headOffice(), product, "20");
        ResponseEntity<JsonNode> used = api.postKeyed(
                "/usage",
                Map.of(
                        "branch_id",
                        t.headOffice(),
                        "kind",
                        "used",
                        "reason",
                        "Test installation",
                        "lines",
                        List.of(Map.of("product_id", product, "qty", "2.5"))),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(used.getStatusCode()).as("%s", used.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(used.getBody().get("cost_total_minor").asLong()).isEqualTo(750);
        assertThat(used.getBody().get("lines").get(0).get("unit_cost_minor").asLong())
                .isEqualTo(300);

        ResponseEntity<JsonNode> damaged = api.postKeyed(
                "/usage",
                Map.of(
                        "branch_id",
                        t.headOffice(),
                        "kind",
                        "damaged",
                        "reason",
                        "Test water damage",
                        "lines",
                        List.of(Map.of("product_id", product, "qty", "1"))),
                SALES,
                "*",
                UUID.randomUUID().toString());
        assertThat(damaged.getStatusCode()).as("%s", damaged.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(damaged.getBody().has("cost_total_minor")).isFalse();
        assertThat(damaged.getBody().get("lines").get(0).has("unit_cost_minor")).isFalse();

        JsonNode stock = api.get("/stock?branch_id=" + t.headOffice(), ADMIN)
                .getBody()
                .get("items")
                .get(0);
        assertThat(stock.get("qty").asString()).isEqualTo("16.500");
        List<String> kinds = TestDatabase.owner()
                .sql(
                        "SELECT kind FROM retail_stock_movements WHERE tenant_id = ? AND source_type = 'retail.usage' ORDER BY created_at")
                .param(t.tenantId())
                .query(String.class)
                .list();
        assertThat(kinds).containsExactly("usage", "damage");
        Long shrinkage = TestDatabase.owner()
                .sql("""
                        SELECT sum(l.debit - l.credit) FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = 'stock_shrinkage' AND e.source_type = 'retail.usage'
                        """)
                .param(t.tenantId())
                .query(Long.class)
                .single();
        assertThat(shrinkage).isEqualTo(1_050);
        assertThat(api.post("/usage", Map.of(), ADMIN).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
