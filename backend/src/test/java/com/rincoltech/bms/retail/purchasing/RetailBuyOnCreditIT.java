package com.rincoltech.bms.retail.purchasing;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Buy stock on credit, see what is owed and pay suppliers without the purchasing capability (issue #557, owner decision
 * D15, ADR-084): a credit purchase raises the supplier's balance, a payment lowers it, the statement keeps a running
 * balance, the journal and the cash book are right, the permissions hold, a payment is never dated in the future, and two
 * payments to one supplier cannot spend the same balance. The capability stays off in every test. Every name and amount
 * is fabricated. The clock is pinned at 22:30 UTC, 01:30 the next day in Africa/Kampala.
 */
@Import(RetailBuyOnCreditIT.FixedClock.class)
class RetailBuyOnCreditIT extends IntegrationTest {

    /** 2026-10-08 01:30 in Kampala, 2026-10-07 22:30 in UTC. */
    static final Instant NOW = Instant.parse("2026-10-07T22:30:00Z");

    static final String TODAY = "2026-10-08";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    /** The administrator column: buys, pays and reads the cash book. */
    static final String OWNER = ADMIN + ",retail.payable.manage,retail.cashbook.read,retail.banking.record";
    /** The Purchasing role (ADR-055): may buy, on credit too, but not pay. */
    static final String BUYER = "retail.stock.read,retail.purchase.create";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID supplier;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-buy-on-credit", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        product = api.product("BC-1", 100, 150);
        supplier = RetailTestSupport.id(api.post("/suppliers", Map.of("name", "Test Supplier 01"), OWNER));
        assertThat(api.get("/settings/capabilities", "core.settings.manage")
                        .getBody()
                        .get("purchasing")
                        .asBoolean())
                .isFalse();
    }

    // ---- helpers ---------------------------------------------------------------------------

    static String key() {
        return UUID.randomUUID().toString();
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    ResponseEntity<JsonNode> buyRaw(String method, String qty, String on, UUID supplierId, String perms) {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("cost_minor", 100);
        l.put("qty_by_branch", List.of(Map.of("branch_id", hq, "qty", qty)));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("purchased_on", on);
        b.put("payment_method", method);
        if (supplierId != null) {
            b.put("supplier_id", supplierId);
        }
        b.put("lines", List.of(l));
        return api.postKeyed("/purchases", b, perms, "*", key());
    }

    UUID buy(String method, String qty, String on) {
        ResponseEntity<JsonNode> r = buyRaw(method, qty, on, supplier, OWNER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    ResponseEntity<JsonNode> payRaw(String method, long amount, String paidOn, String perms) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("supplier_id", supplier);
        b.put("branch_id", hq);
        b.put("method", method);
        b.put("amount_minor", amount);
        if (paidOn != null) {
            b.put("paid_on", paidOn);
        }
        b.put("note", "Paid at the shop");
        return api.postKeyed("/supplier-payments", b, perms, "*", key());
    }

    JsonNode pay(String method, long amount, String paidOn) {
        ResponseEntity<JsonNode> r = payRaw(method, amount, paidOn, OWNER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    long owed() {
        ResponseEntity<JsonNode> r = api.get("/reports/supplier-payables", OWNER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody().get("totals").get("owed_minor").asLong();
    }

    JsonNode statement(String from, String to) {
        ResponseEntity<JsonNode> r = api.get("/suppliers/" + supplier + "/statement?from=" + from + "&to=" + to, OWNER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    /** Debit and credit per account system key of the entries of one source. */
    Map<String, String> posted(String sourceType, UUID sourceId) {
        Map<String, String> out = new TreeMap<>();
        TestDatabase.owner().sql("""
                        SELECT a.system_key, sum(l.debit) AS debit, sum(l.credit) AS credit FROM journal_lines l
                          JOIN journal_entries e ON e.id = l.entry_id
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE e.tenant_id = ? AND e.source_type = ? AND e.source_id = ?
                         GROUP BY a.system_key
                        """).params(t.tenantId(), sourceType, sourceId).query(rs -> {
            out.put(rs.getString(1), rs.getLong(2) + "/" + rs.getLong(3));
        });
        return out;
    }

    /** What the supplier's subledger on trade creditors holds: credits less debits. */
    long creditorsOfSupplier() {
        return TestDatabase.owner()
                .sql("""
                        SELECT coalesce(sum(l.credit - l.debit), 0) FROM journal_lines l
                          JOIN gl_accounts a ON a.id = l.account_id
                         WHERE l.tenant_id = ? AND a.system_key = 'trade_creditors'
                           AND l.subledger_type = 'retail.supplier' AND l.subledger_id = ?
                        """)
                .params(t.tenantId(), supplier)
                .query(Long.class)
                .single();
    }

    /** The cash that left the till for stock and suppliers on one day; a day with nothing in the cash book has no row. */
    long cashOut(String day) {
        ResponseEntity<JsonNode> r =
                api.get("/reports/cash/daily?branch_id=" + hq + "&from=" + day + "&to=" + day, OWNER);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        for (JsonNode row : r.getBody().get("items")) {
            if (row.get("business_date").asString().equals(day)) {
                return row.get("cash_purchases_minor").asLong();
            }
        }
        return 0;
    }

    // ---- buying on credit ---------------------------------------------------------------------

    @Test
    void aCreditPurchaseRaisesTheSupplierBalanceWithoutTheCapability_FR_RET_06() {
        UUID purchase = buy("credit", "5", TODAY);
        assertThat(owed()).isEqualTo(500);
        assertThat(posted("retail.purchase", purchase))
                .containsOnlyKeys("inventory", "trade_creditors")
                .containsEntry("inventory", "500/0")
                .containsEntry("trade_creditors", "0/500");
        assertThat(creditorsOfSupplier()).isEqualTo(500);
        JsonNode open = api.get("/suppliers/" + supplier + "/open-items", OWNER).getBody();
        assertThat(open.get("total_open_minor").asLong()).isEqualTo(500);
        assertThat(open.get("items").get(0).get("kind").asString()).isEqualTo("restock");
        // Bought on credit leaves the till alone.
        assertThat(cashOut(TODAY)).isZero();
    }

    @Test
    void creditNeedsASupplier() {
        ResponseEntity<JsonNode> r = buyRaw("credit", "1", TODAY, null, OWNER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("errors").get(0).get("field").asString()).isEqualTo("supplier_id");
    }

    @Test
    void mobileMoneyIsAWayToPayOnBuyStockAndNeverTouchesTheTill() {
        UUID purchase = buy("mobile_money", "3", TODAY);
        assertThat(posted("retail.purchase", purchase))
                .containsOnlyKeys("inventory", "mobile_money")
                .containsEntry("inventory", "300/0")
                .containsEntry("mobile_money", "0/300");
        assertThat(owed()).isZero();
        assertThat(cashOut(TODAY)).isZero();
        // Cash still leaves the till, as before.
        buy("cash", "2", TODAY);
        assertThat(cashOut(TODAY)).isEqualTo(200);
    }

    // ---- paying a supplier ----------------------------------------------------------------------

    @Test
    void aPaymentLowersTheBalanceAndPostsToTheJournalAndTheCashBook() {
        buy("credit", "5", TODAY);
        JsonNode cash = pay("cash", 300, null);
        assertThat(cash.get("paid_on").asString()).isEqualTo(TODAY);
        assertThat(owed()).isEqualTo(200);
        assertThat(creditorsOfSupplier()).isEqualTo(200);
        UUID id = UUID.fromString(cash.get("id").asString());
        assertThat(posted("retail.supplier_payment", id))
                .containsOnlyKeys("trade_creditors", "cash_on_hand")
                .containsEntry("trade_creditors", "300/0")
                .containsEntry("cash_on_hand", "0/300");
        assertThat(cashOut(TODAY)).isEqualTo(300);
        // Mobile money pays the rest and leaves the till alone.
        JsonNode momo = pay("mobile_money", 200, null);
        assertThat(posted(
                        "retail.supplier_payment",
                        UUID.fromString(momo.get("id").asString())))
                .containsEntry("mobile_money", "0/200");
        assertThat(owed()).isZero();
        assertThat(cashOut(TODAY)).isEqualTo(300);
        // Nothing is owed now, so nothing more can be paid.
        ResponseEntity<JsonNode> over = payRaw("cash", 1, null, OWNER);
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(over)).isEqualTo("payment_exceeds_open");
    }

    @Test
    void aPaymentCanBeDatedEarlierAndPostsOnThatDay() {
        buy("credit", "5", "2026-10-05");
        JsonNode p = pay("cash", 400, "2026-10-06");
        assertThat(p.get("paid_on").asString()).isEqualTo("2026-10-06");
        String entryDate = TestDatabase.owner()
                .sql("SELECT entry_date::text FROM journal_entries WHERE tenant_id = ? AND source_id = ?")
                .params(t.tenantId(), UUID.fromString(p.get("id").asString()))
                .query(String.class)
                .single();
        assertThat(entryDate).isEqualTo("2026-10-06");
        assertThat(cashOut("2026-10-06")).isEqualTo(400);
        assertThat(cashOut(TODAY)).isZero();
    }

    void bank(String day) {
        Map<String, Object> banking = new LinkedHashMap<>();
        banking.put("branch_id", hq);
        banking.put("business_date", day);
        banking.put("amount_minor", 1_000);
        ResponseEntity<JsonNode> banked = api.postKeyed("/bankings", banking, OWNER, "*", key());
        assertThat(banked.getStatusCode()).as("%s", banked.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void aCashPaymentCannotBeDatedOnOrBeforeADayWhoseCashIsBanked() {
        buy("credit", "5", "2026-10-04");
        // Before any banking, an earlier day is open to cash.
        pay("cash", 100, "2026-10-04");
        bank("2026-10-06");
        // The banking day itself, and an earlier day it carried forward with no banking of its own.
        for (String day : List.of("2026-10-06", "2026-10-05")) {
            ResponseEntity<JsonNode> r = payRaw("cash", 100, day, OWNER);
            assertThat(r.getStatusCode()).as(day).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(code(r)).as(day).isEqualTo("cash_day_banked");
            assertThat(cashOut(day)).as(day).isZero();
        }
        // A day after the last banking is open; mobile money and bank never touch the till.
        pay("cash", 100, "2026-10-07");
        pay("mobile_money", 100, "2026-10-05");
        // Today too, once today's cash is banked.
        bank(TODAY);
        assertThat(code(payRaw("cash", 100, TODAY, OWNER))).isEqualTo("cash_day_banked");
        pay("bank", 100, TODAY);
        assertThat(owed()).isEqualTo(100);
    }

    @Test
    void aVoidedMobileMoneyPurchaseNeverTouchesTheTill() {
        UUID purchase = buy("mobile_money", "3", TODAY);
        ResponseEntity<JsonNode> v =
                api.postKeyed("/purchases/" + purchase + "/void", Map.of("reason", "Entered twice"), OWNER, "*", key());
        assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cashOut(TODAY)).isZero();
        assertThat(posted("retail.purchase", purchase)).containsEntry("mobile_money", "300/300");
    }

    @Test
    void aPaymentCannotBeDatedInTheFuture() {
        buy("credit", "5", TODAY);
        ResponseEntity<JsonNode> r = payRaw("cash", 100, "2026-10-09", OWNER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("errors").get(0).get("field").asString()).isEqualTo("paid_on");
        assertThat(r.getBody().get("errors").get(0).get("code").asString()).isEqualTo("future_date");
        assertThat(owed()).isEqualTo(500);
    }

    @Test
    void aPaymentPaysOnlyWhatWasOwedOnItsDay() {
        buy("credit", "5", TODAY);
        ResponseEntity<JsonNode> r = payRaw("cash", 100, "2026-10-07", OWNER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("payment_exceeds_open");
        assertThat(owed()).isEqualTo(500);
    }

    // ---- the statement ------------------------------------------------------------------------------

    @Test
    void theStatementKeepsARunningBalance() {
        buy("credit", "10", "2026-10-01");
        buy("credit", "5", "2026-10-03");
        pay("bank", 600, "2026-10-04");
        JsonNode cash = pay("cash", 200, TODAY);
        api.postKeyed(
                "/supplier-payments/" + cash.get("id").asString() + "/reverse",
                Map.of("reason", "Paid twice by mistake"),
                OWNER,
                "*",
                key());
        JsonNode all = statement("2026-10-01", TODAY);
        assertThat(all.get("opening_minor").asLong()).isZero();
        List<String> rows = new ArrayList<>();
        for (JsonNode l : all.get("lines")) {
            rows.add(l.get("date").asString() + " " + l.get("kind").asString() + " +"
                    + l.get("increase_minor").asLong() + " -"
                    + l.get("decrease_minor").asLong() + " = "
                    + l.get("balance_minor").asLong());
        }
        assertThat(rows)
                .containsExactly(
                        "2026-10-01 purchase +1000 -0 = 1000",
                        "2026-10-03 purchase +500 -0 = 1500",
                        "2026-10-04 payment +0 -600 = 900",
                        "2026-10-08 payment +0 -200 = 700",
                        "2026-10-08 payment_reversed +200 -0 = 900");
        assertThat(all.get("closing_minor").asLong()).isEqualTo(900).isEqualTo(owed());
        assertThat(all.get("lines").get(2).get("method").asString()).isEqualTo("bank");
        // A later start carries what came before as the opening balance.
        JsonNode later = statement("2026-10-04", TODAY);
        assertThat(later.get("opening_minor").asLong()).isEqualTo(1500);
        assertThat(later.get("lines").get(0).get("balance_minor").asLong()).isEqualTo(900);
        assertThat(later.get("closing_minor").asLong()).isEqualTo(900);
        // A voided credit purchase comes off the balance on the day it was voided.
        UUID mistake = buy("credit", "1", TODAY);
        ResponseEntity<JsonNode> v =
                api.postKeyed("/purchases/" + mistake + "/void", Map.of("reason", "Entered twice"), OWNER, "*", key());
        assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode after = statement(TODAY, TODAY);
        JsonNode last = after.get("lines").get(after.get("lines").size() - 1);
        assertThat(last.get("kind").asString()).isEqualTo("purchase_void");
        assertThat(after.get("closing_minor").asLong()).isEqualTo(900).isEqualTo(owed());
    }

    /** A mobile money payment from the head office that pays only purchase {@code b}. */
    JsonNode payToB(UUID b, long amount, String paidOn) {
        Map<String, Object> manual = new LinkedHashMap<>();
        manual.put("supplier_id", supplier);
        manual.put("branch_id", hq);
        manual.put("method", "mobile_money");
        manual.put("amount_minor", amount);
        manual.put("paid_on", paidOn);
        manual.put("allocation", "manual");
        manual.put("allocations", List.of(Map.of("purchase_id", b, "amount_minor", amount)));
        ResponseEntity<JsonNode> r = api.postKeyed("/supplier-payments", manual, OWNER, "*", key());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    @Test
    void aBranchScopedStatementClosesAtWhatIsStillToPayInScope() {
        UUID br2 = t.secondBranch();
        // A: bought on credit into both branches, 500 each. B: into the head office only, 300.
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("product_id", product);
        l.put("cost_minor", 100);
        l.put("qty_by_branch", List.of(Map.of("branch_id", hq, "qty", "5"), Map.of("branch_id", br2, "qty", "5")));
        Map<String, Object> two = new LinkedHashMap<>();
        two.put("purchased_on", "2026-10-01");
        two.put("payment_method", "credit");
        two.put("supplier_id", supplier);
        two.put("lines", List.of(l));
        ResponseEntity<JsonNode> a = api.postKeyed("/purchases", two, OWNER, "*", key());
        assertThat(a.getStatusCode()).as("%s", a.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID b = buy("credit", "3", "2026-10-02");
        // 600 oldest first goes to A; 200 and then 100 chosen for B, the 100 reversed.
        pay("bank", 600, "2026-10-03");
        payToB(b, 200, "2026-10-04");
        JsonNode undone = payToB(b, 100, TODAY);
        api.postKeyed(
                "/supplier-payments/" + undone.get("id").asString() + "/reverse",
                Map.of("reason", "Paid twice by mistake"),
                OWNER,
                "*",
                key());
        // Every branch: A owes 400, B owes 100.
        assertThat(statement("2026-10-01", TODAY).get("closing_minor").asLong())
                .isEqualTo(500)
                .isEqualTo(owed());
        // A payer of the head office only: A also went into the second branch, so neither A nor what paid it counts.
        ResponseEntity<JsonNode> scoped = api.call(
                org.springframework.http.HttpMethod.GET,
                "/suppliers/" + supplier + "/statement?from=2026-10-01&to=" + TODAY,
                null,
                OWNER,
                hq.toString(),
                Map.of());
        assertThat(scoped.getStatusCode()).as("%s", scoped.getBody()).isEqualTo(HttpStatus.OK);
        List<String> rows = new ArrayList<>();
        for (JsonNode line : scoped.getBody().get("lines")) {
            rows.add(line.get("kind").asString() + " +"
                    + line.get("increase_minor").asLong() + " -"
                    + line.get("decrease_minor").asLong() + " = "
                    + line.get("balance_minor").asLong());
        }
        assertThat(rows)
                .containsExactly(
                        "purchase +300 -0 = 300",
                        "payment +0 -200 = 100",
                        "payment +0 -100 = 0",
                        "payment_reversed +100 -0 = 100");
        long scopedOwed = api.call(
                        org.springframework.http.HttpMethod.GET,
                        "/reports/supplier-payables",
                        null,
                        OWNER,
                        hq.toString(),
                        Map.of())
                .getBody()
                .get("totals")
                .get("owed_minor")
                .asLong();
        assertThat(scoped.getBody().get("closing_minor").asLong())
                .isEqualTo(100)
                .isEqualTo(scopedOwed);
    }

    @Test
    void theStatementRefusesABackwardRange() {
        ResponseEntity<JsonNode> r =
                api.get("/suppliers/" + supplier + "/statement?from=2026-10-08&to=2026-10-01", OWNER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/suppliers/" + UUID.randomUUID() + "/statement", OWNER)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- permissions --------------------------------------------------------------------------------

    @Test
    void aBuyerMayBuyOnCreditButOnlyTheOwnerPaysAndSeesWhatIsOwed() {
        ResponseEntity<JsonNode> bought = buyRaw("credit", "2", TODAY, supplier, BUYER);
        assertThat(bought.getStatusCode()).as("%s", bought.getBody()).isEqualTo(HttpStatus.CREATED);
        for (String perms : List.of(BUYER, SALES)) {
            assertThat(payRaw("cash", 100, null, perms).getStatusCode())
                    .as(perms)
                    .isEqualTo(HttpStatus.FORBIDDEN);
            for (String path : List.of(
                    "/reports/supplier-payables",
                    "/supplier-payments",
                    "/suppliers/" + supplier + "/open-items",
                    "/suppliers/" + supplier + "/statement")) {
                assertThat(api.get(path, perms).getStatusCode())
                        .as(perms + " " + path)
                        .isEqualTo(HttpStatus.FORBIDDEN);
            }
        }
        assertThat(owed()).isEqualTo(200);
    }

    @Test
    void theInvoiceRoutesStayBehindTheCapability() {
        for (String path : List.of("/supplier-bills", "/purchase-orders", "/goods-receipts")) {
            ResponseEntity<JsonNode> r = api.get(path, OWNER + ",retail.purchase.approve");
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(code(r)).as(path).isEqualTo("capability_not_enabled");
        }
    }

    // ---- concurrency --------------------------------------------------------------------------------

    @Test
    void twoPaymentsToOneSupplierCannotSpendTheSameBalance() throws Exception {
        buy("credit", "5", TODAY);
        List<Callable<ResponseEntity<JsonNode>>> work =
                List.of(() -> payRaw("cash", 300, null, OWNER), () -> payRaw("bank", 300, null, OWNER));
        ExecutorService pool = Executors.newFixedThreadPool(work.size());
        List<ResponseEntity<JsonNode>> results = new ArrayList<>();
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<JsonNode>> w : work) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return w.call();
                }));
            }
            go.countDown();
            for (Future<ResponseEntity<JsonNode>> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(results.stream()
                        .filter(r -> r.getStatusCode() == HttpStatus.CREATED)
                        .count())
                .isEqualTo(1);
        assertThat(results.stream()
                        .filter(r -> r.getStatusCode() != HttpStatus.CREATED)
                        .map(RetailBuyOnCreditIT::code)
                        .toList())
                .containsExactly("payment_exceeds_open");
        assertThat(owed()).isEqualTo(200);
        assertThat(creditorsOfSupplier()).isEqualTo(200);
    }
}
