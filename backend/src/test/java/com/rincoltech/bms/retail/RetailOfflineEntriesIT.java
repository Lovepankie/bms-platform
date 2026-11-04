package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.cashbook.CashbookTestSupport;
import com.rincoltech.bms.retail.stock.OfflineEntry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Entries kept on the phone with no network and sent later (issue #812, ADR-067 decision 13): an expense, savings, banking,
 * a withdrawal, Buy stock, Move stock and usage carry {@code offline.occurred_at} and are dated the day they were made,
 * judged as an offline sale is; a date sent with them is not used; the same key sent again records nothing more. A phone
 * clock ahead of the server dates the entry now; one behind the time it was last online is refused; older than 7 days is
 * the owner's only. Fabricated names and amounts.
 */
class RetailOfflineEntriesIT extends IntegrationTest {

    static final ZoneId ZONE = BusinessClock.DEFAULT_ZONE;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    UUID hq;
    UUID product;
    UUID[] expenseItem;
    LocalDate today;
    LocalDate yesterday;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("offline-entries", false, true);
        cb = new CashbookTestSupport(http, t);
        hq = t.headOffice();
        today = LocalDate.now(ZONE);
        yesterday = today.minusDays(1);
        product = cb.api.product("TEST-812", 100, 150);
        assertThat(cb.restock(hq, product, 100, 150, "500", "cash", today.minusDays(12))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        expenseItem = cb.categoryWithItem("Test Transport", "Test Fuel", false);
    }

    /** Yesterday at noon on the business clock: an entry made on the phone then. */
    Instant madeYesterday() {
        return yesterday.atTime(12, 0).atZone(ZONE).toInstant();
    }

    /** Two days ago at noon on the business clock. */
    Instant madeTwoDaysAgo() {
        return today.minusDays(2).atTime(12, 0).atZone(ZONE).toInstant();
    }

    /** The offline block; with no `onlineAsOf` given, the phone was last online an hour before the entry was made. */
    Map<String, Object> offline(Instant occurredAt, Instant onlineAsOf) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("occurred_at", occurredAt.toString());
        o.put("online_as_of", (onlineAsOf == null ? occurredAt.minus(Duration.ofHours(1)) : onlineAsOf).toString());
        return o;
    }

    /** One body per route, each kept offline at {@code made}, with the date field it would carry, by route. */
    Map<String, Supplier<Map<String, Object>>> bodies(Instant made, Instant onlineAsOf) {
        Map<String, Supplier<Map<String, Object>>> all = new LinkedHashMap<>();
        all.put("/expenses", () -> {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("branch_id", hq);
            b.put("category_id", expenseItem[0]);
            b.put("item_id", expenseItem[1]);
            b.put("amount_minor", 2_000);
            b.put("offline", offline(made, onlineAsOf));
            return b;
        });
        all.put("/bankings", () -> {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("branch_id", hq);
            b.put("amount_minor", 3_000);
            b.put("reference", "Test slip 01");
            b.put("offline", offline(made, onlineAsOf));
            return b;
        });
        all.put("/withdrawals", () -> {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("branch_id", hq);
            b.put("amount_minor", 1_000);
            b.put("purpose", "Test purpose");
            b.put("offline", offline(made, onlineAsOf));
            return b;
        });
        all.put("/usage", () -> {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("branch_id", hq);
            b.put("kind", "damaged");
            b.put("reason", "Test damage");
            b.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
            b.put("offline", offline(made, onlineAsOf));
            return b;
        });
        all.put("/transfers", () -> {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("from_branch_id", hq);
            b.put("to_branch_id", t.secondBranch());
            b.put("lines", List.of(Map.of("product_id", product, "qty", "2")));
            b.put("offline", offline(made, onlineAsOf));
            return b;
        });
        all.put("/purchases", () -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("product_id", product);
            line.put("cost_minor", 100);
            line.put("sell_minor", 150);
            line.put("qty_by_branch", List.of(Map.of("branch_id", hq, "qty", "3")));
            Map<String, Object> b = new LinkedHashMap<>();
            // Required by the route; the phone's own idea of the day, which an entry kept offline does not use.
            b.put("purchased_on", today.toString());
            b.put("payment_method", "cash");
            b.put("lines", List.of(line));
            b.put("offline", offline(made, onlineAsOf));
            return b;
        });
        return all;
    }

    static final Map<String, String> DATE_FIELD = Map.of(
            "/expenses", "business_date",
            "/bankings", "business_date",
            "/withdrawals", "business_date",
            "/usage", "occurred_on",
            "/transfers", "transfer_date",
            "/purchases", "purchased_on");

    @Test
    void anEntryKeptOfflineIsDatedTheDayItWasMadeAndIsRecordedOnceWhenSentAgain() {
        bodies(madeYesterday(), madeYesterday().minus(Duration.ofHours(1))).forEach((path, body) -> {
            String key = UUID.randomUUID().toString();
            ResponseEntity<JsonNode> first = cb.api.postKeyed(path, body.get(), ALL, "*", key);
            assertThat(first.getStatusCode()).as("%s %s", path, first.getBody()).isEqualTo(HttpStatus.CREATED);
            assertThat(first.getBody().get(DATE_FIELD.get(path)).asString())
                    .as(path)
                    .isEqualTo(yesterday.toString());
            // The answer was lost and the phone sends it again with the same key: the same entry, nothing new.
            ResponseEntity<JsonNode> again = cb.api.postKeyed(path, body.get(), ALL, "*", key);
            assertThat(again.getStatusCode().is2xxSuccessful())
                    .as("%s %s", path, again.getBody())
                    .isTrue();
            assertThat(RetailTestSupport.id(again)).as(path).isEqualTo(RetailTestSupport.id(first));
        });
    }

    @Test
    void savingsKeptOfflineAreDatedTheDayTheyWereMade() {
        assertThat(cb.sale(hq, "cash", product, "10", yesterday).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode suggestion = cb.get("/savings/suggestion?branch_id=" + hq + "&date=" + yesterday, ALL)
                .getBody();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", hq);
        b.put("suggestion_token", suggestion.get("suggestion_token").asString());
        b.put("offline", offline(madeYesterday(), null));
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> saved = cb.api.postKeyed("/savings", b, ALL, "*", key);
        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getBody().get("business_date").asString()).isEqualTo(yesterday.toString());
        ResponseEntity<JsonNode> again = cb.api.postKeyed("/savings", b, ALL, "*", key);
        assertThat(RetailTestSupport.id(again)).isEqualTo(RetailTestSupport.id(saved));
    }

    @Test
    void aPhoneClockAheadDatesTheEntryNowAndOneBehindIsRefused() {
        Instant now = Instant.now();
        // Ahead: the phone says the entry is three hours from now.
        bodies(now.plus(Duration.ofHours(3)), null).forEach((path, body) -> {
            ResponseEntity<JsonNode> r = cb.api.postKeyed(
                    path, body.get(), ALL, "*", UUID.randomUUID().toString());
            assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.CREATED);
            assertThat(r.getBody().get(DATE_FIELD.get(path)).asString())
                    .as(path)
                    .isEqualTo(today.toString());
        });
        // Behind: made two days before the phone was last online, which cannot be, so it is refused (the lead's
        // decision on
        // the review of #839), and nothing is recorded.
        bodies(now.minus(Duration.ofDays(2)), now.minus(Duration.ofMinutes(30))).forEach((path, body) -> {
            ResponseEntity<JsonNode> r = cb.api.postKeyed(
                    path, body.get(), ALL, "*", UUID.randomUUID().toString());
            assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(r.getBody().toString()).as(path).contains("offline_clock_behind");
        });
        // Every queued entry says when the phone was last online.
        bodies(now, null).forEach((path, body) -> {
            Map<String, Object> b = body.get();
            b.put("offline", Map.of("occurred_at", now.toString()));
            ResponseEntity<JsonNode> r =
                    cb.api.postKeyed(path, b, ALL, "*", UUID.randomUUID().toString());
            assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        });
    }

    @Test
    void anEntryKeptOfflineSaysWhenItWasMade() {
        bodies(madeYesterday(), null).forEach((path, body) -> {
            Map<String, Object> b = body.get();
            b.put("offline", Map.of());
            ResponseEntity<JsonNode> r =
                    cb.api.postKeyed(path, b, ALL, "*", UUID.randomUUID().toString());
            assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        });
    }

    // JUSTIFICATION-A3: the review of #839 asks for the age bound and the approval of an offline expense to be proven.
    /**
     * Review of #839, the lead's decision: occurred_at comes from the phone, so an entry made more than 7 days ago (the
     * offline sale's limit, ADR-067 decision 5) is refused for a member of staff, with a plain reason and nothing recorded,
     * and kept on its own day for the owner, who holds the backdate permission (issue #811).
     */
    @Test
    void anEntryOlderThanSevenDaysIsRefusedForStaffAndKeptOnItsDayForTheOwner() {
        Instant old = Instant.now().minus(Duration.ofDays(8));
        LocalDate oldDay = LocalDate.ofInstant(old, ZONE);
        bodies(old, null).forEach((path, body) -> {
            ResponseEntity<JsonNode> r = cb.api.postKeyed(
                    path, body.get(), ALL, "*", UUID.randomUUID().toString());
            assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(r.getBody().toString()).as(path).contains("offline_entry_too_old");
            assertThat(r.getBody().toString()).as(path).contains("Only the owner can save it on its own day");
        });
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_expenses WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
        String ownerPerms = ALL + "," + OfflineEntry.BACKDATE;
        for (String path : List.of("/expenses", "/withdrawals", "/transfers")) {
            ResponseEntity<JsonNode> r = cb.api.postKeyed(
                    path,
                    bodies(old, null).get(path).get(),
                    ownerPerms,
                    "*",
                    UUID.randomUUID().toString());
            assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.CREATED);
            assertThat(r.getBody().get(DATE_FIELD.get(path)).asString())
                    .as(path)
                    .isEqualTo(oldDay.toString());
        }
    }

    /**
     * Review of #839: an offline expense that waits for approval carries the resolved day in its approval payload, so approved
     * two days after it was made it stays on its own day.
     */
    @Test
    void anOfflineExpenseThatWaitsForApprovalKeepsTheDayItWasMade() {
        TestDatabase.owner()
                .sql("INSERT INTO tenant_settings (id, tenant_id) VALUES (?, ?) ON CONFLICT (tenant_id) DO NOTHING")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        TestDatabase.owner().sql("""
                        UPDATE tenant_settings SET settings = settings
                               || jsonb_build_object('approval_switches', jsonb_build_object('retail_expense', true))
                               || jsonb_build_object('approval_thresholds_minor', jsonb_build_object('retail_expense', 1000))
                         WHERE tenant_id = ?
                        """).param(t.tenantId()).update();
        ResponseEntity<JsonNode> asked = cb.api.postKeyed(
                "/expenses",
                bodies(madeTwoDaysAgo(), null).get("/expenses").get(),
                ALL,
                "*",
                UUID.randomUUID().toString());
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        RetailTestSupport checker = new RetailTestSupport(http, t);
        ResponseEntity<JsonNode> done = http.exchange(
                "/api/v1/approvals/" + asked.getBody().get("approval_id").asString() + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("note", "Checked"),
                        checker.headers("core.settings.manage,core.approvals.read", "*", Map.of())),
                JsonNode.class);
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        LocalDate dated = TestDatabase.owner()
                .sql("SELECT business_date FROM retail_expenses WHERE tenant_id = ? AND id = ?")
                .params(
                        t.tenantId(),
                        UUID.fromString(done.getBody().get("subject_id").asString()))
                .query(LocalDate.class)
                .single();
        assertThat(dated).isEqualTo(today.minusDays(2));
    }
}
