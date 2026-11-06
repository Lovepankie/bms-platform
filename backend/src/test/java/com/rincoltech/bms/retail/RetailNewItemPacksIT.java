package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import java.nio.file.Path;
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
import tools.jackson.databind.json.JsonMapper;

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
                "  box  ",
                "Ctn",
                "ctns",
                "PKT",
                "Pkts",
                "bx",
                "Doz",
                "dz")) {
            assertThat(RetailCatalogue.isPackageWord(word)).as(word).isTrue();
        }
        for (String word : List.of(
                "Piece",
                "pc",
                "pcs",
                "Bulb",
                "Metre",
                "Litre",
                "Set",
                "Boxer",
                // Issue #709 round 2: Roll is a standard package that may also be an item's single unit.
                "Roll",
                "rolls",
                "Kg",
                "Packing tape",
                "Bag of cement",
                "")) {
            assertThat(RetailCatalogue.isPackageWord(word)).as(word).isFalse();
        }
        assertThat(RetailCatalogue.isPackageWord(null)).isFalse();
        assertThat(RetailCatalogue.packageAsUnit("Sets of 4")).startsWith("Sets of 4 is a package.");
        assertThat(RetailCatalogue.packageAsUnit("Set of 4")).startsWith("A set of 4 is a package.");
        assertThat(RetailCatalogue.packageAsUnit("Ctn"))
                .isEqualTo("A carton is a package. Choose a single unit, like piece, then add the carton below.");
        assertThat(RetailCatalogue.packageAsUnit("PKT")).startsWith("A packet is a package.");
        // Every short code the word list refuses reads as its word, as unitWord says it in the PWA (round 4 review of
        // #692).
        for (String[] code :
                new String[][] {{"Ctns", "carton"}, {"pkts", "packet"}, {"Bx", "box"}, {"DOZ", "dozen"}, {"dz", "dozen"}
                }) {
            assertThat(RetailCatalogue.packageAsUnit(code[0]))
                    .as(code[0])
                    .startsWith("A " + code[1] + " is a package.");
        }
        // Review of #692: the sentences the PWA's test checks too, from one file
        // (fixtures/retail/package-refusals-689.json), so the server and the screen agree, and the file has every code.
        JsonNode cases = JsonMapper.builder()
                .build()
                .readTree(
                        Path.of("../fixtures/retail/package-refusals-689.json").toFile())
                .get("cases");
        for (JsonNode c : cases) {
            String unit = c.get("unit").asString();
            assertThat(RetailCatalogue.isPackageWord(unit)).as(unit).isTrue();
            assertThat(RetailCatalogue.packageAsUnit(unit))
                    .as(unit)
                    .isEqualTo(c.get("sentence").asString());
        }
        // Every package short code of the one list of unit words (ADR-109).
        List<String> packageCodes = JsonMapper.builder()
                .build()
                .readTree(Path.of("../frontend/src/words/unit-words.json").toFile())
                .get("units")
                .valueStream()
                .flatMap(u -> u.get("codes").valueStream().map(JsonNode::asString))
                .filter(RetailCatalogue::isPackageWord)
                .toList();
        assertThat(packageCodes).contains("ctn", "bx", "dz");
        for (String code : packageCodes) {
            assertThat(cases.valueStream()
                            .anyMatch(c -> c.get("unit").asString().equalsIgnoreCase(code)))
                    .as("the fixture has %s", code)
                    .isTrue();
        }
    }

    /** Review of #692: Add an item with sizes and colours makes items too, and never in a package. */
    // JUSTIFICATION-A3: the review of #692 asks for this route's refusal to be proven by an IT.
    @Test
    void anItemWithSizesIsNeverCountedInAPackage() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Test Bulb");
        body.put("category_id", category);
        body.put("unit_id", box);
        body.put("cost_minor", 3_000);
        body.put("sell_minor", 5_000);
        body.put("sizes", List.of("40W", "60W"));
        ResponseEntity<JsonNode> r = api.post("/product-families", body, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("unit_is_package").contains("A box is a package.");
        body.put("unit_id", bulb);
        assertThat(api.post("/product-families", body, ADMIN).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    /** Round 2 of #709: Roll is a standard package, yet an item may be counted in rolls (tape, tissue, wire). */
    // JUSTIFICATION-A3: the owner's round 2 instruction asks an IT for an item with single unit Roll.
    @Test
    void anItemMayBeCountedInRolls() {
        UUID roll = api.unit("Roll");
        ResponseEntity<JsonNode> r = api.post("/products", item("Test Insulation Tape", roll, null), ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        for (JsonNode u : api.get("/units", ADMIN).getBody().get("items")) {
            if (u.get("id").asString().equals(roll.toString())) {
                assertThat(u.get("is_package").asBoolean()).isFalse();
                assertThat(u.get("standard").asBoolean()).isTrue();
            }
        }
    }

    @Test
    void aPackageIsNeverTheSmallestUnit() {
        ResponseEntity<JsonNode> r = api.post("/products", item("Test Bulb 60W", box, null), ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString())
                .contains("unit_is_package")
                .contains("A box is a package. Choose a single unit, like piece, then add the box below.");
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

    /** Review of #692, round 4: a pack's own bounds are checked by the service, so an oversized size names its pack. */
    // JUSTIFICATION-A3: round 4 of the #692 review asks for this IT on an oversized factor.
    @Test
    void anOversizedPackageSizeIsRefusedOnItsRow() {
        ResponseEntity<JsonNode> r = api.post(
                "/products",
                item(
                        "Test Bulb 60W",
                        bulb,
                        List.of(pack(carton, "10", "both", null), pack(box, "1000000000000", "buy", null))),
                ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("pack_index").asInt()).isEqualTo(1);
        assertThat(r.getBody().toString()).contains("packs[1].factor");
        assertThat(products("Test Bulb 60W")).isZero();
        assertThat(packRows()).isZero();
    }

    /**
     * Review of #692, round 5: the shape of every refusal of {@code packs}. A missing package names its row; more than ten
     * (the form offers no more) is a 422 on {@code packs}; a size that is not a number is a 422 on its package's factor.
     */
    // JUSTIFICATION-A3: the round 5 review asks for an IT pinning these shapes.
    @Test
    void everyRefusalOfThePackagesHasAKnownShape() {
        List<Map<String, Object>> withNull = new java.util.ArrayList<>();
        withNull.add(pack(carton, "10", "both", null));
        withNull.add(null);
        ResponseEntity<JsonNode> missing = api.post("/products", item("Test Bulb 60W", bulb, withNull), ADMIN);
        assertThat(missing.getStatusCode()).as("%s", missing.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(missing.getBody().get("pack_index").asInt()).isEqualTo(1);
        assertThat(missing.getBody().toString()).contains("packs[1].unit_id");

        List<Map<String, Object>> eleven = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(pack(carton, "10", "both", null));
        }
        ResponseEntity<JsonNode> tooMany = api.post("/products", item("Test Bulb 60W", bulb, eleven), ADMIN);
        assertThat(tooMany.getStatusCode()).as("%s", tooMany.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(tooMany.getBody().toString()).contains("\"field\":\"packs\"");

        ResponseEntity<JsonNode> notANumber =
                api.post("/products", item("Test Bulb 60W", bulb, List.of(pack(carton, "ten", "both", null))), ADMIN);
        assertThat(notANumber.getStatusCode())
                .as("%s", notANumber.getBody())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(notANumber.getBody().get("pack_index").asInt()).isZero();
        assertThat(notANumber.getBody().toString()).contains("packs[0].factor");

        assertThat(products("Test Bulb 60W")).isZero();
        assertThat(packRows()).isZero();
    }

    /** Review of #692, round 4: Quick add makes items too, and never in a package. */
    @Test
    void quickAddNeverCountsAnItemInAPackage() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", "Test Bulb 60W");
        body.put("sell_minor", 5_000);
        body.put("category_id", category);
        body.put("unit_id", box);
        body.put("qty", "10");
        body.put("branch_id", t.headOffice());
        ResponseEntity<JsonNode> r =
                api.postKeyed("/quick-add", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("unit_is_package").contains("A box is a package.");
        assertThat(products("Test Bulb 60W")).isZero();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_stock_balances WHERE tenant_id = ?")
                        .params(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
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
        // Review of #692: the refusal names the package it is about, never the item's own unit.
        assertThat(twice.getBody().get("pack_index").asInt()).isEqualTo(1);

        // A package in the item's own unit is refused after the item is written.
        ResponseEntity<JsonNode> own =
                api.post("/products", item("Test Bulb 60W", bulb, List.of(pack(bulb, "10", "both", null))), ADMIN);
        assertThat(own.getStatusCode()).as("%s", own.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(own.getBody().toString()).contains("pack_unit_is_base_unit");
        assertThat(own.getBody().get("pack_index").asInt()).isZero();

        // A package unit switched off meanwhile is refused on that package's field.
        String version = api.get("/units", ADMIN)
                .getBody()
                .get("items")
                .valueStream()
                .filter(u -> u.get("id").asString().equals(box.toString()))
                .findFirst()
                .orElseThrow()
                .get("version")
                .asString();
        api.call(HttpMethod.PATCH, "/units/" + box, Map.of("active", false), ADMIN, "*", Map.of("If-Match", version));
        ResponseEntity<JsonNode> off = api.post(
                "/products",
                item("Test Bulb 60W", bulb, List.of(pack(carton, "10", "both", null), pack(box, "100", "buy", null))),
                ADMIN);
        assertThat(off.getStatusCode()).as("%s", off.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(off.getBody().get("pack_index").asInt()).isEqualTo(1);
        assertThat(off.getBody().toString()).contains("packs[1].unit_id").contains("inactive_unit");

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

    List<String> itemsIn(RetailTestSupport on, UUID unit) {
        ResponseEntity<JsonNode> r = on.get("/products?unit_id=" + unit, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody()
                .get("items")
                .valueStream()
                .map(p -> p.get("description").asString())
                .toList();
    }

    /** Issue #863: the Units page opens one unit's items, those counted in it and those with an active package of it. */
    // JUSTIFICATION-A3: issue #863 asks for tests of the new unit_id filter, tenant scoping included.
    @Test
    void theItemsListShowsOneUnitsItems() {
        assertThat(api.post("/products", item("Test Bulb 60W", bulb, List.of(pack(carton, "10", "both", null))), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        UUID roll = api.unit("Roll");
        assertThat(api.post("/products", item("Test Insulation Tape", roll, null), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(itemsIn(api, bulb)).containsExactly("Test Bulb 60W");
        assertThat(itemsIn(api, carton)).containsExactly("Test Bulb 60W");
        assertThat(itemsIn(api, roll)).containsExactly("Test Insulation Tape");
        assertThat(itemsIn(api, box)).isEmpty();
        assertThat(itemsIn(api, UUID.randomUUID())).isEmpty();
        // Tenant scoped: another business's unit finds none of these items, and this unit finds none of theirs.
        TestDatabase.Fixture other = TestDatabase.tenant("retail-new-item-packs-other", false, true);
        RetailTestSupport there = new RetailTestSupport(http, other);
        UUID theirs = there.unit("Roll");
        assertThat(itemsIn(api, theirs)).isEmpty();
        assertThat(itemsIn(there, roll)).isEmpty();
    }
}
