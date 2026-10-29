package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.math.BigDecimal;
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
 * A Store is a branch that holds stock and takes purchases and transfers but does not sell (issue #366, ADR-048). Transfers between
 * a shop and a store, between two stores, and a restock into a store work as they do between any two branches; a sale, a payment
 * taken and a refund paid at a store are refused with 422 {@code branch_does_not_sell}; {@code /me} reports the flag and the default
 * branch skips a store. The branch is changed by a real tenant administrator; retail events are made with the development headers.
 * Names and amounts are fabricated.
 */
class StoreBranchIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    static final String RETURNER = ADMIN + ",retail.sale.return,retail.cashbook.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport retail;
    Api api;
    Session admin;
    UUID product;
    UUID store2;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("store-branch", false, true);
        retail = new RetailTestSupport(http, t);
        api = Api.tenant(http, t.slug());
        String email = Api.email("admin");
        admin = api.signIn(Api.staff(t, email, new Role("tenant_admin", null)), email, Api.PASSWORD, null);
        product = retail.product("STORE-CABLE", 1_000, 1_500);
        store2 = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO branches (id, tenant_id, code, name, sells_goods) VALUES (?, ?, 'STO2', 'Test Store Two', false)")
                .params(store2, t.tenantId())
                .update();
    }

    // ---------------------------------------------------------------------------------------- helpers

    ResponseEntity<JsonNode> patch(UUID branch, Map<String, Object> body) {
        String etag = api.get("/api/v1/branches/" + branch, admin.accessToken())
                .getHeaders()
                .getETag();
        return api.call(
                HttpMethod.PATCH, "/api/v1/branches/" + branch, body, admin.accessToken(), Map.of("If-Match", etag));
    }

    void markStore(UUID branch) {
        ResponseEntity<JsonNode> r = patch(branch, Map.of("sells_goods", false));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("sells_goods").asBoolean()).isFalse();
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    void assertDoesNotSell(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("branch_does_not_sell");
    }

    BigDecimal qty(UUID branch) {
        return TestDatabase.owner()
                .sql(
                        "SELECT coalesce(sum(qty), 0) FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), branch, product)
                .query(BigDecimal.class)
                .single();
    }

    ResponseEntity<JsonNode> transfer(UUID from, UUID to, int qty) {
        Map<String, Object> body = Map.of(
                "from_branch_id",
                from,
                "to_branch_id",
                to,
                "lines",
                List.of(Map.of("product_id", product, "qty", qty)));
        return retail.postKeyed(
                "/transfers", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    void assertMoved(UUID from, UUID to, int qty) {
        BigDecimal fromBefore = qty(from);
        BigDecimal toBefore = qty(to);
        ResponseEntity<JsonNode> r = transfer(from, to, qty);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(qty(from)).isEqualByComparingTo(fromBefore.subtract(BigDecimal.valueOf(qty)));
        assertThat(qty(to)).isEqualByComparingTo(toBefore.add(BigDecimal.valueOf(qty)));
    }

    // ---------------------------------------------------------------------------------------- stock in and out

    /** Shop to store, store to shop and store to store: the same transfer as between any two branches. */
    @Test
    void stockMovesBetweenShopsAndStores() {
        markStore(t.headOffice());
        retail.stockUp(t.secondBranch(), product, "10");
        assertMoved(t.secondBranch(), t.headOffice(), 4);
        assertMoved(t.headOffice(), t.secondBranch(), 1);
        assertMoved(t.headOffice(), store2, 2);
        assertMoved(store2, t.headOffice(), 1);
        assertThat(qty(t.headOffice())).isEqualByComparingTo("2");
        assertThat(qty(store2)).isEqualByComparingTo("1");
    }

    /** A restock (a purchase) and a stock-take into a store take stock in. */
    @Test
    void aStoreIsRestocked() {
        markStore(t.headOffice());
        Map<String, Object> line = Map.of(
                "product_id",
                product,
                "cost_minor",
                1_000,
                "qty_by_branch",
                List.of(Map.of("branch_id", t.headOffice(), "qty", "6")));
        ResponseEntity<JsonNode> bought = retail.postKeyed(
                "/purchases",
                Map.of("purchased_on", "2026-01-15", "payment_method", "cash", "lines", List.of(line)),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(bought.getStatusCode()).as("%s", bought.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(qty(t.headOffice())).isEqualByComparingTo("6");
        retail.stockUp(store2, product, "3");
        assertThat(qty(store2)).isEqualByComparingTo("3");
    }

    // ---------------------------------------------------------------------------------------- nothing is sold there

    /** A sale at a store is refused with a plain 422 and records nothing; the price check is refused the same way. */
    @Test
    void aSaleAtAStoreIsRefused() {
        retail.stockUp(t.secondBranch(), product, "5");
        markStore(t.secondBranch());
        assertDoesNotSell(retail.sell(t.secondBranch(), "cash", product, "1"));
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_sales WHERE tenant_id = ? AND branch_id = ?")
                        .params(t.tenantId(), t.secondBranch())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(qty(t.secondBranch())).isEqualByComparingTo("5");
        // A shop still sells.
        retail.stockUp(t.headOffice(), product, "5");
        assertThat(retail.sell(t.headOffice(), "cash", product, "1").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    /** A payment taken on a credit sale at a till that became a store is refused. */
    @Test
    void aPaymentAtAStoreIsRefused() {
        retail.stockUp(t.secondBranch(), product, "5");
        UUID sale = RetailTestSupport.id(retail.sell(t.secondBranch(), "credit", product, "1"));
        TestDatabase.owner()
                .sql("UPDATE branches SET sells_goods = false WHERE id = ?")
                .param(t.secondBranch())
                .update();
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("amount_minor", 500);
        pay.put("method", "cash");
        assertDoesNotSell(retail.postKeyed(
                "/sales/" + sale + "/payments",
                pay,
                ADMIN,
                "*",
                UUID.randomUUID().toString()));
    }

    /** A refund paid out of a store's till is refused; naming a shop as the paying branch works. */
    @Test
    void aRefundAtAStoreIsRefused() {
        String etag =
                retail.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> on = retail.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("returns", true),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        retail.stockUp(t.secondBranch(), product, "5");
        UUID sale = RetailTestSupport.id(retail.sell(t.secondBranch(), "cash", product, "2"));
        UUID saleLine = UUID.fromString(retail.get("/sales/" + sale, ADMIN)
                .getBody()
                .get("lines")
                .get(0)
                .get("id")
                .asString());
        markStore(t.secondBranch());
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sale_line_id", saleLine);
        line.put("qty", "1");
        line.put("reason", "defective");
        line.put("disposition", "restock");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(line));
        body.put("refund_method", "cash");
        // Naming the store as the paying branch is refused.
        body.put("refund_branch_id", t.secondBranch());
        assertDoesNotSell(retail.postKeyed(
                "/sales/" + sale + "/returns",
                body,
                RETURNER,
                "*",
                UUID.randomUUID().toString()));
        body.remove("refund_branch_id");
        // With exactly one selling branch in scope (HQ) it pays the refund, as the usual till is a store.
        ResponseEntity<JsonNode> ok = retail.postKeyed(
                "/sales/" + sale + "/returns",
                body,
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(ok.getStatusCode()).as("%s", ok.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(TestDatabase.owner()
                        .sql("SELECT refund_branch_id FROM retail_sale_returns WHERE tenant_id = ? AND sale_id = ?")
                        .params(t.tenantId(), sale)
                        .query(UUID.class)
                        .single())
                .isEqualTo(t.headOffice());
        // With two shops to choose from the caller is asked to choose.
        TestDatabase.owner()
                .sql("INSERT INTO branches (id, tenant_id, code, name) VALUES (?, ?, 'SHP3', 'Test Shop Three')")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        ResponseEntity<JsonNode> asked = retail.postKeyed(
                "/sales/" + sale + "/returns",
                body,
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(asked)).isEqualTo("refund_branch_required");
        // With no shop at all in scope the answer says so, in plain words.
        TestDatabase.owner()
                .sql("UPDATE branches SET sells_goods = false WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        ResponseEntity<JsonNode> none = retail.postKeyed(
                "/sales/" + sale + "/returns",
                body,
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(none)).isEqualTo("refund_branch_required");
        assertThat(none.getBody().get("detail").asString())
                .isEqualTo("No shop you work at can pay this out; credit the account or ask a shop.");
    }

    // ---------------------------------------------------------------------------------------- marking a branch

    /** A credit sale written off (#349, ADR-043) no longer owes, so it does not block marking the branch (#371). */
    @Test
    void aWrittenOffCreditSaleDoesNotBlockMarking() {
        retail.stockUp(t.secondBranch(), product, "5");
        UUID sale = RetailTestSupport.id(retail.sell(t.secondBranch(), "credit", product, "1"));
        assertThat(code(patch(t.secondBranch(), Map.of("sells_goods", false))))
                .isEqualTo("branch_has_open_credit_sales");
        ResponseEntity<JsonNode> written = retail.postKeyed(
                "/sales/" + sale + "/write-off",
                Map.of("reason", "Test buyer left town"),
                ADMIN + ",retail.credit.write_off,retail.credit.manage",
                "*",
                UUID.randomUUID().toString());
        assertThat(written.getStatusCode()).as("%s", written.getBody()).isEqualTo(HttpStatus.CREATED);
        markStore(t.secondBranch());
    }

    /** A credit sale still owing blocks marking the branch; once it is paid the branch can be marked. */
    @Test
    void markingIsRefusedWhileACreditSaleIsOwing() {
        retail.stockUp(t.secondBranch(), product, "5");
        UUID sale = RetailTestSupport.id(retail.sell(t.secondBranch(), "credit", product, "1"));
        ResponseEntity<JsonNode> refused = patch(t.secondBranch(), Map.of("sells_goods", false));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("branch_has_open_credit_sales");
        assertThat(refused.getBody().get("open_credit_sales").asInt()).isEqualTo(1);
        long total =
                retail.get("/sales/" + sale, ADMIN).getBody().get("total_minor").asLong();
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("amount_minor", total);
        pay.put("method", "cash");
        assertThat(retail.postKeyed(
                                "/sales/" + sale + "/payments",
                                pay,
                                ADMIN,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        markStore(t.secondBranch());
    }

    /** A store must keep goods; the two flags cannot both be off. */
    @Test
    void aStoreMustStockGoods() {
        ResponseEntity<JsonNode> both = patch(t.secondBranch(), Map.of("sells_goods", false, "stocks_goods", false));
        assertThat(both.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(both)).isEqualTo("store_must_stock");
        markStore(t.secondBranch());
        ResponseEntity<JsonNode> stops = patch(t.secondBranch(), Map.of("stocks_goods", false));
        assertThat(code(stops)).isEqualTo("store_must_stock");
        // A shop can become a store and back again.
        assertThat(patch(t.secondBranch(), Map.of("sells_goods", true)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------------------------------------------------------------- /me

    /** /me lists the flag per branch and the default selling branch is never a store. */
    @Test
    void meReportsStoresAndSkipsThemAsTheDefault() {
        markStore(t.headOffice());
        JsonNode me = api.get("/api/v1/me", admin.accessToken()).getBody();
        JsonNode hq = me.get("branches").get(0);
        assertThat(hq.get("code").asString()).isEqualTo("HQ");
        assertThat(hq.get("sells_goods").asBoolean()).isFalse();
        assertThat(hq.get("stocks_goods").asBoolean()).isTrue();
        assertThat(me.get("branches").get(1).get("sells_goods").asBoolean()).isTrue();
        assertThat(me.get("default_branch_id").asString())
                .isEqualTo(t.secondBranch().toString());
    }
}
