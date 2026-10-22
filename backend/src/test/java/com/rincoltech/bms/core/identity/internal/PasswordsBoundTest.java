package com.rincoltech.bms.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.kernel.ApiException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Launch readiness M9, ADR-059, issue #426: argon2id runs a bounded number of hashes at once, and a caller that finds no
 * free slot in time is told sign-in is busy (503 with a Retry-After of two to five seconds) instead of the API filling its
 * heap; and no hash runs inside a transaction (review of #433, findings 3 and 7).
 */
class PasswordsBoundTest {

    final Passwords passwords = new Passwords(new AuthProperties("none", null, null, null, null, 4096, 1), 2);

    @Test
    void aHashWaitsForAFreeSlotAndIsRefusedAsBusyWhenNoneComes() throws Exception {
        String hash = passwords.hash("Fabricated-Pass-2026");
        passwords.hashing.acquire(2);
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> passwords.matches("Fabricated-Pass-2026", hash))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.code()).isEqualTo("sign_in_busy");
                        assertThat(e.retryAfter())
                                .hasValueSatisfying(wait ->
                                        assertThat(wait).isBetween(Duration.ofSeconds(2), Duration.ofSeconds(5)));
                    });
            // An unknown account is refused the same way: the dummy hash takes a slot too.
            assertThatThrownBy(() -> passwords.matches("Fabricated-Pass-2026", null))
                    .isInstanceOfSatisfying(
                            ApiException.class, e -> assertThat(e.code()).isEqualTo("sign_in_busy"));
            assertThat((System.nanoTime() - started) / 1_000_000)
                    .isGreaterThanOrEqualTo(2 * 1000L * Passwords.WAIT_SECONDS - 100);
        } finally {
            passwords.hashing.release(2);
        }
        // Every slot comes back after use.
        assertThat(passwords.matches("Fabricated-Pass-2026", hash)).isTrue();
        assertThat(passwords.hashing.availablePermits()).isEqualTo(2);
    }

    /** Finding 3: a hash asked for inside a transaction would hold its database connection while it waits; it is refused. */
    @Test
    void aHashInsideATransactionIsRefused() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> passwords.matches("Fabricated-Pass-2026", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("inside a transaction");
            assertThatThrownBy(() -> passwords.hash("Fabricated-Pass-2026")).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        // No slot was taken by the refusals.
        assertThat(passwords.hashing.availablePermits()).isEqualTo(2);
    }
}
