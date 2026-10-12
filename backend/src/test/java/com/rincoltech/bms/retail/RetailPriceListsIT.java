package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Price lists, quantity breaks, effective dates and customer groups (ADR-033, issue #195;
 * docs/specs/trade-capabilities.md sections 3 and 13): the resolution order, the largest break not above the
 * quantity, the date edges, the strict list, pack prices and exact rounding, the override and its reason, history
 * that a list change never rewrites, the overlap rule under concurrency, cost that stays out of reach, and "capability
 * off equals today". Every name and amount is fabricated.
 */
class RetailPriceListsIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    /** The retail administrator column after migration V41: the price list permissions and the price floor. */
    static final String MANAGER =
            ADMIN + ",retail.pricelist.manage,retail.pricelist.use,retail.price.override,retail.price.below_cost";
    /** An administrator who may manage lists but may not read cost. */
    static final String MANAGER_NO_PROFIT = MANAGER.replace(",retail.profit.read", "");
    /** The sales role after migration V41: it holds the price override, as it holds retail.sale.create. */
    static final String CLERK = RetailTestSupport.SALES + ",retail.price.override";
    /** A sales user whose administrator removed the override. */
    static final String CLERK_NO_OVERRIDE = RetailTestSupport.SALES;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    /** Cost 30 and sell 100 per piece. */
    UUID pen;
    /** Cost 40 and sell 50. */
    UUID ream;
    /** Cost 83 and sell 100 per piece: a carton of twelve costs 996. */
    UUID cased;

    UUID piece;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-price", false, true);
        api = new RetailTestSupport(http, t);
        pen = api.product("PL-PEN", 30, 100);
        ream = api.product("PL-REAM", 40, 50);
        cased = api.product("PL-CASE", 83, 100);
        piece = UUID.fromString(
                api.get("/products/" + pen, ADMIN).getBody().get("unit_id").asString());
        api.stockUp(t.headOffice(), pen, "1000");
        api.stockUp(t.headOffice(), ream, "1000");
        api.stockUp(t.headOffice(), cased, "1000");
        capability("price_lists", true);
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

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    UUID list(String name, boolean strict) {
        ResponseEntity<JsonNode> r = api.post("/price-lists", Map.of("name", name, "strict", strict), MANAGER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    ResponseEntity<JsonNode> item(
            UUID list, UUID product, UUID unit, String minQty, long price, String from, String to) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("product_id", product);
        body.put("unit_id", unit);
        if (minQty != null) {
            body.put("min_qty", minQty);
        }
        body.put("price_minor", price);
        if (from != null) {
            body.put("valid_from", from);
        }
        if (to != null) {
            body.put("valid_to", to);
        }
        return api.post("/price-lists/" + list + "/items", body, MANAGER);
    }

    void price(UUID list, UUID product, UUID unit, String minQty, long price) {
        ResponseEntity<JsonNode> r = item(list, product, unit, minQty, price, null, null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    /** An item written as the owner, which can carry a start date in the past (the API refuses that). */
    void pastItem(UUID list, UUID product, UUID unit, String minQty, long price, String from, String to) {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_price_list_items (id, tenant_id, price_list_id, product_id, unit_id, min_qty,
                            price_minor, valid_from, valid_to)
                        VALUES (gen_random_uuid(), ?, ?, ?, ?, CAST(? AS numeric), ?, CAST(? AS date), CAST(? AS date))
                        """)
                .params(t.tenantId(), list, product, unit, minQty, price, from, to)
                .update();
    }

    UUID group(String name, UUID list) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (list != null) {
            body.put("price_list_id", list);
        }
        ResponseEntity<JsonNode> r = api.post("/customer-groups", body, MANAGER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    UUID customer(String name, UUID group, UUID list) {
        UUID id = RetailTestSupport.id(api.post("/customers", Map.of("name", name), ADMIN));
        if (group != null || list != null) {
            assertThat(pricing(id, group, list, MANAGER).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        return id;
    }

    int customerVersion(UUID id) {
        for (JsonNode c : api.get("/customers", ADMIN).getBody().get("items")) {
            if (c.get("id").asString().equals(id.toString())) {
                return c.get("version").asInt();
            }
        }
        throw new AssertionError("no customer " + id);
    }

    ResponseEntity<JsonNode> pricing(UUID customer, UUID group, UUID list, String perms) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (group != null) {
            body.put("customer_group_id", group);
        }
        if (list != null) {
            body.put("price_list_id", list);
        }
        return api.call(
                HttpMethod.PUT,
                "/customers/" + customer + "/pricing",
                body,
                perms,
                "*",
                Map.of("If-Match", String.valueOf(customerVersion(customer))));
    }

    /** One line of a sale: product, optional unit, quantity, optional typed price and reason. */
    static Map<String, Object> line(UUID product, UUID unit, String qty, Long price, String reason) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("qty", qty);
        if (unit != null) {
            l.put("unit_id", unit);
        }
        if (price != null) {
            l.put("unit_price_minor", price);
        }
        if (reason != null) {
            l.put("price_override_reason", reason);
        }
        return l;
    }

    @SafeVarargs
    final ResponseEntity<JsonNode> sale(
            String perms, UUID customer, UUID list, String date, Map<String, Object>... lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "cash");
        if (customer != null) {
            body.put("customer_id", customer);
        }
        if (list != null) {
            body.put("price_list_id", list);
        }
        if (date != null) {
            body.put("sale_date", date);
        }
        body.put("lines", List.of(lines));
        return api.call(
                HttpMethod.POST,
                "/sales",
                body,
                perms,
                "*",
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }

    @SafeVarargs
    final JsonNode saleOk(String perms, UUID customer, UUID list, String date, Map<String, Object>... lines) {
        ResponseEntity<JsonNode> r = sale(perms, customer, list, date, lines);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    JsonNode one(String perms, UUID customer, String qty) {
        return saleOk(perms, customer, null, null, line(pen, null, qty, null, null));
    }

    static long total(JsonNode sale) {
        return sale.get("lines").get(0).get("line_total_minor").asLong();
    }

    static JsonNode priceOf(JsonNode sale) {
        return sale.get("lines").get(0).get("price");
    }

    @SafeVarargs
    final ResponseEntity<JsonNode> resolve(String perms, UUID customer, UUID list, Map<String, Object>... lines) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        if (customer != null) {
            body.put("customer_id", customer);
        }
        if (list != null) {
            body.put("price_list_id", list);
        }
        body.put("lines", List.of(lines));
        return api.post("/prices/resolve", body, perms);
    }

    LocalDate today() {
        return LocalDate.parse(item(list("Probe today", false), pen, piece, "0", 1, null, null)
                .getBody()
                .get("valid_from")
                .asString());
    }

    // ---- capability off equals today -------------------------------------------------------

    /** Spec 1.1: off is the default; routes are 404, rows that exist are ignored, and a sale is priced as before. */
    @Test
    void withTheCapabilityOffNothingChanges() {
        UUID wholesale = list("Wholesale", false);
        price(wholesale, pen, piece, "0", 90);
        UUID buyers = group("Contractors", wholesale);
        UUID c = customer("Test Buyer 01", buyers, wholesale);
        JsonNode made = one(CLERK, c, "1");
        assertThat(made.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(90);
        assertThat(made.get("lines").get(0).has("price")).isTrue();

        capability("price_lists", false);

        // A sale made while it was on says nothing about price lists once it is off; the rows are kept.
        JsonNode old = api.get("/sales/" + made.get("id").asString(), ADMIN).getBody();
        assertThat(old.has("price_list_id")).isFalse();
        assertThat(old.has("price_list")).isFalse();
        assertThat(old.get("lines").get(0).has("price")).isFalse();
        assertThat(old.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(90);
        for (JsonNode listed : api.get("/sales", ADMIN).getBody().get("items")) {
            assertThat(listed.has("price_list_id")).isFalse();
            assertThat(listed.get("lines").get(0).has("price")).isFalse();
        }
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_sale_lines WHERE tenant_id = ? AND price_source = 'list'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        for (String path : List.of("/price-lists", "/customer-groups", "/price-lists/" + wholesale + "/items")) {
            ResponseEntity<JsonNode> r = api.get(path, MANAGER);
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(code(r)).isEqualTo("capability_not_enabled");
        }
        assertThat(api.post("/price-lists", Map.of("name", "Late"), MANAGER).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(pricing(c, buyers, wholesale, MANAGER).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resolve(CLERK, null, null, Map.of("product_id", pen, "qty", "1"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The customer reads as before: no group and no list.
        for (JsonNode x : api.get("/customers", ADMIN).getBody().get("items")) {
            assertThat(x.has("customer_group_id")).isFalse();
            assertThat(x.has("price_list_id")).isFalse();
        }
        // A sale ignores the list that exists, takes the retail price, and says nothing about price lists.
        JsonNode sale = one(CLERK_NO_OVERRIDE, c, "50");
        assertThat(sale.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(100);
        assertThat(total(sale)).isEqualTo(5_000);
        assertThat(sale.get("lines").get(0).has("price")).isFalse();
        assertThat(sale.has("price_list_id")).isFalse();
        // A typed price needs no permission and no reason, as it did before price lists.
        JsonNode typed = saleOk(CLERK_NO_OVERRIDE, null, null, null, line(pen, null, "1", 95L, null));
        assertThat(typed.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(95);
        assertThat(typed.get("lines").get(0).has("price")).isFalse();
        // A sale that names a list is refused while the capability is off.
        ResponseEntity<JsonNode> named = sale(MANAGER, null, wholesale, null, line(pen, null, "1", null, null));
        assertThat(named.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(named.getBody().toString()).contains("capability_not_enabled");
        // What was recorded while it was on is kept.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_price_list_items WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    // ---- resolution ------------------------------------------------------------------------

    /** Spec 3.2: the sale's list (with the permission), else the customer's own, else the group's, else retail. */
    @Test
    void theListIsChosenInTheOrderOfTheSpec() {
        UUID wholesale = list("Wholesale", false);
        UUID vip = list("Trade partner", false);
        price(wholesale, pen, piece, "0", 90);
        price(vip, pen, piece, "0", 60);
        UUID contractors = group("Contractors", wholesale);
        UUID inGroup = customer("Test Buyer 01", contractors, null);
        UUID own = customer("Test Buyer 02", contractors, vip);
        UUID plain = customer("Test Buyer 03", null, null);

        JsonNode g = one(CLERK, inGroup, "1");
        assertThat(g.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(90);
        assertThat(priceOf(g).get("source").asString()).isEqualTo("list");
        assertThat(priceOf(g).get("price_list").asString()).isEqualTo("Wholesale");
        assertThat(g.get("price_list_id").asString()).isEqualTo(wholesale.toString());
        assertThat(one(CLERK, own, "1")
                        .get("lines")
                        .get(0)
                        .get("unit_price_minor")
                        .asLong())
                .isEqualTo(60);

        JsonNode none = one(CLERK, plain, "1");
        assertThat(none.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(100);
        assertThat(priceOf(none).get("source").asString()).isEqualTo("retail");
        assertThat(priceOf(none).get("resolved_unit_price_minor").asLong()).isEqualTo(100);
        assertThat(priceOf(none).has("price_list_id")).isFalse();
        assertThat(none.has("price_list_id")).isFalse();
        assertThat(one(CLERK, null, "1")
                        .get("lines")
                        .get(0)
                        .get("unit_price_minor")
                        .asLong())
                .isEqualTo(100);

        // Naming a list other than the customer's own or the group's needs retail.pricelist.use.
        ResponseEntity<JsonNode> denied = sale(CLERK, inGroup, vip, null, line(pen, null, "1", null, null));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode named = saleOk(MANAGER, inGroup, vip, null, line(pen, null, "1", null, null));
        assertThat(named.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(60);
        assertThat(named.get("price_list_id").asString()).isEqualTo(vip.toString());
        // Naming the group's own list needs nothing: it applies to any seller anyway.
        assertThat(saleOk(CLERK, inGroup, wholesale, null, line(pen, null, "1", null, null))
                        .get("lines")
                        .get(0)
                        .get("unit_price_minor")
                        .asLong())
                .isEqualTo(90);
        // A list that does not exist is a field problem.
        ResponseEntity<JsonNode> unknown =
                sale(MANAGER, null, UUID.randomUUID(), null, line(pen, null, "1", null, null));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(unknown.getBody().toString()).contains("unknown_price_list");

        // A list that is switched off is passed over: the group's list goes with it and the retail price applies.
        ResponseEntity<JsonNode> off = api.call(
                HttpMethod.PATCH,
                "/price-lists/" + wholesale,
                Map.of("active", false),
                MANAGER,
                "*",
                Map.of(
                        "If-Match",
                        api.get("/price-lists/" + wholesale, MANAGER)
                                .getHeaders()
                                .getETag()));
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(one(CLERK, inGroup, "1")
                        .get("lines")
                        .get(0)
                        .get("unit_price_minor")
                        .asLong())
                .isEqualTo(100);
        ResponseEntity<JsonNode> offNamed = sale(MANAGER, null, wholesale, null, line(pen, null, "1", null, null));
        assertThat(offNamed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** Spec 3.2 step 2: the largest quantity break not above the line quantity, applied to the whole line. */
    @Test
    void theLargestBreakNotAboveTheQuantityApplies() {
        UUID wholesale = list("Wholesale", false);
        price(wholesale, pen, piece, "0", 90);
        price(wholesale, pen, piece, "10", 80);
        price(wholesale, pen, piece, "100", 70);
        UUID c = customer("Test Buyer 01", null, wholesale);

        Map<String, long[]> expected = new LinkedHashMap<>();
        expected.put("0.500", new long[] {90, 45});
        expected.put("9.999", new long[] {90, 900});
        expected.put("10", new long[] {80, 800});
        expected.put("12.500", new long[] {80, 1_000});
        expected.put("99.999", new long[] {80, 8_000});
        expected.put("100", new long[] {70, 7_000});
        for (Map.Entry<String, long[]> e : expected.entrySet()) {
            JsonNode s = one(CLERK, c, e.getKey());
            assertThat(s.get("lines").get(0).get("unit_price_minor").asLong())
                    .as("price at %s", e.getKey())
                    .isEqualTo(e.getValue()[0]);
            assertThat(total(s)).as("total at %s", e.getKey()).isEqualTo(e.getValue()[1]);
        }
        JsonNode atTen = one(CLERK, c, "10");
        assertThat(priceOf(atTen).get("break_min_qty").asString()).isEqualTo("10.000");
        assertThat(priceOf(atTen).get("resolved_unit_price_minor").asLong()).isEqualTo(80);
        // A break applies to its line; the quantities of two lines of one product are not added together.
        JsonNode split =
                saleOk(CLERK, c, null, null, line(pen, null, "6", null, null), line(pen, null, "6", null, null));
        assertThat(split.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(90);
        assertThat(split.get("lines").get(1).get("unit_price_minor").asLong()).isEqualTo(90);
    }

    /** Spec 3.4 and 13: an item is valid on its first and on its last day, and not the day before or after. */
    @Test
    void anItemIsValidOnItsFirstAndLastDay() {
        UUID march = list("March promotion", false);
        pastItem(march, ream, piece(ream), "0", 45, "2026-03-01", "2026-03-31");
        UUID c = customer("Test Buyer 01", null, march);
        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("2026-02-28", 50L);
        expected.put("2026-03-01", 45L);
        expected.put("2026-03-15", 45L);
        expected.put("2026-03-31", 45L);
        expected.put("2026-04-01", 50L);
        for (Map.Entry<String, Long> e : expected.entrySet()) {
            JsonNode s = saleOk(CLERK, c, null, e.getKey(), line(ream, null, "1", null, null));
            assertThat(s.get("lines").get(0).get("unit_price_minor").asLong())
                    .as("price on %s", e.getKey())
                    .isEqualTo(e.getValue());
        }
    }

    UUID piece(UUID product) {
        return UUID.fromString(
                api.get("/products/" + product, ADMIN).getBody().get("unit_id").asString());
    }

    /** Spec 3.2 step 3: no item falls back to the retail price, unless the list is strict (422). */
    @Test
    void aMissingItemFallsBackUnlessTheListIsStrict() {
        UUID loose = list("Wholesale", false);
        UUID strict = list("Strict wholesale", true);
        price(loose, pen, piece, "0", 90);
        price(strict, pen, piece, "0", 90);
        UUID inLoose = customer("Test Buyer 01", null, loose);
        UUID inStrict = customer("Test Buyer 02", null, strict);

        JsonNode fallback = saleOk(CLERK, inLoose, null, null, line(ream, null, "2", null, null));
        assertThat(fallback.get("lines").get(0).get("unit_price_minor").asLong())
                .isEqualTo(50);
        assertThat(priceOf(fallback).get("source").asString()).isEqualTo("retail");
        // The list was in force for the sale, though it priced no line.
        assertThat(fallback.get("price_list_id").asString()).isEqualTo(loose.toString());

        ResponseEntity<JsonNode> refused = sale(CLERK, inStrict, null, null, line(ream, null, "2", null, null));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("no_price_in_list");
        // Strict means strict: a holder of the override with a reason cannot sell the missing item either, because an
        // override changes a price the system resolved and a strict list resolves none (spec section 3).
        ResponseEntity<JsonNode> typed =
                sale(MANAGER, inStrict, null, null, line(ream, null, "2", 45L, "Agreed with the owner today"));
        assertThat(typed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(typed)).isEqualTo("no_price_in_list");
        assertThat(saleOk(CLERK, inStrict, null, null, line(pen, null, "2", null, null))
                        .get("lines")
                        .get(0)
                        .get("unit_price_minor")
                        .asLong())
                .isEqualTo(90);
        // The resolve route reports it on the line instead of refusing.
        ResponseEntity<JsonNode> resolved = resolve(CLERK, inStrict, null, Map.of("product_id", ream, "qty", "2"));
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody().get("lines").get(0).get("problem").asString())
                .isEqualTo("no_price_in_list");
    }

    // ---- packs and rounding ----------------------------------------------------------------

    /** Spec 4.1: a list item may state its own pack price, which wins over sell x factor; money rounds once per line. */
    @Test
    void aPackIsPricedFromItsOwnItemAndTheLineRoundsOnce() {
        capability("units", true);
        UUID carton = api.unit("carton");
        assertThat(api.post("/products/" + pen + "/units", Map.of("unit_id", carton, "factor", "12"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        UUID wholesale = list("Wholesale", false);
        price(wholesale, pen, piece, "0", 90);
        UUID c = customer("Test Buyer 01", null, wholesale);

        // No item in the carton: the retail price of that unit (100 x 12), not the piece price of the list.
        JsonNode noItem = saleOk(CLERK, c, null, null, line(pen, carton, "2", null, null));
        JsonNode l0 = noItem.get("lines").get(0);
        assertThat(l0.get("sale_unit_price_minor").asLong()).isEqualTo(1_200);
        assertThat(priceOf(noItem).get("source").asString()).isEqualTo("retail");

        price(wholesale, pen, carton, "0", 1_000);
        price(wholesale, pen, carton, "5", 950);
        JsonNode packed = saleOk(CLERK, c, null, null, line(pen, carton, "2", null, null));
        JsonNode l1 = packed.get("lines").get(0);
        assertThat(l1.get("sale_unit_price_minor").asLong()).isEqualTo(1_000);
        assertThat(l1.get("qty").asString()).isEqualTo("24.000");
        assertThat(total(packed)).isEqualTo(2_000);
        assertThat(l1.get("unit_price_minor").asLong()).isEqualTo(83);
        assertThat(priceOf(packed).get("source").asString()).isEqualTo("list");
        assertThat(priceOf(packed).get("resolved_unit_price_minor").asLong()).isEqualTo(1_000);
        assertThat(priceOf(packed).get("break_min_qty").asString()).isEqualTo("0.000");
        JsonNode five = saleOk(CLERK, c, null, null, line(pen, carton, "5", null, null));
        assertThat(five.get("lines").get(0).get("sale_unit_price_minor").asLong())
                .isEqualTo(950);
        // The piece items do not price a carton and the carton items do not price a piece.
        assertThat(one(CLERK, c, "5")
                        .get("lines")
                        .get(0)
                        .get("unit_price_minor")
                        .asLong())
                .isEqualTo(90);

        // 1.5 cartons at 995: 1,492.5 rounds half up, once, on the line.
        UUID other = list("Contractors", false);
        price(other, pen, carton, "0", 995);
        UUID c2 = customer("Test Buyer 02", null, other);
        JsonNode half = saleOk(CLERK, c2, null, null, line(pen, carton, "1.5", null, null));
        assertThat(total(half)).isEqualTo(1_493);
        assertThat(half.get("lines").get(0).get("sale_unit_price_minor").asLong())
                .isEqualTo(995);
        assertThat(half.get("lines").get(0).get("qty").asString()).isEqualTo("18.000");

        // The price floor is judged on the line: a carton of the 83-cost item costs 996. 997 is above it, 996 is not.
        assertThat(api.post("/products/" + cased + "/units", Map.of("unit_id", carton, "factor", "12"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        UUID floor = list("Clearance", false);
        price(floor, cased, carton, "0", 997);
        UUID c3 = customer("Test Buyer 03", null, floor);
        assertThat(saleOk(CLERK, c3, null, null, line(cased, carton, "1", null, null))
                        .get("id"))
                .isNotNull();
        UUID cheap = list("Below cost", false);
        // A list item below cost can be saved, and the editor is told.
        ResponseEntity<JsonNode> saved = item(cheap, cased, carton, "0", 996, null, null);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getBody().get("below_cost").asBoolean()).isTrue();
        UUID c4 = customer("Test Buyer 04", null, cheap);
        ResponseEntity<JsonNode> refused = sale(CLERK, c4, null, null, line(cased, carton, "1", null, null));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("price_below_cost");
        assertThat(saleOk(MANAGER, c4, null, null, line(cased, carton, "1", null, null))
                        .get("id"))
                .isNotNull();
    }

    // ---- override --------------------------------------------------------------------------

    /** Spec 3.2 step 4: a typed price that differs from the resolved one needs the permission and a reason. */
    @Test
    void anOverrideNeedsThePermissionAndAReasonAndIsStoredWithBoth() {
        UUID wholesale = list("Wholesale", false);
        price(wholesale, pen, piece, "0", 90);
        UUID c = customer("Test Buyer 01", null, wholesale);

        JsonNode sale = saleOk(CLERK, c, null, null, line(pen, null, "2", 85L, "Loyal buyer, agreed with the owner"));
        JsonNode l = sale.get("lines").get(0);
        assertThat(l.get("unit_price_minor").asLong()).isEqualTo(85);
        assertThat(total(sale)).isEqualTo(170);
        assertThat(priceOf(sale).get("source").asString()).isEqualTo("override");
        assertThat(priceOf(sale).get("resolved_unit_price_minor").asLong()).isEqualTo(90);
        assertThat(priceOf(sale).get("override_reason").asString()).isEqualTo("Loyal buyer, agreed with the owner");
        assertThat(priceOf(sale).get("price_list").asString()).isEqualTo("Wholesale");

        ResponseEntity<JsonNode> noReason = sale(CLERK, c, null, null, line(pen, null, "1", 85L, null));
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(noReason)).isEqualTo("override_reason_required");
        assertThat(code(sale(CLERK, c, null, null, line(pen, null, "1", 85L, "ok"))))
                .isEqualTo("override_reason_required");
        ResponseEntity<JsonNode> denied =
                sale(CLERK_NO_OVERRIDE, c, null, null, line(pen, null, "1", 85L, "Loyal buyer, agreed"));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The resolved price typed back is not an override: no permission and no reason.
        JsonNode same = saleOk(CLERK_NO_OVERRIDE, c, null, null, line(pen, null, "1", 90L, null));
        assertThat(priceOf(same).get("source").asString()).isEqualTo("list");
        assertThat(priceOf(same).has("override_reason")).isFalse();
        // A retail price is overridden the same way, with no list on the line.
        JsonNode retail = saleOk(CLERK, null, null, null, line(pen, null, "1", 95L, "Damaged box, agreed discount"));
        assertThat(priceOf(retail).get("source").asString()).isEqualTo("override");
        assertThat(priceOf(retail).has("price_list_id")).isFalse();
        assertThat(priceOf(retail).get("resolved_unit_price_minor").asLong()).isEqualTo(100);
        assertThat(sale(CLERK_NO_OVERRIDE, null, null, null, line(pen, null, "1", 95L, "Damaged box, agreed"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // The price floor still applies to an overridden price.
        assertThat(code(sale(CLERK, null, null, null, line(cased, null, "1", 80L, "Clearance, agreed with owner"))))
                .isEqualTo("price_below_cost");
    }

    /** Spec 3.2 last paragraph and ADR-020 decision 5: editing a list never changes a sale already made. */
    @Test
    void editingAListNeverChangesAnOldSale() {
        UUID wholesale = list("Wholesale", false);
        price(wholesale, pen, piece, "0", 90);
        UUID c = customer("Test Buyer 01", null, wholesale);
        JsonNode before = one(CLERK, c, "3");
        UUID saleId = UUID.fromString(before.get("id").asString());

        // A new price from tomorrow takes over from the current one, which ends today.
        LocalDate today = today();
        ResponseEntity<JsonNode> next =
                item(wholesale, pen, piece, "0", 95, today.plusDays(1).toString(), null);
        assertThat(next.getStatusCode()).as("%s", next.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(next.getBody().get("status").asString()).isEqualTo("scheduled");
        // Rename and switch the list off: the sale keeps its prices.
        api.call(
                HttpMethod.PATCH,
                "/price-lists/" + wholesale,
                Map.of("active", false),
                MANAGER,
                "*",
                Map.of(
                        "If-Match",
                        api.get("/price-lists/" + wholesale, MANAGER)
                                .getHeaders()
                                .getETag()));

        JsonNode after = api.get("/sales/" + saleId, ADMIN).getBody();
        assertThat(after.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(90);
        assertThat(total(after)).isEqualTo(270);
        assertThat(priceOf(after).get("resolved_unit_price_minor").asLong()).isEqualTo(90);
        assertThat(priceOf(after).get("source").asString()).isEqualTo("list");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT unit_price_minor || ' ' || line_total_minor FROM retail_sale_lines WHERE sale_id = ?")
                        .param(saleId)
                        .query(String.class)
                        .single())
                .isEqualTo("90 270");
        // The old item is closed the day before the new one starts; both rows exist.
        JsonNode items = api.get("/price-lists/" + wholesale + "/items?include_ended=true", MANAGER)
                .getBody()
                .get("items");
        assertThat(items).hasSize(2);
        assertThat(api.get("/price-lists/" + wholesale + "/items", MANAGER)
                        .getBody()
                        .get("items"))
                .hasSize(2);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT valid_to::text FROM retail_price_list_items WHERE price_minor = 90 AND tenant_id = ?")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo(today.toString());
    }

    // ---- the item rules --------------------------------------------------------------------

    /** Spec 3.4: no overlap, no past start, the open item is taken over, an item can be ended. */
    @Test
    void itemsFollowTheDateRules() {
        UUID wholesale = list("Wholesale", false);
        LocalDate today = today();
        assertThat(item(wholesale, pen, piece, "0", 90, null, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> same = item(wholesale, pen, piece, "0", 91, null, null);
        assertThat(same.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(same)).isEqualTo("price_item_overlap");
        // A bounded range inside the open one overlaps too; another break or another unit does not.
        assertThat(code(item(
                        wholesale,
                        pen,
                        piece,
                        "0",
                        80,
                        today.plusDays(5).toString(),
                        today.plusDays(9).toString())))
                .isEqualTo("price_item_overlap");
        assertThat(item(wholesale, pen, piece, "10", 80, null, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(item(wholesale, ream, piece(ream), "0", 40, null, null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> past =
                item(wholesale, pen, piece, "20", 70, today.minusDays(1).toString(), null);
        assertThat(past.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(past.getBody().toString()).contains("in_the_past");
        ResponseEntity<JsonNode> backwards = item(
                wholesale,
                pen,
                piece,
                "20",
                70,
                today.plusDays(3).toString(),
                today.plusDays(2).toString());
        assertThat(backwards.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> noProduct = item(wholesale, UUID.randomUUID(), piece, "0", 1, null, null);
        assertThat(noProduct.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // A unit that is neither the base unit nor an active pack is refused.
        ResponseEntity<JsonNode> badUnit = item(wholesale, pen, api.unit("crate"), "0", 1, null, null);
        assertThat(badUnit.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(badUnit.getBody().toString()).contains("unknown_pack_unit");
        assertThat(api.post(
                                "/price-lists/" + wholesale + "/items",
                                Map.of("product_id", pen, "unit_id", piece, "price_minor", -1),
                                MANAGER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        // Ending an open item: valid_to is its last day, not before today.
        String id = api.get("/price-lists/" + wholesale + "/items?product_id=" + ream, MANAGER)
                .getBody()
                .get("items")
                .get(0)
                .get("id")
                .asString();
        ResponseEntity<JsonNode> early = api.call(
                HttpMethod.PATCH,
                "/price-lists/" + wholesale + "/items/" + id,
                Map.of("valid_to", today.minusDays(1).toString()),
                MANAGER,
                "*",
                Map.of());
        assertThat(early.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> ended = api.call(
                HttpMethod.PATCH,
                "/price-lists/" + wholesale + "/items/" + id,
                Map.of("valid_to", today.plusDays(2).toString()),
                MANAGER,
                "*",
                Map.of());
        assertThat(ended.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ended.getBody().get("valid_to").asString())
                .isEqualTo(today.plusDays(2).toString());
        ResponseEntity<JsonNode> again = api.call(
                HttpMethod.PATCH,
                "/price-lists/" + wholesale + "/items/" + id,
                Map.of("valid_to", today.plusDays(5).toString()),
                MANAGER,
                "*",
                Map.of());
        assertThat(code(again)).isEqualTo("price_item_closed");
        // An unrelated list does not own the item.
        UUID elsewhere = list("Elsewhere", false);
        assertThat(api.call(
                                HttpMethod.PATCH,
                                "/price-lists/" + elsewhere + "/items/" + id,
                                Map.of("valid_to", today.plusDays(5).toString()),
                                MANAGER,
                                "*",
                                Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // A product's base unit cannot change while a list prices it.
        String etag = api.get("/products/" + pen, ADMIN).getHeaders().getETag();
        ResponseEntity<JsonNode> changed = api.call(
                HttpMethod.PATCH,
                "/products/" + pen,
                Map.of("unit_id", api.unit("box")),
                ADMIN,
                "*",
                Map.of("If-Match", etag));
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(changed)).isEqualTo("unit_in_use_by_price_list");
    }

    /** Spec 3.4: concurrent edits of one list serialise under the list's row lock, and the second gets 409. */
    @Test
    void twoConcurrentEditsOfTheSamePriceCannotBothWin() throws Exception {
        UUID wholesale = list("Wholesale", false);
        today();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<ResponseEntity<JsonNode>>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                long price = 90 + i;
                Callable<ResponseEntity<JsonNode>> job =
                        () -> item(wholesale, ream, piece(ream), "3", price, null, null);
                results.add(pool.submit(job));
            }
            int created = 0;
            int conflicts = 0;
            for (Future<ResponseEntity<JsonNode>> f : results) {
                ResponseEntity<JsonNode> r = f.get();
                if (r.getStatusCode() == HttpStatus.CREATED) {
                    created++;
                } else if (r.getStatusCode() == HttpStatus.CONFLICT) {
                    conflicts++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(conflicts).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_price_list_items WHERE price_list_id = ? AND min_qty = 3")
                        .param(wholesale)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /** Spec 3.3 and ADR-020 decision 10: cost never leaves with a price; the margin flag needs retail.profit.read. */
    @Test
    void costStaysOutOfReachOfThoseWhoMayNotReadIt() {
        UUID wholesale = list("Wholesale", false);
        assertThat(item(wholesale, cased, piece(cased), "0", 60, null, null)
                        .getBody()
                        .get("below_cost")
                        .asBoolean())
                .isTrue();
        price(wholesale, ream, piece(ream), "0", 55);

        JsonNode withProfit = api.get("/price-lists/" + wholesale + "/items", MANAGER)
                .getBody()
                .get("items");
        assertThat(withProfit.toString()).contains("below_cost");
        JsonNode without = api.get("/price-lists/" + wholesale + "/items", MANAGER_NO_PROFIT)
                .getBody()
                .get("items");
        assertThat(without).hasSize(2);
        assertThat(without.toString()).doesNotContain("below_cost").doesNotContain("cost");

        UUID c = customer("Test Buyer 01", null, wholesale);
        ResponseEntity<JsonNode> resolved = resolve(CLERK, c, null, Map.of("product_id", ream, "qty", "1"));
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody().toString()).doesNotContain("cost");
        // The sale of the item below cost is refused for a seller without the floor permission, with no figure in it.
        ResponseEntity<JsonNode> refused = sale(CLERK, c, null, null, line(cased, null, "1", null, null));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(refused)).isEqualTo("price_below_cost");
        assertThat(refused.getBody().get("detail").asString()).doesNotContain("83");
        JsonNode sale = one(CLERK, null, "1");
        assertThat(sale.toString()).doesNotContain("unit_cost_minor");
    }

    // ---- customers, groups and permissions -------------------------------------------------

    @Test
    void groupsAndCustomerPricingAreAdministratorWorkWithTheirOwnRules() {
        UUID wholesale = list("Wholesale", false);
        UUID contractors = group("Contractors", wholesale);
        assertThat(api.post("/customer-groups", Map.of("name", "contractors"), MANAGER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(api.post("/customer-groups", Map.of("name", "Nowhere", "price_list_id", UUID.randomUUID()), MANAGER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.post("/customer-groups", Map.of("name", "Late"), CLERK).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.post("/price-lists", Map.of("name", "WHOLESALE"), MANAGER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(api.post("/price-lists", Map.of("name", "Late"), CLERK).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        // Reading needs retail.pricelist.manage too: a list is a customer's negotiated price. The sales role gets 403
        // on
        // every read of a list, its items and the groups, and 200 on the resolve route its sale form uses.
        assertThat(api.get("/price-lists", MANAGER).getBody().get("items")).hasSize(1);
        for (String path : List.of(
                "/price-lists",
                "/price-lists/" + wholesale,
                "/price-lists/" + wholesale + "/items",
                "/customer-groups")) {
            assertThat(api.get(path, CLERK).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(api.get(path, CLERK_NO_OVERRIDE).getStatusCode())
                    .as(path)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(resolve(CLERK, null, null, Map.of("product_id", pen, "qty", "1"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.get("/customer-groups", MANAGER)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("customer_count")
                        .asInt())
                .isZero();

        UUID c = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 01"), ADMIN));
        // The sales role edits a buyer's name and contact only (retail.customer.manage), never the price list.
        ResponseEntity<JsonNode> clerk = pricing(c, contractors, null, CLERK);
        assertThat(clerk.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> set = pricing(c, contractors, wholesale, MANAGER);
        assertThat(set.getStatusCode()).as("%s", set.getBody()).isEqualTo(HttpStatus.OK);
        // The buyer's group and list are shown to the list manager only; the sales role reads the buyer as before.
        for (JsonNode seen : api.get("/customers", CLERK).getBody().get("items")) {
            assertThat(seen.has("customer_group_id")).isFalse();
            assertThat(seen.has("price_list_id")).isFalse();
        }
        assertThat(set.getBody().get("customer_group_id").asString()).isEqualTo(contractors.toString());
        assertThat(set.getBody().get("price_list_id").asString()).isEqualTo(wholesale.toString());
        assertThat(api.get("/customer-groups", MANAGER)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("customer_count")
                        .asInt())
                .isEqualTo(1);
        // A stale version is a conflict; an unknown group or list is a field problem.
        ResponseEntity<JsonNode> stale = api.call(
                HttpMethod.PUT,
                "/customers/" + c + "/pricing",
                Map.of(),
                MANAGER,
                "*",
                Map.of("If-Match", String.valueOf(customerVersion(c) - 1)));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(pricing(c, UUID.randomUUID(), null, MANAGER).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // An empty body clears both.
        ResponseEntity<JsonNode> cleared = pricing(c, null, null, MANAGER);
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody().has("customer_group_id")).isFalse();
        assertThat(cleared.getBody().has("price_list_id")).isFalse();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action LIKE 'retail.customer.pricing%'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);

        // A group is switched off, not deleted, and a group switched off prices nobody.
        ResponseEntity<JsonNode> grp = api.get("/customer-groups", MANAGER);
        String etag = grp.getBody().get("items").get(0).get("version").asString();
        ResponseEntity<JsonNode> off = api.call(
                HttpMethod.PUT,
                "/customer-groups/" + contractors,
                Map.of("name", "Contractors", "price_list_id", wholesale, "active", false),
                MANAGER,
                "*",
                Map.of("If-Match", etag));
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(off.getBody().get("active").asBoolean()).isFalse();
    }

    /** A list and a group of one tenant are invisible to another (NFR-ISO-04). */
    @Test
    void anotherTenantSeesNoneOfIt() {
        UUID wholesale = list("Wholesale", false);
        group("Contractors", wholesale);
        TestDatabase.Fixture other = TestDatabase.tenant("retail-price-other", false, true);
        RetailTestSupport theirs = new RetailTestSupport(http, other);
        String etag =
                theirs.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        theirs.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("price_lists", true),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(theirs.get("/price-lists", MANAGER).getBody().get("items")).isEmpty();
        assertThat(theirs.get("/customer-groups", MANAGER).getBody().get("items"))
                .isEmpty();
        assertThat(theirs.get("/price-lists/" + wholesale, MANAGER).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(theirs.get("/price-lists/" + wholesale + "/items", MANAGER).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** The guard trigger: an item takes one change, its last day, once; nothing is deleted. */
    @Test
    void anItemRowCannotBeRewrittenOrDeleted() {
        UUID wholesale = list("Wholesale", false);
        price(wholesale, pen, piece, "0", 90);
        UUID item = UUID.fromString(api.get("/price-lists/" + wholesale + "/items", MANAGER)
                .getBody()
                .get("items")
                .get(0)
                .get("id")
                .asString());
        var owner = TestDatabase.owner();
        assertThatThrownBy(() -> owner.sql("UPDATE retail_price_list_items SET price_minor = 1 WHERE id = ?")
                        .param(item)
                        .update())
                .hasStackTraceContaining("only valid_to may be set");
        assertThatThrownBy(() -> owner.sql("DELETE FROM retail_price_list_items WHERE id = ?")
                        .param(item)
                        .update())
                .isNotNull();
        assertThat(owner.sql("UPDATE retail_price_list_items SET valid_to = valid_from + 3 WHERE id = ?")
                        .param(item)
                        .update())
                .isEqualTo(1);
        assertThatThrownBy(() -> owner.sql("UPDATE retail_price_list_items SET valid_to = valid_from + 4 WHERE id = ?")
                        .param(item)
                        .update())
                .hasStackTraceContaining("only valid_to may be set");
        // The sale line check: a half-recorded price is refused by the database, not only by the service.
        UUID c = customer("Test Buyer 01", null, wholesale);
        UUID saleId = UUID.fromString(one(CLERK, c, "1").get("id").asString());
        assertThatThrownBy(
                        () -> owner.sql("""
                                INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty,
                                    unit_price_minor, unit_cost_minor, line_total_minor, line_cost_minor, price_source,
                                    resolved_unit_price_minor)
                                VALUES (gen_random_uuid(), ?, ?, 2, ?, 1, 100, 83, 100, 83, 'override', 100)
                                """).params(t.tenantId(), saleId, pen).update())
                .hasStackTraceContaining("retail_sale_lines_price_chk");
    }

    String stock() {
        return TestDatabase.owner()
                .sql(
                        "SELECT qty::text FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), t.headOffice(), pen)
                .query(String.class)
                .single();
    }

    /** Issue #195: every changed price is in the audit entry of the sale, with what the list resolved and why. */
    @Test
    void anOverrideIsAuditedWithTheResolvedPriceAndTheReason() {
        UUID wholesale = list("Wholesale", false);
        price(wholesale, pen, piece, "0", 90);
        UUID c = customer("Test Buyer 01", null, wholesale);
        JsonNode changed = saleOk(CLERK, c, null, null, line(pen, null, "2", 85L, "Loyal buyer, agreed with owner"));
        JsonNode plain = one(CLERK, c, "1");

        String audit = TestDatabase.owner()
                .sql(
                        "SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'retail.sale.created' AND entity_id = ?")
                .params(t.tenantId(), UUID.fromString(changed.get("id").asString()))
                .query(String.class)
                .single();
        assertThat(audit).contains("price_overrides").contains("Loyal buyer, agreed with owner");
        assertThat(audit).contains("\"resolved_unit_price_minor\": 90").contains("\"unit_price_minor\": 85");
        assertThat(audit).contains(wholesale.toString());
        String quiet = TestDatabase.owner()
                .sql(
                        "SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'retail.sale.created' AND entity_id = ?")
                .params(t.tenantId(), UUID.fromString(plain.get("id").asString()))
                .query(String.class)
                .single();
        assertThat(quiet).doesNotContain("price_overrides");
    }

    /** A void restores stock and books and never rewrites how a line was priced, for a list price and for an override. */
    @Test
    void voidingAListPricedAndAnOverriddenSaleLeavesTheirPricesAsTheyWere() {
        UUID wholesale = list("Wholesale", false);
        price(wholesale, pen, piece, "0", 90);
        UUID c = customer("Test Buyer 01", null, wholesale);
        for (boolean override : new boolean[] {false, true}) {
            JsonNode made = override
                    ? saleOk(MANAGER, c, null, null, line(pen, null, "2", 85L, "Loyal buyer, agreed"))
                    : one(MANAGER, c, "2");
            UUID id = UUID.fromString(made.get("id").asString());
            assertThat(stock()).isEqualTo("998.000");
            ResponseEntity<JsonNode> voided =
                    api.post("/sales/" + id + "/void", Map.of("reason", "Test void"), MANAGER);
            assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
            JsonNode after = api.get("/sales/" + id, ADMIN).getBody();
            assertThat(after.get("status").asString()).isEqualTo("voided");
            JsonNode before = made.get("lines").get(0);
            JsonNode kept = after.get("lines").get(0);
            assertThat(kept.get("unit_price_minor")).isEqualTo(before.get("unit_price_minor"));
            assertThat(kept.get("line_total_minor")).isEqualTo(before.get("line_total_minor"));
            assertThat(kept.get("price")).isEqualTo(before.get("price"));
            assertThat(kept.get("price").get("source").asString()).isEqualTo(override ? "override" : "list");
            assertThat(after.get("price_list_id").asString()).isEqualTo(wholesale.toString());
            assertThat(stock()).isEqualTo("1000.000");
        }
        // The price sits in the database row too, untouched by the void.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM retail_sale_lines WHERE tenant_id = ? AND price_source IN ('list', 'override')")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    /** The resolve route refuses a store as the sale does, and the reason column is bounded as the API is. */
    @Test
    void aStoreIsNotPricedAndTheReasonHasTheSameBoundAsTheApi() {
        capability("branch_roles", true);
        ResponseEntity<JsonNode> role = api.call(
                HttpMethod.PUT,
                "/branch-roles/" + t.secondBranch(),
                Map.of("role", "store"),
                ADMIN + ",retail.branch_role.manage",
                "*",
                Map.of());
        assertThat(role.getStatusCode()).as("%s", role.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.secondBranch());
        body.put("lines", List.of(Map.of("product_id", pen, "qty", "1")));
        ResponseEntity<JsonNode> store = api.post("/prices/resolve", body, CLERK);
        assertThat(store.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(store)).isEqualTo("branch_role_forbids_sales");
        body.put("branch_id", t.headOffice());
        assertThat(api.post("/prices/resolve", body, CLERK).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 300 characters is accepted, 301 is refused by the API and by the database.
        UUID wholesale = list("Wholesale", false);
        price(wholesale, pen, piece, "0", 90);
        UUID c = customer("Test Buyer 01", null, wholesale);
        assertThat(sale(MANAGER, c, null, null, line(pen, null, "1", 85L, "x".repeat(300)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(sale(MANAGER, c, null, null, line(pen, null, "1", 85L, "x".repeat(301)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        UUID saleId = UUID.fromString(one(MANAGER, c, "1").get("id").asString());
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("""
                                INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty,
                                    unit_price_minor, unit_cost_minor, line_total_minor, line_cost_minor, price_source,
                                    resolved_unit_price_minor, price_override_reason)
                                VALUES (gen_random_uuid(), ?, ?, 9, ?, 1, 100, 30, 100, 30, 'override', 100, ?)
                                """)
                        .params(t.tenantId(), saleId, pen, "y".repeat(301))
                        .update())
                .hasStackTraceContaining("retail_sale_lines_price_chk");
    }

    // ---- the resolve route -----------------------------------------------------------------

    @Test
    void theResolveRouteShowsPriceSourceAndWhyThatListWithoutMakingASale() {
        capability("units", true);
        UUID carton = api.unit("carton");
        api.post("/products/" + pen + "/units", Map.of("unit_id", carton, "factor", "12"), ADMIN);
        UUID wholesale = list("Wholesale", false);
        price(wholesale, pen, piece, "0", 90);
        price(wholesale, pen, piece, "10", 80);
        price(wholesale, pen, carton, "0", 1_000);
        UUID contractors = group("Contractors", wholesale);
        UUID c = customer("Test Buyer 01", contractors, null);

        ResponseEntity<JsonNode> r = resolve(
                CLERK,
                c,
                null,
                Map.of("product_id", pen, "qty", "10"),
                Map.of("product_id", pen, "unit_id", carton, "qty", "1.5"),
                Map.of("product_id", ream, "qty", "2"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode res = r.getBody();
        assertThat(res.get("price_list").asString()).isEqualTo("Wholesale");
        assertThat(res.get("origin").asString()).isEqualTo("customer_group");
        JsonNode lines = res.get("lines");
        assertThat(lines.get(0).get("unit_price_minor").asLong()).isEqualTo(80);
        assertThat(lines.get(0).get("line_total_minor").asLong()).isEqualTo(800);
        assertThat(lines.get(0).get("price_source").asString()).isEqualTo("list");
        assertThat(lines.get(0).get("break_min_qty").asString()).isEqualTo("10.000");
        assertThat(lines.get(1).get("unit_price_minor").asLong()).isEqualTo(1_000);
        assertThat(lines.get(1).get("line_total_minor").asLong()).isEqualTo(1_500);
        assertThat(lines.get(1).get("unit_id").asString()).isEqualTo(carton.toString());
        assertThat(lines.get(2).get("price_source").asString()).isEqualTo("retail");
        assertThat(lines.get(2).get("unit_price_minor").asLong()).isEqualTo(50);
        assertThat(res.toString()).doesNotContain("cost");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_sales WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();

        // No customer: no list, and the origin is absent.
        ResponseEntity<JsonNode> none = resolve(CLERK, null, null, Map.of("product_id", pen, "qty", "10"));
        assertThat(none.getBody().has("price_list_id")).isFalse();
        assertThat(none.getBody().get("lines").get(0).get("unit_price_minor").asLong())
                .isEqualTo(100);
        // A list other than the customer's needs retail.pricelist.use here too; an unknown product is a field problem.
        UUID other = list("Trade partner", false);
        assertThat(resolve(CLERK, null, other, Map.of("product_id", pen, "qty", "1"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resolve(CLERK, null, null, Map.of("product_id", UUID.randomUUID(), "qty", "1"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // Voiding a sale made at a list price works as before: the stock and the books return.
        JsonNode sale = one(CLERK, c, "10");
        assertThat(api.post("/sales/" + sale.get("id").asString() + "/void", Map.of("reason", "Test void"), MANAGER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
