package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The application's own limit on the public notification endpoints: per address and overall, in a one minute window on the kernel clock. */
class CallbackLimiterTest {

    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-08T10:00:10Z"));
    final BusinessClock clock = new BusinessClock(new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    });

    CallbackLimiter limiter(int perAddress, int overall) {
        return new CallbackLimiter(new PaymentsProperties(true, 5, 40, perAddress, overall, 4096), clock);
    }

    @Test
    void anAddressMayCallUpToItsLimitInAMinuteAndNoMoreAndTheNextMinuteStartsAgain() {
        CallbackLimiter limiter = limiter(3, 1000);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "203.0.113.1"))
                    .isTrue();
        }
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "203.0.113.1"))
                .isFalse();
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "203.0.113.1"))
                .isFalse();
        // Another address is not held back by the first.
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "203.0.113.2"))
                .isTrue();
        now.set(Instant.parse("2026-10-08T10:00:59Z"));
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "203.0.113.1"))
                .as("still the same minute")
                .isFalse();
        now.set(Instant.parse("2026-10-08T10:01:00Z"));
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "203.0.113.1"))
                .isTrue();
    }

    @Test
    void allAddressesTogetherHaveALimitToo() {
        CallbackLimiter limiter = limiter(100, 5);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "198.51.100." + i))
                    .isTrue();
        }
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "198.51.100.99"))
                .isFalse();
        now.set(Instant.parse("2026-10-08T10:02:00Z"));
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "198.51.100.99"))
                .isTrue();
    }

    @Test
    void thePayerReturnPageAndTheProvidersNotificationsHaveSeparateBuckets() {
        CallbackLimiter limiter = limiter(2, 3);
        for (int i = 0; i < 50; i++) {
            limiter.tryAcquire(CallbackLimiter.Bucket.RETURN, "198.51.100." + i);
        }
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.RETURN, "198.51.100.77"))
                .as("the return bucket is full")
                .isFalse();
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "198.51.100.77"))
                .as("the provider is not held back")
                .isTrue();
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "198.51.100.78"))
                .isTrue();
    }

    @Test
    void aRefusedRequestDoesNotUseUpTheOverallBudget() {
        CallbackLimiter limiter = limiter(1, 3);
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "a")).isTrue();
        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "a")).isFalse();
        }
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "b")).isTrue();
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "c")).isTrue();
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "d")).isFalse();
    }

    @Test
    void aFloodFromManyAddressesIsLimitedAsAWholeAndAnUnknownAddressIsCounted() {
        CallbackLimiter limiter = limiter(100, 1_000_000);
        for (int i = 0; i < CallbackLimiter.MAX_ADDRESSES; i++) {
            assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "addr-" + i))
                    .isTrue();
        }
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "one-more"))
                .as("the table of addresses is full")
                .isFalse();
        assertThat(limiter.tryAcquire(CallbackLimiter.Bucket.IPN, "addr-0"))
                .as("a known address still has its own budget")
                .isTrue();
        CallbackLimiter other = limiter(1, 10);
        assertThat(other.tryAcquire(CallbackLimiter.Bucket.IPN, null)).isTrue();
        assertThat(other.tryAcquire(CallbackLimiter.Bucket.IPN, null)).isFalse();
    }
}
