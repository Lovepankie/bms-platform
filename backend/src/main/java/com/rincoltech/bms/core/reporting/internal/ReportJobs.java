package com.rincoltech.bms.core.reporting.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The report jobs (ADR-008, ADR-038): the runner every 15 seconds and the nightly expiry of finished files. The
 * runner is switched off by {@code bms.reports.runner-enabled=false}, which the integration tests set so that they
 * drive {@link ReportRunner#tick} themselves and a scheduler tick cannot race them; it is on everywhere else.
 */
@Configuration(proxyBeanMethods = false)
class ReportJobs {

    static final String RUNNER = "core.reporting.report-runner";
    static final String EXPIRY = "core.reporting.report-expiry";
    static final String WEEKLY = "core.reporting.data-export-weekly";

    private static final Logger log = LoggerFactory.getLogger(ReportJobs.class);

    @Bean
    @ConditionalOnProperty(name = "bms.reports.runner-enabled", havingValue = "true", matchIfMissing = true)
    RecurringTask<Void> reportRunnerTask(ReportRunner runner, ReportsProperties limits) {
        sweepSpool(limits.spoolDir);
        return Tasks.recurring(RUNNER, Schedules.fixedDelay(Duration.ofSeconds(15)))
                .execute((instance, context) -> runner.tick());
    }

    /**
     * The weekly copy of each business's data (ADR-074), every 15 minutes: the week's workbook is queued once from 02:00 on
     * Monday and emailed once it is ready. Switched off by {@code bms.data-export.weekly-enabled=false}, which the integration
     * tests set so that they drive {@link DataExportWeekly#tick} themselves.
     */
    @Bean
    @ConditionalOnProperty(name = "bms.data-export.weekly-enabled", havingValue = "true", matchIfMissing = true)
    RecurringTask<Void> dataExportWeeklyTask(DataExportWeekly weekly) {
        return Tasks.recurring(WEEKLY, Schedules.fixedDelay(Duration.ofMinutes(15)))
                .execute((instance, context) -> weekly.tick());
    }

    /** After the runner's own job: the files that passed their 30 days go, tenant by tenant. */
    @Bean
    RecurringTask<Void> reportExpiryTask(TenantJobs tenantJobs, ReportExpiry expiry, JdbcClient jdbc) {
        return Tasks.recurring(EXPIRY, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(3, 30)))
                .execute((instance, context) -> {
                    // Not forEachActiveTenant: that wraps each tenant in one transaction, and the expiry calls storage.
                    int failures = 0;
                    for (java.util.UUID tenantId : jdbc.sql("SELECT app_list_active_tenants()")
                            .query(java.util.UUID.class)
                            .list()) {
                        try {
                            tenantJobs.callAs(tenantId, expiry::expireDue);
                        } catch (RuntimeException e) {
                            failures++;
                            log.error("report file expiry failed for tenant {}", tenantId, e);
                        }
                    }
                    if (failures > 0) {
                        throw new IllegalStateException("report file expiry failed for " + failures + " tenant(s)");
                    }
                });
    }

    /**
     * At start, removes spool directories a previous process left behind (it died mid-run). Nothing in the
     * directory outlives its run, and no run survives a restart (the runner fails it as interrupted).
     */
    static void sweepSpool(Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var children = Files.list(dir)) {
            children.forEach(child -> {
                try (var walk = Files.walk(child)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> p.toFile().delete());
                } catch (IOException | RuntimeException e) {
                    log.warn("could not remove the leftover report spool {}", child.getFileName());
                }
            });
        } catch (IOException e) {
            log.warn("could not list the report spool directory");
        }
    }
}
