package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.notifications.internal.SmsConfiguration.SmsProviderRef;
import com.rincoltech.bms.core.notifications.internal.SmsTestService.Result;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.SmsTenants;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Review finding 1 of #274: the test message in allowlist mode (which a production pilot check uses) is a real send to a
 * real phone, so before it goes it obeys what a real message obeys (both switches, the opt-out list, the caps), and once
 * sent it is in the delivery and cost log and counts toward the caps. A recording provider remembers every call, so
 * "nothing was sent" is checked at the provider. Fabricated numbers; no network.
 */
class SmsTestMessageGateIT extends IntegrationTest {

    static final String ALLOWED = "+256700000611";
    static final Set<String> ALLOW = Set.of(ALLOWED);

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

    @AfterEach
    void clear() {
        CurrentPrincipal.clear();
    }

    /** A tenant whose SMS is on at both switches with a generous cap: the state in which a real test send may go. */
    TestDatabase.Fixture readyTenant() {
        TestDatabase.Fixture t = TestDatabase.tenant("sms-gate", true);
        SmsTenants.operator(t.tenantId(), true, 1000);
        SmsTenants.enable(t.tenantId());
        return t;
    }

    Result send(RecordingSmsProvider real, TestDatabase.Fixture t, String to) {
        SmsSettings settings = new SmsSettings(
                "test", SmsSettings.Mode.ALLOWLIST, SmsSettings.Kind.FAKE, ALLOW, 1000, null, null, null);
        SmsTestService service = new SmsTestService(
                settings,
                new SmsProviderRef(new AllowlistSmsProvider(real, new SinkSmsProvider(), ALLOW)),
                templates,
                jdbc,
                audit,
                transactionManager,
                tenantConfig,
                hasher,
                clock);
        CurrentPrincipal.set(Principal.uniform(
                UUID.randomUUID(), "staff", Set.of("core.notification_templates.manage"), true, Set.of()));
        return TenantContext.callAs(t.tenantId(), () -> service.send("savings.deposit", null, to));
    }

    long deliveries(TestDatabase.Fixture t) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM sms_deliveries WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    @Test
    void aRealTestSendIsInTheDeliveryAndCostLogWithItsCostMaskedAndWithoutTheText() {
        RecordingSmsProvider real = new RecordingSmsProvider();
        TestDatabase.Fixture t = readyTenant();
        Result r = send(real, t, "0700000611");
        assertThat(r.outcome()).isEqualTo("sent");
        assertThat(real.sent()).hasSize(1);
        Map<String, Object> row = TestDatabase.owner()
                .sql(
                        "SELECT row_to_json(d)::text AS json, status, template_key, recipient_masked, segments, cost_minor,"
                                + " currency, recipient_hash FROM sms_deliveries d WHERE tenant_id = ?")
                .param(t.tenantId())
                .query()
                .singleRow();
        assertThat(row.get("status")).isEqualTo("sent");
        assertThat(row.get("template_key")).isEqualTo("test.savings.deposit");
        assertThat(row.get("recipient_masked")).isEqualTo("+256*****0611");
        assertThat(row.get("segments")).isEqualTo(1);
        assertThat(row.get("cost_minor")).isEqualTo(32L);
        assertThat(row.get("currency")).isEqualTo("UGX");
        assertThat(row.get("recipient_hash")).isEqualTo(hasher.of(ALLOWED));
        assertThat((String) row.get("json")).doesNotContain("700000611").doesNotContain("TEST:");
    }

    @Test
    void aRealTestSendIsRefusedBeforeAnythingGoesWhenTheOperatorOrTheTenantSwitchIsOff() {
        RecordingSmsProvider real = new RecordingSmsProvider();
        TestDatabase.Fixture operatorOff = TestDatabase.tenant("sms-gate", true);
        SmsTenants.enable(operatorOff.tenantId());
        Result r = send(real, operatorOff, "0700000611");
        assertThat(r.outcome()).isEqualTo("not_sent");
        assertThat(r.sent()).isFalse();
        assertThat(r.reason()).isEqualTo("operator_sms_off");

        TestDatabase.Fixture tenantOff = TestDatabase.tenant("sms-gate", true);
        SmsTenants.operator(tenantOff.tenantId(), true, 1000);
        assertThat(send(real, tenantOff, "0700000611").reason()).isEqualTo("tenant_sms_off");

        // The operator switched off after the tenant switched on: still refused.
        TestDatabase.Fixture later = readyTenant();
        SmsTenants.operator(later.tenantId(), false, 1000);
        assertThat(send(real, later, "0700000611").reason()).isEqualTo("operator_sms_off");

        assertThat(real.sent()).isEmpty();
        for (TestDatabase.Fixture refused : List.of(operatorOff, tenantOff, later)) {
            assertThat(deliveries(refused)).isZero();
        }
    }

