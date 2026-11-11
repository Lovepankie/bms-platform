package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Issue #826 (ADR-122; FR-RET-87): a sale, online or offline, and a usage or damage entry keep where the phone was when it
 * said; nothing is refused without it; the owner reads the distance from the shop in plain words, never the coordinates; a
 * seller reads nothing; one business never sees another's. Every place and figure is fabricated.
 */
@ExtendWith(OutputCaptureExtension.class)
class RetailEntryLocationIT extends IntegrationTest {

    /** The owner: the retail admin column with Business set-up and the new permission. */
    static final String OWNER = ADMIN + ",core.settings.manage,retail.entry_location.read";

    /** A fabricated shop position, and points about 40 m north and about 3.2 km east of it. */
    static final double SHOP_LAT = 0.3136;

    static final double SHOP_LNG = 32.5811;
    static final double NEAR_LAT = 0.31396;
    static final double FAR_LNG = 32.60988;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport owner;
    RetailTestSupport seller;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("entry-location", false, true);
        owner = new RetailTestSupport(http, t);
        seller = new RetailTestSupport(http, t);
        product = owner.product("LOC-1", 1_000, 1_500);
        owner.stockUp(t.headOffice(), product, "20");
    }

    static Map<String, Object> fix(double lat, double lng, double accuracy) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("latitude", lat);
        f.put("longitude", lng);
        f.put("accuracy_m", accuracy);
        return f;
    }

    void setShop() {
        ResponseEntity<JsonNode> set = owner.call(
                HttpMethod.PUT, "/shop-locations/" + t.headOffice(), fix(SHOP_LAT, SHOP_LNG, 8), OWNER, "*", Map.of());
        assertThat(set.getStatusCode()).as("%s", set.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(set.getBody().get("has_location").asBoolean()).isTrue();
        assertThat(set.getBody().toString()).doesNotContain("32.58").doesNotContain("latitude");
    }

    Map<String, Object> sale(Map<String, Object> location) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "cash");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        if (location != null) {
            body.put("location", location);
        }
        return body;
    }

    String sell(Map<String, Object> body) {
        ResponseEntity<JsonNode> r =
                seller.postKeyed("/sales", body, SALES, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().toString()).as("the seller's answer").doesNotContain("location");
        return r.getBody().get("id").asString();
    }

    String use(Map<String, Object> location) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("kind", "damaged");
        body.put("reason", "Test broken seal");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        if (location != null) {
            body.put("location", location);
        }
        ResponseEntity<JsonNode> r =
                seller.postKeyed("/usage", body, SALES, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    JsonNode distance(RetailTestSupport who, String permissions, String source, String id) {
        ResponseEntity<JsonNode> r = who.get("/entry-locations?source=" + source + "&id=" + id, permissions);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().toString()).doesNotContain("latitude").doesNotContain("32.5");
        return r.getBody().get("items");
    }

    @Test
    void aSaleWithALocationShowsTheOwnerHowFarFromTheShop() {
        setShop();
        String id = sell(sale(fix(NEAR_LAT, SHOP_LNG, 12)));
        JsonNode item = distance(owner, OWNER, "sale", id).get(0);
        assertThat(item.get("state").asString()).isEqualTo("located");
        assertThat(item.get("distance_m").asLong()).isEqualTo(40);
        assertThat(item.get("accuracy_m").asLong()).isEqualTo(12);
        assertThat(item.get("words").asString()).isEqualTo("40 m from the shop");
    }

    @Test
    void aSaleWithoutALocationIsRecordedAndReadsNoLocation() {
        setShop();
        String id = sell(sale(null));
        JsonNode item = distance(owner, OWNER, "sale", id).get(0);
        assertThat(item.get("state").asString()).isEqualTo("no_location");
        assertThat(item.get("words").asString()).isEqualTo("no location");
        assertThat(item.has("distance_m")).isFalse();
    }

    @Test
    void aUsageEntryWithAndWithoutALocation() {
        setShop();
        String far = use(fix(SHOP_LAT, FAR_LNG, 30));
        String none = use(null);
        ResponseEntity<JsonNode> r = owner.get("/entry-locations?source=usage&id=" + far + "&id=" + none, OWNER);
        JsonNode items = r.getBody().get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("words").asString()).isEqualTo("3.2 km from the shop");
        assertThat(items.get(0).get("distance_m").asLong()).isEqualTo(3_200);
        assertThat(items.get(1).get("words").asString()).isEqualTo("no location");
        // A sale's id asked for as usage is not that sale.
        assertThat(distance(owner, OWNER, "sale", far).get(0).get("state").asString())
                .isEqualTo("no_location");
    }

    @Test
    void aShopWithNoLocationSaysSo() {
        String id = sell(sale(fix(NEAR_LAT, SHOP_LNG, 12)));
        JsonNode item = distance(owner, OWNER, "sale", id).get(0);
        assertThat(item.get("state").asString()).isEqualTo("shop_not_set");
        assertThat(item.get("words").asString()).isEqualTo("shop location not set");
        assertThat(owner.get("/shop-locations", OWNER)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("has_location")
                        .asBoolean())
                .isFalse();
        setShop();
        assertThat(owner.call(HttpMethod.DELETE, "/shop-locations/" + t.headOffice(), null, OWNER, "*", Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(distance(owner, OWNER, "sale", id).get(0).get("state").asString())
                .isEqualTo("shop_not_set");
    }

    @Test
    void aSellerCannotReadLocationsOrSetAShop() {
        setShop();
        String id = sell(sale(fix(NEAR_LAT, SHOP_LNG, 12)));
        assertThat(seller.get("/entry-locations?source=sale&id=" + id, SALES).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(seller.get("/shop-locations", SALES).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(seller.call(HttpMethod.PUT, "/shop-locations/" + t.headOffice(), fix(1, 1, 1), SALES, "*", Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> read = seller.get("/sales/" + id, SALES);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody().toString()).doesNotContain("location").doesNotContain("0.31396");
    }

    @Test
    void anEntryAtABranchOutsideTheOwnersScopeReadsNoLocation() {
        setShop();
        String id = sell(sale(fix(NEAR_LAT, SHOP_LNG, 12)));
        ResponseEntity<JsonNode> r = owner.call(
                HttpMethod.GET,
                "/entry-locations?source=sale&id=" + id,
                null,
                OWNER,
                t.secondBranch().toString(),
                Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The same answer as no such entry, so the id tells nothing (review of #834).
        assertThat(r.getBody().get("items").get(0).get("state").asString()).isEqualTo("no_location");
        assertThat(r.getBody().get("items").get(0).has("distance_m")).isFalse();
    }

    // JUSTIFICATION-A3: two cases the review of #834 asks for; tests, not a mechanism.
    @Test
    void anIncompleteLocationIsNoLocationAndTheOwnerRoleHoldsTheRead() {
        Map<String, Object> half = new LinkedHashMap<>();
        half.put("latitude", NEAR_LAT);
        half.put("longitude", SHOP_LNG);
        String id = sell(sale(half));
        assertThat(distance(owner, OWNER, "sale", id).get(0).get("state").asString())
                .isEqualTo("no_location");
        // The Owner of a business always holds tenant_admin (V79), which V132 grants the read; no selling role has it.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT role_key FROM role_permissions WHERE permission_key = 'retail.entry_location.read'")
                        .query(String.class)
                        .list())
                .containsExactly("tenant_admin");
    }

    @Test
    void anotherBusinessNeverSeesOurLocations() {
        setShop();
        String id = sell(sale(fix(NEAR_LAT, SHOP_LNG, 12)));
        TestDatabase.Fixture other = TestDatabase.tenant("entry-location-other", false, true);
        RetailTestSupport theirs = new RetailTestSupport(http, other);
        JsonNode item = distance(theirs, OWNER, "sale", id).get(0);
        assertThat(item.get("state").asString()).isEqualTo("no_location");
        assertThat(theirs.get("/shop-locations", OWNER).getBody().toString())
                .doesNotContain(t.headOffice().toString());
        assertThat(theirs.call(HttpMethod.PUT, "/shop-locations/" + t.headOffice(), fix(1, 1, 1), OWNER, "*", Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anOfflineSaleKeepsItsLocationAndAResendIsTheSameSale() {
        setShop();
        UUID client = UUID.randomUUID();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofHours(2));
        Map<String, Object> body = sale(fix(SHOP_LAT, FAR_LNG, 20));
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1", "unit_price_minor", 1_500)));
        body.put("client_sale_id", client);
        body.put("occurred_at", at.toString());
        body.put("recorded_offline", true);
        body.put("prices_as_of", at.minus(Duration.ofHours(1)).toString());
        String id = sell(body);
        // The outbox sends it again under a new key, from a phone that has moved since: still the same sale.
        body.put("location", fix(NEAR_LAT, SHOP_LNG, 5));
        assertThat(sell(body)).isEqualTo(id);
        assertThat(distance(owner, OWNER, "sale", id).get(0).get("words").asString())
                .isEqualTo("3.2 km from the shop");
    }

    // JUSTIFICATION-A3: one new test case for the key rule of ADR-122 decision 6; a test, not a mechanism.
    @Test
    void aRetryUnderTheSameKeyFromAPhoneThatMovedIsTheSameEntry() {
        setShop();
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = seller.postKeyed("/sales", sale(fix(NEAR_LAT, SHOP_LNG, 12)), SALES, "*", key);
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> again = seller.postKeyed("/sales", sale(fix(SHOP_LAT, FAR_LNG, 9)), SALES, "*", key);
        assertThat(again.getStatusCode()).as("%s", again.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        // Only the location is left out of the key: a changed buyer contact under it is still refused.
        Map<String, Object> otherBuyer = sale(fix(NEAR_LAT, SHOP_LNG, 12));
        otherBuyer.put("buyer_name", "Test Buyer 01");
        otherBuyer.put("buyer_contact", "+256700000002");
        ResponseEntity<JsonNode> changed = seller.postKeyed("/sales", otherBuyer, SALES, "*", key);
        assertThat(changed.getStatusCode()).as("%s", changed.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(changed.getBody().get("code").asString()).isEqualTo("idempotency_key_reused");
        assertThat(distance(owner, OWNER, "sale", first.getBody().get("id").asString())
                        .get(0)
                        .get("words")
                        .asString())
                .isEqualTo("40 m from the shop");

        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("branch_id", t.headOffice());
        usage.put("kind", "used");
        usage.put("reason", "Test shop lights");
        usage.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        usage.put("location", fix(NEAR_LAT, SHOP_LNG, 12));
        String usageKey = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> used = seller.postKeyed("/usage", usage, SALES, "*", usageKey);
        usage.put("location", fix(SHOP_LAT, FAR_LNG, 9));
        ResponseEntity<JsonNode> usedAgain = seller.postKeyed("/usage", usage, SALES, "*", usageKey);
        assertThat(usedAgain.getStatusCode()).as("%s", usedAgain.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(usedAgain.getBody().get("id")).isEqualTo(used.getBody().get("id"));
    }

    @Test
    void aRefusedOfflineSaleIsKeptWithoutItsLocation() {
        UUID client = UUID.randomUUID();
        Map<String, Object> request = sale(fix(NEAR_LAT, SHOP_LNG, 5));
        request.put("client_sale_id", client);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("client_sale_id", client);
        report.put("branch_id", t.headOffice());
        report.put("occurred_at", Instant.now().minus(Duration.ofHours(1)).toString());
        report.put("payment_method", "cash");
        report.put("total_minor", 1_500);
        report.put("request", request);
        report.put("code", "period_closed");
        report.put("message", "The period is closed.");
        ResponseEntity<JsonNode> r = seller.post("/offline-failures", report, SALES);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("request").has("location")).isFalse();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_offline_failures WHERE tenant_id = ?"
                                + " AND (request -> 'location') IS NOT NULL")
                        .params(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aLocationOutOfRangeIsRefusedAndNoneIsNeverRefused(CapturedOutput output) {
        Map<String, Object> north = sale(fix(91, SHOP_LNG, 5));
        ResponseEntity<JsonNode> r =
                seller.postKeyed("/sales", north, SALES, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        Map<String, Object> negative = sale(fix(SHOP_LAT, SHOP_LNG, -1));
        assertThat(seller.postKeyed(
                                "/sales",
                                negative,
                                SALES,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(owner.call(
                                HttpMethod.PUT,
                                "/shop-locations/" + t.headOffice(),
                                fix(SHOP_LAT, 181, 5),
                                OWNER,
                                "*",
                                Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        sell(sale(null));
        // NFR-SEC-03: a refused location is never written to the log, rejected value or not.
        assertThat(output.getAll())
                .doesNotContain("32.5811")
                .doesNotContain("0.3136")
                .doesNotContain("181.0");
    }

    @Test
    void theCoordinatesAreNeverAudited() {
        setShop();
        sell(sale(fix(NEAR_LAT, SHOP_LNG, 12)));
        use(fix(SHOP_LAT, FAR_LNG, 30));
        // Every column of the row but created_at, audit_log's one timestamp, whose seconds can hold these digits (a
        // time such as 10:32.58 failed it); ids are hex UUIDs and hold no dot. Every coordinate of the fixture, both
        // latitudes too, as a short fragment, so a rounded or truncated value is caught as well. Shop locations are
        // audited without coordinates.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log a WHERE a.tenant_id = ? AND EXISTS (SELECT 1 FROM"
                                + " unnest(CAST(? AS text[])) f WHERE"
                                + " (to_jsonb(a) - 'created_at')::text LIKE '%' || f || '%')")
                        .params(t.tenantId(), new String[] {"0.3136", "0.3139", "32.58", "32.60"})
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_entry_locations WHERE tenant_id = ?")
                        .params(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }
}
