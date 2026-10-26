package com.rincoltech.bms.retail.reports;

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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * How credit sales count in the retail figures (ADR-043, issue #340), under both values of the tenant setting
 * {@code credit_sale_recognition}. One product costs 600 and sells at 1,000 (a margin of 40 percent), so a sale of n pieces
 * is 1,000 n with a cost of 600 n. Everything is fabricated. The golden tests at the top run with the setting untouched
 * (on_sale, the default) and pin what the reports have always said; the rest switch the setting to on_payment.
 */
class RetailCreditRecognitionIT extends IntegrationTest {

    static final String SETTINGS = "core.settings.manage";
    static final String RETURNER = ADMIN + ",retail.sale.return,retail.cashbook.read,retail.savings.record";
    static final String CASHBOOK = ADMIN + ",retail.cashbook.read,retail.savings.record";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;
    UUID product;

    static LocalDate today() {
        return LocalDate.now(BusinessClock.DEFAULT_ZONE);
    }

    static LocalDate ago(int days) {
        return today().minusDays(days);
    }

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-crd", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
        product = api.product("CRD-1", 600, 1_000);
        api.importedBalance(hq, product, "1000");
        api.importedBalance(br2, product, "1000");
        capability(Map.of("returns", true));
    }

    // ---- helpers ---------------------------------------------------------------------------

    ResponseEntity<JsonNode> change(Map<String, Object> body, String permissions) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        return api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                body,
                permissions,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
    }

    void capability(Map<String, Object> body) {
        ResponseEntity<JsonNode> r = change(body, SETTINGS);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** The real switch: it counts from today, so only credit sales dated today or later are counted when the buyer pays. */
    void onPayment() {
        capability(Map.of("credit_sale_recognition", "on_payment"));
    }

    /** A rule that has stood since before any sale of the test, as for a tenant that switched long ago. */
    void onPaymentFromTheStart() {
        onPayment();
        settle("1970-01-01");
    }

    /** Writes the day the rule counts from as the settings row holds it (the real route only ever names tomorrow). */
    void settle(String effectiveFrom) {
        TestDatabase.owner()
                .sql("UPDATE tenant_settings SET settings = jsonb_set(jsonb_set(settings,"
                        + " '{module_groups,retail,credit_sale_recognition}', '\"on_payment\"'),"
                        + " '{module_groups,retail,credit_recognition_effective_from}', CAST(? AS jsonb))"
                        + " WHERE tenant_id = ?")
                .params("\"" + effectiveFrom + "\"", t.tenantId())
                .update();
    }

    UUID cashSale(UUID branch, int qty, LocalDate date) {
        return sale(branch, "cash", qty, date);
    }

    UUID creditSale(UUID branch, int qty, LocalDate date) {
        return sale(branch, "credit", qty, date);
    }

    UUID sale(UUID branch, String method, int qty, LocalDate date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("sale_date", date.toString());
        body.put("payment_method", method);
        if (method.equals("credit")) {
            body.put("buyer_name", "Test Buyer 01");
        }
        body.put("lines", List.of(Map.of("product_id", product, "qty", String.valueOf(qty))));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    /** A payment dated {@code on} and entered on that day (the entry is moved back in time when it is not today). */
    UUID pay(UUID sale, long amount, LocalDate on) {
        UUID id = payEnteredToday(sale, amount, today());
        if (!on.equals(today())) {
            moveEntered(id, on);
        }
        return id;
    }

    /** A payment dated {@code on}, entered now. */
    UUID payEnteredToday(UUID sale, long amount, LocalDate on) {
        ResponseEntity<JsonNode> r = payRaw(sale, amount, on);
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isTrue();
        return UUID.fromString(r.getBody().get("payment").get("id").asString());
    }

    ResponseEntity<JsonNode> payRaw(UUID sale, long amount, LocalDate on) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount_minor", amount);
        body.put("method", "cash");
        body.put("paid_on", on.toString());
        return api.postKeyed(
                "/sales/" + sale + "/payments",
                body,
                ADMIN,
                "*",
                UUID.randomUUID().toString());
    }

    static String enteredAt(LocalDate day) {
        return "(TIMESTAMP '" + day + " 12:00:00') AT TIME ZONE 'Africa/Kampala'";
    }

    /** Moves the moment a payment was entered to the middle of {@code day}, as if it had been entered then. */
    void moveEntered(UUID payment, LocalDate day) {
        TestDatabase.owner()
                .sql("DO $$ BEGIN PERFORM set_config('bms.allow_mutation', 'on', true);"
                        + " UPDATE retail_sale_payments SET created_at = " + enteredAt(day) + ", paid_on = DATE '" + day
                        + "' WHERE id = '" + payment + "'; END $$")
                .update();
    }

    /** Moves the moment a sale was entered to the middle of {@code day}, as if it had been entered then. */
    void sellEntered(UUID sale, LocalDate day) {
        TestDatabase.owner()
                .sql("DO $$ BEGIN PERFORM set_config('bms.allow_mutation', 'on', true);"
                        + " UPDATE retail_sales SET created_at = " + enteredAt(day) + " WHERE id = '" + sale
                        + "'; END $$")
                .update();
    }

    /** The same for every sale payment a receipt on account created. */
    void moveReceiptEntered(UUID receipt, LocalDate day) {
        TestDatabase.owner()
                .sql("DO $$ BEGIN PERFORM set_config('bms.allow_mutation', 'on', true);"
                        + " UPDATE retail_sale_payments SET created_at = " + enteredAt(day)
                        + " WHERE id IN (SELECT sale_payment_id FROM retail_account_payment_allocations WHERE receipt_id = '"
                        + receipt + "'); END $$")
                .update();
    }

    void saveSavings(UUID branch, LocalDate day) {
        JsonNode suggestion = suggestion(branch, day);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("business_date", day.toString());
        body.put("suggestion_token", suggestion.get("suggestion_token").asString());
        ResponseEntity<JsonNode> r =
                api.postKeyed("/savings", body, CASHBOOK, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isTrue();
    }

    JsonNode profit(LocalDate from, LocalDate to) {
        ResponseEntity<JsonNode> r = api.get("/reports/profit/daily?from=" + from + "&to=" + to, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    /** The row of one branch and day, or null. */
    JsonNode row(JsonNode report, UUID branch, LocalDate day) {
        for (JsonNode r : report.get("rows")) {
            if (r.get("branch_id").asString().equals(branch.toString())
                    && r.get("date").asString().equals(day.toString())) {
                return r;
            }
        }
        return null;
    }

    long field(JsonNode row, String name) {
        return row == null || !row.has(name) ? 0 : row.get(name).asLong();
    }

    JsonNode dashboard() {
        ResponseEntity<JsonNode> r = api.get("/reports/dashboard", ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    JsonNode suggestion(UUID branch, LocalDate day) {
        ResponseEntity<JsonNode> r = api.get("/savings/suggestion?branch_id=" + branch + "&date=" + day, CASHBOOK);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    UUID lineOf(UUID sale) {
        return UUID.fromString(api.get("/sales/" + sale, ADMIN)
                .getBody()
                .get("lines")
                .get(0)
                .get("id")
                .asString());
    }

    JsonNode giveBack(UUID sale, String qty, String refund) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sale_line_id", lineOf(sale));
        line.put("qty", qty);
        line.put("reason", "defective");
        line.put("disposition", "restock");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(line));
        body.put("refund_method", refund);
        if (refund.equals("account")) {
            body.put("money_method", "cash");
        }
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + sale + "/returns",
                body,
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    // ---- the setting -----------------------------------------------------------------------

    @Test
    void theSettingDefaultsToOnSaleAndIsValidatedAndAudited() {
        JsonNode g = api.get("/settings/capabilities", SETTINGS).getBody();
        assertThat(g.get("credit_sale_recognition").asString()).isEqualTo("on_sale");

        ResponseEntity<JsonNode> bad = change(Map.of("credit_sale_recognition", "sometimes"), SETTINGS);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> denied = change(Map.of("credit_sale_recognition", "on_payment"), ADMIN);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        long before = AnalyticsData.auditRows();
        onPayment();
        assertThat(api.get("/settings/capabilities", SETTINGS)
                        .getBody()
                        .get("credit_recognition_effective_from")
                        .asString())
                .as("a change counts from the next business day")
                .isEqualTo(today().plusDays(1).toString());
        assertThat(AnalyticsData.auditRows()).isGreaterThan(before);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.capabilities.changed'"
                                        + " AND data::text LIKE '%on_payment%'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isPositive();
        assertThat(api.get("/settings/capabilities", SETTINGS)
                        .getBody()
                        .get("credit_sale_recognition")
                        .asString())
                .isEqualTo("on_payment");
        capability(Map.of("profile", "shops"));
        assertThat(api.get("/settings/capabilities", SETTINGS)
                        .getBody()
                        .get("credit_sale_recognition")
                        .asString())
                .as("choosing a profile does not touch the policy")
                .isEqualTo("on_payment");
        capability(Map.of("credit_sale_recognition", "on_sale"));
    }

    // ---- on_sale: the golden tests, nothing changes ----------------------------------------

    @Test
    void onSaleGoldenCreditSalesCountWhenMadeInTheProfitReportTheDashboardAndTheSavings() {
        creditSale(hq, 3, ago(2));
        cashSale(hq, 2, today());
        creditSale(hq, 1, today());

        JsonNode report = profit(ago(2), today());
        assertThat(report.get("credit_sale_recognition").asString()).isEqualTo("on_sale");
        assertThat(report.has("sold_minor")).isFalse();
        assertThat(report.has("on_credit_minor")).isFalse();
        JsonNode d2 = row(report, hq, ago(2));
        assertThat(field(d2, "sales_minor")).isEqualTo(3_000);
        assertThat(field(d2, "cost_of_sales_minor")).isEqualTo(1_800);
        assertThat(field(d2, "profit_minor")).isEqualTo(1_200);
        assertThat(d2.has("sold_minor")).isFalse();
        JsonNode d0 = row(report, hq, today());
        assertThat(field(d0, "sales_minor")).isEqualTo(3_000);
        assertThat(field(d0, "profit_minor")).isEqualTo(1_200);
        assertThat(report.get("profit_minor").asLong()).isEqualTo(2_400);

        JsonNode dash = dashboard();
        assertThat(dash.get("credit_sale_recognition").asString()).isEqualTo("on_sale");
        JsonNode now = dash.get("today");
        assertThat(now.get("sales_minor").asLong()).isEqualTo(3_000);
        assertThat(now.get("cash_minor").asLong()).isEqualTo(2_000);
        assertThat(now.get("credit_minor").asLong()).isEqualTo(1_000);
        assertThat(now.get("gross_profit_minor").asLong()).isEqualTo(1_200);
        assertThat(now.has("received_minor")).isFalse();
        assertThat(now.has("on_credit_minor")).isFalse();
        assertThat(dash.has("last7_received_minor")).isFalse();

        assertThat(suggestion(hq, ago(2)).get("suggested_minor").asLong()).isEqualTo(600);
        assertThat(suggestion(hq, today()).get("suggested_minor").asLong()).isEqualTo(600);
    }

    @Test
    void onSaleGoldenAPaymentOnACreditSaleChangesNoProfitFigure() {
        UUID s = creditSale(hq, 3, ago(2));
        pay(s, 1_000, ago(1));
        JsonNode report = profit(ago(2), today());
        assertThat(field(row(report, hq, ago(2)), "profit_minor")).isEqualTo(1_200);
        assertThat(row(report, hq, ago(1))).isNull();
        assertThat(report.get("profit_minor").asLong()).isEqualTo(1_200);
        assertThat(suggestion(hq, ago(1)).get("suggested_minor").asLong()).isZero();
    }

    @Test
    void onSaleGoldenAVoidedCreditSaleLeavesTheReports() {
        UUID s = creditSale(hq, 2, ago(1));
        assertThat(profit(ago(1), ago(1)).get("profit_minor").asLong()).isEqualTo(800);
        ResponseEntity<JsonNode> v = api.post("/sales/" + s + "/void", Map.of("reason", "Test void"), ADMIN);
        assertThat(v.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(profit(ago(1), ago(1)).get("profit_minor").asLong()).isZero();
    }

    // ---- on_payment ------------------------------------------------------------------------

    @Test
    void anUnpaidCreditSaleIsSoldAndOnCreditButNotReceivedNorProfitNorSavings() {
        onPaymentFromTheStart();
        creditSale(hq, 3, ago(2));
        cashSale(hq, 2, today());

        JsonNode report = profit(ago(2), today());
        assertThat(report.get("credit_sale_recognition").asString()).isEqualTo("on_payment");
        JsonNode d2 = row(report, hq, ago(2));
        assertThat(field(d2, "sold_minor")).isEqualTo(3_000);
        assertThat(field(d2, "sales_minor")).as("received").isZero();
        assertThat(field(d2, "profit_minor")).isZero();
        assertThat(field(row(report, hq, today()), "sales_minor")).isEqualTo(2_000);
        assertThat(field(row(report, hq, today()), "profit_minor")).isEqualTo(800);
        assertThat(report.get("sold_minor").asLong()).isEqualTo(5_000);
        assertThat(report.get("sales_minor").asLong()).isEqualTo(2_000);
        assertThat(report.get("profit_minor").asLong()).isEqualTo(800);
        assertThat(report.get("on_credit_minor").asLong()).isEqualTo(3_000);

        JsonNode dash = dashboard();
        JsonNode now = dash.get("today");
        assertThat(now.get("sales_minor").asLong()).as("sold today").isEqualTo(2_000);
        assertThat(now.get("received_minor").asLong()).isEqualTo(2_000);
        assertThat(now.get("on_credit_minor").asLong()).isEqualTo(3_000);
        assertThat(now.get("on_credit_count").asInt()).isEqualTo(1);
        assertThat(now.get("gross_profit_minor").asLong()).isEqualTo(800);
        assertThat(dash.get("last7_received_minor").asLong()).isEqualTo(2_000);
        assertThat(dash.get("last7_gross_profit_minor").asLong()).isEqualTo(800);

        assertThat(suggestion(hq, ago(2)).get("suggested_minor").asLong()).isZero();
        assertThat(suggestion(hq, today()).get("suggested_minor").asLong()).isEqualTo(400);
    }

    @Test
    void aPartPaymentGivesPartOfTheProfitAndSeveralPaymentsAddUpExactly() {
        onPaymentFromTheStart();
        UUID s = creditSale(hq, 3, ago(3));
        pay(s, 1_000, ago(2));
        pay(s, 500, ago(1));
        pay(s, 1_500, today());

        JsonNode report = profit(ago(3), today());
        assertThat(row(report, hq, ago(3)))
                .as("the sale day has nothing received")
                .isNotNull();
        assertThat(field(row(report, hq, ago(3)), "sales_minor")).isZero();
        JsonNode p1 = row(report, hq, ago(2));
        assertThat(field(p1, "sales_minor")).isEqualTo(1_000);
        assertThat(field(p1, "cost_of_sales_minor")).isEqualTo(600);
        assertThat(field(p1, "profit_minor")).isEqualTo(400);
        JsonNode p2 = row(report, hq, ago(1));
        assertThat(field(p2, "sales_minor")).isEqualTo(500);
        assertThat(field(p2, "cost_of_sales_minor")).isEqualTo(300);
        assertThat(field(p2, "profit_minor")).isEqualTo(200);
        JsonNode p3 = row(report, hq, today());
        assertThat(field(p3, "profit_minor")).isEqualTo(600);
        assertThat(report.get("profit_minor").asLong())
                .as("the whole sale's profit, once")
                .isEqualTo(1_200);
        assertThat(report.get("on_credit_minor").asLong()).isZero();
        assertThat(suggestion(hq, ago(2)).get("suggested_minor").asLong()).isEqualTo(200);
        assertThat(suggestion(hq, ago(1)).get("suggested_minor").asLong()).isEqualTo(100);
    }

    @Test
    void aPaymentInALaterMonthCountsOnTheDayItIsReceived() {
        onPaymentFromTheStart();
        UUID s = creditSale(hq, 2, ago(40));
        pay(s, 2_000, today());
        JsonNode old = profit(ago(40), ago(40));
        assertThat(old.get("profit_minor").asLong()).isZero();
        assertThat(old.get("sold_minor").asLong()).isEqualTo(2_000);
        JsonNode now = profit(today(), today());
        assertThat(now.get("sales_minor").asLong()).isEqualTo(2_000);
        assertThat(now.get("profit_minor").asLong()).isEqualTo(800);
        assertThat(suggestion(hq, today()).get("suggested_minor").asLong()).isEqualTo(400);
    }

    @Test
    void theProfitStaysWithTheShopThatMadeTheSale() {
        onPaymentFromTheStart();
        UUID s = creditSale(br2, 2, ago(1));
        pay(s, 2_000, today());
        JsonNode report = profit(ago(1), today());
        assertThat(field(row(report, br2, today()), "profit_minor")).isEqualTo(800);
        assertThat(row(report, hq, today())).isNull();
    }

    @Test
    void aVoidedUnpaidCreditSaleLeavesSoldAndOnCredit() {
        onPaymentFromTheStart();
        UUID s = creditSale(hq, 2, ago(1));
        assertThat(profit(ago(1), today()).get("on_credit_minor").asLong()).isEqualTo(2_000);
        assertThat(api.post("/sales/" + s + "/void", Map.of("reason", "Test void"), ADMIN)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        JsonNode report = profit(ago(1), today());
        assertThat(report.get("on_credit_minor").asLong()).isZero();
        assertThat(report.get("sold_minor").asLong()).isZero();
        assertThat(report.get("profit_minor").asLong()).isZero();
        assertThat(dashboard().get("today").get("on_credit_minor").asLong()).isZero();
    }

    @Test
    void aReturnOfTheUnpaidPartTakesNoProfitBackAndThePaymentsThatFollowCountTheSmallerSale() {
        onPaymentFromTheStart();
        UUID s = creditSale(hq, 3, ago(2));
        giveBack(s, "1", "account");
        JsonNode afterReturn = profit(ago(2), today());
        assertThat(afterReturn.get("profit_minor").asLong())
                .as("nothing was received, so nothing to take back")
                .isZero();
        assertThat(afterReturn.get("on_credit_minor").asLong()).isEqualTo(2_000);
        pay(s, 2_000, today());
        JsonNode done = profit(ago(2), today());
        assertThat(done.get("sales_minor").asLong()).isEqualTo(2_000);
        assertThat(done.get("cost_of_sales_minor").asLong()).isEqualTo(1_200);
        assertThat(done.get("profit_minor").asLong()).isEqualTo(800);
        assertThat(done.get("on_credit_minor").asLong()).isZero();
    }

    @Test
    void aReturnOfPaidGoodsRefundedInMoneyGivesTheProfitBack() {
        onPaymentFromTheStart();
        UUID s = creditSale(hq, 3, ago(2));
        pay(s, 3_000, ago(1));
        assertThat(profit(ago(2), today()).get("profit_minor").asLong()).isEqualTo(1_200);
        giveBack(s, "1", "cash");
        JsonNode report = profit(ago(2), today());
        assertThat(report.get("sales_minor").asLong()).isEqualTo(2_000);
        assertThat(report.get("profit_minor").asLong())
                .as("one of three pieces went back and was refunded")
                .isEqualTo(800);
        assertThat(field(row(report, hq, ago(1)), "profit_minor")).isEqualTo(1_200);
        assertThat(field(row(report, hq, today()), "profit_minor")).isEqualTo(-400);
        assertThat(field(row(report, hq, ago(1)), "received_minor")).isEqualTo(3_000);
        assertThat(field(row(report, hq, today()), "received_minor"))
                .as("the cash paid back on the return, on the day of the return")
                .isEqualTo(-1_000);
        assertThat(report.get("received_minor").asLong()).isEqualTo(2_000);
        JsonNode dash = dashboard();
        assertThat(dash.get("today").get("received_minor").asLong()).isEqualTo(-1_000);
        assertThat(shopOf(dash, hq).get("today_received_minor").asLong()).isEqualTo(-1_000);
        assertThat(dash.get("last30_received_minor").asLong()).isEqualTo(2_000);
    }

    @Test
    void anImportedCreditSaleStaysOutsideTheRule() {
        onPaymentFromTheStart();
        UUID sale = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, buyer_name,
                            currency, total_minor, cost_total_minor, paid_minor, status, historical, created_by)
                        VALUES (?, ?, ?, 'IMP-0001', ?, 'credit', 'Test Buyer 02',
                            (SELECT currency FROM tenants WHERE id = ?), 5000, 3000, 0, 'completed', true, ?)
                        """)
                .params(sale, t.tenantId(), hq, java.sql.Date.valueOf(ago(5)), t.tenantId(), api.userId())
                .update();
        TestDatabase.owner().sql("""
                        INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor,
                            unit_cost_minor, line_total_minor, line_cost_minor)
                        VALUES (gen_random_uuid(), ?, ?, 1, ?, 5, 1000, 600, 5000, 3000)
                        """).params(t.tenantId(), sale, product).update();
        JsonNode report = profit(ago(5), ago(5));
        assertThat(report.get("on_credit_minor").asLong())
                .as("settled outside the system")
                .isZero();
        assertThat(field(row(report, hq, ago(5)), "profit_minor"))
                .as("it keeps counting on its own date")
                .isEqualTo(2_000);
        assertThat(dashboard().get("today").get("on_credit_minor").asLong()).isZero();
    }

    @Test
    void anotherTenantKeepsItsOwnSetting() {
        onPayment();
        TestDatabase.Fixture other = TestDatabase.tenant("retail-crd2", false, true);
        RetailTestSupport otherApi = new RetailTestSupport(http, other);
        UUID prod = otherApi.product("CRD-9", 600, 1_000);
        otherApi.importedBalance(other.headOffice(), prod, "10");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", other.headOffice());
        body.put("payment_method", "credit");
        body.put("buyer_name", "Test Buyer 03");
        body.put("lines", List.of(Map.of("product_id", prod, "qty", "2")));
        assertThat(otherApi.postKeyed(
                                "/sales", body, ADMIN, "*", UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode report = otherApi.get("/reports/profit/daily", ADMIN).getBody();
        assertThat(report.get("credit_sale_recognition").asString()).isEqualTo("on_sale");
        assertThat(report.get("profit_minor").asLong()).isEqualTo(800);
        assertThat(profit(ago(6), today()).get("profit_minor").asLong())
                .as("this tenant has no sales")
                .isZero();
    }

    @Test
    void withoutProfitReadTheReceivedMoneyStaysAndTheProfitGoes() {
        onPaymentFromTheStart();
        UUID s = creditSale(hq, 2, ago(1));
        pay(s, 2_000, today());
        JsonNode dash = api.get("/reports/dashboard", RetailTestSupport.SALES + ",retail.sale.all_sellers")
                .getBody();
        assertThat(dash.get("today").get("received_minor").asLong()).isEqualTo(2_000);
        assertThat(dash.get("today").has("gross_profit_minor")).isFalse();
        assertThat(api.get("/reports/profit/daily", RetailTestSupport.SALES).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---- the rule has an effective date --------------------------------------------------------

    @Test
    void switchingTheRuleNeverCountsAnOlderSalesProfitTwiceNorMovesSavedSavings() {
        UUID old = creditSale(hq, 100, ago(3));
        assertThat(suggestion(hq, ago(3)).get("suggested_minor").asLong()).isEqualTo(20_000);
        saveSavings(hq, ago(3));
        // A sale of the day of the switch, made before it, with its savings saved.
        UUID sameDay = creditSale(hq, 2, today());
        assertThat(suggestion(hq, today()).get("suggested_minor").asLong()).isEqualTo(400);
        saveSavings(hq, today());

        onPayment();
        payEnteredToday(old, 100_000, today());
        payEnteredToday(sameDay, 2_000, today());
        JsonNode report = profit(ago(3), today());
        assertThat(field(row(report, hq, ago(3)), "profit_minor"))
                .as("counted at the sale, as it was")
                .isEqualTo(40_000);
        assertThat(field(row(report, hq, today()), "profit_minor"))
                .as("the sale of the day of the switch stays at the sale, and its payment adds nothing")
                .isEqualTo(800);
        assertThat(report.get("profit_minor").asLong()).isEqualTo(40_800);
        assertThat(suggestion(hq, today()).get("suggested_minor").asLong()).isEqualTo(400);
        assertThat(suggestion(hq, ago(3)).get("suggested_minor").asLong()).isEqualTo(20_000);
        assertThat(dashboard().get("today").get("gross_profit_minor").asLong()).isEqualTo(800);
    }

    @Test
    void switchingTwiceInOneDayLeavesOnePendingChangeOrNone() {
        onPayment();
        capability(Map.of("credit_sale_recognition", "on_sale"));
        JsonNode g = api.get("/settings/capabilities", SETTINGS).getBody();
        assertThat(g.get("credit_sale_recognition").asString()).isEqualTo("on_sale");
        assertThat(g.has("credit_recognition_effective_from"))
                .as("cancelled before it took effect")
                .isFalse();
        onPayment();
        capability(Map.of("credit_sale_recognition", "on_sale"));
        onPayment();
        g = api.get("/settings/capabilities", SETTINGS).getBody();
        assertThat(g.get("credit_sale_recognition").asString()).isEqualTo("on_payment");
        assertThat(g.get("credit_recognition_effective_from").asString())
                .isEqualTo(today().plusDays(1).toString());
        UUID today = creditSale(hq, 1, today());
        payEnteredToday(today, 1_000, today());
        assertThat(profit(today(), today()).get("profit_minor").asLong())
                .as("still counted at the sale")
                .isEqualTo(400);
    }

    @Test
    void onceStartedTheRuleCannotBeSwitchedOffAndNothingChanges() {
        onPaymentFromTheStart();
        ResponseEntity<JsonNode> refused = change(Map.of("credit_sale_recognition", "on_sale"), SETTINGS);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().toString())
                .contains("credit_rule_cannot_be_switched_off")
                .contains("This rule cannot be switched off once it has started. Ask Rincol support.");
        JsonNode g = api.get("/settings/capabilities", SETTINGS).getBody();
        assertThat(g.get("credit_sale_recognition").asString()).isEqualTo("on_payment");
        assertThat(g.get("credit_recognition_effective_from").asString()).isEqualTo("1970-01-01");
        // Asking for the rule again while it stands is harmless and does not move the day.
        capability(Map.of("credit_sale_recognition", "on_payment"));
        assertThat(api.get("/settings/capabilities", SETTINGS)
                        .getBody()
                        .get("credit_recognition_effective_from")
                        .asString())
                .isEqualTo("1970-01-01");
    }

    @Test
    void aSwitchThatHasNotStartedYetCanStillBeCancelled() {
        onPayment();
        capability(Map.of("credit_sale_recognition", "on_sale"));
        assertThat(api.get("/settings/capabilities", SETTINGS)
                        .getBody()
                        .get("credit_sale_recognition")
                        .asString())
                .isEqualTo("on_sale");
        // Once the day has come (here: it started yesterday) the same request is refused.
        onPayment();
        settle(ago(1).toString());
        assertThat(change(Map.of("credit_sale_recognition", "on_sale"), SETTINGS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void theSaleSaysWhetherItsPaymentsMustBeDatedToday() {
        onPayment();
        settle(ago(2).toString());
        UUID before = creditSale(hq, 2, ago(5));
        sellEntered(before, ago(5));
        UUID backdated = creditSale(hq, 2, ago(4));
        UUID after = creditSale(hq, 2, ago(1));
        assertThat(api.get("/sales/" + before, ADMIN)
                        .getBody()
                        .get("counted_when_paid")
                        .asBoolean())
                .as("dated and entered before the rule started: paid as it always was")
                .isFalse();
        assertThat(api.get("/sales/" + backdated, ADMIN)
                        .getBody()
                        .get("counted_when_paid")
                        .asBoolean())
                .as("dated before the start but entered after it: counted when the buyer pays")
                .isTrue();
        assertThat(payRaw(backdated, 500, ago(4)).getStatusCode())
                .as("so its payments are dated today too")
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/sales/" + after, ADMIN)
                        .getBody()
                        .get("counted_when_paid")
                        .asBoolean())
                .isTrue();
        assertThat(payRaw(before, 500, ago(3)).getStatusCode())
                .as("a sale of the earlier era may still be paid on an earlier date")
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> refused = payRaw(after, 500, ago(1));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().toString()).contains("backdated_payment");
    }

    @Test
    void aBackdatedCreditSaleEnteredAfterTheStartDoesNotAddProfitToAClosedDay() {
        onPayment();
        settle(ago(2).toString());
        // Entered today but dated three days ago, before the rule started: it must not add 400 of profit to a day whose
        // savings
        // were set aside already.
        UUID backdated = creditSale(hq, 1, ago(3));
        JsonNode report = profit(ago(4), today());
        assertThat(field(row(report, hq, ago(3)), "profit_minor"))
                .as("nothing is added to the dated day")
                .isZero();
        assertThat(report.get("profit_minor").asLong()).isZero();
        assertThat(report.get("on_credit_minor").asLong()).isEqualTo(1_000);
        assertThat(suggestion(hq, ago(3)).get("suggested_minor").asLong()).isZero();
        assertThat(profit(ago(4), today()).get("sold_minor").asLong())
                .as("it is still Sold on its date")
                .isEqualTo(1_000);
        // Its profit comes in as the buyer pays, on the day of the payment, which must be dated today.
        assertThat(payRaw(backdated, 1_000, ago(1)).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        payEnteredToday(backdated, 1_000, today());
        JsonNode paid = profit(ago(4), today());
        assertThat(field(row(paid, hq, today()), "profit_minor")).isEqualTo(400);
        assertThat(field(row(paid, hq, ago(3)), "profit_minor")).isZero();
        assertThat(dashboard().get("today").get("gross_profit_minor").asLong()).isEqualTo(400);
    }

    @Test
    void aCreditSaleEnteredAndDatedBeforeTheStartStillCountsAtTheSale() {
        onPayment();
        settle(ago(2).toString());
        UUID old = creditSale(hq, 1, ago(3));
        sellEntered(old, ago(3));
        assertThat(field(row(profit(ago(4), today()), hq, ago(3)), "profit_minor"))
                .as("entered before the start: counted at the sale, as it was")
                .isEqualTo(400);
    }

    @Test
    void aReportOfDaysBeforeTheStartKeepsTheOldLayoutAndSaysWhenTheRuleStarts() {
        onPayment();
        settle(ago(3).toString());
        JsonNode earlier = profit(ago(6), ago(4));
        assertThat(earlier.get("credit_sale_recognition").asString())
                .as("no day from the start on and no credit sale counted when the buyer pays: read as it always was")
                .isEqualTo("on_sale");
        assertThat(earlier.get("credit_recognition_starts_on").asString()).isEqualTo(ago(3).toString());
        assertThat(profit(ago(6), today()).get("credit_sale_recognition").asString())
                .isEqualTo("on_payment");
        // Chosen today, it starts tomorrow: today's report is still on_sale and names tomorrow.
        settle(today().plusDays(1).toString());
        JsonNode now = profit(today(), today());
        assertThat(now.get("credit_sale_recognition").asString()).isEqualTo("on_sale");
        assertThat(now.get("credit_recognition_starts_on").asString())
                .isEqualTo(today().plusDays(1).toString());
        assertThat(dashboard().get("credit_sale_recognition").asString()).isEqualTo("on_sale");
    }

    @Test
    void aReportOfAMonthBeforeTheStartIsNotRelabelledByTheRuleThatCameLater() {
        onPayment();
        settle(ago(2).toString());
        // Unpaid, made and entered ten days ago, before the start: counted at the sale, as it always was.
        UUID old = creditSale(hq, 1, ago(10));
        sellEntered(old, ago(10));
        JsonNode report = profit(ago(12), ago(8));
        assertThat(report.get("credit_sale_recognition").asString()).isEqualTo("on_sale");
        assertThat(report.has("sold_minor")).as("no Sold").isFalse();
        assertThat(report.has("on_credit_minor")).as("no On credit").isFalse();
        assertThat(report.has("counted_at_sale_minor")).isFalse();
        assertThat(report.get("sales_minor").asLong()).as("Sales, as before").isEqualTo(1_000);
        assertThat(report.get("profit_minor").asLong())
                .as("the 400 is the profit of the sale")
                .isEqualTo(400);
        assertThat(row(report, hq, ago(10)).has("sold_minor")).isFalse();
    }

    @Test
    void aReportThatStraddlesTheStartShowsRealMoneyAsReceivedAndTheOldCreditProfitApart() {
        onPayment();
        settle(ago(2).toString());
        UUID old = creditSale(hq, 1, ago(10));
        sellEntered(old, ago(10));
        cashSale(hq, 1, today());
        UUID counted = creditSale(hq, 1, today());
        payEnteredToday(counted, 500, today());

        JsonNode report = profit(ago(12), today());
        assertThat(report.get("credit_sale_recognition").asString()).isEqualTo("on_payment");
        assertThat(report.get("sold_minor").asLong()).as("everything sold").isEqualTo(3_000);
        assertThat(report.get("received_minor").asLong())
                .as("real money: the cash sale and the part payment, nothing for the unpaid old credit sale")
                .isEqualTo(1_500);
        assertThat(report.get("counted_at_sale_minor").asLong())
                .as("the profit counted at the sale for the old credit sale")
                .isEqualTo(400);
        assertThat(report.get("sales_minor").asLong())
                .as("sales counted for profit: the old credit sale, the cash sale and the part payment")
                .isEqualTo(2_500);
        assertThat(report.get("on_credit_minor").asLong())
                .as("only what is owed on the sale counted when the buyer pays")
                .isEqualTo(500);
        assertThat(report.get("cost_of_sales_minor").asLong()).isEqualTo(1_500);
        assertThat(report.get("profit_minor").asLong()).as("400 + 400 + 200").isEqualTo(1_000);
        assertThat(report.get("sold_minor").asLong())
                .as("Sold is the sales counted for profit plus what is still owed on the counted sale")
                .isEqualTo(report.get("sales_minor").asLong()
                        + report.get("on_credit_minor").asLong());
        assertThat(field(row(report, hq, ago(10)), "profit_minor")).isEqualTo(400);
        assertThat(field(row(report, hq, ago(10)), "received_minor")).isZero();
        assertThat(field(row(report, hq, today()), "received_minor")).isEqualTo(1_500);

        // The home page agrees with the report: the unpaid old credit sale is in no Received figure.
        JsonNode dash = dashboard();
        assertThat(dash.get("today").get("received_minor").asLong()).isEqualTo(1_500);
        assertThat(dash.get("last7_received_minor").asLong()).isEqualTo(1_500);
        assertThat(dash.get("last30_received_minor").asLong())
                .as("not 2,500: the credit sale made ten days ago was counted at the sale but nobody paid it")
                .isEqualTo(1_500);
        assertThat(field(dayOf(dash.get("days"), ago(10)), "received_minor")).isZero();
        assertThat(shopOf(dash, hq).get("today_received_minor").asLong()).isEqualTo(1_500);
        assertThat(dash.get("today").get("gross_profit_minor").asLong())
                .as("profit is a separate leg")
                .isEqualTo(600);
    }

    /** The shop row of one branch in the dashboard. */
    JsonNode shopOf(JsonNode dash, UUID branch) {
        for (JsonNode shop : dash.get("shops")) {
            if (shop.get("branch_id").asString().equals(branch.toString())) {
                return shop;
            }
        }
        throw new AssertionError("no shop row for " + branch);
    }

    @Test
    void aPaymentOnAnOlderCreditSaleIsReceivedWhenItIsCollectedWhateverTheSalesDate() {
        onPayment();
        settle(ago(2).toString());
        // Made and entered ten days ago, before the start: counted at the sale, 400 of profit once.
        UUID old = creditSale(hq, 1, ago(10));
        sellEntered(old, ago(10));
        pay(old, 400, ago(10));
        payEnteredToday(old, 600, today());

        JsonNode report = profit(ago(12), today());
        assertThat(field(row(report, hq, ago(10)), "received_minor"))
                .as("the 400 deposit on the day it was collected")
                .isEqualTo(400);
        assertThat(field(row(report, hq, today()), "received_minor"))
                .as("the rest, on the day it was collected, though the sale is older and not counted when paid")
                .isEqualTo(600);
        assertThat(report.get("received_minor").asLong()).isEqualTo(1_000);
        assertThat(report.get("profit_minor").asLong())
                .as("profit counted once, at the sale")
                .isEqualTo(400);
        assertThat(field(row(report, hq, ago(10)), "profit_minor")).isEqualTo(400);
        assertThat(field(row(report, hq, today()), "profit_minor")).isZero();

        JsonNode dash = dashboard();
        assertThat(dash.get("today").get("received_minor").asLong()).isEqualTo(600);
        assertThat(field(dayOf(dash.get("days"), ago(10)), "received_minor")).isEqualTo(400);
        assertThat(dash.get("last7_received_minor").asLong()).isEqualTo(600);
        assertThat(dash.get("last30_received_minor").asLong()).isEqualTo(1_000);
        assertThat(shopOf(dash, hq).get("today_received_minor").asLong()).isEqualTo(600);
    }

    @Test
    void anImportedCreditSaleIsTreatedTheSameInTheReportAndOnTheHomeCards() {
        onPaymentFromTheStart();
        UUID sale = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, buyer_name,
                            currency, total_minor, cost_total_minor, paid_minor, status, historical, created_by)
                        VALUES (?, ?, ?, 'IMP-0002', ?, 'credit', 'Test Buyer 16',
                            (SELECT currency FROM tenants WHERE id = ?), 5000, 3000, 0, 'completed', true, ?)
                        """)
                .params(sale, t.tenantId(), hq, java.sql.Date.valueOf(ago(5)), t.tenantId(), api.userId())
                .update();
        TestDatabase.owner().sql("""
                        INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor,
                            unit_cost_minor, line_total_minor, line_cost_minor)
                        VALUES (gen_random_uuid(), ?, ?, 1, ?, 5, 1000, 600, 5000, 3000)
                        """).params(t.tenantId(), sale, product).update();
        // The product refuses payments on an imported sale, so none is recorded; the report and the cards must still
        // agree.
        JsonNode report = profit(ago(6), today());
        JsonNode dash = dashboard();
        assertThat(report.get("received_minor").asLong())
                .as("nothing was collected: the 5,000 of the imported sale was settled outside the system")
                .isZero();
        assertThat(dash.get("last30_received_minor").asLong())
                .isEqualTo(report.get("received_minor").asLong());
        assertThat(report.get("counted_at_sale_minor").asLong())
                .as("an imported credit sale is counted at the sale but is not in the credit profit tile")
                .isZero();
        assertThat(report.get("profit_minor").asLong())
                .as("yet its profit is in the profit")
                .isEqualTo(2_000);
        assertThat(dash.get("today").get("received_minor").asLong()).isZero();
    }

    @Test
    void aPaymentRecordedOnAnImportedCreditSaleBeforeItWasRefusedIsReceivedOnTheDayItWasEntered() {
        onPaymentFromTheStart();
        // Issue #375: before #360 an imported credit sale could still take a payment, so such rows exist. The owner
        // seeds
        // one as that route wrote it: dated ten days ago, entered three days ago, with its journal entry.
        UUID sale = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, buyer_name,
                            currency, total_minor, cost_total_minor, paid_minor, status, historical, created_by)
                        VALUES (?, ?, ?, 'IMP-0003', ?, 'credit', 'Test Buyer 17',
                            (SELECT currency FROM tenants WHERE id = ?), 5000, 3000, 2000, 'completed', true, ?)
                        """)
                .params(sale, t.tenantId(), hq, java.sql.Date.valueOf(ago(20)), t.tenantId(), api.userId())
                .update();
        TestDatabase.owner().sql("""
                        INSERT INTO retail_sale_lines (id, tenant_id, sale_id, line_no, product_id, qty, unit_price_minor,
                            unit_cost_minor, line_total_minor, line_cost_minor)
                        VALUES (gen_random_uuid(), ?, ?, 1, ?, 5, 1000, 600, 5000, 3000)
                        """).params(t.tenantId(), sale, product).update();
        LocalDate paidOn = ago(10);
        TestDatabase.owner()
                .sql("""
                        INSERT INTO gl_periods (id, tenant_id, year, month, status) VALUES (gen_random_uuid(), ?, ?, ?, 'open')
                        ON CONFLICT (tenant_id, year, month) DO NOTHING
                        """)
                .params(t.tenantId(), paidOn.getYear(), paidOn.getMonthValue())
                .update();
        UUID period = TestDatabase.owner()
                .sql("SELECT id FROM gl_periods WHERE tenant_id = ? AND year = ? AND month = ?")
                .params(t.tenantId(), paidOn.getYear(), paidOn.getMonthValue())
                .query(UUID.class)
                .single();
        UUID entry = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        WITH e AS (INSERT INTO journal_entries (id, tenant_id, branch_id, entry_no, entry_date, period_id,
                                       reference, source_module, source_type)
                                   VALUES (?, ?, ?, 'JE90000375', ?, ?, 'IMP-0003', 'test', 'fixture')
                                   RETURNING id)
                        INSERT INTO journal_lines (id, tenant_id, entry_id, line_no, account_id, debit, credit, currency)
                        SELECT gen_random_uuid(), ?, e.id, 1, ?, 2000, 0, (SELECT currency FROM tenants WHERE id = ?) FROM e
                        UNION ALL SELECT gen_random_uuid(), ?, e.id, 2, ?, 0, 2000, (SELECT currency FROM tenants WHERE id = ?)
                        FROM e
                        """)
                .params(
                        entry,
                        t.tenantId(),
                        hq,
                        java.sql.Date.valueOf(paidOn),
                        period,
                        t.tenantId(),
                        t.account("cash_on_hand"),
                        t.tenantId(),
                        t.tenantId(),
                        t.account("opening_balance_equity"),
                        t.tenantId())
                .update();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO retail_sale_payments (id, tenant_id, created_at, sale_id, amount_minor, currency, method,"
                                + " paid_on, journal_entry_id, created_by)"
                                + " VALUES (gen_random_uuid(), ?, " + enteredAt(ago(3)) + ", ?, 2000,"
                                + " (SELECT currency FROM tenants WHERE id = ?), 'cash', ?, ?, ?)")
                .params(t.tenantId(), sale, t.tenantId(), java.sql.Date.valueOf(paidOn), entry, api.userId())
                .update();

        JsonNode report = profit(ago(21), today());
        assertThat(field(row(report, hq, ago(3)), "received_minor"))
                .as("money recorded as collected, on the day it was entered")
                .isEqualTo(2_000);
        assertThat(field(row(report, hq, paidOn), "received_minor"))
                .as("not on the earlier date written on it")
                .isZero();
        assertThat(report.get("received_minor").asLong()).isEqualTo(2_000);
        assertThat(report.get("profit_minor").asLong())
                .as("the imported sale keeps its profit at the sale, once")
                .isEqualTo(2_000);
        assertThat(field(row(report, hq, ago(20)), "profit_minor")).isEqualTo(2_000);
        assertThat(field(row(report, hq, ago(3)), "profit_minor")).isZero();

        JsonNode dash = dashboard();
        assertThat(field(dayOf(dash.get("days"), ago(3)), "received_minor")).isEqualTo(2_000);
        assertThat(dash.get("last7_received_minor").asLong()).isEqualTo(2_000);
        assertThat(dash.get("last30_received_minor").asLong())
                .isEqualTo(report.get("received_minor").asLong());
    }

    @Test
    void theWhenPaidFieldsOfTheHomeCardsAppearOnlyOnceTheRuleHasStarted() {
        onPayment();
        JsonNode pending = dashboard();
        assertThat(pending.get("credit_sale_recognition").asString()).isEqualTo("on_sale");
        assertThat(pending.get("today").has("received_minor")).isFalse();
        assertThat(pending.get("today").has("on_credit_minor")).isFalse();
        assertThat(pending.get("today").has("on_credit_count")).isFalse();
        assertThat(pending.has("last7_received_minor")).isFalse();
        assertThat(pending.has("last30_received_minor")).isFalse();
        settle(today().toString());
        JsonNode started = dashboard();
        assertThat(started.get("credit_sale_recognition").asString()).isEqualTo("on_payment");
        assertThat(started.get("today").has("received_minor")).isTrue();
        assertThat(started.get("today").has("on_credit_minor")).isTrue();
        assertThat(started.has("last7_received_minor")).isTrue();
    }

    @Test
    void aReportThatEndsBeforeTheStartStillShowsABackdatedCreditSale() {
        onPayment();
        settle(ago(2).toString());
        // Entered today, dated three days ago, before the start: counted when the buyer pays.
        creditSale(hq, 1, ago(3));
        JsonNode report = profit(ago(3), ago(3));
        assertThat(report.get("credit_sale_recognition").asString()).isEqualTo("on_payment");
        assertThat(report.get("sold_minor").asLong())
                .as("it is Sold on its date, in a report that ends before the start day")
                .isEqualTo(1_000);
        assertThat(report.get("on_credit_minor").asLong()).as("and owed now").isEqualTo(1_000);
        assertThat(report.get("sales_minor").asLong()).isZero();
        assertThat(report.get("profit_minor").asLong()).isZero();
    }

    @Test
    void aBackdatedCreditSaleEnteredAfterTheStartAddsNothingToItsDayOnTheHomeCards() {
        onPayment();
        settle(ago(2).toString());
        UUID backdated = creditSale(hq, 1, ago(3));
        JsonNode days = dashboard().get("days");
        assertThat(field(dayOf(days, ago(3)), "gross_profit_minor"))
                .as("the 400 must not land three days back")
                .isZero();
        payEnteredToday(backdated, 1_000, today());
        JsonNode after = dashboard().get("days");
        assertThat(field(dayOf(after, ago(3)), "gross_profit_minor")).isZero();
        assertThat(field(dayOf(after, today()), "gross_profit_minor")).isEqualTo(400);
    }

    @Test
    void aPaymentOnAccountThatPaysABackdatedSaleEnteredAfterTheStartCannotBeBackdated() {
        capability(Map.of("customer_accounts", true));
        onPayment();
        settle(ago(2).toString());
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 14"), ADMIN));
        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("branch_id", hq);
        sale.put("sale_date", ago(3).toString());
        sale.put("payment_method", "credit");
        sale.put("customer_id", buyer);
        sale.put("lines", List.of(Map.of("product_id", product, "qty", "2")));
        assertThat(api.postKeyed("/sales", sale, ADMIN, "*", UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> refused = api.postKeyed(
                "/customers/" + buyer + "/payments",
                Map.of("branch_id", hq, "amount_minor", 500, "method", "cash", "paid_on", ago(1).toString()),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().toString()).contains("backdated_payment");
        ResponseEntity<JsonNode> accepted = api.postKeyed(
                "/customers/" + buyer + "/payments",
                Map.of("branch_id", hq, "amount_minor", 500, "method", "cash"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(accepted.getStatusCode()).as("%s", accepted.getBody()).isEqualTo(HttpStatus.CREATED);
        // The sale's open-balance row says the payment is dated today.
        assertThat(api.get("/customers/" + buyer + "/balance", ADMIN)
                        .getBody()
                        .get("open_sales")
                        .get(0)
                        .get("counted_when_paid")
                        .asBoolean())
                .isTrue();
    }

    /** The row of one day in the dashboard's 30 days, or null. */
    JsonNode dayOf(JsonNode days, LocalDate day) {
        for (JsonNode d : days) {
            if (d.get("date").asString().equals(day.toString())) {
                return d;
            }
        }
        return null;
    }

    @Test
    void aReturnCreditedToTheBalanceIsSpreadOverTheTillsThatTookTheMoney() {
        capability(Map.of("customer_accounts", true));
        onPaymentFromTheStart();
        UUID cheap = api.product("CRD-3", 200, 1_000);
        api.importedBalance(hq, cheap, "1000");
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 12"), ADMIN));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("sale_date", ago(1).toString());
        body.put("payment_method", "credit");
        body.put("customer_id", buyer);
        body.put("lines", List.of(Map.of("product_id", product, "qty", "2"), Map.of("product_id", cheap, "qty", "2")));
        UUID sale = RetailTestSupport.id(
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString()));
        for (Object[] take : new Object[][] {{br2, 1_500}, {hq, 500}}) {
            ResponseEntity<JsonNode> receipt = api.postKeyed(
                    "/customers/" + buyer + "/payments",
                    Map.of("branch_id", take[0], "amount_minor", take[1], "method", "cash"),
                    ADMIN,
                    "*",
                    UUID.randomUUID().toString());
            assertThat(receipt.getStatusCode()).as("%s", receipt.getBody()).isEqualTo(HttpStatus.CREATED);
        }
        JsonNode before = profit(ago(1), today());
        // 2,000 paid of 4,000 (cost 1,600): revenue 2,000, cost 800, profit 1,200, split 900 and 300 by what each till
        // took.
        assertThat(before.get("profit_minor").asLong()).isEqualTo(1_200);
        assertThat(field(row(before, br2, today()), "profit_minor")).isEqualTo(900);
        assertThat(field(row(before, hq, today()), "profit_minor")).isEqualTo(300);

        // The two dear pieces (value 2,000, cost 1,200) go back for credit: nothing is paid out, so the cost no longer
        // carried is
        // taken back from both tills in proportion to the money they took, 3 to 1.
        giveBack(sale, "2", "account");
        JsonNode report = profit(ago(1), today());
        assertThat(report.get("profit_minor").asLong()).isEqualTo(1_600);
        assertThat(field(row(report, br2, today()), "profit_minor")).isEqualTo(1_200);
        assertThat(field(row(report, hq, today()), "profit_minor")).isEqualTo(400);
        assertThat(field(row(before, br2, today()), "received_minor")).isEqualTo(1_500);
        assertThat(field(row(before, hq, today()), "received_minor")).isEqualTo(500);
        assertThat(field(row(report, br2, today()), "received_minor"))
                .as("a return credited to the balance pays nothing out")
                .isEqualTo(1_500);
        assertThat(field(row(report, hq, today()), "received_minor")).isEqualTo(500);
        JsonNode dash = dashboard();
        assertThat(shopOf(dash, br2).get("today_received_minor").asLong()).isEqualTo(1_500);
        assertThat(shopOf(dash, hq).get("today_received_minor").asLong()).isEqualTo(500);
    }

    @Test
    void underTheRuleAPaymentCannotBeBackdated() {
        onPaymentFromTheStart();
        UUID credit = creditSale(hq, 2, ago(5));
        ResponseEntity<JsonNode> refused = payRaw(credit, 2_000, ago(2));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().toString()).contains("backdated_payment").contains("dated today");
        assertThat(api.get("/sales/" + credit, ADMIN)
                        .getBody()
                        .get("paid_minor")
                        .asLong())
                .isZero();
        assertThat(payRaw(credit, 2_000, today()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void underTheDefaultABackdatedPaymentIsAcceptedAsBefore() {
        UUID credit = creditSale(hq, 2, ago(5));
        assertThat(payRaw(credit, 2_000, ago(2)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aPaymentOnAccountCannotBeBackdatedUnderTheRuleEither() {
        capability(Map.of("customer_accounts", true));
        onPaymentFromTheStart();
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 10"), ADMIN));
        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("branch_id", hq);
        sale.put("sale_date", ago(5).toString());
        sale.put("payment_method", "credit");
        sale.put("customer_id", buyer);
        sale.put("lines", List.of(Map.of("product_id", product, "qty", "2")));
        assertThat(api.postKeyed("/sales", sale, ADMIN, "*", UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> refused = api.postKeyed(
                "/customers/" + buyer + "/payments",
                Map.of("branch_id", hq, "amount_minor", 500, "method", "cash", "paid_on", ago(2).toString()),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().toString()).contains("backdated_payment");
    }

    @Test
    void aPaymentOnAccountForOnlyOlderSalesKeepsItsFreedom() {
        capability(Map.of("customer_accounts", true));
        onPayment();
        settle(ago(2).toString());
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 13"), ADMIN));
        Map<String, Object> sale = new LinkedHashMap<>();
        sale.put("branch_id", hq);
        sale.put("sale_date", ago(5).toString());
        sale.put("payment_method", "credit");
        sale.put("customer_id", buyer);
        sale.put("lines", List.of(Map.of("product_id", product, "qty", "2")));
        ResponseEntity<JsonNode> made =
                api.postKeyed("/sales", sale, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(made.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        sellEntered(RetailTestSupport.id(made), ago(5));
        ResponseEntity<JsonNode> accepted = api.postKeyed(
                "/customers/" + buyer + "/payments",
                Map.of("branch_id", hq, "amount_minor", 500, "method", "cash", "paid_on", ago(3).toString()),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(accepted.getStatusCode()).as("%s", accepted.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aPaymentOnAccountBelongsToTheBranchThatTookIt() {
        capability(Map.of("customer_accounts", true));
        onPaymentFromTheStart();
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 07"), ADMIN));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("sale_date", ago(1).toString());
        body.put("payment_method", "credit");
        body.put("customer_id", buyer);
        body.put("lines", List.of(Map.of("product_id", product, "qty", "2")));
        UUID sale = RetailTestSupport.id(
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString()));
        ResponseEntity<JsonNode> receipt = api.postKeyed(
                "/customers/" + buyer + "/payments",
                Map.of("branch_id", br2, "amount_minor", 2_000, "method", "cash"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(receipt.getStatusCode()).as("%s", receipt.getBody()).isEqualTo(HttpStatus.CREATED);

        JsonNode report = profit(ago(1), today());
        assertThat(row(report, hq, today()))
                .as("the sale's shop never got the money")
                .isNull();
        assertThat(field(row(report, br2, today()), "profit_minor")).isEqualTo(800);
        assertThat(suggestion(br2, today()).get("suggested_minor").asLong()).isEqualTo(400);
        assertThat(suggestion(hq, today()).get("suggested_minor").asLong()).isZero();
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT coalesce(sum(l.debit - l.credit), 0) FROM journal_lines l
                                  JOIN journal_entries e ON e.id = l.entry_id JOIN gl_accounts a ON a.id = l.account_id
                                 WHERE e.tenant_id = ? AND e.branch_id = ? AND a.system_key = 'cash_on_hand'
                                """)
                        .params(t.tenantId(), br2)
                        .query(Long.class)
                        .single())
                .as("the cash is in the receiving branch's till, where the savings are set aside")
                .isEqualTo(2_000);
        assertThat(api.get("/sales/" + sale, ADMIN)
                        .getBody()
                        .get("balance_minor")
                        .asLong())
                .isZero();
    }

    @Test
    void reversingAPaymentOnAccountTakesItsProfitBackOnTheDayOfTheReversal() {
        capability(Map.of("customer_accounts", true));
        onPaymentFromTheStart();
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 08"), ADMIN));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("sale_date", ago(1).toString());
        body.put("payment_method", "credit");
        body.put("customer_id", buyer);
        body.put("lines", List.of(Map.of("product_id", product, "qty", "2")));
        RetailTestSupport.id(
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString()));
        ResponseEntity<JsonNode> receipt = api.postKeyed(
                "/customers/" + buyer + "/payments",
                Map.of("branch_id", hq, "amount_minor", 2_000, "method", "cash"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        UUID receiptId = RetailTestSupport.id(receipt);
        moveReceiptEntered(receiptId, ago(1));
        assertThat(field(row(profit(ago(1), today()), hq, ago(1)), "profit_minor"))
                .isEqualTo(800);

        ResponseEntity<JsonNode> undone = api.postKeyed(
                "/customers/" + buyer + "/payments/" + receiptId + "/reverse",
                Map.of("reason", "Entered by mistake"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(undone.getStatusCode().is2xxSuccessful())
                .as("%s", undone.getBody())
                .isTrue();
        JsonNode report = profit(ago(1), today());
        assertThat(field(row(report, hq, ago(1)), "profit_minor"))
                .as("the day it was received is not rewritten")
                .isEqualTo(800);
        assertThat(field(row(report, hq, today()), "profit_minor")).isEqualTo(-800);
        assertThat(report.get("profit_minor").asLong()).isZero();
        assertThat(report.get("on_credit_minor").asLong())
                .as("the debt is owed again")
                .isEqualTo(2_000);
        assertThat(field(row(report, hq, ago(1)), "received_minor"))
                .as("recorded on its day")
                .isEqualTo(2_000);
        assertThat(field(row(report, hq, today()), "received_minor"))
                .as("reversed on the day of the reversal, not on the day it was taken")
                .isEqualTo(-2_000);
        assertThat(report.get("received_minor").asLong()).isZero();
        JsonNode dash = dashboard();
        assertThat(dash.get("today").get("received_minor").asLong()).isEqualTo(-2_000);
        assertThat(field(dayOf(dash.get("days"), ago(1)), "received_minor")).isEqualTo(2_000);
        assertThat(shopOf(dash, hq).get("today_received_minor").asLong()).isEqualTo(-2_000);
        assertThat(dash.get("last30_received_minor").asLong()).isZero();
    }

    @Test
    void aSaleThatDoesNotDivideEvenlyAddsUpToTheWholeProfitThroughSqlAndJava() {
        UUID dear = api.product("CRD-2", 700, 1_000);
        api.importedBalance(hq, dear, "1000");
        onPaymentFromTheStart();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("sale_date", ago(3).toString());
        body.put("payment_method", "credit");
        body.put("buyer_name", "Test Buyer 09");
        body.put("lines", List.of(Map.of("product_id", dear, "qty", "100")));
        UUID sale = RetailTestSupport.id(
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString()));
        pay(sale, 33_333, ago(2));
        pay(sale, 66_667, ago(1));
        JsonNode report = profit(ago(3), today());
        assertThat(field(row(report, hq, ago(2)), "profit_minor")).isEqualTo(10_000);
        assertThat(field(row(report, hq, ago(1)), "profit_minor")).isEqualTo(20_000);
        assertThat(report.get("profit_minor").asLong()).isEqualTo(30_000);
        assertThat(report.get("sales_minor").asLong()).isEqualTo(100_000);
        assertThat(report.get("cost_of_sales_minor").asLong()).isEqualTo(70_000);
    }

    @Test
    void aReturnRefundedFromAnotherTillTakesTheProfitBackFromThatTill() {
        capability(Map.of("customer_accounts", true));
        onPaymentFromTheStart();
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 11"), ADMIN));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("sale_date", ago(1).toString());
        body.put("payment_method", "credit");
        body.put("customer_id", buyer);
        body.put("lines", List.of(Map.of("product_id", product, "qty", "2")));
        UUID sale = RetailTestSupport.id(
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString()));
        ResponseEntity<JsonNode> receipt = api.postKeyed(
                "/customers/" + buyer + "/payments",
                Map.of("branch_id", br2, "amount_minor", 2_000, "method", "cash"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(receipt.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(field(row(profit(ago(1), today()), br2, today()), "profit_minor"))
                .isEqualTo(800);

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sale_line_id", lineOf(sale));
        line.put("qty", "2");
        line.put("reason", "defective");
        line.put("disposition", "restock");
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("lines", List.of(line));
        ret.put("refund_method", "cash");
        ret.put("refund_branch_id", br2);
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + sale + "/returns",
                ret,
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);

        JsonNode report = profit(ago(1), today());
        assertThat(field(row(report, br2, today()), "profit_minor"))
                .as("the till that paid the cash out takes the profit back")
                .isZero();
        assertThat(field(row(report, hq, today()), "profit_minor"))
                .as("the sale's shop never had the money and does not go negative")
                .isZero();
        assertThat(report.get("profit_minor").asLong()).isZero();
        assertThat(field(row(report, br2, today()), "received_minor"))
                .as("2,000 taken and 2,000 paid back at the same till")
                .isZero();
        assertThat(field(row(report, hq, today()), "received_minor")).isZero();
        assertThat(report.get("received_minor").asLong()).isZero();
        JsonNode dash = dashboard();
        assertThat(dash.get("today").get("received_minor").asLong()).isZero();
        assertThat(shopOf(dash, br2).get("today_received_minor").asLong()).isZero();
    }

    // ---- Received: the money recorded as collected, by the day it was entered (ADR-043)

    /** A sale of {@code prod} with one piece, credit or cash, dated {@code date}. */
    UUID oneOf(UUID prod, String method, LocalDate date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("sale_date", date.toString());
        body.put("payment_method", method);
        if (method.equals("credit")) {
            body.put("buyer_name", "Test Buyer 17");
        }
        body.put("lines", List.of(Map.of("product_id", prod, "qty", "1")));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    /** A cash return of all of a one piece sale, the money paid back from {@code refundBranch}. */
    JsonNode giveBackCashFrom(UUID sale, UUID refundBranch) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sale_line_id", lineOf(sale));
        line.put("qty", "1");
        line.put("reason", "defective");
        line.put("disposition", "restock");
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("lines", List.of(line));
        ret.put("refund_method", "cash");
        ret.put("refund_branch_id", refundBranch);
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + sale + "/returns",
                ret,
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    @Test
    void moneyPaidBackOnACreditSaleReturnLeavesTheRefundBranchNotTheSalesBranch() {
        onPayment();
        settle(ago(2).toString());
        UUID cheap = api.product("CRD-4", 100, 400);
        api.importedBalance(hq, cheap, "100");
        UUID old = oneOf(cheap, "credit", ago(10));
        sellEntered(old, ago(10));
        payEnteredToday(old, 400, today());
        giveBackCashFrom(old, br2);

        JsonNode report = profit(ago(12), today());
        assertThat(field(row(report, hq, today()), "received_minor"))
                .as("taken at the sale's branch")
                .isEqualTo(400);
        assertThat(field(row(report, br2, today()), "received_minor"))
                .as("paid back at the refund branch")
                .isEqualTo(-400);
        assertThat(report.get("received_minor").asLong()).isZero();
        JsonNode dash = dashboard();
        assertThat(shopOf(dash, hq).get("today_received_minor").asLong()).isEqualTo(400);
        assertThat(shopOf(dash, br2).get("today_received_minor").asLong()).isEqualTo(-400);
        assertThat(dash.get("today").get("received_minor").asLong()).isZero();
    }

    @Test
    void moneyPaidBackOnACashSaleReturnLeavesTheSameRefundBranch() {
        onPayment();
        settle(ago(2).toString());
        UUID cheap = api.product("CRD-5", 100, 400);
        api.importedBalance(hq, cheap, "100");
        UUID cash = oneOf(cheap, "cash", today());
        giveBackCashFrom(cash, br2);

        JsonNode report = profit(ago(12), today());
        assertThat(field(row(report, hq, today()), "received_minor"))
                .as("taken at the sale")
                .isEqualTo(400);
        assertThat(field(row(report, br2, today()), "received_minor"))
                .as("paid back at the refund branch, as for a credit sale")
                .isEqualTo(-400);
        JsonNode dash = dashboard();
        assertThat(shopOf(dash, hq).get("today_received_minor").asLong()).isEqualTo(400);
        assertThat(shopOf(dash, br2).get("today_received_minor").asLong()).isEqualTo(-400);
    }

    @Test
    void aPaymentDatedEarlierThanItWasEnteredCountsOnTheDayItWasEntered() {
        onPayment();
        settle(ago(2).toString());
        UUID old = creditSale(hq, 1, ago(10));
        sellEntered(old, ago(10));
        payEnteredToday(old, 600, ago(3));

        JsonNode report = profit(ago(12), today());
        assertThat(field(row(report, hq, ago(3)), "received_minor"))
                .as("not on the day written on it: that day was reported already")
                .isZero();
        assertThat(field(row(report, hq, today()), "received_minor")).isEqualTo(600);
        JsonNode dash = dashboard();
        assertThat(field(dayOf(dash.get("days"), ago(3)), "received_minor")).isZero();
        assertThat(dash.get("today").get("received_minor").asLong()).isEqualTo(600);
    }

    @Test
    void aVoidedReturnGivesTheMoneyBackOnTheDayItIsVoidedAndLeavesTheDayItWasMade() {
        onPayment();
        settle(ago(2).toString());
        UUID old = creditSale(hq, 1, ago(10));
        sellEntered(old, ago(10));
        pay(old, 1_000, ago(10));
        JsonNode made = giveBack(old, "1", "cash");
        // The return was made three days ago, and is voided today.
        TestDatabase.owner()
                .sql("DO $$ BEGIN PERFORM set_config('bms.allow_mutation', 'on', true);"
                        + " UPDATE retail_sale_returns SET returned_on = DATE '" + ago(3) + "' WHERE id = '"
                        + made.get("sale_return").get("id").asString() + "'; END $$")
                .update();
        ResponseEntity<JsonNode> voided = api.postKeyed(
                "/returns/" + made.get("sale_return").get("id").asString() + "/void",
                Map.of("reason", "Taken back by mistake"),
                RETURNER,
                "*",
                UUID.randomUUID().toString());
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);

        JsonNode report = profit(ago(12), today());
        assertThat(field(row(report, hq, ago(10)), "received_minor")).isEqualTo(1_000);
        assertThat(field(row(report, hq, ago(3)), "received_minor"))
                .as("the refund stays on the day it was made")
                .isEqualTo(-1_000);
        assertThat(field(row(report, hq, today()), "received_minor"))
                .as("and comes back on the day the return is voided")
                .isEqualTo(1_000);
        assertThat(report.get("received_minor").asLong()).isEqualTo(1_000);
        JsonNode dash = dashboard();
        assertThat(field(dayOf(dash.get("days"), ago(3)), "received_minor")).isEqualTo(-1_000);
        assertThat(dash.get("today").get("received_minor").asLong()).isEqualTo(1_000);
        assertThat(dash.get("last30_received_minor").asLong()).isEqualTo(1_000);
    }

    @Test
    void aCashSaleDatedInThePastCountsAsReceivedOnTheDayItWasEntered() {
        onPayment();
        settle(ago(2).toString());
        cashSale(hq, 1, ago(3));
        JsonNode report = profit(ago(5), today());
        assertThat(field(row(report, hq, ago(3)), "received_minor"))
                .as("not on the day written on the sale: that day was reported already")
                .isZero();
        assertThat(field(row(report, hq, today()), "received_minor")).isEqualTo(1_000);
        assertThat(report.get("received_minor").asLong()).isEqualTo(1_000);
        JsonNode dash = dashboard();
        assertThat(field(dayOf(dash.get("days"), ago(3)), "received_minor")).isZero();
        assertThat(dash.get("today").get("received_minor").asLong()).isEqualTo(1_000);
        assertThat(shopOf(dash, hq).get("today_received_minor").asLong()).isEqualTo(1_000);
    }

    @Test
    void aPaymentOnAccountTakenAtAnotherBranchAndReversedLeavesBothBranchesAtZero() {
        capability(Map.of("customer_accounts", true));
        onPayment();
        settle(ago(2).toString());
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 19"), ADMIN));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("sale_date", ago(10).toString());
        body.put("payment_method", "credit");
        body.put("customer_id", buyer);
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        UUID old = RetailTestSupport.id(
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString()));
        sellEntered(old, ago(10));
        ResponseEntity<JsonNode> receipt = api.postKeyed(
                "/customers/" + buyer + "/payments",
                Map.of("branch_id", br2, "amount_minor", 600, "method", "cash"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        UUID receiptId = RetailTestSupport.id(receipt);
        ResponseEntity<JsonNode> undone = api.postKeyed(
                "/customers/" + buyer + "/payments/" + receiptId + "/reverse",
                Map.of("reason", "Entered by mistake"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(undone.getStatusCode().is2xxSuccessful())
                .as("%s", undone.getBody())
                .isTrue();

        JsonNode report = profit(ago(12), today());
        assertThat(field(row(report, hq, today()), "received_minor"))
                .as("HQ never took the money")
                .isZero();
        assertThat(field(row(report, br2, today()), "received_minor"))
                .as("taken and given back at the branch that took it")
                .isZero();
        assertThat(report.get("received_minor").asLong()).isZero();
        JsonNode dash = dashboard();
        assertThat(shopOf(dash, br2).get("today_received_minor").asLong()).isZero();
        assertThat(dash.get("today").get("received_minor").asLong()).isZero();
    }

    @Test
    void aBranchThatOnlyTookPaymentsOnAnOldCreditSaleHasItsShopRowForAReaderWithoutStockAccess() {
        capability(Map.of("customer_accounts", true));
        onPayment();
        settle(ago(2).toString());
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 18"), ADMIN));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("sale_date", ago(10).toString());
        body.put("payment_method", "credit");
        body.put("customer_id", buyer);
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        UUID old = RetailTestSupport.id(
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString()));
        sellEntered(old, ago(10));
        ResponseEntity<JsonNode> receipt = api.postKeyed(
                "/customers/" + buyer + "/payments",
                Map.of("branch_id", br2, "amount_minor", 600, "method", "cash"),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(receipt.getStatusCode()).as("%s", receipt.getBody()).isEqualTo(HttpStatus.CREATED);

        JsonNode dash = api.get("/reports/dashboard", "retail.sale.read,retail.sale.all_sellers")
                .getBody();
        assertThat(shopOf(dash, br2).get("today_received_minor").asLong())
                .as("a branch with no sales and no stock access still has the money it took")
                .isEqualTo(600);
        assertThat(dash.get("today").get("received_minor").asLong()).isEqualTo(600);
    }
}