    @Test
    void aRealTestSendHonoursTheOptOutListForEveryMessageButNotARemindersOnlyRow() {
        RecordingSmsProvider real = new RecordingSmsProvider();
        TestDatabase.Fixture optedOut = readyTenant();
        TestDatabase.owner()
                .sql("INSERT INTO sms_opt_outs (id, tenant_id, recipient_hash, recipient_masked, scope, source)"
                        + " VALUES (?, ?, ?, '+256*****0611', 'all', 'number')")
                .params(UUID.randomUUID(), optedOut.tenantId(), hasher.of(ALLOWED))
                .update();
        // Typed another way, the same number is the same row.
        Result blocked = send(real, optedOut, "+256 700 000 611");
        assertThat(blocked.outcome()).isEqualTo("not_sent");
        assertThat(blocked.reason()).isEqualTo("blocked_optout");
        assertThat(real.sent()).isEmpty();
        assertThat(deliveries(optedOut)).isZero();

        // A lifted row no longer blocks.
        TestDatabase.owner()
                .sql("UPDATE sms_opt_outs SET removed_at = now() WHERE tenant_id = ?")
                .param(optedOut.tenantId())
                .update();
        assertThat(send(real, optedOut, "0700000611").outcome()).isEqualTo("sent");

        // A row for reminders only does not block a message of the receipt kind, which is what a test is.
        TestDatabase.Fixture remindersOnly = readyTenant();
        TestDatabase.owner()
                .sql("INSERT INTO sms_opt_outs (id, tenant_id, recipient_hash, scope, source)"
                        + " VALUES (?, ?, ?, 'reminders', 'number')")
                .params(UUID.randomUUID(), remindersOnly.tenantId(), hasher.of(ALLOWED))
                .update();
        assertThat(send(real, remindersOnly, "0700000611").outcome()).isEqualTo("sent");

        // Another business's opt-out of the same number is not this business's.
        TestDatabase.Fixture neighbour = readyTenant();
        assertThat(send(real, neighbour, "0700000611").outcome()).isEqualTo("sent");
        assertThat(real.sent()).hasSize(3);
    }

    @Test
    void aCapOfZeroTheTenantsOrTheOperatorsOrADailyCapAlreadyUsedStopsARealTestSend() {
        RecordingSmsProvider real = new RecordingSmsProvider();
        TestDatabase.Fixture tenantZero = readyTenant();
        SmsTenants.set(tenantZero.tenantId(), Map.of("daily_cap", 0));
        assertThat(send(real, tenantZero, "0700000611").reason()).isEqualTo("cap_reached");

        TestDatabase.Fixture monthZero = readyTenant();
        SmsTenants.set(monthZero.tenantId(), Map.of("monthly_cap", 0));
        assertThat(send(real, monthZero, "0700000611").reason()).isEqualTo("cap_reached");

        TestDatabase.Fixture operatorZero = TestDatabase.tenant("sms-gate", true);
        SmsTenants.operator(operatorZero.tenantId(), true, 0);
        SmsTenants.enable(operatorZero.tenantId());
        assertThat(send(real, operatorZero, "0700000611").reason()).isEqualTo("cap_reached");

        // A cap of 2 allows two real tests (each counted in the log) and refuses the third.
        TestDatabase.Fixture two = readyTenant();
        SmsTenants.set(two.tenantId(), Map.of("daily_cap", 2));
        assertThat(send(real, two, "0700000611").outcome()).isEqualTo("sent");
        assertThat(send(real, two, "0700000611").outcome()).isEqualTo("sent");
        Result third = send(real, two, "0700000611");
        assertThat(third.outcome()).isEqualTo("not_sent");
        assertThat(third.reason()).isEqualTo("cap_reached");
        assertThat(deliveries(two)).isEqualTo(2);

        assertThat(real.sent()).hasSize(2);
        for (TestDatabase.Fixture refused : List.of(tenantZero, monthZero, operatorZero)) {
            assertThat(deliveries(refused)).isZero();
        }
    }

