package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.Cursor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/** Review F7 to F9 and F12: malformed and out-of-range input is refused with a 4xx, never a 500. */
class RetailInputGuardsIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-guard", false, true);
        api = new RetailTestSupport(http, t);
    }

    /** F8: every retail list answers a malformed cursor with 400 malformed_request. */
    @ParameterizedTest
    @ValueSource(strings = {"/sales", "/purchases", "/stock/movements", "/stock", "/products"})
    void aMalformedCursorIsABadRequest(String path) {
        String separator = path.equals("/stock") ? "?branch_id=" + t.headOffice() + "&" : "?";
        boolean byCode = path.equals("/stock") || path.equals("/products");
        String badTime = byCode ? "!!" : Cursor.encode("not-a-time|" + UUID.randomUUID());
        for (String cursor : new String[] {"Zm9v", "Zm9vfGJhcg", badTime, Cursor.encode("x|y"), "!!"}) {
            ResponseEntity<JsonNode> r = api.get(path + separator + "cursor=" + cursor, ADMIN);
            assertThat(r.getStatusCode())
                    .as("%s cursor %s: %s", path, cursor, r.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody().get("code").asString()).isEqualTo("malformed_request");
        }
    }

    /**
     * F9: a code that differs from another only by a no-break space at the end is the same code; a
     * full-width code folds to its ASCII form; a code with a control character or a Unicode space
     * inside is refused, and so is a raw insert that bypasses the API.
     */
    @Test
    void productCodesAreNormalisedAndUnicodeSpacesRefused() {
        api.product("NB-1", 100, 200);
        ResponseEntity<JsonNode> twin = api.post("/products", product("NB-1\u00a0"), ADMIN);
        assertThat(twin.getStatusCode()).as("%s", twin.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(twin.getBody().get("code").asString()).isEqualTo("duplicate_product_code");

        ResponseEntity<JsonNode> wide = api.post("/products", product("\u3000\uff2e\uff22-2"), ADMIN);
        assertThat(wide.getStatusCode()).as("%s", wide.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(wide.getBody().get("code").asString()).isEqualTo("NB-2");

        for (String bad : new String[] {"NB\u00a03", "NB\u200b4", "NB\t5", "\u00a0\u2003"}) {
            ResponseEntity<JsonNode> r = api.post("/products", product(bad), ADMIN);
            assertThat(r.getStatusCode()).as("%s: %s", bad, r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        UUID category = api.category("Test Category Raw");
        UUID unit = api.unit("u-raw");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("""
                                INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id,
                                    cost_minor, sell_minor, currency)
                                VALUES (gen_random_uuid(), ?, ?, 'Test raw', ?, ?, 1, 2, 'UGX')
                                """)
                        .params(t.tenantId(), "NB-6\u00a0", category, unit)
                        .update())
                .hasMessageContaining("retail_products_code_check");
    }

    Map<String, Object> product(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("description", "Test Product");
        body.put("category_id", api.category("Test Category " + UUID.randomUUID()));
        body.put("unit_id", api.unit("u-" + UUID.randomUUID().toString().substring(0, 8)));
        body.put("cost_minor", 100);
        body.put("sell_minor", 200);
        return body;
    }
}
