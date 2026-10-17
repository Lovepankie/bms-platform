package com.rincoltech.bms.lending.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import com.rincoltech.bms.lending.members.MemberLookup;
import com.rincoltech.bms.testsupport.MovableClock;
import com.rincoltech.bms.testsupport.MovableClockConfig;
import com.rincoltech.bms.testsupport.SmsTenants;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
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
 * Savings on real PostgreSQL (#151; FR-SAV-01 to FR-SAV-07): products, accounts, deposits,
 * withdrawals with fees, limits and the {@code savings_withdrawal} checker, reversals through
 * {@code savings_reversal}, freeze, dormancy and reactivation, closure with interest to date, the
 * statement, the reports, the receipt SMS in the outbox, idempotency and branch scope. Every journal
 * is checked against chapter 6 section 6.6.3, and after each test the member savings control
 * account equals each account's balance. All figures fabricated.
 */
@Import(MovableClockConfig.class)
@ResourceLock(MovableClockConfig.LOCK)
class SavingsIT extends LoanFixtures {

    static final String PRODUCTS = "/api/v1/lending/savings-products";
    static final String ACCOUNTS = "/api/v1/lending/savings-accounts";

    @Autowired
    BusinessClock clock;

    @Autowired
    MovableClock movable;

    @Autowired
    SavingsServicing servicing;

    @Autowired
    TenantJobs tenantJobs;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcClient jdbc;

    /** A spy that calls the real bean until a test arms it; the money movement must not notice when it breaks. */
    @MockitoSpyBean
    MemberLookup memberLookup;

    final UUID cashier = UUID.randomUUID();
    final UUID manager = UUID.randomUUID();
    final UUID admin = UUID.randomUUID();
    String cashierPerms;
    LocalDate today;

    @BeforeEach
    void users() {
        cashierPerms = permissionsOf("cashier");
        // One minute before Kampala's midnight on 2026-10-15; no test here moves it, so the date cannot change under
        // one.
        movable.set(MovableClockConfig.BEFORE_MIDNIGHT);
        today = clock.today(BusinessClock.DEFAULT_ZONE);
    }

    // ---- Fixtures -------------------------------------------------------------------------

    /** A business administrator's standing on top of the cashier's: the only kind that may say {@code notify_member: false}. */
    HttpHeaders asCashierWhoMaySayNoText() {
        return as(cashier, cashierPerms + ",core.settings.manage", "*", null);
    }

    HttpHeaders asCashier() {
        return as(cashier, cashierPerms, "*", null);
    }

    HttpHeaders withKey(HttpHeaders h, String key) {
        h.add("Idempotency-Key", key);
        return h;
    }

    static String key() {
        return "test-" + UUID.randomUUID();
    }

    Map<String, Object> terms(String calc, int rateBp, Map<String, Object> overrides) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("name", "Test savings " + calc);
        terms.put("interest_rate_bp", rateBp);
        terms.put("interest_calc", calc);
        terms.put("interest_posting", "monthly");
        terms.putAll(overrides);
        return terms;
    }

    ResponseEntity<JsonNode> createProduct(String code, Map<String, Object> terms) {
        return send(HttpMethod.POST, PRODUCTS, as(admin, adminPerms, "*", null), Map.of("code", code, "terms", terms));
    }

    String product(String code, Map<String, Object> overrides) {
        ResponseEntity<JsonNode> r = createProduct(code, terms("none", 0, overrides));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("id").asString();
    }

    JsonNode open(String member, String product) {
        ResponseEntity<JsonNode> r =
                asOfficer(HttpMethod.POST, ACCOUNTS, null, Map.of("member_id", member, "product_id", product));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    String account(String product) {
        return open(member("Test Saver 01", "+256700000201", t.headOffice(), true), product)
                .get("id")
                .asString();
    }

    ResponseEntity<JsonNode> deposit(String account, long amount, String key) {
        return send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/deposits",
                withKey(asCashier(), key),
                Map.of("amount_minor", amount, "payment_method_key", "cash"));
    }

    ResponseEntity<JsonNode> withdraw(String account, long amount, String key) {
        return send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/withdrawals",
                withKey(asCashier(), key),
                Map.of("amount_minor", amount, "payment_method_key", "mtn_momo"));
    }

    ResponseEntity<JsonNode> decide(String approvalId, UUID user, String perms) {
        return send(
                HttpMethod.POST,
                "/api/v1/approvals/" + approvalId + "/approve",
                as(user, perms, "*", null),
                Map.of("note", "Checked"));
    }

    JsonNode get(String account) {
        return send(HttpMethod.GET, ACCOUNTS + "/" + account, asCashier(), null).getBody();
    }

    void threshold(long amount) {
        TestDatabase.owner()
                .sql("INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?," + " CAST(? AS jsonb))")
                .params(
                        UUID.randomUUID(),
                        t.tenantId(),
                        "{\"approval_thresholds_minor\": {\"savings_withdrawal\": " + amount + "}}")
                .update();
    }

    /** The journal lines of one transaction: {@code system_key debit credit}, in line order. */
    List<String> journal(String txnId) {
        return TestDatabase.owner().sql("""
                        SELECT a.system_key || ' ' || l.debit || ' ' || l.credit FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.id = (SELECT journal_entry_id FROM lending_savings_transactions WHERE id = ?::uuid)
                         ORDER BY l.line_no
                        """).param(txnId).query(String.class).list();
    }

    <T> T inTenant(Supplier<T> work) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tenantJobs.callAsTenant(t.slug(), "lending", () -> tx.execute(s -> work.get()));
    }

    /** The trial balance balances and member_savings equals every account's balance, account by account. */
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
        // The running balance of the last movement equals the account balance.
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT count(*) FROM lending_savings_accounts s WHERE s.tenant_id = ? AND s.txn_count > 0
                                   AND s.balance_minor <> (SELECT balance_after_minor FROM lending_savings_transactions x
                                                            WHERE x.account_id = s.id ORDER BY seq DESC LIMIT 1)
                                """)
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // ---- Products (FR-SAV-01) -------------------------------------------------------------

    @Test
    void frSav01_productsAreValidatedAndTheirInterestTermsLockOnceUsed() {
        ResponseEntity<JsonNode> bad = createProduct("BAD", terms("daily_balance", 0, Map.of()));
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> badCode = createProduct("bad code", terms("none", 0, Map.of()));
        assertThat(badCode.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> created = createProduct(
                "SAVE", terms("daily_balance", 500, Map.of("min_balance_minor", 5_000, "dormancy_days", 90)));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode p = created.getBody();
        assertThat(p.get("currency").asString()).isEqualTo("UGX");
        assertThat(p.get("status").asString()).isEqualTo("active");
        assertThat(createProduct("SAVE", terms("none", 0, Map.of())).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(send(
                                HttpMethod.POST,
                                PRODUCTS,
                                asCashier(),
                                Map.of("code", "X1", "terms", terms("none", 0, Map.of())))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        String id = p.get("id").asString();
        // Unused: the rate may change.
        ResponseEntity<JsonNode> changed = send(
                HttpMethod.PUT,
                PRODUCTS + "/" + id,
                as(admin, adminPerms, "*", "1"),
                Map.of("terms", terms("daily_balance", 600, Map.of()), "status", "active"));
        assertThat(changed.getStatusCode()).as("%s", changed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(changed.getBody().get("version").asInt()).isEqualTo(2);

        account(id);
        ResponseEntity<JsonNode> locked = send(
                HttpMethod.PUT,
                PRODUCTS + "/" + id,
                as(admin, adminPerms, "*", "2"),
                Map.of("terms", terms("daily_balance", 700, Map.of()), "status", "active"));
        assertThat(locked.getBody().get("code").asString()).isEqualTo("product_in_use");
        // Rules that do not touch interest still change, and archiving stops new accounts.
        ResponseEntity<JsonNode> archived = send(
                HttpMethod.PUT,
                PRODUCTS + "/" + id,
                as(admin, adminPerms, "*", "2"),
                Map.of(
                        "terms",
                        terms("daily_balance", 600, Map.of("withdrawal_fee_minor", 500)),
                        "status",
                        "archived"));
        assertThat(archived.getStatusCode()).as("%s", archived.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(archived.getBody().get("withdrawal_fee_minor").asLong()).isEqualTo(500);
        ResponseEntity<JsonNode> refused = asOfficer(
                HttpMethod.POST,
                ACCOUNTS,
                null,
                Map.of("member_id", member("Test Saver 02", "+256700000202", t.headOffice(), true), "product_id", id));
        assertThat(refused.getBody().get("code").asString()).isEqualTo("product_not_active");
        // The database refuses an interest change on a product in use even without the service.
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE lending_savings_products SET interest_rate_bp = 900 WHERE id = ?::uuid")
                        .param(id)
                        .update())
                .hasStackTraceContaining("do not change");
    }

    // ---- Accounts and deposits (FR-SAV-02, FR-SAV-03, FR-SAV-04) ---------------------------

    @Test
    void frSav02_frSav03_aMemberHoldsSeveralAccountsAndDepositsPostAndReplay() {
        String product = product("PLAIN", Map.of("min_opening_balance_minor", 20_000));
        String member = member("Test Saver 03", "+256700000203", t.headOffice(), true);
        JsonNode first = open(member, product);
        JsonNode second = open(member, product);
        assertThat(first.get("account_no").asString()).matches("SV\\d{6}");
        assertThat(second.get("account_no").asString())
                .isNotEqualTo(first.get("account_no").asString());
        assertThat(first.get("branch_id").asString()).isEqualTo(t.headOffice().toString());
        assertThat(first.get("status").asString()).isEqualTo("active");
        JsonNode list = send(HttpMethod.GET, ACCOUNTS + "?member_id=" + member, asCashier(), null)
                .getBody();
        assertThat(list.get("items").size()).isEqualTo(2);

        String account = first.get("id").asString();
        // The first deposit must reach the product's minimum opening balance.
        assertThat(deposit(account, 10_000, key()).getBody().get("code").asString())
                .isEqualTo("below_minimum_opening");
        assertThat(deposit(account, 10_000, null).getBody().get("code").asString())
                .isEqualTo("idempotency_key_missing");

        // SMS is off for every tenant until the platform operator switches it on (ADR-036).
        smsOn();

        String k = key();
        ResponseEntity<JsonNode> d = deposit(account, 50_000, k);
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode txn = d.getBody().get("transaction");
        assertThat(txn.get("receipt_no").asString()).startsWith("RC-");
        assertThat(txn.get("balance_after_minor").asLong()).isEqualTo(50_000);
        assertThat(d.getBody().get("account").get("balance_minor").asLong()).isEqualTo(50_000);
        assertThat(journal(txn.get("id").asString())).containsExactly("cash_on_hand 50000 0", "member_savings 0 50000");

        // The same key replays the receipt and moves nothing; another request with it is refused.
        ResponseEntity<JsonNode> again = deposit(account, 50_000, k);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.getBody().get("transaction").get("id").asString())
                .isEqualTo(txn.get("id").asString());
        assertThat(deposit(account, 60_000, k).getBody().get("code").asString()).isEqualTo("idempotency_key_reused");
        assertThat(get(account).get("balance_minor").asLong()).isEqualTo(50_000);

        // A value date in the future, or before the account opened, is refused.
        ResponseEntity<JsonNode> future = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/deposits",
                withKey(asCashier(), key()),
                Map.of(
                        "amount_minor",
                        1_000,
                        "payment_method_key",
                        "cash",
                        "value_date",
                        today.plusDays(1).toString()));
        assertThat(future.getBody().get("code").asString()).isEqualTo("value_date_in_future");

        // The receipt SMS is queued once, in the deposit's transaction, for the member's phone.
        List<Map<String, Object>> sms = TestDatabase.owner()
                .sql("SELECT recipient, template_key, params::text AS params, status, expires_at IS NOT NULL AS expires"
                        + " FROM notification_outbox WHERE channel = 'sms' AND idempotency_key LIKE ?")
                .param("sms:savings.%:" + t.tenantId() + ":%")
                .query()
                .listOfRows();
        assertThat(sms).hasSize(1);
        assertThat(sms.getFirst().get("recipient")).isEqualTo("+256700000203");
        assertThat(sms.getFirst().get("template_key")).isEqualTo("savings.deposit");
        assertThat((String) sms.getFirst().get("params"))
                .contains("50,000", first.get("account_no").asString())
                // FR-NTF-17: the default receipt carries no balance.
                .doesNotContain("balance");
        assertThat(sms.getFirst().get("status")).isEqualTo("pending");
        assertThat(sms.getFirst().get("expires")).isEqualTo(true);
        assertThat(TestDatabase.owner()
                        .sql("SELECT category || ' ' || subject_type || ' ' || (tenant_id = ?) || ' '"
                                + " || (expires_at <= created_at + interval '1 hour') FROM notification_outbox"
                                + " WHERE channel = 'sms' AND idempotency_key LIKE ?")
                        .params(t.tenantId(), "sms:savings.%:" + t.tenantId() + ":%")
                        .query(String.class)
                        .single())
                .isEqualTo("transactional member true true");
        booksAgree();
    }

    /** ADR-036 decision 13 and 14: no row without the operator's switch; the balance only when the tenant asks. */
    @Test
    void frNtf17_aReceiptIsQueuedOnlyWhenTheOperatorSwitchedSmsOnAndCarriesTheBalanceOnlyIfAsked() {
        String product = product("SMSOFF", Map.of());
        String member = member("Test Saver 04", "+256700000204", t.headOffice(), true);
        String account = open(member, product).get("id").asString();
        String like = "sms:savings.%:" + t.tenantId() + ":%";
        String count = "SELECT count(*) FROM notification_outbox WHERE channel = 'sms' AND idempotency_key LIKE ?";

        assertThat(deposit(account, 30_000, key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(TestDatabase.owner().sql(count).param(like).query(Long.class).single())
                .isZero();

        smsOn();
        assertThat(deposit(account, 10_000, key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(TestDatabase.owner().sql(count).param(like).query(Long.class).single())
                .isEqualTo(1);

        // The tenant admin asks for the balance (the settings write is audited as any other).
        ResponseEntity<JsonNode> settings =
                send(HttpMethod.GET, "/api/v1/settings/sms", as(admin, adminPerms, "*", null), null);
        assertThat(settings.getBody().get("include_balance").asBoolean()).isFalse();
        ResponseEntity<JsonNode> changed = send(
                HttpMethod.PUT,
                "/api/v1/settings/sms",
                as(admin, adminPerms, "*", settings.getHeaders().getETag()),
                Map.of("include_balance", true));
        assertThat(changed.getStatusCode()).as("%s", changed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(deposit(account, 5_000, key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<String> params = TestDatabase.owner()
                .sql("SELECT params::text FROM notification_outbox WHERE channel = 'sms' AND idempotency_key LIKE ?"
                        + " ORDER BY 1")
                .param(like)
                .query(String.class)
                .list();
        assertThat(params).hasSize(2);
        // (The clock is fixed, so the rows share a time; they are told apart by what they hold.)
        assertThat(params.stream().filter(x -> x.contains("balance")))
                .singleElement()
                .satisfies(x -> assertThat(x).contains("45,000"));
        assertThat(params.stream().filter(x -> !x.contains("balance"))).hasSize(1);
    }

    /** The operator switches SMS on for this test's tenant, with a cap well above what a test sends. */
    private void smsOn() {
        TestDatabase.owner()
                .sql("INSERT INTO platform_sms_controls (tenant_id, enabled, daily_cap) VALUES (?, true, 1000)"
                        + " ON CONFLICT (tenant_id) DO UPDATE SET enabled = true")
                .param(t.tenantId())
                .update();
        // And the tenant's own switch, which its administrator sets: nothing is queued before both are on.
        SmsTenants.enable(t.tenantId());
    }

    // ---- Withdrawals (FR-SAV-03) ------------------------------------------------------------

    @Test
    void frSav03_withdrawalsKeepTheMinimumBalanceAndLimitsAndChargeTheFee() {
        threshold(10_000_000);
        String product = product(
                "FEES",
                Map.of(
                        "min_balance_minor", 10_000,
                        "withdrawal_fee_minor", 1_000,
                        "max_withdrawal_minor", 60_000,
                        "max_withdrawals_per_month", 2));
        String account = account(product);
        deposit(account, 100_000, key());
        assertThat(get(account).get("available_minor").asLong()).isEqualTo(89_000);

        assertThat(withdraw(account, 70_000, key()).getBody().get("code").asString())
                .isEqualTo("withdrawal_limit_exceeded");
        ResponseEntity<JsonNode> w = withdraw(account, 50_000, key());
        assertThat(w.getStatusCode()).as("%s", w.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(w.getBody().get("executed").asBoolean()).isTrue();
        JsonNode txn = w.getBody().get("transaction");
        assertThat(txn.get("txn_type").asString()).isEqualTo("withdrawal");
        assertThat(txn.get("receipt_no").asString()).startsWith("VC-");
        assertThat(journal(txn.get("id").asString()))
                .containsExactly("member_savings 50000 0", "mobile_money_mtn 0 50000");
        assertThat(w.getBody().get("account").get("balance_minor").asLong()).isEqualTo(49_000);
        String fee = TestDatabase.owner()
                .sql(
                        "SELECT id::text FROM lending_savings_transactions WHERE related_txn_id = ?::uuid AND txn_type = 'fee'")
                .param(txn.get("id").asString())
                .query(String.class)
                .single();
        assertThat(journal(fee)).containsExactly("member_savings 1000 0", "savings_fee_income 0 1000");

        // 49,000 less the 10,000 minimum and the 1,000 fee leaves 38,000.
        assertThat(withdraw(account, 38_001, key()).getBody().get("code").asString())
                .isEqualTo("insufficient_balance");
        assertThat(withdraw(account, 38_000, key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(get(account).get("balance_minor").asLong()).isEqualTo(10_000);
        deposit(account, 50_000, key());
        // The product allows two withdrawals a calendar month.
        assertThat(withdraw(account, 1_000, key()).getBody().get("code").asString())
                .isEqualTo("withdrawal_count_exceeded");
        booksAgree();
    }

    /** FR-APR-04, chapter 8 section 8.4: at or above the threshold a checker approves; the maker cannot. */
    @Test
    void frSav03_aboveTheThresholdAWithdrawalWaitsForAChecker() {
        threshold(100_000);
        String account = account(product("BIG", Map.of()));
        deposit(account, 500_000, key());

        ResponseEntity<JsonNode> r = withdraw(account, 200_000, key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(r.getBody().get("executed").asBoolean()).isFalse();
        String approval = r.getBody().get("approval_request_id").asString();
        assertThat(get(account).get("balance_minor").asLong()).isEqualTo(500_000);
        // A second request for the same account waits behind the first.
        assertThat(withdraw(account, 150_000, key()).getBody().get("code").asString())
                .isEqualTo("approval_already_pending");

        assertThat(decide(approval, cashier, cashierPerms).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> d = decide(approval, manager, managerPerms);
        assertThat(d.getStatusCode()).as("%s", d.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(get(account).get("balance_minor").asLong()).isEqualTo(300_000);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM lending_savings_transactions WHERE account_id = ?::uuid"
                                + " AND txn_type = 'withdrawal' AND approval_request_id = ?::uuid")
                        .params(account, approval)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // Below the threshold it runs at once.
        assertThat(withdraw(account, 50_000, key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        booksAgree();
    }

    // ---- The savings receipts through SmsEvents (issue #221) ----------------------------------------------

    List<Map<String, Object>> smsRows() {
        return TestDatabase.owner()
                .sql("SELECT template_key, status, last_error, params::text AS params FROM notification_outbox"
                        + " WHERE channel = 'sms' AND tenant_id = ? ORDER BY created_at, template_key")
                .param(t.tenantId())
                .query()
                .listOfRows();
    }

    ResponseEntity<JsonNode> depositWith(String account, long amount, LocalDate date, Boolean notify) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", amount);
        body.put("payment_method_key", "cash");
        if (date != null) {
            body.put("value_date", date.toString());
        }
        if (notify != null) {
            body.put("notify_member", notify);
        }
        return send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/deposits",
                withKey(Boolean.FALSE.equals(notify) ? asCashierWhoMaySayNoText() : asCashier(), key()),
                body);
    }

    @Test
    void frNtf02_notifyMemberFalseAndAnOldValueDateQueueNoReceiptAndTheOthersDo() {
        threshold(10_000_000);
        smsOn();
        String account = account(product("SMSQ", Map.of()));
        // An account opened ten days ago, so that earlier value dates are allowed.
        TestDatabase.owner()
                .sql("UPDATE lending_savings_accounts SET opened_on = ?::date - 10 WHERE id = ?::uuid")
                .params(today.toString(), account)
                .update();
        assertThat(depositWith(account, 20_000, null, false).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(smsRows()).as("a register import says notify_member false").isEmpty();
        // A deposit dated a week ago is a register being loaded, not news; two days ago is inside the cutoff and is
        // texted.
        assertThat(depositWith(account, 20_000, today.minusDays(7), null).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(smsRows()).isEmpty();
        assertThat(depositWith(account, 20_000, today.minusDays(2), true).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(smsRows()).hasSize(1);
        // A withdrawal that says no is silent, and one that does not say is not.
        ResponseEntity<JsonNode> quiet = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/withdrawals",
                withKey(asCashierWhoMaySayNoText(), key()),
                Map.of("amount_minor", 10_000, "payment_method_key", "mtn_momo", "notify_member", false));
        assertThat(quiet.getStatusCode()).as("%s", quiet.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(smsRows()).hasSize(1);
        assertThat(withdraw(account, 10_000, key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(smsRows().stream().map(r -> r.get("template_key")))
                .containsExactlyInAnyOrder("savings.deposit", "savings.withdrawal");
        // A closure's payout follows the same flag.
        ResponseEntity<JsonNode> closed = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/close",
                withKey(asCashierWhoMaySayNoText(), key()),
                Map.of("payment_method_key", "cash", "notify_member", false));
        assertThat(closed.getStatusCode()).as("%s", closed.getBody()).isIn(HttpStatus.CREATED, HttpStatus.ACCEPTED);
        assertThat(smsRows()).as("no third message").hasSize(2);
    }

    /**
     * Before this feature every staff movement texted the member. A cashier must not be able to take money out of an
     * account with {@code notify_member: false} and leave the member in the dark: only a business administrator may say it,
     * and the entry records that it was said.
     */
    @Test
    void frNtf20_aCashierCannotWithdrawOrDepositWithoutTellingTheMemberAndAnAdministratorsNoIsAudited() {
        threshold(10_000_000);
        smsOn();
        String account = account(product("SMSA", Map.of()));
        assertThat(deposit(account, 80_000, key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long balance = get(account).get("balance_minor").asLong();

        ResponseEntity<JsonNode> refusedWithdrawal = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/withdrawals",
                withKey(asCashier(), key()),
                Map.of("amount_minor", 30_000, "payment_method_key", "mtn_momo", "notify_member", false));
        assertThat(refusedWithdrawal.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refusedWithdrawal.getBody().get("code").asString()).isEqualTo("notify_member_forbidden");
        assertThat(refusedWithdrawal.getBody().get("detail").asString()).contains("Only a business administrator");
        ResponseEntity<JsonNode> refusedDeposit = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/deposits",
                withKey(asCashier(), key()),
                Map.of("amount_minor", 5_000, "payment_method_key", "cash", "notify_member", false));
        assertThat(refusedDeposit.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get(account).get("balance_minor").asLong())
                .as("no money moved")
                .isEqualTo(balance);

        // Omitted or true by the same cashier: told, as before.
        assertThat(withdraw(account, 10_000, key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(depositWith(account, 5_000, null, true).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(smsRows()).hasSize(3);
        // The administrator's no goes through, is not texted, and is on the audit entry with the cutoff decision.
        ResponseEntity<JsonNode> quiet = depositWith(account, 7_000, null, false);
        assertThat(quiet.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(smsRows()).hasSize(3);
        Map<String, Object> audit = TestDatabase.owner()
                .sql(
                        "SELECT data -> 'after' ->> 'notify_member' AS notify, data -> 'after' ->> 'sms_within_cutoff' AS cutoff"
                                + " FROM audit_log WHERE tenant_id = ? AND action = 'lending.savings.deposit_recorded'"
                                + " AND data -> 'after' ->> 'transaction_id' = ?")
                .params(
                        t.tenantId(),
                        quiet.getBody().get("transaction").get("id").asString())
                .query()
                .singleRow();
        assertThat(audit.get("notify")).isEqualTo("false");
        assertThat(audit.get("cutoff")).isEqualTo("true");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ?"
                                + " AND action IN ('lending.savings.deposit_recorded', 'lending.savings.withdrawal_recorded')"
                                + " AND data -> 'after' ->> 'notify_member' = 'true'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
    }

    @Test
    void frNtf02_aReversalWithdrawsTheUnclaimedReceiptOfTheEntryItReverses() {
        threshold(10_000_000);
        smsOn();
        String account = account(product("SMSCAN", Map.of()));
        deposit(account, 80_000, key());
        String withdrawal = withdraw(account, 30_000, key())
                .getBody()
                .get("transaction")
                .get("id")
                .asString();
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/transactions/" + withdrawal + "/reverse",
                withKey(asCashier(), key()),
                Map.of("reason", "Recorded on the wrong account"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(decide(r.getBody().get("approval_request_id").asString(), manager, managerPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> rows = smsRows();
        assertThat(rows).hasSize(2);
        Map<String, Object> gone = rows.stream()
                .filter(x -> "savings.withdrawal".equals(x.get("template_key")))
                .findFirst()
                .orElseThrow();
        assertThat(gone.get("status")).isEqualTo("failed");
        assertThat(gone.get("last_error")).isEqualTo("cancelled_reversed");
        assertThat(gone.get("params")).isEqualTo("{}");
        assertThat(rows.stream().filter(x -> "savings.deposit".equals(x.get("template_key"))))
                .singleElement()
                .satisfies(x -> assertThat(x.get("status")).isEqualTo("pending"));
    }

    @Test
    void frNtf01_aFailureOfTheMemberLookupOrANumberWeWouldNotTextNeverLosesTheDeposit() {
        threshold(10_000_000);
        smsOn();
        String member = member("Test Saver 09", "+256700000209", t.headOffice(), true);
        String account = open(member, product("SMSG", Map.of())).get("id").asString();
        MemberLookup lookup = AopTestUtils.getUltimateTargetObject(memberLookup);

        for (boolean sqlError : new boolean[] {false, true}) {
            Mockito.reset(lookup);
            Mockito.doAnswer(i -> {
                        if (sqlError) {
                            jdbc.sql("SELECT 1 / 0").query(Integer.class).single();
                        }
                        throw new IllegalStateException("members down");
                    })
                    .when(lookup)
                    .smsPhone(ArgumentMatchers.any());
            assertThat(deposit(account, 10_000, key()).getStatusCode())
                    .as("sqlError=%s", sqlError)
                    .isEqualTo(HttpStatus.CREATED);
        }
        Mockito.reset(lookup);
        assertThat(get(account).get("balance_minor").asLong())
                .as("both deposits are in")
                .isEqualTo(20_000);
        assertThat(smsRows()).as("and nothing was queued").isEmpty();

        // A number of another country is one we do not text; the deposit is recorded all the same.
        TestDatabase.owner()
                .sql("UPDATE lending_members SET phone_e164 = '+14155550123' WHERE id = ?::uuid")
                .param(member)
                .update();
        assertThat(deposit(account, 10_000, key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(smsRows()).isEmpty();
    }

    // ---- Reversal -----------------------------------------------------------------------------

    /**
     * FR-NTF-02 (issue #221): the receipt of a deposit and of a withdrawal is queued once each, and the reversal that a
     * checker approves queues nothing of its own: the member keeps the receipt they were sent.
     */
    @Test
    void frNtf02_aReversalQueuesNoReceiptOfItsOwnAndAReplayedDepositQueuesNoSecond() {
        threshold(10_000_000);
        smsOn();
        String account = account(product("SMSREV", Map.of()));
        String depositKey = key();
        assertThat(deposit(account, 80_000, depositKey).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(deposit(account, 80_000, depositKey).getHeaders().getFirst("Idempotent-Replayed"))
                .isEqualTo("true");
        String withdrawal = withdraw(account, 30_000, key())
                .getBody()
                .get("transaction")
                .get("id")
                .asString();
        String sms = "SELECT template_key FROM notification_outbox WHERE channel = 'sms' AND idempotency_key LIKE ?"
                + " ORDER BY template_key";
        String like = "sms:savings.%:" + t.tenantId() + ":%";
        assertThat(TestDatabase.owner().sql(sms).param(like).query(String.class).list())
                .containsExactly("savings.deposit", "savings.withdrawal");

        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/transactions/" + withdrawal + "/reverse",
                withKey(asCashier(), key()),
                Map.of("reason", "Recorded on the wrong account"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(decide(r.getBody().get("approval_request_id").asString(), manager, managerPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(get(account).get("balance_minor").asLong()).isEqualTo(80_000);
        assertThat(TestDatabase.owner().sql(sms).param(like).query(String.class).list())
                .as("the reversal sent nothing")
                .containsExactly("savings.deposit", "savings.withdrawal");
    }

    @Test
    void reversalsAreCheckedMirrorTheJournalAndTakeTheFeeWithTheWithdrawal() {
        threshold(10_000_000);
        String account = account(product("REV", Map.of("withdrawal_fee_minor", 500)));
        String deposit = deposit(account, 80_000, key())
                .getBody()
                .get("transaction")
                .get("id")
                .asString();
        String withdrawal = withdraw(account, 30_000, key())
                .getBody()
                .get("transaction")
                .get("id")
                .asString();
        assertThat(get(account).get("balance_minor").asLong()).isEqualTo(49_500);

        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/transactions/" + withdrawal + "/reverse",
                withKey(asCashier(), key()),
                Map.of("reason", "Recorded on the wrong account"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(decide(r.getBody().get("approval_request_id").asString(), manager, managerPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // The withdrawal and its fee come back: 49,500 + 30,000 + 500.
        assertThat(get(account).get("balance_minor").asLong()).isEqualTo(80_000);
        List<String> mirrored = TestDatabase.owner()
                .sql("""
                        SELECT a.system_key || ' ' || l.debit || ' ' || l.credit FROM lending_savings_transactions t
                          JOIN journal_lines l ON l.entry_id = t.journal_entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE t.reverses_txn_id = ?::uuid ORDER BY l.line_no
                        """)
                .param(withdrawal)
                .query(String.class)
                .list();
        assertThat(mirrored).containsExactlyInAnyOrder("member_savings 0 30000", "mobile_money_mtn 30000 0");

        ResponseEntity<JsonNode> twice = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/transactions/" + withdrawal + "/reverse",
                withKey(asCashier(), key()),
                Map.of("reason", "Again"));
        assertThat(twice.getBody().get("code").asString()).isEqualTo("already_reversed");
        String interestLike = TestDatabase.owner()
                .sql("SELECT id::text FROM lending_savings_transactions WHERE reverses_txn_id = ?::uuid")
                .param(withdrawal)
                .query(String.class)
                .single();
        ResponseEntity<JsonNode> notReversible = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/transactions/" + interestLike + "/reverse",
                withKey(asCashier(), key()),
                Map.of("reason", "A reversal is not reversed"));
        assertThat(notReversible.getBody().get("code").asString()).isEqualTo("not_reversible");

        // A deposit the account no longer holds cannot be reversed.
        withdraw(account, 79_000, key());
        ResponseEntity<JsonNode> gone = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/transactions/" + deposit + "/reverse",
                withKey(asCashier(), key()),
                Map.of("reason", "Counterfeit notes"));
        assertThat(gone.getBody().get("code").asString()).isEqualTo("insufficient_balance");
        booksAgree();
    }

    String entryOf(String txnId) {
        return TestDatabase.owner()
                .sql("SELECT journal_entry_id::text FROM lending_savings_transactions WHERE id = ?::uuid")
                .param(txnId)
                .query(String.class)
                .single();
    }

    /**
     * FR-GL-08 (review of #206): savings deposits and withdrawals post to the mapped accounts (the
     * tenant's for cash, the branch's for mobile money), and a reversal after a remap mirrors the
     * original entry, so it leaves the till the deposit went into.
     */
    @Test
    void frGl08_savingsPostToTheMappedAccountsAndAReversalMirrorsAfterARemap() {
        threshold(10_000_000);
        String tillA = assetAccount("1021", "Test savings till A");
        String tillB = assetAccount("1022", "Test savings till B");
        String momo = assetAccount("1023", "Test head office momo");
        assertThat(mapMethod("cash", null, tillA, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapMethod("mtn_momo", t.headOffice(), momo, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String account = account(product("MAPPED", Map.of()));

        String deposit = deposit(account, 80_000, key())
                .getBody()
                .get("transaction")
                .get("id")
                .asString();
        assertThat(codedLines(entryOf(deposit))).containsExactly("1021:80000:0", "2010:0:80000");
        String withdrawal = withdraw(account, 30_000, key())
                .getBody()
                .get("transaction")
                .get("id")
                .asString();
        assertThat(codedLines(entryOf(withdrawal))).containsExactly("2010:30000:0", "1023:0:30000");

        assertThat(mapMethod("cash", null, tillB, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        String later = deposit(account, 50_000, key())
                .getBody()
                .get("transaction")
                .get("id")
                .asString();
        assertThat(codedLines(entryOf(later)))
                .as("new money follows the new mapping")
                .containsExactly("1022:50000:0", "2010:0:50000");

        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/transactions/" + deposit + "/reverse",
                withKey(asCashier(), key()),
                Map.of("reason", "Counterfeit notes"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(decide(r.getBody().get("approval_request_id").asString(), manager, managerPerms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String reversal = TestDatabase.owner()
                .sql("SELECT journal_entry_id::text FROM lending_savings_transactions WHERE reverses_txn_id = ?::uuid")
                .param(deposit)
                .query(String.class)
                .single();
        assertThat(codedLines(reversal))
                .as("the reversal takes the money out of till A, where the deposit put it")
                .containsExactlyInAnyOrder("2010:80000:0", "1021:0:80000");
        booksAgree();
    }

    // ---- Status: freeze, dormancy, reactivation (FR-SAV-06) ---------------------------------

    @Test
    void frSav06_frozenAndDormantAccountsRefuseWithdrawalsUntilAManagerActs() {
        threshold(10_000_000);
        String product = product("DORM", Map.of("dormancy_days", 30));
        String member = member("Test Saver 04", "+256700000204", t.headOffice(), true);
        // Opened 40 days ago with one deposit then: the end of day finds 30 days without activity.
        UUID account = inTenant(() -> {
            UUID id = servicing.openAccount(
                    UUID.fromString(member), UUID.fromString(product), today.minusDays(40), officer);
            servicing.deposit(id, 70_000, today.minusDays(40), "cash", cashier);
            servicing.endOfDay(today.minusDays(1));
            return id;
        });
        assertThat(get(account.toString()).get("status").asString()).isEqualTo("dormant");
        assertThat(withdraw(account.toString(), 1_000, key())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("account_dormant");
        // Deposits still land on a dormant account; it stays dormant until reactivated.
        assertThat(deposit(account.toString(), 5_000, key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(send(
                                HttpMethod.POST,
                                ACCOUNTS + "/" + account + "/reactivate",
                                asCashier(),
                                Map.of("reason", "Member came in"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> back = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/reactivate",
                as(manager, managerPerms, "*", null),
                Map.of("reason", "Member came in with ID"));
        assertThat(back.getBody().get("status").asString()).isEqualTo("active");

        ResponseEntity<JsonNode> frozen = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/freeze",
                as(manager, managerPerms, "*", null),
                Map.of("reason", "Court order, fabricated"));
        assertThat(frozen.getBody().get("status").asString()).isEqualTo("frozen");
        assertThat(frozen.getBody().get("available_minor").asLong()).isZero();
        assertThat(withdraw(account.toString(), 1_000, key())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("account_frozen");
        ResponseEntity<JsonNode> close = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/close",
                withKey(asCashier(), key()),
                Map.of("payment_method_key", "cash"));
        assertThat(close.getBody().get("code").asString()).isEqualTo("account_frozen");
        assertThat(send(
                                HttpMethod.POST,
                                ACCOUNTS + "/" + account + "/unfreeze",
                                as(manager, managerPerms, "*", null),
                                Map.of("reason", "Order lifted"))
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("active");
        assertThat(withdraw(account.toString(), 1_000, key()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action LIKE 'lending.savings_account.%'")
                        .param(account)
                        .query(Long.class)
                        .single())
                .isEqualTo(5);
        booksAgree();
    }

    // ---- Closure (FR-SAV-07) ----------------------------------------------------------------

    /**
     * FR-SAV-07: closing posts interest to date first, then pays the whole balance out. Opened ten
     * days ago at 100,000 on 7.3% daily balance: 10 days x 100,000 x 730 / 3,650,000 = 200 exactly.
     */
    @Test
    void frSav07_closingPostsInterestToDateThenPaysEverythingOut() {
        threshold(10_000_000);
        ResponseEntity<JsonNode> p =
                createProduct("CLOSE", terms("daily_balance", 730, Map.of("interest_posting", "yearly")));
        String product = p.getBody().get("id").asString();
        String member = member("Test Saver 05", "+256700000205", t.headOffice(), true);
        UUID account = inTenant(() -> {
            UUID id = servicing.openAccount(
                    UUID.fromString(member), UUID.fromString(product), today.minusDays(10), officer);
            servicing.deposit(id, 100_000, today.minusDays(10), "cash", cashier);
            return id;
        });
        // The yearly period has not ended, so nothing was posted yet; the account shows nothing accrued
        // until the end of day has run.
        ResponseEntity<JsonNode> r = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/close",
                withKey(asCashier(), key()),
                Map.of("payment_method_key", "cash", "reason", "Member moving away"));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode closed = r.getBody().get("account");
        assertThat(closed.get("status").asString()).isEqualTo("closed");
        assertThat(closed.get("balance_minor").asLong()).isZero();
        assertThat(r.getBody().get("transaction").get("amount_minor").asLong()).isEqualTo(100_200);
        // 20 a day exactly, so the total is 200 even when a year end falls inside the ten days.
        List<String> interest = TestDatabase.owner()
                .sql("SELECT id::text FROM lending_savings_transactions WHERE account_id = ? AND txn_type = 'interest'")
                .param(account)
                .query(String.class)
                .list();
        assertThat(journal(interest.getLast()))
                .hasSize(2)
                .allMatch(l -> l.startsWith("savings_interest_expense") || l.startsWith("member_savings 0 "));
        assertThat(TestDatabase.owner()
                        .sql("SELECT sum(interest_minor) FROM lending_savings_interest_postings WHERE account_id = ?")
                        .param(account)
                        .query(Long.class)
                        .single())
                .isEqualTo(200);
        assertThat(deposit(account.toString(), 1_000, key())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("account_closed");
        booksAgree();
    }

    // ---- Statement and reports --------------------------------------------------------------

    @Test
    void theStatementAndTheReportsAgreeWithTheLedger() {
        threshold(10_000_000);
        String product = product("STMT", Map.of());
        String account = account(product);
        deposit(account, 40_000, key());
        deposit(account, 25_000, key());
        withdraw(account, 15_000, key());

        JsonNode s = send(HttpMethod.GET, ACCOUNTS + "/" + account + "/statement", asCashier(), null)
                .getBody();
        assertThat(s.get("opening_balance_minor").asLong()).isZero();
        assertThat(s.get("total_credits_minor").asLong()).isEqualTo(65_000);
        assertThat(s.get("total_debits_minor").asLong()).isEqualTo(15_000);
        assertThat(s.get("closing_balance_minor").asLong()).isEqualTo(50_000);
        List<Long> running = new ArrayList<>();
        s.get("lines").forEach(l -> running.add(l.get("balance_minor").asLong()));
        assertThat(running).containsExactly(40_000L, 65_000L, 50_000L);
        assertThat(send(
                                HttpMethod.GET,
                                ACCOUNTS + "/" + account + "/statement?from=" + today + "&to=" + today.minusDays(1),
                                asCashier(),
                                null)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("invalid_range");

        HttpHeaders reports = as(manager, managerPerms, "*", null);
        JsonNode balances = send(HttpMethod.GET, "/api/v1/lending/savings-reports/balances", reports, null)
                .getBody();
        assertThat(balances.get("total_minor").asLong()).isEqualTo(50_000);
        long ledger = TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(j.credit - j.debit), 0) FROM journal_lines j
                          JOIN gl_accounts a ON a.id = j.account_id AND a.system_key = 'member_savings'
                         WHERE j.tenant_id = ?
                        """)
                .param(t.tenantId())
                .query(Long.class)
                .single();
        assertThat(balances.get("total_minor").asLong()).isEqualTo(ledger);
        JsonNode movements = send(HttpMethod.GET, "/api/v1/lending/savings-reports/movements", reports, null)
                .getBody();
        JsonNode total = movements.get("total");
        assertThat(total.get("deposits_minor").asLong()).isEqualTo(65_000);
        assertThat(total.get("withdrawals_minor").asLong()).isEqualTo(15_000);
        assertThat(total.get("closing_minor").asLong()).isEqualTo(50_000);
        // A cashier holds no report permission.
        assertThat(send(HttpMethod.GET, "/api/v1/lending/savings-reports/balances", asCashier(), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Branch scope: an account in a branch outside the caller's scope is a 404. */
    @Test
    void anAccountOutsideTheCallersBranchesIsNotFound() {
        String product = product("SCOPE", Map.of());
        String account = account(product);
        HttpHeaders other = as(cashier, cashierPerms, t.secondBranch().toString(), null);
        assertThat(send(HttpMethod.GET, ACCOUNTS + "/" + account, other, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(
                                HttpMethod.POST,
                                ACCOUNTS + "/" + account + "/deposits",
                                withKey(other, key()),
                                Map.of("amount_minor", 1_000, "payment_method_key", "cash"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.GET, ACCOUNTS, other, null)
                        .getBody()
                        .get("items")
                        .size())
                .isZero();
    }
}