    @Test
    void anAmbiguousRealTestSendIsLoggedUnknownBecauseItMayHaveGoneAndCost() {
        RecordingSmsProvider real = new RecordingSmsProvider();
        real.timeOut("provider_timeout");
        TestDatabase.Fixture t = readyTenant();
        Result r = send(real, t, "0700000611");
        assertThat(r.outcome()).isEqualTo("not_sent");
        assertThat(r.reason()).isEqualTo("provider_timeout");
        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM sms_deliveries WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo("unknown");
    }

    /** Round-2 review: the gate, the rate limit and the reservation are one step, so a cap cannot be passed twice. */
    @Test
    void twoRealTestSendsAtCapMinusOneCannotBothGo() throws Exception {
        RecordingSmsProvider real = new RecordingSmsProvider();
        TestDatabase.Fixture t = readyTenant();
        SmsTenants.set(t.tenantId(), Map.of("daily_cap", 2));
        assertThat(send(real, t, "0700000611").outcome()).isEqualTo("sent");

        int n = 4;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Result>> results = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return send(real, t, "0700000611");
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            int sent = 0;
            int capped = 0;
            for (Future<Result> f : results) {
                Result r = f.get(60, TimeUnit.SECONDS);
                if ("sent".equals(r.outcome())) {
                    sent++;
                } else {
                    assertThat(r.reason()).isEqualTo("cap_reached");
                    capped++;
                }
            }
            assertThat(sent).isEqualTo(1);
            assertThat(capped).isEqualTo(n - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(real.sent()).hasSize(2);
        assertThat(deliveries(t)).isEqualTo(2);
    }

    /** Round-2 review: a message the provider accepted is never missing from the log, even if the log cannot be settled. */
    @Test
    void anAcceptedRealTestSendIsStillCountedWhenItsLogRowCannotBeSettled() {
        RecordingSmsProvider real = new RecordingSmsProvider();
        TestDatabase.Fixture t = readyTenant();
        SmsTenants.set(t.tenantId(), Map.of("daily_cap", 1));
        TestDatabase.owner()
                .sql("CREATE OR REPLACE FUNCTION test_refuse_settle() RETURNS trigger LANGUAGE plpgsql AS $$"
                        + " BEGIN RAISE EXCEPTION 'cannot settle'; END $$")
                .update();
        TestDatabase.owner()
                .sql("CREATE TRIGGER test_refuse_settle BEFORE UPDATE ON sms_deliveries FOR EACH ROW"
                        + " WHEN (NEW.tenant_id = '" + t.tenantId() + "') EXECUTE FUNCTION test_refuse_settle()")
                .update();
        try {
            Result r = send(real, t, "0700000611");
            // The provider took it: the caller is told so, and nothing throws.
            assertThat(r.sent()).isTrue();
            assertThat(r.outcome()).isEqualTo("sent");
            assertThat(real.sent()).hasSize(1);
            // The reservation made before the send is the log row, and it counts.
            assertThat(TestDatabase.owner()
                            .sql("SELECT status FROM sms_deliveries WHERE tenant_id = ?")
                            .param(t.tenantId())
                            .query(String.class)
                            .single())
                    .isEqualTo("queued");
            assertThat(send(real, t, "0700000611").reason()).isEqualTo("cap_reached");
        } finally {
            TestDatabase.owner()
                    .sql("DROP TRIGGER IF EXISTS test_refuse_settle ON sms_deliveries")
                    .update();
            TestDatabase.owner()
                    .sql("DROP FUNCTION IF EXISTS test_refuse_settle()")
                    .update();
        }
    }

    @Test
    void aNumberThatIsNotOnTheAllowListIsSunkWhateverTheSwitchesAndLeavesNoLogRow() {
        RecordingSmsProvider real = new RecordingSmsProvider();
        TestDatabase.Fixture off = TestDatabase.tenant("sms-gate", true);
        Result r = send(real, off, "0700000699");
        assertThat(r.outcome()).isEqualTo("sunk");
        assertThat(real.sent()).isEmpty();
        assertThat(deliveries(off)).isZero();
    }
}
