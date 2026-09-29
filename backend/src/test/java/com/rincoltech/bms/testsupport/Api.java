package com.rincoltech.bms.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base32;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import tools.jackson.databind.JsonNode;

/**
 * HTTP helper for the identity, approval and platform integration tests: calls the API as a
 * tenant (X-Tenant, test profile) or on the platform host, signs users in through the real
 * endpoints, and computes TOTP codes with its own RFC 6238 implementation, independent of the
 * code under test. All data fabricated.
 */
public final class Api {

    public static final String PASSWORD = "Fabricated-Pass-2026";
    private static final Argon2PasswordEncoder ENCODER = new Argon2PasswordEncoder(16, 32, 1, 4096, 2);
    private static String cachedHash;

    private final TestRestTemplate http;
    private final String tenantSlug;
    private final String host;

    private Api(TestRestTemplate http, String tenantSlug, String host) {
        this.http = http;
        this.tenantSlug = tenantSlug;
        this.host = host;
    }

    /** Calls a tenant's API, resolving the tenant from X-Tenant as the test profile allows. */
    public static Api tenant(TestRestTemplate http, String slug) {
        return new Api(http, slug, null);
    }

    /** Calls the platform API; the request host names no tenant. */
    public static Api platform(TestRestTemplate http) {
        return new Api(http, null, null);
    }

    /** Calls with an explicit Host header, for the host rules of chapter 7 section 7.2. */
    public Api onHost(String host) {
        return new Api(http, tenantSlug, host);
    }

    public ResponseEntity<JsonNode> call(HttpMethod method, String path, Object body, String token) {
        return call(method, path, body, token, Map.of());
    }

    public ResponseEntity<JsonNode> call(
            HttpMethod method, String path, Object body, String token, Map<String, String> extra) {
        HttpHeaders h = new HttpHeaders();
        if (tenantSlug != null) {
            h.add("X-Tenant", tenantSlug);
        }
        if (host != null) {
            h.add(HttpHeaders.HOST, host);
        }
        if (token != null) {
            h.setBearerAuth(token);
        }
        extra.forEach(h::add);
        return http.exchange(path, method, new HttpEntity<>(body, h), JsonNode.class);
    }

    public ResponseEntity<JsonNode> get(String path, String token) {
        return call(HttpMethod.GET, path, null, token);
    }

    public ResponseEntity<JsonNode> post(String path, Object body, String token) {
        return call(HttpMethod.POST, path, body, token);
    }

