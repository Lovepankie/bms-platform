package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.kernel.BusinessClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Failed sign-ins for an address that matches no account (ADR-059, migration V78, review of #433 finding 1): counted in
 * the database, keyed by the SHA-256 of {@link AuthThrottle#address}, with exactly the statement {@link Accounts#failureSql}
 * runs on an account's row, so the fifth failure in a row locks the address for {@link AuthFlow#LOCK}, a failure while
 * locked keeps the lock, and an expired lock starts the count again. The lock survives a restart and is never evicted, so
 * after a restart a real account and an address of nobody still answer alike.
 *
 * <p>A row nobody has failed on for {@link #FORGET_AFTER} is deleted by the nightly purge (an account's own count is kept
 * until a right password). The table holds no address, only its hash, and no tenant id (the tenant is inside the hash).
 * Every method runs in the caller's transaction.
 */
@Component
class SignInAddressFailures {

    /** A row with no failure for this long, and no lock in force, is purged. */
    static final Duration FORGET_AFTER = Duration.ofDays(7);

    private final JdbcClient jdbc;
    private final BusinessClock clock;

    SignInAddressFailures(JdbcClient jdbc, BusinessClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    static String key(String address) {
        return Secrets.sha256(address);
    }

    /** Whether the address is locked at {@code now}. Read on every sign-in, for a real account too, so both do this work. */
    boolean lockedAt(String key, Instant now) {
        return jdbc.sql("""
                        SELECT coalesce(bool_or(locked_until > ?), false) FROM sign_in_address_failures
                         WHERE address_hash = ?
                        """)
                .params(Timestamp.from(now), key)
                .query(Boolean.class)
                .single();
    }

    /**
     * The counterpart of {@link Accounts#byIdForUpdate} for an address of nobody: makes sure its row exists, locks it for the
     * rest of the transaction and says whether it is locked at {@code now}, so parallel failures for one address settle one
     * after another, as an account's do, and once the lock is set every later one sees it.
     */
    boolean lockedForUpdate(String key, Instant now) {
        jdbc.sql("""
                        INSERT INTO sign_in_address_failures (address_hash, failed_login_count, last_failed_at)
                        VALUES (?, 0, ?) ON CONFLICT (address_hash) DO NOTHING
                        """).params(key, Timestamp.from(now)).update();
        return jdbc.sql("""
                        SELECT coalesce(locked_until > ?, false) FROM sign_in_address_failures
                         WHERE address_hash = ? FOR UPDATE
                        """)
                .params(Timestamp.from(now), key)
                .query(Boolean.class)
                .single();
    }

    /**
     * Counts one more failure in a single statement, as {@link Accounts#recordFailure} does for an account: an expired lock
     * starts a new count, the count reaching {@code maxFailures} sets {@code lockUntil}, a failure while locked keeps the
     * current lock. Parallel failures are all counted (the insert and the update are one statement on one key).
     */
    Accounts.Failures recordFailure(String key, Instant now, int maxFailures, Instant lockUntil) {
        Timestamp at = Timestamp.from(now);
        Timestamp until = Timestamp.from(lockUntil);
        return jdbc.sql("""
                        INSERT INTO sign_in_address_failures AS f (address_hash, failed_login_count, locked_until, last_failed_at)
                        VALUES (?, 1, CASE WHEN 1 >= ? THEN CAST(? AS timestamptz) END, ?)
                        ON CONFLICT (address_hash) DO UPDATE SET
                               failed_login_count = CASE WHEN f.locked_until <= ? THEN 1 ELSE f.failed_login_count + 1 END,
                               locked_until = CASE WHEN f.locked_until > ? THEN f.locked_until
                                                   WHEN f.locked_until IS NULL AND f.failed_login_count + 1 >= ? THEN CAST(? AS timestamptz)
                                                   ELSE NULL END,
                               last_failed_at = ?
                        RETURNING failed_login_count, locked_until
                        """)
                .params(key, maxFailures, until, at, at, at, maxFailures, until, at)
                .query(Accounts::failures)
                .single();
    }

    /** The nightly purge; returns the rows deleted. Runs in the caller's transaction. */
    int purge() {
        return jdbc.sql("SELECT sign_in_address_failures_purge(?)")
                .param(Timestamp.from(clock.now().minus(FORGET_AFTER)))
                .query(Integer.class)
                .single();
    }
}
