package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
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
 * FR-RET-27 and FR-RET-29 (ADR-022 decision 9): the daily cash summary agrees with the ledger's cash
 * on hand at the end of every day, on the day a record is dated and on the later day it is voided,
 * with a cash purchase and with voids made for earlier days; and the expenses report totals the
 * rows. One product costs 1,000 and sells at 1,500. All names and amounts are fabricated.
 */
class RetailCashbookSummaryIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    CashbookTestSupport cb;
    UUID hq;
    UUID product;
    UUID owner;
    UUID[] rent;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("cb-sum", false, true);
        cb = new CashbookTestSupport(http, t);
        hq = t.headOffice();
        product = cb.api.product("CABLE-2MM", 1_000, 1_500);
        cb.api.stockUp(hq, product, "300");
        owner = cb.party("Test Owner 01", "owner");
        rent = cb.categoryWithItem("Premises", "Shop rent", false);
    }

    JsonNode daily(String perms, LocalDate from, LocalDate to) {
        ResponseEntity<JsonNode> r =
                cb.get("/reports/cash/daily?branch_id=" + hq + "&from=" + from + "&to=" + to, perms);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode row(JsonNode report, LocalDate day) {
        for (JsonNode r : report.get("items")) {
            if (r.get("business_date").asString().equals(day.toString())) {
                return r;
            }
        }
        throw new AssertionError("no row for " + day + " in " + report);
    }

    UUID saving(LocalDate on) {
        JsonNode s = cb.get("/savings/suggestion?branch_id=" + hq + "&date=" + on, ALL)
                .getBody();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", hq);
        b.put("business_date", on.toString());
        b.put("suggestion_token", s.get("suggestion_token").asString());
        return RetailTestSupport.id(cb.post("/savings", b, ALL));
    }

    UUID bank(long amount, LocalDate on) {
        return RetailTestSupport.id(cb.post(
                "/bankings", Map.of("branch_id", hq, "business_date", on.toString(), "amount_minor", amount), ALL));
    }

    /** The whole fixture: a day of every kind, then a later day of voids made for the earlier days. */
    record Ids(UUID sale, UUID savings, UUID expense, UUID banking2, UUID withdrawal, UUID repayment, UUID advance) {}

    Ids theDays(LocalDate d1, LocalDate d2) {
        cb.sale(hq, "cash", product, "10", d1);
        UUID sale = RetailTestSupport.id(cb.sale(hq, "cash", product, "2", d1));
        assertThat(cb.restock(hq, product, 1_000, 1_500, "5", "cash", d1).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        UUID savings = saving(d1);
        UUID expense = RetailTestSupport.id(cb.expense(hq, rent[0], rent[1], 2_000, d1));
        Map<String, Object> adv = new LinkedHashMap<>();
        adv.put("branch_id", hq);
        adv.put("business_date", d1.toString());
        adv.put("party_id", owner);
        adv.put("principal_minor", 1_000);
        UUID advance = RetailTestSupport.id(cb.post("/advances", adv, ALL));
        bank(3_000, d1);
        UUID withdrawal = RetailTestSupport.id(cb.post(
                "/withdrawals", Map.of("branch_id", hq, "business_date", d1.toString(), "amount_minor", 500), ALL));
        cb.sale(hq, "cash", product, "4", d2);
        UUID repayment = RetailTestSupport.id(cb.post(
                "/advances/" + advance + "/repayments",
                Map.of("branch_id", hq, "amount_minor", 400, "method", "cash", "paid_on", d2.toString()),
                ALL));
        UUID banking2 = bank(2_000, d2);
        return new Ids(sale, savings, expense, banking2, withdrawal, repayment, advance);
    }

    @Test
    void closingEqualsTheLedgerOnTheRecordDaysAndOnTheLaterDayTheVoidsAreMade() {
        LocalDate d1 = daysAgo(6);
        LocalDate d2 = daysAgo(5);
        LocalDate today = LocalDate.now();
        Ids ids = theDays(d1, d2);
        long savedOnD1 = TestDatabase.owner()
                .sql("SELECT amount_minor FROM retail_daily_savings WHERE id = ?")
                .param(ids.savings())
                .query(Long.class)
                .single();

        // Before any void: both days agree with the ledger and nothing is unexplained.
        JsonNode before = daily(ALL, d1, d2);
        for (LocalDate d : new LocalDate[] {d1, d2}) {
            JsonNode r = row(before, d);
            assertThat(r.get("closing_minor").asLong()).as("closing %s", d).isEqualTo(cb.cashOnHand(hq, d));
            assertThat(r.get("other_movements_minor").asLong()).isZero();
            assertThat(r.get("ledger_basis").asBoolean()).isTrue();
        }
        JsonNode first = row(before, d1);
        assertThat(first.get("opening_minor").asLong()).isZero();
        assertThat(first.get("cash_takings_minor").asLong()).isEqualTo(18_000);
        assertThat(first.get("cash_purchases_minor").asLong()).isEqualTo(5_000);
        assertThat(first.get("savings_minor").asLong()).isEqualTo(savedOnD1).isEqualTo(3_000);
        assertThat(first.get("expenses_minor").asLong()).isEqualTo(2_000);
        assertThat(first.get("advances_out_minor").asLong()).isEqualTo(1_000);
        assertThat(first.get("banked_minor").asLong()).isEqualTo(3_000);
        assertThat(first.get("withdrawals_in_minor").asLong()).isEqualTo(500);
        assertThat(first.get("closing_minor").asLong()).isEqualTo(18_000 - 5_000 - 3_000 - 2_000 - 1_000 - 3_000 + 500);
        assertThat(row(before, d2).get("opening_minor").asLong())
                .isEqualTo(first.get("closing_minor").asLong());

        // The voids, all made today for records of the earlier days.
        assertThat(cb.voidIt("/sales/" + ids.sale(), "Customer returned it", ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.voidIt("/savings/" + ids.savings(), "Entered for the wrong shop", ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.voidIt("/expenses/" + ids.expense(), "Entered twice", ALL).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.voidIt("/bankings/" + ids.banking2(), "Wrong slip", ALL).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.voidIt("/withdrawals/" + ids.withdrawal(), "Typed twice", ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.voidIt("/advances/" + ids.advance() + "/repayments/" + ids.repayment(), "Wrong advance", ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(cb.voidIt("/advances/" + ids.advance(), "Not given after all", ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode after = daily(ALL, d1, today);
        // A past day is never rewritten: the record days still read as they did and still agree.
        for (LocalDate d : new LocalDate[] {d1, d2}) {
            JsonNode r = row(after, d);
            assertThat(r.get("closing_minor").asLong()).as("closing %s", d).isEqualTo(cb.cashOnHand(hq, d));
            assertThat(r.get("other_movements_minor").asLong()).isZero();
        }
        assertThat(row(after, d1).get("cash_takings_minor").asLong()).isEqualTo(18_000);
        assertThat(row(after, d1).get("expenses_minor").asLong()).isEqualTo(2_000);
        // The void day carries every reversal as its own line and also agrees with the ledger.
        JsonNode voidDay = row(after, today);
        assertThat(voidDay.get("cash_sale_voids_minor").asLong()).isEqualTo(3_000);
        assertThat(voidDay.get("savings_voids_minor").asLong()).isEqualTo(savedOnD1);
        assertThat(voidDay.get("expense_voids_minor").asLong()).isEqualTo(2_000);
        assertThat(voidDay.get("advance_voids_minor").asLong()).isEqualTo(1_000);
        assertThat(voidDay.get("repayment_voids_minor").asLong()).isEqualTo(400);
        assertThat(voidDay.get("banking_voids_minor").asLong()).isEqualTo(2_000);
        assertThat(voidDay.get("withdrawal_voids_minor").asLong()).isEqualTo(500);
        assertThat(voidDay.get("closing_minor").asLong()).isEqualTo(cb.cashOnHand(hq, today));
        assertThat(voidDay.get("other_movements_minor").asLong()).isZero();
        assertThat(voidDay.get("opening_minor").asLong()).isEqualTo(cb.cashOnHand(hq, today.minusDays(1)));
    }

    @Test
    void aManualJournalToCashShowsAsOtherMovementsAndIsNeverHidden() {
        LocalDate d1 = daysAgo(4);
        cb.sale(hq, "cash", product, "2", d1);
        UUID period = TestDatabase.owner()
                .sql("SELECT id FROM gl_periods WHERE tenant_id = ? AND year = ? AND month = ?")
                .params(t.tenantId(), d1.getYear(), d1.getMonthValue())
                .query(UUID.class)
                .single();
        TestDatabase.owner()
                .sql("""
                        WITH e AS (INSERT INTO journal_entries (id, tenant_id, branch_id, entry_no, entry_date, period_id,
                                       reference, source_module, source_type)
                                   VALUES (gen_random_uuid(), ?, ?, 'JE90000001', ?, ?, 'MANUAL-TEST', 'test', 'fixture')
                                   RETURNING id)
                        INSERT INTO journal_lines (id, tenant_id, entry_id, line_no, account_id, debit, credit, currency)
                        SELECT gen_random_uuid(), ?, e.id, 1, ?, 700, 0, 'UGX' FROM e
                        UNION ALL SELECT gen_random_uuid(), ?, e.id, 2, ?, 0, 700, 'UGX' FROM e
                        """)
                .params(
                        t.tenantId(),
                        hq,
                        java.sql.Date.valueOf(d1),
                        period,
                        t.tenantId(),
                        t.account("cash_on_hand"),
                        t.tenantId(),
                        t.account("opening_balance_equity"))
                .update();

        JsonNode r = row(daily(ALL, d1, d1), d1);

        assertThat(r.get("other_movements_minor").asLong()).isEqualTo(700);
        assertThat(r.get("closing_minor").asLong()
                        + r.get("other_movements_minor").asLong())
                .isEqualTo(cb.cashOnHand(hq, d1));
    }

    @Test
    void anImportedDayHasNoLedgerBasisAndNoOpeningOrClosing() {
        LocalDate old = daysAgo(20);
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, currency,
                            total_minor, cost_total_minor, paid_minor, status, historical, created_by)
                        VALUES (gen_random_uuid(), ?, ?, 'RSHIST0002', ?, 'cash', 'UGX', 9000, 6000, 9000, 'completed', true,
                            '00000000-0000-0000-0000-000000000000')
                        """)
                .params(t.tenantId(), hq, java.sql.Date.valueOf(old))
                .update();

        JsonNode r = row(daily(ALL, old, old), old);

        assertThat(r.get("ledger_basis").asBoolean()).isFalse();
        assertThat(r.get("historical").asBoolean()).isTrue();
        assertThat(r.get("cash_takings_minor").asLong()).isEqualTo(9_000);
        assertThat(r.has("opening_minor")).isFalse();
        assertThat(r.has("closing_minor")).isFalse();
        assertThat(r.has("other_movements_minor")).isFalse();
        assertThat(r.has("unbanked_running_minor")).isFalse();
    }

    @Test
    void aCashiersSummaryCarriesNoCostProfitOrSavingsFigureAndIgnoresACashRestock() {
        LocalDate d1 = daysAgo(3);
        cb.sale(hq, "cash", product, "10", d1);
        saving(d1);
        JsonNode without = row(daily(CASHIER, d1, d1), d1);
        cb.restock(hq, product, 1_000, 1_500, "5", "cash", d1);

        JsonNode withRestock = row(daily(CASHIER, d1, d1), d1);

        assertThat(withRestock.get("cash_expected_minor").asLong())
                .isEqualTo(without.get("cash_expected_minor").asLong())
                .isEqualTo(15_000);
        for (String field : new String[] {
            "cash_purchases_minor",
            "savings_minor",
            "savings_voids_minor",
            "opening_minor",
            "closing_minor",
            "other_movements_minor",
            "expected_to_bank_minor",
            "unbanked_running_minor",
            "daily_profit_minor"
        }) {
            assertThat(withRestock.has(field)).as("cashier sees %s", field).isFalse();
        }
        // The profit reader sees the components and the profit.
        JsonNode admin = row(daily(ALL, d1, d1), d1);
        assertThat(admin.get("cash_purchases_minor").asLong()).isEqualTo(5_000);
        assertThat(admin.get("savings_minor").asLong()).isEqualTo(2_500);
        assertThat(admin.get("daily_profit_minor").asLong()).isEqualTo(5_000);
        assertThat(admin.get("expected_to_bank_minor").asLong()).isEqualTo(15_000 - 5_000 - 2_500);
    }

    @Test
    void theExpensesReportTotalsTheRowsByCategoryAndItemAndExcludesVoids() {
        UUID[] fuel = cb.categoryWithItem("Transport", "Fuel", false);
        UUID repairs = RetailTestSupport.id(cb.post(
                "/expense-categories/" + fuel[0] + "/items",
                Map.of("name", "Repairs"),
                CashbookTestSupport.CASHBOOK_ADMIN));
        LocalDate d = daysAgo(2);
        cb.expense(hq, rent[0], rent[1], 3_000, d);
        cb.expense(hq, rent[0], rent[1], 2_000, d);
        cb.expense(hq, fuel[0], fuel[1], 1_000, d);
        cb.expense(hq, fuel[0], repairs, 4_000, d);
        UUID wrong = RetailTestSupport.id(cb.expense(hq, fuel[0], fuel[1], 9_000, d));
        cb.voidIt("/expenses/" + wrong, "Entered twice", ALL);
        String q = "?branch_id=" + hq + "&from=" + daysAgo(5) + "&to=" + LocalDate.now();

        JsonNode byCategory = cb.get("/reports/cash/expenses" + q, CASHIER).getBody();
        assertThat(byCategory.get("total_minor").asLong()).isEqualTo(10_000);
        assertThat(byCategory.get("count").asLong()).isEqualTo(4);
        assertThat(byCategory.get("items").get(0).get("label").asString()).isEqualTo("Premises");
        assertThat(byCategory.get("items").get(1).get("label").asString()).isEqualTo("Transport");
        assertThat(byCategory.get("items").get(0).get("total_minor").asLong()).isEqualTo(5_000);
        assertThat(byCategory.get("items").get(1).get("total_minor").asLong()).isEqualTo(5_000);

        JsonNode byItem =
                cb.get("/reports/cash/expenses" + q + "&group_by=item", CASHIER).getBody();
        assertThat(byItem.get("items").size()).isEqualTo(3);
        assertThat(byItem.get("items").get(0).get("label").asString()).isEqualTo("Premises / Shop rent");
        assertThat(byItem.get("items").get(0).get("total_minor").asLong()).isEqualTo(5_000);
        assertThat(byItem.get("items").get(1).get("label").asString()).isEqualTo("Transport / Repairs");
        assertThat(byItem.get("items").get(1).get("total_minor").asLong()).isEqualTo(4_000);
        assertThat(byItem.get("items").get(2).get("total_minor").asLong()).isEqualTo(1_000);

        JsonNode withVoids = cb.get("/reports/cash/expenses" + q + "&include_voided=true", CASHIER)
                .getBody();
        assertThat(withVoids.get("total_minor").asLong()).isEqualTo(19_000);
        assertThat(cb.get("/reports/cash/expenses" + q + "&group_by=month", CASHIER)
                        .getBody()
                        .get("items")
                        .size())
                .isBetween(1, 2);
        assertThat(cb.get("/reports/cash/expenses" + q + "&group_by=branch", CASHIER)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("label")
                        .asString())
                .isEqualTo("Head Office");
        assertThat(cb.get("/reports/cash/expenses?group_by=nonsense", CASHIER).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aRangeOverAYearIsRefused() {
        assertThat(cb.get("/reports/cash/daily?from=" + daysAgo(500) + "&to=" + LocalDate.now(), ALL)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
