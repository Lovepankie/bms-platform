package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.HashMap;
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
 * Issue #815: the item search of the sale screen (and so the copy a phone keeps for selling offline) carries, with a branch,
 * the level at or below which the item is low there: the branch's own minimum, else the item's, else the stock list's
 * low_stock_threshold. Without a branch it is absent. Fabricated items and quantities.
 */
class RetailSaleStockWarningIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("stockwarn", false, true);
        api = new RetailTestSupport(http, t);
    }

    void minimum(UUID product, UUID branch, String qty) {
        Map<String, Object> body = new HashMap<>();
        body.put("min_qty", qty);
        String path = "/products/" + product + "/stock-minimum" + (branch == null ? "" : "/branches/" + branch);
        assertThat(api.call(HttpMethod.PUT, path, body, ADMIN, "*", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    JsonNode found(String code, UUID branch) {
        ResponseEntity<JsonNode> r =
                api.get("/products?query=" + code + (branch == null ? "" : "&branch_id=" + branch), SALES);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        for (JsonNode p : r.getBody().get("items")) {
            if (p.get("code").asString().equals(code)) {
                return p;
            }
        }
        throw new AssertionError(code + " not found");
    }

    @Test
    void anItemWithNoMinimumIsLowAtTheStockListsThreshold() {
        api.product("WARN-1", 600, 1_000);
        String threshold = api.get("/stock?branch_id=" + t.headOffice(), ADMIN)
                .getBody()
                .get("low_stock_threshold")
                .asString();
        JsonNode item = found("WARN-1", t.headOffice());
        assertThat(item.get("qty").asString()).isEqualTo("0.000");
        assertThat(item.get("low_stock_level").asString()).isEqualTo(threshold);
        assertThat(found("WARN-1", null).has("low_stock_level")).isFalse();
    }

    @Test
    void theBranchsOwnMinimumWinsOverTheItemsMinimum() {
        UUID product = api.product("WARN-2", 600, 1_000);
        minimum(product, null, "8");
        minimum(product, t.headOffice(), "3");
        assertThat(found("WARN-2", t.headOffice()).get("low_stock_level").asString())
                .isEqualTo("3.000");
        assertThat(found("WARN-2", t.secondBranch()).get("low_stock_level").asString())
                .isEqualTo("8.000");
    }

    @Test
    void theBarcodeLookupCarriesTheLevelToo() {
        UUID product = api.product("WARN-3", 600, 1_000);
        minimum(product, null, "12");
        api.stockUp(t.headOffice(), product, "4");
        ResponseEntity<JsonNode> code =
                api.post("/products/barcodes/generate", Map.of("product_ids", new UUID[] {product}), ADMIN);
        assertThat(code.getStatusCode().is2xxSuccessful())
                .as("%s", code.getBody())
                .isTrue();
        String barcode =
                found("WARN-3", null).get("barcodes").get(0).get("code").asString();
        JsonNode looked = api.get("/products/by-barcode/" + barcode + "?branch_id=" + t.headOffice(), SALES)
                .getBody()
                .get("product");
        assertThat(looked.get("qty").asString()).isEqualTo("4.000");
        assertThat(looked.get("low_stock_level").asString()).isEqualTo("12.000");
    }
}
