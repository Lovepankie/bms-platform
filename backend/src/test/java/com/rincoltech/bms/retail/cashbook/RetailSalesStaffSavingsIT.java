package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import java.time.LocalDate;
import java.util.HashMap;
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
 * Issue #799, ADR-115: with the Owner's switch "Salespeople can see and change the savings amount" on, a user signed in with
 * the Sales role alone (no till, no savings permission) opens the savings of a branch where they may sell, sees the suggested
 * amount, changes it and saves it, and sees this month's savings records with their amounts, including one made before the
 * switch was turned on; never the profit, the expenses or the cap. With the switch off they have no access. One product costs
 * 1,000 and sells at 1,500. All names and amounts are fabricated.
 */
class RetailSalesStaffSavingsIT extends IntegrationTest {

    static final String R = "/api/v1/retail";
    static final String SALES_STAFF = R + "/savings/sales-staff";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    Api api;
    UUID hq;
    UUID second;
    UUID product;
    LocalDate today;
    UUID owner;
    String seller;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("cb-sales-sav", false, true);
        cb = new CashbookTestSupport(http, t);
        api = Api.tenant(http, t.slug());
        hq = t.headOffice();
        second = t.secondBranch();
        product = cb.api.product("LAMP-5W", 1_000, 1_500);
        cb.api.stockUp(hq, product, "100");
        cb.api.stockUp(second, product, "100");
        today = LocalDate.now(BusinessClock.DEFAULT_ZONE);
        // Ten sold at the head office: profit 5,000, suggestion 2,500. Four at the second branch: suggestion 1,000.
        assertThat(cb.sale(hq, "cash", product, "10", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cb.sale(second, "cash", product, "4", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        owner = Api.staff(t, Api.email("ss-owner"), new Role("tenant_admin", null));
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = true WHERE id = ?")
                .param(owner)
                .update();
        seller = signIn("ss-seller", new Role("retail_sales", null));
    }

    String signIn(String name, Role... roles) {
        String email = Api.email(name);
        UUID id = Api.staff(t, email, roles);
        return api.signIn(id, email, Api.PASSWORD, null).accessToken();
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

    /** A read of the cash book as the Owner of the business. */
    JsonNode asOwner(String path) {
        HttpHeaders h = cb.api.headers(ALL, "*", Map.of());
        h.set("X-Dev-User-Id", owner.toString());
        ResponseEntity<JsonNode> r = http.exchange(R + path, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    ResponseEntity<JsonNode> suggestion(String token, UUID branch, LocalDate date) {
        return api.get(SALES_STAFF + "/suggestion?branch_id=" + branch + "&date=" + date, token);
    }

    ResponseEntity<JsonNode> save(String token, UUID branch, LocalDate date, String suggestionToken, Long amount) {
        return save(
                token, branch, date, suggestionToken, amount, UUID.randomUUID().toString());
    }

    ResponseEntity<JsonNode> save(
            String token, UUID branch, LocalDate date, String suggestionToken, Long amount, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("business_date", date.toString());
        body.put("suggestion_token", suggestionToken);
        if (amount != null) {
            body.put("amount_minor", amount);
        }
        return api.call(HttpMethod.POST, SALES_STAFF, body, token, Map.of("Idempotency-Key", key));
    }

    /** The till records a day's savings with the suggested amount, as an administrator. */
    UUID tillRecords(UUID branch, LocalDate date) {
        JsonNode s = cb.get("/savings/suggestion?branch_id=" + branch + "&date=" + date, ALL)
                .getBody();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("business_date", date.toString());
        body.put("suggestion_token", s.get("suggestion_token").asString());
        ResponseEntity<JsonNode> r = cb.post("/savings", body, ALL);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    /** The seller's savings records by id. */
    Map<String, JsonNode> listed(String query) {
        ResponseEntity<JsonNode> r = api.get(SALES_STAFF + query, seller);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, JsonNode> rows = new HashMap<>();
        r.getBody().get("items").forEach(row -> rows.put(row.get("id").asString(), row));
        return rows;
    }

    static void noProfitExpensesOrCap(JsonNode body) {
        assertThat(body.has("available_minor")).isFalse();
        assertThat(body.has("daily_profit_minor")).isFalse();
        assertThat(body.has("day_expenses_minor")).isFalse();
    }

    @Test
    void withTheSwitchOnASalesUserSeesTheAmountChangesItSavesItAndSeesTheMonthsRecordsIncludingAnOlderOne() {
        // Made before the switch was turned on: one this month, at the second branch, and one last month.
        UUID older = tillRecords(second, today);
        LocalDate lastMonth = today.withDayOfMonth(1).minusDays(1);
        UUID lastMonths = tillRecords(hq, lastMonth);
        theSwitch(true);

        assertThat(api.get("/api/v1/me", seller)
                        .getBody()
                        .get("salespeople_see_savings_amount")
                        .asBoolean())
                .isTrue();

        // The day's savings at the head office: the suggestion, and the right to change it, never the profit.
        JsonNode s = suggestion(seller, hq, today).getBody();
        assertThat(s.get("suggested_minor").asLong()).isEqualTo(2_500);
        assertThat(s.get("may_change_amount").asBoolean()).isTrue();
        noProfitExpensesOrCap(s);

        // The cap holds in words that do not state it.
        ResponseEntity<JsonNode> above =
                save(seller, hq, today, s.get("suggestion_token").asString(), 5_001L);
        assertThat(above.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(above.getBody().get("code").asString()).isEqualTo("savings_above_available");
        noProfitExpensesOrCap(above.getBody());

        // Changed and saved: echoed with the amount, posted, and audited without it.
        ResponseEntity<JsonNode> saved =
                save(seller, hq, today, s.get("suggestion_token").asString(), 2_000L);
        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getBody().get("amount_minor").asLong()).isEqualTo(2_000);
        assertThat(saved.getBody().get("overwritten").asBoolean()).isTrue();
        assertThat(saved.getBody().has("suggested_minor")).isFalse();
        assertThat(cb.accountMovement("savings_reserve", hq)).isEqualTo(2_000);
        assertThat(cb.auditPayloads("retail.savings.created"))
                .anySatisfy(p -> assertThat(p).contains("\"salespeople_setting\": true"))
                .allSatisfy(p -> assertThat(p).doesNotContain("2000").doesNotContain("2500"));
        UUID mine = RetailTestSupport.id(saved);
        ResponseEntity<JsonNode> again =
                save(seller, hq, today, s.get("suggestion_token").asString(), null);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("savings_exists");

        // JUSTIFICATION-A3: the lead's request on PR #800 asks for this check for a seller and for the owner.
        // The owner's request: once the day is saved, its suggestion is gone for the seller and for the owner alike,
        // and
        // only what was saved shows; a stale form on that day is not told the new suggestion either.
        JsonNode sellerView = suggestion(seller, hq, today).getBody();
        assertThat(sellerView.has("suggested_minor")).isFalse();
        assertThat(sellerView.get("may_change_amount").asBoolean()).isFalse();
        assertThat(sellerView.get("existing_amount_minor").asLong()).isEqualTo(2_000);
        JsonNode ownerView = asOwner("/savings/suggestion?branch_id=" + hq + "&date=" + today);
        assertThat(ownerView.has("suggested_minor")).isFalse();
        assertThat(ownerView.get("existing_amount_minor").asLong()).isEqualTo(2_000);
        assertThat(cb.sale(hq, "cash", product, "2", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> stale =
                save(seller, hq, today, s.get("suggestion_token").asString(), null);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("suggestion_changed");
        assertThat(stale.getBody().has("suggested_minor")).isFalse();
        ResponseEntity<JsonNode> staleOwner = cb.post(
                "/savings",
                Map.of(
                        "branch_id",
                        hq,
                        "business_date",
                        today.toString(),
                        "suggestion_token",
                        ownerView.get("suggestion_token").asString()),
                ALL);
        assertThat(staleOwner.getBody().get("code").asString()).isEqualTo("suggestion_changed");
        assertThat(staleOwner.getBody().has("suggested_minor")).isFalse();

        // The older record of this month shows its amount on the suggestion and in the records.
        assertThat(suggestion(seller, second, today)
                        .getBody()
                        .get("existing_amount_minor")
                        .asLong())
                .isEqualTo(1_000);
        Map<String, JsonNode> rows = listed("?include_voided=true");
        assertThat(rows.get(mine.toString()).get("amount_minor").asLong()).isEqualTo(2_000);
        assertThat(rows.get(older.toString()).get("amount_minor").asLong()).isEqualTo(1_000);
        rows.values().forEach(row -> assertThat(row.has("suggested_minor")).isFalse());
        // This month only, even when an earlier day is asked for.
        assertThat(rows).doesNotContainKey(lastMonths.toString());
        assertThat(listed("?from=" + lastMonth + "&to=" + today)).doesNotContainKey(lastMonths.toString());

        // Replacing a saved amount still needs retail.savings.overwrite.
        assertThat(cb.voidIt("/savings/" + mine, "Wrong amount", ALL).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode after = suggestion(seller, hq, today).getBody();
        assertThat(after.get("may_change_amount").asBoolean()).isFalse();
        ResponseEntity<JsonNode> replaced =
                save(seller, hq, today, after.get("suggestion_token").asString(), 1_500L);
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(replaced.getBody().get("code").asString()).isEqualTo("permission_denied");
        ResponseEntity<JsonNode> resaved =
                save(seller, hq, today, after.get("suggestion_token").asString(), null);
        assertThat(resaved.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // JUSTIFICATION-A3: review of 0669c82, must-fix 2: the Owner's list and report hold no saved day's suggestion.
        // The Owner's records and savings report: the saved day carries no suggestion; only the voided record keeps it.
        Map<String, JsonNode> ownerRows = new HashMap<>();
        asOwner("/savings?include_voided=true&branch_id=" + hq)
                .get("items")
                .forEach(row -> ownerRows.put(row.get("id").asString(), row));
        JsonNode active = ownerRows.get(RetailTestSupport.id(resaved).toString());
        // Twelve sold by now (profit 6,000), so the default saved is 3,000; the voided record was suggested 2,500.
        assertThat(active.get("amount_minor").asLong()).isEqualTo(3_000);
        assertThat(active.has("suggested_minor")).isFalse();
        assertThat(ownerRows.get(mine.toString()).get("suggested_minor").asLong())
                .isEqualTo(2_500);
        JsonNode report = asOwner("/reports/cash/savings?branch_id=" + hq + "&from=" + today + "&to=" + today);
        assertThat(report.get("items")).isNotEmpty();
        report.get("items")
                .forEach(day -> assertThat(day.has("suggested_minor")).isFalse());

        // The till's own routes and the cash book stay closed to the seller.
        assertThat(api.get(R + "/savings/suggestion?branch_id=" + hq + "&date=" + today, seller)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get(R + "/savings", seller).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void withTheSwitchOffASalesUserHasNoAccess() {
        UUID older = tillRecords(second, today);
        assertThat(api.get("/api/v1/me", seller)
                        .getBody()
                        .get("salespeople_see_savings_amount")
                        .asBoolean())
                .isFalse();
        // Off from the start, and off again after it was on.
        for (boolean wasOn : new boolean[] {false, true}) {
            if (wasOn) {
                theSwitch(true);
                theSwitch(false);
            }
            ResponseEntity<JsonNode> s = suggestion(seller, hq, today);
            assertThat(s.getStatusCode()).as("%s", s.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(s.getBody().get("code").asString()).isEqualTo("permission_denied");
            ResponseEntity<JsonNode> saved = save(seller, hq, today, "any-token", null);
            assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(saved.getBody().get("code").asString()).isEqualTo("permission_denied");
            ResponseEntity<JsonNode> list = api.get(SALES_STAFF, seller);
            assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(list.toString()).doesNotContain(older.toString());
            assertThat(api.get(R + "/savings/suggestion?branch_id=" + hq + "&date=" + today, seller)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(cb.accountMovement("savings_reserve", hq)).isZero();
    }

    @Test
    void withTheSwitchOnASalesUserReachesOnlyTheBranchesWhereTheyMaySell() {
        tillRecords(second, today);
        String hqSeller = signIn("ss-hq-seller", new Role("retail_sales", hq));
        theSwitch(true);
        JsonNode own = suggestion(hqSeller, hq, today).getBody();
        assertThat(own.get("suggested_minor").asLong()).isEqualTo(2_500);
        // Another branch is 404 unknown_branch (FR-RET-18) on the suggestion, on a save and on the list when named.
        ResponseEntity<JsonNode> elsewhere = suggestion(hqSeller, second, today);
        assertThat(elsewhere.getStatusCode()).as("%s", elsewhere.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(elsewhere.getBody().get("code").asString()).isEqualTo("unknown_branch");
        ResponseEntity<JsonNode> savedElsewhere =
                save(hqSeller, second, today, own.get("suggestion_token").asString(), null);
        assertThat(savedElsewhere.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(savedElsewhere.getBody().get("code").asString()).isEqualTo("unknown_branch");
        assertThat(cb.accountMovement("savings_reserve", second)).isEqualTo(1_000);
        // Without a branch named, the list is the caller's own branches: nothing from the other branch.
        ResponseEntity<JsonNode> list = api.get(SALES_STAFF, hqSeller);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().get("items")).isEmpty();
        ResponseEntity<JsonNode> named = api.get(SALES_STAFF + "?branch_id=" + second, hqSeller);
        assertThat(named.getStatusCode()).as("%s", named.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(named.getBody().get("code").asString()).isEqualTo("unknown_branch");
        assertThat(named.toString()).doesNotContain("amount_minor");
        // Naming the caller's own branch is fine.
        assertThat(api.get(SALES_STAFF + "?branch_id=" + hq, hqSeller).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // JUSTIFICATION-A3: review of 0669c82, must-fix 1 and nice-to-haves 4 and 5, one test each.
    @Test
    void withTheSwitchOnASalesUserOpensAndSavesOnlyDaysOfThisMonth() {
        theSwitch(true);
        LocalDate lastMonth = today.withDayOfMonth(1).minusDays(1);
        ResponseEntity<JsonNode> earlier = suggestion(seller, hq, lastMonth);
        assertThat(earlier.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(earlier.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("outside_this_month");
        assertThat(earlier.getBody().has("suggested_minor")).isFalse();
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();
        ResponseEntity<JsonNode> backdated = save(seller, hq, lastMonth, token, null);
        assertThat(backdated.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(backdated.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("outside_this_month");
        // Nor a day after today.
        LocalDate tomorrow = today.plusDays(1);
        ResponseEntity<JsonNode> later = suggestion(seller, hq, tomorrow);
        assertThat(later.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(later.getBody().get("errors").get(0).get("code").asString()).isEqualTo("future_date");
        ResponseEntity<JsonNode> ahead = save(seller, hq, tomorrow, token, null);
        assertThat(ahead.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(ahead.getBody().get("errors").get(0).get("code").asString()).isEqualTo("future_date");
        assertThat(cb.accountMovement("savings_reserve", hq)).isZero();
        // The till keeps every day.
        assertThat(cb.get("/savings/suggestion?branch_id=" + hq + "&date=" + lastMonth, ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void twoSalesStaffSavesForOneShopAndDayLeaveOneRecord() throws Exception {
        theSwitch(true);
        String other = signIn("ss-seller-2", new Role("retail_sales", null));
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<HttpStatus> statuses = new java.util.ArrayList<>();
        try {
            java.util.List<java.util.concurrent.Future<ResponseEntity<JsonNode>>> calls = new java.util.ArrayList<>();
            for (String who : java.util.List.of(seller, other)) {
                calls.add(pool.submit(() -> {
                    go.await();
                    return save(who, hq, today, token, null);
                }));
            }
            go.countDown();
            for (var call : calls) {
                ResponseEntity<JsonNode> r = call.get(60, java.util.concurrent.TimeUnit.SECONDS);
                statuses.add(HttpStatus.valueOf(r.getStatusCode().value()));
                if (r.getStatusCode() == HttpStatus.CONFLICT) {
                    assertThat(r.getBody().get("code").asString()).isEqualTo("savings_exists");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(cb.accountMovement("savings_reserve", hq)).isEqualTo(2_500);
    }

    // JUSTIFICATION-A3: the race the review of PR #800 asks for (item 4), a seller against the till, as a test of its
    // own.
    /** A seller's save and the till's save for one shop and day at once: exactly one wins, the other is 409. */
    @Test
    void aSalesStaffSaveRacingATillSaveForOneShopAndDayLeavesOneRecord() throws Exception {
        theSwitch(true);
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();
        Map<String, Object> till =
                Map.of("branch_id", hq, "business_date", today.toString(), "suggestion_token", token);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.List<HttpStatus> statuses = new java.util.ArrayList<>();
        try {
            java.util.List<java.util.concurrent.Future<ResponseEntity<JsonNode>>> calls = java.util.List.of(
                    pool.submit(() -> {
                        go.await();
                        return save(seller, hq, today, token, null);
                    }),
                    pool.submit(() -> {
                        go.await();
                        return cb.post("/savings", till, ALL);
                    }));
            go.countDown();
            for (var call : calls) {
                ResponseEntity<JsonNode> r = call.get(60, java.util.concurrent.TimeUnit.SECONDS);
                statuses.add(HttpStatus.valueOf(r.getStatusCode().value()));
                if (r.getStatusCode() == HttpStatus.CONFLICT) {
                    assertThat(r.getBody().get("code").asString()).isEqualTo("savings_exists");
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(cb.accountMovement("savings_reserve", hq)).isEqualTo(2_500);
    }

    @Test
    void aKeyReplayedAfterTheSwitchWasTurnedOffIsRefused() {
        theSwitch(true);
        String key = UUID.randomUUID().toString();
        String token =
                suggestion(seller, hq, today).getBody().get("suggestion_token").asString();
        ResponseEntity<JsonNode> first = save(seller, hq, today, token, 2_000L, key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        theSwitch(false);
        ResponseEntity<JsonNode> replay = save(seller, hq, today, token, 2_000L, key);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(replay.getBody().get("code").asString()).isEqualTo("permission_denied");
        assertThat(replay.getBody().has("amount_minor")).isFalse();
    }
}
