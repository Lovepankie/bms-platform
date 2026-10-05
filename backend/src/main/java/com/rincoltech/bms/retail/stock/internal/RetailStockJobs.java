package com.rincoltech.bms.retail.stock.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RetailStockJobs {

    static final String RECONCILIATION = "retail.stock-reconciliation";

    /** FR-RET-03: nightly, for every active tenant with the retail module on (ADR-008). */
    @Bean
    RecurringTask<Void> retailStockReconciliation(TenantJobs tenantJobs, StockReconciliation reconciliation) {
        return Tasks.recurring(RECONCILIATION, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(1, 30)))
                .execute((instance, context) -> tenantJobs.forEachActiveTenantWithModule(
                        RECONCILIATION, "retail", tenantId -> reconciliation.run()));
    }
}
