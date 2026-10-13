package com.rincoltech.bms.lending.loans;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Increment 5 on real PostgreSQL (#108): disbursement with a checker and fees, schedules,
 * repayments with R-ALLOC, overpayment, payoff and closure, reversal with re-allocation, write-off
 * and recovery, idempotency and branch scope; every journal is checked line by line against
 * chapter 6 section 6.6.3, and the books are checked to agree with the subledger after each test.
 * Four users: an officer originates, an appraiser appraises, a manager approves and checks, a
 * cashier moves the money. All figures fabricated.
 */
class LoanServicingIT extends LoanFixtures {

    @Autowired
    BusinessClock clock;

    final UUID appraiser = UUID.randomUUID();
    final UUID manager = UUID.randomUUID();
    final UUID cashier = UUID.randomUUID();
    final UUID accountant = UUID.randomUUID();
    final UUID admin = UUID.randomUUID();
    String cashierPerms;
    String accountantPerms;
    LocalDate today;

    @BeforeEach
    void users() {
        cashierPerms = permissionsOf("cashier");
        accountantPerms = permissionsOf("accountant");
        today = clock.today(BusinessClock.DEFAULT_ZONE);
    }

    // ---- Fixtures -------------------------------------------------------------------------

    /** A product with these terms over the fixture defaults, and these fees. */
    String product(String code, Map<String, Object> overrides, List<Map<String, Object>> fees) {
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
        terms.putAll(overrides);
        terms.put("fees", fees);
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                "/api/v1/lending/loan-products",
                as(UUID.randomUUID(), adminPerms, "*", null),
                Map.of("code", code, "name", "Test " + code, "terms", terms));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    static Map<String, Object> fee(String timing, Long amount, Integer rateBp) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", "Test fee " + timing);
        f.put("fee_type", "processing");
        f.put("calc_method", amount != null ? "flat" : "percent_of_principal");
        f.put("amount_minor", amount);
        f.put("rate_bp", rateBp);
        f.put("timing", timing);
        return f;
    }

    /** Worked example B's product: 1000 bp per month flat, monthly, no fees. */
    String exampleB(String code) {
        return product(code, Map.of(), List.of());
    }

    /** Applied, submitted, appraised and approved by three different users. */
    String approved(String productId, long principal, int term, int memberNo) {
        String member = member(
                "Test Borrower %02d".formatted(memberNo), "07000000%02d".formatted(memberNo), t.headOffice(), true);
        ResponseEntity<JsonNode> created = apply(member, productId, principal, term);
        assertThat(created.getStatusCode()).as("%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        String loan = created.getBody().get("id").asString();
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

    HttpHeaders withKey(HttpHeaders h, String key) {
        if (key != null) {
            h.add("Idempotency-Key", key);
        }
        return h;
    }

    static String key() {
        return "test-" + UUID.randomUUID();
    }

    ResponseEntity<JsonNode> requestDisbursement(String loan, LocalDate date, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disbursement_date", date.toString());
        body.put("payment_method_key", "cash");
        body.put("external_reference", "TEST-DISB-01");
        return send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/disbursements",
                withKey(as(cashier, cashierPerms, "*", null), key),
                body);
    }

    ResponseEntity<JsonNode> decide(String approvalId, UUID user, String perms) {
        return send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approvalId + "/approve",
                as(user, perms, "*", null),
                Map.of("note", "Checked"));
    }

    /** Requested by the cashier and authorised by the manager. */
    void disburse(String loan, LocalDate date) {
        ResponseEntity<JsonNode> r = requestDisbursement(loan, date, key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<JsonNode> d =
                decide(r.getBody().get("approval_request_id").asString(), manager, managerPerms);
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<JsonNode> repay(String loan, long amount, LocalDate valueDate, String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", amount);
        body.put("value_date", valueDate.toString());
        body.put("payment_method_key", "cash");
        body.put("external_reference", "TEST-REF");
        return send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/repayments",
                withKey(as(cashier, cashierPerms, "*", null), key),
                body);
    }

    JsonNode repaid(String loan, long amount, LocalDate valueDate) {
        ResponseEntity<JsonNode> r = repay(loan, amount, valueDate, key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    JsonNode loan(String loan) {
        return asOfficer(HttpMethod.GET, LOANS + "/" + loan, null, null).getBody();
    }

    JsonNode get(String loan, String suffix) {
        ResponseEntity<JsonNode> r = asOfficer(HttpMethod.GET, LOANS + "/" + loan + suffix, null, null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    /** The lines of a journal entry as {@code system_key:debit:credit}, in line order. */
    static List<String> lines(String entryId) {
        return TestDatabase.owner().sql("""
                        SELECT a.system_key || ':' || l.debit || ':' || l.credit FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.entry_id = ?::uuid ORDER BY l.line_no
                        """).param(entryId).query(String.class).list();
    }

    /** The trial balance balances, and the loans receivable subledger equals each loan's principal outstanding. */
    void booksAgree() {
        JdbcRow totals = TestDatabase.owner()
                .sql("SELECT coalesce(sum(debit), 0) AS d, coalesce(sum(credit), 0) AS c FROM journal_lines"
                        + " WHERE tenant_id = ?")
                .param(t.tenantId())
                .query((rs, n) -> new JdbcRow(rs.getLong("d"), rs.getLong("c")))
                .single();
        assertThat(totals.debit()).isEqualTo(totals.credit());
        List<String> mismatches = TestDatabase.owner()
                .sql("""
                        SELECT l.loan_no || ': ' || l.principal_outstanding_minor || ' vs '
                               || coalesce(sum(j.debit - j.credit), 0)
                          FROM lending_loans l
                          LEFT JOIN journal_lines j ON j.subledger_id = l.id AND j.subledger_type = 'lending.loan'
                               AND j.account_id = (SELECT id FROM gl_accounts WHERE tenant_id = l.tenant_id
                                                    AND system_key = 'loans_receivable')
                         WHERE l.tenant_id = ?
                         GROUP BY l.id, l.loan_no, l.principal_outstanding_minor
                        HAVING l.principal_outstanding_minor <> coalesce(sum(j.debit - j.credit), 0)
                        """)
                .param(t.tenantId())
                .query(String.class)
                .list();
        assertThat(mismatches).as("subledger against loans_receivable").isEmpty();
    }

    record JdbcRow(long debit, long credit) {}

    static List<Long> longs(JsonNode array, String field) {
        List<Long> values = new ArrayList<>();
        array.forEach(n -> values.add(n.get(field).asLong()));
        return values;
    }

    // ---- Disbursement ---------------------------------------------------------------------

    /**
     * FR-DIS-01 to FR-DIS-04, FR-APR-01, FR-PRD-02 (deducted fee): the request waits for a checker
     * with nothing posted; on approval the schedule, the voucher, the journal and the balances
     * appear together; a second disbursement is refused.
     */
    @Test
    void frDis_disbursementWaitsForTheCheckerThenPostsTheScheduleAndJournal() {
        String product = product("DISB", Map.of(), List.of(fee("deducted_at_disbursement", null, 200)));
        String loan = approved(product, 1_200_000, 3, 41);

        ResponseEntity<JsonNode> pending = requestDisbursement(loan, today, key());
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(pending.getBody().get("executed").asBoolean()).isFalse();
        assertThat(pending.getBody().get("loan_status").asString()).isEqualTo("approved");
        assertThat(get(loan, "/schedule").get("items")).isEmpty();
        assertThat(get(loan, "/transactions").get("items")).isEmpty();

        // The cashier cannot authorise; the manager can.
        String approval = pending.getBody().get("approval_request_id").asString();
        assertThat(decide(approval, cashier, cashierPerms).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(decide(approval, manager, managerPerms)
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("approved");

        JsonNode l = loan(loan);
        assertThat(l.get("status").asString()).isEqualTo("active");
        JsonNode b = l.get("balances");
        assertThat(b.get("disbursed_on").asString()).isEqualTo(today.toString());
        assertThat(b.get("maturity_date").asString())
                .isEqualTo(today.plusMonths(3).toString());
        assertThat(b.get("principal_disbursed_minor").asLong()).isEqualTo(1_200_000);
        assertThat(b.get("principal_outstanding_minor").asLong()).isEqualTo(1_200_000);
        assertThat(b.get("interest_outstanding_minor").asLong()).isEqualTo(360_000);
        assertThat(b.get("next_due_date").asString())
                .isEqualTo(today.plusMonths(1).toString());
        assertThat(l.get("provisional_schedule")).isEmpty();

        JsonNode schedule = get(loan, "/schedule");
        assertThat(schedule.get("items")).hasSize(3);
        for (JsonNode item : schedule.get("items")) {
            assertThat(item.get("principal_due_minor").asLong()).isEqualTo(400_000);
            assertThat(item.get("interest_due_minor").asLong()).isEqualTo(120_000);
            assertThat(item.get("status").asString()).isEqualTo("pending");
        }
        assertThat(schedule.get("totals").get("total_due_minor").asLong()).isEqualTo(1_560_000);
        assertThat(schedule.get("totals").get("outstanding_minor").asLong()).isEqualTo(1_560_000);

        JsonNode txn = get(loan, "/transactions").get("items").get(0);
        assertThat(txn.get("txn_type").asString()).isEqualTo("disbursement");
        assertThat(txn.get("receipt_no").asString()).isEqualTo("VC-HQ-000001");
        assertThat(txn.get("approval_request_id").asString()).isEqualTo(approval);
        // 6.6.3: loans receivable the principal; cash the principal less the 2% deducted fee.
        assertThat(lines(txn.get("journal_entry_id").asString()))
                .containsExactly("loans_receivable:1200000:0", "cash_on_hand:0:1176000", "loan_fee_income:0:24000");

        // FR-DIS-03: one disbursement per loan.
        ResponseEntity<JsonNode> again = requestDisbursement(loan, today, key());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("invalid_status_transition");
        booksAgree();
    }

    /**
     * FR-APR-04 and FR-PRD-02 (upfront and added fees): below the threshold the disbursement runs at
     * once; the upfront fee posts its own entry and the added fee is spread over the schedule.
     */
    @Test
    void frApr04_belowTheThresholdItExecutesAtOnceWithUpfrontAndAddedFees() {
        TestDatabase.owner()
                .sql("INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?,"
                        + " '{\"approval_thresholds_minor\": {\"loan_disbursement\": 5000000}}')")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        String product = product(
                "FEES", Map.of(), List.of(fee("paid_upfront", 10_000L, null), fee("added_to_loan", 30_001L, null)));
        String loan = approved(product, 1_200_000, 3, 42);

        ResponseEntity<JsonNode> r = requestDisbursement(loan, today, key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("executed").asBoolean()).isTrue();
        assertThat(r.getBody().get("approval_request_id").isNull()).isTrue();
        assertThat(r.getBody().get("loan_status").asString()).isEqualTo("active");

        JsonNode items = get(loan, "/schedule").get("items");
        assertThat(longs(items, "fees_due_minor")).containsExactly(10_000L, 10_000L, 10_001L);
        assertThat(loan(loan).get("balances").get("fees_outstanding_minor").asLong())
                .isEqualTo(30_001);

        JsonNode txns = get(loan, "/transactions").get("items");
        assertThat(txns.findValuesAsString("txn_type")).containsExactlyInAnyOrder("disbursement", "fee_upfront");
        for (JsonNode txn : txns) {
            List<String> l = lines(txn.get("journal_entry_id").asString());
            if (txn.get("txn_type").asString().equals("fee_upfront")) {
                assertThat(txn.get("receipt_no").asString()).isEqualTo("RC-HQ-000001");
                assertThat(l).containsExactly("cash_on_hand:10000:0", "loan_fee_income:0:10000");
            } else {
                assertThat(l).containsExactly("loans_receivable:1200000:0", "cash_on_hand:0:1200000");
            }
        }
        booksAgree();
    }

    // ---- Repayments -----------------------------------------------------------------------

    /**
     * FR-REP-01 to FR-REP-03 with worked example D: 300,000 on item 1's due date pays its interest
     * then 180,000 of principal; the receipt, the journal, the balances and R-DPD follow.
     */
    @Test
    void frRep02_workedExampleD_partialRepaymentAllocatesAndPosts() {
        String loan = approved(exampleB("REPAY"), 1_200_000, 3, 43);
        LocalDate disbursed = today.minusMonths(1).minusDays(10);
        disburse(loan, disbursed);
        LocalDate due1 = disbursed.plusMonths(1);
        assertThat(loan(loan).get("balances").get("days_past_due").asInt())
                .isEqualTo((int) java.time.temporal.ChronoUnit.DAYS.between(due1, today));

        JsonNode result = repaid(loan, 300_000, due1);
        JsonNode txn = result.get("transaction");
        assertThat(txn.get("txn_type").asString()).isEqualTo("repayment");
        assertThat(txn.get("receipt_no").asString()).isEqualTo("RC-HQ-000001");
        assertThat(txn.get("allocations").findValuesAsString("component")).containsExactly("interest", "principal");
        assertThat(longs(txn.get("allocations"), "amount_minor")).containsExactly(120_000L, 180_000L);
        assertThat(lines(txn.get("journal_entry_id").asString()))
                .containsExactly("cash_on_hand:300000:0", "loan_interest_income:0:120000", "loans_receivable:0:180000");

        JsonNode b = result.get("balances");
        assertThat(result.get("loan_status").asString()).isEqualTo("active");
        assertThat(b.get("principal_outstanding_minor").asLong()).isEqualTo(1_020_000);
        assertThat(b.get("interest_outstanding_minor").asLong()).isEqualTo(240_000);
        assertThat(b.get("arrears_minor").asLong()).isEqualTo(220_000);
        assertThat(b.get("total_paid_minor").asLong()).isEqualTo(300_000);
        assertThat(b.get("last_repayment_on").asString()).isEqualTo(due1.toString());
        JsonNode items = get(loan, "/schedule").get("items");
        assertThat(items.get(0).get("status").asString()).isEqualTo("overdue");
        assertThat(items.get(0).get("outstanding_minor").asLong()).isEqualTo(220_000);
        assertThat(items.get(1).get("status").asString()).isEqualTo("pending");
        booksAgree();
    }

    /** FR-REP-01: the value date and amount rules, each refused before anything is written. */
    @Test
    void frRep01_valueDateAndAmountRules() {
        String loan = approved(exampleB("RULES"), 1_200_000, 3, 44);
        LocalDate disbursed = today.minusDays(20);
        disburse(loan, disbursed);

        assertThat(repay(loan, 1_000, today.plusDays(1), key())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("value_date_in_future");
        assertThat(repay(loan, 1_000, disbursed.minusDays(1), key())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("before_disbursement");
        assertThat(repay(loan, 0, today, key()).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        repaid(loan, 1_000, today.minusDays(2));
        assertThat(repay(loan, 1_000, today.minusDays(3), key())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("before_last_repayment");
        assertThat(get(loan, "/transactions").get("items").findValuesAsString("txn_type"))
                .containsExactly("repayment", "disbursement");
    }

    /**
     * FR-REP-04, FR-REP-06, FR-LCL-01: the payoff quote, a payment above it that closes the loan
     * and holds the excess as the member's credit, and a closed loan that takes no more payments.
     */
    @Test
    void frRep04_payoffClosesTheLoanAndHoldsTheExcess() {
        String loan = approved(product("BULLET", false, false), 500_000, 1, 45);
        disburse(loan, today);

        JsonNode quote = get(loan, "/payoff-quote");
        assertThat(quote.get("value_date").asString()).isEqualTo(today.toString());
        assertThat(quote.get("principal_minor").asLong()).isEqualTo(500_000);
        assertThat(quote.get("interest_minor").asLong()).isEqualTo(100_000);
        assertThat(quote.get("total_minor").asLong()).isEqualTo(600_000);

        JsonNode result = repaid(loan, 650_000, today);
        assertThat(result.get("loan_status").asString()).isEqualTo("closed");
        assertThat(result.get("balances").get("credit_balance_minor").asLong()).isEqualTo(50_000);
        assertThat(result.get("balances").get("total_outstanding_minor").asLong())
                .isZero();
        assertThat(result.get("balances").get("closed_on").asString()).isEqualTo(today.toString());
        assertThat(lines(result.get("transaction").get("journal_entry_id").asString()))
                .containsExactly(
                        "cash_on_hand:650000:0",
                        "loan_interest_income:0:100000",
                        "loans_receivable:0:500000",
                        "member_overpayments:0:50000");
        assertThat(get(loan, "/schedule").get("items").get(0).get("status").asString())
                .isEqualTo("paid");
        assertThat(asOfficer(HttpMethod.GET, LOANS + "/" + loan + "/status-history", null, null)
                        .getBody()
                        .get("items")
                        .findValuesAsString("to_status"))
                .endsWith("approved", "active", "closed");

        ResponseEntity<JsonNode> more = repay(loan, 1_000, today, key());
        assertThat(more.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(asOfficer(HttpMethod.GET, LOANS + "/" + loan + "/payoff-quote", null, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        booksAgree();
    }

    // ---- Reversal -------------------------------------------------------------------------

    String requestReversal(String loan, String txnId) {
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/transactions/" + txnId + "/reverse",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of("reason", "Test keying error"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        return r.getBody().get("approval_request_id").asString();
    }

    /**
     * FR-REP-05: reversing the payment that closed a loan reopens it, takes back the credit and
     * posts the mirror of the original entry; a reversed payment is not reversed again.
     */
    @Test
    void frRep05_reversingTheLatestRepaymentMirrorsItAndReopensTheLoan() {
        String loan = approved(product("REOPEN", false, false), 500_000, 1, 46);
        disburse(loan, today);
        JsonNode paid = repaid(loan, 650_000, today).get("transaction");
        String txn = paid.get("id").asString();

        String approval = requestReversal(loan, txn);
        assertThat(loan(loan).get("status").asString())
                .as("no effect before the checker")
                .isEqualTo("closed");
        assertThat(decide(approval, cashier, cashierPerms).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> d = decide(approval, manager, managerPerms);
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);

        JsonNode l = loan(loan);
        assertThat(l.get("status").asString()).isEqualTo("active");
        assertThat(l.get("balances").get("credit_balance_minor").asLong()).isZero();
        assertThat(l.get("balances").get("total_paid_minor").asLong()).isZero();
        assertThat(l.get("balances").get("closed_on").isNull()).isTrue();
        assertThat(l.get("balances").get("principal_outstanding_minor").asLong())
                .isEqualTo(500_000);
        JsonNode reversal = get(loan, "/transactions").get("items").get(0);
        assertThat(reversal.get("txn_type").asString()).isEqualTo("reversal");
        assertThat(reversal.get("reverses_txn_id").asString()).isEqualTo(txn);
        assertThat(reversal.get("reason").asString()).isEqualTo("Test keying error");
        assertThat(lines(reversal.get("journal_entry_id").asString()))
                .containsExactly(
                        "cash_on_hand:0:650000",
                        "loan_interest_income:100000:0",
                        "loans_receivable:500000:0",
                        "member_overpayments:50000:0");
        assertThat(TestDatabase.owner()
                        .sql("SELECT reverses_entry_id FROM journal_entries WHERE id = ?::uuid")
                        .param(reversal.get("journal_entry_id").asString())
                        .query(UUID.class)
                        .single())
                .isEqualTo(UUID.fromString(paid.get("journal_entry_id").asString()));
        assertThat(get(loan, "/schedule").get("items").get(0).get("status").asString())
                .isEqualTo("pending");

        ResponseEntity<JsonNode> again = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/transactions/" + txn + "/reverse",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of("reason", "Test again"));
        assertThat(again.getBody().get("code").asString()).isEqualTo("already_reversed");
        booksAgree();
    }

    /**
     * A secured loan (bullet, 500,000 at 20% for a month, 1x cover) approved by three users, its
     * item pledged at 1,000,000; disbursed and paid off with 650,000, so it closes and its pledge
     * is released. Returns {loan, item, payoff transaction}.
     */
    String[] securedPaidOff(String code, int memberNo) {
        return securedPaidOff(code, memberNo, 1);
    }

    /** As above with {@code items} pledged items; returns {loan, first item, payoff transaction, other items...}. */
    String[] securedPaidOff(String code, int memberNo, int items) {
        String product = product(code, false, true);
        String member = member(
                "Test Borrower %02d".formatted(memberNo), "07000000%02d".formatted(memberNo), t.headOffice(), true);
        List<String> pledged = new ArrayList<>();
        for (int i = 0; i < items; i++) {
            pledged.add(collateralItem(member, "TEST-PLEDGE-%02d-%d".formatted(memberNo, i)));
        }
        String loan = apply(member, product, 500_000, null).getBody().get("id").asString();
        assertThat(asOfficer(
                                HttpMethod.PUT,
                                LOANS + "/" + loan + "/collateral",
                                "\"1\"",
                                Map.of(
                                        "collateral",
                                        pledged.stream()
                                                .map(i -> Map.of("collateral_id", i, "pledged_value_minor", 1_000_000))
                                                .toList()))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(asOfficer(HttpMethod.POST, LOANS + "/" + loan + "/submit", "\"2\"", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/appraisals",
                as(appraiser, officerPerms, "*", "\"3\""),
                Map.of("declared_monthly_income_minor", 2_400_000));
        assertThat(send(
                                HttpMethod.POST,
                                LOANS + "/" + loan + "/decision",
                                as(manager, managerPerms, "*", "\"4\""),
                                Map.of("decision", "approve"))
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("approved");
        disburse(loan, today);
        String txn = repaid(loan, 650_000, today).get("transaction").get("id").asString();
        assertThat(loan(loan).get("status").asString()).isEqualTo("closed");
        assertThat(openPledges(loan)).as("closing released the pledge").isZero();
        List<String> out = new ArrayList<>(List.of(loan, pledged.get(0), txn));
        out.addAll(pledged.subList(1, pledged.size()));
        return out.toArray(String[]::new);
    }

    static long openPledges(String loan) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM lending_loan_collateral WHERE loan_id = ?::uuid AND released_at IS NULL")
                .param(loan)
                .query(Long.class)
                .single();
    }

    static void custody(String item, String status) {
        TestDatabase.owner()
                .sql("UPDATE lending_collateral_items SET custody_status = ? WHERE id = ?::uuid")
                .params(status, item)
                .update();
    }

    /** #176: a bounced payoff reopens the loan with its collateral pledged again. */
    @Test
    void frRep05_aReopenedLoanTakesBackItsPledges() {
        String[] s = securedPaidOff("REPLEDGE", 61);
        ResponseEntity<JsonNode> d = decide(requestReversal(s[0], s[2]), manager, managerPerms);
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);

        assertThat(loan(s[0]).get("status").asString()).isEqualTo("active");
        assertThat(openPledges(s[0])).as("the pledge is back").isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE entity_id = ?::uuid"
                                + " AND action = 'lending.loan.pledges_restored'")
                        .param(s[0])
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // The item is held by the open loan again: a release is refused.
        ResponseEntity<JsonNode> release = send(
                HttpMethod.POST,
                "/api/v1/lending/collateral/" + s[1] + "/release",
                as(officer, officerPerms, "*", "\"1\""),
                Map.of("collected_by", "Test Borrower 61"));
        assertThat(release.getBody().get("code").asString()).isEqualTo("collateral_secures_open_loan");
        booksAgree();
    }

    static String approvalPayload(String approvalId) {
        return TestDatabase.owner()
                .sql("SELECT payload::text FROM approval_requests WHERE id = ?::uuid")
                .param(approvalId)
                .query(String.class)
                .single();
    }

    static long audits(String loan, String action) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE entity_id = ?::uuid AND action = ?")
                .params(loan, action)
                .query(Long.class)
                .single();
    }

    /**
     * #176 (review of #202): an item handed back after payoff, the normal step, never blocks the
     * reversal of a bounced payoff. The checker sees on the request that the loan will reopen
     * without it, and the reopen is recorded as partly unsecured.
     */
    @Test
    void frRep05_aReopenWithoutTheHandedBackItemIsRecordedNotRefused() {
        String[] s = securedPaidOff("GONE", 62);
        custody(s[1], "released");
        String approval = requestReversal(s[0], s[2]);
        assertThat(approvalPayload(approval))
                .contains("collateral_not_restored_on_reopen", "reopen_would_be_unsecured", s[1]);
        ResponseEntity<JsonNode> d = decide(approval, manager, managerPerms);
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(loan(s[0]).get("status").asString()).isEqualTo("active");
        assertThat(openPledges(s[0])).isZero();
        assertThat(audits(s[0], "lending.loan.reopened_partly_unsecured")).isEqualTo(1);
        booksAgree();
    }

    static long reversalsOf(String loan) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM lending_loan_transactions WHERE loan_id = ?::uuid AND txn_type = 'reversal'")
                .param(loan)
                .query(Long.class)
                .single();
    }

    static String approvalStatus(String approvalId) {
        return TestDatabase.owner()
                .sql("SELECT status FROM approval_requests WHERE id = ?::uuid")
                .param(approvalId)
                .query(String.class)
                .single();
    }

    /**
     * #176 (third review of #202): handing an item back after the request does not change the loan,
     * so the request is not stale by version. The run still refuses when the reopen would leave out
     * an item the request did not list: 409 with the full current list, nothing written, and the
     * request ends as stale (audited, with the reason) so the maker can ask again at once.
     */
    @Test
    void frRep05_anItemHandedBackAfterTheRequestEndsTheRequestAndAllowsANewOne() {
        String[] s = securedPaidOff("STALE", 67);
        String approval = requestReversal(s[0], s[2]);
        assertThat(approvalPayload(approval)).doesNotContain("collateral_not_restored_on_reopen");
        custody(s[1], "released");

        ResponseEntity<JsonNode> d = decide(approval, manager, managerPerms);
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(d.getBody().get("code").asString()).isEqualTo("collateral_facts_changed");
        assertThat(d.getBody().get("collateral_not_restored_on_reopen").toString())
                .contains(s[1]);
        assertThat(loan(s[0]).get("status").asString())
                .as("nothing was written")
                .isEqualTo("closed");
        assertThat(reversalsOf(s[0])).isZero();
        assertThat(approvalStatus(approval)).isEqualTo("stale");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE entity_id = ?::uuid AND action = 'core.approval.stale'")
                        .param(approval)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        String again = requestReversal(s[0], s[2]);
        assertThat(approvalPayload(again)).contains("collateral_not_restored_on_reopen", s[1]);
        assertThat(decide(again, manager, managerPerms).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loan(s[0]).get("status").asString()).isEqualTo("active");
        assertThat(audits(s[0], "lending.loan.reopened_partly_unsecured")).isEqualTo(1);
    }

    /**
     * #176 (second review of #202): a request that reopens a loan without its collateral is approved
     * only by someone who may release collateral (lending.collateral.release_approve, seeded for the
     * tenant administrator and the branch manager). The accountant may approve any other reversal
     * but not this one; a loan officer may approve no reversal at all.
     */
    @Test
    void frRep05_anUnsecuredReopenNeedsAnApproverWhoMayReleaseCollateral() {
        String[] s = securedPaidOff("UNSEC", 68);
        custody(s[1], "released");
        String approval = requestReversal(s[0], s[2]);
        assertThat(approvalPayload(approval)).contains("reopen_would_be_unsecured");

        ResponseEntity<JsonNode> officerTry = decide(approval, UUID.randomUUID(), officerPerms);
        assertThat(officerTry.getStatusCode().value()).isIn(403, 404);
        String accountantPerms = permissionsOf("accountant");
        UUID accountant = UUID.randomUUID();
        assertThat(send(
                                HttpMethod.GET,
                                "/api/v1/approvals/" + approval,
                                as(accountant, accountantPerms, "*", null),
                                null)
                        .getBody()
                        .get("can_decide")
                        .asBoolean())
                .as("the accountant is not offered the decision")
                .isFalse();
        assertThat(send(HttpMethod.GET, "/api/v1/approvals/" + approval, as(manager, managerPerms, "*", null), null)
                        .getBody()
                        .get("can_decide")
                        .asBoolean())
                .isTrue();
        ResponseEntity<JsonNode> accountantTry = decide(approval, accountant, accountantPerms);
        assertThat(accountantTry.getStatusCode())
                .as("%s", accountantTry.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(loan(s[0]).get("status").asString()).isEqualTo("closed");
        assertThat(approvalStatus(approval)).isEqualTo("pending");

        assertThat(decide(approval, manager, managerPerms).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loan(s[0]).get("status").asString()).isEqualTo("active");
    }

    /** A reopen that restores every item needs no more than the usual reversal approver. */
    @Test
    void frRep05_aFullyRestoredReopenNeedsOnlyTheUsualApprover() {
        String[] s = securedPaidOff("SECURE", 69);
        String approval = requestReversal(s[0], s[2]);
        assertThat(approvalPayload(approval)).doesNotContain("reopen_would_be_unsecured");
        assertThat(decide(approval, UUID.randomUUID(), permissionsOf("accountant"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(openPledges(s[0])).isEqualTo(1);
    }

    /** #176: an item now securing another loan stays with that loan; the reopened loan goes without it. */
    @Test
    void frRep05_anItemPledgedElsewhereStaysWithTheOtherLoan() {
        String[] s = securedPaidOff("TAKEN", 64);
        String borrower = loan(s[0]).get("member_id").asString();
        String next = apply(borrower, product("TAKEN2", false, false), 500_000, null)
                .getBody()
                .get("id")
                .asString();
        assertThat(asOfficer(
                                HttpMethod.PUT,
                                LOANS + "/" + next + "/collateral",
                                "\"1\"",
                                Map.of(
                                        "collateral",
                                        List.of(Map.of("collateral_id", s[1], "pledged_value_minor", 1_000_000))))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String approval = requestReversal(s[0], s[2]);
        assertThat(decide(approval, manager, managerPerms).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loan(s[0]).get("status").asString()).isEqualTo("active");
        assertThat(openPledges(s[0])).isZero();
        assertThat(openPledges(next)).isEqualTo(1);
    }

    /**
     * #176 (review of #202): a reversal that restores three pledges and a draft that pledges the same
     * three items at the same moment (in the opposite order) take the item locks in one order, so
     * neither is a deadlock (40P01, a 500). Whoever gets the items first keeps them: the other is refused
     * (collateral_already_pledged, or collateral_facts_changed for the reversal), and no item is open on two loans.
     */
    @Test
    void frRep05_aReopeningReversalAndAPledgeOfTheSameItemsNeverDeadlock() throws Exception {
        String[] s = securedPaidOff("RACE", 66, 3);
        List<String> items = new ArrayList<>(List.of(s[1], s[3], s[4]));
        String borrower = loan(s[0]).get("member_id").asString();
        String next = apply(borrower, product("RACE2", false, false), 500_000, null)
                .getBody()
                .get("id")
                .asString();
        List<String> reversed = new ArrayList<>(items);
        java.util.Collections.reverse(reversed);
        String approval = requestReversal(s[0], s[2]);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ResponseEntity<JsonNode>> reversal = pool.submit(() -> {
                start.await();
                return decide(approval, manager, managerPerms);
            });
            Future<ResponseEntity<JsonNode>> pledge = pool.submit(() -> {
                start.await();
                return asOfficer(
                        HttpMethod.PUT,
                        LOANS + "/" + next + "/collateral",
                        "\"1\"",
                        Map.of(
                                "collateral",
                                reversed.stream()
                                        .map(i -> Map.of("collateral_id", i, "pledged_value_minor", 1_000_000))
                                        .toList()));
            });
            start.countDown();
            ResponseEntity<JsonNode> r = reversal.get(30, TimeUnit.SECONDS);
            ResponseEntity<JsonNode> p = pledge.get(30, TimeUnit.SECONDS);
            assertThat(p.getStatusCode().value()).as("%s", p.getBody()).isIn(200, 409);
            if (p.getStatusCode().value() == 200) {
                // The draft got the items first: the run refuses to reopen without items the request did not list.
                assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(409);
                assertThat(r.getBody().toString()).contains("collateral_facts_changed");
                assertThat(loan(s[0]).get("status").asString()).isEqualTo("closed");
            } else {
                assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
                assertThat(loan(s[0]).get("status").asString()).isEqualTo("active");
            }
        }
        for (String item : items) {
            assertThat(TestDatabase.owner()
                            .sql("SELECT count(*) FROM lending_loan_collateral WHERE collateral_id = ?::uuid"
                                    + " AND released_at IS NULL")
                            .param(item)
                            .query(Long.class)
                            .single())
                    .as("item %s is open on exactly one loan", item)
                    .isEqualTo(1);
        }
    }

    /** V42's guard: un-releasing a pledge needs the transaction to name that loan, and only that loan. */
    @Test
    void theGuardRefusesAnUnreleaseTheTransactionDidNotName() throws Exception {
        String[] s = securedPaidOff("GUARD", 65);
        // The loan must be active for the exception to apply at all; reopen it the way a reversal does.
        TestDatabase.owner()
                .sql("UPDATE lending_loans SET status = 'active' WHERE id = ?::uuid")
                .param(s[0])
                .update();
        String unrelease = "UPDATE lending_loan_collateral SET released_at = NULL WHERE loan_id = ?::uuid";
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> TestDatabase.owner().sql(unrelease).param(s[0]).update())
                .as("no setting")
                .hasMessageContaining("only on a draft loan");
        try (java.sql.Connection c = TestDatabase.ownerDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("SELECT set_config('bms.restore_pledges_loan', '" + UUID.randomUUID() + "', true)");
                java.sql.PreparedStatement u = c.prepareStatement(unrelease);
                u.setString(1, s[0]);
                org.assertj.core.api.Assertions.assertThatThrownBy(u::executeUpdate)
                        .as("another loan named")
                        .hasMessageContaining("only on a draft loan");
            }
            c.rollback();
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("SELECT set_config('bms.restore_pledges_loan', '" + s[0] + "', true)");
                java.sql.PreparedStatement u = c.prepareStatement(unrelease);
                u.setString(1, s[0]);
                assertThat(u.executeUpdate()).as("this loan named").isEqualTo(1);
            }
            c.rollback();
        }
    }

    /**
     * FR-REP-05 acceptance: reversing a repayment that is not the latest re-allocates the later one
     * in value date order and posts the net difference.
     */
    @Test
    void frRep05_reversingAnEarlierRepaymentReallocatesTheLaterOneAndPostsTheNetDifference() {
        String loan = approved(exampleB("REALLOC"), 1_200_000, 3, 47);
        LocalDate disbursed = today.minusMonths(2).minusDays(5);
        disburse(loan, disbursed);
        String first = repaid(loan, 300_000, disbursed.plusMonths(1))
                .get("transaction")
                .get("id")
                .asString();
        JsonNode second = repaid(loan, 520_000, disbursed.plusMonths(2)).get("transaction");
        assertThat(longs(second.get("allocations"), "amount_minor")).containsExactly(220_000L, 120_000L, 180_000L);

        decide(requestReversal(loan, first), manager, managerPerms);

        JsonNode reversal = get(loan, "/transactions").get("items").get(0);
        assertThat(reversal.get("txn_type").asString()).isEqualTo("reversal");
        // The first repayment's rows taken back, and the second's moved from item 2 to item 1.
        List<String> rows = new ArrayList<>();
        for (JsonNode a : reversal.get("allocations")) {
            String owner = a.get("applies_to_txn_id").asString().equals(first) ? "first" : "second";
            rows.add(owner + ":" + a.get("item_no").asInt() + ":"
                    + a.get("component").asString() + ":"
                    + a.get("amount_minor").asLong());
        }
        assertThat(rows)
                .containsExactlyInAnyOrder(
                        "first:1:interest:-120000",
                        "first:1:principal:-180000",
                        "second:1:interest:120000",
                        "second:1:principal:180000",
                        "second:2:interest:-120000",
                        "second:2:principal:-180000");
        // Net: interest -120,000 and principal -180,000 against the 300,000 paid back out.
        assertThat(lines(reversal.get("journal_entry_id").asString()))
                .containsExactlyInAnyOrder(
                        "cash_on_hand:0:300000", "loan_interest_income:120000:0", "loans_receivable:180000:0");
        JsonNode items = get(loan, "/schedule").get("items");
        assertThat(items.get(0).get("status").asString()).isEqualTo("paid");
        assertThat(items.get(1).get("total_paid_minor").asLong()).isZero();
        JsonNode b = loan(loan).get("balances");
        assertThat(b.get("principal_outstanding_minor").asLong()).isEqualTo(800_000);
        assertThat(b.get("total_paid_minor").asLong()).isEqualTo(520_000);
        assertThat(b.get("last_repayment_on").asString())
                .isEqualTo(disbursed.plusMonths(2).toString());
        booksAgree();
    }

    // ---- Write-off and recovery -----------------------------------------------------------

    /**
     * FR-LCL-02 and FR-LCL-03: the accountant asks, the tenant admin approves; the principal leaves
     * loans receivable for write-off expense; a later payment is a recovery to bad debt recovered.
     */
    @Test
    void frLcl02_writeOffThenARecovery() {
        String loan = approved(exampleB("WOFF"), 1_200_000, 3, 48);
        disburse(loan, today.minusDays(5));
        repaid(loan, 300_000, today);

        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/write-off",
                withKey(as(accountant, accountantPerms, "*", null), key()),
                Map.of("reason", "Test borrower unreachable"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String approval = r.getBody().get("approval_request_id").asString();
        assertThat(decide(approval, manager, managerPerms).getStatusCode())
                .as("a branch manager does not approve write-offs")
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        assertThat(decide(approval, admin, adminPerms).getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode l = loan(loan);
        assertThat(l.get("status").asString()).isEqualTo("written_off");
        assertThat(l.get("balances").get("written_off_on").asString()).isEqualTo(today.toString());
        assertThat(l.get("balances").get("principal_outstanding_minor").asLong())
                .isZero();
        JsonNode writeOff = get(loan, "/transactions").get("items").get(0);
        assertThat(writeOff.get("txn_type").asString()).isEqualTo("write_off");
        assertThat(writeOff.get("amount_minor").asLong()).isEqualTo(1_020_000);
        assertThat(lines(writeOff.get("journal_entry_id").asString()))
                .containsExactly("loan_write_off_expense:1020000:0", "loans_receivable:0:1020000");
        JsonNode totals = get(loan, "/schedule").get("totals");
        assertThat(totals.get("written_off_minor").asLong()).isEqualTo(1_020_000 + 240_000);
        assertThat(totals.get("outstanding_minor").asLong()).isZero();

        JsonNode recovery = repaid(loan, 50_000, today).get("transaction");
        assertThat(recovery.get("txn_type").asString()).isEqualTo("recovery");
        assertThat(recovery.get("allocations")).isEmpty();
        assertThat(lines(recovery.get("journal_entry_id").asString()))
                .containsExactly("cash_on_hand:50000:0", "bad_debt_recovered:0:50000");
        assertThat(loan(loan).get("status").asString()).isEqualTo("written_off");
        booksAgree();
    }

    // ---- Idempotency and scope ------------------------------------------------------------

    /**
     * Chapter 7 section 7.8: a retry with the same key replays the stored response and records
     * nothing more; the same key with a different body, or no key, is refused.
     */
    @Test
    void idempotency_aRetriedRepaymentIsRecordedOnce() {
        String loan = approved(exampleB("IDEM"), 1_200_000, 3, 49);
        disburse(loan, today);
        String k = key();

        ResponseEntity<JsonNode> first = repay(loan, 100_000, today, k);
        ResponseEntity<JsonNode> retry = repay(loan, 100_000, today, k);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody().get("transaction").get("id").asString())
                .isEqualTo(first.getBody().get("transaction").get("id").asString());
        assertThat(get(loan, "/transactions").get("items").findValuesAsString("txn_type"))
                .containsExactly("repayment", "disbursement");

        assertThat(repay(loan, 100_001, today, k).getBody().get("code").asString())
                .isEqualTo("idempotency_key_reused");
        assertThat(repay(loan, 100_000, today, null).getBody().get("code").asString())
                .isEqualTo("idempotency_key_missing");
        // A disbursement request replays too: one approval request, not two.
        String other = approved(exampleB("IDEM2"), 1_200_000, 3, 50);
        String dk = key();
        ResponseEntity<JsonNode> a = requestDisbursement(other, today, dk);
        ResponseEntity<JsonNode> b = requestDisbursement(other, today, dk);
        assertThat(b.getBody().get("approval_request_id").asString())
                .isEqualTo(a.getBody().get("approval_request_id").asString());
        assertThat(b.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        booksAgree();
    }

    /** Branch scope (ADR-017): a cashier of another branch sees a 404, and the list search finds the loan. */
    @Test
    void branchScopeAndSearch() {
        String loan = approved(exampleB("SCOPE"), 1_200_000, 3, 51);
        disburse(loan, today);
        HttpHeaders elsewhere =
                withKey(as(cashier, cashierPerms, t.secondBranch().toString(), null), key());
        Map<String, Object> body =
                Map.of("amount_minor", 1_000, "value_date", today.toString(), "payment_method_key", "cash");
        assertThat(send(HttpMethod.POST, LOANS + "/" + loan + "/repayments", elsewhere, body)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        String loanNo = loan(loan).get("loan_no").asString();
        JsonNode byNumber =
                asOfficer(HttpMethod.GET, LOANS + "?q=" + loanNo, null, null).getBody();
        assertThat(byNumber.get("items").findValuesAsString("id")).containsExactly(loan);
        JsonNode item = byNumber.get("items").get(0);
        assertThat(item.get("member_name").asString()).isEqualTo("Test Borrower 51");
        assertThat(item.get("total_outstanding_minor").asLong()).isEqualTo(1_560_000);
        assertThat(asOfficer(HttpMethod.GET, LOANS + "?q=borrower 51", null, null)
                        .getBody()
                        .get("items")
                        .findValuesAsString("id"))
                .containsExactly(loan);
        assertThat(asOfficer(HttpMethod.GET, LOANS + "?q=50%25", null, null)
                        .getBody()
                        .get("items"))
                .isEmpty();
    }

    // ---- Documents (FR-DOC-01, ADR-034) ---------------------------------------------------

    /** The fixture's staff as real users, so the signature blocks carry names. */
    void namedStaff() {
        String[][] staff = {
            {cashier.toString(), "Test Cashier"}, {manager.toString(), "Test Manager"}, {admin.toString(), "Test Admin"}
        };
        for (String[] u : staff) {
            TestDatabase.owner()
                    .sql("INSERT INTO users (id, tenant_id, kind, full_name, email, status)"
                            + " VALUES (?::uuid, ?, 'staff', ?, ?, 'active') ON CONFLICT DO NOTHING")
                    .params(u[0], t.tenantId(), u[1], u[1].toLowerCase().replace(' ', '.') + "@example.test")
                    .update();
        }
        TestDatabase.owner()
                .sql("UPDATE branches SET location = 'Plot 1, Test Road, Kampala' WHERE id = ?")
                .param(t.headOffice())
                .update();
        // A fixed name, footer, brand colour and logo, so the golden text is stable and the letterhead is complete.
        String settingsPerms = "core.settings.read,core.settings.manage";
        int version = send(HttpMethod.GET, "/api/v1/settings", as(admin, settingsPerms, "*", null), null)
                .getBody()
                .get("version")
                .asInt();
        assertThat(send(
                                HttpMethod.PATCH,
                                "/api/v1/settings",
                                as(admin, settingsPerms, "*", "\"" + version + "\""),
                                Map.of(
                                        "display_name", "Test Lending Ltd",
                                        "receipt_footer",
                                                "Test Lending Ltd, Plot 1 Test Road. Keep this document safe.",
                                        "theme_primary", "#1E5631"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        org.springframework.util.LinkedMultiValueMap<String, Object> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", new org.springframework.core.io.ByteArrayResource(logoPng()) {
            @Override
            public String getFilename() {
                return "logo.png";
            }
        });
        HttpHeaders h = as(admin, settingsPerms, "*", null);
        h.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        assertThat(http.exchange(
                                "/api/v1/settings/logo",
                                HttpMethod.PUT,
                                new org.springframework.http.HttpEntity<>(form, h),
                                JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** A fabricated logo: a green square with a white bar, 256 by 128 pixels. */
    static byte[] logoPng() {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(256, 128, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(0x1E, 0x56, 0x31));
        g.fillRect(0, 0, 256, 128);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(32, 52, 192, 24);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(img, "png", out);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** The text of a PDF with every printed date replaced, so a golden file does not move with the calendar. */
    static String text(byte[] pdf) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String raw = new org.apache.pdfbox.text.PDFTextStripper().getText(d);
            return raw.replaceAll("\\b\\d{1,2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{4}\\b", "{DATE}")
                    .replaceAll("-\\d{8}\\b", "-{YYYYMMDD}")
                    .replace("\r", "")
                    .strip();
        }
    }

    /** With {@code -Dbms.documents.samples}: the PDF and a PNG of each page, for the visual check (ADR-034 item 8). */
    static void sample(String name, byte[] pdf) throws Exception {
        if (System.getProperty("bms.documents.samples") == null) {
            return;
        }
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
    }

    /**
     * Compares with {@code golden/documents/<name>.txt}. With {@code -Dbms.golden.record=true} it
     * writes the file instead (review it before committing). With {@code -Dbms.documents.samples}
     * it also writes the PDF and a PNG of each page to {@code target/document-samples} for the
     * visual check of the ADR-034 quality bar.
     */
    static void golden(String name, byte[] pdf) throws Exception {
        sample(name, pdf);
        java.nio.file.Path file = java.nio.file.Path.of("src/test/resources/golden/documents/" + name + ".txt");
        String actual = text(pdf) + "\n";
        if (Boolean.getBoolean("bms.golden.record")) {
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, actual);
            return;
        }
        assertThat(actual).as("golden text of %s", name).isEqualTo(java.nio.file.Files.readString(file));
    }

    ResponseEntity<JsonNode> issue(String loan, String txn) {
        return send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/transactions/" + txn + "/document",
                as(cashier, cashierPerms, "*", null),
                null);
    }

    byte[] stored(String documentId) {
        ResponseEntity<JsonNode> url = send(
                HttpMethod.POST,
                "/api/v1/documents/" + documentId + "/download-url",
                as(cashier, cashierPerms, "*", null),
                null);
        assertThat(url.getStatusCode()).as("%s", url.getBody()).isEqualTo(HttpStatus.OK);
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        return http.exchange(
                        java.net.URI.create(
                                http.getRootUri() + url.getBody().get("url").asString()),
                        HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(h),
                        byte[].class)
                .getBody();
    }

    /**
     * FR-DOC-01 and FR-DOC-02: the voucher and the receipt are rendered on first request, stored
     * once and downloaded through a signed URL; their text matches the golden files, with names,
     * amounts in words, the allocation and the signature blocks.
     */
    @Test
    void frDoc01_theVoucherAndReceiptAreIssuedOnce() throws Exception {
        namedStaff();
        String product = product("DOCS", Map.of(), List.of(fee("deducted_at_disbursement", null, 200)));
        String loan = approved(product, 1_200_000, 3, 81);
        disburse(loan, today);
        String receiptTxn =
                repaid(loan, 300_000, today).get("transaction").get("id").asString();
        String disbursement = null;
        for (JsonNode t : get(loan, "/transactions").get("items")) {
            if (t.get("txn_type").asString().equals("disbursement")) {
                disbursement = t.get("id").asString();
            }
        }

        JsonNode voucher = issue(loan, disbursement).getBody();
        assertThat(voucher.get("doc_type").asString()).isEqualTo("voucher");
        assertThat(voucher.get("doc_number").asString()).isEqualTo("VC-HQ-000001");
        assertThat(voucher.get("created").asBoolean()).isTrue();
        JsonNode again = issue(loan, disbursement).getBody();
        assertThat(again.get("created").asBoolean()).as("stored once").isFalse();
        assertThat(again.get("document_id").asString())
                .isEqualTo(voucher.get("document_id").asString());
        byte[] voucherPdf = stored(voucher.get("document_id").asString());
        golden("voucher", voucherPdf);

        JsonNode receipt = issue(loan, receiptTxn).getBody();
        assertThat(receipt.get("doc_number").asString()).isEqualTo("RC-HQ-000001");
        byte[] receiptPdf = stored(receipt.get("document_id").asString());
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(receiptPdf)) {
            assertThat(d.getNumberOfPages()).as("a receipt is one page").isEqualTo(1);
        }
        golden("receipt", receiptPdf);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM documents WHERE tenant_id = ? AND doc_type IN ('receipt', 'voucher')")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    /** FR-DOC-01: the schedule and the statement are rendered as at today, never stored. */
    @Test
    void frDoc01_theScheduleAndStatementAreRenderedAsAtToday() throws Exception {
        namedStaff();
        String loan = approved(exampleB("DOCS2"), 1_200_000, 3, 82);
        disburse(loan, today);
        String paid = repaid(loan, 300_000, today).get("transaction").get("id").asString();
        ResponseEntity<byte[]> schedule = http.exchange(
                LOANS + "/" + loan + "/documents/schedule",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(as(cashier, cashierPerms, "*", null)),
                byte[].class);
        assertThat(schedule.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(schedule.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        golden("schedule", schedule.getBody());
        byte[] statement = http.exchange(
                        LOANS + "/" + loan + "/documents/loan_statement",
                        HttpMethod.POST,
                        new org.springframework.http.HttpEntity<>(as(cashier, cashierPerms, "*", null)),
                        byte[].class)
                .getBody();
        golden("loan_statement", statement);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM documents WHERE tenant_id = ? AND doc_type <> 'upload'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .as("schedules and statements are not stored")
                .isZero();

        // A reversal has no receipt; a loan not yet disbursed has no schedule; another branch sees nothing.
        String approval = requestReversal(loan, paid);
        decide(approval, manager, managerPerms);
        String reversal =
                get(loan, "/transactions").get("items").get(0).get("id").asString();
        assertThat(issue(loan, reversal).getBody().get("code").asString()).isEqualTo("no_document");
        String notYet = approved(exampleB("DOCS3"), 1_200_000, 3, 83);
        assertThat(send(
                                HttpMethod.POST,
                                LOANS + "/" + notYet + "/documents/schedule",
                                as(cashier, cashierPerms, "*", null),
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(send(
                                HttpMethod.POST,
                                LOANS + "/" + loan + "/transactions/" + paid + "/document",
                                as(cashier, cashierPerms, t.secondBranch().toString(), null),
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- Documents, review findings (ADR-034) ---------------------------------------------

    /** The text of one page of a PDF in reading order, dates left as printed. */
    static String pageText(byte[] pdf, int page) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            org.apache.pdfbox.text.PDFTextStripper s = new org.apache.pdfbox.text.PDFTextStripper();
            s.setSortByPosition(true);
            s.setStartPage(page);
            s.setEndPage(page);
            return s.getText(d).replace("\r", "");
        }
    }

    static int pages(byte[] pdf) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return d.getNumberOfPages();
        }
    }

    /** The raster images drawn on a page: the tenant logo, when the tenant has one. */
    static int images(byte[] pdf, int pageIndex) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            org.apache.pdfbox.pdmodel.PDResources res = d.getPage(pageIndex).getResources();
            int n = 0;
            for (org.apache.pdfbox.cos.COSName name : res.getXObjectNames()) {
                if (res.getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                    n++;
                }
            }
            return n;
        }
    }

    ResponseEntity<byte[]> render(String loan, String docType) {
        return http.exchange(
                LOANS + "/" + loan + "/documents/" + docType,
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(as(cashier, cashierPerms, "*", null)),
                byte[].class);
    }

    /** The statement rows: date, then three amounts at the end of the line ("-" for none). */
    record Row(String line, long charged, long credited, long balance) {}

    static List<Row> statementRows(byte[] pdf) throws Exception {
        java.util.regex.Pattern row = java.util.regex.Pattern.compile(
                "^(\\d{1,2} [A-Z][a-z]{2} \\d{4}) .*?(-|[\\d,]+) (-|[\\d,]+) ([\\d,]+)$");
        List<Row> rows = new ArrayList<>();
        for (int p = 1; p <= pages(pdf); p++) {
            for (String line : pageText(pdf, p).split("\n")) {
                java.util.regex.Matcher m = row.matcher(line.strip().replaceAll("\\s+", " "));
                if (m.matches()) {
                    rows.add(new Row(line.strip(), amount(m.group(2)), amount(m.group(3)), amount(m.group(4))));
                }
            }
        }
        return rows;
    }

    static long amount(String s) {
        return s.equals("-") ? 0 : Long.parseLong(s.replace(",", ""));
    }

    static long closing(byte[] pdf) throws Exception {
        for (int p = 1; p <= pages(pdf); p++) {
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("Closing balance ([\\d,]+)").matcher(pageText(pdf, p));
            if (m.find()) {
                return amount(m.group(1));
            }
        }
        throw new AssertionError("no closing balance row");
    }

    /** Charged less credited over every line is the printed closing balance, and every balance follows its line. */
    static void assertReconciles(byte[] pdf, long outstanding) throws Exception {
        List<Row> rows = statementRows(pdf);
        assertThat(rows).isNotEmpty();
        long running = 0;
        for (Row r : rows) {
            running += r.charged() - r.credited();
            assertThat(r.balance()).as(r.line()).isEqualTo(running);
        }
        assertThat(closing(pdf)).as("closing balance").isEqualTo(running).isEqualTo(outstanding);
    }

    /**
     * The review's HIGH finding: "Loan after this payment" is the loan right after that payment, not
     * as it is when the receipt is first opened. The first payment's receipt is opened only after two
     * later payments and still shows the loan as the first payment left it.
     */
    @Test
    void frDoc01_aReceiptFirstOpenedLaterShowsTheLoanAfterThatPayment() throws Exception {
        namedStaff();
        String loan = approved(exampleB("DOCS4"), 1_200_000, 3, 84);
        disburse(loan, today);
        String first = repaid(loan, 300_000, today).get("transaction").get("id").asString();
        String second =
                repaid(loan, 200_000, today).get("transaction").get("id").asString();
        String third = repaid(loan, 100_000, today).get("transaction").get("id").asString();

        // Opened last, first payment: 1,560,000 owed in all, 300,000 paid; not the 960,000 owed now.
        String text =
                text(stored(issue(loan, first).getBody().get("document_id").asString()));
        assertThat(text).contains("Loan after this payment").contains("Outstanding 1,260,000 UGX");
        assertThat(text).doesNotContain("960,000").doesNotContain("as at");
        assertThat(text(stored(issue(loan, third).getBody().get("document_id").asString())))
                .contains("Outstanding 960,000 UGX");
        assertThat(text(stored(issue(loan, second).getBody().get("document_id").asString())))
                .contains("Outstanding 1,060,000 UGX");

        // Opened while it was the latest payment, or a month later, a receipt reads the same.
        String again =
                text(stored(issue(loan, first).getBody().get("document_id").asString()));
        assertThat(again).isEqualTo(text);
        // The logo is on the page (ADR-034).
        assertThat(images(stored(issue(loan, first).getBody().get("document_id").asString()), 0))
                .isEqualTo(1);
    }

    /**
     * The review's second HIGH finding. A repayment reversed before any receipt was requested has none
     * (422); a receipt issued first stays as issued, byte for byte, and the response and the list of
     * the loan's documents show it as reversed.
     */
    @Test
    void frDoc01_aReversedRepaymentHasNoNewReceiptAndAnIssuedOneIsMarkedReversed() {
        namedStaff();
        String loan = approved(exampleB("DOCS5"), 1_200_000, 3, 85);
        disburse(loan, today);
        String receipted =
                repaid(loan, 300_000, today).get("transaction").get("id").asString();
        String unreceipted =
                repaid(loan, 200_000, today).get("transaction").get("id").asString();
        JsonNode issued = issue(loan, receipted).getBody();
        assertThat(issued.get("reversed").asBoolean()).isFalse();
        byte[] before = stored(issued.get("document_id").asString());

        decide(requestReversal(loan, unreceipted), manager, managerPerms);
        decide(requestReversal(loan, receipted), manager, managerPerms);

        ResponseEntity<JsonNode> refused = issue(loan, unreceipted);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("transaction_reversed");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM documents WHERE tenant_id = ? AND doc_type = 'receipt'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .as("no receipt was stored for the reversed payment")
                .isEqualTo(1);

        JsonNode again = issue(loan, receipted).getBody();
        assertThat(again.get("document_id").asString())
                .isEqualTo(issued.get("document_id").asString());
        assertThat(again.get("created").asBoolean()).isFalse();
        assertThat(again.get("reversed").asBoolean()).isTrue();
        assertThat(again.get("reversed_on").asString()).isEqualTo(today.toString());
        assertThat(stored(again.get("document_id").asString()))
                .as("the stored receipt is immutable")
                .isEqualTo(before);

        JsonNode list = send(
                        HttpMethod.GET, LOANS + "/" + loan + "/documents", as(cashier, cashierPerms, "*", null), null)
                .getBody()
                .get("items");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("doc_number").asString())
                .isEqualTo(issued.get("doc_number").asString());
        assertThat(list.get(0).get("reversed").asBoolean()).isTrue();
    }

    /**
     * The review's statement finding: a running balance, interest and fee charges, the net paid out at
     * disbursement, a reversal that re-allocates a later payment, a write-off and a recovery; the lines
     * add up to the closing balance, which is what the loan's own record says is outstanding.
     */
    @Test
    void frDoc01_theStatementReconcilesToTheLoansOutstanding() throws Exception {
        namedStaff();
        String product = product(
                "RECON",
                Map.of(),
                List.of(
                        fee("deducted_at_disbursement", null, 200),
                        fee("added_to_loan", 30_000L, null),
                        fee("paid_upfront", 10_000L, null)));
        String loan = approved(product, 1_200_000, 3, 86);
        disburse(loan, today.minusDays(5));
        String first = repaid(loan, 300_000, today).get("transaction").get("id").asString();
        repaid(loan, 200_000, today);
        decide(requestReversal(loan, first), manager, managerPerms);
        repaid(loan, 150_000, today);

        // 1,200,000 + 360,000 interest + 30,000 fee added, less 200,000 and 150,000 paid; the first payment was
        // reversed.
        long outstanding =
                loan(loan).get("balances").get("total_outstanding_minor").asLong();
        assertThat(outstanding).isEqualTo(1_240_000);
        byte[] open = render(loan, "loan_statement").getBody();
        assertReconciles(open, outstanding);
        String text = pageText(open, 1);
        assertThat(text)
                .contains("Disbursement (gross) 1,200,000")
                .contains("Less Test fee deducted_at_disbursement deducted from the proceeds: 24,000")
                .contains("Net paid to the member: 1,176,000")
                .contains("Interest charged (schedule) 360,000")
                .contains("Fees added (schedule) 30,000")
                .contains("Fee paid before disbursement: 10,000")
                .contains("Reversal of RC-HQ-000002 300,000");
        golden("loan_statement_reconciled", open);

        // Write-off, then a recovery: the account closes at nothing and the recovery is a note.
        ResponseEntity<JsonNode> w = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/write-off",
                withKey(as(accountant, accountantPerms, "*", null), key()),
                Map.of("reason", "Test borrower unreachable"));
        assertThat(decide(w.getBody().get("approval_request_id").asString(), admin, adminPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        repaid(loan, 50_000, today);
        byte[] closed = render(loan, "loan_statement").getBody();
        assertThat(loan(loan).get("balances").get("total_outstanding_minor").asLong())
                .isZero();
        assertReconciles(closed, 0);
        sample("loan_statement_written_off", closed);
        assertThat(pageText(closed, pages(closed)))
                .contains("Written off")
                .contains("Recovery received after the write-off: 50,000");
    }

    /**
     * ADR-034 item 6: a long table repeats its header on every page and the footer carries real page
     * numbers. Seventy payments make a statement of several pages.
     */
    @Test
    void frDoc01_aLongStatementRepeatsItsHeaderAndNumbersItsPages() throws Exception {
        namedStaff();
        String loan = approved(exampleB("DOCS6"), 1_200_000, 3, 87);
        disburse(loan, today.minusDays(3));
        for (int i = 0; i < 70; i++) {
            repaid(loan, 1_000, today);
        }
        byte[] pdf = render(loan, "loan_statement").getBody();
        sample("loan_statement_long", pdf);
        int pages = pages(pdf);
        assertThat(pages).as("a long statement runs onto more pages").isGreaterThanOrEqualTo(2);
        for (int p = 1; p <= pages; p++) {
            String text = pageText(pdf, p);
            assertThat(text)
                    .as("page %d repeats the table header", p)
                    .contains("Date Number Description Charged Credited Balance");
            assertThat(text).as("page %d footer", p).contains("page " + p + " of " + pages);
        }
        assertReconciles(
                pdf, loan(loan).get("balances").get("total_outstanding_minor").asLong());
        // No letterhead is lost or cut off on later pages: the business name is on each.
        for (int p = 1; p <= pages; p++) {
            assertThat(pageText(pdf, p)).contains("Test Lending Ltd");
        }
    }

    /**
     * The review's lock finding, and the question of a unique constraint: the loan's row lock, taken only
     * to record after looking again, is enough. Concurrent first requests all render, one records, and
     * every caller gets the same stored document.
     */
    @Test
    void frDoc02_concurrentFirstRequestsStoreOneReceipt() throws Exception {
        namedStaff();
        String loan = approved(exampleB("DOCS7"), 1_200_000, 3, 88);
        disburse(loan, today);
        String txn = repaid(loan, 300_000, today).get("transaction").get("id").asString();
        int callers = 4;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            calls.add(pool.submit(() -> {
                go.await();
                return issue(loan, txn);
            }));
        }
        go.countDown();
        List<ResponseEntity<JsonNode>> results = new ArrayList<>();
        for (java.util.concurrent.Future<ResponseEntity<JsonNode>> f : calls) {
            results.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(results)
                .allSatisfy(
                        r -> assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK));
        assertThat(results.stream()
                        .map(r -> r.getBody().get("document_id").asString())
                        .distinct())
                .as("everyone gets the same document")
                .hasSize(1);
        assertThat(results.stream()
                        .filter(r -> r.getBody().get("created").asBoolean())
                        .count())
                .as("exactly one request stored it")
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM documents WHERE tenant_id = ? AND doc_type = 'receipt'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /**
     * The fees a voucher shows as deducted are the ones the disbursement posted to the ledger, not what
     * the product says now: with the product's fee changed to 100 percent afterwards (arranged directly,
     * since versions are never edited through the API) the voucher still shows 24,000 off and 1,176,000
     * paid, and does not fail in the amount in words.
     */
    @Test
    void frDoc01_aVouchersDeductedFeesAreTheOnesTheLedgerPosted() throws Exception {
        namedStaff();
        String product = product("DOCS8", Map.of(), List.of(fee("deducted_at_disbursement", null, 200)));
        String loan = approved(product, 1_200_000, 3, 89);
        disburse(loan, today);
        String disbursement = null;
        for (JsonNode t : get(loan, "/transactions").get("items")) {
            if (t.get("txn_type").asString().equals("disbursement")) {
                disbursement = t.get("id").asString();
            }
        }
        TestDatabase.owner()
                .sql("UPDATE lending_loan_product_fees SET rate_bp = 10000 WHERE tenant_id = ?"
                        + " AND timing = 'deducted_at_disbursement'")
                .param(t.tenantId())
                .update();
        ResponseEntity<JsonNode> r = issue(loan, disbursement);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        String text =
                pageText(stored(r.getBody().get("document_id").asString()), 1).replaceAll("\\s+", " ");
        assertThat(text).contains("Fees (deducted) -24,000").contains("Net paid to the member 1,176,000");
    }

    // ---- Documents, second review (ADR-034) -----------------------------------------------

    static void assertNoStore(ResponseEntity<?> r) {
        assertThat(r.getHeaders().getCacheControl()).as("Cache-Control").isEqualTo("no-store");
        assertThat(r.getHeaders().getFirst(HttpHeaders.PRAGMA)).as("Pragma").isEqualTo("no-cache");
    }

    /** A signed link and a member's financial document are never kept by a browser or a proxy. */
    @Test
    void frDoc03_everyDocumentResponseIsNoStore() {
        namedStaff();
        String loan = approved(exampleB("DOCS9"), 1_200_000, 3, 90);
        disburse(loan, today);
        String txn = repaid(loan, 300_000, today).get("transaction").get("id").asString();
        ResponseEntity<JsonNode> issued = issue(loan, txn);
        assertNoStore(issued);
        HttpHeaders staff = as(cashier, cashierPerms, "*", null);
        assertNoStore(send(HttpMethod.GET, LOANS + "/" + loan + "/documents", staff, null));
        assertNoStore(render(loan, "loan_statement"));
        assertNoStore(render(loan, "schedule"));
        String id = issued.getBody().get("document_id").asString();
        assertNoStore(send(HttpMethod.GET, "/api/v1/documents/" + id, staff, null));
        assertNoStore(send(HttpMethod.POST, "/api/v1/documents/" + id + "/download-url", staff, null));
    }

    /**
     * Another branch and another tenant see nothing of a loan's documents: 404 on the loan routes (the
     * loan is out of scope), and on the core document routes 404 across tenants (row level security)
     * and 403 across branches (the owning module's rule, chapter 7 section 7.11.7).
     */
    @Test
    void frDoc03_otherBranchAndOtherTenantAreRefused() {
        namedStaff();
        String loan = approved(exampleB("DOCS10"), 1_200_000, 3, 91);
        disburse(loan, today);
        String txn = repaid(loan, 300_000, today).get("transaction").get("id").asString();
        String id = issue(loan, txn).getBody().get("document_id").asString();

        HttpHeaders otherBranch = as(cashier, cashierPerms, t.secondBranch().toString(), null);
        assertThat(send(HttpMethod.GET, LOANS + "/" + loan + "/documents", otherBranch, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        for (String type : List.of("schedule", "loan_statement")) {
            assertThat(http.exchange(
                                    LOANS + "/" + loan + "/documents/" + type,
                                    HttpMethod.POST,
                                    new org.springframework.http.HttpEntity<>(otherBranch),
                                    JsonNode.class)
                            .getStatusCode())
                    .as(type)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
        assertThat(send(HttpMethod.POST, LOANS + "/" + loan + "/transactions/" + txn + "/document", otherBranch, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.GET, "/api/v1/documents/" + id, otherBranch, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        TestDatabase.Fixture other = TestDatabase.tenant("other", true);
        HttpHeaders stranger = new HttpHeaders();
        stranger.add("X-Tenant", other.slug());
        stranger.add("X-Dev-User-Id", UUID.randomUUID().toString());
        stranger.add("X-Dev-Permissions", cashierPerms);
        stranger.add("X-Dev-Branch-Ids", "*");
        assertThat(send(HttpMethod.GET, LOANS + "/" + loan + "/documents", stranger, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.exchange(
                                LOANS + "/" + loan + "/documents/loan_statement",
                                HttpMethod.POST,
                                new org.springframework.http.HttpEntity<>(stranger),
                                JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.GET, "/api/v1/documents/" + id, stranger, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Characters that break markup, control characters and scripts the font lacks never break or blank a document. */
    @Test
    void frDoc03_awkwardTextIsPrintedAsTypedOrAsAQuestionMark() throws Exception {
        namedStaff();
        String awkward = "Tom & Jerry <Ltd> \"Keep\" it safe";
        String settingsPerms = "core.settings.read,core.settings.manage";
        int version = send(HttpMethod.GET, "/api/v1/settings", as(admin, settingsPerms, "*", null), null)
                .getBody()
                .get("version")
                .asInt();
        assertThat(send(
                                HttpMethod.PATCH,
                                "/api/v1/settings",
                                as(admin, settingsPerms, "*", "\"" + version + "\""),
                                Map.of("receipt_footer", awkward))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        TestDatabase.owner()
                .sql("UPDATE branches SET name = 'Zoë & <Main> Branch' WHERE id = ?")
                .param(t.headOffice())
                .update();
        String loan = approved(exampleB("DOCS12"), 1_200_000, 3, 93);
        TestDatabase.owner()
                .sql("UPDATE lending_members SET full_name = ? WHERE tenant_id = ?")
                .params("Amélie Ωμέγα Привет & <b>Bold</b> \u0001\u0007 日本語 العربية", t.tenantId())
                .update();
        disburse(loan, today);
        String txn = repaid(loan, 300_000, today).get("transaction").get("id").asString();
        byte[] receipt = stored(issue(loan, txn).getBody().get("document_id").asString());
        String text = pageText(receipt, 1).replaceAll("\\s+", " ");
        assertThat(text).contains(awkward).contains("Zoë & <Main> Branch");
        assertThat(text)
                .contains("Amélie Ωμέγα Привет & <b>Bold</b>")
                .doesNotContain("\u0001")
                .doesNotContain("日")
                .contains("???");
        byte[] statement = render(loan, "loan_statement").getBody();
        assertThat(pageText(statement, 1)).contains("Amélie Ωμέγα Привет & <b>Bold</b>");
    }

    /** When the checker's user record is gone the voucher says so, not "no checker needed". */
    @Test
    void frDoc01_aVoucherNamesAFormerUserAsTheApprover() throws Exception {
        namedStaff();
        String product = product("DOCS13", Map.of(), List.of(fee("deducted_at_disbursement", null, 200)));
        String loan = approved(product, 1_200_000, 3, 94);
        disburse(loan, today);
        String disbursement = null;
        for (JsonNode t : get(loan, "/transactions").get("items")) {
            if (t.get("txn_type").asString().equals("disbursement")) {
                disbursement = t.get("id").asString();
            }
        }
        TestDatabase.owner()
                .sql("DELETE FROM users WHERE id = ?")
                .param(manager)
                .update();
        String text = pageText(
                        stored(issue(loan, disbursement)
                                .getBody()
                                .get("document_id")
                                .asString()),
                        1)
                .replaceAll("\\s+", " ");
        assertThat(text).contains("Approved by a former user").doesNotContain("no checker needed");
    }
}
