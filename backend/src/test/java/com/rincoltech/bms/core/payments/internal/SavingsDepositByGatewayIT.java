package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.payments.FakePaymentGateway;
import com.rincoltech.bms.core.payments.GatewayException;
import com.rincoltech.bms.core.payments.PaymentGateway;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import com.rincoltech.bms.lending.savings.SavingsServicing;
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
 * Savings deposit by mobile money (extras slice A4, issue #219, ADR-035, spec 3.4, 3.4.1, 3.7, 3.8) on a real database with
 * the in-process fake provider, the twin of the loan repayment test: a staff-started intent for a savings account is created
 * only through the tenant's own verified merchant account; a paid order is booked through {@code SavingsServicing} exactly
 * like a cashier's deposit (same receipt number, journal, running balance and SMS, with the method {@code gateway}) and is
 * idempotent on the order tracking id, never on the provider's confirmation code; a payment completed after the order
 * expired still books; a payment dated into a day whose balances are written books dated today; and an account that was
 * frozen or closed meanwhile turns the money into an unallocated receipt instead of stranding it. Every name, number and
 * reference is fabricated.
 */
@Import({SavingsDepositByGatewayIT.Config.class, MovableClockConfig.class})
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
class SavingsDepositByGatewayIT extends LoanFixtures {

    static final String PURPOSE = "lending.savings_deposit";
    static final String ACCOUNTS = "/api/v1/lending/savings-accounts";
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
    SavingsServicing port;

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
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', 'Test Staff 219', ?, 'active')")
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

    // ---- fixtures -------------------------------------------------------------------------------

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

    ResponseEntity<JsonNode> startIntent(String account, long amount) {
        return call(
                HttpMethod.POST,
                "/api/v1/payments/intents",
                Map.of("purpose", PURPOSE, "subject_id", account, "amount_minor", amount, "payer_phone", "0700000219"),
                "key-" + UUID.randomUUID());
    }

    JsonNode intentFor(String account, long amount) {
        ResponseEntity<JsonNode> r = startIntent(account, amount);
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

    JsonNode get(String account) {
        ResponseEntity<JsonNode> r =
                send(HttpMethod.GET, ACCOUNTS + "/" + account, as(cashier, cashierPerms, "*", null), null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    long balance(String account) {
        return get(account).get("balance_minor").asLong();
    }

    void setStatus(String account, String status) {
        TestDatabase.owner()
                .sql(
                        "UPDATE lending_savings_accounts SET status = ?, closed_on = CASE WHEN ? = 'closed' THEN CAST(? AS date) END WHERE id = ?")
                .params(status, status, java.sql.Date.valueOf(TODAY), UUID.fromString(account))
                .update();
    }

    JsonNode cashDeposit(String account, long amount) {
        HttpHeaders h = as(cashier, cashierPerms, "*", null);
        h.add("Idempotency-Key", "cash-" + UUID.randomUUID());
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/deposits",
                h,
                Map.of("amount_minor", amount, "payment_method_key", "cash", "external_reference", "TEST-CASH-219"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    /** The deposit rows of an account, oldest first. */
    List<Map<String, Object>> deposits(String account) {
        return TestDatabase.owner()
                .sql(
                        "SELECT * FROM lending_savings_transactions WHERE account_id = ? AND txn_type = 'deposit' ORDER BY seq")
                .param(UUID.fromString(account))
                .query()
                .listOfRows();
    }

    List<String> lines(Object entryId) {
        return TestDatabase.owner().sql("""
                        SELECT a.system_key || ':' || l.debit || ':' || l.credit FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id WHERE l.entry_id = ? ORDER BY l.line_no
                        """).param(entryId).query(String.class).list();
    }

    Map<String, Object> receiptOf(JsonNode intent) {
        return TestDatabase.owner()
                .sql("SELECT * FROM unallocated_receipts WHERE intent_id = ?")
                .param(UUID.fromString(intent.get("id").asString()))
                .query()
                .singleRow();
    }

    /** The trial balance balances and member_savings equals every account's balance. */
    void booksAgree() {
        assertThat(TestDatabase.owner()
                        .sql("SELECT coalesce(sum(debit) - sum(credit), 0) FROM journal_lines WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT count(*) FROM lending_savings_accounts s WHERE s.tenant_id = ? AND s.balance_minor <>
                                    (SELECT coalesce(sum(j.credit - j.debit), 0) FROM journal_lines j
                                       JOIN gl_accounts a ON a.id = j.account_id AND a.system_key = 'member_savings'
                                      WHERE j.subledger_id = s.id)
                                """)
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // ---- the own-account rule -------------------------------------------------------------------

    @Test
    void aTenantWithoutAVerifiedOwnAccountGetsMerchantAccountNotVerifiedAndNoProviderCall() {
        String account = account();
        long creates = fake.creates();
        fake.failRegister(
                new GatewayException(GatewayException.Kind.AUTH, 200, "invalid_consumer_key_or_secret_provided"));
        ResponseEntity<JsonNode> failed = call(HttpMethod.POST, "/api/v1/payments/merchant-account/test", null, null);
        assertThat(failed.getBody().get("status").asString()).isEqualTo("failed");
        long callsBefore = fake.calls().size();

        ResponseEntity<JsonNode> refused = startIntent(account, 50_000);
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

    // ---- booking --------------------------------------------------------------------------------

    @Test
    void aPaidOrderBooksLikeACashDepositAndTheMoneyIsAttributedToTheLendersOwnMerchantAccount() {
        SmsTenants.operator(t.tenantId(), true, 1000);
        SmsTenants.enable(t.tenantId());
        String cashAccount = account();
        String gatewayAccount = account();
        cashDeposit(cashAccount, 50_000);

        JsonNode intent = intentFor(gatewayAccount, 50_000);
        UUID merchant = TestDatabase.owner()
                .sql("SELECT merchant_id FROM payment_gateway_accounts WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(UUID.class)
                .single();
        PaymentGateway.Account used = fake.accountsUsed().getLast();
        assertThat(used.scope()).isEqualTo(PaymentGateway.Scope.TENANT);
        assertThat(used.tenantId()).isEqualTo(t.tenantId());
        assertThat(used.merchantId()).isEqualTo(merchant);
        assertThat(fake.accountsUsed()).noneMatch(a -> a.scope() == PaymentGateway.Scope.PLATFORM);
        assertThat(row(intent).get("merchant_id")).isEqualTo(merchant);

        pay(intent, 50_000, "CONF-219-0001");
        tick(Duration.ofMinutes(3));
        Map<String, Object> intentRow = row(intent);
        assertThat(intentRow.get("status")).isEqualTo("booked");

        Map<String, Object> txn = deposits(gatewayAccount).getFirst();
        assertThat(deposits(gatewayAccount)).hasSize(1);
        assertThat(txn.get("id")).isEqualTo(intentRow.get("booked_subject_txn_id"));
        assertThat(txn.get("payment_method_key")).isEqualTo("gateway");
        assertThat(txn.get("source")).isEqualTo("gateway");
        assertThat(txn.get("external_reference")).isEqualTo(tracking(intent));
        assertThat(txn.get("recorded_by")).isEqualTo(staff);
        assertThat((String) txn.get("receipt_no")).matches("RC-HQ-\\d{6}");
        assertThat(txn.get("amount_minor")).isEqualTo(50_000L);
        assertThat(txn.get("balance_after_minor")).isEqualTo(50_000L);
        assertThat(balance(gatewayAccount)).isEqualTo(balance(cashAccount));

        // The same journal as the cashier's deposit, apart from the account the money came in on.
        List<String> cashLines = lines(deposits(cashAccount).getFirst().get("journal_entry_id"));
        List<String> gatewayLines = lines(txn.get("journal_entry_id"));
        assertThat(gatewayLines).containsExactly("gateway_clearing:50000:0", "member_savings:0:50000");
        assertThat(cashLines).containsExactly("cash_on_hand:50000:0", "member_savings:0:50000");

        // The audit rows: the deposit's names its origin, the intent's the merchant account, the order and the code.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data->'after'->>'origin' FROM audit_log WHERE entity_id = ? AND action = 'lending.savings.deposit_recorded'")
                        .param(UUID.fromString(gatewayAccount))
                        .query(String.class)
                        .single())
                .isEqualTo("gateway");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data->'after'->>'merchant_id' || '/' || (data->'after'->>'order_tracking_id') || '/' || (data->'after'->>'confirmation_code') FROM audit_log WHERE entity_id = ? AND action = 'core.payments.intent.booked'")
                        .param(UUID.fromString(intent.get("id").asString()))
                        .query(String.class)
                        .single())
                .isEqualTo(merchant + "/" + tracking(intent) + "/CONF-219-0001");
        // The receipt text is queued in the same transaction (the tenant has SMS on): the cashier's and the gateway's.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM notification_outbox WHERE channel = 'sms' AND tenant_id = ? AND template_key = 'savings.deposit'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
        // The confirmation code is on the intent view for the screen.
        ResponseEntity<JsonNode> view = call(
                HttpMethod.GET, "/api/v1/payments/intents/" + intent.get("id").asString(), null, null);
        assertThat(view.getBody().get("confirmation_code").asString()).isEqualTo("CONF-219-0001");
        booksAgree();
    }

    @Test
    void aPartialAndAnExcessPaymentBookWhatWasPaid() {
        String account = account();
        JsonNode partial = intentFor(account, 50_000);
        pay(partial, 30_000, "CONF-219-0002");
        tick(Duration.ofMinutes(3));
        assertThat(row(partial).get("status")).isEqualTo("booked");
        assertThat(row(partial).get("paid_amount_minor")).isEqualTo(30_000L);
        assertThat(balance(account)).isEqualTo(30_000L);
        JsonNode excess = intentFor(account, 50_000);
        pay(excess, 80_000, "CONF-219-0003");
        tick(Duration.ofMinutes(3));
        assertThat(row(excess).get("status")).isEqualTo("booked");
        assertThat(balance(account)).isEqualTo(110_000L);
        booksAgree();
    }

    @Test
    void anAccountThatCannotTakeADepositIsRefusedBeforeAnyOrderIsMade() {
        String frozen = account();
        setStatus(frozen, "frozen");
        String closed = account();
        setStatus(closed, "closed");
        String minimum = account(Map.of("min_opening_balance_minor", 20_000));
        long creates = fake.creates();
        for (String account : List.of(frozen, closed)) {
            ResponseEntity<JsonNode> r = startIntent(account, 50_000);
            assertThat(r.getStatusCode().value()).isEqualTo(422);
            assertThat(r.getBody().get("code").asString()).isEqualTo("subject_not_payable");
        }
        assertThat(startIntent(UUID.randomUUID().toString(), 1000)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("subject_not_payable");
        // A first deposit below the product's minimum opening balance is refused with its own word.
        assertThat(startIntent(minimum, 10_000).getBody().get("code").asString())
                .isEqualTo("below_minimum_opening");
        assertThat(startIntent(minimum, 20_000).getStatusCode().value()).isEqualTo(201);
        assertThat(fake.creates()).isEqualTo(creates + 1);
    }

    // ---- duplicates -----------------------------------------------------------------------------

    @Test
    void theSameNotificationThreeTimesAndAPollBookOneDeposit() {
        String account = account();
        JsonNode intent = intentFor(account, 50_000);
        pay(intent, 50_000, "CONF-219-0004");
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
        assertThat(deposits(account)).hasSize(1);
        assertThat(balance(account)).isEqualTo(50_000L);
    }

    @Test
    void thePortReturnsTheFirstTransactionForARepeatedReferenceAndTheIndexBacksIt() {
        String account = account();
        UUID first = tenantJobs.callForTenant(
                t.tenantId(),
                () -> port.deposit(
                        UUID.fromString(account), 10_000, TODAY, "gateway", staff, "ORDER-219-0005", "gateway", false));
        UUID again = tenantJobs.callForTenant(
                t.tenantId(),
                () -> port.deposit(
                        UUID.fromString(account), 10_000, TODAY, "gateway", staff, "ORDER-219-0005", "gateway", false));
        assertThat(again).isEqualTo(first);
        assertThat(deposits(account)).hasSize(1);
        assertThat(balance(account)).isEqualTo(10_000L);
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
                                INSERT INTO lending_savings_transactions (id, tenant_id, branch_id, account_id, seq, txn_type, amount_minor,
                                    is_credit, currency, balance_after_minor, value_date, payment_method_key, external_reference,
                                    receipt_no, journal_entry_id, source)
                                SELECT gen_random_uuid(), tenant_id, branch_id, account_id, seq + 1, txn_type, amount_minor, is_credit,
                                    currency, balance_after_minor, value_date, payment_method_key, external_reference, 'RC-HQ-999999',
                                    journal_entry_id, source
                                  FROM lending_savings_transactions WHERE id = ?
                                """).param(first).update())
                .hasMessageContaining("lending_savings_transactions_gateway_reference");
    }

    @Test
    void theSameReferenceForAnotherAccountOrAmountThroughThePortIsNeverAReplay() {
        String first = account();
        String second = account();
        tenantJobs.callForTenant(
                t.tenantId(),
                () -> port.deposit(
                        UUID.fromString(first), 10_000, TODAY, "gateway", staff, "ORDER-219-SAME", "gateway", false));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tenantJobs.callForTenant(
                        t.tenantId(),
                        () -> port.deposit(
                                UUID.fromString(second),
                                10_000,
                                TODAY,
                                "gateway",
                                staff,
                                "ORDER-219-SAME",
                                "gateway",
                                false)))
                .hasMessageContaining("already booked a different payment");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tenantJobs.callForTenant(
                        t.tenantId(),
                        () -> port.deposit(
                                UUID.fromString(first),
                                20_000,
                                TODAY,
                                "gateway",
                                staff,
                                "ORDER-219-SAME",
                                "gateway",
                                false)))
                .hasMessageContaining("already booked a different payment");
        assertThat(deposits(second)).isEmpty();
        assertThat(balance(first)).isEqualTo(10_000L);
    }

    @Test
    void twoGenuinePaymentsOfTheSameAmountOnOneAccountWithTheSameConfirmationCodeBothBook() {
        String account = account();
        JsonNode a = intentFor(account, 10_000);
        JsonNode b = intentFor(account, 10_000);
        pay(a, 10_000, "CONF-219-SAME");
        pay(b, 10_000, "CONF-219-SAME");
        tick(Duration.ofMinutes(3));
        assertThat(row(a).get("status")).isEqualTo("booked");
        assertThat(row(b).get("status")).isEqualTo("booked");
        assertThat(row(a).get("booked_subject_txn_id")).isNotEqualTo(row(b).get("booked_subject_txn_id"));
        assertThat(deposits(account)).hasSize(2);
        assertThat(deposits(account).stream()
                        .map(r -> r.get("external_reference"))
                        .toList())
                .containsExactlyInAnyOrder(tracking(a), tracking(b));
        assertThat(balance(account)).isEqualTo(20_000L);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE action = 'core.payments.intent.booked' AND data->'after'->>'confirmation_code' = 'CONF-219-SAME' AND tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
        booksAgree();
    }

    // ---- late, refused and dated -----------------------------------------------------------------

    @Test
    void aPaymentCompletedAfterTheOrderExpiredStillBooksAndIsFlaggedLate() {
        String account = account();
        JsonNode intent = intentFor(account, 50_000);
        tick(Duration.ofMinutes(31));
        assertThat(row(intent).get("status")).isEqualTo("expired");
        assertThat(deposits(account)).isEmpty();
        pay(intent, 50_000, "CONF-219-0006");
        tick(Duration.ofMinutes(15));
        assertThat(row(intent).get("status")).isEqualTo("booked");
        assertThat(deposits(account)).hasSize(1);
        ResponseEntity<JsonNode> view = call(
                HttpMethod.GET, "/api/v1/payments/intents/" + intent.get("id").asString(), null, null);
        assertThat(view.getBody().get("flags").toString()).contains("late");
        tick(Duration.ofHours(2));
        assertThat(deposits(account)).hasSize(1);
    }

    @Test
    void anAccountFrozenBeforeTheMoneyArrivedTurnsItIntoAnUnallocatedReceiptNotADeposit() {
        String account = account();
        JsonNode intent = intentFor(account, 50_000);
        setStatus(account, "frozen");
        pay(intent, 50_000, "CONF-219-0007");
        tick(Duration.ofMinutes(3));
        Map<String, Object> intentRow = row(intent);
        assertThat(intentRow.get("status")).isEqualTo("booked_unallocated");
        assertThat(intentRow.get("refusal_code")).isEqualTo("account_frozen");
        assertThat(intentRow.get("booked_subject_txn_id")).isNull();
        assertThat(deposits(account)).isEmpty();
        assertThat(balance(account)).isZero();
        Map<String, Object> receipt = receiptOf(intent);
        assertThat(receipt.get("amount_minor")).isEqualTo(50_000L);
        assertThat(receipt.get("status")).isEqualTo("open");
        assertThat(receipt.get("confirmation_code")).isEqualTo("CONF-219-0007");
        assertThat(receipt.get("subject_id")).isEqualTo(UUID.fromString(account));
        assertThat(lines(receipt.get("journal_entry_id")))
                .containsExactly("gateway_clearing:50000:0", "unallocated_receipts:0:50000");
        tick(Duration.ofHours(2));
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM unallocated_receipts WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        booksAgree();
    }

    @Test
    void anAccountClosedBeforeTheMoneyArrivedTurnsItIntoAnUnallocatedReceipt() {
        String account = account();
        JsonNode intent = intentFor(account, 50_000);
        setStatus(account, "closed");
        pay(intent, 50_000, "CONF-219-0008");
        tick(Duration.ofMinutes(3));
        assertThat(row(intent).get("status")).isEqualTo("booked_unallocated");
        assertThat(row(intent).get("refusal_code")).isEqualTo("account_closed");
        assertThat(deposits(account)).isEmpty();
    }

    @Test
    void aDormantAccountTakesTheDeposit() {
        String account = account();
        JsonNode intent = intentFor(account, 50_000);
        setStatus(account, "dormant");
        pay(intent, 50_000, "CONF-219-0009");
        tick(Duration.ofMinutes(3));
        assertThat(row(intent).get("status")).isEqualTo("booked");
        assertThat(balance(account)).isEqualTo(50_000L);
    }

    @Test
    void aFirstDepositBelowTheMinimumOpeningBalanceIsHeldUnallocated() {
        String account = account(Map.of("min_opening_balance_minor", 20_000));
        JsonNode intent = intentFor(account, 20_000);
        pay(intent, 5_000, "CONF-219-MIN");
        tick(Duration.ofMinutes(3));
        assertThat(row(intent).get("status")).isEqualTo("booked_unallocated");
        assertThat(row(intent).get("refusal_code")).isEqualTo("below_minimum_opening");
        assertThat(balance(account)).isZero();
    }

    @Test
    void anUnallocatedReceiptDebitsTheAccountTheGatewayMethodIsMappedToAtTheBranch() {
        String till = assetAccount("1099", "Test gateway till");
        TestDatabase.owner()
                .sql(
                        "UPDATE payment_methods SET gl_account_id = ?::uuid WHERE tenant_id = ? AND method_key = 'gateway'")
                .params(till, t.tenantId())
                .update();
        String account = account();
        JsonNode intent = intentFor(account, 50_000);
        setStatus(account, "frozen");
        pay(intent, 50_000, "CONF-219-MAP");
        tick(Duration.ofMinutes(3));
        assertThat(row(intent).get("status")).isEqualTo("booked_unallocated");
        Object entry = receiptOf(intent).get("journal_entry_id");
        assertThat(codedLines(entry.toString())).containsExactly("1099:50000:0", "2040:0:50000");
    }

    @Test
    void whenTheGatewayMethodIsUnmappedTheReceiptFallsBackToGatewayClearingAndCommits() {
        String account = account();
        JsonNode intent = intentFor(account, 50_000);
        TestDatabase.owner()
                .sql("UPDATE payment_methods SET is_active = false WHERE tenant_id = ? AND method_key = 'gateway'")
                .param(t.tenantId())
                .update();
        pay(intent, 50_000, "CONF-219-UNM");
        tick(Duration.ofMinutes(3));
        assertThat(row(intent).get("status")).isEqualTo("booked_unallocated");
        assertThat(row(intent).get("refusal_code")).isEqualTo("payment_method_unmapped");
        assertThat(lines(receiptOf(intent).get("journal_entry_id")))
                .containsExactly("gateway_clearing:50000:0", "unallocated_receipts:0:50000");
        assertThat(deposits(account)).isEmpty();
    }

    @Test
    void aGatewayDepositCannotBeReversedByStaffUntilTheProviderReviewExists() {
        String account = account();
        JsonNode intent = intentFor(account, 50_000);
        pay(intent, 50_000, "CONF-219-REV");
        tick(Duration.ofMinutes(3));
        Object txn = deposits(account).getFirst().get("id");
        HttpHeaders h = as(staff, adminPerms, "*", null);
        h.add("Idempotency-Key", "rev-" + UUID.randomUUID());
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/transactions/" + txn + "/reverse",
                h,
                Map.of("reason", "Test reversal of a gateway payment"));
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(422);
        assertThat(r.getBody().get("code").asString()).isEqualTo("gateway_reversal_pending");
        assertThat(balance(account)).isEqualTo(50_000L);
    }

    @Test
    void aStaffMemberWithoutCollectInTheAccountsBranchIsRefusedBeforeTheAmountIsChecked() {
        String account = account();
        HttpHeaders h = as(staff, "lending.savings.read", "*", null);
        h.add("Idempotency-Key", "k-" + UUID.randomUUID());
        ResponseEntity<JsonNode> r = http.exchange(
                "/api/v1/payments/intents",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "purpose",
                                PURPOSE,
                                "subject_id",
                                account,
                                "amount_minor",
                                99_999_999,
                                "payer_phone",
                                "0700000219"),
                        h),
                JsonNode.class);
        assertThat(r.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void aPaymentDatedIntoADayWhoseBalancesAreWrittenIsBookedDatedToday() {
        String account = account();
        JsonNode intent = intentFor(account, 50_000);
        pay(intent, 50_000, "CONF-219-EOD");
        failInserts(true);
        try {
            tick(Duration.ofMinutes(3));
        } finally {
            failInserts(false);
        }
        assertThat(row(intent).get("status")).isEqualTo("paid");
        // The nightly end of day wrote the balances through the payment's day; the payment is booked a day later.
        TestDatabase.owner()
                .sql("UPDATE lending_savings_accounts SET balances_through = ? WHERE id = ?")
                .params(java.sql.Date.valueOf(TODAY), UUID.fromString(account))
                .update();
        clock.set(Instant.parse("2026-10-17T06:00:00Z"));
        poller.tick();
        assertThat(row(intent).get("status")).isEqualTo("booked");
        assertThat(deposits(account).getFirst().get("value_date").toString()).isEqualTo("2026-10-17");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT data->'after'->>'note' FROM audit_log WHERE entity_id = ? AND action = 'core.payments.intent.booked'")
                        .param(UUID.fromString(intent.get("id").asString()))
                        .query(String.class)
                        .single())
                .isEqualTo("dated_today");
    }

    @Test
    void aPaymentWhoseDayFellInAClosedPeriodIsBookedDatedTodayEndToEnd() {
        String account = account();
        clock.set(Instant.parse("2026-10-31T06:00:00Z"));
        JsonNode intent = intentFor(account, 50_000);
        pay(intent, 50_000, "CONF-219-PC");
        failInserts(true);
        try {
            tick(Duration.ofMinutes(3));
        } finally {
            failInserts(false);
        }
        assertThat(row(intent).get("status")).isEqualTo("paid");
        TestDatabase.owner()
                .sql(
                        "INSERT INTO gl_periods (id, tenant_id, year, month, status, closed_at) VALUES (gen_random_uuid(), ?, 2026, 10, 'closed', now()) ON CONFLICT (tenant_id, year, month) DO UPDATE SET status = 'closed', closed_at = now()")
                .param(t.tenantId())
                .update();
        clock.set(Instant.parse("2026-11-01T06:00:00Z"));
        poller.tick();
        assertThat(row(intent).get("status")).isEqualTo("booked");
        assertThat(deposits(account).getFirst().get("value_date").toString()).isEqualTo("2026-11-01");
    }

    @Test
    void cashAndGatewayDepositsInterleavedOnOneAccountKeepTheRunningBalanceExact() {
        String account = account();
        cashDeposit(account, 10_000);
        JsonNode a = intentFor(account, 20_000);
        pay(a, 20_000, "CONF-219-IL1");
        cashDeposit(account, 5_000);
        tick(Duration.ofMinutes(3));
        JsonNode b = intentFor(account, 7_000);
        cashDeposit(account, 1_000);
        pay(b, 7_000, "CONF-219-IL2");
        tick(Duration.ofMinutes(3));
        assertThat(row(a).get("status")).isEqualTo("booked");
        assertThat(row(b).get("status")).isEqualTo("booked");
        List<Map<String, Object>> rows = deposits(account);
        assertThat(rows.stream().map(r -> r.get("seq")).toList()).containsExactly(1, 2, 3, 4, 5);
        assertThat(rows.stream().map(r -> r.get("balance_after_minor")).toList())
                .containsExactly(10_000L, 15_000L, 35_000L, 36_000L, 43_000L);
        assertThat(balance(account)).isEqualTo(43_000L);
        booksAgree();
    }

    @Test
    void thePortRefusesAGatewayDepositToAFrozenAccountItself() {
        String account = account();
        setStatus(account, "frozen");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tenantJobs.callForTenant(
                        t.tenantId(),
                        () -> port.deposit(
                                UUID.fromString(account),
                                10_000,
                                TODAY,
                                "gateway",
                                staff,
                                "ORDER-219-FRZ",
                                "gateway",
                                false)))
                .hasMessageContaining("frozen");
        assertThat(deposits(account)).isEmpty();
    }

    /** A technical failure on every savings transaction insert, to keep a confirmed payment waiting for its booking. */
    void failInserts(boolean on) {
        if (on) {
            TestDatabase.owner()
                    .sql(
                            "CREATE OR REPLACE FUNCTION it219_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'it219 technical failure'; END $$")
                    .update();
            TestDatabase.owner()
                    .sql(
                            "CREATE TRIGGER it219_fail BEFORE INSERT ON lending_savings_transactions FOR EACH ROW EXECUTE FUNCTION it219_fail()")
                    .update();
        } else {
            TestDatabase.owner()
                    .sql("DROP TRIGGER IF EXISTS it219_fail ON lending_savings_transactions")
                    .update();
        }
    }
}
