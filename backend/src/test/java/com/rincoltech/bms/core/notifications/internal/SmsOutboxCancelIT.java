package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.SmsTenants;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Withdrawing a member's queued message when the entry it is about is reversed (issue #221, migration V53), against the
 * real claim and result functions and two real connections: the claim records a lease on the database's clock; a recorded
 * failure releases it and leaves the row waiting to be retried; {@code notification_outbox_cancel_unclaimed} cancels the
 * unclaimed row and the row waiting to be retried, leaves the row a sender holds under a live lease until the lease has
 * run out, never touches an email or Telegram row of the same event reference, and cannot be used by another tenant; and in
 * both orders of the race between a cancel and a claim a row is never both cancelled and claimed.
 *
 * <p>The rows are dated 2001, so that the claim here (called with a 2001 clock) can see none of the due rows other test
 * classes leave in the shared database, and nothing of theirs is touched.
 */
class SmsOutboxCancelIT extends IntegrationTest {

    static final Instant T = Instant.parse("2001-08-20T07:00:00Z");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    OutboxTemplates templates;

    @Autowired
    com.rincoltech.bms.core.tenancy.TenantSettings tenantSettings;

    /** Leaves nothing pending behind: only this class's own rows (all dated 2001) are touched. */
    @AfterEach
    void finish() {
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET status = 'failed', last_error = 'test_cleanup', failed_at = now()"
                        + " WHERE status = 'pending' AND created_at < '2002-01-01'")
                .update();
    }

    JdbcOutbox outboxAt(Instant now) {
        return new JdbcOutbox(jdbc, mapper, new BusinessClock(new MovableClock(now)), templates, tenantSettings);
    }

    TestDatabase.Fixture tenant(String prefix) {
        TestDatabase.Fixture t = TestDatabase.tenant(prefix, true);
        SmsTenants.operator(t.tenantId(), true, 1000);
        SmsTenants.enable(t.tenantId());
        return t;
    }

    String key(TestDatabase.Fixture t, String template, String ref) {
        return "sms:" + template + ":" + t.tenantId() + ":" + ref + ":" + UUID.randomUUID();
    }

    String queue(TestDatabase.Fixture t, String ref) {
        String key = key(t, "savings.deposit", ref);
        enqueue(
                t,
                new Outbox.Message(
                        Outbox.SMS,
                        "+256700000501",
                        "savings.deposit",
                        Map.of(
                                "tenant_name", "Test Lender",
                                "currency", "UGX",
                                "amount", "5,000",
                                "account_no", "SV000001",
                                "receipt_no", "RC-1"),
                        key,
                        null,
                        T.plusSeconds(3600),
                        Outbox.TRANSACTIONAL,
                        Outbox.Subject.member(UUID.randomUUID()),
                        null));
        return key;
    }

    void enqueue(TestDatabase.Fixture t, Outbox.Message message) {
        Boolean queued = TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager)
                        .execute(tx -> outboxAt(T).enqueue(message)));
        assertThat(queued).isTrue();
    }

    int cancel(TestDatabase.Fixture t, String ref) {
        return TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager)
                        .execute(tx -> outboxAt(T).cancelUnclaimed(ref)));
    }

    Map<String, Object> row(String key) {
        return TestDatabase.owner()
                .sql("SELECT status, attempts, last_error, params::text AS params,"
                        + " leased_until IS NOT NULL AS leased, leased_until > clock_timestamp() AS lease_live,"
                        + " leased_until <= clock_timestamp() + interval '121 seconds' AS lease_two_minutes"
                        + " FROM notification_outbox WHERE idempotency_key = ?")
                .param(key)
                .query()
                .singleRow();
    }

    /** Claims one due SMS row as the sender job does, asking for two minutes of lease. */
    String claim() {
        return TestDatabase.owner()
                .sql("SELECT idempotency_key FROM notification_outbox_claim(ARRAY['sms'], 3, ?, ?)")
                .params(Timestamp.from(T.plusSeconds(1)), Timestamp.from(T.plusSeconds(121)))
                .query(String.class)
                .single();
    }

    /** The event reference is the fourth part of a key {@code sms:<template>:<tenant>:<ref>:<member>}. */
    static String ref(String key) {
        return key.split(":")[3];
    }

    @Test
    void theClaimRecordsAndTheResultReleasesTheLeaseAndCancelWithdrawsEverythingNoSenderHolds() {
        TestDatabase.Fixture t = tenant("cancel");
        List<String> keys = List.of(queue(t, "e1"), queue(t, "e2"), queue(t, "e3"));
        assertThat(keys).allSatisfy(k -> assertThat(row(k).get("leased")).isEqualTo(false));

        // Two are claimed: each gets an attempt and a lease of two minutes on the database's clock.
        String heldByASender = claim();
        String failedOnce = claim();
        assertThat(heldByASender).isNotEqualTo(failedOnce);
        for (String k : List.of(heldByASender, failedOnce)) {
            Map<String, Object> claimed = row(k);
            assertThat(claimed.get("attempts")).isEqualTo(1);
            assertThat(claimed.get("lease_live")).isEqualTo(true);
            assertThat(claimed.get("lease_two_minutes")).isEqualTo(true);
        }
        // One of them fails cleanly (a provider 503): pending again for a retry in a minute, the lease released.
        TestDatabase.owner()
                .sql("SELECT notification_outbox_record_result(id, 'failed', 'provider_http_503', 3, ?, ?)"
                        + " FROM notification_outbox WHERE idempotency_key = ?")
                .params(Timestamp.from(T.plusSeconds(60)), Timestamp.from(T.plusSeconds(2)), failedOnce)
                .query(String.class)
                .single();
        Map<String, Object> retrying = row(failedOnce);
        assertThat(retrying.get("status")).isEqualTo("pending");
        assertThat(retrying.get("attempts")).isEqualTo(1);
        assertThat(retrying.get("leased")).isEqualTo(false);
        assertThat(retrying.get("last_error")).isEqualTo("provider_http_503");

        String never = keys.stream()
                .filter(k -> !k.equals(heldByASender) && !k.equals(failedOnce))
                .findFirst()
                .orElseThrow();

        // Another tenant cannot withdraw anything of this one's, whatever reference it names.
        TestDatabase.Fixture other = tenant("cancel-other");
        for (String k : keys) {
            assertThat(cancel(other, ref(k))).isZero();
        }
        assertThat(keys).allSatisfy(k -> assertThat(row(k).get("status")).isEqualTo("pending"));

        // The tenant's own: the row nobody touched and the row waiting to be retried go; the held one stays.
        assertThat(cancel(t, ref(never))).isEqualTo(1);
        assertThat(cancel(t, ref(failedOnce))).isEqualTo(1);
        assertThat(cancel(t, ref(heldByASender)))
                .as("a sender holds it under a live lease")
                .isZero();
        for (String k : List.of(never, failedOnce)) {
            Map<String, Object> gone = row(k);
            assertThat(gone.get("status")).isEqualTo("failed");
            assertThat(gone.get("last_error")).isEqualTo("cancelled_reversed");
            assertThat(gone.get("params")).isEqualTo("{}");
            assertThat(gone.get("leased")).isEqualTo(false);
        }
        assertThat(row(heldByASender).get("status")).isEqualTo("pending");
        assertThat(cancel(t, ref(never))).as("cancelling twice changes nothing").isZero();

        // Once the lease has run out (the sender died), nothing holds the row and it can be withdrawn.
        TestDatabase.owner()
                .sql("UPDATE notification_outbox SET leased_until = clock_timestamp() - interval '1 second'"
                        + " WHERE idempotency_key = ?")
                .param(heldByASender)
                .update();
        assertThat(cancel(t, ref(heldByASender))).isEqualTo(1);
        assertThat(row(heldByASender).get("last_error")).isEqualTo("cancelled_reversed");
    }

    @Test
    void aCancelLeavesAnEmailOrTelegramRowOfTheSameEventReferenceAlone() {
        TestDatabase.Fixture t = tenant("cancel-mail");
        String ref = "shared-ref";
        String sms = queue(t, ref);
        String telegram = key(t, "savings.deposit", ref);
        enqueue(
                t,
                new Outbox.Message(
                        Outbox.TELEGRAM,
                        Outbox.OPERATOR_CHAT,
                        "sms.cap_alert",
                        Map.of("day", "2001-08-20", "scope", "platform", "state", "80 percent", "cap", "1"),
                        telegram));
        String email = "sms:onboarding.verify_email:" + t.tenantId() + ":" + ref + ":" + UUID.randomUUID();
        enqueue(
                t,
                new Outbox.Message(
                        Outbox.EMAIL,
                        "someone@example.test",
                        "onboarding.verify_email",
                        Map.of("reference", "AP-1", "link", "https://example.test/x"),
                        email));
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .as("the three rows carry this tenant and the same event reference")
                .isEqualTo(3);

        assertThat(cancel(t, ref)).isEqualTo(1);

        assertThat(row(sms).get("last_error")).isEqualTo("cancelled_reversed");
        for (String other : List.of(telegram, email)) {
            Map<String, Object> untouched = row(other);
            assertThat(untouched.get("status")).isEqualTo("pending");
            assertThat(untouched.get("last_error")).isNull();
            assertThat(untouched.get("params")).isNotEqualTo("{}");
        }
    }

    @Test
    void whenTheClaimLocksTheRowFirstTheCancelSkipsItAndTheRowGoesOut() throws SQLException {
        TestDatabase.Fixture t = tenant("cancel-race1");
        String key = queue(t, "r1");
        try (Connection claimer = appConnection(t);
                Connection canceller = appConnection(t)) {
            // The sender's claim has updated the row and not yet committed.
            assertThat(one(
                            claimer,
                            "SELECT idempotency_key FROM notification_outbox_claim(ARRAY['sms'], 3, ?, ?)",
                            Timestamp.from(T.plusSeconds(1)),
                            Timestamp.from(T.plusSeconds(121))))
                    .isEqualTo(key);
            // The reversal's cancel does not wait for it and does not touch it.
            assertThat(one(
                            canceller,
                            "SELECT notification_outbox_cancel_unclaimed(?, ?)::text",
                            ref(key),
                            Timestamp.from(T)))
                    .isEqualTo("0");
            canceller.commit();
            claimer.commit();
        }
        Map<String, Object> after = row(key);
        assertThat(after.get("status")).as("not both cancelled and claimed").isEqualTo("pending");
        assertThat(after.get("attempts")).isEqualTo(1);
        assertThat(after.get("last_error")).isNull();
        assertThat(after.get("lease_live")).isEqualTo(true);
    }

    @Test
    void whenTheCancelLocksTheRowFirstTheClaimSkipsItAndTheRowIsNeverClaimed() throws SQLException {
        TestDatabase.Fixture t = tenant("cancel-race2");
        String key = queue(t, "r2");
        try (Connection claimer = appConnection(t);
                Connection canceller = appConnection(t)) {
            // The reversal's cancel has updated the row and not yet committed.
            assertThat(one(
                            canceller,
                            "SELECT notification_outbox_cancel_unclaimed(?, ?)::text",
                            ref(key),
                            Timestamp.from(T)))
                    .isEqualTo("1");
            // The sender's claim does not wait for it and does not take it.
            assertThat(one(
                            claimer,
                            "SELECT idempotency_key FROM notification_outbox_claim(ARRAY['sms'], 3, ?, ?)",
                            Timestamp.from(T.plusSeconds(1)),
                            Timestamp.from(T.plusSeconds(121))))
                    .as("nothing to claim: the only due row is locked by the cancel")
                    .isNull();
            canceller.commit();
            claimer.commit();
        }
        Map<String, Object> after = row(key);
        assertThat(after.get("status")).isEqualTo("failed");
        assertThat(after.get("last_error")).isEqualTo("cancelled_reversed");
        assertThat(after.get("attempts")).as("never claimed").isEqualTo(0);
        // And a claim afterwards finds nothing either.
        assertThat(TestDatabase.owner()
                        .sql("SELECT idempotency_key FROM notification_outbox_claim(ARRAY['sms'], 3, ?, ?)")
                        .params(Timestamp.from(T.plusSeconds(5)), Timestamp.from(T.plusSeconds(125)))
                        .query(String.class)
                        .list())
                .doesNotContain(key);
    }

    /** A connection as the application role with the tenant bound, in a transaction the test commits. */
    Connection appConnection(TestDatabase.Fixture t) throws SQLException {
        Connection c = DriverManager.getConnection(TestDatabase.url(), "bms_app", TestDatabase.APP_PASSWORD);
        c.setAutoCommit(false);
        try (PreparedStatement bind = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            bind.setString(1, t.tenantId().toString());
            bind.execute();
        }
        return c;
    }

    /** The first column of the first row, or null when there is none. */
    static String one(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                s.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void aWithdrawnRowIsNeverClaimedAgain() {
        TestDatabase.Fixture t = tenant("cancel-gone");
        String key = queue(t, "gone");
        assertThat(cancel(t, ref(key))).isEqualTo(1);
        List<String> claimed = TestDatabase.owner()
                .sql("SELECT idempotency_key FROM notification_outbox_claim(ARRAY['sms'], 3, ?, ?)")
                .params(Timestamp.from(T.plusSeconds(5)), Timestamp.from(T.plusSeconds(125)))
                .query(String.class)
                .list();
        assertThat(claimed).doesNotContain(key);
    }
}
