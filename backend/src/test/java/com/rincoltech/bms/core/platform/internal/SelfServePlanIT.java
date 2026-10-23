package com.rincoltech.bms.core.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
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
        return business(planCode, retail, paidUntil, false);
    }

    /** The same, with two-step sign-in required of administrators when asked, so the Owner proves changes with a code. */
    Business business(String planCode, boolean retail, LocalDate paidUntil, boolean twoStep) {
        TestDatabase.Fixture t = TestDatabase.tenant("plan", true, retail);
        if (twoStep) {
            Api.requireTwoStepForAdmins(t);
        }
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
                        Map.of("module", "retail", "password", Api.PASSWORD),
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
                                Map.of("module", "retail", "password", Api.PASSWORD),
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
                        Map.of("module", "retail", "password", Api.PASSWORD),
                        b.ownerSession().accessToken());
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(asked.getBody().get("status").asString()).isEqualTo("pending");
        String requestId = asked.getBody().get("id").asString();
        assertRefused(
                b.api()
                        .post(
                                "/api/v1/plan/module-requests",
                                Map.of("module", "retail", "password", Api.PASSWORD),
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
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND actor_kind = 'platform'")
                        .params(b.t().tenantId(), "core.plan.module_request_decided")
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(platformAudits("platform.tenant.plan_changed", b.t().tenantId()))
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox WHERE recipient = ? AND template_key = ?")
                        .params(b.ownerEmail(), PlanService.DECIDED)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        Api.allowTotpReuse(op.id());
        assertRefused(
                op.api()
                        .post(
                                "/api/v1/platform/module-requests/" + requestId + "/decline",
                                Map.of(
                                        "reason",
                                        "Test: too late",
                                        "totp_code",
                                        Api.totp(op.session().totpSecret(), Instant.now())),
                                op.session().accessToken()),
                HttpStatus.CONFLICT,
                "request_already_decided");

        Business other = business(lendingPlan, false, today.plusDays(10));
        String otherId = other.api()
                .post(
                        "/api/v1/plan/module-requests",
                        Map.of("module", "retail", "password", Api.PASSWORD),
                        other.ownerSession().accessToken())
                .getBody()
                .get("id")
                .asString();
        assertThat(op.api()
                        .post(
                                "/api/v1/platform/module-requests/" + otherId + "/decline",
                                Map.of("reason", "Test: not offered in this area yet"),
                                op.session().accessToken())
                        .getStatusCode()
                        .is4xxClientError())
                .isTrue();
        Api.allowTotpReuse(op.id());
        ResponseEntity<JsonNode> declined = op.api()
                .post(
                        "/api/v1/platform/module-requests/" + otherId + "/decline",
                        Map.of(
                                "reason",
                                "Test: not offered in this area yet",
                                "totp_code",
                                Api.totp(op.session().totpSecret(), Instant.now())),
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
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT DISTINCT data ->> 'owner_user_id' FROM platform_audit_log
                                 WHERE tenant_id = ? AND action IN ('platform.tenant.plan_change_scheduled',
                                                                    'platform.tenant.plan_change_applied')
                                """)
                        .param(b.t().tenantId())
                        .query(String.class)
                        .list())
                .containsExactly(b.owner().toString());
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

    /**
     * The operator's approval takes the subscription's lock before it reads the modules. While another change holds the lock,
     * the approval waits and switches nothing. When the lock is released, the Owner's immediate stop of the other module is
     * sent at the same moment; the two run one after the other, so the modules on are exactly what the two answers say. With
     * the lock removed from {@code decideRequest} the approval does not wait, and this test fails.
     */
    @Test
    void theOperatorsApprovalWaitsForTheLockAndAnImmediateStopNeverUndoesIt() throws Exception {
        Business b = business(lendingPlan, false, null);
        UUID tenantId = b.t().tenantId();
        ResponseEntity<JsonNode> asked = b.api()
                .post(
                        "/api/v1/plan/module-requests",
                        Map.of("module", "retail", "password", Api.PASSWORD),
                        b.ownerSession().accessToken());
        assertThat(asked.getBody().get("status").asString())
                .as("%s", asked.getBody())
                .isEqualTo("pending");
        String requestId = asked.getBody().get("id").asString();
        // The operator widened the plan meanwhile, so the approval needs no plan move.
        JdbcCall.run("UPDATE plans SET allowed_modules = '{lending,retail}' WHERE code = ?", lendingPlan);
        Operator op = operator();
        Api.allowTotpReuse(op.id());
        Map<String, Object> approve = Map.of(
                "totp_code",
                Api.totp(op.session().totpSecret(), Instant.now()),
                "reason",
                "Test: the plan includes it now");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch released = new CountDownLatch(1);
            Future<ResponseEntity<JsonNode>> approved;
            Future<ResponseEntity<JsonNode>> stopped;
            try (SubscriptionLock held = new SubscriptionLock(tenantId)) {
                approved = pool.submit(() -> op.api()
                        .post(
                                "/api/v1/platform/module-requests/" + requestId + "/approve",
                                approve,
                                op.session().accessToken()));
                assertThat(lockWaiters(1))
                        .as("the approval waits for the subscription's lock")
                        .isTrue();
                assertThat(approved.isDone()).isFalse();
                assertThat(modules(tenantId)).containsExactly("lending");
                stopped = pool.submit(() -> {
                    released.await();
                    return withPassword(b.api(), "/api/v1/plan/modules/lending/stop", b.ownerSession());
                });
                released.countDown();
            }
            ResponseEntity<JsonNode> a = approved.get(60, TimeUnit.SECONDS);
            ResponseEntity<JsonNode> s = stopped.get(60, TimeUnit.SECONDS);
            assertThat(a.getStatusCode()).as("%s", a.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(a.getBody().get("status").asString()).isEqualTo("approved");
            if (s.getStatusCode() == HttpStatus.CREATED) {
                assertThat(s.getBody().get("status").asString()).isEqualTo("applied");
                assertThat(modules(tenantId)).containsExactly("retail");
            } else {
                assertRefused(s, HttpStatus.UNPROCESSABLE_CONTENT, "last_module");
                assertThat(modules(tenantId)).containsExactly("lending", "retail");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The nightly job and the Owner's ask switched on at once both take the subscription's lock first. While another change
     * holds it, neither switches anything. When the lock is released they run one after the other: the job's stop of
     * lending applies only if retail came on first, and is otherwise closed as undone (it would leave no module). Either
     * way the modules on match the two answers. With the lock removed from {@code applyDue} or {@code requestModule} that
     * change does not wait, and this test fails.
     */
    @Test
    void theJobAndAnAskSwitchedOnAtOnceWaitForTheLockAndNeverUndoEachOther() throws Exception {
        LocalDate paidUntil = today.plusDays(5);
        Business b = business(bothPlan, true, paidUntil);
        UUID tenantId = b.t().tenantId();
        ResponseEntity<JsonNode> scheduled =
                withPassword(b.api(), "/api/v1/plan/modules/lending/stop", b.ownerSession());
        assertThat(scheduled.getStatusCode()).as("%s", scheduled.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID stopId = UUID.fromString(scheduled.getBody().get("id").asString());
        Operator op = operator();
        ResponseEntity<JsonNode> switched = op.api()
                .call(
                        HttpMethod.PUT,
                        "/api/v1/platform/tenants/" + tenantId + "/modules",
                        Map.of("modules", List.of("lending"), "reason", "Test: retail switched off by the operator"),
                        op.session().accessToken());
        assertThat(switched.getStatusCode()).as("%s", switched.getBody()).isEqualTo(HttpStatus.OK);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> job;
            Future<ResponseEntity<JsonNode>> asked;
            try (SubscriptionLock held = new SubscriptionLock(tenantId)) {
                job = pool.submit(() -> {
                    start.await();
                    return TenantContext.callAs(tenantId, () -> plans.applyDue(paidUntil.plusDays(1)));
                });
                asked = pool.submit(() -> {
                    start.await();
                    return b.api()
                            .post(
                                    "/api/v1/plan/module-requests",
                                    Map.of("module", "retail", "password", Api.PASSWORD),
                                    b.ownerSession().accessToken());
                });
                start.countDown();
                assertThat(lockWaiters(2))
                        .as("the job and the ask wait for the subscription's lock")
                        .isTrue();
                assertThat(job.isDone()).isFalse();
                assertThat(asked.isDone()).isFalse();
                assertThat(modules(tenantId)).containsExactly("lending");
            }
            int applied = job.get(60, TimeUnit.SECONDS);
            ResponseEntity<JsonNode> a = asked.get(60, TimeUnit.SECONDS);
            assertThat(a.getStatusCode()).as("%s", a.getBody()).isEqualTo(HttpStatus.CREATED);
            assertThat(a.getBody().get("status").asString()).isEqualTo("approved");
            String stop = TestDatabase.owner()
                    .sql("SELECT status FROM subscription_scheduled_changes WHERE id = ?")
                    .param(stopId)
                    .query(String.class)
                    .single();
            if (applied == 1) {
                assertThat(stop).isEqualTo("applied");
                assertThat(modules(tenantId)).containsExactly("retail");
            } else {
                assertThat(stop).isEqualTo("undone");
                assertThat(modules(tenantId)).containsExactly("lending", "retail");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Holds the subscription's row lock in a transaction of its own, as another plan change would, until closed. */
    static final class SubscriptionLock implements AutoCloseable {
        final Connection connection;

        SubscriptionLock(UUID tenantId) throws SQLException {
            connection = TestDatabase.ownerDataSource().getConnection();
            connection.setAutoCommit(false);
            try (PreparedStatement select =
                    connection.prepareStatement("SELECT 1 FROM subscriptions WHERE tenant_id = ? FOR UPDATE")) {
                select.setObject(1, tenantId);
                select.executeQuery().close();
            }
        }

        @Override
        public void close() throws SQLException {
            connection.commit();
            connection.close();
        }
    }

    /** Whether at least n sessions wait on a lock within ten seconds (pg_locks, which every role may read in full). */
    static boolean lockWaiters(int n) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            long waiting = TestDatabase.owner()
                    .sql("SELECT count(DISTINCT pid) FROM pg_locks WHERE NOT granted")
                    .query(Long.class)
                    .single();
            if (waiting >= n) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
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
     * A module asked for again and switched on closes the stop of it still scheduled, so that stop never switches it off on its
     * day without anyone asking (review N1).
     */
    @Test
    void aModuleSwitchedOnAgainClosesItsScheduledStop() {
        LocalDate paidUntil = today.plusDays(10);
        Business b = business(bothPlan, true, paidUntil);
        String stopId = withPassword(b.api(), "/api/v1/plan/modules/retail/stop", b.ownerSession())
                .getBody()
                .get("id")
                .asString();
        JdbcCall.run("DELETE FROM tenant_modules WHERE tenant_id = ? AND module_key = 'retail'", b.t().tenantId());

        ResponseEntity<JsonNode> asked = b.api()
                .post(
                        "/api/v1/plan/module-requests",
                        Map.of("module", "retail", "password", Api.PASSWORD),
                        b.ownerSession().accessToken());
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(asked.getBody().get("status").asString()).isEqualTo("approved");

        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM subscription_scheduled_changes WHERE id = ?::uuid")
                        .param(stopId)
                        .query(String.class)
                        .single())
                .isEqualTo("undone");
        assertThat(applyDue(b.t().tenantId(), paidUntil.plusDays(1))).isZero();
        assertThat(modules(b.t().tenantId())).containsExactly("lending", "retail");
    }

    /**
     * A stop due after the cancellation's day (the operator recorded a payment after the Owner cancelled, so the stop asked
     * then falls later) is closed as undone when the cancellation applies, so it never switches a module off after a resume.
     */
    @Test
    void aStopDueAfterTheEndIsClosedWhenTheCancellationApplies() {
        LocalDate paidUntil = today.plusDays(5);
        Business b = business(bothPlan, true, paidUntil);
        assertThat(withPassword(b.api(), "/api/v1/plan/cancel", b.ownerSession())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        JdbcCall.run(
                "UPDATE subscriptions SET current_period_end = ? WHERE tenant_id = ?",
                paidUntil.plusMonths(1),
                b.t().tenantId());
        ResponseEntity<JsonNode> stop = withPassword(b.api(), "/api/v1/plan/modules/retail/stop", b.ownerSession());
        assertThat(stop.getStatusCode()).as("%s", stop.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(stop.getBody().get("effective_on").asString())
                .isEqualTo(paidUntil.plusMonths(1).plusDays(1).toString());

        assertThat(applyDue(b.t().tenantId(), paidUntil.plusDays(1))).isEqualTo(1);

        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT status FROM subscription_scheduled_changes WHERE tenant_id = ? AND kind = 'stop_module'")
                        .param(b.t().tenantId())
                        .query(String.class)
                        .single())
                .isEqualTo("undone");
        assertThat(applyDue(b.t().tenantId(), paidUntil.plusMonths(1).plusDays(1)))
                .isZero();
        assertThat(modules(b.t().tenantId())).containsExactly("lending", "retail");
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

    /**
     * The owner step-up for a stop and a cancel: a wrong password is refused; an Owner with an authenticator proves it with
     * a current code, the password alone being refused with {@code totp_required}.
     */
    @Test
    void askStopAndCancelNeedTheOwnersStepUp() {
        Business b = business(bothPlan, true, today.plusDays(10));
        Map<String, Object> wrong = Map.of("password", "Not-The-Password-1");
        Business asking = business(bothPlan, false, today.plusDays(10));
        assertRefused(
                asking.api()
                        .post(
                                "/api/v1/plan/module-requests",
                                Map.of("module", "retail", "password", "Not-The-Password-1"),
                                asking.ownerSession().accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "invalid_password");
        assertRefused(
                asking.api()
                        .post(
                                "/api/v1/plan/module-requests",
                                Map.of("module", "retail"),
                                asking.ownerSession().accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "password_required");
        assertThat(modules(asking.t().tenantId())).containsExactly("lending");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM subscription_module_requests WHERE tenant_id = ?")
                        .param(asking.t().tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
        assertRefused(
                b.api()
                        .post(
                                "/api/v1/plan/modules/retail/stop",
                                wrong,
                                b.ownerSession().accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "invalid_password");
        assertRefused(
                b.api().post("/api/v1/plan/cancel", wrong, b.ownerSession().accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "invalid_password");
        assertRefused(
                b.api().post("/api/v1/plan/cancel", Map.of(), b.ownerSession().accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "password_required");

        Business c = business(bothPlan, true, today.plusDays(10), true);
        Session owner = c.ownerSession();
        assertRefused(
                c.api()
                        .post(
                                "/api/v1/plan/module-requests",
                                Map.of("module", "retail", "totp_code", "000000"),
                                owner.accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "module_already_on");
        Business d = business(bothPlan, false, today.plusDays(10), true);
        assertRefused(
                d.api()
                        .post(
                                "/api/v1/plan/module-requests",
                                Map.of("module", "retail", "password", Api.PASSWORD),
                                d.ownerSession().accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "totp_required");
        assertRefused(
                d.api()
                        .post(
                                "/api/v1/plan/module-requests",
                                Map.of("module", "retail", "totp_code", "000000"),
                                d.ownerSession().accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "invalid_mfa_code");
        Api.allowTotpReuse(d.owner());
        ResponseEntity<JsonNode> asked = d.api()
                .post(
                        "/api/v1/plan/module-requests",
                        Map.of(
                                "module",
                                "retail",
                                "totp_code",
                                Api.totp(d.ownerSession().totpSecret(), Instant.now())),
                        d.ownerSession().accessToken());
        assertThat(asked.getStatusCode()).as("%s", asked.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(modules(d.t().tenantId())).containsExactly("lending", "retail");
        assertRefused(
                withPassword(c.api(), "/api/v1/plan/modules/retail/stop", owner),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "totp_required");
        assertRefused(
                withPassword(c.api(), "/api/v1/plan/cancel", owner), HttpStatus.UNPROCESSABLE_CONTENT, "totp_required");
        Api.allowTotpReuse(c.owner());
        ResponseEntity<JsonNode> stopped = c.api()
                .post(
                        "/api/v1/plan/modules/retail/stop",
                        Map.of("totp_code", Api.totp(owner.totpSecret(), Instant.now())),
                        owner.accessToken());
        assertThat(stopped.getStatusCode()).as("%s", stopped.getBody()).isEqualTo(HttpStatus.CREATED);
        Api.allowTotpReuse(c.owner());
        ResponseEntity<JsonNode> cancelled = c.api()
                .post(
                        "/api/v1/plan/cancel",
                        Map.of("totp_code", Api.totp(owner.totpSecret(), Instant.now())),
                        owner.accessToken());
        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    /**
     * After the end one write of every area is refused with {@code subscription_ended} (a retail sale, a disbursement, a
     * repayment, an import, an upload, a settings change, an invitation); the nightly lending job (arrears and penalties)
     * no longer lists the business; the operator still acts on it, and the operator's resume makes it writable again.
     */
    @Test
    void afterTheEndEveryAreaIsReadOnlyUntilTheOperatorResumes() {
        LocalDate paidUntil = today.plusDays(5);
        Business b = business(bothPlan, true, paidUntil);
        UUID tenantId = b.t().tenantId();
        assertThat(lendingJobTenants()).contains(tenantId);
        assertThat(withPassword(b.api(), "/api/v1/plan/cancel", b.ownerSession())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(applyDue(tenantId, paidUntil.plusDays(1))).isEqualTo(1);

        String admin = b.adminSession().accessToken();
        String loan = "/api/v1/lending/loans/" + UUID.randomUUID();
        LinkedMultiValueMap<String, Object> logo = new LinkedMultiValueMap<>();
        logo.add("file", new ByteArrayResource(new byte[] {1, 2, 3}) {
            @Override
            public String getFilename() {
                return "logo.png";
            }
        });
        List<ResponseEntity<JsonNode>> writes = List.of(
                b.api().post("/api/v1/retail/sales", Map.of(), admin),
                b.api().post(loan + "/disbursements", Map.of(), admin),
                b.api().post(loan + "/repayments", Map.of(), admin),
                b.api().post("/api/v1/retail/products/import?dry_run=false", Map.of("csv", "name\nTest"), admin),
                b.api().call(HttpMethod.PUT, "/api/v1/settings/logo", logo, admin),
                b.api().call(HttpMethod.PATCH, "/api/v1/settings", Map.of("name", "Test Business"), admin),
                b.api().post("/api/v1/users", Map.of("email", Api.email("invited")), admin));
        for (ResponseEntity<JsonNode> write : writes) {
            assertRefused(write, HttpStatus.LOCKED, "subscription_ended");
        }
        assertThat(lendingJobTenants()).doesNotContain(tenantId);

        Operator op = operator();
        String base = "/api/v1/platform/tenants/" + tenantId;
        ResponseEntity<JsonNode> modules = op.api()
                .call(
                        HttpMethod.PUT,
                        base + "/modules",
                        Map.of("modules", List.of("lending", "retail"), "reason", "Test: checked after the end"),
                        op.session().accessToken());
        assertThat(modules.getStatusCode()).as("%s", modules.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> resumed = op.api()
                .post(
                        base + "/resume",
                        Map.of("reason", "Test: the owner paid again"),
                        op.session().accessToken());
        assertThat(resumed.getStatusCode()).as("%s", resumed.getBody()).isEqualTo(HttpStatus.OK);

        assertThat(b.api()
                        .post("/api/v1/branches", Map.of("code", "BR5", "name", "Test Branch Five"), admin)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode card =
                b.api().get("/api/v1/plan", b.ownerSession().accessToken()).getBody();
        assertThat(card.get("ended_on").isNull()).isTrue();
        assertThat(card.get("subscription_status").asString()).isNotEqualTo("cancelled");
        assertThat(lendingJobTenants()).contains(tenantId);
    }

    /**
     * The Owner cancels for the day after the paid period (say 1 December), the operator suspends the business before it
     * (say 20 November, for fraud or non-payment), and on the day the business stays suspended: the resolver does not answer
     * {@code ended}, the job leaves the cancellation scheduled, payments do not post, report runs are not picked up and
     * exports stay refused. Once the operator resumes, the cancellation applies at the job's next run.
     */
    @Test
    void anOperatorSuspensionIsNeverTurnedIntoAnEndByTheOwnersCancellation() {
        LocalDate paidUntil = today.plusDays(5);
        Business b = business(bothPlan, true, paidUntil);
        UUID tenantId = b.t().tenantId();
        String admin = b.adminSession().accessToken();
        ResponseEntity<JsonNode> cancelled = withPassword(b.api(), "/api/v1/plan/cancel", b.ownerSession());
        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.CREATED);
        String changeId = cancelled.getBody().get("id").asString();

        Operator op = operator();
        String base = "/api/v1/platform/tenants/" + tenantId;
        ResponseEntity<JsonNode> suspended = op.api()
                .post(
                        base + "/suspend",
                        Map.of("reason", "Test: suspected fraud"),
                        op.session().accessToken());
        assertThat(suspended.getStatusCode()).as("%s", suspended.getBody()).isEqualTo(HttpStatus.OK);

        // The cancellation's day comes: moved to today, so the resolver (which reads the real date) sees it due too.
        JdbcCall.run(
                "UPDATE subscription_scheduled_changes SET effective_on = ? WHERE id = ?",
                today,
                UUID.fromString(changeId));
        UUID run = UUID.randomUUID();
        JdbcCall.run("""
                INSERT INTO report_runs (id, tenant_id, report_key, params, params_hash, scope, format, status,
                                         requested_by, as_of, row_cap, size_cap_bytes)
                VALUES (?, ?, 'lending.par', '{}', repeat('a', 64), '{}', 'json', 'queued', ?, ?, 1000, 1000000)
                """, run, tenantId, b.owner(), today);
        try {
            assertThat(TestDatabase.owner()
                            .sql("SELECT count(*) FROM platform_tenants_with_due_changes(?) WHERE tenant_id = ?")
                            .params(today.plusDays(1), tenantId)
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(applyDue(tenantId, today)).isZero();

            assertThat(TestDatabase.owner()
                            .sql("SELECT status FROM subscription_scheduled_changes WHERE id = ?")
                            .param(UUID.fromString(changeId))
                            .query(String.class)
                            .single())
                    .isEqualTo("scheduled");
            assertThat(TestDatabase.owner()
                            .sql(
                                    "SELECT status || '/' || coalesce(ended_on::text, '-') FROM subscriptions WHERE tenant_id = ?")
                            .param(tenantId)
                            .query(String.class)
                            .single())
                    .isEqualTo("suspended/-");
            assertThat(TestDatabase.owner()
                            .sql("SELECT status FROM app_resolve_tenant_status(?)")
                            .param(b.t().slug())
                            .query(String.class)
                            .single())
                    .isEqualTo("suspended");
            assertThat(TestDatabase.owner()
                            .sql("SELECT app_tenant_takes_payments(?)")
                            .param(tenantId)
                            .query(Boolean.class)
                            .single())
                    .isFalse();
            assertThat(TestDatabase.owner()
                            .sql("SELECT app_tenants_with_queued_report_runs()")
                            .query(UUID.class)
                            .list())
                    .doesNotContain(tenantId);
            ResponseEntity<String> export = b.api().postForText("/api/v1/audit-events/export", Map.of(), admin);
            assertThat(export.getStatusCode()).as("%s", export.getBody()).isEqualTo(HttpStatus.LOCKED);
            assertThat(export.getBody()).contains("\"tenant_suspended\"");
            assertRefused(
                    b.api().post("/api/v1/branches", Map.of("code", "BR6", "name", "Test Branch Six"), admin),
                    HttpStatus.LOCKED,
                    "tenant_suspended");
        } finally {
            JdbcCall.run("UPDATE report_runs SET status = 'cancelled', finished_at = now() WHERE id = ?", run);
        }

        ResponseEntity<JsonNode> resumed = op.api()
                .post(
                        base + "/resume",
                        Map.of("reason", "Test: cleared"),
                        op.session().accessToken());
        assertThat(resumed.getStatusCode()).as("%s", resumed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(applyDue(tenantId, today)).isEqualTo(1);
        assertThat(b.api()
                        .get("/api/v1/plan", b.ownerSession().accessToken())
                        .getBody()
                        .get("ended_on")
                        .asString())
                .isEqualTo(today.toString());
    }

    /** The businesses the nightly lending job (arrears and penalties) runs for. */
    static List<UUID> lendingJobTenants() {
        return TestDatabase.owner()
                .sql("SELECT app_list_active_tenants_with_module('lending')")
                .query(UUID.class)
                .list();
    }

    /** Nothing of one business shows to another: its requests, its changes and its undo. */
    @Test
    void oneBusinessNeverSeesAnothersPlanChanges() {
        Business a = business(lendingPlan, false, today.plusDays(10));
        Business b = business(lendingPlan, false, today.plusDays(10));
        a.api()
                .post(
                        "/api/v1/plan/module-requests",
                        Map.of("module", "retail", "password", Api.PASSWORD),
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

    // ---- independent test review of #474: adversarial cases ----------------------------------------
    // JUSTIFICATION-A3: whole new test methods appended to the suite whose fixtures they reuse; no production code.

    /**
     * After the end, writes in areas the other tests do not list are refused too: a stock count and its commit, a loan
     * application, a branch rename, close and logo removal, a stock transfer, a payment request, a cashier's sale, and the
     * Owner's own plan writes. The branch is unchanged.
     */
    @Test
    void afterTheEndWritesInAreasNotListedElsewhereAreRefused() {
        LocalDate paidUntil = today.plusDays(5);
        Business b = business(bothPlan, true, paidUntil);
        UUID tenantId = b.t().tenantId();
        assertThat(withPassword(b.api(), "/api/v1/plan/cancel", b.ownerSession())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(applyDue(tenantId, paidUntil.plusDays(1))).isEqualTo(1);

        String admin = b.adminSession().accessToken();
        String owner = b.ownerSession().accessToken();
        String branch = "/api/v1/branches/" + b.t().headOffice();
        String branchName = b.api().get(branch, admin).getBody().get("name").asString();
        List<ResponseEntity<JsonNode>> writes = List.of(
                b.api().post("/api/v1/retail/stocktakes", Map.of("branch_id", b.t().headOffice()), admin),
                b.api().post("/api/v1/retail/stocktakes/" + UUID.randomUUID() + "/commit", Map.of(), admin),
                b.api().post("/api/v1/lending/loans", Map.of(), admin),
                b.api().call(HttpMethod.PATCH, branch, Map.of("name", "Test Renamed Branch"), admin),
                b.api().post(branch + "/close", Map.of(), admin),
                b.api().call(HttpMethod.DELETE, branch + "/logo", null, admin),
                b.api().post("/api/v1/retail/transfers", Map.of(), admin),
                b.api().post("/api/v1/payments/intents", Map.of(), admin),
                b.api()
                        .post(
                                "/api/v1/retail/sales",
                                Map.of(),
                                b.cashierSession().accessToken()),
                b.api()
                        .post(
                                "/api/v1/plan/module-requests",
                                Map.of("module", "retail", "password", Api.PASSWORD),
                                owner),
                withPassword(b.api(), "/api/v1/plan/modules/retail/stop", b.ownerSession()),
                withPassword(b.api(), "/api/v1/plan/cancel", b.ownerSession()));
        for (ResponseEntity<JsonNode> write : writes) {
            assertRefused(write, HttpStatus.LOCKED, "subscription_ended");
        }
        assertThat(b.api().get(branch, admin).getBody().get("name").asString()).isEqualTo(branchName);
        assertThat(scheduled(tenantId)).isZero();
    }

    /**
     * The operator suspends after the Owner cancelled, and the cancellation's day comes: through HTTP the business reads as
     * suspended, not ended (no end date on the banner, writes and exports refused with {@code tenant_suspended}, the Owner's
     * undo refused the same way), and however often the job runs nothing is applied.
     */
    @Test
    void aSuspensionFollowedByTheCancellationDayStaysSuspendedThroughHttp() {
        LocalDate paidUntil = today.plusDays(5);
        Business b = business(bothPlan, true, paidUntil);
        UUID tenantId = b.t().tenantId();
        ResponseEntity<JsonNode> cancelled = withPassword(b.api(), "/api/v1/plan/cancel", b.ownerSession());
        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID changeId = UUID.fromString(cancelled.getBody().get("id").asString());
        Operator op = operator();
        assertThat(op.api()
                        .post(
                                "/api/v1/platform/tenants/" + tenantId + "/suspend",
                                Map.of("reason", "Test: unpaid invoice"),
                                op.session().accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JdbcCall.run("UPDATE subscription_scheduled_changes SET effective_on = ? WHERE id = ?", today, changeId);

        for (int run = 0; run < 3; run++) {
            assertThat(applyDue(tenantId, today.plusDays(run))).isZero();
        }
        String admin = b.adminSession().accessToken();
        JsonNode status = b.api()
                .get("/api/v1/plan/status", b.cashierSession().accessToken())
                .getBody();
        assertThat(status.get("ended_on").isNull()).as("%s", status).isTrue();
        assertRefused(
                b.api().post("/api/v1/branches", Map.of("code", "BR7", "name", "Test Branch Seven"), admin),
                HttpStatus.LOCKED,
                "tenant_suspended");
        assertRefused(
                b.api()
                        .post(
                                "/api/v1/plan/changes/" + changeId + "/undo",
                                null,
                                b.ownerSession().accessToken()),
                HttpStatus.LOCKED,
                "tenant_suspended");
        assertThat(b.api()
                        .postForText("/api/v1/audit-events/export", Map.of(), admin)
                        .getStatusCode())
                .isEqualTo(HttpStatus.LOCKED);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT status || '/' || coalesce(ended_on::text, '-') FROM subscriptions WHERE tenant_id = ?")
                        .param(tenantId)
                        .query(String.class)
                        .single())
                .isEqualTo("suspended/-");
        assertThat(platformAudits("platform.tenant.subscription_ended", tenantId))
                .isZero();
    }

    /**
     * A business already ended by its Owner that the operator then suspends (fraud found later) is the operator's
     * suspension from then on: the resolver answers suspended, payments no longer post, writes and exports are refused
     * with {@code tenant_suspended}.
     */
    @Test
    void anEndedBusinessTheOperatorThenSuspendsNoLongerTakesPayments() {
        LocalDate paidUntil = today.plusDays(5);
        Business b = business(bothPlan, true, paidUntil);
        UUID tenantId = b.t().tenantId();
        assertThat(withPassword(b.api(), "/api/v1/plan/cancel", b.ownerSession())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(applyDue(tenantId, paidUntil.plusDays(1))).isEqualTo(1);
        assertThat(takesPayments(tenantId)).isTrue();

        Operator op = operator();
        ResponseEntity<JsonNode> suspended = op.api()
                .post(
                        "/api/v1/platform/tenants/" + tenantId + "/suspend",
                        Map.of("reason", "Test: fraud found after the end"),
                        op.session().accessToken());
        assertThat(suspended.getStatusCode()).as("%s", suspended.getBody()).isEqualTo(HttpStatus.OK);

        assertThat(takesPayments(tenantId)).isFalse();
        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM app_resolve_tenant_status(?)")
                        .param(b.t().slug())
                        .query(String.class)
                        .single())
                .isEqualTo("suspended");
        String admin = b.adminSession().accessToken();
        assertRefused(
                b.api().post("/api/v1/branches", Map.of("code", "BR8", "name", "Test Branch Eight"), admin),
                HttpStatus.LOCKED,
                "tenant_suspended");
        assertThat(b.api()
                        .postForText("/api/v1/audit-events/export", Map.of(), admin)
                        .getStatusCode())
                .isEqualTo(HttpStatus.LOCKED);
    }

    static boolean takesPayments(UUID tenantId) {
        return TestDatabase.owner()
                .sql("SELECT app_tenant_takes_payments(?)")
                .param(tenantId)
                .query(Boolean.class)
                .single();
    }

    /**
     * Stop and cancel without the Owner's step-up are refused ({@code totp_required} with two-step sign-in on, and
     * {@code password_required} for a stop without it); an administrator with a valid code, a cashier, an Owner who was
     * replaced and a business with no Owner are refused, undo included; nothing is scheduled by any of them.
     */
    @Test
    void stopAndCancelWithoutTheStepUpOrByANonOwnerAreRefused() {
        Business plain = business(bothPlan, true, today.plusDays(10));
        assertRefused(
                plain.api()
                        .post(
                                "/api/v1/plan/modules/retail/stop",
                                Map.of(),
                                plain.ownerSession().accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "password_required");

        Business b = business(bothPlan, true, today.plusDays(10), true);
        Session owner = b.ownerSession();
        Session admin = b.adminSession();
        assertRefused(
                b.api().post("/api/v1/plan/modules/retail/stop", Map.of(), owner.accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "totp_required");
        assertRefused(
                b.api().post("/api/v1/plan/cancel", Map.of(), owner.accessToken()),
                HttpStatus.UNPROCESSABLE_CONTENT,
                "totp_required");

        Api.allowTotpReuse(admin.userId());
        Map<String, Object> adminCode = Map.of("totp_code", Api.totp(admin.totpSecret(), Instant.now()));
        assertRefused(
                b.api().post("/api/v1/plan/modules/retail/stop", adminCode, admin.accessToken()),
                HttpStatus.FORBIDDEN,
                "owner_only");
        assertRefused(
                b.api().post("/api/v1/plan/cancel", adminCode, admin.accessToken()),
                HttpStatus.FORBIDDEN,
                "owner_only");
        assertRefused(
                withPassword(b.api(), "/api/v1/plan/modules/retail/stop", b.cashierSession()),
                HttpStatus.FORBIDDEN,
                "permission_denied");
        assertThat(scheduled(b.t().tenantId())).isZero();

        Api.allowTotpReuse(b.owner());
        ResponseEntity<JsonNode> cancelled = b.api()
                .post(
                        "/api/v1/plan/cancel",
                        Map.of("totp_code", Api.totp(owner.totpSecret(), Instant.now())),
                        owner.accessToken());
        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.CREATED);
        String undo = "/api/v1/plan/changes/" + cancelled.getBody().get("id").asString() + "/undo";
        assertRefused(b.api().post(undo, null, admin.accessToken()), HttpStatus.FORBIDDEN, "owner_only");
        assertRefused(
                b.api().post(undo, null, b.cashierSession().accessToken()), HttpStatus.FORBIDDEN, "permission_denied");

        // The Owner role moves to the other administrator: the old Owner's session may no longer undo or stop.
        JdbcCall.run("UPDATE users SET is_owner = false WHERE id = ?", b.owner());
        JdbcCall.run("UPDATE users SET is_owner = true WHERE id = ?", admin.userId());
        assertRefused(b.api().post(undo, null, owner.accessToken()), HttpStatus.FORBIDDEN, "owner_only");
        Api.allowTotpReuse(b.owner());
        assertRefused(
                b.api()
                        .post(
                                "/api/v1/plan/modules/retail/stop",
                                Map.of("totp_code", Api.totp(owner.totpSecret(), Instant.now())),
                                owner.accessToken()),
                HttpStatus.FORBIDDEN,
                "owner_only");

        // No Owner at all: nobody changes the plan.
        JdbcCall.run("UPDATE users SET is_owner = false WHERE id = ?", admin.userId());
        assertRefused(b.api().post(undo, null, admin.accessToken()), HttpStatus.FORBIDDEN, "owner_only");
        assertThat(scheduled(b.t().tenantId())).isEqualTo(1);
        assertThat(modules(b.t().tenantId())).containsExactly("lending", "retail");
    }

    static long scheduled(UUID tenantId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM subscription_scheduled_changes WHERE tenant_id = ? AND status = 'scheduled'")
                .param(tenantId)
                .query(Long.class)
                .single();
    }
}
