package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.notifications.internal.SmsConfiguration.SmsProviderRef;
import com.rincoltech.bms.core.notifications.internal.SmsTestService.Result;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.SmsTenants;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The test message of the wording screen (spec 4.10) in every mode of the channel: it reaches the sink or the allow-list
 * and nothing else. The service is built here with each mode's provider arrangement (the way {@code SmsConfiguration}
 * builds it) and a recording provider that remembers every call, so "nothing was sent" is checked at the provider, not
 * read off the answer. Fabricated numbers; no network.
 */
class SmsTestMessageIT extends IntegrationTest {

    static final String ALLOWED = "+256700000611";

    @Autowired
    SmsTemplateService templates;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    AuditLog audit;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    SmsTenantConfig tenantConfig;

    @Autowired
    SmsRecipientHash hasher;

    @Autowired
    BusinessClock clock;

    // JUSTIFICATION-A3: the gating cases of review finding 1 need a ready tenant and a delivery count in this class.
    /** A tenant whose SMS is on at both switches with a generous cap: the state in which a real test send may go. */
    TestDatabase.Fixture readyTenant() {
        TestDatabase.Fixture t = TestDatabase.tenant("sms-test", true);
        SmsTenants.operator(t.tenantId(), true, 1000);
        SmsTenants.enable(t.tenantId());
        return t;
    }

    long deliveries(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM sms_deliveries WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    @AfterEach
    void clear() {
        CurrentPrincipal.clear();
    }

    SmsSettings settings(SmsSettings.Mode mode, Set<String> allowlist) {
        return new SmsSettings("test", mode, SmsSettings.Kind.FAKE, allowlist, 1000, null, null, null);
    }

    /** The provider arrangement of a mode, as SmsConfiguration builds it, around a recording provider. */
    SmsProvider arrangement(SmsSettings.Mode mode, Set<String> allowlist, RecordingSmsProvider real) {
        return switch (mode) {
            case OFF -> null;
            case SINK -> new SinkSmsProvider();
            case ALLOWLIST -> new AllowlistSmsProvider(real, new SinkSmsProvider(), allowlist);
            case LIVE -> real;
        };
    }

    Result send(
            SmsSettings.Mode mode,
            Set<String> allowlist,
            RecordingSmsProvider real,
            TestDatabase.Fixture t,
            String to) {
        return send(mode, allowlist, real, t, to, null);
    }

    Result send(
            SmsSettings.Mode mode,
            Set<String> allowlist,
            RecordingSmsProvider real,
            TestDatabase.Fixture t,
            String to,
            String text) {
        SmsTestService service = new SmsTestService(
                settings(mode, allowlist),
                new SmsProviderRef(arrangement(mode, allowlist, real)),
                templates,
                jdbc,
                audit,
                transactionManager,
                tenantConfig,
                hasher,
                clock);
        CurrentPrincipal.set(Principal.uniform(
                UUID.randomUUID(), "staff", Set.of("core.notification_templates.manage"), true, Set.of()));
        return TenantContext.callAs(t.tenantId(), () -> service.send("savings.deposit", text, to));
    }

    long audited(TestDatabase.Fixture t, String action) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?")
                .params(t.tenantId(), action)
                .query(Long.class)
                .single();
    }

    @Test
    void withTheChannelOffNothingIsSentAndTheAnswerSaysSo() {
        TestDatabase.Fixture t = TestDatabase.tenant("sms-test", true);
        RecordingSmsProvider real = new RecordingSmsProvider();
        Result r = send(SmsSettings.Mode.OFF, Set.of(), real, t, "0700000611");
        assertThat(r.sent()).isFalse();
        assertThat(r.outcome()).isEqualTo("not_sent");
        assertThat(r.reason()).isEqualTo("channel_off");
        assertThat(r.mode()).isEqualTo("off");
        assertThat(real.sent()).isEmpty();
        // The wording is still shown, with its prefix and its measure, so the screen can preview it.
        assertThat(r.text()).startsWith("TEST: ").contains(": Deposit of UGX 50,000");
        assertThat(r.measure().segments()).isEqualTo(1);
    }

    @Test
    void inSinkModeItIsRecordedByTheSinkAndANumberIsNeverReached() {
        TestDatabase.Fixture t = TestDatabase.tenant("sms-test", true);
        RecordingSmsProvider real = new RecordingSmsProvider();
        Result r = send(SmsSettings.Mode.SINK, Set.of(), real, t, "+256 700 000 611");
        assertThat(r.sent()).isFalse();
        assertThat(r.outcome()).isEqualTo("sunk");
        assertThat(r.recipientMasked()).isEqualTo("+256*****0611");
        assertThat(real.sent()).isEmpty();
    }

