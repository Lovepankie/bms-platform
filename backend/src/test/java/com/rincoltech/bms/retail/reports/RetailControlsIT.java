package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The Controls page (issue #357, group 1; ADR-047). Fabricated shop, product B costs 100 and sells for 300. Head office has
 * two sellers: Test Seller One enters 10 sales there, Test Seller Abel (deactivated) enters 30. Branch two has 5 sales by
 * Seller One, and Seller One also sells 3 lines below cost at head office (price 80 against a cost of 100) with the
 * permission that allows it. The administrator, Test Admin One, voids 5 of Seller One's sales (3 "Test wrong item", 2 "Test
 * customer left") and 1 of Seller Abel's ("Test wrong item"): the voids are credited to the sellers whose sales they were,
 * never to the administrator, who appears only as the approver. So 48 sales were entered (head office 43), 6 voided at
 * once; Seller One entered 18 (13 at head office), Seller Abel 30.
 */
class RetailControlsIT extends IntegrationTest {

    static final String CONTROLS = "retail.controls.read,retail.sale.read";
    static final String CONTROLS_AND_PROFIT = CONTROLS + ",retail.profit.read";
    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport one;
    RetailTestSupport two;
    RetailTestSupport adm;
    UUID b;
    /** The six voided sales, the first five Seller One's. */
    List<UUID> voided = new ArrayList<>();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-controls", false, true);
        one = new RetailTestSupport(http, t);
        two = new RetailTestSupport(http, t);
        adm = new RetailTestSupport(http, t);
        user(one.userId(), "Test Seller One", "active");
        user(two.userId(), "Test Seller Abel", "deactivated");
        user(adm.userId(), "Test Admin One", "active");
        b = one.product("CTL-B", 100, 300);
        one.stockUp(t.headOffice(), b, "500");
        one.stockUp(t.secondBranch(), b, "100");
        List<UUID> oneSales = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            oneSales.add(sell(one, t.headOffice(), null));
        }
        List<UUID> twoSales = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            twoSales.add(sell(two, t.headOffice(), null));
        }
        for (int i = 0; i < 5; i++) {
            sell(one, t.secondBranch(), null);
        }
        for (int i = 0; i < 5; i++) {
            void_(adm, oneSales.get(i), i < 3 ? "Test wrong item" : "Test customer left");
            voided.add(oneSales.get(i));
        }
        void_(adm, twoSales.get(0), "Test wrong item");
        voided.add(twoSales.get(0));
        for (int i = 0; i < 3; i++) {
            sell(one, t.headOffice(), 80L);
        }
    }

    static void user(TestDatabase.Fixture t, UUID id, String name, String status) {
        TestDatabase.owner()
                .sql("INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', ?,"
                        + " ?, ?)")
                .params(id, t.tenantId(), name, "u" + id + "@example.test", status)
                .update();
    }

    void user(UUID id, String name, String status) {
        user(t, id, name, status);
    }

    UUID sell(RetailTestSupport as, UUID branch, Long unitPriceMinor) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", b);
        line.put("qty", "1");
        if (unitPriceMinor != null) {
            line.put("unit_price_minor", unitPriceMinor);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        String permissions = ADMIN + ",retail.price.below_cost";
        ResponseEntity<JsonNode> r =
                as.postKeyed("/sales", body, permissions, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    void void_(RetailTestSupport as, UUID sale, String reason) {
        ResponseEntity<JsonNode> r = as.post("/sales/" + sale + "/void", Map.of("reason", reason), ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** Changes a column the guard keeps still, as the owner role may for a restore (bms.allow_mutation). */
    void owner(String sql) {
        TestDatabase.owner()
                .sql("DO $$ BEGIN PERFORM set_config('bms.allow_mutation', 'on', true); " + sql + "; END $$")
                .update();
    }

    JsonNode get(String path, String permissions) {
        return get(path, permissions, Map.of());
    }

    JsonNode get(String path, String permissions, Map<String, String> extra) {
        ResponseEntity<JsonNode> r = one.call(HttpMethod.GET, "/controls" + path, null, permissions, "*", extra);
        assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    HttpStatusCode status(String path, String permissions, Map<String, String> extra) {
        return one.call(HttpMethod.GET, "/controls" + path, null, permissions, "*", extra)
                .getStatusCode();
    }

    static JsonNode card(JsonNode summary, String metric) {
        for (JsonNode c : summary.get("cards")) {
            if (c.get("metric").asString().equals(metric)) {
                return c;
            }
        }
        return null;
    }

    static boolean has(JsonNode groups, String key) {
        for (JsonNode g : groups.get("items")) {
            if (g.get("key").asString().equals(key)) {
                return true;
            }
        }
        return false;
    }

    static JsonNode group(JsonNode groups, String key) {
        for (JsonNode g : groups.get("items")) {
            if (g.get("key").asString().equals(key)) {
                return g;
            }
        }
        throw new AssertionError("no group " + key + " in " + groups);
    }

    static Map<String, String> scopes(String scopes) {
        return Map.of("X-Dev-Scopes", scopes);
    }

    @Test
    void theCardsCountTheVoidsAndSetThemAgainstAllTheSalesOfThePeriod() {
        JsonNode s = get("/summary", CONTROLS);
        // 10 + 30 + 5 + 3 sales were entered; 6 of them voided at once (a quick cancel is a void within ten minutes).
        assertThat(s.get("period").get("sales_count").asLong()).isEqualTo(48);
        JsonNode voids = card(s, "voided_sales");
        assertThat(voids.get("count").asLong()).isEqualTo(6);
        assertThat(voids.get("sales_affected").asLong()).isEqualTo(6);
        assertThat(voids.get("value_minor").asLong()).isEqualTo(1_800);
        assertThat(voids.get("rate_per_100").asString()).isEqualTo("12.5");
        assertThat(voids.get("previous_count").asLong()).isZero();
        assertThat(card(s, "quick_cancels").get("count").asLong()).isEqualTo(6);
        assertThat(s.get("within_minutes").asInt()).isEqualTo(10);
    }

    @Test
    void aVoidAfterTheQuickWindowIsNotAQuickCancel() {
        // The sale was entered an hour before it was voided: not within ten minutes, but within 120.
        owner("UPDATE retail_sales SET created_at = created_at - interval '1 hour' WHERE id = '" + voided.getFirst()
                + "'");
        assertThat(card(get("/summary", CONTROLS), "voided_sales").get("count").asLong())
                .isEqualTo(6);
        assertThat(card(get("/summary", CONTROLS), "quick_cancels").get("count").asLong())
                .isEqualTo(5);
        assertThat(card(get("/summary?within_minutes=120", CONTROLS), "quick_cancels")
                        .get("count")
                        .asLong())
                .isEqualTo(6);
        JsonNode records = get("/records?metric=quick_cancels", CONTROLS);
        assertThat(records.get("items")).hasSize(5);
    }

    @Test
    void aVoidAtHalfPastOneAtNightInKampalaLandsOnThatDayNotTheUtcOne() {
        LocalDate yesterday = LocalDate.now(KAMPALA).minusDays(1);
        String at = LocalDateTime.of(yesterday, LocalTime.of(1, 30))
                .atZone(KAMPALA)
                .toOffsetDateTime()
                .toString();
        owner("UPDATE retail_sales SET voided_at = TIMESTAMPTZ '" + at + "' WHERE id = '" + voided.getFirst() + "'");
        JsonNode thatDay = get("/summary?from=" + yesterday + "&to=" + yesterday, CONTROLS);
        assertThat(card(thatDay, "voided_sales").get("count").asLong()).isEqualTo(1);
        assertThat(card(thatDay, "voided_sales").get("value_minor").asLong()).isEqualTo(300);
        // 01:30 in Kampala is 22:30 UTC the day before: a dropped time zone would put it there.
        LocalDate utcDay = yesterday.minusDays(1);
        assertThat(card(get("/summary?from=" + utcDay + "&to=" + utcDay, CONTROLS), "voided_sales")
                        .get("count")
                        .asLong())
                .isZero();
        LocalDate today = LocalDate.now(KAMPALA);
        assertThat(card(get("/summary?from=" + today + "&to=" + today, CONTROLS), "voided_sales")
                        .get("count")
                        .asLong())
                .isEqualTo(5);
        JsonNode days = get("/groups?metric=voided_sales&group_by=day&from=" + yesterday + "&to=" + today, CONTROLS);
        assertThat(group(days, yesterday.toString()).get("count").asLong()).isEqualTo(1);
        assertThat(group(days, today.toString()).get("count").asLong()).isEqualTo(5);
    }

    @Test
    void salesBelowCostAreForThoseWhoMayReadProfitAndCarryNoCost() {
        JsonNode withProfit = get("/summary", CONTROLS_AND_PROFIT);
        assertThat(withProfit.get("cost_visible").asBoolean()).isTrue();
        JsonNode below = card(withProfit, "below_cost");
        assertThat(below.get("count").asLong()).isEqualTo(3);
        assertThat(below.get("sales_affected").asLong()).isEqualTo(3);
        assertThat(below.get("value_minor").asLong()).isEqualTo(240);

        JsonNode without = get("/summary", CONTROLS);
        assertThat(without.get("cost_visible").asBoolean()).isFalse();
        assertThat(card(without, "below_cost")).isNull();
        assertThat(without.toString()).doesNotContain("cost_minor").doesNotContain("profit");
        assertThat(status("/groups?metric=below_cost&group_by=user", CONTROLS, Map.of()))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void profitReadAtOneShopOnlyIsNotEnoughForSalesBelowCost() {
        // Controls at head office and branch two, profit at head office only: the figures cover both shops.
        Map<String, String> partial = scopes("retail.controls.read=" + t.headOffice() + "," + t.secondBranch()
                + ";retail.profit.read=" + t.headOffice());
        JsonNode s = get("/summary", CONTROLS_AND_PROFIT, partial);
        assertThat(s.get("cost_visible").asBoolean()).isFalse();
        assertThat(card(s, "below_cost")).isNull();
        assertThat(status("/groups?metric=below_cost&group_by=user", CONTROLS_AND_PROFIT, partial))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status("/records?metric=below_cost", CONTROLS_AND_PROFIT, partial))
                .isEqualTo(HttpStatus.FORBIDDEN);
        for (JsonNode p : get("/patterns", CONTROLS_AND_PROFIT, partial).get("items")) {
            assertThat(p.get("metric").asString()).isNotEqualTo("below_cost");
        }
        // Profit read at both shops: the card, the groups and the pattern appear.
        Map<String, String> both = scopes("retail.controls.read=" + t.headOffice() + "," + t.secondBranch()
                + ";retail.profit.read=" + t.headOffice() + "," + t.secondBranch());
        assertThat(card(get("/summary", CONTROLS_AND_PROFIT, both), "below_cost")
                        .get("count")
                        .asLong())
                .isEqualTo(3);
        assertThat(status("/groups?metric=below_cost&group_by=user", CONTROLS_AND_PROFIT, both))
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void personsAreCreditedWithTheirOwnSalesAndComparedByRateNotByCount() {
        JsonNode users = get("/groups?metric=voided_sales&group_by=user", CONTROLS);
        JsonNode first = group(users, one.userId().toString());
        JsonNode second = group(users, two.userId().toString());
        assertThat(first.get("label").asString()).isEqualTo("Test Seller One");
        assertThat(first.get("user_status").asString()).isEqualTo("active");
        assertThat(first.get("count").asLong()).isEqualTo(5);
        // Seller One entered 10 + 5 + 3 sales; the rate is over the sales the person entered.
        assertThat(first.get("sales_count").asLong()).isEqualTo(18);
        assertThat(first.get("rate_per_100").asString()).isEqualTo("27.8");
        assertThat(second.get("label").asString()).isEqualTo("Test Seller Abel");
        assertThat(second.get("user_status").asString()).isEqualTo("deactivated");
        assertThat(second.get("count").asLong()).isEqualTo(1);
        assertThat(second.get("rate_per_100").asString()).isEqualTo("3.3");
        // The administrator who cancelled them is not a row of the person level: the sellers carry the count.
        assertThat(has(users, adm.userId().toString())).isFalse();
        // People come by name, not by how many: Seller Abel (1) before Seller One (5).
        assertThat(users.get("items").get(0).get("label").asString()).isEqualTo("Test Seller Abel");
    }

    @Test
    void theAdministratorWhoCancelledIsTheApproverAndHasNoRate() {
        JsonNode approvers = get("/groups?metric=voided_sales&group_by=approver", CONTROLS);
        JsonNode admin = group(approvers, adm.userId().toString());
        assertThat(admin.get("label").asString()).isEqualTo("Test Admin One");
        assertThat(admin.get("count").asLong()).isEqualTo(6);
        assertThat(admin.has("rate_per_100")).isFalse();
        assertThat(has(approvers, one.userId().toString())).isFalse();
        // Narrowed to the approver, the person level still shows the two sellers.
        JsonNode sellers = get(
                "/groups?metric=voided_sales&group_by=approver,user&level=user&approver_id=" + adm.userId(), CONTROLS);
        assertThat(sellers.get("items")).hasSize(2);
    }

    @Test
    void aPersonWithNoUserRowReadsUnknownPersonAndKeepsTheirOwnGroup() {
        RetailTestSupport ghost = new RetailTestSupport(http, t);
        void_(adm, sell(ghost, t.headOffice(), null), "Test ghost sale");
        JsonNode users = get("/groups?metric=voided_sales&group_by=user", CONTROLS);
        // The sale carries a user id the users table does not know: a group of its own, named Unknown person.
        JsonNode unknown = group(users, ghost.userId().toString());
        assertThat(unknown.get("label").asString()).isEqualTo("Unknown person");
        assertThat(unknown.get("count").asLong()).isEqualTo(1);
    }

    @Test
    void reasonsGroupByTheTextAndOpenOntoTheRecordsWithBothPeople() {
        JsonNode reasons = get("/groups?metric=voided_sales&group_by=reason", CONTROLS);
        assertThat(group(reasons, "test wrong item").get("count").asLong()).isEqualTo(4);
        assertThat(group(reasons, "test customer left").get("count").asLong()).isEqualTo(2);

        JsonNode records =
                get("/records?metric=voided_sales&reason=test customer left&user_id=" + one.userId(), CONTROLS);
        assertThat(records.get("items")).hasSize(2);
        JsonNode first = records.get("items").get(0);
        assertThat(first.get("sale_no").asString()).isNotBlank();
        // The person is the seller whose sale it was; the administrator is who cancelled it.
        assertThat(first.get("user_name").asString()).isEqualTo("Test Seller One");
        assertThat(first.get("approver_name").asString()).isEqualTo("Test Admin One");
        assertThat(first.get("reason").asString()).isEqualTo("Test customer left");
        assertThat(first.get("value_minor").asLong()).isEqualTo(300);
    }

    @Test
    void aChainNamesTheNextLevelAndShopsComeByName() {
        JsonNode shops = get("/groups?metric=voided_sales&group_by=branch,user,day", CONTROLS);
        assertThat(shops.get("level").asString()).isEqualTo("branch");
        assertThat(shops.get("next_level").asString()).isEqualTo("user");
        JsonNode headOffice = group(shops, t.headOffice().toString());
        assertThat(headOffice.get("count").asLong()).isEqualTo(6);
        assertThat(headOffice.get("sales_count").asLong()).isEqualTo(43);
        JsonNode day = get(
                "/groups?metric=voided_sales&group_by=branch,user,day&level=day&branch_id=" + t.headOffice()
                        + "&user_id=" + one.userId(),
                CONTROLS);
        assertThat(day.get("items")).hasSize(1);
        assertThat(day.get("items").get(0).get("count").asLong()).isEqualTo(5);
    }

    @Test
    void aSellerWhoseSalesWereCancelledMoreThanTwiceAsOftenAsTheShopsIsAPatternAndTheApproverIsNever() {
        JsonNode patterns = get("/patterns", CONTROLS_AND_PROFIT);
        JsonNode found = null;
        JsonNode belowCost = null;
        for (JsonNode p : patterns.get("items")) {
            if (p.get("subject").asString().equals("user")
                    && p.get("key").asString().equals(one.userId().toString())
                    && p.get("branch_id").asString().equals(t.headOffice().toString())) {
                if (p.get("metric").asString().equals("voided_sales")) {
                    found = p;
                } else if (p.get("metric").asString().equals("below_cost")) {
                    belowCost = p;
                }
            }
        }
        assertThat(found).as("%s", patterns).isNotNull();
        // 5 cancelled sales over the 13 sales Seller One entered at head office (38.5 per 100) against 6 over 43
        // (14.0).
        assertThat(found.get("rate_per_100").asString()).isEqualTo("38.5");
        assertThat(found.get("shop_rate_per_100").asString()).isEqualTo("14.0");
        assertThat(found.get("times").asString()).isEqualTo("2.8");
        // 3 sales below cost over the same 13 against 3 over 43: a pattern for a profit reader.
        assertThat(belowCost).isNotNull();
        // Seller Abel had one sale cancelled: below the minimum, never a pattern. The administrator is never one.
        for (JsonNode p : patterns.get("items")) {
            assertThat(p.get("key").asString())
                    .isNotEqualTo(two.userId().toString())
                    .isNotEqualTo(adm.userId().toString());
        }
        // Without profit read the below-cost pattern is not sent.
        for (JsonNode p : get("/patterns", CONTROLS).get("items")) {
            assertThat(p.get("metric").asString()).isNotEqualTo("below_cost");
        }
    }

    @Test
    void theBranchScopeNarrowsEveryRoute() {
        Map<String, String> branchTwo = scopes("retail.controls.read=" + t.secondBranch());
        JsonNode s = get("/summary", CONTROLS, branchTwo);
        assertThat(s.get("period").get("sales_count").asLong()).isEqualTo(5);
        assertThat(card(s, "voided_sales").get("count").asLong()).isZero();
        assertThat(get("/groups?metric=voided_sales&group_by=branch", CONTROLS, branchTwo)
                        .get("items"))
                .isEmpty();
        assertThat(get("/records?metric=voided_sales", CONTROLS, branchTwo).get("items"))
                .isEmpty();
        assertThat(get("/patterns", CONTROLS, branchTwo).get("items")).isEmpty();
        // Head office is outside the scope: asking for it gives nothing, never its figures.
        String outside = "&branch_id=" + t.headOffice();
        JsonNode none = get("/summary?within_minutes=10" + outside, CONTROLS, branchTwo);
        assertThat(none.get("period").get("sales_count").asLong()).isZero();
        assertThat(none.get("cards")).isEmpty();
        assertThat(get("/groups?metric=voided_sales&group_by=user" + outside, CONTROLS, branchTwo)
                        .get("items"))
                .isEmpty();
        assertThat(get("/records?metric=voided_sales" + outside, CONTROLS, branchTwo)
                        .get("items"))
                .isEmpty();
        assertThat(get("/patterns?x=1" + outside, CONTROLS, branchTwo).get("items"))
                .isEmpty();
        // A scope of head office alone sees the voids there.
        Map<String, String> headOffice = scopes("retail.controls.read=" + t.headOffice());
        assertThat(card(get("/summary", CONTROLS, headOffice), "voided_sales")
                        .get("count")
                        .asLong())
                .isEqualTo(6);
    }

    @Test
    void withoutThePermissionTheRoutesRefuse() {
        for (String path : List.of(
                "/summary", "/groups?metric=voided_sales&group_by=user", "/records?metric=voided_sales", "/patterns")) {
            ResponseEntity<JsonNode> r = one.call(HttpMethod.GET, "/controls" + path, null, ADMIN, "*", Map.of());
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void badInputIsRefusedInPlainWords() {
        for (String path : List.of(
                "/summary?within_minutes=0",
                "/summary?from=2026-10-10&to=2026-10-01",
                "/groups?metric=nonsense&group_by=user",
                "/groups?metric=voided_sales&group_by=user,user",
                "/groups?metric=voided_sales&group_by=user&level=day",
                // A price, a cost and a correction have no approver to group by.
                "/groups?metric=price_overrides&group_by=branch,approver",
                "/groups?metric=quantity_corrections&group_by=approver")) {
            ResponseEntity<JsonNode> r = one.call(HttpMethod.GET, "/controls" + path, null, CONTROLS, "*", Map.of());
            assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }
}
