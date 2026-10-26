package com.rincoltech.bms.retail.catalogue.internal;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
 * The entry warnings (issue #353, ADR-093): an item's median quantity, usual unit and last selling price over its newest
 * purchases, sales and moves, voided ones left out; the thresholds the screens use; the "did you mean" and category hints
 * while a new item is named; the record of a confirmed warning; that nothing here ever refuses a save; and that each read
 * walks the item's own index entries, never the business's whole line history. Every name and figure is fabricated.
 */
class EntryChecksIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    /** A storekeeper (ADR-055): reads stock, sees no prices. */
    static final String STOREKEEPER = "retail.stock.read,retail.stock.transfer,retail.stocktake.commit";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID cable;
    UUID roll;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-entry", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        capability("units", true);
        cable = api.product("EC-CABLE", 30, 60);
        roll = api.unit("roll");
        ResponseEntity<JsonNode> pack =
                api.post("/products/" + cable + "/units", Map.of("unit_id", roll, "factor", "100"), ADMIN);
        assertThat(pack.getStatusCode()).as("%s", pack.getBody()).isEqualTo(HttpStatus.CREATED);
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

    /** Buys {@code qty} of the cable at the head office, in rolls when {@code unit} is given; the purchase id. */
    UUID buy(UUID unit, String qty, long cost) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", cable);
        line.put("cost_minor", cost);
        if (unit != null) {
            line.put("unit_id", unit);
        }
        line.put("qty_by_branch", List.of(Map.of("branch_id", hq, "qty", qty)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "purchased_on",
                java.time.LocalDate.now(java.time.ZoneId.of("Africa/Kampala")).toString());
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    /** Sells {@code qty} metres at {@code price} each; the sale id. */
    UUID sell(String qty, long price) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("payment_method", "cash");
        body.put("lines", List.of(Map.of("product_id", cable, "qty", qty, "unit_price_minor", price)));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    JsonNode norms(String perms) {
        ResponseEntity<JsonNode> r = api.get("/products/" + cable + "/entry-norms", perms);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode hints(String typed) {
        ResponseEntity<JsonNode> r =
                api.call(HttpMethod.GET, "/products/name-hints?description=" + typed, null, ADMIN, "*", Map.of());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static List<String> descriptions(JsonNode hints) {
        List<String> out = new ArrayList<>();
        hints.get("did_you_mean").forEach(h -> out.add(h.get("description").asString()));
        return out;
    }

    UUID item(String name, UUID category) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("description", name);
        body.put("category_id", category);
        body.put("unit_id", api.unit("u-" + UUID.randomUUID().toString().substring(0, 8)));
        body.put("cost_minor", 100);
        body.put("sell_minor", 200);
        return RetailTestSupport.id(api.post("/products", body, ADMIN));
    }

    // ---- the maths ---------------------------------------------------------------------------

    @Test
    void theMedianIsTheMiddleLineAndTheMeanOfTheTwoMiddleOnesForAnEvenCount() {
        assertThat(EntryChecksService.median(List.of(n("5"), n("1"), n("3")))).isEqualByComparingTo("3");
        assertThat(EntryChecksService.median(List.of(n("200"), n("300"), n("500"), n("400"))))
                .isEqualByComparingTo("350");
        assertThat(EntryChecksService.median(List.of(n("0.001"), n("0.002")))).isEqualByComparingTo("0.002");
        // One wild line does not move the median, which is why a median and not a mean.
        assertThat(EntryChecksService.median(List.of(n("2"), n("3"), n("2"), n("9999"), n("4"))))
                .isEqualByComparingTo("3");
    }

    @Test
    void theUsualUnitNeedsMoreThanHalfTheLines() {
        UUID box = UUID.randomUUID();
        var inRolls = List.of(line("100", roll, "roll"), line("50", null, null), line("200", roll, "roll"));
        assertThat(EntryChecksService.usualUnit(inRolls).unitId()).isEqualTo(roll);
        var split = List.of(
                line("100", roll, "roll"), line("50", null, null), line("12", box, "box"), line("1", null, null));
        assertThat(EntryChecksService.usualUnit(split)).isNull();
        var under = EntryChecksService.usage(List.of(line("100", roll, "roll"), line("100", roll, "roll")), "metre");
        assertThat(under.linesRead()).isEqualTo(2);
        assertThat(under.medianQty()).isNull();
        assertThat(under.usualUnit()).isNull();
        var base = EntryChecksService.usage(
                List.of(line("2", null, null), line("3", null, null), line("4", roll, "roll")), "metre");
        assertThat(base.usualUnitId()).isNull();
        assertThat(base.usualUnit()).isEqualTo("metre");
        assertThat(base.medianQty()).isEqualTo("3");
        assertThat(EntryChecksService.perBaseUnit(1_000, n("3"))).isEqualTo(333);
        assertThat(EntryChecksService.perBaseUnit(1_000, n("6"))).isEqualTo(167);
    }

    static java.math.BigDecimal n(String v) {
        return new java.math.BigDecimal(v);
    }

    static EntryChecksRepository.LineQty line(String qty, UUID unit, String name) {
        return new EntryChecksRepository.LineQty(n(qty), unit, name);
    }

    // ---- the norms over the API --------------------------------------------------------------

    @Test
    void anItemBoughtInRollsAndSoldInMetresGivesItsMediansUsualUnitsAndLastPrice() {
        JsonNode empty = norms(ADMIN);
        assertThat(empty.get("unit").asString()).isEqualTo("u-ec-cable");
        assertThat(empty.get("buy").get("lines_read").asInt()).isZero();
        assertThat(empty.get("buy").has("median_qty")).isFalse();
        assertThat(empty.has("last_sell_minor")).isFalse();
        assertThat(empty.get("quantity_factor").asInt()).isEqualTo(10);
        assertThat(empty.get("price_factor").asInt()).isEqualTo(2);
        assertThat(empty.get("min_lines").asInt()).isEqualTo(3);

        buy(roll, "2", 3_000);
        buy(roll, "3", 3_000);
        buy(roll, "5", 3_000);
        buy(roll, "4", 3_000);
        // A delivery of 9,999 metres typed by mistake and voided is not history.
        UUID wrong = buy(null, "9999", 30);
        ResponseEntity<JsonNode> voided = api.postKeyed(
                "/purchases/" + wrong + "/void",
                Map.of("reason", "Typed metres for rolls"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);

        sell("1", 60);
        sell("2", 60);
        sell("3", 70);
        UUID mistake = sell("900", 60);
        assertThat(api.post("/sales/" + mistake + "/void", Map.of("reason", "Wrong quantity"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode n = norms(ADMIN);
        assertThat(n.get("buy").get("lines_read").asInt()).isEqualTo(4);
        assertThat(n.get("buy").get("median_qty").asString()).isEqualTo("350");
        assertThat(n.get("buy").get("usual_unit_id").asString()).isEqualTo(roll.toString());
        assertThat(n.get("buy").get("usual_unit").asString()).isEqualTo("roll");
        assertThat(n.get("sell").get("lines_read").asInt()).isEqualTo(3);
        assertThat(n.get("sell").get("median_qty").asString()).isEqualTo("2");
        assertThat(n.get("sell").has("usual_unit_id")).isFalse();
        assertThat(n.get("sell").get("usual_unit").asString()).isEqualTo("u-ec-cable");
        // The last sale standing: 3 metres at 70.
        assertThat(n.get("last_sell_minor").asLong()).isEqualTo(70);
        assertThat(n.has("last_sold_on")).isTrue();
        assertThat(n.get("move").get("lines_read").asInt()).isZero();

        // A storekeeper sees the quantities, never a price (ADR-055).
        JsonNode kept = norms(STOREKEEPER);
        assertThat(kept.get("buy").get("median_qty").asString()).isEqualTo("350");
        assertThat(kept.has("last_sell_minor")).isFalse();
        assertThat(kept.has("last_sold_on")).isFalse();
    }

    /** Review of #590: a seller (the sales role) reads the norms and the last price, so Sell warns for the people who sell. */
    @Test
    void aSellerReadsTheNormsWithTheLastPrice() {
        buy(roll, "10", 3_000);
        sell("2", 60);
        JsonNode n = norms(SALES);
        assertThat(n.get("sell").get("lines_read").asInt()).isEqualTo(1);
        assertThat(n.get("last_sell_minor").asLong()).isEqualTo(60);
    }

    /** Review of #590, ADR-055: a seller who sees only their own figures gets the last price of their own sales. */
    @Test
    void aSellerLimitedToTheirOwnFiguresGetsTheirOwnLastPrice() {
        buy(roll, "10", 3_000);
        RetailTestSupport mine = new RetailTestSupport(http, t);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("payment_method", "cash");
        body.put("lines", List.of(Map.of("product_id", cable, "qty", "1", "unit_price_minor", 65)));
        assertThat(mine.postKeyed(
                                "/sales",
                                body,
                                SALES + ",retail.price.override",
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        // Another seller sells later, at 70.
        sell("1", 70);
        JsonNode own = mine.get("/products/" + cable + "/entry-norms", SALES).getBody();
        assertThat(own.get("last_sell_minor").asLong()).isEqualTo(65);
        // Someone who oversees every seller gets the last sale of all.
        assertThat(norms(ADMIN).get("last_sell_minor").asLong()).isEqualTo(70);
        // A seller with no sale of the item gets none.
        JsonNode none = new RetailTestSupport(http, t)
                .get("/products/" + cable + "/entry-norms", SALES)
                .getBody();
        assertThat(none.has("last_sell_minor")).isFalse();
    }

    /** Review of #590: a sale in a pack counts in base units (V39 keeps qty = packs times the factor), and its unit is the pack's. */
    @Test
    void aSaleInAPackCountsInBaseUnitsAndNamesThePack() {
        buy(roll, "10", 3_000);
        for (String rolls : List.of("1", "2", "1")) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("branch_id", hq);
            body.put("payment_method", "cash");
            body.put(
                    "lines",
                    List.of(Map.of("product_id", cable, "unit_id", roll, "qty", rolls, "unit_price_minor", 6_000)));
            ResponseEntity<JsonNode> r =
                    api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        }
        JsonNode sell = norms(ADMIN).get("sell");
        assertThat(sell.get("median_qty").asString()).isEqualTo("100");
        assertThat(sell.get("usual_unit_id").asString()).isEqualTo(roll.toString());
        assertThat(sell.get("usual_unit").asString()).isEqualTo("roll");
        // 6,000 for 100 metres is 60 a metre.
        assertThat(norms(ADMIN).get("last_sell_minor").asLong()).isEqualTo(60);
    }

    @Test
    void movesCountCompletedOnesOnly() {
        buy(roll, "10", 3_000);
        for (String qty : List.of("100", "150", "50")) {
            Map<String, Object> move = new LinkedHashMap<>();
            move.put("from_branch_id", hq);
            move.put("to_branch_id", t.secondBranch());
            move.put("lines", List.of(Map.of("product_id", cable, "qty", qty)));
            assertThat(api.postKeyed(
                                    "/transfers",
                                    move,
                                    ADMIN,
                                    "*",
                                    UUID.randomUUID().toString())
                            .getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
        JsonNode n = norms(ADMIN);
        assertThat(n.get("move").get("lines_read").asInt()).isEqualTo(3);
        assertThat(n.get("move").get("median_qty").asString()).isEqualTo("100");
    }

    @Test
    void anUnknownItemIsNotFoundAndTheRoutesNeedTheirPermissions() {
        assertThat(api.get("/products/" + UUID.randomUUID() + "/entry-norms", ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.get("/products/" + cable + "/entry-norms", "retail.sale.read")
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/products/name-hints?description=cable", SALES).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** A warning is only a warning: a sale a hundred times the median and far below the last price is still saved. */
    @Test
    void nothingIsEverRefusedForBeingUnusual() {
        buy(roll, "50", 3_000);
        sell("1", 60);
        sell("1", 60);
        sell("2", 60);
        sell("200", 45);
        buy(null, "3", 30);
        assertThat(norms(ADMIN).get("sell").get("lines_read").asInt()).isEqualTo(4);
    }

    // ---- the confirmation ---------------------------------------------------------------------

    @Test
    void aConfirmedWarningIsRecordedInTheAuditLog() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("product_id", cable);
        body.put("branch_id", hq);
        body.put("screen", "buy");
        body.put("kind", "unit");
        body.put("entered_qty", "200");
        body.put("median_qty", "350");
        ResponseEntity<JsonNode> r = api.post("/entry-warnings/confirmations", body, STOREKEEPER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> row =
                TestDatabase.owner().sql("""
                        SELECT entity_id, branch_id, data -> 'after' ->> 'kind' AS kind, data -> 'after' ->> 'screen' AS screen,
                               data -> 'after' ->> 'entered_qty' AS qty,
                               (data -> 'after' -> 'message') IS NOT NULL AS has_text
                          FROM audit_log WHERE tenant_id = ? AND action = 'retail.entry_warning.confirmed'
                        """).params(t.tenantId()).query().singleRow();
        assertThat(row.get("entity_id")).isEqualTo(cable);
        assertThat(row.get("branch_id")).isEqualTo(hq);
        assertThat(row)
                .containsEntry("kind", "unit")
                .containsEntry("screen", "buy")
                .containsEntry("qty", "200");
        // The warning's kind and its numbers only: no text from the client is stored.
        assertThat(row).containsEntry("has_text", false);
        Map<String, Object> words = new LinkedHashMap<>(body);
        words.put("entered_qty", "two hundred, says the boss");
        assertThat(api.post("/entry-warnings/confirmations", words, STOREKEEPER)
                        .getStatusCode()
                        .is4xxClientError())
                .isTrue();

        body.put("kind", "mood");
        assertThat(api.post("/entry-warnings/confirmations", body, STOREKEEPER)
                        .getStatusCode()
                        .is4xxClientError())
                .isTrue();
        body.put("kind", "price");
        body.put("product_id", UUID.randomUUID());
        assertThat(api.post("/entry-warnings/confirmations", body, STOREKEEPER).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- the name hints -----------------------------------------------------------------------

    @Test
    void didYouMeanIgnoresCaseAndSpacingAndCatchesATypo() {
        UUID cables = api.category("Cables and wires");
        UUID lighting = api.category("Lighting");
        item("Single Cable 2.5 mm", cables);
        item("Single cable 1.5mm", cables);
        item("Twin and earth cable 4 mm", cables);
        item("LED bulb 9W screw", lighting);

        assertThat(descriptions(hints("SINGLECABLE"))).containsExactly("Single cable 1.5mm", "Single Cable 2.5 mm");
        // The same name with other spacing comes first; a name one letter apart follows it.
        assertThat(descriptions(hints("single  cable 2.5mm")))
                .containsExactly("Single Cable 2.5 mm", "Single cable 1.5mm");
        assertThat(descriptions(hints("Singel Cable 2.5 mm"))).containsExactly("Single Cable 2.5 mm");
        assertThat(descriptions(hints("led bulb"))).containsExactly("LED bulb 9W screw");
        assertThat(hints("le").get("did_you_mean")).isEmpty();
        // Only the first 200 characters are read: what follows them changes nothing.
        String first200 = "LED bulb" + " ".repeat(192);
        assertThat(first200).hasSize(200);
        assertThat(descriptions(hints(first200 + "z".repeat(100)))).containsExactly("LED bulb 9W screw");
        assertThat(hints("  ").get("did_you_mean")).isEmpty();
        JsonNode first = hints("SINGLECABLE").get("did_you_mean").get(0);
        assertThat(first.get("category").asString()).isEqualTo("Cables and wires");
        assertThat(first.get("code").asString()).isNotBlank();
    }

    @Test
    void theCategoryOfSimilarItemsIsSuggested() {
        UUID cables = api.category("Cables and wires");
        UUID lighting = api.category("Lighting");
        UUID tools = api.category("Tools");
        item("Single Cable 2.5 mm", cables);
        item("Flex cable 3 core", cables);
        item("LED bulb 9W screw", lighting);
        item("LED tube 4 foot", lighting);
        item("Cable cutter", tools);

        JsonNode c = hints("Armoured cable 16mm").get("category");
        assertThat(c.get("id").asString()).isEqualTo(cables.toString());
        assertThat(c.get("name").asString()).isEqualTo("Cables and wires");
        assertThat(c.get("items_like").asInt()).isEqualTo(2);
        // The most shared words win: "LED bulb" shares two words with one item and one with another.
        JsonNode bulb = hints("LED bulb 12W").get("category");
        assertThat(bulb.get("name").asString()).isEqualTo("Lighting");
        assertThat(bulb.get("example").asString()).isEqualTo("LED bulb 9W screw");
        assertThat(hints("Padlock 40mm").has("category")).isFalse();
    }

    // ---- bounded reads ------------------------------------------------------------------------

    /**
     * At the size of a busy shop (3,000 sale, purchase and move lines of other items, 300 of each of this one), each read of
     * an item's history goes through the V104 index on the product and stops at its limit; no line table is scanned whole.
     * Read as {@code bms_app} under row-level security, as the service reads it.
     */
    @Test
    void eachReadWalksTheItemsOwnIndexEntries() throws Exception {
        UUID other = api.product("EC-OTHER", 30, 60);
        var owner = TestDatabase.owner();
        owner.sql("""
                        INSERT INTO retail_purchases (id, tenant_id, purchase_no, purchased_on, payment_method, currency,
                                                      total_minor, created_by)
                        SELECT gen_random_uuid(), ?, 'P' || lpad(n::text, 6, '0'), DATE '2026-01-01' + (n % 250), 'cash',
                               'UGX', 300, ?
                          FROM generate_series(1, 3300) n
                        """).params(t.tenantId(), api.userId()).update();
        owner.sql("""
                        INSERT INTO retail_purchase_lines (id, tenant_id, purchase_id, line_no, product_id, cost_minor,
                                                           sell_minor, qty_total, line_total_minor, created_at)
                        SELECT gen_random_uuid(), pu.tenant_id, pu.id, 1,
                               CASE WHEN row_number() OVER (ORDER BY pu.id) <= 300 THEN ?::uuid ELSE ?::uuid END,
                               30, 60, 10, 300, now() - (random() * interval '300 days')
                          FROM retail_purchases pu
                         WHERE pu.tenant_id = ?
                        """).params(cable, other, t.tenantId()).update();
        owner.sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, currency,
                                                  total_minor, cost_total_minor, paid_minor, status, created_by)
                        SELECT gen_random_uuid(), ?, ?, 'S' || lpad(n::text, 6, '0'), DATE '2026-01-01' + (n % 250), 'cash',
                               'UGX', 60, 30, 60, 'completed', ?
                          FROM generate_series(1, 3300) n
                        """).params(t.tenantId(), hq, api.userId()).update();
        owner.sql("""
                        INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor,
                                                       unit_cost_minor, line_total_minor, line_cost_minor, created_at)
                        SELECT gen_random_uuid(), s.tenant_id, s.id, 1,
                               CASE WHEN row_number() OVER (ORDER BY s.id) <= 300 THEN ?::uuid ELSE ?::uuid END,
                               1, 60, 30, 60, 30, now() - (random() * interval '300 days')
                          FROM retail_sales s
                         WHERE s.tenant_id = ?
                        """).params(cable, other, t.tenantId()).update();
        owner.sql("""
                        INSERT INTO retail_transfers (id, tenant_id, from_branch_id, to_branch_id, transfer_date, currency,
                                                      cost_total_minor, status, created_by)
                        SELECT gen_random_uuid(), ?, ?, ?, DATE '2026-01-01' + (n % 250), 'UGX', 300, 'completed', ?
                          FROM generate_series(1, 3300) n
                        """).params(t.tenantId(), hq, t.secondBranch(), api.userId()).update();
        owner.sql("""
                        INSERT INTO retail_transfer_lines (id, tenant_id, transfer_id, line_no, product_id, qty, unit_cost_minor,
                                                           line_cost_minor, created_at)
                        SELECT gen_random_uuid(), tr.tenant_id, tr.id, 1,
                               CASE WHEN row_number() OVER (ORDER BY tr.id) <= 300 THEN ?::uuid ELSE ?::uuid END,
                               4, 30, 120, now() - (random() * interval '300 days')
                          FROM retail_transfers tr
                         WHERE tr.tenant_id = ?
                        """).params(cable, other, t.tenantId()).update();
        owner.sql("ANALYZE retail_purchases, retail_purchase_lines, retail_sales, retail_sale_lines, retail_transfers,"
                        + " retail_transfer_lines")
                .update();

        // Each read is the newest 20 entries of the item's own index, in the order asked for: a Limit over an index
        // walk,
        // with no sort of the item's whole history and no scan of the line table.
        for (var read : List.of(
                List.of(EntryChecksRepository.PURCHASES, "retail_purchase_lines"),
                List.of(EntryChecksRepository.SALES, "retail_sale_lines"),
                List.of(EntryChecksRepository.MOVES, "retail_transfer_lines"))) {
            String plan = plan(read.get(0));
            assertThat(plan)
                    .as(plan)
                    .startsWith("Limit")
                    .contains(read.get(1) + "_product_recent")
                    .doesNotContain("Sort")
                    .doesNotContain("Seq Scan on " + read.get(1));
        }

        // The last price: for everyone, the newest line; for a seller who sees only their own figures, the newest 200
        // lines of
        // the item, of which only those few are sorted. Each is a walk of the item's index under an inner Limit, and
        // any Sort is
        // above that Limit, never over the item's whole history.
        for (Map<String, String> who : List.of(
                Map.of("window", "1", "seller", "NULL"), Map.of("window", "200", "seller", "'" + api.userId() + "'"))) {
            String plan = plan(EntryChecksRepository.LAST_SALE, who);
            List<String> lines = List.of(plan.split("\n"));
            List<Integer> limits = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("Limit")) {
                    limits.add(i);
                }
            }
            assertThat(limits).as(plan).hasSize(2);
            assertThat(String.join("\n", lines.subList(limits.get(1), lines.size())))
                    .as(plan)
                    .contains("retail_sale_lines_product_recent")
                    .doesNotContain("Sort")
                    .doesNotContain("Seq Scan on retail_sale_lines");
        }

        // And the answer is still the item's own newest 20 lines.
        JsonNode n = norms(ADMIN);
        assertThat(n.get("buy").get("lines_read").asInt()).isEqualTo(20);
        assertThat(n.get("buy").get("median_qty").asString()).isEqualTo("10");
        assertThat(n.get("sell").get("lines_read").asInt()).isEqualTo(20);
        assertThat(n.get("sell").get("median_qty").asString()).isEqualTo("1");
        assertThat(n.get("move").get("lines_read").asInt()).isEqualTo(20);
        assertThat(n.get("move").get("median_qty").asString()).isEqualTo("4");
    }

    /** The plan of one statement for the cable, limit 20, as {@code bms_app} bound to the tenant. */
    String plan(String sql) throws Exception {
        return plan(sql, Map.of());
    }

    /** As {@link #plan(String)}, with other parameters written in as literals. */
    String plan(String sql, Map<String, String> more) throws Exception {
        String bound = sql.replace(":p", "'" + cable + "'::uuid").replace(":limit", "20");
        for (Map.Entry<String, String> e : more.entrySet()) {
            bound = bound.replace(":" + e.getKey(), e.getValue());
        }
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                ps.setString(1, t.tenantId().toString());
                ps.execute();
            }
            try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + bound);
                    ResultSet rs = ps.executeQuery()) {
                StringBuilder out = new StringBuilder();
                while (rs.next()) {
                    out.append(rs.getString(1)).append('\n');
                }
                return out.toString();
            } finally {
                c.rollback();
            }
        }
    }
}
