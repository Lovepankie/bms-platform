package com.rincoltech.bms.lending.loans.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.notifications.SmsEvents;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import com.rincoltech.bms.lending.loans.LoanServicing;
import com.rincoltech.bms.lending.loans.internal.LoanRepository.Loan;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.MovableClockConfig;
import com.rincoltech.bms.testsupport.SmsTenants;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The loan SMS events of issue #221 (FR-NTF-01, FR-NTF-02, FR-NTF-06, ADR-036 third amendment) through the staff routes:
 * a disbursement, a repayment and the repayment that closes a loan each queue one message in the outbox, in the
 * transaction of the money, for the borrower's own phone only; the same request replayed, a rollback, a reversal, the seed's
 * port, a number we would not text, and a tenant without SMS queue nothing; the balance is in a receipt only when the
 * tenant asked; another tenant is untouched. The sending itself (the window, caps, opt-out, backlog, exact texts) is
 * proved with the real channel in {@code SmsEventsIT}. All figures and numbers fabricated.
 */
@Import(MovableClockConfig.class)
@ResourceLock(MovableClockConfig.LOCK)
class LoanEventSmsIT extends LoanFixtures {

    @Autowired
    BusinessClock clock;

    @Autowired
    MovableClock movable;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    LoanServicer servicer;

    @Autowired
    LoanRepository loanRepository;

    @Autowired
    LoanServicing port;

    @Autowired
    JdbcClient jdbc;

    /** Spies that call the real beans until a test arms one; the money event must not notice when one breaks. */
    @MockitoSpyBean
    MemberLookup memberLookup;

    @MockitoSpyBean
    TenantSettings tenantSettings;

    @MockitoSpyBean
    SmsEvents smsEvents;

    final UUID appraiser = UUID.randomUUID();
    final UUID manager = UUID.randomUUID();
    final UUID cashier = UUID.randomUUID();
    String cashierPerms;

    @BeforeEach
    void users() {
        cashierPerms = permissionsOf("cashier");
        // Twenty-nine seconds of a minute before Kampala's midnight: the business date is the 15th, and the test moves
        // it.
        movable.set(MovableClockConfig.BEFORE_MIDNIGHT);
    }

    LocalDate today() {
        return clock.today(BusinessClock.DEFAULT_ZONE);
    }

    static String dmy(LocalDate d) {
        return "%02d/%02d/%d".formatted(d.getDayOfMonth(), d.getMonthValue(), d.getYear());
    }

    // ---- Fixtures ---------------------------------------------------------------------------------

    void smsOn() {
        smsOn(t);
    }

    static void smsOn(TestDatabase.Fixture tenant) {
        SmsTenants.operator(tenant.tenantId(), true, 1000);
        SmsTenants.enable(tenant.tenantId());
    }

    /** Flat 10 percent a month, three monthly instalments, no fees. */
    String instalmentProduct(String code) {
        return instalmentProduct(code, List.of());
    }

