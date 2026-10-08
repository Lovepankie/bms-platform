package com.rincoltech.bms.lending.investments.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class InvestmentJobs {

    static final String DAILY = "lending.investment-returns";

    /**
     * FR-INV-04, FR-INV-05, FR-INV-07: accrues the periods ended, makes payout periods due,
     * matures and rolls over, and records the maturity reminders, for each lending tenant on its
     * business date. Idempotent, so a retried or repeated run changes nothing.
     */
    @Bean
    RecurringTask<Void> investmentReturns(TenantJobs tenantJobs, InvestmentServicer servicer) {
        return Tasks.recurring(DAILY, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(1, 0)))
                .execute((instance, context) -> tenantJobs.forEachActiveTenantWithModule(
                        DAILY, "lending", tenantId -> servicer.runDaily(servicer.today())));
    }
}