    @Test
    void inAllowlistModeOnlyAListedNumberReachesTheProviderAndAnyOtherIsSunk() {
        TestDatabase.Fixture t = readyTenant();
        RecordingSmsProvider real = new RecordingSmsProvider();
        Set<String> allow = Set.of(ALLOWED);

        Result stranger = send(SmsSettings.Mode.ALLOWLIST, allow, real, t, "0700000699");
        assertThat(stranger.sent()).isFalse();
        assertThat(stranger.outcome()).isEqualTo("sunk");
        assertThat(real.sent())
                .as("an unlisted number is never handed to the provider")
                .isEmpty();

        Result listed = send(SmsSettings.Mode.ALLOWLIST, allow, real, t, "0700000611");
        assertThat(listed.sent()).isTrue();
        assertThat(listed.outcome()).isEqualTo("sent");
        assertThat(real.sent()).hasSize(1);
        assertThat(real.sent().getFirst().to()).isEqualTo(ALLOWED);
        assertThat(real.sent().getFirst().body()).startsWith("TEST: ");
        assertThat(real.sent().getFirst().senderId())
                .as("a test uses the shared sender")
                .isNull();
    }

    @Test
    void inAllowlistModeAProviderThatWouldReachEveryoneIsStillOnlyUsedForListedNumbers() {
        // Defence in depth: even handed the bare real provider (no allow-list wrapper), the service itself keeps
        // strangers off it.
        TestDatabase.Fixture t = TestDatabase.tenant("sms-test", true);
        RecordingSmsProvider real = new RecordingSmsProvider();
        SmsTestService service = new SmsTestService(
                settings(SmsSettings.Mode.ALLOWLIST, Set.of(ALLOWED)),
                new SmsProviderRef(real),
                templates,
                jdbc,
                audit,
                transactionManager,
                tenantConfig,
                hasher,
                clock);
        CurrentPrincipal.set(Principal.uniform(UUID.randomUUID(), "staff", Set.of(), true, Set.of()));
        Result r = TenantContext.callAs(t.tenantId(), () -> service.send("savings.deposit", null, "0700000698"));
        assertThat(r.outcome()).isEqualTo("sunk");
        assertThat(real.sent()).isEmpty();
    }

    @Test
    void inLiveModeNothingIsEverSentToANumberTypedIntoTheForm() {
        TestDatabase.Fixture t = TestDatabase.tenant("sms-test", true);
        RecordingSmsProvider real = new RecordingSmsProvider();
        // Not even to a number that would be on an allow-list somewhere else.
        Result r = send(SmsSettings.Mode.LIVE, Set.of(ALLOWED), real, t, "0700000611");
        assertThat(r.sent()).isFalse();
        assertThat(r.outcome()).isEqualTo("not_sent");
        assertThat(r.reason()).isEqualTo("live_mode_preview_only");
        assertThat(real.sent()).isEmpty();
    }

    @Test
    void aNumberWeWillNotTextAndBadWordingAreRefusedBeforeAnythingIsSentOrCounted() {
        TestDatabase.Fixture t = TestDatabase.tenant("sms-test", true);
        RecordingSmsProvider real = new RecordingSmsProvider();
        for (String bad : List.of("2125550101", "+15555550100", "70000611", "not a number", "")) {
            assertThatThrownBy(() -> send(SmsSettings.Mode.ALLOWLIST, Set.of(bad), real, t, bad))
                    .as(bad)
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                        assertThat(e.errors().getFirst().code()).isEqualTo("invalid_phone");
                    });
        }
        assertThatThrownBy(() -> send(
                        SmsSettings.Mode.SINK,
                        Set.of(),
                        real,
                        t,
                        "0700000611",
                        "Hello {unknown} {amount} {account_no}"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.errors().getFirst().code()).isEqualTo("unknown_placeholder"));
        assertThat(real.sent()).isEmpty();
        assertThat(audited(t, "core.sms.test_message"))
                .as("refused requests do not count toward the limit")
                .isZero();
    }

    @Test
    void aTestMessageIsAuditedWithAMaskedNumberLimitedPerHourAndKeptOutOfTheDeliveryLog() {
        TestDatabase.Fixture t = TestDatabase.tenant("sms-test", true);
        RecordingSmsProvider real = new RecordingSmsProvider();
        for (int i = 0; i < SmsTestService.PER_HOUR; i++) {
            assertThat(send(SmsSettings.Mode.SINK, Set.of(), real, t, "0700000611")
                            .outcome())
                    .isEqualTo("sunk");
        }
        assertThatThrownBy(() -> send(SmsSettings.Mode.SINK, Set.of(), real, t, "0700000611"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.code()).isEqualTo("rate_limited");
                });
        // Another business has its own allowance.
        TestDatabase.Fixture other = TestDatabase.tenant("sms-test", true);
        assertThat(send(SmsSettings.Mode.SINK, Set.of(), real, other, "0700000611")
                        .outcome())
                .isEqualTo("sunk");

        assertThat(audited(t, "core.sms.test_message")).isEqualTo(SmsTestService.PER_HOUR);
        assertThat(audited(t, "core.sms.test_message_result")).isEqualTo(SmsTestService.PER_HOUR);
        String data = TestDatabase.owner()
                .sql(
                        "SELECT string_agg(data::text, ' ') FROM audit_log WHERE tenant_id = ? AND action LIKE 'core.sms.test_message%'")
                .param(t.tenantId())
                .query(String.class)
                .single();
        assertThat(data).contains("*********0611").doesNotContain("700000611").doesNotContain("TEST:");
        // Not an outbox row and not a delivery row: a test is not a message, and costs no part of the cap.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM sms_deliveries WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
