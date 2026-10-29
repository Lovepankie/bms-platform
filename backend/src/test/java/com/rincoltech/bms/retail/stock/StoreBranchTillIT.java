package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.retail.cashbook.CashbookTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.time.LocalDate;
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
 * The rest of what a Store refuses and allows (issue #366, ADR-048): quotations and their conversion, price resolution, a payment on
 * account, the cash book (money in, savings, bankings and withdrawals refused; expenses allowed), a store created as one, and an
 * imported credit sale that never blocks marking a branch. Names and amounts are fabricated.
 */
class StoreBranchTillIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    static final String DOCS = ADMIN + ",retail.document.issue,retail.credit.manage,retail.credit.override";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    RetailTestSupport retail;
    Api api;
    Session admin;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("store-till", false, true);
        cb = new CashbookTestSupport(http, t);
        retail = cb.api;
        api = Api.tenant(http, t.slug());
        String email = Api.email("admin");
        admin = api.signIn(Api.staff(t, email, new Role("tenant_admin", null)), email, Api.PASSWORD, null);
        product = retail.product("TILL-CABLE", 1_000, 1_500);
        for (String capability : List.of("documents", "price_lists", "customer_accounts")) {
            capability(capability);
        }
    }

    void capability(String key) {
        String etag =
                retail.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = retail.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of(key, true),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    void makeStore(UUID branch) {
        TestDatabase.owner()
                .sql("UPDATE branches SET sells_goods = false WHERE id = ?")
                .param(branch)
                .update();
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    void assertDoesNotSell(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("branch_does_not_sell");
    }

    ResponseEntity<JsonNode> quote(UUID branch) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("qty", "2");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("buyer_name", "Test Buyer 01");
        body.put("valid_until", LocalDate.now().plusDays(10).toString());
        body.put("lines", List.of(line));
        return retail.postKeyed(
                "/quotations", body, DOCS, "*", UUID.randomUUID().toString());
    }

    // ---------------------------------------------------------------------------------------- sales side

    /** A quotation, its conversion, a price resolution and a payment on account are refused at a store. */
    @Test
    void quotationsPricesAndAccountPaymentsAreRefused() {
        retail.stockUp(t.secondBranch(), product, "10");
        ResponseEntity<JsonNode> made = quote(t.secondBranch());
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID quotation = RetailTestSupport.id(made);
        UUID customer = RetailTestSupport.id(retail.post("/customers", Map.of("name", "Test Buyer 02"), ADMIN));

        makeStore(t.secondBranch());

        assertDoesNotSell(quote(t.secondBranch()));
        assertDoesNotSell(retail.postKeyed(
                "/quotations/" + quotation + "/convert",
                Map.of("payment_method", "cash"),
                DOCS,
                "*",
                UUID.randomUUID().toString()));
        Map<String, Object> resolve = new LinkedHashMap<>();
        resolve.put("branch_id", t.secondBranch());
        resolve.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        assertDoesNotSell(retail.post("/prices/resolve", resolve, ADMIN));
        Map<String, Object> pay = new LinkedHashMap<>();
        pay.put("branch_id", t.secondBranch());
        pay.put("amount_minor", 1_000);
        pay.put("method", "cash");
        assertDoesNotSell(retail.postKeyed(
                "/customers/" + customer + "/payments",
                pay,
                ADMIN,
                "*",
                UUID.randomUUID().toString()));
        // A shop still quotes and prices.
        resolve.put("branch_id", t.headOffice());
        retail.stockUp(t.headOffice(), product, "5");
        assertThat(retail.post("/prices/resolve", resolve, ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(quote(t.headOffice()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /**
     * A branch that keeps no goods (ADR-041) does not quote, as it does not sell (#371, ADR-104): a head office with the old role
     * store, which became both, never starts issuing quotations.
     */
    @Test
    void aBranchThatKeepsNoGoodsDoesNotQuote() {
        TestDatabase.owner()
                .sql("UPDATE branches SET stocks_goods = false WHERE id = ?")
                .param(t.secondBranch())
                .update();
        ResponseEntity<JsonNode> refused = quote(t.secondBranch());
        assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("branch_not_stocking");
        assertThat(quote(t.headOffice()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** A branch can be created as a store, and then refuses a sale. */
    @Test
    void aStoreCanBeCreatedAsOne() {
        ResponseEntity<JsonNode> created = api.call(
                HttpMethod.POST,
                "/api/v1/branches",
                Map.of("code", "STO3", "name", "Test Store Three", "sells_goods", false),
                admin.accessToken(),
                Map.of());
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("sells_goods").asBoolean()).isFalse();
        UUID store = UUID.fromString(created.getBody().get("id").asString());
        retail.stockUp(store, product, "3");
        assertDoesNotSell(retail.sell(store, "cash", product, "1"));
        ResponseEntity<JsonNode> shop = api.call(
                HttpMethod.POST,
                "/api/v1/branches",
                Map.of("code", "SHP4", "name", "Test Shop Four"),
                admin.accessToken(),
                Map.of());
        assertThat(shop.getBody().get("sells_goods").asBoolean()).isTrue();
    }

    /** An imported (historical) credit sale cannot be paid here, so it never blocks marking a branch as a store. */
    @Test
    void anImportedCreditSaleDoesNotBlockMarking() {
        UUID imported = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, buyer_name,
                            currency, total_minor, cost_total_minor, paid_minor, status, historical, created_by)
                        VALUES (?, ?, ?, ?, CAST('2025-10-01' AS date), 'credit', 'Test Buyer 01',
                            'UGX', 4000, 0, 0, 'completed', true, gen_random_uuid())
                        """)
                .params(
                        imported,
                        t.tenantId(),
                        t.secondBranch(),
                        "RS9" + imported.toString().substring(0, 7))
                .update();
        String etag = api.get("/api/v1/branches/" + t.secondBranch(), admin.accessToken())
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> marked = api.call(
                HttpMethod.PATCH,
                "/api/v1/branches/" + t.secondBranch(),
                Map.of("sells_goods", false),
                admin.accessToken(),
                Map.of("If-Match", etag));
        assertThat(marked.getStatusCode()).as("%s", marked.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(marked.getBody().get("sells_goods").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------------------------------- the cash book

    /** A store keeps no cash takings: savings, bankings, withdrawals and advance repayments are refused. */
    @Test
    void moneyInAndSavingsAreRefusedAtAStore() {
        UUID owner = cb.party("Test Owner 01", "owner");
        Map<String, Object> adv = new LinkedHashMap<>();
        adv.put("branch_id", t.secondBranch());
        adv.put("party_id", owner);
        adv.put("principal_minor", 1_000);
        UUID advance = RetailTestSupport.id(cb.post("/advances", adv, ALL));
        makeStore(t.secondBranch());
        UUID store = t.secondBranch();

        Map<String, Object> savings = new LinkedHashMap<>();
        savings.put("branch_id", store);
        savings.put("suggestion_token", "abc");
        assertDoesNotSell(cb.post("/savings", savings, ALL));
        assertDoesNotSell(cb.post("/bankings", Map.of("branch_id", store, "amount_minor", 1_000), ALL));
        assertDoesNotSell(cb.post("/withdrawals", Map.of("branch_id", store, "amount_minor", 1_000), ALL));
        assertDoesNotSell(cb.post(
                "/advances/" + advance + "/repayments",
                Map.of("branch_id", store, "amount_minor", 100, "method", "cash"),
                ALL));
    }

    /** A store may pay expenses (transport, casual labour), and a shop is unaffected. */
    @Test
    void anExpenseIsAllowedAtAStore() {
        UUID[] list = cb.categoryWithItem("Transport", "Test Boda fare", false);
        makeStore(t.secondBranch());
        ResponseEntity<JsonNode> spent = cb.expense(t.secondBranch(), list[0], list[1], 2_000, null);
        assertThat(spent.getStatusCode()).as("%s", spent.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(cb.expense(t.headOffice(), list[0], list[1], 1_000, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }
}
