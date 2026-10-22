package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.payments.FakePaymentGateway;
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
 * The daily reconciliation and the merchant statement upload (extras slice A5/7, second part, issue #224, task #317, ADR-035,
 * spec 3.9 and 3.17, FR-PAY-05, FR-PAY-06, FR-PAY-11) on a real database with the in-process fake provider: a statement is read
 * whole or not at all, matched to the business's own orders by order tracking id (the confirmation code only when a line has no
 * tracking id and names one order), its fees post to the gateway charges account once per order however the files overlap or
 * race, and the reconciliation lists the differences between what the provider and this system hold without changing a ledger or
 * an order. Every name, number and reference is fabricated.
 */
@Import({PaymentReconciliationIT.Config.class, MovableClockConfig.class})
@ResourceLock(MovableClockConfig.LOCK)
@TestPropertySource(
        properties = {
            "spring.datasource.hikari.maximum-pool-size=4",
            "db-scheduler.enabled=false",
            "bms.payments.poller-enabled=false",
            // A short bounded wait for another run's lock, so the busy case is quick to test.
            "bms.payments.reconcile-lock-wait=500ms",
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
class PaymentReconciliationIT extends LoanFixtures {

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
    ReconciliationService reconciliation;

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
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', 'Test Accountant 317', ?, 'active')")
                .params(accountant, t.tenantId(), "accountant-" + accountant + "@example.test")
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

    // ---- allocation -----------------------------------------------------------------------------

    // ---- refund ---------------------------------------------------------------------------------

    // ---- the database's own guard ---------------------------------------------------------------

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

    // ---- failed, late and review windows --------------------------------------------------------

    // ---- exactly once under concurrency, and a tampered request ---------------------------------

    // ---- the attack review of part 1 --------------------------------------------------------------

    // ---- statement and report helpers ----------------------------------------------------------

    static final String HEADER = "order_tracking_id,confirmation_code,amount,fee,currency,date,payment_method";
    static final String RECON = "/api/v1/payments/reconciliation";
    static final String STATEMENTS = "/api/v1/payments/statements";

    String accountantPerms() {
        return permissionsOf("accountant");
    }

    String csv(String... lines) {
        return HEADER + "\n" + String.join("\n", lines) + "\n";
    }

    /** One statement line for an order, as the provider's export would show it. */
    String line(JsonNode intent, String code, long amount, long fee, String date) {
        return tracking(intent) + "," + code + "," + amount + "," + fee + ",UGX," + date + ",MTN";
    }

    ResponseEntity<JsonNode> upload(String csv, UUID user, String perms) {
        return uploadBytes(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), user, perms, "statement.csv");
    }

    ResponseEntity<JsonNode> uploadBytes(byte[] bytes, UUID user, String perms, String name) {
        HttpHeaders h = as(user, perms, "*", null);
        h.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        org.springframework.util.LinkedMultiValueMap<String, Object> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", new org.springframework.core.io.ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return name;
            }
        });
        return http.exchange(STATEMENTS, HttpMethod.POST, new HttpEntity<>(form, h), JsonNode.class);
    }

    ResponseEntity<JsonNode> uploadAsAccountant(String csv) {
        return upload(csv, accountant, accountantPerms());
    }

    long feeEntries() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ? AND source_type = 'statement_fee'")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    long statements() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM payment_statements WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    long chargesBalance() {
        return balanceOf("gateway_charges");
    }

    long balanceOf(String systemKey) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE a.tenant_id = ? AND a.system_key = ?
                        """)
                .params(t.tenantId(), systemKey)
                .query(Long.class)
                .single();
    }

    JsonNode runReconciliation() {
        ResponseEntity<JsonNode> r =
                send(HttpMethod.POST, RECON + "/run", as(accountant, accountantPerms(), "*", null), null);
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(200);
        return r.getBody();
    }

    JsonNode report(UUID user, String perms, String branches) {
        ResponseEntity<JsonNode> r = send(HttpMethod.GET, RECON, as(user, perms, branches, null), null);
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(200);
        return r.getBody();
    }

    /** The findings of a report of the given kind, for the given intent when one is named. */
    List<JsonNode> findings(JsonNode report, String kind, JsonNode intent) {
        List<JsonNode> out = new java.util.ArrayList<>();
        report.get("findings").forEach(f -> {
            boolean kindOk = kind == null || kind.equals(f.get("kind").asString());
            boolean intentOk = intent == null
                    || (f.has("intent_id")
                            && !f.get("intent_id").isNull()
                            && f.get("intent_id")
                                    .asString()
                                    .equals(intent.get("id").asString()));
            if (kindOk && intentOk) {
                out.add(f);
            }
        });
        return out;
    }

    JsonNode manualRepayment(String loan, long amount, String method, String reference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", amount);
        body.put("value_date", TODAY.toString());
        body.put("payment_method_key", method);
        body.put("external_reference", reference);
        HttpHeaders h = as(cashier, cashierPerms, "*", null);
        h.add("Idempotency-Key", "manual-" + UUID.randomUUID());
        ResponseEntity<JsonNode> r = send(HttpMethod.POST, LOANS + "/" + loan + "/repayments", h, body);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    /** An order the provider says is paid, verified, and that nothing has booked, paid this long ago. */
    JsonNode paidAndUnbooked(String loan, long amount, String code, Duration ago) {
        JsonNode intent = intentFor(loan, amount);
        TestDatabase.owner()
                .sql("""
                        UPDATE payment_intents SET status = 'paid', paid_amount_minor = ?, paid_currency = 'UGX',
                               confirmation_code = ?, paid_at = ?, verified_at = ?, next_check_at = NULL WHERE id = ?
                        """)
                .params(
                        amount,
                        code,
                        java.sql.Timestamp.from(clock.instant().minus(ago)),
                        java.sql.Timestamp.from(clock.instant().minus(ago)),
                        UUID.fromString(intent.get("id").asString()))
                .update();
        return intent;
    }

    // ---- the statement upload ------------------------------------------------------------------

    @Test
    void aCleanLinePostsItsFeeOnceAndTheSameFileAgainPostsNothing() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-1001");
        long clearing = clearingBalance();
        String file = csv(line(intent, "CONF-224-1001", 520_000, 5_200, "2026-10-16"));
        ResponseEntity<JsonNode> first = uploadAsAccountant(file);
        assertThat(first.getStatusCode().value()).as("%s", first.getBody()).isEqualTo(201);
        JsonNode statement = first.getBody().get("statement");
        assertThat(statement.get("matched_count").asInt()).isEqualTo(1);
        assertThat(statement.get("fees_posted_minor").asLong()).isEqualTo(5_200);
        JsonNode row = first.getBody().get("rows").get(0);
        assertThat(row.get("match_status").asString()).isEqualTo("matched");
        assertThat(row.get("fee_status").asString()).isEqualTo("posted");
        // Debit the gateway charges account, credit what the gateway is mapped to; the clearing balance falls by the
        // fee.
        Object entry = TestDatabase.owner()
                .sql("SELECT fee_entry_id FROM payment_statement_rows WHERE intent_id = ?")
                .param(UUID.fromString(intent.get("id").asString()))
                .query(UUID.class)
                .single();
        assertThat(lines(entry)).containsExactly("gateway_charges:5200:0", "gateway_clearing:0:5200");
        assertThat(clearingBalance()).isEqualTo(clearing - 5_200);
        assertThat(chargesBalance()).isEqualTo(5_200);
        // The same file again is the same statement: 200, replayed, nothing posted.
        ResponseEntity<JsonNode> again = uploadAsAccountant(file);
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.getBody().get("statement").get("id").asString())
                .isEqualTo(statement.get("id").asString());
        assertThat(statements()).isEqualTo(1L);
        assertThat(feeEntries()).isEqualTo(1L);
        assertThat(chargesBalance()).isEqualTo(5_200);
        // The statement is audited without its lines.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'core.payments.statement.uploaded'")
                        .param(UUID.fromString(statement.get("id").asString()))
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    /** ADR-035 decision 65: a fee at the automatic rate (5% of the line) posts at once; one above it waits for a second person. */
    @Test
    void aFeeAtTheAutomaticRatePostsAtOnceAndOneAboveItWaitsForASecondPersonWhoPostsItOnce() {
        String loan = activeLoan();
        JsonNode atRate = bookedRepayment(loan, 520_000, "CONF-224-1201");
        JsonNode above = bookedRepayment(loan, 300_000, "CONF-224-1202");
        long clearing = clearingBalance();
        ResponseEntity<JsonNode> r = uploadBytes(
                csv(
                                line(atRate, "CONF-224-1201", 520_000, 26_000, "2026-10-16"),
                                line(above, "CONF-224-1202", 300_000, 15_001, "2026-10-16"))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                accountant,
                accountantPerms(),
                "=statement.csv");
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(201);
        assertThat(r.getBody().get("statement").get("file_name").asString())
                .as("a leading formula character is neutralised")
                .isEqualTo("_statement.csv");
        assertThat(r.getBody().get("statement").get("fees_posted_minor").asLong())
                .isEqualTo(26_000);
        assertThat(r.getBody().get("rows").get(0).get("fee_status").asString()).isEqualTo("posted");
        assertThat(r.getBody().get("rows").get(1).get("fee_status").asString()).isEqualTo("awaiting_approval");
        assertThat(chargesBalance()).isEqualTo(26_000);
        assertThat(clearingBalance()).isEqualTo(clearing - 26_000);
        String approval = TestDatabase.owner()
                .sql("SELECT fee_approval_id FROM payment_statement_rows WHERE intent_id = ?")
                .param(UUID.fromString(above.get("id").asString()))
                .query(UUID.class)
                .single()
                .toString();
        JsonNode detail = send(HttpMethod.GET, "/api/v1/approvals/" + approval, as(staff, adminPerms, "*", null), null)
                .getBody();
        assertThat(detail.get("action_type").asString()).isEqualTo("statement_fee");
        assertThat(detail.get("checker_notice").asString())
                .contains("UGX 15,001")
                .contains("5%");
        // The uploader cannot approve their own fee.
        ResponseEntity<JsonNode> own = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/approve",
                as(accountant, accountantPerms(), "*", null),
                Map.of("note", "Mine"));
        assertThat(own.getStatusCode().value()).isEqualTo(422);
        assertThat(own.getBody().get("code").asString()).isEqualTo("self_approval_forbidden");
        assertThat(chargesBalance()).isEqualTo(26_000);
        // The same order in another file while it waits raises nothing more and posts nothing.
        upload(csv(line(above, "CONF-224-1202", 300_000, 15_001, "2026-10-15")), accountant, accountantPerms());
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM approval_requests WHERE tenant_id = ? AND action_type = 'statement_fee'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        // A second person approves: posted once.
        ResponseEntity<JsonNode> ok = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/approve",
                as(staff, adminPerms, "*", null),
                Map.of("note", "Checked against the provider's statement"));
        assertThat(ok.getStatusCode().value()).as("%s", ok.getBody()).isEqualTo(200);
        assertThat(chargesBalance()).isEqualTo(41_001);
        assertThat(clearingBalance()).isEqualTo(clearing - 41_001);
        assertThat(feeEntries()).isEqualTo(2L);
        String statementId = r.getBody().get("statement").get("id").asString();
        assertThat(send(
                                HttpMethod.GET,
                                STATEMENTS + "/" + statementId,
                                as(accountant, accountantPerms(), "*", null),
                                null)
                        .getBody()
                        .get("rows")
                        .get(1)
                        .get("fee_status")
                        .asString())
                .isEqualTo("posted");
        // The database lets nothing else of a line change.
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE payment_statement_rows SET fee_entry_id = NULL WHERE intent_id = ?")
                        .param(UUID.fromString(above.get("id").asString()))
                        .update())
                .hasMessageContaining("payment_statement_rows");
    }

    /** A held fee whose order's fee was posted meanwhile from another line: approving closes the request as stale, posts nothing. */
    @Test
    void aHeldFeeWhoseOrderWasPostedMeanwhileIsClosedAsStaleAndPostsNothing() {
        String loan = activeLoan();
        JsonNode atRate = bookedRepayment(loan, 520_000, "CONF-224-1211");
        JsonNode above = bookedRepayment(loan, 300_000, "CONF-224-1212");
        ResponseEntity<JsonNode> r = upload(
                csv(
                        line(atRate, "CONF-224-1211", 520_000, 5_200, "2026-10-16"),
                        line(above, "CONF-224-1212", 300_000, 30_000, "2026-10-16")),
                accountant,
                accountantPerms());
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(201);
        UUID aboveId = UUID.fromString(above.get("id").asString());
        String approval = TestDatabase.owner()
                .sql("SELECT fee_approval_id FROM payment_statement_rows WHERE intent_id = ?")
                .param(aboveId)
                .query(UUID.class)
                .single()
                .toString();
        // The order's fee is recorded from another line meanwhile (written directly: no ordinary path does this while a
        // request waits, so this stands for any way it could happen).
        UUID entry = TestDatabase.owner()
                .sql("SELECT fee_entry_id FROM payment_statement_rows WHERE intent_id = ?")
                .param(UUID.fromString(atRate.get("id").asString()))
                .query(UUID.class)
                .single();
        UUID other = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO payment_statements (id, tenant_id, uploaded_by, file_name, content_sha256, row_count,
                            period_from, period_to, currency, matched_count, different_count, unmatched_count, fees_posted_minor)
                        VALUES (?, ?, ?, 'other.csv', ?, 1, DATE '2026-10-15', DATE '2026-10-15', 'UGX', 1, 0, 0, 0)
                        """)
                .params(other, t.tenantId(), accountant, "f".repeat(64))
                .update();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO payment_statement_rows (id, tenant_id, statement_id, row_no, order_tracking_id, amount_minor,
                            fee_minor, currency, paid_on, match_status, intent_id, fee_entry_id)
                        VALUES (?, ?, ?, 1, ?, 300000, 3000, 'UGX', DATE '2026-10-15', 'matched', ?, ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), other, tracking(above), aboveId, entry)
                .update();
        long charges = chargesBalance();
        ResponseEntity<JsonNode> ok = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approval + "/approve",
                as(staff, adminPerms, "*", null),
                Map.of("note", "Checked"));
        assertThat(ok.getStatusCode().value()).as("%s", ok.getBody()).isEqualTo(409);
        assertThat(ok.getBody().get("code").asString()).isEqualTo("fee_already_posted");
        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM approval_requests WHERE id = ?")
                        .param(UUID.fromString(approval))
                        .query(String.class)
                        .single())
                .isEqualTo("stale");
        assertThat(chargesBalance()).isEqualTo(charges);
    }

    @Test
    void twoOverlappingStatementsPostTheFeeOfAnOrderOnce() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-1002");
        assertThat(uploadAsAccountant(csv(line(intent, "CONF-224-1002", 520_000, 5_200, "2026-10-16")))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
        // A wider export naming the same order (another file, so another statement).
        ResponseEntity<JsonNode> wider =
                uploadAsAccountant(csv(line(intent, "CONF-224-1002", 520_000, 5_200, "2026-10-15")));
        assertThat(wider.getStatusCode().value()).isEqualTo(201);
        assertThat(wider.getBody().get("rows").get(0).get("fee_status").asString())
                .isEqualTo("already_posted");
        assertThat(wider.getBody().get("statement").get("fees_posted_minor").asLong())
                .isZero();
        assertThat(feeEntries()).isEqualTo(1L);
        assertThat(chargesBalance()).isEqualTo(5_200);
    }

    @Test
    void twoOverlappingFilesUploadedAtTheSameTimePostTheFeeOnce() throws Exception {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-1003");
        String one = csv(line(intent, "CONF-224-1003", 520_000, 5_200, "2026-10-16"));
        String two = csv(line(intent, "CONF-224-1003", 520_000, 5_200, "2026-10-15"));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Future<Integer> a = pool.submit(() -> {
            go.await();
            return uploadAsAccountant(one).getStatusCode().value();
        });
        java.util.concurrent.Future<Integer> b = pool.submit(() -> {
            go.await();
            return uploadAsAccountant(two).getStatusCode().value();
        });
        go.countDown();
        assertThat(a.get(60, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(201);
        assertThat(b.get(60, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(201);
        pool.shutdown();
        assertThat(statements()).isEqualTo(2L);
        assertThat(feeEntries()).isEqualTo(1L);
        assertThat(chargesBalance()).isEqualTo(5_200);
    }

    @Test
    void theConfirmationCodeMatchesOnlyALineWithoutATrackingIdAndOnlyWhenItNamesOneOrder() {
        String loan = activeLoan();
        JsonNode one = bookedRepayment(loan, 520_000, "CONF-224-1004");
        // A line with no tracking id and a code that names one order matches it.
        ResponseEntity<JsonNode> r = uploadAsAccountant(csv(",CONF-224-1004,520000,2000,UGX,2026-10-16,MTN"));
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(201);
        assertThat(r.getBody().get("rows").get(0).get("match_status").asString())
                .isEqualTo("matched");
        assertThat(r.getBody().get("rows").get(0).get("intent_id").asString())
                .isEqualTo(one.get("id").asString());
        assertThat(feeEntries()).isEqualTo(1L);
        // Two genuine payments can carry one short code: a line that names both is ambiguous and posts nothing.
        String loanB = activeLoan();
        JsonNode second = bookedRepayment(loanB, 310_000, "CONF-224-SHARED");
        String loanC = activeLoan();
        JsonNode third = bookedRepayment(loanC, 310_000, "CONF-224-SHARED");
        ResponseEntity<JsonNode> amb = uploadAsAccountant(csv(",CONF-224-SHARED,310000,1000,UGX,2026-10-16,MTN"));
        assertThat(amb.getBody().get("rows").get(0).get("match_status").asString())
                .isEqualTo("ambiguous");
        assertThat(amb.getBody().get("rows").get(0).get("fee_status").asString())
                .isEqualTo("held");
        assertThat(feeEntries()).isEqualTo(1L);
        // A tracking id that is unknown is not rescued by a code: the line is unmatched.
        ResponseEntity<JsonNode> unknown =
                uploadAsAccountant(csv(UUID.randomUUID() + ",CONF-224-1004,520000,2000,UGX,2026-10-16,MTN"));
        assertThat(unknown.getBody().get("rows").get(0).get("match_status").asString())
                .isEqualTo("unmatched");
    }

    @Test
    void aLineThatDiffersIsListedAndItsFeeIsHeldUntilAStatementAgrees() {
        String loan = activeLoan();
        JsonNode paidLess = intentFor(loan, 520_000);
        pay(paidLess, 400_000, "CONF-224-1007");
        tick(Duration.ofMinutes(3));
        assertThat(row(paidLess).get("status")).isEqualTo("booked");
        String loanB = activeLoan();
        JsonNode pending = intentFor(loanB, 310_000);
        ResponseEntity<JsonNode> r = uploadAsAccountant(csv(
                line(paidLess, "CONF-224-1007", 520_000, 5_200, "2026-10-16"),
                line(pending, "CONF-224-1008", 310_000, 3_100, "2026-10-16"),
                UUID.randomUUID() + ",CONF-224-1009,100000,1000,UGX,2026-10-16,MTN"));
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(201);
        JsonNode rows = r.getBody().get("rows");
        assertThat(rows.get(0).get("match_status").asString()).isEqualTo("amount_mismatch");
        assertThat(rows.get(1).get("match_status").asString()).isEqualTo("not_paid_here");
        assertThat(rows.get(2).get("match_status").asString()).isEqualTo("unmatched");
        for (int i = 0; i < 3; i++) {
            assertThat(rows.get(i).get("fee_status").asString()).isEqualTo("held");
        }
        JsonNode statement = r.getBody().get("statement");
        assertThat(statement.get("matched_count").asInt()).isZero();
        assertThat(statement.get("different_count").asInt()).isEqualTo(2);
        assertThat(statement.get("unmatched_count").asInt()).isEqualTo(1);
        assertThat(feeEntries()).isZero();
        // A corrected export (the amount agrees) posts the fee, once.
        ResponseEntity<JsonNode> fixed =
                uploadAsAccountant(csv(line(paidLess, "CONF-224-1007", 400_000, 5_200, "2026-10-16")));
        assertThat(fixed.getBody().get("rows").get(0).get("fee_status").asString())
                .isEqualTo("posted");
        assertThat(feeEntries()).isEqualTo(1L);
    }

    @Test
    void aFileWithAnUnreadableLineIsRefusedWholeAndNothingIsStoredOrPosted() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-1010");
        String good = line(intent, "CONF-224-1010", 520_000, 5_200, "2026-10-16");
        ResponseEntity<JsonNode> bad = uploadAsAccountant(csv(
                good,
                "not-a-tracking-id,CONF-224-1011,100000,0,UGX,2026-10-16,MTN",
                UUID.randomUUID() + ",CONF-224-1012,1000.50,0,UGX,2026-10-16,MTN",
                UUID.randomUUID() + ",CONF-224-1013,1000,5000,UGX,2026-10-16,MTN",
                UUID.randomUUID() + ",CONF-224-1014,1000,0,USD,2026-10-16,MTN",
                UUID.randomUUID() + ",CONF-224-1015,1000,0,UGX,16 October,MTN"));
        assertThat(bad.getStatusCode().value()).isEqualTo(422);
        assertThat(bad.getBody().get("code").asString()).isEqualTo("validation_failed");
        String errors = bad.getBody().get("errors").toString();
        assertThat(errors)
                .contains("line 3", "line 4", "line 5", "line 6", "line 7")
                .doesNotContain("line 2");
        assertThat(statements()).isZero();
        assertThat(feeEntries()).isZero();
        // The same order twice in one file, a missing column, text that is not UTF-8 and an empty file.
        assertThat(uploadAsAccountant(csv(good, good)).getBody().get("errors").toString())
                .contains("twice");
        assertThat(uploadAsAccountant("order_tracking_id,date\n" + tracking(intent) + ",2026-10-16\n")
                        .getBody()
                        .get("errors")
                        .toString())
                .contains("amount");
        assertThat(uploadBytes(new byte[] {(byte) 0xff, (byte) 0xfe, 0x41}, accountant, accountantPerms(), "x.csv")
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        assertThat(uploadBytes(new byte[0], accountant, accountantPerms(), "x.csv")
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        assertThat(statements()).isZero();
        assertThat(feeEntries()).isZero();
    }

    @Test
    void onlyAHolderOfManageUploadsAndAStatementIsNeverVisibleToAnotherBusiness() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-1016");
        String file = csv(line(intent, "CONF-224-1016", 520_000, 5_200, "2026-10-16"));
        assertThat(upload(file, cashier, cashierPerms).getStatusCode().value()).isEqualTo(403);
        assertThat(statements()).isZero();
        ResponseEntity<JsonNode> done = uploadAsAccountant(file);
        String id = done.getBody().get("statement").get("id").asString();
        assertThat(send(HttpMethod.GET, STATEMENTS, as(cashier, cashierPerms, "*", null), null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(send(HttpMethod.GET, STATEMENTS + "/" + id, as(accountant, accountantPerms(), "*", null), null)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        // Another business: it cannot read the statement, and the same tracking id on its own statement matches nothing
        // of its own.
        TestDatabase.Fixture other = TestDatabase.tenant("other", true);
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", other.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", accountantPerms());
        h.add("X-Dev-Branch-Ids", "*");
        assertThat(send(HttpMethod.GET, STATEMENTS + "/" + id, h, null)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(send(HttpMethod.GET, STATEMENTS, h, null).getBody().get("items"))
                .isEmpty();
    }

    // ---- the reconciliation --------------------------------------------------------------------

    @Test
    void aCompletedOrderNobodyBookedIsListedAskedForAgainAndClosesWhenItIsBooked() {
        String loan = activeLoan();
        JsonNode intent = paidAndUnbooked(loan, 520_000, "CONF-224-1020", Duration.ofHours(2));
        long entries = TestDatabase.owner()
                .sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        JsonNode report = runReconciliation();
        assertThat(findings(report, "completed_not_booked", intent)).hasSize(1);
        assertThat(report.get("run").get("trigger").asString()).isEqualTo("manual");
        // The run reports and asks the poller to look; it posts nothing and changes no status.
        assertThat(row(intent).get("status")).isEqualTo("paid");
        assertThat(row(intent).get("next_check_at")).isNotNull();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(entries);
        tick(Duration.ofMinutes(1));
        assertThat(row(intent).get("status")).isEqualTo("booked");
        assertThat(findings(runReconciliation(), "completed_not_booked", intent))
                .isEmpty();
        // A fresh payment, under an hour old, is not yet a difference.
        JsonNode young = paidAndUnbooked(activeLoan(), 310_000, "CONF-224-1021", Duration.ofMinutes(20));
        assertThat(findings(runReconciliation(), "completed_not_booked", young)).isEmpty();
    }

    @Test
    void anOrderUnpaidADayAfterItWasMadeAndAPaymentThatDiffersAreListed() {
        String loan = activeLoan();
        JsonNode unpaid = intentFor(loan, 520_000);
        clock.set(clock.instant().plus(Duration.ofHours(25)));
        JsonNode report = runReconciliation();
        assertThat(findings(report, "pending_over_24h", unpaid)).hasSize(1);
        String loanB = activeLoan();
        JsonNode less = intentFor(loanB, 520_000);
        pay(less, 400_000, "CONF-224-1022");
        tick(Duration.ofMinutes(3));
        List<JsonNode> differs = findings(runReconciliation(), "paid_differs", less);
        assertThat(differs).hasSize(1);
        assertThat(differs.getFirst().get("bms_amount_minor").asLong()).isEqualTo(520_000);
        assertThat(differs.getFirst().get("provider_amount_minor").asLong()).isEqualTo(400_000);
    }

    @Test
    void reversalsAwaitingADecisionRefusedByTheBooksAndOfMoneyAlreadySentBackAreEachListed() {
        // A booked repayment the provider reversed and nobody has decided.
        JsonNode awaiting = bookedRepayment(activeLoan(), 520_000, "CONF-224-1030");
        reportReversed(awaiting);
        // Money the lender already refunded by bank, then reported reversed: a loss for the accountant.
        UUID receipt = openReceipt(310_000, "CONF-224-1031");
        JsonNode refunded = intentOfReceipt(receipt);
        String bank = assetAccount("1095", "Test bank 3");
        assertThat(mapMethod("bank", null, bank, true).getStatusCode().value()).isEqualTo(200);
        assertThat(decide(requested(askRefund(receipt, "bank")), "approve")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        reportReversed(refunded);
        // A deposit withdrawn before the reversal: the books refuse it and the request waits with its error.
        String account = account();
        JsonNode deposit = intentFor(account, SAVINGS, 520_000);
        pay(deposit, 520_000, "CONF-224-1032");
        tick(Duration.ofMinutes(3));
        HttpHeaders h = as(cashier, cashierPerms, "*", null);
        h.add("Idempotency-Key", "wd-" + UUID.randomUUID());
        ResponseEntity<JsonNode> wd = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/withdrawals",
                h,
                Map.of("amount_minor", 500_000, "payment_method_key", "cash"));
        if (wd.getStatusCode().value() == 202) {
            assertThat(decideAs(wd.getBody().get("approval_request_id").asString(), "approve", manager, managerPerms)
                            .getStatusCode()
                            .value())
                    .isEqualTo(200);
        }
        reportReversed(deposit);
        assertThat(decide(reversalRequests(deposit).getFirst().get("id").toString(), "approve")
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        JsonNode report = runReconciliation();
        assertThat(findings(report, "reversed_awaiting_review", awaiting)).hasSize(1);
        List<JsonNode> loss = findings(report, "reversed_after_refund", refunded);
        assertThat(loss).hasSize(1);
        assertThat(loss.getFirst().get("bms_amount_minor").asLong()).isEqualTo(310_000);
        assertThat(findings(report, "reversal_blocked", deposit)).hasSize(1);
        assertThat(report.get("counts_by_kind").get("reversal_blocked").asInt()).isEqualTo(1);
    }

    @Test
    void aReceiptKeyedByHandWithAGatewayConfirmationCodeIsFlaggedThroughTheBookedTransaction() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-1040");
        // The cashier also keyed it in as a mobile money receipt, with the code in another letter case.
        JsonNode manual = manualRepayment(loan, 520_000, "mtn_momo", "conf-224-1040");
        // An unrelated manual receipt, and a manual one with the same code that was reversed, are not flagged.
        manualRepayment(loan, 50_000, "cash", "CONF-224-OTHER");
        JsonNode report = runReconciliation();
        List<JsonNode> flagged = findings(report, "manual_receipt_duplicate", intent);
        assertThat(flagged).hasSize(1);
        assertThat(flagged.getFirst().get("other_transaction_id").asString())
                .isEqualTo(manual.get("transaction").get("id").asString());
        // The gateway's own transaction (whose external reference is the order tracking id) is not the one flagged.
        assertThat(flagged.getFirst().get("other_transaction_id").asString())
                .isNotEqualTo(row(intent).get("booked_subject_txn_id").toString());
        assertThat(findings(report, "manual_receipt_duplicate", null)).hasSize(1);
        // The same for a savings deposit.
        String account = account();
        JsonNode depositIntent = intentFor(account, SAVINGS, 310_000);
        pay(depositIntent, 310_000, "CONF-224-1041");
        tick(Duration.ofMinutes(3));
        HttpHeaders h = as(cashier, cashierPerms, "*", null);
        h.add("Idempotency-Key", "dep-" + UUID.randomUUID());
        ResponseEntity<JsonNode> dep = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/deposits",
                h,
                Map.of(
                        "amount_minor",
                        310_000,
                        "payment_method_key",
                        "airtel_money",
                        "external_reference",
                        "CONF-224-1041"));
        assertThat(dep.getStatusCode().value()).as("%s", dep.getBody()).isEqualTo(201);
        assertThat(findings(runReconciliation(), "manual_receipt_duplicate", depositIntent))
                .hasSize(1);
    }

    @Test
    void theStatementFlagsAreListedAndCloseByThemselvesWhenTheirCauseIsPutRight() {
        String loan = activeLoan();
        JsonNode booked = bookedRepayment(loan, 520_000, "CONF-224-1050");
        JsonNode missing = bookedRepayment(activeLoan(), 310_000, "CONF-224-1051");
        JsonNode differing = bookedRepayment(activeLoan(), 200_000, "CONF-224-1052");
        JsonNode later = intentFor(activeLoan(), 150_000);
        String stranger = UUID.randomUUID().toString();
        // The provider's statement for the day: the first order, the differing one at another amount, an order we have
        // not seen
        // paid yet, and a payment no order of ours has. It does not list the second order.
        uploadCovered(
                csv(
                        line(booked, "CONF-224-1050", 520_000, 0, "2026-10-16"),
                        line(differing, "CONF-224-1052", 250_000, 0, "2026-10-16"),
                        line(later, "CONF-224-1053", 150_000, 0, "2026-10-16"),
                        stranger + ",CONF-224-9999,70000,0,UGX,2026-10-16,MTN"),
                "2026-10-16",
                "2026-10-16");
        JsonNode report = runReconciliation();
        assertThat(findings(report, "in_bms_not_in_provider", missing)).hasSize(1);
        assertThat(findings(report, "in_bms_not_in_provider", booked)).isEmpty();
        List<JsonNode> amount = findings(report, "amount_mismatch", differing);
        assertThat(amount).hasSize(1);
        assertThat(amount.getFirst().get("bms_amount_minor").asLong()).isEqualTo(200_000);
        assertThat(amount.getFirst().get("provider_amount_minor").asLong()).isEqualTo(250_000);
        assertThat(findings(report, "in_provider_not_in_bms", later)).hasSize(1);
        List<JsonNode> strangers = findings(report, "in_provider_not_in_bms", null).stream()
                .filter(f -> stranger.equals(f.get("order_tracking_id").asString()))
                .toList();
        assertThat(strangers).hasSize(1);
        assertThat(strangers.getFirst().get("intent_id").isNull()).isTrue();
        // The order we had not seen paid is now paid: that difference closes by itself on the next run.
        pay(later, 150_000, "CONF-224-1053");
        tick(Duration.ofMinutes(3));
        JsonNode next = runReconciliation();
        assertThat(findings(next, "in_provider_not_in_bms", later)).isEmpty();
        assertThat(findings(next, "in_bms_not_in_provider", later)).isEmpty();
        assertThat(findings(next, "in_bms_not_in_provider", missing)).hasSize(1);
    }

    @Test
    void theReportIsReadByAReaderOfTheBranchAndNobodyElseAndRunningNeedsManage() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-1060");
        reportReversed(intent);
        runReconciliation();
        // A reader of every branch sees it; a reader of another branch sees the run but none of its differences.
        assertThat(findings(report(cashier, cashierPerms, "*"), "reversed_awaiting_review", intent))
                .hasSize(1);
        JsonNode elsewhere = report(cashier, cashierPerms, t.secondBranch().toString());
        assertThat(elsewhere.get("findings")).isEmpty();
        assertThat(elsewhere.get("run").get("finding_count").asInt())
                .as("only what the reader can see")
                .isZero();
        // A cashier may read but not run.
        assertThat(send(HttpMethod.POST, RECON + "/run", as(cashier, cashierPerms, "*", null), null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        // Without the read permission, nothing.
        assertThat(send(HttpMethod.GET, RECON, as(cashier, "lending.loans.read", "*", null), null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        // Another business sees none of it.
        TestDatabase.Fixture other = TestDatabase.tenant("other", true);
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", other.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", accountantPerms());
        h.add("X-Dev-Branch-Ids", "*");
        JsonNode theirs = send(HttpMethod.GET, RECON, h, null).getBody();
        assertThat(theirs.get("findings")).isEmpty();
        assertThat(theirs.has("run") && !theirs.get("run").isNull()).isFalse();
    }

    @Test
    void theNightlyJobWritesNothingForABusinessWithNoPaymentsRunsOnceADayKeepsNinetyDaysAndTwoRunsAtOnceBothFinish()
            throws Exception {
        // No orders, no statement: the job writes no run.
        assertThat(tenantJobs.callForTenant(t.tenantId(), () -> reconciliation.run("job", null, false)))
                .isEmpty();
        assertThat(runCount()).isZero();
        String loan = activeLoan();
        bookedRepayment(loan, 520_000, "CONF-224-1070");
        assertThat(tenantJobs.callForTenant(t.tenantId(), () -> reconciliation.run("job", null, false)))
                .isPresent();
        assertThat(runCount()).isEqualTo(1L);
        // The job run again the same day (a retry after a crash, a second node) finds the day's run and writes nothing.
        assertThat(tenantJobs.callForTenant(t.tenantId(), () -> reconciliation.run("job", null, false)))
                .isEmpty();
        assertThat(runCount()).isEqualTo(1L);
        assertThat(TestDatabase.owner()
                        .sql("SELECT trigger FROM payment_reconciliation_runs WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo("job");
        // A run older than ninety days goes with its findings when the next run is written.
        UUID old = UUID.randomUUID();
        TestDatabase.owner().sql("""
                        INSERT INTO payment_reconciliation_runs (id, tenant_id, created_at, ran_on, trigger, window_from,
                            intents_examined, statements_used, finding_count)
                        VALUES (?, ?, now() - interval '200 days', current_date - 200, 'job', current_date - 320, 0, 0, 1)
                        """).params(old, t.tenantId()).update();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO payment_reconciliation_findings (id, tenant_id, run_id, kind) VALUES (?, ?, ?, 'pending_over_24h')")
                .params(UUID.randomUUID(), t.tenantId(), old)
                .update();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<Integer>> runs = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            runs.add(pool.submit(() -> {
                go.await();
                return send(HttpMethod.POST, RECON + "/run", as(accountant, accountantPerms(), "*", null), null)
                        .getStatusCode()
                        .value();
            }));
        }
        go.countDown();
        for (var f : runs) {
            assertThat(f.get(60, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdown();
        assertThat(runCount()).isEqualTo(3L);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_reconciliation_findings WHERE run_id = ?")
                        .param(old)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /**
     * Another run of the business holds its lock: a person's run waits only the bounded time and is told to try again, nothing
     * is written; once the lock is free the run goes.
     */
    @Test
    void aRunWhileAnotherHoldsTheBusinessLockIsToldToTryAgainAndWritesNothing() throws Exception {
        String loan = activeLoan();
        bookedRepayment(loan, 520_000, "CONF-224-1290");
        long runs = runCount();
        try (java.sql.Connection held = TestDatabase.ownerDataSource().getConnection()) {
            held.setAutoCommit(false);
            try (java.sql.PreparedStatement lock =
                    held.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                lock.setString(1, "payments:reconcile:" + t.tenantId());
                lock.executeQuery();
                long started = System.nanoTime();
                ResponseEntity<JsonNode> busy =
                        send(HttpMethod.POST, RECON + "/run", as(accountant, accountantPerms(), "*", null), null);
                assertThat(busy.getStatusCode().value())
                        .as("%s", busy.getBody())
                        .isEqualTo(409);
                assertThat(busy.getBody().get("code").asString()).isEqualTo("try_again");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
                assertThat(runCount()).isEqualTo(runs);
            } finally {
                held.rollback();
            }
        }
        ResponseEntity<JsonNode> free =
                send(HttpMethod.POST, RECON + "/run", as(accountant, accountantPerms(), "*", null), null);
        assertThat(free.getStatusCode().value()).as("%s", free.getBody()).isEqualTo(200);
        assertThat(runCount()).isEqualTo(runs + 1);
    }

    long runCount() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM payment_reconciliation_runs WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    @Test
    void aRunChangesNeitherTheLedgerNorTheStatusOfAnyOrder() {
        String loan = activeLoan();
        JsonNode booked = bookedRepayment(loan, 520_000, "CONF-224-1080");
        reportReversed(booked);
        UUID receipt = openReceipt(310_000, "CONF-224-1081");
        long entries = TestDatabase.owner()
                .sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        String statuses = TestDatabase.owner()
                .sql("SELECT string_agg(id || ':' || status, ',' ORDER BY id) FROM payment_intents WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(String.class)
                .single();
        runReconciliation();
        runReconciliation();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM journal_entries WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(entries);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT string_agg(id || ':' || status, ',' ORDER BY id) FROM payment_intents WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo(statuses);
        assertThat(receiptRow(receipt).get("status")).isEqualTo("open");
    }

    // ---- the attack review of #318 ---------------------------------------------------------------

    ResponseEntity<JsonNode> uploadCovered(String csv, String from, String to) {
        HttpHeaders h = as(accountant, accountantPerms(), "*", null);
        h.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        org.springframework.util.LinkedMultiValueMap<String, Object> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add(
                "file",
                new org.springframework.core.io.ByteArrayResource(
                        csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    @Override
                    public String getFilename() {
                        return "statement.csv";
                    }
                });
        if (from != null) {
            form.add("covered_from", from);
        }
        if (to != null) {
            form.add("covered_to", to);
        }
        return http.exchange(STATEMENTS, HttpMethod.POST, new HttpEntity<>(form, h), JsonNode.class);
    }

    @Test
    void oneOrderNamedTwiceInOneFileRefusesTheFileAndPostsNothing() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-1100");
        // A line with the tracking id and another with no tracking id but the same order's code resolve to one order.
        ResponseEntity<JsonNode> twice = uploadAsAccountant(csv(
                line(intent, "CONF-224-1100", 520_000, 5_200, "2026-10-16"),
                ",CONF-224-1100,520000,5200,UGX,2026-10-16,MTN"));
        assertThat(twice.getStatusCode().value()).as("%s", twice.getBody()).isEqualTo(422);
        assertThat(twice.getBody().get("errors").toString()).contains("line 2", "line 3", "same order");
        // Two lines with no tracking id and the same code are refused as the same order by the reader.
        ResponseEntity<JsonNode> codes = uploadAsAccountant(
                csv(",CONF-224-1100,520000,5200,UGX,2026-10-16,MTN", ",conf-224-1100,520000,5200,UGX,2026-10-16,MTN"));
        assertThat(codes.getStatusCode().value()).isEqualTo(422);
        assertThat(codes.getBody().get("errors").toString()).contains("same confirmation code");
        assertThat(statements()).isZero();
        assertThat(feeEntries()).isZero();
        // The same lines, one each, are read and the fee posts once.
        assertThat(uploadAsAccountant(csv(line(intent, "CONF-224-1100", 520_000, 5_200, "2026-10-16")))
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
        assertThat(feeEntries()).isEqualTo(1L);
    }

    @Test
    void aStrayQuoteInAnIgnoredColumnRefusesTheFileInsteadOfDroppingTheLinesAfterIt() {
        String loan = activeLoan();
        JsonNode one = bookedRepayment(loan, 520_000, "CONF-224-1101");
        JsonNode two = bookedRepayment(activeLoan(), 310_000, "CONF-224-1102");
        String text = "order_tracking_id,confirmation_code,amount,fee,date,payer_name\n"
                + tracking(one) + ",CONF-224-1101,520000,5200,2026-10-16,Test \"Payer\n"
                + tracking(two) + ",CONF-224-1102,310000,3100,2026-10-16,Another\n";
        ResponseEntity<JsonNode> r = uploadAsAccountant(text);
        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat(r.getBody().get("errors").toString()).contains("line 2");
        assertThat(statements()).isZero();
        assertThat(feeEntries()).isZero();
    }

    @Test
    void aFutureDateOrAnAmbiguousSlashDateIsRefusedAndAnExportCoversOnlyTheDaysItIsCompleteFor() {
        String loan = activeLoan();
        JsonNode booked = bookedRepayment(loan, 520_000, "CONF-224-1103");
        JsonNode other = bookedRepayment(activeLoan(), 310_000, "CONF-224-1104");
        assertThat(uploadAsAccountant(csv(line(booked, "CONF-224-1103", 520_000, 0, "2026-10-17")))
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        assertThat(uploadAsAccountant(csv(line(booked, "CONF-224-1103", 520_000, 0, "10/11/2026")))
                        .getBody()
                        .get("errors")
                        .toString())
                .contains("Ambiguous");
        // A one day export that states no period covers no day: the other order is not reported missing from it.
        ResponseEntity<JsonNode> bare =
                uploadAsAccountant(csv(line(booked, "CONF-224-1103", 520_000, 0, "2026-10-16")));
        assertThat(bare.getStatusCode().value()).isEqualTo(201);
        assertThat(bare.getBody().get("statement").has("covered_from")
                        && !bare.getBody().get("statement").get("covered_from").isNull())
                .isFalse();
        assertThat(findings(runReconciliation(), "in_bms_not_in_provider", other))
                .isEmpty();
        // The uploader states the days it is complete for: now the order missing from it is reported.
        ResponseEntity<JsonNode> stated = uploadCovered(
                csv(line(booked, "CONF-224-1103", 520_000, 100, "2026-10-16")), "2026-10-16", "2026-10-16");
        assertThat(stated.getStatusCode().value()).as("%s", stated.getBody()).isEqualTo(201);
        assertThat(stated.getBody().get("statement").get("covered_from").asString())
                .isEqualTo("2026-10-16");
        assertThat(findings(runReconciliation(), "in_bms_not_in_provider", other))
                .hasSize(1);
        // A stated period must be sensible.
        assertThat(uploadCovered(
                                csv(line(booked, "CONF-224-1103", 520_000, 0, "2026-10-15")),
                                "2026-10-16",
                                "2026-10-15")
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        assertThat(uploadCovered(
                                csv(line(booked, "CONF-224-1103", 520_000, 0, "2026-10-14")),
                                "2026-10-14",
                                "2026-10-20")
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        assertThat(uploadCovered(csv(line(booked, "CONF-224-1103", 520_000, 0, "2026-10-13")), "2026-10-13", null)
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
    }

    @Test
    void aStatementWithSeveralDaysCoversTheDaysBetweenItsFirstAndLastLineWhenNoneIsStated() {
        String loan = activeLoan();
        JsonNode booked = bookedRepayment(loan, 520_000, "CONF-224-1105");
        JsonNode listed = bookedRepayment(activeLoan(), 310_000, "CONF-224-1106");
        // Lines on the 15th and the 17th... today is the 16th, so the export's days are the 14th and the 16th: the day
        // between them (the 15th) is covered and nothing was paid on it.
        ResponseEntity<JsonNode> r = uploadAsAccountant(csv(
                line(listed, "CONF-224-1106", 310_000, 0, "2026-10-14"),
                line(booked, "CONF-224-1105", 520_000, 0, "2026-10-16")));
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        assertThat(r.getBody().get("statement").get("covered_from").asString()).isEqualTo("2026-10-15");
        assertThat(r.getBody().get("statement").get("covered_to").asString()).isEqualTo("2026-10-15");
    }

    @Test
    void aManagerOfOneBranchOnlyCannotUploadReadRunOrMarkAnythingForTheWholeBusiness() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-1107");
        String file = csv(line(intent, "CONF-224-1107", 520_000, 5_200, "2026-10-16"));
        String branchOnly = t.headOffice().toString();
        HttpHeaders upload = as(accountant, accountantPerms(), branchOnly, null);
        upload.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        org.springframework.util.LinkedMultiValueMap<String, Object> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add(
                "file",
                new org.springframework.core.io.ByteArrayResource(
                        file.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    @Override
                    public String getFilename() {
                        return "s.csv";
                    }
                });
        assertThat(http.exchange(STATEMENTS, HttpMethod.POST, new HttpEntity<>(form, upload), JsonNode.class)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(statements()).isZero();
        assertThat(feeEntries()).isZero();
        // Whole-business holders do it; the branch holder cannot read the statement, list them, run, or mark a line.
        ResponseEntity<JsonNode> done = uploadAsAccountant(file);
        String id = done.getBody().get("statement").get("id").asString();
        assertThat(send(HttpMethod.GET, STATEMENTS, as(accountant, accountantPerms(), branchOnly, null), null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(send(
                                HttpMethod.GET,
                                STATEMENTS + "/" + id,
                                as(accountant, accountantPerms(), branchOnly, null),
                                null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(send(HttpMethod.POST, RECON + "/run", as(accountant, accountantPerms(), branchOnly, null), null)
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(send(
                                HttpMethod.POST,
                                STATEMENTS + "/" + id + "/rows/1/outside-system",
                                as(accountant, accountantPerms(), branchOnly, null),
                                Map.of("note", "Not ours"))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        assertThat(runCount()).isZero();
    }

    @Test
    void aLineMarkedAsOutsideThisSystemStopsBeingReportedAndOnlyNewDifferencesCountAsNews() {
        String loan = activeLoan();
        JsonNode booked = bookedRepayment(loan, 520_000, "CONF-224-1108");
        String stranger = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> r = uploadAsAccountant(csv(
                line(booked, "CONF-224-1108", 520_000, 0, "2026-10-16"),
                stranger + ",CONF-224-8888,70000,0,UGX,2026-10-16,MTN"));
        String id = r.getBody().get("statement").get("id").asString();
        long before = counterToday("reconciliation_differences");
        JsonNode first = runReconciliation();
        assertThat(findings(first, "in_provider_not_in_bms", null)).hasSize(1);
        assertThat(counterToday("reconciliation_differences"))
                .as("the first listing is news")
                .isEqualTo(before + 1);
        // The same difference the next night is not news.
        runReconciliation();
        runReconciliation();
        assertThat(counterToday("reconciliation_differences")).isEqualTo(before + 1);
        // A line that is one of our orders cannot be marked: it is not outside.
        ResponseEntity<JsonNode> ours = send(
                HttpMethod.POST,
                STATEMENTS + "/" + id + "/rows/1/outside-system",
                as(accountant, accountantPerms(), "*", null),
                Map.of("note", "Not ours"));
        assertThat(ours.getStatusCode().value()).isEqualTo(422);
        assertThat(ours.getBody().get("code").asString()).isEqualTo("not_outside");
        // A note is needed; a cashier may not; the stranger line can be marked, once, and is audited.
        assertThat(send(
                                HttpMethod.POST,
                                STATEMENTS + "/" + id + "/rows/2/outside-system",
                                as(accountant, accountantPerms(), "*", null),
                                Map.of("note", "x"))
                        .getStatusCode()
                        .value())
                .isEqualTo(422);
        assertThat(send(
                                HttpMethod.POST,
                                STATEMENTS + "/" + id + "/rows/2/outside-system",
                                as(cashier, cashierPerms, "*", null),
                                Map.of("note", "Sale at the shop"))
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        ResponseEntity<JsonNode> marked = send(
                HttpMethod.POST,
                STATEMENTS + "/" + id + "/rows/2/outside-system",
                as(accountant, accountantPerms(), "*", null),
                Map.of("note", "Sale at the shop"));
        assertThat(marked.getStatusCode().value()).as("%s", marked.getBody()).isEqualTo(200);
        // Maker-checker (ADR-035 decision 66): marked is not yet outside; the line stays a finding until a second
        // person
        // confirms.
        assertThat(marked.getBody().get("reviewed_outside").asBoolean()).isFalse();
        assertThat(marked.getBody().get("outside_awaiting_confirmation").asBoolean())
                .isTrue();
        assertThat(send(
                                HttpMethod.POST,
                                STATEMENTS + "/" + id + "/rows/2/outside-system",
                                as(accountant, accountantPerms(), "*", null),
                                Map.of("note", "Sale at the shop"))
                        .getStatusCode()
                        .value())
                .as("marking again asks nothing more")
                .isEqualTo(200);
        assertThat(findings(runReconciliation(), "in_provider_not_in_bms", null))
                .as("still open until confirmed")
                .hasSize(1);
        String request = outsideRequest();
        ResponseEntity<JsonNode> own = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + request + "/approve",
                as(accountant, accountantPerms(), "*", null),
                Map.of("note", "Checked"));
        assertThat(own.getStatusCode().value()).isEqualTo(422);
        assertThat(own.getBody().get("code").asString()).isEqualTo("self_approval_forbidden");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_statement_reviews WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
        ResponseEntity<JsonNode> confirmed = send(
                HttpMethod.POST,
                "/api/v1/approvals/" + request + "/approve",
                as(staff, adminPerms, "*", null),
                Map.of("note", "Checked with the shop"));
        assertThat(confirmed.getStatusCode().value())
                .as("%s", confirmed.getBody())
                .isEqualTo(200);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM payment_statement_reviews WHERE tenant_id = ? AND reviewed_by = ? AND confirmed_by = ?")
                        .params(t.tenantId(), accountant, staff)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'core.payments.statement.line_reviewed'")
                        .param(UUID.fromString(id))
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(findings(runReconciliation(), "in_provider_not_in_bms", null))
                .isEmpty();
        // A different, new difference is news again.
        JsonNode late = paidAndUnbooked(activeLoan(), 150_000, "CONF-224-1109", Duration.ofHours(3));
        runReconciliation();
        assertThat(counterToday("reconciliation_differences")).isEqualTo(before + 2);
        assertThat(findings(report(accountant, accountantPerms(), "*"), "completed_not_booked", late))
                .hasSize(1);
    }

    @Test
    void markingALineOutsideInABusinessWithNoHeadOfficeIsAClearRefusalNotAnError() {
        String stranger = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> r = uploadAsAccountant(csv(stranger + ",CONF-224-8890,70000,0,UGX,2026-10-16,MTN"));
        String id = r.getBody().get("statement").get("id").asString();
        TestDatabase.owner()
                .sql("UPDATE branches SET is_head_office = false WHERE id = ?")
                .param(t.headOffice())
                .update();
        try {
            ResponseEntity<JsonNode> marked = send(
                    HttpMethod.POST,
                    STATEMENTS + "/" + id + "/rows/1/outside-system",
                    as(accountant, accountantPerms(), "*", null),
                    Map.of("note", "Sale at the shop"));
            assertThat(marked.getStatusCode().value())
                    .as("%s", marked.getBody())
                    .isEqualTo(422);
            assertThat(marked.getBody().get("code").asString()).isEqualTo("head_office_missing");
        } finally {
            TestDatabase.owner()
                    .sql("UPDATE branches SET is_head_office = true WHERE id = ?")
                    .param(t.headOffice())
                    .update();
        }
    }

    /** The pending request that asks a second person to confirm a line is outside this system. */
    String outsideRequest() {
        return TestDatabase.owner()
                .sql("""
                        SELECT id FROM approval_requests
                         WHERE tenant_id = ? AND action_type = 'statement_line_outside' AND status = 'pending'
                         ORDER BY requested_at DESC LIMIT 1
                        """)
                .param(t.tenantId())
                .query(UUID.class)
                .single()
                .toString();
    }

    long counterToday(String outcome) {
        return TestDatabase.owner()
                .sql("SELECT coalesce(sum(hits), 0) FROM payment_callback_counters WHERE outcome = ?")
                .param(outcome)
                .query(Long.class)
                .single();
    }

    @Test
    void aReaderOfOneBranchSeesTotalsOfWhatHeCanSeeNotTheWholeBusinesss() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-1110");
        reportReversed(intent);
        runReconciliation();
        JsonNode whole = report(cashier, cashierPerms, "*");
        assertThat(whole.get("run").get("intents_examined").asInt()).isGreaterThan(0);
        JsonNode mine = report(cashier, cashierPerms, t.headOffice().toString());
        assertThat(mine.get("run").get("finding_count").asInt())
                .isEqualTo(mine.get("findings").size());
        JsonNode elsewhere = report(cashier, cashierPerms, t.secondBranch().toString());
        assertThat(elsewhere.get("run").get("finding_count").asInt()).isZero();
        assertThat(elsewhere.get("run").has("intents_examined")
                        && !elsewhere.get("run").get("intents_examined").isNull())
                .isFalse();
        assertThat(elsewhere.get("run").has("statements_used")
                        && !elsewhere.get("run").get("statements_used").isNull())
                .isFalse();
    }

    @Test
    void aRunThatCannotReadEverythingSaysSoAndListsNothingThatNeedsTheWholeAndKeepsTheNewestOrders() {
        String loan = activeLoan();
        JsonNode oldest = bookedRepayment(loan, 520_000, "CONF-224-1120");
        JsonNode middle = bookedRepayment(activeLoan(), 310_000, "CONF-224-1121");
        JsonNode newest = bookedRepayment(activeLoan(), 200_000, "CONF-224-1122");
        // An export that is complete for the day and lists only the newest: the others would be reported missing from
        // it.
        uploadCovered(csv(line(newest, "CONF-224-1122", 200_000, 0, "2026-10-16")), "2026-10-16", "2026-10-16");
        int page = ReconciliationService.page;
        int ceiling = ReconciliationService.intentCeiling;
        try {
            ReconciliationService.page = 2;
            ReconciliationService.intentCeiling = 2;
            JsonNode small = runReconciliation();
            assertThat(small.get("run").get("truncated").asBoolean()).isTrue();
            assertThat(small.get("run").get("intents_examined").asInt()).isEqualTo(2);
            // Partial: nothing that needs the whole is listed, so no payment is falsely reported missing.
            assertThat(findings(small, "in_bms_not_in_provider", null)).isEmpty();
        } finally {
            ReconciliationService.page = page;
            ReconciliationService.intentCeiling = ceiling;
        }
        JsonNode whole = runReconciliation();
        assertThat(whole.get("run").get("truncated").asBoolean()).isFalse();
        assertThat(findings(whole, "in_bms_not_in_provider", oldest)).hasSize(1);
        assertThat(findings(whole, "in_bms_not_in_provider", middle)).hasSize(1);
        assertThat(findings(whole, "in_bms_not_in_provider", newest)).isEmpty();
    }

    @Test
    void aLongStatementIsReadInPagesAndOneLongerThanTheCeilingIsTruncatedNotSilentlyCut() {
        String loan = activeLoan();
        JsonNode a = bookedRepayment(loan, 520_000, "CONF-224-1130");
        JsonNode b = bookedRepayment(activeLoan(), 310_000, "CONF-224-1131");
        JsonNode c = bookedRepayment(activeLoan(), 200_000, "CONF-224-1132");
        uploadCovered(
                csv(
                        line(a, "CONF-224-1130", 520_000, 0, "2026-10-16"),
                        line(b, "CONF-224-1131", 310_000, 0, "2026-10-16"),
                        line(c, "CONF-224-1132", 200_000, 0, "2026-10-16")),
                "2026-10-16",
                "2026-10-16");
        int page = ReconciliationService.page;
        int lines = ReconciliationService.lineCeiling;
        try {
            // Pages of two read three lines whole: nothing is missing, nothing is reported.
            ReconciliationService.page = 2;
            JsonNode paged = runReconciliation();
            assertThat(paged.get("run").get("truncated").asBoolean()).isFalse();
            assertThat(findings(paged, "in_bms_not_in_provider", null)).isEmpty();
            // A ceiling below the statement: partial, and it says so rather than reporting the unread lines' orders
            // missing.
            ReconciliationService.lineCeiling = 2;
            JsonNode cut = runReconciliation();
            assertThat(cut.get("run").get("truncated").asBoolean()).isTrue();
            assertThat(findings(cut, "in_bms_not_in_provider", null)).isEmpty();
        } finally {
            ReconciliationService.page = page;
            ReconciliationService.lineCeiling = lines;
        }
    }

    @Test
    void aManualReceiptThatOnlySharesAShortCodeWithAnUnrelatedPaymentIsNotCalledADuplicate() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-1140");
        String elsewhereLoan = activeLoan();
        // Another loan, another amount, the same short code: two genuine payments (a provider's short code is not
        // unique).
        manualRepayment(elsewhereLoan, 73_000, "mtn_momo", "CONF-224-1140");
        assertThat(findings(runReconciliation(), "manual_receipt_duplicate", intent))
                .isEmpty();
        // The same loan: the same payment keyed twice.
        manualRepayment(loan, 60_000, "mtn_momo", "conf-224-1140");
        assertThat(findings(runReconciliation(), "manual_receipt_duplicate", intent))
                .hasSize(1);
    }

    @Test
    void aManualReceiptOfTheSameAmountWithinThreeDaysIsFlaggedOnAnotherLoanButNotOneFromWeeksBefore() {
        String closeLoan = activeLoan();
        String farLoan = activeLoan();
        String manualClose = activeLoan();
        String manualFar = activeLoan();
        // Both receipts are keyed by hand, dated the 16th, for the same amount as a gateway payment, on a loan of their
        // own.
        JsonNode close = manualRepayment(manualClose, 520_000, "airtel_money", "CONF-224-1141");
        manualRepayment(manualFar, 410_000, "airtel_money", "CONF-224-1142");
        // The gateway payment with that code two days later is the same payment keyed twice...
        clock.set(clock.instant().plus(Duration.ofDays(2)));
        JsonNode nearIntent = bookedRepayment(closeLoan, 520_000, "CONF-224-1141");
        // ...and one with the other code two weeks later is another payment that happens to carry the same short code.
        clock.set(clock.instant().plus(Duration.ofDays(14)));
        JsonNode farIntent = bookedRepayment(farLoan, 410_000, "CONF-224-1142");
        JsonNode report = runReconciliation();
        List<JsonNode> flagged = findings(report, "manual_receipt_duplicate", nearIntent);
        assertThat(flagged).hasSize(1);
        assertThat(flagged.getFirst().get("other_transaction_id").asString())
                .isEqualTo(close.get("transaction").get("id").asString());
        assertThat(findings(report, "manual_receipt_duplicate", farIntent)).isEmpty();
    }

    @Test
    void aRunWritesNothingToAnOrderButItsNextLookAndItsTimestamp() {
        String loan = activeLoan();
        bookedRepayment(loan, 520_000, "CONF-224-1150");
        JsonNode stuck = paidAndUnbooked(activeLoan(), 310_000, "CONF-224-1151", Duration.ofHours(3));
        JsonNode pending = intentFor(activeLoan(), 150_000);
        TestDatabase.owner()
                .sql("UPDATE payment_intents SET next_check_at = NULL WHERE id = ?")
                .param(UUID.fromString(pending.get("id").asString()))
                .update();
        clock.set(clock.instant().plus(Duration.ofHours(25)));
        String snapshot =
                "SELECT string_agg((to_jsonb(i) - 'next_check_at' - 'updated_at')::text, ',' ORDER BY i.id) FROM payment_intents i WHERE i.tenant_id = ?";
        String before = TestDatabase.owner()
                .sql(snapshot)
                .param(t.tenantId())
                .query(String.class)
                .single();
        runReconciliation();
        assertThat(TestDatabase.owner()
                        .sql(snapshot)
                        .param(t.tenantId())
                        .query(String.class)
                        .single())
                .as("no column of an order but its next look and its timestamp changes")
                .isEqualTo(before);
        assertThat(row(stuck).get("next_check_at")).isNotNull();
        assertThat(row(pending).get("next_check_at")).isNotNull();
    }

    @Test
    void aReversalHandedOverToAPersonIsListedAsSuchNotAsWaiting() {
        String loan = activeLoan();
        JsonNode intent = bookedRepayment(loan, 520_000, "CONF-224-1160");
        reportReversed(intent);
        assertThat(findings(runReconciliation(), "reversed_awaiting_review", intent))
                .hasSize(1);
        assertThat(decideAs(
                                reversalRequests(intent).getFirst().get("id").toString(),
                                "reject",
                                accountant,
                                permissionsOf("accountant"))
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        JsonNode report = runReconciliation();
        assertThat(findings(report, "reversed_awaiting_review", intent)).isEmpty();
        assertThat(findings(report, "reversal_handed_over", intent)).hasSize(1);
    }
}
