package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.payments.FakePaymentGateway;
import com.rincoltech.bms.core.payments.GatewayException;
import com.rincoltech.bms.core.payments.PaymentGateway;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import com.rincoltech.bms.lending.loans.LoanServicing;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.MovableClockConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The unallocated receipts queue (extras slice A5, issue #224, ADR-035, spec 3.16, FR-PAY-04) on a real database with the
 * in-process fake provider: money a loan or account would not take waits in the queue; a cashier asks to allocate it to a loan
 * repayment or a savings deposit, or to record that it went back; a second person decides; and each receipt ends exactly once
 * however the requests and decisions are repeated, with the ledger, the intent and the audit consistent. Every name, number and
 * reference is fabricated.
 */
@Import({UnallocatedReceiptsIT.Config.class, MovableClockConfig.class})
@ResourceLock(MovableClockConfig.LOCK)
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.maximum-pool-size=4",
            "db-scheduler.enabled=false",
            "bms.payments.poller-enabled=false",
            "bms.payments.callback-ip-per-minute=100000",
            "bms.payments.callback-global-per-minute=1000000",
            "bms.pesapal.provider=fake",
            "bms.pesapal.callback-base-url=https://callbacks.example.test",
            "bms.pesapal.platform.consumer-key=invented-platform-key",
            "bms.pesapal.platform.consumer-secret=invented-platform-secret",
            "bms.pesapal.platform.ipn-id=aaaaaaaa-0000-4000-8000-000000000002",
            "bms.pesapal.platform.ipn-token=" + PaymentGatewayIT.PLATFORM_TOKEN,
            "bms.pesapal.refused-key-digests=fd7b571e25333d58599ee989f5fe9b901bead180a0d1b8d839c2bb59d078db49"
        })
class UnallocatedReceiptsIT extends LoanFixtures {

