package com.rincoltech.bms.retail.purchasing;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * A mistaken restock is voided, never deleted (issue #351, ADR-044): its stock, journal entries and prices are reversed in
 * one transaction, the restock stays and shows as voided with who, when and why, and the void is refused in plain words when
 * the stock has moved so the reversal is impossible. Fabricated data. The clock is pinned so the day of the void is known.
 */
@Import(RetailPurchaseVoidIT.FixedClock.class)
class RetailPurchaseVoidIT extends IntegrationTest {

    static final Instant NOW = Instant.parse("2026-10-08T09:00:00Z");
    static final String TODAY = "2026-10-08";
    static final String EARLIER = "2026-10-05";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    static final String SETTINGS = "core.settings.manage";
    /** The administrator column, with the cash book and the supplier money side (the payables permission). */
    static final String PAYER =
            ADMIN + ",retail.purchase.approve,retail.payable.manage,retail.cashbook.read," + SETTINGS;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;
    /** Cost 1,000, sell 1,500. */
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-pvoid", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
        product = api.product("PV-1", 1_000, 1_500);
    }

    // ---- helpers ---------------------------------------------------------------------------

    static String key() {
        return UUID.randomUUID().toString();
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    JsonNode buy(String method, String on, long cost, long sell, UUID supplier, Map<UUID, String> qty) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", cost);
        line.put("sell_minor", sell);
        line.put(
                "qty_by_branch",
                qty.entrySet().stream()
                        .map(e -> Map.of("branch_id", e.getKey(), "qty", e.getValue()))
                        .toList());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", on);
        body.put("payment_method", method);
        if (supplier != null) {
            body.put("supplier_id", supplier);
        }
        body.put("lines", List.of(line));
        ResponseEntity<JsonNode> r = api.postKeyed("/purchases", body, ADMIN, "*", key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    ResponseEntity<JsonNode> voidIt(UUID purchase, String reason, String perms, String scope, String key) {
        return api.call(
                HttpMethod.POST,
                "/purchases/" + purchase + "/void",
                Map.of("reason", reason),
                perms,
                scope,
                key == null ? Map.of() : Map.of("Idempotency-Key", key));
    }

    ResponseEntity<JsonNode> voidIt(UUID purchase, String reason) {
        return voidIt(purchase, reason, ADMIN, "*", key());
    }

    BigDecimal stockAt(UUID branch) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE branch_id = ? AND product_id = ?")
                .params(branch, product)
                .query(BigDecimal.class)
                .single();
    }

    long scalar(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    /** The net of an account over the lines of every entry the restock and its reversal posted. */
    long net(String systemKey, UUID purchase) {
        return scalar("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.tenant_id = ? AND e.source_type = 'retail.purchase' AND e.source_id = ? AND a.system_key = ?
                        """, t.tenantId(), purchase, systemKey);
    }

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

    JsonNode product() {
        return api.get("/products/" + product, ADMIN).getBody();
    }

    // ---- the void ----------------------------------------------------------------------------

    @Test
    void aVoidReversesTheStockTheJournalAndThePricesAndKeepsTheRestock() {
        JsonNode bought = buy("cash", TODAY, 1_200, 1_800, null, Map.of(hq, "10", br2, "4"));
        UUID id = UUID.fromString(bought.get("id").asString());
        assertThat(stockAt(hq)).isEqualByComparingTo("10");
        assertThat(product().get("cost_minor").asLong()).isEqualTo(1_200);

        String voidKey = key();
        ResponseEntity<JsonNode> voided = voidIt(id, "Entered ten, there were only six", ADMIN, "*", voidKey);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = voided.getBody();
        assertThat(body.get("status").asString()).isEqualTo("voided");
        assertThat(body.get("void_reason").asString()).isEqualTo("Entered ten, there were only six");
        assertThat(body.get("voided_on").asString()).isEqualTo(TODAY);
        assertThat(body.get("voided_by").asString()).isEqualTo(api.userId().toString());
        assertThat(body.get("lines")).hasSize(1);

        // Stock is back where it was, and every reversal points at the movement it undoes.
        assertThat(stockAt(hq)).isEqualByComparingTo("0");
        assertThat(stockAt(br2)).isEqualByComparingTo("0");
        assertThat(scalar("""
                                SELECT count(*) FROM retail_stock_movements
                                 WHERE tenant_id = ? AND source_type = 'retail.purchase_void' AND source_id = ?
                                   AND kind = 'adjustment' AND qty < 0 AND reverses_movement_id IS NOT NULL
                                   AND note = 'Entered ten, there were only six'
                                """, t.tenantId(), id)).isEqualTo(2);
        // The journal: one reversal per branch entry, and the inventory and cash accounts net to nothing.
        assertThat(scalar(
                        "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_id = ? AND reverses_entry_id IS NOT NULL",
                        t.tenantId(),
                        id))
                .isEqualTo(2);
        assertThat(net("inventory", id)).isZero();
        assertThat(net("cash_on_hand", id)).isZero();
        // The prices the restock set are back, with a history row that says why.
        JsonNode p = product();
        assertThat(p.get("cost_minor").asLong()).isEqualTo(1_000);
        assertThat(p.get("sell_minor").asLong()).isEqualTo(1_500);
        JsonNode history = api.get("/products/" + product + "/price-history", ADMIN)
                .getBody()
                .get("items");
        JsonNode last = history.get(history.size() - 1);
        assertThat(last.get("source").asString()).isEqualTo("purchase");
        assertThat(last.get("new_cost_minor").asLong()).isEqualTo(1_000);
        assertThat(last.get("reason").asString())
                .isEqualTo("Restock " + bought.get("purchase_no").asString() + " voided");
        // Never deleted: the restock and the void are both still there, and the audit says who.
        assertThat(scalar("SELECT count(*) FROM retail_purchases WHERE tenant_id = ? AND id = ?", t.tenantId(), id))
                .isEqualTo(1);
        assertThat(scalar(
                        "SELECT count(*) FROM retail_purchase_voids WHERE tenant_id = ? AND purchase_id = ?",
                        t.tenantId(),
                        id))
                .isEqualTo(1);
        assertThat(scalar(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.purchase.voided' AND entity_id = ?",
                        t.tenantId(),
                        id))
                .isEqualTo(1);

        // Read back and listed as voided; the filters and the order work.
        JsonNode one = api.get("/purchases/" + id, ADMIN).getBody();
        assertThat(one.get("status").asString()).isEqualTo("voided");
        assertThat(api.get("/purchases?status=voided", ADMIN).getBody().get("items"))
                .hasSize(1);
        assertThat(api.get("/purchases?status=posted", ADMIN).getBody().get("items"))
                .isEmpty();
        assertThat(api.get("/purchases?status=other", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        // A second void is refused; the same key replays the first answer.
        ResponseEntity<JsonNode> again = voidIt(id, "Once more please");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(again)).isEqualTo("purchase_voided");
        ResponseEntity<JsonNode> replay = voidIt(id, "Entered ten, there were only six", ADMIN, "*", voidKey);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(stockAt(hq)).isEqualByComparingTo("0");
    }

    @Test
    void theRequestNeedsAKeyAReasonAndThePermission() {
        UUID id = UUID.fromString(buy("cash", TODAY, 1_200, 1_800, null, Map.of(hq, "3"))
                .get("id")
                .asString());
        ResponseEntity<JsonNode> noKey = voidIt(id, "Typed the wrong number", ADMIN, "*", null);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(noKey)).isEqualTo("idempotency_key_missing");
        assertThat(voidIt(id, "no").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(voidIt(id, "Typed the wrong number", SALES, "*", key()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(voidIt(UUID.randomUUID(), "Typed the wrong number").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stockAt(hq)).isEqualByComparingTo("3");
        assertThat(scalar("SELECT count(*) FROM retail_purchase_voids WHERE tenant_id = ?", t.tenantId()))
                .isZero();
    }

    @Test
    void aBranchScopedManagerCannotVoidARestockThatTouchedAnotherBranch() {
        UUID id = UUID.fromString(buy("cash", TODAY, 1_200, 1_800, null, Map.of(hq, "3", br2, "2"))
                .get("id")
                .asString());
        // Scope: only the head office. The restock also added stock at the second branch.
        ResponseEntity<JsonNode> partial = voidIt(id, "Typed the wrong number", ADMIN, hq.toString(), key());
        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(partial)).isEqualTo("permission_denied");
        // A branch that has nothing to do with it does not even see it.
        UUID other = UUID.fromString(buy("cash", TODAY, 1_200, 1_800, null, Map.of(hq, "1"))
                .get("id")
                .asString());
        ResponseEntity<JsonNode> blind = voidIt(other, "Typed the wrong number", ADMIN, br2.toString(), key());
        assertThat(blind.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // A copy scoped to the head office says it hides other branches, so a screen offers no void the server refuses;
        // the whole business sees no such field.
        JsonNode narrow = api.call(HttpMethod.GET, "/purchases/" + id, null, ADMIN, hq.toString(), Map.of())
                .getBody();
        assertThat(narrow.get("partial").asBoolean()).isTrue();
        assertThat(api.get("/purchases/" + id, ADMIN).getBody().has("partial")).isFalse();
        // Scoped to both it works.
        assertThat(voidIt(id, "Typed the wrong number", ADMIN, hq + "," + br2, key())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aVoidIsRefusedInPlainWordsWhenTheStockWasSoldAndChangesNothing() {
        JsonNode bought = buy("cash", TODAY, 1_200, 1_800, null, Map.of(hq, "10"));
        UUID id = UUID.fromString(bought.get("id").asString());
        assertThat(api.sell(hq, "cash", product, "4").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stockAt(hq)).isEqualByComparingTo("6");

        ResponseEntity<JsonNode> refused = voidIt(id, "Entered ten, there were only six");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("purchase_stock_moved");
        String words = refused.getBody().get("detail").asString();
        assertThat(words)
                .contains("Test Product PV-1")
                .contains("added 10")
                .contains("only 6")
                .contains("Correct the quantity");

        assertThat(stockAt(hq)).isEqualByComparingTo("6");
        assertThat(product().get("cost_minor").asLong()).isEqualTo(1_200);
        assertThat(scalar("SELECT count(*) FROM retail_purchase_voids WHERE tenant_id = ?", t.tenantId()))
                .isZero();
        assertThat(scalar(
                        "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_id = ? AND reverses_entry_id IS NOT NULL",
                        t.tenantId(),
                        id))
                .isZero();
        assertThat(api.get("/purchases/" + id, ADMIN).getBody().get("status").asString())
                .isEqualTo("posted");
    }

    @Test
    void aVoidWorksWhenEnoughStockIsLeftEvenIfSomeWasSold() {
        UUID first = UUID.fromString(buy("cash", EARLIER, 1_200, 1_800, null, Map.of(hq, "10"))
                .get("id")
                .asString());
        buy("cash", TODAY, 1_200, 1_800, null, Map.of(hq, "10"));
        assertThat(api.sell(hq, "cash", product, "4").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stockAt(hq)).isEqualByComparingTo("16");
        assertThat(voidIt(first, "Counted twice by mistake").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stockAt(hq)).isEqualByComparingTo("6");
    }

    @Test
    void thePricesStayWhenALaterEventMovedThemAndTheHistorySaysSo() {
        JsonNode bought = buy("cash", TODAY, 1_200, 1_800, null, Map.of(hq, "10"));
        UUID id = UUID.fromString(bought.get("id").asString());
        ResponseEntity<JsonNode> edit = api.editPrices(
                product,
                Map.of("cost_minor", 1_300, "sell_minor", 1_900, "reason", "Supplier raised the price"),
                ADMIN);
        assertThat(edit.getStatusCode()).as("%s", edit.getBody()).isEqualTo(HttpStatus.OK);

        assertThat(voidIt(id, "Entered ten, there were only six").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode p = product();
        assertThat(p.get("cost_minor").asLong()).isEqualTo(1_300);
        assertThat(p.get("sell_minor").asLong()).isEqualTo(1_900);
        JsonNode history = api.get("/products/" + product + "/price-history", ADMIN)
                .getBody()
                .get("items");
        JsonNode last = history.get(history.size() - 1);
        assertThat(last.get("old_cost_minor").asLong()).isEqualTo(1_300);
        assertThat(last.get("new_cost_minor").asLong()).isEqualTo(1_300);
        assertThat(last.get("reason").asString()).contains("voided").contains("left as they are");
    }

    @Test
    void aVoidedCashRestockGivesItsCashBackOnTheDayOfTheVoid() {
        UUID id = UUID.fromString(buy("cash", EARLIER, 1_200, 1_800, null, Map.of(hq, "10"))
                .get("id")
                .asString());
        assertThat(cashPurchases(EARLIER)).isEqualTo(12_000);
        assertThat(voidIt(id, "Entered ten, there were only six").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // The day it was bought is not rewritten; the day of the void takes the cash back.
        assertThat(cashPurchases(EARLIER)).isEqualTo(12_000);
        assertThat(cashPurchases(TODAY)).isEqualTo(-12_000);
    }

    long cashPurchases(String day) {
        ResponseEntity<JsonNode> r =
                api.get("/reports/cash/daily?branch_id=" + hq + "&from=" + day + "&to=" + day, PAYER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        for (JsonNode row : r.getBody().get("items")) {
            if (row.get("business_date").asString().equals(day)) {
                return row.get("cash_purchases_minor").asLong();
            }
        }
        throw new AssertionError("no row for " + day + " in " + r.getBody());
    }

    // ---- credit restocks and the supplier -------------------------------------------------------

    UUID supplier() {
        return RetailTestSupport.id(api.post("/suppliers", Map.of("name", "Test Supplier 01"), PAYER));
    }

    @Test
    void aVoidedCreditRestockIsNoLongerOwedAndAPaidOneCannotBeVoidedUntilThePaymentIsReversed() {
        capability("purchasing", true);
        UUID supplier = supplier();
        UUID unpaid = UUID.fromString(buy("credit", TODAY, 1_000, 1_500, supplier, Map.of(hq, "5"))
                .get("id")
                .asString());
        assertThat(api.get("/suppliers/" + supplier + "/open-items", PAYER)
                        .getBody()
                        .get("items"))
                .hasSize(1);
        assertThat(voidIt(unpaid, "Wrong supplier chosen").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.get("/suppliers/" + supplier + "/open-items", PAYER)
                        .getBody()
                        .get("items"))
                .isEmpty();
        assertThat(net("trade_creditors", unpaid)).isZero();

        UUID paidFor = UUID.fromString(buy("credit", TODAY, 1_000, 1_500, supplier, Map.of(hq, "5"))
                .get("id")
                .asString());
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("supplier_id", supplier);
        payment.put("branch_id", hq);
        payment.put("method", "bank");
        payment.put("amount_minor", 2_000);
        ResponseEntity<JsonNode> paid = api.postKeyed("/supplier-payments", payment, PAYER, "*", key());
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> refused = voidIt(paidFor, "Wrong supplier chosen");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("purchase_paid");
        assertThat(refused.getBody().get("detail").asString()).contains("Reverse that payment first");

        ResponseEntity<JsonNode> reversed = api.postKeyed(
                "/supplier-payments/" + paid.getBody().get("id").asString() + "/reverse",
                Map.of("reason", "Paid the wrong supplier"),
                PAYER,
                "*",
                key());
        assertThat(reversed.getStatusCode()).as("%s", reversed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(voidIt(paidFor, "Wrong supplier chosen").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anImportedRestockCannotBeVoidedHere() {
        UUID id = UUID.fromString(buy("cash", EARLIER, 1_200, 1_800, null, Map.of(hq, "2"))
                .get("id")
                .asString());
        // retail_purchases is append-only; the owner's maintenance escape hatch (as RetailReportsIT uses) marks it
        // imported.
        TestDatabase.owner()
                .sql("DO $$ BEGIN PERFORM set_config('bms.allow_mutation', 'on', true);"
                        + " UPDATE retail_purchases SET historical = true WHERE id = '" + id + "'; END $$")
                .update();
        ResponseEntity<JsonNode> refused = voidIt(id, "Typed the wrong number");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("purchase_historical");
    }

    // ---- isolation ---------------------------------------------------------------------------------

    @Test
    void anotherBusinessNeitherSeesNorVoidsTheRestock() {
        UUID id = UUID.fromString(buy("cash", TODAY, 1_200, 1_800, null, Map.of(hq, "3"))
                .get("id")
                .asString());
        TestDatabase.Fixture other = TestDatabase.tenant("retail-pvoid-other", false, true);
        RetailTestSupport theirs = new RetailTestSupport(http, other);
        assertThat(theirs.get("/purchases/" + id, ADMIN).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<JsonNode> blind = theirs.postKeyed(
                "/purchases/" + id + "/void", Map.of("reason", "Typed the wrong number"), ADMIN, "*", key());
        assertThat(blind.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(theirs.get("/purchases", ADMIN).getBody().get("items")).isEmpty();
        assertThat(stockAt(hq)).isEqualByComparingTo("3");
    }

    @Test
    void aLaterRestockAtTheSamePricesKeepsThemWhenTheFirstIsVoided() {
        // The item costs 1,000 and sells at 1,500. R1 sets 1,200 and 1,800; R2 pays the same prices, so it writes no
        // price-history row, but it is a later restock that stands: voiding R1 must not roll the prices back to 1,000.
        UUID first = UUID.fromString(buy("cash", EARLIER, 1_200, 1_800, null, Map.of(hq, "10"))
                .get("id")
                .asString());
        buy("cash", TODAY, 1_200, 1_800, null, Map.of(hq, "5"));
        assertThat(voidIt(first, "Entered ten, there were only six").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode p = product();
        assertThat(p.get("cost_minor").asLong()).isEqualTo(1_200);
        assertThat(p.get("sell_minor").asLong()).isEqualTo(1_800);
        JsonNode history = api.get("/products/" + product + "/price-history", ADMIN)
                .getBody()
                .get("items");
        JsonNode last = history.get(history.size() - 1);
        assertThat(last.get("new_cost_minor").asLong()).isEqualTo(1_200);
        assertThat(last.get("reason").asString()).contains("voided").contains("left as they are");
        assertThat(stockAt(hq)).isEqualByComparingTo("5");
    }

    @Test
    void aBranchThatKeepsNoGoodsAnymoreRefusesTheVoidInPlainWords() {
        UUID id = UUID.fromString(buy("cash", TODAY, 1_200, 1_800, null, Map.of(hq, "3"))
                .get("id")
                .asString());
        TestDatabase.owner()
                .sql("UPDATE branches SET stocks_goods = false WHERE id = ?")
                .param(hq)
                .update();
        ResponseEntity<JsonNode> refused = voidIt(id, "Typed the wrong number");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("branch_not_stocking");
        assertThat(stockAt(hq)).isEqualByComparingTo("3");
    }
}
