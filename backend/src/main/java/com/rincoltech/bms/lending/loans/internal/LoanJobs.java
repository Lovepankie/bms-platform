package com.rincoltech.bms.lending.loans.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class LoanJobs {

    private static final Logger log = LoggerFactory.getLogger(LoanJobs.class);

    static final String APPROVAL_EXPIRY = "lending.loan-approval-expiry";
    static final String ARREARS = "lending.loan-arrears";

    /**
     * FR-ARR-01 every quarter hour: each lending tenant whose business date has turned since its last finished run
     * gets that date's run, in its own time zone, at the first tick after its midnight. Each tenant runs outside any
     * transaction ({@code callAs}): the run claims its date with a lease and takes one short transaction per loan
     * ({@link ArrearsRun}). One tenant's failure is logged and does not stop the others. The insights snapshot (00:45)
     * computes its positions itself from the items and transactions of each date, so it does not wait for this run.
     * Switched off by {@code bms.lending.arrears-job-enabled=false}, which the integration tests set so that a tick
     * never charges penalties on a test's loans; the tests drive {@link ArrearsRun} themselves. On everywhere else.
     */
    @Bean
    @ConditionalOnProperty(name = "bms.lending.arrears-job-enabled", havingValue = "true", matchIfMissing = true)
    RecurringTask<Void> loanArrears(TenantJobs tenantJobs, ArrearsRun arrears, JdbcClient jdbc) {
        return Tasks.recurring(ARREARS, Schedules.cron("0 0/15 * * * *", BusinessClock.DEFAULT_ZONE))
                .execute((instance, context) -> {
                    List<UUID> tenants = jdbc.sql("SELECT app_list_active_tenants_with_module(?)")
                            .param("lending")
                            .query(UUID.class)
                            .list();
                    int failures = 0;
                    for (UUID tenantId : tenants) {
                        try {
                            tenantJobs.callAs(tenantId, arrears::runIfDue);
                        } catch (RuntimeException e) {
                            failures++;
                            log.error("job {} failed for tenant {}", ARREARS, tenantId, e);
                        }
                    }
                    if (failures > 0) {
                        throw new IllegalStateException("job " + ARREARS + " failed for " + failures + " tenant(s)");
                    }
                });
    }

    /** An approved loan not disbursed within the tenant's approval_validity_days is cancelled (FR-ORG-08). */
    @Bean
    RecurringTask<Void> loanApprovalExpiry(TenantJobs tenantJobs, DecisionService decisions) {
        return Tasks.recurring(APPROVAL_EXPIRY, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(0, 30)))
                .execute((instance, context) -> tenantJobs.forEachActiveTenantWithModule(
                        APPROVAL_EXPIRY, "lending", tenantId -> decisions.expireOverdue()));
    }
}
