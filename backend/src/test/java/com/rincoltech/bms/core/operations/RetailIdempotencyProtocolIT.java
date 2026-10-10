package com.rincoltech.bms.core.operations;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHBOOK_ADMIN;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.daysAgo;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.retail.cashbook.CashbookTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The Idempotency-Key protocol of chapter 7 section 7.8 through a retail route (the cash book
 * expense), the same assertions as {@link LendingIdempotencyProtocolIT} makes through a lending
 * route: both modules share the one implementation in {@code core.operations} (issue #177).
 * Fabricated names and amounts.
 */
class RetailIdempotencyProtocolIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    UUID hq;
    UUID category;
    UUID item;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("idem-retail", false, true);
        cb = new CashbookTestSupport(http, t);
        hq = t.headOffice();
        UUID[] list = cb.categoryWithItem("Premises", "Shop rent", false);
        category = list[0];
        item = list[1];
    }

    Map<String, Object> expense(long amount) {
        return Map.of(
                "branch_id", hq,
                "category_id", category,
                "item_id", item,
                "amount_minor", amount,
                "business_date", daysAgo(1).toString());
    }

    ResponseEntity<JsonNode> post(String path, Object body, String key) {
        return cb.api.postKeyed(path, body, CASHBOOK_ADMIN, "*", key);
    }

    @Test
    void aReplayReturnsTheStoredResponseAndDoesTheWorkOnce() {
        String key = "idem-" + UUID.randomUUID();
        ResponseEntity<JsonNode> first = post("/expenses", expense(12_000), key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();

        ResponseEntity<JsonNode> again = post("/expenses", expense(12_000), key);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(RetailTestSupport.id(again)).isEqualTo(RetailTestSupport.id(first));
        assertThat(cb.accountMovement("operating_expenses", hq)).isEqualTo(12_000);
    }

    @Test
    void aMissingKeyIs422() {
        ResponseEntity<JsonNode> r =
                cb.api.call(HttpMethod.POST, "/expenses", expense(1_000), CASHBOOK_ADMIN, "*", Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("idempotency_key_missing");
    }

    @Test
    void theSameKeyWithADifferentBodyIs422AndMovesNothing() {
        String key = "idem-" + UUID.randomUUID();
        assertThat(post("/expenses", expense(12_000), key).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> other = post("/expenses", expense(13_000), key);

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(other.getBody().get("code").asString()).isEqualTo("idempotency_key_reused");
        assertThat(cb.accountMovement("operating_expenses", hq)).isEqualTo(12_000);
    }

    @Test
    void theSameKeyOnAnotherEndpointIs422() {
        String key = "idem-" + UUID.randomUUID();
        assertThat(post("/expenses", expense(12_000), key).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> other = post("/bankings", Map.of("amount_minor", 5_000, "branch_id", hq), key);

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(other.getBody().get("code").asString()).isEqualTo("idempotency_key_reused");
    }

    @Test
    void aKeyHeldByARequestStillRunningIs409AfterTheLockTimeout() throws Exception {
        String key = "idem-" + UUID.randomUUID();
        try (HeldIdempotencyKey held = new HeldIdempotencyKey(t.tenantId(), cb.api.userId(), key)) {
            ResponseEntity<JsonNode> r = post("/expenses", expense(12_000), key);

            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(r.getBody().get("code").asString()).isEqualTo("idempotency_in_progress");
        }
        assertThat(cb.accountMovement("operating_expenses", hq)).isZero();
    }
}