    static final String PURPOSE = "lending.loan_repayment";
    static final String SAVINGS = "lending.savings_deposit";
    static final String ACCOUNTS = "/api/v1/lending/savings-accounts";
    static final String UNALLOCATED = "/api/v1/payments/unallocated";
    static final Instant NOW = Instant.parse("2026-10-16T06:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        PaymentGatewayIT.StubSecurity stubSecurity() {
            return new PaymentGatewayIT.StubSecurity();
        }
    }

    @Autowired
    PaymentGateway gateway;

    @Autowired
    PaymentPoller poller;

    @Autowired
    MovableClock clock;

    @Autowired
    LoanServicing port;

    @Autowired
    TenantJobs tenantJobs;

    @Autowired
    MerchantAccountService merchantAccounts;

    FakePaymentGateway fake;
    UUID staff;
    String token;
    final UUID appraiser = UUID.randomUUID();
    final UUID manager = UUID.randomUUID();
    final UUID cashier = UUID.randomUUID();
    String cashierPerms;
    String accountantPerms;
    final UUID accountant = UUID.randomUUID();
    int memberNo = 10;
    UUID loansAt;

    @BeforeEach
    void gatewaySetUp() {
        fake = (FakePaymentGateway) gateway;
        fake.reset();
        merchantAccounts.forgetTests();
        clock.set(NOW);
        cashierPerms = permissionsOf("cashier");
        accountantPerms = permissionsOf("accountant");
        staff = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', 'Test Staff 224', ?, 'active')")
                .params(staff, t.tenantId(), "staff-" + staff + "@example.test")
                .update();
        token = connect();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO payment_methods (id, tenant_id, method_key, gl_account_id) VALUES (?, ?, 'gateway', ?)")
                .params(UUID.randomUUID(), t.tenantId(), t.account("gateway_clearing"))
                .update();
        setSettings(Map.of("collections_enabled", true, "daily_ceiling_minor", 100_000_000L));
        // Nothing left due from an earlier test: only this test's intents are checked.
        TestDatabase.owner()
                .sql("UPDATE payment_intents SET next_check_at = NULL")
                .update();
    }

    // ---- helpers ------------------------------------------------------------------------------

    ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body, String key) {
        HttpHeaders h = as(staff, adminPerms, "*", null);
        if (key != null) {
            h.add("Idempotency-Key", key);
        }
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    String connect() {
        String suffix = t.slug().substring(t.slug().length() - 8);
        ResponseEntity<JsonNode> saved = call(
                HttpMethod.PUT,
                "/api/v1/payments/merchant-account",
                Map.of(
                        "consumer_key",
                        "invented-key-" + suffix,
                        "consumer_secret",
                        "invented-secret-" + suffix,
                        "totp_code",
                        "000000"),
                null);
        assertThat(saved.getStatusCode().value()).as("%s", saved.getBody()).isEqualTo(200);
        ResponseEntity<JsonNode> tested = call(HttpMethod.POST, "/api/v1/payments/merchant-account/test", null, null);
        assertThat(tested.getBody().get("status").asString()).isEqualTo("verified");
        String url = fake.registeredUrl(PaymentGateway.Account.tenant(t.tenantId()));
        merchantAccounts.forgetTests();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    void setSettings(Map<String, Object> body) {
        ResponseEntity<JsonNode> current = call(HttpMethod.GET, "/api/v1/payments/settings", null, null);
        HttpHeaders h = as(staff, adminPerms, "*", current.getHeaders().getETag());
        ResponseEntity<JsonNode> r =
                http.exchange("/api/v1/payments/settings", HttpMethod.PUT, new HttpEntity<>(body, h), JsonNode.class);
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(200);
    }

    /** Flat 10 percent a month, three monthly instalments of 400,000 principal and 120,000 interest on 1,200,000. */
    String exampleProduct(String code) {
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
        terms.put("fees", List.of());
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                "/api/v1/lending/loan-products",
                as(UUID.randomUUID(), adminPerms, "*", null),
                Map.of("code", code, "name", "Test " + code, "terms", terms));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    /** An approved loan of 1,200,000 over three months. */
    String approvedLoan(String product) {
        memberNo++;
        String member = member(
                "Test Borrower %02d".formatted(memberNo),
                "07000000%02d".formatted(memberNo),
                loansAt != null ? loansAt : t.headOffice(),
                true);
        String loan = apply(member, product, 1_200_000, 3).getBody().get("id").asString();
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

    /** An active loan: requested by the cashier, authorised by the manager. */
    String activeLoan() {
        String loan = approvedLoan(exampleProduct("GW" + memberNo));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("disbursement_date", TODAY.toString());
        body.put("payment_method_key", "cash");
        body.put("external_reference", "TEST-DISB-216");
        HttpHeaders h = as(cashier, cashierPerms, "*", null);
        h.add("Idempotency-Key", "disb-" + UUID.randomUUID());
        ResponseEntity<JsonNode> r = send(HttpMethod.POST, LOANS + "/" + loan + "/disbursements", h, body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        ResponseEntity<JsonNode> d = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + r.getBody().get("approval_request_id").asString() + "/approve",
                as(manager, managerPerms, "*", null),
                Map.of("note", "Checked"));
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);
        return loan;
    }

    ResponseEntity<JsonNode> startIntent(String loan, long amount) {
        return call(
                HttpMethod.POST,
                "/api/v1/payments/intents",
                Map.of("purpose", PURPOSE, "subject_id", loan, "amount_minor", amount, "payer_phone", "0700000224"),
                "key-" + UUID.randomUUID());
    }

    JsonNode intentFor(String loan, long amount) {
        ResponseEntity<JsonNode> r = startIntent(loan, amount);
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(201);
        assertThat(r.getBody().get("status").asString()).isEqualTo("pending");
        return r.getBody();
    }

    String tracking(JsonNode intent) {
        return fake.orderFor(intent.get("merchant_reference").asString()).providerReference;
    }

    void pay(JsonNode intent, long paid, String code) {
        fake.complete(tracking(intent), paid, "UGX", code);
    }

    void tick(Duration after) {
        clock.set(clock.instant().plus(after));
        poller.tick();
    }

    Map<String, Object> row(JsonNode intent) {
        return TestDatabase.owner()
                .sql("SELECT * FROM payment_intents WHERE id = ?")
                .param(UUID.fromString(intent.get("id").asString()))
                .query()
                .singleRow();
    }

    JsonNode get(String loan, String suffix) {
        ResponseEntity<JsonNode> r = asOfficer(HttpMethod.GET, LOANS + "/" + loan + suffix, null, null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode cashRepayment(String loan, long amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", amount);
        body.put("value_date", TODAY.toString());
        body.put("payment_method_key", "cash");
        body.put("external_reference", "TEST-CASH-216");
        HttpHeaders h = as(cashier, cashierPerms, "*", null);
        h.add("Idempotency-Key", "cash-" + UUID.randomUUID());
        ResponseEntity<JsonNode> r = send(HttpMethod.POST, LOANS + "/" + loan + "/repayments", h, body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    /** The repayment rows of a loan, oldest first. */
    List<Map<String, Object>> repayments(String loan) {
        return TestDatabase.owner()
                .sql(
                        "SELECT * FROM lending_loan_transactions WHERE loan_id = ? AND txn_type = 'repayment' ORDER BY created_at")
                .param(UUID.fromString(loan))
                .query()
                .listOfRows();
    }

    List<String> lines(Object entryId) {
        return TestDatabase.owner().sql("""
                        SELECT a.system_key || ':' || l.debit || ':' || l.credit FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id WHERE l.entry_id = ? ORDER BY l.line_no
                        """).param(entryId).query(String.class).list();
    }

    // ---- savings fixtures -----------------------------------------------------------------------

    String savingsProduct(String code, Map<String, Object> overrides) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("name", "Test savings " + code);
        terms.put("interest_rate_bp", 0);
        terms.put("interest_calc", "none");
        terms.put("interest_posting", "monthly");
        terms.putAll(overrides);
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                "/api/v1/lending/savings-products",
                as(UUID.randomUUID(), adminPerms, "*", null),
                Map.of("code", code, "terms", terms));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    /** An open savings account of a verified member on a product with no minimum opening balance. */
    String account() {
        return account(Map.of());
    }

    String account(Map<String, Object> productOverrides) {
        memberNo++;
        String product = savingsProduct("SV" + memberNo, productOverrides);
        String member =
                member("Test Saver %02d".formatted(memberNo), "07000000%02d".formatted(memberNo), t.headOffice(), true);
        ResponseEntity<JsonNode> r =
                asOfficer(HttpMethod.POST, ACCOUNTS, null, Map.of("member_id", member, "product_id", product));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    long savingsBalance(String account) {
        ResponseEntity<JsonNode> r =
                send(HttpMethod.GET, ACCOUNTS + "/" + account, as(cashier, cashierPerms, "*", null), null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody().get("balance_minor").asLong();
    }

    // ---- the queue's own helpers ----------------------------------------------------------------

    /** A paid order for a loan that closed meanwhile: its money is an open unallocated receipt. Returns the receipt id. */
    UUID openReceipt(long amount, String code) {
        String loan = activeLoan();
        JsonNode intent = intentFor(loan, amount);
        cashRepayment(loan, 1_560_000);
        pay(intent, amount, code);
        tick(Duration.ofMinutes(3));
        assertThat(row(intent).get("status")).isEqualTo("booked_unallocated");
        return TestDatabase.owner()
                .sql("SELECT id FROM unallocated_receipts WHERE intent_id = ?")
                .param(UUID.fromString(intent.get("id").asString()))
                .query(UUID.class)
                .single();
    }

    /** The order whose money a receipt holds, in the shape the intent helpers return. */
    JsonNode intentOfReceipt(UUID receipt) {
        Map<String, Object> r = TestDatabase.owner()
                .sql(
                        "SELECT i.id, i.merchant_reference FROM payment_intents i JOIN unallocated_receipts u ON u.intent_id = i.id WHERE u.id = ?")
                .param(receipt)
                .query()
                .singleRow();
        return new tools.jackson.databind.ObjectMapper()
                .createObjectNode()
                .put("id", r.get("id").toString())
                .put("merchant_reference", r.get("merchant_reference").toString());
    }

    JsonNode intentFor(String subject, String purpose, long amount) {
        ResponseEntity<JsonNode> r = call(
                HttpMethod.POST,
                "/api/v1/payments/intents",
                Map.of("purpose", purpose, "subject_id", subject, "amount_minor", amount, "payer_phone", "0700000224"),
                "key-" + UUID.randomUUID());
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(201);
        return r.getBody();
    }

    Map<String, Object> receiptRow(UUID id) {
        return TestDatabase.owner()
                .sql("SELECT * FROM unallocated_receipts WHERE id = ?")
                .param(id)
                .query()
                .singleRow();
    }

    ResponseEntity<JsonNode> ask(
            UUID receipt, String action, Map<String, Object> body, UUID user, String perms, String key) {
        HttpHeaders h = as(user, perms, "*", null);
        h.add("Idempotency-Key", key == null ? "k-" + UUID.randomUUID() : key);
        return send(HttpMethod.POST, UNALLOCATED + "/" + receipt + "/" + action, h, body);
    }

    ResponseEntity<JsonNode> askAllocate(UUID receipt, String purpose, String subject) {
        return ask(
                receipt,
                "allocate",
                Map.of("purpose", purpose, "subject_id", subject, "reason", "Paid by the member's relative"),
                cashier,
                cashierPerms,
                null);
    }

    ResponseEntity<JsonNode> askRefund(UUID receipt, String method) {
        return ask(
                receipt,
                "refund",
                Map.of("method_key", method, "reference", "REF-224-" + method, "reason", "Paid twice, sent back"),
                cashier,
                cashierPerms,
                null);
    }

    String requested(ResponseEntity<JsonNode> r) {
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(202);
        return r.getBody().get("approval_request_id").asString();
    }

    ResponseEntity<JsonNode> decide(String approval, String verb) {
        return send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/" + verb,
                as(accountant, accountantPerms, "*", null),
                Map.of("note", "Checked against the statement"));
    }

    long unallocatedLiability() {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.credit - l.debit), 0) FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE a.tenant_id = ? AND a.system_key = 'unallocated_receipts'
                        """)
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    long clearingBalance() {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE a.tenant_id = ? AND a.system_key = 'gateway_clearing'
                        """)
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    ResponseEntity<JsonNode> read(UUID receipt, UUID user, String perms) {
        return send(HttpMethod.GET, UNALLOCATED + "/" + receipt, as(user, perms, "*", null), null);
    }

    // ---- the queue ------------------------------------------------------------------------------

    @Test
    void theQueueListsOpenReceiptsOldestFirstWithTheFactsOfTheOrder() {
        UUID first = openReceipt(520_000, "CONF-224-0001");
        UUID second = openReceipt(310_000, "CONF-224-0002");
        ResponseEntity<JsonNode> list = send(HttpMethod.GET, UNALLOCATED, as(cashier, cashierPerms, "*", null), null);
        assertThat(list.getStatusCode()).as("%s", list.getBody()).isEqualTo(HttpStatus.OK);
        List<String> ids = new java.util.ArrayList<>();
        list.getBody().get("items").forEach(i -> ids.add(i.get("id").asString()));
        assertThat(ids).containsSubsequence(first.toString(), second.toString());
        JsonNode one = read(first, cashier, cashierPerms).getBody();
        assertThat(one.get("status").asString()).isEqualTo("open");
        assertThat(one.get("amount_minor").asLong()).isEqualTo(520_000);
        assertThat(one.get("confirmation_code").asString()).isEqualTo("CONF-224-0001");
        assertThat(one.get("refusal_code").asString()).isEqualTo("invalid_status_transition");
        assertThat(one.get("order_reference").asString()).startsWith("BMS-");
        assertThat(one.get("order_tracking_id").asString()).hasSize(36);
        assertThat(one.get("pending_requests")).isEmpty();
        // The payer's phone number is not part of the answer.
        assertThat(one.toString()).doesNotContain("0700000224").doesNotContain("payer");
        // A reader without the permission sees nothing of it.
        assertThat(read(first, cashier, "lending.loans.read").getStatusCode().value())
                .isEqualTo(403);
        assertThat(send(HttpMethod.GET, UNALLOCATED, as(cashier, "lending.loans.read", "*", null), null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
    }

    @Test
    void aCashierWhoCanReadNoBranchSeesNoReceiptAndIsToldItDoesNotExist() {
        UUID receipt = openReceipt(520_000, "CONF-224-0003");
        HttpHeaders h = as(
                cashier,
                "core.payments.read,core.payments.allocate",
                UUID.randomUUID().toString(),
                null);
        ResponseEntity<JsonNode> r = send(HttpMethod.GET, UNALLOCATED + "/" + receipt, h, null);
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        ResponseEntity<JsonNode> list = send(HttpMethod.GET, UNALLOCATED, h, null);
        assertThat(list.getBody().get("items")).isEmpty();
        HttpHeaders post = as(
                cashier,
                "core.payments.read,core.payments.allocate",
                UUID.randomUUID().toString(),
                null);
        post.add("Idempotency-Key", "k-" + UUID.randomUUID());
        assertThat(send(
                                HttpMethod.POST,
                                UNALLOCATED + "/" + receipt + "/refund",
                                post,
                                Map.of("method_key", "gateway", "reference", "R1", "reason", "x"))
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    // ---- allocation -----------------------------------------------------------------------------

    @Test
    void allocatingToALoanMovesNothingUntilTheCheckerApprovesThenBooksARepaymentAndClearsTheLiability() {
        UUID receipt = openReceipt(520_000, "CONF-224-0010");
        String target = activeLoan();
        long liability = unallocatedLiability();
        assertThat(liability).isEqualTo(520_000);
        long clearing = clearingBalance();
        String approval = requested(askAllocate(receipt, PURPOSE, target));
        // Asking moved nothing: the receipt is open, the loan has no repayment, the books are as they were.
        assertThat(receiptRow(receipt).get("status")).isEqualTo("open");
        assertThat(repayments(target)).isEmpty();
        assertThat(unallocatedLiability()).isEqualTo(liability);
        JsonNode waiting = read(receipt, cashier, cashierPerms).getBody();
        assertThat(waiting.get("pending_requests")
                        .get(0)
                        .get("approval_request_id")
                        .asString())
                .isEqualTo(approval);
        assertThat(waiting.get("pending_requests").get(0).get("action_type").asString())
                .isEqualTo("unallocated_allocation");

        ResponseEntity<JsonNode> done = decide(approval, "approve");
        assertThat(done.getStatusCode().value()).as("%s", done.getBody()).isEqualTo(200);

        Map<String, Object> row = receiptRow(receipt);
        assertThat(row.get("status")).isEqualTo("allocated");
        assertThat(row.get("version")).isEqualTo(1);
        assertThat(row.get("allocated_purpose")).isEqualTo(PURPOSE);
        assertThat(row.get("allocated_subject_id")).isEqualTo(UUID.fromString(target));
        assertThat(row.get("requested_by")).isEqualTo(cashier);
        assertThat(row.get("decided_by")).isEqualTo(accountant);
        assertThat(row.get("approval_id")).isEqualTo(UUID.fromString(approval));
        List<Map<String, Object>> repayments = repayments(target);
        assertThat(repayments).hasSize(1);
        Map<String, Object> repayment = repayments.getFirst();
        assertThat(repayment.get("id")).isEqualTo(row.get("allocated_txn_id"));
        assertThat(repayment.get("payment_method_key")).isEqualTo("gateway");
        assertThat(repayment.get("source")).isEqualTo("gateway");
        assertThat(repayment.get("amount_minor")).isEqualTo(520_000L);
        // The order tracking id is the reference, never the confirmation code.
        String tracking = TestDatabase.owner()
                .sql(
                        "SELECT i.provider_reference FROM payment_intents i JOIN unallocated_receipts r ON r.intent_id = i.id WHERE r.id = ?")
                .param(receipt)
                .query(String.class)
                .single();
        assertThat(repayment.get("external_reference")).isEqualTo(tracking);
        // The clearing entry is the receipt's own entry, reversed: the liability is gone and the clearing account holds
        // the
        // money once (the repayment's debit), not twice.
        assertThat(lines(row.get("cleared_entry_id")))
                .containsExactly("gateway_clearing:0:520000", "unallocated_receipts:520000:0");
        assertThat(unallocatedLiability()).isEqualTo(liability - 520_000);
        assertThat(clearingBalance()).isEqualTo(clearing);
        // The intent now names the transaction that holds its money: the join the reconciliation uses.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT booked_subject_txn_id FROM payment_intents i JOIN unallocated_receipts r ON r.intent_id = i.id WHERE r.id = ?")
                        .param(receipt)
                        .query(UUID.class)
                        .single())
                .isEqualTo(repayment.get("id"));
        // Audited, by the checker, with the request's maker and approval.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'core.payments.receipt.allocated' AND actor_user_id = ?")
                        .params(receipt, accountant)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        // The view says the same.
        JsonNode view = read(receipt, cashier, cashierPerms).getBody();
        assertThat(view.get("status").asString()).isEqualTo("allocated");
        assertThat(view.get("allocation").get("transaction_id").asString())
                .isEqualTo(repayment.get("id").toString());
        assertThat(view.get("pending_requests")).isEmpty();
        // The open queue no longer lists it.
        ResponseEntity<JsonNode> open = send(HttpMethod.GET, UNALLOCATED, as(cashier, cashierPerms, "*", null), null);
        open.getBody()
                .get("items")
                .forEach(i -> assertThat(i.get("id").asString()).isNotEqualTo(receipt.toString()));
    }

    /**
     * Spec 3.7 governs an allocation above the payoff (ADR-035 decision 64): the excess becomes the loan's credit balance, and the
     * checker is told so in words before deciding, on the list and the detail; the request carries it and an audit row records it.
     */
    @Test
    void anAllocationAboveThePayoffTellsTheCheckerTheCreditBalanceItCreatesAndAuditsTheExcess() {
        UUID receipt = openReceipt(500_000, "CONF-224-0030");
        String target = activeLoan();
        long payoff = get(target, "/payoff-quote").get("total_minor").asLong();
        cashRepayment(target, payoff - 50_000);
        assertThat(get(target, "/payoff-quote").get("total_minor").asLong()).isEqualTo(50_000L);
        String loanNo = get(target, "").get("loan_no").asString();

        String approval = requested(askAllocate(receipt, PURPOSE, target));
        String notice = "This creates a credit balance of UGX 450,000 on loan " + loanNo
                + ": the payment is more than the UGX 50,000 it takes to pay it off today.";
        JsonNode detail = send(
                        HttpMethod.GET,
                        "/api/v1/approvals/" + approval,
                        as(accountant, accountantPerms, "*", null),
                        null)
                .getBody();
        assertThat(detail.get("checker_notice").asString()).isEqualTo(notice);
        assertThat(detail.get("payload").get("credit_balance_minor").asLong()).isEqualTo(450_000L);
        JsonNode listed = send(HttpMethod.GET, "/api/v1/approvals", as(accountant, accountantPerms, "*", null), null)
                .getBody();
        boolean shown = false;
        for (JsonNode item : listed.get("items")) {
            if (item.get("id").asString().equals(approval)) {
                assertThat(item.get("checker_notice").asString()).isEqualTo(notice);
                shown = true;
            }
        }
        assertThat(shown)
                .as("the request is in the checker's list with its notice")
                .isTrue();
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT (data -> 'after' ->> 'credit_balance_minor')::bigint FROM audit_log
                                 WHERE entity_id = ? AND action = 'core.payments.receipt.allocation_creates_credit'
                                   AND actor_user_id = ?
                                """)
                        .params(receipt, cashier)
                        .query(Long.class)
                        .single())
                .isEqualTo(450_000L);

        ResponseEntity<JsonNode> done = decide(approval, "approve");
        assertThat(done.getStatusCode().value()).as("%s", done.getBody()).isEqualTo(200);
        assertThat(receiptRow(receipt).get("status")).isEqualTo("allocated");
        Map<String, Object> loan = TestDatabase.owner()
                .sql("SELECT status, credit_balance_minor FROM lending_loans WHERE id = ?")
                .param(UUID.fromString(target))
                .query()
                .singleRow();
        assertThat(loan.get("status")).isEqualTo("closed");
        assertThat(loan.get("credit_balance_minor")).isEqualTo(450_000L);
    }

    @Test
    void anAllocationWithinThePayoffCarriesNoNotice() {
        UUID receipt = openReceipt(100_000, "CONF-224-0031");
        String target = activeLoan();
        String approval = requested(askAllocate(receipt, PURPOSE, target));
        JsonNode detail = send(
                        HttpMethod.GET,
                        "/api/v1/approvals/" + approval,
                        as(accountant, accountantPerms, "*", null),
                        null)
                .getBody();
        assertThat(detail.get("checker_notice") == null
                        || detail.get("checker_notice").isNull())
                .isTrue();
        assertThat(detail.get("payload").has("credit_balance_minor")).isFalse();
    }

    @Test
    void allocatingToASavingsAccountBooksAGatewayDepositAndRaisesTheBalanceOnce() {
        UUID receipt = openReceipt(520_000, "CONF-224-0011");
        String account = account();
        assertThat(savingsBalance(account)).isZero();
        String approval = requested(askAllocate(receipt, SAVINGS, account));
        assertThat(decide(approval, "approve").getStatusCode().value()).isEqualTo(200);
        assertThat(savingsBalance(account)).isEqualTo(520_000);
        Map<String, Object> row = receiptRow(receipt);
        assertThat(row.get("status")).isEqualTo("allocated");
        assertThat(row.get("allocated_subject_type")).isEqualTo("savings_account");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT payment_method_key || ':' || source FROM lending_savings_transactions WHERE id = ?")
                        .param(row.get("allocated_txn_id"))
                        .query(String.class)
                        .single())
                .isEqualTo("gateway:gateway");
        // A second approval of the same request books nothing more.
        assertThat(decide(approval, "approve").getStatusCode().is2xxSuccessful())
                .isFalse();
        assertThat(savingsBalance(account)).isEqualTo(520_000);
    }

    @Test
    void aReceiptIsAllocatedOnceWhateverIsAskedAndApprovedTwice() {
        UUID receipt = openReceipt(520_000, "CONF-224-0012");
        String one = activeLoan();
        String two = activeLoan();
        String first = requested(askAllocate(receipt, PURPOSE, one));
        // A second request on the same receipt and action waits behind the first one.
        ResponseEntity<JsonNode> again = askAllocate(receipt, PURPOSE, two);
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(again.getBody().get("code").asString()).isEqualTo("approval_already_pending");
        // A refund asked meanwhile is another action on the same receipt: it can be asked, but only one of them can
        // win.
        String refund = requested(askRefund(receipt, "bank"));
        assertThat(read(receipt, cashier, cashierPerms).getBody().get("pending_requests"))
                .hasSize(2);
        assertThat(decide(first, "approve").getStatusCode().value()).isEqualTo(200);
        ResponseEntity<JsonNode> late = decide(refund, "approve");
        assertThat(late.getStatusCode().value()).as("%s", late.getBody()).isEqualTo(422);
        assertThat(late.getBody().get("code").asString()).isEqualTo("subject_changed");
        assertThat(receiptRow(receipt).get("status")).isEqualTo("allocated");
        assertThat(repayments(one)).hasSize(1);
        assertThat(repayments(two)).isEmpty();
        // The settled receipt takes no new request.
        ResponseEntity<JsonNode> closed = askAllocate(receipt, PURPOSE, two);
        assertThat(closed.getStatusCode().value()).isEqualTo(409);
        assertThat(closed.getBody().get("code").asString()).isEqualTo("receipt_not_open");
        assertThat(unallocatedLiability()).isZero();
    }

    @Test
    void theSameIdempotencyKeyReplaysTheAnswerAndMakesOneRequest() {
        UUID receipt = openReceipt(520_000, "CONF-224-0013");
        String target = activeLoan();
        Map<String, Object> body = Map.of("purpose", PURPOSE, "subject_id", target, "reason", "Paid by a relative");
        ResponseEntity<JsonNode> first = ask(receipt, "allocate", body, cashier, cashierPerms, "replay-224-key");
        ResponseEntity<JsonNode> second = ask(receipt, "allocate", body, cashier, cashierPerms, "replay-224-key");
        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(second.getStatusCode().value()).isEqualTo(202);
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("approval_request_id").asString())
                .isEqualTo(first.getBody().get("approval_request_id").asString());
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM approval_requests WHERE subject_id = ?")
                        .param(receipt)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        // A key without a request body that matches is refused, not replayed.
        ResponseEntity<JsonNode> reused = ask(
                receipt,
                "allocate",
                Map.of("purpose", PURPOSE, "subject_id", target, "reason", "Another reason"),
                cashier,
                cashierPerms,
                "replay-224-key");
        assertThat(reused.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void theMakerCannotDecideTheirOwnRequestAndAMakerWithoutTheCheckerPermissionCannotEither() {
        UUID receipt = openReceipt(520_000, "CONF-224-0014");
        String target = activeLoan();
        // The administrator holds both permissions; the mechanism still refuses the maker as checker.
        String approval = requested(ask(
                receipt,
                "allocate",
                Map.of("purpose", PURPOSE, "subject_id", target, "reason", "Paid by a relative"),
                staff,
                adminPerms,
                null));
        ResponseEntity<JsonNode> own = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/approve",
                as(staff, adminPerms, "*", null),
                Map.of("note", "ok"));
        assertThat(own.getStatusCode().is2xxSuccessful())
                .as("%s", own.getBody())
                .isFalse();
        ResponseEntity<JsonNode> cashierDecides = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/approve",
                as(cashier, cashierPerms, "*", null),
                Map.of("note", "ok"));
        assertThat(cashierDecides.getStatusCode().value()).isIn(403, 404);
        assertThat(receiptRow(receipt).get("status")).isEqualTo("open");
        assertThat(repayments(target)).isEmpty();
    }

    @Test
    void askingNeedsTheAllocatePermissionInTheReceiptsBranchAndTheTargetsBranchBeforeAnythingIsSaid() {
        UUID receipt = openReceipt(520_000, "CONF-224-0015");
        String target = activeLoan();
        // Reads the receipt, may not allocate: refused before the loan is looked at.
        ResponseEntity<JsonNode> readerOnly = ask(
                receipt,
                "allocate",
                Map.of("purpose", PURPOSE, "subject_id", UUID.randomUUID().toString(), "reason", "x"),
                cashier,
                "core.payments.read",
                null);
        assertThat(readerOnly.getStatusCode().value()).isEqualTo(403);
        // Allocates in the receipt's branch only: a loan that exists or not, the answer is the same word.
        UUID elsewhere = UUID.randomUUID();
        ResponseEntity<JsonNode> missing = ask(
                receipt,
                "allocate",
                Map.of("purpose", PURPOSE, "subject_id", UUID.randomUUID().toString(), "reason", "x"),
                cashier,
                cashierPerms,
                null);
        assertThat(missing.getStatusCode().value()).isEqualTo(422);
        assertThat(missing.getBody().get("code").asString()).isEqualTo("subject_not_found");
        // A purpose that is not a lender's own money is not offered.
        ResponseEntity<JsonNode> platform = askAllocate(receipt, "platform.subscription_payment", target);
        assertThat(platform.getStatusCode().value()).isEqualTo(422);
        assertThat(platform.getBody().get("code").asString()).isEqualTo("unknown_purpose");
        assertThat(elsewhere).isNotNull();
    }

    @Test
    void aTargetThatCannotTakeTheMoneyIsRefusedWhenAskedAndWhenApproved() {
        UUID receipt = openReceipt(520_000, "CONF-224-0016");
        String closed = activeLoan();
        cashRepayment(closed, 1_560_000);
        ResponseEntity<JsonNode> refused = askAllocate(receipt, PURPOSE, closed);
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("subject_not_payable");
        // Asked while the loan could take it, then it closes before the checker decides: the approval fails, stays
        // pending with the reason, and nothing is booked.
        String target = activeLoan();
        String approval = requested(askAllocate(receipt, PURPOSE, target));
        cashRepayment(target, 1_560_000);
        ResponseEntity<JsonNode> failed = decide(approval, "approve");
        assertThat(failed.getStatusCode().value()).as("%s", failed.getBody()).isEqualTo(422);
        assertThat(failed.getBody().get("code").asString()).isEqualTo("approval_execution_failed");
        assertThat(failed.getBody().get("detail").asString()).contains("invalid_status_transition");
        assertThat(receiptRow(receipt).get("status")).isEqualTo("open");
        assertThat(receiptRow(receipt).get("version")).isEqualTo(0);
        assertThat(repayments(target)).hasSize(1);
        assertThat(unallocatedLiability()).isEqualTo(520_000);
        // The checker rejects it; the receipt is free for another request.
        assertThat(decide(approval, "reject").getStatusCode().value()).isEqualTo(200);
        String again = activeLoan();
        assertThat(decide(requested(askAllocate(receipt, PURPOSE, again)), "approve")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(repayments(again)).hasSize(1);
    }

    @Test
    void aPaymentTheProviderHasReversedCannotBeAllocatedOrRefundedHere() {
        UUID receipt = openReceipt(520_000, "CONF-224-0017");
        String target = activeLoan();
        String approval = requested(askAllocate(receipt, PURPOSE, target));
        TestDatabase.owner()
                .sql(
                        "UPDATE payment_intents SET status = 'reversed_pending_review' WHERE id = (SELECT intent_id FROM unallocated_receipts WHERE id = ?)")
                .param(receipt)
                .update();
        ResponseEntity<JsonNode> r = askRefund(receipt, "bank");
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody().get("code").asString()).isEqualTo("payment_not_held");
        ResponseEntity<JsonNode> late = decide(approval, "approve");
        assertThat(late.getStatusCode().value()).as("%s", late.getBody()).isEqualTo(409);
        assertThat(late.getBody().get("code").asString()).isEqualTo("payment_not_held");
        assertThat(receiptRow(receipt).get("status")).isEqualTo("open");
        assertThat(repayments(target)).isEmpty();
    }

    // ---- refund ---------------------------------------------------------------------------------

    @Test
    void aRefundPaidOutByBankIsRecordedAfterTheCheckerApprovesAndDebitsTheLiabilityOnce() {
        UUID receipt = openReceipt(520_000, "CONF-224-0020");
        String bank = assetAccount("1098", "Test bank");
        assertThat(mapMethod("bank", null, bank, true).getStatusCode().value()).isEqualTo(200);
        String approval = requested(askRefund(receipt, "bank"));
        assertThat(receiptRow(receipt).get("status")).isEqualTo("open");
        assertThat(unallocatedLiability()).isEqualTo(520_000);
        assertThat(decide(approval, "approve").getStatusCode().value()).isEqualTo(200);
        Map<String, Object> row = receiptRow(receipt);
        assertThat(row.get("status")).isEqualTo("refunded");
        assertThat(row.get("refund_method_key")).isEqualTo("bank");
        assertThat(row.get("refund_reference")).isEqualTo("REF-224-bank");
        assertThat(row.get("allocated_txn_id")).isNull();
        assertThat(codedLines(row.get("cleared_entry_id").toString()))
                .containsExactly("2040:520000:0", "1098:0:520000");
        assertThat(unallocatedLiability()).isZero();
        JsonNode view = read(receipt, cashier, cashierPerms).getBody();
        assertThat(view.get("refund").get("reference").asString()).isEqualTo("REF-224-bank");
        // Neither another refund nor an allocation can follow.
        assertThat(askRefund(receipt, "bank").getStatusCode().value()).isEqualTo(409);
        assertThat(askAllocate(receipt, PURPOSE, activeLoan()).getStatusCode().value())
                .isEqualTo(409);
        assertThat(decide(approval, "approve").getStatusCode().is2xxSuccessful())
                .isFalse();
        assertThat(unallocatedLiability()).isZero();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'core.payments.receipt.refunded'")
                        .param(receipt)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void aRefundThroughTheProviderReversesTheReceiptsOwnEntry() {
        UUID receipt = openReceipt(520_000, "CONF-224-0021");
        long clearing = clearingBalance();
        String approval = requested(askRefund(receipt, "gateway"));
        assertThat(decide(approval, "approve").getStatusCode().value()).isEqualTo(200);
        Map<String, Object> row = receiptRow(receipt);
        assertThat(row.get("status")).isEqualTo("refunded");
        assertThat(lines(row.get("cleared_entry_id")))
                .containsExactly("gateway_clearing:0:520000", "unallocated_receipts:520000:0");
        assertThat(clearingBalance()).isEqualTo(clearing - 520_000);
        assertThat(unallocatedLiability()).isZero();
    }

    @Test
    void aRefundMethodThatHasNoAccountOrIsNotOfferedIsRefusedWhenAsked() {
        UUID receipt = openReceipt(520_000, "CONF-224-0022");
        TestDatabase.owner()
                .sql(
                        "INSERT INTO payment_methods (id, tenant_id, method_key, gl_account_id, is_active) VALUES (?, ?, 'bank', ?, false)")
                .params(UUID.randomUUID(), t.tenantId(), UUID.fromString(assetAccount("1097", "Test closed bank")))
                .update();
        ResponseEntity<JsonNode> r = askRefund(receipt, "bank");
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(422);
        assertThat(r.getBody().get("code").asString()).isEqualTo("payment_method_unmapped");
        ResponseEntity<JsonNode> odd = askRefund(receipt, "crypto");
        assertThat(odd.getStatusCode().value()).isEqualTo(422);
        assertThat(odd.getBody().get("code").asString()).isEqualTo("validation_failed");
        ResponseEntity<JsonNode> ctl = ask(
                receipt,
                "refund",
                Map.of("method_key", "gateway", "reference", "bad\nreference", "reason", "x"),
                cashier,
                cashierPerms,
                null);
        assertThat(ctl.getStatusCode().value()).isEqualTo(422);
        assertThat(receiptRow(receipt).get("status")).isEqualTo("open");
    }

    // ---- the database's own guard ---------------------------------------------------------------

    @Test
    void theDatabaseRefusesWhatTheCodeNeverDoes() {
        UUID receipt = openReceipt(520_000, "CONF-224-0030");
        var owner = TestDatabase.owner();
        // What the payment was never changes.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> owner.sql(
                                "UPDATE unallocated_receipts SET status = 'allocated', amount_minor = 1, version = version + 1 WHERE id = ?")
                        .param(receipt)
                        .update()))
                .hasMessageContaining("what the payment was never changes");
        // An open receipt ends as allocated, refunded or reversed, one version higher, and nowhere else.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> owner.sql(
                                "UPDATE unallocated_receipts SET note = 'x', version = version + 1 WHERE id = ?")
                        .param(receipt)
                        .update()))
                .hasMessageContaining("ends as allocated, refunded or reversed");
        // A refunded receipt carries its refund and nothing of an allocation.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> owner.sql(
                                "UPDATE unallocated_receipts SET status = 'refunded', version = version + 1 WHERE id = ?")
                        .param(receipt)
                        .update()))
                .isNotNull();
        String approval = requested(askRefund(receipt, "gateway"));
        assertThat(decide(approval, "approve").getStatusCode().value()).isEqualTo(200);
        // A settled receipt never changes.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> owner.sql(
                                "UPDATE unallocated_receipts SET status = 'open', version = version + 1 WHERE id = ?")
                        .param(receipt)
                        .update()))
                .hasMessageContaining("settled receipt never changes");
    }

    @Test
    void theApplicationRoleCanSettleAReceiptButNeverRewriteWhatThePaymentWas() {
        UUID receipt = openReceipt(520_000, "CONF-224-0031");
        Boolean mayUpdateAmount = TestDatabase.owner()
                .sql("SELECT has_column_privilege('bms_app', 'unallocated_receipts', 'amount_minor', 'UPDATE')")
                .query(Boolean.class)
                .single();
        Boolean mayUpdateStatus = TestDatabase.owner()
                .sql("SELECT has_column_privilege('bms_app', 'unallocated_receipts', 'status', 'UPDATE')")
                .query(Boolean.class)
                .single();
        Boolean mayDelete = TestDatabase.owner()
                .sql("SELECT has_table_privilege('bms_app', 'unallocated_receipts', 'DELETE')")
                .query(Boolean.class)
                .single();
        assertThat(mayUpdateAmount).isFalse();
        assertThat(mayUpdateStatus).isTrue();
        assertThat(mayDelete).isFalse();
        assertThat(receiptRow(receipt).get("status")).isEqualTo("open");
    }

    /** The balance (debits less credits) of the account with this key over entries dated on or before the day. */
    long balanceAsOf(String systemKey, String day) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE a.tenant_id = ? AND a.system_key = ? AND e.entry_date <= CAST(? AS date)
                        """)
                .params(t.tenantId(), systemKey, day)
                .query(Long.class)
                .single();
    }

    /** A loan with one pledged item, disbursed and paid off by a gateway payment, so it is closed and its pledge released. */
    String[] securedLoanPaidOffByGateway(String code) {
        memberNo++;
        String product = product(code, false, true);
        String member = member(
                "Test Borrower %02d".formatted(memberNo), "07000000%02d".formatted(memberNo), t.headOffice(), true);
        String item = collateralItem(member, "TEST-PLEDGE-224-" + memberNo);
        String loan = apply(member, product, 500_000, null).getBody().get("id").asString();
        assertThat(asOfficer(
                                HttpMethod.PUT,
                                LOANS + "/" + loan + "/collateral",
                                "\"1\"",
                                Map.of(
                                        "collateral",
                                        List.of(Map.of("collateral_id", item, "pledged_value_minor", 1_000_000))))
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
        HttpHeaders h = as(cashier, cashierPerms, "*", null);
        h.add("Idempotency-Key", "disb-" + UUID.randomUUID());
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/disbursements",
                h,
                Map.of(
                        "disbursement_date",
                        TODAY.toString(),
                        "payment_method_key",
                        "cash",
                        "external_reference",
                        "TEST-DISB-224"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(send(
                                HttpMethod.POST,
                                "/api/v1/approvals/"
                                        + r.getBody().get("approval_request_id").asString() + "/approve",
                                as(manager, managerPerms, "*", null),
                                Map.of("note", "Checked"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        long payoff = get(loan, "/payoff-quote").get("total_minor").asLong();
        JsonNode intent = bookedRepayment(loan, payoff, "CONF-224-SECURED-" + memberNo);
        assertThat(get(loan, "").get("status").asString()).isEqualTo("closed");
        return new String[] {
            loan,
            item,
            intent.get("id").asString(),
            intent.get("merchant_reference").asString()
        };
    }

    long pendingRequests(JsonNode intent) {
        return reversalRequests(intent).stream()
                .filter(r -> "pending".equals(r.get("status")))
                .count();
    }

    // ---- the provider reports a payment reversed ------------------------------------------------

    /** The provider now answers REVERSED for the order, a notification arrives and the poller looks. */
    void reportReversed(JsonNode intent) {
        fake.reverse(tracking(intent));
        notifyProvider(intent);
        tick(Duration.ofSeconds(30));
    }

    void notifyProvider(JsonNode intent) {
        http.exchange(
                "/api/v1/payments/callbacks/pesapal/" + token + "?OrderTrackingId=" + tracking(intent)
                        + "&OrderMerchantReference="
                        + intent.get("merchant_reference").asString()
                        + "&OrderNotificationType=IPNCHANGE",
                HttpMethod.GET,
                HttpEntity.EMPTY,
                JsonNode.class);
    }

    List<Map<String, Object>> reversalRequests(JsonNode intent) {
        return TestDatabase.owner()
                .sql("SELECT * FROM approval_requests WHERE action_type = 'gateway_payment_reversal' AND subject_id = ?"
                        + " ORDER BY requested_at")
                .param(UUID.fromString(intent.get("id").asString()))
                .query()
                .listOfRows();
    }

    /** A booked gateway repayment on the given loan; returns the intent. */
    JsonNode bookedRepayment(String loan, long amount, String code) {
        JsonNode intent = intentFor(loan, amount);
        pay(intent, amount, code);
        tick(Duration.ofMinutes(3));
        assertThat(row(intent).get("status")).isEqualTo("booked");
        return intent;
    }

    ResponseEntity<JsonNode> decideAs(String approval, String verb, UUID user, String perms) {
        return send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/" + verb,
                as(user, perms, "*", null),
                Map.of("note", "Checked"));
    }

    long reversalCount(String loan) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM lending_loan_transactions WHERE loan_id = ? AND txn_type = 'reversal'")
                .param(UUID.fromString(loan))
                .query(Long.class)
                .single();
    }

    @Test
    void aReversalAfterBookingRaisesOneRequestByTheSystemUserAndMovesNoMoneyUntilAPersonApproves() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0040");
        long clearing = clearingBalance();
        reportReversed(intent);
        reportReversed(intent);
        reportReversed(intent);
        assertThat(row(intent).get("status")).isEqualTo("reversed_pending_review");
        List<Map<String, Object>> requests = reversalRequests(intent);
        assertThat(requests).hasSize(1);
        Map<String, Object> request = requests.getFirst();
        assertThat(request.get("status")).isEqualTo("pending");
        assertThat(request.get("amount_minor")).isEqualTo(520_000L);
        // The maker is the tenant's non-login system user: no credentials, no role, not a staff member.
        Map<String, Object> maker = TestDatabase.owner()
                .sql("SELECT kind, status FROM users WHERE id = ?")
                .param(request.get("requested_by"))
                .query()
                .singleRow();
        assertThat(maker.get("kind")).isEqualTo("system");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM user_credentials WHERE user_id = ?")
                        .param(request.get("requested_by"))
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM user_role_assignments WHERE user_id = ?")
                        .param(request.get("requested_by"))
                        .query(Long.class)
                        .single())
                .isZero();
        // Nothing moved: the repayment stands, the clearing balance is as it was.
        assertThat(repayments(loan)).hasSize(1);
        assertThat(clearingBalance()).isEqualTo(clearing);
        // Staff still cannot reverse the gateway repayment by hand, whatever the provider says.
        HttpHeaders h = as(staff, adminPerms, "*", null);
        h.add("Idempotency-Key", "rev-" + UUID.randomUUID());
        ResponseEntity<JsonNode> byHand = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/transactions/"
                        + repayments(loan).getFirst().get("id") + "/reverse",
                h,
                Map.of("reason", "Test reversal by hand"));
        assertThat(byHand.getBody().get("code").asString()).isEqualTo("gateway_reversal_pending");
        // One system user per tenant, however many requests.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM users WHERE tenant_id = ? AND kind = 'system'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void approvingTheReversalReversesTheRepaymentOnceAndPutsTheClearingBalanceBack() {
        String loan = activeLoan();
        long clearingBefore = clearingBalance();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0041");
        assertThat(clearingBalance()).isEqualTo(clearingBefore + 520_000);
        reportReversed(intent);
        String approval = reversalRequests(intent).getFirst().get("id").toString();
        // The checker needs both the payments permission and the loan's own reversal approval.
        ResponseEntity<JsonNode> partial =
                decideAs(approval, "approve", accountant, "core.payments.allocate_approve,core.approvals.read");
        assertThat(partial.getStatusCode().value()).isEqualTo(403);
        assertThat(repayments(loan)).hasSize(1);
        ResponseEntity<JsonNode> done = decide(approval, "approve");
        assertThat(done.getStatusCode().value()).as("%s", done.getBody()).isEqualTo(200);
        assertThat(row(intent).get("status")).isEqualTo("reversed");
        assertThat(reversalCount(loan)).isEqualTo(1L);
        assertThat(clearingBalance()).isEqualTo(clearingBefore);
        // Exactly once: the same approval again, or a new notification, changes nothing.
        assertThat(decide(approval, "approve").getStatusCode().is2xxSuccessful())
                .isFalse();
        notifyProvider(intent);
        tick(Duration.ofDays(2));
        assertThat(reversalRequests(intent)).hasSize(1);
        assertThat(reversalCount(loan)).isEqualTo(1L);
        assertThat(clearingBalance()).isEqualTo(clearingBefore);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'core.payments.intent.reversed'")
                        .param(UUID.fromString(intent.get("id").asString()))
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void theCheckerRejectingTheReversalKeepsThePaymentAndItIsNotRaisedAgain() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0042");
        reportReversed(intent);
        String approval = reversalRequests(intent).getFirst().get("id").toString();
        assertThat(decide(approval, "reject").getStatusCode().value()).isEqualTo(200);
        reportReversed(intent);
        tick(Duration.ofDays(8));
        assertThat(reversalRequests(intent)).hasSize(1);
        assertThat(row(intent).get("status")).isEqualTo("reversed_pending_review");
        assertThat(repayments(loan)).hasSize(1);
    }

    @Test
    void aReversalRequestThatWentStaleBecauseTheLoanMovedIsRaisedAgainWithTheFreshFacts() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0043");
        reportReversed(intent);
        String first = reversalRequests(intent).getFirst().get("id").toString();
        // The loan moves meanwhile (a cashier's repayment bumps its version).
        cashRepayment(loan, 100_000);
        ResponseEntity<JsonNode> stale = decide(first, "approve");
        assertThat(stale.getStatusCode().value()).as("%s", stale.getBody()).isEqualTo(422);
        assertThat(stale.getBody().get("code").asString()).isEqualTo("subject_changed");
        assertThat(row(intent).get("status")).isEqualTo("reversed_pending_review");
        reportReversed(intent);
        List<Map<String, Object>> requests = reversalRequests(intent);
        assertThat(requests).hasSize(2);
        assertThat(requests.getLast().get("status")).isEqualTo("pending");
        assertThat(decide(requests.getLast().get("id").toString(), "approve")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(row(intent).get("status")).isEqualTo("reversed");
        // Only the gateway repayment was reversed; the cashier's stands.
        assertThat(reversalCount(loan)).isEqualTo(1L);
    }

    @Test
    void aReversalWhileTheMoneyStillSitsInTheQueueClearsTheReceiptOnApproval() {
        UUID receipt = openReceipt(520_000, "CONF-224-0044");
        JsonNode intent = intentOfReceipt(receipt);
        long clearing = clearingBalance();
        reportReversed(intent);
        assertThat(row(intent).get("status")).isEqualTo("reversed_pending_review");
        String approval = reversalRequests(intent).getFirst().get("id").toString();
        // While the request waits, the receipt can no longer be refunded or allocated: the intent does not hold it.
        assertThat(askRefund(receipt, "gateway").getStatusCode().value()).isEqualTo(409);
        assertThat(decide(approval, "approve").getStatusCode().value()).isEqualTo(200);
        Map<String, Object> row = receiptRow(receipt);
        assertThat(row.get("status")).isEqualTo("reversed");
        assertThat(row.get("approval_id")).isEqualTo(UUID.fromString(approval));
        assertThat(unallocatedLiability()).isZero();
        assertThat(clearingBalance()).isEqualTo(clearing - 520_000);
        assertThat(row(intent).get("status")).isEqualTo("reversed");
        assertThat(decide(approval, "approve").getStatusCode().is2xxSuccessful())
                .isFalse();
        assertThat(unallocatedLiability()).isZero();
    }

    @Test
    void aReceiptAllocatedToALoanAndThenReversedByTheProviderReversesTheRepaymentItBecame() {
        UUID receipt = openReceipt(520_000, "CONF-224-0045");
        JsonNode intent = intentOfReceipt(receipt);
        String target = activeLoan();
        assertThat(decide(requested(askAllocate(receipt, PURPOSE, target)), "approve")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        long clearing = clearingBalance();
        reportReversed(intent);
        List<Map<String, Object>> requests = reversalRequests(intent);
        assertThat(requests).hasSize(1);
        assertThat(decide(requests.getFirst().get("id").toString(), "approve")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(row(intent).get("status")).isEqualTo("reversed");
        assertThat(reversalCount(target)).isEqualTo(1L);
        assertThat(clearingBalance()).isEqualTo(clearing - 520_000);
        assertThat(receiptRow(receipt).get("status")).isEqualTo("allocated");
    }

    @Test
    void aRefundedReceiptThenReportedReversedRaisesNothingBecauseNothingStandsToReverse() {
        UUID receipt = openReceipt(520_000, "CONF-224-0046");
        JsonNode intent = intentOfReceipt(receipt);
        String bank = assetAccount("1096", "Test bank 2");
        assertThat(mapMethod("bank", null, bank, true).getStatusCode().value()).isEqualTo(200);
        assertThat(decide(requested(askRefund(receipt, "bank")), "approve")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        reportReversed(intent);
        assertThat(row(intent).get("status")).isEqualTo("reversed_pending_review");
        assertThat(reversalRequests(intent)).isEmpty();
        assertThat(row(intent).get("next_check_at"))
                .as("nothing of ours to reverse: no more looks")
                .isNull();
    }

    @Test
    void aDepositReversedAfterItWasWithdrawnIsRefusedAndNeverMakesTheBalanceNegative() {
        String account = account();
        JsonNode intent = intentFor(account, SAVINGS, 520_000);
        pay(intent, 520_000, "CONF-224-0047");
        tick(Duration.ofMinutes(3));
        assertThat(row(intent).get("status")).isEqualTo("booked");
        assertThat(savingsBalance(account)).isEqualTo(520_000);
        // The member takes the money out.
        HttpHeaders h = as(cashier, cashierPerms, "*", null);
        h.add("Idempotency-Key", "wd-" + UUID.randomUUID());
        ResponseEntity<JsonNode> wd = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/withdrawals",
                h,
                Map.of("amount_minor", 500_000, "payment_method_key", "cash"));
        assertThat(wd.getStatusCode().value()).as("%s", wd.getBody()).isIn(201, 202);
        if (wd.getStatusCode().value() == 202) {
            ResponseEntity<JsonNode> ok = send(
                    HttpMethod.POST,
                    "/api/v1/approvals/"
                            + wd.getBody().get("approval_request_id").asString() + "/approve",
                    as(manager, managerPerms, "*", null),
                    Map.of("note", "Checked"));
            assertThat(ok.getStatusCode().value()).as("%s", ok.getBody()).isEqualTo(200);
        }
        long balance = savingsBalance(account);
        assertThat(balance).isEqualTo(20_000);
        reportReversed(intent);
        String approval = reversalRequests(intent).getFirst().get("id").toString();
        ResponseEntity<JsonNode> refused = decide(approval, "approve");
        assertThat(refused.getStatusCode().value()).as("%s", refused.getBody()).isEqualTo(422);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("approval_execution_failed");
        assertThat(refused.getBody().get("detail").asString()).contains("insufficient_balance");
        assertThat(savingsBalance(account)).isEqualTo(balance);
        assertThat(row(intent).get("status")).isEqualTo("reversed_pending_review");
        assertThat(reversalRequests(intent).getFirst().get("status")).isEqualTo("pending");
    }

    @Test
    void aReversalRequestOfAnotherBusinessIsNeitherVisibleNorDecidable() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0048");
        reportReversed(intent);
        String approval = reversalRequests(intent).getFirst().get("id").toString();
        TestDatabase.Fixture other = TestDatabase.tenant("other", true);
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", other.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", accountantPerms);
        h.add("X-Dev-Branch-Ids", "*");
        ResponseEntity<JsonNode> r =
                send(HttpMethod.POST, "/api/v1/approvals/" + approval + "/approve", h, Map.of("note", "x"));
        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(row(intent).get("status")).isEqualTo("reversed_pending_review");
        ResponseEntity<JsonNode> queue = send(HttpMethod.GET, UNALLOCATED, h, null);
        queue.getBody()
                .get("items")
                .forEach(i -> assertThat(i.get("order_reference").asString())
                        .isNotEqualTo(intent.get("merchant_reference").asString()));
    }

    // ---- failed, late and review windows --------------------------------------------------------

    @Test
    void aFailedAttemptThatThePayerRetriesAndPaysOnTheSameOrderStillBooksOnceTheNotificationArrives() {
        String loan = activeLoan();
        JsonNode intent = intentFor(loan, 520_000);
        fake.fail(tracking(intent));
        notifyProvider(intent);
        tick(Duration.ofSeconds(30));
        assertThat(row(intent).get("status")).isEqualTo("failed");
        assertThat(repayments(loan)).isEmpty();
        // The payer tries again on the same hosted order and it completes.
        pay(intent, 520_000, "CONF-224-0050");
        notifyProvider(intent);
        tick(Duration.ofSeconds(30));
        tick(Duration.ofMinutes(3));
        assertThat(row(intent).get("status")).isEqualTo("booked");
        assertThat(repayments(loan)).hasSize(1);
        tick(Duration.ofHours(3));
        assertThat(repayments(loan)).hasSize(1);
    }

    @Test
    void theReviewWindowIsSevenDaysForMobileMoneyAndWeeklyToOneHundredTwentyForACard() {
        java.time.Instant paid = java.time.Instant.parse("2026-10-01T06:00:00Z");
        // Mobile money: daily to day 7, then nothing.
        assertThat(CheckSchedule.nextReview(paid.plus(Duration.ofDays(3)), paid, "MTN"))
                .isEqualTo(paid.plus(Duration.ofDays(4)));
        assertThat(CheckSchedule.nextReview(paid.plus(Duration.ofDays(7)), paid, "AIRTEL"))
                .isNull();
        // A card: daily for 14 days, weekly after, to 120 days.
        assertThat(CheckSchedule.nextReview(paid.plus(Duration.ofDays(10)), paid, "VISA"))
                .isEqualTo(paid.plus(Duration.ofDays(11)));
        assertThat(CheckSchedule.nextReview(paid.plus(Duration.ofDays(20)), paid, "VISA"))
                .isEqualTo(paid.plus(Duration.ofDays(27)));
        assertThat(CheckSchedule.nextReview(paid.plus(Duration.ofDays(60)), paid, "MASTERCARD"))
                .isEqualTo(paid.plus(Duration.ofDays(67)));
        assertThat(CheckSchedule.nextReview(paid.plus(Duration.ofDays(118)), paid, "VISA"))
                .isNull();
        // M-Pesa under either spelling is a wallet; a string that merely mentions mobile money is not on the list and
        // is kept
        // under review as a card is.
        assertThat(CheckSchedule.mobileMoney("M-PESA")).isTrue();
        assertThat(CheckSchedule.mobileMoney(" mpesa ")).isTrue();
        assertThat(CheckSchedule.mobileMoney("MOBILE MONEY")).isFalse();
        assertThat(CheckSchedule.mobileMoney("MTN UGANDA CARD")).isFalse();
        // An unknown method is kept under review as a card is.
        assertThat(CheckSchedule.nextReview(paid.plus(Duration.ofDays(30)), paid, null))
                .isEqualTo(paid.plus(Duration.ofDays(37)));
    }

    @Test
    void aCardPaymentReversedSixtyDaysLaterRaisesExactlyOneRequest() {
        String loan = activeLoan();
        JsonNode intent = intentFor(loan, 520_000);
        fake.complete(tracking(intent), 520_000, "UGX", "CONF-224-0051", "VISA");
        tick(Duration.ofMinutes(3));
        assertThat(row(intent).get("status")).isEqualTo("booked");
        assertThat(row(intent).get("reported_method")).isEqualTo("VISA");
        for (int d = 0; d < 20; d++) {
            tick(Duration.ofDays(3));
        }
        assertThat(row(intent).get("status")).isEqualTo("booked");
        fake.reverse(tracking(intent));
        // Looked at again within the review window, however many notifications and polls arrive: one request.
        tick(Duration.ofDays(8));
        for (int d = 0; d < 3; d++) {
            notifyProvider(intent);
            tick(Duration.ofDays(1));
        }
        assertThat(row(intent).get("status")).isEqualTo("reversed_pending_review");
        assertThat(reversalRequests(intent)).hasSize(1);
    }

    // ---- exactly once under concurrency, and a tampered request ---------------------------------

    @Test
    void anAllocationAndARefundApprovedAtTheSameTimeSettleTheReceiptOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            UUID receipt = openReceipt(520_000, "CONF-224-006" + round);
            String target = activeLoan();
            String allocate = requested(askAllocate(receipt, PURPOSE, target));
            String refund = requested(askRefund(receipt, "gateway"));
            long liability = unallocatedLiability();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Future<Integer> a = pool.submit(() -> {
                go.await();
                return decide(allocate, "approve").getStatusCode().value();
            });
            java.util.concurrent.Future<Integer> r = pool.submit(() -> {
                go.await();
                return decide(refund, "approve").getStatusCode().value();
            });
            go.countDown();
            int first = a.get(60, java.util.concurrent.TimeUnit.SECONDS);
            int second = r.get(60, java.util.concurrent.TimeUnit.SECONDS);
            pool.shutdown();
            assertThat(java.util.List.of(first, second).stream()
                            .filter(c -> c == 200)
                            .count())
                    .as("statuses %s and %s", first, second)
                    .isEqualTo(1L);
            Map<String, Object> row = receiptRow(receipt);
            assertThat(row.get("status")).isIn("allocated", "refunded");
            assertThat(row.get("version")).isEqualTo(1);
            // One clearing entry, one liability release, whichever won.
            assertThat(unallocatedLiability()).isEqualTo(liability - 520_000);
            assertThat(TestDatabase.owner()
                            .sql("SELECT count(*) FROM journal_entries WHERE reverses_entry_id = ?")
                            .param(row.get("journal_entry_id"))
                            .query(Long.class)
                            .single())
                    .isEqualTo(1L);
            assertThat(repayments(target)).hasSize("allocated".equals(row.get("status")) ? 1 : 0);
        }
    }

    @Test
    void aRequestWhoseAmountWasTamperedWithIsClosedAndMovesNothing() {
        UUID receipt = openReceipt(520_000, "CONF-224-0070");
        String approval = requested(askRefund(receipt, "gateway"));
        // A refund carries no amount of its own: it is always the receipt's whole amount. A payload that says otherwise
        // is
        // not the request that was made.
        TestDatabase.owner()
                .sql(
                        "UPDATE approval_requests SET payload = jsonb_set(payload, '{amount_minor}', '900000') WHERE id = ?")
                .param(UUID.fromString(approval))
                .update();
        ResponseEntity<JsonNode> r = decide(approval, "approve");
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(409);
        assertThat(r.getBody().get("code").asString()).isEqualTo("receipt_changed");
        assertThat(receiptRow(receipt).get("status")).isEqualTo("open");
        assertThat(unallocatedLiability()).isEqualTo(520_000);
    }

    // ---- the attack review of part 1 --------------------------------------------------------------

    @Test
    void aCheckerWithoutTheLoansReversalPermissionCannotRejectTheReversalEither() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0090");
        reportReversed(intent);
        String approval = reversalRequests(intent).getFirst().get("id").toString();
        ResponseEntity<JsonNode> partial =
                decideAs(approval, "reject", accountant, "core.payments.allocate_approve,core.approvals.read");
        assertThat(partial.getStatusCode().value()).as("%s", partial.getBody()).isEqualTo(403);
        assertThat(reversalRequests(intent).getFirst().get("status")).isEqualTo("pending");
        assertThat(decide(approval, "reject").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aReceiptOfOneBranchAllocatedToALoanOfAnotherIsReversedOnlyByACheckerOfTheLoansBranch() {
        UUID hq = t.headOffice();
        UUID other = t.secondBranch();
        UUID receipt = openReceipt(520_000, "CONF-224-0091");
        JsonNode intent = intentOfReceipt(receipt);
        loansAt = other;
        String target = activeLoan();
        loansAt = null;
        assertThat(decide(requested(askAllocate(receipt, PURPOSE, target)), "approve")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        reportReversed(intent);
        Map<String, Object> request = reversalRequests(intent).getFirst();
        assertThat(request.get("branch_id")).as("raised where the loan is").isEqualTo(other);
        String approval = request.get("id").toString();
        String perms = permissionsOf("accountant");
        // A checker of the receipt's branch only cannot touch the other branch's loan.
        ResponseEntity<JsonNode> hqOnly = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/approve",
                as(accountant, perms, hq.toString(), null),
                Map.of("note", "x"));
        assertThat(hqOnly.getStatusCode().value()).isIn(403, 404);
        assertThat(reversalCount(target)).isZero();
        // A checker of the loan's branch only did not receive the money there: refused to approve AND to reject, and
        // the
        // queue does not offer the decision.
        ResponseEntity<JsonNode> otherOnly = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/approve",
                as(accountant, perms, other.toString(), null),
                Map.of("note", "x"));
        assertThat(otherOnly.getStatusCode().value())
                .as("%s", otherOnly.getBody())
                .isEqualTo(403);
        ResponseEntity<JsonNode> otherReject = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/reject",
                as(accountant, perms, other.toString(), null),
                Map.of("note", "no"));
        assertThat(otherReject.getStatusCode().value())
                .as("%s", otherReject.getBody())
                .isEqualTo(403);
        assertThat(send(
                                HttpMethod.GET,
                                "/api/v1/approvals/" + approval,
                                as(accountant, perms, other.toString(), null),
                                null)
                        .getBody()
                        .get("can_decide")
                        .asBoolean())
                .isFalse();
        assertThat(reversalRequests(intent).getFirst().get("status")).isEqualTo("pending");
        assertThat(reversalCount(target)).isZero();
        // One who decides in both branches reverses it.
        ResponseEntity<JsonNode> both = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/approve",
                as(accountant, perms, hq + "," + other, null),
                Map.of("note", "x"));
        assertThat(both.getStatusCode().value()).as("%s", both.getBody()).isEqualTo(200);
        assertThat(reversalCount(target)).isEqualTo(1L);
    }

    @Test
    void aReceiptOfOneBranchAllocatedToALoanOfAnotherNeedsACheckerOfBothBranchesAndAnAdministratorIsOne() {
        UUID hq = t.headOffice();
        UUID other = t.secondBranch();
        UUID receipt = openReceipt(520_000, "CONF-224-0105");
        loansAt = other;
        String target = activeLoan();
        loansAt = null;
        String approval = requested(askAllocate(receipt, PURPOSE, target));
        String perms = permissionsOf("accountant");
        // The request sits in the receipt's branch; the money goes to a loan of the other. A checker of the receipt's
        // branch
        // only is refused when it runs, and so is one of the loan's branch only (it cannot see the request).
        ResponseEntity<JsonNode> hqOnly = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/approve",
                as(accountant, perms, hq.toString(), null),
                Map.of("note", "x"));
        assertThat(hqOnly.getStatusCode().value()).as("%s", hqOnly.getBody()).isEqualTo(422);
        assertThat(repayments(target)).isEmpty();
        assertThat(receiptRow(receipt).get("status")).isEqualTo("open");
        ResponseEntity<JsonNode> otherOnly = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/approve",
                as(accountant, perms, other.toString(), null),
                Map.of("note", "x"));
        assertThat(otherOnly.getStatusCode().value()).isIn(403, 404);
        // One who decides in both branches, which the tenant administrator always does, allocates it.
        ResponseEntity<JsonNode> both = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/approve",
                as(staff, adminPerms, "*", null),
                Map.of("note", "x"));
        assertThat(both.getStatusCode().value()).as("%s", both.getBody()).isEqualTo(200);
        assertThat(repayments(target)).hasSize(1);
    }

    @Test
    void anAllocationBackDatedIntoAnEarlierMonthClearsTheLiabilityOnTheDayTheRepaymentIsBooked() {
        UUID receipt = openReceipt(520_000, "CONF-224-0092");
        String target = activeLoan();
        assertThat(balanceAsOf("unallocated_receipts", "2026-10-31")).isEqualTo(-520_000);
        // Weeks later, in the next month, the cashier and the checker deal with it.
        clock.set(java.time.Instant.parse("2026-11-03T06:00:00Z"));
        assertThat(decide(requested(askAllocate(receipt, PURPOSE, target)), "approve")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        Map<String, Object> repayment = repayments(target).getFirst();
        assertThat(repayment.get("value_date").toString()).isEqualTo("2026-10-16");
        Map<String, Object> cleared = TestDatabase.owner()
                .sql("SELECT entry_date FROM journal_entries WHERE id = ?")
                .param(receiptRow(receipt).get("cleared_entry_id"))
                .query()
                .singleRow();
        assertThat(cleared.get("entry_date").toString())
                .as("dated with the repayment")
                .isEqualTo("2026-10-16");
        // October closes with the liability gone and the money in the clearing account once.
        assertThat(balanceAsOf("unallocated_receipts", "2026-10-31")).isZero();
        assertThat(balanceAsOf("gateway_clearing", "2026-10-31")).isEqualTo(520_000);
        assertThat(balanceAsOf("unallocated_receipts", "2026-11-30")).isZero();
        assertThat(balanceAsOf("gateway_clearing", "2026-11-30")).isEqualTo(520_000);
    }

    @Test
    void anAllocationWhoseOriginalMonthIsClosedByThenIsBookedAndClearedTodayTogether() {
        UUID receipt = openReceipt(520_000, "CONF-224-0102");
        String target = activeLoan();
        // October is closed before the checker deals with it, so the repayment cannot be dated into it.
        TestDatabase.owner()
                .sql("UPDATE gl_periods SET status = 'closed' WHERE tenant_id = ? AND year = 2026 AND month = 10")
                .param(t.tenantId())
                .update();
        clock.set(java.time.Instant.parse("2026-11-03T06:00:00Z"));
        ResponseEntity<JsonNode> done = decide(requested(askAllocate(receipt, PURPOSE, target)), "approve");
        assertThat(done.getStatusCode().value()).as("%s", done.getBody()).isEqualTo(200);
        assertThat(repayments(target).getFirst().get("value_date").toString()).isEqualTo("2026-11-03");
        Map<String, Object> cleared = TestDatabase.owner()
                .sql("SELECT entry_date FROM journal_entries WHERE id = ?")
                .param(receiptRow(receipt).get("cleared_entry_id"))
                .query()
                .singleRow();
        assertThat(cleared.get("entry_date").toString())
                .as("dated with the repayment: today")
                .isEqualTo("2026-11-03");
        // October keeps what it had (the receipt in the liability); November carries the release and the repayment
        // together.
        assertThat(balanceAsOf("unallocated_receipts", "2026-10-31")).isEqualTo(-520_000);
        assertThat(balanceAsOf("unallocated_receipts", "2026-11-30")).isZero();
        assertThat(balanceAsOf("gateway_clearing", "2026-11-30")).isEqualTo(520_000);
    }

    @Test
    void theDatabaseTakesAnOrderWithABookedTransactionOutOfNeedsAttentionOnlyThroughTheApprovedReversal() {
        var owner = TestDatabase.owner();
        // One order whose money stands in a loan (it carries the booked transaction) and one that was never booked,
        // both
        // handed to a person.
        UUID withTxn = UUID.fromString(
                paidAndHeld(activeLoan(), "CONF-224-0110", true).get("id").asString());
        UUID without = UUID.fromString(
                paidAndHeld(activeLoan(), "CONF-224-0111", false).get("id").asString());
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> owner.sql("UPDATE payment_intents SET status = 'reversed' WHERE id = ?")
                                .param(withTxn)
                                .update()))
                .hasMessageContaining("refused for an order with a booked transaction");
        assertThat(owner.sql("UPDATE payment_intents SET status = 'reversed' WHERE id = ?")
                        .param(without)
                        .update())
                .isEqualTo(1);
        // A reversed order never goes back to anything, and the new returns to booked are only from review.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> owner.sql("UPDATE payment_intents SET status = 'booked' WHERE id = ?")
                                .param(without)
                                .update()))
                .hasMessageContaining("not an allowed transition");
    }

    /** An order paid and handed to a person (needs_attention), with or without a booked transaction recorded on it. */
    JsonNode paidAndHeld(String loan, String code, boolean withBookedTxn) {
        JsonNode intent = intentFor(loan, 520_000);
        UUID id = UUID.fromString(intent.get("id").asString());
        TestDatabase.owner().sql("""
                        UPDATE payment_intents SET status = 'paid', paid_amount_minor = 520000, paid_currency = 'UGX',
                               confirmation_code = ?, paid_at = now(), verified_at = now(), next_check_at = NULL WHERE id = ?
                        """).params(code, id).update();
        TestDatabase.owner()
                .sql("""
                        UPDATE payment_intents SET status = 'needs_attention', booked_subject_txn_id = ?,
                               booked_at = CASE WHEN ?::uuid IS NULL THEN NULL ELSE now() END WHERE id = ?
                        """)
                .params(withBookedTxn ? UUID.randomUUID() : null, withBookedTxn ? UUID.randomUUID() : null, id)
                .update();
        return intent;
    }

    @Test
    void theQueueCanBeFilteredToReversedReceiptsAndRefusesAStatusItDoesNotKnow() {
        UUID receipt = openReceipt(520_000, "CONF-224-0093");
        JsonNode intent = intentOfReceipt(receipt);
        reportReversed(intent);
        assertThat(decide(reversalRequests(intent).getFirst().get("id").toString(), "approve")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        ResponseEntity<JsonNode> list =
                send(HttpMethod.GET, UNALLOCATED + "?status=reversed", as(cashier, cashierPerms, "*", null), null);
        assertThat(list.getStatusCode().value()).as("%s", list.getBody()).isEqualTo(200);
        List<String> ids = new java.util.ArrayList<>();
        list.getBody().get("items").forEach(i -> ids.add(i.get("id").asString()));
        assertThat(ids).contains(receipt.toString());
        assertThat(send(HttpMethod.GET, UNALLOCATED + "?status=bogus", as(cashier, cashierPerms, "*", null), null)
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
    }

    @Test
    void anUnsecuredReopenIsListedOnTheRequestAndNeedsTheAuthorityToLetCollateralGoAndEndsWhenApproved() {
        String[] s = securedLoanPaidOffByGateway("UNSEC224");
        TestDatabase.owner()
                .sql("UPDATE lending_collateral_items SET custody_status = 'released' WHERE id = ?::uuid")
                .param(s[1])
                .update();
        JsonNode intent = new tools.jackson.databind.ObjectMapper()
                .createObjectNode()
                .put("id", s[2])
                .put("merchant_reference", s[3]);
        reportReversed(intent);
        Map<String, Object> request = reversalRequests(intent).getFirst();
        assertThat(request.get("payload").toString())
                .contains("reopen_would_be_unsecured", "collateral_not_restored_on_reopen", s[1]);
        String approval = request.get("id").toString();
        // The accountant may reverse a repayment but not let collateral go: neither approve nor reject.
        assertThat(decide(approval, "approve").getStatusCode().value()).isEqualTo(403);
        assertThat(decide(approval, "reject").getStatusCode().value()).isEqualTo(403);
        assertThat(get(s[0], "").get("status").asString()).isEqualTo("closed");
        // The branch manager may, with the items listed on the request there is nothing left to go stale on.
        UUID branchManager = UUID.randomUUID();
        ResponseEntity<JsonNode> done = decideAs(approval, "approve", branchManager, managerPerms);
        assertThat(done.getStatusCode().value()).as("%s", done.getBody()).isEqualTo(200);
        assertThat(get(s[0], "").get("status").asString()).isEqualTo("active");
        assertThat(row(intent).get("status")).isEqualTo("reversed");
        assertThat(reversalRequests(intent)).hasSize(1);
    }

    @Test
    void aRejectedReversalAndAnOrderWithNothingToReverseAreNotLookedAtAgain() {
        String loan = activeLoan();
        JsonNode rejected = bookedRepayment(loan, 520_000, "CONF-224-0094");
        reportReversed(rejected);
        assertThat(row(rejected).get("next_check_at"))
                .as("a request waits: looked at weekly")
                .isNotNull();
        assertThat(decide(reversalRequests(rejected).getFirst().get("id").toString(), "reject")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        reportReversed(rejected);
        assertThat(row(rejected).get("next_check_at"))
                .as("rejected: nothing more to look at")
                .isNull();
        int asked = fake.queryCount();
        tick(Duration.ofDays(30));
        assertThat(fake.queryCount()).isEqualTo(asked);
        assertThat(reversalRequests(rejected)).hasSize(1);
    }

    @Test
    void aReversalThatKeepsGoingStaleIsHandedToAPersonWhoCanPutItToACheckerAgain() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0095");
        for (int round = 1; round <= 3; round++) {
            reportReversed(intent);
            assertThat(reversalRequests(intent)).hasSize(round);
            // The loan moves under the request each time, so deciding it makes it stale.
            cashRepayment(loan, 10_000);
            ResponseEntity<JsonNode> stale =
                    decide(reversalRequests(intent).getLast().get("id").toString(), "approve");
            assertThat(stale.getBody().get("code").asString()).isEqualTo("subject_changed");
        }
        reportReversed(intent);
        assertThat(reversalRequests(intent)).as("no fourth request by itself").hasSize(3);
        // Handed over: still in review (it is), with the word that says why nobody is looking at it, and no weekly
        // look.
        assertThat(row(intent).get("status")).isEqualTo("reversed_pending_review");
        assertThat(row(intent).get("refusal_code")).isEqualTo("reversal_unresolved");
        assertThat(row(intent).get("next_check_at")).isNull();
        assertThat(repayments(loan)).hasSize(4);
        int asked = fake.queryCount();
        tick(Duration.ofDays(30));
        assertThat(fake.queryCount()).as("not polled").isEqualTo(asked);
        // The way out: a holder of allocate_approve puts it to a checker again. The asker is the maker of the new
        // request and so
        // can never decide it; a second checker does.
        UUID id = UUID.fromString(intent.get("id").asString());
        assertThat(reask(intent, cashier, cashierPerms).getStatusCode().value()).isEqualTo(403);
        ResponseEntity<JsonNode> again = reask(intent, accountant, accountantPerms);
        assertThat(again.getStatusCode().value()).as("%s", again.getBody()).isEqualTo(202);
        assertThat(reversalRequests(intent)).hasSize(4);
        assertThat(row(intent).get("refusal_code")).isNull();
        String newRequest = again.getBody().get("approval_request_id").asString();
        assertThat(TestDatabase.owner()
                        .sql("SELECT requested_by FROM approval_requests WHERE id = ?")
                        .param(UUID.fromString(newRequest))
                        .query(UUID.class)
                        .single())
                .as("the person is the maker, not the platform")
                .isEqualTo(accountant);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'core.payments.intent.reversal_requested_again' AND actor_user_id = ?")
                        .params(id, accountant)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        // Asking twice is one request.
        ResponseEntity<JsonNode> twice = reask(intent, accountant, accountantPerms);
        assertThat(twice.getBody().get("approval_request_id").asString()).isEqualTo(newRequest);
        assertThat(reversalRequests(intent)).hasSize(4);
        ResponseEntity<JsonNode> self = decideAs(newRequest, "approve", accountant, accountantPerms);
        assertThat(self.getStatusCode().value()).isEqualTo(422);
        assertThat(code(self)).isEqualTo("self_approval_forbidden");
        assertThat(reversalCount(loan)).isZero();
        assertThat(decideAs(newRequest, "approve", manager, managerPerms)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(row(intent).get("status")).isEqualTo("reversed");
        assertThat(reversalCount(loan)).isEqualTo(1L);
    }

    @Test
    void aPaymentReportedCompletedAgainAfterAReversalGoesBackToBookedAndTheRequestEndsWithANote() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0096");
        reportReversed(intent);
        String approval = reversalRequests(intent).getFirst().get("id").toString();
        long counted = counterToday("completed_after_reversal");
        // The provider takes the reversal back.
        pay(intent, 520_000, "CONF-224-0096");
        notifyProvider(intent);
        tick(Duration.ofSeconds(30));
        assertThat(row(intent).get("status")).isEqualTo("booked");
        assertThat(row(intent).get("refusal_code")).isNull();
        Map<String, Object> request = reversalRequests(intent).getFirst();
        assertThat(request.get("status")).isEqualTo("stale");
        assertThat(request.get("decision_note").toString()).contains("completed again");
        assertThat(counterToday("completed_after_reversal")).as("counted once").isEqualTo(counted + 1);
        // The checker can no longer approve it, and nothing moves.
        assertThat(decide(approval, "approve").getStatusCode().value()).isEqualTo(409);
        assertThat(reversalCount(loan)).isZero();
        assertThat(repayments(loan)).hasSize(1);
        // A new reversal starts a new request.
        reportReversed(intent);
        assertThat(reversalRequests(intent)).hasSize(2);
        assertThat(row(intent).get("status")).isEqualTo("reversed_pending_review");
    }

    /** A person asks again, after the provider throttle (ten seconds) since its last answer. */
    ResponseEntity<JsonNode> reask(JsonNode intent, UUID user, String perms) {
        clock.set(clock.instant().plus(CheckSchedule.THROTTLE).plusSeconds(1));
        return reaskAtOnce(intent, user, perms);
    }

    ResponseEntity<JsonNode> reaskAtOnce(JsonNode intent, UUID user, String perms) {
        return send(
                HttpMethod.POST,
                "/api/v1/payments/intents/" + intent.get("id").asString() + "/reversal-request",
                as(user, perms, "*", null),
                null);
    }

    String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    long counterToday(String outcome) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(hits), 0) FROM payment_callback_counters WHERE outcome = ?")
                .param(outcome)
                .query(Long.class)
                .single();
    }

    @Test
    void aRejectedReversalIsHandedOverAtOnceAndAPersonCanPutItToACheckerAgain() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0097");
        reportReversed(intent);
        String approval = reversalRequests(intent).getFirst().get("id").toString();
        assertThat(decide(approval, "reject").getStatusCode().value()).isEqualTo(200);
        // At once, not at the next look: no longer counted as waiting for a checker.
        assertThat(row(intent).get("status")).isEqualTo("reversed_pending_review");
        assertThat(row(intent).get("refusal_code")).isEqualTo("reversal_rejected");
        assertThat(row(intent).get("next_check_at")).isNull();
        reportReversed(intent);
        assertThat(reversalRequests(intent)).hasSize(1);
        // A person asks again (the branch manager here; the accountant rejected it). The asker is the maker and cannot
        // decide it,
        // and neither can the one who rejected the earlier request: only a third checker (the administrator) can.
        ResponseEntity<JsonNode> again = reask(intent, manager, managerPerms);
        assertThat(again.getStatusCode().value()).as("%s", again.getBody()).isEqualTo(202);
        assertThat(reversalRequests(intent)).hasSize(2);
        assertThat(row(intent).get("refusal_code")).isNull();
        String second = again.getBody().get("approval_request_id").asString();
        ResponseEntity<JsonNode> asker = decideAs(second, "approve", manager, managerPerms);
        assertThat(code(asker)).isEqualTo("self_approval_forbidden");
        ResponseEntity<JsonNode> rejecter = decideAs(second, "approve", accountant, accountantPerms);
        assertThat(rejecter.getStatusCode().value()).isEqualTo(422);
        assertThat(code(rejecter)).isEqualTo("approver_conflict");
        assertThat(reversalCount(loan)).isZero();
        assertThat(decideAs(second, "approve", staff, adminPerms)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(row(intent).get("status")).isEqualTo("reversed");
        // An order that is not waiting for a reversal is told so.
        assertThat(reask(intent, accountant, accountantPerms).getStatusCode().value())
                .isEqualTo(409);
    }

    @Test
    void aReversalIsNotPutToACheckerAgainWhenTheProviderNowSaysThePaymentIsCompletedOrCannotBeAsked() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0103");
        reportReversed(intent);
        assertThat(decide(reversalRequests(intent).getFirst().get("id").toString(), "reject")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(row(intent).get("refusal_code")).isEqualTo("reversal_rejected");
        // The provider cannot be asked: nothing is raised.
        fake.failQuery(new GatewayException(GatewayException.Kind.UNAVAILABLE, 503, "down"));
        ResponseEntity<JsonNode> down = reask(intent, manager, managerPerms);
        fake.failQuery(null);
        assertThat(down.getStatusCode().value()).isEqualTo(409);
        assertThat(code(down)).isEqualTo("try_again");
        assertThat(reversalRequests(intent)).hasSize(1);
        // The provider now says it is paid: the order goes back to booked and nothing is raised or reversed.
        pay(intent, 520_000, "CONF-224-0103");
        ResponseEntity<JsonNode> completed = reask(intent, manager, managerPerms);
        assertThat(completed.getStatusCode().value()).isEqualTo(409);
        assertThat(code(completed)).isEqualTo("completed_again");
        assertThat(auditCount(intent, "completed_after_reversal"))
                .as("counted once")
                .isEqualTo(1L);
        assertThat(row(intent).get("status")).isEqualTo("booked");
        assertThat(reversalRequests(intent)).hasSize(1);
        assertThat(reversalCount(loan)).isZero();
    }

    long auditCount(JsonNode intent, String action) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = ?")
                .params(UUID.fromString(intent.get("id").asString()), "core.payments.intent." + action)
                .query(Long.class)
                .single();
    }

    /** Rejected by the accountant, handed over; the branch manager asks again (a second request). */
    String rejectedThenAskedByTheManager(JsonNode intent) {
        reportReversed(intent);
        assertThat(decide(reversalRequests(intent).getFirst().get("id").toString(), "reject")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        ResponseEntity<JsonNode> again = reask(intent, manager, managerPerms);
        assertThat(again.getStatusCode().value()).as("%s", again.getBody()).isEqualTo(202);
        return again.getBody().get("approval_request_id").asString();
    }

    void assertNeitherTheRejecterNorTheEarlierAskerCanDecideTheNext(JsonNode intent) {
        ResponseEntity<JsonNode> third = reask(intent, staff, adminPerms);
        assertThat(third.getStatusCode().value()).as("%s", third.getBody()).isEqualTo(202);
        String next = third.getBody().get("approval_request_id").asString();
        for (UUID barred : List.of(manager, accountant)) {
            String perms = barred.equals(manager) ? managerPerms : accountantPerms;
            ResponseEntity<JsonNode> r = decideAs(next, "approve", barred, perms);
            assertThat(r.getStatusCode().value()).isEqualTo(422);
            assertThat(code(r)).isEqualTo("approver_conflict");
            assertThat(send(HttpMethod.GET, "/api/v1/approvals/" + next, as(barred, perms, "*", null), null)
                            .getBody()
                            .get("can_decide")
                            .asBoolean())
                    .as("the queue does not offer it to one who is barred")
                    .isFalse();
        }
    }

    @Test
    void aPersonWhoAskedAgainAndCancelledCannotGetRoundTheRejectionByLettingThePlatformAskNext() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0110");
        String second = rejectedThenAskedByTheManager(intent);
        assertThat(send(
                                HttpMethod.POST,
                                "/api/v1/approvals/" + second + "/cancel",
                                as(manager, managerPerms, "*", null),
                                null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        // The platform's next weekly look does not raise a request of its own for the manager to approve.
        tick(Duration.ofDays(8));
        assertThat(reversalRequests(intent)).hasSize(2);
        assertThat(row(intent).get("refusal_code")).isEqualTo("reversal_rejected");
        assertThat(row(intent).get("next_check_at")).isNull();
        assertNeitherTheRejecterNorTheEarlierAskerCanDecideTheNext(intent);
        assertThat(reversalCount(loan)).isZero();
    }

    @Test
    void aPersonsRequestThatExpiredIsNotFollowedByARequestOfThePlatformsEither() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0111");
        rejectedThenAskedByTheManager(intent);
        // Nobody decides it; the week passes with the request past its expiry but not yet swept.
        tick(Duration.ofDays(8));
        assertThat(reversalRequests(intent)).hasSize(2);
        assertThat(row(intent).get("refusal_code")).isEqualTo("reversal_rejected");
        assertNeitherTheRejecterNorTheEarlierAskerCanDecideTheNext(intent);
        assertThat(reversalCount(loan)).isZero();
    }

    @Test
    void aRequestPastItsExpiryIsNotWaitingSoTheNextLookEndsItAndRaisesAFreshOne() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0112");
        reportReversed(intent);
        tick(Duration.ofDays(8));
        List<Map<String, Object>> requests = reversalRequests(intent);
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst().get("status")).isEqualTo("stale");
        assertThat(requests.getLast().get("status")).isEqualTo("pending");
        assertThat(((java.sql.Timestamp) requests.getLast().get("expires_at")).toInstant())
                .isAfter(clock.instant());
    }

    @Test
    void aCheckerHoldingAnExpiredRequestMakesTheNextLookAndAReAskTryAgainInsteadOfReusingIt() throws Exception {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0113");
        reportReversed(intent);
        String approval = reversalRequests(intent).getFirst().get("id").toString();
        try (java.sql.Connection held = TestDatabase.ownerDataSource().getConnection()) {
            held.setAutoCommit(false);
            try (java.sql.PreparedStatement lock =
                    held.prepareStatement("SELECT 1 FROM approval_requests WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, UUID.fromString(approval));
                lock.executeQuery();
                tick(Duration.ofDays(8));
                // Not ended (it is being decided), not raised again and not looked at for good: tried again later.
                assertThat(reversalRequests(intent)).hasSize(1);
                assertThat(row(intent).get("refusal_code")).isNull();
                assertThat(row(intent).get("next_check_at")).isNotNull();
                // The person's ask is told to try again, and is not handed that request as their own.
                ResponseEntity<JsonNode> r = reask(intent, accountant, accountantPerms);
                assertThat(r.getStatusCode().value()).isEqualTo(409);
                assertThat(code(r)).isEqualTo("try_again");
                assertThat(reversalRequests(intent)).hasSize(1);
            } finally {
                held.rollback();
            }
        }
    }

    @Test
    void anOrderThatIsNotWaitingForAReversalIsNeverAskedAboutOrTouchedByAReAsk() {
        String loan = activeLoan();
        JsonNode pending = intentFor(loan, 520_000);
        int asked = fake.queryCount();
        ResponseEntity<JsonNode> r = reask(pending, accountant, accountantPerms);
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("not_in_review");
        assertThat(row(pending).get("status")).isEqualTo("pending");
        assertThat(fake.queryCount()).isEqualTo(asked);
        // Paid at the provider but not yet booked: the booking is not run by this endpoint either.
        pay(pending, 520_000, "CONF-224-0114");
        ResponseEntity<JsonNode> paid = reask(pending, accountant, accountantPerms);
        assertThat(code(paid)).isEqualTo("not_in_review");
        assertThat(row(pending).get("status")).isEqualTo("pending");
        assertThat(fake.queryCount()).isEqualTo(asked);
        tick(Duration.ofMinutes(3));
        assertThat(row(pending).get("status")).isEqualTo("booked");
        asked = fake.queryCount();
        ResponseEntity<JsonNode> booked = reask(pending, accountant, accountantPerms);
        assertThat(booked.getStatusCode().value()).isEqualTo(409);
        assertThat(code(booked)).isEqualTo("completed_again");
        assertThat(fake.queryCount()).isEqualTo(asked);
    }

    @Test
    void askingAgainInQuickSuccessionAsksTheProviderOnceAndReturnsTheSameRequest() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0115");
        reportReversed(intent);
        assertThat(decide(reversalRequests(intent).getFirst().get("id").toString(), "reject")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        int before = fake.queryCount();
        ResponseEntity<JsonNode> first = reask(intent, manager, managerPerms);
        assertThat(first.getStatusCode().value()).as("%s", first.getBody()).isEqualTo(202);
        assertThat(fake.queryCount()).isEqualTo(before + 1);
        // At once again, and from another caller: the answer of a moment ago is used, the provider is not asked.
        ResponseEntity<JsonNode> second = reaskAtOnce(intent, manager, managerPerms);
        ResponseEntity<JsonNode> third = reaskAtOnce(intent, accountant, accountantPerms);
        assertThat(fake.queryCount()).isEqualTo(before + 1);
        assertThat(second.getBody().get("approval_request_id").asString())
                .isEqualTo(first.getBody().get("approval_request_id").asString());
        assertThat(third.getBody().get("approval_request_id").asString())
                .isEqualTo(first.getBody().get("approval_request_id").asString());
        assertThat(reversalRequests(intent)).hasSize(2);
    }

    @Test
    void aProviderThatNoLongerSaysReversedGetsADistinctAnswerAndNothingIsRaised() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0116");
        reportReversed(intent);
        assertThat(decide(reversalRequests(intent).getFirst().get("id").toString(), "reject")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        fake.forceStatus(PaymentGateway.Status.FAILED);
        ResponseEntity<JsonNode> r;
        try {
            r = reask(intent, manager, managerPerms);
        } finally {
            fake.forceStatus(null);
        }
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(code(r)).isEqualTo("provider_disagrees");
        assertThat(reversalRequests(intent)).hasSize(1);
        assertThat(row(intent).get("status")).isEqualTo("reversed_pending_review");
        // An account with no credentials, or one the provider refuses, cannot be asked: try again.
        for (GatewayException failure : List.of(
                new GatewayException(GatewayException.Kind.UNAVAILABLE, 0, "not_configured"),
                new GatewayException(GatewayException.Kind.AUTH, 200, "invalid_consumer_key_or_secret_provided"))) {
            fake.failQuery(failure);
            try {
                ResponseEntity<JsonNode> down = reask(intent, manager, managerPerms);
                assertThat(down.getStatusCode().value()).isEqualTo(409);
                assertThat(code(down)).isEqualTo("try_again");
            } finally {
                fake.failQuery(null);
            }
        }
        assertThat(reversalRequests(intent)).hasSize(1);
    }

    @Test
    void anEpisodeThatKeepsGoingStaleIsHandedOverAfterItsOwnThreeRequestsWhateverWasAskedBefore() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0117");
        reportReversed(intent);
        assertThat(decide(reversalRequests(intent).getFirst().get("id").toString(), "reject")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        pay(intent, 520_000, "CONF-224-0117");
        notifyProvider(intent);
        tick(Duration.ofSeconds(30));
        assertThat(row(intent).get("status")).isEqualTo("booked");
        // Episode two: three requests that each go stale, then no fourth by itself.
        for (int round = 1; round <= 3; round++) {
            reportReversed(intent);
            assertThat(reversalRequests(intent)).hasSize(1 + round);
            cashRepayment(loan, 10_000);
            assertThat(code(decide(reversalRequests(intent).getLast().get("id").toString(), "approve")))
                    .isEqualTo("subject_changed");
        }
        reportReversed(intent);
        assertThat(reversalRequests(intent))
                .as("episode one's request plus three")
                .hasSize(4);
        assertThat(row(intent).get("refusal_code")).isEqualTo("reversal_unresolved");
    }

    @Test
    void aReversalReportedAgainAfterOneWasRejectedAndTakenBackIsANewEpisodeWithItsOwnRequests() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0104");
        // Episode one: reported, rejected (handed over), then the provider takes it back.
        reportReversed(intent);
        assertThat(decide(reversalRequests(intent).getFirst().get("id").toString(), "reject")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        pay(intent, 520_000, "CONF-224-0104");
        notifyProvider(intent);
        tick(Duration.ofSeconds(30));
        assertThat(row(intent).get("status")).isEqualTo("booked");
        // Episode two: reversed again. The earlier rejection and its count say nothing about it: a new request is
        // raised.
        reportReversed(intent);
        assertThat(row(intent).get("status")).isEqualTo("reversed_pending_review");
        assertThat(row(intent).get("refusal_code")).isNull();
        List<Map<String, Object>> requests = reversalRequests(intent);
        assertThat(requests).hasSize(2);
        assertThat(requests.getLast().get("status")).isEqualTo("pending");
        // Episodes three and four each get a fresh request of their own, however many were asked before.
        for (int episode = 3; episode <= 4; episode++) {
            pay(intent, 520_000, "CONF-224-0104");
            notifyProvider(intent);
            tick(Duration.ofSeconds(30));
            assertThat(row(intent).get("status")).isEqualTo("booked");
            reportReversed(intent);
            assertThat(row(intent).get("status")).isEqualTo("reversed_pending_review");
            assertThat(row(intent).get("refusal_code"))
                    .as("episode %s", episode)
                    .isNull();
            assertThat(reversalRequests(intent).getLast().get("status")).isEqualTo("pending");
        }
        assertThat(reversalRequests(intent)).hasSize(4);
        assertThat(reversalRequests(intent).stream()
                        .filter(r -> "pending".equals(r.get("status")))
                        .count())
                .isEqualTo(1L);
    }

    @Test
    void aProviderThatCannotBeReachedDoesNotEndTheReviewOfABookedPaymentOrOfAReversalWaitingForACheckerForGood() {
        String loan = activeLoan();
        JsonNode booked = bookedRepayment(loan, 520_000, "CONF-224-0098");
        JsonNode waiting = bookedRepayment(activeLoan(), 310_000, "CONF-224-0099");
        reportReversed(waiting);
        TestDatabase.owner()
                .sql("UPDATE payment_intents SET next_check_at = ? WHERE id IN (?, ?)")
                .params(
                        java.sql.Timestamp.from(clock.instant()),
                        UUID.fromString(booked.get("id").asString()),
                        UUID.fromString(waiting.get("id").asString()))
                .update();
        fake.failQuery(new GatewayException(GatewayException.Kind.UNAVAILABLE, 503, "down"));
        poller.tick();
        fake.failQuery(null);
        assertThat(row(booked).get("next_check_at"))
                .as("a booked payment keeps its review")
                .isNotNull();
        assertThat(row(waiting).get("next_check_at"))
                .as("a reversal waiting for a checker keeps its look")
                .isNotNull();
    }

    @Test
    void aPaymentReportedCompletedAfterItsReversalWasApprovedIsCountedForAPersonNotQuietlyDropped() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0100");
        reportReversed(intent);
        assertThat(decide(reversalRequests(intent).getFirst().get("id").toString(), "approve")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        long counted = counterToday("completed_after_reversal");
        pay(intent, 520_000, "CONF-224-0100");
        notifyProvider(intent);
        tick(Duration.ofSeconds(30));
        assertThat(row(intent).get("status")).isEqualTo("reversed");
        assertThat(counterToday("completed_after_reversal")).isEqualTo(counted + 1);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT outcome FROM payment_callbacks WHERE intent_id = ? ORDER BY created_at DESC LIMIT 1")
                        .param(UUID.fromString(intent.get("id").asString()))
                        .query(String.class)
                        .single())
                .isEqualTo("status_mismatch");
        // Told once, not polled for ever: past the lease several times, nothing is asked and nothing is counted again.
        assertThat(row(intent).get("next_check_at")).isNull();
        int asked = fake.queryCount();
        for (int i = 0; i < 5; i++) {
            tick(CheckSchedule.LEASE.plusSeconds(30));
        }
        assertThat(fake.queryCount()).as("not polled").isEqualTo(asked);
        assertThat(counterToday("completed_after_reversal")).isEqualTo(counted + 1);
        // A later notification asks again once and is not counted again.
        notifyProvider(intent);
        tick(Duration.ofSeconds(30));
        assertThat(counterToday("completed_after_reversal")).isEqualTo(counted + 1);
        assertThat(row(intent).get("next_check_at")).isNull();
    }

    @Test
    void aCheckerDecidingTheRequestAtTheMomentTheProviderTakesTheReversalBackNeitherDeadlocksNorReverses()
            throws Exception {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-0101");
        reportReversed(intent);
        String approval = reversalRequests(intent).getFirst().get("id").toString();
        // A checker has the request row open (deciding it): the status check must not wait for it.
        try (java.sql.Connection held = TestDatabase.ownerDataSource().getConnection()) {
            held.setAutoCommit(false);
            try (java.sql.PreparedStatement lock =
                    held.prepareStatement("SELECT 1 FROM approval_requests WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, UUID.fromString(approval));
                lock.executeQuery();
                pay(intent, 520_000, "CONF-224-0101");
                notifyProvider(intent);
                tick(Duration.ofSeconds(30));
                assertThat(row(intent).get("status")).isEqualTo("booked");
            } finally {
                held.rollback();
            }
        }
        // The checker, arriving after, finds the payment no longer in review: nothing is reversed.
        ResponseEntity<JsonNode> late = decide(approval, "approve");
        assertThat(late.getStatusCode().value()).as("%s", late.getBody()).isGreaterThanOrEqualTo(409);
        assertThat(reversalCount(loan)).isZero();
        assertThat(repayments(loan)).hasSize(1);
    }
}
