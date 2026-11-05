package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.KeyOwnership;
import java.util.LinkedHashMap;
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
 * Issue #852 (ADR-026 decision 5) on the sales routes: a sale's Idempotency-Key answers only the person who made the
 * sale. Every name and quantity is fabricated.
 */
class RetailSalesKeyPerPersonIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport seller;
    RetailTestSupport someoneElse;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("sale-key-person", false, true);
        seller = new RetailTestSupport(http, t);
        someoneElse = new RetailTestSupport(http, t);
        product = seller.product("SKP-1", 100, 150);
        seller.stockUp(t.headOffice(), product, "20");
    }

    Map<String, Object> sale(String qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "cash");
        body.put("lines", List.of(Map.of("product_id", product, "qty", qty)));
        return body;
    }

    long sales() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_sales WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    @Test
    void anotherPersonsSaleKeyIsRefusedAndTheSellerStillReplays() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> made = seller.postKeyed("/sales", sale("2"), ADMIN, "*", key);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);

        KeyOwnership.refused(someoneElse.postKeyed("/sales", sale("2"), ADMIN, "*", key), made, sale("2"));
        assertThat(sales()).isEqualTo(1);
        assertThat(KeyOwnership.holders(t.tenantId(), key)).containsExactly(seller.userId());

        KeyOwnership.replayed(seller.postKeyed("/sales", sale("2"), ADMIN, "*", key), made);
        assertThat(sales()).isEqualTo(1);
    }
}