    /** As above with these fees. */
    String instalmentProduct(String code, List<Map<String, Object>> fees) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("interest_method", "flat");
        terms.put("interest_rate_bp", 1000);
        terms.put("rate_unit", "per_month");
        terms.put("term_unit", "month");
        terms.put("min_term_count", 1);
        terms.put("max_term_count", 6);
        terms.put("default_term_count", 3);
        terms.put("repayment_pattern", "instalments");
        terms.put("instalment_frequency", "monthly");
        terms.put("min_principal_minor", 100_000);
        terms.put("max_principal_minor", 5_000_000);
        terms.put("fees", fees);
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                "/api/v1/lending/loan-products",
                as(UUID.randomUUID(), adminPerms, "*", null),
                Map.of("code", code, "name", "Test " + code, "terms", terms));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    String memberId;

    /** Applied, submitted, appraised and approved by three different users; the borrower's phone is 0700000{no}. */
    String approved(String productId, long principal, int term, int no) {
        memberId = member("Test Borrower %02d".formatted(no), "07000001%02d".formatted(no), t.headOffice(), true);
        String loan =
                apply(memberId, productId, principal, term).getBody().get("id").asString();
        assertThat(asOfficer(HttpMethod.POST, LOANS + "/" + loan + "/submit", "\"1\"", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(send(
                                HttpMethod.POST,
                                LOANS + "/" + loan + "/appraisals",
                                as(appraiser, officerPerms, "*", "\"2\""),
                                Map.of("declared_monthly_income_minor", 2_400_000))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> decided = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/decision",
                as(manager, managerPerms, "*", "\"3\""),
                Map.of("decision", "approve"));
        assertThat(decided.getBody().get("status").asString()).isEqualTo("approved");
        return loan;
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

    void disburse(String loan) {
        disburse(loan, today(), null);
    }

    /** @param notify the request's {@code notify_member}, or null to leave it out */
    void disburse(String loan, LocalDate date, Boolean notify) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disbursement_date", date.toString());
        body.put("payment_method_key", "cash");
        if (notify != null) {
            body.put("notify_member", notify);
        }
        ResponseEntity<JsonNode> r =
                send(HttpMethod.POST, LOANS + "/" + loan + "/disbursements", withKey(cashierFor(notify), key()), body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<JsonNode> d = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + r.getBody().get("approval_request_id").asString() + "/approve",
                as(manager, managerPerms, "*", null),
                Map.of("note", "Checked"));
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<JsonNode> repay(String loan, long amount, String key) {
        return repay(loan, amount, today(), key, null);
    }

    ResponseEntity<JsonNode> repay(String loan, long amount, LocalDate date, String key, Boolean notify) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", amount);
        body.put("value_date", date.toString());
        body.put("payment_method_key", "cash");
        if (notify != null) {
            body.put("notify_member", notify);
        }
        return send(HttpMethod.POST, LOANS + "/" + loan + "/repayments", withKey(cashierFor(notify), key), body);
    }

    JsonNode repaid(String loan, long amount) {
        return repaid(loan, amount, today(), null);
    }

    JsonNode repaid(String loan, long amount, LocalDate date, Boolean notify) {
        ResponseEntity<JsonNode> r = repay(loan, amount, date, key(), notify);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    JsonNode get(String loan, String suffix) {
        return asOfficer(HttpMethod.GET, LOANS + "/" + loan + suffix, null, null)
                .getBody();
    }

    void reverse(String loan, String txnId) {
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/transactions/" + txnId + "/reverse",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of("reason", "Test keying error"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<JsonNode> d = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + r.getBody().get("approval_request_id").asString() + "/approve",
                as(manager, managerPerms, "*", null),
                Map.of("note", "Checked"));
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);
    }

    List<Map<String, Object>> rows(TestDatabase.Fixture tenant) {
        return TestDatabase.owner()
                .sql("SELECT idempotency_key, recipient, template_key, category, subject_type, subject_id, status,"
                        + " params::text AS params, created_at, expires_at FROM notification_outbox"
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
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree((String) row.get("params"));
    }

    static String grouped(long minor) {
        return String.format(Locale.ROOT, "%,d", minor);
    }

    // ---- Disbursement -----------------------------------------------------------------------------------

    @Test
    void frNtf02_aDisbursementQueuesOneMessageForTheBorrowerAndNobodyElse() {
        smsOn();
        String loan = approved(instalmentProduct("SMSD"), 1_200_000, 3, 1);
        // A next of kin with a phone of its own: it is never texted.
        assertThat(send(
                                HttpMethod.POST,
                                "/api/v1/lending/members/" + memberId + "/next-of-kin",
                                as(officer, officerPerms, "*", null),
                                Map.of("full_name", "Test Kin 01", "phone", "0700000199", "relationship", "sibling"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(rows(t)).as("nothing before the money moves").isEmpty();

        disburse(loan);

        assertThat(rows(t)).hasSize(1);
        Map<String, Object> row = only("loan.disbursed");
        JsonNode first = get(loan, "/schedule").get("items").get(0);
        assertThat(row.get("recipient")).isEqualTo("+256700000101");
        assertThat(row.get("category")).isEqualTo("transactional");
        assertThat(row.get("subject_type")).isEqualTo("member");
        assertThat(row.get("subject_id")).isEqualTo(UUID.fromString(memberId));
        JsonNode p = params(row);
        assertThat(p.get("loan_no").asString())
                .isEqualTo(get(loan, "").get("loan_no").asString());
        assertThat(p.get("currency").asString()).isEqualTo("UGX");
        assertThat(p.get("amount").asString()).isEqualTo("1,200,000");
        assertThat(p.get("date").asString()).isEqualTo(dmy(today()));
        assertThat(p.get("next_amount").asString())
                .isEqualTo(grouped(first.get("total_due_minor").asLong()));
        assertThat(p.get("next_due_date").asString())
                .isEqualTo(LocalDate.parse(first.get("due_date").asString())
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)));
        // No name of the borrower, no purpose, no number of anyone in what is stored.
        assertThat((String) row.get("params"))
                .doesNotContain("Test Borrower")
                .doesNotContain("Test stock")
                .doesNotContain("business")
                .doesNotContain("700000");
        assertThat(rows(t).stream().map(r -> r.get("recipient")))
                .as("never the next of kin")
                .doesNotContain("+256700000199");
    }

    // ---- Repayments ---------------------------------------------------------------------------------------

    @Test
    void frNtf02_aRepaymentQueuesTheReceiptWithAmountDateAndReceiptNumberAndNoBalanceByDefault() {
        smsOn();
        String loan = approved(instalmentProduct("SMSR"), 1_200_000, 3, 2);
        disburse(loan);

        JsonNode paid = repaid(loan, 400_000);

        Map<String, Object> row = only("loan.repayment_received");
        JsonNode p = params(row);
        assertThat(row.get("recipient")).isEqualTo("+256700000102");
        assertThat(p.get("amount").asString()).isEqualTo("400,000");
        assertThat(p.get("receipt_no").asString())
                .isEqualTo(paid.get("transaction").get("receipt_no").asString())
                .startsWith("RC-");
        assertThat(p.get("date").asString()).isEqualTo(dmy(today()));
        assertThat(p.has("balance")).as("default: no balance").isFalse();
        assertThat(only("loan.repayment_received").get("idempotency_key").toString())
                .endsWith(":" + paid.get("transaction").get("id").asString() + ":" + memberId);

        // The tenant asks for the balance: the next receipt carries what is still owed after it.
        SmsTenants.set(t.tenantId(), Map.of("include_balance", true));
        JsonNode second = repaid(loan, 300_000);
        long owed = second.get("balances").get("total_outstanding_minor").asLong();
        assertThat(owed).isPositive();
        List<Map<String, Object>> receipts = rows(t).stream()
                .filter(r -> "loan.repayment_received".equals(r.get("template_key")))
                .toList();
        assertThat(receipts).hasSize(2);
        assertThat(params(receipts.get(1)).get("balance").asString()).isEqualTo(grouped(owed));
        assertThat(params(receipts.get(0)).has("balance")).isFalse();
    }

    @Test
    void frNtf06_aRepaymentRequestSentAgainWithTheSameKeyQueuesNothingNew() {
        smsOn();
        String loan = approved(instalmentProduct("SMSI"), 1_200_000, 3, 3);
        disburse(loan);
        String k = key();

        ResponseEntity<JsonNode> first = repay(loan, 100_000, k);
        ResponseEntity<JsonNode> retry = repay(loan, 100_000, k);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(templates()).containsExactly("loan.disbursed", "loan.repayment_received");
        // A different payment of the same amount is another event, and another message.
        repaid(loan, 100_000);
        assertThat(templates()).hasSize(3);
        // And the key is the transaction's, so even the same transaction queued again would be the same row.
        String txn = first.getBody().get("transaction").get("id").asString();
        assertThat(rows(t).stream()
                        .map(r -> r.get("idempotency_key").toString())
                        .filter(x -> x.contains(txn)))
                .hasSize(1);
    }

    @Test
    void frNtf02_theRepaymentThatSettlesTheLoanQueuesTheReceiptAndTheThanksAndAReversalQueuesNothing() {
        smsOn();
        String loan = approved(instalmentProduct("SMSC"), 1_200_000, 3, 4);
        disburse(loan);
        long total = get(loan, "/payoff-quote").get("total_minor").asLong();

        JsonNode closing = repaid(loan, total);
        assertThat(closing.get("loan_status").asString()).isEqualTo("closed");
        assertThat(templates()).containsExactlyInAnyOrder("loan.disbursed", "loan.repayment_received", "loan.closed");
        Map<String, Object> thanks = only("loan.closed");
        assertThat(params(thanks).get("loan_no").asString())
                .isEqualTo(get(loan, "").get("loan_no").asString());
        // Nothing but the number: no amount and no balance in the thanks.
        assertThat(params(thanks).propertyNames()).containsExactlyInAnyOrder("loan_no", "tenant_name");
        assertThat(thanks.get("recipient")).isEqualTo("+256700000104");

        // FR-REP-05: the checker reverses the closing repayment. The loan reopens; the member is told nothing.
        reverse(loan, closing.get("transaction").get("id").asString());
        assertThat(get(loan, "").get("status").asString()).isEqualTo("active");
        assertThat(templates()).as("a reversal sends nothing new").hasSize(3);
        // And what the closing repayment queued, if the sender has not claimed it, is withdrawn: the receipt and the
        // thanks.
        assertThat(cancelled()).containsExactlyInAnyOrder("loan.repayment_received", "loan.closed");

        // Paying it off again closes it again, once more: a new transaction, a new receipt and a new thanks.
        repaid(loan, get(loan, "/payoff-quote").get("total_minor").asLong());
        assertThat(templates()).hasSize(5);
        assertThat(templates().stream().filter("loan.closed"::equals)).hasSize(2);
        assertThat(cancelled()).as("the new ones stand").hasSize(2);
    }

    @Test
    void frNtf02_aRecoveryOnAWrittenOffLoanIsAReceiptWithoutABalanceEvenForATenantThatAskedForIt() {
        smsOn();
        SmsTenants.set(t.tenantId(), Map.of("include_balance", true));
        String loan = approved(instalmentProduct("SMSW"), 1_200_000, 3, 10);
        disburse(loan);
        repaid(loan, 300_000);
        ResponseEntity<JsonNode> request = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/write-off",
                withKey(as(UUID.randomUUID(), permissionsOf("accountant"), "*", null), key()),
                Map.of("reason", "Test borrower unreachable"));
        assertThat(request.getStatusCode()).as("%s", request.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(send(
                                HttpMethod.POST,
                                "/api/v1/approvals/"
                                        + request.getBody()
                                                .get("approval_request_id")
                                                .asString() + "/approve",
                                as(UUID.randomUUID(), adminPerms, "*", null),
                                Map.of("note", "Checked"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get(loan, "").get("status").asString()).isEqualTo("written_off");
        assertThat(templates())
                .as("a write-off sends nothing")
                .containsExactlyInAnyOrder("loan.disbursed", "loan.repayment_received");

        JsonNode recovery = repaid(loan, 50_000).get("transaction");

        assertThat(recovery.get("txn_type").asString()).isEqualTo("recovery");
        List<Map<String, Object>> receipts = rows(t).stream()
                .filter(r -> "loan.repayment_received".equals(r.get("template_key")))
                .toList();
        assertThat(receipts).hasSize(2);
        assertThat(receipts.stream().map(r -> params(r)))
                .filteredOn(p -> "50,000".equals(p.get("amount").asString()))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.has("balance")).isFalse();
                    assertThat(p.get("receipt_no").asString())
                            .isEqualTo(recovery.get("receipt_no").asString());
                });
        assertThat(receipts.stream().map(r -> params(r)))
                .filteredOn(p -> "300,000".equals(p.get("amount").asString()))
                .singleElement()
                .satisfies(p -> assertThat(p.has("balance")).isTrue());
        assertThat(templates()).doesNotContain("loan.closed");
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

    // ---- The edge of Kampala's day ------------------------------------------------------------------------

    /**
     * 20:59 UTC is 23:59 on the 15th in Kampala and 21:01 UTC is 00:01 on the 16th. The date in a message is the business
     * date of the event, whichever side of midnight it happens.
     */
    @Test
    void frNtf04_theDateInAMessageIsTheKampalaDateOfTheEventAcrossMidnight() {
        smsOn();
        String loan = approved(instalmentProduct("SMSE"), 1_200_000, 3, 11);
        assertThat(today()).isEqualTo(LocalDate.parse("2026-10-15"));
        disburse(loan);
        assertThat(params(only("loan.disbursed")).get("date").asString()).isEqualTo("15/10/2026");

        movable.set(MovableClockConfig.AFTER_MIDNIGHT);
        assertThat(today()).isEqualTo(LocalDate.parse("2026-10-16"));
        repaid(loan, 100_000);

        assertThat(params(only("loan.repayment_received")).get("date").asString())
                .isEqualTo("16/10/2026");
        // Both are receipts, queued at once with their hour from the moment of the event.
        assertThat(rows(t).stream().map(r -> r.get("category")).distinct()).containsExactly("transactional");
    }

    // ---- What the member receives, with fees ---------------------------------------------------------------

    @Test
    void frDis02_theDisbursementMessageStatesWhatWasPaidOutAfterFeesDeductedAtDisbursement() {
        smsOn();
        Map<String, Object> fee = new LinkedHashMap<>();
        fee.put("name", "Test processing fee");
        fee.put("fee_type", "processing");
        fee.put("calc_method", "flat");
        fee.put("amount_minor", 50_000);
        fee.put("rate_bp", null);
        fee.put("timing", "deducted_at_disbursement");
        String loan = approved(instalmentProduct("SMSFEE", List.of(fee)), 1_200_000, 3, 12);

        disburse(loan);

        // The loan is 1,200,000; 50,000 of fee is kept at disbursement; the member receives 1,150,000.
        assertThat(params(only("loan.disbursed")).get("amount").asString()).isEqualTo("1,150,000");
    }

    // ---- The request can say no; old events are never texted --------------------------------------------------

    @Test
    void notifyMemberFalseOnADisbursementAndARepaymentQueuesNothingAndTrueOrOmittedDoes() {
        smsOn();
        String quiet = approved(instalmentProduct("SMSQ"), 1_200_000, 3, 13);
        disburse(quiet, today(), false);
        repaid(quiet, 100_000, today(), false);
        assertThat(rows(t)).as("a register import says notify_member false").isEmpty();
        assertThat(get(quiet, "/transactions").get("items").findValuesAsString("txn_type"))
                .containsExactlyInAnyOrder("disbursement", "repayment");

        repaid(quiet, 100_000, today(), true);
        assertThat(templates()).containsExactly("loan.repayment_received");
        repaid(quiet, 100_000, today(), null);
        assertThat(templates()).hasSize(2);
    }

    @Test
    void frNtf17_anEventWhoseValueDateIsOlderThanTheCutoffIsNeverTexted() {
        smsOn();
        LocalDate today = today();
        String loan = approved(instalmentProduct("SMSO3"), 1_200_000, 3, 14);
        // Disbursed ten days ago: a register being loaded, not news.
        disburse(loan, today.minusDays(10), null);
        assertThat(rows(t)).isEmpty();
        // Repayments dated four days ago (one day past the cutoff of three): no text. Three days ago: the edge, a text.
        repaid(loan, 100_000, today.minusDays(4), null);
        assertThat(rows(t)).isEmpty();
        repaid(loan, 100_000, today.minusDays(3), null);
        assertThat(templates()).containsExactly("loan.repayment_received");
        assertThat(params(only("loan.repayment_received")).get("date").asString())
                .isEqualTo(dmy(today.minusDays(3)));
        repaid(loan, 100_000, today, null);
        assertThat(templates()).hasSize(2);
        // Past midnight the cutoff moves with the Kampala date: on the 16th the 12th is four days old and is not
        // texted,
        // the 13th is three days old and is.
        movable.set(MovableClockConfig.AFTER_MIDNIGHT);
        LocalDate twelfth = LocalDate.parse("2026-10-12");
        String other = approved(instalmentProduct("SMSO4"), 1_200_000, 3, 15);
        disburse(other, twelfth, null);
        repaid(other, 100_000, twelfth, null);
        assertThat(templates()).as("nothing new for the 12th").hasSize(2);
        repaid(other, 100_000, twelfth.plusDays(1), null);
        assertThat(templates()).hasSize(3);
    }

    // ---- A reversal withdraws the message that has not gone ---------------------------------------------------

    /**
     * Three receipts, each reversed: one nobody has taken, one that failed once and waits to be retried (attempts 1, pending,
     * a retry time, no lease: it would still go out), and one a sender holds right now (a live lease: it may be at the
     * provider). The first two are withdrawn; the third is left, whatever the reversal says.
     */
    @Test
    void frNtf01_aReversalWithdrawsAnUnclaimedAndARetryPendingReceiptAndLeavesOneASenderHolds() {
        smsOn();
        String loan = approved(instalmentProduct("SMSV"), 1_200_000, 3, 16);
        disburse(loan);
        JsonNode held = repaid(loan, 100_000).get("transaction");
        JsonNode retrying = repaid(loan, 150_000).get("transaction");
        JsonNode fresh = repaid(loan, 200_000).get("transaction");
        Instant now = MovableClockConfig.BEFORE_MIDNIGHT;
        // A sender holds the first: the claim counted an attempt and took a lease of two minutes.
        TestDatabase.owner()
                .sql(
                        "UPDATE notification_outbox SET attempts = 1, leased_until = clock_timestamp() + interval '2 minutes',"
                                + " next_attempt_at = ? WHERE tenant_id = ? AND idempotency_key LIKE ?")
                .params(Timestamp.from(now.plusSeconds(120)), t.tenantId(), keyOf(held))
                .update();
        // The second failed once (a provider 503): pending again with a retry time and no lease.
        TestDatabase.owner()
                .sql(
                        "UPDATE notification_outbox SET attempts = 1, leased_until = NULL, last_error = 'provider_http_503',"
                                + " next_attempt_at = ? WHERE tenant_id = ? AND idempotency_key LIKE ?")
                .params(Timestamp.from(now.plusSeconds(60)), t.tenantId(), keyOf(retrying))
                .update();

        reverse(loan, fresh.get("id").asString());
        reverse(loan, retrying.get("id").asString());
        assertThat(cancelled()).containsExactlyInAnyOrder("loan.repayment_received", "loan.repayment_received");
        for (JsonNode txn : new JsonNode[] {fresh, retrying}) {
            Map<String, Object> gone = TestDatabase.owner()
                    .sql("SELECT status, last_error, params::text AS params FROM notification_outbox"
                            + " WHERE idempotency_key LIKE ?")
                    .param(keyOf(txn))
                    .query()
                    .singleRow();
            assertThat(gone.get("status")).isEqualTo("failed");
            assertThat(gone.get("last_error")).isEqualTo("cancelled_reversed");
            assertThat(gone.get("params"))
                    .as("the amount and receipt number are cleared")
                    .isEqualTo("{}");
        }

        reverse(loan, held.get("id").asString());
        assertThat(cancelled()).as("the one a sender holds is left alone").hasSize(2);
        Map<String, Object> kept = TestDatabase.owner()
                .sql("SELECT status, attempts FROM notification_outbox WHERE idempotency_key LIKE ?")
                .param(keyOf(held))
                .query()
                .singleRow();
        assertThat(kept.get("status")).isEqualTo("pending");
        assertThat(kept.get("attempts")).isEqualTo(1);
        // The operator's list of failed messages does not offer a withdrawn one for "send again".
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox_failures(1000)"
                                + " WHERE last_error = 'cancelled_reversed'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    String keyOf(JsonNode txn) {
        return "sms:loan.repayment_received:%:" + txn.get("id").asString() + ":%";
    }

    // ---- Only an administrator may record a movement without telling the member ---------------------------------

    @Test
    void frNtf20_aCashierCannotRecordAMovementWithoutTellingTheMemberButAnAdministratorCanAndItIsAudited() {
        smsOn();
        String loan = approved(instalmentProduct("SMSA"), 1_200_000, 3, 18);
        // A disbursement request from a cashier that says no text: refused in plain words, no approval is raised.
        Map<String, Object> disbursement = new LinkedHashMap<>();
        disbursement.put("disbursement_date", today().toString());
        disbursement.put("payment_method_key", "cash");
        disbursement.put("notify_member", false);
        ResponseEntity<JsonNode> refusedDisbursement = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/disbursements",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                disbursement);
        assertThat(refusedDisbursement.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refusedDisbursement.getBody().get("code").asString()).isEqualTo("notify_member_forbidden");
        assertThat(refusedDisbursement.getBody().get("detail").asString())
                .contains("Only a business administrator")
                .contains("notify_member");
        assertThat(get(loan, "").get("status").asString()).isEqualTo("approved");
        disburse(loan);

        // A repayment from a cashier that says no text: refused before anything is recorded.
        ResponseEntity<JsonNode> refused = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/repayments",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of(
                        "amount_minor",
                        100_000,
                        "value_date",
                        today().toString(),
                        "payment_method_key",
                        "cash",
                        "notify_member",
                        false));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("notify_member_forbidden");
        assertThat(get(loan, "/transactions").get("items").findValuesAsString("txn_type"))
                .as("no money moved")
                .containsExactly("disbursement");

        // The default is unchanged: omitted or true by the same cashier is told.
        repaid(loan, 100_000, today(), null);
        repaid(loan, 100_000, today(), true);
        assertThat(templates()).containsExactly("loan.disbursed", "loan.repayment_received", "loan.repayment_received");

        // An administrator may say no, and the entry says so, with the cutoff decision.
        JsonNode quiet = repaid(loan, 100_000, today(), false).get("transaction");
        assertThat(templates()).as("nothing new for the quiet one").hasSize(3);
        Map<String, Object> audit = TestDatabase.owner()
                .sql(
                        "SELECT data -> 'after' ->> 'notify_member' AS notify, data -> 'after' ->> 'sms_within_cutoff' AS cutoff,"
                                + " data -> 'after' ->> 'receipt_no' AS receipt FROM audit_log"
                                + " WHERE tenant_id = ? AND action = 'lending.loan.repayment_recorded'"
                                + " AND data -> 'after' ->> 'receipt_no' = ?")
                .params(t.tenantId(), quiet.get("receipt_no").asString())
                .query()
                .singleRow();
        assertThat(audit.get("notify")).isEqualTo("false");
        assertThat(audit.get("cutoff")).isEqualTo("true");
        // And the told ones say true; an old value date says it was past the cutoff.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ?"
                                + " AND action IN ('lending.loan.repayment_recorded', 'lending.loan.disbursed')"
                                + " AND data -> 'after' ->> 'notify_member' = 'true'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
        String other = approved(instalmentProduct("SMSA2"), 1_200_000, 3, 19);
        disburse(other, today().minusDays(10), null);
        assertThat(TestDatabase.owner()
                        .sql("SELECT data -> 'after' ->> 'sms_within_cutoff' FROM audit_log WHERE tenant_id = ?"
                                + " AND action = 'lending.loan.disbursed' AND entity_id = ?::uuid")
                        .params(t.tenantId(), other)
                        .query(String.class)
                        .single())
                .isEqualTo("false");
    }

    // ---- A failure of any collaborator of the message never loses the money ------------------------------------

    /**
     * Each of the four things the message needs from the caller's transaction is made to fail in turn, once with a Java
     * exception and once with a database error that aborts the PostgreSQL transaction (as a statement that raises does): the
     * member lookup and the settings read (both go through transactional proxies, which mark the transaction rollback-only
     * when an exception leaves them), the amount formatting, and the check that SMS is on. The repayment is recorded all the
     * same, nothing is queued, and the next repayment, with the collaborator healthy again, queues its receipt.
     */
    @Test
    void frNtf01_aFailureOfAnyCollaboratorOfTheMessageNeverLosesTheRepayment() {
        smsOn();
        SmsTenants.set(t.tenantId(), Map.of("include_balance", true));
        String loan = approved(instalmentProduct("SMSG"), 1_200_000, 3, 17);
        disburse(loan);
        MemberLookup lookup = AopTestUtils.getUltimateTargetObject(memberLookup);
        TenantSettings settings = AopTestUtils.getUltimateTargetObject(tenantSettings);
        SmsEvents events = AopTestUtils.getUltimateTargetObject(smsEvents);
        Map<String, Consumer<Boolean>> breakers = new LinkedHashMap<>();
        breakers.put(
                "member lookup",
                sql -> Mockito.doAnswer(i -> fail(sql)).when(lookup).smsPhone(ArgumentMatchers.any()));
        breakers.put(
                "settings",
                sql -> Mockito.doAnswer(i -> fail(sql)).when(settings).smsIncludeBalance());
        breakers.put(
                "amount formatting",
                sql -> Mockito.doAnswer(i -> fail(sql))
                        .when(events)
                        .amount(ArgumentMatchers.anyLong(), ArgumentMatchers.anyString()));
        breakers.put(
                "active check",
                sql -> Mockito.doAnswer(i -> fail(sql)).when(events).active());

        int repayments = 0;
        for (Map.Entry<String, Consumer<Boolean>> breaker : breakers.entrySet()) {
            for (boolean sqlError : new boolean[] {false, true}) {
                Mockito.reset(lookup, settings, events);
                breaker.getValue().accept(sqlError);

                JsonNode paid = repaid(loan, 10_000);
                repayments++;

                String what = breaker.getKey() + (sqlError ? " (database error)" : " (exception)");
                assertThat(paid.get("transaction").get("txn_type").asString())
                        .as(what)
                        .isEqualTo("repayment");
                assertThat(get(loan, "/transactions").get("items").findValuesAsString("txn_type").stream()
                                .filter("repayment"::equals))
                        .as(what + ": the repayment is recorded")
                        .hasSize(repayments);
                assertThat(templates()).as(what + ": nothing queued").containsExactly("loan.disbursed");
            }
        }
        Mockito.reset(lookup, settings, events);
        repaid(loan, 10_000);
        assertThat(templates())
                .as("healthy again")
                .containsExactlyInAnyOrder("loan.disbursed", "loan.repayment_received");
    }

    /** Fails as asked: a database error inside the caller's transaction, or a plain exception. */
    Object fail(boolean sqlError) {
        if (sqlError) {
            jdbc.sql("SELECT 1 / 0").query(Integer.class).single();
        }
        throw new IllegalStateException("collaborator down");
    }

    // ---- What queues nothing ------------------------------------------------------------------------------

    @Test
    void frNtf01_aRepaymentThatRollsBackQueuesNothing() {
        smsOn();
        String loan = approved(instalmentProduct("SMSB"), 1_200_000, 3, 5);
        disburse(loan);
        assertThat(rows(t)).hasSize(1);
        long paidBefore = get(loan, "").get("balances").get("total_paid_minor").asLong();

        // The same code the route runs, in a transaction that fails after the money and the message were written.
        UUID loanId = UUID.fromString(loan);
        try {
            TenantContext.callAs(
                    t.tenantId(),
                    () -> new TransactionTemplate(transactionManager).execute(tx -> {
                        Loan l = loanRepository.lock(loanId).orElseThrow();
                        servicer.repay(l, 400_000, today(), "cash", null, cashier, "staff");
                        assertThat(TestDatabase.owner()
                                        .sql(
                                                "SELECT count(*) FROM notification_outbox WHERE tenant_id = ? AND template_key = ?")
                                        .params(t.tenantId(), "loan.repayment_received")
                                        .query(Long.class)
                                        .single())
                                .as("not visible to anyone else until it commits")
                                .isZero();
                        throw new IllegalStateException("the day's closing failed");
                    }));
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("the day's closing failed");
        }

        assertThat(templates()).as("the receipt rolled back with the money").containsExactly("loan.disbursed");
        assertThat(get(loan, "").get("balances").get("total_paid_minor").asLong())
                .isEqualTo(paidBefore);
    }

    @Test
    void theSeedAndTheImportPortNeverMessageAMember() {
        smsOn();
        String loan = approved(instalmentProduct("SMSS"), 1_200_000, 3, 6);
        UUID loanId = UUID.fromString(loan);
        TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager).execute(tx -> {
                    port.disburseApproved(loanId, today(), "cash", cashier, manager);
                    port.recordRepayment(loanId, 100_000, today(), "cash", cashier);
                    return null;
                }));
        assertThat(get(loan, "").get("status").asString()).isEqualTo("active");
        assertThat(get(loan, "/transactions").get("items").findValuesAsString("txn_type"))
                .containsExactlyInAnyOrder("repayment", "disbursement");
        assertThat(rows(t)).isEmpty();
    }

    @Test
    void frNtf20_withSmsOffForTheOperatorOrTheTenantOrAZeroCapNothingIsQueuedAndTheMoneyStillMoves() {
        // Neither switch.
        String off = approved(instalmentProduct("SMSO1"), 1_200_000, 3, 7);
        disburse(off);
        repaid(off, 100_000);
        assertThat(rows(t)).isEmpty();
        // The operator on, the tenant's own switch off.
        SmsTenants.operator(t.tenantId(), true, 1000);
        repaid(off, 100_000);
        assertThat(rows(t)).isEmpty();
        // Both on, but the tenant's daily cap is 0: stop.
        SmsTenants.enable(t.tenantId());
        SmsTenants.set(t.tenantId(), Map.of("daily_cap", 0));
        repaid(off, 100_000);
        assertThat(rows(t)).isEmpty();
        // Lifted: the next one is queued.
        SmsTenants.set(t.tenantId(), Map.of("daily_cap", 10));
        repaid(off, 100_000);
        assertThat(templates()).containsExactly("loan.repayment_received");
        assertThat(get(off, "/transactions").get("items").findValuesAsString("txn_type"))
                .containsExactly("repayment", "repayment", "repayment", "repayment", "disbursement");
    }

    @Test
    void frNtf19_aBorrowerWithoutANumberWeWouldTextIsNotMessagedAndTheRepaymentStillWorks() {
        smsOn();
        String loan = approved(instalmentProduct("SMSN"), 1_200_000, 3, 8);
        // A number of another country, stored as a member's phone, is one we do not text.
        TestDatabase.owner()
                .sql("UPDATE lending_members SET phone_e164 = '+14155550123' WHERE id = ?::uuid")
                .param(memberId)
                .update();
        disburse(loan);
        repaid(loan, 100_000);
        assertThat(rows(t)).isEmpty();
        assertThat(get(loan, "").get("status").asString()).isEqualTo("active");
    }

    @Test
    void anotherTenantsRowsAndSwitchesAreNeverMixedIn() {
        smsOn();
        TestDatabase.Fixture other = TestDatabase.tenant("loans-other", true);
        smsOn(other);
        String loan = approved(instalmentProduct("SMSX"), 1_200_000, 3, 9);
        disburse(loan);
        repaid(loan, 100_000);

        assertThat(rows(t)).hasSize(2);
        assertThat(rows(other)).isEmpty();
        assertThat(rows(t).stream().map(r -> r.get("idempotency_key").toString()))
                .allSatisfy(k -> assertThat(k).contains(t.tenantId().toString()));
        // Every message of the platform outbox carries the tenant that caused it, and only that one.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox WHERE idempotency_key LIKE ? AND tenant_id <> ?")
                        .params("sms:loan.%:" + t.tenantId() + ":%", t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // ---- Reminders and arrears notices (#227) -----------------------------------------------------

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("loanArrears")
    com.github.kagkarlsson.scheduler.task.helper.RecurringTask<Void> arrearsTask;

    /** The nightly arrears job as the scheduler runs it: every active lending tenant, one transaction each. */
    void nightly() {
        arrearsTask.execute(null, null);
    }

    Instant notBefore(String template) {
        return TestDatabase.owner()
                .sql("SELECT next_attempt_at FROM notification_outbox WHERE channel = 'sms' AND tenant_id = ?"
                        + " AND template_key = ?")
                .params(t.tenantId(), template)
                .query(Timestamp.class)
                .single()
                .toInstant();
    }

    /**
     * FR-NTF-02, FR-NTF-04 and FR-NTF-06: an instalment two days away gets one reminder, about that instalment, held
     * for the tenant's window: run at 21:00 Kampala time it waits for 08:00 the next morning. Rerunning the job queues
     * nothing new.
     */
    @Test
    void frNtf06_aReminderIsQueuedOncePerInstalmentAndWaitsForTheWindow() {
        smsOn();
        String loan = approved(instalmentProduct("REMIND"), 1_200_000, 3, 41);
        disburse(loan, today().minusMonths(1).plusDays(2), null);
        rows(t).forEach(r -> assertThat(r.get("template_key")).isNotEqualTo(LoanNotices.DUE_IN_3_DAYS));
        movable.set(Instant.parse("2026-10-15T18:00:00Z")); // 21:00 in Kampala
        LocalDate due = today().plusDays(2);

        nightly();
        Map<String, Object> row = only(LoanNotices.DUE_IN_3_DAYS);
        assertThat(row.get("category")).isEqualTo("reminder");
        assertThat(row.get("recipient")).isEqualTo("+256700000141");
        JsonNode p = params(row);
        assertThat(p.get("loan_no").asString()).startsWith("LN");
        assertThat(p.get("amount").asString()).isEqualTo("520,000");
        assertThat(p.get("due_date").asString()).isEqualTo(dmy(due));
        assertThat(notBefore(LoanNotices.DUE_IN_3_DAYS)).isEqualTo(Instant.parse("2026-10-16T05:00:00Z"));

        int before = rows(t).size();
        nightly();
        assertThat(rows(t)).as("a rerun queues nothing new").hasSize(before);
    }

    /**
     * FR-NTF-02: the arrears notices follow the loan's DPD, with the arrears and the days past due, in the arrears
     * category; each step once.
     */
    @Test
    void frNtf02_arrearsNoticesFollowTheStepsOnce() {
        smsOn();
        String loan = approved(instalmentProduct("ARREARSMS"), 1_200_000, 3, 42);
        LocalDate disbursed = today().minusMonths(1).minusDays(1);
        disburse(loan, disbursed, null);

        nightly();
        Map<String, Object> first = only(LoanNotices.OVERDUE_1);
        assertThat(first.get("category")).isEqualTo("arrears");
        assertThat(params(first).get("arrears").asString()).isEqualTo("520,000");
        assertThat(params(first).get("dpd").asString()).isEqualTo("1");
        nightly();
        assertThat(templates()).filteredOn(LoanNotices.OVERDUE_1::equals).hasSize(1);

        movable.set(MovableClockConfig.BEFORE_MIDNIGHT.plus(java.time.Duration.ofDays(6)));
        nightly();
        assertThat(params(only(LoanNotices.OVERDUE_7)).get("dpd").asString()).isEqualTo("7");
        assertThat(templates()).doesNotContain(LoanNotices.OVERDUE_30);
    }

    /** A tenant without SMS gets no reminder from the nightly job, and the job runs as before. */
    @Test
    void frNtf20_noReminderIsQueuedWhileSmsIsOff() {
        String loan = approved(instalmentProduct("NOSMSREM"), 1_200_000, 3, 43);
        disburse(loan, today().minusMonths(1).minusDays(1), null);
        nightly();
        assertThat(rows(t)).isEmpty();
        assertThat(get(loan, "").get("balances").get("days_past_due").asInt()).isEqualTo(1);
    }
}
