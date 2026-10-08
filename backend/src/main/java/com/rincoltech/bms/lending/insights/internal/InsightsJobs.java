package com.rincoltech.bms.lending.insights.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Duration;
import java.time.LocalTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The insights jobs (ADR-008): the nightly snapshot and the digest check, per lending tenant. */
@Configuration(proxyBeanMethods = false)
class InsightsJobs {

    static final String SNAPSHOT = "lending.insights-snapshot";
    static final String DIGEST = "lending.insights-digest";

    /** After midnight, once the business date has turned: yesterday's positions are final. */
    @Bean
    RecurringTask<Void> insightsSnapshot(TenantJobs tenantJobs, SnapshotService snapshots) {
        return Tasks.recurring(SNAPSHOT, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(0, 45)))
                .execute((instance, context) ->
                        tenantJobs.forEachActiveTenantWithModule(SNAPSHOT, "lending", tenantId -> snapshots.nightly()));
    }

    /** Every 15 minutes: a tenant whose hour has come and whose digest has not gone today gets it. */
    @Bean
    RecurringTask<Void> insightsDigest(TenantJobs tenantJobs, DigestService digest) {
        return Tasks.recurring(DIGEST, Schedules.fixedDelay(Duration.ofMinutes(15)))
                .execute((instance, context) ->
                        tenantJobs.forEachActiveTenantWithModule(DIGEST, "lending", tenantId -> digest.sendIfDue()));
    }
}
