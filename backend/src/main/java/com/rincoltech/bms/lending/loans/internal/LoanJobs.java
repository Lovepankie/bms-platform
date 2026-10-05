package com.rincoltech.bms.lending.loans.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class LoanJobs {

    static final String APPROVAL_EXPIRY = "lending.loan-approval-expiry";

    /** An approved loan not disbursed within the tenant's approval_validity_days is cancelled (FR-ORG-08). */
    @Bean
    RecurringTask<Void> loanApprovalExpiry(TenantJobs tenantJobs, DecisionService decisions) {
        return Tasks.recurring(APPROVAL_EXPIRY, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(0, 30)))
                .execute((instance, context) -> tenantJobs.forEachActiveTenantWithModule(
                        APPROVAL_EXPIRY, "lending", tenantId -> decisions.expireOverdue()));
    }
}
