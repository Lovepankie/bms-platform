package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.nio.file.Path;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Units of measure (ADR-033, issue #194; docs/specs/trade-capabilities.md section 4 and 13): pack
 * sizes, sale and restock in any unit, exact conversion, rounding at the edges, and "capability off
 * equals today". Every name and amount is fabricated.
 */
class RetailUnitsIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    static final String ADMINISTRATOR = ADMIN + "," + SETTINGS;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    /** Cost 83 and sell 100 per piece; a carton holds twelve. */
    UUID pen;

    UUID piece;
    UUID carton;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-units", false, true);
        api = new RetailTestSupport(http, t);
        pen = api.product("UNIT-PEN", 83, 100);
        piece = UUID.fromString(
                api.get("/products/" + pen, ADMIN).getBody().get("unit_id").asString());
        carton = api.unit("carton");
    }

    // ---- helpers ---------------------------------------------------------------------------

    void switchUnits(boolean on) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("units", on),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<JsonNode> addPack(UUID product, UUID unit, String factor) {
        return api.post("/products/" + product + "/units", Map.of("unit_id", unit, "factor", factor), ADMIN);
    }

    void pack(UUID product, UUID unit, String factor) {
        ResponseEntity<JsonNode> r = addPack(product, unit, factor);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    ResponseEntity<JsonNode> sell(UUID branch, UUID product, UUID unit, String qty, Long price, String date) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("qty", qty);
        if (unit != null) {
            line.put("unit_id", unit);
        }
        if (price != null) {
            line.put("unit_price_minor", price);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", "cash");
        if (date != null) {
            body.put("sale_date", date);
        }
        body.put("lines", List.of(line));
        return api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    ResponseEntity<JsonNode> restock(UUID branch, UUID product, UUID unit, String qty, long cost) {
        return restock(Map.of(branch, qty), product, unit, cost, "cash");
    }

    ResponseEntity<JsonNode> restock(Map<UUID, String> qtyByBranch, UUID product, UUID unit, long cost, String method) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", cost);
        if (unit != null) {
            line.put("unit_id", unit);
        }
        line.put(
                "qty_by_branch",
                qtyByBranch.entrySet().stream()
                        .map(e -> Map.of("branch_id", e.getKey(), "qty", e.getValue()))
                        .toList());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", "2026-01-15");
        body.put("payment_method", method);
        body.put("lines", List.of(line));
        return api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
    }

    String stock(UUID branch, UUID product) {
        return TestDatabase.owner()
                .sql(
                        "SELECT qty::text FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), branch, product)
                .query(String.class)
                .optional()
                .orElse("none");
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    /** The journal lines of every entry that has {@code sourceId} as its source: account key, debit, credit. */
    static List<String> journal(UUID sourceId) {
        return TestDatabase.owner().sql("""
                        SELECT a.system_key || ' ' || l.debit || ' ' || l.credit
                          FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.source_id = ? ORDER BY e.entry_no, l.line_no
                        """).param(sourceId).query(String.class).list();
    }

    // ---- capability off equals today -------------------------------------------------------

    /** Spec 1.1: off is the default; the routes are 404, a pack is refused, the base unit sale is as before. */
    @Test
    void withTheCapabilityOffNothingChanges() {
        ResponseEntity<JsonNode> packs = api.get("/products/" + pen + "/units", ADMIN);
        assertThat(packs.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(packs)).isEqualTo("capability_not_enabled");
        assertThat(packs.getBody().get("detail").asString())
                .isEqualTo("This feature is not switched on for your business.");
        assertThat(addPack(pen, carton, "12").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/products/" + pen, ADMIN).getBody().has("packs")).isFalse();

        api.stockUp(t.headOffice(), pen, "100");
        // Rows written while on are kept and ignored when off: write one directly, then sell in it.
        TestDatabase.owner()
                .sql(
                        "INSERT INTO retail_product_units (id, tenant_id, product_id, unit_id, factor) VALUES (?, ?, ?, ?, 12)")
                .params(UUID.randomUUID(), t.tenantId(), pen, carton)
                .update();
        ResponseEntity<JsonNode> refused = sell(t.headOffice(), pen, carton, "1", null, null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().toString()).contains("capability_not_enabled");
        assertThat(stock(t.headOffice(), pen)).isEqualTo("100.000");

        // Naming the base unit is the base unit: the line has no unit columns and the stock moves as before.
        ResponseEntity<JsonNode> sale = sell(t.headOffice(), pen, piece, "3", null, null);
        assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode line = sale.getBody().get("lines").get(0);
        assertThat(line.get("qty").asString()).isEqualTo("3.000");
        assertThat(line.get("unit_price_minor").asLong()).isEqualTo(100);
        assertThat(line.has("sale_unit_id")).isFalse();
        assertThat(line.has("sale_qty")).isFalse();
        assertThat(line.has("unit_factor")).isFalse();
        assertThat(stock(t.headOffice(), pen)).isEqualTo("97.000");

        ResponseEntity<JsonNode> restocked = restock(t.headOffice(), pen, carton, "1", 1_000);
        assertThat(restocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(restock(t.headOffice(), pen, null, "2", 90).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(stock(t.headOffice(), pen)).isEqualTo("99.000");
    }

    // ---- managing pack sizes ---------------------------------------------------------------

    @Test
    void packsAreManagedPerProductWithTheirRules() {
        switchUnits(true);
        assertThat(api.get("/products/" + pen, ADMIN).getBody().get("packs")).isEmpty();

        ResponseEntity<JsonNode> added = addPack(pen, carton, "12");
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(added.getBody().get("factor").asString()).isEqualTo("12.000");
        assertThat(added.getBody().get("unit").asString()).isEqualTo("carton");
        JsonNode product = api.get("/products/" + pen, ADMIN).getBody();
        assertThat(product.get("packs")).hasSize(1);
        assertThat(api.get("/products?query=UNIT-PEN", ADMIN)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("packs"))
                .hasSize(1);
        assertThat(api.get("/products/" + pen + "/units", "retail.stock.read")
                        .getBody()
                        .get("items"))
                .hasSize(1);

        assertThat(code(addPack(pen, carton, "6"))).isEqualTo("duplicate_pack");
        ResponseEntity<JsonNode> sameAsBase = addPack(pen, piece, "2");
        assertThat(sameAsBase.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(sameAsBase)).isEqualTo("pack_unit_is_base_unit");
        assertThat(addPack(pen, carton, "0").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // A pack holds at least one base unit: a fraction would drift stock (carton base, sold by the piece).
        assertThat(addPack(pen, carton, "0.083").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(addPack(pen, carton, "0.999").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(addPack(pen, carton, "1.2345").getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // Writing needs retail.catalogue.manage; reading needs only retail.stock.read.
        assertThat(api.post(
                                "/products/" + pen + "/units",
                                Map.of("unit_id", carton, "factor", "3"),
                                "retail.stock.read")
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // The base unit cannot change while a pack exists: factors are relative to it.
        UUID other = api.unit("box");
        String etag = api.get("/products/" + pen, ADMIN).getHeaders().getETag();
        ResponseEntity<JsonNode> changed = api.call(
                HttpMethod.PATCH, "/products/" + pen, Map.of("unit_id", other), ADMIN, "*", Map.of("If-Match", etag));
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(changed)).isEqualTo("unit_in_use_by_pack");

        // A unit that a pack uses cannot be switched off.
        int unitVersion = 0;
        for (JsonNode u : api.get("/units", ADMIN).getBody().get("items")) {
            if (u.get("id").asString().equals(carton.toString())) {
                unitVersion = u.get("version").asInt();
            }
        }
        ResponseEntity<JsonNode> off = api.call(
                HttpMethod.PATCH,
                "/units/" + carton,
                Map.of("active", false),
                ADMIN,
                "*",
                Map.of("If-Match", String.valueOf(unitVersion)));
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(off)).isEqualTo("unit_in_use_by_pack");

        // A factor change applies to later lines only; a pack switched off is not offered.
        ResponseEntity<JsonNode> edited = api.call(
                HttpMethod.PATCH,
                "/products/" + pen + "/units/" + carton,
                Map.of("factor", "24"),
                ADMIN,
                "*",
                Map.of("If-Match", added.getHeaders().getETag()));
        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().get("factor").asString()).isEqualTo("24.000");
        ResponseEntity<JsonNode> stale = api.call(
                HttpMethod.PATCH,
                "/products/" + pen + "/units/" + carton,
                Map.of("active", false),
                ADMIN,
                "*",
                Map.of("If-Match", added.getHeaders().getETag()));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        api.stockUp(t.headOffice(), pen, "100");
        ResponseEntity<JsonNode> retired = api.call(
                HttpMethod.PATCH,
                "/products/" + pen + "/units/" + carton,
                Map.of("active", false),
                ADMIN,
                "*",
                Map.of("If-Match", edited.getHeaders().getETag()));
        assertThat(retired.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> notOffered = sell(t.headOffice(), pen, carton, "1", null, null);
        assertThat(notOffered.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(notOffered.getBody().toString()).contains("unknown_pack_unit");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action LIKE 'retail.product_unit.%'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(3);

        // Only active packs tie a unit down: with the carton pack switched off the unit may be switched off and the
        // item's own unit may change. Switching the pack on again then confirms the factor and re-checks the unit.
        String packEtag = retired.getHeaders().getETag();
        ResponseEntity<JsonNode> noFactor = api.call(
                HttpMethod.PATCH,
                "/products/" + pen + "/units/" + carton,
                Map.of("active", true),
                ADMIN,
                "*",
                Map.of("If-Match", packEtag));
        assertThat(noFactor.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(noFactor)).isEqualTo("factor_required");
        ResponseEntity<JsonNode> unitOff = api.call(
                HttpMethod.PATCH,
                "/units/" + carton,
                Map.of("active", false),
                ADMIN,
                "*",
                Map.of("If-Match", String.valueOf(unitVersion)));
        assertThat(unitOff.getStatusCode()).as("%s", unitOff.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> onWithUnitOff = api.call(
                HttpMethod.PATCH,
                "/products/" + pen + "/units/" + carton,
                Map.of("active", true, "factor", "12"),
                ADMIN,
                "*",
                Map.of("If-Match", packEtag));
        assertThat(onWithUnitOff.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        api.call(
                HttpMethod.PATCH,
                "/units/" + carton,
                Map.of("active", true),
                ADMIN,
                "*",
                Map.of("If-Match", unitOff.getHeaders().getETag()));
        // The pen has stock, so its unit is never converted through the API (issue #351, ADR-044) ...
        ResponseEntity<JsonNode> toCarton = api.call(
                HttpMethod.PATCH,
                "/products/" + pen,
                Map.of("unit_id", carton),
                ADMIN,
                "*",
                Map.of(
                        "If-Match",
                        api.get("/products/" + pen, ADMIN).getHeaders().getETag()));
        assertThat(toCarton.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(toCarton)).isEqualTo("unit_in_use_by_stock");
        // ... so the base unit is set by the owner here, to keep testing the pack rule that follows a changed base
        // unit.
        TestDatabase.owner()
                .sql("UPDATE retail_products SET unit_id = ?, version = version + 1 WHERE id = ?")
                .params(carton, pen)
                .update();
        ResponseEntity<JsonNode> nowTheBase = api.call(
                HttpMethod.PATCH,
                "/products/" + pen + "/units/" + carton,
                Map.of("active", true, "factor", "12"),
                ADMIN,
                "*",
                Map.of("If-Match", packEtag));
        assertThat(nowTheBase.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(nowTheBase)).isEqualTo("pack_unit_is_base_unit");
    }

    // ---- selling in a pack ------------------------------------------------------------------

    /** Spec 4.1 and 4.2: stock in base units, price per pack defaulting to sell x factor, the line exact. */
    @Test
    void aSaleInCartonsMovesBaseUnitsAndKeepsWhatWasEntered() {
        switchUnits(true);
        pack(pen, carton, "12");
        api.stockUp(t.headOffice(), pen, "100");

        ResponseEntity<JsonNode> sale = sell(t.headOffice(), pen, carton, "2", null, null);
        assertThat(sale.getStatusCode()).as("%s", sale.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode line = sale.getBody().get("lines").get(0);
        assertThat(line.get("qty").asString()).isEqualTo("24.000");
        assertThat(line.get("sale_qty").asString()).isEqualTo("2.000");
        assertThat(line.get("sale_unit").asString()).isEqualTo("carton");
        assertThat(line.get("sale_unit_id").asString()).isEqualTo(carton.toString());
        assertThat(line.get("unit_factor").asString()).isEqualTo("12.000");
        assertThat(line.get("sale_unit_price_minor").asLong()).isEqualTo(1_200);
        assertThat(line.get("unit_price_minor").asLong()).isEqualTo(100);
        assertThat(line.get("line_total_minor").asLong()).isEqualTo(2_400);
        assertThat(line.get("unit_cost_minor").asLong()).isEqualTo(83);
        assertThat(line.get("line_cost_minor").asLong()).isEqualTo(24 * 83);
        assertThat(sale.getBody().get("total_minor").asLong()).isEqualTo(2_400);
        assertThat(stock(t.headOffice(), pen)).isEqualTo("76.000");

        UUID id = UUID.fromString(sale.getBody().get("id").asString());
        assertThat(journal(id))
                .containsExactly(
                        "cash_on_hand 2400 0",
                        "sales_revenue 0 2400",
                        "cost_of_goods_sold " + 24 * 83 + " 0",
                        "inventory 0 " + 24 * 83);

        // The history shows the unit used.
        JsonNode read = api.get("/sales/" + id, ADMIN).getBody().get("lines").get(0);
        assertThat(read.get("sale_unit").asString()).isEqualTo("carton");

        // A void returns the base units.
        ResponseEntity<JsonNode> voided =
                api.call(HttpMethod.POST, "/sales/" + id + "/void", Map.of("reason", "Test"), ADMIN, "*", Map.of());
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(stock(t.headOffice(), pen)).isEqualTo("100.000");
    }

    /** A carton line and a loose-pieces line of one item can be in one sale. */
    @Test
    void oneSaleCanHoldCartonsAndLoosePiecesOfTheSameItem() {
        switchUnits(true);
        pack(pen, carton, "12");
        api.stockUp(t.headOffice(), pen, "100");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "cash");
        body.put(
                "lines",
                List.of(
                        Map.of("product_id", pen, "qty", "2", "unit_id", carton),
                        Map.of("product_id", pen, "qty", "5")));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("lines")).hasSize(2);
        assertThat(r.getBody().get("total_minor").asLong()).isEqualTo(2_400 + 500);
        assertThat(stock(t.headOffice(), pen)).isEqualTo("71.000");
    }

    /** Spec 13: stock is conserved across a pack sale and a base sale. */
    @Test
    void stockIsConservedAcrossAPackSaleAndABaseSale() {
        switchUnits(true);
        pack(pen, carton, "12");
        api.stockUp(t.headOffice(), pen, "100");
        assertThat(sell(t.headOffice(), pen, carton, "2", null, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(sell(t.headOffice(), pen, null, "5", null, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(sell(t.headOffice(), pen, carton, "0.5", null, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(stock(t.headOffice(), pen)).isEqualTo("65.000");
        // More cartons than stock is the usual oversell refusal, in base units.
        ResponseEntity<JsonNode> over = sell(t.headOffice(), pen, carton, "6", null, null);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(over)).isEqualTo("insufficient_stock");
        assertThat(stock(t.headOffice(), pen)).isEqualTo("65.000");
    }

    /**
     * Spec 3.3 and 13: the price floor is judged on the line total. A carton of twelve at 83 costs 996;
     * at 997 the price per piece rounds to 83, the cost, and the sale is still above cost.
     */
    @Test
    void aPackSaleAboveCostOnTheLineTotalIsAcceptedAlthoughThePricePerPieceRoundsToTheCost() {
        switchUnits(true);
        pack(pen, carton, "12");
        api.stockUp(t.headOffice(), pen, "100");
        ResponseEntity<JsonNode> atCost = sell(t.headOffice(), pen, carton, "1", 996L, null);
        assertThat(atCost.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(atCost)).isEqualTo("price_below_cost");
        ResponseEntity<JsonNode> above = sell(t.headOffice(), pen, carton, "1", 997L, null);
        assertThat(above.getStatusCode()).as("%s", above.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode line = above.getBody().get("lines").get(0);
        assertThat(line.get("unit_price_minor").asLong()).isEqualTo(83);
        assertThat(line.get("line_total_minor").asLong()).isEqualTo(997);
        assertThat(line.get("line_cost_minor").asLong()).isEqualTo(996);
    }

    /** Spec 4.2: a conversion that is not exact in three places is refused, never rounded. */
    @Test
    void aQuantityThatDoesNotConvertExactlyIsRefused() {
        switchUnits(true);
        pack(pen, carton, "1.5");
        api.stockUp(t.headOffice(), pen, "100");
        ResponseEntity<JsonNode> refused = sell(t.headOffice(), pen, carton, "0.001", 1_000L, null);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("quantity_not_representable");
        assertThat(sell(t.headOffice(), pen, carton, "0.003", 1_000L, null).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> exact = sell(t.headOffice(), pen, carton, "3", 1_000L, null);
        assertThat(exact.getStatusCode()).as("%s", exact.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(exact.getBody().get("lines").get(0).get("qty").asString()).isEqualTo("4.500");
        assertThat(stock(t.headOffice(), pen)).isEqualTo("95.500");
    }

    /**
     * The column bounds of 10^13 hold for the derived price per base unit and the defaulted pack price.
     * JUSTIFICATION-A3: a new test case for a guard added in the same change; no existing case covers it.
     */
    @Test
    void aDerivedPriceBeyondTheBoundIsRefusedNotStored() {
        switchUnits(true);
        UUID big = api.product("UNIT-BIG", 1, 9_000_000_000_000L);
        pack(big, carton, "5000");
        api.stockUp(t.headOffice(), big, "100000");
        assertThat(code(sell(t.headOffice(), big, carton, "1", null, null))).isEqualTo("amount_out_of_range");
    }

    /** A pack the product does not have, or an unknown unit, is a field problem on the line. */
    @Test
    void aUnitThatIsNotAPackOfTheProductIsRefused() {
        switchUnits(true);
        api.stockUp(t.headOffice(), pen, "10");
        ResponseEntity<JsonNode> r = sell(t.headOffice(), pen, carton, "1", null, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().toString()).contains("lines[0].unit_id").contains("unknown_pack_unit");
        assertThat(sell(t.headOffice(), pen, UUID.randomUUID(), "1", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(stock(t.headOffice(), pen)).isEqualTo("10.000");
    }

    /** A product that existed before any pack (no pack rows) sells and restocks in base units as always. */
    @Test
    void aProductWithoutPacksBehavesAsBeforeWhileTheCapabilityIsOn() {
        switchUnits(true);
        api.stockUp(t.headOffice(), pen, "10");
        ResponseEntity<JsonNode> sale = sell(t.headOffice(), pen, null, "4", null, null);
        assertThat(sale.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(sale.getBody().get("lines").get(0).has("sale_unit_id")).isFalse();
        ResponseEntity<JsonNode> bought = restock(t.headOffice(), pen, null, "2.5", 90);
        assertThat(bought.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode line = bought.getBody().get("lines").get(0);
        assertThat(line.has("unit_id")).isFalse();
        assertThat(line.get("cost_minor").asLong()).isEqualTo(90);
        assertThat(line.get("line_total_minor").asLong()).isEqualTo(225);
        assertThat(journal(UUID.fromString(bought.getBody().get("id").asString())))
                .containsExactly("inventory 225 0", "cash_on_hand 0 225");
        assertThat(stock(t.headOffice(), pen)).isEqualTo("8.500");
    }

    /** Spec 13: a whole month of mixed-unit sales reconciles to the ledger and to the stock. */
    @Test
    void aMonthOfMixedUnitSalesReconcilesToTheLedgerAndTheStock() {
        switchUnits(true);
        pack(pen, carton, "12");
        api.stockUp(t.headOffice(), pen, "2000");
        long units = 0;
        List<UUID> sales = new ArrayList<>();
        for (int day = 1; day <= 31; day++) {
            String date = "2026-01-%02d".formatted(day);
            boolean pack = day % 3 != 0;
            String qty = pack ? (day % 2 == 0 ? "1.5" : "2") : String.valueOf(day);
            ResponseEntity<JsonNode> r = sell(t.headOffice(), pen, pack ? carton : null, qty, null, date);
            assertThat(r.getStatusCode()).as("day %d: %s", day, r.getBody()).isEqualTo(HttpStatus.CREATED);
            sales.add(UUID.fromString(r.getBody().get("id").asString()));
            units += pack ? (day % 2 == 0 ? 18 : 24) : day;
        }
        assertThat(stock(t.headOffice(), pen)).isEqualTo("%d.000".formatted(2000 - units));
        long lineTotals = 0;
        long lineCosts = 0;
        long revenue = 0;
        long cogs = 0;
        for (UUID id : sales) {
            JsonNode sale = api.get("/sales/" + id, ADMIN).getBody();
            lineTotals += sale.get("lines").get(0).get("line_total_minor").asLong();
            lineCosts += sale.get("lines").get(0).get("line_cost_minor").asLong();
            for (String l : journal(id)) {
                String[] p = l.split(" ");
                if (p[0].equals("sales_revenue")) {
                    revenue += Long.parseLong(p[2]);
                } else if (p[0].equals("cost_of_goods_sold")) {
                    cogs += Long.parseLong(p[1]);
                }
            }
        }
        assertThat(revenue).isEqualTo(lineTotals);
        assertThat(cogs).isEqualTo(lineCosts);
        assertThat(lineCosts).isEqualTo(units * 83);
    }

    // ---- restocking in a pack ---------------------------------------------------------------

    /**
     * Spec 4.2: a pack cost that does not divide exactly. Three cartons at 1,000 are 3,000 paid; the
     * product's cost is 83 a piece (83.33 rounded), so the movements are 36 x 83 = 2,988 and the
     * remainder of 12 goes to purchase_rounding, in the branch's own entry.
     */
    @Test
    void aRestockInCartonsPostsThePackCostRemainderToPurchaseRounding() {
        switchUnits(true);
        pack(pen, carton, "12");
        Map<UUID, String> qty = new LinkedHashMap<>();
        qty.put(t.headOffice(), "3");
        qty.put(t.secondBranch(), "1");
        ResponseEntity<JsonNode> bought = restock(qty, pen, carton, 1_000, "cash");
        assertThat(bought.getStatusCode()).as("%s", bought.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID id = UUID.fromString(bought.getBody().get("id").asString());
        assertThat(bought.getBody().get("total_minor").asLong()).isEqualTo(4_000);
        JsonNode line = bought.getBody().get("lines").get(0);
        assertThat(line.get("cost_minor").asLong()).isEqualTo(83);
        assertThat(line.get("qty_total").asString()).isEqualTo("48.000");
        assertThat(line.get("line_total_minor").asLong()).isEqualTo(4_000);
        assertThat(line.get("unit").asString()).isEqualTo("carton");
        assertThat(line.get("unit_factor").asString()).isEqualTo("12.000");
        assertThat(line.get("unit_qty_total").asString()).isEqualTo("4.000");
        assertThat(line.get("unit_cost_minor").asLong()).isEqualTo(1_000);
        for (JsonNode b : line.get("qty_by_branch")) {
            assertThat(b.get("qty").asString()).isIn("36.000", "12.000");
            assertThat(b.get("unit_qty").asString()).isIn("3.000", "1.000");
        }
        assertThat(stock(t.headOffice(), pen)).isEqualTo("36.000");
        assertThat(stock(t.secondBranch(), pen)).isEqualTo("12.000");
        assertThat(api.get("/products/" + pen, ADMIN)
                        .getBody()
                        .get("cost_minor")
                        .asLong())
                .isEqualTo(83);

        assertThat(journal(id))
                .containsExactlyInAnyOrder(
                        "inventory 2988 0",
                        "purchase_rounding 12 0",
                        "cash_on_hand 0 3000",
                        "inventory 996 0",
                        "purchase_rounding 4 0",
                        "cash_on_hand 0 1000");
        // Every entry balances (the deferred trigger would have refused it otherwise).
        assertThat(TestDatabase.owner().sql("""
                                SELECT count(*) FROM (SELECT e.id FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id
                                 WHERE e.source_id = ? GROUP BY e.id HAVING sum(l.debit) <> sum(l.credit)) x
                                """).param(id).query(Long.class).single())
                .isZero();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_purchase_lines WHERE purchase_id = ? AND unit_id IS NOT NULL")
                        .param(id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        // A branch-scoped caller sees only its branch's quantity and value, in the unit it was entered in.
        ResponseEntity<JsonNode> scoped = api.call(
                HttpMethod.GET,
                "/purchases",
                null,
                "retail.purchase.create",
                t.secondBranch().toString(),
                Map.of());
        JsonNode scopedLine = scoped.getBody().get("items").get(0).get("lines").get(0);
        assertThat(scopedLine.get("qty_total").asString()).isEqualTo("12.000");
        assertThat(scopedLine.get("unit_qty_total").asString()).isEqualTo("1.000");
        assertThat(scopedLine.get("line_total_minor").asLong()).isEqualTo(1_000);
    }

    /** The remainder may go the other way: a half is rounded up, so the stock is worth more than was paid. */
    @Test
    void theRemainderCanBeACreditAndAnExactCostHasNone() {
        switchUnits(true);
        pack(pen, carton, "12");
        // 1,002 / 12 = 83.5 rounds up to 84: 3 cartons paid 3,006, stocked 36 x 84 = 3,024, credit 18.
        ResponseEntity<JsonNode> up = restock(t.headOffice(), pen, carton, "3", 1_002);
        assertThat(up.getStatusCode()).as("%s", up.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(journal(UUID.fromString(up.getBody().get("id").asString())))
                .containsExactlyInAnyOrder("inventory 3024 0", "cash_on_hand 0 3006", "purchase_rounding 0 18");
        assertThat(api.get("/products/" + pen, ADMIN)
                        .getBody()
                        .get("cost_minor")
                        .asLong())
                .isEqualTo(84);
        // 1,200 / 12 = 100 exactly: no rounding leg in the restock's entry. ADR-079: the branch's average becomes
        // (3,024 + 2,400) / 60 = 90.4, carried as 90 (5,400), so the average's own entry puts 24 to purchase rounding.
        ResponseEntity<JsonNode> exact = restock(t.headOffice(), pen, carton, "2", 1_200);
        assertThat(journal(UUID.fromString(exact.getBody().get("id").asString())))
                .containsExactlyInAnyOrder(
                        "inventory 2400 0", "cash_on_hand 0 2400", "purchase_rounding 24 0", "inventory 0 24");
        assertThat(stock(t.headOffice(), pen)).isEqualTo("60.000");
        // A credit restock owes the amount as entered.
        UUID supplier = RetailTestSupport.id(api.post("/suppliers", Map.of("name", "Test Supplier 01"), ADMIN));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("supplier_id", supplier);
        body.put("purchased_on", "2026-01-15");
        body.put("payment_method", "credit");
        body.put(
                "lines",
                List.of(Map.of(
                        "product_id",
                        pen,
                        "cost_minor",
                        1_000,
                        "unit_id",
                        carton,
                        "qty_by_branch",
                        List.of(Map.of("branch_id", t.headOffice(), "qty", "1")))));
        ResponseEntity<JsonNode> credit =
                api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(credit.getStatusCode()).as("%s", credit.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(journal(UUID.fromString(credit.getBody().get("id").asString())))
                .containsExactlyInAnyOrder(
                        "inventory 996 0",
                        "purchase_rounding 4 0",
                        "trade_creditors 0 1000",
                        // ADR-079: (5,400 + 996) / 72 = 88.8, carried as 89 (6,408): 12 more than came in.
                        "inventory 12 0",
                        "purchase_rounding 0 12");
    }

    @Test
    void aRestockQuantityThatDoesNotConvertExactlyChangesNothing() {
        switchUnits(true);
        pack(pen, carton, "1.5");
        ResponseEntity<JsonNode> refused = restock(t.headOffice(), pen, carton, "0.001", 100);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("quantity_not_representable");
        assertThat(stock(t.headOffice(), pen)).isEqualTo("none");
        assertThat(api.get("/products/" + pen, ADMIN)
                        .getBody()
                        .get("cost_minor")
                        .asLong())
                .isEqualTo(83);
    }

    // ---- the CSV import ---------------------------------------------------------------------

    @Test
    void theProductImportReadsThePackColumnsOnlyWhileTheCapabilityIsOn() {
        String csv = "code,description,category,unit,sell price,pack unit,pack factor\n"
                + "IMP-U1,Test item one,Test Category,piece,100,carton,12\n"
                + "IMP-U2,Test item two,Test Category,piece,200,,\n"
                + "IMP-U3,Test item three,Test Category,piece,300,box,0\n"
                + "IMP-U4,Test item four,Test Category,piece,400,piece,6\n"
                + "IMP-U5,Test item five,Test Category,piece,500,box,\"12,5\"\n"
                + "IMP-U6,Test item six,Test Category,piece,600,box,0.5\n";
        ResponseEntity<JsonNode> off = api.post("/products/import?dry_run=true", Map.of("csv", csv), ADMINISTRATOR);
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(off.getBody().get("rows").get(0).get("outcome").asString()).isEqualTo("error");
        assertThat(off.getBody().get("rows").get(0).get("message").asString()).contains("units capability");
        assertThat(off.getBody().get("rows").get(1).get("outcome").asString()).isEqualTo("added");

        switchUnits(true);
        ResponseEntity<JsonNode> dry = api.post("/products/import?dry_run=true", Map.of("csv", csv), ADMINISTRATOR);
        JsonNode rows = dry.getBody().get("rows");
        assertThat(rows.get(0).get("outcome").asString()).isEqualTo("added");
        assertThat(rows.get(1).get("outcome").asString()).isEqualTo("added");
        assertThat(rows.get(2).get("outcome").asString()).isEqualTo("error");
        assertThat(rows.get(3).get("outcome").asString()).isEqualTo("error");
        assertThat(rows.get(3).get("message").asString()).contains("must differ");
        // A comma is refused, never dropped (12,5 must not become 125), and a pack holds at least one base unit.
        assertThat(rows.get(4).get("outcome").asString()).isEqualTo("error");
        assertThat(rows.get(5).get("outcome").asString()).isEqualTo("error");
        // The dry run shows the factor it parsed.
        assertThat(rows.get(0).get("message").asString()).contains("12.000").contains("carton");

        String good = csv.substring(0, csv.indexOf("IMP-U3"));
        ResponseEntity<JsonNode> applied =
                api.post("/products/import?dry_run=false", Map.of("csv", good), ADMINISTRATOR);
        assertThat(applied.getStatusCode()).as("%s", applied.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(applied.getBody().get("added").asInt()).isEqualTo(2);
        JsonNode found =
                api.get("/products?query=IMP-U1", ADMIN).getBody().get("items").get(0);
        assertThat(found.get("packs")).hasSize(1);
        assertThat(found.get("packs").get(0).get("unit").asString()).isEqualTo("carton");
        assertThat(found.get("packs").get(0).get("factor").asString()).isEqualTo("12.000");
        assertThat(api.get("/products?query=IMP-U2", ADMIN)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("packs"))
                .isEmpty();
        // A file without the new columns is read exactly as before.
        ResponseEntity<JsonNode> plain = api.post(
                "/products/import?dry_run=true",
                Map.of(
                        "csv",
                        "code,description,category,unit,sell price\nIMP-U9,Test item nine,Test Category,piece,900\n"),
                ADMINISTRATOR);
        assertThat(plain.getBody().get("added").asInt()).isEqualTo(1);
    }

    // ---- stock in packs and pieces (issue #650) ---------------------------------------------

    /**
     * The stock lists and the item carry the standard pack (ADR-103), only while the capability is on: a selling pack whose
     * unit has a usual pack first, then the biggest selling pack, then the biggest of any, a tie going to the unit id.
     */
    @Test
    void stockRowsCarryTheStandardPack() {
        switchUnits(true);
        UUID dozen = api.unit("dozen");
        UUID bale = api.unit("bale");
        UUID pair = api.unit("pair");
        pack(pen, dozen, "12");
        pack(pen, carton, "24");
        pack(pen, pair, "1");
        ResponseEntity<JsonNode> baled = api.post(
                "/products/" + pen + "/units", Map.of("unit_id", bale, "factor", "100", "purpose", "buy"), ADMIN);
        assertThat(baled.getStatusCode()).as("%s", baled.getBody()).isEqualTo(HttpStatus.CREATED);
        api.stockUp(t.headOffice(), pen, "127");
        // A bale only bought is never counted in while a pack for buying and selling exists: the biggest of those wins.
        assertThat(standardOf(pen)).isEqualTo("carton 24.000");
        JsonNode all = rowOf(api.get("/stock/all-branches", ADMIN).getBody(), pen);
        assertThat(all.get("standard_pack").get("unit").asString()).isEqualTo("carton");
        assertThat(standardOnItem(pen)).isEqualTo("carton");

        // A bale only bought keeps its place even with a usual pack: only a pack used for selling or both comes first.
        TestDatabase.owner()
                .sql("UPDATE retail_units SET pack_size = 100, pack_of_unit_id = ?, pack_purpose = 'buy' WHERE id = ?")
                .params(piece, bale)
                .update();
        assertThat(standardOf(pen)).isEqualTo("carton 24.000");
        // A selling pack whose unit has a usual pack, dozen of twelve pieces (ADR-100), comes first.
        TestDatabase.owner()
                .sql("UPDATE retail_units SET pack_size = 12, pack_of_unit_id = ?, pack_purpose = 'both' WHERE id = ?")
                .params(piece, dozen)
                .update();
        assertThat(standardOf(pen)).isEqualTo("dozen 12.000");
        assertThat(standardOnItem(pen)).isEqualTo("dozen");

        // A pack switched off is never the standard one; an item with no pack has none.
        TestDatabase.owner()
                .sql(
                        "UPDATE retail_product_units SET active = false WHERE tenant_id = ? AND product_id = ? AND unit_id = ?")
                .params(t.tenantId(), pen, dozen)
                .update();
        assertThat(standardOf(pen)).isEqualTo("carton 24.000");
        // Only packs for buying left: the biggest of any; on a tie of size, the lower unit id.
        TestDatabase.owner()
                .sql(
                        "UPDATE retail_product_units SET purpose = 'buy', sell_minor = NULL WHERE tenant_id = ? AND product_id = ?")
                .params(t.tenantId(), pen)
                .update();
        assertThat(standardOf(pen)).isEqualTo("bale 100.000");
        TestDatabase.owner()
                .sql(
                        "UPDATE retail_product_units SET factor = 24 WHERE tenant_id = ? AND product_id = ? AND unit_id = ?")
                .params(t.tenantId(), pen, bale)
                .update();
        String lower = bale.toString().compareTo(carton.toString()) < 0 ? "bale" : "carton";
        assertThat(standardOf(pen)).isEqualTo(lower + " 24.000");
        UUID plain = api.product("UNIT-PLAIN", 50, 60);
        assertThat(rowOf(api.get("/stock?branch_id=" + t.headOffice(), ADMIN).getBody(), plain)
                        .has("standard_pack"))
                .isFalse();

        switchUnits(false);
        JsonNode row =
                rowOf(api.get("/stock?branch_id=" + t.headOffice(), ADMIN).getBody(), pen);
        assertThat(row.has("standard_pack")).isFalse();
        assertThat(rowOf(api.get("/stock/all-branches", ADMIN).getBody(), pen).has("standard_pack"))
                .isFalse();
    }

    /**
     * Issue #660 (ADR-103): a unit's usual pack promotes a pack only when it counts the item's own base unit and is for selling
     * or both. A box whose usual pack is 50 metres is not promoted on an item counted in pieces, nor a box of pieces only
     * bought; the biggest selling pack stays the standard one. A usual pack of pieces for selling or both promotes its pack,
     * and two promoted packs of one size go to the lower unit id.
     */
    @Test
    void aUsualPackOfAnotherUnitIsNotPromoted() {
        switchUnits(true);
        UUID box = api.unit("box");
        UUID metre = api.unit("metre");
        pack(pen, box, "10");
        pack(pen, carton, "24");
        api.stockUp(t.headOffice(), pen, "127");
        assertThat(standardOf(pen)).isEqualTo("carton 24.000");

        TestDatabase.owner()
                .sql("UPDATE retail_units SET pack_size = 50, pack_of_unit_id = ?, pack_purpose = 'sell' WHERE id = ?")
                .params(metre, box)
                .update();
        assertThat(standardOf(pen)).isEqualTo("carton 24.000");
        assertThat(standardOnItem(pen)).isEqualTo("carton");
        // The payload the PWA's contract test reads is this one (fixtures/retail/standard-pack-660.json).
        JsonNode contract = JsonMapper.builder()
                .build()
                .readTree(Path.of("../fixtures/retail/standard-pack-660.json").toFile());
        List<String> packs = new ArrayList<>();
        for (JsonNode k : api.get("/products/" + pen, ADMIN).getBody().get("packs")) {
            packs.add(k.get("unit").asString() + " " + k.get("factor").asString() + " "
                    + k.get("purpose").asString() + " " + k.get("standard").asBoolean());
        }
        List<String> expected = new ArrayList<>();
        for (JsonNode k : contract.get("item_packs")) {
            expected.add(k.get("unit").asString() + " " + k.get("factor").asString() + " "
                    + k.get("purpose").asString() + " " + k.get("standard").asBoolean());
        }
        assertThat(packs).containsExactlyInAnyOrderElementsOf(expected);
        JsonNode row =
                rowOf(api.get("/stock?branch_id=" + t.headOffice(), ADMIN).getBody(), pen);
        JsonNode want = contract.get("stock_row");
        assertThat(row.get("unit").asString()).isEqualTo(want.get("unit").asString());
        assertThat(row.get("qty").asString()).isEqualTo(want.get("qty").asString());
        assertThat(row.get("standard_pack")).isEqualTo(want.get("standard_pack"));

        TestDatabase.owner()
                .sql("UPDATE retail_units SET pack_size = 10, pack_of_unit_id = ?, pack_purpose = 'buy' WHERE id = ?")
                .params(piece, box)
                .update();
        assertThat(standardOf(pen)).isEqualTo("carton 24.000");

        // A usual pack of pieces for selling promotes the box over the bigger carton.
        TestDatabase.owner()
                .sql("UPDATE retail_units SET pack_purpose = 'sell' WHERE id = ?")
                .params(box)
                .update();
        assertThat(standardOf(pen)).isEqualTo("box 10.000");
        assertThat(standardOnItem(pen)).isEqualTo("box");
        // A usual pack for both promotes it too.
        TestDatabase.owner()
                .sql("UPDATE retail_units SET pack_purpose = 'both' WHERE id = ?")
                .params(box)
                .update();
        assertThat(standardOf(pen)).isEqualTo("box 10.000");

        // Two promoted packs of the same size: the lower unit id wins, as in the other tiers.
        UUID bundle = api.unit("bundle");
        pack(pen, bundle, "10");
        TestDatabase.owner()
                .sql("UPDATE retail_units SET pack_size = 10, pack_of_unit_id = ?, pack_purpose = 'sell' WHERE id = ?")
                .params(piece, bundle)
                .update();
        String lower = bundle.toString().compareTo(box.toString()) < 0 ? "bundle" : "box";
        assertThat(standardOf(pen)).isEqualTo(lower + " 10.000");
        assertThat(standardOnItem(pen)).isEqualTo(lower);
    }

    String standardOf(UUID product) {
        JsonNode pack = rowOf(
                        api.get("/stock?branch_id=" + t.headOffice(), ADMIN).getBody(), product)
                .get("standard_pack");
        return pack.get("unit").asString() + " " + pack.get("factor").asString();
    }

    String standardOnItem(UUID product) {
        List<String> marked = new ArrayList<>();
        for (JsonNode k : api.get("/products/" + product, ADMIN).getBody().get("packs")) {
            if (k.get("standard").asBoolean()) {
                marked.add(k.get("unit").asString());
            }
        }
        assertThat(marked).hasSize(1);
        return marked.getFirst();
    }

    static JsonNode rowOf(JsonNode page, UUID product) {
        for (JsonNode r : page.get("items")) {
            if (r.get("product_id").asString().equals(product.toString())) {
                return r;
            }
        }
        throw new AssertionError("no row for " + product + " in " + page);
    }
}
