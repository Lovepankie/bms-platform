package com.rincoltech.bms.retail.reports;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 * The shop Home summary (issue #485, ADR-076). The data of {@link AnalyticsData} (yesterday 2 ANA-1 cash at head office, 3,000;
 * ten days ago 3 ANA-2 on credit there, 900, due in 20 days; today a voided cash sale there and 1 ANA-1 cash at branch two,
 * 1,500) plus: five days ago 2 ANA-2 on credit at head office (600, due in 25 days), paid today 200 in cash and 100 by bank;
 * today 2 ANA-2 by mobile money at head office (600) and 1 ANA-1 on credit there to a second buyer (1,500, due in 3 days); and
 * one open customer order at head office. Today: sales 3,600 in 3 sales; takings cash 1,700 (1,500 sold, 200 paid on credit),
 * mobile money 600, bank 100, 2,400 in all; owed 900 + 300 + 1,500 = 2,700 by 2 buyers on 3 sales, the oldest due in 3 days;
 * head office 2,100 in 2 sales, branch two 1,500 in 1.
 */
class RetailHomeSummaryIT extends IntegrationTest {

    static final String CASHBOOK = "retail.cashbook.read,retail.till.close";
    /** The admin column with the cash book read, which the takings need as the cash summary does. */
    static final String TILL = ADMIN + ",retail.cashbook.read";

