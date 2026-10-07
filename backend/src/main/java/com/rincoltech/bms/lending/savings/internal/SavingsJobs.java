package com.rincoltech.bms.lending.savings.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SavingsJobs {

    static final String END_OF_DAY = "lending.savings-end-of-day";

    /**
     * FR-SAV-05, FR-SAV-06: every night, for every tenant with lending, the end of day through
     * yesterday (end-of-day balances, interest at each period end, dormancy). Catches up any days a
     * missed run left, and is idempotent per account and day.
     */
    @Bean
    RecurringTask<Void> savingsEndOfDay(TenantJobs tenantJobs, SavingsServicer servicer) {
        return Tasks.recurring(END_OF_DAY, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(0, 20)))
                .execute((instance, context) -> tenantJobs.forEachActiveTenantWithModule(
                        END_OF_DAY,
                        "lending",
                        tenantId -> servicer.endOfDay(servicer.today().minusDays(1))));
    }
}
