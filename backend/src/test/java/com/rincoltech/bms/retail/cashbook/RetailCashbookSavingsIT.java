package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.daysAgo;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import java.time.LocalDate;
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
 * FR-RET-18 to FR-RET-20 and the profit gate of ADR-022 decisions 11 to 13. One product costs 1,000
 * and sells at 1,500, so ten units sold on a day make a profit of 5,000 and a suggestion of 2,500.
 * All names and amounts are fabricated.
 */
class RetailCashbookSavingsIT extends IntegrationTest {

    /** A caller who may see profit and record savings, but not overwrite the suggestion. */
    /**
     * These tests save a past day's savings, so their till people also hold the owner's earlier date (ADR-120); without it
     * an earlier date is refused, which RetailEntryDatesIT proves.
     */
    static final String CASHBOOK_SALES = CashbookTestSupport.CASHBOOK_SALES + ",retail.entry.backdate";

    static final String CASHIER = CashbookTestSupport.CASHIER + ",retail.entry.backdate";

    static final String PROFIT_RECORDER = CASHBOOK_SALES + ",retail.profit.read";

    /** The audit reader of ADR-022 decision 12: reads the audit log, never profit. */
    static final String AUDIT_ONLY = "core.audit.read,retail.cashbook.read,retail.till.close";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    UUID hq;
    UUID product;
    LocalDate day;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("cb-sav", false, true);
        cb = new CashbookTestSupport(http, t);
        hq = t.headOffice();
        product = cb.api.product("CABLE-2MM", 1_000, 1_500);
        cb.api.stockUp(hq, product, "100");
        day = daysAgo(2);
        assertThat(cb.sale(hq, "cash", product, "10", day).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    JsonNode suggestion(String perms) {
        return cb.get("/savings/suggestion?branch_id=" + hq + "&date=" + day, perms)
                .getBody();
    }

    Map<String, Object> body(JsonNode suggestion, Object amount, String reason) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", hq);
        b.put("business_date", day.toString());
        b.put("suggestion_token", suggestion.get("suggestion_token").asString());
        if (amount != null) {
            b.put("amount_minor", amount);
        }
        if (reason != null) {
            b.put("overwrite_reason", reason);
        }
        return b;
    }

