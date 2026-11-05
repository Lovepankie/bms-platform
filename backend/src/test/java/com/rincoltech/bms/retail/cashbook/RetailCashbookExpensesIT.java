package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHBOOK_ADMIN;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHBOOK_SALES;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.daysAgo;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
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
        for (String kind : new String[] {"owner", "staff", "related_entity"}) {
            ResponseEntity<JsonNode> refused =
                    cb.post("/cash-parties", Map.of("name", "Test Person 01", "kind", kind), CASHBOOK_SALES);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(refused.getBody().get("code").asString()).isEqualTo("permission_denied");
        }
        assertThat(cb.get("/cash-parties?query=vendor", CASHBOOK_SALES)
                        .getBody()
                        .get("items")
                        .size())
                .isEqualTo(1);
        assertThat(cb.get("/expense-categories", "retail.sale.read").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ADR-118 (issue #802). JUSTIFICATION-A3: new integration tests the issue asks for, not new mechanism.
    @Test
    void aShopUserAddsAnItemInPlaceRecordsWithItAndADuplicateNameIsReused() {
        String items = "/expense-categories/" + rent + "/items";
        Map<String, Object> fuel = Map.of("name", "Generator fuel", "reuse_existing", true);
        ResponseEntity<JsonNode> added = cb.post(items, fuel, CASHBOOK_SALES);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID item = RetailTestSupport.id(added);
        assertThat(added.getBody().get("requires_explanation").asBoolean()).isFalse();
        assertThat(cb.expense(hq, rent, item, 7_000, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        int audited = cb.auditPayloads("retail.expense_item.created").size();
        for (String same : new String[] {" generator FUEL ", "Generator  fuel", "generator\tfuel"}) {
            ResponseEntity<JsonNode> again =
                    cb.post(items, Map.of("name", same, "reuse_existing", true), CASHBOOK_SALES);
            assertThat(again.getStatusCode()).as(same).isEqualTo(HttpStatus.OK);
            assertThat(RetailTestSupport.id(again)).as(same).isEqualTo(item);
        }
        assertThat(cb.auditPayloads("retail.expense_item.created")).hasSize(audited);
        // A new name with doubled spaces is stored with one.
        ResponseEntity<JsonNode> spaced =
                cb.post(items, Map.of("name", "  Car   wash ", "reuse_existing", true), CASHBOOK_SALES);
        assertThat(spaced.getBody().get("name").asString()).isEqualTo("Car wash");
        // Without reuse_existing the name is still taken, as in set-up.
        assertThat(cb.post(items, Map.of("name", "Generator fuel"), CASHBOOK_SALES)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("duplicate_item");

        // The explanation flag and a switched off category stay with the owner.
        assertThat(cb.post(items, Map.of("name", "Water", "requires_explanation", true), CASHBOOK_SALES)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        int version =
                categoryVersion(cb.get("/expense-categories", CASHBOOK_SALES).getBody(), other);
        cb.patch("/expense-categories/" + other, Map.of("active", false), CASHBOOK_ADMIN, version);
        assertThat(cb.post("/expense-categories/" + other + "/items", fuel, CASHBOOK_SALES)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aSwitchedOffItemIsNotPickedAgainAndAUserWithoutExpenseRecordCannotAdd() {
        String items = "/expense-categories/" + rent + "/items";
        cb.patch(items + "/" + rentItem, Map.of("active", false), CASHBOOK_ADMIN, 1);
        ResponseEntity<JsonNode> refused =
                cb.post(items, Map.of("name", "Shop rent", "reuse_existing", true), CASHBOOK_SALES);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("item_switched_off");

        ResponseEntity<JsonNode> noRecord =
                cb.post(items, Map.of("name", "Water"), "retail.cashbook.read,retail.till.close");
        assertThat(noRecord.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(noRecord.getBody().get("code").asString()).isEqualTo("permission_denied");
    }

    // Review of #803. JUSTIFICATION-A3: the tests the review asked for (race, tenant, rename), not new mechanism.
    @Test
    void twoTillsAddingTheSameNameAtOnceGetOneItem() throws Exception {
        String items = "/expense-categories/" + rent + "/items";
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 5; round++) {
                Map<String, Object> body = Map.of("name", "Test Item " + round, "reuse_existing", true);
                java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                java.util.List<java.util.concurrent.Future<ResponseEntity<JsonNode>>> calls =
                        new java.util.ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    calls.add(pool.submit(() -> {
                        go.await();
                        return cb.post(items, body, CASHBOOK_SALES);
                    }));
                }
                go.countDown();
                java.util.Set<UUID> ids = new java.util.HashSet<>();
                int created = 0;
                for (var call : calls) {
                    ResponseEntity<JsonNode> r = call.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    assertThat(r.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
                    created += r.getStatusCode() == HttpStatus.CREATED ? 1 : 0;
                    ids.add(RetailTestSupport.id(r));
                }
                assertThat(created).as("round " + round).isEqualTo(1);
                assertThat(ids).as("round " + round).hasSize(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The loser of a race with a writer that skips the category lock: a raw insert of the same name, not yet committed, makes
     * the API's insert wait on the unique index; once it commits, the API answers with that item (reuse_existing) or
     * duplicate_item (set-up), never a 500. JUSTIFICATION-A3: the test the lead's note asks for, not new mechanism.
     */
    @Test
    void theLoserOfARaceOnTheUniqueIndexGetsTheWinningItem() throws Exception {
        String items = "/expense-categories/" + rent + "/items";
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            for (boolean reuse : new boolean[] {true, false}) {
                String name = "Raced item " + reuse;
                UUID winner = UUID.randomUUID();
                try (java.sql.Connection c = TestDatabase.ownerDataSource().getConnection()) {
                    c.setAutoCommit(false);
                    try (var st = c.prepareStatement(
                            "INSERT INTO retail_expense_items (id, tenant_id, category_id, name) VALUES (?, ?, ?, ?)")) {
                        st.setObject(1, winner);
                        st.setObject(2, t.tenantId());
                        st.setObject(3, rent);
                        st.setString(4, name);
                        st.executeUpdate();
                    }
                    Map<String, Object> body =
                            reuse ? Map.of("name", name.toUpperCase(), "reuse_existing", true) : Map.of("name", name);
                    java.util.concurrent.Future<ResponseEntity<JsonNode>> add =
                            pool.submit(() -> cb.post(items, body, CASHBOOK_SALES));
                    Thread.sleep(1_500);
                    assertThat(add.isDone())
                            .as("the insert waits on the unique index")
                            .isFalse();
                    c.commit();

                    ResponseEntity<JsonNode> r = add.get(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (reuse) {
                        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
                        assertThat(RetailTestSupport.id(r)).isEqualTo(winner);
                    } else {
                        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(r.getBody().get("code").asString()).isEqualTo("duplicate_item");
                    }
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anotherBusinessesCategoryIsNotFoundAndNothingIsWritten() {
        TestDatabase.Fixture b = TestDatabase.tenant("cb-exp-b", false, true);
        CashbookTestSupport other = new CashbookTestSupport(http, b);
        UUID theirs = other.categoryWithItem("Their premises", "Their rent", false)[0];
        int before = cb.auditPayloads("retail.expense_item.created").size();
        for (Map<String, Object> body : java.util.List.<Map<String, Object>>of(
                Map.of("name", "Their rent", "reuse_existing", true),
                Map.of("name", "Their rent"),
                Map.of("name", "Something new", "reuse_existing", true))) {
            ResponseEntity<JsonNode> r = cb.post("/expense-categories/" + theirs + "/items", body, CASHBOOK_SALES);
            assertThat(r.getStatusCode()).as(body.toString()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(r.getBody().toString()).doesNotContain("Their rent");
        }
        assertThat(cb.auditPayloads("retail.expense_item.created")).hasSize(before);
        assertThat(other.get("/expense-categories", CASHBOOK_SALES).getBody().toString())
                .doesNotContain("Something new");
    }

    // JUSTIFICATION-A3: issue #810 asks for spaces collapsed on the owner's rename and in the set-up check; no case
    // covers it.
    @Test
    void theOwnersRenameAndTheSetUpCheckCollapseSpacesLikeTheAddInPlace() {
        String items = "/expense-categories/" + rent + "/items";
        UUID wash = RetailTestSupport.id(cb.post(items, Map.of("name", "Test wash"), CASHBOOK_ADMIN));

        // The rename is stored with one space between words and none around it.
        ResponseEntity<JsonNode> renamed =
                cb.patch(items + "/" + wash, Map.of("name", "  Car   wash\t"), CASHBOOK_ADMIN, 1);
        assertThat(renamed.getStatusCode()).as("%s", renamed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().get("name").asString()).isEqualTo("Car wash");
        // Set-up refuses a name that differs only in spaces or capitals, and so does another rename.
        ResponseEntity<JsonNode> again = cb.post(items, Map.of("name", "car  wash"), CASHBOOK_ADMIN);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("duplicate_item");
        UUID other = RetailTestSupport.id(cb.post(items, Map.of("name", "Test polish"), CASHBOOK_ADMIN));
        ResponseEntity<JsonNode> clash = cb.patch(items + "/" + other, Map.of("name", "CAR\twash"), CASHBOOK_ADMIN, 1);
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(clash.getBody().get("code").asString()).isEqualTo("duplicate_item");

        // A name stored before names were tidied (a tab in front, two spaces inside) still counts as the same name.
        UUID legacy = UUID.randomUUID();
        TestDatabase.owner()
                .sql("INSERT INTO retail_expense_items (id, tenant_id, category_id, name) VALUES (?, ?, ?, ?)")
                .params(legacy, t.tenantId(), rent, "\tGenerator  fuel")
                .update();
        ResponseEntity<JsonNode> setUp = cb.post(items, Map.of("name", "Generator fuel"), CASHBOOK_ADMIN);
        assertThat(setUp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(setUp.getBody().get("code").asString()).isEqualTo("duplicate_item");
        ResponseEntity<JsonNode> reused =
                cb.post(items, Map.of("name", "generator fuel", "reuse_existing", true), CASHBOOK_SALES);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(RetailTestSupport.id(reused)).isEqualTo(legacy);
        // The owner tidies it by renaming it to its own name written plainly: allowed, as it is the same item.
        ResponseEntity<JsonNode> tidied =
                cb.patch(items + "/" + legacy, Map.of("name", "Generator fuel"), CASHBOOK_ADMIN, 1);
        assertThat(tidied.getStatusCode()).as("%s", tidied.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(tidied.getBody().get("name").asString()).isEqualTo("Generator fuel");
    }

    // JUSTIFICATION-A3: the review of #861 asks for proof that the server refuses what the screen no longer offers.
    @Test
    void addingAnItemInPlaceNeedsTillCloseAsTheScreenAsks() {
        String items = "/expense-categories/" + rent + "/items";
        ResponseEntity<JsonNode> refused =
                cb.post(items, Map.of("name", "Test no till", "reuse_existing", true), "retail.expense.record");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("permission_denied");
        ResponseEntity<JsonNode> added = cb.post(
                items,
                Map.of("name", "Test no till", "reuse_existing", true),
                "retail.expense.record,retail.till.close");
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void theOwnerStillRenamesAnItemAddedInPlaceAndTheNewNameIsTheOneReused() {
        String items = "/expense-categories/" + rent + "/items";
        UUID item = RetailTestSupport.id(
                cb.post(items, Map.of("name", "Genset fuel", "reuse_existing", true), CASHBOOK_SALES));
        assertThat(cb.patch(items + "/" + item, Map.of("name", "Diesel"), CASHBOOK_ADMIN, 1)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.patch(items + "/" + item, Map.of("name", "Diesel"), CASHBOOK_SALES, 2)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> renamed =
                cb.post(items, Map.of("name", "diesel", "reuse_existing", true), CASHBOOK_SALES);
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(RetailTestSupport.id(renamed)).isEqualTo(item);
        ResponseEntity<JsonNode> oldName =
                cb.post(items, Map.of("name", "Genset fuel", "reuse_existing", true), CASHBOOK_SALES);
        assertThat(oldName.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(RetailTestSupport.id(oldName)).isNotEqualTo(item);
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
        body.put(
                "business_date",
                java.time.LocalDate.now(BusinessClock.DEFAULT_ZONE).plusDays(3).toString());
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

    UUID document(String subjectType) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO documents (id, tenant_id, doc_type, subject_type, subject_id, object_key, content_type,
                            size_bytes, sha256, created_by)
                        VALUES (?, ?, 'upload', ?, gen_random_uuid(), 'tenants/' || ?::text || '/test/' || ?::text,
                            'application/pdf', 10, repeat('a', 64), ?)
                        """)
                // Issue #819: an expense names only a receipt its recorder sent, so the fabricated one is theirs.
                .params(id, t.tenantId(), subjectType, t.tenantId(), id, cb.api.userId())
                .update();
        return id;
    }

    @Test
    void aReceiptMustBeADocumentUploadedForAnExpenseNeverAMembersFile() throws java.io.IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("category_id", rent);
        body.put("item_id", rentItem);
        body.put("amount_minor", 7_000);
        body.put("receipt_document_id", document("lending.member"));

        ResponseEntity<JsonNode> refused = cb.post("/expenses", body, CASHBOOK_ADMIN);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("unknown_document");

        body.put("receipt_document_id", sentReceipt());
        assertThat(cb.post("/expenses", body, CASHBOOK_ADMIN).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // JUSTIFICATION-A3: since #819 an expense names only a receipt whose file is kept, so the test sends a real one.
    /** A receipt photo the recorder sent through the expense form's route (issue #819), its file kept. */
    String sentReceipt() throws java.io.IOException {
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(80, 80, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
        org.springframework.util.MultiValueMap<String, Object> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", new org.springframework.core.io.ByteArrayResource(png.toByteArray()) {
            @Override
            public String getFilename() {
                return "receipt.png";
            }
        });
        org.springframework.http.HttpHeaders h = cb.api.headers("retail.expense.record", "*", Map.of());
        h.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        return http.exchange(
                        RetailTestSupport.BASE + "/expenses/receipt-photos",
                        org.springframework.http.HttpMethod.POST,
                        new org.springframework.http.HttpEntity<>(form, h),
                        JsonNode.class)
                .getBody()
                .get("photo_id")
                .asString();
    }
}
