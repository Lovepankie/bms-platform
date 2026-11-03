package com.rincoltech.bms.retail.stock;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.ArrayList;
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
 * Issue #807 (ADR-119): My stock lists, on every row, the quantity in every active shop of the business, this shop first and
 * marked here, to everyone who may read stock at the shop listed, scoped to one shop or not. Quantities only: no cost of any
 * shop for a role without it. Read for the whole page in one request; only the caller's own business. The owner's switch
 * {@code other_shops_stock} (on by default) takes the list away. Figures fabricated.
 */
class RetailAllShopsStockIT extends IntegrationTest {

    /** A seller: stock read and selling, no cost, scoped to head office only. */
    static final String SELLER = RetailTestSupport.SALES;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("all-shops", false, true);
        api = new RetailTestSupport(http, t);
        product = api.product("ASH-1", 400, 700);
        api.stockUp(t.secondBranch(), product, "1");
    }

    /** The stock of head office, read by a seller whose role covers head office only. */
    JsonNode sellerStock() {
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.GET,
                "/stock?branch_id=" + t.headOffice(),
                null,
                SELLER,
                t.headOffice().toString(),
                Map.of());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode row(JsonNode page, UUID id) {
        for (JsonNode item : page.get("items")) {
            if (item.get("product_id").asString().equals(id.toString())) {
                return item;
            }
        }
        throw new AssertionError("no row for " + id + " in " + page);
    }

    @Test
    void aSellerAtOneShopSeesEveryShopsCountHereFirst() {
        JsonNode item = row(sellerStock(), product);
        assertThat(item.get("qty").asString()).isEqualTo("0.000");
        JsonNode shops = item.get("shops");
        assertThat(shops).hasSize(2);
        assertThat(shops.get(0).get("branch_id").asString())
                .isEqualTo(t.headOffice().toString());
        assertThat(shops.get(0).get("here").asBoolean()).isTrue();
        assertThat(shops.get(0).get("qty").asString())
                .as("a shop with none shows 0")
                .isEqualTo("0.000");
        assertThat(shops.get(1).get("branch_id").asString())
                .isEqualTo(t.secondBranch().toString());
        assertThat(shops.get(1).get("here").asBoolean()).isFalse();
        assertThat(shops.get(1).get("qty").asString()).isEqualTo("1.000");
        assertThat(shops.get(1).get("negative").asBoolean()).isFalse();
        assertThat(shops.get(1).get("name").asString()).isEqualTo("Test Branch Two");
    }

    @Test
    void quantitiesOnlyAndNoCostForARoleWithoutIt() {
        JsonNode item = row(sellerStock(), product);
        assertThat(item.has("cost_minor")).isFalse();
        assertThat(item.get("sell_minor").asLong())
                .as("selling price as before")
                .isEqualTo(700);
        for (JsonNode shop : item.get("shops")) {
            List<String> fields = new ArrayList<>(shop.propertyNames());
            assertThat(fields).containsExactlyInAnyOrder("branch_id", "name", "qty", "negative", "here");
        }
    }

    @Test
    void onlyTheCallersOwnBusiness() {
        TestDatabase.Fixture other = TestDatabase.tenant("all-shops-other", false, true);
        RetailTestSupport elsewhere = new RetailTestSupport(http, other);
        UUID theirs = elsewhere.product("ASH-1", 400, 700);
        elsewhere.stockUp(other.headOffice(), theirs, "9");
        elsewhere.stockUp(other.secondBranch(), theirs, "9");

        JsonNode page = sellerStock();
        List<String> ours = List.of(t.headOffice().toString(), t.secondBranch().toString());
        for (JsonNode item : page.get("items")) {
            assertThat(item.get("product_id").asString()).isNotEqualTo(theirs.toString());
            for (JsonNode shop : item.get("shops")) {
                assertThat(ours).contains(shop.get("branch_id").asString());
            }
        }
        // Another business's shop cannot be asked for by its id.
        ResponseEntity<JsonNode> theirShop =
                api.call(HttpMethod.GET, "/stock?branch_id=" + other.secondBranch(), null, SELLER, "*", Map.of());
        assertThat(theirShop.getStatusCode()).as("%s", theirShop.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(theirShop.getBody().toString()).doesNotContain(theirs.toString());
    }

    // JUSTIFICATION-A3: the three cases the review of #809 asks for; tests, not a mechanism.
    @Test
    void theListedShopIsAlwaysFirstEvenWhenItKeepsNoGoods() {
        // Review of #809: a listed branch outside the stocking filter keeps its own quantity, with two other shops to
        // list.
        UUID third = UUID.randomUUID();
        TestDatabase.owner()
                .sql("INSERT INTO branches (id, tenant_id, code, name) VALUES (?, ?, 'BR3', 'Test Branch Three')")
                .params(third, t.tenantId())
                .update();
        api.stockUp(third, product, "2");
        api.importedBalance(t.headOffice(), product, "-3");
        TestDatabase.owner()
                .sql("UPDATE branches SET stocks_goods = false WHERE id = ?")
                .params(t.headOffice())
                .update();
        JsonNode shops = row(sellerStock(), product).get("shops");
        assertThat(shops).hasSize(3);
        assertThat(shops.get(0).get("branch_id").asString())
                .isEqualTo(t.headOffice().toString());
        assertThat(shops.get(0).get("here").asBoolean()).isTrue();
        assertThat(shops.get(0).get("qty").asString()).as("its own deficit").isEqualTo("-3.000");
        assertThat(shops.get(0).get("negative").asBoolean()).isTrue();
    }

    @Test
    void aClosedShopIsNeitherListedNorReadable() {
        // Review of #809: a closed shop holding less than none. Its stock is not read as a shop in use (422, "That
        // branch
        // is closed."), so its deficit cannot be hidden behind the other shops; reading an open shop leaves it out.
        UUID third = UUID.randomUUID();
        TestDatabase.owner()
                .sql("INSERT INTO branches (id, tenant_id, code, name) VALUES (?, ?, 'BR3', 'Test Branch Three')")
                .params(third, t.tenantId())
                .update();
        api.importedBalance(third, product, "-3");
        ResponseEntity<JsonNode> open =
                api.call(HttpMethod.GET, "/stock?branch_id=" + third, null, SELLER, "*", Map.of());
        assertThat(open.getStatusCode())
                .as("readable while open: %s", open.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(row(open.getBody(), product).get("shops").get(0).get("qty").asString())
                .isEqualTo("-3.000");
        TestDatabase.owner()
                .sql("UPDATE branches SET status = 'inactive' WHERE id = ?")
                .params(third)
                .update();
        ResponseEntity<JsonNode> closed =
                api.call(HttpMethod.GET, "/stock?branch_id=" + third, null, SELLER, "*", Map.of());
        assertThat(closed.getStatusCode()).as("%s", closed.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(closed.getBody().get("errors").get(0).get("code").asString()).isEqualTo("unknown_branch");
        assertThat(closed.getBody().get("errors").get(0).get("message").asString())
                .isEqualTo("That branch is closed.");
        JsonNode shops = row(sellerStock(), product).get("shops");
        assertThat(shops).hasSize(2);
        for (JsonNode shop : shops) {
            assertThat(shop.get("branch_id").asString()).isNotEqualTo(third.toString());
        }
    }

    @Test
    void anEmptyPageWithTheListOnIsFine() {
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.GET,
                "/stock?query=NOTHING-MATCHES-THIS&branch_id=" + t.headOffice(),
                null,
                SELLER,
                t.headOffice().toString(),
                Map.of());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("items")).isEmpty();
    }

    @Test
    void aReaderOfCostHereStillGetsQuantitiesOnlyForEveryShop() {
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.GET,
                "/stock?branch_id=" + t.headOffice(),
                null,
                SELLER,
                "*",
                Map.of("X-Dev-Scopes", "retail.profit.read=" + t.headOffice()));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode item = row(r.getBody(), product);
        assertThat(item.has("cost_minor"))
                .as("cost of the listed shop for its reader")
                .isTrue();
        for (JsonNode shop : item.get("shops")) {
            List<String> fields = new ArrayList<>(shop.propertyNames());
            assertThat(fields).containsExactlyInAnyOrder("branch_id", "name", "qty", "negative", "here");
        }
    }

    @Test
    void theOwnerSwitchIsOnByDefaultAndOffTakesTheListAway() {
        ResponseEntity<JsonNode> read = api.get("/settings/capabilities", "core.settings.manage");
        assertThat(read.getBody().get("other_shops_stock").asBoolean()).isTrue();

        ResponseEntity<JsonNode> off = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("other_shops_stock", false),
                "core.settings.manage",
                "*",
                Map.of("If-Match", read.getHeaders().getETag()));
        assertThat(off.getStatusCode()).as("%s", off.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(off.getBody().get("other_shops_stock").asBoolean()).isFalse();

        JsonNode item = row(sellerStock(), product);
        assertThat(item.has("shops")).isFalse();
        assertThat(item.get("qty").asString()).isEqualTo("0.000");

        ResponseEntity<JsonNode> wrong = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("other_shops_stock", "yes"),
                "core.settings.manage",
                "*",
                Map.of("If-Match", off.getHeaders().getETag()));
        assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
