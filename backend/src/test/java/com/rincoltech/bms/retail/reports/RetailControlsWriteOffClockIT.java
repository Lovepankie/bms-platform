package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.MovableClockConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Credit write-offs on the Controls page against the business clock (issue #357; ADR-087). The write-offs are made through the
 * API with the application's clock moved, so the day the write-off path stores, and the day the card counts it on, are both
 * proven: 23:30 on 15 October 2026 in Kampala (20:30 UTC) is the 15th, and 00:30 on the 16th (still the 15th in UTC) is the
 * 16th. A write-off that waits for a second administrator is not counted until it is approved. Everything is fabricated.
 */
@Import(MovableClockConfig.class)
@ResourceLock(MovableClockConfig.LOCK)
class RetailControlsWriteOffClockIT extends IntegrationTest {

    static final String CONTROLS = "retail.controls.read,retail.sale.read";
    static final String WRITE_OFF = ADMIN + ",retail.credit.manage,retail.credit.write_off,core.approvals.read";
    static final LocalDate DAY = LocalDate.parse("2026-10-15");

    /** 12:00 on the 15th in Kampala. */
    static final Instant NOON = Instant.parse("2026-10-15T09:00:00Z");

    /** 23:30 on the 15th in Kampala, 20:30 UTC. */
    static final Instant LATE = Instant.parse("2026-10-15T20:30:00Z");

    /** 00:30 on the 16th in Kampala, still the 15th in UTC. */
    static final Instant EARLY = Instant.parse("2026-10-15T21:30:00Z");

    @Autowired
    TestRestTemplate http;

    @Autowired
    MovableClock clock;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID product;

    @BeforeEach
    void setUp() {
        clock.set(NOON);
        t = TestDatabase.tenant("retail-ctl-woclk", false, true);
        api = new RetailTestSupport(http, t);
        RetailControlsIT.user(t, api.userId(), "Test Admin One", "active");
        product = api.product("WK-1", 600, 1_000);
        api.stockUp(t.headOffice(), product, "20");
    }

    /** The clock back where MovableClockConfig starts it, so no later class inherits this one's night. */
    @AfterEach
    void resetClock() {
        clock.set(MovableClockConfig.BEFORE_MIDNIGHT);
    }

    UUID creditSale(int qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("sale_date", DAY.minusDays(1).toString());
        body.put("payment_method", "credit");
        body.put("buyer_name", "Test Buyer 04");
        body.put("lines", List.of(Map.of("product_id", product, "qty", String.valueOf(qty))));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    ResponseEntity<JsonNode> writeOff(UUID sale) {
        return api.postKeyed(
                "/sales/" + sale + "/write-off",
                Map.of("reason", "Test buyer moved away"),
                WRITE_OFF,
                "*",
                UUID.randomUUID().toString());
    }

    LocalDate stored(UUID sale) {
        return TestDatabase.owner()
                .sql("SELECT written_off_on FROM retail_sale_writeoffs WHERE sale_id = ?")
                .param(sale)
                .query(LocalDate.class)
                .single();
    }

    long count(LocalDate day) {
        ResponseEntity<JsonNode> r =
                api.call(HttpMethod.GET, "/controls/summary?from=" + day + "&to=" + day, null, CONTROLS, "*", Map.of());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        for (JsonNode c : r.getBody().get("cards")) {
            if (c.get("metric").asString().equals("credit_writeoffs")) {
                return c.get("count").asLong();
            }
        }
        throw new AssertionError("no credit_writeoffs card");
    }

    @Test
    void aWriteOffMadeAtHalfPastElevenAtNightInKampalaIsStoredAndCountedOnThatDay() {
        UUID late = creditSale(1);
        UUID early = creditSale(2);
        clock.set(LATE);
        assertThat(writeOff(late).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        clock.set(EARLY);
        assertThat(writeOff(early).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // The write-off path stores the Kampala day, never the UTC one: both were made on the 15th in UTC.
        assertThat(stored(late)).isEqualTo(DAY);
        assertThat(stored(early)).isEqualTo(DAY.plusDays(1));
        // And the card counts each on that day.
        assertThat(count(DAY)).isEqualTo(1);
        assertThat(count(DAY.plusDays(1))).isEqualTo(1);
        assertThat(count(DAY.minusDays(1))).isZero();
    }

    @Test
    void aWriteOffWaitingForASecondAdministratorIsNotCountedUntilItIsApproved() {
        // At or above 1,500 a write-off needs a second administrator (maker-checker, ADR-043).
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = settings || CAST(? AS jsonb) WHERE tenant_id = ?")
                .params("{\"approval_thresholds_minor\": {\"retail_sale_write_off\": 1500}}", t.tenantId())
                .update();
        // Two active administrators, so a second person exists to approve it (with one, a write-off goes through
        // alone).
        for (int i = 0; i < 2; i++) {
            UUID admin = UUID.randomUUID();
            TestDatabase.owner()
                    .sql(
                            "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', ?, ?,"
                                    + " 'active')")
                    .params(
                            admin,
                            t.tenantId(),
                            "Test Admin 0" + i,
                            "test.admin" + i + "." + admin + "@example.invalid")
                    .update();
            TestDatabase.owner()
                    .sql(
                            "INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, granted_by) VALUES (?, ?, ?,"
                                    + " 'tenant_admin', ?)")
                    .params(UUID.randomUUID(), t.tenantId(), admin, admin)
                    .update();
        }
        UUID big = creditSale(2);
        ResponseEntity<JsonNode> asked = writeOff(big);
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(asked.getBody().get("executed").asBoolean()).isFalse();
        // Asked for, not done: no row, nothing on the card or in the records.
        assertThat(count(DAY)).isZero();
        ResponseEntity<JsonNode> records = api.call(
                HttpMethod.GET,
                "/controls/records?metric=credit_writeoffs&from=" + DAY + "&to=" + DAY,
                null,
                CONTROLS,
                "*",
                Map.of());
        assertThat(records.getBody().get("items")).isEmpty();

        RetailTestSupport checker = new RetailTestSupport(http, t);
        ResponseEntity<JsonNode> decided = http.exchange(
                "/api/v1/approvals/" + asked.getBody().get("approval_id").asString() + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "Checked the debt"), checker.headers(WRITE_OFF, "*", Map.of())),
                JsonNode.class);
        assertThat(decided.getStatusCode()).as("%s", decided.getBody()).isEqualTo(HttpStatus.OK);
        // Approved: written off now, on the day of the approval, and counted.
        assertThat(stored(big)).isEqualTo(DAY);
        assertThat(count(DAY)).isEqualTo(1);
        // The approver of the record is the administrator who made the write-off, not the checker (ADR-087).
        ResponseEntity<JsonNode> after = api.call(
                HttpMethod.GET,
                "/controls/records?metric=credit_writeoffs&from=" + DAY + "&to=" + DAY,
                null,
                CONTROLS,
                "*",
                Map.of());
        JsonNode record = after.getBody().get("items").get(0);
        assertThat(record.get("approver_id").asString()).isEqualTo(api.userId().toString());
        assertThat(record.get("approver_name").asString()).isEqualTo("Test Admin One");
    }

    @Test
    void aWriteOffWhoseEntryWasReversedIsLeftOut() throws Exception {
        UUID sale = creditSale(1);
        assertThat(writeOff(sale).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(count(DAY)).isEqualTo(1);
        // No path reverses a write-off today (V64 is append-only); a reversing entry made by hand, as the superuser
        // with the
        // balance trigger off, stands for one, should a reversal ever be added.
        try (java.sql.Connection c = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        TestDatabase.url(), "postgres", "test-superuser-password")
                .getConnection()) {
            c.setAutoCommit(false);
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("SET LOCAL session_replication_role = replica");
            }
            try (java.sql.PreparedStatement ps =
                    c.prepareStatement("INSERT INTO journal_entries (id, tenant_id, branch_id,"
                            + " entry_no, entry_date, period_id, reference, memo, source_module, source_type, source_id,"
                            + " reverses_entry_id, created_by) SELECT gen_random_uuid(), e.tenant_id, e.branch_id, e.entry_no ||"
                            + " 'R', e.entry_date, e.period_id, e.reference, e.memo, e.source_module, e.source_type, e.source_id,"
                            + " e.id, e.created_by FROM journal_entries e JOIN retail_sale_writeoffs w ON w.tenant_id ="
                            + " e.tenant_id AND w.journal_entry_id = e.id WHERE w.sale_id = ?")) {
                ps.setObject(1, sale);
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            c.commit();
        }
        assertThat(count(DAY)).isZero();
    }
}
