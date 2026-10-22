package com.rincoltech.bms.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The sign-in limits of ADR-059 through HTTP (launch readiness M9, issue #426, the review of #433; FR-IAM-05, FR-IAM-15): an
 * address of nobody is counted and locked in the database exactly like an account, only failures count against a client
 * IP, a reset or an audited unlock lifts a lock, and no request waiting for a hash holds a database connection. All users
 * and contacts are fabricated.
 */
class SignInLimitsIT extends IntegrationTest {

    static final String STAFF_LOGIN = "/api/v1/auth/staff/login";
    static final String PLATFORM_LOGIN = "/api/v1/platform/auth/login";
    static final String FORGOT = "/api/v1/auth/staff/password/forgot";
    static final AtomicInteger ADDRESS = new AtomicInteger();

    @Autowired
    TestRestTemplate http;

    @Autowired
    AuthThrottle throttle;

    @Autowired
    Passwords passwords;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    TestDatabase.Fixture t;
    Api api;

    @BeforeEach
    void setUp() {
        // The limits answer the real refill time as Retry-After; the shared client would sleep through it and retry.
        http = Api.withoutRetries(http);
        t = TestDatabase.tenant("limits", true);
        api = Api.tenant(http, t.slug());
    }

    static String freshIp() {
        return "192.0.2." + (ADDRESS.incrementAndGet() % 250 + 1);
    }

    ResponseEntity<JsonNode> login(Api on, String path, String login, String password, String ip) {
        return on.call(
                HttpMethod.POST,
                path,
                Map.of("login", login, "password", password),
                null,
                Map.of("X-Forwarded-For", ip));
    }

    /** What a prober compares: the status, the code and the words. */
    static String answer(ResponseEntity<JsonNode> response) {
        return response.getStatusCode().value() + " "
                + response.getBody().get("code").asString() + " "
                + response.getBody().get("detail").asString();
    }

    List<String> wrongPasswords(String login, int times) {
        List<String> answers = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            answers.add(answer(login(api, STAFF_LOGIN, login, "Wrong-Password-" + i, freshIp())));
        }
        return answers;
    }

    String addressKey(String email) {
        return SignInAddressFailures.key("staff:" + t.tenantId() + ":" + email.toLowerCase(Locale.ROOT));
    }

    long lockAudits(UUID entity) {
        String sql = "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.auth.account_locked' AND ";
        return entity == null
                ? TestDatabase.owner()
                        .sql(sql + "entity_id IS NULL")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single()
                : TestDatabase.owner()
                        .sql(sql + "entity_id = ?")
                        .params(t.tenantId(), entity)
                        .query(Long.class)
                        .single();
    }

    /**
     * FR-IAM-05 without enumeration (finding 1): a real account and an address that matches nobody answer alike, lock
     * included, and the lock of the address of nobody is a database row, so a restart or a flood of other addresses cannot
     * lift it.
     */
    @Test
    void anUnknownAddressIsAnsweredAndLockedExactlyLikeARealAccount() {
        String real = Api.email("cashier");
        UUID cashier = Api.staff(t, real, new Role("cashier", t.headOffice()));
        String nobody = Api.email("nobody");

        List<String> forReal = wrongPasswords(real, 6);
        List<String> forNobody = wrongPasswords(nobody, 6);

        assertThat(forNobody).isEqualTo(forReal);
        assertThat(forReal.subList(0, 5)).allMatch(a -> a.startsWith("401 invalid_credentials "));
        assertThat(forReal.get(5)).startsWith("423 account_locked ");
        // The right password changes nothing while the lock holds, and an unknown address stays locked the same way.
        assertThat(answer(login(api, STAFF_LOGIN, real, Api.PASSWORD, freshIp())))
                .isEqualTo(answer(login(api, STAFF_LOGIN, nobody, Api.PASSWORD, freshIp())));
        // Both locks are rows of the database, written the same way, and each was audited once.
        Map<String, Object> row =
                TestDatabase.owner().sql("""
                        SELECT failed_login_count, locked_until > now() AS locked FROM sign_in_address_failures
                         WHERE address_hash = ?
                        """).param(addressKey(nobody)).query().singleRow();
        assertThat((Boolean) row.get("locked")).isTrue();
        assertThat(((Number) row.get("failed_login_count")).intValue()).isEqualTo(AuthFlow.MAX_FAILURES);
        assertThat(TestDatabase.owner()
                        .sql("SELECT failed_login_count FROM users WHERE id = ?")
                        .param(cashier)
                        .query(Integer.class)
                        .single())
                .isEqualTo(AuthFlow.MAX_FAILURES);
        assertThat(lockAudits(cashier)).isEqualTo(1);
        assertThat(lockAudits(null)).isEqualTo(1);
        // Nothing of the address itself is stored.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM sign_in_address_failures WHERE address_hash LIKE '%@%'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** The lock lapses after 15 minutes for an address of nobody as for an account, and the count starts again. */
    @Test
    void anUnknownAddressLockLapsesAndCountsAfresh() {
        String nobody = Api.email("nobody");
        wrongPasswords(nobody, AuthFlow.MAX_FAILURES);
        TestDatabase.owner()
                .sql(
                        "UPDATE sign_in_address_failures SET locked_until = now() - interval '1 second' WHERE address_hash = ?")
                .param(addressKey(nobody))
                .update();

        assertThat(wrongPasswords(nobody, 1).getFirst()).startsWith("401 invalid_credentials ");
        assertThat(TestDatabase.owner()
                        .sql("SELECT failed_login_count FROM sign_in_address_failures WHERE address_hash = ?")
                        .param(addressKey(nobody))
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    /**
     * Carrier-grade NAT (finding 8): only failures count against a client IP, so the many people a mobile carrier puts
     * behind one public address sign in without using it up; a run of failures from that IP is refused with the refill
     * time (finding 7).
     */
    @Test
    void onlyFailuresCountAgainstAClientIp() {
        String ip = "198.18.1." + (ADDRESS.incrementAndGet() % 250 + 1);
        String email = Api.email("teller");
        Api.staff(t, email, new Role("cashier", t.headOffice()));
        AuthThrottle.Limit suite = throttle.signInPerIp;
        // Three failures a day, under a name of its own so this IP starts with a full bucket that does not refill
        // mid-test.
        throttle.signInPerIp = new AuthThrottle.Limit("sign-in-ip-" + UUID.randomUUID(), 3, Duration.ofDays(1));
        try {
            for (int i = 0; i < 10; i++) {
                assertThat(login(api, STAFF_LOGIN, email, Api.PASSWORD, ip).getStatusCode())
                        .isEqualTo(HttpStatus.OK);
            }
            for (int i = 0; i < 3; i++) {
                assertThat(login(api, STAFF_LOGIN, Api.email("someone"), "Wrong-Password-1", ip)
                                .getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED);
            }
            ResponseEntity<JsonNode> refused = login(api, STAFF_LOGIN, email, Api.PASSWORD, ip);
            assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(refused.getBody().get("code").asString()).isEqualTo("rate_limited");
            // One token of three a day comes back in eight hours.
            assertThat(Long.parseLong(refused.getHeaders().getFirst("Retry-After")))
                    .isBetween(28_700L, 28_800L);
            refusedFrom(ip);
        } finally {
            throttle.signInPerIp = suite;
        }
        // Another client IP still signs in.
        assertThat(login(api, STAFF_LOGIN, email, Api.PASSWORD, freshIp()).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** The per-IP bucket covers the staff and the operator sign-in, and the two-step and invitation routes with them. */
    void refusedFrom(String ip) {
        ResponseEntity<JsonNode> operator =
                login(Api.platform(http), PLATFORM_LOGIN, Api.email("operator"), "Wrong-Password-1", ip);
        assertThat(operator.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        Map<String, Map<String, String>> bodies = Map.of(
                "/api/v1/auth/staff/mfa/verify", Map.of("mfa_token", "x", "code", "000000"),
                "/api/v1/auth/staff/mfa/confirm", Map.of("mfa_token", "x", "code", "000000"),
                "/api/v1/auth/staff/invitations/accept", Map.of("token", "x", "password", "Fabricated-Pass-2026"),
                "/api/v1/platform/auth/mfa/verify", Map.of("mfa_token", "x", "code", "000000"),
                "/api/v1/platform/auth/setup", Map.of("token", "x", "password", "Fabricated-Pass-2026"));
        bodies.forEach((path, body) -> {
            Api on = path.startsWith("/api/v1/platform") ? Api.platform(http) : api;
            ResponseEntity<JsonNode> refused =
                    on.call(HttpMethod.POST, path, body, null, Map.of("X-Forwarded-For", ip));
            assertThat(refused.getStatusCode()).as(path).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(refused.getHeaders().getFirst("Retry-After")).as(path).isNotBlank();
        });
    }

    /** A lock refusal costs the IP nothing: an owner retrying a locked account does not use up the office's budget. */
    @Test
    void aLockedRefusalDoesNotCountAgainstTheIp() {
        String ip = "198.18.2." + (ADDRESS.incrementAndGet() % 250 + 1);
        String nobody = Api.email("nobody");
        wrongPasswords(nobody, AuthFlow.MAX_FAILURES);
        AuthThrottle.Limit suite = throttle.signInPerIp;
        throttle.signInPerIp = new AuthThrottle.Limit("sign-in-ip-" + UUID.randomUUID(), 2, Duration.ofDays(1));
        try {
            for (int i = 0; i < 5; i++) {
                assertThat(login(api, STAFF_LOGIN, nobody, Api.PASSWORD, ip).getStatusCode())
                        .isEqualTo(HttpStatus.LOCKED);
            }
            assertThat(throttle.allows(throttle.signInPerIp, ip)).isTrue();
        } finally {
            throttle.signInPerIp = suite;
        }
    }

    /** FR-IAM-15: reset links for one address are bounded whoever asks, with the same 429 for an address of nobody. */
    @Test
    void resetLinksForOneAddressAreLimitedWhetherOrNotItIsAnAccount() {
        String real = Api.email("clerk");
        Api.staff(t, real, new Role("cashier", t.headOffice()));
        String nobody = Api.email("nobody");
        int capacity = AuthThrottle.REQUEST_PER_ADDRESS.capacity();
        List<String> answers = new ArrayList<>();
        for (String login : List.of(real, nobody)) {
            for (int i = 0; i < capacity; i++) {
                assertThat(forgot(login).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            }
            answers.add(answer(forgot(login)));
        }
        assertThat(answers.get(0)).isEqualTo(answers.get(1)).startsWith("429 rate_limited ");
    }

    ResponseEntity<JsonNode> forgot(String login) {
        return api.call(HttpMethod.POST, FORGOT, Map.of("login", login), null, Map.of("X-Forwarded-For", freshIp()));
    }

    void lockOut(String email) {
        wrongPasswords(email, AuthFlow.MAX_FAILURES);
        assertThat(login(api, STAFF_LOGIN, email, Api.PASSWORD, freshIp()).getStatusCode())
                .isEqualTo(HttpStatus.LOCKED);
    }

    long unlockAudits(UUID userId, String by, String actorKind) {
        return TestDatabase.owner()
                .sql("""
                        SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.auth.unlocked' AND entity_id = ?
                           AND data -> 'after' ->> 'by' = ? AND actor_kind = ?
                        """)
                .params(t.tenantId(), userId, by, actorKind)
                .query(Long.class)
                .single();
    }

    /** Finding 2: a tenant admin lifts the lock of a staff user of the tenant, audited; a user without the right cannot. */
    @Test
    void aTenantAdminUnlocksAStaffUser() {
        String adminEmail = Api.email("admin");
        UUID admin = Api.staff(t, adminEmail, new Role("tenant_admin", null));
        Session adminSession = api.signIn(admin, adminEmail, Api.PASSWORD, null);
        String cashierEmail = Api.email("cashier");
        UUID cashier = Api.staff(t, cashierEmail, new Role("cashier", t.headOffice()));
        Session cashierSession = api.signIn(cashier, cashierEmail, Api.PASSWORD, null);
        String otherEmail = Api.email("teller");
        UUID other = Api.staff(t, otherEmail, new Role("cashier", t.headOffice()));
        lockOut(otherEmail);

        Map<String, String> why = Map.of("reason", "Test: locked out by a stranger");
        assertThat(api.post("/api/v1/users/" + other + "/unlock", why, cashierSession.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> unlocked =
                api.post("/api/v1/users/" + other + "/unlock", why, adminSession.accessToken());
        assertThat(unlocked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unlocked.getBody().get("id").asString()).isEqualTo(other.toString());
        assertThat(unlockAudits(other, "tenant_admin", "staff")).isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT data -> 'after' ->> 'reason' FROM audit_log WHERE tenant_id = ?"
                                + " AND action = 'core.auth.unlocked' AND entity_id = ?")
                        .params(t.tenantId(), other)
                        .query(String.class)
                        .single())
                .isEqualTo("Test: locked out by a stranger");
        assertThat(login(api, STAFF_LOGIN, otherEmail, Api.PASSWORD, freshIp()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // A platform operator is not a staff user of any tenant: there is nothing to unlock.
        UUID operator = Api.platformUser(Api.email("operator"));
        // A reason is required.
        assertThat(api.post("/api/v1/users/" + other + "/unlock", Map.of("reason", ""), adminSession.accessToken())
                        .getStatusCode()
                        .is4xxClientError())
                .isTrue();
        assertThat(api.post("/api/v1/users/" + operator + "/unlock", why, adminSession.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Finding 2: a platform operator lifts the lock of a tenant's staff user with a reason, audited in both logs. */
    @Test
    void aPlatformOperatorUnlocksATenantUserButNotAnOperator() {
        Api platform = Api.platform(http);
        String operatorEmail = Api.email("operator");
        UUID operatorId = Api.platformUser(operatorEmail);
        Session operator = platform.signIn(operatorId, operatorEmail, Api.PASSWORD, null);
        String ownerEmail = Api.email("owner");
        UUID owner = Api.staff(t, ownerEmail, new Role("tenant_admin", null));
        lockOut(ownerEmail);
        String base = "/api/v1/platform/tenants/" + t.tenantId() + "/users/";
        Map<String, String> reason = Map.of("reason", "Test: the owner is locked out");

        assertThat(platform.post(base + owner + "/unlock", reason, operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(unlockAudits(owner, "platform", "platform")).isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("""
                                SELECT count(*) FROM platform_audit_log
                                 WHERE action = 'platform.tenant_user.sign_in_unlocked' AND tenant_id = ?
                                   AND data ->> 'reason' = 'Test: the owner is locked out'
                                """)
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT locked_until IS NULL AND failed_login_count = 0 FROM users WHERE id = ?")
                        .param(owner)
                        .query(Boolean.class)
                        .single())
                .isTrue();

        // Another operator, or this one, is not a user of the tenant: refused, nothing changed.
        assertThat(platform.post(base + operatorId + "/unlock", reason, operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // A staff user of another tenant is not found through this tenant.
        TestDatabase.Fixture other = TestDatabase.tenant("limits-other", true);
        UUID stranger = Api.staff(other, Api.email("stranger"), new Role("cashier", other.headOffice()));
        assertThat(platform.post(base + stranger + "/unlock", reason, operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Delta review of #433: guesses that all passed the first look before the lock was set are settled under the row lock,
     * and once it is set every one answers 423, the right password among them, so a burst cannot tell the right one from a
     * 423 among 401s. Every slot is held so all of them queue after the first look; the lock is then set, as parallel
     * failures would set it, and the slots are given back.
     */
    @Test
    void aBurstSettledAfterTheLockAnswersAlikeRightPasswordIncluded() throws Exception {
        String real = Api.email("cashier");
        UUID cashier = Api.staff(t, real, new Role("cashier", t.headOffice()));
        String nobody = Api.email("nobody");
        wrongPasswords(nobody, 1);
        List<String> passwordsSent = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            passwordsSent.add("Wrong-Password-" + i);
        }
        passwordsSent.add(Api.PASSWORD);

        int slots = passwords.hashing.drainPermits();
        // Long enough that no queued request gives up (503) on a slow runner before the slots come back.
        passwords.waitMillis = 60_000;
        ExecutorService callers = Executors.newFixedThreadPool(2 * passwordsSent.size());
        try {
            List<Future<ResponseEntity<JsonNode>>> forReal = new ArrayList<>();
            List<Future<ResponseEntity<JsonNode>>> forNobody = new ArrayList<>();
            for (String password : passwordsSent) {
                forReal.add(callers.submit(() -> login(api, STAFF_LOGIN, real, password, freshIp())));
                forNobody.add(callers.submit(() -> login(api, STAFF_LOGIN, nobody, password, freshIp())));
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (passwords.hashing.getQueueLength() < 2 * passwordsSent.size() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(passwords.hashing.getQueueLength()).isEqualTo(2 * passwordsSent.size());
            TestDatabase.owner()
                    .sql(
                            "UPDATE users SET failed_login_count = 5, locked_until = now() + interval '15 minutes' WHERE id = ?")
                    .param(cashier)
                    .update();
            TestDatabase.owner()
                    .sql("UPDATE sign_in_address_failures SET failed_login_count = 5,"
                            + " locked_until = now() + interval '15 minutes' WHERE address_hash = ?")
                    .param(addressKey(nobody))
                    .update();
            passwords.hashing.release(slots);
            slots = 0;

            List<String> realAnswers = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> call : forReal) {
                realAnswers.add(answer(call.get()));
            }
            List<String> nobodyAnswers = new ArrayList<>();
            for (Future<ResponseEntity<JsonNode>> call : forNobody) {
                nobodyAnswers.add(answer(call.get()));
            }
            // The right password (the last one sent) answers exactly as every wrong one, and as an address of nobody.
            assertThat(realAnswers).allMatch(a -> a.startsWith("423 account_locked "));
            assertThat(realAnswers).containsOnly(realAnswers.getFirst());
            assertThat(nobodyAnswers).containsOnly(realAnswers.getFirst());
            // Nothing was counted while locked, and no session was made.
            assertThat(TestDatabase.owner()
                            .sql("SELECT failed_login_count FROM users WHERE id = ?")
                            .param(cashier)
                            .query(Integer.class)
                            .single())
                    .isEqualTo(5);
            assertThat(TestDatabase.owner()
                            .sql("SELECT count(*) FROM auth_sessions WHERE user_id = ?")
                            .param(cashier)
                            .query(Long.class)
                            .single())
                    .isZero();
        } finally {
            if (slots > 0) {
                passwords.hashing.release(slots);
            }
            passwords.waitMillis = Passwords.WAIT_SECONDS * 1000;
            callers.shutdownNow();
        }
    }

    /**
     * The same burst sent with the slots free, so it races the lock for real: whatever the order, once a 423 is answered
     * no later guess answers 401, so if the right password is refused as locked exactly five wrong ones got 401.
     */
    @Test
    void aConcurrentBurstWithTheRightPasswordNeverSingsOutTheRightOne() throws Exception {
        String real = Api.email("cashier");
        Api.staff(t, real, new Role("cashier", t.headOffice()));
        List<String> passwordsSent = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            passwordsSent.add("Wrong-Password-" + i);
        }
        passwordsSent.add(Api.PASSWORD);
        ExecutorService callers = Executors.newFixedThreadPool(passwordsSent.size());
        try {
            List<Future<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
            for (String password : passwordsSent) {
                calls.add(callers.submit(() -> login(api, STAFF_LOGIN, real, password, freshIp())));
            }
            List<Integer> wrong = new ArrayList<>();
            int right = 0;
            for (int i = 0; i < calls.size(); i++) {
                int status = calls.get(i).get().getStatusCode().value();
                if (i == calls.size() - 1) {
                    right = status;
                } else {
                    wrong.add(status);
                }
            }
            // A smoke test under load: 503 (every hashing slot busy) is a legitimate answer; the forced-lock test above
            // is
            // the one that proves the property. A wrong password never signs in.
            assertThat(wrong).allMatch(s -> s == 401 || s == 423 || s == 503);
            assertThat(right).isIn(200, 423, 503);
            if (right == 423) {
                assertThat(wrong.stream().filter(s -> s == 401).count()).isEqualTo(AuthFlow.MAX_FAILURES);
            }
        } finally {
            callers.shutdownNow();
        }
    }

    /**
     * Finding 3: a sign-in waiting for a hashing slot holds no database connection. Every slot is taken, more sign-ins than
     * the pool has connections queue for one, and the pool still serves another request at once; the waiting sign-ins are
     * then told the service is busy, with a Retry-After of two to five seconds.
     */
    @Test
    void aSignInWaitingForAHashHoldsNoDatabaseConnection() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        // One more than the pool has connections: had each held one while it waited, the last could not even get here.
        int waiting = pool.getMaximumPoolSize() + 1;
        int slots = passwords.hashing.drainPermits();
        // Long enough that every request is still queued when the pool is checked, even on a slow runner; they are then
        // turned away as busy when it ends.
        passwords.waitMillis = 20_000;
        ExecutorService callers = Executors.newFixedThreadPool(waiting);
        try {
            List<Future<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
            for (int i = 0; i < waiting; i++) {
                calls.add(callers.submit(
                        () -> login(api, STAFF_LOGIN, Api.email("nobody"), "Wrong-Password-1", freshIp())));
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (passwords.hashing.getQueueLength() < waiting && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(passwords.hashing.getQueueLength())
                    .as("every sign-in is waiting for a hashing slot")
                    .isEqualTo(waiting);
            // None of them holds a connection, so the pool answers another request at once.
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isLessThan(pool.getMaximumPoolSize());
            long started = System.nanoTime();
            Integer one = new TransactionTemplate(transactionManager)
                    .execute(status -> jdbc.sql("SELECT 1").query(Integer.class).single());
            assertThat(one).isEqualTo(1);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));

            for (Future<ResponseEntity<JsonNode>> call : calls) {
                ResponseEntity<JsonNode> busy = call.get();
                assertThat(busy.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(busy.getBody().get("code").asString()).isEqualTo("sign_in_busy");
                assertThat(Long.parseLong(busy.getHeaders().getFirst("Retry-After")))
                        .isBetween(2L, 5L);
            }
        } finally {
            passwords.hashing.release(slots);
            passwords.waitMillis = Passwords.WAIT_SECONDS * 1000;
            callers.shutdownNow();
        }
    }
}
