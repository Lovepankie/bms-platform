package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
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
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The sales grouped by a chain of levels (issue #341): shop, year, month, salesperson, day, product category and item,
 * each one query with the parent selection passed as filters. Names and amounts are fabricated. Product A costs 1,500
 * and sells for 2,000 (profit 500 a unit); product B costs 100 and sells for 300 (profit 200 a unit).
 */
class RetailSalesGroupingChainIT extends IntegrationTest {

    static final String CHAIN = "branch,year,month,seller,day,category,item";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport one;
    RetailTestSupport two;
    UUID a;
    UUID b;
    UUID categoryA;
    UUID categoryB;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-chain", false, true);
        one = new RetailTestSupport(http, t);
        two = new RetailTestSupport(http, t);
        user(one.userId(), "Test Seller One", "active");
        user(two.userId(), "Test Seller Two", "deactivated");
        a = one.product("CHN-A", 1_500, 2_000);
        b = one.product("CHN-B", 100, 300);
        categoryA = category(a);
        categoryB = category(b);
        for (UUID branch : List.of(t.headOffice(), t.secondBranch())) {
            one.stockUp(branch, a, "300");
            one.stockUp(branch, b, "300");
        }
        sale(one, t.headOffice(), "cash", "2026-10-19", List.of(line(a, "2"), line(b, "1")));
        sale(two, t.headOffice(), "mobile_money", "2026-10-19", List.of(line(a, "1")));
        sale(one, t.secondBranch(), "cash", "2026-10-18", List.of(line(b, "3")));
        sale(one, t.headOffice(), "credit", "2025-12-31", List.of(line(a, "1")));
        UUID voided = RetailTestSupport.id(sale(one, t.headOffice(), "cash", "2026-10-19", List.of(line(a, "5"))));
        ResponseEntity<JsonNode> v = one.post("/sales/" + voided + "/void", Map.of("reason", "Test wrong item"), ADMIN);
        assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.OK);
    }

    void user(UUID id, String name, String status) {
        user(t, id, name, status);
    }

    static void user(TestDatabase.Fixture t, UUID id, String name, String status) {
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', ?, ?, ?)")
                .params(id, t.tenantId(), name, "u" + id + "@example.test", status)
                .update();
    }

    UUID category(UUID product) {
        return TestDatabase.owner()
                .sql("SELECT category_id FROM retail_products WHERE id = ?")
                .param(product)
                .query(UUID.class)
                .single();
    }

    static Map<String, Object> line(UUID product, String qty) {
        return Map.of("product_id", product, "qty", qty);
    }

    ResponseEntity<JsonNode> sale(
            RetailTestSupport as, UUID branch, String method, String date, List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", method);
        body.put("sale_date", date);
        if (method.equals("credit")) {
            body.put("buyer_name", "Test Buyer 01");
        }
        body.put("lines", lines);
        ResponseEntity<JsonNode> r =
                as.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r;
    }

    ResponseEntity<JsonNode> call(RetailTestSupport as, String query, String permissions, Map<String, String> extra) {
        return as.call(HttpMethod.GET, "/sales/summary?" + query, null, permissions, "*", extra);
    }

    JsonNode level(String level, String filters) {
        return level(level, filters, ADMIN);
    }

    JsonNode level(String level, String filters, String permissions) {
        String q = "group_by=" + CHAIN + "&level=" + level + (filters.isEmpty() ? "" : "&" + filters);
        ResponseEntity<JsonNode> r = call(one, q, permissions, Map.of());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static JsonNode only(JsonNode summary, String key) {
        for (JsonNode g : summary.get("items")) {
            if (g.get("key").asString().equals(key)) {
                return g;
            }
        }
        throw new AssertionError("no group " + key + " in " + summary);
    }

    static long total(JsonNode group, String method) {
        return group.get("by_method").get(method).get("total_minor").asLong();
    }

    @Test
    void theChainStartsAtTheShopAndNamesTheLevelUnderIt() {
        JsonNode shops = level("branch", "");
        assertThat(shops.get("group_by").asString()).isEqualTo("branch");
        assertThat(shops.get("next_level").asString()).isEqualTo("year");
        assertThat(shops.get("levels")).hasSize(7);
        assertThat(shops.get("available_levels")).hasSize(7);
        assertThat(shops.get("items")).hasSize(2);
        JsonNode head = shops.get("items").get(0);
        assertThat(head.get("key").asString()).isEqualTo(t.headOffice().toString());
        assertThat(head.get("label").asString()).isEqualTo("Head Office");
        assertThat(head.get("sales_count").asLong()).isEqualTo(3);
        assertThat(head.get("total_minor").asLong()).isEqualTo(8_300);
        assertThat(head.get("profit_minor").asLong()).isEqualTo(2_200);
        assertThat(head.get("voided_count").asLong()).isEqualTo(1);
        assertThat(head.has("period")).isFalse();
        JsonNode second = shops.get("items").get(1);
        assertThat(second.get("label").asString()).isEqualTo("Test Branch Two");
        assertThat(second.get("total_minor").asLong()).isEqualTo(900);
        assertThat(second.get("profit_minor").asLong()).isEqualTo(600);
    }

    @Test
    void theOwnerCanWalkFromTheShopDownToTheItems() {
        String hq = "branch_id=" + t.headOffice();
        JsonNode years = level("year", hq);
        assertThat(years.get("next_level").asString()).isEqualTo("month");
        assertThat(only(years, "2026").get("total_minor").asLong()).isEqualTo(6_300);
        assertThat(only(years, "2025").get("total_minor").asLong()).isEqualTo(2_000);

        String year = hq + "&from=2026-01-01&to=2026-12-31";
        JsonNode months = level("month", year);
        assertThat(months.get("items")).hasSize(1);
        assertThat(months.get("items").get(0).get("key").asString()).isEqualTo("2026-10");

        String month = hq + "&from=2026-10-01&to=2026-10-31";
        JsonNode sellers = level("seller", month);
        assertThat(sellers.get("next_level").asString()).isEqualTo("day");
        JsonNode s1 = only(sellers, one.userId().toString());
        assertThat(s1.get("label").asString()).isEqualTo("Test Seller One");
        assertThat(s1.get("total_minor").asLong()).isEqualTo(4_300);
        assertThat(s1.get("voided_count").asLong()).isEqualTo(1);
        JsonNode s2 = only(sellers, two.userId().toString());
        assertThat(s2.get("label").asString())
                .as("a deactivated user keeps the name")
                .isEqualTo("Test Seller Two");
        assertThat(s2.get("total_minor").asLong()).isEqualTo(2_000);
        assertThat(sellers.get("items").get(0).get("key").asString())
                .as("the one who sold most comes first")
                .isEqualTo(one.userId().toString());

        String seller = month + "&seller_id=" + one.userId();
        JsonNode days = level("day", seller);
        assertThat(days.get("items")).hasSize(1);
        assertThat(days.get("items").get(0).get("key").asString()).isEqualTo("2026-10-19");
        assertThat(days.get("items").get(0).get("total_minor").asLong()).isEqualTo(4_300);

        String day = hq + "&from=2026-10-19&to=2026-10-19&seller_id=" + one.userId();
        JsonNode categories = level("category", day);
        assertThat(categories.get("next_level").asString()).isEqualTo("item");
        JsonNode ca = only(categories, categoryA.toString());
        assertThat(ca.get("label").asString()).isEqualTo("Test Category CHN-A");
        assertThat(ca.get("total_minor").asLong()).isEqualTo(4_000);
        assertThat(ca.get("profit_minor").asLong()).isEqualTo(1_000);
        assertThat(ca.get("sales_count").asLong()).isEqualTo(1);
        JsonNode cb = only(categories, categoryB.toString());
        assertThat(cb.get("total_minor").asLong()).isEqualTo(300);
        assertThat(cb.get("profit_minor").asLong()).isEqualTo(200);
        assertThat(ca.get("total_minor").asLong() + cb.get("total_minor").asLong())
                .as("the categories add up to the day")
                .isEqualTo(4_300);

        JsonNode items = level("item", day + "&category_id=" + categoryA);
        assertThat(items.has("next_level")).isFalse();
        assertThat(items.get("items")).hasSize(1);
        JsonNode item = items.get("items").get(0);
        assertThat(item.get("key").asString()).isEqualTo(a.toString());
        assertThat(item.get("label").asString()).isEqualTo("Test Product CHN-A");
        assertThat(item.get("quantity").asString()).isEqualTo("2");
        assertThat(item.get("total_minor").asLong()).isEqualTo(4_000);
        assertThat(item.get("profit_minor").asLong()).isEqualTo(1_000);
    }

    @Test
    void anyOrderOfTheLevelsWorksAndALevelNotInTheChainIsRefused() {
        ResponseEntity<JsonNode> r = call(one, "group_by=category,seller,day&level=category", ADMIN, Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("next_level").asString()).isEqualTo("seller");
        assertThat(only(r.getBody(), categoryA.toString()).get("total_minor").asLong())
                .isEqualTo(8_000);
        assertThat(only(r.getBody(), categoryA.toString()).get("voided_count").asLong())
                .isEqualTo(1);
        assertThat(only(r.getBody(), categoryB.toString()).get("total_minor").asLong())
                .isEqualTo(1_200);

        JsonNode sellers = call(
                        one, "group_by=category,seller,day&level=seller&category_id=" + categoryB, ADMIN, Map.of())
                .getBody();
        assertThat(sellers.get("items")).hasSize(1);
        assertThat(sellers.get("items").get(0).get("total_minor").asLong())
                .as("under a category the seller's figure is the category's lines only")
                .isEqualTo(1_200);

        JsonNode single = call(one, "group_by=month", ADMIN, Map.of()).getBody();
        assertThat(single.get("group_by").asString()).isEqualTo("month");
        assertThat(single.has("next_level")).isFalse();

        for (String bad : List.of(
                "group_by=year,day&level=month",
                "group_by=year,year",
                "group_by=year,week",
                "group_by=year,,day",
                "group_by=")) {
            assertThat(call(one, bad, ADMIN, Map.of()).getStatusCode())
                    .as(bad)
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        assertThat(call(one, "", ADMIN, Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anItemIsChosenFirstAndTheLevelsUnderItAreItsOwnLines() {
        JsonNode days = call(one, "group_by=item,day&level=day&item_id=" + a, ADMIN, Map.of())
                .getBody();
        assertThat(days.get("items")).hasSize(2);
        JsonNode nineteenth = only(days, "2026-10-19");
        assertThat(nineteenth.get("total_minor").asLong()).isEqualTo(6_000);
        assertThat(nineteenth.get("sales_count").asLong()).isEqualTo(2);
        assertThat(nineteenth.get("voided_count").asLong()).isEqualTo(1);
        assertThat(only(days, "2025-12-31").get("total_minor").asLong()).isEqualTo(2_000);
    }

    @Test
    void thePaymentMethodsAreBrokenDownAtEveryLevel() {
        JsonNode categories = level("category", "");
        JsonNode ca = only(categories, categoryA.toString());
        assertThat(total(ca, "cash")).isEqualTo(4_000);
        assertThat(total(ca, "mobile_money")).isEqualTo(2_000);
        assertThat(total(ca, "credit")).isEqualTo(2_000);
        assertThat(total(ca, "bank")).isZero();
        JsonNode items = level("item", "");
        JsonNode item = only(items, a.toString());
        assertThat(item.get("quantity").asString()).isEqualTo("4");
        assertThat(item.get("by_method").get("credit").get("count").asLong()).isEqualTo(1);
        JsonNode sellers = level("seller", "");
        assertThat(total(only(sellers, one.userId().toString()), "cash")).isEqualTo(5_200);
        JsonNode shops = level("branch", "");
        assertThat(total(only(shops, t.secondBranch().toString()), "cash")).isEqualTo(900);
    }

    @Test
    void aVoidedSaleIsOnlyCountedApartAtEveryLevel() {
        for (String lvl : List.of("branch", "year", "month", "seller", "day", "category", "item")) {
            JsonNode all = level(lvl, "from=2026-10-19&to=2026-10-19");
            long cash = 0;
            for (JsonNode g : all.get("items")) {
                cash += total(g, "cash");
            }
            assertThat(cash)
                    .as("cash on the 19th at %s leaves the voided five units out", lvl)
                    .isEqualTo(4_300L);
        }
        assertThat(only(level("item", "from=2026-10-19&to=2026-10-19"), a.toString())
                        .get("quantity")
                        .asString())
                .isEqualTo("3");
    }

    @Test
    void noLevelCarriesProfitWithoutProfitRead() {
        // The salesperson level is not offered to this caller at all (see the next test).
        for (String lvl : List.of("branch", "year", "month", "day", "category", "item")) {
            ResponseEntity<JsonNode> r =
                    call(one, "group_by=branch,year,month,day,category,item&level=" + lvl, SALES, Map.of());
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
            JsonNode groups = r.getBody().get("items");
            assertThat(groups).as(lvl).isNotEmpty();
            for (JsonNode g : groups) {
                assertThat(g.has("profit_minor")).as("%s %s", lvl, g).isFalse();
                assertThat(g.get("total_minor").asLong()).isPositive();
            }
        }
    }

    /** A cashier must not see a ranking of colleagues: the salesperson level needs retail.profit.read somewhere in scope. */
    @Test
    void theSalespersonLevelNeedsProfitReadSomewhereInScope() {
        ResponseEntity<JsonNode> plain = call(one, "group_by=branch,day", SALES, Map.of());
        assertThat(plain.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(plain.getBody().get("available_levels").toString()).doesNotContain("seller");
        assertThat(call(one, "group_by=seller", SALES, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(one, "group_by=branch,seller&level=branch", SALES, Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // ADR-055: a seller sees only their own sales, so naming oneself is allowed and naming a colleague is not.
        assertThat(call(one, "group_by=day&seller_id=" + one.userId(), SALES, Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(one, "group_by=day&seller_id=" + two.userId(), SALES, Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> manager = call(one, "group_by=seller", ADMIN, Map.of());
        assertThat(manager.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(manager.getBody().get("available_levels").toString()).contains("seller");

        // Profit read at one branch only: the salesperson level is that branch's alone, never a ranking across
        // branches.
        Map<String, String> oneBranch = Map.of("X-Dev-Scopes", "retail.profit.read=" + t.secondBranch());
        assertThat(call(one, "group_by=seller", ADMIN, oneBranch).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(one, "group_by=seller&branch_id=" + t.headOffice(), ADMIN, oneBranch)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(
                                one,
                                "group_by=seller&branch_id=" + t.headOffice() + "&branch_id=" + t.secondBranch(),
                                ADMIN,
                                oneBranch)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(call(one, "group_by=seller&branch_id=" + t.secondBranch(), ADMIN, oneBranch)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(
                                one,
                                "group_by=day&seller_id=" + one.userId() + "&branch_id=" + t.headOffice(),
                                ADMIN,
                                oneBranch)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** The sales list is no way round the rule: no seller filter for a cashier, and nobody else's id on a row. */
    @Test
    void theSalesListGivesACashierNoTakingsPerPerson() {
        RetailTestSupport cashier = new RetailTestSupport(http, t);
        assertThat(cashier.get("/sales?seller_id=" + two.userId(), SALES).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cashier.get("/sales?seller_id=" + two.userId() + "&from=2026-10-19&to=2026-10-19", SALES)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // Profit read at one branch is not enough for a list that covers the other.
        Map<String, String> oneBranch = Map.of("X-Dev-Scopes", "retail.profit.read=" + t.headOffice());
        assertThat(one.call(HttpMethod.GET, "/sales?seller_id=" + two.userId(), null, ADMIN, "*", oneBranch)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(one.call(
                                HttpMethod.GET,
                                "/sales?seller_id=" + two.userId() + "&branch_id=" + t.headOffice(),
                                null,
                                ADMIN,
                                "*",
                                oneBranch)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(one.get("/sales?seller_id=" + two.userId(), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // ADR-055: a cashier who made no sale sees none; the second seller sees their one sale, as theirs.
        ResponseEntity<JsonNode> list = cashier.get("/sales?limit=100", SALES);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(list.getBody().get("items")).isEmpty();
        JsonNode own = two.get("/sales?limit=100", SALES).getBody().get("items");
        assertThat(own).hasSize(1);
        assertThat(own.get(0).get("created_by").asString())
                .isEqualTo(two.userId().toString());
        // On the debts list, which shows every debt still owed so the till can take a payment, a colleague's row does
        // not
        // say who made it, so it cannot be summed per person.
        JsonNode debts =
                cashier.get("/sales?limit=100&owing=owing", SALES).getBody().get("items");
        assertThat(debts).isNotEmpty();
        for (JsonNode row : debts) {
            for (String who : List.of("created_by", "voided_by")) {
                JsonNode v = row.get(who);
                assertThat(v == null || v.isNull())
                        .as("%s of a cashier's row for sale %s", who, row.get("sale_no"))
                        .isTrue();
            }
        }
        // The detail of a sale somebody else made is not found.
        JsonNode theirs = null;
        for (JsonNode row : one.get("/sales?limit=100", ADMIN).getBody().get("items")) {
            if (row.get("created_by").asString().equals(two.userId().toString())) {
                theirs = row;
            }
        }
        assertThat(theirs).isNotNull();
        ResponseEntity<JsonNode> detail =
                cashier.get("/sales/" + theirs.get("id").asString(), SALES);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // A person still sees their own sales as theirs, and a manager sees everybody's.
        ResponseEntity<JsonNode> mine = two.get("/sales/" + theirs.get("id").asString(), SALES);
        assertThat(mine.getBody().get("created_by").asString())
                .isEqualTo(two.userId().toString());
        assertThat(one.get("/sales/" + theirs.get("id").asString(), ADMIN)
                        .getBody()
                        .get("created_by")
                        .asString())
                .isEqualTo(two.userId().toString());
    }

    @Test
    void profitReadAtOneBranchOnlyShowsProfitOnlyWhereThatBranchIsAllThatIsCovered() {
        Map<String, String> scoped = Map.of("X-Dev-Scopes", "retail.profit.read=" + t.headOffice());
        // The salesperson level is refused wherever profit read does not cover every branch shown (next test).
        for (String lvl : List.of("branch", "year", "month", "day", "category", "item")) {
            String all = "group_by=branch,year,month,day,category,item&level=" + lvl;
            JsonNode everywhere = call(one, all, ADMIN, scoped).getBody();
            for (JsonNode g : everywhere.get("items")) {
                assertThat(g.has("profit_minor")).as("all branches at %s", lvl).isFalse();
            }
            JsonNode second = call(one, all + "&branch_id=" + t.secondBranch(), ADMIN, scoped)
                    .getBody();
            for (JsonNode g : second.get("items")) {
                assertThat(g.has("profit_minor")).as("second branch at %s", lvl).isFalse();
            }
            JsonNode both = call(
                            one, all + "&branch_id=" + t.headOffice() + "&branch_id=" + t.secondBranch(), ADMIN, scoped)
                    .getBody();
            for (JsonNode g : both.get("items")) {
                assertThat(g.has("profit_minor")).as("both branches at %s", lvl).isFalse();
            }
            JsonNode head = call(one, all + "&branch_id=" + t.headOffice(), ADMIN, scoped)
                    .getBody();
            assertThat(head.get("items")).as(lvl).isNotEmpty();
            for (JsonNode g : head.get("items")) {
                assertThat(g.has("profit_minor")).as("head office at %s", lvl).isTrue();
            }
        }
    }

    @Test
    void theBranchScopeOfTheCallerNarrowsEveryLevel() {
        ResponseEntity<JsonNode> r = one.call(
                HttpMethod.GET,
                "/sales/summary?group_by=" + CHAIN + "&level=branch",
                null,
                ADMIN,
                t.secondBranch().toString(),
                Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("items")).hasSize(1);
        assertThat(r.getBody().get("items").get(0).get("key").asString())
                .isEqualTo(t.secondBranch().toString());
        ResponseEntity<JsonNode> outside = one.call(
                HttpMethod.GET,
                "/sales/summary?group_by=seller&branch_id=" + t.headOffice(),
                null,
                ADMIN,
                t.secondBranch().toString(),
                Map.of());
        assertThat(outside.getBody().get("items")).isEmpty();
    }

    @Test
    void anotherTenantsSalesPeopleAndItemsNeverReachAnyLevel() {
        TestDatabase.Fixture other = TestDatabase.tenant("retail-chain-other", false, true);
        RetailTestSupport theirs = new RetailTestSupport(http, other);
        user(other, theirs.userId(), "Test Stranger", "active");
        UUID p = theirs.product("CHN-Z", 1_500, 2_000);
        theirs.stockUp(other.headOffice(), p, "50");
        sale(theirs, other.headOffice(), "cash", "2026-10-19", List.of(line(p, "9")));

        for (String lvl : List.of("branch", "year", "month", "seller", "day", "category", "item")) {
            String text = level(lvl, "").toString();
            assertThat(text).as(lvl).doesNotContain("Test Stranger").doesNotContain("CHN-Z");
        }
        assertThat(level("year", "").get("items").get(0).get("total_minor").asLong())
                .isEqualTo(7_200);
        JsonNode theirYears = call(theirs, "group_by=year", ADMIN, Map.of()).getBody();
        assertThat(theirYears.get("items")).hasSize(1);
        assertThat(theirYears.get("items").get(0).get("total_minor").asLong()).isEqualTo(18_000);
        // Their person, category and item as filters of my request match nothing of mine.
        assertThat(level("day", "seller_id=" + theirs.userId()).get("items")).isEmpty();
        assertThat(level("day", "item_id=" + p).get("items")).isEmpty();
        assertThat(level("day", "category_id=" + category(p)).get("items")).isEmpty();
    }

    @Test
    void everyLevelIsBoundedAndTheRestIsReachedByTheOffset() {
        UUID cheap = one.product("CHN-C", 100, 200);
        one.stockUp(t.headOffice(), cheap, "500");
        for (int i = 0; i < 51; i++) {
            RetailTestSupport s = new RetailTestSupport(http, t);
            user(s.userId(), "Test Seller " + (100 + i), "active");
            sale(s, t.headOffice(), "cash", "2024-03-0" + (1 + i % 9), List.of(line(cheap, "1")));
        }
        JsonNode page = level("seller", "from=2024-03-01&to=2024-03-31");
        assertThat(page.get("items")).hasSize(50);
        assertThat(page.get("has_more").asBoolean()).isTrue();
        assertThat(page.get("next_offset").asInt()).isEqualTo(50);
        assertThat(page.has("next_to")).isFalse();
        JsonNode rest = level("seller", "from=2024-03-01&to=2024-03-31&offset=50");
        assertThat(rest.get("items")).hasSize(1);
        assertThat(rest.get("has_more").asBoolean()).isFalse();
        assertThat(rest.has("next_offset")).isFalse();

        // 51 items in 51 categories, sold in one sale.
        List<Map<String, Object>> lines = new ArrayList<>();
        List<Map<String, Object>> counted = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            UUID p = one.product("CHN-M" + i, 100, 200);
            counted.add(Map.of("product_id", p, "counted_qty", "5"));
            lines.add(line(p, "1"));
        }
        UUID stocktake = RetailTestSupport.id(
                one.post("/stocktakes", Map.of("branch_id", t.secondBranch(), "lines", counted), ADMIN));
        assertThat(one.post("/stocktakes/" + stocktake + "/commit", Map.of(), ADMIN)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        sale(one, t.secondBranch(), "cash", "2023-05-05", lines);
        for (String lvl : List.of("category", "item")) {
            String range = "from=2023-05-05&to=2023-05-05";
            JsonNode first = level(lvl, range);
            assertThat(first.get("items")).as(lvl).hasSize(50);
            assertThat(first.get("has_more").asBoolean()).isTrue();
            assertThat(first.get("next_offset").asInt()).isEqualTo(50);
            JsonNode last = level(lvl, range + "&offset=50");
            assertThat(last.get("items")).as(lvl).hasSize(1);
            assertThat(last.get("has_more").asBoolean()).isFalse();
        }
    }

    /** The product filter of the top of the page keeps that product's lines under a category or an item. */
    @Test
    void aProductFilterKeepsOnlyThatProductsLinesOverLines() {
        JsonNode categories = call(one, "group_by=category,item&level=category&product_id=" + a, ADMIN, Map.of())
                .getBody();
        assertThat(categories.get("items")).hasSize(1);
        assertThat(only(categories, categoryA.toString()).get("total_minor").asLong())
                .isEqualTo(8_000);
        JsonNode items =
                call(one, "group_by=item&product_id=" + b, ADMIN, Map.of()).getBody();
        assertThat(items.get("items")).hasSize(1);
        assertThat(items.get("items").get(0).get("key").asString()).isEqualTo(b.toString());
        assertThat(items.get("items").get(0).get("total_minor").asLong()).isEqualTo(1_200);
        assertThat(call(one, "group_by=item&category_id=" + categoryB + "&product_id=" + a, ADMIN, Map.of())
                        .getBody()
                        .get("items"))
                .isEmpty();
        assertThat(call(one, "group_by=day&item_id=" + a + "&product_id=" + b, ADMIN, Map.of())
                        .getBody()
                        .get("items"))
                .isEmpty();
        // Over whole sales a product filter still means the sales that hold the product, whole.
        JsonNode days =
                call(one, "group_by=day&product_id=" + b, ADMIN, Map.of()).getBody();
        assertThat(only(days, "2026-10-19").get("total_minor").asLong()).isEqualTo(4_300);
    }

    @Test
    void theOwingFilterAndACategoryAgree() {
        JsonNode owing =
                call(one, "group_by=category&owing=owing", ADMIN, Map.of()).getBody();
        assertThat(owing.get("items")).hasSize(1);
        assertThat(only(owing, categoryA.toString()).get("total_minor").asLong())
                .isEqualTo(2_000);
        assertThat(call(one, "group_by=category&owing=paid", ADMIN, Map.of())
                        .getBody()
                        .get("items"))
                .isEmpty();
        JsonNode items = call(
                        one,
                        "group_by=item&category_id=" + categoryA + "&owing=owing&payment_method=credit",
                        ADMIN,
                        Map.of())
                .getBody();
        assertThat(items.get("items")).hasSize(1);
        assertThat(items.get("items").get(0).get("quantity").asString()).isEqualTo("1");
    }

    /** More than 100 shops: the first 100 come at once and the rest by the offset. */
    @Test
    void theShopLevelIsBoundedAt100() {
        TestDatabase.owner()
                .sql(
                        "INSERT INTO branches (id, tenant_id, code, name) SELECT gen_random_uuid(), ?, 'S' || g, 'Test Shop ' || g"
                                + " FROM generate_series(1, 101) g")
                .param(t.tenantId())
                .update();
        TestDatabase.owner()
                .sql("INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, currency,"
                        + " total_minor, cost_total_minor, paid_minor, status, created_by)"
                        + " SELECT gen_random_uuid(), ?, b.id, 'TS' || lpad(row_number() OVER ()::text, 6, '0'),"
                        + " DATE '2022-01-01', 'cash', 'UGX', 100, 50, 100, 'completed', ?"
                        + " FROM branches b WHERE b.tenant_id = ? AND b.code LIKE 'S%'")
                .params(t.tenantId(), one.userId(), t.tenantId())
                .update();
        JsonNode first = level("branch", "");
        assertThat(first.get("items")).hasSize(100);
        assertThat(first.get("has_more").asBoolean()).isTrue();
        assertThat(first.get("next_offset").asInt()).isEqualTo(100);
        JsonNode rest = level("branch", "offset=100");
        assertThat(rest.get("items")).hasSize(3);
        assertThat(rest.get("has_more").asBoolean()).isFalse();
    }

    @Test
    void anUpsideDownOrNegativeRequestIsRefused() {
        assertThat(call(one, "group_by=day&from=2026-10-19&to=2026-10-01", ADMIN, Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(call(one, "group_by=seller&offset=-1", ADMIN, Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** The list answers the same seller and category filters the chain passes down, so a leaf can show its sales. */
    @Test
    void theSalesListTakesTheSellerAndTheCategoryOfTheChain() {
        ResponseEntity<JsonNode> mine = one.get("/sales?status=completed&seller_id=" + two.userId(), ADMIN);
        assertThat(mine.getBody().get("items")).hasSize(1);
        ResponseEntity<JsonNode> cat = one.get("/sales?status=completed&category_id=" + categoryB, ADMIN);
        assertThat(cat.getBody().get("items")).hasSize(2);
        ResponseEntity<JsonNode> both =
                one.get("/sales?status=completed&category_id=" + categoryB + "&seller_id=" + two.userId(), ADMIN);
        assertThat(both.getBody().get("items")).isEmpty();
    }
}
