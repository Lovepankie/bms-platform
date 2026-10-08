package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHBOOK_SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
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
 * FR-RET-28 and FR-RET-32 over HTTP: every cash book write is money-moving and needs an
 * Idempotency-Key, every void needs one and the void permission, and the sales role is refused
 * what ADR-022 withholds from it. Fabricated names and amounts.
 */
class RetailCashbookRoutesIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    UUID hq;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("cb-routes", false, true);
        cb = new CashbookTestSupport(http, t);
        hq = t.headOffice();
    }

    void assertKeyMissing(String path, Object body) {
        ResponseEntity<JsonNode> r = cb.api.post(path, body, ALL);
        assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).as(path).isEqualTo("idempotency_key_missing");
    }

    @Test
    void everyWriteAndEveryVoidRequiresAnIdempotencyKey() {
        UUID[] list = cb.categoryWithItem("Premises", "Shop rent", false);
        UUID owner = cb.party("Test Owner 01", "owner");
        Map<String, Object> savings = new LinkedHashMap<>();
        savings.put("suggestion_token", "abc");
        assertKeyMissing("/savings", savings);
        assertKeyMissing("/bankings", Map.of("amount_minor", 1_000, "branch_id", hq));
        assertKeyMissing("/withdrawals", Map.of("amount_minor", 1_000, "branch_id", hq));
        assertKeyMissing(
                "/expenses",
                Map.of("category_id", list[0], "item_id", list[1], "amount_minor", 1_000, "branch_id", hq));
        assertKeyMissing("/advances", Map.of("party_id", owner, "principal_minor", 1_000, "branch_id", hq));
        UUID advance = com.rincoltech.bms.retail.RetailTestSupport.id(
                cb.post("/advances", Map.of("party_id", owner, "principal_minor", 1_000, "branch_id", hq), ALL));
        assertKeyMissing("/advances/" + advance + "/repayments", Map.of("amount_minor", 100, "method", "cash"));
        Map<String, String> reason = Map.of("reason", "Entered twice");
        for (String path : new String[] {
            "/savings/" + UUID.randomUUID() + "/void",
            "/bankings/" + UUID.randomUUID() + "/void",
            "/withdrawals/" + UUID.randomUUID() + "/void",
            "/expenses/" + UUID.randomUUID() + "/void",
            "/advances/" + advance + "/void",
            "/advances/" + advance + "/repayments/" + UUID.randomUUID() + "/void"
        }) {
            assertKeyMissing(path, reason);
        }
    }

    @Test
    void theSalesRoleIsRefusedWhatTheAdrWithholdsFromIt() {
        UUID id = UUID.randomUUID();
        Map<String, Object> any = Map.of("amount_minor", 100);
        for (String path : new String[] {
            "/withdrawals",
            "/advances",
            "/advances/" + id + "/repayments",
            "/expense-categories",
            "/savings/" + id + "/void",
            "/bankings/" + id + "/void",
            "/expenses/" + id + "/void"
        }) {
            Object body = path.endsWith("/void") ? Map.of("reason", "Entered twice") : any;
            ResponseEntity<JsonNode> r = cb.post(path, body, CASHBOOK_SALES);
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(r.getBody().get("code").asString()).isEqualTo("permission_denied");
        }
        // And what it does hold works.
        assertThat(cb.get("/reports/cash/daily", CASHBOOK_SALES).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.get("/savings/suggestion", CASHBOOK_SALES, hq.toString()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.get("/bankings/expected", CASHBOOK_SALES, hq.toString()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void aReadingUserWithoutTheCashbookReadPermissionSeesNothing() {
        for (String path : new String[] {
            "/savings",
            "/bankings",
            "/withdrawals",
            "/expenses",
            "/advances",
            "/cash-parties",
            "/expense-categories",
            "/reports/cash/daily",
            "/reports/cash/banking",
            "/reports/cash/expenses",
            "/reports/cash/savings",
            "/reports/cash/advances"
        }) {
            assertThat(cb.get(path, "retail.sale.read,retail.stock.read").getStatusCode())
                    .as(path)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void aFutureDateOnAnyRecordIsAFieldProblemNamingTheField() {
        String future = java.time.LocalDate.now().plusDays(1).toString();
        ResponseEntity<JsonNode> banking =
                cb.post("/bankings", Map.of("branch_id", hq, "business_date", future, "amount_minor", 100), ALL);
        assertThat(banking.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("future_date");
        ResponseEntity<JsonNode> withdrawal =
                cb.post("/withdrawals", Map.of("branch_id", hq, "business_date", future, "amount_minor", 100), ALL);
        assertThat(withdrawal.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("future_date");
        UUID owner = cb.party("Test Owner 01", "owner");
        ResponseEntity<JsonNode> advance = cb.post(
                "/advances",
                Map.of("branch_id", hq, "business_date", future, "party_id", owner, "principal_minor", 100),
                ALL);
        assertThat(advance.getBody().get("errors").get(0).get("code").asString())
                .isEqualTo("future_date");
        ResponseEntity<JsonNode> late = cb.post(
                "/bankings",
                Map.of(
                        "branch_id",
                        hq,
                        "amount_minor",
                        100,
                        "banked_at",
                        java.time.Instant.now().plusSeconds(3 * 86_400).toString()),
                ALL);
        assertThat(late.getBody().get("errors").get(0).get("field").asString()).isEqualTo("banked_at");
    }

    @Test
    void everyWriteAndVoidLeavesAnAuditRowInItsTransaction() {
        UUID[] list = cb.categoryWithItem("Premises", "Shop rent", false);
        UUID expense = com.rincoltech.bms.retail.RetailTestSupport.id(cb.expense(hq, list[0], list[1], 1_000, null));
        UUID banking = com.rincoltech.bms.retail.RetailTestSupport.id(
                cb.post("/bankings", Map.of("branch_id", hq, "amount_minor", 100), ALL));
        UUID withdrawal = com.rincoltech.bms.retail.RetailTestSupport.id(
                cb.post("/withdrawals", Map.of("branch_id", hq, "amount_minor", 100), ALL));
        cb.voidIt("/expenses/" + expense, "Entered twice", ALL);
        cb.voidIt("/bankings/" + banking, "Wrong slip", ALL);
        cb.voidIt("/withdrawals/" + withdrawal, "Typed twice", ALL);
        for (String action : new String[] {
            "retail.expense_category.created",
            "retail.expense_item.created",
            "retail.expense.created",
            "retail.banking.created",
            "retail.withdrawal.created",
            "retail.expense.voided",
            "retail.banking.voided",
            "retail.withdrawal.voided"
        }) {
            assertThat(cb.auditPayloads(action)).as(action).hasSize(1);
        }
    }
}
