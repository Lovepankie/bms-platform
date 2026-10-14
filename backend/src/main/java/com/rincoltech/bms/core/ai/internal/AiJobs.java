package com.rincoltech.bms.core.ai.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AiJobs {

    static final String HOUSEKEEPING = "core.ai-calls-housekeeping";

    /**
     * The call log is kept 90 days (spec 5.10); a call left pending for an hour is closed as failed. One
     * database function does it for every tenant at once, suspended and closed ones too: retention is a
     * duty that does not stop with the subscription, so this job does not go through the active-tenant loop.
     */
    @Bean
    RecurringTask<Void> aiCallsHousekeeping(AiCallLog callLog) {
        return Tasks.recurring(HOUSEKEEPING, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(3, 20)))
                .execute((instance, context) -> callLog.housekeeping());
    }
}
