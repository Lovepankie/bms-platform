package com.rincoltech.bms.retail.catalogue;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
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
 * A product is marked inactive, never deleted (issue #342): it leaves the default stock lists, a sale, a restock and a
 * transfer of it are refused with 422 product_inactive, the stock on hand is reported on the product, and history stays.
 * Fabricated data.
 */
class RetailProductInactiveIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-inactive", false, true);
        api = new RetailTestSupport(http, t);
        product = api.product("OLD-LAMP", 1_000, 1_500);
        api.stockUp(t.headOffice(), product, "5");
    }

    void switchOff() {
        String etag = api.get("/products/" + product, ADMIN).getHeaders().getETag();
        ResponseEntity<JsonNode> off = api.call(
                HttpMethod.PATCH,
                "/products/" + product,
                Map.of("active", false),
                ADMIN,
                "*",
                Map.of("If-Match", etag));
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(off.getBody().get("active").asBoolean()).isFalse();
    }

    @Test
    void theProductReportsItsStockOnHandSoTheScreenCanWarn() {
        JsonNode one = api.get("/products/" + product, ADMIN).getBody();
        assertThat(one.get("stock_on_hand").asString()).isEqualTo("5.000");
        JsonNode list = api.get("/products", ADMIN).getBody().get("items");
        assertThat(list.get(0).has("stock_on_hand")).isFalse();
    }

    @Test
    void anInactiveProductLeavesTheDefaultStockListsAndComesBackOnRequest() {
        switchOff();
        assertThat(ids(api.get("/stock?branch_id=" + t.headOffice(), ADMIN))).doesNotContain(product.toString());
        assertThat(ids(api.get("/stock?branch_id=" + t.headOffice() + "&include_inactive=true", ADMIN)))
                .contains(product.toString());
        assertThat(ids(api.get("/stock/all-branches", ADMIN))).doesNotContain(product.toString());
        assertThat(ids(api.get("/stock/all-branches?include_inactive=true", ADMIN)))
                .contains(product.toString());
        // The record, its history and the stock-by-location report keep it.
        assertThat(api.get("/products/" + product, ADMIN).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(api.get("/stock/movements?product_id=" + product, ADMIN)
                        .getBody()
                        .get("items"))
                .isNotEmpty();
    }

    @Test
    void anInactiveProductCannotBeSoldRestockedOrTransferred() {
        switchOff();
        ResponseEntity<JsonNode> sale = api.sell(t.headOffice(), "cash", product, "1");
        assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(sale.getBody().toString()).contains("product_inactive").contains("inactive");

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", 1_100);
        line.put("qty_by_branch", List.of(Map.of("branch_id", t.headOffice(), "qty", "2")));
        ResponseEntity<JsonNode> restock = api.postKeyed(
                "/purchases",
                Map.of("purchased_on", "2026-10-20", "payment_method", "cash", "lines", List.of(line)),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(restock.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(restock.getBody().toString()).contains("product_inactive");

        Map<String, Object> move = new LinkedHashMap<>();
        move.put("from_branch_id", t.headOffice());
        move.put("to_branch_id", t.secondBranch());
        move.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        ResponseEntity<JsonNode> transfer =
                api.postKeyed("/transfers", move, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(transfer.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(transfer.getBody().toString()).contains("product_inactive");
    }

    @Test
    void switchingAProductOnAgainWorksAndNeedsTheCatalogueManagePermission() {
        switchOff();
        String etag = api.get("/products/" + product, ADMIN).getHeaders().getETag();
        assertThat(api.call(
                                HttpMethod.PATCH,
                                "/products/" + product,
                                Map.of("active", true),
                                SALES,
                                "*",
                                Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.call(
                                HttpMethod.PATCH,
                                "/products/" + product,
                                Map.of("active", true),
                                ADMIN,
                                "*",
                                Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.sell(t.headOffice(), "cash", product, "1").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    private static List<String> ids(ResponseEntity<JsonNode> response) {
        return response.getBody()
                .get("items")
                .valueStream()
                .map(r -> r.get("product_id").asString())
                .toList();
    }

    /** A positive and a negative balance net to zero, yet two branches hold a quantity; a caller who reads one branch sees one. */
    @Test
    void theStockWarningCountsEveryBranchWithAQuantityWithinTheCallersScope() {
        api.importedBalance(t.secondBranch(), product, "-5");
        JsonNode all = api.get("/products/" + product, ADMIN).getBody();
        assertThat(all.get("stock_on_hand").asString()).isEqualTo("0.000");
        assertThat(all.get("stock_branches").asInt()).isEqualTo(2);

        JsonNode one = api.call(
                        HttpMethod.GET,
                        "/products/" + product,
                        null,
                        ADMIN,
                        t.headOffice().toString(),
                        Map.of())
                .getBody();
        assertThat(one.get("stock_branches").asInt()).isEqualTo(1);
        assertThat(one.get("stock_on_hand").asString()).isEqualTo("5.000");
    }
}
