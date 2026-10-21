package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Issue #399, ADR-055, slice 1: the retail roles of a business, signed in for real, against the retail routes. A storekeeper
 * moves, receives and counts stock and reports usage at its own branches and nothing else (no sale, price, purchase, cash book,
 * profit or cost); Purchasing, given on top of the Sales role, records restocks at its own branches only; the Sales role is
 * refused a restock; and an administrator with no branch assignment sees and acts in every branch, every report, Controls,
 * profit and cost, and stays so when a storekeeper role for one branch is added to the same person. The owner assigns a
 * person to two branches through the Users API and the person's scope is exactly those two. Names and amounts are fabricated.
 */
class RetailRolesIT extends IntegrationTest {

    static final String R = "/api/v1/retail";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport retail;
    Api api;
    UUID product;
    UUID saleHq;
    UUID saleTwo;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-roles", false, true);
        retail = new RetailTestSupport(http, t);
        api = Api.tenant(http, t.slug());
        product = retail.product("ROLE-1", 4_000, 7_000);
        retail.stockUp(t.headOffice(), product, "20");
        retail.stockUp(t.secondBranch(), product, "20");
        saleHq = RetailTestSupport.id(retail.sell(t.headOffice(), "cash", product, "1"));
        saleTwo = RetailTestSupport.id(retail.sell(t.secondBranch(), "cash", product, "1"));
    }

    static LocalDate today() {
        return LocalDate.now(BusinessClock.DEFAULT_ZONE);
    }

    String signIn(String name, Role... roles) {
        String email = Api.email(name);
        UUID id = Api.staff(t, email, roles);
        return api.signIn(id, email, Api.PASSWORD, null).accessToken();
    }

    ResponseEntity<JsonNode> get(String path, String token) {
        return api.get(R + path, token);
    }

    ResponseEntity<JsonNode> post(String path, Object body, String token) {
        return api.call(
                HttpMethod.POST,
                R + path,
                body,
                token,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    static void allowed(ResponseEntity<JsonNode> r, String what) {
        assertThat(r.getStatusCode().is2xxSuccessful())
                .as("%s: %s %s", what, r.getStatusCode(), r.getBody())
                .isTrue();
    }

    /** The route itself is refused: the role lacks its permission. */
    static void forbidden(ResponseEntity<JsonNode> r, String what) {
        assertThat(r.getStatusCode()).as("%s: %s", what, r.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().get("code").asString()).isEqualTo("permission_denied");
    }

    /** The role has the permission but not at that branch: refused as a branch outside the scope, or not found. */
    static void outsideScope(ResponseEntity<JsonNode> r, String what) {
        int status = r.getStatusCode().value();
        assertThat(status).as("%s: %s %s", what, status, r.getBody()).isIn(403, 404, 422);
        if (status == 422) {
            assertThat(r.getBody().toString()).as(what).contains("unknown_branch");
        }
    }

    Map<String, Object> move(UUID from, UUID to) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("from_branch_id", from);
        b.put("to_branch_id", to);
        b.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        return b;
    }

    Map<String, Object> count(UUID branch) {
        return Map.of("branch_id", branch, "lines", List.of(Map.of("product_id", product, "counted_qty", "19")));
    }

    Map<String, Object> usage(UUID branch) {
        return Map.of(
                "branch_id",
                branch,
                "kind",
                "damaged",
                "reason",
                "Test breakage",
                "lines",
                List.of(Map.of("product_id", product, "qty", "1")));
    }

    Map<String, Object> sale(UUID branch) {
        return Map.of(
                "branch_id",
                branch,
                "payment_method",
                "cash",
                "lines",
                List.of(Map.of("product_id", product, "qty", "1")));
    }

    Map<String, Object> restock(UUID branch) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", 4_000);
        line.put("qty_by_branch", List.of(Map.of("branch_id", branch, "qty", "2")));
        return Map.of("purchased_on", today().toString(), "payment_method", "cash", "lines", List.of(line));
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

    /**
     * No cost, profit, margin, stock value or selling price under any key (a storekeeper sees no prices, the owner's decision of 21
     * October 2026); a flag saying the figure is hidden is not a figure.
     */
    static void noCost(ResponseEntity<JsonNode> r, String what) {
        allowed(r, what);
        List<String> keys = new ArrayList<>();
        collectKeys(r.getBody(), keys);
        assertThat(keys)
                .as(what)
                .filteredOn(k -> !k.endsWith("_visible"))
                .noneMatch(k -> k.contains("cost")
                        || k.contains("profit")
                        || k.contains("margin")
                        || k.contains("value")
                        || k.contains("expected_sales")
                        || k.contains("sell")
                        || k.contains("price"));
    }

    static Set<String> branchIds(JsonNode items) {
        Set<String> out = new TreeSet<>();
        items.forEach(i -> out.add(i.get("branch_id").asString()));
        return out;
    }

    // ---- Storekeeper ---------------------------------------------------------------------------------------

    @Test
    void aStorekeeperMovesCountsAndReportsStockAtItsOwnBranchesOnly() {
        String keeper = signIn("keeper", new Role("retail_storekeeper", t.headOffice()));
        allowed(post("/transfers", move(t.headOffice(), t.secondBranch()), keeper), "send from its store");
        outsideScope(post("/transfers", move(t.secondBranch(), t.headOffice()), keeper), "send from another branch");
        ResponseEntity<JsonNode> counted = post("/stocktakes", count(t.headOffice()), keeper);
        allowed(counted, "count its store");
        allowed(post("/stocktakes/" + RetailTestSupport.id(counted) + "/commit", Map.of(), keeper), "commit its count");
        outsideScope(post("/stocktakes", count(t.secondBranch()), keeper), "count another branch");
        allowed(post("/usage", usage(t.headOffice()), keeper), "report damage in its store");
        outsideScope(post("/usage", usage(t.secondBranch()), keeper), "report damage elsewhere");
        noCost(get("/stock?branch_id=" + t.headOffice(), keeper), "stock of its store, without cost");
        noCost(get("/stock/movements", keeper), "movements, without cost");
        outsideScope(get("/stock?branch_id=" + t.secondBranch(), keeper), "stock of another branch");
        // No stock value either, even at the selling price; the quantities stay.
        noCost(get("/reports/valuation", keeper), "valuation, without value");
        noCost(get("/stock/summary?group_by=branch", keeper), "stock summary, without value");
        noCost(get("/reports/stock-health", keeper), "stock health, without value");
        assertThat(get("/reports/valuation", keeper).getBody().get("rows")).isNotEmpty();
        // No selling price either, on any item or stock response, and no price history.
        noCost(get("/products", keeper), "items, without prices");
        noCost(get("/products/" + product, keeper), "one item, without prices");
        noCost(get("/stock/all-branches", keeper), "stock of every branch in scope, without prices");
        ResponseEntity<JsonNode> location = get("/reports/stock-by-location", keeper);
        if (location.getStatusCode().is2xxSuccessful()) {
            noCost(location, "stock by location, without prices");
        }
        forbidden(get("/products/" + product + "/price-history", keeper), "price history");
        assertThat(get("/products/" + product, keeper).getBody().get("code").asString())
                .isEqualTo("ROLE-1");
    }

    @Test
    void aStorekeeperReceivesAtItsStoreAndCountsAStoreThatDoesNotSell() {
        // The second branch is a store (ADR-048) and the business uses two-step transfers.
        TestDatabase.owner()
                .sql("UPDATE branches SET sells_goods = false WHERE id = ?")
                .param(t.secondBranch())
                .update();
        String settings = "core.settings.manage";
        String etag =
                retail.get("/settings/capabilities", settings).getHeaders().getETag();
        ResponseEntity<JsonNode> on = retail.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("transfer_receipt", true),
                settings,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        UUID sent = RetailTestSupport.id(retail.postKeyed(
                "/transfers",
                move(t.headOffice(), t.secondBranch()),
                ADMIN,
                "*",
                UUID.randomUUID().toString()));

        String store = signIn("store-keeper", new Role("retail_storekeeper", t.secondBranch()));
        String shop = signIn("shop-keeper", new Role("retail_storekeeper", t.headOffice()));
        outsideScope(post("/transfers/" + sent + "/receive", Map.of(), shop), "receive at another branch");
        allowed(post("/transfers/" + sent + "/receive", Map.of(), store), "receive at its store");
        ResponseEntity<JsonNode> counted = post("/stocktakes", count(t.secondBranch()), store);
        allowed(counted, "count the store");
        allowed(post("/stocktakes/" + RetailTestSupport.id(counted) + "/commit", Map.of(), store), "commit the count");
    }

    @Test
    void aStorekeeperCannotSellPriceBuyOrSeeMoney() {
        String keeper = signIn("keeper2", new Role("retail_storekeeper", t.headOffice()));
        forbidden(post("/sales", sale(t.headOffice()), keeper), "sell");
        forbidden(get("/sales", keeper), "read sales");
        forbidden(get("/sales/" + saleHq, keeper), "read a sale");
        forbidden(post("/purchases", restock(t.headOffice()), keeper), "restock");
        forbidden(get("/purchases", keeper), "read restocks");
        String etag = retail.get("/products/" + product, ADMIN).getHeaders().getETag();
        forbidden(
                api.call(
                        HttpMethod.POST,
                        R + "/products/" + product + "/prices",
                        Map.of("sell_minor", 7_500, "reason", "Test change"),
                        keeper,
                        Map.of("If-Match", etag)),
                "change a price");
        forbidden(get("/reports/profit/daily?from=" + today() + "&to=" + today(), keeper), "daily profit");
        forbidden(get("/reports/margins", keeper), "margins");
        forbidden(get("/reports/dashboard", keeper), "owner dashboard");
        forbidden(get("/reports/cash/daily", keeper), "cash book");
        forbidden(get("/controls/summary", keeper), "Controls");
        forbidden(post("/customers", Map.of("name", "Test Buyer 09"), keeper), "credit buyers");
    }

    // ---- Purchasing and Sales --------------------------------------------------------------------------------

    @Test
    void purchasingIsGivenOnTopOfSalesAndRecordsRestocksAtItsBranchesOnly() {
        String seller = signIn("seller", new Role("retail_sales", t.headOffice()));
        forbidden(post("/purchases", restock(t.headOffice()), seller), "a seller restocks");
        allowed(post("/sales", sale(t.headOffice()), seller), "a seller sells in its shop");
        outsideScope(post("/sales", sale(t.secondBranch()), seller), "a seller sells in another shop");
        outsideScope(get("/sales/" + saleTwo, seller), "a sale of another shop");

        String buyer = signIn(
                "buyer", new Role("retail_sales", t.headOffice()), new Role("retail_purchasing", t.headOffice()));
        allowed(post("/purchases", restock(t.headOffice()), buyer), "restock its shop");
        outsideScope(post("/purchases", restock(t.secondBranch()), buyer), "restock another branch");
        allowed(post("/sales", sale(t.headOffice()), buyer), "still sells");
        forbidden(get("/reports/profit/daily?from=" + today() + "&to=" + today(), buyer), "no profit for a buyer");
        forbidden(post("/supplier-payments", Map.of(), buyer), "no supplier payments for a buyer");
        // A new selling price applies in every shop, so a restock may set one only with the price permission; the price
        // the
        // item already has may be sent back unchanged, as the restock form does.
        Map<String, Object> line = new java.util.LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", 4_000);
        line.put("sell_minor", 9_000);
        line.put("qty_by_branch", List.of(Map.of("branch_id", t.headOffice(), "qty", "1")));
        Map<String, Object> repriced =
                Map.of("purchased_on", today().toString(), "payment_method", "cash", "lines", List.of(line));
        forbidden(post("/purchases", repriced, buyer), "a buyer sets a new selling price");
        // A buyer reads the buying prices of the restocks they recorded, not the owner's.
        UUID ownersRestock = RetailTestSupport.id(retail.postKeyed(
                "/purchases",
                restock(t.headOffice()),
                ADMIN,
                "*",
                UUID.randomUUID().toString()));
        ResponseEntity<JsonNode> mine = post("/purchases", restock(t.headOffice()), buyer);
        allowed(mine, "the buyer's own restock");
        JsonNode listed = get("/purchases", buyer).getBody().get("items");
        List<String> listedIds = new ArrayList<>();
        listed.forEach(p -> listedIds.add(p.get("id").asString()));
        assertThat(listedIds).contains(RetailTestSupport.id(mine).toString()).doesNotContain(ownersRestock.toString());
        outsideScope(get("/purchases/" + ownersRestock, buyer), "the owner's restock");
        // A seller still sees the selling price, to sell.
        assertThat(get("/products/" + product, seller).getBody().has("sell_minor"))
                .isTrue();
        line.put("sell_minor", 7_000);
        allowed(post("/purchases", repriced, buyer), "a buyer sends the price unchanged");
        assertThat(retail.get("/products/" + product, ADMIN)
                        .getBody()
                        .get("sell_minor")
                        .asLong())
                .isEqualTo(7_000);
    }

    // ---- The administrator -----------------------------------------------------------------------------------

    void seesEverything(String token, String who) {
        JsonNode me = api.get("/api/v1/me", token).getBody();
        assertThat(me.get("all_branches").asBoolean()).as(who).isTrue();
        assertThat(me.get("permission_scopes")
                        .get("retail.profit.read")
                        .get("all_branches")
                        .asBoolean())
                .as(who)
                .isTrue();
        ResponseEntity<JsonNode> sales = get("/sales", token);
        allowed(sales, who + " lists sales");
        assertThat(branchIds(sales.getBody().get("items")))
                .as(who + " sees the sales of every branch")
                .containsExactlyInAnyOrder(
                        t.headOffice().toString(), t.secondBranch().toString());
        sales.getBody()
                .get("items")
                .forEach(s -> assertThat(s.has("profit_minor")).as(who).isTrue());
        assertThat(get("/sales/" + saleTwo, token).getBody().has("profit_minor"))
                .as(who)
                .isTrue();
        ResponseEntity<JsonNode> valuation = get("/reports/valuation", token);
        allowed(valuation, who + " valuation");
        assertThat(branchIds(valuation.getBody().get("branches")))
                .containsExactlyInAnyOrder(
                        t.headOffice().toString(), t.secondBranch().toString());
        assertThat(valuation.getBody().has("value_at_cost_minor"))
                .as(who + " sees the value at cost across branches")
                .isTrue();
        allowed(get("/reports/profit/daily?from=" + today() + "&to=" + today(), token), who + " daily profit");
        allowed(get("/reports/margins", token), who + " margins");
        allowed(get("/reports/dashboard", token), who + " dashboard");
        allowed(get("/reports/business-evaluation", token), who + " evaluation");
        allowed(get("/reports/stock-by-location", token), who + " stock by location");
        allowed(get("/controls/summary", token), who + " Controls");
        allowed(get("/stock?branch_id=" + t.secondBranch(), token), who + " stock of branch two");
        assertThat(get("/stock?branch_id=" + t.secondBranch(), token)
                        .getBody()
                        .get("items")
                        .get(0)
                        .has("cost_minor"))
                .as(who + " sees cost")
                .isTrue();
        allowed(post("/transfers", move(t.secondBranch(), t.headOffice()), token), who + " moves stock anywhere");
        allowed(post("/purchases", restock(t.secondBranch()), token), who + " restocks anywhere");
        allowed(post("/sales", sale(t.secondBranch()), token), who + " sells anywhere");
    }

    @Test
    void anAdministratorWithNoBranchAssignmentSeesAndActsInEveryBranch() {
        seesEverything(signIn("owner", new Role("tenant_admin", null)), "the owner");
    }

    @Test
    void anAdministratorIsNotNarrowedByAStoreRoleForOneBranch() {
        seesEverything(
                signIn("owner2", new Role("tenant_admin", null), new Role("retail_storekeeper", t.headOffice())),
                "an owner who is also a storekeeper");
    }

    @Test
    void anAdministratorCannotBeLimitedToABranch() {
        String owner = signIn("owner3", new Role("tenant_admin", null));
        UUID other = Api.staff(t, Api.email("other"), new Role("retail_sales", t.headOffice()));
        ResponseEntity<JsonNode> limited = api.call(
                HttpMethod.PUT,
                "/api/v1/users/" + other + "/roles",
                List.of(Map.of("role_key", "tenant_admin", "branch_id", t.headOffice())),
                owner);
        assertThat(limited.getStatusCode()).as("%s", limited.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("""
                                INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, branch_id, granted_by)
                                VALUES (gen_random_uuid(), ?, ?, 'tenant_admin', ?, ?)
                                """)
                        .params(t.tenantId(), other, t.headOffice(), other)
                        .update())
                .hasStackTraceContaining("user_role_assignments_admin_every_branch");
    }

    // ---- The owner assigns the branches --------------------------------------------------------------------

    @Test
    void theOwnerAssignsAStorekeeperToTheBranchesTheyWorkIn() {
        String owner = signIn("owner4", new Role("tenant_admin", null));
        String email = Api.email("assigned");
        UUID person = Api.staff(t, email, new Role("retail_sales", t.headOffice()));
        ResponseEntity<JsonNode> set = api.call(
                HttpMethod.PUT,
                "/api/v1/users/" + person + "/roles",
                List.of(
                        Map.of("role_key", "retail_storekeeper", "branch_id", t.headOffice()),
                        Map.of("role_key", "retail_storekeeper", "branch_id", t.secondBranch())),
                owner);
        assertThat(set.getStatusCode()).as("%s", set.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(set.getBody().get("roles")).hasSize(2);

        String keeper = api.signIn(person, email, Api.PASSWORD, null).accessToken();
        JsonNode me = api.get("/api/v1/me", keeper).getBody();
        assertThat(me.get("all_branches").asBoolean()).isFalse();
        Set<String> scope = new TreeSet<>();
        me.get("permission_scopes")
                .get("retail.stock.transfer")
                .get("branch_ids")
                .forEach(b -> scope.add(b.asString()));
        assertThat(scope)
                .containsExactlyInAnyOrder(
                        t.headOffice().toString(), t.secondBranch().toString());
        assertThat(me.get("permission_scopes").has("retail.sale.create"))
                .as("the sales role was replaced")
                .isFalse();
        allowed(post("/transfers", move(t.secondBranch(), t.headOffice()), keeper), "send from the second store");
        forbidden(post("/sales", sale(t.headOffice()), keeper), "no longer sells");
    }
}
