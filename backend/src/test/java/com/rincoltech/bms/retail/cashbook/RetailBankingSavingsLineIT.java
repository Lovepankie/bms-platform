package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Issue #814, ADR-115 decision 11, FR-RET-21: as the owner's old app did, with the Owner's savings switch on, a seller given the
 * till (the Sales and Till roles, no {@code retail.profit.read}) sees on Banking the day's savings set aside and the cash expected
 * less them; the cash bought with cash, the net expected amount and the flag of a save stay hidden. With the switch off the
 * answer is the one of before. Ten sold for cash, two bought for cash, the day's savings recorded by the till. All names and
 * amounts are fabricated.
 */
class RetailBankingSavingsLineIT extends IntegrationTest {

    static final String R = "/api/v1/retail";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    Api api;
    UUID hq;
    LocalDate today;
    UUID owner;
    String seller;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("cb-bank-sav", false, true);
        cb = new CashbookTestSupport(http, t);
        api = Api.tenant(http, t.slug());
        hq = t.headOffice();
        UUID product = cb.api.product("TORCH-3", 1_000, 1_500);
        cb.api.stockUp(hq, product, "100");
        today = LocalDate.now(BusinessClock.DEFAULT_ZONE);
        assertThat(cb.sale(hq, "cash", product, "10", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> bought = cb.restock(hq, product, 1_000, 1_500, "2", "cash", today);
        assertThat(bought.getStatusCode()).as("%s", bought.getBody()).isEqualTo(HttpStatus.CREATED);
        saveSavings(today);
        owner = Api.staff(t, Api.email("bs-owner"), new Role("tenant_admin", null));
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = true WHERE id = ?")
                .param(owner)
                .update();
        String email = Api.email("bs-till-seller");
        UUID id = Api.staff(t, email, new Role("retail_sales", null), new Role("retail_till", null));
        seller = api.signIn(id, email, Api.PASSWORD, null).accessToken();
    }

    /** The day's suggested savings at the head office, saved by a holder of every cash book permission. */
    ResponseEntity<JsonNode> saveSavings(LocalDate day) {
        JsonNode s = cb.get("/savings/suggestion?branch_id=" + hq + "&date=" + day, ALL)
                .getBody();
        Map<String, Object> saving = new LinkedHashMap<>();
        saving.put("branch_id", hq);
        saving.put("business_date", day.toString());
        saving.put("suggestion_token", s.get("suggestion_token").asString());
        ResponseEntity<JsonNode> saved = cb.post("/savings", saving, ALL);
        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.CREATED);
        return saved;
    }

