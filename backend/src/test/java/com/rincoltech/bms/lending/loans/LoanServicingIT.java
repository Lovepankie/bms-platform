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
}
