package com.rincoltech.bms.lending.advisor.internal;

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
 * The daily brief job (ADR-008, ADR-037). It runs every 15 minutes and does nothing before 06:00 Kampala time, so the brief
 * is made from a morning's figures and a tenant that switches the assistant on during the day gets today's brief at the
 * next tick. The business date is read ONCE per run, from the time the run was due (a run that was queued at 23:50 and
 * served at 00:10 is still the earlier date's), and is handed to every tenant. A tenant with the assistant off costs one
 * settings read and no call; one tenant's failure is logged and does not stop the others.
 */
@Configuration(proxyBeanMethods = false)
class AdvisorJobs {

    static final String DAILY_BRIEF = "lending.advisor-daily-brief";

    /** The brief is not made before this hour on the Africa/Kampala clock. */
    static final LocalTime EARLIEST = LocalTime.of(6, 0);

    private static final Logger log = LoggerFactory.getLogger(AdvisorJobs.class);

    /** The Africa/Kampala business date a run due at {@code dueAt} is for, or null when it is before {@link #EARLIEST}. */
    static LocalDate businessDateFor(Instant dueAt) {
        if (LocalTime.ofInstant(dueAt, BusinessClock.DEFAULT_ZONE).isBefore(EARLIEST)) {
            return null;
        }
        return LocalDate.ofInstant(dueAt, BusinessClock.DEFAULT_ZONE);
    }

    @Bean
    RecurringTask<Void> advisorDailyBrief(TenantJobs tenantJobs, DailyBriefService briefs, JdbcClient jdbc) {
        return Tasks.recurring(DAILY_BRIEF, Schedules.fixedDelay(Duration.ofMinutes(15)))
                .execute((instance, context) ->
                        runFor(context.getExecution().executionTime, () -> lendingTenants(jdbc), tenantJobs, briefs));
    }

    static List<UUID> lendingTenants(JdbcClient jdbc) {
        return jdbc.sql("SELECT app_list_active_tenants_with_module(?)")
                .param("lending")
                .query(UUID.class)
                .list();
    }

    /**
     * One run, for the date its due time falls on, over the tenants the lister names. Package visible so a test can drive
     * it with a chosen due time and its own tenant.
     */
    static void runFor(Instant dueAt, Supplier<List<UUID>> tenants, TenantJobs tenantJobs, DailyBriefService briefs) {
        LocalDate date = businessDateFor(dueAt);
        if (date == null) {
            return;
        }
        int failures = 0;
        // Not forEachActiveTenant: that wraps each tenant in one transaction and the gateway must run outside one.
        for (UUID tenantId : tenants.get()) {
            try {
                tenantJobs.callAs(tenantId, () -> briefs.generate(date));
            } catch (RuntimeException e) {
                failures++;
                log.error(
                        "daily brief failed for tenant {} error={}",
                        tenantId,
                        e.getClass().getSimpleName());
            }
        }
        if (failures > 0) {
            throw new IllegalStateException("daily brief failed for " + failures + " tenant(s)");
        }
    }
}
