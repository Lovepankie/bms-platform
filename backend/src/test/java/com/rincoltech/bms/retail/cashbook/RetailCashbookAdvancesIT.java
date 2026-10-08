package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHBOOK_SALES;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.daysAgo;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * FR-RET-25 and FR-RET-26 (ADR-022 decisions 2 and 6): advances to the owner or a related party are a
 * receivable with a sequential number, repaid by cash, mobile money or bank; the balance and the
 * account move together, also under concurrent repayments. All names and amounts are fabricated.
 */
class RetailCashbookAdvancesIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    UUID hq;
    UUID owner;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("cb-adv", false, true);
        cb = new CashbookTestSupport(http, t);
        hq = t.headOffice();
        owner = cb.party("Test Owner 01", "owner");
    }

    ResponseEntity<JsonNode> advance(long principal, UUID party, UUID branch) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", branch);
        b.put("business_date", daysAgo(2).toString());
        b.put("party_id", party);
        b.put("principal_minor", principal);
        b.put("purpose", "Test purpose");
        return cb.post("/advances", b, ALL);
    }

    ResponseEntity<JsonNode> repay(UUID advance, long amount, String method, UUID branch) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (branch != null) {
            b.put("branch_id", branch);
        }
        b.put("amount_minor", amount);
        b.put("method", method);
        b.put("paid_on", daysAgo(1).toString());
        return cb.post("/advances/" + advance + "/repayments", b, ALL);
    }

    @Test
    void anAdvanceDebitsTheReceivableCreditsCashAndTakesTheNextSequentialNumber() {
        ResponseEntity<JsonNode> first = advance(100_000, owner, hq);
        ResponseEntity<JsonNode> second = advance(50_000, owner, hq);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("advance_no").asString()).isEqualTo("RA00000001");
        assertThat(second.getBody().get("advance_no").asString()).isEqualTo("RA00000002");
        assertThat(first.getBody().get("balance_minor").asLong()).isEqualTo(100_000);
        assertThat(cb.accountMovement("owner_advances", hq)).isEqualTo(150_000);
        assertThat(cb.accountMovement("cash_on_hand", hq)).isEqualTo(-150_000);
        // The advance is the subledger of its entry.
        long sub = TestDatabase.owner()
                .sql(
                        "SELECT count(*) FROM journal_lines WHERE tenant_id = ? AND subledger_type = 'retail.advance' AND subledger_id = ?")
                .params(t.tenantId(), RetailTestSupport.id(first))
                .query(Long.class)
                .single();
        assertThat(sub).isEqualTo(1);
    }

    @Test
    void onlyOwnerStaffOrRelatedPartiesMayReceiveAnAdvance() {
        UUID supplier = cb.party("Test Vendor 01", "supplier");
        UUID related = cb.party("Test Sister Co 01", "related_entity");
        ResponseEntity<JsonNode> refused = advance(1_000, supplier, hq);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("party_kind_not_allowed");
        assertThat(advance(1_000, related, hq).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cb.post("/cash-parties", Map.of("name", "Test Co", "kind", "company"), ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void repaymentsByEachMethodMoveTheRightAccountAndTheBalance() {
        UUID id = RetailTestSupport.id(advance(100_000, owner, hq));

        ResponseEntity<JsonNode> cash = repay(id, 30_000, "cash", null);
        ResponseEntity<JsonNode> momo = repay(id, 20_000, "mobile_money", null);
        ResponseEntity<JsonNode> bank = repay(id, 10_000, "bank", null);

        assertThat(cash.getBody().get("balance_minor").asLong()).isEqualTo(70_000);
        assertThat(momo.getBody().get("balance_minor").asLong()).isEqualTo(50_000);
        assertThat(bank.getBody().get("balance_minor").asLong()).isEqualTo(40_000);
        assertThat(cb.accountMovement("mobile_money", hq)).isEqualTo(20_000);
        assertThat(cb.accountMovement("bank", hq)).isEqualTo(10_000);
        assertThat(cb.accountMovement("cash_on_hand", hq)).isEqualTo(-100_000 + 30_000);
        assertThat(cb.accountMovement("owner_advances", hq)).isEqualTo(40_000);
        JsonNode read = cb.get("/advances/" + id, ALL).getBody();
        assertThat(read.get("repaid_minor").asLong()).isEqualTo(60_000);
        assertThat(read.get("balance_minor").asLong()).isEqualTo(40_000);
        assertThat(read.get("repayments").size()).isEqualTo(3);
    }

    @Test
    void anOverpaymentAndAPaymentOnASettledAdvanceAreRefused() {
        UUID id = RetailTestSupport.id(advance(10_000, owner, hq));

        ResponseEntity<JsonNode> over = repay(id, 10_001, "cash", null);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(over.getBody().get("code").asString()).isEqualTo("repayment_exceeds_balance");

        assertThat(repay(id, 10_000, "cash", null)
                        .getBody()
                        .get("balance_minor")
                        .asLong())
                .isZero();
        ResponseEntity<JsonNode> settled = repay(id, 1, "cash", null);
        assertThat(settled.getBody().get("code").asString()).isEqualTo("advance_settled");
        // A settled advance no longer shows in the repayment form's list.
        assertThat(cb.get("/advances?open_only=true", ALL).getBody().get("items"))
                .isEmpty();
        assertThat(cb.get("/advances", ALL).getBody().get("items").size()).isEqualTo(1);
    }

    @Test
    void aRepaymentReceivedAtAnotherBranchPostsThereAndEachEntryBalancesPerBranch() {
        UUID id = RetailTestSupport.id(advance(10_000, owner, hq));

        assertThat(repay(id, 4_000, "cash", t.secondBranch()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(cb.accountMovement("cash_on_hand", t.secondBranch())).isEqualTo(4_000);
        assertThat(cb.accountMovement("owner_advances", t.secondBranch())).isEqualTo(-4_000);
        assertThat(cb.accountMovement("owner_advances", hq)).isEqualTo(10_000);
    }

    @Test
    void anAdvanceWithRepaymentsCannotBeVoidedUntilTheyAre() {
        UUID id = RetailTestSupport.id(advance(10_000, owner, hq));
        UUID repayment = RetailTestSupport.id(repay(id, 4_000, "cash", null));

        ResponseEntity<JsonNode> blocked = cb.voidIt("/advances/" + id, "Wrong party", ALL);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody().get("code").asString()).isEqualTo("advance_has_repayments");

        assertThat(cb.voidIt("/advances/" + id + "/repayments/" + repayment, "Typed twice", ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.get("/advances/" + id, ALL).getBody().get("balance_minor").asLong())
                .isEqualTo(10_000);
        assertThat(cb.voidIt("/advances/" + id, "Wrong party", ALL).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.accountMovement("owner_advances", hq)).isZero();
        assertThat(cb.accountMovement("cash_on_hand", hq)).isZero();
    }

    @Test
    void concurrentRepaymentsCannotOverpayAndTheRunningTotalEqualsTheSum() throws Exception {
        UUID id = RetailTestSupport.id(advance(1_000, owner, hq));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(() -> repay(id, 400, "cash", null));
            }
            List<Future<ResponseEntity<JsonNode>>> results = pool.invokeAll(calls);
            long created = 0;
            for (Future<ResponseEntity<JsonNode>> f : results) {
                if (f.get().getStatusCode() == HttpStatus.CREATED) {
                    created++;
                } else {
                    assertThat(f.get().getBody().get("code").asString())
                            .isIn("repayment_exceeds_balance", "advance_settled");
                }
            }
            // 1,000 owed: two payments of 400 fit, a third would leave a negative balance.
            assertThat(created).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        JsonNode read = cb.get("/advances/" + id, ALL).getBody();
        assertThat(read.get("repaid_minor").asLong()).isEqualTo(800);
        long mismatched = TestDatabase.owner()
                .sql("""
                        SELECT count(*) FROM retail_advances a
                         WHERE a.tenant_id = ? AND a.repaid_minor <> coalesce((SELECT sum(r.amount_minor)
                               FROM retail_advance_repayments r WHERE r.advance_id = a.id AND r.voided_at IS NULL), 0)
                        """)
                .param(t.tenantId())
                .query(Long.class)
                .single();
        assertThat(mismatched).isZero();
    }

    @Test
    void advancesAndRepaymentsAreAdminOnlyAndTheSalesRoleMayRead() {
        assertThat(cb.post("/advances", Map.of("party_id", owner, "principal_minor", 1_000), CASHBOOK_SALES)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        UUID id = RetailTestSupport.id(advance(10_000, owner, hq));
        assertThat(cb.post(
                                "/advances/" + id + "/repayments",
                                Map.of("amount_minor", 1, "method", "cash"),
                                CASHBOOK_SALES)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cb.get("/advances", CASHBOOK_SALES).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cb.get("/advances/" + id, CASHBOOK_SALES)
                        .getBody()
                        .get("party_name")
                        .asString())
                .isEqualTo("Test Owner 01");
        assertThat(cb.get("/advances/" + id, "retail.sale.read").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theOutstandingReportGroupsTheBalancesByParty() {
        UUID related = cb.party("Test Sister Co 01", "related_entity");
        UUID a = RetailTestSupport.id(advance(10_000, owner, hq));
        advance(5_000, owner, hq);
        advance(7_000, related, hq);
        repay(a, 4_000, "cash", null);

        JsonNode report = cb.get("/reports/cash/advances", CASHBOOK_SALES).getBody();

        assertThat(report.get("balance_minor").asLong()).isEqualTo(10_000 + 5_000 + 7_000 - 4_000);
        JsonNode top = report.get("items").get(0);
        assertThat(top.get("party_name").asString()).isEqualTo("Test Owner 01");
        assertThat(top.get("principal_minor").asLong()).isEqualTo(15_000);
        assertThat(top.get("repaid_minor").asLong()).isEqualTo(4_000);
        assertThat(top.get("balance_minor").asLong()).isEqualTo(11_000);
        assertThat(top.get("count").asLong()).isEqualTo(2);
        assertThat(top.get("oldest_advance_date").asString())
                .isEqualTo(daysAgo(2).toString());
    }

    @Test
    void theTakerOfAnAdvanceMustBeAnOwnerStaffOrRelatedParty() {
        UUID supplier = cb.party("Test Vendor 01", "supplier");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", hq);
        b.put("party_id", owner);
        b.put("taken_by_party_id", supplier);
        b.put("principal_minor", 1_000);

        ResponseEntity<JsonNode> refused = cb.post("/advances", b, ALL);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("party_kind_not_allowed");
        b.put("taken_by_party_id", cb.party("Test Staff 01", "staff"));
        assertThat(cb.post("/advances", b, ALL).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void anAdvanceShowsOnlyTheRepaymentsRecordedInBranchesWithinTheCallersScope() {
        UUID id = RetailTestSupport.id(advance(100_000, owner, hq));
        assertThat(repay(id, 10_000, "cash", hq).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(repay(id, 5_000, "cash", t.secondBranch()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(cb.get("/advances/" + id, ALL).getBody().get("repayments").size())
                .isEqualTo(2);
        JsonNode scoped = cb.get("/advances/" + id, ALL, hq.toString()).getBody();
        assertThat(scoped.get("repayments").size()).isEqualTo(1);
        assertThat(scoped.get("repayments").get(0).get("branch_id").asString()).isEqualTo(hq.toString());
    }
}
