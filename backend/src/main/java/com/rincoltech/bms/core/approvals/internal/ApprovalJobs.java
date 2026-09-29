package com.rincoltech.bms.core.approvals.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ApprovalJobs {

    static final String EXPIRY = "core.approval-expiry";

    /** Pending requests expire 7 days after they were made (FR-APR-07). */
    @Bean
    RecurringTask<Void> approvalExpiry(TenantJobs tenantJobs, ApprovalService approvals) {
        return Tasks.recurring(EXPIRY, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(0, 15)))
                .execute((instance, context) ->
                        tenantJobs.forEachActiveTenant(EXPIRY, tenantId -> approvals.expireOverdue()));
    }
}
