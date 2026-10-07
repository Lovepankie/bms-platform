package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHBOOK_SALES;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHIER;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.daysAgo;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
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
    static final String AUDIT_ONLY = "core.audit.read,retail.cashbook.read";

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
        assertThat(s.get("existing_id").isNull()).isTrue();

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
    void anOverwriteNeedsTheOverwritePermissionAndAReason() {
        JsonNode s = suggestion(PROFIT_RECORDER);

        ResponseEntity<JsonNode> forbidden = cb.post("/savings", body(s, 4_000, "Owner asked"), PROFIT_RECORDER);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The equal amount is the default, not an overwrite, so it is accepted without the permission.
        ResponseEntity<JsonNode> equal = cb.post("/savings", body(s, 2_500, null), PROFIT_RECORDER);
        assertThat(equal.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(equal.getBody().get("overwritten").asBoolean()).isFalse();
        cb.voidIt("/savings/" + RetailTestSupport.id(equal), "Redo", ALL);

        ResponseEntity<JsonNode> noReason = cb.post("/savings", body(s, 4_000, null), ALL);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(noReason.getBody().get("code").asString()).isEqualTo("reason_required");
        assertThat(cb.post("/savings", body(s, 4_000, "no"), ALL)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("reason_required");

        ResponseEntity<JsonNode> ok = cb.post("/savings", body(s, 4_000, "Owner asked for more"), ALL);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody().get("overwritten").asBoolean()).isTrue();
        assertThat(ok.getBody().get("amount_minor").asLong()).isEqualTo(4_000);
        assertThat(cb.accountMovement("savings_reserve", hq)).isEqualTo(4_000);
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
        b.put("business_date", LocalDate.now().plusDays(2).toString());
        ResponseEntity<JsonNode> r = cb.post("/savings", b, PROFIT_RECORDER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("errors").get(0).get("code").asString()).isEqualTo("future_date");
    }
}
