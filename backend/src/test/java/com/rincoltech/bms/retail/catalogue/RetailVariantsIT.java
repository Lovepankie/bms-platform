package com.rincoltech.bms.retail.catalogue;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.ArrayList;
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
 * Items with sizes and colours (issue #384, ADR-051, FR-RET-51 and FR-RET-52): the quick creation of every combination, a
 * variant's own code, prices and stock, every stock path (restock, sale, move, stock-take, usage, return) per variant, the
 * item's stock and the sales and restock summaries rolled up to the item, an existing item given sizes keeping its stock
 * and history, and items without variants exactly as before. Every name and amount is fabricated.
 */
class RetailVariantsIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    static final String RETURNER = ADMIN + ",retail.sale.return,retail.cashbook.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;
    UUID category;
    UUID pieces;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-variants", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
        category = RetailTestSupport.id(
                api.post("/categories", Map.of("name", "Test Baby Wear", "code_prefix", "ROMP"), ADMIN));
        pieces = api.unit("test-piece");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Issue #562: the item's codes start with the category's next code, ROMP-0001 in the first item of a test. */
    ResponseEntity<JsonNode> createFamily(String name, List<String> sizes, List<String> colours) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("category_id", category);
        body.put("unit_id", pieces);
        body.put("cost_minor", 4_000);
        body.put("sell_minor", 9_000);
        body.put("sizes", sizes);
        body.put("colours", colours);
        return api.post("/product-families", body, ADMIN);
    }

    JsonNode romper() {
        ResponseEntity<JsonNode> r =
                createFamily("Test Romper", List.of("0-3m", "3-6m", "6-12m"), List.of("Blue", "Pink"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    static UUID variant(JsonNode family, String size, String colour) {
        for (JsonNode v : family.get("variants")) {
            JsonNode of = v.get("variant");
            if (size.equals(text(of, "size")) && colour.equals(text(of, "colour"))) {
                return UUID.fromString(v.get("id").asString());
            }
        }
        throw new AssertionError("no " + size + " " + colour + " in " + family);
    }

    static String text(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asString() : null;
    }

    JsonNode family(UUID id) {
        ResponseEntity<JsonNode> r = api.get("/product-families/" + id, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    String qty(UUID branch, UUID product) {
        return TestDatabase.owner()
                .sql(
                        "SELECT qty::text FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), branch, product)
                .query(String.class)
                .optional()
                .orElse("0.000");
    }

    void restock(UUID branch, Object... productQty) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i < productQty.length; i += 2) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("product_id", productQty[i]);
            line.put("cost_minor", 4_000);
            line.put("qty_by_branch", List.of(Map.of("branch_id", branch, "qty", productQty[i + 1])));
            lines.add(line);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", "2026-10-05");
        body.put("payment_method", "cash");
        body.put("lines", lines);
        ResponseEntity<JsonNode> r =
                api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    UUID sell(UUID branch, UUID product, String qty) {
        ResponseEntity<JsonNode> r = api.sell(branch, "cash", product, qty);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    JsonNode summary(String query) {
        ResponseEntity<JsonNode> r = api.get(query, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static JsonNode group(JsonNode summary, String key) {
        for (JsonNode g : summary.get("items")) {
            if (g.get("key").asString().equals(key)) {
                return g;
            }
        }
        throw new AssertionError("no group " + key + " in " + summary);
    }

    void capability(String key, boolean on) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of(key, on),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<JsonNode> patchProduct(UUID product, Map<String, Object> change) {
        String etag = api.get("/products/" + product, ADMIN).getHeaders().getETag();
        return api.call(HttpMethod.PATCH, "/products/" + product, change, ADMIN, "*", Map.of("If-Match", etag));
    }

    // ---- quick creation --------------------------------------------------------------------

    @Test
    void frRet51ChoosingSizesAndColoursOnceMakesEveryVariantWithItsOwnCodeAndPrices() {
        JsonNode f = romper();
        assertThat(f.get("name").asString()).isEqualTo("Test Romper");
        assertThat(f.get("sizes").toString()).isEqualTo("[\"0-3m\",\"3-6m\",\"6-12m\"]");
        assertThat(f.get("colours").toString()).isEqualTo("[\"Blue\",\"Pink\"]");
        assertThat(f.get("variants")).hasSize(6);
        assertThat(f.get("stock_on_hand").asString()).isEqualTo("0.000");
        JsonNode first = f.get("variants").get(0);
        assertThat(first.get("code").asString()).isEqualTo("ROMP-0001-03M-BLUE");
        assertThat(first.get("description").asString()).isEqualTo("Test Romper (0-3m, Blue)");
        assertThat(first.get("sell_minor").asLong()).isEqualTo(9_000);
        assertThat(first.get("cost_minor").asLong()).isEqualTo(4_000);
        assertThat(first.get("category_id").asString()).isEqualTo(category.toString());
        assertThat(first.get("variant").get("family_name").asString()).isEqualTo("Test Romper");
        assertThat(f.get("variants").get(5).get("code").asString()).isEqualTo("ROMP-0001-612M-PINK");

        // Each variant is a product: it has its own page, price history and a place in the item list.
        UUID blue = variant(f, "0-3m", "Blue");
        JsonNode product = api.get("/products/" + blue, ADMIN).getBody();
        assertThat(product.get("variant").get("size").asString()).isEqualTo("0-3m");
        assertThat(api.get("/products/" + blue + "/price-history", ADMIN)
                        .getBody()
                        .get("items"))
                .hasSize(1);
        JsonNode search = api.get("/products?query=romper", ADMIN).getBody();
        assertThat(search.get("items")).hasSize(6);

        // A combination already there is skipped; a new size is added at the item's prices.
        Map<String, Object> more = new LinkedHashMap<>();
        more.put("sizes", List.of("0-3m", "12-18m"));
        more.put("colours", List.of("Blue"));
        ResponseEntity<JsonNode> added =
                api.post("/product-families/" + f.get("id").asString() + "/variants", more, ADMIN);
        assertThat(added.getStatusCode()).as("%s", added.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(added.getBody().get("variants")).hasSize(7);
        JsonNode last = added.getBody().get("variants").get(6);
        assertThat(last.get("code").asString()).isEqualTo("ROMP-0001-1218M-BLUE");
        assertThat(last.get("sell_minor").asLong()).isEqualTo(9_000);
        assertThat(last.get("variant").get("position").asInt()).isEqualTo(6);
        ResponseEntity<JsonNode> nothing =
                api.post("/product-families/" + f.get("id").asString() + "/variants", more, ADMIN);
        assertThat(nothing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(nothing.getBody().get("code").asString()).isEqualTo("nothing_to_add");
    }

    @Test
    void frRet51NamedVariantsTakeTheirOwnPricesAndTheAppsCodesAndCodesStayUnique() {
        // An item that already holds the code the app would give a size: the item is refused and nothing is kept, not
        // even the number, so the next item gets ROMP-0001 again.
        api.existingCode(api.product("ROMP-OLD", 1, 2), "ROMP-0001-03M-BLUE");
        ResponseEntity<JsonNode> clash = createFamily("Test Romper", List.of("0-3m"), List.of("Blue"));
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(clash.getBody().get("code").asString()).isEqualTo("duplicate_product_code");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_product_families WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .as("nothing is kept of a refused item")
                .isZero();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Test Cable");
        body.put("category_id", category);
        body.put("unit_id", pieces);
        body.put("cost_minor", 1_000);
        body.put("sell_minor", 1_500);
        body.put(
                "variants",
                List.of(Map.of("size", "1.5mm", "sell_minor", 1_200), Map.of("size", "2.5mm", "colour", "Red")));
        ResponseEntity<JsonNode> r = api.post("/product-families", body, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("base_code").asString()).isEqualTo("ROMP-0001");
        JsonNode thin = r.getBody().get("variants").get(0);
        assertThat(thin.get("code").asString()).isEqualTo("ROMP-0001-15MM");
        assertThat(thin.get("sell_minor").asLong()).isEqualTo(1_200);
        assertThat(thin.get("cost_minor").asLong()).isEqualTo(1_000);
        assertThat(thin.get("description").asString()).isEqualTo("Test Cable (1.5mm)");
        assertThat(r.getBody().get("variants").get(1).get("code").asString()).isEqualTo("ROMP-0001-25MM-RED");
    }

    @Test
    void issue562ACodeTypedForAnItemWithSizesOrForOneOfItsSizesIsRefused() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Test Cable");
        body.put("base_code", "CAB");
        body.put("category_id", category);
        body.put("unit_id", pieces);
        body.put("cost_minor", 1_000);
        body.put("sell_minor", 1_500);
        body.put("sizes", List.of("1.5mm"));
        ResponseEntity<JsonNode> base = api.post("/product-families", body, ADMIN);
        assertThat(base.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(base.getBody().get("code").asString()).isEqualTo("item_code_made_by_app");

        body.remove("base_code");
        body.remove("sizes");
        body.put("variants", List.of(Map.of("size", "1.5mm", "code", "CAB-THIN")));
        ResponseEntity<JsonNode> line = api.post("/product-families", body, ADMIN);
        assertThat(line.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(line.getBody().get("errors").get(0).get("field").asString()).isEqualTo("variants[0].code");

        JsonNode f = romper();
        ResponseEntity<JsonNode> more = api.post(
                "/product-families/" + f.get("id").asString() + "/variants",
                Map.of("variants", List.of(Map.of("size", "12-18m", "code", "ROMP-BIG"))),
                ADMIN);
        assertThat(more.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_product_families WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .as("only the romper was made")
                .isEqualTo(1);
    }

    @Test
    void frRet51OnlyTheCatalogueManagerMakesItemsAndASellerSeesNoCost() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Test Romper");
        body.put("category_id", category);
        body.put("unit_id", pieces);
        body.put("cost_minor", 4_000);
        body.put("sell_minor", 9_000);
        body.put("sizes", List.of("0-3m"));
        assertThat(api.post("/product-families", body, SALES).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode f = romper();
        JsonNode seen =
                api.get("/product-families/" + f.get("id").asString(), SALES).getBody();
        assertThat(seen.get("variants").get(0).has("cost_minor")).isFalse();
        assertThat(api.get("/product-families/" + UUID.randomUUID(), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- every stock path per variant, rolled up to the item -------------------------------------

    @Test
    void frRet52EveryStockPathWorksPerVariantAndTheItemSumsThem() {
        JsonNode f = romper();
        UUID familyId = UUID.fromString(f.get("id").asString());
        UUID small = variant(f, "0-3m", "Blue");
        UUID large = variant(f, "6-12m", "Pink");

        restock(hq, small, "10", large, "4");
        assertThat(qty(hq, small)).isEqualTo("10.000");
        assertThat(qty(hq, large)).isEqualTo("4.000");

        sell(hq, small, "3");
        assertThat(qty(hq, small)).isEqualTo("7.000");
        assertThat(qty(hq, large)).as("a sale of one size leaves the others").isEqualTo("4.000");

        Map<String, Object> move = new LinkedHashMap<>();
        move.put("from_branch_id", hq);
        move.put("to_branch_id", br2);
        move.put("lines", List.of(Map.of("product_id", small, "qty", "2")));
        ResponseEntity<JsonNode> moved =
                api.postKeyed("/transfers", move, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(moved.getStatusCode().is2xxSuccessful())
                .as("%s", moved.getBody())
                .isTrue();
        assertThat(qty(hq, small)).isEqualTo("5.000");
        assertThat(qty(br2, small)).isEqualTo("2.000");

        // A stock-take counts one variant: the others are untouched.
        api.stockUp(hq, large, "3");
        assertThat(qty(hq, large)).isEqualTo("3.000");
        assertThat(qty(hq, small)).isEqualTo("5.000");

        Map<String, Object> damaged = new LinkedHashMap<>();
        damaged.put("branch_id", hq);
        damaged.put("kind", "damaged");
        damaged.put("reason", "Test torn seam");
        damaged.put("lines", List.of(Map.of("product_id", large, "qty", "1")));
        ResponseEntity<JsonNode> usage =
                api.postKeyed("/usage", damaged, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(usage.getStatusCode().is2xxSuccessful())
                .as("%s", usage.getBody())
                .isTrue();
        assertThat(qty(hq, large)).isEqualTo("2.000");

        // The item: every variant together, per branch and in all.
        JsonNode item = family(familyId);
        assertThat(item.get("stock_on_hand").asString()).isEqualTo("9.000");
        Map<String, String> byBranch = new LinkedHashMap<>();
        item.get("stock_by_branch")
                .forEach(b ->
                        byBranch.put(b.get("branch_id").asString(), b.get("qty").asString()));
        assertThat(byBranch).containsEntry(hq.toString(), "7.000").containsEntry(br2.toString(), "2.000");
        for (JsonNode v : item.get("variants")) {
            if (v.get("id").asString().equals(small.toString())) {
                assertThat(v.get("stock_on_hand").asString()).isEqualTo("7.000");
                assertThat(v.get("stock_branches").asInt()).isEqualTo(2);
            }
        }
    }

    @Test
    void frRet52AReturnGoesBackToTheVariantThatWasSold() {
        capability("returns", true);
        JsonNode f = romper();
        UUID small = variant(f, "0-3m", "Blue");
        UUID other = variant(f, "0-3m", "Pink");
        restock(hq, small, "5", other, "5");
        UUID sale = sell(hq, small, "2");
        JsonNode lines = api.get("/sales/" + sale, ADMIN).getBody().get("lines");
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sale_line_id", lines.get(0).get("id").asString());
        line.put("qty", "1");
        line.put("reason", "defective");
        line.put("disposition", "restock");
        line.put("restock_branch_id", hq);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(line));
        body.put("refund_method", "cash");
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + sale + "/returns",
                body,
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isTrue();
        assertThat(qty(hq, small)).isEqualTo("4.000");
        assertThat(qty(hq, other)).isEqualTo("5.000");
    }

    @Test
    void frRet52SalesAndRestocksRollUpToTheItemAndSplitBySizeAndColour() {
        UUID plain = api.product("PLAIN-1", 1_000, 5_000); // sells above the 4,000 the restock pays
        // A tenant with no item with sizes is offered the levels it always had.
        JsonNode before = summary("/sales/summary?group_by=item");
        assertThat(before.get("available_levels").toString()).doesNotContain("variant");

        JsonNode f = romper();
        String familyKey = f.get("id").asString();
        UUID small = variant(f, "0-3m", "Blue");
        UUID large = variant(f, "6-12m", "Pink");
        restock(hq, small, "10", large, "10", plain, "10");
        sell(hq, small, "3");
        sell(hq, large, "2");
        sell(hq, plain, "1");

        JsonNode items = summary("/sales/summary?group_by=item,variant&level=item");
        assertThat(items.get("available_levels").toString()).contains("variant");
        JsonNode romper = group(items, familyKey);
        assertThat(romper.get("label").asString()).isEqualTo("Test Romper");
        assertThat(romper.get("quantity").asString()).isEqualTo("5");
        assertThat(romper.get("total_minor").asLong()).isEqualTo(5 * 9_000);
        assertThat(romper.get("sales_count").asLong()).isEqualTo(2);
        JsonNode alone = group(items, plain.toString());
        assertThat(alone.get("label").asString()).isEqualTo("Test Product PLAIN-1");
        assertThat(alone.get("total_minor").asLong()).isEqualTo(5_000);

        JsonNode sizes = summary("/sales/summary?group_by=item,variant&level=variant&item_id=" + familyKey);
        assertThat(sizes.get("items")).hasSize(2);
        assertThat(group(sizes, small.toString()).get("label").asString()).isEqualTo("Test Romper (0-3m, Blue)");
        assertThat(group(sizes, small.toString()).get("quantity").asString()).isEqualTo("3");
        assertThat(group(sizes, large.toString()).get("total_minor").asLong()).isEqualTo(2 * 9_000);

        // The sales list filtered by the item finds the sales of any of its sizes; by one size, that size's only.
        assertThat(api.get("/sales?product_id=" + familyKey, ADMIN).getBody().get("items"))
                .hasSize(2);
        assertThat(api.get("/sales?product_id=" + small, ADMIN).getBody().get("items"))
                .hasSize(1);

        JsonNode restocks = summary("/purchases/summary?group_by=item,variant&level=item");
        JsonNode bought = group(restocks, familyKey);
        assertThat(bought.get("quantity").asString()).isEqualTo("20");
        assertThat(bought.get("unit").asString()).isEqualTo("test-piece");
        assertThat(bought.get("total_minor").asLong()).isEqualTo(20 * 4_000);
        JsonNode boughtSizes = summary("/purchases/summary?group_by=item,variant&level=variant&item_id=" + familyKey);
        assertThat(boughtSizes.get("items")).hasSize(2);
        assertThat(api.get("/purchases?product_id=" + familyKey, ADMIN)
                        .getBody()
                        .get("items"))
                .hasSize(1);
    }

    // ---- an existing item given sizes ------------------------------------------------------------

    @Test
    void frRet51AnItemGivenSizesKeepsItsIdCodeStockAndHistoryAsOneOfThem() {
        UUID old = api.product("DRESS-1", 3_000, 7_000);
        api.stockUp(hq, old, "6");
        UUID sale = sell(hq, old, "1");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", "Small");
        body.put("sizes", List.of("Small", "Large"));
        body.put("colours", List.of());
        ResponseEntity<JsonNode> r = api.post("/products/" + old + "/family", body, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode f = r.getBody();
        assertThat(f.get("name").asString()).isEqualTo("Test Product DRESS-1");
        assertThat(f.get("variants")).hasSize(2);
        JsonNode kept = f.get("variants").get(0);
        assertThat(kept.get("id").asString()).isEqualTo(old.toString());
        assertThat(kept.get("code").asString()).isEqualTo("DRESS-1");
        assertThat(kept.get("description").asString()).isEqualTo("Test Product DRESS-1 (Small)");
        assertThat(kept.get("stock_on_hand").asString()).isEqualTo("5.000");
        JsonNode added = f.get("variants").get(1);
        assertThat(added.get("code").asString()).isEqualTo("DRESS-1-LARGE");
        assertThat(added.get("sell_minor").asLong()).isEqualTo(7_000);
        assertThat(added.get("stock_on_hand").asString()).isEqualTo("0.000");
        // The sale made before still reads as a sale of this product.
        assertThat(api.get("/sales/" + sale, ADMIN)
                        .getBody()
                        .get("lines")
                        .get(0)
                        .get("product_id")
                        .asString())
                .isEqualTo(old.toString());
        ResponseEntity<JsonNode> again = api.post("/products/" + old + "/family", body, ADMIN);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("already_has_variants");
    }

    // ---- what a variant may change, and the item's name and category --------------------------------

    @Test
    void frRet51AVariantFollowsItsItemsNameAndCategoryAndChangesOnlyItsOwnFields() {
        JsonNode f = romper();
        UUID familyId = UUID.fromString(f.get("id").asString());
        UUID small = variant(f, "0-3m", "Blue");

        ResponseEntity<JsonNode> renamed = patchProduct(small, Map.of("description", "Something else"));
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(renamed.getBody().get("code").asString()).isEqualTo("variant_name_follows_item");
        UUID other = api.category("Test Other");
        assertThat(patchProduct(small, Map.of("category_id", other))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("variant_follows_item");

        // Issue #562: a size keeps the code the app gave it.
        ResponseEntity<JsonNode> recoded = patchProduct(small, Map.of("variant_colour", "Navy", "code", "ROMP-S-NAVY"));
        assertThat(recoded.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(recoded.getBody().get("code").asString()).isEqualTo("item_code_made_by_app");
        ResponseEntity<JsonNode> corrected = patchProduct(small, Map.of("variant_colour", "Navy"));
        assertThat(corrected.getStatusCode()).as("%s", corrected.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(corrected.getBody().get("description").asString()).isEqualTo("Test Romper (0-3m, Navy)");
        assertThat(corrected.getBody().get("code").asString()).isEqualTo("ROMP-0001-03M-BLUE");
        ResponseEntity<JsonNode> twice = patchProduct(small, Map.of("variant_colour", "Pink"));
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(twice.getBody().get("code").asString()).isEqualTo("duplicate_variant");

        UUID plain = api.product("PLAIN-2", 1, 2);
        assertThat(patchProduct(plain, Map.of("variant_size", "XL"))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("not_a_variant");

        String etag =
                api.get("/product-families/" + familyId, ADMIN).getHeaders().getETag();
        ResponseEntity<JsonNode> moved = api.call(
                HttpMethod.PATCH,
                "/product-families/" + familyId,
                Map.of("name", "Test Sleepsuit", "category_id", other),
                ADMIN,
                "*",
                Map.of("If-Match", etag));
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(moved.getBody().get("category_id").asString()).isEqualTo(other.toString());
        JsonNode now = api.get("/products/" + small, ADMIN).getBody();
        assertThat(now.get("description").asString()).isEqualTo("Test Sleepsuit (0-3m, Navy)");
        assertThat(now.get("category_id").asString()).isEqualTo(other.toString());
        assertThat(now.get("variant").get("family_name").asString()).isEqualTo("Test Sleepsuit");
        assertThat(api.call(
                                HttpMethod.PATCH,
                                "/product-families/" + familyId,
                                Map.of("name", "Late"),
                                ADMIN,
                                "*",
                                Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void frRet52APriceChangeOnOneVariantLeavesTheOthers() {
        JsonNode f = romper();
        UUID small = variant(f, "0-3m", "Blue");
        UUID large = variant(f, "6-12m", "Blue");
        ResponseEntity<JsonNode> r =
                api.editPrices(large, Map.of("sell_minor", 11_000, "reason", "Bigger size"), ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(api.get("/products/" + large, ADMIN)
                        .getBody()
                        .get("sell_minor")
                        .asLong())
                .isEqualTo(11_000);
        assertThat(api.get("/products/" + small, ADMIN)
                        .getBody()
                        .get("sell_minor")
                        .asLong())
                .isEqualTo(9_000);
    }

    @Test
    void anItemWithoutVariantsReadsExactlyAsBefore() {
        UUID plain = api.product("PLAIN-3", 1_000, 2_000);
        JsonNode p = api.get("/products/" + plain, ADMIN).getBody();
        assertThat(p.has("variant")).isFalse();
        assertThat(api.get("/products?query=PLAIN-3", ADMIN)
                        .getBody()
                        .get("items")
                        .get(0)
                        .has("variant"))
                .isFalse();
        JsonNode items = summary("/sales/summary?group_by=item");
        assertThat(items.get("levels")).hasSize(1);
    }

    // ---- review of #388: on sale, one name per item, joining items kept apart, sizes on one line
    // -------------------------

    static final String ADMINISTRATOR = ADMIN + ",core.settings.manage";

    /** An item without variants in this test's category and unit. */
    /** Issue #562: the app makes the code; {@code code} is the one the item held before, as an existing item keeps it. */
    UUID plain(String code, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", description);
        body.put("category_id", category);
        body.put("unit_id", pieces);
        body.put("cost_minor", 1_000);
        body.put("sell_minor", 2_000);
        UUID id = RetailTestSupport.id(api.post("/products", body, ADMIN));
        api.existingCode(id, code);
        return id;
    }

    @Test
    void frRet51ASwitchedOffItemGivenSizesKeepsEverySizeSwitchedOffAndSoDoesASizeAddedToIt() {
        UUID old = plain("OFF-1", "Test Old Dress");
        assertThat(patchProduct(old, Map.of("active", false)).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", "Small");
        body.put("sizes", List.of("Small", "Large"));
        ResponseEntity<JsonNode> r = api.post("/products/" + old + "/family", body, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        for (JsonNode v : r.getBody().get("variants")) {
            assertThat(v.get("active").asBoolean()).as(v.get("code").asString()).isFalse();
        }
        ResponseEntity<JsonNode> more = api.post(
                "/product-families/" + r.getBody().get("id").asString() + "/variants",
                Map.of("sizes", List.of("Medium")),
                ADMIN);
        assertThat(more.getStatusCode()).as("%s", more.getBody()).isEqualTo(HttpStatus.CREATED);
        for (JsonNode v : more.getBody().get("variants")) {
            assertThat(v.get("active").asBoolean()).as(v.get("code").asString()).isFalse();
        }
        // An item on sale gives on-sale sizes.
        JsonNode f = romper();
        ResponseEntity<JsonNode> added = api.post(
                "/product-families/" + f.get("id").asString() + "/variants", Map.of("sizes", List.of("12-18m")), ADMIN);
        assertThat(added.getBody().get("variants").get(6).get("active").asBoolean())
                .isTrue();
    }

    @Test
    void adr044AnItemsNameIsKeptOncePerUnitAgainstItemsWithAndWithoutSizes() {
        // The HATS case: a shop that has "Test Twin Cable" as an item names an item with sizes the same.
        UUID existing = plain("TWIN-1", "Test Twin Cable");
        ResponseEntity<JsonNode> same = createFamily("test  twin cable", List.of("1.5mm", "2.5mm"), List.of());
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(same.getBody().get("code").asString()).isEqualTo("duplicate_product_name");
        assertThat(same.getBody().get("existing_id").asString()).isEqualTo(existing.toString());
        assertThat(same.getBody().get("may_add_anyway").asBoolean()).isFalse();

        Map<String, Object> anyway = new LinkedHashMap<>();
        anyway.put("name", "Test Twin Cable");
        anyway.put("category_id", category);
        anyway.put("unit_id", pieces);
        anyway.put("cost_minor", 1_000);
        anyway.put("sell_minor", 2_000);
        anyway.put("sizes", List.of("1.5mm"));
        anyway.put("duplicate_reason", "Old item kept for history");
        assertThat(api.post("/product-families", anyway, ADMIN).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> added = api.post("/product-families", anyway, ADMINISTRATOR);
        assertThat(added.getStatusCode()).as("%s", added.getBody()).isEqualTo(HttpStatus.CREATED);

        // Another item with sizes holds its name too, and a new item without sizes cannot take it either.
        JsonNode f = romper();
        ResponseEntity<JsonNode> twice = createFamily("Test Romper", List.of("Small"), List.of());
        assertThat(twice.getBody().get("code").asString()).isEqualTo("duplicate_product_name");
        assertThat(twice.getBody().get("existing_id").asString())
                .isEqualTo(f.get("id").asString());
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("description", "Test Romper");
        product.put("category_id", category);
        product.put("unit_id", pieces);
        product.put("cost_minor", 1);
        product.put("sell_minor", 2);
        assertThat(api.post("/products", product, ADMIN).getBody().get("code").asString())
                .isEqualTo("duplicate_product_name");

        // A rename to a name another item holds is refused the same way.
        String etag = api.get("/product-families/" + f.get("id").asString(), ADMIN)
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> renamed = api.call(
                HttpMethod.PATCH,
                "/product-families/" + f.get("id").asString(),
                Map.of("name", "Test Twin Cable"),
                ADMIN,
                "*",
                Map.of("If-Match", etag));
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(renamed.getBody().get("code").asString()).isEqualTo("duplicate_product_name");

        // In another unit the same name is a different thing.
        Map<String, Object> rolls = new LinkedHashMap<>(anyway);
        rolls.remove("duplicate_reason");
        rolls.put("unit_id", api.unit("test-roll"));
        assertThat(api.post("/product-families", rolls, ADMIN).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void frRet51ItemsKeptApartOnePerSizeBecomeTheSizesOfOneItemKeepingIdsStockAndHistory() {
        UUID thin = plain("CAB-15", "Test Cable thin");
        UUID thick = plain("CAB-25", "Test Cable thick");
        api.stockUp(hq, thin, "8");
        api.stockUp(hq, thick, "3");
        UUID sale = sell(hq, thin, "1");
        UUID other = api.category("Test Cables");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Test Twin Cable");
        body.put("category_id", other);
        body.put(
                "items",
                List.of(Map.of("product_id", thin, "size", "1.5mm"), Map.of("product_id", thick, "size", "2.5mm")));
        ResponseEntity<JsonNode> r = api.post("/product-families/join", body, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode f = r.getBody();
        assertThat(f.get("variants")).hasSize(2);
        JsonNode first = f.get("variants").get(0);
        assertThat(first.get("id").asString()).isEqualTo(thin.toString());
        assertThat(first.get("code").asString()).isEqualTo("CAB-15");
        assertThat(first.get("description").asString()).isEqualTo("Test Twin Cable (1.5mm)");
        assertThat(first.get("category_id").asString()).isEqualTo(other.toString());
        assertThat(first.get("stock_on_hand").asString()).isEqualTo("7.000");
        assertThat(f.get("stock_on_hand").asString()).isEqualTo("10.000");
        assertThat(f.get("base_code").asString()).isEqualTo("CAB-15");
        assertThat(api.get("/sales/" + sale, ADMIN)
                        .getBody()
                        .get("lines")
                        .get(0)
                        .get("product_id")
                        .asString())
                .isEqualTo(thin.toString());
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND entity_id = ? AND action = 'retail.product.updated'")
                        .params(t.tenantId(), thick)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        // A third item joins the item that exists; a size it has, another unit or a size already taken is refused.
        UUID heavy = plain("CAB-40", "Test Cable heavy");
        String familyId = f.get("id").asString();
        ResponseEntity<JsonNode> clash = api.post(
                "/product-families/" + familyId + "/join",
                Map.of("items", List.of(Map.of("product_id", heavy, "size", "2.5MM"))),
                ADMIN);
        assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(clash.getBody().get("code").asString()).isEqualTo("duplicate_variant");
        ResponseEntity<JsonNode> joined = api.post(
                "/product-families/" + familyId + "/join",
                Map.of("items", List.of(Map.of("product_id", heavy, "size", "4mm"))),
                ADMIN);
        assertThat(joined.getStatusCode()).as("%s", joined.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(joined.getBody().get("variants")).hasSize(3);
        UUID roll = api.product("CAB-ROLL", 1, 2);
        assertThat(api.post(
                                "/product-families/" + familyId + "/join",
                                Map.of("items", List.of(Map.of("product_id", roll, "size", "6mm"))),
                                ADMIN)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("unit_differs");
        assertThat(api.post(
                                "/product-families/join",
                                Map.of("name", "Test Other", "items", List.of(Map.of("product_id", thin, "size", "S"))),
                                ADMIN)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("already_has_variants");
    }

    @Test
    void frRet51ASizeOrColourIsOneLineOfAtMostThirtyLetters() {
        ResponseEntity<JsonNode> lineBreak = createFamily("Test Sock", List.of("S\nM"), List.of());
        assertThat(lineBreak.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(lineBreak.getBody().get("code").asString()).isEqualTo("validation_failed");
        // Spaces around a size are not part of it.
        ResponseEntity<JsonNode> trimmed = createFamily("Test Sock", List.of("  Small  "), List.of());
        assertThat(trimmed.getStatusCode()).as("%s", trimmed.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(trimmed.getBody()
                        .get("variants")
                        .get(0)
                        .get("variant")
                        .get("size")
                        .asString())
                .isEqualTo("Small");
    }

    // ---- follow-ups to #388 and #392 (issue #395) ------------------------------------------------

    private String auditOf(String action, UUID entity) {
        return TestDatabase.owner()
                .sql("SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = ? AND entity_id = ?")
                .params(t.tenantId(), action, entity)
                .query(String.class)
                .single();
    }

    @Test
    void joiningAnItemKeepsTheReasonItHadForItsNameInTheAuditBefore() {
        UUID thin = plain("CAB-15", "Test Cable thin");
        UUID thick = plain("CAB-25", "Test Cable thick");
        TestDatabase.owner()
                .sql("UPDATE retail_products SET duplicate_reason = 'Test two brands, one name' WHERE id = ?")
                .params(thin)
                .update();
        ResponseEntity<JsonNode> r = api.post(
                "/product-families/join",
                Map.of(
                        "name",
                        "Test Twin Cable",
                        "items",
                        List.of(
                                Map.of("product_id", thin, "size", "1.5mm"),
                                Map.of("product_id", thick, "size", "2.5mm"))),
                ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(TestDatabase.owner()
                        .sql("SELECT duplicate_reason FROM retail_products WHERE id = ?")
                        .param(thin)
                        .query(String.class)
                        .optional())
                .isEmpty();
        assertThat(auditOf("retail.product.updated", thin)).contains("Test two brands, one name");
        assertThat(auditOf("retail.product.updated", thick)).doesNotContain("duplicate_reason");

        // The same when an item without sizes is given them in place.
        UUID shirt = plain("SHIRT-1", "Test Shirt");
        TestDatabase.owner()
                .sql("UPDATE retail_products SET duplicate_reason = 'Test another shirt' WHERE id = ?")
                .params(shirt)
                .update();
        assertThat(api.post("/products/" + shirt + "/family", Map.of("size", "S", "sizes", List.of("M")), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(auditOf("retail.product.updated", shirt)).contains("Test another shirt");
    }

    @Test
    void joiningAnItemThatExistsRefusesWhatWouldNameItAnewInsteadOfIgnoringIt() {
        JsonNode f = romper();
        UUID extra = plain("ROMP-X", "Test Romper extra");
        ResponseEntity<JsonNode> r = api.post(
                "/product-families/" + f.get("id").asString() + "/join",
                Map.of(
                        "name",
                        "Test Renamed",
                        "base_code",
                        "NEW",
                        "category_id",
                        category,
                        "duplicate_reason",
                        "Test reason here",
                        "items",
                        List.of(Map.of("product_id", extra, "size", "12-18m"))),
                ADMIN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("errors").toString())
                .contains("name")
                .contains("base_code")
                .contains("category_id")
                .contains("duplicate_reason");
        // Nothing was joined.
        assertThat(family(UUID.fromString(f.get("id").asString())).get("variants"))
                .hasSize(6);
        assertThat(api.get("/products/" + extra, ADMIN).getBody().has("variant"))
                .isFalse();
    }

    @Test
    void joiningAnItemThatExistsNeverTakesItPastTheMostSizesAnItemMayHave() {
        // Ten sizes in ten colours is the 100 an item may have.
        List<String> sizes = new ArrayList<>();
        List<String> colours = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            sizes.add("S" + i);
            colours.add("C" + i);
        }
        ResponseEntity<JsonNode> full = createFamily("Test Full Item", sizes, colours);
        assertThat(full.getStatusCode()).as("%s", full.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID one = plain("ONE-1", "Test One");
        ResponseEntity<JsonNode> r = api.post(
                "/product-families/" + full.getBody().get("id").asString() + "/join",
                Map.of("items", List.of(Map.of("product_id", one, "size", "S11"))),
                ADMIN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("too_many_variants");
        assertThat(api.get("/products/" + one, ADMIN).getBody().has("variant")).isFalse();
    }

    @Test
    void aNewSizeTakesThePackSizesAndPriceListPricesOfTheSizeItIsMadeFrom() {
        capability("units", true);
        capability("price_lists", true);
        String perms = ADMIN + ",retail.pricelist.manage,retail.pricelist.use";
        JsonNode f = romper();
        UUID model = variant(f, "0-3m", "Blue");
        UUID carton = api.unit("test-carton");
        assertThat(api.post("/products/" + model + "/units", Map.of("unit_id", carton, "factor", "12"), perms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        UUID list = RetailTestSupport.id(api.post("/price-lists", Map.of("name", "Test Wholesale"), perms));
        assertThat(api.post(
                                "/price-lists/" + list + "/items",
                                Map.of("product_id", model, "unit_id", carton, "price_minor", 100_000),
                                perms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(api.post(
                                "/price-lists/" + list + "/items",
                                Map.of("product_id", model, "unit_id", pieces, "price_minor", 8_000),
                                perms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> more = api.post(
                "/product-families/" + f.get("id").asString() + "/variants",
                Map.of("sizes", List.of("12-18m"), "colours", List.of("Blue")),
                perms);
        assertThat(more.getStatusCode()).as("%s", more.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID added = variant(more.getBody(), "12-18m", "Blue");
        JsonNode copied = more.getBody().get("terms_copied");
        assertThat(copied.get("packs_copied").asInt()).isEqualTo(1);
        assertThat(copied.get("price_list_items_copied").asInt()).isEqualTo(2);
        assertThat(copied.get("price_list_items_not_copied")).isEmpty();
        // Each copied price is audited as one set by hand is.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.price_list_item.created'"
                                        + " AND data::text LIKE ?")
                        .params(t.tenantId(), "%" + added + "%")
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
        JsonNode packs =
                api.get("/products/" + added + "/units", perms).getBody().get("items");
        assertThat(packs).hasSize(1);
        assertThat(packs.get(0).get("unit_id").asString()).isEqualTo(carton.toString());
        assertThat(packs.get(0).get("factor").asString()).isEqualTo("12.000");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT string_agg(price_minor::text, ',' ORDER BY price_minor) FROM retail_price_list_items"
                                        + " WHERE tenant_id = ? AND product_id = ?")
                        .params(t.tenantId(), added)
                        .query(String.class)
                        .single())
                .isEqualTo("8000,100000");
        assertThat(auditOf(
                        "retail.product_family.variants_added",
                        UUID.fromString(f.get("id").asString())))
                .contains("packs_copied")
                .contains("price_list_items_copied");

        // An item given sizes in place does the same: the sizes made from it sell as it does.
        UUID sock = plain("SOCK-1", "Test Sock");
        assertThat(api.post("/products/" + sock + "/units", Map.of("unit_id", carton, "factor", "6"), perms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> made =
                api.post("/products/" + sock + "/family", Map.of("size", "S", "sizes", List.of("M")), perms);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID medium = null;
        for (JsonNode v : made.getBody().get("variants")) {
            if ("M".equals(text(v.get("variant"), "size"))) {
                medium = UUID.fromString(v.get("id").asString());
            }
        }
        assertThat(api.get("/products/" + medium + "/units", perms).getBody().get("items"))
                .hasSize(1);
    }

    // ---- issue #395 review: an item never passes 100, and copied list prices keep the price list rules --------------

    String listPrices(UUID product) {
        return TestDatabase.owner()
                .sql(
                        "SELECT coalesce(string_agg(price_minor::text, ',' ORDER BY price_minor), '') FROM retail_price_list_items"
                                + " WHERE tenant_id = ? AND product_id = ?")
                .params(t.tenantId(), product)
                .query(String.class)
                .single();
    }

    void listPrice(UUID list, UUID product, UUID unit, long price, String perms) {
        ResponseEntity<JsonNode> r = api.post(
                "/price-lists/" + list + "/items",
                Map.of("product_id", product, "unit_id", unit, "price_minor", price),
                perms);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void addingSizesNeverTakesAnItemPastTheMostSizesItMayHave() {
        // Nine sizes in ten colours is 90; fifty more would make 140.
        List<String> sizes = new ArrayList<>();
        List<String> colours = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            colours.add("C" + i);
        }
        for (int i = 1; i <= 9; i++) {
            sizes.add("S" + i);
        }
        ResponseEntity<JsonNode> ninety = createFamily("Test Ninety Item", sizes, colours);
        assertThat(ninety.getStatusCode()).as("%s", ninety.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString(ninety.getBody().get("id").asString());
        ResponseEntity<JsonNode> r = api.post(
                "/product-families/" + id + "/variants",
                Map.of("sizes", List.of("S10", "S11", "S12", "S13", "S14"), "colours", colours),
                ADMIN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("too_many_variants");
        assertThat(family(id).get("variants")).hasSize(90);
        // Ten more is exactly the 100 an item may have.
        ResponseEntity<JsonNode> ten = api.post(
                "/product-families/" + id + "/variants", Map.of("sizes", List.of("S10"), "colours", colours), ADMIN);
        assertThat(ten.getStatusCode()).as("%s", ten.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(family(id).get("variants")).hasSize(100);

        // An item given sizes in place counts the size it becomes: 1 and 100 more is refused.
        UUID plain = plain("PLN-1", "Test Plain Item");
        ResponseEntity<JsonNode> made = api.post(
                "/products/" + plain + "/family", Map.of("size", "S0", "sizes", sizes, "colours", colours), ADMIN);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID other = plain("PLN-2", "Test Other Item");
        List<String> tenSizes = new ArrayList<>(sizes);
        tenSizes.add("S10");
        ResponseEntity<JsonNode> over = api.post(
                "/products/" + other + "/family", Map.of("size", "S0", "sizes", tenSizes, "colours", colours), ADMIN);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(over.getBody().get("code").asString()).isEqualTo("too_many_variants");
        assertThat(api.get("/products/" + other, ADMIN).getBody().has("variant"))
                .isFalse();
    }

    @Test
    void aListPriceIsCopiedOnlyInTheSizesOwnUnitOrAPackCopiedWithIt() {
        capability("units", true);
        capability("price_lists", true);
        String perms = ADMIN + ",retail.pricelist.manage,retail.pricelist.use";
        JsonNode f = romper();
        UUID model = variant(f, "0-3m", "Blue");
        UUID carton = api.unit("test-carton");
        UUID bale = api.unit("test-bale");
        assertThat(api.post("/products/" + model + "/units", Map.of("unit_id", carton, "factor", "12"), perms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(api.post("/products/" + model + "/units", Map.of("unit_id", bale, "factor", "50"), perms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        UUID list = RetailTestSupport.id(api.post("/price-lists", Map.of("name", "Test Wholesale"), perms));
        listPrice(list, model, pieces, 8_000, perms);
        listPrice(list, model, carton, 100_000, perms);
        listPrice(list, model, bale, 400_000, perms);
        // The bale is switched off on the size the new ones are made from: neither the pack nor its price goes across.
        TestDatabase.owner()
                .sql(
                        "UPDATE retail_product_units SET active = false WHERE tenant_id = ? AND product_id = ? AND unit_id = ?")
                .params(t.tenantId(), model, bale)
                .update();

        ResponseEntity<JsonNode> more = api.post(
                "/product-families/" + f.get("id").asString() + "/variants",
                Map.of("sizes", List.of("12-18m"), "colours", List.of("Blue")),
                perms);
        assertThat(more.getStatusCode()).as("%s", more.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID added = variant(more.getBody(), "12-18m", "Blue");
        assertThat(listPrices(added)).isEqualTo("8000,100000");
        JsonNode notCopied = more.getBody().get("terms_copied").get("price_list_items_not_copied");
        assertThat(notCopied).hasSize(1);
        assertThat(notCopied.get(0).get("unit_id").asString()).isEqualTo(bale.toString());
        assertThat(notCopied.get(0).get("reason").asString()).isEqualTo("unit_not_sold");

        // With the units capability off no pack is copied, and so no pack's list price either.
        capability("units", false);
        ResponseEntity<JsonNode> off = api.post(
                "/product-families/" + f.get("id").asString() + "/variants",
                Map.of("sizes", List.of("18-24m"), "colours", List.of("Blue")),
                perms);
        assertThat(off.getStatusCode()).as("%s", off.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID bare = variant(off.getBody(), "18-24m", "Blue");
        assertThat(listPrices(bare)).isEqualTo("8000");
        assertThat(off.getBody()
                        .get("terms_copied")
                        .get("price_list_items_copied")
                        .asInt())
                .isEqualTo(1);
    }

    @Test
    void aCatalogueOnlyRoleAddsSizesWithoutMakingPriceListPrices() {
        capability("price_lists", true);
        String perms = ADMIN + ",retail.pricelist.manage,retail.pricelist.use";
        JsonNode f = romper();
        UUID model = variant(f, "0-3m", "Blue");
        UUID list = RetailTestSupport.id(api.post("/price-lists", Map.of("name", "Test Wholesale"), perms));
        listPrice(list, model, pieces, 8_000, perms);

        ResponseEntity<JsonNode> more = api.post(
                "/product-families/" + f.get("id").asString() + "/variants",
                Map.of("sizes", List.of("12-18m"), "colours", List.of("Blue")),
                ADMIN);
        assertThat(more.getStatusCode()).as("%s", more.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID added = variant(more.getBody(), "12-18m", "Blue");
        assertThat(listPrices(added)).isEmpty();
        JsonNode copied = more.getBody().get("terms_copied");
        assertThat(copied.get("price_list_items_copied").asInt()).isZero();
        assertThat(copied.get("price_list_items_skipped").asString()).isEqualTo("price_list_permission");
        // Nothing of the list is shown to a caller who may not read it.
        assertThat(copied.get("price_list_items_not_copied")).isEmpty();
        assertThat(more.getBody().toString()).doesNotContain(list.toString());

        // A size with no list price at all gets the identical answer, and so does any size with the capability off:
        // adding sizes item by item never tells which items carry list prices, nor whether the lists are on.
        ResponseEntity<JsonNode> bare = createFamily("Test Bare Item", List.of("S"), List.of());
        assertThat(bare.getStatusCode()).as("%s", bare.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> none = api.post(
                "/product-families/" + bare.getBody().get("id").asString() + "/variants",
                Map.of("sizes", List.of("M")),
                ADMIN);
        assertThat(none.getStatusCode()).as("%s", none.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(none.getBody().get("terms_copied")).isEqualTo(copied);
        capability("price_lists", false);
        ResponseEntity<JsonNode> off = api.post(
                "/product-families/" + f.get("id").asString() + "/variants",
                Map.of("sizes", List.of("18-24m"), "colours", List.of("Blue")),
                ADMIN);
        assertThat(off.getStatusCode()).as("%s", off.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(off.getBody().get("terms_copied")).isEqualTo(copied);
    }

    @Test
    void aCallerWithoutProfitReadIsNotToldThatAListPriceIsBelowCost() {
        capability("price_lists", true);
        String perms = ADMIN + ",retail.pricelist.manage,retail.pricelist.use";
        String noProfit = perms.replace(",retail.profit.read", "");
        assertThat(noProfit).doesNotContain("retail.profit.read");
        Map<String, Object> shirt = new LinkedHashMap<>();
        shirt.put("description", "Test Tee");
        shirt.put("category_id", category);
        shirt.put("unit_id", pieces);
        shirt.put("cost_minor", 20_000);
        shirt.put("sell_minor", 30_000);
        UUID m = RetailTestSupport.id(api.post("/products", shirt, ADMIN));
        UUID staff = RetailTestSupport.id(api.post("/price-lists", Map.of("name", "Test Staff"), perms));
        listPrice(staff, m, pieces, 15_000, perms);

        ResponseEntity<JsonNode> made =
                api.post("/products/" + m + "/family", Map.of("size", "M", "sizes", List.of("L")), noProfit);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode notCopied = made.getBody().get("terms_copied").get("price_list_items_not_copied");
        assertThat(notCopied).hasSize(1);
        assertThat(notCopied.get(0).get("reason").asString()).isEqualTo("not_copied");
        assertThat(made.getBody().toString()).doesNotContain("below_cost");
    }

    @Test
    void aSizeAtItsOwnPricesTakesNoListPriceAndNoneIsEverWrittenBelowCost() {
        capability("price_lists", true);
        String perms = ADMIN + ",retail.pricelist.manage,retail.pricelist.use";
        // Size M sells at 30,000 (cost 20,000) with a wholesale price of 25,000.
        Map<String, Object> shirt = new LinkedHashMap<>();
        shirt.put("description", "Test Shirt");
        shirt.put("category_id", category);
        shirt.put("unit_id", pieces);
        shirt.put("cost_minor", 20_000);
        shirt.put("sell_minor", 30_000);
        UUID m = RetailTestSupport.id(api.post("/products", shirt, ADMIN));
        UUID list = RetailTestSupport.id(api.post("/price-lists", Map.of("name", "Test Wholesale"), perms));
        listPrice(list, m, pieces, 25_000, perms);
        UUID staff = RetailTestSupport.id(api.post("/price-lists", Map.of("name", "Test Staff"), perms));
        listPrice(staff, m, pieces, 15_000, perms);

        // XL is added at 50,000, cost 40,000: the wholesale 25,000 would sell it below cost, so nothing is copied.
        Map<String, Object> xl = new LinkedHashMap<>();
        xl.put("size", "XL");
        xl.put("sell_minor", 50_000);
        xl.put("cost_minor", 40_000);
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("size", "L");
        ResponseEntity<JsonNode> made =
                api.post("/products/" + m + "/family", Map.of("size", "M", "variants", List.of(xl, l)), perms);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID xlId = null;
        UUID lId = null;
        for (JsonNode v : made.getBody().get("variants")) {
            String size = text(v.get("variant"), "size");
            if ("XL".equals(size)) {
                xlId = UUID.fromString(v.get("id").asString());
            } else if ("L".equals(size)) {
                lId = UUID.fromString(v.get("id").asString());
            }
        }
        assertThat(listPrices(xlId)).isEmpty();
        // L keeps M's prices; the wholesale price goes across, the staff price below cost does not.
        assertThat(listPrices(lId)).isEqualTo("25000");
        JsonNode notCopied = made.getBody().get("terms_copied").get("price_list_items_not_copied");
        List<String> reasons = new ArrayList<>();
        for (JsonNode n : notCopied) {
            reasons.add(n.get("product_id").asString() + ":"
                    + n.get("price_list_id").asString() + ":" + n.get("reason").asString());
        }
        assertThat(reasons)
                .containsExactlyInAnyOrder(
                        xlId + ":" + list + ":price_differs",
                        xlId + ":" + staff + ":price_differs",
                        lId + ":" + staff + ":below_cost");
    }

    @Test
    void marginsAndSalesAnalysisCountAnItemWithSizesOnceAllItsSizesTogether() {
        UUID plain = api.product("PLAIN-1", 1_000, 5_000);
        JsonNode f = romper();
        String familyKey = f.get("id").asString();
        UUID small = variant(f, "0-3m", "Blue");
        UUID large = variant(f, "6-12m", "Pink");
        restock(hq, small, "10", large, "10", plain, "10");
        sell(hq, small, "3");
        sell(hq, large, "2");
        sell(hq, plain, "1");

        for (String path : List.of("/reports/margins", "/reports/sales-analysis")) {
            JsonNode rows = summary(path).get("by_product");
            JsonNode romperRow = null;
            for (JsonNode row : rows) {
                if (familyKey.equals(row.get("id").asString())) {
                    romperRow = row;
                }
                // Never a size of the item on a row of its own.
                assertThat(row.get("id").asString()).isNotIn(small.toString(), large.toString());
            }
            assertThat(romperRow).as(path).isNotNull();
            assertThat(romperRow.get("label").asString()).isEqualTo("Test Romper");
            assertThat(romperRow.get("code").asString()).isEqualTo("ROMP-0001");
            assertThat(romperRow.get("sales_minor").asLong()).isEqualTo(5 * 9_000);
            assertThat(romperRow.get("qty").asString()).isEqualTo("5.000");
            JsonNode alone = null;
            for (JsonNode row : rows) {
                if (plain.toString().equals(row.get("id").asString())) {
                    alone = row;
                }
            }
            assertThat(alone).as(path).isNotNull();
            assertThat(alone.get("sales_minor").asLong()).isEqualTo(5_000);
        }
        JsonNode margin = null;
        for (JsonNode row : summary("/reports/margins").get("by_product")) {
            if (familyKey.equals(row.get("id").asString())) {
                margin = row;
            }
        }
        assertThat(margin.get("cost_minor").asLong()).isEqualTo(5 * 4_000);
        assertThat(margin.get("profit_minor").asLong()).isEqualTo(5 * 5_000);
    }
}
