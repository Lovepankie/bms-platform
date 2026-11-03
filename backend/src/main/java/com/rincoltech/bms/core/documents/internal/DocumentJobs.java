package com.rincoltech.bms.core.documents.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The document jobs (ADR-008), shaped like {@code PaymentJobs}: the daily orphan sweep at 04:10 Kampala time (issue #39).
 *
 * <p>The run is leased by db-scheduler. The instance that picks it marks its {@code scheduled_tasks} row picked and keeps
 * a heartbeat on it while it works, so no second instance can take the same run. If the process dies part way, the
 * heartbeat stops, another instance (or this one after a restart) finds the dead execution and runs it again; the sweep
 * repeats safely because each step is checked against what is still in storage and in {@code documents}.
 *
 * <p>{@code bms.documents.orphan-sweep-enabled=false} removes the task; {@code bms.documents.orphan-sweep-dry-run=true}
 * keeps it scheduled but only logs what it would remove (the first nights after a deploy, or while checking a bucket).
 */
@Configuration(proxyBeanMethods = false)
class DocumentJobs {

    @Bean
    @ConditionalOnProperty(name = "bms.documents.orphan-sweep-enabled", havingValue = "true", matchIfMissing = true)
    RecurringTask<Void> documentOrphanSweepTask(
            OrphanSweep sweep, @Value("${bms.documents.orphan-sweep-dry-run:false}") boolean dryRun) {
        return Tasks.recurring(OrphanSweep.JOB, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(4, 10)))
                .execute((instance, context) -> sweep.sweepAll(dryRun));
    }
}
