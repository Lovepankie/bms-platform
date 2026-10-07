package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHBOOK_ADMIN;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHBOOK_SALES;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.daysAgo;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.LinkedHashMap;
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
 * FR-RET-17, FR-RET-24, FR-RET-28 (ADR-022 decisions 5 and 17): the expense lists, the item that
 * must belong to its category, the explanation rule, the account the entry debits, voids, idempotency
 * and the permission of each route. Names and amounts fabricated.
 */
class RetailCashbookExpensesIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    UUID hq;
    UUID rent;
    UUID rentItem;
    UUID other;
    UUID othersItem;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("cb-exp", false, true);
        cb = new CashbookTestSupport(http, t);
        hq = t.headOffice();
        UUID[] a = cb.categoryWithItem("Premises", "Shop rent", false);
        rent = a[0];
        rentItem = a[1];
        UUID[] b = cb.categoryWithItem("Miscellaneous", "Others", true);
        other = b[0];
        othersItem = b[1];
    }

    @Test
    void anExpenseDebitsOperatingExpensesAndCreditsCashAndAVoidReversesIt() {
        ResponseEntity<JsonNode> r = cb.expense(hq, rent, rentItem, 40_000, daysAgo(1));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("category_name").asString()).isEqualTo("Premises");
        assertThat(r.getBody().get("item_name").asString()).isEqualTo("Shop rent");
        assertThat(r.getBody().get("voided").asBoolean()).isFalse();
        assertThat(cb.accountMovement("operating_expenses", hq)).isEqualTo(40_000);
        assertThat(cb.accountMovement("cash_on_hand", hq)).isEqualTo(-40_000);
        UUID id = RetailTestSupport.id(r);

        ResponseEntity<JsonNode> voided = cb.voidIt("/expenses/" + id, "Entered twice", ALL);

        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(voided.getBody().get("voided").asBoolean()).isTrue();
        assertThat(cb.accountMovement("operating_expenses", hq)).isZero();
        assertThat(cb.accountMovement("cash_on_hand", hq)).isZero();
        assertThat(cb.voidIt("/expenses/" + id, "Again", ALL).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(cb.voidIt("/expenses/" + id, "Again", ALL)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("cash_record_voided");
        // The audit rows exist for the create and the void.
        assertThat(cb.auditPayloads("retail.expense.created")).hasSize(1);
        assertThat(cb.auditPayloads("retail.expense.voided")).hasSize(1);
    }

    @Test
    void aCategoryMappedToAnExpenseAccountIsDebitedInsteadOfOperatingExpenses() {
        UUID account = UUID.randomUUID();
        TestDatabase.owner().sql("""
                        INSERT INTO gl_accounts (id, tenant_id, code, name, account_type, normal_balance, currency, is_postable)
                        VALUES (?, ?, '5910', 'Test Transport', 'expense', 'debit', 'UGX', true)
                        """).params(account, t.tenantId()).update();
        UUID category = RetailTestSupport.id(cb.post(
                "/expense-categories", Map.of("name", "Transport", "expense_account_id", account), CASHBOOK_ADMIN));
        UUID item = RetailTestSupport.id(
                cb.post("/expense-categories/" + category + "/items", Map.of("name", "Fuel"), CASHBOOK_ADMIN));

        assertThat(cb.expense(hq, category, item, 9_000, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        long mapped = TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit), 0) FROM journal_lines l WHERE l.tenant_id = ? AND l.account_id = ?
                        """)
                .params(t.tenantId(), account)
                .query(Long.class)
                .single();
        assertThat(mapped).isEqualTo(9_000);
        assertThat(cb.accountMovement("operating_expenses", hq)).isZero();
    }

    @Test
    void aHeaderOrAssetAccountIsRefusedForACategory() {
        for (UUID bad : new UUID[] {t.account("cash_on_hand"), t.account("sales_revenue")}) {
            ResponseEntity<JsonNode> r = cb.post(
                    "/expense-categories", Map.of("name", "Bad " + bad, "expense_account_id", bad), CASHBOOK_ADMIN);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(r.getBody().get("errors").get(0).get("code").asString()).isEqualTo("account_not_expense");
        }
    }

    @Test
    void anItemOfAnotherCategoryAndAMissingExplanationAreRefused() {
        ResponseEntity<JsonNode> wrong = cb.expense(hq, rent, othersItem, 1_000, null);
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(wrong.getBody().get("code").asString()).isEqualTo("item_not_in_category");

        ResponseEntity<JsonNode> none = cb.expense(hq, other, othersItem, 1_000, null);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(none.getBody().get("code").asString()).isEqualTo("explanation_required");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("category_id", other);
        body.put("item_id", othersItem);
        body.put("amount_minor", 1_000);
        body.put("explanation", "ok");
        assertThat(cb.post("/expenses", body, ALL).getBody().get("code").asString())
                .isEqualTo("explanation_required");
        body.put("explanation", "Cleaning cloths");
        ResponseEntity<JsonNode> ok = cb.post("/expenses", body, ALL);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody().get("explanation").asString()).isEqualTo("Cleaning cloths");
    }

    @Test
    void aRenameLeavesOldRecordsAndASwitchedOffCategoryCannotBeChosen() {
        UUID id = RetailTestSupport.id(cb.expense(hq, rent, rentItem, 5_000, null));
        ResponseEntity<JsonNode> read = cb.get("/expense-categories", CASHBOOK_SALES);
        int version = categoryVersion(read.getBody(), rent);

        assertThat(cb.patch("/expense-categories/" + rent, Map.of("name", "Premises and rent"), CASHBOOK_ADMIN, version)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.get("/expenses", CASHBOOK_SALES)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("category_name")
                        .asString())
                .isEqualTo("Premises");
        // A stale version is refused.
        assertThat(cb.patch("/expense-categories/" + rent, Map.of("name", "Again"), CASHBOOK_ADMIN, version)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(cb.api
                        .call(
                                org.springframework.http.HttpMethod.PATCH,
                                "/expense-categories/" + rent,
                                Map.of("name", "No header"),
                                CASHBOOK_ADMIN,
                                "*",
                                Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);

        int next = categoryVersion(cb.get("/expense-categories", CASHBOOK_SALES).getBody(), rent);
        cb.patch("/expense-categories/" + rent, Map.of("active", false), CASHBOOK_ADMIN, next);
        ResponseEntity<JsonNode> refused = cb.expense(hq, rent, rentItem, 1_000, null);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("category_inactive");
        assertThat(id).isNotNull();
        // The inactive filter hides it from the form's list.
        JsonNode active = cb.get("/expense-categories?active=true", CASHBOOK_SALES)
                .getBody()
                .get("items");
        for (JsonNode c : active) {
            assertThat(c.get("id").asString()).isNotEqualTo(rent.toString());
        }
    }

    static int categoryVersion(JsonNode list, UUID category) {
        for (JsonNode c : list.get("items")) {
            if (c.get("id").asString().equals(category.toString())) {
                return c.get("version").asInt();
            }
        }
        throw new AssertionError("no category");
    }

    @Test
    void namesAreUniqueIgnoringCaseWithinTheirParent() {
        assertThat(cb.post("/expense-categories", Map.of("name", "PREMISES"), CASHBOOK_ADMIN)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("duplicate_category");
        assertThat(cb.post("/expense-categories/" + rent + "/items", Map.of("name", "shop RENT"), CASHBOOK_ADMIN)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("duplicate_item");
        // The same item name in another category is fine.
        assertThat(cb.post("/expense-categories/" + other + "/items", Map.of("name", "Shop rent"), CASHBOOK_ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        cb.party("Test Owner 01", "owner");
        assertThat(cb.post("/cash-parties", Map.of("name", "test owner 01", "kind", "owner"), CASHBOOK_ADMIN)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("duplicate_party");
    }

    @Test
    void theListsNeedExpenseManageButAShopUserMayAddABeneficiaryOnTheFly() {
        assertThat(cb.post("/expense-categories", Map.of("name", "Staff welfare"), CASHBOOK_SALES)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cb.post("/cash-parties", Map.of("name", "Test Vendor 01", "kind", "supplier"), CASHBOOK_SALES)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(cb.get("/cash-parties?query=vendor", CASHBOOK_SALES)
                        .getBody()
                        .get("items")
                        .size())
                .isEqualTo(1);
        assertThat(cb.get("/expense-categories", "retail.sale.read").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aReplayedKeyPostsOnceAndAMissingKeyIsRefused() {
        Map<String, Object> body =
                Map.of("branch_id", hq, "category_id", rent, "item_id", rentItem, "amount_minor", 7_000);
        String key = CashbookTestSupport.key();

        ResponseEntity<JsonNode> first = cb.api.postKeyed("/expenses", body, ALL, "*", key);
        ResponseEntity<JsonNode> again = cb.api.postKeyed("/expenses", body, ALL, "*", key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.getBody().get("id").asString())
                .isEqualTo(first.getBody().get("id").asString());
        assertThat(cb.accountMovement("operating_expenses", hq)).isEqualTo(7_000);

        ResponseEntity<JsonNode> missing = cb.api.post("/expenses", body, ALL);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(missing.getBody().get("code").asString()).isEqualTo("idempotency_key_missing");
    }

    @Test
    void aFutureDateIsAFieldProblemAndABackDatedExpensePostsOnItsOwnDay() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("category_id", rent);
        body.put("item_id", rentItem);
        body.put("amount_minor", 2_000);
        body.put("business_date", java.time.LocalDate.now().plusDays(3).toString());
        ResponseEntity<JsonNode> future = cb.post("/expenses", body, ALL);
        assertThat(future.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(future.getBody().get("code").asString()).isEqualTo("validation_failed");
        assertThat(future.getBody().get("errors").get(0).get("field").asString())
                .isEqualTo("business_date");
        assertThat(future.getBody().get("errors").get(0).get("code").asString()).isEqualTo("future_date");

        assertThat(cb.expense(hq, rent, rentItem, 3_000, daysAgo(5)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        java.sql.Date entryDate = TestDatabase.owner()
                .sql("""
                        SELECT e.entry_date FROM journal_entries e WHERE e.tenant_id = ? AND e.source_type = 'retail.expense'
                        """)
                .param(t.tenantId())
                .query(java.sql.Date.class)
                .single();
        assertThat(entryDate.toLocalDate()).isEqualTo(daysAgo(5));
    }

    @Test
    void aSalesUserRecordsButCannotVoidAndTheBranchScopeApplies() {
        assertThat(cb.post(
                                "/expenses",
                                Map.of(
                                        "branch_id",
                                        hq,
                                        "category_id",
                                        rent,
                                        "item_id",
                                        rentItem,
                                        "amount_minor",
                                        1_500),
                                CASHBOOK_SALES)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        UUID id = RetailTestSupport.id(cb.expense(hq, rent, rentItem, 1_100, null));
        assertThat(cb.voidIt("/expenses/" + id, "Wrong shop", CASHBOOK_SALES).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // A caller scoped to the second branch cannot see the head office's records nor record in it.
        String second = t.secondBranch().toString();
        assertThat(cb.get("/expenses", CASHBOOK_SALES, second).getBody().get("items"))
                .isEmpty();
        assertThat(cb.api
                        .call(
                                org.springframework.http.HttpMethod.POST,
                                "/expenses/" + id + "/void",
                                Map.of("reason", "Wrong shop"),
                                CASHBOOK_ADMIN,
                                second,
                                Map.of("Idempotency-Key", CashbookTestSupport.key()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
