package com.rincoltech.bms.retail.purchasing;

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
 * Issue #852 (ADR-026 decision 5, ADR-055): a buyer who sends the Idempotency-Key the owner used for a restock is
 * refused and never gets the owner's restock, its total or its lines; the owner still replays it. Every name and
 * quantity is fabricated.
 */
class RetailRestockKeyPerPersonIT extends IntegrationTest {

    /** Buys stock, with no profit permission and no permission to change prices. */
    static final String BUYER = "retail.purchase.create,retail.stock.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport owner;
    RetailTestSupport buyer;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("restock-key-person", false, true);
        owner = new RetailTestSupport(http, t);
        buyer = new RetailTestSupport(http, t);
        product = owner.product("RKP-1", 250, 400);
    }

    Map<String, Object> restock(String qty) {
        return Map.of(
                "payment_method",
                "cash",
                "lines",
                List.of(Map.of(
                        "product_id",
                        product,
                        "cost_minor",
                        250,
                        "qty_by_branch",
                        List.of(Map.of("branch_id", t.headOffice(), "qty", qty)))));
    }

    long restocks() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_purchases WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    @Test
    void aBuyerWithTheOwnersKeyIsRefusedAndGetsNoneOfTheOwnersRestock() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> made = owner.postKeyed("/purchases", restock("4"), ADMIN, "*", key);
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> asBuyer = buyer.postKeyed("/purchases", restock("4"), BUYER, "*", key);
        KeyOwnership.refused(asBuyer, made, restock("4"));
        assertThat(asBuyer.getBody().has("total_minor")).isFalse();
        assertThat(asBuyer.getBody().has("lines")).isFalse();
        assertThat(restocks()).isEqualTo(1);
        assertThat(KeyOwnership.holders(t.tenantId(), key)).containsExactly(owner.userId());

        KeyOwnership.replayed(owner.postKeyed("/purchases", restock("4"), ADMIN, "*", key), made);
        assertThat(restocks()).isEqualTo(1);
    }

    @Test
    void anotherPersonsKeyOnAVoidIsRefusedAndTheRestockStaysAsItIs() {
        String made = UUID.randomUUID().toString();
        UUID id = RetailTestSupport.id(owner.postKeyed("/purchases", restock("3"), ADMIN, "*", made));
        String key = UUID.randomUUID().toString();
        String path = "/purchases/" + id + "/void";
        Map<String, Object> reason = Map.of("reason", "Test wrong delivery");
        ResponseEntity<JsonNode> voided = owner.postKeyed(path, reason, ADMIN, "*", key);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);

        KeyOwnership.refused(buyer.postKeyed(path, reason, BUYER, "*", key), voided, path, reason);
        KeyOwnership.replayed(owner.postKeyed(path, reason, ADMIN, "*", key), voided);
    }
}
