package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.cashbook.CashbookTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Entries kept on the phone offline across days, with the server's clock moved on (issue #812, second review of #839): an
 * offline expense that waits for approval and is approved two business days later stays on the day it was made; and a
 * seller's savings made offline on the last day of a month and sent on the first day of the next are kept on their day,
 * since the phone dated them within the 7 days. Fabricated names and amounts.
 */
@Import(RetailOfflineEntriesClockIT.Clocks.class)
class RetailOfflineEntriesClockIT extends IntegrationTest {

    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");

    /** The last day of a month, 15:00 in Kampala. */
    static final Instant MONTH_END =
            LocalDate.of(2026, 10, 31).atTime(15, 0).atZone(KAMPALA).toInstant();

    static final MutableClock CLOCK = new MutableClock(MONTH_END);

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        Clock mutableClock() {
            return CLOCK;
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    UUID hq;

    @BeforeEach
    void setUp() {
        CLOCK.set(MONTH_END);
        t = TestDatabase.tenant("offline-clock", false, true);
        cb = new CashbookTestSupport(http, t);
        hq = t.headOffice();
    }

    Map<String, Object> offline(Instant made) {
        return Map.of(
                "occurred_at",
                made.toString(),
                "online_as_of",
                made.minus(Duration.ofHours(1)).toString());
    }

    @Test
    void anOfflineExpenseApprovedTwoBusinessDaysLaterStaysOnTheDayItWasMade() {
        UUID[] item = cb.categoryWithItem("Test Transport", "Test Fuel", false);
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("category_id", item[0]);
        body.put("item_id", item[1]);
        body.put("amount_minor", 5_000);
        body.put("offline", offline(MONTH_END.minus(Duration.ofHours(2))));
        ResponseEntity<JsonNode> asked =
                cb.api.postKeyed("/expenses", body, ALL, "*", UUID.randomUUID().toString());
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.ACCEPTED);

        // Two business days later the administrator approves it.
        CLOCK.set(MONTH_END.plus(Duration.ofDays(2)));
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
        assertThat(dated).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    // JUSTIFICATION-A3: the lead's decision 3 on the second review of #839 names this exact case; one test of it.
    /**
     * The lead's decision 3 on the second review of #839: an expense saved online at 23:58 whose answer never came is sent
     * again the next morning with when it was made, under the same key, and stays on its own day; a send of the same request
     * again then answers the same expense.
     */
    @Test
    void anExpenseSavedAt2358WhoseAnswerWasLostIsSentTheNextMorningAndStaysOnItsDay() {
        UUID[] item = cb.categoryWithItem("Test Transport", "Test Fuel", false);
        Instant saved =
                LocalDate.of(2026, 10, 31).atTime(23, 58).atZone(KAMPALA).toInstant();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("category_id", item[0]);
        body.put("item_id", item[1]);
        body.put("amount_minor", 700);
        // The request at 23:58 never reached the server; the phone kept it with the moment the save began.
        body.put(
                "offline",
                Map.of(
                        "occurred_at",
                        saved.toString(),
                        "online_as_of",
                        saved.minus(Duration.ofMinutes(1)).toString()));
        String key = UUID.randomUUID().toString();
        CLOCK.set(LocalDate.of(2026, 11, 1).atTime(7, 0).atZone(KAMPALA).toInstant());
        ResponseEntity<JsonNode> sent = cb.api.postKeyed("/expenses", body, CASHIER_WITH_EXPENSES, hq.toString(), key);
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(sent.getBody().get("business_date").asString()).isEqualTo("2026-10-31");
        ResponseEntity<JsonNode> again = cb.api.postKeyed("/expenses", body, CASHIER_WITH_EXPENSES, hq.toString(), key);
        assertThat(RetailTestSupport.id(again)).isEqualTo(RetailTestSupport.id(sent));
    }

    // JUSTIFICATION-A3: issue #912 moves this test unchanged in intent from RetailOfflineEntriesIT onto this clock.
    /**
     * Review of #839: a banking or withdrawal time sent with an offline entry is not used; the phone's judged time is. On this
     * class's clock (issue #912): made 10 minutes before 15:00 Kampala, so the banking never closes a day before the
     * withdrawal's, as it did on the wall clock just after midnight.
     */
    @Test
    void aTimeSentWithAnOfflineBankingOrWithdrawalIsNotUsed() {
        Instant made = MONTH_END.minus(Duration.ofMinutes(10));
        String twentyDaysAgo = MONTH_END.minus(Duration.ofDays(20)).toString();
        for (String path : java.util.List.of("/bankings", "/withdrawals")) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("branch_id", hq);
            if (path.equals("/bankings")) {
                b.put("amount_minor", 3_000);
                b.put("reference", "Test slip 01");
                b.put("banked_at", twentyDaysAgo);
            } else {
                b.put("amount_minor", 1_000);
                b.put("purpose", "Test purpose");
                b.put("withdrawn_at", twentyDaysAgo);
            }
            b.put("offline", offline(made));
            ResponseEntity<JsonNode> r = cb.api.postKeyed(
                    path,
                    b,
                    RetailOfflineEntriesIT.SELLER,
                    hq.toString(),
                    UUID.randomUUID().toString());
            assertThat(r.getStatusCode()).as("%s %s", path, r.getBody()).isEqualTo(HttpStatus.CREATED);
            String at = r.getBody()
                    .get(path.equals("/bankings") ? "banked_at" : "withdrawn_at")
                    .asString();
            assertThat(Instant.parse(at)).as(path).isEqualTo(made);
            // Issue #856: the day the entry was made on the business clock.
            String madeOn = LocalDate.ofInstant(made, KAMPALA).toString();
            assertThat(r.getBody().get("business_date").asString()).as(path).isEqualTo(madeOn);
        }
    }

