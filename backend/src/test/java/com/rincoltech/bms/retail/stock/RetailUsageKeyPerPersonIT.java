package com.rincoltech.bms.retail.stock;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.KeyOwnership;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Issue #852 (ADR-026 decision 5) on a usage or damage entry: its Idempotency-Key answers only the person who made it;
 * anyone else is refused and no stock moves. Every name and quantity is fabricated.
 */
class RetailUsageKeyPerPersonIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport reporter;
    RetailTestSupport someoneElse;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("usage-key-person", false, true);
        reporter = new RetailTestSupport(http, t);
        someoneElse = new RetailTestSupport(http, t);
        product = reporter.product("UKP-1", 300, 450);
        reporter.stockUp(t.headOffice(), product, "20");
    }

    Map<String, Object> usage() {
        return Map.of(
                "branch_id",
                t.headOffice(),
                "kind",
                "used",
                "reason",
                "Test installation",
                "lines",
                List.of(Map.of("product_id", product, "qty", "2")));
    }

    String held() {
        return TestDatabase.owner()
                .sql("SELECT qty::text FROM retail_stock_balances WHERE tenant_id = ? AND product_id = ?")
                .params(t.tenantId(), product)
                .query(String.class)
                .single();
    }

    @Test
    void anotherPersonsUsageKeyIsRefusedAndNoStockMoves() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> made = reporter.postKeyed("/usage", usage(), ADMIN, "*", key);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        String after = held();

        KeyOwnership.refused(someoneElse.postKeyed("/usage", usage(), ADMIN, "*", key), made, usage());
        assertThat(held()).isEqualTo(after);
        assertThat(KeyOwnership.holders(t.tenantId(), key)).containsExactly(reporter.userId());

        KeyOwnership.replayed(reporter.postKeyed("/usage", usage(), ADMIN, "*", key), made);
        assertThat(held()).isEqualTo(after);
    }
}
