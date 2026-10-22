package com.rincoltech.bms.core.payments.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The payment jobs (ADR-008): the poller every 10 seconds, the nightly retention and the nightly re-seal of stored credentials after a data key rotation. The poller is switched off by
 * {@code bms.payments.poller-enabled=false}, which the integration tests set so that they drive
 * {@link PaymentPoller#tick} themselves and a scheduler tick cannot race them; it is on everywhere else. (The scheduler
 * itself polls for due tasks every 10 seconds, so a notification is acted on within about 20 seconds.)
 */
@Configuration(proxyBeanMethods = false)
class PaymentJobs {

    static final String POLL = "core.payments.poll";
    static final String HOUSEKEEPING = "core.payments.housekeeping";
    static final String RECONCILE = "core.payments.reconcile";
    static final String RESEAL = "core.payments.reseal-accounts";

    @Bean
    @ConditionalOnProperty(name = "bms.payments.poller-enabled", havingValue = "true", matchIfMissing = true)
    RecurringTask<Void> paymentPollTask(PaymentPoller poller) {
        return Tasks.recurring(POLL, Schedules.fixedDelay(Duration.ofSeconds(10)))
                .execute((instance, context) -> poller.tick());
    }

    /** The nightly reconciliation of each business's gateway payments (FR-PAY-05), after the end of day. */
    @Bean
    @ConditionalOnProperty(name = "bms.payments.poller-enabled", havingValue = "true", matchIfMissing = true)
    RecurringTask<Void> paymentReconcileTask(TenantJobs tenantJobs, ReconciliationService reconciliation) {
        return Tasks.recurring(RECONCILE, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(3, 10)))
                .execute((instance, context) ->
                        tenantJobs.forEachActiveTenant(RECONCILE, tenantId -> reconciliation.run("job", null, false)));
    }

    /** Re-seals lenders' stored credentials with the current data key after a rotation; a no-op when none is left. */
    @Bean
    @ConditionalOnProperty(name = "bms.payments.poller-enabled", havingValue = "true", matchIfMissing = true)
    RecurringTask<Void> paymentAccountResealTask(AccountKeyRotation rotation) {
        return Tasks.recurring(RESEAL, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(3, 50)))
                .execute((instance, context) -> rotation.run());
    }

    @Bean
    @ConditionalOnProperty(name = "bms.payments.poller-enabled", havingValue = "true", matchIfMissing = true)
    RecurringTask<Void> paymentHousekeepingTask(PaymentPoller poller) {
        return Tasks.recurring(HOUSEKEEPING, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(3, 40)))
                .execute((instance, context) -> poller.housekeeping());
    }
}