    @Test
    void theSuggestionIsHalfTheDaysProfitAndTheDefaultPostsAReserveTransferNotAnExpense() {
        JsonNode s = suggestion(PROFIT_RECORDER);
        assertThat(s.get("suggested_minor").asLong()).isEqualTo(2_500);
        assertThat(s.get("daily_profit_minor").asLong()).isEqualTo(5_000);
        assertThat(s.get("total_sold_minor").asLong()).isEqualTo(15_000);
        // Absent, not null, when the day has no savings (#536): the form reads absence as "none yet".
        assertThat(s.has("existing_id")).isFalse();

        ResponseEntity<JsonNode> r = cb.post("/savings", body(s, null, null), PROFIT_RECORDER);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("amount_minor").asLong()).isEqualTo(2_500);
        assertThat(r.getBody().get("overwritten").asBoolean()).isFalse();
        assertThat(cb.accountMovement("savings_reserve", hq)).isEqualTo(2_500);
        // Cash after the 15,000 sale: down by the 2,500 set aside. Profit accounts are untouched by it.
        assertThat(cb.accountMovement("cash_on_hand", hq)).isEqualTo(15_000 - 2_500);
        assertThat(cb.accountMovement("operating_expenses", hq)).isZero();
        assertThat(suggestion(PROFIT_RECORDER).get("existing_id").asString())
                .isEqualTo(r.getBody().get("id").asString());
    }

    @Test
    void aSecondActiveRecordIsRefusedAndAVoidFreesTheDay() {
        JsonNode s = suggestion(PROFIT_RECORDER);
        UUID first = RetailTestSupport.id(cb.post("/savings", body(s, null, null), PROFIT_RECORDER));

        ResponseEntity<JsonNode> second = cb.post("/savings", body(s, null, null), PROFIT_RECORDER);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("code").asString()).isEqualTo("savings_exists");

        assertThat(cb.voidIt("/savings/" + first, "Wrong day", ALL).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.accountMovement("savings_reserve", hq)).isZero();
        assertThat(cb.post("/savings", body(suggestion(PROFIT_RECORDER), null, null), PROFIT_RECORDER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // Voided rows are listed only when asked for.
        assertThat(cb.get("/savings", ALL).getBody().get("items").size()).isEqualTo(1);
        assertThat(cb.get("/savings?include_voided=true", ALL)
                        .getBody()
                        .get("items")
                        .size())
                .isEqualTo(2);
    }

    @Test
    void theRateIsASettingNotCode() {
        cb.setting("retail.cashbook.savings_rate_bp", 3_000);
        assertThat(suggestion(PROFIT_RECORDER).get("suggested_minor").asLong()).isEqualTo(1_500);
    }

    @Test
    void anOverwriteNeedsTheOverwritePermissionButTheReasonIsOptional() {
        JsonNode s = suggestion(PROFIT_RECORDER);

        ResponseEntity<JsonNode> forbidden = cb.post("/savings", body(s, 4_000, "Owner asked"), PROFIT_RECORDER);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The equal amount is the default, not an overwrite, so it is accepted without the permission.
        ResponseEntity<JsonNode> equal = cb.post("/savings", body(s, 2_500, null), PROFIT_RECORDER);
        assertThat(equal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(equal.getBody().get("overwritten").asBoolean()).isFalse();
        cb.voidIt("/savings/" + RetailTestSupport.id(equal), "Redo", ALL);

        // #538: no reason at all is accepted, and the row keeps none (V95 relaxed the check).
        ResponseEntity<JsonNode> noReason = cb.post("/savings", body(s, 4_000, null), ALL);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(noReason.getBody().get("overwritten").asBoolean()).isTrue();
        assertThat(noReason.getBody().get("amount_minor").asLong()).isEqualTo(4_000);
        assertThat(reasonOf(RetailTestSupport.id(noReason))).isNull();
        cb.voidIt("/savings/" + RetailTestSupport.id(noReason), "Redo", ALL);

        // A blank reason counts as none; a short pick such as "Debt" is kept as given.
        ResponseEntity<JsonNode> blank = cb.post("/savings", body(s, 3_000, "   "), ALL);
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reasonOf(RetailTestSupport.id(blank))).isNull();
        cb.voidIt("/savings/" + RetailTestSupport.id(blank), "Redo", ALL);

        ResponseEntity<JsonNode> picked = cb.post("/savings", body(s, 4_000, "Debt"), ALL);
        assertThat(picked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reasonOf(RetailTestSupport.id(picked))).isEqualTo("Debt");
        assertThat(cb.accountMovement("savings_reserve", hq)).isEqualTo(4_000);
    }

    String reasonOf(UUID id) {
        return TestDatabase.owner()
                .sql("SELECT overwrite_reason FROM retail_daily_savings WHERE id = ?")
                .param(id)
                .query((rs, n) -> rs.getString(1))
                .list()
                .getFirst();
    }

    // The V95 check itself (a reason, when present, is not blank) is asserted by name on an insert in
    // RetailCashbookGuardsIT: an UPDATE here would be refused by the update guard trigger before any check ran.

    // ------------------------------------------------------------ the cap: profit less expenses (ADR-080)

    UUID[] rent;

    UUID expense(long amount) {
        if (rent == null) {
            rent = cb.categoryWithItem("Premises", "Rent", false);
        }
        ResponseEntity<JsonNode> r = cb.expense(hq, rent[0], rent[1], amount, day);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    @Test
    void theSuggestionShowsProfitExpensesAndAvailable() {
        JsonNode s = suggestion(PROFIT_RECORDER);
        assertThat(s.get("day_expenses_minor").asLong()).isZero();
        assertThat(s.get("available_minor").asLong()).isEqualTo(5_000);

        JsonNode hidden = suggestion(CASHIER);
        assertThat(hidden.has("day_expenses_minor")).isFalse();
        assertThat(hidden.has("available_minor")).isFalse();
    }

    @Test
    void theServerRefusesAnAmountAboveTheAvailableInPlainWords() {
        ResponseEntity<JsonNode> above = cb.post("/savings", body(suggestion(ALL), 5_001, "Owner asked"), ALL);

        assertThat(above.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(above.getBody().get("code").asString()).isEqualTo("savings_above_available");
        assertThat(above.getBody().get("detail").asString())
                .isEqualTo("You can save at most UGX 5,000 for " + words(day)
                        + " (profit UGX 5,000 minus expenses UGX 0).");
        assertThat(above.getBody().get("errors").get(0).get("field").asString()).isEqualTo("amount_minor");
        assertThat(above.getBody().get("available_minor").asLong()).isEqualTo(5_000);
        assertThat(cb.accountMovement("savings_reserve", hq)).isZero();
        assertThat(cb.get("/savings", ALL).getBody().get("items")).isEmpty();
    }

    @Test
    void theServerAcceptsAnAmountEqualToTheAvailable() {
        ResponseEntity<JsonNode> at = cb.post("/savings", body(suggestion(ALL), 5_000, null), ALL);
        assertThat(at.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(at.getBody().get("amount_minor").asLong()).isEqualTo(5_000);
        assertThat(cb.accountMovement("savings_reserve", hq)).isEqualTo(5_000);
    }

    @Test
    void theDaysExpensesReduceTheAvailableAndTheSuggestion() {
        expense(3_000);
        // A voided expense no longer counts.
        UUID voided = expense(500);
        assertThat(cb.voidIt("/expenses/" + voided, "Typed twice", ALL).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode s = suggestion(PROFIT_RECORDER);
        assertThat(s.get("daily_profit_minor").asLong()).isEqualTo(5_000);
        assertThat(s.get("day_expenses_minor").asLong()).isEqualTo(3_000);
        assertThat(s.get("available_minor").asLong()).isEqualTo(2_000);
        assertThat(s.get("suggested_minor").asLong()).isEqualTo(1_000);

        ResponseEntity<JsonNode> above = cb.post("/savings", body(s, 2_001, null), ALL);
        assertThat(above.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(above.getBody().get("detail").asString())
                .isEqualTo("You can save at most UGX 2,000 for " + words(day)
                        + " (profit UGX 5,000 minus expenses UGX 3,000).");

        ResponseEntity<JsonNode> ok = cb.post("/savings", body(s, null, null), PROFIT_RECORDER);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody().get("amount_minor").asLong()).isEqualTo(1_000);
    }

    @Test
    void anExpenseAfterTheFormWasShownChangesTheTokenAndWritesNothing() {
        JsonNode s = suggestion(PROFIT_RECORDER);
        expense(3_000);
        ResponseEntity<JsonNode> stale = cb.post("/savings", body(s, null, null), PROFIT_RECORDER);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("suggestion_changed");
        assertThat(stale.getBody().get("suggested_minor").asLong()).isEqualTo(1_000);
    }

    /**
     * The race of the review on #540: an expense committing while a savings create runs. The expense's transaction holds
     * the branch-day lock, so the savings create waits for it and then reads the expense: it is refused, never saved above
     * the day's available amount.
     */
    @Test
    void aSavingsCreateWaitsForAnExpenseInFlightOnTheSameDayAndThenSeesIt() throws Exception {
        UUID[] item = cb.categoryWithItem("Premises", "Rent", false);
        UUID recorder = seededUser(item);
        JsonNode s = suggestion(ALL);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.sql.Connection c = TestDatabase.ownerDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                st.setString(1, t.tenantId().toString());
                st.execute();
            }
            // The same lock ExpenseService takes, then an expense of 3,000 not yet committed.
            try (var st =
                    c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(current_setting('app.tenant_id')"
                            + " || ':retail.cash_day:' || CAST(? AS text) || ':' || CAST(? AS text), 0))")) {
                st.setObject(1, hq);
                st.setDate(2, java.sql.Date.valueOf(day));
                st.execute();
            }
            try (var st = c.prepareStatement("""
                    INSERT INTO retail_expenses (id, tenant_id, branch_id, business_date, category_id, item_id, category_name,
                                                 item_name, amount_minor, currency, occurred_at, recorded_by)
                    VALUES (?, ?, ?, ?, ?, ?, 'Premises', 'Rent', 3000, 'UGX', now(), ?)
                    """)) {
                st.setObject(1, UUID.randomUUID());
                st.setObject(2, t.tenantId());
                st.setObject(3, hq);
                st.setDate(4, java.sql.Date.valueOf(day));
                st.setObject(5, item[0]);
                st.setObject(6, item[1]);
                st.setObject(7, recorder);
                st.executeUpdate();
            }
            java.util.concurrent.Future<ResponseEntity<JsonNode>> save =
                    pool.submit(() -> cb.post("/savings", body(s, 5_000, null), ALL));
            Thread.sleep(1_500);
            assertThat(save.isDone())
                    .as("the savings create waits for the expense's lock")
                    .isFalse();
            c.commit();

            ResponseEntity<JsonNode> r = save.get(30, java.util.concurrent.TimeUnit.SECONDS);
            // The expense changed the suggestion, so the stale token is refused first; either way nothing is saved
            // above 2,000.
            assertThat(r.getStatusCode()).isIn(HttpStatus.CONFLICT, HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(cb.get("/savings", ALL).getBody().get("items")).isEmpty();
            assertThat(suggestion(ALL).get("available_minor").asLong()).isEqualTo(2_000);
        } finally {
            pool.shutdownNow();
        }
    }

    /** A real recorder: the one the API stamped on an expense on another day, so the raw insert stays valid. */
    UUID seededUser(UUID[] item) {
        UUID id = RetailTestSupport.id(cb.expense(hq, item[0], item[1], 100, daysAgo(6)));
        return TestDatabase.owner()
                .sql("SELECT recorded_by FROM retail_expenses WHERE id = ?")
                .param(id)
                .query(UUID.class)
                .single();
    }

    /**
     * The same race through the API, looped: a savings save of the whole available amount and an expense of 1 for the same
     * branch and day sent at once. An expense of 1 leaves the suggestion (and so the token) as it was, so a save that comes
     * second meets the cap (422), not the token (409). The lock orders them, and both take their clock reading
     * ({@code occurred_at}) after it, so the later row is the one that waited. Savings never exceed the profit less the
     * expenses that committed before them; an expense that came after is a later change (ADR-080 decision 3).
     */
    @Test
    void racingASavingsSaveAgainstAnExpenseNeverSavesAboveWhatTheDayHadAtCommit() throws Exception {
        rent = cb.categoryWithItem("Premises", "Rent", false);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                JsonNode s = suggestion(ALL);
                long available = s.get("available_minor").asLong();
                java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                var save = pool.submit(() -> {
                    go.await();
                    return cb.post("/savings", body(s, available, null), ALL);
                });
                var spend = pool.submit(() -> {
                    go.await();
                    return cb.expense(hq, rent[0], rent[1], 1, day);
                });
                go.countDown();
                ResponseEntity<JsonNode> saved = save.get(60, java.util.concurrent.TimeUnit.SECONDS);
                ResponseEntity<JsonNode> spent = spend.get(60, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(spent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(saved.getStatusCode())
                        .as("round %d", round)
                        .isIn(HttpStatus.CREATED, HttpStatus.UNPROCESSABLE_CONTENT);
                if (saved.getStatusCode() == HttpStatus.UNPROCESSABLE_CONTENT) {
                    assertThat(saved.getBody().get("code").asString()).isEqualTo("savings_above_available");
                    assertThat(saved.getBody().get("available_minor").asLong()).isEqualTo(available - 1);
                }
                if (saved.getStatusCode() == HttpStatus.CREATED) {
                    UUID id = RetailTestSupport.id(saved);
                    long before = TestDatabase.owner()
                            .sql("""
                                    SELECT coalesce(sum(e.amount_minor), 0) FROM retail_expenses e, retail_daily_savings v
                                     WHERE v.id = ? AND e.tenant_id = v.tenant_id AND e.branch_id = v.branch_id
                                       AND e.business_date = v.business_date AND e.voided_at IS NULL
                                       AND e.occurred_at <= v.occurred_at
                                    """)
                            .param(id)
                            .query(Long.class)
                            .single();
                    assertThat(saved.getBody().get("amount_minor").asLong())
                            .as("round %d", round)
                            .isLessThanOrEqualTo(Math.max(0, 5_000 - before));
                    cb.voidIt("/savings/" + id, "Next round", ALL);
                }
                assertThat(cb.voidIt("/expenses/" + RetailTestSupport.id(spent), "Next round", ALL)
                                .getStatusCode())
                        .isEqualTo(HttpStatus.OK);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anExpenseAfterTheSaveLeavesTheSavingsAndTheSuggestionSaysTheDayNowHasLess() {
        assertThat(cb.post("/savings", body(suggestion(ALL), 5_000, null), ALL).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        expense(3_000);

        JsonNode s = suggestion(PROFIT_RECORDER);
        assertThat(s.get("existing_amount_minor").asLong()).isEqualTo(5_000);
        assertThat(s.get("available_minor").asLong()).isEqualTo(2_000);
        assertThat(cb.get("/savings", ALL)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("voided")
                        .asBoolean())
                .isFalse();
        assertThat(suggestion(CASHIER).has("existing_amount_minor")).isFalse();
    }

    @Test
    void expensesAboveTheProfitLeaveZeroAvailableNeverLess() {
        expense(7_000);

        JsonNode s = suggestion(PROFIT_RECORDER);
        assertThat(s.get("available_minor").asLong()).isZero();
        assertThat(s.get("suggested_minor").asLong()).isZero();

        ResponseEntity<JsonNode> one = cb.post("/savings", body(s, 1, null), ALL);
        assertThat(one.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(one.getBody().get("detail").asString())
                .isEqualTo("You can save at most UGX 0 for " + words(day)
                        + " (profit UGX 5,000 minus expenses UGX 7,000).");

        ResponseEntity<JsonNode> zero = cb.post("/savings", body(s, null, null), PROFIT_RECORDER);
        assertThat(zero.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(zero.getBody().get("amount_minor").asLong()).isZero();
        assertThat(cb.accountMovement("savings_reserve", hq)).isZero();
    }

    @Test
    void todayIsSaidAsToday() {
        // No date: the server's own business date (support.today()), so the test never disagrees with it at midnight.
        JsonNode s = cb.get("/savings/suggestion?branch_id=" + hq, ALL).getBody();
        Map<String, Object> b = body(s, 1, null);
        b.put("business_date", s.get("business_date").asString());
        ResponseEntity<JsonNode> r = cb.post("/savings", b, ALL);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("savings_above_available");
        assertThat(r.getBody().get("detail").asString()).startsWith("You can save at most UGX 0 today (profit");
    }

    static String words(LocalDate d) {
        return d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH));
    }

    @Test
    void anAmountOfZeroIsValidAndPostsNothing() {
        JsonNode s = suggestion(ALL);
        ResponseEntity<JsonNode> r = cb.post("/savings", body(s, 0, "Nothing spare today"), ALL);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("amount_minor").asLong()).isZero();
        assertThat(cb.accountMovement("savings_reserve", hq)).isZero();
        Long entry = TestDatabase.owner()
                .sql("SELECT count(journal_entry_id) FROM retail_daily_savings WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        assertThat(entry).isZero();
    }

    @Test
    void aCallerWithoutProfitReadSeesNoAmountSuggestionOrOverwriteAnywhere() {
        JsonNode s = suggestion(CASHIER);
        assertThat(s.has("suggestion_token")).isTrue();
        assertThat(s.has("suggested_minor")).isFalse();
        assertThat(s.has("daily_profit_minor")).isFalse();
        assertThat(s.get("total_sold_minor").asLong()).isEqualTo(15_000);

        ResponseEntity<JsonNode> r = cb.post("/savings", body(s, null, null), CASHIER);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().has("amount_minor")).isFalse();
        assertThat(r.getBody().has("overwritten")).isFalse();
        assertThat(r.getBody().has("suggested_minor")).isFalse();
        assertThat(cb.accountMovement("savings_reserve", hq)).isEqualTo(2_500);

        JsonNode row = cb.get("/savings", CASHIER).getBody().get("items").get(0);
        assertThat(row.has("amount_minor")).isFalse();
        assertThat(row.has("overwritten")).isFalse();
        assertThat(row.has("suggested_minor")).isFalse();
        assertThat(row.get("total_sold_minor").asLong()).isEqualTo(15_000);
        JsonNode report = cb.get("/reports/cash/savings?from=" + daysAgo(5) + "&to=" + daysAgo(0), CASHIER)
                .getBody()
                .get("items")
                .get(0);
        assertThat(report.has("amount_minor")).isFalse();
        assertThat(report.has("suggested_minor")).isFalse();
        assertThat(report.has("daily_profit_minor")).isFalse();
        // The same list for a profit reader shows them.
        assertThat(cb.get("/savings", PROFIT_RECORDER)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("amount_minor")
                        .asLong())
                .isEqualTo(2_500);
    }

    // JUSTIFICATION-A3: new on and off tests for issue #768; a test per case, no new mechanism.
    // JUSTIFICATION-A3: the review asks that only the Owner sets it, so the switch is sent as a real Owner, or as an
    // Admin to be refused.
    UUID owner;

    /** The Owner of the business, made on first use (ADR-062): only the Owner sets the salespeople savings setting (ADR-115). */
    UUID owner() {
        if (owner == null) {
            owner = Api.staff(t, Api.email("sav-owner"), new Api.Role("tenant_admin", null));
            TestDatabase.owner()
                    .sql("UPDATE users SET is_owner = true WHERE id = ?")
                    .param(owner)
                    .update();
        }
        return owner;
    }

    /** Business set-up's switch "Salespeople can see and change the savings amount" (issue #768, ADR-115), by the Owner. */
    JsonNode salespeopleSeeSavingsAmount(boolean on) {
        ResponseEntity<JsonNode> r = patchSalespeopleSetting(owner(), on);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    ResponseEntity<JsonNode> patchSalespeopleSetting(UUID by, boolean on) {
        org.springframework.http.HttpHeaders h =
                cb.api.headers("core.settings.manage,core.settings.read", "*", Map.of());
        h.set("X-Dev-User-Id", by.toString());
        int version = http.exchange(
                        "/api/v1/settings",
                        org.springframework.http.HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(h),
                        JsonNode.class)
                .getBody()
                .get("version")
                .asInt();
        h.add("If-Match", "\"" + version + "\"");
        return http.exchange(
                "/api/v1/settings",
                org.springframework.http.HttpMethod.PATCH,
                new org.springframework.http.HttpEntity<>(Map.of("salespeople_see_savings_amount", on), h),
                JsonNode.class);
    }

    @Test
    void onlyTheOwnerTurnsTheSalespeopleSettingOnAnAdminWithSettingsManageIsRefused() {
        UUID admin = Api.staff(t, Api.email("sav-admin"), new Api.Role("tenant_admin", null));
        owner();
        ResponseEntity<JsonNode> refused = patchSalespeopleSetting(admin, true);
        assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("owner_only");
        assertThat(patchSalespeopleSetting(admin, false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(suggestion(CASHIER).has("suggested_minor")).isFalse();

        salespeopleSeeSavingsAmount(true);
        // Read back after the save, not only from the PATCH answer: the key is stored.
        org.springframework.http.HttpHeaders h = cb.api.headers("core.settings.read", "*", Map.of());
        assertThat(http.exchange(
                                "/api/v1/settings",
                                org.springframework.http.HttpMethod.GET,
                                new org.springframework.http.HttpEntity<>(h),
                                JsonNode.class)
                        .getBody()
                        .get("salespeople_see_savings_amount")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void frRet18TheSalespeopleSettingIsOffByDefaultAndOffKeepsTheAmountHidden() {
        org.springframework.http.HttpHeaders h = cb.api.headers("core.settings.read", "*", Map.of());
        JsonNode settings = http.exchange(
                        "/api/v1/settings",
                        org.springframework.http.HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(h),
                        JsonNode.class)
                .getBody();
        assertThat(settings.get("salespeople_see_savings_amount").asBoolean()).isFalse();

        // Turned on and off again: as if it had never been on.
        salespeopleSeeSavingsAmount(true);
        assertThat(salespeopleSeeSavingsAmount(false)
                        .get("salespeople_see_savings_amount")
                        .asBoolean())
                .isFalse();
        JsonNode s = suggestion(CASHIER);
        assertThat(s.has("suggested_minor")).isFalse();
        assertThat(s.has("available_minor")).isFalse();
        ResponseEntity<JsonNode> typed = cb.post("/savings", body(s, 1_000, null), CASHIER);
        assertThat(typed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(typed.getBody().get("code").asString()).isEqualTo("amount_requires_profit_access");
        assertThat(cb.post("/savings", body(s, null, null), CASHIER).getBody().has("amount_minor"))
                .isFalse();
        assertThat(cb.get("/savings", CASHIER).getBody().get("items").get(0).has("amount_minor"))
                .isFalse();
    }

    @Test
    void frRet19WithTheSettingOnASalespersonSeesAndChangesTheAmountButNeverTheProfitOrExpenses() {
        assertThat(salespeopleSeeSavingsAmount(true)
                        .get("salespeople_see_savings_amount")
                        .asBoolean())
                .isTrue();
        assertThat(cb.auditPayloads("core.settings.updated"))
                .anySatisfy(p -> assertThat(p).contains("salespeople_see_savings_amount"));
        expense(1_000);

        JsonNode s = suggestion(CASHIER);
        assertThat(s.get("suggested_minor").asLong()).isEqualTo(2_000);
        // Only what the request needs: never the cap, the profit or the expenses.
        assertThat(s.has("available_minor")).isFalse();
        assertThat(s.has("daily_profit_minor")).isFalse();
        assertThat(s.has("day_expenses_minor")).isFalse();

        // The cap holds, in plain words that state neither the cap, the profit nor the expenses.
        ResponseEntity<JsonNode> above = cb.post("/savings", body(s, 4_001, null), CASHIER);
        assertThat(above.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(above.getBody().get("code").asString()).isEqualTo("savings_above_available");
        assertThat(above.getBody().get("detail").asString())
                .isEqualTo(
                        "That amount is more than this shop can save on " + words(day) + ". Enter a smaller amount.");
        assertThat(above.getBody().toString()).doesNotContain("4,000").doesNotContain("4000");
        assertThat(above.getBody().has("available_minor")).isFalse();
        assertThat(above.getBody().has("daily_profit_minor")).isFalse();
        assertThat(above.getBody().has("day_expenses_minor")).isFalse();

        // A changed amount needs no overwrite permission under the setting, and is echoed, listed and audited.
        ResponseEntity<JsonNode> changed = cb.post("/savings", body(s, 1_500, "Slow day"), CASHIER);
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(changed.getBody().get("amount_minor").asLong()).isEqualTo(1_500);
        assertThat(changed.getBody().get("overwritten").asBoolean()).isTrue();
        assertThat(changed.getBody().has("suggested_minor")).isFalse();
        assertThat(cb.accountMovement("savings_reserve", hq)).isEqualTo(1_500);
        JsonNode row = cb.get("/savings", CASHIER).getBody().get("items").get(0);
        assertThat(row.get("amount_minor").asLong()).isEqualTo(1_500);
        assertThat(row.has("suggested_minor")).isFalse();
        assertThat(suggestion(CASHIER).get("existing_amount_minor").asLong()).isEqualTo(1_500);

        String payload = cb.auditPayloads("retail.savings.created").getFirst();
        assertThat(payload)
                .contains("\"overwritten\": true")
                .contains("\"salespeople_setting\": true")
                .contains("Slow day");
        assertThat(payload).doesNotContain("1500").doesNotContain("2000").doesNotContain("4000");
    }

    // JUSTIFICATION-A3: the review's third must-fix asks for tests that older records and cash book readers keep
    // amounts hidden.
    /** The row of the savings list with that id, read with the given permissions, voided rows included. */
    JsonNode listed(UUID id, String perms) {
        for (JsonNode row :
                cb.get("/savings?include_voided=true", perms).getBody().get("items")) {
            if (row.get("id").asString().equals(id.toString())) {
                return row;
            }
        }
        throw new AssertionError("not listed: " + id);
    }

    /** The default savings of a day other than the fixture's, recorded with the given permissions. */
    ResponseEntity<JsonNode> recordOn(LocalDate date, String perms) {
        JsonNode s = cb.get("/savings/suggestion?branch_id=" + hq + "&date=" + date, perms)
                .getBody();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", hq);
        b.put("business_date", date.toString());
        b.put("suggestion_token", s.get("suggestion_token").asString());
        return cb.post("/savings", b, perms);
    }

    // JUSTIFICATION-A3: issue #827 opens every month's amounts while the setting is on; the test proves it on fixed
    // months.
    @Test
    void withTheSettingOnEveryMonthsRecordsShowAmountsButNeverToAReaderOfTheCashBookOnly() {
        LocalDate today = LocalDate.now(BusinessClock.DEFAULT_ZONE);
        LocalDate lastMonth = today.withDayOfMonth(1).minusDays(1);
        // Made before the Owner turned the setting on: one last month and one this month.
        UUID earlier = RetailTestSupport.id(recordOn(lastMonth, PROFIT_RECORDER));
        UUID thisMonths = RetailTestSupport.id(recordOn(today, PROFIT_RECORDER));
        assertThat(listed(earlier, CASHIER).has("amount_minor")).isFalse();
        salespeopleSeeSavingsAmount(true);

        // Issue #827, as the owner's old app did: every month's record shows, whenever it was made.
        for (UUID id : java.util.List.of(earlier, thisMonths)) {
            assertThat(listed(id, CASHIER).has("amount_minor")).isTrue();
            assertThat(listed(id, CASHIER).has("overwritten")).isTrue();
        }
        JsonNode s = cb.get("/savings/suggestion?branch_id=" + hq + "&date=" + lastMonth, CASHIER)
                .getBody();
        assertThat(s.get("existing_id").asString()).isEqualTo(earlier.toString());
        assertThat(s.has("existing_amount_minor")).isTrue();

        // A reader of the cash book who neither records savings nor sells sees no amount, on any record.
        for (UUID id : java.util.List.of(earlier, thisMonths)) {
            assertThat(listed(id, AUDIT_ONLY).has("amount_minor")).isFalse();
            assertThat(listed(id, AUDIT_ONLY).has("overwritten")).isFalse();
        }

        // Off again: every amount is hidden again. On once more: every month's show again.
        salespeopleSeeSavingsAmount(false);
        assertThat(listed(earlier, CASHIER).has("amount_minor")).isFalse();
        assertThat(listed(thisMonths, CASHIER).has("amount_minor")).isFalse();
        salespeopleSeeSavingsAmount(true);
        assertThat(listed(earlier, CASHIER).has("amount_minor")).isTrue();
        assertThat(listed(thisMonths, CASHIER).has("amount_minor")).isTrue();
        // A caller with profit read sees every amount, as before.
        assertThat(listed(earlier, PROFIT_RECORDER).has("amount_minor")).isTrue();
    }

    // JUSTIFICATION-A3: the review's fourth must-fix asks for tests that no caller gets more by holding less.
    @Test
    void withTheSettingOnChangingTheSuggestionNeedsOnlyRecordButReplacingASavedAmountNeedsOverwriteForEveryone() {
        // Off: a caller with profit read but no overwrite may not change it, as before.
        assertThat(suggestion(PROFIT_RECORDER).get("may_change_amount").asBoolean())
                .isFalse();
        assertThat(suggestion(CASHIER).get("may_change_amount").asBoolean()).isFalse();
        assertThat(suggestion(ALL).get("may_change_amount").asBoolean()).isTrue();

        // On: before the day is saved, a supervisor with profit read changes it just as a cashier may.
        salespeopleSeeSavingsAmount(true);
        assertThat(suggestion(PROFIT_RECORDER).get("may_change_amount").asBoolean())
                .isTrue();
        assertThat(suggestion(CASHIER).get("may_change_amount").asBoolean()).isTrue();
        ResponseEntity<JsonNode> bySupervisor =
                cb.post("/savings", body(suggestion(PROFIT_RECORDER), 1_000, null), PROFIT_RECORDER);
        assertThat(bySupervisor.getStatusCode())
                .as("%s", bySupervisor.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(bySupervisor.getBody().get("overwritten").asBoolean()).isTrue();

        // Replacing the saved amount (void, then a different amount) needs retail.savings.overwrite, for everyone.
        assertThat(cb.voidIt("/savings/" + RetailTestSupport.id(bySupervisor), "Wrong amount", ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(suggestion(CASHIER).get("may_change_amount").asBoolean()).isFalse();
        assertThat(suggestion(PROFIT_RECORDER).get("may_change_amount").asBoolean())
                .isFalse();
        // The cap is 5,000: refused anyway, an amount below it and one above it get the same 403 (the cap is checked
        // last).
        for (long amount : new long[] {1_500, 4_999, 5_001, 9_000}) {
            ResponseEntity<JsonNode> refused = cb.post("/savings", body(suggestion(CASHIER), amount, null), CASHIER);
            assertThat(refused.getStatusCode()).as("%s", amount).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(refused.getBody().get("code").asString()).isEqualTo("permission_denied");
        }
        assertThat(cb.post("/savings", body(suggestion(PROFIT_RECORDER), 1_500, null), PROFIT_RECORDER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // The suggestion itself is not a change, and a holder of the overwrite permission may replace it.
        assertThat(suggestion(ALL).get("may_change_amount").asBoolean()).isTrue();
        ResponseEntity<JsonNode> byAdmin = cb.post("/savings", body(suggestion(ALL), 1_500, "Owner asked"), ALL);
        assertThat(byAdmin.getStatusCode()).as("%s", byAdmin.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(cb.voidIt("/savings/" + RetailTestSupport.id(byAdmin), "Redo", ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.post("/savings", body(suggestion(CASHIER), null, null), CASHIER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // With an active record, every amount gets the same 409, never the cap's 422.
        for (long amount : new long[] {1_000, 4_999, 5_001}) {
            ResponseEntity<JsonNode> refused = cb.post("/savings", body(suggestion(ALL), amount, null), ALL);
            assertThat(refused.getStatusCode()).as("%s", amount).isEqualTo(HttpStatus.CONFLICT);
            assertThat(refused.getBody().get("code").asString()).isEqualTo("savings_exists");
        }
    }

    @Test
    void theSuggestionChangedAnswerCarriesTheNewSuggestionForASalespersonOnlyWithTheSettingOn() {
        JsonNode stale = suggestion(CASHIER);
        assertThat(cb.sale(hq, "cash", product, "2", day).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> off = cb.post("/savings", body(stale, null, null), CASHIER);
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(off.getBody().get("code").asString()).isEqualTo("suggestion_changed");
        assertThat(off.getBody().has("suggested_minor")).isFalse();

        salespeopleSeeSavingsAmount(true);
        assertThat(cb.sale(hq, "cash", product, "2", day).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> on = cb.post("/savings", body(stale, null, null), CASHIER);
        assertThat(on.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Ten units, then two and two more: the suggestion grows to 3,500.
        assertThat(on.getBody().get("suggested_minor").asLong()).isEqualTo(3_500);
        assertThat(on.getBody().has("available_minor")).isFalse();
        assertThat(on.getBody().has("daily_profit_minor")).isFalse();
    }

    @Test
    void withTheSettingOnASalespersonMaySaveTheSuggestionAndItIsNotAnOverwrite() {
        salespeopleSeeSavingsAmount(true);
        ResponseEntity<JsonNode> r = cb.post("/savings", body(suggestion(CASHIER), null, null), CASHIER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("amount_minor").asLong()).isEqualTo(2_500);
        assertThat(r.getBody().get("overwritten").asBoolean()).isFalse();
    }

    @Test
    void aTypedAmountWithoutProfitReadIsTheSameRefusalForEveryValueSoNothingCanBeProbed() {
        JsonNode s = suggestion(CASHIER);
        ResponseEntity<JsonNode> equalToTheDefault = cb.post("/savings", body(s, 2_500, null), CASHIER);
        ResponseEntity<JsonNode> different = cb.post("/savings", body(s, 1, null), CASHIER);
        ResponseEntity<JsonNode> withReason = cb.post("/savings", body(s, null, "A good reason"), CASHIER);

        for (ResponseEntity<JsonNode> r : java.util.List.of(equalToTheDefault, different, withReason)) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(r.getBody().get("code").asString()).isEqualTo("amount_requires_profit_access");
        }
        assertThat(cb.accountMovement("savings_reserve", hq)).isZero();
        assertThat(cb.get("/savings", ALL).getBody().get("items")).isEmpty();
    }

    @Test
    void aSaleBetweenTheFormAndTheSaveGivesANewTokenAndWritesNothing() {
        JsonNode s = suggestion(PROFIT_RECORDER);
        assertThat(cb.sale(hq, "cash", product, "2", day).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> stale = cb.post("/savings", body(s, null, null), PROFIT_RECORDER);

        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("suggestion_changed");
        assertThat(stale.getBody().get("suggestion_token").asString())
                .isNotEqualTo(s.get("suggestion_token").asString());
        assertThat(stale.getBody().get("suggested_minor").asLong()).isEqualTo(3_000);
        assertThat(cb.accountMovement("savings_reserve", hq)).isZero();
        // A stale token with a typed amount never records an overwrite either.
        assertThat(cb.post("/savings", body(s, 9_000, "Owner asked for it"), ALL)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("suggestion_changed");
        // A caller without profit read gets the new token but not the figure.
        JsonNode cashierToken = suggestion(CASHIER);
        assertThat(cb.sale(hq, "cash", product, "1", day).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> staleCashier = cb.post("/savings", body(cashierToken, null, null), CASHIER);
        assertThat(staleCashier.getBody().get("code").asString()).isEqualTo("suggestion_changed");
        assertThat(staleCashier.getBody().has("suggested_minor")).isFalse();
        assertThat(staleCashier.getBody().has("suggestion_token")).isTrue();
        // Retrying with the new token records the new default.
        ResponseEntity<JsonNode> retry =
                cb.post("/savings", body(suggestion(PROFIT_RECORDER), null, null), PROFIT_RECORDER);
        assertThat(retry.getBody().get("amount_minor").asLong()).isEqualTo(3_250);
    }

    @Test
    void aMissingTokenIsRefused() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", hq);
        b.put("business_date", day.toString());
        ResponseEntity<JsonNode> r = cb.post("/savings", b, PROFIT_RECORDER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("suggestion_token_required");
    }

    @Test
    void theAuditRowCarriesFlagsOnlyNeverTheAmountTheSuggestionOrTheProfit() {
        UUID first = RetailTestSupport.id(cb.post("/savings", body(suggestion(ALL), null, null), ALL));
        cb.voidIt("/savings/" + first, "Redo with a change", ALL);
        ResponseEntity<JsonNode> ow = cb.post("/savings", body(suggestion(ALL), 4_321, "Owner asked for more"), ALL);
        assertThat(ow.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(cb.auditPayloads("retail.savings.created")).hasSize(2);
        for (String payload : cb.auditPayloads("retail.savings.created")) {
            assertThat(payload).contains("overwritten").contains("default_applied");
            assertThat(payload).doesNotContain("2500").doesNotContain("5000").doesNotContain("4321");
            assertThat(payload).doesNotContain("amount_minor").doesNotContain("suggested_minor");
        }
        // A reader holding core.audit.read but not retail.profit.read gets no amount from the API either.
        assertThat(cb.get("/savings", AUDIT_ONLY).getBody().get("items").get(0).has("amount_minor"))
                .isFalse();
    }

    @Test
    void aReplayedKeyPostsOnce() {
        JsonNode s = suggestion(PROFIT_RECORDER);
        String key = CashbookTestSupport.key();
        ResponseEntity<JsonNode> a = cb.api.postKeyed("/savings", body(s, null, null), PROFIT_RECORDER, "*", key);
        ResponseEntity<JsonNode> b = cb.api.postKeyed("/savings", body(s, null, null), PROFIT_RECORDER, "*", key);
        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(cb.accountMovement("savings_reserve", hq)).isEqualTo(2_500);
    }

    @Test
    void aFutureDateIsAFieldProblem() {
        JsonNode s = suggestion(PROFIT_RECORDER);
        Map<String, Object> b = body(s, null, null);
        b.put(
                "business_date",
                LocalDate.now(BusinessClock.DEFAULT_ZONE).plusDays(2).toString());
        ResponseEntity<JsonNode> r = cb.post("/savings", b, PROFIT_RECORDER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("errors").get(0).get("code").asString()).isEqualTo("future_date");
    }

    @Test
    void twoSimultaneousCreatesForOneShopAndDayLeaveOneRecordAndRefuseTheOtherAsSavingsExists() throws Exception {
        JsonNode s = suggestion(PROFIT_RECORDER);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.List<java.util.concurrent.Future<ResponseEntity<JsonNode>>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(pool.submit(() -> {
                    go.await();
                    return cb.post("/savings", body(s, null, null), PROFIT_RECORDER);
                }));
            }
            go.countDown();
            java.util.List<HttpStatus> statuses = new java.util.ArrayList<>();
            for (var call : calls) {
                ResponseEntity<JsonNode> r = call.get();
                statuses.add(HttpStatus.valueOf(r.getStatusCode().value()));
                if (r.getStatusCode() == HttpStatus.CONFLICT) {
                    assertThat(r.getBody().get("code").asString()).isEqualTo("savings_exists");
                }
            }
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(cb.accountMovement("savings_reserve", hq)).isEqualTo(2_500);
    }

    @Test
    void aVoidIsStampedAndReversedOnTheSameDay() {
        UUID id = RetailTestSupport.id(
                cb.post("/savings", body(suggestion(PROFIT_RECORDER), null, null), PROFIT_RECORDER));
        assertThat(cb.voidIt("/savings/" + id, "Wrong day", ALL).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        LocalDate voidedOn = TestDatabase.owner()
                .sql("SELECT (voided_at AT TIME ZONE 'Africa/Kampala')::date FROM retail_daily_savings WHERE id = ?")
                .param(id)
                .query(java.sql.Date.class)
                .single()
                .toLocalDate();
        LocalDate reversedOn = TestDatabase.owner()
                .sql("SELECT max(entry_date) FROM journal_entries WHERE tenant_id = ? AND idempotency_key LIKE ?")
                .params(t.tenantId(), "%" + id + ":void")
                .query(java.sql.Date.class)
                .single()
                .toLocalDate();
        assertThat(reversedOn).isEqualTo(voidedOn);
    }
}
