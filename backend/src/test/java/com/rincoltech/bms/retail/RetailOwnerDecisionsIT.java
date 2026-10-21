package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
 * Issue #399, ADR-055: the owner's decisions of 21 October 2026, signed in for real. The cash book is the till's, given per
 * person ("Can close the till and do the banking", the role retail_till); a Supervisor sees every seller's sales, totals, the
 * dashboard and the sales analysis at its own branches only, with no cost, profit, Users or Controls; a buyer's account opens
 * for any seller of the branch with the amounts of a colleague's sale but never the colleague. Names and amounts are fabricated.
 */
class RetailOwnerDecisionsIT extends IntegrationTest {

    static final String R = "/api/v1/retail";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport retail;
    Api api;
    UUID product;
    UUID sellerB;
    String tokenA;
    String tokenB;
    UUID saleA;
    UUID saleB;
    UUID saleTwo;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-decide", false, true);
        retail = new RetailTestSupport(http, t);
        api = Api.tenant(http, t.slug());
        product = retail.product("DEC-1", 4_000, 7_000);
        retail.stockUp(t.headOffice(), product, "20");
        retail.stockUp(t.secondBranch(), product, "20");
        tokenA = signIn("seller-a", new Role("retail_sales", t.headOffice()));
        String emailB = Api.email("seller-b");
        sellerB = Api.staff(t, emailB, new Role("retail_sales", t.headOffice()));
        tokenB = api.signIn(sellerB, emailB, Api.PASSWORD, null).accessToken();
        saleA = sell(tokenA, t.headOffice(), "1", Map.of());
        saleB = sell(tokenB, t.headOffice(), "2", Map.of());
        saleTwo = RetailTestSupport.id(retail.sell(t.secondBranch(), "cash", product, "3"));
    }

    static LocalDate today() {
        return LocalDate.now(BusinessClock.DEFAULT_ZONE);
    }

    String signIn(String name, Role... roles) {
        String email = Api.email(name);
        UUID id = Api.staff(t, email, roles);
        return api.signIn(id, email, Api.PASSWORD, null).accessToken();
    }

    UUID sell(String token, UUID branch, String qty, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", "cash");
        body.put("lines", List.of(Map.of("product_id", product, "qty", qty)));
        body.putAll(extra);
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.POST,
                R + "/sales",
                body,
                token,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    ResponseEntity<JsonNode> get(String path, String token) {
        return api.get(R + path, token);
    }

    static void collectKeys(JsonNode node, List<String> keys) {
        if (node.isObject()) {
            node.properties().forEach(e -> {
                keys.add(e.getKey());
                collectKeys(e.getValue(), keys);
            });
        } else if (node.isArray()) {
            node.forEach(n -> collectKeys(n, keys));
        }
    }

    static void noCost(JsonNode body, String what) {
        List<String> keys = new ArrayList<>();
        collectKeys(body, keys);
        assertThat(keys)
                .as(what)
                .filteredOn(k -> !k.endsWith("_visible"))
                .noneMatch(
                        k -> k.contains("cost") || k.contains("profit") || k.contains("margin") || k.contains("value"));
    }

    static Set<String> ids(JsonNode items) {
        Set<String> out = new TreeSet<>();
        items.forEach(i -> out.add(i.get("id").asString()));
        return out;
    }

    // ---- The till ------------------------------------------------------------------------------------------

    @Test
    void theCashBookIsTheTillsAndASellerWithoutItSeesNone() {
        String day = "branch_id=" + t.headOffice() + "&date=" + today();
        String days = "branch_id=" + t.headOffice() + "&from=" + today() + "&to=" + today();
        List<String> reads = List.of(
                "/reports/cash/daily?" + days,
                "/bankings/expected?" + day,
                "/savings/suggestion?" + day,
                "/savings",
                "/bankings",
                "/expenses",
                "/reports/cash/banking?" + days);
        for (String path : reads) {
            ResponseEntity<JsonNode> r = get(path, tokenA);
            assertThat(r.getStatusCode())
                    .as("a seller: %s %s", path, r.getBody())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        String till = signIn("till", new Role("retail_sales", t.headOffice()), new Role("retail_till", t.headOffice()));
        for (String path : reads) {
            ResponseEntity<JsonNode> r = get(path, till);
            assertThat(r.getStatusCode())
                    .as("the till: %s %s", path, r.getBody())
                    .isEqualTo(HttpStatus.OK);
        }
        JsonNode daily = get("/reports/cash/daily?" + days, till).getBody();
        assertThat(daily.toString()).doesNotContain(t.secondBranch().toString());
        ResponseEntity<JsonNode> elsewhere =
                get("/bankings/expected?branch_id=" + t.secondBranch() + "&date=" + today(), till);
        assertThat(elsewhere.getStatusCode().value())
                .as("%s", elsewhere.getBody())
                .isIn(403, 404, 422);
        String owner = signIn("owner", new Role("tenant_admin", null));
        for (String path : reads) {
            assertThat(get(path, owner).getStatusCode())
                    .as("the owner: %s", path)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    // ---- The supervisor ------------------------------------------------------------------------------------

    @Test
    void aSupervisorSeesEverySellersSalesAtItsBranchOnlyAndNoCost() {
        String supervisor = signIn("supervisor", new Role("retail_supervisor", t.headOffice()));
        JsonNode sales = get("/sales?limit=100", supervisor).getBody();
        assertThat(ids(sales.get("items"))).containsExactlyInAnyOrder(saleA.toString(), saleB.toString());
        assertThat(ids(sales.get("items"))).doesNotContain(saleTwo.toString());
        noCost(sales, "the supervisor's sales list");
        assertThat(get("/sales/" + saleB, supervisor).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/sales/" + saleTwo, supervisor).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode summary = get("/sales/summary?group_by=day&from=" + today() + "&to=" + today(), supervisor)
                .getBody();
        long total = 0;
        for (JsonNode g : summary.get("items")) {
            total += g.get("total_minor").asLong();
        }
        assertThat(total).as("both sellers of head office, not branch two").isEqualTo(21_000);
        ResponseEntity<JsonNode> dashboard = get("/reports/dashboard", supervisor);
        assertThat(dashboard.getStatusCode()).as("%s", dashboard.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(dashboard.getBody().get("today").get("sales_minor").asLong()).isEqualTo(21_000);
        noCost(dashboard.getBody(), "the supervisor's dashboard");
        // Issue #428: a range follows the same scope, with no cost and no ranking of colleagues.
        ResponseEntity<JsonNode> week = get("/reports/dashboard?period=7d", supervisor);
        assertThat(week.getStatusCode()).as("%s", week.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(week.getBody().get("range").get("current").get("sales_minor").asLong())
                .isEqualTo(21_000);
        assertThat(week.getBody().get("range").get("best_shop").get("id").asString())
                .isEqualTo(t.headOffice().toString());
        assertThat(week.getBody().get("range").has("top_salesperson")).isFalse();
        noCost(week.getBody(), "the supervisor's dashboard over 7 days");
        ResponseEntity<JsonNode> analysis = get("/reports/sales-analysis", supervisor);
        assertThat(analysis.getStatusCode()).as("%s", analysis.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(analysis.getBody().get("totals").get("sales_minor").asLong()).isEqualTo(21_000);
        noCost(analysis.getBody(), "the supervisor's analysis");
        for (String path : List.of("/reports/profit/daily", "/reports/margins", "/controls/summary")) {
            assertThat(get(path, supervisor).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(api.get("/api/v1/users", supervisor).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // A Supervisor at head office who also sells at branch two: the dashboard covers the supervised shop, not a
        // refusal.
        String both = signIn(
                "supervisor2",
                new Role("retail_supervisor", t.headOffice()),
                new Role("retail_sales", t.secondBranch()));
        ResponseEntity<JsonNode> mixed = get("/reports/dashboard", both);
        assertThat(mixed.getStatusCode()).as("%s", mixed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(mixed.getBody().get("today").get("sales_minor").asLong()).isEqualTo(21_000);
        assertThat(get("/reports/dashboard?branch_id=" + t.secondBranch(), both).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // Issue #428: a range does not widen it. A branch outside the caller's sales scope covers nothing, so a
        // Supervisor
        // asking for it gets an empty report, as without a range (AnalyticsSupport.everySellerFilter), and a Supervisor
        // who
        // sells there is refused.
        for (String period : List.of("period=7d", "period=all", "period=today")) {
            ResponseEntity<JsonNode> outside =
                    get("/reports/dashboard?" + period + "&branch_id=" + t.secondBranch(), supervisor);
            assertThat(outside.getStatusCode())
                    .as("%s: %s", period, outside.getBody())
                    .isEqualTo(HttpStatus.OK);
            assertThat(outside.getBody()
                            .get("range")
                            .get("current")
                            .get("sales_minor")
                            .asLong())
                    .as(period)
                    .isZero();
            assertThat(outside.getBody().get("shops")).as(period).isEmpty();
            assertThat(outside.getBody().toString())
                    .as(period)
                    .doesNotContain(t.secondBranch().toString());
            assertThat(get("/reports/dashboard?" + period + "&branch_id=" + t.secondBranch(), both)
                            .getStatusCode())
                    .as("also for the supervisor who sells at branch two: %s", period)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        // A seller at the same branch still sees only their own.
        assertThat(ids(get("/sales?limit=100", tokenA).getBody().get("items"))).containsExactly(saleA.toString());
    }

    // ---- Orders --------------------------------------------------------------------------------------------

    /**
     * Decision 4a: the orders are the shop's shared work, so every seller of the branch sees them and their values, but the
     * sale a colleague made on delivery is not linked: no sale id or number, in the list or on the order.
     * JUSTIFICATION-A3: a new test case for decision 4a; the anchor is only the insertion point.
     */
    @Test
    void everySellerSeesTheOrdersAndValuesButNotAColleaguesDeliveredSale() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("source", "whatsapp");
        body.put("customer_name", "Test Customer 09");
        body.put("customer_phone", "+256700000019");
        body.put("delivery_place", "Test Place, Plot 9");
        body.put("delivery_date", today().toString());
        body.put("delivery_fee_minor", 1_000);
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        ResponseEntity<JsonNode> placed = api.call(
                HttpMethod.POST,
                R + "/orders",
                body,
                tokenB,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(placed.getStatusCode()).as("%s", placed.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID order = RetailTestSupport.id(placed);
        ResponseEntity<JsonNode> delivered = api.call(
                HttpMethod.POST,
                R + "/orders/" + order + "/deliver",
                Map.of("payment_method", "cash"),
                tokenB,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(delivered.getStatusCode()).as("%s", delivered.getBody()).isEqualTo(HttpStatus.OK);
        String sale = delivered.getBody().get("sale_id").asString();
        String saleNo = delivered.getBody().get("sale_no").asString();

        JsonNode listed = null;
        for (JsonNode o : get("/orders", tokenA).getBody().get("items")) {
            if (o.get("id").asString().equals(order.toString())) {
                listed = o;
            }
        }
        assertThat(listed).as("a colleague's order is on the list").isNotNull();
        JsonNode one = get("/orders/" + order, tokenA).getBody();
        for (JsonNode o : List.of(listed, one)) {
            assertThat(o.get("status").asString()).isEqualTo("delivered");
            assertThat(o.get("items_total_minor").asLong()).isEqualTo(7_000);
            assertThat(o.get("total_minor").asLong()).isEqualTo(8_000);
            assertThat(o.has("sale_id")).as("no link to the colleague's sale").isFalse();
            assertThat(o.has("sale_no")).isFalse();
            assertThat(o.toString()).doesNotContain(sale).doesNotContain(saleNo).doesNotContain(sellerB.toString());
            assertThat(o.has("created_by"))
                    .as("who took the order is not named")
                    .isFalse();
        }
        assertThat(get("/sales/" + sale, tokenA).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The history says what happened, not who did it nor which sale it became.
        JsonNode events = one.get("history");
        assertThat(events).isNotEmpty();
        for (JsonNode e : events) {
            assertThat(e.has("by_user")).as("%s", e).isFalse();
            assertThat(e.has("by_name")).as("%s", e).isFalse();
            if (e.has("note")) {
                assertThat(e.get("note").asString()).doesNotContain(saleNo);
            }
        }
        JsonNode own = get("/orders/" + order, tokenB).getBody();
        assertThat(own.get("history").toString()).contains(saleNo).contains(sellerB.toString());
        // The seller who delivered it, and the owner, see the sale.
        assertThat(get("/orders/" + order, tokenB).getBody().get("sale_id").asString())
                .isEqualTo(sale);
        String owner = signIn("owner3", new Role("tenant_admin", null));
        assertThat(get("/orders/" + order, owner).getBody().get("sale_id").asString())
                .isEqualTo(sale);
    }

    /**
     * Decision 3: the payments on a colleague's credit sale are open to the seller at the till, without saying who took them.
     * JUSTIFICATION-A3: a new test case for the payments list; the anchor is only the insertion point.
     */
    @Test
    void aColleaguesPartPaymentIsListedWithoutWhoTookIt() {
        UUID credit =
                sell(tokenB, t.headOffice(), "1", Map.of("payment_method", "credit", "buyer_name", "Test Buyer 08"));
        ResponseEntity<JsonNode> paid = api.call(
                HttpMethod.POST,
                R + "/sales/" + credit + "/payments",
                Map.of("amount_minor", 3_000, "method", "cash"),
                tokenB,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode seen = get("/sales/" + credit + "/payments", tokenA).getBody().get("items");
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).get("amount_minor").asLong()).isEqualTo(3_000);
        assertThat(seen.get(0).has("created_by")).isFalse();
        assertThat(seen.toString()).doesNotContain(sellerB.toString());
        JsonNode theirs =
                get("/sales/" + credit + "/payments", tokenB).getBody().get("items");
        assertThat(theirs.get(0).get("created_by").asString()).isEqualTo(sellerB.toString());
    }

    // ---- A buyer's account ---------------------------------------------------------------------------------

    @Test
    void aSellerOpensABuyersAccountWithAColleaguesSaleAndSeesTheAmountButNotTheColleague() {
        String settings = ADMIN + ",core.settings.manage";
        String etag =
                retail.get("/settings/capabilities", settings).getHeaders().getETag();
        ResponseEntity<JsonNode> on = retail.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("customer_accounts", true),
                settings,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        UUID buyer = RetailTestSupport.id(retail.post("/customers", Map.of("name", "Test Buyer 07"), ADMIN));
        UUID credit = sell(tokenB, t.headOffice(), "1", Map.of("payment_method", "credit", "customer_id", buyer));

        for (String path : List.of(
                "/customers/" + buyer + "/account",
                "/customers/" + buyer + "/statement",
                "/customers/" + buyer + "/payments",
                "/customers/" + buyer + "/balance",
                "/reports/credit-control",
                "/reports/customer-ageing",
                "/sales/" + credit)) {
            ResponseEntity<JsonNode> r = get(path, tokenA);
            assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.OK);
            String body = r.getBody().toString();
            assertThat(body).as("%s names no colleague", path).doesNotContain(sellerB.toString());
            noCost(r.getBody(), path);
        }
        assertThat(get("/customers/" + buyer + "/statement", tokenA).getBody().toString())
                .as("the amount of the colleague's sale is there")
                .contains("7000");
    }
}