    static final String ORDERS = TILL + ",retail.order.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    AnalyticsData d;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-home", false, true);
        api = new RetailTestSupport(http, t);
        d = new AnalyticsData(t, api);
        d.products();
        d.stock();
        d.sales();
        UUID owing = d.sell(t.headOffice(), "credit", d.p2, "2", 5);
        pay(owing, 200, "cash");
        pay(owing, 100, "bank");
        d.sell(t.headOffice(), "mobile_money", d.p2, "2", 0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "credit");
        body.put("buyer_name", "Test Buyer 02");
        body.put("due_date", today().plusDays(3).toString());
        body.put("lines", List.of(Map.of("product_id", d.p1, "qty", "1")));
        ResponseEntity<JsonNode> sale =
                api.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(sale.getStatusCode()).as("%s", sale.getBody()).isEqualTo(HttpStatus.CREATED);
        order(0);
    }

    /** An order at head office for 1 ANA-3 (700), to deliver today, with this delivery fee. */
    UUID order(long fee) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("branch_id", t.headOffice());
        order.put("source", "whatsapp");
        order.put("customer_name", "Test Customer 01");
        order.put("customer_phone", "+256700000011");
        order.put("delivery_place", "Test Place, Plot 1");
        order.put("delivery_date", today().toString());
        order.put("delivery_fee_minor", fee);
        order.put("lines", List.of(Map.of("product_id", d.p3, "qty", "1")));
        ResponseEntity<JsonNode> placed =
                api.postKeyed("/orders", order, ORDERS, "*", UUID.randomUUID().toString());
        assertThat(placed.getStatusCode()).as("%s", placed.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(placed);
    }

    static LocalDate today() {
        return AnalyticsData.today();
    }

    UUID pay(UUID sale, long amount, String method) {
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + sale + "/payments",
                Map.of("amount_minor", amount, "method", method),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(r.getBody().get("payment").get("id").asString());
    }

    /** Dates a payment {@code paidOn} and moves the moment it was entered to {@code entered}, Kampala time, as the owner. */
    static void entered(UUID payment, LocalDate paidOn, String entered) {
        TestDatabase.owner()
                .sql("DO $$ BEGIN PERFORM set_config('bms.allow_mutation', 'on', true);"
                        + " UPDATE retail_sale_payments SET paid_on = DATE '" + paidOn + "', created_at = (TIMESTAMP '"
                        + entered + "') AT TIME ZONE 'Africa/Kampala' WHERE id = '" + payment + "'; END $$")
                .update();
    }

    long cash(String query, String branches) {
        return method(home(query, TILL, branches).get("takings"), "cash")
                .get("amount_minor")
                .asLong();
    }

    JsonNode home(String query, String permissions, String branches) {
        ResponseEntity<JsonNode> r =
                api.call(HttpMethod.GET, "/reports/home" + query, null, permissions, branches, Map.of());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    static JsonNode method(JsonNode takings, String method) {
        for (JsonNode m : takings.get("methods")) {
            if (m.get("method").asString().equals(method)) {
                return m;
            }
        }
        return null;
    }

    @Test
    void todaysSalesAndTakingsByCashMobileMoneyAndBank() {
        JsonNode r = home("", ORDERS, "*");
        assertThat(r.get("date").asString()).isEqualTo(today().toString());
        assertThat(r.get("week_from").asString())
                .isEqualTo(today().with(DayOfWeek.MONDAY).toString());
        assertThat(r.get("sales_today").get("sales_minor").asLong()).isEqualTo(3_600);
        assertThat(r.get("sales_today").get("sale_count").asLong()).isEqualTo(3);

        JsonNode takings = r.get("takings");
        assertThat(takings.get("total_minor").asLong()).isEqualTo(2_400);
        assertThat(takings.get("methods").get(0).get("method").asString()).isEqualTo("cash");
        assertThat(takings.get("methods").get(1).get("method").asString()).isEqualTo("mobile_money");
        JsonNode cash = method(takings, "cash");
        // The cash book's reading: the cash sale at head office dated today counts in and, cancelled today, out.
        assertThat(cash.get("amount_minor").asLong()).isEqualTo(1_700);
        assertThat(cash.get("sales_minor").asLong()).isEqualTo(3_000);
        assertThat(cash.get("payments_minor").asLong()).isEqualTo(200);
        assertThat(cash.get("fees_minor").asLong()).isZero();
        assertThat(cash.get("out_minor").asLong()).isEqualTo(1_500);
        assertThat(method(takings, "mobile_money").get("amount_minor").asLong()).isEqualTo(600);
        assertThat(method(takings, "bank").get("amount_minor").asLong()).isEqualTo(100);
        assertThat(method(takings, "bank").get("payments_minor").asLong()).isEqualTo(100);
        assertCashIsTheCashSummarys(takings);
    }

    /**
     * The cash on the card is today's Cash takings less Cash sales voided or refunded of the cash summary it links to, every
     * shop added up, to the shilling (no return is voided in these tests, so the in and out lines match one for one too).
     */
    void assertCashIsTheCashSummarys(JsonNode takings) {
        ResponseEntity<JsonNode> daily = api.get("/reports/cash/daily?from=" + today() + "&to=" + today(), CASHBOOK);
        assertThat(daily.getStatusCode()).as("%s", daily.getBody()).isEqualTo(HttpStatus.OK);
        long in = 0;
        long out = 0;
        for (JsonNode day : daily.getBody().get("items")) {
            if (day.get("business_date").asString().equals(today().toString())) {
                in += day.get("cash_takings_minor").asLong();
                out += day.get("cash_sale_voids_minor").asLong();
            }
        }
        JsonNode cash = method(takings, "cash");
        assertThat(cash.get("amount_minor").asLong()).isEqualTo(in - out);
        assertThat(cash.get("sales_minor").asLong()
                        + cash.get("payments_minor").asLong()
                        + cash.get("fees_minor").asLong())
                .isEqualTo(in);
        assertThat(cash.get("out_minor").asLong()).isEqualTo(out);
    }

    @Test
    void theSalesTodayAreTheDashboardsFigure() {
        JsonNode dashboard = api.get("/reports/dashboard?period=today", ADMIN).getBody();
        JsonNode r = home("", TILL, "*");
        assertThat(r.get("sales_today").get("sales_minor").asLong())
                .isEqualTo(dashboard.get("today").get("sales_minor").asLong());
        assertThat(r.get("low_stock").asInt())
                .isEqualTo(dashboard.get("stock").get("low_stock").asInt());
    }

    @Test
    void creditOwedIsTheCreditControlTotalWithTheBuyersAndTheOldestDueDate() {
        JsonNode owed = home("", TILL, "*").get("credit_owed");
        assertThat(owed.get("owed_minor").asLong()).isEqualTo(2_700);
        assertThat(owed.get("customers").asInt()).isEqualTo(2);
        assertThat(owed.get("sales").asInt()).isEqualTo(3);
        assertThat(owed.get("oldest_due_date").asString())
                .isEqualTo(today().plusDays(3).toString());
        JsonNode control = api.get("/reports/credit-control", ADMIN).getBody();
        assertThat(owed.get("owed_minor").asLong())
                .isEqualTo(control.get("totals").get("owed_minor").asLong());
        assertThat(owed.get("customers").asInt())
                .isEqualTo(control.get("buyer_count").asInt());
    }

    @Test
    void topFiveItemsTodayAndThisWeek() {
        JsonNode top = home("", TILL, "*").get("top_items");
        JsonNode today = top.get("today");
        assertThat(today).hasSize(2);
        assertThat(today.get(0).get("code").asString()).isEqualTo("ANA-1");
        assertThat(today.get(0).get("sales_minor").asLong()).isEqualTo(3_000);
        assertThat(today.get(0).get("qty").asString()).startsWith("2");
        assertThat(today.get(1).get("code").asString()).isEqualTo("ANA-2");
        assertThat(today.get(1).get("sales_minor").asLong()).isEqualTo(600);

        // This week runs from Monday, so what falls in it depends on today's weekday.
        LocalDate monday = today().with(DayOfWeek.MONDAY);
        long ana1 = 3_000 + (today().minusDays(1).isBefore(monday) ? 0 : 3_000);
        long ana2 = 600 + (today().minusDays(5).isBefore(monday) ? 0 : 600);
        JsonNode week = top.get("week");
        assertThat(week).hasSize(2);
        assertThat(week.get(0).get("code").asString()).isEqualTo("ANA-1");
        assertThat(week.get(0).get("sales_minor").asLong()).isEqualTo(ana1);
        assertThat(week.get(1).get("sales_minor").asLong()).isEqualTo(ana2);
    }

    @Test
    void oneLinePerBranchMostSalesFirst() {
        JsonNode lines = home("", TILL, "*").get("branches");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).get("branch_id").asString())
                .isEqualTo(t.headOffice().toString());
        assertThat(lines.get(0).get("name").asString()).isEqualTo("Head Office");
        assertThat(lines.get(0).get("sales_minor").asLong()).isEqualTo(2_100);
        assertThat(lines.get(0).get("sale_count").asLong()).isEqualTo(2);
        assertThat(lines.get(1).get("branch_id").asString())
                .isEqualTo(t.secondBranch().toString());
        assertThat(lines.get(1).get("sales_minor").asLong()).isEqualTo(1_500);
        assertThat(lines.get(1).get("sale_count").asLong()).isEqualTo(1);
    }

    @Test
    void theBranchChosenInTheHeaderNarrowsEveryFigureAndDropsTheBranchLines() {
        JsonNode r = home("?branch_id=" + t.secondBranch(), ORDERS, "*");
        assertThat(r.get("sales_today").get("sales_minor").asLong()).isEqualTo(1_500);
        assertThat(r.get("sales_today").get("sale_count").asLong()).isEqualTo(1);
        assertThat(r.get("takings").get("total_minor").asLong()).isEqualTo(1_500);
        assertThat(method(r.get("takings"), "mobile_money").get("amount_minor").asLong())
                .isZero();
        assertThat(method(r.get("takings"), "bank")).isNull();
        assertThat(r.get("credit_owed").get("owed_minor").asLong()).isZero();
        assertThat(r.get("credit_owed").get("customers").asInt()).isZero();
        assertThat(r.get("credit_owed").has("oldest_due_date")).isFalse();
        assertThat(r.get("top_items").get("today")).hasSize(1);
        assertThat(r.has("branches")).isFalse();
        assertThat(r.get("open_orders").asInt()).isZero();
    }

    @Test
    void aCallerScopedToOneBranchSeesOnlyItAndNoBranchLines() {
        JsonNode r = home("", ORDERS, t.headOffice().toString());
        assertThat(r.get("sales_today").get("sales_minor").asLong()).isEqualTo(2_100);
        assertThat(r.get("takings").get("total_minor").asLong()).isEqualTo(900);
        assertThat(method(r.get("takings"), "cash").get("amount_minor").asLong())
                .isEqualTo(200);
        assertThat(r.get("credit_owed").get("owed_minor").asLong()).isEqualTo(2_700);
        assertThat(r.has("branches")).isFalse();
        assertThat(r.get("open_orders").asInt()).isEqualTo(1);
        assertThat(AnalyticsData.figures(r)).doesNotContain("3600");

        // Asking for a branch outside the scope shows nothing of it: no part, rather than zeros it cannot vouch for.
        JsonNode outside =
                home("?branch_id=" + t.secondBranch(), ORDERS, t.headOffice().toString());
        for (String part :
                new String[] {"sales_today", "takings", "credit_owed", "top_items", "low_stock", "open_orders"}) {
            assertThat(outside.has(part)).as(part).isFalse();
        }
    }

    @Test
    void aRoleWithoutTheMoneyPermissionGetsNoMoneyFigures() {
        long audit = AnalyticsData.auditRows();
        JsonNode r = home("", SALES + ",retail.order.manage", "*");
        for (String part : new String[] {"sales_today", "takings", "credit_owed", "top_items", "branches"}) {
            assertThat(r.has(part)).as(part).isFalse();
        }
        assertThat(r.get("low_stock").isInt()).isTrue();
        assertThat(r.get("open_orders").asInt()).isEqualTo(1);
        assertThat(AnalyticsData.figures(r)).doesNotContain("3600", "2400", "2700", "1700", "2100");
        assertThat(AnalyticsData.auditRows()).isEqualTo(audit);

        // Without the orders and stock permissions those parts go too.
        JsonNode bare = home("", "retail.sale.read", "*");
        assertThat(bare.has("low_stock")).isFalse();
        assertThat(bare.has("open_orders")).isFalse();
    }

    @Test
    void theMoneyPartsNeedEverySellerInEveryBranchCovered() {
        // A Supervisor at head office who is a seller at branch two: over every branch the summary covers head office
        // only.
        Map<String, String> scopes = Map.of("X-Dev-Scopes", "retail.sale.all_sellers=" + t.headOffice());
        ResponseEntity<JsonNode> r = api.call(HttpMethod.GET, "/reports/home", null, SALES, "*", scopes);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("sales_today").get("sales_minor").asLong()).isEqualTo(2_100);
        assertThat(r.getBody().has("branches")).isFalse();
        // Asked for branch two, where they see only their own sales: no money parts, and no refusal.
        ResponseEntity<JsonNode> two =
                api.call(HttpMethod.GET, "/reports/home?branch_id=" + t.secondBranch(), null, SALES, "*", scopes);
        assertThat(two.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(two.getBody().has("sales_today")).isFalse();
        assertThat(two.getBody().has("takings")).isFalse();
    }

    @Test
    void theTakingsNeedTheCashSummarysPermissionsToo() {
        // Everybody's money without the cash book: the sales and the rest, but not the takings.
        JsonNode noBook = home("", ADMIN, "*");
        assertThat(noBook.get("sales_today").get("sales_minor").asLong()).isEqualTo(3_600);
        assertThat(noBook.has("credit_owed")).isTrue();
        assertThat(noBook.has("takings")).isFalse();
        // The cash book read without the till is refused the cash summary, so it gets no takings either.
        String noTill = TILL.replace(",retail.till.close", "");
        assertThat(api.get("/reports/cash/daily", noTill).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(home("", noTill, "*").has("takings")).isFalse();
        // The cash book read at head office only covers head office, not both shops.
        Map<String, String> scopes = Map.of("X-Dev-Scopes", "retail.cashbook.read=" + t.headOffice());
        ResponseEntity<JsonNode> one = api.call(HttpMethod.GET, "/reports/home", null, TILL, "*", scopes);
        assertThat(one.getBody().has("takings")).isFalse();
        ResponseEntity<JsonNode> hq =
                api.call(HttpMethod.GET, "/reports/home?branch_id=" + t.headOffice(), null, TILL, "*", scopes);
        assertThat(hq.getBody().get("takings").get("total_minor").asLong()).isEqualTo(900);
    }

    @Test
    void theRouteNeedsTheSalesRead() {
        ResponseEntity<JsonNode> denied = api.get("/reports/home", "retail.stock.read,retail.order.manage");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    void switchOn(String capability) {
        String settings = "core.settings.manage";
        String etag = api.get("/settings/capabilities", settings).getHeaders().getETag();
        ResponseEntity<JsonNode> on = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of(capability, true),
                settings,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(on.getStatusCode()).as("%s", on.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** Cash takings of the cash summary on {@code day}, every shop added up. */
    long cashSummaryIn(LocalDate day) {
        ResponseEntity<JsonNode> daily = api.get("/reports/cash/daily?from=" + day + "&to=" + day, CASHBOOK);
        assertThat(daily.getStatusCode()).as("%s", daily.getBody()).isEqualTo(HttpStatus.OK);
        long in = 0;
        for (JsonNode row : daily.getBody().get("items")) {
            if (row.get("business_date").asString().equals(day.toString())) {
                in += row.get("cash_takings_minor").asLong();
            }
        }
        return in;
    }

    /** Switches returns on and gives back the first line of {@code sale}, refunded by {@code method} at head office. */
    void giveBack(UUID sale, String method) {
        switchOn("returns");
        String line = api.get("/sales/" + sale, ADMIN)
                .getBody()
                .get("lines")
                .get(0)
                .get("id")
                .asString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "lines",
                List.of(Map.of("sale_line_id", line, "qty", "1", "reason", "defective", "disposition", "restock")));
        body.put("refund_method", method);
        body.put("refund_branch_id", t.headOffice());
        ResponseEntity<JsonNode> returned = api.postKeyed(
                "/sales/" + sale + "/returns",
                body,
                ADMIN + ",retail.sale.return",
                "*",
                UUID.randomUUID().toString());
        assertThat(returned.getStatusCode()).as("%s", returned.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aRefundTodayOfYesterdaysCashSaleComesOffTodaysCash() {
        giveBack(d.sell(t.headOffice(), "cash", d.p2, "1", 1), "cash");

        JsonNode cash = method(home("", TILL, "*").get("takings"), "cash");
        assertThat(cash.get("out_minor").asLong()).isEqualTo(1_500 + 300);
        assertThat(cash.get("amount_minor").asLong()).isEqualTo(1_700 - 300);
        // Yesterday's sale is not today's money, and the head office till paid the refund.
        assertThat(cash.get("sales_minor").asLong()).isEqualTo(3_000);
        assertThat(cash("?branch_id=" + t.secondBranch(), "*")).isEqualTo(1_500);
        assertCashIsTheCashSummarys(home("", TILL, "*").get("takings"));
        // Sales today still agree with the dashboard, which takes the goods back on the day of the return.
        assertThat(home("", TILL, "*").get("sales_today").get("sales_minor").asLong())
                .isEqualTo(api.get("/reports/dashboard?period=today", ADMIN)
                        .getBody()
                        .get("today")
                        .get("sales_minor")
                        .asLong());
    }

    @Test
    void aRefundTodayOfYesterdaysMobileMoneySaleComesOffTodaysMobileMoneyNotCash() {
        giveBack(d.sell(t.headOffice(), "mobile_money", d.p2, "1", 1), "mobile_money");
        JsonNode takings = home("", TILL, "*").get("takings");
        JsonNode mobile = method(takings, "mobile_money");
        assertThat(mobile.get("out_minor").asLong()).isEqualTo(300);
        assertThat(mobile.get("amount_minor").asLong()).isEqualTo(600 - 300);
        assertThat(method(takings, "cash").get("amount_minor").asLong()).isEqualTo(1_700);
        assertThat(takings.get("total_minor").asLong()).isEqualTo(2_400 - 300);
        assertCashIsTheCashSummarys(takings);
    }

    @Test
    void aReturnOnACreditSaleTakenOffTheBuyersBalanceIsNoMoney() {
        giveBack(d.sell(t.headOffice(), "credit", d.p2, "1", 1), "account");
        JsonNode takings = home("", TILL, "*").get("takings");
        // Nothing left the till: no line for the account, and the money taken today is what it was.
        assertThat(takings.get("total_minor").asLong()).isEqualTo(2_400);
        for (JsonNode m : takings.get("methods")) {
            assertThat(m.get("method").asString()).isIn("cash", "mobile_money", "bank");
            assertThat(m.get("out_minor").asLong())
                    .isEqualTo(m.get("method").asString().equals("cash") ? 1_500 : 0);
        }
        assertThat(method(takings, "cash").get("amount_minor").asLong()).isEqualTo(1_700);
        assertCashIsTheCashSummarys(takings);
    }

    @Test
    void aReceiptOnAccountReversedTodayTakesOutWhatItBroughtInOnItsOwnDay() {
        switchOn("customer_accounts");
        String perms = ADMIN + ",retail.credit.manage";
        UUID buyer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 03"), ADMIN));
        for (String qty : new String[] {"2", "3"}) {
            Map<String, Object> sale = new LinkedHashMap<>();
            sale.put("branch_id", t.headOffice());
            sale.put("payment_method", "credit");
            sale.put("customer_id", buyer);
            sale.put("sale_date", today().minusDays(2).toString());
            sale.put("lines", List.of(Map.of("product_id", d.p2, "qty", qty)));
            ResponseEntity<JsonNode> sold =
                    api.postKeyed("/sales", sale, perms, "*", UUID.randomUUID().toString());
            assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        }
        // The buyer owes 600 + 900: a receipt cannot leave anything unallocated on the account, so none is partly so.
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("branch_id", t.headOffice());
        receipt.put("amount_minor", 1_501);
        receipt.put("method", "cash");
        receipt.put("paid_on", today().minusDays(1).toString());
        ResponseEntity<JsonNode> tooMuch = api.postKeyed(
                "/customers/" + buyer + "/payments",
                receipt,
                perms,
                "*",
                UUID.randomUUID().toString());
        assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(tooMuch.getBody().get("code").asString()).isEqualTo("payment_exceeds_allocatable");

        // Yesterday 1,000 came in on the receipt, split over both sales; today it is reversed as a whole.
        long yesterday = cashSummaryIn(today().minusDays(1));
        receipt.put("amount_minor", 1_000);
        ResponseEntity<JsonNode> paid = api.postKeyed(
                "/customers/" + buyer + "/payments",
                receipt,
                perms,
                "*",
                UUID.randomUUID().toString());
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(cashSummaryIn(today().minusDays(1)) - yesterday).isEqualTo(1_000);
        ResponseEntity<JsonNode> reversed = api.postKeyed(
                "/customers/" + buyer + "/payments/" + RetailTestSupport.id(paid) + "/reverse",
                Map.of("reason", "Recorded against the wrong buyer"),
                perms,
                "*",
                UUID.randomUUID().toString());
        assertThat(reversed.getStatusCode()).as("%s", reversed.getBody()).isEqualTo(HttpStatus.OK);

        JsonNode takings = home("", TILL, "*").get("takings");
        JsonNode cash = method(takings, "cash");
        assertThat(cash.get("payments_minor").asLong()).isEqualTo(200);
        assertThat(cash.get("out_minor").asLong()).isEqualTo(1_500 + 1_000);
        assertThat(cash.get("amount_minor").asLong()).isEqualTo(1_700 - 1_000);
        assertThat(takings.get("total_minor").asLong()).isEqualTo(2_400 - 1_000);
        assertCashIsTheCashSummarys(takings);
    }

    @Test
    void aDeliveryFeeTakenInCashAtTheDoorIsCash() {
        UUID id = order(500);
        ResponseEntity<JsonNode> done = api.postKeyed(
                "/orders/" + id + "/deliver",
                Map.of("payment_method", "cash"),
                ORDERS,
                "*",
                UUID.randomUUID().toString());
        assertThat(done.getStatusCode().is2xxSuccessful())
                .as("%s", done.getBody())
                .isTrue();
        JsonNode cash = method(home("", TILL, "*").get("takings"), "cash");
        assertThat(cash.get("sales_minor").asLong()).isEqualTo(3_000 + 700);
        assertThat(cash.get("fees_minor").asLong()).isEqualTo(500);
        assertThat(cash.get("amount_minor").asLong()).isEqualTo(1_700 + 700 + 500);
        assertCashIsTheCashSummarys(home("", TILL, "*").get("takings"));
    }

    /**
     * Issue #489: a credit sale made today and paid 200 in cash today cannot then be voided today (422
     * {@code sale_has_payments}), so the 200 stays in today's cash on the card and in the cash summary, which agree.
     */
    @Test
    void aCreditSalePaidInCashTodayCannotBeVoidedAndItsCashStaysAsTheCashSummarySays() {
        JsonNode before = method(home("", TILL, "*").get("takings"), "cash");
        UUID sale = d.sell(t.headOffice(), "credit", d.p2, "2", 0);
        pay(sale, 200, "cash");
        ResponseEntity<JsonNode> voided =
                api.post("/sales/" + sale + "/void", Map.of("reason", "Test void of a paid credit sale"), ADMIN);
        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(voided.getBody().get("code").asString()).isEqualTo("sale_has_payments");
        JsonNode takings = home("", TILL, "*").get("takings");
        JsonNode cash = method(takings, "cash");
        assertThat(cash.get("amount_minor").asLong())
                .isEqualTo(before.get("amount_minor").asLong() + 200);
        assertThat(cash.get("payments_minor").asLong())
                .isEqualTo(before.get("payments_minor").asLong() + 200);
        assertThat(cash.get("out_minor").asLong())
                .isEqualTo(before.get("out_minor").asLong());
        assertCashIsTheCashSummarys(takings);
    }

    @Test
    void aPaymentCountsOnItsOwnDateWhateverTheHourItWasEnteredInKampala() {
        UUID sale = d.sell(t.headOffice(), "credit", d.p2, "4", 2);
        // Entered at half past midnight today, dated today: today's money.
        UUID early = pay(sale, 40, "cash");
        entered(early, today(), today() + " 00:30:00");
        // Entered at half past eleven last night, dated yesterday: yesterday's money.
        UUID late = pay(sale, 50, "cash");
        entered(late, today().minusDays(1), today().minusDays(1) + " 23:30:00");
        JsonNode cash = method(home("", TILL, "*").get("takings"), "cash");
        assertThat(cash.get("payments_minor").asLong()).isEqualTo(200 + 40);

        // The payment route refuses a payment dated after the day it is entered; the summary does not rely on it and
        // chooses payments by paid_on alone (RetailHomeTakingsClockIT dates one after its entry through the database).
        ResponseEntity<JsonNode> future = api.postKeyed(
                "/sales/" + sale + "/payments",
                Map.of(
                        "amount_minor",
                        10,
                        "method",
                        "cash",
                        "paid_on",
                        today().plusDays(1).toString()),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(future.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void thisWeekStartsOnMondaySoSundaysSalesAreLastWeeks() {
        LocalDate monday = today().with(DayOfWeek.MONDAY);
        int sunday = (int) ChronoUnit.DAYS.between(monday.minusDays(1), today());
        int mondayAgo = (int) ChronoUnit.DAYS.between(monday, today());
        d.sell(t.headOffice(), "cash", d.p3, "1", sunday);
        d.sell(t.headOffice(), "cash", d.p3, "1", mondayAgo);
        JsonNode week = home("", TILL, "*").get("top_items").get("week");
        JsonNode ana3 = null;
        for (JsonNode i : week) {
            if (i.get("code").asString().equals("ANA-3")) {
                ana3 = i;
            }
        }
        assertThat(ana3).as("%s", week).isNotNull();
        assertThat(ana3.get("sales_minor").asLong()).isEqualTo(700);
    }

    @Test
    void aBranchThatSoldTodayKeepsItsLineAndAStoreWithNoSaleHasNone() {
        // Branch two becomes a Store after selling today; a third branch, a Store, never sold.
        TestDatabase.owner()
                .sql("UPDATE branches SET sells_goods = false WHERE id = ?")
                .param(t.secondBranch())
                .update();
        TestDatabase.owner()
                .sql("INSERT INTO branches (id, tenant_id, code, name, sells_goods)"
                        + " VALUES (?, ?, 'ST3', 'Test Store Three', false)")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        JsonNode lines = home("", TILL, "*").get("branches");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1).get("branch_id").asString())
                .isEqualTo(t.secondBranch().toString());
        assertThat(lines.get(1).get("sales_minor").asLong()).isEqualTo(1_500);
    }

    @Test
    void anotherBusinessesSalesNeverReachTheSummary() {
        TestDatabase.Fixture other = TestDatabase.tenant("retail-home-other", false, true);
        RetailTestSupport otherApi = new RetailTestSupport(http, other);
        UUID item = otherApi.product("OTH-1", 100, 9_000);
        otherApi.stockUp(other.headOffice(), item, "5");
        assertThat(otherApi.sell(other.headOffice(), "mobile_money", item, "1").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        JsonNode mine = home("", TILL, "*");
        assertThat(mine.get("sales_today").get("sales_minor").asLong()).isEqualTo(3_600);
        assertThat(method(mine.get("takings"), "mobile_money")
                        .get("amount_minor")
                        .asLong())
                .isEqualTo(600);
        assertThat(AnalyticsData.figures(mine)).doesNotContain("9000");
        JsonNode theirs = otherApi.call(HttpMethod.GET, "/reports/home", null, ADMIN, "*", Map.of())
                .getBody();
        assertThat(theirs.get("sales_today").get("sales_minor").asLong()).isEqualTo(9_000);
        assertThat(theirs.get("credit_owed").get("owed_minor").asLong()).isZero();
    }
}
