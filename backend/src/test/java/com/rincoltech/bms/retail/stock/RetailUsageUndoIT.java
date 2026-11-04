package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.MovableClockConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * FR-RET-88 (issue #818, ADR-123): a wrong "used in the shop" or damage entry is undone with a reason, as a sale is voided.
 * The stock and the inventory value come back at the same unit cost on the day of the undo, the entry is kept and marked
 * undone (who, when, why), a second undo is 409, a person without the void permission gets 403, the audit row is written
 * and Controls shows it. Fabricated items, costs and reasons.
 */
@Import(MovableClockConfig.class)
@ResourceLock(MovableClockConfig.LOCK)
class RetailUsageUndoIT extends IntegrationTest {

    /** 12:00 in Kampala on 12 and 13 October 2026. */
    static final Instant DAY_ONE = Instant.parse("2026-10-12T09:00:00Z");

    static final Instant DAY_TWO = Instant.parse("2026-10-13T09:00:00Z");
    static final String CONTROLS = "retail.controls.read";

    @Autowired
    TestRestTemplate http;

    @Autowired
    MovableClock clock;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    /** Cost 400, selling price 700. */
    UUID product;

    @BeforeEach
    void setUp() {
        clock.set(DAY_ONE);
        t = TestDatabase.tenant("retail-usage-undo", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        product = api.product("UU-1", 400, 700);
        api.stockUp(hq, product, "10");
    }

    UUID usage(String kind, String qty) {
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/usage",
                Map.of(
                        "branch_id",
                        hq,
                        "kind",
                        kind,
                        "reason",
                        "Test entry",
                        "lines",
                        List.of(Map.of("product_id", product, "qty", qty))),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    ResponseEntity<JsonNode> undo(UUID report, String reason, String permissions) {
        return api.call(
                HttpMethod.POST, "/usage/" + report + "/undo", Map.of("reason", reason), permissions, "*", Map.of());
    }

    BigDecimal balance() {
        return TestDatabase.owner()
                .sql("SELECT qty FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), hq, product)
                .query(BigDecimal.class)
                .single();
    }

    long inventory() {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.tenant_id = ? AND a.system_key = 'inventory'
                        """)
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    @Test
    void undoingADamageEntryPutsTheStockAndItsValueBackOnTheDayOfTheUndo() {
        long valueBefore = inventory();
        UUID report = usage("damaged", "3");
        assertThat(balance()).isEqualByComparingTo("7");
        assertThat(inventory()).isEqualTo(valueBefore - 1_200);

        // The next day: the undo is dated today, the day of the entry keeps its figures.
        clock.set(DAY_TWO);
        ResponseEntity<JsonNode> done = undo(report, "Counted twice by mistake", ADMIN);
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("undone_on").asString()).isEqualTo("2026-10-13");
        assertThat(done.getBody().get("items_back").asInt()).isEqualTo(1);
        assertThat(done.getBody().get("reason").asString()).isEqualTo("Counted twice by mistake");
        assertThat(balance()).isEqualByComparingTo("10");
        assertThat(inventory()).isEqualTo(valueBefore);

        // The stock came back at the unit cost it left at, as a return naming the movement it reverses.
        Map<String, Object> back = TestDatabase.owner()
                .sql("""
                        SELECT r.kind, r.qty, r.unit_cost_minor, (r.occurred_at AT TIME ZONE 'Africa/Kampala')::date AS day
                          FROM retail_stock_movements r JOIN retail_stock_movements m ON m.id = r.reverses_movement_id
                         WHERE r.tenant_id = ? AND m.source_id = ? AND r.source_type = 'retail.usage_undo'
                        """)
                .params(t.tenantId(), report)
                .query()
                .singleRow();
        assertThat(back.get("kind")).isEqualTo("return");
        assertThat((BigDecimal) back.get("qty")).isEqualByComparingTo("3");
        assertThat(((Number) back.get("unit_cost_minor")).longValue()).isEqualTo(400);
        assertThat(back.get("day").toString()).isEqualTo("2026-10-13");
        LocalDate reversedOn = TestDatabase.owner()
                .sql("""
                        SELECT r.entry_date FROM journal_entries r JOIN retail_usage_reports u
                            ON u.tenant_id = r.tenant_id AND u.journal_entry_id = r.reverses_entry_id
                         WHERE u.id = ?
                        """)
                .param(report)
                .query(LocalDate.class)
                .single();
        assertThat(reversedOn).isEqualTo(LocalDate.parse("2026-10-13"));

        // The entry is kept, never deleted, and the list marks it undone with who, when and why.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_usage_reports WHERE id = ?")
                        .param(report)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        JsonNode rows = api.get(
                        "/stock/movements?kind=usage&kind=damage&from=2026-10-01&to=2026-10-31&newest_first=true",
                        ADMIN)
                .getBody()
                .get("items");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("entry_lines").asInt()).isEqualTo(1);
        JsonNode undone = rows.get(0).get("undone");
        assertThat(undone.get("reason").asString()).isEqualTo("Counted twice by mistake");
        assertThat(undone.get("by").asString()).isEqualTo(api.userId().toString());
        assertThat(undone.has("at")).isTrue();

        // An undone entry counts in no total of Past usage and damage.
        JsonNode shops = api.get("/usage/summary?group_by=branch,month,category,item", ADMIN)
                .getBody();
        assertThat(shops.get("items")).isEmpty();

        // The audit row, as a void writes one.
        List<Map<String, Object>> audits = TestDatabase.owner()
                .sql("SELECT action, data::text AS data FROM audit_log WHERE entity_id = ? ORDER BY created_at, action")
                .param(report)
                .query()
                .listOfRows();
        assertThat(audits)
                .extracting(a -> a.get("action"))
                .containsExactly("retail.usage.reported", "retail.usage.undone");
        assertThat(audits.get(1).get("data").toString()).contains("Counted twice by mistake");
    }

    // JUSTIFICATION-A3: the review of 56e3ad9 asks the count to come from the server; a two-item entry proves it.
    @Test
    void anEntryOfTwoItemsPutsBothBackAndSaysSo() {
        UUID brush = api.product("UU-2", 100, 200);
        api.stockUp(hq, brush, "5");
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/usage",
                Map.of(
                        "branch_id",
                        hq,
                        "kind",
                        "damaged",
                        "reason",
                        "Test entry",
                        "lines",
                        List.of(Map.of("product_id", product, "qty", "1"), Map.of("product_id", brush, "qty", "2"))),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        // Filtered to one of its items, the list still says the entry has two lines.
        JsonNode filtered = api.get(
                        "/stock/movements?kind=usage&kind=damage&from=2026-10-01&to=2026-10-31&product_id=" + brush,
                        ADMIN)
                .getBody()
                .get("items");
        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("entry_lines").asInt()).isEqualTo(2);
        ResponseEntity<JsonNode> done = undo(RetailTestSupport.id(r), "Wrong entry", ADMIN);
        assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("items_back").asInt()).isEqualTo(2);
        assertThat(balance()).isEqualByComparingTo("10");
    }

    @Test
    void anEntryIsUndoneOnceAndTheSecondUndoIs409() {
        UUID report = usage("used", "2");
        assertThat(undo(report, "Wrong item", ADMIN).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> twice = undo(report, "Wrong item again", ADMIN);
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(twice.getBody().get("code").asString()).isEqualTo("usage_undone");
        // The stock went back once, not twice.
        assertThat(balance()).isEqualByComparingTo("10");
    }

    // JUSTIFICATION-A3: the review of #843 asks for two new cases, two undos at once and another business's entry.
    @Test
    void twoUndosAtOnceTakeTheStockBackOnceOneAnswered409() throws Exception {
        UUID report = usage("damaged", "4");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpStatusCode>> both = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                both.add(pool.submit(() -> {
                    start.await();
                    return undo(report, "Test undo at once", ADMIN).getStatusCode();
                }));
            }
            start.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<HttpStatusCode> f : both) {
                codes.add(f.get(60, TimeUnit.SECONDS).value());
            }
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(balance()).isEqualByComparingTo("10");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_stock_movements WHERE tenant_id = ?"
                                + " AND source_type = 'retail.usage_undo' AND source_id = ?")
                        .params(t.tenantId(), report)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void anotherBusinessCannotUndoThisBusinessesEntry() {
        UUID report = usage("used", "2");
        TestDatabase.Fixture other = TestDatabase.tenant("retail-usage-undo-other", false, true);
        RetailTestSupport elsewhere = new RetailTestSupport(http, other);
        ResponseEntity<JsonNode> refused = elsewhere.call(
                HttpMethod.POST, "/usage/" + report + "/undo", Map.of("reason", "Test reason"), ADMIN, "*", Map.of());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(balance()).isEqualByComparingTo("8");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_usage_undos WHERE report_id = ?")
                        .param(report)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void withoutThePermissionToVoidASaleTheUndoIs403AndNothingMoves() {
        UUID report = usage("used", "2");
        ResponseEntity<JsonNode> refused = undo(report, "Test reason", SALES);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(balance()).isEqualByComparingTo("8");
        // A reason is required.
        assertThat(undo(report, " ", ADMIN).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // Outside the caller's branches the entry is not found, as a sale is.
        ResponseEntity<JsonNode> elsewhere = api.call(
                HttpMethod.POST,
                "/usage/" + report + "/undo",
                Map.of("reason", "Test reason"),
                ADMIN,
                t.secondBranch().toString(),
                Map.of());
        assertThat(elsewhere.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(undo(UUID.randomUUID(), "Test reason", ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void controlsShowsTheUndoneEntryWithItsReasonLikeAVoidedSale() {
        UUID report = usage("damaged", "1");
        undo(report, "Not damaged after all", ADMIN);
        JsonNode summary = api.get("/controls/summary", CONTROLS).getBody();
        JsonNode card = null;
        for (JsonNode c : summary.get("cards")) {
            if (c.get("metric").asString().equals("usage_undos")) {
                card = c;
            }
        }
        assertThat(card).isNotNull();
        assertThat(card.get("count").asLong()).isEqualTo(1);
        // 1 at the selling price of 700, never the cost of 400.
        assertThat(card.get("value_minor").asLong()).isEqualTo(700);
        assertThat(card.has("rate_per_100")).isFalse();
        JsonNode records =
                api.get("/controls/records?metric=usage_undos", CONTROLS).getBody();
        assertThat(records.get("items")).hasSize(1);
        JsonNode record = records.get("items").get(0);
        assertThat(record.get("sale_id").asString()).isEqualTo(report.toString());
        assertThat(record.get("reason").asString()).isEqualTo("Not damaged after all");
        assertThat(record.get("item").asString()).isEqualTo("Damaged");
        assertThat(record.get("approver_id").asString()).isEqualTo(api.userId().toString());
        JsonNode approvers = api.get("/controls/groups?metric=usage_undos&group_by=approver", CONTROLS)
                .getBody();
        assertThat(approvers.get("items")).hasSize(1);
    }
}
