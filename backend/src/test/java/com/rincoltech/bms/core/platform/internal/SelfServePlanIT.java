package com.rincoltech.bms.core.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The Owner's own plan (issue #472, ADR-073) through HTTP: only the Owner changes it (administrators see it, other staff do
 * not); a module asked for is switched on at once when the plan includes it, otherwise the operator approves (possibly
 * moving the plan) or declines with a reason; a module stop and a cancellation take effect the day after the last day paid
 * for, applied by the nightly work on that day and not before, once; a business keeps at least one module; a cancellation
 * can be undone before its day; after it the business is read only (writes refused with {@code subscription_ended}, reads,
 * sign-in and exports allowed); nothing of one business shows to another. Fabricated businesses, people and dates only.
 */
class SelfServePlanIT extends IntegrationTest {

    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");

    @Autowired
    TestRestTemplate http;

    @Autowired
    PlanService plans;

    String lendingPlan;
    String bothPlan;
    LocalDate today;

    record Business(
            TestDatabase.Fixture t,
            Api api,
            UUID owner,
            Session ownerSession,
            Session adminSession,
            Session cashierSession,
            String ownerEmail) {}

    @BeforeEach
    void setUp() {
        http = Api.withoutRetries(http);
        today = LocalDate.now(KAMPALA);
        lendingPlan = plan("{lending}");
        bothPlan = plan("{lending,retail}");
    }

    static String plan(String modules) {
        String code = "test-" + UUID.randomUUID().toString().substring(0, 8);
        TestDatabase.owner()
                .sql("INSERT INTO plans (id, code, name, allowed_modules) VALUES (?, ?, ?, ?::text[])")
                .params(UUID.randomUUID(), code, "Test Plan " + code, modules)
                .update();
        return code;
    }

    /** A business on a plan, with an Owner, another administrator and a cashier, paid until the day given (or not). */
    Business business(String planCode, boolean retail, LocalDate paidUntil) {
        TestDatabase.Fixture t = TestDatabase.tenant("plan", true, retail);
        JdbcCall.run(
                "UPDATE tenants SET plan_id = (SELECT id FROM plans WHERE code = ?) WHERE id = ?",
                planCode,
                t.tenantId());
        JdbcCall.run(
                """
                INSERT INTO subscriptions (id, tenant_id, plan_id, status, current_period_start, current_period_end)
                VALUES (?, ?, (SELECT id FROM plans WHERE code = ?), ?, ?, ?)
                """,
                UUID.randomUUID(),
                t.tenantId(),
                planCode,
                paidUntil == null ? "trial" : "active",
                paidUntil == null ? null : paidUntil.minusMonths(1),
                paidUntil);
        Api api = Api.tenant(http, t.slug());
        String ownerEmail = Api.email("owner");
        String adminEmail = Api.email("admin");
        String cashierEmail = Api.email("cashier");
        UUID owner = Api.staff(t, ownerEmail, new Role("tenant_admin", null));
        UUID admin = Api.staff(t, adminEmail, new Role("tenant_admin", null));
        UUID cashier = Api.staff(t, cashierEmail, new Role("cashier", t.headOffice()));
        JdbcCall.run("UPDATE users SET is_owner = true WHERE tenant_id = ? AND id = ?", t.tenantId(), owner);
        return new Business(
                t,
                api,
                owner,
                api.signIn(owner, ownerEmail, Api.PASSWORD, null),
                api.signIn(admin, adminEmail, Api.PASSWORD, null),
                api.signIn(cashier, cashierEmail, Api.PASSWORD, null),
                ownerEmail);
    }

    /** Raw fixture writes as bms_owner. */
    static final class JdbcCall {
        static void run(String sql, Object... params) {
            TestDatabase.owner().sql(sql).params(params).update();
        }
    }

    static ResponseEntity<JsonNode> withPassword(Api api, String path, Session by) {
        return api.post(path, Map.of("password", Api.PASSWORD), by.accessToken());
    }

