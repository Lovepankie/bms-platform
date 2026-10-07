package com.rincoltech.bms.lending.savings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
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
 * Savings on real PostgreSQL (#151; FR-SAV-01 to FR-SAV-07): products, accounts, deposits,
 * withdrawals with fees, limits and the {@code savings_withdrawal} checker, reversals through
 * {@code savings_reversal}, freeze, dormancy and reactivation, closure with interest to date, the
 * statement, the reports, the receipt SMS in the outbox, idempotency and branch scope. Every journal
 * is checked against chapter 6 section 6.6.3, and after each test the member savings control
 * account equals each account's balance. All figures fabricated.
 */
class SavingsIT extends LoanFixtures {

    static final String PRODUCTS = "/api/v1/lending/savings-products";
    static final String ACCOUNTS = "/api/v1/lending/savings-accounts";

    @Autowired
    BusinessClock clock;

    @Autowired
    SavingsServicing servicing;

    @Autowired
    TenantJobs tenantJobs;

    @Autowired
    PlatformTransactionManager transactionManager;

    final UUID cashier = UUID.randomUUID();
    final UUID manager = UUID.randomUUID();
    final UUID admin = UUID.randomUUID();
    String cashierPerms;
    LocalDate today;

    @BeforeEach
    void users() {
        cashierPerms = permissionsOf("cashier");
        today = clock.today(BusinessClock.DEFAULT_ZONE);
    }

    // ---- Fixtures -------------------------------------------------------------------------

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
                .param("lending.savings.sms:" + t.tenantId() + ":%")
                .query()
                .listOfRows();
        assertThat(sms).hasSize(1);
        assertThat(sms.getFirst().get("recipient")).isEqualTo("+256700000203");
        assertThat(sms.getFirst().get("template_key")).isEqualTo("savings.deposit");
        assertThat((String) sms.getFirst().get("params"))
                .contains("50,000", first.get("account_no").asString());
        assertThat(sms.getFirst().get("status")).isEqualTo("pending");
        assertThat(sms.getFirst().get("expires")).isEqualTo(true);
        booksAgree();
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

    // ---- Reversal -----------------------------------------------------------------------------

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
