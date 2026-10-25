package com.rincoltech.bms.core.platform.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.kernel.BusinessClock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ClosureJobs {

    static final String PURGE = "core.platform.closed-retention";

    /**
     * Owner decision D46 (ADR-090): every night at 03:20 in Kampala, the money records a removed business kept are deleted once
     * its {@code keep_until} has passed ({@link ClosureService#purgeDue}). Idempotent: a purged request is never listed again.
     * Switched off by {@code bms.platform.closed-retention-job-enabled=false}, which the integration tests set; they call
     * {@code purgeDue} themselves.
     */
    @Bean
    @ConditionalOnProperty(
            name = "bms.platform.closed-retention-job-enabled",
            havingValue = "true",
            matchIfMissing = true)
    RecurringTask<Void> closedRetention(ClosureService closures) {
        return Tasks.recurring(PURGE, Schedules.cron("0 20 3 * * *", BusinessClock.DEFAULT_ZONE))
                .execute((instance, context) -> closures.purgeDue());
    }
}
