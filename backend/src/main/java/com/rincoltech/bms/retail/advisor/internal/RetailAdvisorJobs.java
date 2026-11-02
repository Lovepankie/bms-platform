package com.rincoltech.bms.retail.advisor.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The daily retail brief job (issue #422), on the lending brief's rules (ADR-008, ADR-037): every 15 minutes, nothing before
 * 06:00 Kampala time, the business date read once per run from the time the run was due, every tenant with the retail module in
 * turn, outside a transaction, one tenant's failure logged without stopping the others. A tenant with the assistant off costs one
 * settings read and no call.
 */
@Configuration(proxyBeanMethods = false)
class RetailAdvisorJobs {

    static final String DAILY_BRIEF = "retail.advisor-daily-brief";
    static final LocalTime EARLIEST = LocalTime.of(6, 0);

    private static final Logger log = LoggerFactory.getLogger(RetailAdvisorJobs.class);

    static LocalDate businessDateFor(Instant dueAt) {
        if (LocalTime.ofInstant(dueAt, BusinessClock.DEFAULT_ZONE).isBefore(EARLIEST)) {
            return null;
        }
        return LocalDate.ofInstant(dueAt, BusinessClock.DEFAULT_ZONE);
    }

    @Bean
    RecurringTask<Void> retailAdvisorDailyBrief(
            TenantJobs tenantJobs, RetailDailyBriefService briefs, JdbcClient jdbc) {
        return Tasks.recurring(DAILY_BRIEF, Schedules.fixedDelay(Duration.ofMinutes(15)))
                .execute((instance, context) ->
                        runFor(context.getExecution().executionTime, () -> retailTenants(jdbc), tenantJobs, briefs));
    }

    static List<UUID> retailTenants(JdbcClient jdbc) {
        return jdbc.sql("SELECT app_list_active_tenants_with_module(?)")
                .param("retail")
                .query(UUID.class)
                .list();
    }

    static void runFor(
            Instant dueAt, Supplier<List<UUID>> tenants, TenantJobs tenantJobs, RetailDailyBriefService briefs) {
        LocalDate date = businessDateFor(dueAt);
        if (date == null) {
            return;
        }
        int failures = 0;
        for (UUID tenantId : tenants.get()) {
            try {
                tenantJobs.callAs(tenantId, () -> briefs.generate(date));
            } catch (RuntimeException e) {
                failures++;
                log.error(
                        "retail brief failed for tenant {} error={}",
                        tenantId,
                        e.getClass().getSimpleName());
            }
        }
        if (failures > 0) {
            throw new IllegalStateException("retail brief failed for " + failures + " tenant(s)");
        }
    }
}
