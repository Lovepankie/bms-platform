package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.cashbook.CashbookTestSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
 * The old app's buttons on BMS's safe paths (issue #830). The screens say Delete this sale, Mark as paid, Edit a delivery, Edit or
 * Delete an expense and Delete an item, category or unit; underneath each one is the existing void, payment, correct-this-purchase
 * or switch-off path, sent exactly as the screens send it, so the record is kept and linked, the stock and the books come right,
 * and the permissions stand. The void model is unchanged. Fabricated names and figures.
 */
class RetailOldAppWordsIT extends IntegrationTest {

    /** The reasons and notes the screens send (sale-actions-state.ts, restock-correct-state.ts, expenses-state.ts). */
    static final String SALE_DELETED = "Deleted from the sale screen";

    static final String EDITED = "Edited: entered again with the changes";
    static final String EXPENSE_DELETED = "Deleted from the expenses screen";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    CashbookTestSupport cash;
    UUID hq;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-oldapp", false, true);
        api = new RetailTestSupport(http, t);
        cash = new CashbookTestSupport(http, t);
        hq = t.headOffice();
        product = api.product("OA-1", 1_000, 1_500);
        api.stockUp(hq, product, "20");
    }

    JsonNode ok(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isTrue();
        return r.getBody();
    }

    String qtyAtHq() {
        for (JsonNode row : ok(api.get("/stock?branch_id=" + hq + "&include_inactive=true", ADMIN))
                .get("items")) {
            if (row.get("product_id").asString().equals(product.toString())) {
                return row.get("qty").asString();
            }
        }
        throw new AssertionError("the item is not on the stock list");
    }

    long count(String sql, Object... params) {
        return TestDatabase.owner()
                .sql(sql)
                .params(List.of(params))
                .query(Long.class)
                .single();
    }

    // ---- 1. Delete this sale --------------------------------------------------------------------

    @Test
    void deleteThisSaleVoidsItKeepingTheSaleWithTheStockAndTheBooksBack() {
        UUID sale = RetailTestSupport.id(api.sell(hq, "cash", product, "3"));
        assertThat(qtyAtHq()).isEqualTo("17.000");

        // Owner-level only, as before: the sales role cannot delete a sale.
        ResponseEntity<JsonNode> denied = api.post("/sales/" + sale + "/void", Map.of("reason", SALE_DELETED), SALES);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The reason is optional on the screen; a blank one sends the plain default, which the void keeps.
        JsonNode deleted = ok(api.post("/sales/" + sale + "/void", Map.of("reason", SALE_DELETED), ADMIN));
        assertThat(deleted.get("status").asString()).isEqualTo("voided");
        assertThat(deleted.get("void_reason").asString()).isEqualTo(SALE_DELETED);
        assertThat(qtyAtHq()).isEqualTo("20.000");

        // Kept, never removed: the sale reads back, and every entry it posted has its reversal.
        assertThat(ok(api.get("/sales/" + sale, ADMIN)).get("status").asString())
                .isEqualTo("voided");
        long posted = count(
                "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_id = ? AND reverses_entry_id IS"
                        + " NULL",
                t.tenantId(),
                sale);
        long reversed = count(
                "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_id = ? AND reverses_entry_id IS"
                        + " NOT NULL",
                t.tenantId(),
                sale);
        assertThat(posted).isPositive();
        assertThat(reversed).isEqualTo(posted);
    }

    // ---- 2. Mark as paid ------------------------------------------------------------------------

    @Test
    void markAsPaidRecordsTheWholeBalanceTodayInCash() {
        UUID sale = RetailTestSupport.id(api.sell(hq, "credit", product, "2"));
        long owed = ok(api.get("/sales/" + sale, ADMIN)).get("balance_minor").asLong();
        assertThat(owed).isEqualTo(3_000);
        String today = LocalDate.now(BusinessClock.DEFAULT_ZONE).toString();
        Map<String, Object> body = Map.of("amount_minor", owed, "method", "cash", "paid_on", today);
        // The sales role may take it, as Record payment.
        ok(api.postKeyed(
                "/sales/" + sale + "/payments",
                body,
                SALES,
                "*",
                UUID.randomUUID().toString()));
        JsonNode paid = ok(api.get("/sales/" + sale, ADMIN));
        assertThat(paid.get("balance_minor").asLong()).isZero();
        assertThat(paid.get("paid_minor").asLong()).isEqualTo(3_000);
        long debtors = count("""
            SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l JOIN gl_accounts a ON a.id = l.account_id
             WHERE l.tenant_id = ? AND a.system_key = 'trade_debtors' AND l.subledger_id = ?
            """, t.tenantId(), sale);
        assertThat(debtors).isZero();
    }

    // ---- 3. Edit a delivery ---------------------------------------------------------------------

    JsonNode buy(String qty, String note) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", 1_000);
        line.put("sell_minor", 1_500);
        line.put("qty_by_branch", List.of(Map.of("branch_id", hq, "qty", qty)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", LocalDate.now(BusinessClock.DEFAULT_ZONE).toString());
        body.put("payment_method", "cash");
        if (note != null) {
            body.put("note", note);
        }
        body.put("lines", List.of(line));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    Map<String, Object> delivery(String qty) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", 1_000);
        line.put("sell_minor", 1_500);
        line.put("qty_by_branch", List.of(Map.of("branch_id", hq, "qty", qty)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", LocalDate.now(BusinessClock.DEFAULT_ZONE).toString());
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        return body;
    }

    /** Save changes on an edited delivery: the one call the screen makes. */
    ResponseEntity<JsonNode> replace(JsonNode old, String qty, String perms) {
        return api.postKeyed(
                "/purchases/" + old.get("id").asString() + "/replace",
                delivery(qty),
                perms,
                "*",
                UUID.randomUUID().toString());
    }

    @Test
    void editADeliveryVoidsTheOldOneAndSavesTheNewOneNamingItInOneCall() {
        JsonNode old = buy("10", null);
        String oldNo = old.get("purchase_no").asString();
        assertThat(qtyAtHq()).isEqualTo("30.000");

        // Owner-level as Correct this purchase: the sales role may not.
        assertThat(replace(old, "8", SALES).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Save changes: one call voids the old delivery and saves the new one, whose note names it.
        ResponseEntity<JsonNode> r = replace(old, "8", ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode edited = r.getBody();
        assertThat(qtyAtHq()).isEqualTo("28.000");

        JsonNode kept = ok(api.get("/purchases/" + old.get("id").asString(), ADMIN));
        assertThat(kept.get("status").asString()).isEqualTo("voided");
        assertThat(kept.get("void_reason").asString()).isEqualTo(EDITED);
        JsonNode linked = ok(api.get("/purchases/" + edited.get("id").asString(), ADMIN));
        assertThat(linked.get("note").asString()).isEqualTo("Replaces " + oldNo + ", edited");
        assertThat(linked.get("status").asString()).isEqualTo("posted");

        // The books: the old delivery's entries are reversed one for one.
        long posted = count(
                "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_id = ? AND reverses_entry_id IS"
                        + " NULL",
                t.tenantId(),
                UUID.fromString(old.get("id").asString()));
        long reversed = count(
                "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_id = ? AND reverses_entry_id IS"
                        + " NOT NULL",
                t.tenantId(),
                UUID.fromString(old.get("id").asString()));
        assertThat(reversed).isEqualTo(posted).isPositive();
    }

    @Test
    void editADeliveryIsRefusedWhereTheStockHasMovedSoNothingIsEnteredTwice() {
        JsonNode old = buy("5", null);
        // The 25 now at head office are counted down to 3: the 5 the delivery added cannot be taken back out.
        api.stockUp(hq, product, "3");
        ResponseEntity<JsonNode> refused = replace(old, "4", ADMIN);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("purchase_stock_moved");
        assertThat(ok(api.get("/purchases/" + old.get("id").asString(), ADMIN))
                        .get("status")
                        .asString())
                .isEqualTo("posted");
        assertThat(qtyAtHq()).isEqualTo("3.000");
    }

    @Test
    void anEditWhoseNewDeliveryIsRefusedLeavesTheOldOneAsItWas() {
        // The review of #830: the item was deleted (switched off) after the delivery; the new restock is refused
        // (product_inactive), so the void rolls back with it and the old delivery, its stock and its entries stay.
        JsonNode old = buy("10", null);
        patch("/products/" + product, Map.of("active", false));
        ResponseEntity<JsonNode> refused = replace(old, "8", ADMIN);
        assertThat(refused.getStatusCode().is4xxClientError())
                .as("%s", refused.getBody())
                .isTrue();
        assertThat(refused.getBody().toString()).contains("product_inactive");
        JsonNode kept = ok(api.get("/purchases/" + old.get("id").asString(), ADMIN));
        assertThat(kept.get("status").asString()).isEqualTo("posted");
        assertThat(qtyAtHq()).isEqualTo("30.000");
        assertThat(count(
                        "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_id = ? AND reverses_entry_id IS"
                                + " NOT NULL",
                        t.tenantId(),
                        UUID.fromString(old.get("id").asString())))
                .isZero();
        assertThat(count(
                        "SELECT count(*) FROM retail_purchases WHERE tenant_id = ? AND note LIKE 'Replaces %'",
                        t.tenantId()))
                .isZero();
    }

    // ---- 4. Edit or delete an expense -----------------------------------------------------------

    @Test
    void editAnExpenseVoidsTheOldOneAndSavesTheNewOneLinkedAndDeleteIsOwnerLevel() {
        UUID[] list = cash.categoryWithItem("Test Transport", "Test Fuel", false);
        LocalDate day = CashbookTestSupport.daysAgo(0);
        UUID first = RetailTestSupport.id(cash.expense(hq, list[0], list[1], 7_000, day));
        long before = cash.cashOnHand(hq, day);

        // Edit: one call voids the old one and saves the new one with the words that name it.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("business_date", day.toString());
        body.put("category_id", list[0]);
        body.put("item_id", list[1]);
        body.put("amount_minor", 6_500);
        // The till role records expenses but may not void one, so it may not edit one either.
        assertThat(cash.post("/expenses/" + first + "/replace", body, CashbookTestSupport.CASHIER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> replaced =
                cash.post("/expenses/" + first + "/replace", body, CashbookTestSupport.CASHBOOK_ADMIN);
        assertThat(replaced.getStatusCode()).as("%s", replaced.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID second = RetailTestSupport.id(replaced);
        // The cash book: 7,000 back, 6,500 out.
        assertThat(cash.cashOnHand(hq, day)).isEqualTo(before + 500);

        JsonNode rows = ok(cash.get(
                "/expenses?branch_id=" + hq + "&from=" + day + "&to=" + day + "&include_voided=true",
                CashbookTestSupport.CASHBOOK_ADMIN));
        JsonNode kept = null;
        JsonNode linked = null;
        for (JsonNode r : rows.get("items")) {
            if (r.get("id").asString().equals(first.toString())) {
                kept = r;
            }
            if (r.get("id").asString().equals(second.toString())) {
                linked = r;
            }
        }
        assertThat(kept).isNotNull();
        assertThat(kept.get("voided").asBoolean()).isTrue();
        assertThat(kept.get("void_reason").asString()).isEqualTo(EDITED);
        assertThat(linked).isNotNull();
        assertThat(linked.get("explanation").asString()).startsWith("Replaces the Test Fuel entry of");

        // Delete is owner-level: the till role, which records expenses, may not void one.
        ResponseEntity<JsonNode> denied =
                cash.voidIt("/expenses/" + second, EXPENSE_DELETED, CashbookTestSupport.CASHIER);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ok(cash.voidIt("/expenses/" + second, EXPENSE_DELETED, CashbookTestSupport.CASHBOOK_ADMIN));
        assertThat(cash.cashOnHand(hq, day)).isEqualTo(before + 7_000);
    }

    // JUSTIFICATION-A3: the review of #830 asks the tests to prove a refused or waiting edit leaves the old expense.
    @Test
    void anExpenseEditThatIsRefusedOrWouldWaitForApprovalLeavesTheOldOneAsItWas() {
        UUID[] list = cash.categoryWithItem("Test Repairs", "Test Parts", false);
        LocalDate day = CashbookTestSupport.daysAgo(0);
        UUID first = RetailTestSupport.id(cash.expense(hq, list[0], list[1], 7_000, day));
        long before = cash.cashOnHand(hq, day);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("business_date", day.toString());
        body.put("category_id", list[0]);
        body.put("item_id", UUID.randomUUID());
        body.put("amount_minor", 6_500);

        // A new expense the server refuses (an item not in the category): the void rolls back with it.
        ResponseEntity<JsonNode> refused =
                cash.post("/expenses/" + first + "/replace", body, CashbookTestSupport.CASHBOOK_ADMIN);
        assertThat(refused.getStatusCode().is4xxClientError())
                .as("%s", refused.getBody())
                .isTrue();
        assertThat(cash.cashOnHand(hq, day)).isEqualTo(before);

        // Expenses from 5,000 ask for an administrator (ADR-091): the old one is never voided while its replacement
        // waits.
        approvalRule("retail_expense", 5_000);
        body.put("item_id", list[1]);
        ResponseEntity<JsonNode> waits =
                cash.post("/expenses/" + first + "/replace", body, CashbookTestSupport.CASHBOOK_ADMIN);
        assertThat(waits.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(waits.getBody().get("code").asString()).isEqualTo("edit_needs_approval");
        assertThat(cash.cashOnHand(hq, day)).isEqualTo(before);
        assertThat(count(
                        "SELECT count(*) FROM retail_expenses WHERE tenant_id = ? AND voided_at IS NULL", t.tenantId()))
                .isEqualTo(1);
    }

    /** Switches on the approval of an action from an amount, as RetailApprovalRulesIT does. */
    void approvalRule(String action, long amount) {
        TestDatabase.owner()
                .sql("INSERT INTO tenant_settings (id, tenant_id) VALUES (?, ?) ON CONFLICT (tenant_id) DO NOTHING")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        TestDatabase.owner()
                .sql("""
                        UPDATE tenant_settings SET settings = settings
                               || jsonb_build_object('approval_switches',
                                      coalesce(settings->'approval_switches', '{}'::jsonb) || jsonb_build_object(?::text, true))
                               || jsonb_build_object('approval_thresholds_minor',
                                      coalesce(settings->'approval_thresholds_minor', '{}'::jsonb) || jsonb_build_object(?::text, ?))
                         WHERE tenant_id = ?
                        """)
                .params(action, action, amount, t.tenantId())
                .update();
    }

    // ---- 5. Delete an item, category or unit ----------------------------------------------------

    ResponseEntity<JsonNode> patch(String path, Map<String, Object> body) {
        String etag = api.get(path, ADMIN).getHeaders().getETag();
        return api.call(HttpMethod.PATCH, path, body, ADMIN, "*", Map.of("If-Match", etag == null ? "\"1\"" : etag));
    }

    @Test
    void deleteAnItemSwitchesItOffHidingItWhileItsHistoryStays() {
        UUID sale = RetailTestSupport.id(api.sell(hq, "cash", product, "1"));
        ok(patch("/products/" + product, Map.of("active", false)));

        // Hidden from the lists the screens use, and from selling.
        assertThat(ok(api.get("/products?query=OA-1&active=true", ADMIN)).get("items"))
                .isEmpty();
        assertThat(ok(api.get("/stock?branch_id=" + hq, ADMIN)).get("items").toString())
                .doesNotContain(product.toString());
        ResponseEntity<JsonNode> sell = api.sell(hq, "cash", product, "1");
        assertThat(sell.getStatusCode().is4xxClientError()).isTrue();
        assertThat(sell.getBody().toString()).contains("product_inactive");
        // Kept: Show deleted lists it, its stock and its past sale stay.
        assertThat(ok(api.get("/products?query=OA-1&active=false", ADMIN)).get("items"))
                .hasSize(1);
        assertThat(qtyAtHq()).isEqualTo("19.000");
        assertThat(ok(api.get("/sales/" + sale, ADMIN))
                        .get("lines")
                        .get(0)
                        .get("product_id")
                        .asString())
                .isEqualTo(product.toString());

        // Bring back.
        ok(patch("/products/" + product, Map.of("active", true)));
        assertThat(ok(api.get("/products?query=OA-1&active=true", ADMIN)).get("items"))
                .hasSize(1);
    }

    @Test
    void deleteACategoryOrAUnitSwitchesItOffAndKeepsTheItemsThatUseIt() {
        JsonNode item = ok(api.get("/products/" + product, ADMIN));
        UUID category = UUID.fromString(item.get("category_id").asString());
        JsonNode deleted = ok(patch("/categories/" + category, Map.of("active", false)));
        assertThat(deleted.get("active").asBoolean()).isFalse();
        // The item keeps its category; the category stays listed for Show deleted, marked off.
        assertThat(ok(api.get("/products/" + product, ADMIN)).get("category_id").asString())
                .isEqualTo(category.toString());
        boolean listedOff = false;
        for (JsonNode c : ok(api.get("/categories", ADMIN)).get("items")) {
            if (c.get("id").asString().equals(category.toString())) {
                listedOff = !c.get("active").asBoolean();
            }
        }
        assertThat(listedOff).isTrue();

        UUID unit = api.unit("Test Bundle");
        JsonNode offUnit = ok(patch("/units/" + unit, Map.of("active", false)));
        assertThat(offUnit.get("active").asBoolean()).isFalse();

        // Owner-level: the sales role may not delete a category.
        ResponseEntity<JsonNode> denied = api.call(
                HttpMethod.PATCH,
                "/categories/" + category,
                Map.of("active", true),
                SALES,
                "*",
                Map.of("If-Match", "\"2\""));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- 6. Low stock -------------------------------------------------------------------------

    @Test
    void anItemWithNoMinimumIsLowAtTenOrFewerAndItsOwnMinimumStillWins() {
        api.stockUp(hq, product, "10");
        JsonNode low = ok(api.get("/stock?branch_id=" + hq + "&stock_level=low", ADMIN));
        assertThat(low.get("low_stock_threshold").asString()).isEqualTo("10.000");
        assertThat(low.get("items").toString()).contains(product.toString());
        api.stockUp(hq, product, "11");
        assertThat(ok(api.get("/stock?branch_id=" + hq + "&stock_level=low", ADMIN))
                        .get("items")
                        .toString())
                .doesNotContain(product.toString());
        // Its own minimum of 12 wins over the default.
        ok(api.call(
                HttpMethod.PUT,
                "/products/" + product + "/stock-minimum/branches/" + hq,
                Map.of("min_qty", "12"),
                ADMIN,
                "*",
                Map.of()));
        assertThat(ok(api.get("/stock?branch_id=" + hq + "&stock_level=low", ADMIN))
                        .get("items")
                        .toString())
                .contains(product.toString());
    }
}
