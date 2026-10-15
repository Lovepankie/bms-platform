package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.SmsTenants;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The SMS channel end to end on the outbox (issue #212, ADR-036, FR-NTF-12 to FR-NTF-18), with a
 * fake provider, a sink and a clock the test moves: tenant on the row, the modes, the delivery
 * and cost log, outcomes (sent, deferred, unknown, refused, clean failure) and their retry rules,
 * idempotency after a crash, transactional rows first, round-robin across tenants, the window,
 * the per tenant and platform ceilings, the opt-out, expiry, and the backlog that is dropped when
 * sending is switched on. Nothing here depends on the wall-clock date: every test fixes its own
 * Kampala day (2031) and uses a day of its own, so the platform-wide count of one test is not
 * another's.
 */
class SmsChannelIT extends IntegrationTest {

    /** 2031-03-10 07:00 UTC is 10:00 in Kampala (UTC+3 all year). */
    static final Instant BASE = Instant.parse("2031-03-10T07:00:00Z");

    static final Map<String, String> PARAMS = Map.of(
            "tenant_name", "Test Lender",
            "currency", "UGX",
            "amount", "5,000",
            "account_no", "SV000001",
            "receipt_no", "RC-1");

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
    com.rincoltech.bms.core.jobs.TenantJobs tenantJobs;

    @Autowired
    OutboxDispatcher managedDispatcher;

    @Autowired
    SmsSettings managedSettings;

    /** A clock the test moves. */
    static final class MovableClock extends Clock {
        volatile Instant now;

        MovableClock(Instant now) {
            this.now = now;
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

    /** One configuration of the channel and the dispatcher around it, on its own clock. */
    final class Rig {
        final MovableClock clock;
        final BusinessClock business;
        final RecordingSmsProvider provider = new RecordingSmsProvider();
        final JdbcOutbox outbox;
        final SmsOutboxChannel channel;
        final OutboxDispatcher dispatcher;

        Rig(String mode, SmsProvider chosen, Instant start, int platformCap) {
            this.clock = new MovableClock(start);
            this.business = new BusinessClock(clock);
            this.outbox = new JdbcOutbox(jdbc, mapper, business, templates, tenantSettings);
            SmsSettings settings = new SmsSettings(
                    "test",
                    SmsSettings.Mode.valueOf(mode.toUpperCase()),
                    SmsSettings.Kind.FAKE,
                    Set.of(),
                    platformCap,
                    null,
                    null,
                    null);
            this.channel = new SmsOutboxChannel(
                    jdbc,
                    transactionManager,
                    settings,
                    chosen == null ? provider : chosen,
                    templates,
                    business,
                    tenantConfig,
                    tenantJobs,
                    hasher,
                    outbox);
            this.dispatcher =
                    new OutboxDispatcher(jdbc, transactionManager, templates, mapper, business, List.of(channel));
            // As at application start: the mode is recorded before anything is sent.
            channel.enabled();
        }

        int dispatch(int max) {
            return dispatcher.dispatch(List.of(channel), max);
        }

        void at(Instant now) {
            clock.now = now;
        }

        void advance(Duration by) {
            clock.now = clock.now.plus(by);
        }
    }

    Rig live(Instant start) {
        return new Rig("live", null, start, 100_000);
    }

    static Instant day(int n) {
        return BASE.plus(Duration.ofDays(n));
    }

    /** A Kampala wall-clock time on the same date as {@code start}. */
    static Instant kampala(Instant start, int hour, int minute) {
        return start.atZone(ZoneId.of("Africa/Kampala"))
                .toLocalDate()
                .atTime(hour, minute)
                .atZone(ZoneId.of("Africa/Kampala"))
                .toInstant();
    }

    @BeforeEach
    void clean() {
        // A fresh channel state and no pending SMS from a previous test (or class) in the shared database.
        TestDatabase.owner().sql("DELETE FROM sms_channel_state").update();
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET status = 'failed', last_error = 'test_cleanup', failed_at = now()"
                        + " WHERE channel = 'sms' AND status = 'pending'")
                .update();
    }

    TestDatabase.Fixture tenant(int dailyCap) {
        TestDatabase.Fixture t = TestDatabase.tenant("sms", true);
        setControls(t, true, dailyCap);
        // The tenant's own switch: a tenant sends nothing until its administrator has switched SMS on.
        SmsTenants.enable(t.tenantId());
        return t;
    }

    void setControls(TestDatabase.Fixture t, boolean enabled, int dailyCap) {
        TestDatabase.owner()
                .sql(
                        "INSERT INTO platform_sms_controls (tenant_id, enabled, daily_cap) VALUES (?, ?, ?)"
                                + " ON CONFLICT (tenant_id) DO UPDATE SET enabled = excluded.enabled, daily_cap = excluded.daily_cap")
                .params(t.tenantId(), enabled, dailyCap)
                .update();
    }

    static String key() {
        return "test.sms:" + UUID.randomUUID();
    }

    /** The enqueue a savings receipt makes: in the tenant's own transaction, from the bound tenant. */
    boolean enqueue(Rig rig, TestDatabase.Fixture t, String phone, String category, UUID member, String key) {
        return Boolean.TRUE.equals(TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager)
                        .execute(tx -> rig.outbox.enqueue(new Outbox.Message(
                                Outbox.SMS,
                                phone,
                                "savings.deposit",
                                PARAMS,
                                key,
                                null,
                                null,
                                category,
                                member == null ? null : Outbox.Subject.member(member),
                                null)))));
    }

    String receipt(Rig rig, TestDatabase.Fixture t, String phone) {
        String key = key();
        assertThat(enqueue(rig, t, phone, Outbox.TRANSACTIONAL, UUID.randomUUID(), key))
                .isTrue();
        return key;
    }

    Map<String, Object> outboxRow(String key) {
        return TestDatabase.owner()
                .sql("SELECT status, attempts, last_error, next_attempt_at, expires_at, params::text AS params,"
                        + " tenant_id, category, subject_type, subject_id, recipient FROM notification_outbox"
                        + " WHERE idempotency_key = ?")
                .param(key)
                .query()
                .singleRow();
    }

    Map<String, Object> delivery(String key) {
        return TestDatabase.owner()
                .sql("SELECT d.* FROM sms_deliveries d JOIN notification_outbox o ON o.id = d.outbox_id"
                        + " WHERE o.idempotency_key = ?")
                .param(key)
                .query()
                .singleRow();
    }

    boolean hasDelivery(String key) {
        return TestDatabase.owner()
                        .sql("SELECT count(*) FROM sms_deliveries d JOIN notification_outbox o ON o.id = d.outbox_id"
                                + " WHERE o.idempotency_key = ?")
                        .param(key)
                        .query(Long.class)
                        .single()
                > 0;
    }

    static Instant at(Object timestamp) {
        if (timestamp instanceof Timestamp t) {
            return t.toInstant();
        }
        return ((OffsetDateTime) timestamp).toInstant();
    }

    // ---- The test profile ----------------------------------------------------------------------

    /** The shipped configuration is off, and the SMS sender is not among the senders that send. */
    @Test
    void theTestProfileHasTheSmsChannelOffWithoutFailing() {
        assertThat(managedSettings.mode()).isEqualTo(SmsSettings.Mode.OFF);
        assertThat(managedDispatcher.enabledChannels()).doesNotContain("sms");
    }

    // ---- Acceptance: sink mode ------------------------------------------------------------------

    /**
     * Acceptance 1 (FR-NTF-12, FR-NTF-13): with mode sink a deposit writes a delivery row with status
     * sunk and sends nothing; the log keeps the number masked and hashed, never in clear.
     */
    @Test
    void sinkModeWritesASunkRowAndSendsNothing() {
        Rig rig = new Rig("sink", new SinkSmsProvider(), day(1), 100_000);
        TestDatabase.Fixture t = tenant(100);
        UUID member = UUID.randomUUID();
        String key = key();
        assertThat(enqueue(rig, t, "0700000101", Outbox.TRANSACTIONAL, member, key))
                .isTrue();

        assertThat(rig.channel.enabled()).isTrue();
        assertThat(rig.dispatch(10)).isEqualTo(1);

        Map<String, Object> outbox = outboxRow(key);
        assertThat(outbox.get("status")).isEqualTo("sent");
        assertThat(outbox.get("params")).isEqualTo("{}");
        Map<String, Object> d = delivery(key);
        assertThat(d.get("status")).isEqualTo("sunk");
        assertThat(d.get("provider")).isEqualTo("sink");
        assertThat(d.get("tenant_id")).isEqualTo(t.tenantId());
        assertThat(d.get("segments")).isEqualTo(1);
        assertThat(d.get("category")).isEqualTo("transactional");
        assertThat(d.get("subject_type")).isEqualTo("member");
        assertThat(d.get("subject_id")).isEqualTo(member);
        assertThat(d.get("recipient_masked")).isEqualTo("+256*****0101");
        // Typed 0700000101, read as the Uganda number it is: the hash is of the E.164 form.
        assertThat(d.get("recipient_hash")).isEqualTo(hasher.of("+256700000101"));
        assertThat(((String) d.get("recipient_hash"))).hasSize(64).matches("[0-9a-f]+");
        assertThat(d.get("cost_minor")).isNull();
        // The delivery log holds the number in no column.
        assertThat(TestDatabase.owner()
                        .sql("SELECT row_to_json(d)::text FROM sms_deliveries d WHERE id = ?")
                        .param(d.get("id"))
                        .query(String.class)
                        .single())
                .doesNotContain("700000101");
    }

    /** Acceptance 2: with no provider the sender is off and the rows stay pending. */
    @Test
    void withNoProviderTheSenderIsOffAndRowsStayPending() {
        TestDatabase.Fixture t = tenant(100);
        for (String mode : List.of("live", "allowlist", "off", "sink")) {
            Rig rig = new Rig(mode, null, day(2), 100_000);
            SmsOutboxChannel noProvider = new SmsOutboxChannel(
                    jdbc,
                    transactionManager,
                    new SmsSettings(
                            "test",
                            SmsSettings.Mode.valueOf(mode.toUpperCase()),
                            SmsSettings.Kind.NONE,
                            Set.of(),
                            100,
                            null,
                            null,
                            null),
                    null,
                    templates,
                    rig.business,
                    tenantConfig,
                    tenantJobs,
                    hasher,
                    rig.outbox);
            assertThat(noProvider.enabled()).as(mode).isFalse();
            String key = receipt(rig, t, "+256700000102");
            OutboxDispatcher dispatcher = new OutboxDispatcher(
                    jdbc, transactionManager, templates, mapper, rig.business, List.of(noProvider));
            assertThat(dispatcher.enabledChannels()).isEmpty();
            assertThat(dispatcher.dispatch(List.of(noProvider), 10)).isZero();
            assertThat(outboxRow(key).get("status")).isEqualTo("pending");
            assertThat(outboxRow(key).get("attempts")).isEqualTo(0);
            assertThat(hasDelivery(key)).isFalse();
        }
    }

    // ---- Outcomes ------------------------------------------------------------------------------

    /** Acceptance 3 (FR-NTF-16): a read timeout records unknown and is not retried. */
    @Test
    void aReadTimeoutRecordsUnknownAndIsNeverRetried() {
        Rig rig = live(day(3));
        rig.provider.timeOut("provider_timeout");
        TestDatabase.Fixture t = tenant(100);
        String key = receipt(rig, t, "+256700000103");

        assertThat(rig.dispatch(1)).isEqualTo(1);
        Map<String, Object> outbox = outboxRow(key);
        assertThat(outbox.get("status")).isEqualTo("failed");
        assertThat(outbox.get("last_error")).isEqualTo("unknown_outcome");
        assertThat(outbox.get("attempts")).isEqualTo(1);
        assertThat(delivery(key).get("status")).isEqualTo("unknown");
        assertThat(delivery(key).get("failure_reason")).isEqualTo("provider_timeout");

        // However long it waits, and however often the sender runs, the provider is not called again.
        rig.advance(Duration.ofMinutes(10));
        assertThat(rig.dispatch(10)).isZero();
        assertThat(rig.provider.sent()).hasSize(1);

        // The operator's list names the tenant and the category, and keeps the recipient for masking.
        List<Map<String, Object>> failures = jdbc.sql("SELECT * FROM notification_outbox_failures(1000)")
                .query()
                .listOfRows();
        assertThat(failures).anySatisfy(f -> {
            assertThat(f.get("last_error")).isEqualTo("unknown_outcome");
            assertThat(f.get("tenant_id")).isEqualTo(t.tenantId());
            assertThat(f.get("category")).isEqualTo("transactional");
        });
    }

    /** FR-NTF-05: attempts at once, after 1 minute and after 5 minutes, then failed; no 30 minute step. */
    @Test
    void aCleanFailureIsRetriedAtZeroOneAndFiveMinutesThenFails() {
        Instant start = day(4);
        Rig rig = live(start);
        rig.provider.failWith("provider_http_503");
        TestDatabase.Fixture t = tenant(100);
        String key = receipt(rig, t, "+256700000104");

        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(outboxRow(key).get("status")).isEqualTo("pending");
        assertThat(at(outboxRow(key).get("next_attempt_at"))).isEqualTo(start.plus(Duration.ofMinutes(1)));
        assertThat(delivery(key).get("status")).isEqualTo("failed");
        assertThat(delivery(key).get("failure_reason")).isEqualTo("provider_http_503");

        assertThat(rig.dispatch(10)).isZero();
        rig.advance(Duration.ofMinutes(1));
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(at(outboxRow(key).get("next_attempt_at"))).isEqualTo(start.plus(Duration.ofMinutes(6)));

        rig.at(start.plus(Duration.ofMinutes(6)));
        assertThat(rig.dispatch(10)).isEqualTo(1);
        Map<String, Object> outbox = outboxRow(key);
        assertThat(outbox.get("status")).isEqualTo("failed");
        assertThat(outbox.get("attempts")).isEqualTo(3);
        assertThat(outbox.get("last_error")).isEqualTo("SendFailure: provider_http_503");
        assertThat(delivery(key).get("attempts")).isEqualTo(3);
        assertThat(rig.provider.sent()).hasSize(3);
        rig.advance(Duration.ofHours(2));
        assertThat(rig.dispatch(10)).isZero();
    }

    /** A clean failure then a success sends once; a row replayed after a lost record is not sent again. */
    @Test
    void aRowAlreadySentIsNotSentAgainWhateverTheOutboxSays() {
        Rig rig = live(day(5));
        AtomicInteger calls = new AtomicInteger();
        rig.provider.answer(() -> {
            if (calls.getAndIncrement() == 0) {
                throw new SmsProvider.Transient("provider_http_503");
            }
            return new SmsProvider.Outcome("fake", SmsProvider.Status.ACCEPTED, "fake-ok", 1, "UGX 32.0000", null);
        });
        TestDatabase.Fixture t = tenant(100);
        String key = receipt(rig, t, "+256700000105");

        rig.dispatch(1);
        rig.advance(Duration.ofMinutes(1));
        rig.dispatch(1);
        assertThat(outboxRow(key).get("status")).isEqualTo("sent");
        assertThat(delivery(key).get("status")).isEqualTo("sent");
        assertThat(delivery(key).get("attempts")).isEqualTo(2);
        assertThat(rig.provider.sent()).hasSize(2);

        // The record of the outcome was lost (the row is pending again, as after a crash and a lease).
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET status = 'pending', attempts = 1, sent_at = NULL,"
                        + " params = ?::jsonb, next_attempt_at = ? WHERE idempotency_key = ?")
                .params(mapper.writeValueAsString(PARAMS), Timestamp.from(rig.clock.now), key)
                .update();
        assertThat(rig.dispatch(1)).isEqualTo(1);
        assertThat(rig.provider.sent()).hasSize(2);
        assertThat(outboxRow(key).get("status")).isEqualTo("sent");
    }

    /** The operator's "send again" is the deliberate duplicate: it alone lets an unknown message go once more. */
    @Test
    void theOperatorsSendAgainResendsAnUnknownMessageOnce() {
        Instant start = day(28);
        Rig rig = live(start);
        rig.provider.timeOut("provider_timeout");
        TestDatabase.Fixture t = tenant(100);
        String key = receipt(rig, t, "+256700000204");
        rig.dispatch(1);
        assertThat(outboxRow(key).get("last_error")).isEqualTo("unknown_outcome");
        assertThat(rig.provider.sent()).hasSize(1);

        // Not by itself, however long it waits.
        rig.advance(Duration.ofMinutes(5));
        assertThat(rig.dispatch(10)).isZero();

        // The operator resends it, inside the hour the receipt lives.
        String result = jdbc.sql("SELECT notification_outbox_retry((SELECT id FROM notification_outbox_failures(1000)"
                        + " f WHERE f.last_error = 'unknown_outcome' AND f.tenant_id = ? LIMIT 1), ?, ?)")
                .params(t.tenantId(), Timestamp.from(start.minus(Duration.ofDays(7))), Timestamp.from(rig.clock.now))
                .query(String.class)
                .single();
        assertThat(result).isEqualTo("retried");
        rig.provider.answer(() ->
                new SmsProvider.Outcome("fake", SmsProvider.Status.ACCEPTED, "fake-resent", 1, "UGX 32.0000", null));
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent()).hasSize(2);
        assertThat(outboxRow(key).get("status")).isEqualTo("sent");
        assertThat(delivery(key).get("status")).isEqualTo("sent");
        assertThat(delivery(key).get("provider_message_id")).isEqualTo("fake-resent");
    }

    /** A crash after the request may have gone, before the answer: found queued, recorded unknown, not sent. */
    @Test
    void aMessageFoundQueuedAfterACrashIsNeverResent() {
        Rig rig = live(day(6));
        TestDatabase.Fixture t = tenant(100);
        String key = receipt(rig, t, "+256700000106");
        // What the first transaction left behind before the process died.
        TestDatabase.owner()
                .sql("INSERT INTO sms_deliveries (id, tenant_id, outbox_id, template_key, category, recipient_masked,"
                        + " recipient_hash, provider, segments, status)"
                        + " SELECT gen_random_uuid(), tenant_id, id, template_key, category, '+256*****0106',"
                        + " repeat('a', 64), 'fake', 1, 'queued' FROM notification_outbox WHERE idempotency_key = ?")
                .param(key)
                .update();

        assertThat(rig.dispatch(1)).isEqualTo(1);
        assertThat(rig.provider.sent()).isEmpty();
        assertThat(outboxRow(key).get("status")).isEqualTo("failed");
        assertThat(outboxRow(key).get("last_error")).isEqualTo("unknown_outcome");
        assertThat(delivery(key).get("status")).isEqualTo("unknown");
    }

    /** The provider's final no (a blocked or invalid number) is recorded rejected and not retried. */
    @Test
    void aRejectionIsFinalAndTheCostAndSegmentsAreRecorded() {
        Rig rig = live(day(7));
        TestDatabase.Fixture t = tenant(100);
        String ok = receipt(rig, t, "+256700000107");
        rig.dispatch(1);
        Map<String, Object> d = delivery(ok);
        assertThat(d.get("status")).isEqualTo("sent");
        assertThat(d.get("provider")).isEqualTo("fake");
        assertThat(d.get("provider_message_id")).isEqualTo("fake-1");
        assertThat(d.get("segments")).isEqualTo(1);
        assertThat(d.get("provider_segments")).isEqualTo(1);
        assertThat(d.get("cost_minor")).isEqualTo(32L);
        assertThat(d.get("currency")).isEqualTo("UGX");
        assertThat(d.get("cost_raw")).isEqualTo("UGX 32.0000");

        rig.provider.answer(
                () -> new SmsProvider.Outcome("fake", SmsProvider.Status.REJECTED, null, null, null, "provider_403"));
        String bad = receipt(rig, t, "+256700000108");
        assertThat(rig.dispatch(1)).isEqualTo(1);
        assertThat(outboxRow(bad).get("status")).isEqualTo("failed");
        assertThat(outboxRow(bad).get("last_error")).isEqualTo("provider_403");
        assertThat(outboxRow(bad).get("attempts")).isEqualTo(1);
        assertThat(delivery(bad).get("status")).isEqualTo("rejected");
        assertThat(delivery(bad).get("failure_reason")).isEqualTo("provider_403");
        rig.advance(Duration.ofMinutes(10));
        assertThat(rig.dispatch(10)).isZero();
    }

    // ---- Numbers -------------------------------------------------------------------------------

    /** An ambiguous or foreign number is never sent: it is refused rather than guessed (FR-NTF-19). */
    @Test
    void anAmbiguousNumberIsRefusedAndNeverReachesTheProvider() {
        Rig rig = live(day(8));
        TestDatabase.Fixture t = tenant(100);
        List<String> keys = new ArrayList<>();
        for (String phone : List.of("2125550123", "+14155550123", "700000401", "+254700000401", "0612345678")) {
            keys.add(receipt(rig, t, phone));
        }
        String good = receipt(rig, t, "0700000402");
        assertThat(rig.dispatch(10)).isEqualTo(6);
        assertThat(rig.provider.sent())
                .extracting(RecordingSmsProvider.Sent::to)
                .containsExactly("+256700000402");
        for (String key : keys) {
            assertThat(outboxRow(key).get("status")).isEqualTo("failed");
            assertThat(outboxRow(key).get("last_error")).isEqualTo("invalid_phone");
            assertThat(delivery(key).get("status")).isEqualTo("rejected");
            assertThat(delivery(key).get("failure_reason")).isEqualTo("invalid_phone");
            assertThat((String) delivery(key).get("recipient_masked"))
                    .doesNotContain("555012")
                    .hasSizeLessThanOrEqualTo(20);
        }
        assertThat(delivery(good).get("status")).isEqualTo("sent");
    }

    // ---- Tenant switch and the tenant on the row -------------------------------------------------

    @Test
    void theRowCarriesTheBoundTenantAndSmsIsOffUntilTheOperatorSwitchesItOn() {
        Rig rig = live(day(9));
        TestDatabase.Fixture t = TestDatabase.tenant("sms-off", true);
        // No control row: off by default; no row is written at all.
        assertThat(enqueue(rig, t, "+256700000109", Outbox.TRANSACTIONAL, null, key()))
                .isFalse();
        setControls(t, false, 100);
        assertThat(enqueue(rig, t, "+256700000109", Outbox.TRANSACTIONAL, null, key()))
                .isFalse();
        setControls(t, true, 100);
        // The operator's switch alone is not enough: the tenant's own switch is still off, so no row is written.
        assertThat(enqueue(rig, t, "+256700000109", Outbox.TRANSACTIONAL, null, key()))
                .isFalse();
        SmsTenants.enable(t.tenantId());
        String key = key();
        assertThat(enqueue(rig, t, "+256700000109", Outbox.TRANSACTIONAL, UUID.randomUUID(), key))
                .isTrue();
        Map<String, Object> row = outboxRow(key);
        assertThat(row.get("tenant_id")).isEqualTo(t.tenantId());
        assertThat(row.get("category")).isEqualTo("transactional");
        assertThat(row.get("subject_type")).isEqualTo("member");
        // A transactional SMS lives at most one hour, whatever the caller asked (ADR-036 decision 14).
        assertThat(at(row.get("expires_at"))).isEqualTo(rig.clock.now.plus(Duration.ofHours(1)));

        // Switched off after it was queued: refused at send time, never sent.
        setControls(t, false, 100);
        assertThat(rig.dispatch(1)).isEqualTo(1);
        assertThat(rig.provider.sent()).isEmpty();
        assertThat(outboxRow(key).get("status")).isEqualTo("failed");
        assertThat(outboxRow(key).get("last_error")).isEqualTo("tenant_sms_off");
    }

    /** A suspended tenant's queued messages are refused, not sent: its outbox rows are no longer its to use. */
    @Test
    void aSuspendedTenantsMessageIsRefused() {
        Rig rig = live(day(29));
        TestDatabase.Fixture t = tenant(100);
        String key = receipt(rig, t, "+256700000207");
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .param(t.tenantId())
                .update();
        assertThat(rig.dispatch(1)).isEqualTo(1);
        assertThat(rig.provider.sent()).isEmpty();
        assertThat(outboxRow(key).get("status")).isEqualTo("failed");
        assertThat(outboxRow(key).get("last_error")).isEqualTo("tenant_inactive");
    }

    @Test
    void anSmsNeedsABoundTenantAndAKnownCategory() {
        Rig rig = live(day(10));
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(tx -> rig.outbox.enqueue(
                                new Outbox.Message(Outbox.SMS, "+256700000110", "savings.deposit", PARAMS, key()))))
                .hasStackTraceContaining("needs a bound tenant");
        TestDatabase.Fixture t = tenant(100);
        assertThatThrownBy(() -> TenantContext.callAs(
                        t.tenantId(),
                        () -> new TransactionTemplate(transactionManager)
                                .execute(tx -> rig.outbox.enqueue(new Outbox.Message(
                                        Outbox.SMS,
                                        "+256700000110",
                                        "savings.deposit",
                                        PARAMS,
                                        key(),
                                        null,
                                        null,
                                        "marketing",
                                        null,
                                        null)))))
                .isInstanceOf(IllegalArgumentException.class);
        // A platform row (email, Telegram) keeps no tenant.
        String platform = "test.sms.platform:" + UUID.randomUUID();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> rig.outbox.enqueue(new Outbox.Message(
                        Outbox.TELEGRAM,
                        Outbox.OPERATOR_CHAT,
                        "sms.cap_alert",
                        Map.of("day", "2031-03-20", "scope", "platform", "state", "80 percent", "cap", "10"),
                        platform,
                        null,
                        rig.clock.now.minus(Duration.ofHours(1)))));
        assertThat(outboxRow(platform).get("tenant_id")).isNull();
    }

    // ---- Priority and fairness -----------------------------------------------------------------

    /** Acceptance 5 (FR-NTF-18): a receipt queued behind 1,000 due reminders is claimed first. */
    @Test
    void aReceiptQueuedBehindAThousandDueRemindersIsClaimedFirst() {
        Rig rig = live(day(11));
        TestDatabase.Fixture t = tenant(100_000);
        bulk(t, "reminder", "+256700000211", 1000, rig.clock.now.minus(Duration.ofHours(2)), "bulk-a-");
        String receipt = receipt(rig, t, "+256700000212");

        assertThat(rig.dispatch(1)).isEqualTo(1);
        assertThat(rig.provider.sent())
                .extracting(RecordingSmsProvider.Sent::to)
                .containsExactly("+256700000212");
        assertThat(outboxRow(receipt).get("status")).isEqualTo("sent");
        // The next claim is a reminder.
        assertThat(rig.dispatch(1)).isEqualTo(1);
        assertThat(rig.provider.sent())
                .extracting(RecordingSmsProvider.Sent::to)
                .contains("+256700000211");
    }

    /** Rows are taken round-robin across tenants by rank inside the tenant, not tenant by tenant. */
    @Test
    void rowsAreClaimedRoundRobinAcrossTenants() {
        Rig rig = live(day(12));
        TestDatabase.Fixture a = tenant(1000);
        TestDatabase.Fixture b = tenant(1000);
        // Tenant A has four reminders, all due before tenant B's two.
        for (int i = 1; i <= 4; i++) {
            raw(a, "reminder", "+25670000030" + i, rig.clock.now.minus(Duration.ofMinutes(20 - i)), key());
        }
        for (int i = 1; i <= 2; i++) {
            raw(b, "reminder", "+25670000031" + i, rig.clock.now.minus(Duration.ofMinutes(5 - i)), key());
        }
        assertThat(rig.dispatch(6)).isEqualTo(6);
        assertThat(rig.provider.sent())
                .extracting(RecordingSmsProvider.Sent::to)
                .containsExactly(
                        "+256700000301",
                        "+256700000311",
                        "+256700000302",
                        "+256700000312",
                        "+256700000303",
                        "+256700000304");
    }

    /** Inserts due rows the way the enqueue would have, in bulk, for the claim tests. */
    void bulk(TestDatabase.Fixture t, String category, String phone, int count, Instant due, String keyPrefix) {
        TestDatabase.owner()
                .sql(
                        "INSERT INTO notification_outbox (id, channel, recipient, template_key, params, idempotency_key,"
                                + " expires_at, next_attempt_at, created_at, updated_at, tenant_id, category)"
                                + " SELECT gen_random_uuid(), 'sms', ?, 'savings.deposit', ?::jsonb, ? || g || '-' || ?,"
                                + " ?, CAST(? AS timestamptz) + g * interval '1 millisecond', ?, ?, ?, ? FROM generate_series(1, ?) g")
                .params(
                        phone,
                        mapper.writeValueAsString(PARAMS),
                        keyPrefix,
                        UUID.randomUUID().toString(),
                        Timestamp.from(due.plus(Duration.ofHours(12))),
                        Timestamp.from(due),
                        Timestamp.from(due),
                        Timestamp.from(due),
                        t.tenantId(),
                        category,
                        count)
                .update();
    }

    void raw(TestDatabase.Fixture t, String category, String phone, Instant due, String key) {
        TestDatabase.owner()
                .sql("INSERT INTO notification_outbox (id, channel, recipient, template_key, params, idempotency_key,"
                        + " expires_at, next_attempt_at, created_at, updated_at, tenant_id, category)"
                        + " VALUES (gen_random_uuid(), 'sms', ?, 'savings.deposit', ?::jsonb, ?, ?, ?, ?, ?, ?, ?)")
                .params(
                        phone,
                        mapper.writeValueAsString(PARAMS),
                        key,
                        Timestamp.from(due.plus(Duration.ofHours(12))),
                        Timestamp.from(due),
                        Timestamp.from(due),
                        Timestamp.from(due),
                        t.tenantId(),
                        category)
                .update();
    }

    // ---- The window ----------------------------------------------------------------------------

    /** FR-NTF-04, FR-NTF-17: a reminder at 21:00 waits for 08:00 and expires at 20:00 that day; a receipt does not wait. */
    @Test
    void remindersWaitForTheWindowAndReceiptsDoNot() {
        Instant evening = kampala(day(13), 21, 0);
        Rig rig = live(evening);
        TestDatabase.Fixture t = tenant(100);
        String reminder = key();
        assertThat(enqueue(rig, t, "+256700000113", Outbox.REMINDER, UUID.randomUUID(), reminder))
                .isTrue();
        String receipt = receipt(rig, t, "+256700000114");

        Instant morning = kampala(evening.plus(Duration.ofDays(1)), 8, 0);
        assertThat(at(outboxRow(reminder).get("next_attempt_at"))).isEqualTo(morning);
        assertThat(at(outboxRow(reminder).get("expires_at"))).isEqualTo(kampala(morning, 20, 0));
        assertThat(at(outboxRow(receipt).get("expires_at"))).isEqualTo(evening.plus(Duration.ofHours(1)));

        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent())
                .extracting(RecordingSmsProvider.Sent::to)
                .containsExactly("+256700000114");
        rig.at(morning);
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent())
                .extracting(RecordingSmsProvider.Sent::to)
                .contains("+256700000113");
        assertThat(outboxRow(reminder).get("status")).isEqualTo("sent");
    }

    /** The channel checks again at send time: a due reminder at 21:00 is deferred, with its attempt given back. */
    @Test
    void aDueReminderOutsideTheWindowIsDeferredWithItsAttemptGivenBack() {
        Instant evening = kampala(day(14), 21, 0);
        Rig rig = live(evening);
        TestDatabase.Fixture t = tenant(100);
        String key = key();
        raw(t, "reminder", "+256700000115", evening.minus(Duration.ofHours(2)), key);

        assertThat(rig.dispatch(10)).isEqualTo(1);
        Map<String, Object> row = outboxRow(key);
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(at(row.get("next_attempt_at"))).isEqualTo(kampala(evening.plus(Duration.ofDays(1)), 8, 0));
        assertThat(rig.provider.sent()).isEmpty();
        assertThat(hasDelivery(key)).isFalse();
        // Not due again until the window opens.
        assertThat(rig.dispatch(10)).isZero();
    }

    /** The window is the tenant's own setting when it has one: the hook the reminder slices use. */
    @Test
    void aTenantsOwnWindowSettingIsHonoured() {
        Instant start = kampala(day(15), 9, 0);
        Rig rig = live(start);
        TestDatabase.Fixture t = tenant(100);
        SmsTenants.set(t.tenantId(), Map.of("window_start", "09:30", "window_end", "17:00"));
        String key = key();
        assertThat(enqueue(rig, t, "+256700000116", Outbox.ARREARS, UUID.randomUUID(), key))
                .isTrue();
        assertThat(at(outboxRow(key).get("next_attempt_at"))).isEqualTo(kampala(start, 9, 30));
        assertThat(at(outboxRow(key).get("expires_at"))).isEqualTo(kampala(start, 17, 0));
        assertThat(rig.dispatch(10)).isZero();
        rig.at(kampala(start, 9, 30));
        assertThat(rig.dispatch(10)).isEqualTo(1);
    }

    // ---- Ceilings ------------------------------------------------------------------------------

    /**
     * ADR-036 decision 13: over the tenant cap a reminder waits for tomorrow's window, a receipt goes on
     * to twice the cap, and beyond that it is held an hour at a time until it expires. Nothing over a
     * cap is sent, and nothing is dropped before it expires.
     */
    @Test
    void theTenantCapDefersRemindersAndHoldsReceiptsPastTwiceTheCap() {
        Instant start = day(40);
        Rig rig = live(start);
        TestDatabase.Fixture t = tenant(5);
        for (int i = 0; i < 5; i++) {
            raw(t, "reminder", "+25670000012" + i, start.minus(Duration.ofMinutes(10)), key());
        }
        assertThat(rig.dispatch(10)).isEqualTo(5);
        assertThat(rig.provider.sent()).hasSize(5);

        // The sixth reminder is over the cap: it waits for tomorrow's window.
        String over = key();
        raw(t, "reminder", "+256700000125", start.minus(Duration.ofMinutes(5)), over);
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(outboxRow(over).get("status")).isEqualTo("pending");
        assertThat(outboxRow(over).get("attempts")).isEqualTo(0);
        Instant tomorrow = start.plus(Duration.ofDays(1));
        assertThat(at(outboxRow(over).get("next_attempt_at"))).isEqualTo(kampala(tomorrow, 8, 0));
        // It must outlive today's window day: its expiry moves to the end of tomorrow's (it would have expired
        // at 20:00 today, before its turn).
        assertThat(at(outboxRow(over).get("expires_at"))).isEqualTo(kampala(tomorrow, 20, 0));
        assertThat(rig.provider.sent()).hasSize(5);

        // Receipts go on to twice the cap (10) ...
        for (int i = 0; i < 5; i++) {
            receipt(rig, t, "+25670000013" + i);
        }
        assertThat(rig.dispatch(10)).isEqualTo(5);
        assertThat(rig.provider.sent()).hasSize(10);
        // ... and the next is held, an hour at a time, not sent and not failed.
        String held = receipt(rig, t, "+256700000139");
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(outboxRow(held).get("status")).isEqualTo("pending");
        assertThat(outboxRow(held).get("attempts")).isEqualTo(0);
        assertThat(at(outboxRow(held).get("next_attempt_at"))).isEqualTo(start.plus(Duration.ofHours(1)));
        assertThat(rig.provider.sent()).hasSize(10);

        // Until it expires, then it fails as expired: never dropped early, never sent over the cap.
        rig.at(start.plus(Duration.ofHours(1)));
        assertThat(rig.dispatch(10)).isZero();
        assertThat(outboxRow(held).get("status")).isEqualTo("failed");
        assertThat(outboxRow(held).get("last_error")).isEqualTo("expired");
        assertThat(rig.provider.sent()).hasSize(10);

        // The operator was told at 80 percent and at the cap, once each for the day.
        String day = start.atZone(ZoneId.of("Africa/Kampala")).toLocalDate().toString();
        assertThat(alerts("tenant " + t.tenantId(), day)).containsExactlyInAnyOrder("80 percent", "100 percent");

        // Tomorrow the count starts again and the deferred reminder is sent, as the spec says.
        rig.at(kampala(tomorrow, 8, 0));
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(outboxRow(over).get("status")).isEqualTo("sent");
        assertThat(rig.provider.sent()).hasSize(11);
    }

    /** The extension is bounded: a deferral cannot push an expiry past 2 days after the row was queued. */
    @Test
    void aDeferralCanOnlyMoveTheExpiryLaterAndOnlyWithinTwoDays() {
        Instant start = day(30);
        TestDatabase.Fixture t = tenant(100);
        String key = key();
        raw(t, "reminder", "+256700000208", start, key);
        Map<String, Object> before = outboxRow(key);
        UUID id = TestDatabase.owner()
                .sql("SELECT id FROM notification_outbox WHERE idempotency_key = ?")
                .param(key)
                .query(UUID.class)
                .single();
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET attempts = 1 WHERE id = ?")
                .param(id)
                .update();
        for (Instant asked : List.of(start.plus(Duration.ofDays(30)), start.minus(Duration.ofDays(1)))) {
            jdbc.sql("SELECT notification_outbox_record_result(?, 'deferred', NULL, 3, ?, ?, ?)")
                    .params(id, Timestamp.from(start.plusSeconds(60)), Timestamp.from(start), Timestamp.from(asked))
                    .query(String.class)
                    .single();
            TestDatabase.owner()
                    .sql("UPDATE notification_outbox SET attempts = 1 WHERE id = ?")
                    .param(id)
                    .update();
        }
        // 30 days asked: capped at created_at + 2 days; one day earlier asked: unchanged.
        assertThat(at(outboxRow(key).get("expires_at"))).isEqualTo(start.plus(Duration.ofDays(2)));
        assertThat(at(before.get("expires_at"))).isBefore(start.plus(Duration.ofDays(2)));
    }

    /** A reminder queued three days ahead already expires later than the 2 day bound: a deferral never pulls it earlier. */
    @Test
    void aDeferralNeverMovesAnExpiryEarlierEvenForARowQueuedDaysAhead() {
        Instant start = day(33);
        TestDatabase.Fixture t = tenant(100);
        String key = key();
        raw(t, "reminder", "+256700000216", start, key);
        Instant farExpiry = start.plus(Duration.ofDays(3)).plus(Duration.ofHours(12));
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET expires_at = ?, attempts = 1 WHERE idempotency_key = ?")
                .params(Timestamp.from(farExpiry), key)
                .update();
        UUID id = TestDatabase.owner()
                .sql("SELECT id FROM notification_outbox WHERE idempotency_key = ?")
                .param(key)
                .query(UUID.class)
                .single();
        // Asked for a later time than the current expiry but the 2 day bound is earlier than the current expiry.
        jdbc.sql("SELECT notification_outbox_record_result(?, 'deferred', NULL, 3, ?, ?, ?)")
                .params(
                        id,
                        Timestamp.from(start.plusSeconds(60)),
                        Timestamp.from(start),
                        Timestamp.from(farExpiry.plus(Duration.ofDays(1))))
                .query(String.class)
                .single();
        assertThat(at(outboxRow(key).get("expires_at"))).isEqualTo(farExpiry);
    }

    /** Two senders racing for the same due rows never take one twice (the locked row is re-checked). */
    @Test
    void concurrentClaimersNeverTakeTheSameRow() throws Exception {
        Instant start = day(31);
        TestDatabase.Fixture t = tenant(100_000);
        int rows = 300;
        bulk(t, "reminder", "+256700000209", rows, start.minus(Duration.ofHours(1)), "race-");
        java.util.Set<UUID> taken = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.concurrent.atomic.AtomicInteger duplicates = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            futures.add(pool.submit(() -> {
                JdbcClient app = JdbcClient.create(TestDatabase.appDataSource());
                while (true) {
                    List<UUID> got = app.sql("SELECT id FROM notification_outbox_claim(ARRAY['sms'], 3, ?, ?)")
                            .params(Timestamp.from(start), Timestamp.from(start.plus(Duration.ofMinutes(2))))
                            .query(UUID.class)
                            .list();
                    if (got.isEmpty()) {
                        // SKIP LOCKED can come back empty while another holds the best rows: stop when none are due.
                        if (remaining(start) == 0) {
                            return null;
                        }
                        continue;
                    }
                    for (UUID id : got) {
                        if (!taken.add(id)) {
                            duplicates.incrementAndGet();
                        }
                    }
                }
            }));
        }
        for (var f : futures) {
            f.get(120, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(duplicates.get()).isZero();
        assertThat(taken).hasSize(rows);
    }

    /** Rows of this tenant's bulk still due at {@code at}. */
    long remaining(Instant at) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM notification_outbox WHERE channel = 'sms' AND status = 'pending'"
                        + " AND attempts = 0 AND next_attempt_at <= ?")
                .param(Timestamp.from(at))
                .query(Long.class)
                .single();
    }

    /** Spec 4.14: a refused key or an empty account tells the operator once a day, with no message detail. */
    @Test
    void aRefusedKeyOrAnEmptyAccountAlertsTheOperatorOncePerDay() {
        Instant start = day(32);
        Rig rig = live(start);
        TestDatabase.Fixture t = tenant(100);
        rig.provider.failWith("provider_auth_failed");
        for (int i = 0; i < 3; i++) {
            receipt(rig, t, "+25670000021" + i);
        }
        rig.dispatch(10);
        rig.provider.failWith("provider_insufficient_balance");
        receipt(rig, t, "+256700000214");
        rig.dispatch(10);
        // A different failure does not alert.
        rig.provider.failWith("provider_http_503");
        receipt(rig, t, "+256700000215");
        rig.dispatch(10);

        String day = start.atZone(ZoneId.of("Africa/Kampala")).toLocalDate().toString();
        List<String> alerts = TestDatabase.owner()
                .sql("SELECT params->>'reason' FROM notification_outbox WHERE channel = 'telegram'"
                        + " AND template_key = 'sms.provider_alert' AND params->>'day' = ? AND tenant_id IS NULL"
                        + " ORDER BY 1")
                .param(day)
                .query(String.class)
                .list();
        assertThat(alerts).containsExactly("provider_auth_failed", "provider_insufficient_balance");
        // The alert text renders and carries neither a number nor a message.
        String text = templates
                .render("telegram", "sms.provider_alert", Map.of("day", day, "reason", "provider_auth_failed"))
                .text();
        assertThat(text).contains("provider_auth_failed").doesNotContain("+256");
    }

    /** The platform ceiling sits above every tenant: over it, even a receipt is held. */
    @Test
    void thePlatformCeilingHoldsEveryTenant() {
        Instant start = day(17);
        Rig rig = new Rig("live", null, start, 2);
        TestDatabase.Fixture a = tenant(1000);
        TestDatabase.Fixture b = tenant(1000);
        receipt(rig, a, "+256700000141");
        receipt(rig, b, "+256700000142");
        assertThat(rig.dispatch(10)).isEqualTo(2);
        String held = receipt(rig, a, "+256700000143");
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent()).hasSize(2);
        assertThat(outboxRow(held).get("status")).isEqualTo("pending");
        assertThat(at(outboxRow(held).get("next_attempt_at"))).isEqualTo(start.plus(Duration.ofHours(1)));
        String day = start.atZone(ZoneId.of("Africa/Kampala")).toLocalDate().toString();
        assertThat(alerts("platform", day)).contains("100 percent");
        // The next business day the count starts again.
        rig.at(kampala(start.plus(Duration.ofDays(1)), 9, 0));
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET expires_at = ? WHERE idempotency_key = ?")
                .params(Timestamp.from(rig.clock.now.plus(Duration.ofHours(1))), held)
                .update();
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(outboxRow(held).get("status")).isEqualTo("sent");
    }

    @Test
    void aCapOfZeroSendsNothing() {
        Rig rig = live(day(18));
        TestDatabase.Fixture t = tenant(0);
        String key = receipt(rig, t, "+256700000144");
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent()).isEmpty();
        assertThat(outboxRow(key).get("status")).isEqualTo("pending");
    }

    /** The states of the alerts raised for a scope on a day, from the operator's Telegram rows. */
    List<String> alerts(String scope, String day) {
        return TestDatabase.owner()
                .sql("SELECT params->>'state' FROM notification_outbox WHERE channel = 'telegram'"
                        + " AND template_key = 'sms.cap_alert' AND params->>'scope' = ? AND params->>'day' = ?"
                        + " AND tenant_id IS NULL")
                .params(scope, day)
                .query(String.class)
                .list();
    }

    // ---- Opt-out -------------------------------------------------------------------------------

    @Test
    void anOptedOutMemberIsLoggedBlockedAndNeverTexted() {
        Rig rig = live(day(19));
        TestDatabase.Fixture t = tenant(100);
        UUID reminderOptOut = UUID.randomUUID();
        UUID allOptOut = UUID.randomUUID();
        optOut(t, reminderOptOut, "+256700000151", "reminders");
        optOut(t, allOptOut, "+256700000152", "all");

        // Reminders opted out: a reminder is blocked, a receipt still goes.
        String blockedReminder = key();
        assertThat(enqueue(rig, t, "+256700000151", Outbox.REMINDER, reminderOptOut, blockedReminder))
                .isTrue();
        String receipt = key();
        assertThat(enqueue(rig, t, "+256700000151", Outbox.TRANSACTIONAL, reminderOptOut, receipt))
                .isTrue();
        // All opted out: even a receipt is blocked.
        String blockedAll = key();
        assertThat(enqueue(rig, t, "+256700000152", Outbox.TRANSACTIONAL, allOptOut, blockedAll))
                .isTrue();
        assertThat(rig.dispatch(10)).isEqualTo(3);

        assertThat(rig.provider.sent())
                .extracting(RecordingSmsProvider.Sent::to)
                .containsExactly("+256700000151");
        for (String key : List.of(blockedReminder, blockedAll)) {
            assertThat(delivery(key).get("status")).isEqualTo("blocked_optout");
            assertThat(outboxRow(key).get("status")).isEqualTo("failed");
            assertThat(outboxRow(key).get("last_error")).isEqualTo("blocked_optout");
        }
        assertThat(delivery(receipt).get("status")).isEqualTo("sent");
    }

    /** FR-NTF-14: the match is by member and number hash, so a reused number does not block the wrong person. */
    @Test
    void aNumberReusedByAnotherMemberIsNotBlockedButTheSameMembersOtherNumberIsNotEither() {
        Rig rig = live(day(20));
        TestDatabase.Fixture t = tenant(100);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        optOut(t, first, "+256700000153", "all");

        // A different member now holds the same number: not blocked.
        String reused = key();
        assertThat(enqueue(rig, t, "+256700000153", Outbox.TRANSACTIONAL, second, reused))
                .isTrue();
        // The opted-out member changed number: the opt-out was recorded for the old one.
        String moved = key();
        assertThat(enqueue(rig, t, "+256700000154", Outbox.TRANSACTIONAL, first, moved))
                .isTrue();
        // The opted-out member on the number it was recorded for: blocked.
        String same = key();
        assertThat(enqueue(rig, t, "0700000153", Outbox.TRANSACTIONAL, first, same))
                .isTrue();
        assertThat(rig.dispatch(10)).isEqualTo(3);
        assertThat(delivery(reused).get("status")).isEqualTo("sent");
        assertThat(delivery(moved).get("status")).isEqualTo("sent");
        assertThat(delivery(same).get("status")).isEqualTo("blocked_optout");

        // An opt-out with no number holds for the member on any number; a removed one holds for nobody.
        UUID anyNumber = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO sms_opt_outs (id, tenant_id, member_id, scope, source) VALUES (?, ?, ?, 'all', 'staff')")
                .params(UUID.randomUUID(), t.tenantId(), anyNumber)
                .update();
        UUID removed = UUID.randomUUID();
        optOut(t, removed, "+256700000155", "all");
        TestDatabase.owner()
                .sql("UPDATE sms_opt_outs SET removed_at = now() WHERE member_id = ?")
                .param(removed)
                .update();
        String anyKey = key();
        String removedKey = key();
        enqueue(rig, t, "+256700000156", Outbox.TRANSACTIONAL, anyNumber, anyKey);
        enqueue(rig, t, "+256700000155", Outbox.TRANSACTIONAL, removed, removedKey);
        assertThat(rig.dispatch(10)).isEqualTo(2);
        assertThat(delivery(anyKey).get("status")).isEqualTo("blocked_optout");
        assertThat(delivery(removedKey).get("status")).isEqualTo("sent");
    }

    void optOut(TestDatabase.Fixture t, UUID member, String phone, String scope) {
        TestDatabase.owner()
                .sql("INSERT INTO sms_opt_outs (id, tenant_id, member_id, recipient_hash, scope, source)"
                        + " VALUES (?, ?, ?, ?, ?, 'staff')")
                .params(UUID.randomUUID(), t.tenantId(), member, hasher.of(phone), scope)
                .update();
    }

    // ---- Expiry and backlog --------------------------------------------------------------------

    @Test
    void aReceiptOlderThanAnHourIsNeverSent() {
        Instant start = day(21);
        Rig rig = live(start);
        TestDatabase.Fixture t = tenant(100);
        String key = receipt(rig, t, "+256700000161");
        rig.at(start.plus(Duration.ofMinutes(61)));
        assertThat(rig.dispatch(10)).isZero();
        assertThat(rig.provider.sent()).isEmpty();
        assertThat(outboxRow(key).get("status")).isEqualTo("failed");
        assertThat(outboxRow(key).get("last_error")).isEqualTo("expired");
    }

    /**
     * Acceptance 7 (FR-NTF-17): moving the mode from sink to allowlist sends none of the older queued
     * rows; a row queued after the move goes; a move between two sending modes drops nothing.
     */
    @Test
    void movingTheModeFromSinkToASendingModeSendsNoneOfTheOlderQueuedRows() {
        Instant start = day(22);
        TestDatabase.Fixture t = tenant(100);
        // The channel starts in sink mode: the state is recorded, nothing sends to a phone.
        Rig sink = new Rig("sink", new SinkSmsProvider(), start, 100_000);
        assertThat(sink.channel.enabled()).isTrue();
        String old1 = receipt(sink, t, "+256700000171");
        String old2 = receipt(sink, t, "+256700000172");
        // A reminder queued the same way: all older rows.
        String old3 = key();
        enqueue(sink, t, "+256700000173", Outbox.REMINDER, UUID.randomUUID(), old3);

        // The operator moves the mode to allowlist and restarts: a new channel, a later clock.
        Rig allowlist = new Rig("allowlist", null, start.plus(Duration.ofMinutes(5)), 100_000);
        assertThat(allowlist.channel.enabled()).isTrue();
        for (String key : List.of(old1, old2, old3)) {
            assertThat(outboxRow(key).get("status")).isEqualTo("failed");
            assertThat(outboxRow(key).get("last_error")).isEqualTo("backlog_dropped");
            assertThat(outboxRow(key).get("params")).isEqualTo("{}");
        }
        assertThat(allowlist.dispatch(10)).isZero();
        assertThat(allowlist.provider.sent()).isEmpty();

        // A message queued after the move is sent.
        String fresh = receipt(allowlist, t, "+256700000174");
        assertThat(allowlist.dispatch(10)).isEqualTo(1);
        assertThat(outboxRow(fresh).get("status")).isEqualTo("sent");

        // From allowlist to live, nothing is dropped: both send.
        String queued = receipt(allowlist, t, "+256700000175");
        Rig live = new Rig("live", null, start.plus(Duration.ofMinutes(10)), 100_000);
        assertThat(live.channel.enabled()).isTrue();
        assertThat(outboxRow(queued).get("status")).isEqualTo("pending");
        assertThat(live.dispatch(10)).isEqualTo(1);
        assertThat(outboxRow(queued).get("status")).isEqualTo("sent");
    }

    /** An off start is recorded too, so a later move from off to a sending mode drops what was queued meanwhile. */
    @Test
    void anOffStartIsRecordedSoTheNextMoveToLiveDropsWhatWasQueuedMeanwhile() {
        Instant start = day(23);
        TestDatabase.Fixture t = tenant(100);
        Rig live = new Rig("live", null, start, 100_000);
        assertThat(live.channel.enabled()).isTrue();
        // Restarted with the mode off: recorded, though the channel does not send.
        SmsOutboxChannel off = new SmsOutboxChannel(
                jdbc,
                transactionManager,
                new SmsSettings("test", SmsSettings.Mode.OFF, SmsSettings.Kind.NONE, Set.of(), 100, null, null, null),
                null,
                templates,
                live.business,
                tenantConfig,
                tenantJobs,
                hasher,
                live.outbox);
        assertThat(off.enabled()).isFalse();
        String whileOff = receipt(live, t, "+256700000181");
        // Restarted live again: the rows from the off period are dropped.
        Rig again = new Rig("live", null, start.plus(Duration.ofMinutes(1)), 100_000);
        assertThat(again.channel.enabled()).isTrue();
        assertThat(outboxRow(whileOff).get("last_error")).isEqualTo("backlog_dropped");
        assertThat(again.dispatch(10)).isZero();
    }

    // ---- Logs ----------------------------------------------------------------------------------

    /** Acceptance 4 (standing rule 9): no log line carries a phone number or message text. */
    @Test
    void noLogLineCarriesAPhoneNumberOrMessageText() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            Instant start = day(24);
            Rig rig = live(start);
            TestDatabase.Fixture t = tenant(2);
            UUID optedOut = UUID.randomUUID();
            optOut(t, optedOut, "+256700000199", "all");
            receipt(rig, t, "+256700000191");
            rig.dispatch(1);
            rig.provider.timeOut("provider_timeout");
            receipt(rig, t, "+256700000192");
            rig.dispatch(1);
            rig.provider.failWith("provider_http_503");
            receipt(rig, t, "+256700000193");
            rig.dispatch(1);
            rig.provider.answer(() ->
                    new SmsProvider.Outcome("fake", SmsProvider.Status.REJECTED, null, null, null, "provider_403"));
            receipt(rig, t, "+256700000194");
            rig.dispatch(1);
            receipt(rig, t, "2125550195");
            rig.dispatch(1);
            enqueue(rig, t, "+256700000199", Outbox.TRANSACTIONAL, optedOut, key());
            rig.dispatch(1);
            // Over the cap, and a retry a minute later.
            rig.provider.answer(
                    () -> new SmsProvider.Outcome("fake", SmsProvider.Status.ACCEPTED, "fake-9", 1, null, null));
            for (int i = 6; i <= 8; i++) {
                receipt(rig, t, "+25670000019" + i);
            }
            rig.dispatch(50);
            rig.advance(Duration.ofMinutes(2));
            rig.dispatch(50);
            Rig sink = new Rig("sink", new SinkSmsProvider(), start, 100_000);
            receipt(sink, t, "+256700000190");
            sink.dispatch(10);

            String logged = appender.list.stream()
                    .map(e -> e.getFormattedMessage() + " "
                            + (e.getThrowableProxy() == null
                                    ? ""
                                    : e.getThrowableProxy().getMessage()))
                    .reduce("", (x, y) -> x + "\n" + y);
            assertThat(logged).contains("SMS", "outbox");
            for (String secret : List.of(
                    "70000019", "0700000", "2125550195", "Test Lender", "Deposit of", "SV000001", "RC-1", "5,000")) {
                assertThat(logged).as("log text must not hold %s", secret).doesNotContain(secret);
            }
        } finally {
            root.detachAppender(appender);
        }
    }

    // ---- The dispatcher's other results ---------------------------------------------------------

    /** What a scripted channel answers, for the generic result handling of the dispatcher. */
    static final class Scripted implements OutboxChannel {
        OutboxChannel.Result answer = OutboxChannel.Result.SENT;
        final List<Integer> attempts = new ArrayList<>();

        @Override
        public String channel() {
            return "email";
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Result send(Delivery delivery) {
            attempts.add(delivery.context().attempt());
            return answer;
        }
    }

    @Test
    void theDispatcherRecordsDeferredUnknownAndRefusedWithoutCountingOrRetrying() {
        Instant start = day(25);
        // This test owns the pending email rows while it runs, so its one-row dispatches claim its own row.
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET status = 'failed', last_error = 'test_cleanup', failed_at = now()"
                        + " WHERE channel = 'email' AND status = 'pending'")
                .update();
        MovableClock clock = new MovableClock(start);
        Scripted channel = new Scripted();
        OutboxDispatcher dispatcher = new OutboxDispatcher(
                jdbc, transactionManager, templates, mapper, new BusinessClock(clock), List.of(channel));

        // Deferred: the attempt is given back and the row waits until the time given.
        String deferred = platformEmail(start);
        channel.answer = OutboxChannel.Result.deferred(start.plus(Duration.ofMinutes(30)));
        assertThat(dispatcher.dispatch(List.of(channel), 1)).isEqualTo(1);
        Map<String, Object> row = outboxRow(deferred);
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(at(row.get("next_attempt_at"))).isEqualTo(start.plus(Duration.ofMinutes(30)));
        assertThat(dispatcher.dispatch(List.of(channel), 1)).isZero();
        // A deferral that names a time already gone would be claimed again at once: it is moved a minute on.
        clock.now = start.plus(Duration.ofMinutes(30));
        channel.answer = OutboxChannel.Result.deferred(start);
        assertThat(dispatcher.dispatch(List.of(channel), 1)).isEqualTo(1);
        assertThat(at(outboxRow(deferred).get("next_attempt_at"))).isEqualTo(start.plus(Duration.ofMinutes(31)));
        assertThat(outboxRow(deferred).get("attempts")).isEqualTo(0);
        assertThat(channel.attempts).containsExactly(1, 1);
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET status = 'failed', failed_at = now() WHERE idempotency_key = ?")
                .param(deferred)
                .update();

        // Unknown: failed, parameters kept, never retried.
        String unknown = platformEmail(clock.now);
        channel.answer = OutboxChannel.Result.unknown();
        assertThat(dispatcher.dispatch(List.of(channel), 1)).isEqualTo(1);
        assertThat(outboxRow(unknown).get("status")).isEqualTo("failed");
        assertThat(outboxRow(unknown).get("last_error")).isEqualTo("unknown_outcome");
        assertThat(outboxRow(unknown).get("params")).isNotEqualTo("{}");
        clock.now = clock.now.plus(Duration.ofHours(1));
        assertThat(dispatcher.dispatch(List.of(channel), 1)).isZero();

        // Refused: failed with the reason, parameters cleared, one attempt only.
        String refused = platformEmail(clock.now);
        channel.answer = OutboxChannel.Result.refused("blocked_optout");
        assertThat(dispatcher.dispatch(List.of(channel), 1)).isEqualTo(1);
        assertThat(outboxRow(refused).get("status")).isEqualTo("failed");
        assertThat(outboxRow(refused).get("last_error")).isEqualTo("blocked_optout");
        assertThat(outboxRow(refused).get("params")).isEqualTo("{}");
        assertThat(outboxRow(refused).get("attempts")).isEqualTo(1);
        // A reason that is not a short code is reduced: no text or number reaches a row.
        assertThat(OutboxDispatcher.safeReason("+256700000001")).isEqualTo("refused");
        assertThat(OutboxDispatcher.safeReason("blocked_optout")).isEqualTo("blocked_optout");
    }

    String platformEmail(Instant due) {
        String key = key();
        TestDatabase.owner()
                .sql("INSERT INTO notification_outbox (id, channel, recipient, template_key, params, idempotency_key,"
                        + " next_attempt_at, created_at, updated_at) VALUES (gen_random_uuid(), 'email',"
                        + " 'test-ops@example.test', 'onboarding.operator_alert', ?::jsonb, ?, ?, ?, ?)")
                .params(
                        mapper.writeValueAsString(Map.of(
                                "reference", "A-TEST0001",
                                "business_name", "Test Shop",
                                "modules", "retail",
                                "portal_url", "https://localhost/x")),
                        key,
                        Timestamp.from(due),
                        Timestamp.from(due),
                        Timestamp.from(due))
                .update();
        return key;
    }

    // ---- Retention and throughput --------------------------------------------------------------

    @Test
    void theDeliveryLogIsKeptThirteenMonths() {
        Instant start = day(26);
        Rig rig = live(start);
        TestDatabase.Fixture t = tenant(100);
        String key = receipt(rig, t, "+256700000201");
        rig.dispatch(1);
        UUID old = UUID.randomUUID();
        TestDatabase.owner()
                .sql("INSERT INTO sms_deliveries (id, tenant_id, created_at, outbox_id, template_key, category,"
                        + " recipient_masked, recipient_hash, provider, status) VALUES (?, ?, ?, ?, 'savings.deposit',"
                        + " 'transactional', '+256*****0202', repeat('d', 64), 'fake', 'sent')")
                .params(old, t.tenantId(), Timestamp.from(start.minus(Duration.ofDays(14 * 31))), UUID.randomUUID())
                .update();
        // A failed SMS row keeps its recipient column until it is deleted: 30 days after it failed, like a sent one.
        String oldFailed = key();
        String recentFailed = key();
        raw(t, "transactional", "+256700000205", start.minus(Duration.ofDays(40)), oldFailed);
        raw(t, "transactional", "+256700000206", start.minus(Duration.ofDays(2)), recentFailed);
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET status = 'failed', params = '{}'::jsonb, failed_at = ?"
                        + " WHERE idempotency_key = ?")
                .params(Timestamp.from(start.minus(Duration.ofDays(31))), oldFailed)
                .update();
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET status = 'failed', params = '{}'::jsonb, failed_at = ?"
                        + " WHERE idempotency_key = ?")
                .params(Timestamp.from(start.minus(Duration.ofDays(1))), recentFailed)
                .update();
        assertThat(rig.dispatcher.purge()).isGreaterThanOrEqualTo(2);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM sms_deliveries WHERE id = ?")
                        .param(old)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(hasDelivery(key)).isTrue();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox WHERE idempotency_key = ?")
                        .param(oldFailed)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(outboxRow(recentFailed).get("status")).isEqualTo("failed");
    }

    /** Spec section 4.6, throughput: measured with the fake provider, so the figure is the dispatcher's own cost. */
    @Test
    void theDispatcherThroughputIsMeasuredWithTheFakeProvider() {
        Instant start = day(27);
        Rig rig = live(start);
        TestDatabase.Fixture t = tenant(100_000);
        int rows = 200;
        bulk(t, "transactional", "+256700000203", rows, start.minus(Duration.ofMinutes(30)), "tp-");
        long began = System.nanoTime();
        int tried = rig.dispatch(rows);
        long millis = Duration.ofNanos(System.nanoTime() - began).toMillis();
        assertThat(tried).isEqualTo(rows);
        assertThat(rig.provider.sent()).hasSize(rows);
        LoggerFactory.getLogger(SmsChannelIT.class)
                .info(
                        "SMS dispatcher throughput with the fake provider: {} messages in {} ms ({} per second)",
                        rows,
                        millis,
                        millis == 0 ? rows : rows * 1000 / millis);
        // A generous bound: the figure above is the measurement, this only catches a collapse.
        assertThat(millis).isLessThan(120_000);
    }

    // ---- The tenant's own settings (issue #218, ADR-036, spec 4.8 to 4.10) -------------------------
    // JUSTIFICATION-A3: these tests need this class's Rig (channel, dispatcher, movable clock) and its helpers.

    /** A message with parameters of the test's own choosing, queued as a savings receipt is. */
    boolean enqueueWith(Rig rig, TestDatabase.Fixture t, String phone, Map<String, String> params, String key) {
        return Boolean.TRUE.equals(TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager)
                        .execute(tx -> rig.outbox.enqueue(new Outbox.Message(
                                Outbox.SMS,
                                phone,
                                "savings.deposit",
                                params,
                                key,
                                null,
                                null,
                                Outbox.TRANSACTIONAL,
                                Outbox.Subject.member(UUID.randomUUID()),
                                null)))));
    }

    static String uniqueSender() {
        return "B" + UUID.randomUUID().toString().replace("-", "").substring(0, 9);
    }

    /** The tenant's own switch gates the channel: a row queued before it was switched off is refused, never held. */
    @Test
    void aRowQueuedBeforeTheTenantSwitchedSmsOffIsRefusedAndSwitchingOnAgainDoesNotReleaseIt() {
        Rig rig = live(day(60));
        TestDatabase.Fixture t = tenant(100);
        String key = receipt(rig, t, "+256700000401");
        SmsTenants.set(t.tenantId(), Map.of("enabled", false));
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent()).isEmpty();
        assertThat(outboxRow(key).get("status")).isEqualTo("failed");
        assertThat(outboxRow(key).get("last_error")).isEqualTo("tenant_sms_off");
        assertThat(hasDelivery(key)).isFalse();

        SmsTenants.enable(t.tenantId());
        assertThat(rig.dispatch(10)).isZero();
        assertThat(rig.provider.sent()).isEmpty();
        assertThat(outboxRow(key).get("status")).isEqualTo("failed");
    }

    @Test
    void theApprovedSenderIdGoesToTheProviderAndAWithdrawnOrNeverApprovedOneDoesNot() {
        Rig rig = live(day(61));
        TestDatabase.Fixture t = tenant(100);
        String sender = uniqueSender();
        TestDatabase.owner()
                .sql("INSERT INTO platform_sms_sender_ids (id, tenant_id, sender_id, approved_at)"
                        + " VALUES (gen_random_uuid(), ?, ?, now())")
                .params(t.tenantId(), sender)
                .update();
        SmsTenants.set(t.tenantId(), Map.of("sender_id", sender));

        receipt(rig, t, "+256700000411");
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent())
                .extracting(RecordingSmsProvider.Sent::senderId)
                .containsExactly(sender);

        // The operator withdraws it: the very next message goes from the shared sender, with no edit of the settings.
        TestDatabase.owner()
                .sql("UPDATE platform_sms_sender_ids SET withdrawn_at = now() WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        receipt(rig, t, "+256700000412");
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent().get(1).senderId()).isNull();

        // A name written straight into the settings that nobody approved is no sender either.
        SmsTenants.set(t.tenantId(), Map.of("sender_id", uniqueSender()));
        receipt(rig, t, "+256700000413");
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent().get(2).senderId()).isNull();

        // Another business's approved id is not this business's either.
        String theirs = uniqueSender();
        TestDatabase.Fixture other = tenant(100);
        TestDatabase.owner()
                .sql("INSERT INTO platform_sms_sender_ids (id, tenant_id, sender_id, approved_at)"
                        + " VALUES (gen_random_uuid(), ?, ?, now())")
                .params(other.tenantId(), theirs)
                .update();
        SmsTenants.set(t.tenantId(), Map.of("sender_id", theirs));
        receipt(rig, t, "+256700000414");
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent().get(3).senderId()).isNull();
    }

    /** The tenant's own daily cap lowers the operator's; the lower of the two applies. */
    @Test
    void theTenantsOwnDailyCapLowersTheOperatorsAndAHigherOneDoesNotRaiseIt() {
        Rig rig = live(day(62));
        TestDatabase.Fixture t = tenant(5);
        SmsTenants.set(t.tenantId(), Map.of("daily_cap", 2));
        Instant due = rig.clock.now.minus(Duration.ofMinutes(10));
        List<String> reminders = List.of(key(), key(), key());
        for (int i = 0; i < reminders.size(); i++) {
            raw(t, "reminder", "+25670000042" + i, due, reminders.get(i));
        }
        assertThat(rig.dispatch(10)).isEqualTo(3);
        assertThat(rig.provider.sent()).hasSize(2);
        // The third waits for tomorrow's window, as any reminder over its cap does.
        List<Map<String, Object>> waiting = reminders.stream()
                .map(this::outboxRow)
                .filter(r -> "pending".equals(r.get("status")))
                .toList();
        assertThat(waiting).hasSize(1);
        assertThat(at(waiting.getFirst().get("next_attempt_at"))).isEqualTo(kampala(day(63), 8, 0));

        // Receipts go on to twice the cap (two more), then are held.
        String r1 = receipt(rig, t, "+256700000431");
        String r2 = receipt(rig, t, "+256700000432");
        String r3 = receipt(rig, t, "+256700000433");
        assertThat(rig.dispatch(10)).isEqualTo(3);
        // Which of the three is held depends on the order they were claimed in; two go and one waits.
        assertThat(List.of(r1, r2, r3).stream()
                        .map(k -> outboxRow(k).get("status"))
                        .sorted(java.util.Comparator.comparing(Object::toString)))
                .containsExactly("pending", "sent", "sent");
        assertThat(rig.provider.sent()).hasSize(4);

        // A tenant cap above the operator's (written past the screen's validation) raises nothing: operator cap 2.
        Rig second = live(day(64));
        TestDatabase.Fixture u = tenant(2);
        SmsTenants.set(u.tenantId(), Map.of("daily_cap", 50));
        Instant due2 = second.clock.now.minus(Duration.ofMinutes(10));
        for (int i = 0; i < 3; i++) {
            raw(u, "reminder", "+25670000044" + i, due2, key());
        }
        assertThat(second.dispatch(10)).isEqualTo(3);
        assertThat(second.provider.sent()).hasSize(2);
    }

    /** A cap of 0 stops everything and is never read as "no cap of its own"; absent is the operator's cap. */
    @Test
    void aTenantCapOfZeroStopsEveryMessageAndAbsentMeansTheOperatorsCap() {
        Rig rig = live(day(65));
        TestDatabase.Fixture daily = tenant(100);
        SmsTenants.set(daily.tenantId(), Map.of("daily_cap", 0));
        String receipt = receipt(rig, daily, "+256700000451");
        String reminder = key();
        raw(daily, "reminder", "+256700000452", rig.clock.now.minus(Duration.ofMinutes(5)), reminder);
        assertThat(rig.dispatch(10)).isEqualTo(2);
        assertThat(rig.provider.sent()).isEmpty();
        assertThat(outboxRow(receipt).get("status")).isEqualTo("pending");
        assertThat(outboxRow(reminder).get("status")).isEqualTo("pending");
        assertThat(hasDelivery(receipt)).isFalse();

        TestDatabase.Fixture monthly = tenant(100);
        SmsTenants.set(monthly.tenantId(), Map.of("monthly_cap", 0));
        String monthlyReceipt = receipt(rig, monthly, "+256700000453");
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent()).isEmpty();
        assertThat(outboxRow(monthlyReceipt).get("status")).isEqualTo("pending");

        TestDatabase.Fixture none = tenant(100);
        String sentKey = receipt(rig, none, "+256700000454");
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(outboxRow(sentKey).get("status")).isEqualTo("sent");
    }

    /** Africa/Kampala midnight is 21:00 UTC: 20:59 is still the old day and 21:01 is the new one. */
    @Test
    void theDailyCapCountsTheKampalaDayAndResetsAtItsMidnight() {
        // 2031-04-02 20:59 UTC is 23:59 in Kampala; the cap counts messages, receipts may reach twice it.
        Rig rig = live(Instant.parse("2031-04-02T20:59:00Z"));
        TestDatabase.Fixture t = tenant(100);
        SmsTenants.set(t.tenantId(), Map.of("daily_cap", 1));
        String a = receipt(rig, t, "+256700000461");
        String b = receipt(rig, t, "+256700000462");
        String c = receipt(rig, t, "+256700000463");
        assertThat(rig.dispatch(10)).isEqualTo(3);
        assertThat(List.of(a, b, c).stream()
                        .map(k -> outboxRow(k).get("status"))
                        .sorted(java.util.Comparator.comparing(Object::toString)))
                .containsExactly("pending", "sent", "sent");

        // Two minutes later it is 00:01 on the next Kampala day: the count starts again.
        rig.at(Instant.parse("2031-04-02T21:01:00Z"));
        String d = receipt(rig, t, "+256700000464");
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(outboxRow(d).get("status")).isEqualTo("sent");
        assertThat(rig.provider.sent()).hasSize(3);
    }

    @Test
    void theMonthlyCapCountsAcrossDaysAndResetsAtTheKampalaMidnightOfTheFirst() {
        // Day boundary inside a month: 2031-03-14 20:59:58 UTC to 21:00:01 UTC. A cap of 1 a month lets receipts reach
        // 2.
        Rig rig = live(Instant.parse("2031-03-14T20:59:58Z"));
        TestDatabase.Fixture t = tenant(100);
        SmsTenants.set(t.tenantId(), Map.of("monthly_cap", 1));
        String a = receipt(rig, t, "+256700000471");
        String b = receipt(rig, t, "+256700000472");
        assertThat(rig.dispatch(10)).isEqualTo(2);
        assertThat(outboxRow(a).get("status")).isEqualTo("sent");
        assertThat(outboxRow(b).get("status")).isEqualTo("sent");

        // The day changed (the daily count is zero again) but the month did not: the third is held.
        rig.at(Instant.parse("2031-03-14T21:00:01Z"));
        String c = receipt(rig, t, "+256700000473");
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(outboxRow(c).get("status")).isEqualTo("pending");
        assertThat(rig.provider.sent()).hasSize(2);

        // Month boundary: 2031-03-31 20:59:59 UTC is still March, 21:00:00 UTC is 00:00 on 1 April in Kampala.
        Rig month = live(Instant.parse("2031-03-31T20:59:50Z"));
        TestDatabase.Fixture u = tenant(100);
        SmsTenants.set(u.tenantId(), Map.of("monthly_cap", 1));
        String m1 = receipt(month, u, "+256700000481");
        String m2 = receipt(month, u, "+256700000482");
        assertThat(month.dispatch(10)).isEqualTo(2);
        month.at(Instant.parse("2031-03-31T20:59:59Z"));
        String m3 = receipt(month, u, "+256700000483");
        assertThat(month.dispatch(10)).isEqualTo(1);
        assertThat(outboxRow(m3).get("status"))
                .as("still March at 23:59:59 in Kampala")
                .isEqualTo("pending");
        month.at(Instant.parse("2031-03-31T21:00:00Z"));
        String m4 = receipt(month, u, "+256700000484");
        assertThat(month.dispatch(10)).isEqualTo(1);
        assertThat(outboxRow(m4).get("status")).as("1 April in Kampala").isEqualTo("sent");
        assertThat(outboxRow(m1).get("status")).isEqualTo("sent");
        assertThat(outboxRow(m2).get("status")).isEqualTo("sent");
        assertThat(month.provider.sent()).hasSize(3);
    }

    @Test
    void aNumberOnTheListBlocksItWhoeverTheMessageIsAboutAndAMemberRowFollowsTheMember() {
        Rig rig = live(day(66));
        TestDatabase.Fixture t = tenant(100);
        String stopped = "+256700000491";
        // A number alone (a pasted STOP list), scope all: blocks a receipt about a member, and one about nobody.
        TestDatabase.owner()
                .sql("INSERT INTO sms_opt_outs (id, tenant_id, recipient_hash, recipient_masked, scope, source)"
                        + " VALUES (?, ?, ?, '+256*****0491', 'all', 'import')")
                .params(UUID.randomUUID(), t.tenantId(), hasher.of(stopped))
                .update();
        String aboutMember = key();
        String aboutNobody = key();
        assertThat(enqueue(rig, t, "0700000491", Outbox.TRANSACTIONAL, UUID.randomUUID(), aboutMember))
                .isTrue();
        assertThat(enqueue(rig, t, stopped, Outbox.TRANSACTIONAL, null, aboutNobody))
                .isTrue();
        assertThat(rig.dispatch(10)).isEqualTo(2);
        for (String blocked : List.of(aboutMember, aboutNobody)) {
            assertThat(delivery(blocked).get("status")).isEqualTo("blocked_optout");
            assertThat(outboxRow(blocked).get("status")).isEqualTo("failed");
        }
        assertThat(rig.provider.sent()).isEmpty();

        // The other tenant does not see this row: the same number is theirs to text.
        TestDatabase.Fixture other = tenant(100);
        String theirs = key();
        assertThat(enqueue(rig, other, stopped, Outbox.TRANSACTIONAL, null, theirs))
                .isTrue();
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(delivery(theirs).get("status")).isEqualTo("sent");

        // A number row with scope reminders blocks a reminder and lets a receipt through.
        String remindersOnly = "+256700000492";
        TestDatabase.owner()
                .sql(
                        "INSERT INTO sms_opt_outs (id, tenant_id, recipient_hash, scope, source) VALUES (?, ?, ?, 'reminders', 'number')")
                .params(UUID.randomUUID(), t.tenantId(), hasher.of(remindersOnly))
                .update();
        String reminder = key();
        String receipt = key();
        assertThat(enqueue(rig, t, remindersOnly, Outbox.REMINDER, null, reminder))
                .isTrue();
        assertThat(enqueue(rig, t, remindersOnly, Outbox.TRANSACTIONAL, null, receipt))
                .isTrue();
        rig.at(kampala(rig.clock.now, 10, 0));
        assertThat(rig.dispatch(10)).isEqualTo(2);
        assertThat(delivery(reminder).get("status")).isEqualTo("blocked_optout");
        assertThat(delivery(receipt).get("status")).isEqualTo("sent");

        // A member-wide row (no number) follows the member to a new number, and does not touch another member.
        UUID member = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO sms_opt_outs (id, tenant_id, member_id, scope, source) VALUES (?, ?, ?, 'all', 'staff')")
                .params(UUID.randomUUID(), t.tenantId(), member)
                .update();
        String followed = key();
        String reused = key();
        assertThat(enqueue(rig, t, "+256700000493", Outbox.TRANSACTIONAL, member, followed))
                .isTrue();
        assertThat(enqueue(rig, t, "+256700000493", Outbox.TRANSACTIONAL, UUID.randomUUID(), reused))
                .isTrue();
        assertThat(rig.dispatch(10)).isEqualTo(2);
        assertThat(delivery(followed).get("status")).isEqualTo("blocked_optout");
        assertThat(delivery(reused).get("status")).isEqualTo("sent");

        // Lifted: the number row no longer blocks.
        TestDatabase.owner()
                .sql("UPDATE sms_opt_outs SET removed_at = now() WHERE tenant_id = ? AND recipient_hash = ?")
                .params(t.tenantId(), hasher.of(stopped))
                .update();
        String afterLift = key();
        assertThat(enqueue(rig, t, stopped, Outbox.TRANSACTIONAL, null, afterLift))
                .isTrue();
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(delivery(afterLift).get("status")).isEqualTo("sent");
    }

    // ---- The tenant's own wording -------------------------------------------------------------------

    void override(TestDatabase.Fixture t, String eventKey, String body) {
        TestDatabase.owner()
                .sql("INSERT INTO sms_template_overrides (id, tenant_id, event_key, body) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (tenant_id, event_key) DO UPDATE SET body = excluded.body")
                .params(UUID.randomUUID(), t.tenantId(), eventKey, body)
                .update();
    }

    @Test
    void anOverrideIsSentExactlyWithTheNameInsertedAsPlainTextAndCountedInTheEncodingItNeeds() {
        Rig rig = live(day(67));
        TestDatabase.Fixture t = tenant(100);
        override(t, "savings.deposit", "{tenant_name}: received {currency} {amount} into {account_no}. Thanks!");
        int sentSoFar = 0;
        for (String name : List.of("A & B <Sacco> \"Quoted\" 'x'", "مشروع الأمل", "幸福銀行", "{amount} {unknown} $1")) {
            Map<String, String> params = new java.util.HashMap<>(PARAMS);
            params.put("tenant_name", name);
            String key = key();
            assertThat(enqueueWith(rig, t, "+256700000501", params, key)).isTrue();
            assertThat(rig.dispatch(10)).isEqualTo(1);
            String body = rig.provider.sent().get(sentSoFar++).body();
            // Exactly the tenant's wording, the name as it is: nothing escaped, nothing read for placeholders again.
            assertThat(body).isEqualTo(name + ": received UGX 5,000 into SV000001. Thanks!");
            // The log counts the segments of what was really sent, in the encoding it needed.
            assertThat(delivery(key).get("segments"))
                    .isEqualTo(SmsSegments.count(body).segments());
        }
        // A name outside the SMS alphabet makes the whole message UCS-2; a plain one does not.
        assertThat(SmsSegments.count(rig.provider.sent().get(1).body()).encoding())
                .isEqualTo(SmsSegments.Encoding.UCS2);
        assertThat(SmsSegments.count(rig.provider.sent().get(0).body()).encoding())
                .isEqualTo(SmsSegments.Encoding.GSM7);
    }

    @Test
    void anOverrideNeverCarriesTheBalanceAndABrokenOneFallsBackToTheDefaultWording() {
        Rig rig = live(day(68));
        TestDatabase.Fixture t = tenant(100);
        SmsTenants.set(t.tenantId(), Map.of("include_balance", true));
        // With the balance switch on and no override, the default wording carries the balance.
        Map<String, String> withBalance = new java.util.HashMap<>(PARAMS);
        withBalance.put("balance", "99,000");
        assertThat(enqueueWith(rig, t, "+256700000511", withBalance, key())).isTrue();
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent().get(0).body()).contains("Balance UGX 99,000");

        // An override says what it says: no balance, even though the parameter is there.
        override(t, "savings.deposit", "Received {amount} on {account_no}");
        assertThat(enqueueWith(rig, t, "+256700000512", withBalance, key())).isTrue();
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent().get(1).body())
                .isEqualTo("Received 5,000 on SV000001")
                .doesNotContain("99,000");

        // A saved wording that no longer passes the rules (written past the screen) costs no receipt: the default goes.
        override(t, "savings.deposit", "Received {amount} on {account_no} balance {balance}");
        assertThat(enqueueWith(rig, t, "+256700000513", PARAMS, key())).isTrue();
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent().get(2).body()).startsWith("Test Lender: Deposit of UGX 5,000");

        // One business's wording is not another's.
        TestDatabase.Fixture other = tenant(100);
        assertThat(enqueueWith(rig, other, "+256700000514", PARAMS, key())).isTrue();
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(rig.provider.sent().get(3).body()).startsWith("Test Lender: Deposit of");
    }

    // JUSTIFICATION-A3: tests for review findings 3, 4 and 6 need this class's Rig and helpers.

    /** Finding 6: a row queued before the switch-on is old news even when it was claimed before the switch-on committed. */
    @Test
    void aRowQueuedBeforeTheMomentOfTheSwitchOnIsRefusedEvenThoughTheTenantIsOnWhenItIsRead() {
        Rig rig = live(day(80));
        TestDatabase.Fixture t = tenant(100);
        String old = receipt(rig, t, "+256700000521");
        // The tenant switches on a minute after the row was queued (the stamp is the acknowledgement's time), and the
        // channel reads the settings after that commit.
        Instant switchedOn = rig.clock.now.plus(Duration.ofMinutes(1));
        SmsTenants.set(t.tenantId(), Map.of("enabled", true, "acknowledged_at", switchedOn.toString()));
        rig.advance(Duration.ofMinutes(2));
        String fresh = receipt(rig, t, "+256700000522");
        assertThat(rig.dispatch(10)).isEqualTo(2);
        assertThat(outboxRow(old).get("status")).isEqualTo("failed");
        assertThat(outboxRow(old).get("last_error")).isEqualTo("backlog_dropped");
        assertThat(hasDelivery(old)).isFalse();
        assertThat(outboxRow(fresh).get("status")).isEqualTo("sent");
        assertThat(rig.provider.sent())
                .extracting(RecordingSmsProvider.Sent::to)
                .containsExactly("+256700000522");
    }

    /** Finding 3: a window stored outside the hard limits (an old value copied or written by hand) is clamped on read. */
    @Test
    void aWindowStoredOutsideTheHardLimitsIsClampedWhenTheChannelReadsIt() {
        Rig rig = live(kampala(day(81), 5, 30));
        TestDatabase.Fixture t = tenant(100);
        SmsTenants.set(t.tenantId(), Map.of("window_start", "05:00", "window_end", "23:00"));
        String early = key();
        raw(t, "reminder", "+256700000531", rig.clock.now.minus(Duration.ofMinutes(5)), early);
        assertThat(rig.dispatch(10)).isEqualTo(1);
        // 05:30 is before 06:00, the hard limit, though the stored window opened at 05:00: it waits for 06:00.
        assertThat(outboxRow(early).get("status")).isEqualTo("pending");
        assertThat(at(outboxRow(early).get("next_attempt_at"))).isEqualTo(kampala(day(81), 6, 0));
        rig.at(kampala(day(81), 6, 0));
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(outboxRow(early).get("status")).isEqualTo("sent");

        // 21:00 is after the hard limit though the stored window ran to 23:00: held for the next morning.
        rig.at(kampala(day(81), 21, 0));
        String late = key();
        raw(t, "reminder", "+256700000532", rig.clock.now.minus(Duration.ofMinutes(5)), late);
        assertThat(rig.dispatch(10)).isEqualTo(1);
        assertThat(outboxRow(late).get("status")).isEqualTo("pending");
        assertThat(at(outboxRow(late).get("next_attempt_at"))).isEqualTo(kampala(day(82), 6, 0));
        assertThat(rig.provider.sent()).hasSize(1);
    }

    /** Finding 4: a value too long for its place is never shortened; the default wording goes, whole. */
    @Test
    void aValueTooLongForTheOverrideSendsTheDefaultWordingWholeAndNeverACutNumber() {
        Rig rig = live(day(83));
        TestDatabase.Fixture t = tenant(100);
        override(t, "savings.deposit", "Received {amount} on {account_no}");
        String amount = "1".repeat(25);
        Map<String, String> params = new java.util.HashMap<>(PARAMS);
        params.put("amount", amount);
        assertThat(enqueueWith(rig, t, "+256700000541", params, key())).isTrue();
        assertThat(rig.dispatch(10)).isEqualTo(1);
        String body = rig.provider.sent().getFirst().body();
        assertThat(body).contains(amount).startsWith("Test Lender: Deposit of UGX " + amount);
    }
}
