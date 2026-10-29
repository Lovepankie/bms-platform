package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
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
 * Issue #689 (ADR-106): Add an item makes the item in its smallest unit and its packages in one request and one
 * transaction, and a package word is never the smallest unit. Every name and amount is fabricated.
 */
class RetailNewItemPacksIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    /** Manages the catalogue but may not change selling prices. */
    static final String CATALOGUE_ONLY = ADMIN.replace("retail.price.edit,", "");

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID category;
    UUID bulb;
    UUID carton;
    UUID box;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-new-item-packs", false, true);
        api = new RetailTestSupport(http, t);
        category = api.category("Test Lighting");
        bulb = api.unit("Bulb");
        carton = api.unit("Carton");
        box = api.unit("Box");
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> on = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("units", true),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
    }

    Map<String, Object> item(String name, UUID unit, List<Map<String, Object>> packs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", name);
        body.put("category_id", category);
        body.put("unit_id", unit);
        body.put("cost_minor", 3_000);
        body.put("sell_minor", 5_000);
        if (packs != null) {
            body.put("packs", packs);
        }
        return body;
    }

    static Map<String, Object> pack(UUID unit, String factor, String purpose, Long sell) {
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("unit_id", unit);
        k.put("factor", factor);
        k.put("purpose", purpose);
        if (sell != null) {
            k.put("sell_minor", sell);
        }
        return k;
    }

    long products(String name) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_products WHERE tenant_id = ? AND description = ?")
                .params(t.tenantId(), name)
                .query(Long.class)
                .single();
    }

    long packRows() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_product_units WHERE tenant_id = ?")
                .params(t.tenantId())
                .query(Long.class)
                .single();
    }

    @Test
    void theWordListKnowsAPackageFromASmallestUnit() {
        for (String word : List.of(
                "Box",
                "boxes",
                "CARTON",
                "Dozen",
                "pack",
                "Packets",
                "bag",
                "Sacks",
                "crate",
                "case",
                "Trays",
                "bundle",
                "Bales",
                "Set of 6",
                "sets of 12",
                "Carton of 24",
                "  box  ")) {
            assertThat(RetailCatalogue.isPackageWord(word)).as(word).isTrue();
        }
        for (String word : List.of("Piece", "Bulb", "Metre", "Litre", "Set", "Boxer", "Kg", "Packing tape", "Bag of cement", "")) {
            assertThat(RetailCatalogue.isPackageWord(word)).as(word).isFalse();
        }
        assertThat(RetailCatalogue.isPackageWord(null)).isFalse();
    }

    @Test
    void aPackageIsNeverTheSmallestUnit() {
        ResponseEntity<JsonNode> r = api.post("/products", item("Test Bulb 60W", box, null), ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString())
                .contains("unit_is_package")
                .contains("A box is a package. Choose the smallest unit, like piece, then add the box below.");
        ResponseEntity<JsonNode> cartons = api.post("/products", item("Test Bulb 60W", carton, null), ADMIN);
        assertThat(cartons.getBody().toString()).contains("A carton is a package.");
        assertThat(products("Test Bulb 60W")).isZero();

        // Every unit says whether it is a package, so the screens read the same list.
        Map<String, Boolean> isPackage = new LinkedHashMap<>();
        for (JsonNode u : api.get("/units", ADMIN).getBody().get("items")) {
            isPackage.put(u.get("id").asString(), u.get("is_package").asBoolean());
        }
        assertThat(isPackage.get(box.toString())).isTrue();
        assertThat(isPackage.get(carton.toString())).isTrue();
        assertThat(isPackage.get(bulb.toString())).isFalse();
    }

    @Test
    void anItemAndTwoPackagesAreMadeInOneRequest() {
        ResponseEntity<JsonNode> r = api.post(
                "/products",
                item(
                        "Test Bulb 60W",
                        bulb,
                        List.of(pack(carton, "10", "both", 45_000L), pack(box, "100", "buy", null))),
                ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID id = RetailTestSupport.id(r);
        JsonNode packs = api.get("/products/" + id + "/units", ADMIN).getBody().get("items");
        assertThat(packs).hasSize(2);
        for (JsonNode k : packs) {
            if (k.get("unit_id").asString().equals(carton.toString())) {
                assertThat(k.get("factor").asString()).isEqualTo("10.000");
                assertThat(k.get("purpose").asString()).isEqualTo("both");
                assertThat(k.get("sell_minor").asLong()).isEqualTo(45_000);
            } else {
                assertThat(k.get("unit_id").asString()).isEqualTo(box.toString());
                assertThat(k.get("factor").asString()).isEqualTo("100.000");
                assertThat(k.get("purpose").asString()).isEqualTo("buy");
                assertThat(k.has("sell_minor")).isFalse();
            }
        }
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.product_unit.created'")
                        .params(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void aRefusedPackageSavesNothing() {
        // The second package repeats the first unit: the item and the first package are made, then the whole
        // transaction is rolled back.
        ResponseEntity<JsonNode> twice = api.post(
                "/products",
                item(
                        "Test Bulb 60W",
                        bulb,
                        List.of(pack(carton, "10", "both", null), pack(carton, "20", "both", null))),
                ADMIN);
        assertThat(twice.getStatusCode()).as("%s", twice.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(twice.getBody().toString()).contains("duplicate_pack");

        // A package in the item's own unit is refused after the item is written.
        ResponseEntity<JsonNode> own =
                api.post("/products", item("Test Bulb 60W", bulb, List.of(pack(bulb, "10", "both", null))), ADMIN);
        assertThat(own.getStatusCode()).as("%s", own.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(own.getBody().toString()).contains("pack_unit_is_base_unit");

        // A price on a package from someone who may not change prices is refused before anything is written.
        ResponseEntity<JsonNode> priced = api.post(
                "/products", item("Test Bulb 60W", bulb, List.of(pack(carton, "10", "both", 45_000L))), CATALOGUE_ONLY);
        assertThat(priced.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(products("Test Bulb 60W")).isZero();
        assertThat(packRows()).isZero();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action LIKE 'retail.product%created'")
                        .params(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void packagesNeedTheUnitsCapability() {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("units", false),
                SETTINGS,
                "*",
                Map.of("If-Match", etag));
        ResponseEntity<JsonNode> r =
                api.post("/products", item("Test Bulb 60W", bulb, List.of(pack(carton, "10", "both", null))), ADMIN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(products("Test Bulb 60W")).isZero();
        // Without packages the item is made as before.
        assertThat(api.post("/products", item("Test Bulb 60W", bulb, null), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }
}
