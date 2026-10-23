package com.rincoltech.bms.core.platform.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.kernel.BusinessClock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class BillingJobs {

    static final String BILLING = "core.platform.subscription-billing";

    /**
     * Issue #480, ADR-075: every quarter hour, each business whose last day (paid for, or of the free month) has passed in its
     * own time zone becomes overdue at the first tick after its midnight, and each reminder due today goes at the first tick
     * from 08:00 there, once ({@link BillingService#run}). Nothing is ever suspended. One business's failure is logged and does
     * not stop the others; the run then fails so the scheduler records it. Switched off by
     * {@code bms.platform.billing-job-enabled=false}, which the integration tests set; they call {@code run} themselves. On
     * everywhere else.
     */
    @Bean
    @ConditionalOnProperty(name = "bms.platform.billing-job-enabled", havingValue = "true", matchIfMissing = true)
    RecurringTask<Void> subscriptionBilling(BillingService billing) {
        return Tasks.recurring(BILLING, Schedules.cron("0 10/15 * * * *", BusinessClock.DEFAULT_ZONE))
                .execute((instance, context) -> {
                    BillingService.RunResult result = billing.run();
                    if (result.failures() > 0) {
                        throw new IllegalStateException(
                                "job " + BILLING + " failed for " + result.failures() + " tenant(s)");
                    }
                });
    }
}
