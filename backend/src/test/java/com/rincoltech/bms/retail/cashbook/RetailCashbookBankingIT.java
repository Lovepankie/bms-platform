package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHBOOK_SALES;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.CASHIER;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.daysAgo;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
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
 * FR-RET-21 to FR-RET-23 (ADR-022 decisions 9 and 10): the expected amount to bank is worked from
 * the day's cash takings on the server, every record counts on its own day and a void on the day it
 * is made, the four flags, partial deposits, the banking report and the running unbanked total.
 * One product costs 1,000 and sells at 1,500. All names and amounts are fabricated.
 */
class RetailCashbookBankingIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    UUID hq;
    UUID product;
    LocalDate day;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("cb-bank", false, true);
        cb = new CashbookTestSupport(http, t);
        hq = t.headOffice();
        product = cb.api.product("CABLE-2MM", 1_000, 1_500);
        cb.api.stockUp(hq, product, "200");
        day = daysAgo(3);
    }

    JsonNode expected(String perms, LocalDate date) {
        ResponseEntity<JsonNode> r = cb.get("/bankings/expected?branch_id=" + hq + "&date=" + date, perms);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    ResponseEntity<JsonNode> bank(long amount, LocalDate date, String perms) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", hq);
        b.put("business_date", date.toString());
        b.put("amount_minor", amount);
        return cb.post("/bankings", b, perms);
    }

    void aDayOfEveryKind(LocalDate on) {
        cb.sale(hq, "cash", product, "10", on);
        cb.sale(hq, "credit", product, "2", on);
        cb.sale(hq, "mobile_money", product, "1", on);
        cb.sale(hq, "bank", product, "1", on);
    }

    @Test
    void aDayWithOneOfEachSaleKindExpectsTheCashSaleOnlyAndTheClientCannotSendIt() {
        aDayOfEveryKind(day);

        JsonNode e = expected(ALL, day);

        assertThat(e.get("cash_takings_minor").asLong()).isEqualTo(15_000);
        assertThat(e.get("cash_expected_minor").asLong()).isEqualTo(15_000);
        assertThat(e.get("expected_minor").asLong()).isEqualTo(15_000);
        assertThat(e.get("banked_so_far_minor").asLong()).isZero();

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", hq);
        b.put("business_date", day.toString());
        b.put("amount_minor", 15_000);
        b.put("expected_minor", 999_999);
        ResponseEntity<JsonNode> r = cb.post("/bankings", b, ALL);
        // The unknown field is refused rather than ignored, so a client cannot even try.
        assertThat(r.getStatusCode()).isNotEqualTo(HttpStatus.CREATED);
        assertThat(cb.get("/bankings", ALL).getBody().get("items")).isEmpty();

        ResponseEntity<JsonNode> ok = bank(15_000, day, ALL);
        assertThat(ok.getBody().get("expected_minor").asLong()).isEqualTo(15_000);
        assertThat(ok.getBody().get("flag").asString()).isEqualTo("ok");
        assertThat(ok.getBody().get("difference_minor").asLong()).isZero();
        // The bank sale of the day already sits in the bank account: 1,500 plus the 15,000 deposit.
        assertThat(cb.accountMovement("bank", hq)).isEqualTo(15_000 + 1_500);
    }

    @Test
    void aSaleVoidedTheSameDayNetsToZeroAndALaterVoidMovesOnlyTheVoidDay() {
        LocalDate today = LocalDate.now();
        UUID sameDay = RetailTestSupport.id(cb.sale(hq, "cash", product, "3", today));
        assertThat(expected(ALL, today).get("cash_expected_minor").asLong()).isEqualTo(4_500);
        assertThat(cb.voidIt("/sales/" + sameDay, "Customer left", ALL).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode netted = expected(ALL, today);
        // Counted on its own day, reversed on the void day: the same day, so it nets to zero.
        assertThat(netted.get("cash_takings_minor").asLong()).isEqualTo(4_500);
        assertThat(netted.get("cash_sale_voids_minor").asLong()).isEqualTo(4_500);
        assertThat(netted.get("cash_expected_minor").asLong()).isZero();

        UUID earlier = RetailTestSupport.id(cb.sale(hq, "cash", product, "2", day));
        assertThat(expected(ALL, day).get("cash_expected_minor").asLong()).isEqualTo(3_000);
        cb.voidIt("/sales/" + earlier, "Wrong customer", ALL);
        // The earlier day is not rewritten; the void day carries the reversal.
        assertThat(expected(ALL, day).get("cash_expected_minor").asLong()).isEqualTo(3_000);
        JsonNode voidDay = expected(ALL, today);
        assertThat(voidDay.get("cash_sale_voids_minor").asLong()).isEqualTo(4_500 + 3_000);
        assertThat(voidDay.get("cash_expected_minor").asLong()).isEqualTo(-3_000);
    }

    @Test
    void aCashRestockLowersTheNetExpectedAmountButNeverACashiersFigure() {
        aDayOfEveryKind(day);
        long before = expected(CASHIER, day).get("cash_expected_minor").asLong();

        assertThat(cb.restock(hq, product, 1_000, 1_500, "5", "cash", day).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        JsonNode cashier = expected(CASHIER, day);
        assertThat(cashier.get("cash_expected_minor").asLong())
                .isEqualTo(before)
                .isEqualTo(15_000);
        assertThat(cashier.has("cash_purchases_minor")).isFalse();
        assertThat(cashier.has("expected_minor")).isFalse();
        assertThat(cashier.has("savings_minor")).isFalse();
        JsonNode admin = expected(ALL, day);
        assertThat(admin.get("cash_purchases_minor").asLong()).isEqualTo(5_000);
        assertThat(admin.get("expected_minor").asLong()).isEqualTo(10_000);
        // A bank restock is not cash.
        cb.restock(hq, product, 1_000, 1_500, "5", "bank", day);
        assertThat(expected(ALL, day).get("cash_purchases_minor").asLong()).isEqualTo(5_000);
    }

    @Test
    void aCashPaymentOnACreditSaleCountsOnItsPaidOnDay() {
        UUID credit = RetailTestSupport.id(cb.sale(hq, "credit", product, "4", day));
        assertThat(expected(ALL, day).get("cash_expected_minor").asLong()).isZero();

        assertThat(cb.pay(credit, 2_000, "cash", daysAgo(1)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cb.pay(credit, 500, "mobile_money", daysAgo(1)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        assertThat(expected(ALL, day).get("cash_expected_minor").asLong()).isZero();
        assertThat(expected(ALL, daysAgo(1)).get("cash_expected_minor").asLong())
                .isEqualTo(2_000);
    }

    @Test
    void savingsExpensesAdvancesAndCashRepaymentsMoveTheExpectedAmount() {
        aDayOfEveryKind(day);
        JsonNode s = cb.get("/savings/suggestion?branch_id=" + hq + "&date=" + day, ALL)
                .getBody();
        Map<String, Object> savings = new LinkedHashMap<>();
        savings.put("branch_id", hq);
        savings.put("business_date", day.toString());
        savings.put("suggestion_token", s.get("suggestion_token").asString());
        long saved = s.get("suggested_minor").asLong();
        assertThat(cb.post("/savings", savings, ALL).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID[] list = cb.categoryWithItem("Premises", "Shop rent", false);
        cb.expense(hq, list[0], list[1], 2_000, day);
        UUID owner = cb.party("Test Owner 01", "owner");
        Map<String, Object> adv = new LinkedHashMap<>();
        adv.put("branch_id", hq);
        adv.put("business_date", day.toString());
        adv.put("party_id", owner);
        adv.put("principal_minor", 1_000);
        UUID advance = RetailTestSupport.id(cb.post("/advances", adv, ALL));
        assertThat(cb.post(
                                "/advances/" + advance + "/repayments",
                                Map.of(
                                        "branch_id",
                                        hq,
                                        "amount_minor",
                                        400,
                                        "method",
                                        "cash",
                                        "paid_on",
                                        day.toString()),
                                ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(cb.post(
                                "/advances/" + advance + "/repayments",
                                Map.of(
                                        "branch_id",
                                        hq,
                                        "amount_minor",
                                        100,
                                        "method",
                                        "bank",
                                        "paid_on",
                                        day.toString()),
                                ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        JsonNode e = expected(ALL, day);

        // 15,000 takings less 2,000 expense, 1,000 advance out, plus 400 cash back (the bank repayment is not cash).
        assertThat(e.get("cash_expected_minor").asLong()).isEqualTo(15_000 - 2_000 - 1_000 + 400);
        assertThat(e.get("savings_minor").asLong()).isEqualTo(saved);
        assertThat(e.get("expected_minor").asLong()).isEqualTo(15_000 - 2_000 - 1_000 + 400 - saved);
        assertThat(expected(CASHIER, day).get("cash_expected_minor").asLong()).isEqualTo(12_400);
        assertThat(expected(CASHIER, day).has("savings_minor")).isFalse();
    }

    @Test
    void theFourFlagsAndTheTolerance() {
        aDayOfEveryKind(day);
        assertThat(bank(10_000, day, ALL).getBody().get("flag").asString()).isEqualTo("shortfall");
        assertThat(bank(5_000, day, ALL).getBody().get("flag").asString()).isEqualTo("ok");
        assertThat(bank(2_000, day, ALL).getBody().get("flag").asString()).isEqualTo("surplus");

        LocalDate other = daysAgo(2);
        cb.sale(hq, "cash", product, "2", other);
        JsonNode notBanked = report(ALL, daysAgo(2), daysAgo(2)).get("items").get(0);
        assertThat(notBanked.get("flag").asString()).isEqualTo("not_banked");

        cb.setting("retail.cashbook.tolerance_minor", 500);
        assertThat(bank(2_800, other, ALL).getBody().get("flag").asString()).isEqualTo("ok");
        LocalDate third = daysAgo(1);
        cb.sale(hq, "cash", product, "2", third);
        assertThat(bank(2_000, third, ALL).getBody().get("flag").asString()).isEqualTo("shortfall");
    }

    JsonNode report(String perms, LocalDate from, LocalDate to) {
        ResponseEntity<JsonNode> r =
                cb.get("/reports/cash/banking?branch_id=" + hq + "&from=" + from + "&to=" + to, perms);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    void twoPartialDepositsSumInTheReportAndBankingAboveTheCashIsAcceptedAndFlagged() {
        cb.sale(hq, "cash", product, "10", day);
        assertThat(bank(9_000, day, ALL).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> second = bank(6_000, day, ALL);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().get("flag").asString()).isEqualTo("ok");

        JsonNode row = report(ALL, day, day).get("items").get(0);
        assertThat(row.get("banked_minor").asLong()).isEqualTo(15_000);
        assertThat(row.get("entries").size()).isEqualTo(2);
        assertThat(row.get("entries").get(0).has("by")).isTrue();

        // More than the cash held: accepted, never refused, flagged for the profit reader only.
        ResponseEntity<JsonNode> over = bank(50_000, day, ALL);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(over.getBody().get("warnings").get(0).asString()).isEqualTo("cash_below_banked");
        assertThat(over.getBody().get("flag").asString()).isEqualTo("surplus");
        ResponseEntity<JsonNode> cashier = bank(40_000, day, CASHIER);
        assertThat(cashier.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cashier.getBody().has("warnings")).isFalse();
        assertThat(cashier.getBody().has("flag")).isFalse();
        assertThat(cashier.getBody().has("difference_minor")).isFalse();
        assertThat(cashier.getBody().has("expected_minor")).isFalse();
    }

    @Test
    void theStoredExpectedFigureDoesNotMoveWhenABackDatedSaleChangesTheReport() {
        cb.sale(hq, "cash", product, "10", day);
        UUID id = RetailTestSupport.id(bank(15_000, day, ALL));

        cb.sale(hq, "cash", product, "2", day);

        JsonNode stored = cb.get("/bankings", ALL).getBody().get("items").get(0);
        assertThat(stored.get("id").asString()).isEqualTo(id.toString());
        assertThat(stored.get("expected_minor").asLong()).isEqualTo(15_000);
        JsonNode row = report(ALL, day, day).get("items").get(0);
        assertThat(row.get("expected_minor").asLong()).isEqualTo(18_000);
        assertThat(row.get("flag").asString()).isEqualTo("shortfall");
    }

    @Test
    void theRunningUnbankedTotalIsTheSumOfExpectedLessBankedFromTheFirstLiveDay() {
        LocalDate d1 = daysAgo(4);
        LocalDate d2 = daysAgo(3);
        LocalDate d3 = daysAgo(2);
        cb.sale(hq, "cash", product, "10", d1);
        cb.sale(hq, "cash", product, "4", d2);
        cb.sale(hq, "cash", product, "2", d3);
        bank(15_000, d1, ALL);
        bank(4_000, d2, ALL);

        JsonNode items = report(ALL, d1, d3).get("items");

        // Newest first: d3 owes 3,000 on top of d2's 2,000.
        assertThat(items.get(0).get("business_date").asString()).isEqualTo(d3.toString());
        assertThat(items.get(0).get("unbanked_running_minor").asLong()).isEqualTo(5_000);
        assertThat(items.get(1).get("unbanked_running_minor").asLong()).isEqualTo(2_000);
        assertThat(items.get(2).get("unbanked_running_minor").asLong()).isZero();
        // A window that starts later still carries the earlier days into the total.
        assertThat(report(ALL, d3, d3)
                        .get("items")
                        .get(0)
                        .get("unbanked_running_minor")
                        .asLong())
                .isEqualTo(5_000);
        // A cashier sees the takings figure and what was banked, none of the net figures.
        JsonNode cashierRow = report(CASHIER, d1, d3).get("items").get(0);
        assertThat(cashierRow.get("cash_expected_minor").asLong()).isEqualTo(3_000);
        assertThat(cashierRow.has("expected_minor")).isFalse();
        assertThat(cashierRow.has("unbanked_running_minor")).isFalse();
        assertThat(cashierRow.has("flag")).isFalse();
        assertThat(cashierRow.has("difference_minor")).isFalse();
    }

    @Test
    void importedDaysAreListedApartAndDoNotMoveTheRunningTotal() {
        LocalDate old = daysAgo(10);
        // An imported cash sale: a historical row with no journal, as the importer writes it.
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, currency,
                            total_minor, cost_total_minor, paid_minor, status, historical, created_by)
                        VALUES (gen_random_uuid(), ?, ?, 'RSHIST0001', ?, 'cash', 'UGX', 9000, 6000, 9000, 'completed', true,
                            '00000000-0000-0000-0000-000000000000')
                        """)
                .params(t.tenantId(), hq, java.sql.Date.valueOf(old))
                .update();
        cb.sale(hq, "cash", product, "2", day);

        JsonNode items = report(ALL, old, day).get("items");

        assertThat(items.size()).isEqualTo(2);
        JsonNode live = items.get(0);
        JsonNode imported = items.get(1);
        assertThat(imported.get("historical").asBoolean()).isTrue();
        assertThat(imported.has("unbanked_running_minor")).isFalse();
        assertThat(imported.get("cash_expected_minor").asLong()).isEqualTo(9_000);
        assertThat(live.get("unbanked_running_minor").asLong()).isEqualTo(3_000);
    }

    @Test
    void aVoidedBankingPostsTheReversalAndNeedsTheVoidPermission() {
        cb.sale(hq, "cash", product, "10", day);
        UUID id = RetailTestSupport.id(bank(15_000, day, ALL));
        assertThat(cb.voidIt("/bankings/" + id, "Wrong slip", CASHBOOK_SALES).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(cb.voidIt("/bankings/" + id, "Wrong slip", ALL).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(cb.accountMovement("bank", hq)).isZero();
        assertThat(cb.voidIt("/bankings/" + id, "Again", ALL)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("cash_record_voided");
        assertThat(cb.get("/bankings", ALL).getBody().get("items")).isEmpty();
    }

    @Test
    void withdrawalsPostBankToCashWarnBelowZeroAndAreAdminOnly() {
        cb.sale(hq, "cash", product, "10", day);
        bank(10_000, day, ALL);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("business_date", day.toString());
        body.put("amount_minor", 4_000);
        body.put("purpose", "Float for change");

        assertThat(cb.post("/withdrawals", body, CASHBOOK_SALES).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> ok = cb.post("/withdrawals", body, ALL);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody().get("warnings")).isEmpty();
        assertThat(cb.accountMovement("bank", hq)).isEqualTo(6_000);

        body.put("amount_minor", 7_000);
        ResponseEntity<JsonNode> over = cb.post("/withdrawals", body, ALL);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(over.getBody().get("warnings").get(0).asString()).isEqualTo("bank_balance_negative");

        assertThat(cb.voidIt("/withdrawals/" + RetailTestSupport.id(over), "Typed wrong", ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.accountMovement("bank", hq)).isEqualTo(6_000);
        assertThat(cb.get("/withdrawals", CASHBOOK_SALES).getBody().get("items").size())
                .isEqualTo(1);
    }

    @Test
    void aWithdrawalWithNoBranchTakesTheCallersOneBranchElseTheHeadOffice() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", 1_000);
        ResponseEntity<JsonNode> all = cb.post("/withdrawals", body, ALL);
        assertThat(all.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(all.getBody().get("branch_id").asString()).isEqualTo(hq.toString());

        ResponseEntity<JsonNode> one =
                cb.post("/withdrawals", body, ALL, t.secondBranch().toString());
        assertThat(one.getBody().get("branch_id").asString())
                .isEqualTo(t.secondBranch().toString());

        ResponseEntity<JsonNode> two = cb.post("/withdrawals", body, ALL, hq + "," + t.secondBranch());
        assertThat(two.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(two.getBody().get("branch_id").asString()).isEqualTo(hq.toString());
    }

    @Test
    void aBranchOutsideTheCallersScopeIsRefusedOnEveryRoute() {
        String second = t.secondBranch().toString();
        assertThat(cb.get("/bankings/expected?branch_id=" + hq, ALL, second).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", hq);
        b.put("amount_minor", 1_000);
        assertThat(cb.post("/bankings", b, ALL, second).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        cb.sale(hq, "cash", product, "1", day);
        assertThat(report(ALL, day, day).get("items").size()).isEqualTo(1);
        ResponseEntity<JsonNode> scoped = cb.get("/reports/cash/banking?from=" + day + "&to=" + day, ALL, second);
        assertThat(scoped.getBody().get("items")).isEmpty();
        assertThat(cb.get("/reports/cash/daily?branch_id=" + hq + "&from=" + day + "&to=" + day, ALL, second)
                        .getBody()
                        .get("items"))
                .isEmpty();
    }

    @Test
    void aUserWithoutTheReadPermissionGets403OnTheReports() {
        assertThat(cb.get("/reports/cash/banking", "retail.sale.read").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(cb.get("/reports/cash/daily", "retail.sale.read").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}
