package com.rincoltech.bms.core.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The Idempotency-Key protocol of chapter 7 section 7.8 through a lending route (a savings
 * deposit), the same assertions as {@link RetailIdempotencyProtocolIT} makes through a retail
 * route: both modules share the one implementation in {@code core.operations} (issue #177).
 * Fabricated names and amounts.
 */
class LendingIdempotencyProtocolIT extends LoanFixtures {

    static final String ACCOUNTS = "/api/v1/lending/savings-accounts";

    final UUID cashier = UUID.randomUUID();

    HttpHeaders cashier(String key) {
        HttpHeaders h = as(cashier, permissionsOf("cashier"), "*", null);
        if (key != null) {
            h.add("Idempotency-Key", key);
        }
        return h;
    }

    String account() {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("name", "Test idem");
        terms.put("interest_rate_bp", 0);
        terms.put("interest_calc", "none");
        terms.put("interest_posting", "monthly");
        String product = send(
                        HttpMethod.POST,
                        "/api/v1/lending/savings-products",
                        as(UUID.randomUUID(), adminPerms, "*", null),
                        Map.of("code", "IDEM", "terms", terms))
                .getBody()
                .get("id")
                .asString();
        String member = member("Test Saver 11", "+256700000411", t.headOffice(), true);
        return asOfficer(HttpMethod.POST, ACCOUNTS, null, Map.of("member_id", member, "product_id", product))
                .getBody()
                .get("id")
                .asString();
    }

    ResponseEntity<JsonNode> deposit(String account, long amount, String key) {
        return send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/deposits",
                cashier(key),
                Map.of("amount_minor", amount, "payment_method_key", "cash"));
    }

    long txnCount(String account) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM lending_savings_transactions WHERE account_id = ?::uuid")
                .param(account)
                .query(Long.class)
                .single();
    }

    @Test
    void aReplayReturnsTheStoredResponseAndDoesTheWorkOnce() {
        String account = account();
        String key = "idem-" + UUID.randomUUID();
        ResponseEntity<JsonNode> first = deposit(account, 5_000, key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();

        ResponseEntity<JsonNode> again = deposit(account, 5_000, key);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(again.getBody().get("transaction").get("id").asString())
                .isEqualTo(first.getBody().get("transaction").get("id").asString());
        assertThat(txnCount(account)).isEqualTo(1);
    }

    @Test
    void aMissingKeyIs422() {
        ResponseEntity<JsonNode> r = deposit(account(), 5_000, null);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("idempotency_key_missing");
    }

    @Test
    void theSameKeyWithADifferentBodyIs422AndMovesNothing() {
        String account = account();
        String key = "idem-" + UUID.randomUUID();
        assertThat(deposit(account, 5_000, key).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> other = deposit(account, 6_000, key);

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(other.getBody().get("code").asString()).isEqualTo("idempotency_key_reused");
        assertThat(txnCount(account)).isEqualTo(1);
    }

    @Test
    void theSameKeyOnAnotherEndpointIs422() {
        String account = account();
        String key = "idem-" + UUID.randomUUID();
        assertThat(deposit(account, 5_000, key).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> other = send(
                HttpMethod.POST,
                ACCOUNTS + "/" + account + "/withdrawals",
                cashier(key),
                Map.of("amount_minor", 1_000, "payment_method_key", "cash"));

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(other.getBody().get("code").asString()).isEqualTo("idempotency_key_reused");
    }

    @Test
    void aKeyHeldByARequestStillRunningIs409AfterTheLockTimeout() throws Exception {
        String account = account();
        String key = "idem-" + UUID.randomUUID();
        try (HeldIdempotencyKey held = new HeldIdempotencyKey(t.tenantId(), cashier, key)) {
            ResponseEntity<JsonNode> r = deposit(account, 5_000, key);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody().get("code").asString()).isEqualTo("idempotency_in_progress");
        }
        assertThat(txnCount(account)).isZero();
    }
}
