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
     * F9: a code that differs from another only by a no-break space at the end is the same code; a full-width code folds to
     * its ASCII form; a code with a control character or a Unicode space inside is refused, and so is a raw insert that
     * bypasses the API. Issue #562: an add takes no code at all, so the rule now shows where a code is still read, the import
     * finding an item by its code.
     */
    @Test
    void productCodesAreNormalisedAndUnicodeSpacesRefused() {
        api.product("NB-1", 100, 200);
        for (String typed : new String[] {"NB-1\u00a0", "\u3000\uff2e\uff22-2", "NB\u200b4", "\u00a0\u2003"}) {
            ResponseEntity<JsonNode> r = api.post("/products", product(typed), ADMIN);
            assertThat(r.getStatusCode()).as("%s: %s", typed, r.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(r.getBody().get("code").asString()).isEqualTo("item_code_made_by_app");
        }
        String csv = "code,description,category,unit,sell price\n"
                + "NB-1\u00a0,Test twin,Test Category NB-1,u-nb-1,500\n"
                + "\uff2e\uff22-1,Test wide twin,Test Category NB-1,u-nb-1,500\n";
        ResponseEntity<JsonNode> imported =
                api.post("/products/import?dry_run=true", Map.of("csv", csv), ADMIN + ",core.settings.manage");
        assertThat(imported.getStatusCode()).as("%s", imported.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(imported.getBody().get("skipped").asInt())
                .as("%s", imported.getBody())
                .isEqualTo(2);
        // The import keeps a file's codes while the tick is off, so it is where a code with a control character or a
        // Unicode space inside is refused now: an error row, never a code of its own.
        String bad = "code,description,category,unit,sell price\n"
                + "NB\u200b4,Test zero width,Test Category NB-1,u-nb-1,500\n"
                + "NB\t5,Test tab,Test Category NB-1,u-nb-1,500\n"
                + "NB\u00a03,Test inner space,Test Category NB-1,u-nb-1,500\n";
        JsonNode rows = api.post("/products/import?dry_run=true", Map.of("csv", bad), ADMIN + ",core.settings.manage")
                .getBody()
                .get("rows");
        assertThat(rows).hasSize(3);
        for (JsonNode row : rows) {
            assertThat(row.get("outcome").asString()).as("%s", row).isEqualTo("error");
            assertThat(row.get("message").asString()).as("%s", row).contains("special character");
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

    /**
     * F7: every amount in minor units is bounded at 10^13 by validation and by a database CHECK; a
     * product of value beyond a long is 422 amount_out_of_range on a sale; and one such product
     * leaves the valuation report working for every other row.
     */
    @Test
    void amountsAreBoundedAndOverflowIsNot500() {
        Map<String, Object> huge = product("BIG-1");
        huge.put("cost_minor", 10_000_000_000_001L);
        ResponseEntity<JsonNode> refused = api.post("/products", huge, ADMIN);
        assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("errors").get(0).get("field").asString())
                .isEqualTo("cost_minor");

        UUID normal = api.product("OK-1", 100, 200);
        api.stockUp(t.headOffice(), normal, "10");
        UUID big = api.product("BIG-2", 10_000_000_000_000L, 10_000_000_000_000L);
        api.importedBalance(t.headOffice(), big, "90000000000");

        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("branch_id", t.headOffice());
        sale.put("payment_method", "cash");
        sale.put("lines", java.util.List.of(Map.of("product_id", big, "qty", "90000000000")));
        ResponseEntity<JsonNode> overflow =
                api.postKeyed("/sales", sale, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(overflow.getStatusCode()).as("%s", overflow.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(overflow.getBody().get("code").asString()).isEqualTo("amount_out_of_range");

        ResponseEntity<JsonNode> valuation = api.get("/reports/valuation", ADMIN);
        assertThat(valuation.getStatusCode()).as("%s", valuation.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode rows = valuation.getBody().get("rows");
        assertThat(rows).hasSize(2);
        for (JsonNode row : rows) {
            if (row.get("code").asString().equals("OK-1")) {
                assertThat(row.get("value_at_cost_minor").asLong()).isEqualTo(1_000);
                assertThat(row.get("amount_out_of_range").asBoolean()).isFalse();
            } else {
                assertThat(row.get("amount_out_of_range").asBoolean()).isTrue();
                assertThat(row.has("value_at_cost_minor")).isFalse();
            }
        }
        assertThat(valuation.getBody().get("value_at_cost_minor").asLong()).isEqualTo(1_000);

        UUID category = api.category("Test Category Raw Amount");
        UUID unit = api.unit("u-rawamt");
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("""
                                INSERT INTO retail_products (id, tenant_id, code, description, category_id, unit_id,
                                    cost_minor, sell_minor, currency)
                                VALUES (gen_random_uuid(), ?, 'RAW-AMT', 'Test raw', ?, ?, 10000000000001, 2, 'UGX')
                                """)
                        .params(t.tenantId(), category, unit)
                        .update())
                .hasMessageContaining("retail_products_cost_minor_range");
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
