package com.rincoltech.bms.retail.catalogue;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * The retail catalogue through HTTP (#51; FR-RET-01, FR-RET-02, FR-RET-13, FR-RET-14; ADR-020
 * decisions 5, 7 and 10). All names and amounts are fabricated.
 */
class RetailCatalogueIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-cat", false, true);
        api = new RetailTestSupport(http, t);
    }

    /** FR-RET-01, FR-RET-02, FR-RET-14: a product is created with an initial history row and audited. */
    @Test
    void aProductIsCreatedWithItsInitialHistoryAndAudited() {
        UUID id = api.product("CBL-2.5", 1_500, 2_000);
        JsonNode p = api.get("/products/" + id, ADMIN).getBody();
        assertThat(p.get("code").asString()).isEqualTo("CBL-2.5");
        assertThat(p.get("cost_minor").asLong()).isEqualTo(1_500);
        assertThat(p.get("sell_minor").asLong()).isEqualTo(2_000);
        assertThat(p.get("currency").asString()).isEqualTo("UGX");
        assertThat(p.get("unit").asString()).isEqualTo("u-cbl-2.5");
        assertThat(p.get("active").asBoolean()).isTrue();

        JsonNode history =
                api.get("/products/" + id + "/price-history", ADMIN).getBody().get("items");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("source").asString()).isEqualTo("initial");
        assertThat(history.get(0).get("new_cost_minor").asLong()).isEqualTo(1_500);
        assertThat(history.get(0).get("by").asString()).isEqualTo(api.userId().toString());

        List<String> actions = TestDatabase.owner()
                .sql("SELECT action FROM audit_log WHERE tenant_id = ? ORDER BY created_at, action")
                .param(t.tenantId())
                .query(String.class)
                .list();
        assertThat(actions).contains("retail.category.created", "retail.unit.created", "retail.product.created");

        assertThat(api.get("/products?query=cbl", ADMIN).getBody().get("items")).hasSize(1);
        assertThat(api.get("/products?query=nothing", ADMIN).getBody().get("items"))
                .isEmpty();
    }

    /** Data dictionary rule 1: codes are unique ignoring case and surrounding spaces. */
    @Test
    void productCodesAreUniqueIgnoringCase() {
        api.product("SW-01", 1_000, 1_200);
        UUID category = api.category("Test Switches");
        UUID unit = api.unit("pcs");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "  sw-01 ");
        body.put("description", "Test switch again");
        body.put("category_id", category);
        body.put("unit_id", unit);
        body.put("cost_minor", 1);
        body.put("sell_minor", 2);
        ResponseEntity<JsonNode> again = api.post("/products", body, ADMIN);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("duplicate_product_code");

        assertThat(api.post("/units", Map.of("name", "PCS"), ADMIN)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("duplicate_unit");
        body.put("code", "SW-02");
        body.put("category_id", UUID.randomUUID());
        assertThat(api.post("/products", body, ADMIN).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** FR-RET-02, ADR-020 decision 5: a manual edit changes the price and writes history together. */
    @Test
    void aManualPriceEditWritesAHistoryRow() {
        UUID id = api.product("LMP-9W", 3_000, 4_000);
        ResponseEntity<JsonNode> denied =
                api.editPrices(id, Map.of("sell_minor", 4_500, "reason", "Test increase"), SALES);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> edited =
                api.editPrices(id, Map.of("sell_minor", 4_500, "reason", "Test increase"), ADMIN);
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().get("sell_minor").asLong()).isEqualTo(4_500);
        assertThat(edited.getBody().get("cost_minor").asLong()).isEqualTo(3_000);
        assertThat(edited.getBody().get("version").asInt()).isEqualTo(2);

        JsonNode history =
                api.get("/products/" + id + "/price-history", ADMIN).getBody().get("items");
        assertThat(history).hasSize(2);
        JsonNode last = history.get(1);
        assertThat(last.get("source").asString()).isEqualTo("manual");
        assertThat(last.get("old_sell_minor").asLong()).isEqualTo(4_000);
        assertThat(last.get("new_sell_minor").asLong()).isEqualTo(4_500);
        assertThat(last.get("old_cost_minor").asLong()).isEqualTo(3_000);
        assertThat(last.get("new_cost_minor").asLong()).isEqualTo(3_000);
        assertThat(last.get("reason").asString()).isEqualTo("Test increase");

        ResponseEntity<JsonNode> same = api.editPrices(id, Map.of("sell_minor", 4_500, "reason", "Test again"), ADMIN);
        assertThat(same.getBody().get("code").asString()).isEqualTo("price_unchanged");
        assertThat(api.editPrices(id, Map.of("reason", "Test none"), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE action = 'retail.product.price_changed' AND entity_id = ?")
                        .param(id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /** Prices never change through PATCH; non-price fields do, under If-Match. */
    @Test
    void editingAProductLeavesItsPricesAlone() {
        UUID id = api.product("PLG-3P", 900, 1_100);
        ResponseEntity<JsonNode> noMatch =
                api.call(HttpMethod.PATCH, "/products/" + id, Map.of("description", "Test plug"), ADMIN, "*", Map.of());
        assertThat(noMatch.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        ResponseEntity<JsonNode> patched = api.call(
                HttpMethod.PATCH,
                "/products/" + id,
                Map.of("description", "Test plug three pin", "active", false),
                ADMIN,
                "*",
                Map.of("If-Match", "\"1\""));
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody().get("description").asString()).isEqualTo("Test plug three pin");
        assertThat(patched.getBody().get("active").asBoolean()).isFalse();
        assertThat(patched.getBody().get("sell_minor").asLong()).isEqualTo(1_100);
        ResponseEntity<JsonNode> withPrice = api.call(
                HttpMethod.PATCH, "/products/" + id, Map.of("sell_minor", 1), ADMIN, "*", Map.of("If-Match", "\"2\""));
        assertThat(withPrice.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.get("/products?active=true", ADMIN).getBody().get("items"))
                .isEmpty();
    }

    /**
     * ADR-020 decision 10, FR-RET-13: a user signed in with the sales role holds exactly the sales
     * column, and cost fields are absent from the body, not merely null.
     */
    @Test
    void aSalesUserNeverReceivesCostFields() {
        UUID id = api.product("BRK-20A", 7_000, 9_000);
        api.editPrices(id, Map.of("cost_minor", 7_500, "reason", "Test cost"), ADMIN);

        String email = Api.email("sales");
        UUID user = Api.staff(t, email, new Role("retail_sales", t.headOffice()));
        Api signedIn = Api.tenant(http, t.slug());
        Session session = signedIn.signIn(user, email, Api.PASSWORD, null);
        JsonNode me = signedIn.get("/api/v1/me", session.accessToken()).getBody();
        List<String> held = me.get("permissions")
                .valueStream()
                .map(JsonNode::asString)
                .sorted()
                .toList();
        assertThat(held)
                .containsExactly(
                        "retail.banking.record",
                        "retail.cashbook.read",
                        "retail.customer.manage",
                        "retail.expense.record",
                        "retail.sale.create",
                        "retail.sale.read",
                        "retail.savings.record",
                        "retail.stock.read",
                        "retail.usage.report");

        JsonNode product = signedIn.get("/api/v1/retail/products/" + id, session.accessToken())
                .getBody();
        assertThat(product.has("sell_minor")).isTrue();
        assertThat(product.has("cost_minor"))
                .as("cost field present for sales: %s", product)
                .isFalse();
        JsonNode listed = signedIn.get("/api/v1/retail/products", session.accessToken())
                .getBody()
                .get("items");
        assertThat(listed.get(0).has("cost_minor")).isFalse();
        JsonNode history = signedIn.get("/api/v1/retail/products/" + id + "/price-history", session.accessToken())
                .getBody()
                .get("items");
        assertThat(history).hasSize(2);
        for (JsonNode h : history) {
            assertThat(h.has("old_cost_minor")).isFalse();
            assertThat(h.has("new_cost_minor")).isFalse();
            assertThat(h.has("new_sell_minor")).isTrue();
        }
        assertThat(signedIn.post("/api/v1/retail/categories", Map.of("name", "Test Nope"), session.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** ADR-001, FR-TEN-03: a tenant without the module is refused every retail route. */
    @Test
    void aTenantWithoutTheModuleIsRefused() {
        TestDatabase.Fixture lendingOnly = TestDatabase.tenant("no-retail", true);
        ResponseEntity<JsonNode> r = new RetailTestSupport(http, lendingOnly).get("/products", ADMIN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().get("code").asString()).isEqualTo("module_not_enabled");
    }

    /**
     * ADR-020 decision 7: switching the module on seeds the retail chart; a tenant with lending too
     * keeps one cash, bank and opening balance equity account, and switching again adds nothing.
     */
    @Test
    void theRetailChartIsSeededWhenTheModuleIsSwitchedOn() {
        List<String> keys = List.of(
                "inventory",
                "sales_revenue",
                "cost_of_goods_sold",
                "stock_shrinkage",
                "trade_debtors",
                "trade_creditors",
                "cash_on_hand",
                "mobile_money",
                "bank",
                "opening_balance_equity");
        for (String key : keys) {
            assertThat(t.account(key)).as(key).isNotNull();
        }
        TestDatabase.Fixture both = TestDatabase.tenant("both", true, true);
        long before = accounts(both);
        for (String key : keys) {
            assertThat(both.account(key)).as(key).isNotNull();
        }
        TestDatabase.owner()
                .sql("SELECT platform_set_tenant_modules(?, '{lending,retail}', NULL)")
                .param(both.tenantId())
                .query((rs, n) -> 1)
                .single();
        assertThat(accounts(both)).isEqualTo(before);
        Long unparented = TestDatabase.owner()
                .sql("SELECT count(*) FROM gl_accounts WHERE tenant_id = ? AND is_postable AND parent_id IS NULL")
                .param(both.tenantId())
                .query(Long.class)
                .single();
        assertThat(unparented).isZero();
    }

    /** ADR-020 decision 5: price history is append-only for the application and the owner alike. */
    @Test
    void priceHistoryIsAppendOnly() throws Exception {
        UUID id = api.product("FUS-10", 100, 200);
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                bind.setString(1, t.tenantId().toString());
                bind.execute();
            }
            assertThatThrownBy(() -> {
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE retail_price_history SET new_sell_minor = 1 WHERE product_id = ?")) {
                            ps.setObject(1, id);
                            ps.executeUpdate();
                        }
                    })
                    .hasMessageContaining("permission denied");
            c.rollback();
        }
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("DELETE FROM retail_price_history WHERE product_id = ?")
                        .param(id)
                        .update())
                .rootCause()
                .hasMessageContaining("append-only");
    }

    static long accounts(TestDatabase.Fixture f) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM gl_accounts WHERE tenant_id = ?")
                .param(f.tenantId())
                .query(Long.class)
                .single();
    }
}
