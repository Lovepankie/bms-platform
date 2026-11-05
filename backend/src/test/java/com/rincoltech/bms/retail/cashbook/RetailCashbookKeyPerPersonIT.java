package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.daysAgo;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.KeyOwnership;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
 * Issue #852 (ADR-026 decision 5) on the cash book: an expense's, a savings record's and a banking's Idempotency-Key
 * answers only the person who recorded it; anyone else is refused, nothing moves, and the first person still replays.
 * One product costs 1,000 and sells at 1,500. All names and amounts are fabricated.
 */
class RetailCashbookKeyPerPersonIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    CashbookTestSupport someoneElse;
    UUID hq;
    LocalDate day;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("cb-key-person", false, true);
        cb = new CashbookTestSupport(http, t);
        someoneElse = new CashbookTestSupport(http, t);
        hq = t.headOffice();
        day = daysAgo(2);
    }

    ResponseEntity<JsonNode> post(CashbookTestSupport who, String path, Object body, String key) {
        return who.api.postKeyed(path, body, ALL, "*", key);
    }

    /** Records, refuses the other person, replays to the first; returns the first answer. */
    ResponseEntity<JsonNode> provenPerPerson(String path, Map<String, Object> body) {
        String key = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> first = post(cb, path, body, key);
        assertThat(first.getStatusCode()).as("%s %s", path, first.getBody()).isEqualTo(HttpStatus.CREATED);

        KeyOwnership.refused(post(someoneElse, path, body, key), first, body);
        assertThat(KeyOwnership.holders(t.tenantId(), key)).containsExactly(cb.api.userId());
        KeyOwnership.replayed(post(cb, path, body, key), first);
        return first;
    }

    @Test
    void anExpenseKeyAnswersOnlyThePersonWhoRecordedIt() {
        UUID[] list = cb.categoryWithItem("Premises", "Shop rent", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("category_id", list[0]);
        body.put("item_id", list[1]);
        body.put("amount_minor", 12_000);
        body.put("business_date", day.toString());

        provenPerPerson("/expenses", body);
        assertThat(cb.accountMovement("operating_expenses", hq)).isEqualTo(12_000);
    }

    @Test
    void aSavingsKeyAnswersOnlyThePersonWhoRecordedIt() {
        UUID product = cb.api.product("KP-SAV", 1_000, 1_500);
        cb.api.stockUp(hq, product, "100");
        assertThat(cb.sale(hq, "cash", product, "10", day).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode suggestion = cb.get("/savings/suggestion?branch_id=" + hq + "&date=" + day, ALL)
                .getBody();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("business_date", day.toString());
        body.put("suggestion_token", suggestion.get("suggestion_token").asString());

        ResponseEntity<JsonNode> first = provenPerPerson("/savings", body);
        assertThat(cb.accountMovement("savings_reserve", hq))
                .isEqualTo(first.getBody().get("amount_minor").asLong());
    }

    @Test
    void aBankingKeyAnswersOnlyThePersonWhoRecordedIt() {
        UUID product = cb.api.product("KP-BANK", 1_000, 1_500);
        cb.api.stockUp(hq, product, "100");
        assertThat(cb.sale(hq, "cash", product, "10", day).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("business_date", day.toString());
        body.put("amount_minor", 15_000);

        provenPerPerson("/bankings", body);
        assertThat(cb.get("/bankings", ALL).getBody().get("items").size()).isEqualTo(1);
    }
}
