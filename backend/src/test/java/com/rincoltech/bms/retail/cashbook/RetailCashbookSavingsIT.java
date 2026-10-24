package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHBOOK_SALES;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHIER;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.daysAgo;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
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