    static void assertRefused(ResponseEntity<JsonNode> r, HttpStatus status, String code) {
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(status);
        assertThat(r.getBody().get("code").asString()).isEqualTo(code);
    }

    static List<String> modules(UUID tenantId) {
        return TestDatabase.owner()
                .sql("SELECT module_key FROM tenant_modules WHERE tenant_id = ? ORDER BY module_key")
                .param(tenantId)
                .query(String.class)
                .list();
    }

    static long platformAudits(String action, UUID tenantId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM platform_audit_log WHERE action = ? AND tenant_id = ?")
                .params(action, tenantId)
                .query(Long.class)
                .single();
    }

    int applyDue(UUID tenantId, LocalDate on) {
        return TenantContext.callAs(tenantId, () -> plans.applyDue(on));
    }

    record Operator(Api api, UUID id, Session session) {}

    Operator operator() {
        Api platform = Api.platform(http);
        String email = Api.email("operator");
        UUID id = Api.platformUser(email);
        return new Operator(platform, id, platform.signIn(id, email, Api.PASSWORD, null));
    }

    /** Admins see the card; only the Owner changes the plan; other staff see neither, but do see the end date. */
    @Test
    void onlyTheOwnerChangesThePlan() {
        Business b = business(bothPlan, true, today.plusDays(10));

        ResponseEntity<JsonNode> seen =
                b.api().get("/api/v1/plan", b.adminSession().accessToken());
        assertThat(seen.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(seen.getBody().get("can_change").asBoolean()).isFalse();
        assertThat(seen.getBody().get("paid_until").asString())
                .isEqualTo(today.plusDays(10).toString());
        assertThat(b.api()
                        .get("/api/v1/plan", b.ownerSession().accessToken())
                        .getBody()
                        .get("can_change")
                        .asBoolean())
                .isTrue();
        assertThat(b.api().get("/api/v1/plan", b.cashierSession().accessToken()).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(b.api()
                        .get("/api/v1/plan/status", b.cashierSession().accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertRefused(
                withPassword(b.api(), "/api/v1/plan/modules/retail/stop", b.adminSession()),
                HttpStatus.FORBIDDEN,
                "owner_only");
        assertRefused(
                withPassword(b.api(), "/api/v1/plan/cancel", b.adminSession()), HttpStatus.FORBIDDEN, "owner_only");
        assertRefused(
                withPassword(b.api(), "/api/v1/plan/cancel", b.cashierSession()),
                HttpStatus.FORBIDDEN,
                "permission_denied");
        assertThat(modules(b.t().tenantId())).containsExactly("lending", "retail");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM subscription_scheduled_changes WHERE tenant_id = ?")
                        .param(b.t().tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** A module the plan includes is switched on at once; the operator is told, and the request is audited. */
    @Test
    void aModuleThePlanIncludesIsSwitchedOnAtOnce() {
        Business b = business(bothPlan, false, today.plusDays(10));

        ResponseEntity<JsonNode> asked = b.api()
                .post(
                        "/api/v1/plan/module-requests",
                        Map.of("module", "retail"),
                        b.ownerSession().accessToken());

        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(asked.getBody().get("status").asString()).isEqualTo("approved");
        assertThat(asked.getBody().get("decided_automatically").asBoolean()).isTrue();
        assertThat(modules(b.t().tenantId())).containsExactly("lending", "retail");
        assertThat(platformAudits("platform.tenant.module_requested", b.t().tenantId()))
                .isEqualTo(1);
        assertThat(platformAudits("platform.tenant.modules_changed", b.t().tenantId()))
                .isEqualTo(1);
        assertRefused(
                b.api()
                        .post(
                                "/api/v1/plan/module-requests",
                                Map.of("module", "retail"),
                                b.ownerSession().accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "module_already_on");
    }

    /**
     * Any other module waits for the operator: one pending request per module; approving with a plan move switches it on and
     * emails the Owner; declining keeps the reason the Owner sees. A decided request is not decided again.
     */
    @Test
    void theOperatorApprovesOrDeclinesAModuleThePlanDoesNotInclude() {
        Business b = business(lendingPlan, false, today.plusDays(10));
        Operator op = operator();

        ResponseEntity<JsonNode> asked = b.api()
                .post(
                        "/api/v1/plan/module-requests",
                        Map.of("module", "retail"),
                        b.ownerSession().accessToken());
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(asked.getBody().get("status").asString()).isEqualTo("pending");
        String requestId = asked.getBody().get("id").asString();
        assertRefused(
                b.api()
                        .post(
                                "/api/v1/plan/module-requests",
                                Map.of("module", "retail"),
                                b.ownerSession().accessToken()),
                HttpStatus.CONFLICT,
                "module_request_pending");
        assertThat(modules(b.t().tenantId())).containsExactly("lending");

        ResponseEntity<JsonNode> pending =
                op.api().get("/api/v1/platform/module-requests", op.session().accessToken());
        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> ids = new ArrayList<>();
        pending.getBody().get("items").forEach(i -> ids.add(i.get("id").asString()));
        assertThat(ids).contains(requestId);
        assertThat(b.api()
                        .get(
                                "/api/v1/platform/module-requests",
                                b.ownerSession().accessToken())
                        .getStatusCode())
                .isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED);

        Api.allowTotpReuse(op.id());
        Map<String, Object> approve = new HashMap<>();
        approve.put("totp_code", Api.totp(op.session().totpSecret(), Instant.now()));
        approve.put("reason", "Test: the owner agreed the new plan");
        approve.put("plan_code", bothPlan);
        ResponseEntity<JsonNode> approved = op.api()
                .post(
                        "/api/v1/platform/module-requests/" + requestId + "/approve",
                        approve,
                        op.session().accessToken());
        assertThat(approved.getStatusCode()).as("%s", approved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody().get("status").asString()).isEqualTo("approved");
        assertThat(modules(b.t().tenantId())).containsExactly("lending", "retail");
        assertThat(b.api()
                        .get("/api/v1/plan", b.ownerSession().accessToken())
                        .getBody()
                        .get("plan_code")
                        .asString())
                .isEqualTo(bothPlan);
        assertThat(platformAudits("platform.tenant.module_request_decided", b.t().tenantId()))
                .isEqualTo(1);
        assertThat(platformAudits("platform.tenant.plan_changed", b.t().tenantId()))
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox WHERE recipient = ? AND template_key = ?")
                        .params(b.ownerEmail(), PlanService.DECIDED)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertRefused(
                op.api()
                        .post(
                                "/api/v1/platform/module-requests/" + requestId + "/decline",
                                Map.of("reason", "Test: too late"),
                                op.session().accessToken()),
                HttpStatus.CONFLICT,
                "request_already_decided");

        Business other = business(lendingPlan, false, today.plusDays(10));
        String otherId = other.api()
                .post(
                        "/api/v1/plan/module-requests",
                        Map.of("module", "retail"),
                        other.ownerSession().accessToken())
                .getBody()
                .get("id")
                .asString();
        ResponseEntity<JsonNode> declined = op.api()
                .post(
                        "/api/v1/platform/module-requests/" + otherId + "/decline",
                        Map.of("reason", "Test: not offered in this area yet"),
                        op.session().accessToken());
        assertThat(declined.getStatusCode()).as("%s", declined.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(modules(other.t().tenantId())).containsExactly("lending");
        JsonNode card = other.api()
                .get("/api/v1/plan", other.ownerSession().accessToken())
                .getBody();
        assertThat(card.get("requests").get(0).get("status").asString()).isEqualTo("declined");
        assertThat(card.get("requests").get(0).get("decision_reason").asString())
                .isEqualTo("Test: not offered in this area yet");
    }

    /** A module stop needs the step-up, waits for the day after the last day paid for, and is applied once. */
    @Test
    void aStoppedModuleIsSwitchedOffOnItsDayAndNotBefore() {
        LocalDate paidUntil = today.plusDays(10);
        Business b = business(bothPlan, true, paidUntil);

        assertRefused(
                b.api()
                        .post(
                                "/api/v1/plan/modules/retail/stop",
                                Map.of(),
                                b.ownerSession().accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "password_required");
        ResponseEntity<JsonNode> stopped = withPassword(b.api(), "/api/v1/plan/modules/retail/stop", b.ownerSession());
        assertThat(stopped.getStatusCode()).as("%s", stopped.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(stopped.getBody().get("effective_on").asString())
                .isEqualTo(paidUntil.plusDays(1).toString());
        assertThat(stopped.getBody().get("status").asString()).isEqualTo("scheduled");
        assertThat(modules(b.t().tenantId())).containsExactly("lending", "retail");
        assertRefused(
                withPassword(b.api(), "/api/v1/plan/modules/retail/stop", b.ownerSession()),
                HttpStatus.CONFLICT,
                "change_already_scheduled");
        Operator op = operator();
        List<String> scheduled = new ArrayList<>();
        op.api()
                .get("/api/v1/platform/scheduled-changes", op.session().accessToken())
                .getBody()
                .get("items")
                .forEach(i -> scheduled.add(i.get("id").asString()));
        assertThat(scheduled).contains(stopped.getBody().get("id").asString());

        assertThat(applyDue(b.t().tenantId(), paidUntil)).isZero();
        assertThat(modules(b.t().tenantId())).containsExactly("lending", "retail");

        assertThat(applyDue(b.t().tenantId(), paidUntil.plusDays(1))).isEqualTo(1);
        assertThat(modules(b.t().tenantId())).containsExactly("lending");
        assertThat(applyDue(b.t().tenantId(), paidUntil.plusDays(2))).isZero();
        assertThat(platformAudits("platform.tenant.plan_change_applied", b.t().tenantId()))
                .isEqualTo(1);
        // The records stay: switching retail on again finds the chart of accounts it had.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM gl_accounts WHERE tenant_id = ? AND system_key = 'cash_on_hand'")
                        .param(b.t().tenantId())
                        .query(Long.class)
                        .single())
                .isPositive();
    }

    /** In a free month (nothing paid ahead) a module stops at once. */
    @Test
    void inAFreeMonthAModuleStopsAtOnce() {
        Business b = business(bothPlan, true, null);

        ResponseEntity<JsonNode> stopped = withPassword(b.api(), "/api/v1/plan/modules/retail/stop", b.ownerSession());

        assertThat(stopped.getStatusCode()).as("%s", stopped.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(stopped.getBody().get("status").asString()).isEqualTo("applied");
        assertThat(modules(b.t().tenantId())).containsExactly("lending");
    }

    /** A business keeps at least one module, counting the stops already scheduled. */
    @Test
    void theLastModuleCannotBeStopped() {
        Business only = business(lendingPlan, false, today.plusDays(10));
        assertRefused(
                withPassword(only.api(), "/api/v1/plan/modules/lending/stop", only.ownerSession()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "last_module");

        Business both = business(bothPlan, true, today.plusDays(10));
        assertThat(withPassword(both.api(), "/api/v1/plan/modules/retail/stop", both.ownerSession())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertRefused(
                withPassword(both.api(), "/api/v1/plan/modules/lending/stop", both.ownerSession()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "last_module");
    }

    // JUSTIFICATION-A3: review fix 4 of #474 asks for this concurrency test; the threads need their own setup.
    /**
     * Two stops sent at once are made one after the other under the subscription's row lock: of the two last modules only
     * one is stopped (the other refused with {@code last_module}), and the same module twice is scheduled once.
     */
    @Test
    void twoStopsSentAtOnceKeepOneModule() throws Exception {
        Business b = business(bothPlan, true, today.plusDays(10));
        List<ResponseEntity<JsonNode>> both = atOnce(
                () -> withPassword(b.api(), "/api/v1/plan/modules/lending/stop", b.ownerSession()),
                () -> withPassword(b.api(), "/api/v1/plan/modules/retail/stop", b.ownerSession()));
        assertThat(both.stream().map(r -> r.getStatusCode().value()).sorted().toList())
                .as("%s", both)
                .containsExactly(201, 422);
        assertThat(both.stream()
                        .filter(r -> r.getStatusCode().value() == 422)
                        .findFirst()
                        .orElseThrow()
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("last_module");

        Business c = business(bothPlan, true, today.plusDays(10));
        List<ResponseEntity<JsonNode>> same = atOnce(
                () -> withPassword(c.api(), "/api/v1/plan/modules/retail/stop", c.ownerSession()),
                () -> withPassword(c.api(), "/api/v1/plan/modules/retail/stop", c.ownerSession()));
        assertThat(same.stream().map(r -> r.getStatusCode().value()).sorted().toList())
                .as("%s", same)
                .containsExactly(201, 409);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM subscription_scheduled_changes WHERE tenant_id IN (?, ?)")
                        .params(b.t().tenantId(), c.t().tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @SafeVarargs
    static List<ResponseEntity<JsonNode>> atOnce(Callable<ResponseEntity<JsonNode>>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResponseEntity<JsonNode>>> futures = new ArrayList<>();
            for (Callable<ResponseEntity<JsonNode>> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<ResponseEntity<JsonNode>> results = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** A scheduled stop that has nothing left to switch off is closed as undone, not applied. */
    @Test
    void aStopThatChangesNothingIsClosedAsUndone() {
        LocalDate paidUntil = today.plusDays(10);
        Business b = business(bothPlan, true, paidUntil);
        String id = withPassword(b.api(), "/api/v1/plan/modules/retail/stop", b.ownerSession())
                .getBody()
                .get("id")
                .asString();
        JdbcCall.run("DELETE FROM tenant_modules WHERE tenant_id = ? AND module_key = 'retail'", b.t().tenantId());

        assertThat(applyDue(b.t().tenantId(), paidUntil.plusDays(1))).isZero();

        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM subscription_scheduled_changes WHERE id = ?::uuid")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("undone");
        assertThat(modules(b.t().tenantId())).containsExactly("lending");
        assertThat(platformAudits("platform.tenant.plan_change_applied", b.t().tenantId()))
                .isZero();
    }

    /**
     * A cancellation waits for the end of the paid period and shows its date to every member of staff; the Owner undoes it
     * before then. Cancelled again and applied on its day, the business is read only: writes are refused with
     * {@code subscription_ended}; reading, signing in and the audit export still work; data is kept.
     */
    @Test
    void cancelUndoAndReadOnlyAfterTheEnd() {
        LocalDate paidUntil = today.plusDays(5);
        Business b = business(bothPlan, true, paidUntil);

        ResponseEntity<JsonNode> cancelled = withPassword(b.api(), "/api/v1/plan/cancel", b.ownerSession());
        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.CREATED);
        String endsOn = paidUntil.plusDays(1).toString();
        assertThat(cancelled.getBody().get("effective_on").asString()).isEqualTo(endsOn);
        assertThat(b.api()
                        .get("/api/v1/plan/status", b.cashierSession().accessToken())
                        .getBody()
                        .get("ends_on")
                        .asString())
                .isEqualTo(endsOn);

        ResponseEntity<JsonNode> undone = b.api()
                .post(
                        "/api/v1/plan/changes/" + cancelled.getBody().get("id").asString() + "/undo",
                        null,
                        b.ownerSession().accessToken());
        assertThat(undone.getStatusCode()).as("%s", undone.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(undone.getBody().get("status").asString()).isEqualTo("undone");
        assertThat(b.api()
                        .get("/api/v1/plan/status", b.cashierSession().accessToken())
                        .getBody()
                        .get("ends_on")
                        .isNull())
                .isTrue();
        assertThat(applyDue(b.t().tenantId(), paidUntil.plusDays(1))).isZero();

        ResponseEntity<JsonNode> again = withPassword(b.api(), "/api/v1/plan/cancel", b.ownerSession());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(applyDue(b.t().tenantId(), paidUntil)).isZero();
        assertThat(b.api()
                        .post(
                                "/api/v1/branches",
                                Map.of("code", "BR3", "name", "Test Branch Three"),
                                b.adminSession().accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(applyDue(b.t().tenantId(), paidUntil.plusDays(1))).isEqualTo(1);
        assertThat(applyDue(b.t().tenantId(), paidUntil.plusDays(1))).isZero();

        assertRefused(
                b.api()
                        .post(
                                "/api/v1/branches",
                                Map.of("code", "BR4", "name", "Test Branch Four"),
                                b.adminSession().accessToken()),
                HttpStatus.LOCKED,
                "subscription_ended");
        assertRefused(
                b.api()
                        .post(
                                "/api/v1/plan/changes/"
                                        + again.getBody().get("id").asString() + "/undo",
                                null,
                                b.ownerSession().accessToken()),
                HttpStatus.LOCKED,
                "subscription_ended");
        assertThat(b.api()
                        .get("/api/v1/branches", b.adminSession().accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode card =
                b.api().get("/api/v1/plan", b.ownerSession().accessToken()).getBody();
        assertThat(card.get("ended_on").asString())
                .isEqualTo(paidUntil.plusDays(1).toString());
        assertThat(card.get("subscription_status").asString()).isEqualTo("cancelled");
        assertThat(b.api()
                        .get("/api/v1/plan/status", b.cashierSession().accessToken())
                        .getBody()
                        .get("ended_on")
                        .asString())
                .isEqualTo(paidUntil.plusDays(1).toString());
        assertThat(b.api().login(b.ownerEmail(), Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(b.api()
                        .call(
                                HttpMethod.POST,
                                "/api/v1/audit-events/export",
                                Map.of(),
                                b.adminSession().accessToken())
                        .getStatusCode())
                .isNotEqualTo(HttpStatus.LOCKED);
        assertThat(modules(b.t().tenantId())).containsExactly("lending", "retail");
        assertThat(platformAudits("platform.tenant.subscription_ended", b.t().tenantId()))
                .isEqualTo(1);
    }

    /** Nothing of one business shows to another: its requests, its changes and its undo. */
    @Test
    void oneBusinessNeverSeesAnothersPlanChanges() {
        Business a = business(lendingPlan, false, today.plusDays(10));
        Business b = business(lendingPlan, false, today.plusDays(10));
        a.api()
                .post(
                        "/api/v1/plan/module-requests",
                        Map.of("module", "retail"),
                        a.ownerSession().accessToken());
        String cancelId = withPassword(a.api(), "/api/v1/plan/cancel", a.ownerSession())
                .getBody()
                .get("id")
                .asString();

        JsonNode card =
                b.api().get("/api/v1/plan", b.ownerSession().accessToken()).getBody();
        assertThat(card.get("requests")).isEmpty();
        assertThat(card.get("changes")).isEmpty();
        assertThat(card.get("ends_on").isNull()).isTrue();
        assertThat(b.api()
                        .post(
                                "/api/v1/plan/changes/" + cancelId + "/undo",
                                null,
                                b.ownerSession().accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(a.api()
                        .get("/api/v1/plan/status", a.cashierSession().accessToken())
                        .getBody()
                        .get("ends_on")
                        .isNull())
                .isFalse();
    }
}
