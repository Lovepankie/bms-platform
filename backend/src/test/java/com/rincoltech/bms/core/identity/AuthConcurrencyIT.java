package com.rincoltech.bms.core.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import com.rincoltech.bms.testsupport.Api.Session;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The second factor and the failure lockout hold under concurrent requests (FR-IAM-05, FR-IAM-06,
 * FR-IAM-11; chapter 8 section 8.2.4): N requests released together through the real HTTP API
 * against PostgreSQL. All users fabricated.
 */
class AuthConcurrencyIT extends IntegrationTest {

    static final int PARALLEL = 8;
    static final int WRONG_PASSWORDS = 20;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    Api api;
    UUID admin;
    String adminEmail;
    Session adminSession;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("race", true);
        api = Api.tenant(http, t.slug());
        adminEmail = Api.email("admin");
        admin = Api.staff(t, adminEmail, new Role("tenant_admin", null));
        adminSession = api.signIn(admin, adminEmail, Api.PASSWORD, null);
    }

    String mfaToken() {
        return api.login(adminEmail, Api.PASSWORD).getBody().get("mfa_token").asString();
    }

    long sessions(UUID userId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM auth_sessions WHERE user_id = ?")
                .param(userId)
                .query(Long.class)
                .single();
    }

    /** FR-IAM-06: one TOTP code sent N times at once opens exactly one session. */
    @Test
    void oneTotpCodeSignsInOnceUnderConcurrentVerifies() throws Exception {
        String mfaToken = mfaToken();
        Api.allowTotpReuse(admin);
        long before = sessions(admin);
        String code = Api.totp(adminSession.totpSecret(), Instant.now());

        List<HttpStatusCode> statuses = Api.race(
                PARALLEL,
                () -> api.post("/api/v1/auth/staff/mfa/verify", Map.of("mfa_token", mfaToken, "code", code), null));

        assertThat(Api.count(statuses, HttpStatus.OK))
                .as("statuses %s", statuses)
                .isEqualTo(1);
        assertThat(sessions(admin) - before).isEqualTo(1);
    }

    /** FR-IAM-11: one recovery code sent N times at once opens exactly one session. */
    @Test
    void oneRecoveryCodeSignsInOnceUnderConcurrentVerifies() throws Exception {
        String mfaToken = mfaToken();
        String code = adminSession.recoveryCodes().getFirst();
        long before = sessions(admin);

        List<HttpStatusCode> statuses = Api.race(
                PARALLEL,
                () -> api.post("/api/v1/auth/staff/mfa/verify", Map.of("mfa_token", mfaToken, "code", code), null));

        assertThat(Api.count(statuses, HttpStatus.OK))
                .as("statuses %s", statuses)
                .isEqualTo(1);
        assertThat(sessions(admin) - before).isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM user_recovery_codes WHERE user_id = ? AND used_at IS NULL")
                        .param(admin)
                        .query(Long.class)
                        .single())
                .isEqualTo(9);
    }

    /** FR-IAM-11: one TOTP code regenerates the recovery codes once, not N times. */
    @Test
    void oneTotpCodeRegeneratesRecoveryCodesOnce() throws Exception {
        Api.allowTotpReuse(admin);
        String code = Api.totp(adminSession.totpSecret(), Instant.now());

        List<HttpStatusCode> statuses = Api.race(
                PARALLEL,
                () -> api.post(
                        "/api/v1/auth/staff/mfa/recovery-codes", Map.of("code", code), adminSession.accessToken()));

        assertThat(Api.count(statuses, HttpStatus.OK))
                .as("statuses %s", statuses)
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM user_recovery_codes WHERE user_id = ?")
                        .param(admin)
                        .query(Long.class)
                        .single())
                .isEqualTo(10);
    }

    /**
     * FR-IAM-05: N wrong passwords at once are all counted or refused as locked, the account ends
     * locked with one lock audit, and the right password is then refused.
     */
    @Test
    void parallelWrongPasswordsCannotBypassTheLockout() throws Exception {
        String email = Api.email("teller");
        UUID cashier = Api.staff(t, email, new Role("cashier", t.headOffice()));

        List<HttpStatusCode> statuses =
                Api.race(WRONG_PASSWORDS, () -> api.login(email, "Wrong-Password-" + UUID.randomUUID()));

        long refusedLocked = Api.count(statuses, HttpStatus.LOCKED);
        assertThat(Api.count(statuses, HttpStatus.UNAUTHORIZED) + refusedLocked)
                .as("statuses %s", statuses)
                .isEqualTo(WRONG_PASSWORDS);
        Map<String, Object> row = TestDatabase.owner()
                .sql("SELECT failed_login_count, locked_until > now() AS locked FROM users WHERE id = ?")
                .param(cashier)
                .query()
                .singleRow();
        assertThat((Boolean) row.get("locked")).isTrue();
        assertThat(((Number) row.get("failed_login_count")).longValue() + refusedLocked)
                .isEqualTo(WRONG_PASSWORDS);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ? AND entity_id = ?")
                        .params(t.tenantId(), "core.auth.account_locked", cashier)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        ResponseEntity<JsonNode> right = api.login(email, Api.PASSWORD);
        assertThat(right.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(right.getBody().get("code").asString()).isEqualTo("account_locked");
    }

    /** FR-IAM-05 on the platform console: the same lockout for operators. */
    @Test
    void parallelWrongPasswordsLockAPlatformOperator() throws Exception {
        Api platform = Api.platform(http);
        String email = Api.email("operator");
        UUID operator = Api.platformUser(email);

        List<HttpStatusCode> statuses =
                Api.race(WRONG_PASSWORDS, () -> platform.login(email, "Wrong-Password-" + UUID.randomUUID()));

        long refusedLocked = Api.count(statuses, HttpStatus.LOCKED);
        Map<String, Object> row = TestDatabase.owner()
                .sql("SELECT failed_login_count, locked_until > now() AS locked FROM platform_users WHERE id = ?")
                .param(operator)
                .query()
                .singleRow();
        assertThat((Boolean) row.get("locked")).as("statuses %s", statuses).isTrue();
        assertThat(((Number) row.get("failed_login_count")).longValue() + refusedLocked)
                .isEqualTo(WRONG_PASSWORDS);
        assertThat(platform.login(email, Api.PASSWORD).getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }

    /** FR-IAM-06 on the platform console: one TOTP code, one session. */
    @Test
    void oneTotpCodeSignsAPlatformOperatorInOnce() throws Exception {
        Api platform = Api.platform(http);
        String email = Api.email("operator");
        UUID operator = Api.platformUser(email);
        Session first = platform.signIn(operator, email, Api.PASSWORD, null);
        String mfaToken =
                platform.login(email, Api.PASSWORD).getBody().get("mfa_token").asString();
        Api.allowTotpReuse(operator);
        String code = Api.totp(first.totpSecret(), Instant.now());

        List<HttpStatusCode> statuses = Api.race(
                PARALLEL,
                () -> platform.post(
                        "/api/v1/platform/auth/mfa/verify", Map.of("mfa_token", mfaToken, "code", code), null));

        assertThat(Api.count(statuses, HttpStatus.OK))
                .as("statuses %s", statuses)
                .isEqualTo(1);
    }

    /** FR-IAM-07: one refresh token presented N times at once rotates once; the rest are reuse. */
    @Test
    void oneRefreshTokenRotatesOnceUnderConcurrentRefreshes() throws Exception {
        String refreshToken = adminSession.refreshToken();

        List<HttpStatusCode> statuses = Api.race(PARALLEL, () -> api.refresh(refreshToken));

        assertThat(Api.count(statuses, HttpStatus.OK))
                .as("statuses %s", statuses)
                .isEqualTo(1);
        assertThat(Api.count(statuses, HttpStatus.UNAUTHORIZED)).isEqualTo(PARALLEL - 1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM auth_sessions WHERE user_id = ? AND revoked_at IS NULL")
                        .param(admin)
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
