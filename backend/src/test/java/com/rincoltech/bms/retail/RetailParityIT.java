package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The retail parity pass (#145, #144): categories on the stock views, expected profit, the
 * all-branches stock, low and out of stock, and the sales filters. Fabricated figures.
 */
class RetailParityIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID cable;
    UUID bulb;

    /** Two products, each in its own category "Test Category CODE". */
    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-par", false, true);
        api = new RetailTestSupport(http, t);
        cable = api.product("PAR-CABLE", 1_000, 1_500);
        bulb = api.product("PAR-BULB", 200, 300);
        api.stockUp(t.headOffice(), cable, "10");
        api.stockUp(t.headOffice(), bulb, "4");
    }

    JsonNode ok(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void stockRowsCarryTheCategoryAndFilterByIt() {
        JsonNode all = ok(api.get("/stock?branch_id=" + t.headOffice(), ADMIN)).get("items");
        assertThat(all).hasSize(2);
        assertThat(all.get(0).get("category").asString()).isEqualTo("Test Category PAR-BULB");
        assertThat(all.get(0).has("category_id")).isTrue();

        UUID categoryId = UUID.fromString(all.get(1).get("category_id").asString());
        JsonNode one = ok(api.get("/stock?branch_id=" + t.headOffice() + "&category_id=" + categoryId, ADMIN))
                .get("items");
        assertThat(one).hasSize(1);
        assertThat(one.get(0).get("code").asString()).isEqualTo("PAR-CABLE");
    }

    @Test
    void searchMatchesTheCategoryTextOnStockAndProducts() {
        JsonNode stock = ok(api.get("/stock?branch_id=" + t.headOffice() + "&query=category par-cab", ADMIN))
                .get("items");
        assertThat(stock).hasSize(1);
        assertThat(stock.get(0).get("code").asString()).isEqualTo("PAR-CABLE");
        JsonNode products = ok(api.get("/products?query=Category PAR-BULB&branch_id=" + t.headOffice(), ADMIN))
                .get("items");
        assertThat(products).hasSize(1);
        assertThat(products.get(0).get("code").asString()).isEqualTo("PAR-BULB");
        assertThat(products.get(0).get("category").asString()).isEqualTo("Test Category PAR-BULB");
    }
}
