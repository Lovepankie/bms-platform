package com.rincoltech.bms.core.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.notifications.EmailAttachments;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.MovableClockConfig;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Subscription billing automation (issue #480, ADR-075, FR-SUB-08 to FR-SUB-12): the reminders on the right days and once
 * only, the move to overdue on the day after in Africa/Kampala and its audit row, the grace period the operator sets, the
 * list of businesses due for suspension (nothing is suspended), the numbered receipt of a payment emailed to the Owner, and
 * the Owner's page in each state. The clock is moved by the test; the run is called for the test's own businesses only,
 * since every test shares one database. Fabricated data and invented round figures only.
 */
@Import(MovableClockConfig.class)
@ResourceLock(MovableClockConfig.LOCK)
@ResourceLock(BillingAutomationIT.SETTINGS_LOCK)
class BillingAutomationIT extends IntegrationTest {

    /** Held by every suite that changes or depends on the one platform_billing_settings row. */
    static final String SETTINGS_LOCK = "platform-billing-settings";

    /** 09:00 on 1 Nov 2026 in Kampala (UTC+3). */
    static final Instant NOV_1_MORNING = Instant.parse("2026-11-01T06:00:00Z");

    @Autowired
    TestRestTemplate http;

    @Autowired
    MovableClock clock;

    @Autowired
    BillingService billing;

    @Autowired
    SubscriptionReceiptAttachments attachments;

    Api platform;
    Session operator;
    String plan;

    @BeforeEach
    void setUp() {
        clock.set(NOV_1_MORNING);
        platform = Api.platform(http);
        signInOperator();
        plan = pricedPlan();
    }

    /**
     * A fresh operator signed in at the moved clock: the second factor is enrolled with a code of the clock's own time, and
     * the session is valid at that time. Called again after the clock moves.
     */
    void signInOperator() {
        String email = Api.email("bill-op");
        UUID operatorId = Api.platformUser(email);
        ResponseEntity<JsonNode> first = platform.login(email, Api.PASSWORD);
        String mfaToken = first.getBody().get("mfa_token").asString();
        String secret = platform.post("/api/v1/platform/auth/mfa/enrol", Map.of("mfa_token", mfaToken), null)
                .getBody()
                .get("secret")
                .asString();
        ResponseEntity<JsonNode> confirmed = platform.post(
                "/api/v1/platform/auth/mfa/confirm",
                Map.of("mfa_token", mfaToken, "code", Api.totp(secret, clock.instant())),
                null);
        assertThat(confirmed.getStatusCode()).as("%s", confirmed.getBody()).isEqualTo(HttpStatus.OK);
        operator = platform.session(operatorId, confirmed, secret, List.of());
    }

    /** A retail plan priced at 10,000 a month from today, a year at 11 months: invented figures. */
    String pricedPlan() {
        String code = "bill-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Test Billing Plan");
        body.put("description", "Test plan");
        body.put("allowed_modules", List.of("retail", "lending"));
        body.put("active", true);
        body.put("sort_order", 5);
        body.put("reason", "Test: a plan for a test");
        ResponseEntity<JsonNode> made =
                platform.call(HttpMethod.PUT, "/api/v1/platform/plans/" + code, body, operator.accessToken());
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> priced = platform.post(
                "/api/v1/platform/plans/" + code + "/prices",
                Map.of("monthly_minor", 10000, "annual_months", 11, "reason", "Test price"),
                operator.accessToken());
        assertThat(priced.getStatusCode()).as("%s", priced.getBody()).isEqualTo(HttpStatus.OK);
        return code;
    }

    record Business(TestDatabase.Fixture fixture, UUID ownerId, String ownerEmail) {
        UUID id() {
            return fixture.tenantId();
        }
    }

    /** A business on the priced plan with an Owner and the subscription given. */
    Business business(String status, LocalDate trialEndsOn, LocalDate paidUntil, boolean pilot) {
        TestDatabase.Fixture t = TestDatabase.tenant("bill", false, true);
        String email = Api.email("bill-owner");
        UUID owner = Api.staff(t, email, new Api.Role("tenant_admin", null));
        var db = TestDatabase.owner();
        db.sql("UPDATE users SET is_owner = true WHERE id = ?").param(owner).update();
        db.sql("UPDATE tenants SET plan_id = (SELECT id FROM plans WHERE code = ?), status = ? WHERE id = ?")
                .params(
                        plan,
                        status.equals("suspended") || status.equals("cancelled") ? "suspended" : "active",
                        t.tenantId())
                .update();
        db.sql("""
                        INSERT INTO subscriptions (id, tenant_id, plan_id, status, trial_ends_on, current_period_start,
                                                   current_period_end, is_pilot)
                        SELECT ?, ?, p.id, ?, ?, ?, ?, ? FROM plans p WHERE p.code = ?
                        """)
                .params(
                        UUID.randomUUID(),
                        t.tenantId(),
                        status,
                        trialEndsOn,
                        paidUntil == null ? null : paidUntil.minusMonths(1).plusDays(1),
                        paidUntil,
                        pilot,
                        plan)
                .update();
        return new Business(t, owner, email);
    }

    BillingService.RunResult run(Business... businesses) {
        Set<UUID> only = new java.util.HashSet<>();
        for (Business b : businesses) {
            only.add(b.id());
        }
        return billing.run(only);
    }

    List<String> reminders(Business b) {
        return TestDatabase.owner()
                .sql("SELECT kind FROM subscription_reminders WHERE tenant_id = ? ORDER BY created_at, kind")
                .param(b.id())
                .query(String.class)
                .list();
    }

    long emails(Business b, String template) {
        return TestDatabase.owner()
                .sql("""
                        SELECT count(*) FROM notification_outbox
                         WHERE tenant_id = ? AND template_key = ? AND channel = 'email' AND recipient = ?
                        """)
                .params(b.id(), template, b.ownerEmail())
                .query(Long.class)
                .single();
    }

    String status(Business b) {
        return TestDatabase.owner()
                .sql("SELECT status FROM subscriptions WHERE tenant_id = ?")
                .param(b.id())
                .query(String.class)
                .single();
    }

    long audits(String action, UUID tenantId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM platform_audit_log WHERE action = ? AND tenant_id IS NOT DISTINCT FROM ?")
                .params(action, tenantId)
                .query(Long.class)
                .single();
    }

    String token(Business b) {
        return Api.tenant(http, b.fixture().slug())
                .signIn(b.ownerId(), b.ownerEmail(), Api.PASSWORD, null)
                .accessToken();
    }

    /** FR-SUB-08: 7 days before, the day before and on the day, each once, and only from 08:00 in Kampala. */
    @Test
    void frSub08_remindersGoOnTheRightDaysOnceOnlyAndFromEightInTheMorning() {
        Business b = business("active", null, LocalDate.of(2026, 11, 8), false);

        assertThat(run(b).remindersQueued()).isEqualTo(1);
        assertThat(reminders(b)).containsExactly("due_in_7");
        assertThat(emails(b, "billing.due_in_7")).isEqualTo(1);
        assertThat(run(b).remindersQueued()).isZero();
        assertThat(emails(b, "billing.due_in_7")).isEqualTo(1);

        clock.set(Instant.parse("2026-11-02T06:00:00Z"));
        run(b);
        assertThat(reminders(b)).containsExactly("due_in_7");

        clock.set(Instant.parse("2026-11-07T04:59:00Z"));
        run(b);
        assertThat(reminders(b)).as("07:59 in Kampala is too early").containsExactly("due_in_7");
        clock.set(Instant.parse("2026-11-07T05:00:00Z"));
        run(b);
        run(b);
        assertThat(reminders(b)).containsExactly("due_in_7", "due_in_1");

        clock.set(Instant.parse("2026-11-08T06:00:00Z"));
        run(b);
        assertThat(reminders(b)).containsExactly("due_in_7", "due_in_1", "due_today");
        assertThat(emails(b, "billing.due_in_1")).isEqualTo(1);
        assertThat(emails(b, "billing.due_today")).isEqualTo(1);
        assertThat(status(b)).isEqualTo("active");
        Map<String, Object> row = TestDatabase.owner()
                .sql("SELECT amount_minor, currency, channels::text AS channels FROM subscription_reminders"
                        + " WHERE tenant_id = ? AND kind = 'due_today'")
                .param(b.id())
                .query()
                .singleRow();
        assertThat(row.get("amount_minor")).isEqualTo(10000L);
        assertThat(row.get("currency").toString()).isEqualTo("UGX");
        assertThat(row.get("channels"))
                .as("SMS is off unless the platform switches it on")
                .isEqualTo("{email}");
        assertThat(TestDatabase.owner()
                        .sql("SELECT params ->> 'amount_line' FROM notification_outbox"
                                + " WHERE tenant_id = ? AND template_key = 'billing.due_today' AND channel = 'email'")
                        .param(b.id())
                        .query(String.class)
                        .single())
                .as("the price a month and a year, for a business paying either way")
                .isEqualTo("The price is UGX 10,000 a month, or UGX 110,000 for a year.");
    }

    /** FR-SUB-08: a free month gets its own reminders; a pilot, a suspended and an overdue business get none. */
    @Test
    void frSub08_aFreeMonthGetsItsOwnRemindersAndNobodyElseGetsAny() {
        Business trial = business("trial", LocalDate.of(2026, 11, 8), null, false);
        Business pilot = business("active", null, LocalDate.of(2026, 11, 8), true);
        Business suspended = business("suspended", null, LocalDate.of(2026, 11, 8), false);
        Business late = business("past_due", null, LocalDate.of(2026, 10, 20), false);

        run(trial, pilot, suspended, late);

        assertThat(reminders(trial)).containsExactly("trial_in_7");
        assertThat(emails(trial, "billing.trial_in_7")).isEqualTo(1);
        assertThat(reminders(pilot)).isEmpty();
        assertThat(reminders(suspended)).isEmpty();
        assertThat(reminders(late)).isEmpty();
        assertThat(status(late)).isEqualTo("past_due");
        assertThat(status(suspended)).isEqualTo("suspended");

        // A pilot whose date has passed is never made overdue.
        clock.set(Instant.parse("2026-11-20T06:00:00Z"));
        run(trial, pilot, suspended);
        assertThat(status(pilot)).isEqualTo("active");
        assertThat(status(suspended)).isEqualTo("suspended");
        assertThat(status(trial))
                .as("a free month that ended unpaid is overdue")
                .isEqualTo("past_due");
    }

    /** FR-SUB-09: overdue the day after the last day paid for, at Kampala's midnight, audited once; nothing suspended. */
    @Test
    void frSub09_overdueOnTheDayAfterInKampalaAuditedAndStillWorking() {
        Business b = business("active", null, LocalDate.of(2026, 11, 8), false);

        clock.set(Instant.parse("2026-11-08T20:59:00Z"));
        assertThat(run(b).markedOverdue()).isZero();
        assertThat(status(b)).as("23:59 on the 8th in Kampala").isEqualTo("active");

        clock.set(Instant.parse("2026-11-08T21:01:00Z"));
        assertThat(run(b).markedOverdue()).isEqualTo(1);
        assertThat(status(b))
                .as("00:01 on the 9th in Kampala, still the 8th in UTC")
                .isEqualTo("past_due");
        assertThat(run(b).markedOverdue()).isZero();
        assertThat(audits("platform.tenant.subscription_overdue", b.id())).isEqualTo(1);
        Map<String, Object> audit =
                TestDatabase.owner().sql("""
                        SELECT platform_user_id, data ->> 'before' AS before, data ->> 'due_on' AS due_on,
                               data ->> 'pay_by' AS pay_by
                          FROM platform_audit_log WHERE action = 'platform.tenant.subscription_overdue' AND tenant_id = ?
                        """).param(b.id()).query().singleRow();
        assertThat(audit.get("platform_user_id")).isNull();
        assertThat(audit.get("before")).isEqualTo("active");
        assertThat(audit.get("due_on")).isEqualTo("2026-11-08");
        assertThat(audit.get("pay_by")).isEqualTo("2026-11-15");
        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM tenants WHERE id = ?")
                        .param(b.id())
                        .query(String.class)
                        .single())
                .as("overdue changes nothing the business can do")
                .isEqualTo("active");

        // Much later: still past_due, never suspended by the run.
        clock.set(Instant.parse("2026-12-31T06:00:00Z"));
        run(b);
        assertThat(status(b)).isEqualTo("past_due");
    }

    /** FR-SUB-09: a subscription with a status change the operator scheduled is never made overdue, and keeps its date. */
    @Test
    void frSub09_aScheduledChangeIsNeverOverwrittenByTheOverdueMove() {
        Business b = business("active", null, LocalDate.of(2026, 11, 8), false);
        TestDatabase.owner()
                .sql("UPDATE subscriptions SET next_status_change_on = ? WHERE tenant_id = ?")
                .params(LocalDate.of(2026, 11, 8), b.id())
                .update();

        clock.set(Instant.parse("2026-11-10T06:00:00Z"));
        assertThat(run(b).markedOverdue()).isZero();
        assertThat(status(b)).isEqualTo("active");
        assertThat(TestDatabase.owner()
                        .sql("SELECT next_status_change_on FROM subscriptions WHERE tenant_id = ?")
                        .param(b.id())
                        .query(LocalDate.class)
                        .single())
                .isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(audits("platform.tenant.subscription_overdue", b.id())).isZero();
    }

    /**
     * FR-SUB-08, FR-SUB-11: a deactivated Owner gets no reminder and no receipt. V79's {@code users_owner_is_staff} forbids
     * a deactivated Owner in {@code users}, so the shared table is never altered: the run is checked end to end with the
     * person who left deactivated and no longer the Owner, and the Owner query itself against a copy of the business's users
     * in a schema of the test's own, where the Owner is deactivated.
     * JUSTIFICATION-A3: two new test cases for the review; the anchor is only the insertion point.
     */
    @Test
    void frSub08_aDeactivatedOwnerGetsNoReminderAndNoReceipt() {
        Business b = business("active", null, LocalDate.of(2026, 11, 8), false);
        TestDatabase.owner()
                .sql("UPDATE users SET is_owner = false, status = 'deactivated' WHERE id = ?")
                .param(b.ownerId())
                .update();

        assertThat(run(b).remindersQueued()).isZero();
        assertThat(reminders(b)).containsExactly("due_in_7");
        assertThat(TestDatabase.owner()
                        .sql("SELECT channels::text FROM subscription_reminders WHERE tenant_id = ?")
                        .param(b.id())
                        .query(String.class)
                        .single())
                .isEqualTo("{}");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", 10000);
        body.put("method", "mtn_momo");
        body.put("reference", "TEST-REF-480-D");
        body.put("paid_on", "2026-11-01");
        body.put("months", 1);
        body.put("reason", "Test: seen on the statement");
        ResponseEntity<JsonNode> paid = platform.call(
                HttpMethod.POST,
                "/api/v1/platform/tenants/" + b.id() + "/payments",
                body,
                operator.accessToken(),
                Map.of("Idempotency-Key", "pay-" + UUID.randomUUID()));
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM subscription_receipts WHERE tenant_id = ?")
                        .param(b.id())
                        .query(Long.class)
                        .single())
                .as("the receipt is still numbered, on the page only")
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM notification_outbox WHERE tenant_id = ? AND template_key LIKE 'billing.%'")
                        .param(b.id())
                        .query(Long.class)
                        .single())
                .isZero();

        String schema = "billing_it_" + UUID.randomUUID().toString().replace("-", "");
        var ds = TestDatabase.ownerDataSource();
        var db = JdbcClient.create(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        try {
            db.sql("CREATE SCHEMA " + schema).update();
            db.sql("CREATE TABLE " + schema + ".users AS SELECT * FROM public.users WHERE tenant_id = ?")
                    .param(b.id())
                    .update();
            db.sql("UPDATE " + schema + ".users SET is_owner = true WHERE id = ?")
                    .param(b.ownerId())
                    .update();
            List<String> deactivated = tx.execute(st -> {
                db.sql("SET LOCAL search_path = " + schema).update();
                return db.sql(BillingService.OWNER_SELECT)
                        .query((rs, n) -> rs.getString("email"))
                        .list();
            });
            assertThat(deactivated)
                    .as("a deactivated Owner is never written to")
                    .isEmpty();
            db.sql("UPDATE " + schema + ".users SET status = 'invited' WHERE id = ?")
                    .param(b.ownerId())
                    .update();
            List<String> invited = tx.execute(st -> {
                db.sql("SET LOCAL search_path = " + schema).update();
                return db.sql(BillingService.OWNER_SELECT)
                        .query((rs, n) -> rs.getString("email"))
                        .list();
            });
            assertThat(invited).as("an invited Owner is").containsExactly(b.ownerEmail());
        } finally {
            db.sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE").update();
        }
    }

    /** FR-SUB-08: a subscription with an end the operator scheduled gets no "pay to keep using" reminder. */
    @Test
    void frSub08_aScheduledEndGetsNoReminder() {
        Business paid = business("active", null, LocalDate.of(2026, 11, 8), false);
        Business trial = business("trial", LocalDate.of(2026, 11, 8), null, false);
        TestDatabase.owner()
                .sql("UPDATE subscriptions SET next_status_change_on = ? WHERE tenant_id IN (?, ?)")
                .params(LocalDate.of(2026, 11, 8), paid.id(), trial.id())
                .update();

        for (String at : List.of("2026-11-01T06:00:00Z", "2026-11-07T06:00:00Z", "2026-11-08T06:00:00Z")) {
            clock.set(Instant.parse(at));
            assertThat(run(paid, trial).remindersQueued()).as(at).isZero();
        }
        assertThat(reminders(paid)).isEmpty();
        assertThat(reminders(trial)).isEmpty();
    }

    /**
     * FR-SUB-08, FR-SUB-09: a business whose Owner cancelled (ADR-073, in force the day after the last day paid for) gets no
     * "pay to keep using" reminder and is never made overdue: nothing is owed after the end.
     * JUSTIFICATION-A3: a new test case; no existing case combines the Owner's cancellation with the billing run.
     */
    @Test
    void frSub08_anOwnersCancellationGetsNoReminderAndIsNeverMadeOverdue() {
        Business b = business("active", null, LocalDate.of(2026, 11, 8), false);
        TestDatabase.owner()
                .sql("""
                        INSERT INTO subscription_scheduled_changes (id, tenant_id, kind, effective_on, requested_by)
                        VALUES (?, ?, 'cancel', ?, ?)
                        """)
                .params(UUID.randomUUID(), b.id(), LocalDate.of(2026, 11, 9), b.ownerId())
                .update();

        for (String at : List.of("2026-11-01T06:00:00Z", "2026-11-07T06:00:00Z", "2026-11-08T06:00:00Z")) {
            clock.set(Instant.parse(at));
            assertThat(run(b).remindersQueued()).as(at).isZero();
        }
        assertThat(reminders(b)).isEmpty();
        assertThat(TestDatabase.owner()
                        .sql("SELECT platform_mark_subscription_overdue(?, ?, ?)")
                        .params(b.id(), LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 10))
                        .query(Boolean.class)
                        .single())
                .as("the database function refuses too, whoever calls it")
                .isFalse();
        clock.set(Instant.parse("2026-11-10T06:00:00Z"));
        assertThat(run(b).markedOverdue()).isZero();
        assertThat(status(b)).isEqualTo("active");
        assertThat(audits("platform.tenant.subscription_overdue", b.id())).isZero();
    }

    /**
     * FR-SUB-08, FR-SUB-09: a cancellation the Owner undid (ADR-073) counts for nothing: the business gets its reminders and
     * is made overdue the day after its last day, as any other.
     * JUSTIFICATION-A3: a new test case; no existing case covers an undone cancellation in the billing run.
     */
    @Test
    void frSub08_anUndoneCancellationIsBilledAsUsual() {
        Business b = business("active", null, LocalDate.of(2026, 11, 8), false);
        TestDatabase.owner()
                .sql("""
                        INSERT INTO subscription_scheduled_changes (id, tenant_id, kind, effective_on, status, requested_by,
                                                                    undone_at, undone_by)
                        VALUES (?, ?, 'cancel', ?, 'undone', ?, now(), ?)
                        """)
                .params(UUID.randomUUID(), b.id(), LocalDate.of(2026, 11, 9), b.ownerId(), b.ownerId())
                .update();

        for (String at : List.of("2026-11-01T06:00:00Z", "2026-11-07T06:00:00Z", "2026-11-08T06:00:00Z")) {
            clock.set(Instant.parse(at));
            assertThat(run(b).remindersQueued()).as(at).isEqualTo(1);
        }
        assertThat(reminders(b)).containsExactly("due_in_7", "due_in_1", "due_today");
        for (String kind : List.of("due_in_7", "due_in_1", "due_today")) {
            assertThat(emails(b, "billing." + kind)).as(kind).isEqualTo(1);
        }
        clock.set(Instant.parse("2026-11-10T06:00:00Z"));
        assertThat(run(b).markedOverdue()).isEqualTo(1);
        assertThat(status(b)).isEqualTo("past_due");
        assertThat(audits("platform.tenant.subscription_overdue", b.id())).isEqualTo(1);
    }

    List<String> listed() {
        ResponseEntity<JsonNode> list =
                platform.get("/api/v1/platform/billing/due-for-suspension", operator.accessToken());
        assertThat(list.getStatusCode()).as("%s", list.getBody()).isEqualTo(HttpStatus.OK);
        List<String> ids = new ArrayList<>();
        list.getBody().get("items").forEach(i -> ids.add(i.get("tenant_id").asString()));
        return ids;
    }

    ResponseEntity<JsonNode> saveGrace(int days) {
        return platform.call(
                HttpMethod.PUT,
                "/api/v1/platform/billing/settings",
                Map.of("grace_days", days, "how_to_pay", "Test: pay to the test account", "reason", "Test: grace"),
                operator.accessToken());
    }

    /** FR-SUB-09, FR-SUB-10: the grace period is the operator's setting, and only businesses past it are listed. */
    @Test
    void frSub10_theListHoldsOnlyBusinessesPastTheGracePeriodAndTheOperatorSetsItsLength() {
        clock.set(Instant.parse("2026-11-20T06:00:00Z"));
        Business past = business("past_due", null, LocalDate.of(2026, 11, 12), false);
        Business inGrace = business("past_due", null, LocalDate.of(2026, 11, 14), false);
        Business pilot = business("past_due", null, LocalDate.of(2026, 11, 1), true);
        Business suspended = business("suspended", null, LocalDate.of(2026, 11, 1), false);
        Business ended = business("past_due", null, LocalDate.of(2026, 11, 1), false);
        Business scheduled = business("past_due", null, LocalDate.of(2026, 11, 1), false);
        TestDatabase.owner()
                .sql("UPDATE subscriptions SET next_status_change_on = ? WHERE tenant_id = ?")
                .params(LocalDate.of(2026, 11, 30), scheduled.id())
                .update();
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .param(ended.id())
                .update();
        String staffToken = token(inGrace);
        signInOperator();
        Map<String, Object> before = TestDatabase.owner()
                .sql("SELECT grace_days, how_to_pay FROM platform_billing_settings WHERE id = 1")
                .query()
                .singleRow();
        try {
            assertThat(saveGrace(7).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(listed())
                    .contains(past.id().toString())
                    .doesNotContain(
                            inGrace.id().toString(),
                            pilot.id().toString(),
                            suspended.id().toString(),
                            ended.id().toString(),
                            scheduled.id().toString());
            JsonNode item = null;
            for (JsonNode i : platform.get("/api/v1/platform/billing/due-for-suspension", operator.accessToken())
                    .getBody()
                    .get("items")) {
                if (i.get("tenant_id").asString().equals(past.id().toString())) {
                    item = i;
                }
            }
            assertThat(item.get("days_overdue").asLong()).isEqualTo(8);
            assertThat(item.get("amount_due_minor").asLong()).isEqualTo(10000);
            assertThat(item.get("grace_ended_on").asString()).isEqualTo("2026-11-19");
            assertThat(status(past)).as("listed, never suspended").isEqualTo("past_due");

            ResponseEntity<JsonNode> longer = saveGrace(10);
            assertThat(longer.getStatusCode()).as("%s", longer.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(longer.getBody().get("grace_days").asInt()).isEqualTo(10);
            assertThat(audits("platform.billing.settings_changed", null)).isPositive();
            assertThat(listed()).doesNotContain(past.id().toString());
            JsonNode page = Api.tenant(http, past.fixture().slug())
                    .get("/api/v1/billing", token(past))
                    .getBody();
            assertThat(page.get("state").asString()).isEqualTo("in_grace");
            assertThat(page.get("overdue_since").asString()).isEqualTo("2026-11-13");
            assertThat(page.get("pay_by").asString()).isEqualTo("2026-11-22");
            assertThat(page.get("how_to_pay").asString()).isEqualTo("Test: pay to the test account");
            ResponseEntity<JsonNode> graceOnly = platform.call(
                    HttpMethod.PUT,
                    "/api/v1/platform/billing/settings",
                    Map.of("grace_days", 9, "reason", "Test: grace only"),
                    operator.accessToken());
            assertThat(graceOnly.getStatusCode()).as("%s", graceOnly.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(graceOnly.getBody().get("how_to_pay").asString())
                    .as("left out, kept as it is")
                    .isEqualTo("Test: pay to the test account");
            saveGrace(10);

            JsonNode notice = Api.tenant(http, inGrace.fixture().slug())
                    .get("/api/v1/billing/notice", staffToken)
                    .getBody();
            assertThat(notice.get("state").asString()).isEqualTo("in_grace");
            assertThat(notice.get("pay_by").asString()).isEqualTo("2026-11-24");

            assertThat(saveGrace(61).getStatusCode().value()).isEqualTo(422);
            assertThat(platform.get("/api/v1/platform/billing/due-for-suspension", staffToken)
                            .getStatusCode())
                    .as("not for a business's staff")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        } finally {
            Object howToPay = before.get("how_to_pay");
            ResponseEntity<JsonNode> restored = platform.call(
                    HttpMethod.PUT,
                    "/api/v1/platform/billing/settings",
                    Map.of(
                            "grace_days",
                            before.get("grace_days"),
                            "how_to_pay",
                            howToPay == null ? "" : howToPay,
                            "reason",
                            "Test: restore"),
                    operator.accessToken());
            assertThat(restored.getStatusCode()).as("%s", restored.getBody()).isEqualTo(HttpStatus.OK);
        }
    }

    /** FR-SUB-11: a payment gets a numbered receipt in its own transaction, emailed to the Owner with the PDF attached. */
    @Test
    void frSub11_aPaymentGetsANumberedReceiptEmailedToTheOwner() {
        Business b = business("trial", LocalDate.of(2026, 11, 10), null, false);
        Business other = business("active", null, LocalDate.of(2026, 12, 31), false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", 10000);
        body.put("method", "mtn_momo");
        body.put("reference", "TEST-REF-480");
        body.put("paid_on", "2026-11-01");
        body.put("months", 1);
        body.put("reason", "Test: seen on the statement");
        String key = "pay-" + UUID.randomUUID();
        Map<String, String> headers = Map.of("Idempotency-Key", key);
        String path = "/api/v1/platform/tenants/" + b.id() + "/payments";
        ResponseEntity<JsonNode> paid = platform.call(HttpMethod.POST, path, body, operator.accessToken(), headers);
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID paymentId = UUID.fromString(paid.getBody().get("id").asString());
        platform.call(HttpMethod.POST, path, body, operator.accessToken(), headers);

        List<String> numbers = TestDatabase.owner()
                .sql("SELECT receipt_no FROM subscription_receipts WHERE tenant_id = ?")
                .param(b.id())
                .query(String.class)
                .list();
        assertThat(numbers).hasSize(1);
        assertThat(numbers.getFirst()).matches("RT-2026-\\d{6,}");
        assertThat(emails(b, "billing.receipt")).isEqualTo(1);

        Optional<EmailAttachments.Attachment> file = attachments.read(b.id(), paymentId.toString());
        assertThat(file).isPresent();
        assertThat(file.get().contentType()).isEqualTo("application/pdf");
        assertThat(file.get().fileName()).isEqualTo("receipt-" + numbers.getFirst() + ".pdf");
        assertThat(new String(file.get().bytes(), 0, 4, StandardCharsets.US_ASCII))
                .isEqualTo("%PDF");
        sample("subscription-receipt", file.get().bytes());
        assertThat(attachments.read(other.id(), paymentId.toString()))
                .as("another business's receipt")
                .isEmpty();

        ResponseEntity<byte[]> pdf = download(b, paymentId);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(download(other, paymentId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode page = Api.tenant(http, b.fixture().slug())
                .get("/api/v1/billing", token(b))
                .getBody();
        assertThat(page.get("state").asString()).isEqualTo("active");
        assertThat(page.get("payments")).hasSize(1);
        assertThat(page.get("payments").get(0).get("receipt_no").asString()).isEqualTo(numbers.getFirst());
        assertThat(Api.tenant(http, other.fixture().slug())
                        .get("/api/v1/billing", token(other))
                        .getBody()
                        .get("payments"))
                .isEmpty();
    }

    /** With {@code -Dbms.documents.samples}: the PDF and a PNG of each page, for the visual check (ADR-034 item 8). */
    static void sample(String name, byte[] pdf) {
        if (System.getProperty("bms.documents.samples") == null) {
            return;
        }
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("target/document-samples");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(name + ".pdf"), pdf);
            try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) {
                org.apache.pdfbox.rendering.PDFRenderer r = new org.apache.pdfbox.rendering.PDFRenderer(d);
                for (int i = 0; i < d.getNumberOfPages(); i++) {
                    javax.imageio.ImageIO.write(
                            r.renderImageWithDPI(i, 110),
                            "png",
                            dir.resolve(name + "-p" + (i + 1) + ".png").toFile());
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    ResponseEntity<byte[]> download(Business b, UUID paymentId) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", b.fixture().slug());
        h.setBearerAuth(token(b));
        return http.exchange(
                "/api/v1/billing/payments/" + paymentId + "/receipt",
                HttpMethod.GET,
                new HttpEntity<>(null, h),
                byte[].class);
    }

    /** FR-SUB-12: the Owner's page names each state; staff without the permission are refused, but see the banner. */
    @Test
    void frSub12_theOwnersPageNamesEachState() {
        clock.set(Instant.parse("2026-11-20T06:00:00Z"));
        Map<String, Business> byState = new LinkedHashMap<>();
        byState.put("trial", business("trial", LocalDate.of(2026, 11, 30), null, false));
        byState.put("active", business("active", null, LocalDate.of(2026, 12, 31), false));
        byState.put("in_grace", business("past_due", null, LocalDate.of(2026, 11, 18), false));
        byState.put("overdue", business("past_due", null, LocalDate.of(2026, 11, 1), false));
        byState.put("pilot", business("active", null, null, true));
        Business suspended = business("active", null, LocalDate.of(2026, 11, 1), false);
        String suspendedToken = token(suspended);
        TestDatabase.owner()
                .sql("UPDATE subscriptions SET status = 'suspended', suspended_from = 'past_due' WHERE tenant_id = ?")
                .param(suspended.id())
                .update();
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .param(suspended.id())
                .update();

        for (Map.Entry<String, Business> e : byState.entrySet()) {
            ResponseEntity<JsonNode> page =
                    Api.tenant(http, e.getValue().fixture().slug()).get("/api/v1/billing", token(e.getValue()));
            assertThat(page.getStatusCode()).as("%s", page.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(page.getBody().get("state").asString()).as(e.getKey()).isEqualTo(e.getKey());
            assertThat(page.getBody().get("monthly_minor").asLong()).isEqualTo(10000);
            assertThat(page.getBody().get("grace_days").asInt()).isEqualTo(7);
        }
        ResponseEntity<JsonNode> suspendedPage =
                Api.tenant(http, suspended.fixture().slug()).get("/api/v1/billing", suspendedToken);
        assertThat(suspendedPage.getStatusCode())
                .as("%s", suspendedPage.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(suspendedPage.getBody().get("state").asString()).isEqualTo("suspended");

        Business overdue = byState.get("overdue");
        String clerkEmail = Api.email("bill-clerk");
        UUID clerk = Api.staff(
                overdue.fixture(),
                clerkEmail,
                new Api.Role("cashier", overdue.fixture().headOffice()));
        String clerkToken = Api.tenant(http, overdue.fixture().slug())
                .signIn(clerk, clerkEmail, Api.PASSWORD, null)
                .accessToken();
        assertThat(Api.tenant(http, overdue.fixture().slug())
                        .get("/api/v1/billing", clerkToken)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode notice = Api.tenant(http, overdue.fixture().slug())
                .get("/api/v1/billing/notice", clerkToken)
                .getBody();
        assertThat(notice.get("state").asString()).isEqualTo("overdue");
        assertThat(notice.get("overdue_since").asString()).isEqualTo("2026-11-02");
        assertThat(Api.tenant(http, byState.get("active").fixture().slug())
                        .get("/api/v1/billing/notice", token(byState.get("active")))
                        .getBody()
                        .get("state")
                        .asString())
                .isEqualTo("none");
    }

    /**
     * FR-SUB-13 (ADR-090): the Owner names a billing contact; reminders and receipts go to it instead of the Owner, the Owner's
     * own sign-in contact does not change, only the Owner may set it, and removing it sends billing to the Owner again.
     */
    @Test
    void frSub13_remindersAndReceiptsGoToTheBillingContactAndSignInIsUntouched() {
        Business b = business("active", null, LocalDate.of(2026, 11, 8), false);
        UUID admin = Api.staff(b.fixture(), Api.email("bill-admin"), new Api.Role("tenant_admin", null));
        Api api = Api.tenant(http, b.fixture().slug());
        String adminToken = api.signIn(
                        admin,
                        TestDatabase.owner()
                                .sql("SELECT email FROM users WHERE id = ?")
                                .param(admin)
                                .query(String.class)
                                .single(),
                        Api.PASSWORD,
                        null)
                .accessToken();
        String ownerToken = token(b);
        String accountant = Api.email("bill-acct");
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("full_name", "Test Accountant");
        contact.put("email", accountant);
        contact.put("phone", "0700 000 009");

        ResponseEntity<JsonNode> refused = api.call(HttpMethod.PUT, "/api/v1/billing/contact", contact, adminToken);
        assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("owner_only");
        assertThat(api.get("/api/v1/billing", adminToken)
                        .getBody()
                        .get("can_change_contact")
                        .asBoolean())
                .isFalse();
        Map<String, Object> badPhone = new LinkedHashMap<>(contact);
        badPhone.put("phone", "12345");
        assertThat(api.call(HttpMethod.PUT, "/api/v1/billing/contact", badPhone, ownerToken)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> set = api.call(HttpMethod.PUT, "/api/v1/billing/contact", contact, ownerToken);
        assertThat(set.getStatusCode()).as("%s", set.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(set.getBody().get("billing_contact").get("email").asString()).isEqualTo(accountant);
        assertThat(set.getBody().get("billing_contact").get("phone").asString()).isEqualTo("+256700000009");
        assertThat(set.getBody().get("bills_go_to").asString()).isEqualTo("Test Accountant");
        assertThat(set.getBody().get("can_change_contact").asBoolean()).isTrue();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.billing.contact_changed'")
                        .param(b.id())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        run(b);
        assertThat(reminders(b)).containsExactly("due_in_7");
        assertThat(emails(b, "billing.due_in_7")).as("not the Owner").isZero();
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT count(*) FROM notification_outbox
                                 WHERE tenant_id = ? AND template_key = 'billing.due_in_7' AND recipient = ?
                                   AND params ->> 'owner_name' = 'Test Accountant'
                                """)
                        .params(b.id(), accountant)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", 10000);
        body.put("method", "mtn_momo");
        body.put("reference", "TEST-REF-571");
        body.put("paid_on", "2026-11-01");
        body.put("months", 1);
        body.put("reason", "Test: seen on the statement");
        ResponseEntity<JsonNode> paid = platform.call(
                HttpMethod.POST,
                "/api/v1/platform/tenants/" + b.id() + "/payments",
                body,
                operator.accessToken(),
                Map.of("Idempotency-Key", "pay-" + UUID.randomUUID()));
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(emails(b, "billing.receipt")).isZero();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM notification_outbox WHERE tenant_id = ? AND template_key = 'billing.receipt'"
                                        + " AND recipient = ?")
                        .params(b.id(), accountant)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        Map<String, Object> owner = TestDatabase.owner()
                .sql("SELECT email, contact_changed_at FROM users WHERE id = ?")
                .param(b.ownerId())
                .query()
                .singleRow();
        assertThat(owner.get("email")).as("the Owner's sign-in email").isEqualTo(b.ownerEmail());
        assertThat(owner.get("contact_changed_at")).isNull();

        ResponseEntity<JsonNode> cleared = api.call(HttpMethod.DELETE, "/api/v1/billing/contact", null, ownerToken);
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody().get("billing_contact").isNull()).isTrue();
        assertThat(cleared.getBody().get("bills_go_to").asString())
                .isEqualTo(TestDatabase.owner()
                        .sql("SELECT full_name FROM users WHERE id = ?")
                        .param(b.ownerId())
                        .query(String.class)
                        .single());
    }
}
