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
import com.rincoltech.bms.testsupport.SmsTenants;
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
 * Loan repayment by mobile money (extras slice A3, issue #216, ADR-035, spec 3.4, 3.4.1, 3.7, 3.8) on a real database with
 * the in-process fake provider: a staff-started intent for a loan is created only through the tenant's own verified
 * merchant account (a tenant without one is refused with {@code merchant_account_not_verified} and no provider call, and
 * the money is attributed to that account's merchant id); a paid order is booked through {@code LoanServicing} exactly like
 * a cashier's repayment (same allocation, receipt number and journal, with the method {@code gateway}) and is idempotent
 * when the notification comes three times or the same confirmation code is presented again; a payment completed after the
 * order expired still books; and a loan that closed meanwhile turns the money into an unallocated receipt instead of
 * stranding it. Every name, number and reference is fabricated.
 */
@Import({LoanRepaymentByGatewayIT.Config.class, MovableClockConfig.class})
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
class LoanRepaymentByGatewayIT extends LoanFixtures {

    static final String PURPOSE = "lending.loan_repayment";
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
    int memberNo = 10;

    @BeforeEach
    void gatewaySetUp() {
        fake = (FakePaymentGateway) gateway;
        fake.reset();
        merchantAccounts.forgetTests();
        clock.set(NOW);
        cashierPerms = permissionsOf("cashier");
        staff = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', 'Test Staff 216', ?, 'active')")
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
                "Test Borrower %02d".formatted(memberNo), "07000000%02d".formatted(memberNo), t.headOffice(), true);
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
                Map.of("purpose", PURPOSE, "subject_id", loan, "amount_minor", amount, "payer_phone", "0700000216"),
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

    // ---- the own-account rule -----------------------------------------------------------------

    @Test
    void aTenantWithoutAVerifiedOwnAccountGetsMerchantAccountNotVerifiedAndNoProviderCall() {
        String loan = activeLoan();
        long callsBefore = fake.calls().size();
        long creates = fake.creates();
        // The account is connected and collections are on, then a connection test fails: the account is not verified.
        fake.failRegister(
                new GatewayException(GatewayException.Kind.AUTH, 200, "invalid_consumer_key_or_secret_provided"));
        ResponseEntity<JsonNode> failed = call(HttpMethod.POST, "/api/v1/payments/merchant-account/test", null, null);
        assertThat(failed.getBody().get("status").asString()).isEqualTo("failed");
        callsBefore = fake.calls().size();

        ResponseEntity<JsonNode> refused = startIntent(loan, 520_000);
        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("merchant_account_not_verified");
        assertThat(fake.calls()).hasSize((int) callsBefore);
        assertThat(fake.creates()).isEqualTo(creates);
        assertThat(fake.accountsUsed()).noneMatch(a -> a.scope() == PaymentGateway.Scope.PLATFORM);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_intents WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aTenantThatNeverConnectedAnAccountCannotCollectEitherAndNothingReachesTheProvider() {
        TestDatabase.Fixture none = TestDatabase.tenant("loans-noacct", true);
        UUID admin = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', 'Test Staff 216', ?, 'active')")
                .params(admin, none.tenantId(), "staff-" + admin + "@example.test")
                .update();
        long before = fake.calls().size();
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", none.slug());
        h.add("X-Dev-User-Id", admin.toString());
        h.add("X-Dev-Permissions", adminPerms);
        h.add("X-Dev-Branch-Ids", "*");
        ResponseEntity<JsonNode> r = http.exchange(
                "/api/v1/payments/intents",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "purpose",
                                PURPOSE,
                                "subject_id",
                                UUID.randomUUID(),
                                "amount_minor",
                                1000,
                                "payer_phone",
                                "0700000216"),
                        withKey(h)),
                JsonNode.class);
        assertThat(r.getStatusCode().value()).isEqualTo(422);
        assertThat(r.getBody().get("code").asString()).isIn("collections_disabled", "merchant_account_not_verified");
        assertThat(fake.calls()).hasSize((int) before);
    }

    static HttpHeaders withKey(HttpHeaders h) {
        h.add("Idempotency-Key", "key-" + UUID.randomUUID());
        return h;
    }

    // ---- booking -------------------------------------------------------------------------------

    @Test
    void aPaidOrderBooksLikeACashRepaymentAndTheMoneyIsAttributedToTheLendersOwnMerchantAccount() {
        SmsTenants.operator(t.tenantId(), true, 1000);
        SmsTenants.enable(t.tenantId());
        String cashLoan = activeLoan();
        String gatewayLoan = activeLoan();
        cashRepayment(cashLoan, 520_000);

        JsonNode intent = intentFor(gatewayLoan, 520_000);
        UUID merchant = TestDatabase.owner()
                .sql("SELECT merchant_id FROM payment_gateway_accounts WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(UUID.class)
                .single();
        // The order was made on this tenant's own account, bound to its merchant id; the platform's never.
        PaymentGateway.Account used = fake.accountsUsed().getLast();
        assertThat(used.scope()).isEqualTo(PaymentGateway.Scope.TENANT);
        assertThat(used.tenantId()).isEqualTo(t.tenantId());
        assertThat(used.merchantId()).isEqualTo(merchant);
        assertThat(fake.accountsUsed()).noneMatch(a -> a.scope() == PaymentGateway.Scope.PLATFORM);
        assertThat(row(intent).get("merchant_id")).isEqualTo(merchant);

        pay(intent, 520_000, "CONF-216-0001");
        tick(Duration.ofMinutes(3));
        Map<String, Object> intentRow = row(intent);
        assertThat(intentRow.get("status")).isEqualTo("booked");
        assertThat(intentRow.get("merchant_id")).isEqualTo(merchant);

        // One repayment, by method gateway, with the confirmation code, a receipt of the same shape as the cashier's.
        Map<String, Object> txn = repayments(gatewayLoan).getFirst();
        assertThat(repayments(gatewayLoan)).hasSize(1);
        assertThat(txn.get("id")).isEqualTo(intentRow.get("booked_subject_txn_id"));
        assertThat(txn.get("payment_method_key")).isEqualTo("gateway");
        assertThat(txn.get("source")).isEqualTo("gateway");
        assertThat(txn.get("external_reference")).isEqualTo(tracking(intent));
        assertThat(txn.get("recorded_by")).isEqualTo(staff);
        assertThat((String) txn.get("receipt_no")).matches("RC-HQ-\\d{6}");
        assertThat(txn.get("amount_minor")).isEqualTo(520_000L);

        // The same allocation and journal as the cashier's entry, apart from the account the money came in on.
        Map<String, Object> cash = repayments(cashLoan).getFirst();
        List<String> cashLines = lines(cash.get("journal_entry_id"));
        List<String> gatewayLines = lines(txn.get("journal_entry_id"));
        assertThat(gatewayLines.getFirst()).isEqualTo("gateway_clearing:520000:0");
        assertThat(cashLines.getFirst()).isEqualTo("cash_on_hand:520000:0");
        assertThat(gatewayLines.subList(1, gatewayLines.size())).isEqualTo(cashLines.subList(1, cashLines.size()));
        assertThat(get(gatewayLoan, "/schedule").get("items"))
                .isEqualTo(get(cashLoan, "/schedule").get("items").deepCopy());
        JsonNode gatewayBalances = get(gatewayLoan, "").get("balances");
        JsonNode cashBalances = get(cashLoan, "").get("balances");
        assertThat(gatewayBalances.get("principal_outstanding_minor"))
                .isEqualTo(cashBalances.get("principal_outstanding_minor"));
        assertThat(gatewayBalances.get("interest_outstanding_minor"))
                .isEqualTo(cashBalances.get("interest_outstanding_minor"));

        // The audit rows: the repayment's names its origin, the intent's the merchant account and the order.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data->'after'->>'origin' FROM audit_log WHERE entity_id = ? AND action = 'lending.loan.repayment_recorded'")
                        .param(UUID.fromString(gatewayLoan))
                        .query(String.class)
                        .single())
                .isEqualTo("gateway");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data->'after'->>'merchant_id' || '/' || (data->'after'->>'order_tracking_id') FROM audit_log WHERE entity_id = ? AND action = 'core.payments.intent.booked'")
                        .param(UUID.fromString(intent.get("id").asString()))
                        .query(String.class)
                        .single())
                .isEqualTo(merchant + "/" + tracking(intent));
        // The receipt text is queued in the same transaction (the tenant has SMS on).
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM notification_outbox WHERE channel = 'sms' AND tenant_id = ? AND template_key = 'loan.repayment_received'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
        // The trial balance still balances.
        assertThat(TestDatabase.owner()
                        .sql("SELECT coalesce(sum(debit) - sum(credit), 0) FROM journal_lines WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aPartialPaymentIsBookedAsPaidAndAnExcessIsHeldAsACreditOnTheLoan() {
        String loan = activeLoan();
        JsonNode partial = intentFor(loan, 520_000);
        pay(partial, 300_000, "CONF-216-0002");
        tick(Duration.ofMinutes(3));
        assertThat(row(partial).get("status")).isEqualTo("booked");
        assertThat(row(partial).get("paid_amount_minor")).isEqualTo(300_000L);
        assertThat(repayments(loan).getFirst().get("amount_minor")).isEqualTo(300_000L);
        assertThat(get(loan, "")
                        .get("balances")
                        .get("principal_outstanding_minor")
                        .asLong())
                // R-ALLOC: interest of the first instalment (120,000) before principal, so 180,000 of principal is
                // paid.
                .isEqualTo(1_200_000L - 180_000L);

        JsonNode excess = intentFor(loan, 520_000);
        pay(excess, 1_400_000, "CONF-216-0003");
        tick(Duration.ofMinutes(3));
        assertThat(row(excess).get("status")).isEqualTo("booked");
        assertThat(row(excess).get("paid_amount_minor")).isEqualTo(1_400_000L);
        // 1,560,000 is owed in all; 300,000 and 1,400,000 paid: the loan is settled and 140,000 is held as credit.
        assertThat(get(loan, "").get("status").asString()).isEqualTo("closed");
        assertThat(TestDatabase.owner()
                        .sql("SELECT credit_balance_minor FROM lending_loans WHERE id = ?")
                        .param(UUID.fromString(loan))
                        .query(Long.class)
                        .single())
                .isEqualTo(140_000L);
    }

    @Test
    void anAmountAboveThePayoffAndALoanThatIsNotActiveAreRefusedBeforeAnyOrderIsMade() {
        String loan = activeLoan();
        long creates = fake.creates();
        ResponseEntity<JsonNode> tooMuch = startIntent(loan, 1_560_001);
        assertThat(tooMuch.getStatusCode().value()).isEqualTo(422);
        assertThat(tooMuch.getBody().get("code").asString()).isEqualTo("exceeds_payoff");
        String approved = approvedLoan(exampleProduct("GWAPP"));
        ResponseEntity<JsonNode> notActive = startIntent(approved, 1000);
        assertThat(notActive.getStatusCode().value()).isEqualTo(422);
        assertThat(notActive.getBody().get("code").asString()).isEqualTo("subject_not_payable");
        assertThat(startIntent(UUID.randomUUID().toString(), 1000)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("subject_not_payable");
        assertThat(fake.creates()).isEqualTo(creates);
    }

    // ---- duplicates ----------------------------------------------------------------------------

    @Test
    void theSameNotificationThreeTimesAndAPollBookOneRepayment() {
        String loan = activeLoan();
        JsonNode intent = intentFor(loan, 520_000);
        pay(intent, 520_000, "CONF-216-0004");
        for (int i = 0; i < 3; i++) {
            ResponseEntity<JsonNode> r = http.exchange(
                    "/api/v1/payments/callbacks/pesapal/" + token + "?OrderTrackingId=" + tracking(intent)
                            + "&OrderMerchantReference="
                            + intent.get("merchant_reference").asString()
                            + "&OrderNotificationType=IPNCHANGE",
                    HttpMethod.GET,
                    HttpEntity.EMPTY,
                    JsonNode.class);
            assertThat(r.getStatusCode().value()).isEqualTo(200);
        }
        tick(Duration.ofSeconds(30));
        tick(Duration.ofMinutes(3));
        tick(Duration.ofMinutes(3));
        assertThat(row(intent).get("status")).isEqualTo("booked");
        assertThat(repayments(loan)).hasSize(1);
        assertThat(get(loan, "")
                        .get("balances")
                        .get("principal_outstanding_minor")
                        .asLong())
                .isEqualTo(1_200_000L - 400_000L);
    }

    @Test
    void thePortReturnsTheFirstTransactionForARepeatedConfirmationCodeAndTheIndexBacksIt() {
        String loan = activeLoan();
        UUID first = tenantJobs.callForTenant(
                t.tenantId(),
                () -> port.recordRepayment(
                        UUID.fromString(loan), 100_000, TODAY, "gateway", staff, "CONF-216-0005", "gateway", false));
        UUID again = tenantJobs.callForTenant(
                t.tenantId(),
                () -> port.recordRepayment(
                        UUID.fromString(loan), 100_000, TODAY, "gateway", staff, "CONF-216-0005", "gateway", false));
        assertThat(again).isEqualTo(first);
        assertThat(repayments(loan)).hasSize(1);
        // The seed and the import keep texting nobody: the port with queueReceiptNotice false queued nothing.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
        // At the database: a second row with the same method and reference is refused for the table owner too.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> TestDatabase.owner().sql("""
                                INSERT INTO lending_loan_transactions (id, tenant_id, branch_id, loan_id, txn_type, amount_minor, currency,
                                    value_date, payment_method_key, external_reference, receipt_no, journal_entry_id, source)
                                SELECT gen_random_uuid(), tenant_id, branch_id, loan_id, txn_type, amount_minor, currency,
                                    value_date, payment_method_key, external_reference, 'RC-HQ-999999', journal_entry_id, source
                                  FROM lending_loan_transactions WHERE id = ?
                                """).param(first).update())
                .hasMessageContaining("lending_loan_transactions_gateway_reference");
    }

    // ---- late and refused ----------------------------------------------------------------------

    @Test
    void aPaymentCompletedAfterTheOrderExpiredStillBooksAndIsFlaggedLate() {
        String loan = activeLoan();
        JsonNode intent = intentFor(loan, 520_000);
        tick(Duration.ofMinutes(31));
        assertThat(row(intent).get("status")).isEqualTo("expired");
        assertThat(repayments(loan)).isEmpty();
        pay(intent, 520_000, "CONF-216-0006");
        tick(Duration.ofMinutes(15));
        assertThat(row(intent).get("status")).isEqualTo("booked");
        assertThat(repayments(loan)).hasSize(1);
        ResponseEntity<JsonNode> view = call(
                HttpMethod.GET, "/api/v1/payments/intents/" + intent.get("id").asString(), null, null);
        assertThat(view.getBody().get("flags").findValuesAsString("")).isNotNull();
        assertThat(view.getBody().get("flags").toString()).contains("late");
        // The same completion seen again books nothing more.
        tick(Duration.ofHours(2));
        assertThat(repayments(loan)).hasSize(1);
    }

    @Test
    void aLoanThatClosedBeforeTheMoneyArrivedTurnsItIntoAnUnallocatedReceiptNotALostPayment() {
        String loan = activeLoan();
        JsonNode intent = intentFor(loan, 520_000);
        // The cashier settles the whole loan while the payer is still on the hosted page.
        cashRepayment(loan, 1_560_000);
        assertThat(get(loan, "").get("status").asString()).isEqualTo("closed");
        pay(intent, 520_000, "CONF-216-0007");
        tick(Duration.ofMinutes(3));
        Map<String, Object> intentRow = row(intent);
        assertThat(intentRow.get("status")).isEqualTo("booked_unallocated");
        assertThat(intentRow.get("refusal_code")).isEqualTo("invalid_status_transition");
        assertThat(intentRow.get("booked_subject_txn_id")).isNull();
        // Nothing was added to the loan; the money sits in unallocated receipts with its journal.
        assertThat(repayments(loan)).hasSize(1);
        Map<String, Object> receipt = TestDatabase.owner()
                .sql("SELECT * FROM unallocated_receipts WHERE intent_id = ?")
                .param(UUID.fromString(intent.get("id").asString()))
                .query()
                .singleRow();
        assertThat(receipt.get("amount_minor")).isEqualTo(520_000L);
        assertThat(receipt.get("status")).isEqualTo("open");
        assertThat(receipt.get("confirmation_code")).isEqualTo("CONF-216-0007");
        assertThat(lines(receipt.get("journal_entry_id")))
                .containsExactly("gateway_clearing:520000:0", "unallocated_receipts:0:520000");
        // More polling books nothing further.
        tick(Duration.ofHours(2));
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM unallocated_receipts WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    void theSameReferenceForAnotherLoanThroughThePortIsNeverAReplayAndTheHandlerHoldsItUnallocated() {
        String first = activeLoan();
        String second = activeLoan();
        tenantJobs.callForTenant(
                t.tenantId(),
                () -> port.recordRepayment(
                        UUID.fromString(first), 100_000, TODAY, "gateway", staff, "ORDER-216-SAME", "gateway", false));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tenantJobs.callForTenant(
                        t.tenantId(),
                        () -> port.recordRepayment(
                                UUID.fromString(second),
                                100_000,
                                TODAY,
                                "gateway",
                                staff,
                                "ORDER-216-SAME",
                                "gateway",
                                false)))
                .hasMessageContaining("already booked a different payment");
        assertThat(repayments(second)).isEmpty();
    }

    @Test
    void twoGenuinePaymentsOfTheSameAmountOnOneLoanWithTheSameConfirmationCodeBothBook() {
        String loan = activeLoan();
        JsonNode a = intentFor(loan, 100_000);
        JsonNode b = intentFor(loan, 100_000);
        pay(a, 100_000, "CONF-216-SAME");
        pay(b, 100_000, "CONF-216-SAME");
        tick(Duration.ofMinutes(3));
        assertThat(row(a).get("status")).isEqualTo("booked");
        assertThat(row(b).get("status")).isEqualTo("booked");
        assertThat(row(a).get("booked_subject_txn_id")).isNotEqualTo(row(b).get("booked_subject_txn_id"));
        assertThat(repayments(loan)).hasSize(2);
        assertThat(repayments(loan).stream()
                        .map(r -> r.get("external_reference"))
                        .toList())
                .containsExactlyInAnyOrder(tracking(a), tracking(b));
        // The provider's code is on the intents' audit rows.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE action = 'core.payments.intent.booked' AND data->'after'->>'confirmation_code' = 'CONF-216-SAME' AND tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
        // The database backstop exists: one subject transaction is never the booking of two intents.
        assertThat(TestDatabase.owner()
                        .sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'payment_intents_one_booking_per_txn'")
                        .query(String.class)
                        .single())
                .contains("UNIQUE", "booked_subject_txn_id");
    }

    @Test
    void anUnallocatedReceiptDebitsTheAccountTheGatewayMethodIsMappedToAtTheBranch() {
        String till = assetAccount("1099", "Test gateway till");
        TestDatabase.owner()
                .sql(
                        "UPDATE payment_methods SET gl_account_id = ?::uuid WHERE tenant_id = ? AND method_key = 'gateway'")
                .params(till, t.tenantId())
                .update();
        String loan = activeLoan();
        JsonNode intent = intentFor(loan, 520_000);
        cashRepayment(loan, 1_560_000);
        pay(intent, 520_000, "CONF-216-MAP");
        tick(Duration.ofMinutes(3));
        assertThat(row(intent).get("status")).isEqualTo("booked_unallocated");
        Object entry = TestDatabase.owner()
                .sql("SELECT journal_entry_id FROM unallocated_receipts WHERE intent_id = ?")
                .param(UUID.fromString(intent.get("id").asString()))
                .query(UUID.class)
                .single();
        assertThat(codedLines(entry.toString())).containsExactly("1099:520000:0", "2040:0:520000");
    }

    @Test
    void whenTheGatewayMethodIsUnmappedTheReceiptFallsBackToGatewayClearingAndCommits() {
        String loan = activeLoan();
        JsonNode intent = intentFor(loan, 520_000);
        TestDatabase.owner()
                .sql("UPDATE payment_methods SET is_active = false WHERE tenant_id = ? AND method_key = 'gateway'")
                .param(t.tenantId())
                .update();
        pay(intent, 520_000, "CONF-216-UNM");
        tick(Duration.ofMinutes(3));
        assertThat(row(intent).get("status")).isEqualTo("booked_unallocated");
        assertThat(row(intent).get("refusal_code")).isEqualTo("payment_method_unmapped");
        Object entry = TestDatabase.owner()
                .sql("SELECT journal_entry_id FROM unallocated_receipts WHERE intent_id = ?")
                .param(UUID.fromString(intent.get("id").asString()))
                .query(UUID.class)
                .single();
        assertThat(lines(entry)).containsExactly("gateway_clearing:520000:0", "unallocated_receipts:0:520000");
        assertThat(repayments(loan)).isEmpty();
    }

    @Test
    void aLoanWrittenOffBeforeTheMoneyArrivedTurnsItIntoAnUnallocatedReceiptNotARecovery() {
        String loan = activeLoan();
        JsonNode intent = intentFor(loan, 520_000);
        TestDatabase.owner()
                .sql("UPDATE lending_loans SET status = 'written_off' WHERE id = ?")
                .param(UUID.fromString(loan))
                .update();
        pay(intent, 520_000, "CONF-216-WO");
        tick(Duration.ofMinutes(3));
        assertThat(row(intent).get("status")).isEqualTo("booked_unallocated");
        assertThat(row(intent).get("refusal_code")).isEqualTo("invalid_status_transition");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM lending_loan_transactions WHERE loan_id = ? AND txn_type = 'recovery'")
                        .param(UUID.fromString(loan))
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aGatewayRepaymentCannotBeReversedByStaffUntilTheProviderReviewExists() {
        String loan = activeLoan();
        JsonNode intent = intentFor(loan, 520_000);
        pay(intent, 520_000, "CONF-216-REV");
        tick(Duration.ofMinutes(3));
        Object txn = repayments(loan).getFirst().get("id");
        HttpHeaders h = as(staff, adminPerms, "*", null);
        h.add("Idempotency-Key", "rev-" + UUID.randomUUID());
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                LOANS + "/" + loan + "/transactions/" + txn + "/reverse",
                h,
                Map.of("reason", "Test reversal of a gateway payment"));
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(422);
        assertThat(r.getBody().get("code").asString()).isEqualTo("gateway_reversal_pending");
    }

    @Test
    void aStaffMemberWithoutCollectInTheLoansBranchIsRefusedBeforeTheAmountIsChecked() {
        String loan = activeLoan();
        HttpHeaders h = as(staff, "lending.loans.read", "*", null);
        h.add("Idempotency-Key", "k-" + UUID.randomUUID());
        ResponseEntity<JsonNode> r = http.exchange(
                "/api/v1/payments/intents",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "purpose",
                                PURPOSE,
                                "subject_id",
                                loan,
                                "amount_minor",
                                99_999_999,
                                "payer_phone",
                                "0700000216"),
                        h),
                JsonNode.class);
        assertThat(r.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void aPaymentWhoseDayFellInAClosedPeriodIsBookedDatedTodayEndToEnd() {
        String loan = activeLoan();
        clock.set(Instant.parse("2026-10-31T06:00:00Z"));
        JsonNode intent = intentFor(loan, 520_000);
        pay(intent, 520_000, "CONF-216-PC");
        // The first booking fails technically (so the payment is confirmed on 31 October and waits), then the October
        // period closes.
        TestDatabase.owner()
                .sql(
                        "CREATE OR REPLACE FUNCTION it216_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'it216 technical failure'; END $$")
                .update();
        TestDatabase.owner()
                .sql(
                        "CREATE TRIGGER it216_fail BEFORE INSERT ON lending_loan_transactions FOR EACH ROW EXECUTE FUNCTION it216_fail()")
                .update();
        try {
            tick(Duration.ofMinutes(3));
        } finally {
            TestDatabase.owner()
                    .sql("DROP TRIGGER IF EXISTS it216_fail ON lending_loan_transactions")
                    .update();
        }
        assertThat(row(intent).get("status")).isEqualTo("paid");
        TestDatabase.owner()
                .sql(
                        "UPDATE gl_periods SET status = 'closed', closed_at = now() WHERE tenant_id = ? AND year = 2026 AND month = 10")
                .param(t.tenantId())
                .update();
        clock.set(Instant.parse("2026-11-01T06:00:00Z"));
        poller.tick();
        assertThat(row(intent).get("status")).isEqualTo("booked");
        assertThat(repayments(loan).getFirst().get("value_date").toString()).isEqualTo("2026-11-01");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data->'after'->>'note' FROM audit_log WHERE entity_id = ? AND action = 'core.payments.intent.booked'")
                        .param(UUID.fromString(intent.get("id").asString()))
                        .query(String.class)
                        .single())
                .isEqualTo("dated_today");
    }
}
