package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.LocalDate;
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
 * Damaged and used as a share of the stock that passed through (issue #822, ADR-127, FR-RET-92). Fabricated data, the period
 * is today alone. Head office: DSH-1 had 40 delivered two days ago (the opening stock), 10 delivered today and 7 delivered and
 * voided today (base 50); 5 damaged and 2 used today, and 3 more damaged and then undone. DSH-2 was only counted in today (a
 * stock count, so base 0) and 1 was damaged. Branch two: DSH-1 had 20 delivered today and 1 damaged. No real prices or costs.
 */
class RetailDamageShareIT extends IntegrationTest {

    static final String PATH = "/reports/stock-health/damage-share";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID p1;
    UUID p2;
    LocalDate today;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-dmg", false, true);
        api = new RetailTestSupport(http, t);
        today = LocalDate.now(BusinessClock.DEFAULT_ZONE);
        p1 = api.product("DSH-1", 100, 150);
        p2 = api.product("DSH-2", 100, 150);
        restock(t.headOffice(), p1, "40", today.minusDays(2));
        restock(t.headOffice(), p1, "10", today);
        UUID voided = restock(t.headOffice(), p1, "7", today);
        ResponseEntity<JsonNode> v = api.postKeyed(
                "/purchases/" + voided + "/void",
                Map.of("reason", "Typed the wrong delivery"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.OK);
        restock(t.secondBranch(), p1, "20", today);
        usage(t.headOffice(), "damaged", p1, "5");
        usage(t.headOffice(), "used", p1, "2");
        UUID undone = usage(t.headOffice(), "damaged", p1, "3");
        ResponseEntity<JsonNode> u = api.call(
                HttpMethod.POST, "/usage/" + undone + "/undo", Map.of("reason", "Entered twice"), ADMIN, "*", Map.of());
        assertThat(u.getStatusCode()).as("%s", u.getBody()).isEqualTo(HttpStatus.OK);
        api.stockUp(t.headOffice(), p2, "8");
        usage(t.headOffice(), "damaged", p2, "1");
        usage(t.secondBranch(), "damaged", p1, "1");
    }

    UUID restock(UUID branch, UUID product, String qty, LocalDate on) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", 100);
        line.put("qty_by_branch", List.of(Map.of("branch_id", branch, "qty", qty)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", on.toString());
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    /** The body of a usage or damage entry with one line, as every entry here sends it. */
    static Map<String, Object> usageBody(UUID branch, String kind, Map<String, Object> line) {
        return Map.of("branch_id", branch, "kind", kind, "reason", "Test " + kind, "lines", List.of(line));
    }

    UUID usage(UUID branch, String kind, UUID product, String qty) {
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/usage",
                usageBody(branch, kind, Map.of("product_id", product, "qty", qty)),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    String todayOnly() {
        return "?from=" + today + "&to=" + today;
    }

    JsonNode report(String query, String permissions, Map<String, String> extra) {
        ResponseEntity<JsonNode> r = api.call(HttpMethod.GET, PATH + query, null, permissions, "*", extra);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    String firstShop(JsonNode report) {
        return report.get("branches").get(0).get("branch_id").asString();
    }

    JsonNode hqItems() {
        return shop(report(todayOnly(), ADMIN, Map.of()), t.headOffice()).get("items");
    }

    JsonNode shop(JsonNode report, UUID branch) {
        for (JsonNode b : report.get("branches")) {
            if (b.get("branch_id").asString().equals(branch.toString())) {
                return b;
            }
        }
        throw new AssertionError("no row for " + branch + " in " + report);
    }

    @Test
    void theShareIsOutOfTheOpeningStockPlusWhatCameInDamageAndUseApart() {
        JsonNode r = report(todayOnly(), ADMIN, Map.of());
        JsonNode hq = shop(r, t.headOffice());
        JsonNode item = hq.get("items").get(0);
        assertThat(item.get("code").asString()).isEqualTo("DSH-1");
        // 40 before today, 10 in today; the voided 7 and the stock the undo put back are not "came in".
        assertThat(item.get("opening_qty").asString()).isEqualTo("40.000");
        assertThat(item.get("received_qty").asString()).isEqualTo("10.000");
        assertThat(item.get("base_qty").asString()).isEqualTo("50.000");
        // 5 of 50 damaged is 10 %, 2 of 50 used is 4 %; the undone 3 count in neither.
        assertThat(item.get("damaged_qty").asString()).isEqualTo("5.000");
        assertThat(item.get("damaged_pct").asInt()).isEqualTo(10);
        assertThat(item.get("used_qty").asString()).isEqualTo("2.000");
        assertThat(item.get("used_pct").asInt()).isEqualTo(4);

        // The shop total: 6 damaged and 2 used of the 50 that passed through, DSH-2 adding nothing to the base.
        assertThat(hq.get("base_qty").asString()).isEqualTo("50.000");
        assertThat(hq.get("damaged_qty").asString()).isEqualTo("6.000");
        assertThat(hq.get("damaged_pct").asInt()).isEqualTo(12);
        assertThat(hq.get("used_pct").asInt()).isEqualTo(4);

        // Branch two: 1 of 20 is 5 %, so head office comes first.
        JsonNode two = shop(r, t.secondBranch());
        assertThat(two.get("damaged_pct").asInt()).isEqualTo(5);
        assertThat(two.get("used_pct").asInt()).isZero();
        assertThat(firstShop(r)).isEqualTo(t.headOffice().toString());
        assertThat(r.toString()).doesNotContain("cost").doesNotContain("price").doesNotContain("margin");
    }

    @Test
    void aZeroBaseShowsNoShare() {
        JsonNode items = hqItems();
        assertThat(items).hasSize(2);
        JsonNode counted = items.get(1);
        assertThat(counted.get("code").asString()).as("no share comes last").isEqualTo("DSH-2");
        assertThat(counted.get("base_qty").asString()).isEqualTo("0.000");
        assertThat(counted.get("damaged_qty").asString()).isEqualTo("1.000");
        assertThat(counted.has("damaged_pct")).isFalse();
        assertThat(counted.has("used_pct")).isFalse();
    }

    @Test
    void undoneEntriesAreLeftOut() {
        JsonNode item = hqItems().get(0);
        assertThat(item.get("damaged_qty").asString()).isEqualTo("5.000");
        // The day the 40 came in, the opening was nothing and the 40 came in; nothing was damaged then.
        LocalDate day = today.minusDays(2);
        JsonNode earlier = report("?from=" + day + "&to=" + day, ADMIN, Map.of());
        List<String> codes = new ArrayList<>();
        for (JsonNode b : earlier.get("branches")) {
            b.get("items").forEach(i -> codes.add(i.get("code").asString()));
        }
        assertThat(codes).isEmpty();
        assertThat(shop(earlier, t.headOffice()).get("base_qty").asString()).isEqualTo("40.000");
    }

    @Test
    void thisMonthByDefault() {
        JsonNode r = report("", ADMIN, Map.of());
        assertThat(r.get("from").asString()).isEqualTo(today.withDayOfMonth(1).toString());
        assertThat(r.get("to").asString()).isEqualTo(today.toString());
    }

    @Test
    void onlyTheShopsWhereTheCallerMaySeeProfit() {
        Map<String, String> hqOnly = Map.of("X-Dev-Scopes", "retail.profit.read=" + t.headOffice());
        JsonNode r = report(todayOnly(), "retail.stock.read", hqOnly);
        assertThat(r.get("branches")).hasSize(1);
        assertThat(firstShop(r)).isEqualTo(t.headOffice().toString());
        String two = PATH + todayOnly() + "&branch_id=" + t.secondBranch();
        String reader = "retail.stock.read";
        ResponseEntity<JsonNode> other = api.call(HttpMethod.GET, two, null, reader, "*", hqOnly);
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(other.getBody().get("code").asString()).isEqualTo("unknown_branch");
        JsonNode asked = report(todayOnly() + "&branch_id=" + t.secondBranch(), ADMIN, Map.of());
        assertThat(asked.get("branches")).hasSize(1);
    }

    @Test
    void anotherBusinessesShopIs404() {
        TestDatabase.Fixture other = TestDatabase.tenant("retail-dmg2", false, true);
        String path = PATH + todayOnly() + "&branch_id=" + other.headOffice();
        ResponseEntity<JsonNode> r = api.get(path, ADMIN);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().toString()).doesNotContain("DSH-");
        // And the other business sees none of this one's figures.
        RetailTestSupport otherApi = new RetailTestSupport(http, other);
        String theirs = otherApi.get(PATH + todayOnly(), ADMIN).getBody().toString();
        assertThat(theirs).doesNotContain("DSH-").doesNotContain(t.headOffice().toString());
    }

    @Test
    void aPersonWithoutTheProfitPermissionGetsNothing() {
        String noProfit = "retail.stock.read,retail.usage.report,retail.sale.all_sellers";
        for (String permissions : List.of(SALES, noProfit)) {
            ResponseEntity<JsonNode> r = api.get(PATH + todayOnly(), permissions);
            assertThat(r.getStatusCode()).as(permissions).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.getBody().has("branches")).isFalse();
            assertThat(r.getBody().toString()).doesNotContain("DSH-");
        }
        // Stock health itself stays open to them, without the share.
        ResponseEntity<JsonNode> health = api.get("/reports/stock-health", SALES);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody().toString()).doesNotContain("damaged_pct");
    }

    // ---- issue #854: the base definition, each kind of "came in" and what is left out ----
    // JUSTIFICATION-A3: #854 item 2 lists these cases; they reuse this class's restock, usage and report helpers.

    /** A stock movement of {@code kind} written as the owner, as an import or a first starting stock writes it, and its balance. */
    void movement(UUID branch, UUID product, String kind, String qty, LocalDate on) {
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_stock_movements (id, tenant_id, occurred_at, business_date, branch_id, product_id, kind, qty,
                            unit_cost_minor, source_type, historical)
                        VALUES (gen_random_uuid(), ?, now(), ?, ?, ?, ?, CAST(? AS numeric), 0, 'retail.import', true)
                        """)
                .params(t.tenantId(), on, branch, product, kind, qty)
                .update();
        TestDatabase.owner().sql("""
                        INSERT INTO retail_stock_balances (tenant_id, branch_id, product_id, qty, avg_cost_minor)
                        SELECT ?, ?, p.id, CAST(? AS numeric), p.cost_minor FROM retail_products p WHERE p.id = ?
                        ON CONFLICT (tenant_id, branch_id, product_id)
                        DO UPDATE SET qty = retail_stock_balances.qty + EXCLUDED.qty
                        """).params(t.tenantId(), branch, qty, product).update();
    }

    JsonNode item(JsonNode shop, String code) {
        for (JsonNode i : shop.get("items")) {
            if (i.get("code").asString().equals(code)) {
                return i;
            }
        }
        throw new AssertionError("no " + code + " in " + shop);
    }

    @Test
    void stockMovedInCountsAndAVoidedMoveDoesNot() {
        UUID p = api.product("DSH-3", 100, 150);
        restock(t.headOffice(), p, "30", today.minusDays(2));
        Map<String, Object> line = Map.of("product_id", p, "qty", "12");
        Map<String, Object> move =
                Map.of("from_branch_id", t.headOffice(), "to_branch_id", t.secondBranch(), "lines", List.of(line));
        ResponseEntity<JsonNode> moved =
                api.postKeyed("/transfers", move, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(moved.getStatusCode()).as("%s", moved.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> wrong = Map.of(
                "from_branch_id",
                t.headOffice(),
                "to_branch_id",
                t.secondBranch(),
                "lines",
                List.of(Map.of("product_id", p, "qty", "5")));
        UUID voided = RetailTestSupport.id(
                api.postKeyed("/transfers", wrong, ADMIN, "*", UUID.randomUUID().toString()));
        ResponseEntity<JsonNode> v =
                api.post("/transfers/" + voided + "/void", Map.of("reason", "Test wrong branch"), ADMIN);
        assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.OK);
        usage(t.secondBranch(), "damaged", p, "1");

        JsonNode moved3 = item(shop(report(todayOnly(), ADMIN, Map.of()), t.secondBranch()), "DSH-3");
        // The 12 moved in came in; the 5 moved in and voided the same day did not.
        assertThat(moved3.get("opening_qty").asString()).isEqualTo("0.000");
        assertThat(moved3.get("received_qty").asString()).isEqualTo("12.000");
        assertThat(moved3.get("base_qty").asString()).isEqualTo("12.000");
        assertThat(moved3.get("damaged_pct").asInt()).isEqualTo(8);
    }

    @Test
    void aStartingStockSetInThePeriodCameIn() {
        UUID p = api.product("DSH-4", 100, 150);
        movement(t.headOffice(), p, "opening", "6", today);
        movement(t.headOffice(), p, "legacy_balance", "4", today);
        usage(t.headOffice(), "damaged", p, "1");

        JsonNode started = item(shop(report(todayOnly(), ADMIN, Map.of()), t.headOffice()), "DSH-4");
        assertThat(started.get("opening_qty").asString()).isEqualTo("0.000");
        assertThat(started.get("received_qty").asString()).isEqualTo("10.000");
        assertThat(started.get("damaged_pct").asInt()).isEqualTo(10);
    }

    @Test
    void aNegativeOpeningCountsAsNothing() {
        UUID p = api.product("DSH-5", 100, 150);
        movement(t.headOffice(), p, "legacy_balance", "-9", today.minusDays(2));
        restock(t.headOffice(), p, "20", today);
        usage(t.headOffice(), "damaged", p, "2");

        JsonNode floored = item(shop(report(todayOnly(), ADMIN, Map.of()), t.headOffice()), "DSH-5");
        // Below zero at the start is no stock, so the base is the 20 that came in, not 11.
        assertThat(floored.get("opening_qty").asString()).isEqualTo("0.000");
        assertThat(floored.get("base_qty").asString()).isEqualTo("20.000");
        assertThat(floored.get("damaged_pct").asInt()).isEqualTo(10);
    }

    @Test
    void aPeriodOfMoreThan366DaysIsRefused() {
        String tooLong = PATH + "?from=" + today.minusDays(366) + "&to=" + today;
        ResponseEntity<JsonNode> r = api.call(HttpMethod.GET, tooLong, null, ADMIN, "*", Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("validation_failed");
        assertThat(r.getBody().toString()).doesNotContain("DSH-");
        // 366 days, today included, is the longest period.
        assertThat(report("?from=" + today.minusDays(365) + "&to=" + today, ADMIN, Map.of())
                        .get("from")
                        .asString())
                .isEqualTo(today.minusDays(365).toString());
    }

    @Test
    void aPackDeliveryAndAUsageLineCountInTheItemsSmallestUnit() {
        // Pack sizes are switched on for this test's own business: setUp makes a new one for every test, and units can
        // never be switched off again (BusinessTypes.NEVER_SWITCHED_OFF), so no other test sees it.
        String etag = api.get("/settings/capabilities", "core.settings.manage")
                .getHeaders()
                .getETag();
        ResponseEntity<JsonNode> on = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of("units", true),
                "core.settings.manage",
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
        UUID p = api.product("DSH-6", 100, 150);
        UUID carton = api.unitOrExisting("carton");
        ResponseEntity<JsonNode> pack =
                api.post("/products/" + p + "/units", Map.of("unit_id", carton, "factor", "24"), ADMIN);
        assertThat(pack.getStatusCode()).as("%s", pack.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", p);
        line.put("cost_minor", 100);
        line.put("unit_id", carton);
        line.put("qty_by_branch", List.of(Map.of("branch_id", t.headOffice(), "qty", "2")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("purchased_on", today.toString());
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/purchases", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID used = usage(t.headOffice(), "damaged", p, "6");
        // A usage line takes no unit (RetailUsageLineRequest is an item and a quantity in its smallest unit), so the
        // "pack usage" guard is that the line and its stock movement hold the same smallest-unit quantity, which the
        // share reads from the line.
        assertThat(TestDatabase.owner()
                        .sql("SELECT l.qty::text || '|' || (-m.qty)::text FROM retail_usage_lines l"
                                + " JOIN retail_stock_movements m ON m.tenant_id = l.tenant_id"
                                + " AND m.source_type = 'retail.usage' AND m.source_line_id = l.id"
                                + " WHERE l.tenant_id = ? AND l.report_id = ?")
                        .params(t.tenantId(), used)
                        .query(String.class)
                        .single())
                .isEqualTo("6.000|6.000");
        // A line carrying the carton as unit_id is refused for that field alone: the API refuses an unknown field
        // (400 malformed_request), so a carton line is never counted as 24, nor stored at all. The same body without
        // unit_id is accepted and counts as the 1 piece typed. If usage lines ever take a pack unit, the refusal fails
        // and this test must be changed on purpose (#860, the lead's sign-off).
        // JUSTIFICATION-A3: the #860 review asks this refusal to be proven against the same body accepted.
        // First the body without unit_id (the shape usage() sends): accepted, 201, as the 1 piece typed.
        usage(t.headOffice(), "damaged", p, "1");
        String written = "SELECT (SELECT count(*) FROM retail_usage_lines WHERE tenant_id = ? AND product_id = ?)"
                + " || '|' || (SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ? AND product_id = ?"
                + " AND source_type = 'retail.usage')";
        assertThat(TestDatabase.owner()
                        .sql(written)
                        .params(t.tenantId(), p, t.tenantId(), p)
                        .query(String.class)
                        .single())
                .isEqualTo("2|2");
        // Then the same body with the carton as unit_id: refused for that field alone, with nothing written.
        Map<String, Object> withUnit = new LinkedHashMap<>(Map.of("product_id", p, "qty", "1"));
        withUnit.put("unit_id", carton);
        ResponseEntity<JsonNode> refused = api.postKeyed(
                "/usage",
                usageBody(t.headOffice(), "damaged", withUnit),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("malformed_request");
        assertThat(TestDatabase.owner()
                        .sql(written)
                        .params(t.tenantId(), p, t.tenantId(), p)
                        .query(String.class)
                        .single())
                .as("no usage line and no stock movement from the refused line")
                .isEqualTo("2|2");

        JsonNode packed = item(shop(report(todayOnly(), ADMIN, Map.of()), t.headOffice()), "DSH-6");
        // Two cartons of 24 are 48 pieces; 6 + 1 pieces of 48 is 14.6 %, a whole 15 half up; never 6 + 24.
        assertThat(packed.get("received_qty").asString()).isEqualTo("48.000");
        assertThat(packed.get("damaged_qty").asString()).isEqualTo("7.000");
        assertThat(packed.get("damaged_pct").asInt()).isEqualTo(15);
    }

    /** Issue #854 item 1: a void dated after the period does not change it; one inside the period does. */
    @Test
    void aDeliveryVoidedAfterThePeriodStillCameInDuringIt() {
        LocalDate yesterday = today.minusDays(1);
        UUID p = api.product("DSH-7", 100, 150);
        restock(t.headOffice(), p, "10", yesterday);
        UUID late = restock(t.headOffice(), p, "15", yesterday);
        ResponseEntity<JsonNode> v = api.postKeyed(
                "/purchases/" + late + "/void",
                Map.of("reason", "Typed the wrong delivery"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.OK);

        // Where 65 and 60 come from: setUp gives head office DSH-1's 40 delivered two days ago, DSH-1's 10 delivered
        // today and 7 delivered and voided today, and DSH-2 counted in today (a count, never "came in"). The shop total
        // adds every item that passed through the shop, listed or not (ADR-127 decision 4), so DSH-7, neither damaged
        // nor used, counts in it.
        // Yesterday alone: DSH-1's 40 at the start, and both of DSH-7's deliveries of yesterday (10 and 15), the one
        // voided today included, because on yesterday the books held it: 40 + 25 = 65.
        JsonNode closed = shop(report("?from=" + yesterday + "&to=" + yesterday, ADMIN, Map.of()), t.headOffice());
        assertThat(closed.get("base_qty").asString()).isEqualTo("65.000");
        // Yesterday and today: the void is inside the period, so the 15 never came in.
        JsonNode open = report("?from=" + yesterday + "&to=" + today, ADMIN, Map.of());
        assertThat(item(shop(open, t.headOffice()), "DSH-1").get("received_qty").asString())
                .isEqualTo("10.000");
        JsonNode hq = shop(open, t.headOffice());
        // 40 at the start; came in: DSH-1's 10 today and DSH-7's 10 yesterday (the voided 7 and 15 left out): 60.
        assertThat(hq.get("base_qty").asString()).isEqualTo("60.000");
    }
}
