package com.rincoltech.bms.retail.cashbook;

import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.lending.loans.LoanFixtures;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * FR-GL-08 for retail (issue #236, ADR-105): retail's sales, purchases and cash book postings go to the account the
 * tenant maps the payment method {@code cash} or {@code bank} to, at the posting's branch, and the cash book reads that
 * account. A tenant that never remapped posts to exactly the seeded accounts it always did; a tenant that remaps cash
 * keeps its retail and lending cash on one till that the cash book shows. One item costs 1,000 and sells at 1,500. All
 * names and amounts are fabricated.
 */
class RetailPaymentMethodsIT extends LoanFixtures {

    static final String SAVINGS_ACCOUNTS = "/api/v1/lending/savings-accounts";

    CashbookTestSupport cb;
    UUID hq;
    UUID item;
    LocalDate today;

    @Override
    @BeforeEach
    protected void setUp() {
        t = TestDatabase.tenant("retail-pm", true, true);
        officer = UUID.randomUUID();
        officerPerms = permissionsOf("loan_officer");
        managerPerms = permissionsOf("branch_manager");
        adminPerms = permissionsOf("tenant_admin");
        cb = new CashbookTestSupport(http, t);
        hq = t.headOffice();
        item = cb.api.product("CABLE-2MM", 1_000, 1_500);
        cb.api.stockUp(hq, item, "100");
        today = LocalDate.now(BusinessClock.DEFAULT_ZONE);
    }

    /** A lending savings account at the head office, then a cash deposit into it: lending cash through FR-GL-08. */
    ResponseEntity<JsonNode> lendingCashDeposit(long amount) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("name", "Test savings none");
        terms.put("interest_rate_bp", 0);
        terms.put("interest_calc", "none");
        terms.put("interest_posting", "monthly");
        String product = send(
                        HttpMethod.POST,
                        "/api/v1/lending/savings-products",
                        as(UUID.randomUUID(), adminPerms, "*", null),
                        Map.of(
                                "code",
                                "SAV"
                                        + UUID.randomUUID()
                                                .toString()
                                                .substring(0, 4)
                                                .toUpperCase(java.util.Locale.ROOT),
                                "terms",
                                terms))
                .getBody()
                .get("id")
                .asString();
        String member = member("Test Saver 01", "+256700000201", hq, true);
        String account = asOfficer(
                        HttpMethod.POST, SAVINGS_ACCOUNTS, null, Map.of("member_id", member, "product_id", product))
                .getBody()
                .get("id")
                .asString();
        HttpHeaders cashier = as(UUID.randomUUID(), permissionsOf("cashier"), "*", null);
        cashier.add("Idempotency-Key", CashbookTestSupport.key());
        return send(
                HttpMethod.POST,
                SAVINGS_ACCOUNTS + "/" + account + "/deposits",
                cashier,
                Map.of("amount_minor", amount, "payment_method_key", "cash"));
    }

    ResponseEntity<JsonNode> bank(long amount) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", hq);
        b.put("business_date", today.toString());
        b.put("amount_minor", amount);
        return cb.post("/bankings", b, ALL);
    }

    /** Debit less credit of one account in one branch, over every entry. */
    static long movement(UUID account, UUID branch) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                         WHERE l.account_id = ? AND e.branch_id = ?
                        """)
                .params(account, branch)
                .query(Long.class)
                .single();
    }

    JsonNode cashDay(UUID branch) {
        return cashDay(branch, today);
    }

    JsonNode cashDay(UUID branch, LocalDate on) {
        ResponseEntity<JsonNode> r =
                cb.get("/reports/cash/daily?branch_id=" + branch + "&from=" + on + "&to=" + on, ALL);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody().get("items").get(0);
    }

    @Test
    void frGl08_aTenantThatNeverRemappedPostsRetailToTheSeededAccountsAsBefore() {
        assertThat(cb.sale(hq, "cash", item, "10", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cb.sale(hq, "bank", item, "2", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cb.sale(hq, "mobile_money", item, "1", today).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(cb.restock(hq, item, 1_000, 1_500, "3", "cash", today).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(bank(4_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> deposit = lendingCashDeposit(20_000);
        assertThat(deposit.getStatusCode()).as("%s", deposit.getBody()).isEqualTo(HttpStatus.CREATED);

        // Cash: 15,000 sold, 3,000 restock paid, 4,000 banked, 20,000 lending deposit. Bank: 3,000 sold, 4,000 banked.
        assertThat(movement(t.account("cash_on_hand"), hq)).isEqualTo(15_000 - 3_000 - 4_000 + 20_000);
        assertThat(movement(t.account("bank"), hq)).isEqualTo(3_000 + 4_000);
        assertThat(movement(t.account("mobile_money"), hq)).isEqualTo(1_500);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM payment_methods WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();

        JsonNode day = cashDay(hq);
        assertThat(day.get("closing_minor").asLong()
                        + day.get("other_movements_minor").asLong())
                .isEqualTo(cb.cashOnHand(hq, today));
        assertThat(day.get("other_movements_minor").asLong())
                .as("the lending deposit")
                .isEqualTo(20_000);
    }

    @Test
    void frGl08_withCashRemappedRetailAndLendingCashLandOnOneTillThatTheCashBookShows() {
        String till = assetAccount("1091", "Test shared till");
        ResponseEntity<JsonNode> mapped = mapMethod("cash", null, till, null);
        assertThat(mapped.getStatusCode()).as("%s", mapped.getBody()).isEqualTo(HttpStatus.OK);
        UUID tillId = UUID.fromString(till);

        assertThat(cb.sale(hq, "cash", item, "10", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cb.restock(hq, item, 1_000, 1_500, "3", "cash", today).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> deposit = lendingCashDeposit(20_000);
        assertThat(deposit.getStatusCode()).as("%s", deposit.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> banked = bank(30_000);
        assertThat(banked.getStatusCode()).as("%s", banked.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(banked.getBody().path("warnings"))
                .as("the banking check reads the mapped till, which holds the lending cash too")
                .isEmpty();

        assertThat(movement(tillId, hq)).isEqualTo(15_000 - 3_000 + 20_000 - 30_000);
        assertThat(movement(t.account("cash_on_hand"), hq))
                .as("nothing is left on the seeded till")
                .isZero();
        assertThat(movement(t.account("bank"), hq)).isEqualTo(30_000);

        JsonNode day = cashDay(hq);
        assertThat(day.get("cash_takings_minor").asLong()).isEqualTo(15_000);
        assertThat(day.get("other_movements_minor").asLong())
                .as("the lending deposit")
                .isEqualTo(20_000);
        assertThat(day.get("closing_minor").asLong()
                        + day.get("other_movements_minor").asLong())
                .isEqualTo(movement(tillId, hq));

        // A branch's own mapping wins at that branch; the head office keeps the tenant's.
        String branchTill = assetAccount("1092", "Test branch two till");
        assertThat(mapMethod("cash", t.secondBranch(), branchTill, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        cb.api.stockUp(t.secondBranch(), item, "10");
        assertThat(cb.sale(t.secondBranch(), "cash", item, "2", today).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(movement(UUID.fromString(branchTill), t.secondBranch())).isEqualTo(3_000);
        assertThat(movement(tillId, t.secondBranch())).isZero();
        JsonNode branchDay = cashDay(t.secondBranch());
        assertThat(branchDay.get("closing_minor").asLong()).isEqualTo(3_000);
        assertThat(branchDay.get("other_movements_minor").asLong()).isZero();
        assertThat(cashDay(hq).get("closing_minor").asLong())
                .as("the head office does not see the branch's till")
                .isEqualTo(day.get("closing_minor").asLong());
    }

    /** JUSTIFICATION-A3: two new test cases the review of #677 asked for (history across a remap, the bank remapped). */
    @Test
    void frGl08_aRemapLeavesTheDaysBeforeItAsTheyReadAndKeepsTheirCashInTheTill() {
        LocalDate before = today.minusDays(1);
        assertThat(cb.sale(hq, "cash", item, "4", before).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode was = cashDay(hq, before);
        String till = assetAccount("1094", "Test new till");
        assertThat(mapMethod("cash", null, till, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cb.sale(hq, "cash", item, "2", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode todayWas = cashDay(hq);
        // A second remap, away from the till that took today's sale: no figure moves.
        assertThat(mapMethod("cash", null, assetAccount("1096", "Test newer till"), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(cashDay(hq, before)).as("the day before the remaps").isEqualTo(was);
        JsonNode day = cashDay(hq);
        assertThat(day).as("today after the second remap").isEqualTo(todayWas);
        assertThat(day.get("opening_minor").asLong()).isEqualTo(6_000);
        assertThat(day.get("closing_minor").asLong()).isEqualTo(9_000);
        assertThat(day.get("other_movements_minor").asLong()).isZero();
        ResponseEntity<JsonNode> banked = bank(9_000);
        assertThat(banked.getBody().path("warnings"))
                .as("the cash on the seeded till before the remap still counts")
                .isEmpty();
    }

    @Test
    void frGl08_withBankRemappedRetailBankMoneyLandsOnTheMappedBankAndItsBalanceWarns() {
        String bankAccount = assetAccount("1095", "Test business bank");
        UUID bankId = UUID.fromString(bankAccount);
        assertThat(mapMethod("bank", null, bankAccount, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cb.sale(hq, "bank", item, "2", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cb.sale(hq, "cash", item, "10", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(bank(10_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(movement(bankId, hq)).isEqualTo(3_000 + 10_000);
        assertThat(movement(t.account("bank"), hq)).isZero();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("business_date", today.toString());
        body.put("amount_minor", 12_000);
        body.put("purpose", "Float for change");
        assertThat(cb.post("/withdrawals", body, ALL).getBody().path("warnings"))
                .isEmpty();
        body.put("amount_minor", 2_000);
        assertThat(cb.post("/withdrawals", body, ALL)
                        .getBody()
                        .get("warnings")
                        .get(0)
                        .asString())
                .isEqualTo("bank_balance_negative");
        assertThat(movement(bankId, hq)).isEqualTo(13_000 - 14_000);
    }

    @Test
    void frGl08_cashSwitchedOffStillTakesRetailCashOnTheSeededTill() {
        String till = assetAccount("1093", "Test closed till");
        assertThat(mapMethod("cash", null, till, false).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(cb.sale(hq, "cash", item, "2", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(movement(t.account("cash_on_hand"), hq)).isEqualTo(3_000);
        assertThat(movement(UUID.fromString(till), hq)).isZero();
        assertThat(lendingCashDeposit(10_000).getBody().get("code").asString())
                .as("lending still refuses a method switched off")
                .isEqualTo("payment_method_unmapped");

        // Mapped again, then the mapped till is deactivated: retail falls back to the seeded till, never a dead
        // account.
        assertThat(mapMethod("cash", null, till, true).getStatusCode()).isEqualTo(HttpStatus.OK);
        TestDatabase.owner()
                .sql("UPDATE gl_accounts SET is_active = false WHERE id = ?::uuid")
                .param(till)
                .update();
        assertThat(cb.sale(hq, "cash", item, "2", today).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(movement(t.account("cash_on_hand"), hq)).isEqualTo(6_000);
        assertThat(movement(UUID.fromString(till), hq)).isZero();
    }
}