    /** The Owner turns the switch on or off in Business set-up. */
    void theSwitch(boolean on) {
        HttpHeaders h = cb.api.headers("core.settings.manage,core.settings.read", "*", Map.of());
        h.set("X-Dev-User-Id", owner.toString());
        int version = http.exchange("/api/v1/settings", HttpMethod.GET, new HttpEntity<>(h), JsonNode.class)
                .getBody()
                .get("version")
                .asInt();
        h.add("If-Match", "\"" + version + "\"");
        ResponseEntity<JsonNode> r = http.exchange(
                "/api/v1/settings",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("salespeople_see_savings_amount", on), h),
                JsonNode.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    JsonNode sellersExpected() {
        ResponseEntity<JsonNode> r = api.get(R + "/bankings/expected?branch_id=" + hq + "&date=" + today, seller);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode ownersExpected() {
        ResponseEntity<JsonNode> r = cb.get("/bankings/expected?branch_id=" + hq + "&date=" + today, ALL);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static void noCashPurchasesOrNet(JsonNode body) {
        assertThat(body.has("cash_purchases_minor")).isFalse();
        assertThat(body.has("expected_minor")).isFalse();
        assertThat(body.has("daily_profit_minor")).isFalse();
    }

    @Test
    void withTheSwitchOnATillSellerSeesTheSavingsAndTheCashExpectedLessThemButNoCashPurchases() {
        theSwitch(true);
        JsonNode full = ownersExpected();
        long savings = full.get("savings_minor").asLong();
        assertThat(savings).isPositive();
        assertThat(full.get("cash_purchases_minor").asLong()).isPositive();

        JsonNode e = sellersExpected();

        noCashPurchasesOrNet(e);
        assertThat(e.get("savings_minor").asLong()).isEqualTo(savings);
        assertThat(e.has("savings_voids_minor")).isFalse();
        assertThat(e.get("cash_expected_minor").asLong())
                .isEqualTo(full.get("cash_expected_minor").asLong());
        assertThat(e.get("expected_after_savings_minor").asLong())
                .isEqualTo(e.get("cash_expected_minor").asLong() - savings);
        // The owner keeps the net figure and is not sent the seller's line.
        assertThat(full.has("expected_after_savings_minor")).isFalse();
        assertThat(full.has("expected_minor")).isTrue();
    }

    /**
     * Issue #847: a savings record of an earlier day voided today puts its amount back in today's till. The seller is not sent
     * the voided amount on its own (their Savings view leaves voided records out); it is folded into the after-savings figure,
     * with the sign of the owner's net figure.
     */
    @Test
    void aSavingsRecordOfAnEarlierDayVoidedTodayIsAddedBackIntoTheSellersFigureOnly() {
        LocalDate earlier = today.minusDays(2);
        UUID lamp = cb.api.product("LAMP-5", 1_000, 1_500);
        cb.api.stockUp(hq, lamp, "10");
        assertThat(cb.sale(hq, "cash", lamp, "4", earlier).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode saved = saveSavings(earlier).getBody();
        long voided = saved.get("amount_minor").asLong();
        assertThat(voided).isPositive();
        String record = saved.get("id").asString();
        ResponseEntity<JsonNode> undone = cb.voidIt("/savings/" + record, "Saved on the wrong day", ALL);
        assertThat(undone.getStatusCode()).as("%s", undone.getBody()).isEqualTo(HttpStatus.OK);
        theSwitch(true);

        JsonNode full = ownersExpected();
        long savings = full.get("savings_minor").asLong();
        long voids = full.get("savings_voids_minor").asLong();
        assertThat(savings).isPositive();
        assertThat(voids).isEqualTo(voided);
        JsonNode e = sellersExpected();

        assertThat(e.has("savings_voids_minor")).isFalse();
        assertThat(e.get("savings_minor").asLong()).isEqualTo(savings);
        long afterSavings = e.get("expected_after_savings_minor").asLong();
        assertThat(afterSavings).isEqualTo(e.get("cash_expected_minor").asLong() - savings + voids);
        long net = full.get("expected_minor").asLong();
        long purchases = full.get("cash_purchases_minor").asLong();
        assertThat(afterSavings).isEqualTo(net + purchases);
    }

    /** Issue #847: the savings line follows the seller's branches; another shop's banking is unknown to them. */
    @Test
    void withTheSwitchOnASellerAtOneShopAskingForAnotherShopsBankingGets404() {
        theSwitch(true);
        String email = Api.email("bs-hq-seller");
        UUID id = Api.staff(t, email, new Role("retail_sales", hq), new Role("retail_till", hq));
        String hqSeller = api.signIn(id, email, Api.PASSWORD, null).accessToken();

        ResponseEntity<JsonNode> own = api.get(R + "/bankings/expected?branch_id=" + hq + "&date=" + today, hqSeller);
        assertThat(own.getStatusCode()).as("%s", own.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(own.getBody().has("expected_after_savings_minor")).isTrue();

        ResponseEntity<JsonNode> other =
                api.get(R + "/bankings/expected?branch_id=" + t.secondBranch() + "&date=" + today, hqSeller);
        assertThat(other.getStatusCode()).as("%s", other.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.getBody().get("code").asString()).isEqualTo("unknown_branch");
        assertThat(other.getBody().toString()).doesNotContain("savings_minor");
    }

    @Test
    void withTheSwitchOnTheSellersSaveStillCarriesNoExpectedAmountFlagOrDifference() {
        theSwitch(true);
        long amount = sellersExpected().get("expected_after_savings_minor").asLong();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("amount_minor", amount);

        ResponseEntity<JsonNode> r =
                api.call(HttpMethod.POST, R + "/bankings", body, seller, Map.of("Idempotency-Key", "key-814-bank"));

        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("amount_minor").asLong()).isEqualTo(amount);
        assertThat(r.getBody().has("expected_minor")).isFalse();
        assertThat(r.getBody().has("difference_minor")).isFalse();
        assertThat(r.getBody().has("flag")).isFalse();
    }

    @Test
    void withTheSwitchOffATillSellerSeesTheCashExpectedOnlyAsBefore() {
        theSwitch(false);

        JsonNode e = sellersExpected();

        noCashPurchasesOrNet(e);
        assertThat(e.has("savings_minor")).isFalse();
        assertThat(e.has("savings_voids_minor")).isFalse();
        assertThat(e.has("expected_after_savings_minor")).isFalse();
        assertThat(e.get("cash_expected_minor").asLong())
                .isEqualTo(ownersExpected().get("cash_expected_minor").asLong());
    }
}
