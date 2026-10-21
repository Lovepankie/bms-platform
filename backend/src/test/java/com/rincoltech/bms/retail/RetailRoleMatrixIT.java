package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
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
 * Issue #399, ADR-055, slice 1: a table of role, route and branch. Each row is a signed-in user of one role at head office
 * calling one retail route, and what must come back: refused because the role lacks the permission (403), refused or not found
 * because the record is at another branch, or answered without a single record of another branch. The records of the other
 * branches are made by the owner first: a transfer between the second and a third branch, a stock-take, a restock, a purchase
 * order and a goods receipt at the second branch. Names and amounts are fabricated.
 */
class RetailRoleMatrixIT extends IntegrationTest {

    static final String R = "/api/v1/retail";
    static final String OWNER = ADMIN + ",core.settings.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport retail;
    Api api;
    UUID third;
    UUID product;
    UUID supplier;
    UUID transferAway;
    UUID stocktakeTwo;
    UUID restockTwo;
    UUID orderTwo;
    UUID receiptTwo;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-matrix", false, true);
        retail = new RetailTestSupport(http, t);
        api = Api.tenant(http, t.slug());
        third = UUID.randomUUID();
        TestDatabase.owner()
                .sql("INSERT INTO branches (id, tenant_id, code, name) VALUES (?, ?, 'BR3', 'Test Branch Three')")
                .params(third, t.tenantId())
                .update();
        product = retail.product("MTX-1", 4_000, 7_000);
        retail.stockUp(t.headOffice(), product, "20");
        retail.stockUp(t.secondBranch(), product, "20");
        String etag = retail.get("/settings/capabilities", OWNER).getHeaders().getETag();
        ResponseEntity<JsonNode> on = retail.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("purchasing", true),
                OWNER,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        supplier = RetailTestSupport.id(retail.post("/suppliers", Map.of("name", "Test Supplier 01"), OWNER));

