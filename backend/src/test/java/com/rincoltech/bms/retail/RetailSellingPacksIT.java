package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
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
 * Selling packs with their own prices (issue #624, owner decision D23, ADR-099; docs/specs/units-packs-and-costing.md
 * section 3 and section 9 step 3): the suggested price, the price a walk-in sale takes against a price list sale, the
 * below-cost check per pack against the average cost per piece times the size, and what a pack is for. Every name and
 * amount is fabricated.
 */
class RetailSellingPacksIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    static final String MANAGER = ADMIN + ",retail.pricelist.manage,retail.pricelist.use";
    /** Manages the catalogue (packs) but may not change selling prices. */
    static final String CATALOGUE_ONLY = ADMIN.replace("retail.price.edit,", "");
    /** May see prices but not cost. */
    static final String NO_PROFIT = ADMIN.replace(",retail.profit.read", "");

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    /** Cost 83 and sell 100 per piece: a carton of twelve costs 996 and is suggested at 1,200. */
    UUID pen;

    UUID carton;
    UUID halfCarton;
    UUID box;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-selling-packs", false, true);
        api = new RetailTestSupport(http, t);
        pen = api.product("SP-PEN", 83, 100);
        carton = api.unit("carton");
        halfCarton = api.unit("half carton");
        box = api.unit("box");
        capability("units", true);
        api.stockUp(t.headOffice(), pen, "1000");
    }

    // ---- helpers ---------------------------------------------------------------------------

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

    ResponseEntity<JsonNode> addPack(UUID unit, String factor, String purpose, Long sell, String perms) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("unit_id", unit);
        body.put("factor", factor);
        if (purpose != null) {
            body.put("purpose", purpose);
        }
        if (sell != null) {
            body.put("sell_minor", sell);
        }
        return api.post("/products/" + pen + "/units", body, perms);
    }

    JsonNode pack(UUID unit, String factor, String purpose, Long sell) {
        ResponseEntity<JsonNode> r = addPack(unit, factor, purpose, sell, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    JsonNode packOf(UUID unit, String perms) {
        for (JsonNode k :
                api.get("/products/" + pen + "/units", perms).getBody().get("items")) {
            if (k.get("unit_id").asString().equals(unit.toString())) {
                return k;
            }
        }
        throw new AssertionError("no pack " + unit);
    }

    ResponseEntity<JsonNode> change(UUID unit, Map<String, Object> body, String perms) {
        return api.call(
                HttpMethod.PATCH,
                "/products/" + pen + "/units/" + unit,
                body,
                perms,
                "*",
                Map.of("If-Match", packOf(unit, ADMIN).get("version").asString()));
    }

    ResponseEntity<JsonNode> sale(String perms, UUID customer, UUID unit, String qty) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", pen);
        line.put("qty", qty);
        if (unit != null) {
            line.put("unit_id", unit);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "cash");
        if (customer != null) {
            body.put("customer_id", customer);
        }
        body.put("lines", List.of(line));
        return api.call(
                HttpMethod.POST,
                "/sales",
                body,
                perms,
                "*",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    JsonNode sold(String perms, UUID customer, UUID unit, String qty) {
        ResponseEntity<JsonNode> r = sale(perms, customer, unit, qty);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("lines").get(0);
    }

    ResponseEntity<JsonNode> restock(UUID unit, String qty, long cost) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", pen);
        line.put("cost_minor", cost);
        line.put("unit_id", unit);
        line.put("qty_by_branch", List.of(Map.of("branch_id", t.headOffice(), "qty", qty)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", "2026-01-15");
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        return api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    // ---- the suggested price and the pack's own price --------------------------------------

    /** The app suggests the piece price times the size; the owner types the real one, which a walk-in sale takes. */
    @Test
    void aSellingPackSuggestsAPriceAndSellsAtItsOwn() {
        JsonNode added = pack(carton, "12", null, null);
        assertThat(added.get("purpose").asString()).isEqualTo("both");
        assertThat(added.get("suggested_sell_minor").asLong()).isEqualTo(1_200);
        assertThat(added.has("sell_minor")).isFalse();
        assertThat(added.get("below_cost").asBoolean()).isFalse();
        // Without its own price the pack sells at the suggested price, as before.
        assertThat(sold(ADMIN, null, carton, "1").get("sale_unit_price_minor").asLong())
                .isEqualTo(1_200);

        ResponseEntity<JsonNode> priced = change(carton, Map.of("sell_minor", 1_100), ADMIN);
        assertThat(priced.getStatusCode()).as("%s", priced.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(priced.getBody().get("sell_minor").asLong()).isEqualTo(1_100);
        assertThat(api.get("/products/" + pen, ADMIN)
                        .getBody()
                        .get("packs")
                        .get(0)
                        .get("sell_minor")
                        .asLong())
                .isEqualTo(1_100);

        JsonNode line = sold(ADMIN, null, carton, "2");
        assertThat(line.get("sale_unit_price_minor").asLong()).isEqualTo(1_100);
        assertThat(line.get("line_total_minor").asLong()).isEqualTo(2_200);
        // Profit stays per piece: 24 pieces at the average of 83.
        assertThat(line.get("qty").asString()).isEqualTo("24.000");
        assertThat(line.get("line_cost_minor").asLong()).isEqualTo(1_992);

        // A half carton is just a selling pack of six with its own price.
        pack(halfCarton, "6", "sell", 580L);
        assertThat(sold(ADMIN, null, halfCarton, "1")
                        .get("sale_unit_price_minor")
                        .asLong())
                .isEqualTo(580);

        // Dropping the own price goes back to the suggested one.
        assertThat(change(carton, Map.of("clear_sell_minor", true), ADMIN)
                        .getBody()
                        .has("sell_minor"))
                .isFalse();
        assertThat(sold(ADMIN, null, carton, "1").get("sale_unit_price_minor").asLong())
                .isEqualTo(1_200);
    }

    /** Setting a pack's price is a price change: retail.price.edit, as for the item's own price. */
    @Test
    void aPacksPriceNeedsThePermissionToChangePrices() {
        pack(carton, "12", null, null);
        ResponseEntity<JsonNode> refused = change(carton, Map.of("sell_minor", 1_100), CATALOGUE_ONLY);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(addPack(box, "10", "sell", 900L, CATALOGUE_ONLY).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // Dropping a price the pack does not have changes nothing, so it needs no permission.
        assertThat(change(carton, Map.of("clear_sell_minor", true), CATALOGUE_ONLY)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // The size of a pack without its own price may still be changed without it: it sells at the piece price times
        // the size.
        assertThat(change(carton, Map.of("factor", "24"), CATALOGUE_ONLY).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(packOf(carton, ADMIN).has("sell_minor")).isFalse();

        // Once it has its own price, a new size or making it buy only changes what its pieces sell for: a price change.
        assertThat(change(carton, Map.of("sell_minor", 2_200), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> resized = change(carton, Map.of("factor", "48"), CATALOGUE_ONLY);
        assertThat(resized.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resized.getBody().toString())
                .contains("This pack has its own price. Ask someone who may change prices.");
        // The purpose round trip (buy only, which drops the price, then both again) is refused at its first step, so
        // the
        // owner's pack price can never be lost that way.
        ResponseEntity<JsonNode> toBuy = change(carton, Map.of("purpose", "buy"), CATALOGUE_ONLY);
        assertThat(toBuy.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(toBuy.getBody().toString())
                .contains("This pack has its own price. Ask someone who may change prices.");
        assertThat(change(carton, Map.of("purpose", "both"), CATALOGUE_ONLY).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode kept = packOf(carton, ADMIN);
        assertThat(kept.get("factor").asString()).isEqualTo("24.000");
        assertThat(kept.get("purpose").asString()).isEqualTo("both");
        assertThat(kept.get("sell_minor").asLong()).isEqualTo(2_200);
        // Making it for selling only, or switching it off, leaves its price alone and needs no price permission.
        assertThat(change(carton, Map.of("purpose", "sell"), CATALOGUE_ONLY).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // Someone who may change prices may resize it; the price stays the pack's.
        assertThat(change(carton, Map.of("factor", "48"), ADMIN)
                        .getBody()
                        .get("sell_minor")
                        .asLong())
                .isEqualTo(2_200);
    }

    /**
     * Review of #624: "Use 30 from now on" on Buy stock (ADR-082) changes a pack's usual size, so on a pack with its own price
     * it needs retail.price.edit too; without it the delivery still saves at its own size and the usual size stays.
     */
    @Test
    void useFromNowOnOnAPricedPackNeedsThePermissionToChangePrices() {
        pack(carton, "24", null, 2_200L);
        // Someone who may not change prices: the delivery saves at its own size, only "from now on" is left out.
        ResponseEntity<JsonNode> kept = delivered(30, CATALOGUE_ONLY);
        assertThat(kept.getStatusCode()).as("%s", kept.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode keptLine = kept.getBody().get("lines").get(0);
        assertThat(keptLine.get("size_kept").asBoolean()).isTrue();
        assertThat(keptLine.get("size_kept_note").asString()).contains("This pack has its own price");
        assertThat(packOf(carton, ADMIN).get("factor").asString()).isEqualTo("24.000");
        assertThat(packOf(carton, ADMIN).get("sell_minor").asLong()).isEqualTo(2_200);
        assertThat(stock()).isEqualTo("1030.000");

        ResponseEntity<JsonNode> allowed = delivered(30, ADMIN);
        assertThat(allowed.getStatusCode()).as("%s", allowed.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(allowed.getBody().get("lines").get(0).has("size_kept")).isFalse();
        JsonNode resized = packOf(carton, ADMIN);
        assertThat(resized.get("factor").asString()).isEqualTo("30.000");
        assertThat(resized.get("sell_minor").asLong()).isEqualTo(2_200);
        assertThat(stock()).isEqualTo("1060.000");

        // A pack without its own price still takes the delivery's size from someone who manages the catalogue.
        pack(box, "10", null, null);
        Map<String, Object> line = new LinkedHashMap<>(deliveryLine(box, 12));
        assertThat(api.postKeyed(
                                "/purchases",
                                purchase(line),
                                CATALOGUE_ONLY,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(packOf(box, ADMIN).get("factor").asString()).isEqualTo("12.000");
    }

    ResponseEntity<JsonNode> delivered(int size, String perms) {
        return api.postKeyed(
                "/purchases",
                purchase(deliveryLine(carton, size)),
                perms,
                "*",
                UUID.randomUUID().toString());
    }

    Map<String, Object> deliveryLine(UUID unit, int size) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", pen);
        line.put("cost_minor", 2_400);
        line.put("unit_id", unit);
        line.put("pack_size", String.valueOf(size));
        line.put("use_from_now_on", true);
        line.put("qty_by_branch", List.of(Map.of("branch_id", t.headOffice(), "qty", "1")));
        return line;
    }

    static Map<String, Object> purchase(Map<String, Object> line) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", "2026-01-15");
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        return body;
    }

    String stock() {
        return TestDatabase.owner()
                .sql(
                        "SELECT qty::text FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), t.headOffice(), pen)
                .query(String.class)
                .single();
    }

    // ---- price lists still win -------------------------------------------------------------

    /** A walk-in sale takes the pack's own price; a customer on a price list gets the list's price for that pack. */
    @Test
    void aPriceListWinsOverThePacksOwnPrice() {
        capability("price_lists", true);
        pack(carton, "12", null, 1_100L);
        ResponseEntity<JsonNode> list = api.post("/price-lists", Map.of("name", "Wholesale", "strict", false), MANAGER);
        UUID wholesale = RetailTestSupport.id(list);
        ResponseEntity<JsonNode> item = api.post(
                "/price-lists/" + wholesale + "/items",
                Map.of("product_id", pen, "unit_id", carton, "price_minor", 1_000),
                MANAGER);
        assertThat(item.getStatusCode()).as("%s", item.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 01"), ADMIN));
        int version = 0;
        for (JsonNode c : api.get("/customers", ADMIN).getBody().get("items")) {
            if (c.get("id").asString().equals(buyer.toString())) {
                version = c.get("version").asInt();
            }
        }
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/customers/" + buyer + "/pricing",
                                Map.of("price_list_id", wholesale),
                                MANAGER,
                                "*",
                                Map.of("If-Match", String.valueOf(version)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode listed = sold(MANAGER, buyer, carton, "1");
        assertThat(listed.get("sale_unit_price_minor").asLong()).isEqualTo(1_000);
        assertThat(listed.get("price").get("source").asString()).isEqualTo("list");

        JsonNode walkIn = sold(MANAGER, null, carton, "1");
        assertThat(walkIn.get("sale_unit_price_minor").asLong()).isEqualTo(1_100);
        assertThat(walkIn.get("price").get("source").asString()).isEqualTo("retail");

        // A list with no price for the pack falls back to the pack's own price, not the piece price times the size.
        UUID other =
                RetailTestSupport.id(api.post("/price-lists", Map.of("name", "Contractors", "strict", false), MANAGER));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("price_list_id", other);
        body.put("lines", List.of(Map.of("product_id", pen, "unit_id", carton, "qty", "1")));
        JsonNode resolved = api.post("/prices/resolve", body, MANAGER)
                .getBody()
                .get("lines")
                .get(0);
        assertThat(resolved.get("unit_price_minor").asLong()).isEqualTo(1_100);
    }

    // ---- quotations and customer orders -----------------------------------------------------

    /** A quotation and a customer order go through the sale's pricing: the pack's own price, and never a pack only bought. */
    @Test
    void aQuotationAndAnOrderTakeThePacksOwnPrice() {
        capability("documents", true);
        pack(carton, "12", null, 1_100L);
        pack(box, "10", "buy", null);
        String docs = ADMIN + ",retail.document.issue";
        String orders = ADMIN + ",retail.order.manage";

        ResponseEntity<JsonNode> quoted = api.postKeyed(
                "/quotations", quotation(carton), docs, "*", UUID.randomUUID().toString());
        assertThat(quoted.getStatusCode()).as("%s", quoted.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(quoted.getBody().get("lines").get(0).get("unit_price_minor").asLong())
                .isEqualTo(1_100);
        ResponseEntity<JsonNode> quotedBox = api.postKeyed(
                "/quotations", quotation(box), docs, "*", UUID.randomUUID().toString());
        assertThat(quotedBox.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(quotedBox.getBody().toString()).contains("unknown_pack_unit");

        ResponseEntity<JsonNode> ordered = api.postKeyed(
                "/orders", order(carton), orders, "*", UUID.randomUUID().toString());
        assertThat(ordered.getStatusCode()).as("%s", ordered.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(ordered.getBody().get("lines").get(0).get("unit_price_minor").asLong())
                .isEqualTo(1_100);
        ResponseEntity<JsonNode> orderedBox = api.postKeyed(
                "/orders", order(box), orders, "*", UUID.randomUUID().toString());
        assertThat(orderedBox.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(orderedBox.getBody().toString()).contains("unknown_pack_unit");
    }

    /** Review of #624 (ADR-099 decision 1): an order taken in a pack later made buy only is still delivered. */
    @Test
    void anOrderTakenInAPackLaterMadeBuyOnlyIsStillDelivered() {
        pack(carton, "12", null, 1_100L);
        String orders = ADMIN + ",retail.order.manage";
        ResponseEntity<JsonNode> ordered = api.postKeyed(
                "/orders", order(carton), orders, "*", UUID.randomUUID().toString());
        assertThat(ordered.getStatusCode()).as("%s", ordered.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(change(carton, Map.of("purpose", "buy"), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> delivered = api.postKeyed(
                "/orders/" + ordered.getBody().get("id").asString() + "/deliver",
                Map.of("payment_method", "cash"),
                orders,
                "*",
                UUID.randomUUID().toString());
        assertThat(delivered.getStatusCode()).as("%s", delivered.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode sale = api.get("/sales/" + delivered.getBody().get("sale_id").asString(), ADMIN)
                .getBody()
                .get("lines")
                .get(0);
        // The price agreed on the order stands.
        assertThat(sale.get("sale_unit_price_minor").asLong()).isEqualTo(1_100);
        // A new sale in that carton is refused.
        assertThat(sale(ADMIN, null, carton, "1").getBody().toString()).contains("unknown_pack_unit");
    }

    Map<String, Object> quotation(UUID unit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("buyer_name", "Test Buyer 02");
        body.put("buyer_contact", "+256700000002");
        body.put(
                "valid_until",
                java.time.LocalDate.now(java.time.ZoneId.of("Africa/Kampala"))
                        .plusDays(10)
                        .toString());
        body.put("lines", List.of(Map.of("product_id", pen, "unit_id", unit, "qty", "1")));
        return body;
    }

    Map<String, Object> order(UUID unit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("source", "walk_in");
        body.put("customer_name", "Test Customer 03");
        body.put("customer_phone", "+256700000013");
        body.put("delivery_place", "Test Place, Plot 3");
        body.put(
                "delivery_date",
                java.time.LocalDate.now(java.time.ZoneId.of("Africa/Kampala")).toString());
        body.put("delivery_fee_minor", 0);
        body.put("lines", List.of(Map.of("product_id", pen, "unit_id", unit, "qty", "1")));
        return body;
    }

    // ---- below cost, per pack --------------------------------------------------------------

    /** A pack is below cost when its price is not above the size times the average cost per piece (ADR-079). */
    @Test
    void theBelowCostCheckIsPerPack() {
        // 12 x 83 = 996: 997 is above it, 996 is not.
        assertThat(pack(carton, "12", null, 997L).get("below_cost").asBoolean()).isFalse();
        assertThat(sold(ADMIN, null, carton, "1").get("line_total_minor").asLong())
                .isEqualTo(997);

        ResponseEntity<JsonNode> low = change(carton, Map.of("sell_minor", 996), ADMIN);
        assertThat(low.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The editor is told, and the price is kept: the owner decides.
        assertThat(low.getBody().get("below_cost").asBoolean()).isTrue();
        // The cost flag never reaches a caller who may not read cost.
        assertThat(packOf(carton, NO_PROFIT).has("below_cost")).isFalse();
        assertThat(packOf(carton, NO_PROFIT).get("sell_minor").asLong()).isEqualTo(996);

        ResponseEntity<JsonNode> refused = sale(ADMIN, null, carton, "1");
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("price_below_cost");
        assertThat(sold(ADMIN + ",retail.price.below_cost", null, carton, "1")
                        .get("line_total_minor")
                        .asLong())
                .isEqualTo(996);

        // A dearer delivery moves the average and with it the check.
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", pen);
        line.put("cost_minor", 103);
        line.put("qty_by_branch", List.of(Map.of("branch_id", t.headOffice(), "qty", "988")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", "2026-01-15");
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        assertThat(api.postKeyed(
                                "/purchases",
                                body,
                                ADMIN,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // 976 left at 83 and 988 at 103 average 93 a piece, so a carton costs 1,116.
        assertThat(change(carton, Map.of("sell_minor", 1_100), ADMIN)
                        .getBody()
                        .get("below_cost")
                        .asBoolean())
                .isTrue();
        assertThat(code(sale(ADMIN, null, carton, "1"))).isEqualTo("price_below_cost");
        assertThat(change(carton, Map.of("sell_minor", 1_200), ADMIN)
                        .getBody()
                        .get("below_cost")
                        .asBoolean())
                .isFalse();
    }

    // ---- what a pack is for ----------------------------------------------------------------

    /** A pack only for buying is never sold or priced; a pack only for selling is never bought. */
    @Test
    void aPackIsUsedOnlyForWhatItIsFor() {
        pack(box, "10", "buy", null);
        pack(halfCarton, "6", "sell", 580L);

        ResponseEntity<JsonNode> soldBox = sale(ADMIN, null, box, "1");
        assertThat(soldBox.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(soldBox.getBody().toString()).contains("unknown_pack_unit");
        assertThat(restock(box, "2", 800).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> boughtHalf = restock(halfCarton, "2", 400);
        assertThat(boughtHalf.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(boughtHalf.getBody().toString()).contains("unknown_pack_unit");

        // A price list never prices a pack only for buying.
        capability("price_lists", true);
        UUID list = RetailTestSupport.id(api.post("/price-lists", Map.of("name", "Trade", "strict", false), MANAGER));
        ResponseEntity<JsonNode> listed = api.post(
                "/price-lists/" + list + "/items",
                Map.of("product_id", pen, "unit_id", box, "price_minor", 900),
                MANAGER);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(listed.getBody().toString()).contains("unknown_pack_unit");

        ResponseEntity<JsonNode> pricedBox = addPack(carton, "12", "buy", 1_100L, ADMIN);
        assertThat(pricedBox.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(pricedBox.getBody().toString()).contains("pack_not_sold");
        assertThat(packOf(box, ADMIN).has("below_cost")).isFalse();

        // Making a priced pack buy only drops its price; making it both again sells it at the suggested price.
        ResponseEntity<JsonNode> bought = change(halfCarton, Map.of("purpose", "buy"), ADMIN);
        assertThat(bought.getStatusCode()).as("%s", bought.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(bought.getBody().has("sell_minor")).isFalse();
        assertThat(change(halfCarton, Map.of("purpose", "both"), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(sold(ADMIN, null, halfCarton, "1")
                        .get("sale_unit_price_minor")
                        .asLong())
                .isEqualTo(600);
        ResponseEntity<JsonNode> bad = change(halfCarton, Map.of("purpose", "gift"), ADMIN);
        assertThat(bad.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
