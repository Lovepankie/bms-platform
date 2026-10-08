package com.rincoltech.bms.lending.investments;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.investments.InvestmentServicing.DailyRun;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Increment 10 on real PostgreSQL (#152): products, opening, funding with a checker and below the
 * threshold, the schedule, the daily job (accrual, due returns, maturity, rollover, reminders) and
 * its idempotency, return and maturity payouts, early withdrawal, reversal, branch scope,
 * idempotency keys and concurrency. Every journal is checked line by line against chapter 6
 * section 6.6.3, and after each test the trial balance balances and both investment liabilities
 * agree with the investments, one by one. All figures fabricated.
 */
class InvestmentsIT extends LoanFixtures {

    static final String INV = "/api/v1/lending/investments";
    static final String PRODUCTS = "/api/v1/lending/investment-products";

    @Autowired
    BusinessClock clock;

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
    LocalDate today;
    int members;

    @BeforeEach
    void users() {
        cashierPerms = permissionsOf("cashier");
        accountantPerms = permissionsOf("accountant");
        today = clock.today(BusinessClock.DEFAULT_ZONE);
    }

    // ---- Fixtures -------------------------------------------------------------------------

    String product(String code, Map<String, Object> overrides) {
        ResponseEntity<JsonNode> r = createProduct(code, overrides);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    ResponseEntity<JsonNode> createProduct(String code, Map<String, Object> overrides) {
        return send(
                HttpMethod.POST,
                PRODUCTS,
                as(UUID.randomUUID(), adminPerms, "*", null),
                Map.of("code", code, "name", "Test " + code, "terms", terms(overrides)));
    }

    static Map<String, Object> terms(Map<String, Object> overrides) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("product_type", "fixed_term");
        t.put("allowed_terms_months", List.of(3, 6, 12));
        t.put("return_rate_bp", 1200);
        t.put("return_method", "flat");
        t.put("payout_frequency", "monthly");
        t.put("min_amount_minor", 100_000);
        t.put("max_amount_minor", 50_000_000);
        t.put("early_withdrawal_allowed", false);
        t.putAll(overrides);
        return t;
    }

    String investor() {
        members++;
        return member(
                "Test Investor %02d".formatted(members), "07000000%02d".formatted(50 + members), t.headOffice(), true);
    }

    ResponseEntity<JsonNode> openAs(UUID user, String perms, String member, String product, long amount, int term) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("member_id", member);
        body.put("product_id", product);
        body.put("amount_minor", amount);
        body.put("term_months", term);
        return send(HttpMethod.POST, INV, as(user, perms, "*", null), body);
    }

    String open(String product, long amount, int term) {
        ResponseEntity<JsonNode> r = openAs(officer, officerPerms, investor(), product, amount, term);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("status").asString()).isEqualTo("pending_funding");
        assertThat(r.getBody().get("account_no").asString()).matches("IV\\d{6}");
        return r.getBody().get("id").asString();
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

    ResponseEntity<JsonNode> requestFunding(String id, LocalDate date, String key) {
        return send(
                HttpMethod.POST,
                INV + "/" + id + "/funding",
                withKey(as(cashier, cashierPerms, "*", null), key),
                Map.of("value_date", date.toString(), "payment_method_key", "cash", "external_reference", "TEST-FUND"));
    }

    ResponseEntity<JsonNode> decide(String approvalId, UUID user, String perms) {
        return send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approvalId + "/approve",
                as(user, perms, "*", null),
                Map.of("note", "Checked"));
    }

    /** Requested by the cashier and approved by the manager. */
    void fund(String id, LocalDate date) {
        ResponseEntity<JsonNode> r = requestFunding(id, date, key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<JsonNode> d =
                decide(r.getBody().get("approval_request_id").asString(), manager, managerPerms);
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);
    }

    DailyRun runDaily(LocalDate date) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tenantJobs.callAsTenant(t.slug(), "lending", () -> tx.execute(s -> servicing.runDaily(date)));
    }

    JsonNode inv(String id) {
        ResponseEntity<JsonNode> r = asOfficer(HttpMethod.GET, INV + "/" + id, null, null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode get(String path) {
        ResponseEntity<JsonNode> r = asOfficer(HttpMethod.GET, path, null, null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    ResponseEntity<JsonNode> pay(String id, String route, String key) {
        return send(
                HttpMethod.POST,
                INV + "/" + id + "/" + route,
                withKey(as(cashier, cashierPerms, "*", null), key),
                Map.of("payment_method_key", "cash"));
    }

    /** The lines of a journal entry as {@code system_key:debit:credit}, in line order. */
    static List<String> lines(String entryId) {
        return TestDatabase.owner().sql("""
                        SELECT a.system_key || ':' || l.debit || ':' || l.credit FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.entry_id = ?::uuid ORDER BY l.line_no
                        """).param(entryId).query(String.class).list();
    }

    String entryOf(String id, String txnType) {
        return TestDatabase.owner()
                .sql("""
                        SELECT journal_entry_id::text FROM lending_investment_transactions
                         WHERE investment_id = ?::uuid AND txn_type = ? ORDER BY created_at DESC LIMIT 1
                        """)
                .params(id, txnType)
                .query(String.class)
                .single();
    }

    long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    /**
     * The trial balance balances, and for every investment investments payable equals the principal
     * held and returns payable equals accrued less paid.
     */
    void booksAgree() {
        assertThat(count(
                        "SELECT coalesce(sum(debit), 0) - coalesce(sum(credit), 0) FROM journal_lines WHERE tenant_id = ?",
                        t.tenantId()))
                .isZero();
        List<String> mismatches = TestDatabase.owner()
                .sql("""
                        SELECT i.account_no || ': ' || i.principal_held_minor || '/' || (i.return_accrued_minor
                               - i.return_paid_minor) || ' vs ' || coalesce(p.bal, 0) || '/' || coalesce(r.bal, 0)
                          FROM lending_investments i
                          LEFT JOIN LATERAL (SELECT sum(j.credit - j.debit) AS bal FROM journal_lines j
                                JOIN gl_accounts a ON a.id = j.account_id AND a.system_key = 'investments_payable'
                               WHERE j.subledger_id = i.id) p ON true
                          LEFT JOIN LATERAL (SELECT sum(j.credit - j.debit) AS bal FROM journal_lines j
                                JOIN gl_accounts a ON a.id = j.account_id AND a.system_key = 'investment_returns_payable'
                               WHERE j.subledger_id = i.id) r ON true
                         WHERE i.tenant_id = ?
                           AND (i.principal_held_minor <> coalesce(p.bal, 0)
                                OR i.return_accrued_minor - i.return_paid_minor <> coalesce(r.bal, 0))
                        """)
                .param(t.tenantId())
                .query(String.class)
                .list();
        assertThat(mismatches)
                .as("investment subledgers against the two liabilities")
                .isEmpty();
    }

    // ---- Products (FR-INV-01, FR-INV-08) --------------------------------------------------

    @Test
    void frInv01_productsAreValidatedEditedAndArchived() {
        ResponseEntity<JsonNode> bad =
                createProduct("BAD1", Map.of("return_method", "compound", "payout_frequency", "monthly"));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(bad.getBody().toString()).contains("compounding_needs_maturity_payout");
        ResponseEntity<JsonNode> rule = createProduct(
                "BAD2", Map.of("early_withdrawal_allowed", true, "early_withdrawal_rule", "reduced_rate"));
        assertThat(rule.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(rule.getBody().toString()).contains("early_withdrawal_rate_bp");

        String id = product("FD12", Map.of());
        assertThat(createProduct("FD12", Map.of()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<JsonNode> noAdmin = send(
                HttpMethod.POST,
                PRODUCTS,
                as(officer, officerPerms, "*", null),
                Map.of("code", "X1", "name", "Test", "terms", terms(Map.of())));
        assertThat(noAdmin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> edited = send(
                HttpMethod.PUT,
                PRODUCTS + "/" + id,
                as(UUID.randomUUID(), adminPerms, "*", "\"1\""),
                Map.of("name", "Test fixed deposit", "terms", terms(Map.of("return_rate_bp", 1400))));
        assertThat(edited.getStatusCode()).as("%s", edited.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody().get("return_rate_bp").asInt()).isEqualTo(1400);
        ResponseEntity<JsonNode> stale = send(
                HttpMethod.PUT,
                PRODUCTS + "/" + id,
                as(UUID.randomUUID(), adminPerms, "*", "\"1\""),
                Map.of("name", "Test", "terms", terms(Map.of())));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<JsonNode> preview = send(
                HttpMethod.POST,
                PRODUCTS + "/return-preview",
                as(officer, officerPerms, "*", null),
                Map.of("product_id", id, "amount_minor", 1_000_000, "term_months", 6, "start_date", "2027-01-31"));
        assertThat(preview.getStatusCode()).as("%s", preview.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(preview.getBody().get("agreed_return_minor").asLong()).isEqualTo(70_000);
        assertThat(preview.getBody().get("maturity_date").asString()).isEqualTo("2027-07-31");
        assertThat(preview.getBody().get("periods").get(0).get("period_end").asString())
                .isEqualTo("2027-02-28");

        ResponseEntity<JsonNode> archived = send(
                HttpMethod.POST,
                PRODUCTS + "/" + id + "/archive",
                as(UUID.randomUUID(), adminPerms, "*", "\"2\""),
                null);
        assertThat(archived.getBody().get("status").asString()).isEqualTo("archived");
        ResponseEntity<JsonNode> refused = openAs(officer, officerPerms, investor(), id, 1_000_000, 6);
        assertThat(refused.getBody().toString()).contains("product_archived");
    }

    @Test
    void frInv02_openingChecksTermAmountAndScope() {
        String p = product("FD01", Map.of());
        String member = investor();
        assertThat(openAs(officer, officerPerms, member, p, 1_000_000, 9)
                        .getBody()
                        .toString())
                .contains("term_not_offered");
        assertThat(openAs(officer, officerPerms, member, p, 50_000, 6).getBody().toString())
                .contains("amount_out_of_range");
        // A loan officer of the second branch cannot open for a head office member.
        ResponseEntity<JsonNode> scoped = send(
                HttpMethod.POST,
                INV,
                as(UUID.randomUUID(), officerPerms, t.secondBranch().toString(), null),
                Map.of("member_id", member, "product_id", p, "amount_minor", 1_000_000, "term_months", 6));
        assertThat(scoped.getBody().toString()).contains("member_not_found");
        ResponseEntity<JsonNode> opened = openAs(officer, officerPerms, member, p, 1_000_000, 6);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = opened.getBody().get("id").asString();
        // Nothing posts before funding.
        assertThat(count("SELECT count(*) FROM lending_investment_transactions WHERE investment_id = ?::uuid", id))
                .isZero();
        JsonNode list = get(INV + "?member_id=" + member + "&status=pending_funding");
        assertThat(list.get("items").size()).isEqualTo(1);
    }

    // ---- Funding, accrual, payout (FR-INV-03, FR-INV-04, FR-INV-09) ------------------------

    @Test
    void frInv03_frInv04_fundingWithACheckerThenMonthlyAccrualAndReturnPayout() {
        String p = product("FD02", Map.of());
        String id = open(p, 1_000_000, 6);
        LocalDate start = today.minusMonths(3).minusDays(1);

        ResponseEntity<JsonNode> requested = requestFunding(id, start, key());
        assertThat(requested.getStatusCode()).as("%s", requested.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String approval = requested.getBody().get("approval_request_id").asString();
        assertThat(inv(id).get("pending_approval_id").asString()).isEqualTo(approval);
        // The maker never checks, and a cashier holds no fund_approve.
        assertThat(decide(approval, cashier, cashierPerms + ",core.approvals.read,lending.investments.fund_approve")
                        .getBody()
                        .toString())
                .contains("self_approval_forbidden");
        assertThat(decide(approval, UUID.randomUUID(), cashierPerms + ",core.approvals.read")
                        .getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        assertThat(decide(approval, accountant, accountantPerms).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode funded = inv(id);
        assertThat(funded.get("status").asString()).isEqualTo("active");
        assertThat(funded.get("start_date").asString()).isEqualTo(start.toString());
        assertThat(funded.get("maturity_date").asString())
                .isEqualTo(start.plusMonths(6).toString());
        assertThat(funded.get("agreed_return_minor").asLong()).isEqualTo(60_000);
        assertThat(funded.get("certificate_no").asString()).matches("IC\\d{6}");
        assertThat(lines(entryOf(id, "funding")))
                .containsExactly("cash_on_hand:1000000:0", "investments_payable:0:1000000");

        JsonNode schedule = get(INV + "/" + id + "/schedule");
        assertThat(schedule.get("periods").size()).isEqualTo(6);
        assertThat(schedule.get("total_return_minor").asLong()).isEqualTo(60_000);

        // Three months have ended: three accruals, each made due by the monthly payout.
        DailyRun first = runDaily(today);
        assertThat(first.accruals()).isEqualTo(3);
        assertThat(runDaily(today).accruals())
                .as("a second run changes nothing")
                .isZero();
        JsonNode accrued = inv(id);
        assertThat(accrued.get("return_accrued_minor").asLong()).isEqualTo(30_000);
        assertThat(accrued.get("return_available_minor").asLong()).isEqualTo(30_000);
        assertThat(lines(entryOf(id, "return_accrual")))
                .containsExactly("investment_return_expense:10000:0", "investment_returns_payable:0:10000");
        assertThat(count(
                        "SELECT count(*) FROM journal_entries WHERE source_type = 'investment_transaction' AND"
                                + " source_id IN (SELECT id FROM lending_investment_transactions WHERE"
                                + " investment_id = ?::uuid AND txn_type = 'return_accrual')",
                        id))
                .isEqualTo(3);

        String k = key();
        ResponseEntity<JsonNode> paid = pay(id, "return-payouts", k);
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(paid.getBody().get("transaction").get("receipt_no").asString())
                .startsWith("VC-");
        assertThat(lines(entryOf(id, "return_payout")))
                .containsExactly("investment_returns_payable:30000:0", "cash_on_hand:0:30000");
        ResponseEntity<JsonNode> replay = pay(id, "return-payouts", k);
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(pay(id, "return-payouts", key()).getBody().toString()).contains("no_return_due");
        assertThat(inv(id).get("return_paid_minor").asLong()).isEqualTo(30_000);

        JsonNode statement = get(INV + "/" + id + "/statement");
        assertThat(statement.get("lines").size()).isEqualTo(5);
        assertThat(statement.get("principal_balance_minor").asLong()).isEqualTo(1_000_000);
        assertThat(statement.get("return_payable_minor").asLong()).isZero();
        JsonNode certificate = get(INV + "/" + id + "/certificate");
        assertThat(certificate.get("maturity_value_minor").asLong()).isEqualTo(1_060_000);
        booksAgree();
    }

    @Test
    void frApr04_fundingBelowTheThresholdExecutesAtOnce() {
        TestDatabase.owner()
                .sql("INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?,"
                        + " '{\"approval_thresholds_minor\": {\"investment_funding\": 5000000}}')")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        String p = product("FD03", Map.of());
        String small = open(p, 1_000_000, 3);
        ResponseEntity<JsonNode> r = requestFunding(small, today, key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("investment_status").asString()).isEqualTo("active");
        String large = open(p, 6_000_000, 3);
        assertThat(requestFunding(large, today, key()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(requestFunding(small, today, null).getBody().toString()).contains("idempotency_key_missing");
        assertThat(requestFunding(large, today.plusDays(1), key()).getBody().toString())
                .contains("value_date_in_future");
        booksAgree();
    }

    // ---- Maturity (FR-INV-05, FR-INV-07, FR-INV-08) ---------------------------------------

    @Test
    void frInv05_maturityPayoutAndItsReversal() {
        String p = product("FD04", Map.of("payout_frequency", "at_maturity"));
        String id = open(p, 2_000_000, 3);
        fund(id, today.minusMonths(3));
        DailyRun run = runDaily(today);
        assertThat(run.accruals()).isEqualTo(3);
        assertThat(run.matured()).isEqualTo(1);
        JsonNode matured = inv(id);
        assertThat(matured.get("status").asString()).isEqualTo("matured");
        assertThat(matured.get("return_available_minor").asLong()).isEqualTo(60_000);

        ResponseEntity<JsonNode> out = pay(id, "payout", key());
        assertThat(out.getStatusCode()).as("%s", out.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(out.getBody().get("investment").get("status").asString()).isEqualTo("paid_out");
        assertThat(lines(entryOf(id, "maturity_payout")))
                .containsExactly(
                        "investments_payable:2000000:0",
                        "investment_returns_payable:60000:0",
                        "cash_on_hand:0:2060000");
        booksAgree();

        String txn = out.getBody().get("transaction").get("id").asString();
        ResponseEntity<JsonNode> reversal = send(
                HttpMethod.POST,
                INV + "/" + id + "/transactions/" + txn + "/reverse",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of("reason", "Test paid to the wrong member"));
        assertThat(reversal.getStatusCode()).as("%s", reversal.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(decide(reversal.getBody().get("approval_request_id").asString(), manager, managerPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode back = inv(id);
        assertThat(back.get("status").asString()).isEqualTo("matured");
        assertThat(back.get("principal_held_minor").asLong()).isEqualTo(2_000_000);
        assertThat(back.get("return_available_minor").asLong()).isEqualTo(60_000);
        assertThat(lines(entryOf(id, "reversal")))
                .containsExactlyInAnyOrder(
                        "investments_payable:0:2000000",
                        "investment_returns_payable:0:60000",
                        "cash_on_hand:2060000:0");
        // An accrual is never reversed by staff.
        String accrual = TestDatabase.owner()
                .sql("SELECT id::text FROM lending_investment_transactions WHERE investment_id = ?::uuid"
                        + " AND txn_type = 'return_accrual' LIMIT 1")
                .param(id)
                .query(String.class)
                .single();
        assertThat(send(
                                HttpMethod.POST,
                                INV + "/" + id + "/transactions/" + accrual + "/reverse",
                                withKey(as(cashier, cashierPerms, "*", null), key()),
                                Map.of("reason", "Test"))
                        .getBody()
                        .toString())
                .contains("not_reversible");
        booksAgree();
    }

    /** FR-INV-08: a recurring product renews principal and return with no instruction, compounding. */
    @Test
    void frInv08_recurringRollsOverAutomaticallyOnTheCurrentTerms() {
        String p = product(
                "RC01",
                Map.of("product_type", "recurring", "return_method", "compound", "payout_frequency", "at_maturity"));
        String id = open(p, 1_000_000, 3);
        fund(id, today.minusMonths(3));
        // The rate changes before maturity: the rollover takes the new one.
        send(
                HttpMethod.PUT,
                PRODUCTS + "/" + p,
                as(UUID.randomUUID(), adminPerms, "*", "\"1\""),
                Map.of(
                        "name",
                        "Test recurring",
                        "terms",
                        terms(Map.of(
                                "product_type", "recurring",
                                "return_method", "compound",
                                "payout_frequency", "at_maturity",
                                "return_rate_bp", 2400))));
        DailyRun run = runDaily(today);
        assertThat(run.rolledOver()).isEqualTo(1);
        JsonNode old = inv(id);
        assertThat(old.get("status").asString()).isEqualTo("rolled_over");
        assertThat(old.get("return_accrued_minor").asLong()).isEqualTo(30_301);
        String nextId = old.get("rolled_over_to_id").asString();
        JsonNode next = inv(nextId);
        assertThat(next.get("status").asString()).isEqualTo("active");
        assertThat(next.get("principal_minor").asLong()).isEqualTo(1_030_301);
        assertThat(next.get("return_rate_bp").asInt()).isEqualTo(2400);
        assertThat(next.get("start_date").asString())
                .isEqualTo(old.get("maturity_date").asString());
        assertThat(next.get("rolled_over_from_id").asString()).isEqualTo(id);
        assertThat(lines(entryOf(id, "rollover_out")))
                .containsExactly(
                        "investments_payable:1000000:0",
                        "investment_returns_payable:30301:0",
                        "investments_payable:0:1030301");
        assertThat(runDaily(today).rolledOver()).isZero();
        booksAgree();
    }

    @Test
    void frInv05_rolloverOfPrincipalLeavesTheReturnToPayAndAPayoutInstructionWaits() {
        String p = product("FD05", Map.of("payout_frequency", "at_maturity"));
        String rolled = open(p, 1_000_000, 3);
        String waits = open(p, 1_000_000, 3);
        assertThat(send(
                                HttpMethod.PUT,
                                INV + "/" + rolled + "/maturity-instruction",
                                as(officer, officerPerms, "*", null),
                                Map.of("instruction", "rollover_principal"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        fund(rolled, today.minusMonths(3));
        fund(waits, today.minusMonths(3));
        runDaily(today);
        JsonNode old = inv(rolled);
        assertThat(old.get("status").asString()).isEqualTo("rolled_over");
        assertThat(old.get("return_available_minor").asLong()).isEqualTo(30_000);
        assertThat(inv(old.get("rolled_over_to_id").asString())
                        .get("principal_minor")
                        .asLong())
                .isEqualTo(1_000_000);
        assertThat(pay(rolled, "return-payouts", key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(inv(waits).get("status").asString()).isEqualTo("matured");
        // Staff roll the waiting one over by hand.
        ResponseEntity<JsonNode> manual = send(
                HttpMethod.POST,
                INV + "/" + waits + "/rollover",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of("mode", "rollover_all"));
        assertThat(manual.getStatusCode()).as("%s", manual.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(manual.getBody().get("next").get("principal_minor").asLong()).isEqualTo(1_030_000);
        booksAgree();
    }

    /** FR-INV-07 and FR-INV-05: one reminder before maturity, one after 7 days with no instruction, each once. */
    @Test
    void frInv07_maturityRemindersAreRecordedOnce() {
        String p = product("FD06", Map.of("payout_frequency", "at_maturity"));
        String soon = open(p, 1_000_000, 3);
        fund(soon, today.minusMonths(3).plusDays(5));
        String late = open(p, 1_000_000, 3);
        fund(late, today.minusMonths(3).minusDays(8));
        DailyRun run = runDaily(today);
        assertThat(run.reminders()).isEqualTo(2);
        assertThat(inv(soon).get("pre_maturity_reminded_on").asString()).isEqualTo(today.toString());
        assertThat(inv(late).get("post_maturity_reminded_on").asString()).isEqualTo(today.toString());
        assertThat(runDaily(today).reminders()).isZero();
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'lending.investment.maturity_reminder'",
                        t.tenantId()))
                .isEqualTo(2);
    }

    // ---- Early withdrawal (FR-INV-06) -----------------------------------------------------

    @Test
    void frInv06_forfeitClawsBackPaidReturnsAndTakesThePenalty() {
        String p = product(
                "EW01",
                Map.of(
                        "early_withdrawal_allowed",
                        true,
                        "early_withdrawal_rule",
                        "forfeit_return",
                        "early_withdrawal_penalty_bp",
                        200));
        String id = open(p, 1_000_000, 12);
        fund(id, today.minusMonths(2).minusDays(3));
        runDaily(today);
        assertThat(pay(id, "return-payouts", key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        JsonNode quote = get(INV + "/" + id + "/early-withdrawal-quote");
        assertThat(quote.get("earned_return_minor").asLong()).isZero();
        assertThat(quote.get("return_paid_minor").asLong()).isEqualTo(20_000);
        assertThat(quote.get("penalty_minor").asLong()).isEqualTo(20_000);
        assertThat(quote.get("cash_minor").asLong()).isEqualTo(960_000);

        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                INV + "/" + id + "/early-withdrawal",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of("payment_method_key", "cash", "reason", "Test member needs the money"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(decide(r.getBody().get("approval_request_id").asString(), accountant, accountantPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode done = inv(id);
        assertThat(done.get("status").asString()).isEqualTo("withdrawn_early");
        assertThat(done.get("principal_held_minor").asLong()).isZero();
        assertThat(lines(entryOf(id, "early_withdrawal")))
                .containsExactly(
                        "investments_payable:1000000:0",
                        "investment_return_expense:0:20000",
                        "cash_on_hand:0:960000",
                        "investment_penalty_income:0:20000");
        assertThat(count(
                        "SELECT count(*) FROM lending_investment_schedule_items WHERE investment_id = ?::uuid"
                                + " AND status = 'cancelled'",
                        id))
                .isEqualTo(10);
        booksAgree();
    }

    @Test
    void frInv06_reducedRateTruesTheAccrualDownAndIsRefusedWhereNotAllowed() {
        String p = product(
                "EW02",
                Map.of(
                        "payout_frequency",
                        "at_maturity",
                        "early_withdrawal_allowed",
                        true,
                        "early_withdrawal_rule",
                        "reduced_rate",
                        "early_withdrawal_rate_bp",
                        600));
        String id = open(p, 1_000_000, 12);
        fund(id, today.minusMonths(4));
        runDaily(today);
        JsonNode quote = get(INV + "/" + id + "/early-withdrawal-quote");
        long earned = quote.get("earned_return_minor").asLong();
        assertThat(earned).isBetween(20_000L, 20_500L);
        assertThat(quote.get("return_accrued_minor").asLong()).isEqualTo(40_000);
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                INV + "/" + id + "/early-withdrawal",
                withKey(as(cashier, cashierPerms, "*", null), key()),
                Map.of("payment_method_key", "cash", "reason", "Test"));
        decide(r.getBody().get("approval_request_id").asString(), manager, managerPerms);
        assertThat(lines(entryOf(id, "early_withdrawal")))
                .containsExactly(
                        "investments_payable:1000000:0",
                        "investment_returns_payable:40000:0",
                        "investment_return_expense:0:" + (40_000 - earned),
                        "cash_on_hand:0:" + (1_000_000 + earned));
        booksAgree();

        String plain = product("FD07", Map.of());
        String other = open(plain, 1_000_000, 6);
        fund(other, today.minusDays(10));
        assertThat(get(INV + "/" + other).get("early_withdrawal_allowed").asBoolean())
                .isFalse();
        assertThat(asOfficer(HttpMethod.GET, INV + "/" + other + "/early-withdrawal-quote", null, null)
                        .getBody()
                        .toString())
                .contains("early_withdrawal_not_allowed");
    }

    // ---- Reversal, scope, concurrency -----------------------------------------------------

    @Test
    void frInv11_aFundingIsReversedOnlyBeforeAnythingElseMoves() {
        String p = product("FD08", Map.of());
        String id = open(p, 1_000_000, 6);
        fund(id, today);
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
        // The cashier who asked holds no reverse_approve; the manager does.
        assertThat(decide(
                                r.getBody().get("approval_request_id").asString(),
                                UUID.randomUUID(),
                                cashierPerms + ",core.approvals.read")
                        .getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        decide(r.getBody().get("approval_request_id").asString(), manager, managerPerms);
        JsonNode cancelled = inv(id);
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        assertThat(cancelled.get("principal_held_minor").asLong()).isZero();
        assertThat(runDaily(today.plusMonths(2)).accruals()).isZero();

        String accruing = open(p, 1_000_000, 6);
        fund(accruing, today.minusMonths(1).minusDays(1));
        runDaily(today);
        String funded = entryOf(accruing, "funding");
        assertThat(funded).isNotNull();
        String fundingTxn = TestDatabase.owner()
                .sql("SELECT id::text FROM lending_investment_transactions WHERE investment_id = ?::uuid"
                        + " AND txn_type = 'funding'")
                .param(accruing)
                .query(String.class)
                .single();
        assertThat(send(
                                HttpMethod.POST,
                                INV + "/" + accruing + "/transactions/" + fundingTxn + "/reverse",
                                withKey(as(cashier, cashierPerms, "*", null), key()),
                                Map.of("reason", "Test"))
                        .getBody()
                        .toString())
                .contains("not_reversible");
        booksAgree();
    }

    @Test
    void anInvestmentOutsideTheCallersBranchesIsNotFound() {
        String p = product("FD09", Map.of());
        String id = open(p, 1_000_000, 6);
        HttpHeaders other = as(UUID.randomUUID(), officerPerms, t.secondBranch().toString(), null);
        assertThat(send(HttpMethod.GET, INV + "/" + id, other, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.GET, INV, other, null).getBody().get("items").size())
                .isZero();
        HttpHeaders otherCashier =
                withKey(as(UUID.randomUUID(), cashierPerms, t.secondBranch().toString(), null), key());
        assertThat(send(
                                HttpMethod.POST,
                                INV + "/" + id + "/funding",
                                otherCashier,
                                Map.of("value_date", today.toString(), "payment_method_key", "cash"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Two cashiers pay the same due return at once with different keys: exactly one pays. */
    @Test
    void concurrentReturnPayoutsPayOnce() throws Exception {
        String p = product("FD10", Map.of());
        String id = open(p, 1_000_000, 6);
        fund(id, today.minusMonths(2).minusDays(1));
        runDaily(today);
        List<Integer> statuses =
                race(2, () -> pay(id, "return-payouts", key()).getStatusCode().value());
        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(inv(id).get("return_paid_minor").asLong()).isEqualTo(20_000);
        booksAgree();
    }

    /** Two daily runs at once accrue each period exactly once. */
    @Test
    void concurrentDailyRunsAccrueOnce() throws Exception {
        String p = product("FD11", Map.of());
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String id = open(p, 1_000_000 + i * 100_000L, 12);
            fund(id, today.minusMonths(5).minusDays(1));
            ids.add(id);
        }
        List<Integer> accruals = race(3, () -> runDaily(today).accruals());
        assertThat(accruals.stream().mapToInt(Integer::intValue).sum()).isEqualTo(20);
        for (String id : ids) {
            assertThat(count(
                            "SELECT count(*) FROM lending_investment_transactions WHERE investment_id = ?::uuid"
                                    + " AND txn_type = 'return_accrual'",
                            id))
                    .isEqualTo(5);
        }
        booksAgree();
    }

    <T> List<T> race(int n, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- Maturity ladder and metrics (FR-INV-12) ------------------------------------------

    @Test
    void frInv12_theLadderAndTheMetricsComeFromPostedRows() {
        String p = product("FD12", Map.of("payout_frequency", "at_maturity"));
        String in5 = open(p, 1_000_000, 3);
        fund(in5, today.minusMonths(3).plusDays(5));
        String in20 = open(p, 2_000_000, 3);
        fund(in20, today.minusMonths(3).plusDays(20));
        String in60 = open(p, 3_000_000, 3);
        fund(in60, today.minusMonths(3).plusDays(60));
        String due = open(p, 4_000_000, 3);
        fund(due, today.minusMonths(3).minusDays(2));
        runDaily(today);

        JsonNode m = get(INV + "/maturities");
        Map<String, JsonNode> ladder = new LinkedHashMap<>();
        m.get("ladder").forEach(b -> ladder.put(b.get("bucket").asString(), b));
        assertThat(ladder.keySet()).containsExactly("overdue", "0_7", "8_30", "31_90");
        assertThat(ladder.get("overdue").get("principal_minor").asLong()).isEqualTo(4_000_000);
        assertThat(ladder.get("overdue").get("return_minor").asLong()).isEqualTo(120_000);
        assertThat(ladder.get("0_7").get("total_minor").asLong()).isEqualTo(1_030_000);
        assertThat(ladder.get("8_30").get("total_minor").asLong()).isEqualTo(2_060_000);
        assertThat(ladder.get("31_90").get("total_minor").asLong()).isEqualTo(3_090_000);
        assertThat(m.get("items").get(0).get("investment_id").asString()).isEqualTo(due);

        JsonNode metrics = get(INV + "/metrics?from=" + today.minusMonths(4) + "&to=" + today);
        Map<String, Long> values = new LinkedHashMap<>();
        metrics.get("metrics")
                .forEach(x -> values.put(x.get("key").asString(), x.get("value").asLong()));
        assertThat(values.get("investments.balance")).isEqualTo(10_000_000);
        assertThat(values.get("investments.inflows")).isEqualTo(10_000_000);
        assertThat(values.get("investments.outflows")).isZero();
        assertThat(values.get("investments.returns_payable"))
                .isEqualTo(values.get("investments.returns_accrued"))
                .isPositive();
        assertThat(values.get("investments.investor_count")).isEqualTo(4);
        assertThat(metrics.get("total_principal_minor").asLong()).isEqualTo(10_000_000);
        assertThat(metrics.get("top_investors").get(0).get("share_bp").asInt()).isEqualTo(4_000);
        assertThat(metrics.get("products").get(0).get("share_bp").asInt()).isEqualTo(10_000);
    }
}
