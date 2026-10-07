package com.rincoltech.bms.lending.savings;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.lending.loans.LoanFixtures;
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
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * FR-SAV-04 and chapter 15 section 15.6: concurrent movements on one account serialise on its row
 * lock. Two withdrawals racing for the same balance never overdraw it, and 50 concurrent deposits
 * produce the exact balance with unique, gap-free running order and receipt numbers.
 */
class SavingsConcurrencyIT extends LoanFixtures {

    static final String ACCOUNTS = "/api/v1/lending/savings-accounts";

    final UUID cashier = UUID.randomUUID();

    HttpHeaders cashier(String key) {
        HttpHeaders h = as(cashier, permissionsOf("cashier"), "*", null);
        h.add("Idempotency-Key", key);
        return h;
    }

    String account() {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("name", "Test race");
        terms.put("interest_rate_bp", 0);
        terms.put("interest_calc", "none");
        terms.put("interest_posting", "monthly");
        String product = send(
                        HttpMethod.POST,
                        "/api/v1/lending/savings-products",
                        as(UUID.randomUUID(), adminPerms, "*", null),
                        Map.of("code", "RACE", "terms", terms))
                .getBody()
                .get("id")
                .asString();
        String member = member("Test Saver 10", "+256700000410", t.headOffice(), true);
        return asOfficer(HttpMethod.POST, ACCOUNTS, null, Map.of("member_id", member, "product_id", product))
                .getBody()
                .get("id")
                .asString();
    }

    List<ResponseEntity<JsonNode>> race(int n, Callable<ResponseEntity<JsonNode>> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<ResponseEntity<JsonNode>> out = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void frSav04_twoWithdrawalsRacingForOneBalanceNeverOverdrawIt() throws Exception {
        TestDatabase.owner()
                .sql("INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?,"
                        + " '{\"approval_thresholds_minor\": {\"savings_withdrawal\": 10000000}}')")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        String account = account();
        assertThat(send(
                                HttpMethod.POST,
                                ACCOUNTS + "/" + account + "/deposits",
                                cashier("dep-" + UUID.randomUUID()),
                                Map.of("amount_minor", 100_000, "payment_method_key", "cash"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        List<ResponseEntity<JsonNode>> results = race(
                2,
                () -> send(
                        HttpMethod.POST,
                        ACCOUNTS + "/" + account + "/withdrawals",
                        cashier("wd-" + UUID.randomUUID()),
                        Map.of("amount_minor", 70_000, "payment_method_key", "cash")));

        assertThat(results.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED))
                .hasSize(1);
        assertThat(results.stream()
                        .filter(r -> r.getStatusCode() == HttpStatus.UNPROCESSABLE_CONTENT)
                        .map(r -> r.getBody().get("code").asString()))
                .containsExactly("insufficient_balance");
        assertThat(TestDatabase.owner()
                        .sql("SELECT balance_minor FROM lending_savings_accounts WHERE id = ?::uuid")
                        .param(account)
                        .query(Long.class)
                        .single())
                .isEqualTo(30_000);
    }

    @Test
    void frSav04_fiftyConcurrentDepositsGiveTheExactBalance() throws Exception {
        String account = account();
        List<ResponseEntity<JsonNode>> results = race(
                50,
                () -> send(
                        HttpMethod.POST,
                        ACCOUNTS + "/" + account + "/deposits",
                        cashier("dep-" + UUID.randomUUID()),
                        Map.of("amount_minor", 1_000, "payment_method_key", "cash")));

        assertThat(results).allMatch(r -> r.getStatusCode() == HttpStatus.CREATED);
        Map<String, Object> row =
                TestDatabase.owner().sql("""
                        SELECT a.balance_minor, a.txn_count,
                               (SELECT count(DISTINCT seq) FROM lending_savings_transactions WHERE account_id = a.id) AS seqs,
                               (SELECT max(seq) FROM lending_savings_transactions WHERE account_id = a.id) AS top,
                               (SELECT count(DISTINCT receipt_no) FROM lending_savings_transactions WHERE account_id = a.id) AS receipts,
                               (SELECT max(balance_after_minor) FROM lending_savings_transactions WHERE account_id = a.id) AS last
                          FROM lending_savings_accounts a WHERE a.id = ?::uuid
                        """).param(account).query().singleRow();
        assertThat(((Number) row.get("balance_minor")).longValue()).isEqualTo(50_000);
        assertThat(((Number) row.get("txn_count")).intValue()).isEqualTo(50);
        assertThat(((Number) row.get("seqs")).intValue()).isEqualTo(50);
        assertThat(((Number) row.get("top")).intValue()).isEqualTo(50);
        assertThat(((Number) row.get("receipts")).intValue()).isEqualTo(50);
        assertThat(((Number) row.get("last")).longValue()).isEqualTo(50_000);
    }
}
