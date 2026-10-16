package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.notifications.SmsEvents.MemberSms;
import com.rincoltech.bms.core.notifications.SmsEvents.Result;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.SmsTenants;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The member event SMS of issue #221 (FR-NTF-01, FR-NTF-02, FR-NTF-17, FR-NTF-19, FR-NTF-20), from the port the
 * verticals call to the provider: what is queued, for whom, once; what is never queued; that a failure to queue never
 * fails the caller's transaction; and, through the real channel with a recording fake provider and a clock the test moves,
 * the exact text of every event, its segments and cost, the escaping of values, the opt-out, the backlog, the sending window
 * and the caps. Nothing here depends on the wall clock: every test names its own Kampala day (2031) and fixes the clock.
 */
class SmsEventsIT extends IntegrationTest {

    /** 2031-06-10 07:00 UTC is 10:00 in Kampala (UTC+3 all year). */
    static final Instant BASE = Instant.parse("2031-06-10T07:00:00Z");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    OutboxTemplates templates;

    @Autowired
    TenantSettings tenantSettings;

    @Autowired
    SmsTenantConfig tenantConfig;

    @Autowired
    SmsRecipientHash hasher;

    @Autowired
    TenantJobs tenantJobs;

    @Autowired
    CurrentTenant currentTenant;

    /** One configuration of the events port and the channel around it, on its own clock. */
    final class Rig {
        final SmsChannelIT.MovableClock clock;
        final BusinessClock business;
        final RecordingSmsProvider provider = new RecordingSmsProvider();
        final JdbcOutbox outbox;
        final SmsEventsService events;
        final SmsOutboxChannel channel;
        final OutboxDispatcher dispatcher;

        Rig(Instant start) {
            this(start, null);
        }

        /** @param replacement an outbox that fails, standing in for the real one in the events port; null for the real one */
        Rig(Instant start, Outbox replacement) {
            this.clock = new SmsChannelIT.MovableClock(start);
            this.business = new BusinessClock(clock);
            this.outbox = new JdbcOutbox(jdbc, mapper, business, templates, tenantSettings);
            this.events = new SmsEventsService(
                    replacement == null ? outbox : replacement,
                    jdbc,
                    tenantConfig,
                    currentTenant,
                    tenantSettings,
                    business,
                    transactionManager,
                    3);
            SmsSettings settings = new SmsSettings(
                    "test", SmsSettings.Mode.LIVE, SmsSettings.Kind.FAKE, Set.of(), 100_000, null, null, null);
            this.channel = new SmsOutboxChannel(
                    jdbc,
                    transactionManager,
                    settings,
                    provider,
                    templates,
                    business,
                    tenantConfig,
                    tenantJobs,
                    hasher,
                    outbox);
            this.dispatcher =
                    new OutboxDispatcher(jdbc, transactionManager, templates, mapper, business, List.of(channel));
            channel.enabled();
        }

        int dispatch() {
            return dispatcher.dispatch(List.of(channel), 50);
        }

        void at(Instant now) {
            clock.now = now;
        }
    }

