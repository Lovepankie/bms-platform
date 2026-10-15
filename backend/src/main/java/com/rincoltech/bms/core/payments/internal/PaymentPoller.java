package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The job {@code core.payments.poll} (ADR-008, spec 3.2 and 9.9): every few seconds it takes the intents whose next check
 * is due and gives each to {@link StatusChecker#process}, which makes the order, asks the provider or books what is paid.
 * One job serves the notification (it only moves a check to now), the poll of an order nobody notified us about
 * (every two minutes while young, then less often, until 48 hours), the creation of an order a request did not finish
 * and the retry of a booking that failed technically, so there is one path to every payment.
 *
 * <p><b>Fair across tenants.</b> A tick takes at most {@code pollTotal} intents (40 by default), at most {@code
 * pollPerTenant} (5) from one tenant, and visits the tenants in the order of their oldest due check, so a tenant with a
 * thousand due intents delays a tenant with one by at most the time it takes to serve the tenants ahead of it, and the
 * calls made to the provider per tick are bounded. Intents are claimed with {@code FOR UPDATE SKIP LOCKED} and a lease,
 * so two instances never take the same one, and a worker that dies leaves its intents to be taken again when the lease
 * ends. It never marks anything paid itself: that is the checker, on the provider's verified answer.
 */
@Component
class PaymentPoller {

    private static final Logger log = LoggerFactory.getLogger(PaymentPoller.class);

    private final JdbcClient jdbc;
    private final TenantJobs tenantJobs;
    private final IntentStore store;
    private final StatusChecker checker;
    private final PaymentsProperties properties;
    private final BusinessClock clock;

    PaymentPoller(
            JdbcClient jdbc,
            TenantJobs tenantJobs,
            IntentStore store,
            StatusChecker checker,
            PaymentsProperties properties,
            BusinessClock clock) {
        this.jdbc = jdbc;
        this.tenantJobs = tenantJobs;
        this.store = store;
        this.checker = checker;
        this.properties = properties;
        this.clock = clock;
    }

    /** One pass. Returns the number of intents it worked on. */
    int tick() {
        Instant now = clock.now();
        List<UUID> tenants = jdbc.sql("SELECT app_tenants_with_due_payment_checks(?, ?)")
                .params(IntentStore.ts(now), 200)
                .query(UUID.class)
                .list();
        int budget = properties.pollTotal();
        int worked = 0;
        int failures = 0;
        for (UUID tenantId : tenants) {
            if (budget <= 0) {
                break;
            }
            int take = Math.min(properties.pollPerTenant(), budget);
            List<UUID> claimed;
            try {
                claimed = tenantJobs.callForTenant(
                        tenantId, () -> store.claimDue(now, take, now.plus(CheckSchedule.LEASE)));
            } catch (TenantJobs.InactiveTenantException e) {
                continue;
            } catch (RuntimeException e) {
                failures++;
                log.error(
                        "payment poll could not claim for tenant {}: {}",
                        tenantId,
                        e.getClass().getSimpleName());
                continue;
            }
            for (UUID intentId : claimed) {
                budget--;
                worked++;
                try {
                    tenantJobs.callAs(tenantId, () -> {
                        checker.process(intentId);
                        return null;
                    });
                } catch (RuntimeException e) {
                    // The lease keeps the intent from being taken again at once; the next tick after it retries.
                    failures++;
                    log.error(
                            "payment intent {} check failed: {}",
                            intentId,
                            e.getClass().getSimpleName());
                }
            }
        }
        if (failures > 0) {
            log.warn("payment poll: {} failure(s) in a tick of {} intent(s)", failures, worked);
        }
        return worked;
    }

    /** The nightly retention of finished notification rows and counters (spec 9.2). */
    int housekeeping() {
        return jdbc.sql("SELECT payments_housekeeping(?)")
                .param(IntentStore.ts(clock.now()))
                .query(Integer.class)
                .single();
    }
}
