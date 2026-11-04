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

    UUID usage(UUID branch, String kind, UUID product, String qty) {
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/usage",
                Map.of(
                        "branch_id",
                        branch,
                        "kind",
                        kind,
                        "reason",
                        "Test " + kind,
                        "lines",
                        List.of(Map.of("product_id", product, "qty", qty))),
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
}