    @BeforeEach
    void clean() {
        TestDatabase.owner().sql("DELETE FROM sms_channel_state").update();
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET status = 'failed', last_error = 'test_cleanup', failed_at = now()"
                        + " WHERE channel = 'sms' AND status = 'pending'")
                .update();
    }

    /** A tenant whose SMS the operator and its own administrator switched on, with a generous cap. */
    TestDatabase.Fixture tenant(int dailyCap) {
        TestDatabase.Fixture t = TestDatabase.tenant("smsev", true);
        SmsTenants.operator(t.tenantId(), true, dailyCap);
        SmsTenants.enable(t.tenantId());
        return t;
    }

    <T> T inTenant(TestDatabase.Fixture t, Supplier<T> work) {
        return TenantContext.callAs(
                t.tenantId(), () -> new TransactionTemplate(transactionManager).execute(tx -> work.get()));
    }

    static final Map<String, String> RECEIPT = Map.of(
            "loan_no", "LN-HQ-000123",
            "currency", "UGX",
            "amount", "400,000",
            "date", "15/10/2026",
            "receipt_no", "RC-HQ-000042");

    Result queue(
            Rig rig,
            TestDatabase.Fixture t,
            UUID member,
            String phone,
            String template,
            Map<String, String> params,
            String ref,
            String category) {
        return inTenant(t, () -> rig.events.queue(new MemberSms(template, member, phone, params, ref, category)));
    }

    Result receipt(Rig rig, TestDatabase.Fixture t, UUID member, String phone, String ref) {
        return queue(rig, t, member, phone, "loan.repayment_received", RECEIPT, ref, null);
    }

    List<Map<String, Object>> rows(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql(
                        "SELECT idempotency_key, recipient, template_key, category, subject_type, subject_id, status,"
                                + " last_error, params::text AS params, created_at, next_attempt_at, expires_at"
                                + " FROM notification_outbox WHERE channel = 'sms' AND tenant_id = ? ORDER BY created_at, idempotency_key")
                .param(t.tenantId())
                .query()
                .listOfRows();
    }

    static Instant at(Object timestamp) {
        return timestamp instanceof Timestamp ts ? ts.toInstant() : ((OffsetDateTime) timestamp).toInstant();
    }

    // ---- What is queued, once ----------------------------------------------------------------------

    @Test
    void frNtf02_aQueuedEventIsOneRowForTheMembersNumberAndTheSameEventNeverQueuesTwice() {
        Rig rig = new Rig(BASE);
        TestDatabase.Fixture t = tenant(1000);
        UUID member = UUID.randomUUID();
        String ref = UUID.randomUUID().toString();

        assertThat(receipt(rig, t, member, "0700000111", ref)).isEqualTo(Result.QUEUED);
        // A retry, a replay of the event, the same job twice: the same row, so nothing new.
        assertThat(receipt(rig, t, member, "0700000111", ref)).isEqualTo(Result.NOT_QUEUED);
        assertThat(receipt(rig, t, member, "+256 700 000 111", ref)).isEqualTo(Result.NOT_QUEUED);

        List<Map<String, Object>> rows = rows(t);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        // The number was typed as 0700000111 and is stored as the E.164 number the channel will use.
        assertThat(row.get("recipient")).isEqualTo("+256700000111");
        assertThat(row.get("template_key")).isEqualTo("loan.repayment_received");
        assertThat(row.get("category")).isEqualTo("transactional");
        assertThat(row.get("subject_type")).isEqualTo("member");
        assertThat(row.get("subject_id")).isEqualTo(member);
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("idempotency_key"))
                .isEqualTo("sms:loan.repayment_received:" + t.tenantId() + ":" + ref + ":" + member);
        // A receipt lives an hour.
        assertThat(at(row.get("expires_at")))
                .isEqualTo(at(row.get("created_at")).plus(Duration.ofHours(1)));
        // The tenant's own name is added by the port; no name of the member is anywhere.
        assertThat((String) row.get("params")).contains("\"tenant_name\"").doesNotContain("Test Borrower");

        // Another event, or another member for the same event, is another row.
        assertThat(receipt(rig, t, member, "0700000111", UUID.randomUUID().toString()))
                .isEqualTo(Result.QUEUED);
        assertThat(receipt(rig, t, UUID.randomUUID(), "0700000112", ref)).isEqualTo(Result.QUEUED);
        assertThat(rows(t)).hasSize(3);
    }

    @Test
    void frNtf19_noRowIsQueuedForANumberWeWouldNotText() {
        Rig rig = new Rig(BASE);
        TestDatabase.Fixture t = tenant(1000);
        for (String phone :
                new String[] {null, "", "   ", "2125550123", "+14155550123", "700000111", "070000011", "abc"}) {
            assertThat(receipt(
                            rig, t, UUID.randomUUID(), phone, UUID.randomUUID().toString()))
                    .as("phone %s", phone)
                    .isEqualTo(Result.NO_PHONE);
        }
        assertThat(rows(t)).isEmpty();
    }

    @Test
    void frNtf20_nothingIsQueuedWhileEitherSwitchIsOffOrACapIsZero() {
        Rig rig = new Rig(BASE);
        TestDatabase.Fixture t = TestDatabase.tenant("smsev", true);
        UUID member = UUID.randomUUID();

        // Neither switch: the port says so and queues nothing.
        assertThat(inTenant(t, rig.events::active)).isFalse();
        assertThat(queueWhenActive(rig, t, member)).isEqualTo(Result.NOT_QUEUED);
        // The operator on, the tenant's own switch off.
        SmsTenants.operator(t.tenantId(), true, 100);
        assertThat(inTenant(t, rig.events::active)).isFalse();
        assertThat(queueWhenActive(rig, t, member)).isEqualTo(Result.NOT_QUEUED);
        // Both on: a row.
        SmsTenants.enable(t.tenantId());
        assertThat(inTenant(t, rig.events::active)).isTrue();
        assertThat(queueWhenActive(rig, t, member)).isEqualTo(Result.QUEUED);
        assertThat(rows(t)).hasSize(1);

        // A cap of 0 is stop, never "no cap": the operator's, the tenant's daily and the tenant's monthly.
        SmsTenants.operator(t.tenantId(), true, 0);
        assertThat(inTenant(t, rig.events::active)).isFalse();
        assertThat(queueWhenActive(rig, t, member)).isEqualTo(Result.NOT_QUEUED);
        SmsTenants.operator(t.tenantId(), true, 100);
        SmsTenants.set(t.tenantId(), Map.of("daily_cap", 0));
        assertThat(inTenant(t, rig.events::active)).isFalse();
        assertThat(queueWhenActive(rig, t, member)).isEqualTo(Result.NOT_QUEUED);
        SmsTenants.set(t.tenantId(), Map.of("daily_cap", 5, "monthly_cap", 0));
        assertThat(inTenant(t, rig.events::active)).isFalse();
        SmsTenants.set(t.tenantId(), Map.of("daily_cap", 5, "monthly_cap", 50));
        assertThat(inTenant(t, rig.events::active)).isTrue();
        assertThat(rows(t)).as("only the one queued while both were on").hasSize(1);
    }

    Result queueWhenActive(Rig rig, TestDatabase.Fixture t, UUID member) {
        return inTenant(
                t,
                () -> rig.events.queueWhenActive(
                        "loan.closed",
                        member,
                        UUID.randomUUID().toString(),
                        Outbox.TRANSACTIONAL,
                        null,
                        () -> "0700000113",
                        () -> Map.of("loan_no", "LN-HQ-000123")));
    }

    @Test
    void frNtf17_theBalanceIsDroppedUnlessTheTenantAskedForIt() {
        Rig rig = new Rig(BASE);
        TestDatabase.Fixture t = tenant(1000);
        Map<String, String> withBalance = new java.util.HashMap<>(RECEIPT);
        withBalance.put("balance", "600,000");

        assertThat(queue(rig, t, UUID.randomUUID(), "0700000114", "loan.repayment_received", withBalance, "a", null))
                .isEqualTo(Result.QUEUED);
        assertThat((String) rows(t).getFirst().get("params")).doesNotContain("balance");

        SmsTenants.set(t.tenantId(), Map.of("include_balance", true));
        assertThat(queue(rig, t, UUID.randomUUID(), "0700000115", "loan.repayment_received", withBalance, "b", null))
                .isEqualTo(Result.QUEUED);
        assertThat(rows(t).get(1).get("params").toString())
                .contains("\"balance\"")
                .contains("600,000");
    }

    // ---- A failure to queue never fails the business transaction -----------------------------------------

    /** An outbox that fails the way a database can: with an error inside the statement, or before any statement. */
    static final class FailingOutbox implements Outbox {
        final JdbcClient jdbc;
        final boolean databaseError;

        FailingOutbox(JdbcClient jdbc, boolean databaseError) {
            this.jdbc = jdbc;
            this.databaseError = databaseError;
        }

        @Override
        public boolean enqueue(Message message) {
            if (databaseError) {
                jdbc.sql("SELECT 1 / 0").query(Integer.class).single();
            }
            throw new IllegalStateException("outbox down for " + message.templateKey());
        }

        @Override
        public int cancelUnclaimed(String eventRef) {
            throw new IllegalStateException("outbox down");
        }

        @Override
        public int countSince(String throttleKey, String templateKey, Instant since, Instant until) {
            return 0;
        }
    }

    @Test
    void frNtf01_aFailureToWriteTheRowNeverFailsOrAbortsTheCallersTransaction() {
        for (boolean databaseError : new boolean[] {false, true}) {
            Rig rig = new Rig(BASE, new FailingOutbox(jdbc, databaseError));
            TestDatabase.Fixture t = tenant(1000);
            Logger logger = (Logger) LoggerFactory.getLogger(SmsEventsService.class);
            ListAppender<ILoggingEvent> logs = new ListAppender<>();
            logs.start();
            logger.addAppender(logs);
            try {
                // The repayment of the test: a business row written first, the SMS fails, a second business row after
                // it,
                // then the transaction commits. With the failure inside a savepoint all of it survives.
                String marker = "test.sms.marker:" + UUID.randomUUID();
                Result result = inTenant(t, () -> {
                    Outbox real = rig.outbox;
                    real.enqueue(new Outbox.Message(
                            Outbox.TELEGRAM,
                            Outbox.OPERATOR_CHAT,
                            "sms.cap_alert",
                            Map.of("day", "2031-06-10", "scope", "platform", "state", "80 percent", "cap", "1"),
                            marker + ":before"));
                    Result r = rig.events.queue(new MemberSms(
                            "loan.repayment_received",
                            UUID.randomUUID(),
                            "0700000116",
                            RECEIPT,
                            "ref-" + databaseError));
                    real.enqueue(new Outbox.Message(
                            Outbox.TELEGRAM,
                            Outbox.OPERATOR_CHAT,
                            "sms.cap_alert",
                            Map.of("day", "2031-06-10", "scope", "platform", "state", "100 percent", "cap", "1"),
                            marker + ":after"));
                    return r;
                });
                assertThat(result).isEqualTo(Result.FAILED);
                assertThat(TestDatabase.owner()
                                .sql(
                                        "SELECT idempotency_key FROM notification_outbox WHERE idempotency_key LIKE ? ORDER BY 1")
                                .param(marker + "%")
                                .query(String.class)
                                .list())
                        .as("databaseError=%s", databaseError)
                        .containsExactly(marker + ":after", marker + ":before");
                assertThat(rows(t)).isEmpty();
                // The log says which event, and why in a word; it holds no number and no values.
                assertThat(logs.list)
                        .anySatisfy(e -> assertThat(e.getFormattedMessage())
                                .contains("loan.repayment_received")
                                .doesNotContain("700000116")
                                .doesNotContain("RC-HQ-000042"));
            } finally {
                logger.detachAppender(logs);
            }
        }
    }

    // ---- The real channel: text, segments, cost, escaping --------------------------------------------------

    /** The exact text of every event with fabricated values: the render test of the acceptance criteria. */
    @Test
    void frNtf02_everyEventIsSentWithItsExactDefaultTextWithinOneSegmentAndTheCostIsLogged() {
        Rig rig = new Rig(BASE);
        TestDatabase.Fixture t = tenant(1000);
        TestDatabase.owner()
                .sql("UPDATE tenants SET name = 'Test Lender' WHERE id = ?")
                .param(t.tenantId())
                .update();
        Map<String, Map<String, String>> params = new java.util.LinkedHashMap<>();
        params.put(
                "loan.disbursed",
                Map.of(
                        "loan_no",
                        "LN-HQ-000123",
                        "currency",
                        "UGX",
                        "amount",
                        "500,000",
                        "date",
                        "15/10/2026",
                        "next_amount",
                        "600,000",
                        "next_due_date",
                        "15/11/2026"));
        params.put("loan.repayment_received", RECEIPT);
        params.put("loan.closed", Map.of("loan_no", "LN-HQ-000123"));
        params.put(
                "investment.funded",
                Map.of(
                        "account_no",
                        "IV000007",
                        "currency",
                        "UGX",
                        "amount",
                        "2,000,000",
                        "date",
                        "15/10/2026",
                        "maturity_date",
                        "15/10/2027",
                        "receipt_no",
                        "RC-HQ-000043"));
        params.put(
                "investment.maturing",
                Map.of(
                        "account_no",
                        "IV000007",
                        "currency",
                        "UGX",
                        "amount",
                        "2,000,000",
                        "maturity_date",
                        "15/10/2027"));
        params.put(
                "investment.paid_out",
                Map.of(
                        "account_no",
                        "IV000007",
                        "currency",
                        "UGX",
                        "amount",
                        "2,240,000",
                        "date",
                        "15/10/2027",
                        "voucher_no",
                        "VC-HQ-000044"));
        Map<String, String> expected = Map.of(
                "loan.disbursed",
                "Test Lender: Loan LN-HQ-000123: UGX 500,000 paid out on 15/10/2026. First payment UGX 600,000 due 15/11/2026.",
                "loan.repayment_received",
                "Test Lender: Received UGX 400,000 on 15/10/2026 for loan LN-HQ-000123. Receipt RC-HQ-000042.",
                "loan.closed",
                "Test Lender: Loan LN-HQ-000123 is fully paid. Thank you.",
                "investment.funded",
                "Test Lender: Investment IV000007 of UGX 2,000,000 received on 15/10/2026. Matures 15/10/2027. Receipt RC-HQ-000043.",
                "investment.maturing",
                "Test Lender: Investment IV000007 of UGX 2,000,000 matures on 15/10/2027. Please contact us about your instruction.",
                "investment.paid_out",
                "Test Lender: Paid UGX 2,240,000 on investment IV000007 on 15/10/2027. Voucher VC-HQ-000044.");
        UUID member = UUID.randomUUID();
        int n = 0;
        for (Map.Entry<String, Map<String, String>> e : params.entrySet()) {
            boolean reminder = e.getKey().equals("investment.maturing");
            assertThat(queue(
                            rig,
                            t,
                            member,
                            "0700000120",
                            e.getKey(),
                            e.getValue(),
                            "ref-" + n++,
                            reminder ? Outbox.REMINDER : null))
                    .isEqualTo(Result.QUEUED);
        }
        // The reminder is held for the window; at 10:00 Kampala it is inside it, so all six go.
        assertThat(rig.dispatch()).isEqualTo(6);

        assertThat(rig.provider.sent()).hasSize(6);
        for (RecordingSmsProvider.Sent sent : rig.provider.sent()) {
            assertThat(sent.to()).isEqualTo("+256700000120");
            assertThat(expected.values()).contains(sent.body());
            assertThat(SmsSegments.count(sent.body()).segments())
                    .as(sent.body())
                    .isEqualTo(1);
            assertThat(SmsSegments.count(sent.body()).encoding()).isEqualTo(SmsSegments.Encoding.GSM7);
        }
        assertThat(rig.provider.sent().stream()
                        .map(RecordingSmsProvider.Sent::body)
                        .toList())
                .containsExactlyInAnyOrderElementsOf(expected.values());
        // The delivery log: one row per message with our segments, the provider's, and the cost as integer minor units.
        List<Map<String, Object>> deliveries = TestDatabase.owner()
                .sql(
                        "SELECT template_key, status, segments, provider_segments, cost_minor, currency, cost_raw, category,"
                                + " recipient_masked FROM sms_deliveries WHERE tenant_id = ? ORDER BY template_key")
                .param(t.tenantId())
                .query()
                .listOfRows();
        assertThat(deliveries).hasSize(6);
        for (Map<String, Object> d : deliveries) {
            assertThat(d.get("status")).isEqualTo("sent");
            assertThat(d.get("segments")).isEqualTo(1);
            assertThat(d.get("provider_segments")).isEqualTo(1);
            assertThat(d.get("cost_minor")).isEqualTo(32L);
            assertThat(d.get("currency")).isEqualTo("UGX");
            assertThat(d.get("recipient_masked")).isEqualTo("+256*****0120");
        }
        assertThat(deliveries.stream().map(d -> d.get("category")).distinct().toList())
                .containsExactlyInAnyOrder("transactional", "reminder");
    }

    @Test
    void frNtf03_aBusinessNameWithMarkupQuotesNewlinesAndOtherScriptsIsSentAsPlainTextOnOneLine() {
        Rig rig = new Rig(BASE);
        TestDatabase.Fixture t = tenant(1000);
        // Markup characters, both quotes, a line feed and a tab, an invisible character, a placeholder look-alike, a
        // right-to-left override, and a name in another script with an emoji: none is escaped or interpreted.
        String name = "A&B <Sons> \"Ltd\" 'x'\nline2\t​z {amount} ‮evil Kampala ก😀";
        TestDatabase.owner()
                .sql("UPDATE tenants SET name = ? WHERE id = ?")
                .params(name, t.tenantId())
                .update();

        assertThat(queue(
                        rig,
                        t,
                        UUID.randomUUID(),
                        "0700000130",
                        "loan.closed",
                        Map.of("loan_no", "LN-HQ-000123"),
                        "esc",
                        null))
                .isEqualTo(Result.QUEUED);
        assertThat(rig.dispatch()).isEqualTo(1);

        String body = rig.provider.sent().getFirst().body();
        assertThat(body)
                .isEqualTo("A&B <Sons> \"Ltd\" 'x' line2 z {amount} evil Kampala ก😀:"
                        + " Loan LN-HQ-000123 is fully paid. Thank you.");
        assertThat(body)
                .doesNotContain("\n")
                .doesNotContain("\t")
                .doesNotContain("​")
                .doesNotContain("‮");
        // Another script puts the whole body in UCS-2: 70 units a segment, an emoji two units.
        SmsSegments.Count count = SmsSegments.count(body);
        assertThat(count.encoding()).isEqualTo(SmsSegments.Encoding.UCS2);
        assertThat(count.units()).isEqualTo(body.length());
        assertThat(count.segments()).isEqualTo(2);
        Map<String, Object> d = TestDatabase.owner()
                .sql("SELECT segments, status FROM sms_deliveries WHERE tenant_id = ?")
                .param(t.tenantId())
                .query()
                .singleRow();
        assertThat(d.get("segments")).isEqualTo(2);
        assertThat(d.get("status")).isEqualTo("sent");
    }

    @Test
    void frNtf03_emojiInAValueCountTwoUnitsAndTheSegmentsAreRecordedExactly() {
        Rig rig = new Rig(BASE);
        TestDatabase.Fixture t = tenant(1000);
        // 24 emoji are 48 units; with the text around them the body is UCS-2 and crosses 70 units, so two segments.
        TestDatabase.owner()
                .sql("UPDATE tenants SET name = ? WHERE id = ?")
                .params("😀".repeat(24), t.tenantId())
                .update();
        assertThat(queue(
                        rig,
                        t,
                        UUID.randomUUID(),
                        "0700000131",
                        "loan.closed",
                        Map.of("loan_no", "LN-HQ-000123"),
                        "e2",
                        null))
                .isEqualTo(Result.QUEUED);
        assertThat(rig.dispatch()).isEqualTo(1);
        String body = rig.provider.sent().getFirst().body();
        assertThat(SmsSegments.count(body).units()).isEqualTo(body.length());
        int expected = SmsSegments.count(body).segments();
        assertThat(expected).isGreaterThanOrEqualTo(2);
        assertThat(TestDatabase.owner()
                        .sql("SELECT segments FROM sms_deliveries WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(expected);
    }

    /**
     * The tenant's own wording for a new event is used, with the values cleaned; a broken override (it no longer validates)
     * never costs the member the receipt: the default goes. No override ever carries the balance, even for a tenant that
     * asked for it.
     */
    @Test
    void frNtf03_aTenantsOwnWordingIsUsedForTheNewEventsAndABrokenOneFallsBackToTheDefault() {
        Rig rig = new Rig(BASE);
        TestDatabase.Fixture t = tenant(1000);
        TestDatabase.owner()
                .sql("UPDATE tenants SET name = 'Test Lender' WHERE id = ?")
                .param(t.tenantId())
                .update();
        SmsTenants.set(t.tenantId(), Map.of("include_balance", true));
        Map<String, String> withBalance = new java.util.HashMap<>(RECEIPT);
        withBalance.put("balance", "600,000");

        override(
                t,
                "loan.repayment_received",
                "Thank you! {currency} {amount} received for {loan_no} (ref {receipt_no}).");
        assertThat(queue(rig, t, UUID.randomUUID(), "0700000190", "loan.repayment_received", withBalance, "w1", null))
                .isEqualTo(Result.QUEUED);
        assertThat(rig.dispatch()).isEqualTo(1);
        assertThat(rig.provider.sent().getFirst().body())
                .isEqualTo("Thank you! UGX 400,000 received for LN-HQ-000123 (ref RC-HQ-000042).");

        // The wording now names a placeholder this event does not offer: the default is sent, balance and all.
        override(t, "loan.repayment_received", "Paid {amount} on {account_no}");
        assertThat(queue(rig, t, UUID.randomUUID(), "0700000191", "loan.repayment_received", withBalance, "w2", null))
                .isEqualTo(Result.QUEUED);
        assertThat(rig.dispatch()).isEqualTo(1);
        assertThat(rig.provider.sent().get(1).body())
                .isEqualTo(
                        "Test Lender: Received UGX 400,000 on 15/10/2026 for loan LN-HQ-000123. Receipt RC-HQ-000042."
                                + " Balance UGX 600,000.");
    }

    void override(TestDatabase.Fixture t, String event, String body) {
        TestDatabase.owner()
                .sql("INSERT INTO sms_template_overrides (id, tenant_id, event_key, body) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (tenant_id, event_key) DO UPDATE SET body = excluded.body")
                .params(UUID.randomUUID(), t.tenantId(), event, body)
                .update();
    }

    /**
     * A send that ran past its two minute lease can have been withdrawn by a reversal meanwhile. The channel asks once more
     * immediately before the provider call: a row that is no longer pending is not sent, and the delivery log says why.
     */
    @Test
    void frNtf01_aRowCancelledWhileTheSendWasInFlightIsNotSentToTheProvider() throws Exception {
        Rig rig = new Rig(BASE);
        TestDatabase.Fixture t = tenant(1000);
        UUID member = UUID.randomUUID();
        assertThat(receipt(rig, t, member, "0700000195", "late")).isEqualTo(Result.QUEUED);
        Map<String, Object> row = TestDatabase.owner()
                .sql("SELECT id, idempotency_key, created_at, expires_at FROM notification_outbox"
                        + " WHERE tenant_id = ? AND channel = 'sms'")
                .param(t.tenantId())
                .query()
                .singleRow();
        // The reversal withdraws it (nobody holds a lease), as the sender is about to call the provider.
        Integer withdrawn = TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager).execute(tx -> rig.outbox.cancelUnclaimed("late")));
        assertThat(withdrawn).isEqualTo(1);

        OutboxChannel.Result result = rig.channel.send(new OutboxChannel.Delivery(
                "+256700000195",
                null,
                null,
                (String) row.get("idempotency_key"),
                new OutboxChannel.RowContext(
                        (UUID) row.get("id"),
                        t.tenantId(),
                        Outbox.TRANSACTIONAL,
                        "member",
                        member,
                        "loan.repayment_received",
                        java.util.stream.Stream.concat(
                                        RECEIPT.entrySet().stream(),
                                        Map.of("tenant_name", "Test Lender").entrySet().stream())
                                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)),
                        1,
                        at(row.get("created_at")),
                        at(row.get("expires_at")))));

        assertThat(result).isEqualTo(OutboxChannel.Result.refused("cancelled_reversed"));
        assertThat(rig.provider.sent()).as("the provider was not called").isEmpty();
        Map<String, Object> d = delivery(t);
        assertThat(d.get("status")).isEqualTo("failed");
        assertThat(d.get("failure_reason")).isEqualTo("cancelled_reversed");
    }

    // ---- Opt-out, other tenants, backlog --------------------------------------------------------------------

    @Test
    void frNtf14_anOptedOutMemberIsNotTextedAndAnotherTenantsOptOutOrRowsAreNeverMixedIn() {
        Rig rig = new Rig(BASE);
        TestDatabase.Fixture a = tenant(1000);
        TestDatabase.Fixture b = tenant(1000);
        UUID memberA = UUID.randomUUID();
        UUID memberB = UUID.randomUUID();
        // The same number belongs to a member of each tenant. Tenant A opts its member out of everything;
        // tenant B's member is not opted out, and B opts a different number out.
        TestDatabase.owner()
                .sql("INSERT INTO sms_opt_outs (id, tenant_id, member_id, recipient_hash, scope, source)"
                        + " VALUES (?, ?, ?, ?, 'all', 'staff')")
                .params(UUID.randomUUID(), a.tenantId(), memberA, hasher.of("+256700000140"))
                .update();
        TestDatabase.owner()
                .sql("INSERT INTO sms_opt_outs (id, tenant_id, recipient_hash, recipient_masked, scope, source)"
                        + " VALUES (?, ?, ?, '+256*****0999', 'all', 'import')")
                .params(UUID.randomUUID(), b.tenantId(), hasher.of("+256700000999"))
                .update();

        assertThat(receipt(rig, a, memberA, "0700000140", "r1")).isEqualTo(Result.QUEUED);
        assertThat(receipt(rig, b, memberB, "0700000140", "r1")).isEqualTo(Result.QUEUED);
        assertThat(rows(a)).hasSize(1);
        assertThat(rows(b)).hasSize(1);
        assertThat(rows(a).getFirst().get("idempotency_key"))
                .isNotEqualTo(rows(b).getFirst().get("idempotency_key"));
        assertThat(rig.dispatch()).isEqualTo(2);

        // One reached the phone (tenant B's); tenant A's was blocked and logged as such, in A's own log.
        assertThat(rig.provider.sent()).hasSize(1);
        assertThat(delivery(a).get("status")).isEqualTo("blocked_optout");
        assertThat(delivery(b).get("status")).isEqualTo("sent");
        assertThat(delivery(a).get("tenant_id")).isEqualTo(a.tenantId());
        assertThat(delivery(b).get("tenant_id")).isEqualTo(b.tenantId());
    }

    Map<String, Object> delivery(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql("SELECT * FROM sms_deliveries WHERE tenant_id = ?")
                .param(t.tenantId())
                .query()
                .singleRow();
    }

    @Test
    void frNtf17_aRowQueuedBeforeTheTenantSwitchedSmsOnIsNeverSent() {
        Rig rig = new Rig(BASE);
        TestDatabase.Fixture t = tenant(1000);
        UUID member = UUID.randomUUID();
        assertThat(receipt(rig, t, member, "0700000150", "old")).isEqualTo(Result.QUEUED);

        // The administrator switches SMS off and on again a minute later: the acknowledgement time moves past the row.
        SmsTenants.set(
                t.tenantId(), Map.of("acknowledged_at", BASE.plusSeconds(60).toString()));
        rig.at(BASE.plusSeconds(120));
        assertThat(receipt(rig, t, member, "0700000150", "new")).isEqualTo(Result.QUEUED);

        rig.dispatch();
        assertThat(rig.provider.sent()).hasSize(1);
        Map<String, Object> old = TestDatabase.owner()
                .sql("SELECT status, last_error FROM notification_outbox WHERE idempotency_key = ?")
                .param("sms:loan.repayment_received:" + t.tenantId() + ":old:" + member)
                .query()
                .singleRow();
        assertThat(old.get("status")).isEqualTo("failed");
        assertThat(old.get("last_error")).isEqualTo("backlog_dropped");
    }

    // ---- The window and the caps, at the Kampala day boundary ------------------------------------------------

    /**
     * 20:59 and 21:01 UTC are 23:59 and 00:01 in Kampala, either side of its midnight. A reminder is held for the next
     * 08:00 window opening whichever side it was queued on, and its expiry (the end of that window day, 20:00) is later than
     * the deferral; a receipt is not held at all.
     */
    @Test
    void frNtf04_aReminderIsHeldToTheNextWindowFromEitherSideOfKampalaMidnightAndAReceiptIsNot() {
        Instant before = Instant.parse("2031-06-10T20:59:00Z");
        Instant after = Instant.parse("2031-06-10T21:01:00Z");
        Instant opens = Instant.parse("2031-06-11T05:00:00Z");
        Instant closes = Instant.parse("2031-06-11T17:00:00Z");
        Rig rig = new Rig(before);
        TestDatabase.Fixture t = tenant(1000);
        Map<String, String> maturing = Map.of(
                "account_no", "IV000007", "currency", "UGX", "amount", "2,000,000", "maturity_date", "15/10/2027");

        assertThat(queue(
                        rig,
                        t,
                        UUID.randomUUID(),
                        "0700000160",
                        "investment.maturing",
                        maturing,
                        "m1",
                        Outbox.REMINDER))
                .isEqualTo(Result.QUEUED);
        assertThat(receipt(rig, t, UUID.randomUUID(), "0700000161", "c1")).isEqualTo(Result.QUEUED);
        rig.at(after);
        assertThat(queue(
                        rig,
                        t,
                        UUID.randomUUID(),
                        "0700000162",
                        "investment.maturing",
                        maturing,
                        "m2",
                        Outbox.REMINDER))
                .isEqualTo(Result.QUEUED);

        for (Map<String, Object> row : rows(t)) {
            if ("investment.maturing".equals(row.get("template_key"))) {
                assertThat(at(row.get("next_attempt_at")))
                        .as("held to the window opening")
                        .isEqualTo(opens);
                assertThat(at(row.get("expires_at"))).isEqualTo(closes).isAfter(at(row.get("next_attempt_at")));
            } else {
                assertThat(at(row.get("next_attempt_at"))).isEqualTo(before);
            }
        }
        // 21:01 UTC: only the receipt is due, and it goes; the reminders wait.
        assertThat(rig.dispatch()).isEqualTo(1);
        assertThat(rig.provider.sent())
                .extracting(RecordingSmsProvider.Sent::to)
                .containsExactly("+256700000161");
        rig.at(opens.minusSeconds(1));
        assertThat(rig.dispatch()).isZero();
        rig.at(opens);
        assertThat(rig.dispatch()).isEqualTo(2);
        assertThat(rig.provider.sent()).hasSize(3);
    }

    @Test
    void frNtf18_theDailyCapCountsTheKampalaDayAndACapOfZeroSendsNothingEvenWhatWasQueuedBefore() {
        Instant before = Instant.parse("2031-06-12T20:59:00Z");
        Instant after = Instant.parse("2031-06-12T21:01:00Z");
        Rig rig = new Rig(before);
        TestDatabase.Fixture t = tenant(1);

        // Cap 1: receipts may go up to twice the cap in a day. Three queued at 23:59 Kampala: two go, one is held.
        for (int i = 0; i < 3; i++) {
            assertThat(receipt(rig, t, UUID.randomUUID(), "07000001" + (70 + i), "d" + i))
                    .isEqualTo(Result.QUEUED);
        }
        rig.dispatch();
        rig.dispatch();
        rig.dispatch();
        assertThat(rig.provider.sent()).hasSize(2);
        // Two minutes later it is the next Kampala day: the count starts again, and a new receipt goes.
        rig.at(after);
        assertThat(receipt(rig, t, UUID.randomUUID(), "0700000180", "d9")).isEqualTo(Result.QUEUED);
        rig.dispatch();
        assertThat(rig.provider.sent()).hasSize(3);

        // The tenant sets its own cap to 0 after a row was queued: that row is never sent.
        assertThat(receipt(rig, t, UUID.randomUUID(), "0700000181", "z")).isEqualTo(Result.QUEUED);
        SmsTenants.set(t.tenantId(), Map.of("daily_cap", 0));
        rig.dispatch();
        assertThat(rig.provider.sent()).as("a cap of 0 is stop").hasSize(3);
        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM notification_outbox WHERE idempotency_key = ?")
                        .param("sms:loan.repayment_received:" + t.tenantId() + ":z:" + memberOf(t, "z"))
                        .query(String.class)
                        .single())
                .isEqualTo("pending");
    }

    UUID memberOf(TestDatabase.Fixture t, String ref) {
        return TestDatabase.owner()
                .sql("SELECT subject_id FROM notification_outbox WHERE tenant_id = ? AND idempotency_key LIKE ?")
                .params(t.tenantId(), "%:" + ref + ":%")
                .query(UUID.class)
                .single();
    }
}
