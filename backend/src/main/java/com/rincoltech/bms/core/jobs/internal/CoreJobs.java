package com.rincoltech.bms.core.jobs.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Core recurring tasks. db-scheduler's Spring Boot starter registers every task bean; the
 * {@code scheduled_tasks} table (migration V1) holds their state, and row locks make each run
 * happen on exactly one instance.
 */
@Configuration(proxyBeanMethods = false)
class CoreJobs {

    static final String IDEMPOTENCY_KEY_PURGE = "core.idempotency-key-purge";

    /** Keys are kept 7 days (chapter 7 section 7.8) and purged nightly. */
    @Bean
    RecurringTask<Void> idempotencyKeyPurge(TenantJobs tenantJobs, JdbcClient jdbc) {
        return Tasks.recurring(IDEMPOTENCY_KEY_PURGE, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(2, 30)))
                .execute((instance, context) -> tenantJobs.forEachActiveTenant(
                        IDEMPOTENCY_KEY_PURGE,
                        tenantId -> jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at < now()")
                                .update()));
    }
}
