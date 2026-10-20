package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The write-off of a credit sale whose buyer never pays (ADR-043, issue #340, slice 2). One product costs 600 and sells at
 * 1,000, so a sale of three pieces is 3,000 with a cost of 1,800. Everything is fabricated. Under {@code on_payment} the loss is the
 * unpaid share of the cost; under the default {@code on_sale} it is the whole unpaid amount; the entry always balances and clears
 * the buyer's trade debtors.
 */
class RetailSaleWriteOffIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    static final String MANAGER = ADMIN
            + ",retail.credit.manage,retail.credit.write_off,retail.sale.return,core.approvals.read,retail.savings.record,"
            + "retail.cashbook.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID product;

    static LocalDate today() {
        return LocalDate.now(BusinessClock.DEFAULT_ZONE);
    }

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-wo", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        product = api.product("WO-1", 600, 1_000);
        api.importedBalance(hq, product, "1000");
        setting(Map.of("returns", true));
    }

    // ---- helpers ---------------------------------------------------------------------------

    void setting(Map<String, Object> body) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                body,
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    UUID creditSale(int qty, int daysAgo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("sale_date", today().minusDays(daysAgo).toString());
        body.put("payment_method", "credit");
        body.put("buyer_name", "Test Buyer 01");
        body.put("lines", List.of(Map.of("product_id", product, "qty", String.valueOf(qty))));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    void pay(UUID sale, long amount, int daysAgo) {
        Map<String, Object> body = Map.of("amount_minor", amount, "method", "cash", "paid_on", today().toString());
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + sale + "/payments",
                body,
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isTrue();
        if (daysAgo > 0) {
            String id = r.getBody().get("payment").get("id").asString();
            LocalDate day = today().minusDays(daysAgo);
            TestDatabase.owner()
                    .sql("DO $$ BEGIN PERFORM set_config('bms.allow_mutation', 'on', true);"
                            + " UPDATE retail_sale_payments SET created_at = (TIMESTAMP '" + day
                            + " 12:00:00') AT TIME ZONE 'Africa/Kampala', paid_on = DATE '" + day + "' WHERE id = '"
                            + id
                            + "'; END $$")
                    .update();
        }
    }

    /** The rule "when the buyer pays" as for a tenant that switched long ago: it counts every sale of the test. */
    void fromTheStart() {
        setting(Map.of("credit_sale_recognition", "on_payment"));
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = jsonb_set(settings,"
                        + " '{module_groups,retail,credit_recognition_effective_from}', '\"1970-01-01\"'::jsonb)"
                        + " WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
    }

    /** The rule "when the buyer pays" starting {@code daysAgo} days ago. */
    void startedDaysAgo(int daysAgo) {
        setting(Map.of("credit_sale_recognition", "on_payment"));
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = jsonb_set(settings,"
                        + " '{module_groups,retail,credit_recognition_effective_from}', CAST(? AS jsonb))"
                        + " WHERE tenant_id = ?")
                .params("\"" + today().minusDays(daysAgo) + "\"", t.tenantId())
                .update();
    }

    @Test
    void aBackdatedSaleEnteredAfterTheStartIsWrittenOffWithItsCostShareAsTheLoss() {
        startedDaysAgo(2);
        // Entered today, dated three days ago: counted when the buyer pays, so its profit was never counted and the
        // loss is the
        // cost of the goods (600), not the whole 1,000.
        UUID s = creditSale(1, 3);
        JsonNode w = writeOffOk(s);
        assertThat(w.get("credit_sale_recognition").asString()).isEqualTo("on_payment");
        assertThat(w.get("loss_minor").asLong()).isEqualTo(600);
        assertThat(w.get("unpaid_minor").asLong()).isEqualTo(1_000);
    }

    ResponseEntity<JsonNode> writeOff(UUID sale, String reason, String perms) {
        return api.postKeyed(
                "/sales/" + sale + "/write-off",
                Map.of("reason", reason),
                perms,
                "*",
                UUID.randomUUID().toString());
    }

    JsonNode writeOffOk(UUID sale) {
        ResponseEntity<JsonNode> r = writeOff(sale, "Buyer left town", MANAGER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("executed").asBoolean()).isTrue();
        return r.getBody().get("write_off");
    }

    JsonNode profit() {
        return api.get("/reports/profit/daily?from=" + today().minusDays(5) + "&to=" + today(), ADMIN)
                .getBody();
    }

    JsonNode sale(UUID id) {
        return api.get("/sales/" + id, ADMIN).getBody();
    }

    /** Debit and credit of the entries of one write-off, by account system key. */
    Map<String, long[]> posted(UUID writeOff) {
        Map<String, long[]> out = new java.util.TreeMap<>();
        TestDatabase.owner()
                .sql("""
                        SELECT a.system_key, sum(l.debit) AS debit, sum(l.credit) AS credit FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.tenant_id = ? AND e.source_type = 'retail.sale_writeoff' AND e.source_id = ?
                         GROUP BY a.system_key
                        """)
                .params(t.tenantId(), writeOff)
                .query((rs, n) -> {
                    out.put(rs.getString("system_key"), new long[] {rs.getLong("debit"), rs.getLong("credit")});
                    return 0;
                })
                .list();
        return out;
    }

    long ledgerImbalance() {
        return TestDatabase.owner()
                .sql(
                        "SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l JOIN journal_entries e ON e.id = l.entry_id"
                                + " WHERE e.tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    long debtorsOf(UUID sale) {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = 'trade_debtors' AND l.subledger_id = ?
                        """)
                .params(t.tenantId(), sale)
                .query(Long.class)
                .single();
    }

    // ---- on_payment: the loss is the unpaid share of the cost -----------------------------------

    @Test
    void underOnPaymentTheLossIsTheUnpaidShareOfTheCostAndTheBooksBalance() {
        fromTheStart();
        UUID s = creditSale(3, 3);
        pay(s, 1_000, 2);
        assertThat(debtorsOf(s)).isEqualTo(2_000);

        JsonNode w = writeOffOk(s);
        assertThat(w.get("unpaid_minor").asLong()).isEqualTo(2_000);
        assertThat(w.get("loss_minor").asLong()).isEqualTo(1_200);
        assertThat(w.get("credit_sale_recognition").asString()).isEqualTo("on_payment");
        assertThat(w.get("written_off_on").asString()).isEqualTo(today().toString());

        Map<String, long[]> posted = posted(UUID.fromString(w.get("id").asString()));
        assertThat(posted.get("bad_debt_loss")[0]).isEqualTo(1_200);
        assertThat(posted.get("sales_revenue")[0])
                .as("the profit that was never counted")
                .isEqualTo(800);
        assertThat(posted.get("trade_debtors")[1]).isEqualTo(2_000);
        assertThat(debtorsOf(s)).as("the debt is cleared").isZero();
        assertThat(ledgerImbalance()).as("the accounts still balance").isZero();

        JsonNode after = sale(s);
        assertThat(after.get("balance_minor").asLong()).isZero();
        assertThat(after.get("status").asString()).isEqualTo("completed");
        assertThat(after.get("write_off").get("reason").asString()).isEqualTo("Buyer left town");
        assertThat(after.get("write_off").get("unpaid_minor").asLong()).isEqualTo(2_000);

        JsonNode report = profit();
        assertThat(report.get("on_credit_minor").asLong())
                .as("leaves On credit")
                .isZero();
        assertThat(report.get("bad_debt_minor").asLong()).isEqualTo(1_200);
        JsonNode todayRow = null;
        for (JsonNode r : report.get("rows")) {
            if (r.get("date").asString().equals(today().toString())) {
                todayRow = r;
            }
        }
        assertThat(todayRow.get("bad_debt_minor").asLong()).isEqualTo(1_200);
        assertThat(todayRow.get("profit_minor").asLong()).isEqualTo(-1_200);
        assertThat(report.get("profit_minor").asLong())
                .as("1,000 received less the 1,800 cost of the goods")
                .isEqualTo(-800);
        assertThat(api.get("/savings/suggestion?branch_id=" + hq + "&date=" + today(), MANAGER)
                        .getBody()
                        .get("suggested_minor")
                        .asLong())
                .as("a loss day suggests nothing")
                .isZero();
    }

    @Test
    void theSaleLeavesTheOwingAndOverdueLists() {
        UUID s = creditSale(2, 3);
        assertThat(api.get("/sales?owing=owing", ADMIN).getBody().get("items")).hasSize(1);
        assertThat(api.get("/sales?owing=overdue", ADMIN).getBody().get("items"))
                .isEmpty();
        writeOffOk(s);
        assertThat(api.get("/sales?owing=owing", ADMIN).getBody().get("items")).isEmpty();
        assertThat(api.get("/sales?owing=overdue", ADMIN).getBody().get("items"))
                .isEmpty();
    }

    /** A return of {@code qty} pieces credited to the buyer's balance; the credit note's id. */
    UUID returnToAccount(UUID sale, String qty) {
        UUID line = UUID.fromString(sale(sale).get("lines").get(0).get("id").asString());
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("sale_line_id", line);
        l.put("qty", qty);
        l.put("reason", "defective");
        l.put("disposition", "restock");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(l));
        body.put("refund_method", "account");
        body.put("money_method", "cash");
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + sale + "/returns",
                body,
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(r.getBody().get("sale_return").get("id").asString());
    }

    @Test
    void afterAReturnTheLossIsTheCostThatIsLeftLessWhatThePaymentsCarriedIn() {
        fromTheStart();
        UUID s = creditSale(3, 3);
        pay(s, 500, 2);
        returnToAccount(s, "1");
        // Left: 2 pieces worth 2,000 at a cost of 1,200. The 500 received carried in 500 x 1,200 / 2,000 = 300 of that
        // cost.
        JsonNode w = writeOffOk(s);
        assertThat(w.get("unpaid_minor").asLong()).isEqualTo(1_500);
        assertThat(w.get("loss_minor").asLong()).isEqualTo(900);
        Map<String, long[]> posted = posted(UUID.fromString(w.get("id").asString()));
        assertThat(posted.get("bad_debt_loss")[0]).isEqualTo(900);
        assertThat(posted.get("sales_revenue")[0]).isEqualTo(600);
        assertThat(posted.get("trade_debtors")[1]).isEqualTo(1_500);
        assertThat(ledgerImbalance()).isZero();
        assertThat(profit().get("profit_minor").asLong())
                .as("500 received less the 1,200 cost of the 2 pieces that stayed sold")
                .isEqualTo(-700);
    }

    @Test
    void aReturnOfAWrittenOffSaleCannotBeVoidedBack() {
        UUID s = creditSale(3, 2);
        UUID credit = returnToAccount(s, "1");
        writeOffOk(s);
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/returns/" + credit + "/void",
                Map.of("reason", "Customer changed their mind"),
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("sale_written_off");
        assertThat(sale(s).get("balance_minor").asLong()).isZero();
    }

    // ---- on_sale: the whole unpaid amount ---------------------------------------------------------

    @Test
    void underTheDefaultTheLossIsTheWholeUnpaidAmountAndRevenueIsLeftAlone() {
        UUID s = creditSale(3, 3);
        pay(s, 1_000, 2);
        JsonNode w = writeOffOk(s);
        assertThat(w.get("loss_minor").asLong()).isEqualTo(2_000);
        assertThat(w.get("credit_sale_recognition").asString()).isEqualTo("on_sale");
        Map<String, long[]> posted = posted(UUID.fromString(w.get("id").asString()));
        assertThat(posted.get("bad_debt_loss")[0]).isEqualTo(2_000);
        assertThat(posted).doesNotContainKey("sales_revenue");
        assertThat(ledgerImbalance()).isZero();
        JsonNode report = profit();
        assertThat(report.get("credit_sale_recognition").asString()).isEqualTo("on_sale");
        assertThat(report.get("bad_debt_minor").asLong()).isEqualTo(2_000);
        assertThat(report.get("profit_minor").asLong())
                .as("1,200 counted at the sale less the 2,000 lost")
                .isEqualTo(-800);
    }

    @Test
    void aSaleIsWrittenOffTheWayItWasCountedWhateverTheRuleIsLater() {
        UUID s = creditSale(3, 3);
        pay(s, 1_000, 2);
        JsonNode w = writeOffOk(s);
        assertThat(w.get("loss_minor").asLong())
                .as("counted at the sale: the whole unpaid amount")
                .isEqualTo(2_000);
        setting(Map.of("credit_sale_recognition", "on_payment"));
        JsonNode report = profit();
        assertThat(report.get("bad_debt_minor").asLong())
                .as("the sale is dated before the rule changed, so it keeps counting as it did")
                .isEqualTo(2_000);
        assertThat(report.get("profit_minor").asLong()).isEqualTo(-800);
        setting(Map.of("credit_sale_recognition", "on_sale"));
        assertThat(profit().get("bad_debt_minor").asLong()).isEqualTo(2_000);
    }

    @Test
    void aSaleMadeBeforeTheSwitchOnTheSameDayIsWrittenOffAsItWasCountedAndStaysSoAfterSwitchingBack() {
        UUID s = creditSale(3, 0);
        setting(Map.of("credit_sale_recognition", "on_payment"));
        JsonNode w = writeOffOk(s);
        assertThat(w.get("credit_sale_recognition").asString())
                .as("the switch starts tomorrow")
                .isEqualTo("on_sale");
        assertThat(w.get("loss_minor").asLong()).isEqualTo(3_000);
        assertThat(profit().get("bad_debt_minor").asLong()).isEqualTo(3_000);
        setting(Map.of("credit_sale_recognition", "on_sale"));
        assertThat(profit().get("bad_debt_minor").asLong()).isEqualTo(3_000);
        assertThat(profit().get("profit_minor").asLong())
                .as("1,200 at the sale less the 3,000 lost")
                .isEqualTo(-1_800);
    }

    @Test
    void aSaleCountedWhenTheBuyerPaysIsWrittenOffAtTheCostNeverCarriedIntoTheProfit() {
        fromTheStart();
        UUID s = creditSale(3, 0);
        pay(s, 1_000, 0);
        JsonNode w = writeOffOk(s);
        assertThat(w.get("loss_minor").asLong()).isEqualTo(1_200);
        assertThat(w.get("credit_sale_recognition").asString()).isEqualTo("on_payment");
        assertThat(profit().get("bad_debt_minor").asLong()).isEqualTo(1_200);
    }

    @Test
    void aPaymentOnAccountCannotBeReversedOnceTheSaleWasWrittenOff() {
        setting(Map.of("customer_accounts", true));
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 05"), ADMIN));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("sale_date", today().minusDays(1).toString());
        body.put("payment_method", "credit");
        body.put("customer_id", buyer);
        body.put("lines", List.of(Map.of("product_id", product, "qty", "3")));
        UUID s = RetailTestSupport.id(
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString()));
        ResponseEntity<JsonNode> receipt = api.postKeyed(
                "/customers/" + buyer + "/payments",
                Map.of("branch_id", hq, "amount_minor", 1_000, "method", "cash"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        UUID receiptId = RetailTestSupport.id(receipt);
        writeOffOk(s);
        ResponseEntity<JsonNode> undo = api.postKeyed(
                "/customers/" + buyer + "/payments/" + receiptId + "/reverse",
                Map.of("reason", "Entered by mistake"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(undo.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(undo.getBody().get("code").asString()).isEqualTo("payment_not_reversible");
        assertThat(sale(s).get("balance_minor").asLong()).isZero();
    }

    // ---- rules and permissions ----------------------------------------------------------------------

    @Test
    void itNeverDeletesAndRefusesTheWrongSales() {
        UUID s = creditSale(2, 1);
        assertThat(writeOff(s, "abc", MANAGER).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(writeOff(s, "     ab     ", MANAGER).getStatusCode())
                .as("blanks around a short reason do not make it long enough")
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(sale(s).has("write_off")).isFalse();
        writeOffOk(s);
        ResponseEntity<JsonNode> again = writeOff(s, "Buyer left town", MANAGER);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("already_written_off");
        assertThat(api.get("/sales/" + s, ADMIN).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> cash = api.postKeyed(
                "/sales",
                Map.of(
                        "branch_id",
                        hq,
                        "payment_method",
                        "cash",
                        "lines",
                        List.of(Map.of("product_id", product, "qty", "1"))),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        ResponseEntity<JsonNode> notCredit = writeOff(RetailTestSupport.id(cash), "Buyer left town", MANAGER);
        assertThat(notCredit.getBody().get("code").asString()).isEqualTo("sale_not_credit");

        UUID paid = creditSale(1, 1);
        pay(paid, 1_000, 0);
        assertThat(writeOff(paid, "Buyer left town", MANAGER)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("nothing_owing");

        UUID voided = creditSale(1, 1);
        assertThat(api.post("/sales/" + voided + "/void", Map.of("reason", "Test void"), ADMIN)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        assertThat(writeOff(voided, "Buyer left town", MANAGER)
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("sale_voided");
    }

    @Test
    void afterAWriteOffTheSaleTakesNoPaymentNoVoidAndNoReturn() {
        UUID s = creditSale(2, 1);
        writeOffOk(s);
        Map<String, Object> pay = Map.of("amount_minor", 100, "method", "cash");
        ResponseEntity<JsonNode> payment = api.postKeyed(
                "/sales/" + s + "/payments", pay, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(payment.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(payment.getBody().get("code").asString()).isEqualTo("sale_written_off");
        ResponseEntity<JsonNode> voided = api.post("/sales/" + s + "/void", Map.of("reason", "Test void"), ADMIN);
        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(voided.getBody().get("code").asString()).isEqualTo("sale_written_off");
        UUID line = UUID.fromString(sale(s).get("lines").get(0).get("id").asString());
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("sale_line_id", line);
        l.put("qty", "1");
        l.put("reason", "defective");
        l.put("disposition", "restock");
        ResponseEntity<JsonNode> ret = api.postKeyed(
                "/sales/" + s + "/returns",
                Map.of("lines", List.of(l), "refund_method", "cash"),
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(ret.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ret.getBody().get("code").asString()).isEqualTo("sale_written_off");
    }

    @Test
    void itNeedsItsOwnPermissionNotJustRetailCreditManage() {
        UUID s = creditSale(2, 1);
        String manageOnly = ADMIN + ",retail.credit.manage";
        assertThat(writeOff(s, "Buyer left town", manageOnly).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(writeOff(s, "Buyer left town", RetailTestSupport.SALES).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(writeOff(s, "Buyer left town", ADMIN).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(sale(s).has("write_off")).isFalse();
    }

    @Test
    void anImportedSaleCannotBeWrittenOff() {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, buyer_name,
                            currency, total_minor, cost_total_minor, paid_minor, status, historical, created_by)
                        VALUES (?, ?, ?, 'IMP-0009', ?, 'credit', 'Test Buyer 02',
                            (SELECT currency FROM tenants WHERE id = ?), 5000, 3000, 0, 'completed', true, ?)
                        """)
                .params(id, t.tenantId(), hq, java.sql.Date.valueOf(today().minusDays(9)), t.tenantId(), api.userId())
                .update();
        ResponseEntity<JsonNode> r = writeOff(id, "Buyer left town", MANAGER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("sale_historical");
    }

    @Test
    void anotherTenantCannotSeeOrTouchIt() {
        UUID s = creditSale(2, 1);
        TestDatabase.Fixture other = TestDatabase.tenant("retail-wo2", false, true);
        RetailTestSupport otherApi = new RetailTestSupport(http, other);
        ResponseEntity<JsonNode> r = otherApi.postKeyed(
                "/sales/" + s + "/write-off",
                Map.of("reason", "Buyer left town"),
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- maker-checker above a threshold -------------------------------------------------------------

    void threshold(long amount) {
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = settings || CAST(? AS jsonb) WHERE tenant_id = ?")
                .params("{\"approval_thresholds_minor\": {\"retail_sale_write_off\": " + amount + "}}", t.tenantId())
                .update();
    }

    ResponseEntity<JsonNode> decide(String approval, RetailTestSupport who) {
        return decide(approval, who, MANAGER);
    }

    ResponseEntity<JsonNode> decide(String approval, RetailTestSupport who, String perms) {
        return http.exchange(
                "/api/v1/approvals/" + approval + "/approve",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("note", "Checked the debt"), who.headers(perms, "*", Map.of())),
                JsonNode.class);
    }

    void activeAdmins(int n) {
        for (int i = 0; i < n; i++) {
            admin(i, "active", null, null);
        }
    }

    /**
     * One tenant administrator with an email. {@code status} is the user's, {@code daysSinceChange} how long ago the user was last
     * changed (a deactivation), {@code lockedDays} a lock that many days ahead, both null for none.
     */
    UUID admin(int i, String status, Integer daysSinceChange, Integer lockedDays) {
        return admin(UUID.randomUUID(), i, status, daysSinceChange, lockedDays);
    }

    /** The same, with the id of the user (the maker of a request is the test client, whose id is {@code api.userId()}). */
    UUID admin(UUID user, int i, String status, Integer daysSinceChange, Integer lockedDays) {
        java.time.Instant now = java.time.Instant.now();
        TestDatabase.owner()
                .sql("INSERT INTO users (id, tenant_id, kind, full_name, email, status, updated_at, locked_until)"
                        + " VALUES (?, ?, 'staff', ?, ?, ?, ?, ?)")
                .params(
                        user,
                        t.tenantId(),
                        "Test Admin 0" + i,
                        "test.admin" + i + "." + user + "@example.invalid",
                        status,
                        java.sql.Timestamp.from(
                                now.minus(java.time.Duration.ofDays(daysSinceChange == null ? 0 : daysSinceChange))),
                        lockedDays == null
                                ? null
                                : java.sql.Timestamp.from(now.plus(java.time.Duration.ofDays(lockedDays))))
                .update();
        TestDatabase.owner()
                .sql("INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, granted_by)"
                        + " VALUES (?, ?, ?, 'tenant_admin', ?)")
                .params(UUID.randomUUID(), t.tenantId(), user, user)
                .update();
        return user;
    }

    /** The administrators emailed about a write-off, by user id. */
    java.util.Set<String> toldIds(JsonNode writeOff) {
        String prefix = "retail.write_off_alone:" + writeOff.get("id").asString() + ":";
        return new java.util.TreeSet<>(TestDatabase.owner()
                .sql("SELECT substring(idempotency_key from char_length(?) + 1) FROM notification_outbox"
                        + " WHERE idempotency_key LIKE ?")
                .params(prefix, prefix + "%")
                .query(String.class)
                .list());
    }

    String noSecondPerson(JsonNode writeOff) {
        return TestDatabase.owner()
                .sql("SELECT data -> 'after' ->> 'no_second_person' FROM audit_log WHERE tenant_id = ?"
                        + " AND action = 'retail.sale.written_off' AND data -> 'after' ->> 'write_off_id' = ?")
                .params(t.tenantId(), writeOff.get("id").asString())
                .query(String.class)
                .optional()
                .orElse(null);
    }

    long told(JsonNode writeOff) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM notification_outbox WHERE idempotency_key LIKE ?")
                .param("retail.write_off_alone:" + writeOff.get("id").asString() + ":%")
                .query(Long.class)
                .single();
    }

    @Test
    void withTwoActiveAdministratorsEveryWriteOffNeedsASecondOneByDefault() {
        activeAdmins(2);
        UUID s = creditSale(1, 1);
        ResponseEntity<JsonNode> asked = writeOff(s, "Buyer left town", MANAGER);
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String approval = asked.getBody().get("approval_id").asString();
        assertThat(sale(s).has("write_off")).isFalse();
        assertThat(decide(approval, api).getStatusCode().is2xxSuccessful())
                .as("the maker cannot decide")
                .isFalse();
        String manageOnly = ADMIN + ",retail.credit.manage,core.approvals.read";
        ResponseEntity<JsonNode> notAllowed = decide(approval, new RetailTestSupport(http, t), manageOnly);
        assertThat(notAllowed.getStatusCode().value())
                .as(
                        "retail.credit.manage alone cannot decide it: the approvals module refuses it (403) or hides the request (404)")
                .isIn(403, 404);
        assertThat(sale(s).has("write_off")).isFalse();
        assertThat(decide(approval, new RetailTestSupport(http, t)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(sale(s).get("write_off").get("unpaid_minor").asLong()).isEqualTo(1_000);
    }

    @Test
    void withASingleActiveAdministratorTheWriteOffIsAllowedAloneAndTheAuditRowSaysSo() {
        activeAdmins(1);
        UUID s = creditSale(1, 1);
        writeOffOk(s);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.sale.written_off'"
                                + " AND data::text LIKE '%only_one_active_administrator%'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void anAdministratorDeactivatedWithinThirtyDaysStillCountsSoItCannotBeDoneAlone() {
        admin(0, "active", null, null);
        admin(1, "deactivated", 5, null);
        UUID s = creditSale(1, 1);
        assertThat(writeOff(s, "Buyer left town", MANAGER).getStatusCode())
                .as("the second administrator left five days ago and still counts")
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void anAdministratorWhoseRoleWasRevokedWithinThirtyDaysStillCounts() {
        admin(0, "active", null, null);
        UUID gone = admin(1, "active", null, null);
        TestDatabase.owner()
                .sql("UPDATE user_role_assignments SET revoked_at = now() - interval '3 days', revoked_by = ?"
                        + " WHERE user_id = ?")
                .params(gone, gone)
                .update();
        UUID s = creditSale(1, 1);
        assertThat(writeOff(s, "Buyer left town", MANAGER).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void aLockedAdministratorCountsAsActive() {
        admin(0, "active", null, null);
        admin(1, "active", null, 1);
        UUID s = creditSale(1, 1);
        assertThat(writeOff(s, "Buyer left town", MANAGER).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void anAdministratorGoneForMoreThanThirtyDaysNoLongerCountsAndIsNotToldAlone() {
        admin(0, "active", null, null);
        admin(1, "deactivated", 40, null);
        UUID s = creditSale(1, 1);
        JsonNode w = writeOffOk(s);
        assertThat(told(w))
                .as("only the one administrator who is still counted")
                .isEqualTo(1);
    }

    @Test
    void writingOffAloneTellsEveryAdministratorOfTheLastThirtyDaysAndTheAuditRowCountsThem() {
        // Alone means no second administrator counted, so the one who counts is the one told; the audit row records how
        // many.
        admin(0, "active", null, null);
        UUID s = creditSale(1, 1);
        JsonNode w = writeOffOk(s);
        assertThat(told(w)).isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.sale.written_off'"
                                + " AND data::text LIKE '%notified_admins%'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void raisingTheThresholdFirstDoesNotMakeAWriteOffSilent() {
        UUID a = admin(api.userId(), 0, "active", null, null);
        UUID b = admin(1, "active", null, null);
        threshold(1_000_000_000L);
        UUID s = creditSale(1, 1);
        JsonNode w = writeOffOk(s);
        assertThat(noSecondPerson(w))
                .as("done without a second person, with two administrators counted")
                .isEqualTo("below_threshold");
        assertThat(toldIds(w))
                .as("the other administrator is told, the maker is not told about their own act")
                .containsExactly(b.toString());
        assertThat(toldIds(w)).doesNotContain(a.toString());
    }

    @Test
    void aDeactivatedAdministratorCountsButIsNotEmailedWhenTheMakerGoesThroughBelowTheThreshold() {
        UUID a = admin(api.userId(), 0, "active", null, null);
        UUID b = admin(1, "deactivated", 5, null);
        threshold(1_000_000_000L);
        JsonNode w = writeOffOk(creditSale(1, 1));
        assertThat(noSecondPerson(w))
                .as("two administrators count, so the threshold let it through")
                .isEqualTo("below_threshold");
        assertThat(toldIds(w))
                .as("only an active administrator can be told, and no other is active: the maker")
                .containsExactly(a.toString());
        assertThat(toldIds(w)).doesNotContain(b.toString());
    }

    @Test
    void anAdministratorWhoseRoleWasRevokedCountsButIsNotEmailedWhenTheMakerGoesThroughBelowTheThreshold() {
        UUID a = admin(api.userId(), 0, "active", null, null);
        UUID c = admin(1, "active", null, null);
        TestDatabase.owner()
                .sql("UPDATE user_role_assignments SET revoked_at = now() - interval '3 days', revoked_by = ?"
                        + " WHERE user_id = ?")
                .params(c, c)
                .update();
        threshold(1_000_000_000L);
        JsonNode w = writeOffOk(creditSale(1, 1));
        assertThat(noSecondPerson(w)).isEqualTo("below_threshold");
        assertThat(toldIds(w)).containsExactly(a.toString());
        assertThat(toldIds(w)).doesNotContain(c.toString());
    }

    @Test
    void aSoleAdministratorWhoMadeTheRequestIsTheOnlyOneToldAndTheAuditRowSaysSo() {
        UUID a = admin(api.userId(), 0, "active", null, null);
        UUID gone = admin(1, "deactivated", 40, null);
        UUID s = creditSale(1, 1);
        JsonNode w = writeOffOk(s);
        assertThat(noSecondPerson(w)).isEqualTo("only_one_active_administrator");
        assertThat(toldIds(w))
                .as("a self-notice: the maker is the only administrator who counts; one gone for 40 days is not told")
                .containsExactly(a.toString());
        assertThat(toldIds(w)).doesNotContain(gone.toString());
    }

    @Test
    void aWriteOffThatNeededNoSecondPersonBecauseOfTheThresholdWithOneAdministratorSaysOneAdministrator() {
        admin(api.userId(), 0, "active", null, null);
        threshold(1_000_000_000L);
        JsonNode w = writeOffOk(creditSale(1, 1));
        assertThat(noSecondPerson(w)).isEqualTo("only_one_active_administrator");
    }

    @Test
    void aPositiveThresholdReplacesTheDefault() {
        activeAdmins(2);
        threshold(5_000);
        UUID s = creditSale(1, 1);
        JsonNode w = writeOffOk(s);
        assertThat(w.get("unpaid_minor").asLong())
                .as("below the limit: no second person")
                .isEqualTo(1_000);
    }

    @Test
    void aWriteOffAtOrAboveTheThresholdWaitsForASecondAdministrator() {
        threshold(1_500);
        UUID small = creditSale(1, 2);
        JsonNode done = writeOffOk(small);
        assertThat(done.get("unpaid_minor").asLong()).isEqualTo(1_000);

        UUID big = creditSale(2, 2);
        ResponseEntity<JsonNode> asked = writeOff(big, "Buyer left town", MANAGER);
        assertThat(asked.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(asked.getBody().get("executed").asBoolean()).isFalse();
        String approval = asked.getBody().get("approval_id").asString();
        assertThat(sale(big).has("write_off"))
                .as("nothing moves until the checker decides")
                .isFalse();
        assertThat(sale(big).get("balance_minor").asLong()).isEqualTo(2_000);

        assertThat(decide(approval, api).getStatusCode().is2xxSuccessful())
                .as("the maker cannot decide")
                .isFalse();
        RetailTestSupport checker = new RetailTestSupport(http, t);
        ResponseEntity<JsonNode> decided = decide(approval, checker);
        assertThat(decided.getStatusCode()).as("%s", decided.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(sale(big).get("write_off").get("unpaid_minor").asLong()).isEqualTo(2_000);
        assertThat(sale(big).get("balance_minor").asLong()).isZero();
        assertThat(ledgerImbalance()).isZero();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT created_by = ? AND approved_by = ? AND approval_id IS NOT NULL FROM retail_sale_writeoffs WHERE sale_id = ?")
                        .params(api.userId(), checker.userId(), big)
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }
}