    public ResponseEntity<String> postForText(String path, Object body, String token) {
        HttpHeaders h = new HttpHeaders();
        if (tenantSlug != null) {
            h.add("X-Tenant", tenantSlug);
        }
        h.setBearerAuth(token);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, h), String.class);
    }

    // ---- Sign-in --------------------------------------------------------------------------

    /** A signed-in session: tokens, and the TOTP secret and recovery codes when enrolled here. */
    public record Session(
            UUID userId, String accessToken, String refreshToken, String totpSecret, List<String> recoveryCodes) {}

    private String prefix() {
        return tenantSlug == null ? "/api/v1/platform/auth" : "/api/v1/auth/staff";
    }

    private String cookieName() {
        return tenantSlug == null ? "bms_prt" : "bms_rt";
    }

    public ResponseEntity<JsonNode> login(String login, String password) {
        return post(prefix() + "/login", Map.of("login", login, "password", password), null);
    }

    /**
     * Signs in with the password and, when the account needs one, the second factor: enrols TOTP
     * when enrolment is forced, or answers with a fresh code from {@code knownSecret}.
     */
    public Session signIn(UUID userId, String login, String password, String knownSecret) {
        ResponseEntity<JsonNode> first = login(login, password);
        assertThat(first.getStatusCode())
                .as("login of %s: %s", login, first.getBody())
                .isEqualTo(HttpStatus.OK);
        String status = first.getBody().get("status").asString();
        if (status.equals("signed_in")) {
            return session(userId, first, null, List.of());
        }
        String mfaToken = first.getBody().get("mfa_token").asString();
        if (status.equals("mfa_enrolment_required")) {
            JsonNode enrolment = post(prefix() + "/mfa/enrol", Map.of("mfa_token", mfaToken), null)
                    .getBody();
            String secret = enrolment.get("secret").asString();
            ResponseEntity<JsonNode> confirmed = post(
                    prefix() + "/mfa/confirm",
                    Map.of("mfa_token", mfaToken, "code", totp(secret, Instant.now())),
                    null);
            assertThat(confirmed.getStatusCode())
                    .as("confirm: %s", confirmed.getBody())
                    .isEqualTo(HttpStatus.OK);
            List<String> codes = new ArrayList<>();
            confirmed.getBody().get("recovery_codes").forEach(c -> codes.add(c.asString()));
            return session(userId, confirmed, secret, codes);
        }
        allowTotpReuse(userId);
        ResponseEntity<JsonNode> verified = post(
                prefix() + "/mfa/verify",
                Map.of("mfa_token", mfaToken, "code", totp(knownSecret, Instant.now())),
                null);
        assertThat(verified.getStatusCode())
                .as("verify: %s", verified.getBody())
                .isEqualTo(HttpStatus.OK);
        return session(userId, verified, knownSecret, List.of());
    }

    public ResponseEntity<JsonNode> refresh(String refreshToken) {
        return call(
                HttpMethod.POST,
                tenantSlug == null ? "/api/v1/platform/auth/refresh" : "/api/v1/auth/refresh",
                null,
                null,
                Map.of(HttpHeaders.COOKIE, cookieName() + "=" + refreshToken));
    }

    public Session session(UUID userId, ResponseEntity<JsonNode> response, String secret, List<String> codes) {
        return new Session(
                userId, response.getBody().get("access_token").asString(), refreshCookie(response), secret, codes);
    }

    public String refreshCookie(ResponseEntity<?> response) {
        List<String> cookies = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertThat(cookies).as("Set-Cookie").isNotNull();
        String cookie = cookies.stream()
                .filter(c -> c.startsWith(cookieName() + "="))
                .findFirst()
                .orElseThrow();
        assertThat(cookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict");
        return cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
    }

    // ---- Concurrency ---------------------------------------------------------------------

    /** Releases {@code n} calls at once and returns their statuses. */
    public static List<HttpStatusCode> race(int n, Supplier<ResponseEntity<JsonNode>> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<HttpStatusCode>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<HttpStatusCode> task = () -> {
                    ready.countDown();
                    go.await();
                    return call.get().getStatusCode();
                };
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<HttpStatusCode> statuses = new ArrayList<>();
            for (Future<HttpStatusCode> f : futures) {
                statuses.add(f.get(60, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    public static long count(List<HttpStatusCode> statuses, HttpStatus status) {
        return statuses.stream().filter(s -> s.value() == status.value()).count();
    }

    // ---- Fixtures -------------------------------------------------------------------------

    public record Role(String key, UUID branchId) {}

    /** An active staff user with the test password and the given roles, inserted as the owner. */
    public static UUID staff(TestDatabase.Fixture t, String email, Role... roles) {
        UUID id = UUID.randomUUID();
        var owner = TestDatabase.owner();
        owner.sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, email, status) VALUES (?, ?, 'staff', ?, ?, 'active')")
                .params(id, t.tenantId(), "Test Staff " + email.substring(0, email.indexOf('@')), email)
                .update();
        owner.sql("INSERT INTO user_credentials (user_id, tenant_id, password_hash) VALUES (?, ?, ?)")
                .params(id, t.tenantId(), passwordHash())
                .update();
        for (Role role : roles) {
            owner.sql("""
                            INSERT INTO user_role_assignments (id, tenant_id, user_id, role_key, branch_id, granted_by)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """)
                    .params(UUID.randomUUID(), t.tenantId(), id, role.key(), role.branchId(), id)
                    .update();
        }
        return id;
    }

    /** A platform operator with the test password, not yet enrolled in MFA. */
    public static UUID platformUser(String email) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO platform_users (id, email, full_name, password_hash) VALUES (?, ?, 'Test Operator', ?)")
                .params(id, email, passwordHash())
                .update();
        return id;
    }

    private static synchronized String passwordHash() {
        if (cachedHash == null) {
            cachedHash = ENCODER.encode(PASSWORD);
        }
        return cachedHash;
    }

    /**
     * A TOTP code is accepted once per time step; tests that sign the same user in twice within
     * one step clear the remembered step first, as the owner.
     */
    public static void allowTotpReuse(UUID userId) {
        if (userId == null) {
            return;
        }
        TestDatabase.owner()
                .sql("UPDATE user_credentials SET totp_last_step = NULL WHERE user_id = ?")
                .param(userId)
                .update();
        TestDatabase.owner()
                .sql("UPDATE platform_users SET totp_last_step = NULL WHERE id = ?")
                .param(userId)
                .update();
    }

    /** RFC 6238 with SHA-1, 6 digits and 30 second steps. */
    public static String totp(String base32Secret, Instant at) {
        try {
            byte[] key = new Base32().decode(base32Secret);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(
                    ByteBuffer.allocate(8).putLong(at.getEpochSecond() / 30).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
    }
}
