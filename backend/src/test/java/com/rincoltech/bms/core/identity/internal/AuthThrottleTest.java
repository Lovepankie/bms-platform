package com.rincoltech.bms.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The in-memory sign-in buckets of ADR-059 (launch readiness M9, issue #426; review of #433 findings 1, 2, 7 and 8): only
 * failures count per client IP, a refusal carries the refill time, and a bucket that remembers anything is never evicted.
 * Fabricated values only.
 */
class AuthThrottleTest {

    /** A clock the test moves by hand. */
    static final class MovingClock extends Clock {
        Instant now = Instant.parse("2026-10-21T08:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final MovingClock clock = new MovingClock();
    final AuthThrottle throttle = new AuthThrottle(new BusinessClock(clock), 3);

    static Accounts accounts(String kind, UUID tenantId) {
        Accounts accounts = mock(Accounts.class);
        when(accounts.kind()).thenReturn(kind);
        when(accounts.tenantId()).thenReturn(tenantId);
        return accounts;
    }

    static ApiException wrongPassword() {
        return AuthFlow.invalidCredentials();
    }

    /** Runs a sign-in that fails as a wrong password does, through the per-IP guard. */
    void fail(String ip) {
        assertThatThrownBy(() -> throttle.countingFailures(throttle.signInPerIp, ip, () -> {
                    throw wrongPassword();
                }))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_credentials"));
    }

    @Test
    void theDefaultIsThreeHundredFailuresInTenMinutes() {
        AuthThrottle defaults =
                new AuthThrottle(new BusinessClock(clock), AuthThrottle.DEFAULT_SIGN_IN_FAILURES_PER_IP);
        assertThat(defaults.signInPerIp.capacity()).isEqualTo(300);
        assertThat(defaults.signInPerIp.per()).isEqualTo(Duration.ofMinutes(10));
    }

    /**
     * Carrier-grade NAT (finding 8): many people behind one IP sign in successfully without using the IP's budget, and a
     * caller with the right password is let through however many others have signed in from that IP.
     */
    @Test
    void successesFromOneIpCostNothing() {
        AtomicInteger signedIn = new AtomicInteger();
        for (int i = 0; i < 1_000; i++) {
            throttle.countingFailures(throttle.signInPerIp, "203.0.113.7", signedIn::incrementAndGet);
        }
        assertThat(signedIn).hasValue(1_000);
        // The whole budget of failures is still there.
        for (int i = 0; i < 3; i++) {
            fail("203.0.113.7");
        }
        assertThat(throttle.allows(throttle.signInPerIp, "203.0.113.7")).isFalse();
    }

    /**
     * Review of 433: the token is taken before the work, so requests in flight at once hold the budget between them and
     * one more is refused at once, before it does any work; when they succeed their tokens come back.
     */
    @Test
    void requestsInFlightHoldTheBudgetSoABurstCannotAllPass() throws Exception {
        CountDownLatch started = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<Integer>> inFlight = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                inFlight.add(pool.submit(() -> throttle.countingFailures(throttle.signInPerIp, "203.0.113.20", () -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return 1;
                })));
            }
            started.await();
            AtomicInteger ran = new AtomicInteger();
            assertThatThrownBy(
                            () -> throttle.countingFailures(throttle.signInPerIp, "203.0.113.20", ran::incrementAndGet))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo("rate_limited"));
            assertThat(ran).hasValue(0);
            release.countDown();
            for (Future<Integer> call : inFlight) {
                assertThat(call.get()).isEqualTo(1);
            }
            // Three successes later the budget is whole again.
            assertThat(throttle.countingFailures(throttle.signInPerIp, "203.0.113.20", ran::incrementAndGet))
                    .isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void failuresFromOneIpAreLimitedAndTheRefusalSaysWhenToComeBack() {
        for (int i = 0; i < 3; i++) {
            fail("203.0.113.7");
        }
        AtomicInteger ran = new AtomicInteger();
        assertThatThrownBy(() -> throttle.countingFailures(throttle.signInPerIp, "203.0.113.7", ran::incrementAndGet))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.code()).isEqualTo("rate_limited");
                    // Three tokens over ten minutes: one comes back in 200 seconds (finding 7).
                    assertThat(e.retryAfter()).contains(Duration.ofSeconds(200));
                });
        // Refused before any work: no hash, no database.
        assertThat(ran).hasValue(0);
        // Another office is not affected.
        assertThat(throttle.allows(throttle.signInPerIp, "203.0.113.8")).isTrue();

        clock.now = clock.now.plus(Duration.ofSeconds(150));
        assertThatThrownBy(() -> throttle.countingFailures(throttle.signInPerIp, "203.0.113.7", ran::incrementAndGet))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(50)));
        clock.now = clock.now.plus(Duration.ofSeconds(50));
        assertThat(throttle.countingFailures(throttle.signInPerIp, "203.0.113.7", ran::incrementAndGet))
                .isEqualTo(1);
    }

    @Test
    void oneIpv6SlashSixtyFourIsOneClient() {
        for (int i = 0; i < 3; i++) {
            fail("2001:db8:1:2::" + (i + 1));
        }
        assertThat(throttle.allows(throttle.signInPerIp, "2001:db8:1:2:ffff::9"))
                .isFalse();
        assertThat(throttle.allows(throttle.signInPerIp, "2001:db8:1:3::1")).isTrue();
    }

    /** What counts against the IP: a client error does; a lock, a limit and the server being busy cost it no work. */
    @Test
    void onlyClientErrorsCountAsFailures() {
        assertThat(AuthThrottle.isFailure(wrongPassword())).isTrue();
        assertThat(AuthThrottle.isFailure(ApiException.rule("invitation_invalid", "x")))
                .isTrue();
        assertThat(AuthThrottle.isFailure(new ApiException(HttpStatus.LOCKED, "account_locked", "t", "d")))
                .isFalse();
        assertThat(AuthThrottle.isFailure(AuthThrottle.limited(Duration.ofSeconds(1))))
                .isFalse();
        assertThat(AuthThrottle.isFailure(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "sign_in_busy", "t", "d")))
                .isFalse();

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> throttle.countingFailures(throttle.signInPerIp, "203.0.113.9", () -> {
                        throw new ApiException(HttpStatus.LOCKED, "account_locked", "t", "d");
                    }))
                    .isInstanceOf(ApiException.class);
        }
        assertThat(throttle.allows(throttle.signInPerIp, "203.0.113.9")).isTrue();
    }

    /** The reset buckets still count every request, and their refusal carries the refill time too. */
    @Test
    void theResetBucketsCountEveryRequestWithRetryAfter() {
        String address = AuthThrottle.address(accounts("staff", UUID.randomUUID()), "test.clerk@example.test");
        for (int i = 0; i < AuthThrottle.REQUEST_PER_ADDRESS.capacity(); i++) {
            throttle.acquireFor(AuthThrottle.REQUEST_PER_ADDRESS, address);
        }
        assertThatThrownBy(() -> throttle.acquireFor(AuthThrottle.REQUEST_PER_ADDRESS, address))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("rate_limited");
                    assertThat(e.retryAfter()).contains(Duration.ofMinutes(6));
                });
    }

    /** The key of an address: every form of one address is one key, and another tenant or kind is another. */
    @Test
    void everyFormOfAnAddressIsOneKey() {
        Accounts staff = accounts("staff", UUID.fromString("00000000-0000-4000-8000-0000000000a1"));
        String phone = AuthThrottle.address(staff, "0700 000 001");
        assertThat(AuthThrottle.address(staff, "+256700000001")).isEqualTo(phone);
        assertThat(AuthThrottle.address(staff, " Test.Cashier@Example.TEST "))
                .isEqualTo(AuthThrottle.address(staff, "test.cashier@example.test"));
        assertThat(AuthThrottle.address(accounts("staff", UUID.randomUUID()), "0700000001"))
                .isNotEqualTo(phone);
        assertThat(AuthThrottle.address(accounts("platform", null), "0700000001"))
                .isNotEqualTo(phone);
        assertThat(SignInAddressFailures.key(phone)).hasSize(64).matches("[0-9a-f]{64}");
    }

    /**
     * Finding 1: a flood of new keys cannot evict a drained bucket. Once the store is full, refilled buckets are dropped to
     * make room and drained ones are kept; when nothing can be dropped, a new key goes uncounted instead of a kept one
     * being forgotten.
     */
    @Test
    void aDrainedBucketSurvivesAFloodOfNewKeys() {
        for (int i = 0; i < 3; i++) {
            fail("198.51.100.1");
        }
        // Fill the store with other IPs that failed once (partly drained, so they must be kept too).
        for (int i = 0; i < AuthThrottle.MAX_KEYS; i++) {
            fail("10." + (i >> 16 & 255) + "." + (i >> 8 & 255) + "." + (i & 255));
        }
        assertThat(throttle.keys(throttle.signInPerIp)).isEqualTo(AuthThrottle.MAX_KEYS);
        assertThat(throttle.allows(throttle.signInPerIp, "198.51.100.1"))
                .as("the drained IP is still limited")
                .isFalse();

        // Every kept bucket refills; after the sweep interval the next new key makes room by dropping refilled ones
        // only.
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        fail("198.51.100.2");
        assertThat(throttle.keys(throttle.signInPerIp)).isLessThan(AuthThrottle.MAX_KEYS);
        assertThat(throttle.allows(throttle.signInPerIp, "198.51.100.2")).isTrue();
    }

    @Test
    void aFullStoreOfLiveBucketsLetsANewKeyThroughUncountedRatherThanForgetOne() {
        for (int i = 0; i < AuthThrottle.MAX_KEYS; i++) {
            fail("10." + (i >> 16 & 255) + "." + (i >> 8 & 255) + "." + (i & 255));
        }
        for (int i = 0; i < 5; i++) {
            fail("198.51.100.3");
        }
        assertThat(throttle.keys(throttle.signInPerIp)).isEqualTo(AuthThrottle.MAX_KEYS);
        assertThat(throttle.allows(throttle.signInPerIp, "198.51.100.3")).isTrue();
        // None of the kept buckets was forgotten.
        assertThat(throttle.allows(throttle.signInPerIp, "10.0.0.0")).isTrue();
        fail("10.0.0.0");
        fail("10.0.0.0");
        assertThat(throttle.allows(throttle.signInPerIp, "10.0.0.0")).isFalse();
    }
}
