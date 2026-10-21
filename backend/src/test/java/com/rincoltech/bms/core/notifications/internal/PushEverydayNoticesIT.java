package com.rincoltech.bms.core.notifications.internal;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The everyday push notices of issue #412 (ADR-057), each publisher driven through its own route or job, and each recipient
 * rule: a new remote order to the staff of its shop only (not other shops, not the administrators, not the taker, never for a
 * walk-in order); out for delivery and delivered to the person who took the order, unless they did it; low stock by the
 * minimum (the branch's own level first) or by the pace, to that branch's stock readers and the administrators, once per item
 * and day; credit payments due to the administrators each morning, once, and to the supervisors of that shop only; the daily summary at the business's time, once, with
 * counts and no amount; and the business's switches. Nothing is sent (the scheduler is off): the test reads the outbox rows.
 * Fabricated tenants, people, items and devices.
 */
@TestPropertySource(properties = {"bms.push.ephemeral-keys=true", "db-scheduler.enabled=false"})
class PushEverydayNoticesIT extends IntegrationTest {

    static final String ORDERS = ADMIN + ",retail.order.manage";
    static final String SETTINGS = "core.settings.read,core.settings.manage";
    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");

    @Autowired
    TestRestTemplate http;

    @Autowired
    ApplicationContext context;

    @Autowired
    PlatformTransactionManager transactionManager;

    TestDatabase.Fixture t;
    RetailTestSupport retail;
    UUID admin;
    UUID hqSeller;
    UUID otherShopSeller;
    UUID hqSellerWithoutDevice;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("pusheveryday", false, true);
        retail = new RetailTestSupport(http, t);
        admin = Api.staff(t, Api.email("ev-admin"), new Role("tenant_admin", null));
        hqSeller = Api.staff(t, Api.email("ev-hq"), new Role("retail_sales", t.headOffice()));
        otherShopSeller = Api.staff(t, Api.email("ev-b2"), new Role("retail_sales", t.secondBranch()));
        hqSellerWithoutDevice = Api.staff(t, Api.email("ev-nodevice"), new Role("retail_sales", t.headOffice()));
        device(admin);
        device(hqSeller);
        device(otherShopSeller);
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    void device(UUID user) {
        String endpoint = "https://fcm.googleapis.com/fcm/send/test-" + UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO push_subscriptions (id, tenant_id, user_id, endpoint, endpoint_hash, p256dh, auth, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, now())
                        """)
                .params(
                        UUID.randomUUID(),
                        t.tenantId(),
                        user,
                        endpoint,
                        PushDevices.hash(endpoint),
                        "A".repeat(87),
                        "A".repeat(22))
                .update();
    }

    /** A retail actor (the X-Dev user of the support) who is also a real seller at the head office with a device. */
    RetailTestSupport realSellerAtHq() {
        RetailTestSupport actor = new RetailTestSupport(http, t);
        var owner = TestDatabase.owner();
        owner.sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', ?, ?, 'active')")
                .params(actor.userId(), t.tenantId(), "Test Staff ev-taker", Api.email("ev-taker"))
                .update();
        owner.sql("""
                        INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, branch_id, granted_by)
                        VALUES (?, ?, ?, 'retail_sales', ?, ?)
                        """)
                .params(UUID.randomUUID(), t.tenantId(), actor.userId(), t.headOffice(), actor.userId())
                .update();
        device(actor.userId());
        return actor;
    }

    List<String> told(String template) {
        return TestDatabase.owner()
                .sql("""
                        SELECT recipient FROM notification_outbox
                         WHERE tenant_id = ? AND channel = 'push' AND idempotency_key LIKE ?
                         ORDER BY recipient
                        """)
                .params(t.tenantId(), "push:" + template + ":%")
                .query(String.class)
                .list();
    }

    /** A parameter of the first queued row of the template. */
    String param(String template, String name) {
        return TestDatabase.owner()
                .sql("""
                        SELECT params ->> ? FROM notification_outbox
                         WHERE tenant_id = ? AND channel = 'push' AND idempotency_key LIKE ?
                         ORDER BY created_at LIMIT 1
                        """)
                .params(name, t.tenantId(), "push:" + template + ":%")
                .query(String.class)
                .list()
                .getFirst();
    }

    static String user(UUID id) {
        return "user:" + id;
    }

    Map<String, Object> order(UUID branch, String source, UUID product) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("branch_id", branch);
        b.put("source", source);
        b.put("customer_name", "Test Customer 01");
        b.put("customer_phone", "+256700000011");
        b.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        return b;
    }

    UUID placed(RetailTestSupport by, String source, UUID product) {
        ResponseEntity<JsonNode> r = by.postKeyed(
                "/orders",
                order(t.headOffice(), source, product),
                ORDERS,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    UUID stocked(String code, String qty) {
        UUID product = retail.product(code, 600, 1_000);
        retail.stockUp(t.headOffice(), product, qty);
        retail.stockUp(t.secondBranch(), product, qty);
        return product;
    }

    void sell(UUID branch, UUID product, String qty) {
        ResponseEntity<JsonNode> sold = retail.sell(branch, "cash", product, qty);
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    void minimum(UUID product, UUID branch, String qty) {
        String path = "/products/" + product + "/stock-minimum" + (branch == null ? "" : "/branches/" + branch);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("min_qty", qty);
        ResponseEntity<JsonNode> r = retail.call(HttpMethod.PUT, path, body, ADMIN, "*", Map.of());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<JsonNode> settings(HttpMethod method, Map<String, Object> body, Map<String, String> extra) {
        return http.exchange(
                "/api/v1/settings/notifications",
                method,
                new HttpEntity<>(body, retail.headers(SETTINGS, "*", extra)),
                JsonNode.class);
    }

    void switchSetting(Map<String, Object> change) {
        String etag = settings(HttpMethod.GET, null, Map.of()).getHeaders().getETag();
        ResponseEntity<JsonNode> r = settings(HttpMethod.PUT, change, Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** Runs the daily notices for this tenant at a moment, as the job does inside the tenant's own transaction. */
    void dailyAt(LocalDate day, LocalTime time) {
        Object notices = context.getBean("dailyNotices");
        java.time.Instant at = ZonedDateTime.of(day, time, KAMPALA).toInstant();
        TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager).execute(status -> {
                    invoke(notices, at);
                    return null;
                }));
    }

    /** DailyNotices is package private in retail.sales.internal; its one tick is reached by reflection. */
    static void invoke(Object notices, java.time.Instant at) {
        try {
            var run = notices.getClass().getDeclaredMethod("runAt", java.time.Instant.class);
            run.setAccessible(true);
            run.invoke(notices, at);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static LocalDate today() {
        return LocalDate.now(KAMPALA);
    }

    // ---- New orders ------------------------------------------------------------------------------

    @Test
    void aRemoteOrderTellsTheStaffOfItsShopOnly() {
        UUID product = stocked("EV-ORD-1", "10");
        RetailTestSupport taker = realSellerAtHq();
        placed(taker, "whatsapp", product);
        // Not the other shop's seller, not the administrator (no branch: not the shop's own staff), not the taker,
        // nobody
        // without a device.
        assertThat(told("retail.order_new")).containsExactly(user(hqSeller));
        assertThat(param("retail.order_new", "source_label")).isEqualTo("WhatsApp");
        assertThat(param("retail.order_new", "customer_name")).isNull();
    }

    @Test
    void aWalkInOrderTellsNobody() {
        UUID product = stocked("EV-ORD-2", "10");
        placed(retail, "walk_in", product);
        assertThat(told("retail.order_new")).isEmpty();
    }

    @Test
    void outForDeliveryAndDeliveredTellThePersonWhoTookTheOrder() {
        UUID product = stocked("EV-ORD-3", "10");
        RetailTestSupport taker = realSellerAtHq();
        UUID order = placed(taker, "phone", product);
        ResponseEntity<JsonNode> sent =
                retail.post("/orders/" + order + "/send", Map.of("delivered_by", "Test Rider 01"), ORDERS);
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(told("retail.order_out_for_delivery")).containsExactly(user(taker.userId()));
        ResponseEntity<JsonNode> delivered = retail.postKeyed(
                "/orders/" + order + "/deliver",
                Map.of("payment_method", "cash"),
                ORDERS,
                "*",
                UUID.randomUUID().toString());
        assertThat(delivered.getStatusCode()).as("%s", delivered.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(told("retail.order_delivered")).containsExactly(user(taker.userId()));
    }

    @Test
    void theTakerWhoSendsTheOrderOutHimselfIsNotTold() {
        UUID product = stocked("EV-ORD-4", "10");
        RetailTestSupport taker = realSellerAtHq();
        UUID order = placed(taker, "instagram", product);
        ResponseEntity<JsonNode> sent =
                taker.post("/orders/" + order + "/send", Map.of("delivered_by", "Test Rider 02"), ORDERS);
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(told("retail.order_out_for_delivery")).isEmpty();
    }

    // ---- Low stock -------------------------------------------------------------------------------

    @Test
    void belowTheMinimumTellsThatShopsStaffAndTheAdministratorsOnceADay() {
        UUID product = stocked("EV-LOW-1", "10");
        minimum(product, null, "5");
        sell(t.headOffice(), product, "6");
        // 4 left, under 5: the head office's seller and the administrator; the other shop's seller is not told.
        assertThat(told("retail.low_stock")).containsExactlyInAnyOrder(user(hqSeller), user(admin));
        assertThat(param("retail.low_stock", "reason")).isEqualTo("below your minimum of 5");
        // A second sale the same day tells nobody again.
        sell(t.headOffice(), product, "1");
        assertThat(told("retail.low_stock")).hasSize(2);
    }

    @Test
    void aBranchsOwnLevelWinsOverTheLevelForEveryBranch() {
        UUID product = stocked("EV-LOW-2", "10");
        minimum(product, null, "2");
        minimum(product, t.headOffice(), "8");
        sell(t.secondBranch(), product, "3");
        // 7 left at the second branch, above its level of 2 (and above the pace rule: 7 x 30 is more than 14 x 3).
        assertThat(told("retail.low_stock")).isEmpty();
        sell(t.headOffice(), product, "3");
        assertThat(told("retail.low_stock")).containsExactlyInAnyOrder(user(hqSeller), user(admin));
        assertThat(param("retail.low_stock", "reason")).isEqualTo("below your minimum of 8");
    }

    @Test
    void withoutAMinimumThePaceRuleSaysItIsLow() {
        UUID product = stocked("EV-LOW-3", "10");
        sell(t.headOffice(), product, "9");
        // 1 left after 9 sold in 30 days: 30 days of 1 is under 14 days of 9, about 3 days left.
        assertThat(told("retail.low_stock")).containsExactlyInAnyOrder(user(hqSeller), user(admin));
        assertThat(param("retail.low_stock", "reason")).isEqualTo("about 3 days from running out at this pace");
    }

    @Test
    void anItemThatNeverSoldIsJudgedByItsMinimumAlone() {
        UUID withLevel = stocked("EV-LOW-4", "10");
        UUID withoutLevel = stocked("EV-LOW-5", "10");
        minimum(withLevel, null, "5");
        for (UUID product : List.of(withLevel, withoutLevel)) {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("branch_id", t.headOffice());
            usage.put("kind", "damaged");
            usage.put("reason", "Test water damage");
            usage.put("lines", List.of(Map.of("product_id", product, "qty", "6")));
            ResponseEntity<JsonNode> r = retail.postKeyed(
                    "/usage", usage, ADMIN, "*", UUID.randomUUID().toString());
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        }
        // Only the item with a level: the other has no sales, so no pace, and no minimum.
        assertThat(told("retail.low_stock")).hasSize(2);
        assertThat(param("retail.low_stock", "item_name")).contains("EV-LOW-4");
    }

    @Test
    void aVoidedSaleDoesNotCountInThePaceAsStockHealthDoesNot() {
        UUID product = stocked("EV-LOW-7", "10");
        ResponseEntity<JsonNode> first = retail.sell(t.headOffice(), "cash", product, "6");
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        // 4 left after 6 sold: 4 x 30 is not under 14 x 6, so nobody is told yet.
        assertThat(told("retail.low_stock")).isEmpty();
        ResponseEntity<JsonNode> voided = retail.post(
                "/sales/" + RetailTestSupport.id(first) + "/void", Map.of("reason", "Test wrong item"), ADMIN);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);
        sell(t.headOffice(), product, "6");
        // 4 left after 6 sold that count (the voided 6 do not): still not low. Counting the void would make it 12 sold,
        // low.
        assertThat(told("retail.low_stock")).isEmpty();
    }

    @Test
    void anEmptyShelfReadsOutOfStock() {
        UUID product = stocked("EV-LOW-8", "10");
        minimum(product, null, "5");
        sell(t.headOffice(), product, "10");
        assertThat(param("retail.low_stock", "reason")).isEqualTo("out of stock");
    }

    @Test
    void salesAtTheSameMomentQueueOneLowStockRowForEachPerson() throws Exception {
        UUID product = stocked("EV-LOW-9", "20");
        minimum(product, null, "15");
        List<org.springframework.http.HttpStatusCode> statuses =
                Api.race(4, () -> retail.sell(t.headOffice(), "cash", product, "2"));
        assertThat(Api.count(statuses, HttpStatus.CREATED)).isEqualTo(4);
        assertThat(told("retail.low_stock")).containsExactlyInAnyOrder(user(hqSeller), user(admin));
    }

    @Test
    void aBusinessThatSwitchedLowStockOffTellsNobody() {
        UUID product = stocked("EV-LOW-6", "10");
        minimum(product, null, "5");
        switchSetting(Map.of("low_stock", false));
        sell(t.headOffice(), product, "6");
        assertThat(told("retail.low_stock")).isEmpty();
    }

    // ---- Credit payments due ---------------------------------------------------------------------

    void creditSaleDue(LocalDate due) {
        UUID product = stocked("EV-CR-" + UUID.randomUUID().toString().substring(0, 6), "10");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "credit");
        body.put("buyer_name", "Test Buyer 01");
        body.put("due_date", due.toString());
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        ResponseEntity<JsonNode> r =
                retail.postKeyed("/sales", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void creditDueTodayTellsTheAdministratorsOnceFromEightInTheMorning() {
        // The Supervisor of #404 (retail.sale.all_sellers at its branch) at the head office and at the second branch,
        // and a
        // storekeeper at the head office, all with devices.
        UUID hqSupervisor = Api.staff(t, Api.email("ev-sup-hq"), new Role("retail_supervisor", t.headOffice()));
        UUID otherShopSupervisor =
                Api.staff(t, Api.email("ev-sup-b2"), new Role("retail_supervisor", t.secondBranch()));
        UUID hqStorekeeper = Api.staff(t, Api.email("ev-store-hq"), new Role("retail_storekeeper", t.headOffice()));
        device(hqSupervisor);
        device(otherShopSupervisor);
        device(hqStorekeeper);
        creditSaleDue(today());
        dailyAt(today(), LocalTime.of(7, 30));
        assertThat(told("retail.credit_due")).isEmpty();
        dailyAt(today(), LocalTime.of(8, 15));
        assertThat(told("retail.credit_due")).containsExactly(user(admin));
        assertThat(param("retail.credit_due", "due_count")).isEqualTo("1");
        // The shop's notice: only the supervisor of the head office, where the sale is. Not the second branch's
        // supervisor,
        // not the sellers or the storekeeper, and not the administrator, whose branchless role hears the business-wide
        // one.
        assertThat(told("retail.credit_due_branch")).containsExactly(user(hqSupervisor));
        assertThat(param("retail.credit_due_branch", "branch_id"))
                .isEqualTo(t.headOffice().toString());
        assertThat(param("retail.credit_due", "credit_words")).isEqualTo("1 credit sale falls due today");
        dailyAt(today(), LocalTime.of(8, 30));
        assertThat(told("retail.credit_due")).hasSize(1);
        // The day after, the same sale fell overdue: one more notice, for that day.
        dailyAt(today().plusDays(1), LocalTime.of(9, 0));
        assertThat(told("retail.credit_due")).hasSize(2);
        // Nothing falls due that day: the words leave out "0 credit sales fall due".
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT params ->> 'credit_words' FROM notification_outbox
                                 WHERE tenant_id = ? AND channel = 'push' AND idempotency_key LIKE ?
                                 ORDER BY created_at DESC LIMIT 1
                                """)
                        .params(t.tenantId(), "push:retail.credit_due:%")
                        .query(String.class)
                        .single())
                .isEqualTo("1 credit sale is overdue in all");
        // The shop's notice that day goes to the head office's supervisor again, and to nobody else.
        assertThat(told("retail.credit_due_branch")).containsExactly(user(hqSupervisor), user(hqSupervisor));
        assertThat(told("retail.credit_due")).doesNotContain(user(hqSupervisor), user(otherShopSupervisor));
    }

    @Test
    void aServerDownAllMorningSendsNoLateCreditNotice() {
        creditSaleDue(today());
        // The first tick of the day comes after the cut-off: the notice says "today" and is not sent late.
        dailyAt(today(), LocalTime.of(12, 30));
        assertThat(told("retail.credit_due")).isEmpty();
    }

    @Test
    void nothingDueTellsNobody() {
        creditSaleDue(today().plusDays(10));
        dailyAt(today(), LocalTime.of(9, 0));
        assertThat(told("retail.credit_due")).isEmpty();
    }

    // ---- Daily closing summary -------------------------------------------------------------------

    @Test
    void theDailySummaryGoesAtTheBusinesssTimeOnceWithCountsAndNoAmount() {
        UUID product = stocked("EV-SUM-1", "10");
        sell(t.headOffice(), product, "1");
        dailyAt(today(), LocalTime.of(19, 59));
        assertThat(told("retail.daily_summary")).isEmpty();
        dailyAt(today(), LocalTime.of(20, 0));
        assertThat(told("retail.daily_summary")).containsExactly(user(admin));
        assertThat(param("retail.daily_summary", "shops")).contains("1 sale").contains("no sales");
        assertThat(param("retail.daily_summary", "amount")).isNull();
        dailyAt(today(), LocalTime.of(20, 15));
        assertThat(told("retail.daily_summary")).hasSize(1);
        // The business moves it to 18:30: the next day it goes then.
        switchSetting(Map.of("daily_summary_time", "18:30"));
        dailyAt(today().plusDays(1), LocalTime.of(18, 15));
        assertThat(told("retail.daily_summary")).hasSize(1);
        dailyAt(today().plusDays(1), LocalTime.of(18, 30));
        assertThat(told("retail.daily_summary")).hasSize(2);
    }

    @Test
    void aSummaryAtHalfPastElevenGoesBeforeMidnight() {
        // A summary on an earlier day first, so a late one would be owed if the day at 23:30 were not recorded.
        dailyAt(today(), LocalTime.of(20, 0));
        assertThat(told("retail.daily_summary")).hasSize(1);
        switchSetting(Map.of("daily_summary_time", "23:30"));
        LocalDate day2 = today().plusDays(1);
        dailyAt(day2, LocalTime.of(23, 15));
        assertThat(told("retail.daily_summary")).hasSize(1);
        dailyAt(day2, LocalTime.of(23, 30));
        assertThat(told("retail.daily_summary")).hasSize(2);
        // After midnight nothing is owed: day two's went before midnight.
        dailyAt(day2.plusDays(1), LocalTime.of(0, 15));
        assertThat(told("retail.daily_summary_late")).isEmpty();
    }

    @Test
    void aSummaryMissedWhileTheServerWasDownGoesNextMorningAsYesterdays() {
        // A business that had its summary before: day one at closing time.
        dailyAt(today(), LocalTime.of(20, 0));
        assertThat(told("retail.daily_summary")).hasSize(1);
        // Day two: the server is down all evening, so no tick reaches 20:00. It restarts after midnight.
        dailyAt(today().plusDays(2), LocalTime.of(0, 15));
        assertThat(told("retail.daily_summary_late")).containsExactly(user(admin));
        // Once only, and the day after's own summary still goes at its time.
        dailyAt(today().plusDays(2), LocalTime.of(0, 30));
        assertThat(told("retail.daily_summary_late")).hasSize(1);
        dailyAt(today().plusDays(2), LocalTime.of(20, 0));
        assertThat(told("retail.daily_summary")).hasSize(2);
    }

    @Test
    void aFirstReleaseSendsNoLateSummary() {
        // No summary was ever prepared for this business: nothing is caught up after midnight.
        dailyAt(today(), LocalTime.of(0, 15));
        assertThat(told("retail.daily_summary_late")).isEmpty();
    }

    @Test
    void aBusinessThatSwitchedTheSummaryOffGetsNone() {
        switchSetting(Map.of("daily_summary", false));
        dailyAt(today(), LocalTime.of(21, 0));
        assertThat(told("retail.daily_summary")).isEmpty();
    }

    @Test
    void aDaySwitchedOffIsNotSentLateWhenTheSummaryIsTurnedBackOn() {
        // Day one: a summary, so the business has had one before.
        dailyAt(today(), LocalTime.of(20, 0));
        assertThat(told("retail.daily_summary")).hasSize(1);
        // Day two: switched off, its closing time passes without a notice.
        switchSetting(Map.of("daily_summary", false));
        dailyAt(today().plusDays(1), LocalTime.of(20, 0));
        assertThat(told("retail.daily_summary")).hasSize(1);
        // Day three: on again at 08:00. Day two was switched off, so no "Yesterday's sales".
        switchSetting(Map.of("daily_summary", true));
        dailyAt(today().plusDays(2), LocalTime.of(8, 0));
        assertThat(told("retail.daily_summary_late")).isEmpty();
        // Day three's own summary still goes at its time.
        dailyAt(today().plusDays(2), LocalTime.of(20, 0));
        assertThat(told("retail.daily_summary")).hasSize(2);
    }

    // ---- The settings ----------------------------------------------------------------------------

    @Test
    void theSettingsStartAllOnAtEightPmAndRefuseABadTime() {
        ResponseEntity<JsonNode> got = settings(HttpMethod.GET, null, Map.of());
        assertThat(got.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = got.getBody();
        for (String key : List.of("new_orders", "order_updates", "low_stock", "credit_due", "daily_summary")) {
            assertThat(body.get(key).asBoolean()).as(key).isTrue();
        }
        assertThat(body.get("daily_summary_time").asString()).isEqualTo("20:00");
        String etag = got.getHeaders().getETag();
        assertThat(settings(HttpMethod.PUT, Map.of("daily_summary_time", "25:00"), Map.of("If-Match", etag))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // The job wakes on the quarter hour: a time between two, or after 23:30, is refused.
        for (String time : List.of("20:07", "23:45")) {
            assertThat(settings(HttpMethod.PUT, Map.of("daily_summary_time", time), Map.of("If-Match", etag))
                            .getStatusCode())
                    .as(time)
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        assertThat(settings(HttpMethod.PUT, Map.of("low_stock", false), Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        ResponseEntity<JsonNode> seller = http.exchange(
                "/api/v1/settings/notifications",
                HttpMethod.PUT,
                new HttpEntity<>(
                        Map.of("low_stock", false),
                        retail.headers("core.settings.read", "*", Map.of("If-Match", etag))),
                JsonNode.class);
        assertThat(seller.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
