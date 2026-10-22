package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
 * Sales made offline and sent later (issue #458, ADR-067; FR-RET-67, NFR-OFF-07): sent once by the key and by the client sale
 * id after the key expired, dated when they happened within the clock rule, every clash recorded and flagged instead of
 * refused, refused ones reported, resent or discarded by a manager with an audit row, and nothing seen across tenants. All
 * names and amounts are fabricated.
 */
class RetailOfflineSalesIT extends IntegrationTest {

    /** A manager: the retail admin column with the new permission. */
    static final String MANAGER = ADMIN + ",retail.offline.resolve";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-offline", false, true);
        api = new RetailTestSupport(http, t);
        product = api.product("OFF-1", 1_500, 2_000);
    }

    Map<String, Object> offlineSale(UUID clientSaleId, Instant occurredAt, String qty, long priceMinor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "cash");
        body.put("lines", List.of(Map.of("product_id", product, "qty", qty, "unit_price_minor", priceMinor)));
        body.put("client_sale_id", clientSaleId);
        body.put("occurred_at", occurredAt.toString());
        body.put("recorded_offline", true);
        body.put("prices_as_of", occurredAt.minus(Duration.ofHours(1)).toString());
        return body;
    }

    ResponseEntity<JsonNode> send(Map<String, Object> body, String key) {
        return api.postKeyed("/sales", body, SALES, "*", key);
    }

    long sales() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_sales WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    List<String> clashKinds(JsonNode sale) {
        List<String> kinds = new ArrayList<>();
        sale.get("device").get("clashes").forEach(c -> kinds.add(c.get("kind").asString()));
        return kinds;
    }

    String balance() {
        return api.get("/stock?include_inactive=true&branch_id=" + t.headOffice(), ADMIN)
                .getBody()
                .get("items")
                .get(0)
                .get("qty")
                .asString();
    }

    LocalDate dayIn(Instant at) {
        String zone = TestDatabase.owner()
                .sql("SELECT timezone FROM tenants WHERE id = ?")
                .param(t.tenantId())
                .query(String.class)
                .single();
        return LocalDate.ofInstant(at, ZoneId.of(zone));
    }

    /** NFR-OFF-07: the same key replays; a covered offline sale has no clash and stores its client id and time. */
    @Test
    void anOfflineSaleSentTwiceWithItsKeyIsOneSale() {
        api.stockUp(t.headOffice(), product, "5");
        UUID client = UUID.randomUUID();
        // A device sends milliseconds; the column keeps microseconds.
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofHours(2));
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = send(offlineSale(client, at, "2", 2_000), key);
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> again = send(offlineSale(client, at, "2", 2_000), key);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(sales()).isEqualTo(1);
        assertThat(balance()).isEqualTo("3.000");

        JsonNode device = first.getBody().get("device");
        assertThat(device.get("client_sale_id").asString()).isEqualTo(client.toString());
        assertThat(device.get("recorded_offline").asBoolean()).isTrue();
        assertThat(Instant.parse(device.get("occurred_at").asString())).isEqualTo(at);
        assertThat(device.get("clashes")).isEmpty();
        assertThat(first.getBody().get("sale_date").asString())
                .isEqualTo(dayIn(at).toString());
    }

    /** ADR-067 decision 5: after the key expired and was purged, the client sale id still finds the same sale. */
    @Test
    void aReplayAfterTheKeyExpiredIsTheSameSaleByItsClientId() {
        api.stockUp(t.headOffice(), product, "5");
        UUID client = UUID.randomUUID();
        Instant at = Instant.now().minus(Duration.ofDays(1));
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = send(offlineSale(client, at, "1", 2_000), key);
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        // What the daily purge does to a key past its 7 days (CoreJobs IDEMPOTENCY_KEY_PURGE).
        TestDatabase.owner()
                .sql("UPDATE idempotency_keys SET expires_at = now() - interval '1 second' WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        TestDatabase.owner()
                .sql("DELETE FROM idempotency_keys WHERE tenant_id = ? AND expires_at < now()")
                .param(t.tenantId())
                .update();

        ResponseEntity<JsonNode> sameKey = send(offlineSale(client, at, "1", 2_000), key);
        ResponseEntity<JsonNode> newKey =
                send(offlineSale(client, at, "1", 2_000), UUID.randomUUID().toString());
        for (ResponseEntity<JsonNode> r : List.of(sameKey, newKey)) {
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
            assertThat(r.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
            assertThat(r.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        }
        assertThat(sales()).isEqualTo(1);
        assertThat(balance()).isEqualTo("4.000");

        // An online sale screen sends the client id too, so a lost answer moved to the outbox cannot record twice.
        Map<String, Object> online = new LinkedHashMap<>();
        online.put("branch_id", t.headOffice());
        online.put("payment_method", "cash");
        online.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        online.put("client_sale_id", UUID.randomUUID());
        ResponseEntity<JsonNode> on = send(online, UUID.randomUUID().toString());
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(on.getBody().get("device").get("recorded_offline").asBoolean())
                .isFalse();
        assertThat(send(online, UUID.randomUUID().toString()).getBody().get("id"))
                .isEqualTo(on.getBody().get("id"));
        assertThat(sales()).isEqualTo(2);
    }

    /** ADR-067 decision 5: another person's client sale id is refused, never answered with their sale. */
    @Test
    void anotherPersonsClientIdIsRefused() {
        api.stockUp(t.headOffice(), product, "5");
        UUID client = UUID.randomUUID();
        Instant at = Instant.now().minus(Duration.ofMinutes(10));
        assertThat(send(offlineSale(client, at, "1", 2_000), UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        RetailTestSupport other = new RetailTestSupport(http, t);
        ResponseEntity<JsonNode> theirs = other.postKeyed(
                "/sales",
                offlineSale(client, at, "1", 2_000),
                SALES,
                "*",
                UUID.randomUUID().toString());
        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(theirs.getBody().get("code").asString()).isEqualTo("client_sale_id_reused");
        assertThat(sales()).isEqualTo(1);
    }

    /** ADR-067 decision 6: skew is now, further ahead is refused online and flagged offline, a mismatched date refused. */
    @Test
    void theTimeOfTheSaleIsBounded() {
        api.stockUp(t.headOffice(), product, "10");
        Instant before = Instant.now();
        ResponseEntity<JsonNode> skew = send(
                offlineSale(UUID.randomUUID(), before.plus(Duration.ofMinutes(2)), "1", 2_000),
                UUID.randomUUID().toString());
        assertThat(skew.getStatusCode()).as("%s", skew.getBody()).isEqualTo(HttpStatus.CREATED);
        Instant skewAt =
                Instant.parse(skew.getBody().get("device").get("occurred_at").asString());
        assertThat(skewAt).isBetween(before, Instant.now());
        assertThat(clashKinds(skew.getBody())).isEmpty();

        ResponseEntity<JsonNode> ahead = send(
                offlineSale(UUID.randomUUID(), Instant.now().plus(Duration.ofHours(3)), "1", 2_000),
                UUID.randomUUID().toString());
        assertThat(ahead.getStatusCode()).as("%s", ahead.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(clashKinds(ahead.getBody())).containsExactly("clock_ahead");
        assertThat(Instant.parse(
                        ahead.getBody().get("device").get("occurred_at").asString()))
                .isBefore(Instant.now().plusSeconds(1));

        Map<String, Object> online = new LinkedHashMap<>(
                offlineSale(UUID.randomUUID(), Instant.now().plus(Duration.ofHours(3)), "1", 2_000));
        online.remove("recorded_offline");
        ResponseEntity<JsonNode> refused = send(online, UUID.randomUUID().toString());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("occurred_at_in_future");

        Instant lastWeek = Instant.now().minus(Duration.ofDays(6));
        Map<String, Object> wrongDay = offlineSale(UUID.randomUUID(), lastWeek, "1", 2_000);
        wrongDay.put("sale_date", dayIn(lastWeek).plusDays(1).toString());
        ResponseEntity<JsonNode> mismatch = send(wrongDay, UUID.randomUUID().toString());
        assertThat(mismatch.getBody().get("code").asString()).isEqualTo("sale_date_mismatch");

        ResponseEntity<JsonNode> dated = send(
                offlineSale(UUID.randomUUID(), lastWeek, "1", 2_000),
                UUID.randomUUID().toString());
        assertThat(dated.getStatusCode()).as("%s", dated.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(dated.getBody().get("sale_date").asString())
                .isEqualTo(dayIn(lastWeek).toString());

        // An offline sale names its client id and its time, and is never a credit sale.
        Map<String, Object> bare = offlineSale(UUID.randomUUID(), lastWeek, "1", 2_000);
        bare.remove("client_sale_id");
        bare.remove("occurred_at");
        ResponseEntity<JsonNode> missing = send(bare, UUID.randomUUID().toString());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(missing.getBody().toString()).contains("client_sale_id", "occurred_at");
        Map<String, Object> credit = offlineSale(UUID.randomUUID(), lastWeek, "1", 2_000);
        credit.put("payment_method", "credit");
        credit.put("buyer_name", "Test Buyer 07");
        assertThat(send(credit, UUID.randomUUID().toString())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("offline_credit_sale");
    }

    /**
     * ADR-067 decision 7: short stock, an item switched off, a typed price and a price not above cost record the sale and
     * a clash each; the stock goes negative; the same sale online is refused; a manager resolves a clash once, audited.
     */
    @Test
    void clashesAreRecordedAndFlaggedNeverDropped() {
        api.stockUp(t.headOffice(), product, "1");
        String tag = api.get("/products/" + product, ADMIN).getHeaders().getETag();
        assertThat(api.call(
                                HttpMethod.PATCH,
                                "/products/" + product,
                                Map.of("active", false),
                                ADMIN,
                                "*",
                                Map.of("If-Match", tag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> online = new LinkedHashMap<>(offlineSale(UUID.randomUUID(), Instant.now(), "3", 1_000));
        online.remove("recorded_offline");
        ResponseEntity<JsonNode> refusedOnline = send(online, UUID.randomUUID().toString());
        assertThat(refusedOnline.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(sales()).isZero();

        ResponseEntity<JsonNode> sold = send(
                offlineSale(UUID.randomUUID(), Instant.now().minus(Duration.ofHours(1)), "3", 1_000),
                UUID.randomUUID().toString());
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode sale = sold.getBody();
        assertThat(sale.get("total_minor").asLong()).isEqualTo(3_000);
        assertThat(sale.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(1_000);
        assertThat(clashKinds(sale))
                .containsExactlyInAnyOrder("item_inactive", "price_typed", "below_cost", "stock_short");
        sale.get("device")
                .get("clashes")
                .forEach(c -> assertThat(c.get("words").asString())
                        .doesNotContain("1500")
                        .doesNotContain("1,500"));
        assertThat(balance()).isEqualTo("-2.000");
        UUID saleId = RetailTestSupport.id(sold);
        assertThat(TestDatabase.owner()
                        .sql("SELECT action FROM audit_log WHERE entity_id = ?")
                        .param(saleId)
                        .query(String.class)
                        .list())
                .containsExactly("retail.sale.created");

        // A seller cannot list or resolve; a manager sees the open ones and resolves one, once.
        assertThat(api.get("/sale-clashes", SALES).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode open = api.get("/sale-clashes", MANAGER).getBody().get("items");
        assertThat(open).hasSize(4);
        String clash = open.get(0).get("id").asString();
        ResponseEntity<JsonNode> resolved = api.post(
                "/sale-clashes/" + clash + "/resolve", Map.of("note", "Counted the shelf and corrected"), MANAGER);
        assertThat(resolved.getStatusCode()).as("%s", resolved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody().get("resolution_note").asString()).isEqualTo("Counted the shelf and corrected");
        assertThat(api.post("/sale-clashes/" + clash + "/resolve", Map.of("note", "Again and again"), MANAGER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        String other = open.get(1).get("id").asString();
        assertThat(api.post("/sale-clashes/" + other + "/resolve", Map.of("note", "    ok    "), MANAGER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/sale-clashes", MANAGER).getBody().get("items")).hasSize(3);
        assertThat(api.get("/sale-clashes?status=resolved", MANAGER).getBody().get("items"))
                .hasSize(1);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE action = 'retail.sale_clash.resolved' AND entity_id = ?")
                        .param(UUID.fromString(clash))
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /**
     * ADR-067 decision 8: a refused sale is reported once; a manager asks for it again, a refusal reopens it, a discard needs
     * a reason and is audited and final; a sale later recorded with the client id closes its report.
     */
    @Test
    void aRefusedSaleIsReportedThenResentOrDiscardedByAManager() {
        // The manager is another person: nobody decides their own refused sale.
        RetailTestSupport boss = new RetailTestSupport(http, t);
        UUID client = UUID.randomUUID();
        Map<String, Object> request = offlineSale(client, Instant.now().minus(Duration.ofHours(5)), "1", 2_000);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("client_sale_id", client);
        report.put("branch_id", t.headOffice());
        report.put("occurred_at", request.get("occurred_at"));
        report.put("payment_method", "cash");
        report.put("total_minor", 2_000);
        report.put("request", request);
        report.put("code", "period_closed");
        report.put("message", "The period is closed.");
        Map<String, Object> nowhere = new LinkedHashMap<>(report);
        nowhere.put("branch_id", UUID.randomUUID());
        assertThat(api.post("/offline-failures", nowhere, SALES).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> reported = api.post("/offline-failures", report, SALES);
        assertThat(reported.getStatusCode()).as("%s", reported.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(reported.getBody().get("status").asString()).isEqualTo("open");
        assertThat(api.post("/offline-failures", report, SALES).getBody().get("id"))
                .isEqualTo(reported.getBody().get("id"));
        String id = reported.getBody().get("id").asString();
        assertThat(api.get("/offline-failures/mine?client_sale_id=" + client, SALES)
                        .getBody()
                        .get("items"))
                .hasSize(1);

        assertThat(api.get("/offline-failures", SALES).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.post("/offline-failures/" + id + "/discard", Map.of("reason", "Typed twice"), SALES)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(boss.get("/offline-failures", MANAGER).getBody().get("items"))
                .hasSize(1);

        ResponseEntity<JsonNode> retry = boss.post("/offline-failures/" + id + "/retry", Map.of(), MANAGER);
        assertThat(retry.getBody().get("status").asString()).isEqualTo("retry");
        report.put("code", "branch_does_not_sell");
        assertThat(api.post("/offline-failures", report, SALES)
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("open");
        assertThat(boss.post("/offline-failures/" + id + "/discard", Map.of("reason", "no"), MANAGER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> discarded =
                boss.post("/offline-failures/" + id + "/discard", Map.of("reason", "Entered again by hand"), MANAGER);
        assertThat(discarded.getStatusCode()).as("%s", discarded.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(discarded.getBody().get("status").asString()).isEqualTo("discarded");
        assertThat(boss.post("/offline-failures/" + id + "/retry", Map.of(), MANAGER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE action = 'retail.offline_sale.discarded'"
                                + " AND entity_id = ?")
                        .param(UUID.fromString(id))
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        // Another refused sale, asked to be sent again, then recorded: the sale closes its report.
        api.stockUp(t.headOffice(), product, "5");
        UUID second = UUID.randomUUID();
        report.put("client_sale_id", second);
        String secondId =
                api.post("/offline-failures", report, SALES).getBody().get("id").asString();
        boss.post("/offline-failures/" + secondId + "/retry", Map.of(), MANAGER);
        ResponseEntity<JsonNode> sold = send(
                offlineSale(second, Instant.now().minus(Duration.ofHours(1)), "1", 2_000),
                UUID.randomUUID().toString());
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode mine = api.get("/offline-failures/mine?client_sale_id=" + second, SALES)
                .getBody()
                .get("items")
                .get(0);
        assertThat(mine.get("status").asString()).isEqualTo("recorded");
        assertThat(mine.get("sale_id").asString())
                .isEqualTo(sold.getBody().get("id").asString());
    }

    /**
     * Review fix 2: a send under a recorded client sale id must carry the same sale. How it was delivered (the time, the
     * offline flag, the prices time) may differ, so an online sale whose answer was lost and that the outbox sends is the
     * same; a changed line is 422 client_sale_conflict and records nothing.
     */
    @Test
    void aReplayWithADifferentBodyIsAConflict() {
        api.stockUp(t.headOffice(), product, "5");
        UUID client = UUID.randomUUID();
        Map<String, Object> online = new LinkedHashMap<>();
        online.put("branch_id", t.headOffice());
        online.put("payment_method", "cash");
        online.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        online.put("client_sale_id", client);
        ResponseEntity<JsonNode> first = send(online, UUID.randomUUID().toString());
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> fromOutbox = new LinkedHashMap<>(online);
        fromOutbox.put("occurred_at", Instant.now().minus(Duration.ofMinutes(1)).toString());
        fromOutbox.put("recorded_offline", true);
        fromOutbox.put("prices_as_of", Instant.now().minus(Duration.ofHours(1)).toString());
        ResponseEntity<JsonNode> same = send(fromOutbox, UUID.randomUUID().toString());
        assertThat(same.getStatusCode()).as("%s", same.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(same.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(same.getBody().get("id")).isEqualTo(first.getBody().get("id"));

        Map<String, Object> changed = new LinkedHashMap<>(online);
        changed.put("lines", List.of(Map.of("product_id", product, "qty", "3")));
        ResponseEntity<JsonNode> conflict = send(changed, UUID.randomUUID().toString());
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(conflict.getBody().get("code").asString()).isEqualTo("client_sale_conflict");
        assertThat(sales()).isEqualTo(1);
        assertThat(balance()).isEqualTo("4.000");
    }

    /**
     * ADR-067 decision 5: sends of one client sale id at the same moment with different keys make one sale; a send that
     * loses the race on the unique index is 409 client_sale_in_progress, never a second sale.
     */
    @Test
    void concurrentSendsOfOneClientIdMakeOneSale() throws Exception {
        api.stockUp(t.headOffice(), product, "20");
        UUID client = UUID.randomUUID();
        Map<String, Object> body = offlineSale(client, Instant.now().minus(Duration.ofMinutes(5)), "1", 2_000);
        int n = 6;
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        List<java.util.concurrent.Future<ResponseEntity<JsonNode>>> sent = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                sent.add(pool.submit(() -> {
                    go.await();
                    return send(body, UUID.randomUUID().toString());
                }));
            }
            go.countDown();
            List<String> ids = new ArrayList<>();
            for (var f : sent) {
                ResponseEntity<JsonNode> r = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
                if (r.getStatusCode() == HttpStatus.CONFLICT) {
                    assertThat(r.getBody().get("code").asString()).isEqualTo("client_sale_in_progress");
                } else {
                    assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
                    ids.add(r.getBody().get("id").asString());
                }
            }
            assertThat(ids).isNotEmpty();
            assertThat(ids.stream().distinct().count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(sales()).isEqualTo(1);
        assertThat(balance()).isEqualTo("19.000");
    }

    /**
     * Review fix 3, ADR-067 decision 11: offline sales follow the online price rules. The price the phone saved (in force at
     * prices_as_of) stands, flagged price_changed when it changed since; a price typed by a holder of retail.price.override
     * with a reason stands with no clash; any other typed price is recorded as charged with a price_typed clash.
     */
    @Test
    void offlinePricesFollowTheOnlineRules() {
        api.stockUp(t.headOffice(), product, "20");
        Instant saved = Instant.now();
        assertThat(api.editPrices(product, Map.of("sell_minor", 2_500, "reason", "Test price rise"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Instant at = Instant.now().plus(Duration.ofSeconds(1));

        Map<String, Object> phonePrice = offlineSale(UUID.randomUUID(), at, "1", 2_000);
        phonePrice.put("prices_as_of", saved.toString());
        ResponseEntity<JsonNode> old = send(phonePrice, UUID.randomUUID().toString());
        assertThat(old.getStatusCode()).as("%s", old.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(clashKinds(old.getBody())).containsExactly("price_changed");
        assertThat(old.getBody().get("total_minor").asLong()).isEqualTo(2_000);

        Map<String, Object> today = offlineSale(UUID.randomUUID(), at, "1", 2_500);
        today.put("prices_as_of", saved.toString());
        assertThat(clashKinds(send(today, UUID.randomUUID().toString()).getBody()))
                .isEmpty();

        Map<String, Object> typed = offlineSale(UUID.randomUUID(), at, "1", 2_200);
        ResponseEntity<JsonNode> seller = send(typed, UUID.randomUUID().toString());
        assertThat(seller.getStatusCode()).as("%s", seller.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(clashKinds(seller.getBody())).containsExactly("price_typed");
        assertThat(seller.getBody().get("total_minor").asLong()).isEqualTo(2_200);

        String mayChange = SALES + ",retail.price.override";
        Map<String, Object> withReason = offlineSale(UUID.randomUUID(), at, "1", 2_200);
        withReason.put(
                "lines",
                List.of(Map.of(
                        "product_id",
                        product,
                        "qty",
                        "1",
                        "unit_price_minor",
                        2_200,
                        "price_override_reason",
                        "Regular buyer, agreed")));
        ResponseEntity<JsonNode> allowed = api.postKeyed(
                "/sales", withReason, mayChange, "*", UUID.randomUUID().toString());
        assertThat(allowed.getStatusCode()).as("%s", allowed.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(clashKinds(allowed.getBody())).isEmpty();

        ResponseEntity<JsonNode> noReason = api.postKeyed(
                "/sales",
                offlineSale(UUID.randomUUID(), at, "1", 2_200),
                mayChange,
                "*",
                UUID.randomUUID().toString());
        assertThat(clashKinds(noReason.getBody())).containsExactly("price_typed");
    }

    /**
     * Review fix 8: a sale that says it happened before the server time the phone last saved its prices had a clock behind;
     * it is dated when it reached the server with a clock_behind clash, never refused for the wrong day.
     */
    @Test
    void aPhoneClockBehindIsDatedNowAndFlagged() {
        api.stockUp(t.headOffice(), product, "5");
        Instant before = Instant.now();
        Map<String, Object> body = offlineSale(UUID.randomUUID(), before.minus(Duration.ofDays(400)), "1", 2_000);
        body.put("prices_as_of", before.minus(Duration.ofMinutes(30)).toString());
        ResponseEntity<JsonNode> sold = send(body, UUID.randomUUID().toString());
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(clashKinds(sold.getBody())).containsExactly("clock_behind");
        assertThat(Instant.parse(sold.getBody().get("device").get("occurred_at").asString()))
                .isBetween(before, Instant.now());
        assertThat(sold.getBody().get("sale_date").asString())
                .isEqualTo(dayIn(Instant.now()).toString());
    }

    /** Review fix 4: a Supervisor who made the refused sale can neither discard it nor ask for it again; another can. */
    @Test
    void aManagerCannotDecideTheirOwnRefusedSale() {
        UUID client = UUID.randomUUID();
        Map<String, Object> request = offlineSale(client, Instant.now().minus(Duration.ofHours(2)), "1", 2_000);
        String id = api.post("/offline-failures", failureReport(client, request), MANAGER)
                .getBody()
                .get("id")
                .asString();
        for (String action : List.of("discard", "retry")) {
            ResponseEntity<JsonNode> own =
                    api.post("/offline-failures/" + id + "/" + action, Map.of("reason", "My own mistake"), MANAGER);
            assertThat(own.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(own.getBody().get("code").asString()).isEqualTo("own_offline_sale");
        }
        RetailTestSupport other = new RetailTestSupport(http, t);
        ResponseEntity<JsonNode> decided =
                other.post("/offline-failures/" + id + "/discard", Map.of("reason", "Entered again by hand"), MANAGER);
        assertThat(decided.getStatusCode()).as("%s", decided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(decided.getBody().get("status").asString()).isEqualTo("discarded");
    }

    /** Review fix 1: a sale sent after a manager discarded it is refused and never recorded, whatever key it carries. */
    @Test
    void aSaleSentAfterItWasDiscardedIsRefused() {
        api.stockUp(t.headOffice(), product, "5");
        UUID client = UUID.randomUUID();
        Map<String, Object> request = offlineSale(client, Instant.now().minus(Duration.ofHours(3)), "1", 2_000);
        String id = api.post("/offline-failures", failureReport(client, request), SALES)
                .getBody()
                .get("id")
                .asString();
        RetailTestSupport boss = new RetailTestSupport(http, t);
        assertThat(boss.post("/offline-failures/" + id + "/discard", Map.of("reason", "Entered again by hand"), MANAGER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        for (int i = 0; i < 2; i++) {
            ResponseEntity<JsonNode> sent = send(request, UUID.randomUUID().toString());
            assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(sent.getBody().get("code").asString()).isEqualTo("offline_sale_discarded");
        }
        assertThat(sales()).isZero();
        assertThat(balance()).isEqualTo("5.000");
    }

    Map<String, Object> failureReport(UUID client, Map<String, Object> request) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("client_sale_id", client);
        report.put("branch_id", t.headOffice());
        report.put("occurred_at", request.get("occurred_at"));
        report.put("payment_method", "cash");
        report.put("total_minor", 2_000);
        report.put("request", request);
        report.put("code", "period_closed");
        report.put("message", "The period is closed.");
        return report;
    }

    /** ADR-003: another business sees none of these rows and may use the same client sale id for its own sale. */
    @Test
    void anotherTenantSeesNothingAndKeepsItsOwnClientIds() {
        api.stockUp(t.headOffice(), product, "1");
        UUID client = UUID.randomUUID();
        ResponseEntity<JsonNode> here = send(
                offlineSale(client, Instant.now().minus(Duration.ofHours(1)), "2", 2_000),
                UUID.randomUUID().toString());
        assertThat(clashKinds(here.getBody())).containsExactly("stock_short");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("client_sale_id", UUID.randomUUID());
        report.put("branch_id", t.headOffice());
        report.put("occurred_at", Instant.now().toString());
        report.put("payment_method", "cash");
        report.put("total_minor", 1_000);
        report.put("request", Map.of("lines", List.of()));
        report.put("code", "period_closed");
        report.put("message", "The period is closed.");
        assertThat(api.post("/offline-failures", report, SALES).getStatusCode()).isEqualTo(HttpStatus.OK);

        TestDatabase.Fixture b = TestDatabase.tenant("retail-offline-b", false, true);
        RetailTestSupport other = new RetailTestSupport(http, b);
        assertThat(other.get("/sale-clashes?status=all", MANAGER).getBody().get("items"))
                .isEmpty();
        assertThat(other.get("/offline-failures?status=open&status=retry&status=discarded&status=recorded", MANAGER)
                        .getBody()
                        .get("items"))
                .isEmpty();
        assertThat(other.get("/sales/" + RetailTestSupport.id(here), ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        UUID theirProduct = other.product("OFF-B", 1_500, 2_000);
        other.stockUp(b.headOffice(), theirProduct, "5");
        Map<String, Object> theirs = new LinkedHashMap<>();
        theirs.put("branch_id", b.headOffice());
        theirs.put("payment_method", "cash");
        theirs.put("lines", List.of(Map.of("product_id", theirProduct, "qty", "1", "unit_price_minor", 2_000)));
        theirs.put("client_sale_id", client);
        theirs.put("occurred_at", Instant.now().minus(Duration.ofMinutes(5)).toString());
        theirs.put("recorded_offline", true);
        ResponseEntity<JsonNode> sold =
                other.postKeyed("/sales", theirs, SALES, "*", UUID.randomUUID().toString());
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(sold.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(sold.getBody().get("id")).isNotEqualTo(here.getBody().get("id"));
    }
}
