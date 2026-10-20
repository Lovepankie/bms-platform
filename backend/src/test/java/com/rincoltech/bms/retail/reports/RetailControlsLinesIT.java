package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The Controls page, the metrics that need a capability (issue #357, group 1; ADR-047): prices changed, discounts and
 * goods taken back, with their values, and the rate counted over sales and not over lines. Product L1 costs 100 and sells
 * for 300, L2 costs 100 and sells for 500. Sale A has two lines whose price was changed (L1 x 2 at 250, L2 x 1 at 400: a
 * discount of 100 on each), sale B is plain, sale C changes one price upwards (L1 x 2 at 350). So three sales were entered;
 * prices were changed on 3 lines in 2 sales (worth 500 + 400 + 700 = 1,600), and discounts were given on 2 lines in 1 sale
 * (100 + 100 = 200). Goods are taken back from sale B (one piece, 300).
 */
class RetailControlsLinesIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    static final String SELLER = ADMIN + ",retail.price.override";
    static final String RETURNER = ADMIN + ",retail.sale.return,retail.cashbook.read";
    static final String CONTROLS = "retail.controls.read,retail.sale.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID l1;
    UUID l2;

    void capability(String key) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of(key, true),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    static Map<String, Object> line(UUID product, String qty, Long price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product_id", product);
        m.put("qty", qty);
        if (price != null) {
            m.put("unit_price_minor", price);
            m.put("price_override_reason", "Test agreed price");
        }
        return m;
    }

    UUID sale(List<Map<String, Object>> lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "cash");
        body.put("lines", lines);
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, SELLER, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    JsonNode get(String path) {
        ResponseEntity<JsonNode> r = api.get("/controls" + path, CONTROLS);
        assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static JsonNode card(JsonNode summary, String metric) {
        for (JsonNode c : summary.get("cards")) {
            if (c.get("metric").asString().equals(metric)) {
                return c;
            }
        }
        throw new AssertionError("no card " + metric);
    }

    @Test
    void goodsTakenBackAreCreditedToTheSellerWhoseSaleItWasAndTheAdministratorIsTheApprover() {
        t = TestDatabase.tenant("retail-ctl-ret", false, true);
        api = new RetailTestSupport(http, t);
        RetailTestSupport seller = new RetailTestSupport(http, t);
        RetailControlsIT.user(t, seller.userId(), "Test Seller A", "active");
        RetailControlsIT.user(t, api.userId(), "Test Admin B", "active");
        l1 = api.product("RA-1", 100, 300);
        api.stockUp(t.headOffice(), l1, "100");
        capability("returns");
        capability("customer_accounts");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "cash");
        body.put("lines", List.of(line(l1, "2", null)));
        UUID sold = RetailTestSupport.id(
                seller.postKeyed("/sales", body, SELLER, "*", UUID.randomUUID().toString()));
        UUID saleLine = UUID.fromString(api.get("/sales/" + sold, ADMIN)
                .getBody()
                .get("lines")
                .get(0)
                .get("id")
                .asString());
        Map<String, Object> returned = new LinkedHashMap<>();
        returned.put("sale_line_id", saleLine);
        returned.put("qty", "1");
        returned.put("reason", "defective");
        returned.put("disposition", "restock");
        ResponseEntity<JsonNode> ret = api.postKeyed(
                "/sales/" + sold + "/returns",
                Map.of("lines", List.of(returned), "refund_method", "cash"),
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(ret.getStatusCode()).as("%s", ret.getBody()).isEqualTo(HttpStatus.CREATED);

        JsonNode record = get("/records?metric=returns").get("items").get(0);
        assertThat(record.get("user_name").asString()).isEqualTo("Test Seller A");
        assertThat(record.get("approver_name").asString()).isEqualTo("Test Admin B");
        JsonNode people = get("/groups?metric=returns&group_by=user");
        assertThat(people.get("items")).hasSize(1);
        assertThat(people.get("items").get(0).get("label").asString()).isEqualTo("Test Seller A");
        JsonNode approvers = get("/groups?metric=returns&group_by=approver");
        assertThat(approvers.get("items").get(0).get("label").asString()).isEqualTo("Test Admin B");
    }

    @Test
    void withTheCapabilitiesOffTheCardsSayTheyAreNotSwitchedOn() {
        t = TestDatabase.tenant("retail-ctl-off", false, true);
        api = new RetailTestSupport(http, t);
        JsonNode s = get("/summary");
        assertThat(card(s, "returns").get("tracked").asBoolean()).isFalse();
        assertThat(card(s, "price_overrides").get("tracked").asBoolean()).isFalse();
        assertThat(card(s, "discounts").get("tracked").asBoolean()).isFalse();
        assertThat(card(s, "voided_sales").get("tracked").asBoolean()).isTrue();
    }

    @Test
    void changedPricesDiscountsAndReturnsAreCountedAtTheirValuesAndRatedOverSales() {
        t = TestDatabase.tenant("retail-ctl-lines", false, true);
        api = new RetailTestSupport(http, t);
        l1 = api.product("LN-1", 100, 300);
        l2 = api.product("LN-2", 100, 500);
        api.stockUp(t.headOffice(), l1, "100");
        api.stockUp(t.headOffice(), l2, "100");
        capability("price_lists");
        capability("returns");
        capability("customer_accounts");
        sale(List.of(line(l1, "2", 250L), line(l2, "1", 400L)));
        UUID plain = sale(List.of(line(l1, "1", null)));
        sale(List.of(line(l1, "2", 350L)));
        UUID saleLine = UUID.fromString(api.get("/sales/" + plain, ADMIN)
                .getBody()
                .get("lines")
                .get(0)
                .get("id")
                .asString());
        Map<String, Object> returned = new LinkedHashMap<>();
        returned.put("sale_line_id", saleLine);
        returned.put("qty", "1");
        returned.put("reason", "defective");
        returned.put("disposition", "restock");
        ResponseEntity<JsonNode> ret = api.postKeyed(
                "/sales/" + plain + "/returns",
                Map.of("lines", List.of(returned), "refund_method", "cash"),
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(ret.getStatusCode()).as("%s", ret.getBody()).isEqualTo(HttpStatus.CREATED);

        JsonNode s = get("/summary");
        assertThat(s.get("period").get("sales_count").asLong()).isEqualTo(3);

        JsonNode changed = card(s, "price_overrides");
        assertThat(changed.get("tracked").asBoolean()).isTrue();
        // Three lines in two sales; the rate counts the sales, so it reads 2 of 3 and not 3 of 3.
        assertThat(changed.get("count").asLong()).isEqualTo(3);
        assertThat(changed.get("sales_affected").asLong()).isEqualTo(2);
        assertThat(changed.get("value_minor").asLong()).isEqualTo(1_600);
        assertThat(changed.get("rate_per_100").asString()).isEqualTo("66.7");

        JsonNode discounts = card(s, "discounts");
        assertThat(discounts.get("count").asLong()).isEqualTo(2);
        assertThat(discounts.get("sales_affected").asLong()).isEqualTo(1);
        assertThat(discounts.get("value_minor").asLong()).isEqualTo(200);
        assertThat(discounts.get("rate_per_100").asString()).isEqualTo("33.3");

        JsonNode back = card(s, "returns");
        assertThat(back.get("count").asLong()).isEqualTo(1);
        assertThat(back.get("value_minor").asLong()).isEqualTo(300);
        assertThat(back.get("rate_per_100").asString()).isEqualTo("33.3");

        // The same units in the groups: the shop's rate for changed prices is over sales, not lines.
        JsonNode shops = get("/groups?metric=price_overrides&group_by=branch");
        JsonNode shop = shops.get("items").get(0);
        assertThat(shop.get("count").asLong()).isEqualTo(3);
        assertThat(shop.get("sales_affected").asLong()).isEqualTo(2);
        assertThat(shop.get("rate_per_100").asString()).isEqualTo("66.7");

        JsonNode records = get("/records?metric=discounts");
        assertThat(records.get("items")).hasSize(2);
        assertThat(records.get("items").get(0).get("item").asString()).startsWith("Test Product LN-");
        assertThat(records.get("items").get(0).get("reason").asString()).isEqualTo("Test agreed price");
        assertThat(records.get("items").get(0).get("value_minor").asLong()).isEqualTo(100);

        JsonNode returns = get("/records?metric=returns");
        assertThat(returns.get("items")).hasSize(1);
        assertThat(returns.get("items").get(0).get("reference").asString()).isNotBlank();
        assertThat(returns.get("items").get(0).get("value_minor").asLong()).isEqualTo(300);
    }
}