    /** A seller with the till's cash book (issue #799), scoped to the head office, with no backdate permission. */
    static final String CASHIER_WITH_EXPENSES = CashbookTestSupport.CASHIER;

    @Test
    void aSellersSavingsMadeOfflineOnTheLastDayOfAMonthAreKeptOnThatDayWhenSentOnTheFirst() {
        UUID product = cb.api.product("TEST-812-C", 1_000, 1_500);
        cb.api.stockUp(hq, product, "50");
        assertThat(cb.sale(hq, "cash", product, "10", LocalDate.of(2026, 10, 31))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        theSwitch(true);
        // The seller reads the day's suggestion on the 31st, then the phone goes offline and the savings wait on it.
        RetailTestSupport seller = new RetailTestSupport(http, t);
        String sellerPerms = RetailTestSupport.SALES;
        ResponseEntity<JsonNode> s = seller.call(
                HttpMethod.GET,
                "/savings/sales-staff/suggestion?branch_id=" + hq + "&date=2026-10-31",
                null,
                sellerPerms,
                hq.toString(),
                Map.of());
        assertThat(s.getStatusCode()).as("%s", s.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("suggestion_token", s.getBody().get("suggestion_token").asString());
        body.put("offline", offline(MONTH_END.plus(Duration.ofMinutes(30))));

        // Sent at 08:00 on the 1st.
        CLOCK.set(LocalDate.of(2026, 11, 1).atTime(8, 0).atZone(KAMPALA).toInstant());
        ResponseEntity<JsonNode> saved = seller.postKeyed(
                "/savings/sales-staff",
                body,
                sellerPerms,
                hq.toString(),
                UUID.randomUUID().toString());
        assertThat(saved.getStatusCode()).as("%s", saved.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getBody().get("business_date").asString()).isEqualTo("2026-10-31");
    }

    /** The Owner turns "Salespeople can see and change the savings amount" on (ADR-115), as RetailSalesStaffSavingsIT does. */
    void theSwitch(boolean on) {
        UUID owner = Api.staff(t, Api.email("oc-owner"), new Role("tenant_admin", null));
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = true WHERE id = ?")
                .param(owner)
                .update();
        HttpHeaders h = cb.api.headers("core.settings.manage,core.settings.read", "*", Map.of());
        h.set("X-Dev-User-Id", owner.toString());
        int version = http.exchange("/api/v1/settings", HttpMethod.GET, new HttpEntity<>(h), JsonNode.class)
                .getBody()
                .get("version")
                .asInt();
        h.add("If-Match", "\"" + version + "\"");
        ResponseEntity<JsonNode> r = http.exchange(
                "/api/v1/settings",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("salespeople_see_savings_amount", on), h),
                JsonNode.class);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }
}
