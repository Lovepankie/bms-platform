package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.kernel.ApiException;
import java.time.Duration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A transaction-scoped advisory lock per business and key, so a crashed holder leaves nothing held. Two keys are used:
 * {@code payments:statement:<tenant>} puts one business's statement uploads, fee approvals and line marks one after the other,
 * and {@code payments:reconcile:<tenant>} its reconciliation runs; an upload and a run do not wait for each other. The wait for it is bounded, and only that wait: the lock timeout is
 * set for the one statement that takes the lock and put back to what it was at once, so a later write of the same transaction
 * (a scheduled look, the retention delete, a posting) waits as it always does and is never reported as "another run is going".
 * Failing to take the lock in time is 409 {@code try_again}; the nightly job that meets it logs the miss and tries that business
 * again later the same night ({@link ReconciliationService#runNightly}).
 */
final class BusinessLock {

    private BusinessLock() {}

    static void take(JdbcClient jdbc, String key, Duration wait) {
        String before = jdbc.sql("SELECT current_setting('lock_timeout')")
                .query(String.class)
                .single();
        jdbc.sql("SELECT set_config('lock_timeout', ?, true)")
                .param(Math.max(1, wait.toMillis()) + "ms")
                .query(String.class)
                .single();
        try {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                    .param(key)
                    .query((rs, n) -> 0)
                    .list();
        } catch (DataAccessException e) {
            if (lockWaitEnded(e)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "try_again",
                        "Try again",
                        "Another reconciliation or statement upload of this business is going. Try again in a minute.");
            }
            throw e;
        }
        jdbc.sql("SELECT set_config('lock_timeout', ?, true)")
                .param(before)
                .query(String.class)
                .single();
    }

    /** The wait for the lock ran out ({@code lock_timeout}, SQL state 55P03). */
    static boolean lockWaitEnded(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && "55P03".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