        transferAway = RetailTestSupport.id(ownerPost("/transfers", move(t.secondBranch(), third)));
        stocktakeTwo = RetailTestSupport.id(ownerPost(
                "/stocktakes",
                Map.of(
                        "branch_id",
                        t.secondBranch(),
                        "lines",
                        List.of(Map.of("product_id", product, "counted_qty", "19")))));
        restockTwo = RetailTestSupport.id(ownerPost("/purchases", restock(t.secondBranch())));
        orderTwo = RetailTestSupport.id(ownerPost("/purchase-orders", order(t.secondBranch())));
        receiptTwo = RetailTestSupport.id(ownerPost("/goods-receipts", receipt(t.secondBranch(), null)));
    }

    ResponseEntity<JsonNode> ownerPost(String path, Object body) {
        ResponseEntity<JsonNode> r =
                retail.postKeyed(path, body, OWNER, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode().is2xxSuccessful())
                .as("%s %s", path, r.getBody())
                .isTrue();
        return r;
    }

    Map<String, Object> move(UUID from, UUID to) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("from_branch_id", from);
        b.put("to_branch_id", to);
        b.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        return b;
    }

    Map<String, Object> restock(UUID branch) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", 4_000);
        line.put("qty_by_branch", List.of(Map.of("branch_id", branch, "qty", "2")));
        return Map.of(
                "purchased_on", RetailRolesIT.today().toString(), "payment_method", "cash", "lines", List.of(line));
    }

    Map<String, Object> order(UUID branch) {
        return Map.of(
                "supplier_id",
                supplier,
                "branch_id",
                branch,
                "lines",
                List.of(Map.of("product_id", product, "qty", "3", "cost_minor", 4_000)));
    }

    Map<String, Object> receipt(UUID branch, Long sell) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("qty", "1");
        line.put("cost_minor", 4_000);
        if (sell != null) {
            line.put("sell_minor", sell);
        }
        return Map.of("supplier_id", supplier, "branch_id", branch, "lines", List.of(line));
    }

    String signIn(String name, Role... roles) {
        String email = Api.email(name);
        UUID id = Api.staff(t, email, roles);
        return api.signIn(id, email, Api.PASSWORD, null).accessToken();
    }

    /** What a row must answer. */
    enum Expect {
        /** 403 permission_denied: the role lacks the route's permission. */
        FORBIDDEN,
        /** The record is at a branch outside the scope: 403, 404 or 422 unknown_branch. */
        OUTSIDE,
        /** Either refused as OUTSIDE, or answered with no record and no id of a branch outside the scope. */
        HIDDEN
    }

    record Row(HttpMethod method, String path, Expect expect) {}

    static Row get(String path, Expect expect) {
        return new Row(HttpMethod.GET, path, expect);
    }

    static Row post(String path, Expect expect) {
        return new Row(HttpMethod.POST, path, expect);
    }

    /** Runs every row for one signed-in user and reports every row that answered otherwise, all at once. */
    void check(String token, String who, List<Row> rows) {
        List<String> wrong = new ArrayList<>();
        List<String> foreign = List.of(
                t.secondBranch().toString(),
                third.toString(),
                transferAway.toString(),
                stocktakeTwo.toString(),
                restockTwo.toString(),
                orderTwo.toString(),
                receiptTwo.toString());
        for (Row row : rows) {
            ResponseEntity<JsonNode> r = api.call(
                    row.method(),
                    R + row.path(),
                    row.method() == HttpMethod.GET
                            ? null
                            : row.path().endsWith("/void") || row.path().endsWith("/cancel")
                                    ? Map.of("reason", "Test reason for the matrix")
                                    : Map.of(),
                    token,
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            int status = r.getStatusCode().value();
            String body = r.getBody() == null ? "" : r.getBody().toString();
            boolean refused = status == 403 || status == 404 || (status == 422 && body.contains("unknown_branch"));
            boolean ok = switch (row.expect()) {
                case FORBIDDEN -> status == 403 && body.contains("permission_denied");
                case OUTSIDE -> refused;
                case HIDDEN -> refused || (status == 200 && foreign.stream().noneMatch(body::contains));
            };
            if (!ok) {
                wrong.add(
                        row.method() + " " + row.path() + " expected " + row.expect() + " got " + status + " " + body);
            }
        }
        assertThat(wrong).as("rows that answered otherwise for %s", who).isEmpty();
    }

    @Test
    void aStorekeeperAtHeadOfficeReadsNothingOfAnotherBranchAndNoMoneyAnywhere() {
        String keeper = signIn("keeper", new Role("retail_storekeeper", t.headOffice()));
        String two = "branch_id=" + t.secondBranch();
        check(
                keeper,
                "a storekeeper",
                List.of(
                        // Stock of another branch.
                        get("/transfers", Expect.HIDDEN),
                        get("/transfers/" + transferAway, Expect.OUTSIDE),
                        get("/stocktakes/" + stocktakeTwo, Expect.OUTSIDE),
                        get("/stock/movements?" + two, Expect.HIDDEN),
                        get("/stock?" + two, Expect.OUTSIDE),
                        get("/stock/all-branches", Expect.HIDDEN),
                        get("/stock/summary?group_by=branch", Expect.HIDDEN),
                        get("/reports/stock-by-location", Expect.HIDDEN),
                        get("/reports/transfers-in-flight", Expect.HIDDEN),
                        get("/reports/valuation?" + two, Expect.HIDDEN),
                        get("/reports/stock-health?" + two, Expect.HIDDEN),
                        post("/transfers/" + transferAway + "/void", Expect.OUTSIDE),
                        post("/transfers/" + transferAway + "/cancel", Expect.OUTSIDE),
                        post("/transfers/" + transferAway + "/receive", Expect.OUTSIDE),
                        post("/stocktakes/" + stocktakeTwo + "/commit", Expect.OUTSIDE),
                        // The cash book, every read.
                        get("/savings", Expect.FORBIDDEN),
                        get("/savings/suggestion", Expect.FORBIDDEN),
                        get("/bankings", Expect.FORBIDDEN),
                        get("/bankings/expected", Expect.FORBIDDEN),
                        get("/withdrawals", Expect.FORBIDDEN),
                        get("/expenses", Expect.FORBIDDEN),
                        get("/advances", Expect.FORBIDDEN),
                        get("/expense-categories", Expect.FORBIDDEN),
                        get("/cash-parties", Expect.FORBIDDEN),
                        get("/reports/cash/daily", Expect.FORBIDDEN),
                        get("/reports/cash/banking", Expect.FORBIDDEN),
                        get("/reports/cash/expenses", Expect.FORBIDDEN),
                        get("/reports/cash/savings", Expect.FORBIDDEN),
                        get("/reports/cash/advances", Expect.FORBIDDEN),
                        // Buyers, debts, orders, returns, documents and prices.
                        get("/reports/credit-control", Expect.FORBIDDEN),
                        get("/reports/customer-ageing", Expect.FORBIDDEN),
                        get("/customers", Expect.FORBIDDEN),
                        get("/orders", Expect.FORBIDDEN),
                        get("/orders/board", Expect.FORBIDDEN),
                        get("/returns", Expect.FORBIDDEN),
                        get("/documents", Expect.FORBIDDEN),
                        get("/document-series", Expect.FORBIDDEN),
                        get("/quotations", Expect.FORBIDDEN),
                        get("/price-lists", Expect.FORBIDDEN),
                        get("/customer-groups", Expect.FORBIDDEN),
                        // Buying and its money.
                        get("/suppliers", Expect.FORBIDDEN),
                        get("/purchases", Expect.FORBIDDEN),
                        get("/purchases/" + restockTwo, Expect.FORBIDDEN),
                        get("/purchase-orders", Expect.FORBIDDEN),
                        get("/goods-receipts", Expect.FORBIDDEN),
                        get("/supplier-bills", Expect.FORBIDDEN),
                        get("/supplier-payments", Expect.FORBIDDEN),
                        get("/reports/supplier-payables", Expect.FORBIDDEN),
                        // Sales, profit and the owner's reports.
                        get("/sales", Expect.FORBIDDEN),
                        get("/sales/summary?group_by=day", Expect.FORBIDDEN),
                        get("/reports/profit/daily", Expect.FORBIDDEN),
                        get("/reports/margins", Expect.FORBIDDEN),
                        get("/reports/business-evaluation", Expect.FORBIDDEN),
                        get("/reports/sales-analysis", Expect.FORBIDDEN),
                        get("/reports/dashboard", Expect.FORBIDDEN),
                        get("/controls/summary", Expect.FORBIDDEN)));
    }

    @Test
    void purchasingAtHeadOfficeSeesNoRestockOrderOrReceiptOfAnotherBranch() {
        String buyer = signIn(
                "buyer", new Role("retail_sales", t.headOffice()), new Role("retail_purchasing", t.headOffice()));
        check(
                buyer,
                "a buyer",
                List.of(
                        get("/purchases", Expect.HIDDEN),
                        get("/purchases/" + restockTwo, Expect.OUTSIDE),
                        get("/purchases/summary?group_by=branch", Expect.HIDDEN),
                        get("/purchase-orders", Expect.HIDDEN),
                        get("/purchase-orders/" + orderTwo, Expect.OUTSIDE),
                        get("/goods-receipts", Expect.HIDDEN),
                        get("/goods-receipts/" + receiptTwo, Expect.OUTSIDE),
                        post("/purchases/" + restockTwo + "/void", Expect.OUTSIDE),
                        post("/purchase-orders/" + orderTwo + "/cancel", Expect.OUTSIDE),
                        post("/goods-receipts/" + receiptTwo + "/void", Expect.OUTSIDE),
                        get("/supplier-bills", Expect.FORBIDDEN),
                        get("/supplier-payments", Expect.FORBIDDEN),
                        get("/reports/supplier-payables", Expect.FORBIDDEN),
                        get("/reports/profit/daily", Expect.FORBIDDEN)));
    }

    /**
     * The restocks summary adds up only what a buyer recorded: the owner's restock at the same branch is not counted.
     * JUSTIFICATION-A3: a new test case for the summary's own-records rule; the anchor is only the insertion point.
     */
    @Test
    void purchasingAddsUpOnlyTheRestocksItRecorded() {
        String buyer = signIn(
                "buyer3", new Role("retail_sales", t.headOffice()), new Role("retail_purchasing", t.headOffice()));
        ownerPost("/purchases", restock(t.headOffice()));
        ResponseEntity<JsonNode> mine = api.call(
                HttpMethod.POST,
                R + "/purchases",
                restock(t.headOffice()),
                buyer,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(mine.getStatusCode()).as("%s", mine.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode summary =
                api.get(R + "/purchases/summary?group_by=branch", buyer).getBody();
        long count = 0;
        for (JsonNode g : summary.get("items")) {
            count += g.get("purchases_count").asLong();
        }
        assertThat(count).as("only the buyer's own restock").isEqualTo(1);
        JsonNode owners = retail.get("/purchases/summary?group_by=branch&branch_id=" + t.headOffice(), OWNER)
                .getBody();
        assertThat(owners.get("items").get(0).get("purchases_count").asLong()).isEqualTo(2);
    }

    /** The selling price applies in every shop: a goods receipt sets a new one only with the price permission. */
    @Test
    void purchasingCannotSetANewSellingPriceThroughAGoodsReceipt() {
        String buyer = signIn(
                "buyer2", new Role("retail_sales", t.headOffice()), new Role("retail_purchasing", t.headOffice()));
        ResponseEntity<JsonNode> repriced = api.call(
                HttpMethod.POST,
                R + "/goods-receipts",
                receipt(t.headOffice(), 9_000L),
                buyer,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(repriced.getStatusCode()).as("%s", repriced.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(retail.get("/products/" + product, ADMIN)
                        .getBody()
                        .get("sell_minor")
                        .asLong())
                .isEqualTo(7_000);
        ResponseEntity<JsonNode> same = api.call(
                HttpMethod.POST,
                R + "/goods-receipts",
                receipt(t.headOffice(), 7_000L),
                buyer,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(same.getStatusCode()).as("%s", same.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> byOwner = retail.postKeyed(
                "/goods-receipts",
                receipt(t.headOffice(), 9_000L),
                OWNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(byOwner.getStatusCode()).as("%s", byOwner.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(retail.get("/products/" + product, ADMIN)
                        .getBody()
                        .get("sell_minor")
                        .asLong())
                .isEqualTo(9_000);
    }
}
