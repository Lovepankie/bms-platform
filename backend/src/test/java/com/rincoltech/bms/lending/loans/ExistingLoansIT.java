package com.rincoltech.bms.lending.loans;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.loans.internal.ArrearsTestRun;
import com.rincoltech.bms.testsupport.Api;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Existing loans brought in from a paper register on real PostgreSQL (issue #466, ADR-071; FR-EXL-01 to FR-EXL-07): the
 * schedule from the real past date, the past repayments applied in date order by the product's allocation order, the
 * position (balance, next due date, days overdue from the real due dates), the checker's approval before it counts,
 * historic transactions and one opening entry at go-live, no penalty for the time before the bring-in, the go-live
 * guard, the CSV check and commit, and isolation between businesses. An administrator records (the maker), a branch
 * manager approves (the checker). All figures and people fabricated.
 */
class ExistingLoansIT extends LoanFixtures {

    static final String EXISTING = "/api/v1/lending/existing-loans";

    @Autowired
    BusinessClock clock;

    @Autowired
    ApplicationContext context;

    final UUID admin = UUID.randomUUID();
    final UUID manager = UUID.randomUUID();
    LocalDate today;

    @BeforeEach
    void dates() {
        today = clock.today(BusinessClock.DEFAULT_ZONE);
    }

    // ---- Fixtures -------------------------------------------------------------------------

    /** 1000 bp per month flat, monthly, three months by default, principal paid first; with these overrides. */
    String product(String code, Map<String, Object> overrides) {
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
        terms.put("allocation_order", List.of("principal", "interest", "fee", "penalty"));
        terms.putAll(overrides);
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                "/api/v1/lending/loan-products",
                as(UUID.randomUUID(), adminPerms, "*", null),
                Map.of("code", code, "name", "Test " + code, "terms", terms));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    String borrower(int n) {
        return member("Test Borrower %02d".formatted(n), "07000000%02d".formatted(n), t.headOffice(), true);
    }

    UUID owner;

    /** The Owner of the business, made on first use: only the Owner sets the go-live date (ADR-062, ADR-071). */
    UUID owner() {
        if (owner == null) {
            owner = Api.staff(t, Api.email("exl-owner"), new Api.Role("tenant_admin", null));
            TestDatabase.owner()
                    .sql("UPDATE users SET is_owner = true WHERE id = ?")
                    .param(owner)
                    .update();
        }
        return owner;
    }

    /** The go-live date set by the Owner in the settings. */
    ResponseEntity<JsonNode> setGoLive(LocalDate date) {
        return setGoLive(owner(), date);
    }

    ResponseEntity<JsonNode> setGoLive(UUID by, LocalDate date) {
        HttpHeaders h = as(by, adminPerms, "*", null);
        String version =
                send(HttpMethod.GET, "/api/v1/settings", h, null).getHeaders().getETag();
        return send(
                HttpMethod.PATCH, "/api/v1/settings", as(by, adminPerms, "*", version), Map.of("go_live_date", date));
    }

    static Map<String, Object> repayment(LocalDate date, long amount, String method, String note) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("value_date", date.toString());
        r.put("amount_minor", amount);
        r.put("payment_method_key", method);
        r.put("note", note);
        return r;
    }

    Map<String, Object> request(
            String member, String product, long principal, LocalDate disbursed, List<Map<String, Object>> repayments) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("member_id", member);
        body.put("product_id", product);
        body.put("principal_minor", principal);
        body.put("disbursement_date", disbursed.toString());
        body.put("repayments", repayments);
        return body;
    }

    ResponseEntity<JsonNode> check(Map<String, Object> body) {
        return send(HttpMethod.POST, EXISTING + "?dry_run=true", as(admin, adminPerms, "*", null), body);
    }

    ResponseEntity<JsonNode> record(Map<String, Object> body) {
        HttpHeaders h = as(admin, adminPerms, "*", null);
        h.add("Idempotency-Key", "test-" + UUID.randomUUID());
        return send(HttpMethod.POST, EXISTING, h, body);
    }

    JsonNode recorded(Map<String, Object> body) {
        ResponseEntity<JsonNode> r = record(body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    ResponseEntity<JsonNode> decide(String approvalId, UUID user, String perms) {
        return send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approvalId + "/approve",
                as(user, perms, "*", null),
                Map.of("note", "Checked against the register"));
    }

    void approve(JsonNode recorded) {
        ResponseEntity<JsonNode> d = decide(recorded.get("approval_request_id").asString(), manager, managerPerms);
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);
    }

    JsonNode loan(String id) {
        return send(HttpMethod.GET, LOANS + "/" + id, as(admin, adminPerms, "*", null), null)
                .getBody();
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    static List<String> errorCodes(ResponseEntity<JsonNode> r) {
        List<String> codes = new ArrayList<>();
        r.getBody()
                .get("errors")
                .forEach(e -> codes.add(
                        e.get("field").asString() + ":" + e.get("code").asString()));
        return codes;
    }

    static List<String> systemLines(String entryId) {
        return TestDatabase.owner().sql("""
                        SELECT a.system_key || ':' || l.debit || ':' || l.credit FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.entry_id = ?::uuid ORDER BY l.line_no
                        """).param(entryId).query(String.class).list();
    }

    long receivableOf(String loanId) {
        return TestDatabase.owner().sql("""
                        SELECT coalesce(sum(j.debit - j.credit), 0) FROM journal_lines j
                          JOIN gl_accounts a ON a.id = j.account_id AND a.system_key = 'loans_receivable'
                         WHERE j.subledger_type = 'lending.loan' AND j.subledger_id = ?::uuid
                        """).param(loanId).query(Long.class).single();
    }

    long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    // ---- The schedule, the repayments and the position (FR-EXL-02) ----------------------------

    /**
     * Disbursed three months and twenty days ago: 1,200,000 at 1000 bp a month flat over three months, so each item is
     * 400,000 principal and 120,000 interest, due one, two and three months after the real date. Two repayments are
     * entered out of date order; they are applied in date order, principal first as the product says: the first pays
     * item 1, the second 300,000 of item 2's principal. On today's date items 2 and 3 are overdue, days past due count
     * from item 2's real due date, and item 2 is next due.
     */
    @Test
    void frExl02_theScheduleRunsFromThePastDateAndTheRepaymentsApplyInDateOrderByTheProductsOrder() {
        String product = product("EXL1", Map.of());
        String member = borrower(1);
        LocalDate disbursed = today.minusMonths(3).minusDays(20);
        assertThat(setGoLive(today.minusDays(10)).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = request(
                member,
                product,
                1_200_000,
                disbursed,
                List.of(
                        repayment(disbursed.plusMonths(1).plusDays(5), 300_000, "mtn_momo", "Test receipt 02"),
                        repayment(disbursed.plusDays(10), 520_000, "cash", "Test receipt 01")));

        ResponseEntity<JsonNode> checked = check(body);
        assertThat(checked.getStatusCode()).as("%s", checked.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode preview = checked.getBody();
        assertThat(preview.get("loan_id").isNull()).isTrue();
        JsonNode schedule = preview.get("schedule");
        assertThat(schedule).hasSize(3);
        for (int k = 0; k < 3; k++) {
            assertThat(schedule.get(k).get("due_date").asString())
                    .isEqualTo(disbursed.plusMonths(k + 1).toString());
            assertThat(schedule.get(k).get("principal_due_minor").asLong()).isEqualTo(400_000);
            assertThat(schedule.get(k).get("interest_due_minor").asLong()).isEqualTo(120_000);
        }
        assertThat(schedule.get(0).get("status").asString()).isEqualTo("paid");
        assertThat(schedule.get(1).get("paid_minor").asLong()).isEqualTo(300_000);
        assertThat(schedule.get(1).get("status").asString()).isEqualTo("overdue");
        JsonNode position = preview.get("position");
        assertThat(position.get("principal_outstanding_minor").asLong())
                .as("principal first: 400,000 of item 1 and 300,000 of item 2 are paid")
                .isEqualTo(500_000);
        assertThat(position.get("interest_outstanding_minor").asLong()).isEqualTo(240_000);
        assertThat(position.get("total_outstanding_minor").asLong()).isEqualTo(740_000);
        assertThat(position.get("arrears_minor").asLong()).isEqualTo(740_000);
        assertThat(position.get("days_past_due").asInt())
                .isEqualTo((int) ChronoUnit.DAYS.between(disbursed.plusMonths(2), today));
        assertThat(position.get("next_due_date").asString())
                .isEqualTo(disbursed.plusMonths(2).toString());
        assertThat(position.get("total_paid_minor").asLong()).isEqualTo(820_000);
        assertThat(count("SELECT count(*) FROM lending_loans WHERE tenant_id = ?", t.tenantId()))
                .as("a check saves nothing")
                .isZero();

        JsonNode loan = recorded(body);
        String id = loan.get("loan_id").asString();
        assertThat(loan.get("status").asString()).isEqualTo("brought_in");
        assertThat(loan.get("position").get("principal_outstanding_minor").asLong())
                .isEqualTo(500_000);
        approve(loan);

        JsonNode after = loan(id);
        assertThat(after.get("status").asString()).isEqualTo("active");
        JsonNode b = after.get("balances");
        assertThat(b.get("disbursed_on").asString()).isEqualTo(disbursed.toString());
        assertThat(b.get("principal_outstanding_minor").asLong()).isEqualTo(500_000);
        assertThat(b.get("interest_outstanding_minor").asLong()).isEqualTo(240_000);
        assertThat(b.get("days_past_due").asInt())
                .isEqualTo(position.get("days_past_due").asInt());
        assertThat(b.get("next_due_date").asString())
                .isEqualTo(disbursed.plusMonths(2).toString());
        assertThat(b.get("total_paid_minor").asLong()).isEqualTo(820_000);
        assertThat(b.get("last_repayment_on").asString())
                .isEqualTo(disbursed.plusMonths(1).plusDays(5).toString());

        // Historic: no journal, no receipt number, in value date order; the allocation rows are the plan's.
        assertThat(TestDatabase.owner().sql("""
                                SELECT txn_type || ':' || amount_minor || ':' || is_historic || ':'
                                       || (journal_entry_id IS NULL) || ':' || (receipt_no IS NULL)
                                  FROM lending_loan_transactions WHERE loan_id = ?::uuid ORDER BY value_date
                                """).param(id).query(String.class).list())
                .containsExactly(
                        "disbursement:1200000:true:true:true",
                        "repayment:520000:true:true:true",
                        "repayment:300000:true:true:true");
        assertThat(TestDatabase.owner().sql("""
                                SELECT s.item_no || ':' || a.component || ':' || a.amount_minor
                                  FROM lending_repayment_allocations a
                                  JOIN lending_loan_transactions t ON t.id = a.transaction_id
                                  JOIN lending_schedule_items s ON s.id = a.schedule_item_id
                                 WHERE t.loan_id = ?::uuid AND t.amount_minor = 300000
                                """).param(id).query(String.class).list())
                .containsExactly("2:principal:300000");

        // One opening entry dated at go-live for the principal still owed; no cash account moves.
        String entry = TestDatabase.owner()
                .sql("SELECT opening_entry_id::text FROM lending_loans WHERE id = ?::uuid")
                .param(id)
                .query(String.class)
                .single();
        assertThat(systemLines(entry)).containsExactly("loans_receivable:500000:0", "opening_balance_equity:0:500000");
        assertThat(TestDatabase.owner()
                        .sql("SELECT entry_date FROM journal_entries WHERE id = ?::uuid")
                        .param(entry)
                        .query(LocalDate.class)
                        .single())
                .isEqualTo(today.minusDays(10));
        assertThat(count(
                        "SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_module = 'lending'",
                        t.tenantId()))
                .as("only the opening entry")
                .isEqualTo(1);
        assertThat(receivableOf(id)).isEqualTo(500_000);
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE entity_id = ?::uuid AND action IN"
                                + " ('lending.loan.brought_in_recorded', 'lending.loan.brought_in')",
                        id))
                .isEqualTo(2);

        // After go-live the loan is serviced as usual: a repayment today posts its cash and the subledger follows.
        HttpHeaders h = as(admin, adminPerms, "*", null);
        h.add("Idempotency-Key", "test-" + UUID.randomUUID());
        ResponseEntity<JsonNode> paid = send(
                HttpMethod.POST,
                LOANS + "/" + id + "/repayments",
                h,
                Map.of("amount_minor", 100_000, "value_date", today.toString(), "payment_method_key", "cash"));
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(systemLines(paid.getBody()
                        .get("transaction")
                        .get("journal_entry_id")
                        .asString()))
                .containsExactly("cash_on_hand:100000:0", "loans_receivable:0:100000");
        assertThat(receivableOf(id)).isEqualTo(400_000);

        // A repayment from the register is not reversed: it moved no cash in the books.
        String historic = TestDatabase.owner()
                .sql("SELECT id::text FROM lending_loan_transactions WHERE loan_id = ?::uuid AND amount_minor = 520000")
                .param(id)
                .query(String.class)
                .single();
        HttpHeaders r = as(admin, adminPerms, "*", null);
        r.add("Idempotency-Key", "test-" + UUID.randomUUID());
        ResponseEntity<JsonNode> reversal = send(
                HttpMethod.POST,
                LOANS + "/" + id + "/transactions/" + historic + "/reverse",
                r,
                Map.of("reason", "Test reversal"));
        assertThat(reversal.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(reversal)).isEqualTo("historic_not_reversible");

        // Its receipt is the paper one; the loan statement shows the whole history.
        ResponseEntity<JsonNode> receipt = send(
                HttpMethod.POST,
                LOANS + "/" + id + "/transactions/" + historic + "/document",
                as(admin, adminPerms, "*", null),
                null);
        assertThat(code(receipt)).isEqualTo("no_document");
        ResponseEntity<byte[]> statement = http.exchange(
                LOANS + "/" + id + "/documents/loan_statement",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(as(admin, adminPerms, "*", null)),
                byte[].class);
        assertThat(statement.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statement.getBody()).isNotEmpty();
    }

    /** The register gives the total to repay instead of a rate: the difference is spread as flat interest, no fee. */
    @Test
    void frExl02_anAgreedTotalIsSpreadAsFlatInterest() {
        String product = product("EXL2", Map.of());
        assertThat(setGoLive(today.minusDays(1)).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = request(borrower(2), product, 1_000_000, today.minusDays(40), List.of());
        body.put("agreed_total_minor", 1_300_000);
        ResponseEntity<JsonNode> checked = check(body);
        assertThat(checked.getStatusCode()).as("%s", checked.getBody()).isEqualTo(HttpStatus.OK);
        List<String> rows = new ArrayList<>();
        checked.getBody()
                .get("schedule")
                .forEach(i -> rows.add(i.get("principal_due_minor").asLong() + "+"
                        + i.get("interest_due_minor").asLong() + "+"
                        + i.get("fees_due_minor").asLong()));
        assertThat(rows).containsExactly("333333+100000+0", "333333+100000+0", "333334+100000+0");
        body.put("agreed_total_minor", 900_000);
        ResponseEntity<JsonNode> below = check(body);
        assertThat(errorCodes(below)).containsExactly("agreed_total_minor:agreed_total_below_principal");
    }

    /** Repaid in full on the register: closed on approval, on the day of its last repayment, with no opening entry. */
    @Test
    void frExl05_aLoanRepaidInFullComesInClosedWithNoOpeningEntry() {
        String product = product("EXL3", Map.of("default_term_count", 1));
        assertThat(setGoLive(today.minusDays(1)).getStatusCode()).isEqualTo(HttpStatus.OK);
        LocalDate disbursed = today.minusMonths(2);
        JsonNode loan = recorded(request(
                borrower(3),
                product,
                500_000,
                disbursed,
                List.of(repayment(disbursed.plusMonths(1), 550_000, "bank", null))));
        assertThat(loan.get("position").get("settled").asBoolean()).isTrue();
        approve(loan);
        String id = loan.get("loan_id").asString();
        JsonNode after = loan(id);
        assertThat(after.get("status").asString()).isEqualTo("closed");
        assertThat(after.get("balances").get("closed_on").asString())
                .isEqualTo(disbursed.plusMonths(1).toString());
        assertThat(count("SELECT count(*) FROM lending_loans WHERE id = ?::uuid AND opening_entry_id IS NULL", id))
                .isEqualTo(1);
        assertThat(receivableOf(id)).isZero();

        // Paying more than was owed is refused: the register is checked again rather than a credit invented.
        ResponseEntity<JsonNode> over = check(request(
                borrower(4),
                product,
                500_000,
                disbursed,
                List.of(
                        repayment(disbursed.plusDays(5), 300_000, "cash", null),
                        repayment(disbursed.plusDays(9), 300_000, "cash", null))));
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(errorCodes(over)).containsExactly("repayments[1].amount_minor:repayments_exceed_balance");
    }

    // ---- Maker-checker (FR-EXL-04) --------------------------------------------------------

    /**
     * Until a second person approves, the loan counts nowhere: no schedule, no transaction, no journal, no balance, not on
     * the arrears run. The maker cannot approve it; a rejection leaves it waiting, the maker may ask again, and a loan
     * withdrawn ends cancelled.
     */
    @Test
    void frExl04_nothingCountsUntilASecondPersonApproves() {
        String product = product("EXL4", Map.of());
        assertThat(setGoLive(today.minusDays(5)).getStatusCode()).isEqualTo(HttpStatus.OK);
        LocalDate disbursed = today.minusMonths(2).minusDays(10);
        JsonNode loan = recorded(request(
                borrower(5),
                product,
                900_000,
                disbursed,
                List.of(repayment(disbursed.plusMonths(1), 100_000, "cash", null))));
        String id = loan.get("loan_id").asString();
        String approval = loan.get("approval_request_id").asString();
        ArrearsTestRun.run(context, t.tenantId());

        JsonNode waiting = loan(id);
        assertThat(waiting.get("status").asString()).isEqualTo("brought_in");
        assertThat(waiting.get("balances").get("principal_outstanding_minor").asLong())
                .isZero();
        assertThat(waiting.get("balances").get("days_past_due").asInt()).isZero();
        assertThat(count("SELECT count(*) FROM lending_schedule_items WHERE loan_id = ?::uuid", id))
                .isZero();
        assertThat(count("SELECT count(*) FROM lending_loan_transactions WHERE loan_id = ?::uuid", id))
                .isZero();
        assertThat(count("SELECT count(*) FROM journal_lines WHERE subledger_id = ?::uuid", id))
                .isZero();
        assertThat(count("SELECT count(*) FROM lending_loan_charges WHERE loan_id = ?::uuid", id))
                .isZero();
        JsonNode active = send(HttpMethod.GET, LOANS + "?status=active", as(admin, adminPerms, "*", null), null)
                .getBody();
        assertThat(active.get("items").toString()).doesNotContain(id);

        ResponseEntity<JsonNode> self = decide(approval, admin, adminPerms);
        assertThat(self.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(code(self)).isEqualTo("self_approval_forbidden");
        assertThat(loan(id).get("status").asString()).isEqualTo("brought_in");

        ResponseEntity<JsonNode> rejected = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/reject",
                as(manager, managerPerms, "*", null),
                Map.of("note", "The register shows a different amount"));
        assertThat(rejected.getStatusCode()).as("%s", rejected.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(loan(id).get("status").asString()).isEqualTo("brought_in");
        ResponseEntity<JsonNode> again =
                send(HttpMethod.POST, EXISTING + "/" + id + "/approval", as(admin, adminPerms, "*", null), null);
        assertThat(again.getStatusCode()).as("%s", again.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String second = again.getBody().get("approval_request_id").asString();
        assertThat(second).isNotEqualTo(approval);

        ResponseEntity<JsonNode> cancelled = send(
                HttpMethod.POST,
                EXISTING + "/" + id + "/cancel",
                as(admin, adminPerms, "*", null),
                Map.of("reason", "Recorded on the wrong member"));
        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status").asString()).isEqualTo("cancelled");
        ResponseEntity<JsonNode> late = decide(second, manager, managerPerms);
        assertThat(late.getStatusCode().is2xxSuccessful())
                .as("the withdrawn loan's request is stale")
                .isFalse();
        assertThat(count("SELECT count(*) FROM lending_loan_transactions WHERE loan_id = ?::uuid", id))
                .isZero();

        // A loan officer neither records nor sees the route: the permission is the administrators' and managers'.
        ResponseEntity<JsonNode> officer = send(
                HttpMethod.POST,
                EXISTING + "?dry_run=true",
                as(UUID.randomUUID(), officerPerms, "*", null),
                request(borrower(6), product, 900_000, disbursed, List.of()));
        assertThat(officer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- No retroactive penalties (FR-EXL-05) ---------------------------------------------

    /**
     * A loan overdue for months is brought in on a product that charges 2,000 a day after the due date. The arrears run
     * charges no period that starts before the bring-in date (today): none at all on the bring-in day, while days past
     * due count from the real due date. Once the loan has been in the app for three days, the three days after the
     * bring-in are charged and none on or before it (ADR-071 decision 6).
     */
    @Test
    void frExl05_noPenaltyForTheTimeBeforeTheBringInButPenaltiesAfterIt() {
        Map<String, Object> penalty = new LinkedHashMap<>();
        penalty.put("penalty_method", "flat_per_period");
        penalty.put("penalty_period_unit", "day");
        penalty.put("penalty_grace_days", 0);
        penalty.put("penalty_flat_minor", 2_000);
        String product = product("EXL5", penalty);
        assertThat(setGoLive(today.minusDays(20)).getStatusCode()).isEqualTo(HttpStatus.OK);
        LocalDate disbursed = today.minusMonths(4);
        JsonNode loan = recorded(request(borrower(7), product, 1_200_000, disbursed, List.of()));
        approve(loan);
        String id = loan.get("loan_id").asString();
        assertThat(TestDatabase.owner()
                        .sql("SELECT brought_in_on FROM lending_loans WHERE id = ?::uuid")
                        .param(id)
                        .query(LocalDate.class)
                        .single())
                .as("the later of go-live and the approval's date")
                .isEqualTo(today);
        // The business has run arrears since long ago, so only the bring-in date holds the penalties back.
        TestDatabase.owner().sql("""
                        INSERT INTO lending_arrears_runs (tenant_id, business_date, started_at, lease_until, finished_at)
                        VALUES (?, DATE '2000-01-01', now(), now(), now()) ON CONFLICT DO NOTHING
                        """).param(t.tenantId()).update();

        ArrearsTestRun.run(context, t.tenantId());
        assertThat(count("SELECT count(*) FROM lending_loan_charges WHERE loan_id = ?::uuid", id))
                .as("items 1, 2 and 3 are overdue, but today's period started yesterday, before the bring-in")
                .isZero();
        JsonNode b = loan(id).get("balances");
        assertThat(b.get("days_past_due").asInt())
                .as("from the real due date of item 1")
                .isEqualTo((int) ChronoUnit.DAYS.between(disbursed.plusMonths(1), today));
        assertThat(b.get("penalties_outstanding_minor").asLong()).isZero();

        // Three days later in the app: the loan has been in since today minus three, so the days after it are charged.
        TestDatabase.owner()
                .sql("UPDATE lending_loans SET brought_in_on = ?, created_at = created_at - interval '3 days'"
                        + " WHERE id = ?::uuid")
                .params(today.minusDays(3), UUID.fromString(id))
                .update();
        TestDatabase.owner()
                .sql("DELETE FROM lending_arrears_runs WHERE tenant_id = ? AND business_date > DATE '2000-01-01'")
                .param(t.tenantId())
                .update();
        ArrearsTestRun.run(context, t.tenantId());
        List<LocalDate> charged = TestDatabase.owner()
                .sql("SELECT DISTINCT business_date FROM lending_loan_charges WHERE loan_id = ?::uuid ORDER BY 1")
                .param(id)
                .query(LocalDate.class)
                .list();
        assertThat(charged).containsExactly(today.minusDays(2), today.minusDays(1), today);
        assertThat(loan(id).get("balances").get("penalties_outstanding_minor").asLong())
                .isEqualTo(3 * 3 * 2_000);
    }

    // ---- The go-live guard (FR-EXL-03) ----------------------------------------------------

    /**
     * Without a go-live date nothing comes in. The first administrator sign-in sets the default; the Owner sets it in
     * the settings, never in the future, and the operator sets it
     * for a business. A disbursement or a repayment on or after it is refused, so the route cannot backdate new lending.
     */
    @Test
    void frExl03_theGoLiveDateGuardsAgainstBackdatingNewLending() {
        String product = product("EXL6", Map.of());
        String member = borrower(8);
        Map<String, Object> early = request(member, product, 900_000, today.minusDays(40), List.of());
        ResponseEntity<JsonNode> none = check(early);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(none)).isEqualTo("go_live_not_set");
        assertThat(send(HttpMethod.GET, EXISTING + "/go-live", as(admin, adminPerms, "*", null), null)
                        .getBody()
                        .get("source")
                        .asString())
                .isEqualTo("none");

        // The first sign-in of an administrator is the default.
        String email = Api.email("exl-admin");
        UUID firstAdmin = Api.staff(t, email, new Api.Role("tenant_admin", null));
        Api.tenant(http, t.slug()).signIn(firstAdmin, email, Api.PASSWORD, null);
        TestDatabase.owner()
                .sql("UPDATE auth_sessions SET created_at = created_at - interval '30 days' WHERE user_id = ?")
                .param(firstAdmin)
                .update();
        JsonNode byDefault = send(HttpMethod.GET, EXISTING + "/go-live", as(admin, adminPerms, "*", null), null)
                .getBody();
        assertThat(byDefault.get("source").asString()).isEqualTo("first_admin_sign_in");
        LocalDate firstDay = LocalDate.parse(byDefault.get("go_live_date").asString());
        assertThat(firstDay)
                .isEqualTo(Instant.now()
                        .minus(30, ChronoUnit.DAYS)
                        .atZone(BusinessClock.DEFAULT_ZONE)
                        .toLocalDate());
        assertThat(check(early).getStatusCode()).as("disbursed before it").isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> onGoLive = check(request(member, product, 900_000, firstDay, List.of()));
        assertThat(errorCodes(onGoLive)).containsExactly("disbursement_date:disbursement_not_before_go_live");
        ResponseEntity<JsonNode> recent = check(request(member, product, 900_000, today.minusDays(2), List.of()));
        assertThat(errorCodes(recent)).containsExactly("disbursement_date:disbursement_not_before_go_live");

        // The settings: never in the future; a repayment on the go-live date belongs to the app's own books.
        ResponseEntity<JsonNode> future = setGoLive(today.plusDays(1));
        assertThat(future.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(errorCodes(future)).containsExactly("go_live_date:go_live_in_future");
        assertThat(setGoLive(today.minusDays(50)).getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode set = send(HttpMethod.GET, EXISTING + "/go-live", as(admin, adminPerms, "*", null), null)
                .getBody();
        assertThat(set.get("source").asString()).isEqualTo("set");
        assertThat(errorCodes(check(early))).containsExactly("disbursement_date:disbursement_not_before_go_live");
        ResponseEntity<JsonNode> lateRepayment = check(request(
                member,
                product,
                900_000,
                today.minusDays(80),
                List.of(repayment(today.minusDays(50), 10_000, "cash", null))));
        assertThat(errorCodes(lateRepayment)).containsExactly("repayments[0].value_date:repayment_not_before_go_live");

        // The operator sets it for the business, with a reason, audited in both logs.
        Api platform = Api.platform(http);
        String operatorEmail = Api.email("exl-operator");
        UUID operatorId = Api.platformUser(operatorEmail);
        Api.Session operator = platform.signIn(operatorId, operatorEmail, Api.PASSWORD, null);
        ResponseEntity<JsonNode> byOperator = platform.call(
                HttpMethod.PUT,
                "/api/v1/platform/tenants/" + t.tenantId() + "/go-live",
                Map.of("go_live_date", today.minusDays(30).toString(), "reason", "Pilot moved off paper on this day"),
                operator.accessToken());
        assertThat(byOperator.getStatusCode()).as("%s", byOperator.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(send(HttpMethod.GET, EXISTING + "/go-live", as(admin, adminPerms, "*", null), null)
                        .getBody()
                        .get("go_live_date")
                        .asString())
                .isEqualTo(today.minusDays(30).toString());
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.settings.go_live_set'",
                        t.tenantId()))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM platform_audit_log WHERE tenant_id = ? AND action = 'platform.tenant.go_live_set'",
                        t.tenantId()))
                .isEqualTo(1);

        // Nobody but the Owner moves the date (ADR-062).
        ResponseEntity<JsonNode> notOwner = setGoLive(firstAdmin, today.minusDays(60));
        assertThat(notOwner.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(notOwner)).isEqualTo("owner_only");
    }

    /**
     * A business with no Owner: no administrator sets the go-live date, not even the only one; the platform operator
     * does, with a reason (ADR-071).
     */
    @Test
    void frExl03_withNoOwnerOnlyTheOperatorSetsTheGoLiveDate() {
        String email = Api.email("exl-lone-admin");
        UUID lone = Api.staff(t, email, new Api.Role("tenant_admin", null));
        ResponseEntity<JsonNode> refused = setGoLive(lone, today.minusDays(5));
        assertThat(refused.getStatusCode()).as("%s", refused.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(code(refused)).isEqualTo("owner_only");
        assertThat(count(
                        "SELECT count(*) FROM tenant_settings WHERE tenant_id = ? AND settings->>'go_live_date' IS NOT NULL",
                        t.tenantId()))
                .isZero();
    }

    // ---- The CSV import (FR-EXL-06) -------------------------------------------------------

    String memberNo(String memberId) {
        return TestDatabase.owner()
                .sql("SELECT member_no FROM lending_members WHERE id = ?::uuid")
                .param(memberId)
                .query(String.class)
                .single();
    }

    ResponseEntity<JsonNode> importCsv(String csv, boolean dryRun) {
        return send(
                HttpMethod.POST,
                EXISTING + "/import?dry_run=" + dryRun,
                as(admin, adminPerms, "*", null),
                Map.of("csv", csv));
    }

    /**
     * A check reports every row: an unknown member, a date that is not year-month-day, a repayment with no loan row, a
     * repayment after go-live. A commit of a file with errors records nothing; the corrected file is checked clean,
     * committed (each loan waiting for its checker), and committed again it skips every loan.
     */
    @Test
    void frExl06_theCsvImportChecksThenCommits() {
        product("EXL7", Map.of());
        String one = memberNo(borrower(9));
        String two = memberNo(borrower(10));
        assertThat(setGoLive(today.minusDays(10)).getStatusCode()).isEqualTo(HttpStatus.OK);
        LocalDate d1 = today.minusMonths(2);
        LocalDate d2 = today.minusMonths(1).minusDays(5);
        String header = "kind,ref,member_no,product_code,principal,disbursement_date,term,agreed_total,purpose,"
                + "repayment_date,amount,method,note\n";
        String bad = header
                + "loan,REG-001," + one + ",EXL7,\"1,200,000\"," + d1 + ",3,,business,,,,\n"
                + "repayment,REG-001,,,,,,,," + d1.plusMonths(1) + ",520000,cash,Test receipt 11\n"
                + "loan,REG-002,M999999,EXL7,800000," + d2 + ",,,,,,,\n"
                + "loan,REG-003," + two + ",EXL7,800000,31/01/2026,,,,,,,\n"
                + "repayment,REG-009,,,,,,,," + d1.plusDays(3) + ",1000,cash,\n"
                + "repayment,REG-001,,,,,,,," + today + ",1000,cash,\n";
        ResponseEntity<JsonNode> checked = importCsv(bad, true);
        assertThat(checked.getStatusCode()).as("%s", checked.getBody()).isEqualTo(HttpStatus.OK);
        Map<Integer, String> outcomes = new LinkedHashMap<>();
        checked.getBody()
                .get("rows")
                .forEach(r -> outcomes.put(
                        r.get("line").asInt(),
                        r.get("outcome").asString() + ":" + r.get("message").asString("")));
        assertThat(outcomes.get(2)).startsWith("error:A repayment row of this loan has an error");
        assertThat(outcomes.get(4)).startsWith("error:No member with this member number");
        assertThat(outcomes.get(5)).startsWith("error:The disbursement date must be year-month-day");
        assertThat(outcomes.get(6)).startsWith("error:No loan row with this ref");
        assertThat(outcomes.get(7)).startsWith("error:A repayment on or after the go-live date");
        assertThat(checked.getBody().get("errors").asInt()).isEqualTo(5);
        ResponseEntity<JsonNode> refused = importCsv(bad, false);
        assertThat(refused.getBody().get("added").asInt()).isZero();
        assertThat(count("SELECT count(*) FROM lending_loans WHERE tenant_id = ?", t.tenantId()))
                .as("a commit with errors records nothing")
                .isZero();

        String good = header
                + "loan,REG-001," + one + ",EXL7,\"1,200,000\"," + d1 + ",3,,business,,,,\n"
                + "repayment,REG-001,,,,,,,," + d1.plusMonths(1) + ",520000,cash,Test receipt 11\n"
                + "loan,REG-002," + two + ",EXL7,800000," + d2 + ",,1000000,,,,,\n";
        ResponseEntity<JsonNode> clean = importCsv(good, true);
        assertThat(clean.getBody().get("errors").asInt())
                .as("%s", clean.getBody())
                .isZero();
        assertThat(clean.getBody().get("rows").get(0).get("outcome").asString()).isEqualTo("ready");
        assertThat(clean.getBody().get("added").asInt())
                .as("the loans a commit would record")
                .isEqualTo(2);
        ResponseEntity<JsonNode> committed = importCsv(good, false);
        assertThat(committed.getBody().get("added").asInt())
                .as("%s", committed.getBody())
                .isEqualTo(2);
        assertThat(count(
                        "SELECT count(*) FROM lending_loans WHERE tenant_id = ? AND status = 'brought_in'"
                                + " AND channel = 'import'",
                        t.tenantId()))
                .isEqualTo(2);
        assertThat(count(
                        "SELECT count(*) FROM approval_requests WHERE tenant_id = ? AND action_type = 'loan_bring_in'"
                                + " AND status = 'pending'",
                        t.tenantId()))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM lending_brought_in_repayments WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(1);
        ResponseEntity<JsonNode> twice = importCsv(good, false);
        assertThat(twice.getBody().get("added").asInt()).isZero();
        assertThat(twice.getBody().get("skipped").asInt()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM lending_loans WHERE tenant_id = ?", t.tenantId()))
                .isEqualTo(2);
    }

    // ---- Isolation (NFR-ISO-02) -----------------------------------------------------------

    /** Another business sees neither the loan nor its repayments, and its import cannot skip on this one's numbers. */
    @Test
    void anotherBusinessNeverSeesTheBroughtInLoan() {
        String product = product("EXL8", Map.of());
        assertThat(setGoLive(today.minusDays(3)).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = request(
                borrower(11),
                product,
                700_000,
                today.minusDays(20),
                List.of(repayment(today.minusDays(10), 50_000, "cash", "Test receipt 21")));
        body.put("register_ref", "REG-100");
        String id = recorded(body).get("loan_id").asString();

        TestDatabase.Fixture other = TestDatabase.tenant("exl-b", true);
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", other.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", adminPerms);
        h.add("X-Dev-Branch-Ids", "*");
        assertThat(send(HttpMethod.GET, EXISTING + "/" + id, h, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.GET, EXISTING, h, null).getBody().get("items"))
                .isEmpty();
        assertThat(send(HttpMethod.GET, EXISTING + "/" + id, as(admin, adminPerms, "*", null), null)
                        .getBody()
                        .get("repayments"))
                .hasSize(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM lending_brought_in_repayments WHERE tenant_id = ?")
                        .param(other.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
