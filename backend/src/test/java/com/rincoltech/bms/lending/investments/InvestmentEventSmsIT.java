package com.rincoltech.bms.lending.investments;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.MovableClockConfig;
import com.rincoltech.bms.testsupport.SmsTenants;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The investment SMS events of issue #221 (FR-NTF-02, ADR-036 third amendment) through the staff routes and the nightly job:
 * funding, the reminder that an investment matures, and every payout (a return, the maturity payout, an early withdrawal)
 * each queue one message for the investor's own phone in the outbox, once; a reversal, the seed's port, a replay of past
 * days and a tenant without SMS queue nothing. All figures and numbers fabricated.
 */
@Import(MovableClockConfig.class)
@ResourceLock(MovableClockConfig.LOCK)
class InvestmentEventSmsIT extends LoanFixtures {

    static final String INV = "/api/v1/lending/investments";
    static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);

    @Autowired
    BusinessClock clock;

    @Autowired
    MovableClock movable;

    @Autowired
    TenantJobs tenantJobs;

    @Autowired
    InvestmentServicing servicing;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID cashier = UUID.randomUUID();
    final UUID manager = UUID.randomUUID();
    final UUID accountant = UUID.randomUUID();
    String cashierPerms;
    String accountantPerms;
    int members;
    String investorPhone;

    @BeforeEach
    void users() {
        cashierPerms = permissionsOf("cashier");
        accountantPerms = permissionsOf("accountant");
        // One minute before Kampala's midnight on 2026-10-15: the business date is the 15th.
        movable.set(MovableClockConfig.BEFORE_MIDNIGHT);
    }

    LocalDate today() {
        return clock.today(BusinessClock.DEFAULT_ZONE);
    }

    // ---- Fixtures -------------------------------------------------------------------------------------

    void smsOn() {
        SmsTenants.operator(t.tenantId(), true, 1000);
        SmsTenants.enable(t.tenantId());
    }

    String product(String code, Map<String, Object> overrides) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("product_type", "fixed_term");
        terms.put("allowed_terms_months", List.of(3, 6, 12));
        terms.put("return_rate_bp", 1200);
        terms.put("return_method", "flat");
        terms.put("payout_frequency", "monthly");
        terms.put("min_amount_minor", 100_000);
        terms.put("max_amount_minor", 50_000_000);
        terms.put("early_withdrawal_allowed", false);
        terms.putAll(overrides);
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                "/api/v1/lending/investment-products",
                as(UUID.randomUUID(), adminPerms, "*", null),
                Map.of("code", code, "name", "Test " + code, "terms", terms));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    String open(String product, long amount, int term) {
        members++;
        investorPhone = "07000002%02d".formatted(members);
        String member = member("Test Investor %02d".formatted(members), investorPhone, t.headOffice(), true);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("member_id", member);
        body.put("product_id", product);
        body.put("amount_minor", amount);
        body.put("term_months", term);
        ResponseEntity<JsonNode> r = send(HttpMethod.POST, INV, as(officer, officerPerms, "*", null), body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    static String key() {
        return "test-" + UUID.randomUUID();
    }

    /** The cashier; with {@code notify_member: false} a business administrator's standing is added (only that may say it). */
    HttpHeaders cashierFor(Boolean notify) {
        return as(
                cashier,
                Boolean.FALSE.equals(notify) ? cashierPerms + ",core.settings.manage" : cashierPerms,
                "*",
                null);
    }

    HttpHeaders withKey(HttpHeaders h, String key) {
        h.add("Idempotency-Key", key);
        return h;
    }

    ResponseEntity<JsonNode> requestFunding(String id, LocalDate date, String key) {
        return requestFunding(id, date, key, null);
    }

    ResponseEntity<JsonNode> requestFunding(String id, LocalDate date, String key, Boolean notify) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value_date", date.toString());
        body.put("payment_method_key", "cash");
        if (notify != null) {
            body.put("notify_member", notify);
        }
        return send(HttpMethod.POST, INV + "/" + id + "/funding", withKey(cashierFor(notify), key), body);
    }

    ResponseEntity<JsonNode> decide(String approvalId, UUID user, String perms) {
        return send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approvalId + "/approve",
                as(user, perms, "*", null),
                Map.of("note", "Checked"));
    }

    void fund(String id, LocalDate date) {
        ResponseEntity<JsonNode> r = requestFunding(id, date, key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(decide(r.getBody().get("approval_request_id").asString(), manager, managerPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    int runDaily(LocalDate date) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tenantJobs
                .callAsTenant(t.slug(), "lending", () -> tx.execute(s -> servicing.runDaily(date)))
                .reminders();
    }

    ResponseEntity<JsonNode> pay(String id, String route, String key) {
        return send(
                HttpMethod.POST,
                INV + "/" + id + "/" + route,
                withKey(as(cashier, cashierPerms, "*", null), key),
                Map.of("payment_method_key", "cash"));
    }

    JsonNode inv(String id) {
        return asOfficer(HttpMethod.GET, INV + "/" + id, null, null).getBody();
    }

    List<Map<String, Object>> rows(TestDatabase.Fixture tenant) {
        return TestDatabase.owner()
                .sql("SELECT idempotency_key, recipient, template_key, category, subject_type, subject_id,"
                        + " params::text AS params, created_at, next_attempt_at, expires_at FROM notification_outbox"
                        + " WHERE channel = 'sms' AND tenant_id = ? ORDER BY created_at, template_key")
                .param(tenant.tenantId())
                .query()
                .listOfRows();
    }

    List<String> templates() {
        return rows(t).stream().map(r -> (String) r.get("template_key")).toList();
    }

    Map<String, Object> only(String template) {
        List<Map<String, Object>> match = rows(t).stream()
                .filter(r -> template.equals(r.get("template_key")))
                .toList();
        assertThat(match).as(template).hasSize(1);
        return match.getFirst();
    }

    JsonNode params(Map<String, Object> row) {
        return JsonMapper.builder().build().readTree((String) row.get("params"));
    }

    static Instant at(Object timestamp) {
        return timestamp instanceof Timestamp ts ? ts.toInstant() : ((OffsetDateTime) timestamp).toInstant();
    }

    // ---- Funding -----------------------------------------------------------------------------------------

    @Test
    void frNtf02_aFundingQueuesOneMessageForTheInvestorAndASecondRequestWithTheSameKeyQueuesNone() {
        smsOn();
        String id = open(product("SMSF1", Map.of()), 1_000_000, 6);
        assertThat(rows(t)).isEmpty();
        String k = key();

        // Below the threshold it executes at once; above it waits for a checker (the default here): fund with a
        // checker.
        ResponseEntity<JsonNode> r = requestFunding(id, today(), k);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(rows(t)).as("nothing before the checker approves").isEmpty();
        assertThat(decide(r.getBody().get("approval_request_id").asString(), manager, managerPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> replay = requestFunding(id, today(), k);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");

        Map<String, Object> row = only("investment.funded");
        assertThat(rows(t)).hasSize(1);
        JsonNode p = params(row);
        JsonNode funded = inv(id);
        assertThat(row.get("recipient")).isEqualTo("+256" + investorPhone.substring(1));
        assertThat(row.get("category")).isEqualTo("transactional");
        assertThat(p.get("account_no").asString())
                .isEqualTo(funded.get("account_no").asString());
        assertThat(p.get("amount").asString()).isEqualTo("1,000,000");
        assertThat(p.get("currency").asString()).isEqualTo("UGX");
        assertThat(p.get("date").asString()).isEqualTo(today().format(DMY));
        assertThat(p.get("maturity_date").asString())
                .isEqualTo(
                        LocalDate.parse(funded.get("maturity_date").asString()).format(DMY));
        assertThat(p.get("receipt_no").asString()).startsWith("RC-");
        assertThat((String) row.get("params")).doesNotContain("Test Investor").doesNotContain("700000");
        // Queued at 20:59 UTC, 23:59 in Kampala: a receipt is not held by the window, and lives an hour.
        assertThat(at(row.get("created_at"))).isEqualTo(MovableClockConfig.BEFORE_MIDNIGHT);
        assertThat(at(row.get("next_attempt_at"))).isEqualTo(MovableClockConfig.BEFORE_MIDNIGHT);
        assertThat(at(row.get("expires_at"))).isEqualTo(MovableClockConfig.BEFORE_MIDNIGHT.plusSeconds(3600));
        assertThat(p.get("date").asString()).isEqualTo("15/10/2026");
    }

    // ---- The maturity reminder ---------------------------------------------------------------------------

    @Test
    void frNtf02_theReminderThatAnInvestmentMaturesIsQueuedOnceHeldForTheWindowAndNeverForAReplayOfPastDays() {
        smsOn();
        String p = product("SMSM", Map.of("payout_frequency", "at_maturity"));
        String soon = open(p, 1_000_000, 3);
        fund(soon, today().minusMonths(3).plusDays(5));
        String soonPhone = investorPhone;
        // The fabricated history replays days that are gone: that run marks reminders and texts nobody.
        String replayed = open(p, 1_000_000, 3);
        fund(replayed, today().minusMonths(3).plusDays(4));
        assertThat(rows(t))
                .as("fundings dated months ago are a register being loaded, not news: not texted")
                .isEmpty();
        TestDatabase.owner()
                .sql("DELETE FROM notification_outbox WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();

        assertThat(runDaily(today().minusDays(2)))
                .as("both are due a reminder two days ago")
                .isEqualTo(2);
        assertThat(rows(t)).as("a past date texts nobody").isEmpty();
        assertThat(runDaily(today())).as("and they are not reminded again").isZero();
        assertThat(rows(t)).isEmpty();

        // A third one, reached on the day itself.
        String third = open(p, 2_000_000, 3);
        fund(third, today().minusMonths(3).plusDays(6));
        String thirdPhone = investorPhone;
        TestDatabase.owner()
                .sql("DELETE FROM notification_outbox WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();

        assertThat(runDaily(today())).isEqualTo(1);
        assertThat(runDaily(today())).as("the job run twice").isZero();
        Map<String, Object> row = only("investment.maturing");
        assertThat(rows(t)).hasSize(1);
        JsonNode params = params(row);
        assertThat(row.get("recipient")).isEqualTo("+256" + thirdPhone.substring(1));
        assertThat(row.get("recipient")).isNotEqualTo("+256" + soonPhone.substring(1));
        assertThat(row.get("category")).isEqualTo("reminder");
        assertThat(params.get("amount").asString()).isEqualTo("2,000,000");
        assertThat(params.get("maturity_date").asString())
                .isEqualTo(LocalDate.parse(inv(third).get("maturity_date").asString())
                        .format(DMY));
        assertThat(params.has("balance")).isFalse();
        // Queued at 23:59 in Kampala: held to the window opening at 08:00 on the 16th (05:00 UTC), and it expires at
        // the
        // end of that window day (20:00, 17:00 UTC), after the deferral.
        assertThat(at(row.get("created_at"))).isEqualTo(MovableClockConfig.BEFORE_MIDNIGHT);
        assertThat(at(row.get("next_attempt_at"))).isEqualTo(Instant.parse("2026-10-16T05:00:00Z"));
        assertThat(at(row.get("expires_at")))
                .isEqualTo(Instant.parse("2026-10-16T17:00:00Z"))
                .isAfter(at(row.get("next_attempt_at")));
        // The key names the investment and its maturity date, so a rollover (a new date) would be a new message.
        assertThat(row.get("idempotency_key").toString())
                .contains(third + ":" + inv(third).get("maturity_date").asString());
    }

    // ---- Payouts -----------------------------------------------------------------------------------------

    @Test
    void frNtf02_everyPayoutQueuesItsVoucherOnceAndAReversalQueuesNothing() {
        smsOn();
        // A return payout.
        String monthly = open(product("SMSP1", Map.of()), 1_000_000, 6);
        fund(monthly, today().minusMonths(3));
        runDaily(today());
        TestDatabase.owner()
                .sql("DELETE FROM notification_outbox WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        String k = key();
        ResponseEntity<JsonNode> paid = pay(monthly, "return-payouts", k);
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(pay(monthly, "return-payouts", k).getHeaders().getFirst("Idempotent-Replayed"))
                .isEqualTo("true");
        Map<String, Object> row = only("investment.paid_out");
        assertThat(rows(t)).hasSize(1);
        assertThat(params(row).get("amount").asString()).isEqualTo("30,000");
        assertThat(params(row).get("voucher_no").asString())
                .isEqualTo(paid.getBody().get("transaction").get("receipt_no").asString())
                .startsWith("VC-");
        assertThat(params(row).get("date").asString()).isEqualTo(today().format(DMY));

        // Its reversal (a checker's decision) sends nothing.
        ResponseEntity<JsonNode> reversal = send(
                HttpMethod.POST,
                INV + "/" + monthly + "/transactions/"
                        + paid.getBody().get("transaction").get("id").asString() + "/reverse",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of("reason", "Test wrong investor"));
        assertThat(reversal.getStatusCode()).as("%s", reversal.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(decide(reversal.getBody().get("approval_request_id").asString(), manager, managerPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(templates()).containsExactly("investment.paid_out");
        assertThat(cancelled())
                .as("the unclaimed voucher message of the reversed payout is withdrawn")
                .containsExactly("investment.paid_out");

        // The maturity payout.
        String matured = open(product("SMSP2", Map.of("payout_frequency", "at_maturity")), 2_000_000, 3);
        fund(matured, today().minusMonths(3));
        runDaily(today());
        TestDatabase.owner()
                .sql("DELETE FROM notification_outbox WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        ResponseEntity<JsonNode> out = pay(matured, "payout", key());
        assertThat(out.getStatusCode()).as("%s", out.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> maturity = only("investment.paid_out");
        assertThat(params(maturity).get("amount").asString()).isEqualTo("2,060,000");
        assertThat(params(maturity).get("account_no").asString())
                .isEqualTo(inv(matured).get("account_no").asString());

        // An early withdrawal, which a checker approves.
        String early = open(
                product(
                        "SMSP3",
                        Map.of(
                                "early_withdrawal_allowed",
                                true,
                                "early_withdrawal_rule",
                                "forfeit_return",
                                "early_withdrawal_penalty_bp",
                                200)),
                1_000_000,
                12);
        fund(early, today().minusMonths(2).minusDays(3));
        runDaily(today());
        TestDatabase.owner()
                .sql("DELETE FROM notification_outbox WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        long cash = asOfficer(HttpMethod.GET, INV + "/" + early + "/early-withdrawal-quote", null, null)
                .getBody()
                .get("cash_minor")
                .asLong();
        ResponseEntity<JsonNode> ew = send(
                HttpMethod.POST,
                INV + "/" + early + "/early-withdrawal",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of("payment_method_key", "cash", "reason", "Test member needs the money"));
        assertThat(ew.getStatusCode()).as("%s", ew.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(rows(t)).as("nothing before the checker").isEmpty();
        assertThat(decide(ew.getBody().get("approval_request_id").asString(), accountant, accountantPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> withdrawn = only("investment.paid_out");
        assertThat(params(withdrawn).get("amount").asString()).isEqualTo(String.format(Locale.ROOT, "%,d", cash));
    }

    /** The templates of the rows a reversal withdrew, with their parameters cleared. */
    List<String> cancelled() {
        return TestDatabase.owner()
                .sql("SELECT template_key FROM notification_outbox WHERE channel = 'sms' AND tenant_id = ?"
                        + " AND status = 'failed' AND last_error = 'cancelled_reversed' AND params = '{}'::jsonb")
                .param(t.tenantId())
                .query(String.class)
                .list();
    }

    @Test
    void notifyMemberFalseOnAFundingAndAPayoutQueuesNothing() {
        smsOn();
        String id = open(product("SMSQ", Map.of("payout_frequency", "at_maturity")), 1_000_000, 3);
        ResponseEntity<JsonNode> r = requestFunding(id, today().minusMonths(3).plusDays(1), key(), false);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(decide(r.getBody().get("approval_request_id").asString(), manager, managerPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rows(t)).as("a register import sends notify_member false").isEmpty();
        // A funding dated today with the flag false is silent too, and the same with true is not.
        String today = open(product("SMSQ2", Map.of()), 1_000_000, 6);
        ResponseEntity<JsonNode> quiet = requestFunding(today, today(), key(), false);
        assertThat(decide(quiet.getBody().get("approval_request_id").asString(), manager, managerPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(rows(t)).isEmpty();
        String loud = open(product("SMSQ3", Map.of()), 1_000_000, 6);
        ResponseEntity<JsonNode> told = requestFunding(loud, today(), key(), true);
        assertThat(decide(told.getBody().get("approval_request_id").asString(), manager, managerPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(templates()).containsExactly("investment.funded");

        // A return payout with the flag false.
        String monthly = open(product("SMSQ4", Map.of()), 1_000_000, 6);
        fund(monthly, today().minusMonths(3));
        runDaily(today());
        ResponseEntity<JsonNode> paid = send(
                HttpMethod.POST,
                INV + "/" + monthly + "/return-payouts",
                withKey(cashierFor(false), key()),
                Map.of("payment_method_key", "cash", "notify_member", false));
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        // (The first investment, funded with notify_member false and maturing tomorrow, is not reminded either.)
        assertThat(templates()).as("no payout message, no reminder").containsExactly("investment.funded");
    }

    @Test
    void frNtf01_aReversedFundingWithdrawsItsUnclaimedMessage() {
        smsOn();
        String id = open(product("SMSV", Map.of()), 1_000_000, 6);
        fund(id, today());
        assertThat(templates()).containsExactly("investment.funded");
        String funding = TestDatabase.owner()
                .sql("SELECT id::text FROM lending_investment_transactions WHERE investment_id = ?::uuid")
                .param(id)
                .query(String.class)
                .single();

        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                INV + "/" + id + "/transactions/" + funding + "/reverse",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of("reason", "Test counterfeit notes"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(decide(r.getBody().get("approval_request_id").asString(), manager, managerPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(inv(id).get("status").asString()).isEqualTo("cancelled");
        assertThat(cancelled()).containsExactly("investment.funded");
        assertThat(templates()).as("nothing new was queued").containsExactly("investment.funded");
    }

    @Test
    void frNtf20_aCashierCannotPayOutOrFundWithoutTellingTheInvestorAndTheAdministratorsChoiceIsAudited() {
        smsOn();
        String monthly = open(product("SMSA", Map.of()), 1_000_000, 6);
        fund(monthly, today().minusMonths(3));
        runDaily(today());
        long paidBefore = inv(monthly).get("return_paid_minor").asLong();

        // A cashier that says no text on a payout: refused before the money moves.
        ResponseEntity<JsonNode> refused = send(
                HttpMethod.POST,
                INV + "/" + monthly + "/return-payouts",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of("payment_method_key", "cash", "notify_member", false));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("notify_member_forbidden");
        assertThat(inv(monthly).get("return_paid_minor").asLong()).isEqualTo(paidBefore);
        // And on a funding request: no approval is raised.
        String unfunded = open(product("SMSA2", Map.of()), 1_000_000, 6);
        ResponseEntity<JsonNode> refusedFunding = send(
                HttpMethod.POST,
                INV + "/" + unfunded + "/funding",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of("value_date", today().toString(), "payment_method_key", "cash", "notify_member", false));
        assertThat(refusedFunding.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(inv(unfunded).get("status").asString()).isEqualTo("pending_funding");

        // The same cashier, saying nothing or true, is told; an administrator's no is audited.
        assertThat(pay(monthly, "return-payouts", key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(templates()).contains("investment.paid_out");
        ResponseEntity<JsonNode> quiet = requestFunding(unfunded, today(), key(), false);
        assertThat(decide(quiet.getBody().get("approval_request_id").asString(), manager, managerPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> audit = TestDatabase.owner()
                .sql(
                        "SELECT data -> 'after' ->> 'notify_member' AS notify, data -> 'after' ->> 'sms_within_cutoff' AS cutoff"
                                + " FROM audit_log WHERE tenant_id = ? AND action = 'lending.investment.funded' AND entity_id = ?::uuid")
                .params(t.tenantId(), unfunded)
                .query()
                .singleRow();
        assertThat(audit.get("notify")).isEqualTo("false");
        assertThat(audit.get("cutoff")).isEqualTo("true");
        assertThat(TestDatabase.owner()
                        .sql("SELECT data -> 'after' ->> 'notify_member' FROM audit_log WHERE tenant_id = ?"
                                + " AND action = 'lending.investment.return_paid'")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo("true");
    }

    /**
     * An investment loaded with notify_member false (or by the seed or import port) keeps that choice: the nightly job
     * never reminds its investor that it matures, and a rollover inherits it; one funded as usual is reminded.
     */
    @Test
    void frNtf20_anInvestmentRecordedWithoutNotifyingItsInvestorIsNeverRemindedLater() {
        smsOn();
        String p = product("SMSN", Map.of("payout_frequency", "at_maturity"));
        String quiet = open(p, 1_000_000, 3);
        ResponseEntity<JsonNode> r =
                requestFunding(quiet, today().minusMonths(3).plusDays(3), key(), false);
        assertThat(decide(r.getBody().get("approval_request_id").asString(), manager, managerPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String told = open(p, 1_000_000, 3);
        fund(told, today().minusMonths(3).plusDays(3));
        assertThat(TestDatabase.owner()
                        .sql("SELECT notify_member FROM lending_investments WHERE id = ?::uuid")
                        .param(quiet)
                        .query(Boolean.class)
                        .single())
                .isFalse();

        assertThat(runDaily(today()))
                .as("both are due a reminder; the job marks both")
                .isEqualTo(2);

        assertThat(rows(t)).as("only the one whose investor may be told").hasSize(1);
        assertThat(params(only("investment.maturing")).get("account_no").asString())
                .isEqualTo(inv(told).get("account_no").asString());
    }

    // ---- What queues nothing -------------------------------------------------------------------------------

    @Test
    void theSeedPortNeverMessagesAnInvestor() {
        smsOn();
        String id = open(product("SMSS", Map.of("payout_frequency", "at_maturity")), 1_000_000, 3);
        TestDatabase.owner()
                .sql("DELETE FROM notification_outbox WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tenantJobs.callAsTenant(
                t.slug(),
                "lending",
                () -> tx.execute(s -> {
                    servicing.fundApproved(UUID.fromString(id), today().minusMonths(3), "cash", cashier, manager);
                    servicing.runDaily(today(), false);
                    servicing.payOutMatured(UUID.fromString(id), today(), "cash", cashier);
                    return null;
                }));
        assertThat(inv(id).get("status").asString()).isEqualTo("paid_out");
        assertThat(rows(t))
                .as("the seed's port texts nobody: no funding, no payout and, replaying today, no reminder either")
                .isEmpty();
    }

    @Test
    void frNtf20_withSmsOffNothingIsQueuedAndAnotherTenantIsNeverMixedIn() {
        String p = product("SMSO", Map.of("payout_frequency", "at_maturity"));
        String id = open(p, 1_000_000, 3);
        fund(id, today().minusMonths(3));
        runDaily(today());
        assertThat(pay(id, "payout", key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(rows(t)).as("neither switch is on").isEmpty();

        TestDatabase.Fixture other = TestDatabase.tenant("inv-other", true);
        SmsTenants.operator(other.tenantId(), true, 1000);
        SmsTenants.enable(other.tenantId());
        smsOn();
        String second = open(p, 1_000_000, 3);
        fund(second, today());
        assertThat(templates()).containsExactly("investment.funded");
        assertThat(rows(other)).isEmpty();
    }
}
