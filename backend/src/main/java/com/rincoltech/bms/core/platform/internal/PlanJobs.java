package com.rincoltech.bms.core.platform.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class PlanJobs {

    private static final Logger log = LoggerFactory.getLogger(PlanJobs.class);

    static final String SCHEDULED_CHANGES = "core.platform.plan-scheduled-changes";

    /**
     * ADR-073: every quarter hour, each business with a module stop or a cancellation whose day has come in its own time zone
     * gets it applied ({@link PlanService#applyDue}), one tenant at a time and each in a transaction bound to it, so the
     * change lands at the first tick after that business's midnight. Applying is idempotent: a change is applied once, under
     * its row lock. One tenant's failure is logged and does not stop the others. Switched off by
     * {@code bms.platform.plan-changes-job-enabled=false}, which the integration tests set; they call {@code applyDue}
     * themselves. On everywhere else.
     */
    @Bean
    @ConditionalOnProperty(name = "bms.platform.plan-changes-job-enabled", havingValue = "true", matchIfMissing = true)
    RecurringTask<Void> planScheduledChanges(PlanService plans, JdbcClient jdbc, BusinessClock clock) {
        return Tasks.recurring(SCHEDULED_CHANGES, Schedules.cron("0 5/15 * * * *", BusinessClock.DEFAULT_ZONE))
                .execute((instance, context) -> {
                    // The latest business date anywhere: UTC+14 is the furthest ahead.
                    LocalDate through = clock.today(ZoneOffset.ofHours(14));
                    List<UUID> tenants = jdbc.sql("SELECT tenant_id FROM platform_tenants_with_due_changes(?)")
                            .param(through)
                            .query(UUID.class)
                            .list();
                    int failures = 0;
                    for (UUID tenantId : tenants) {
                        try {
                            TenantContext.callAs(tenantId, plans::applyDue);
                        } catch (RuntimeException e) {
                            failures++;
                            log.error("job {} failed for tenant {}", SCHEDULED_CHANGES, tenantId, e);
                        }
                    }
                    if (failures > 0) {
                        throw new IllegalStateException(
                                "job " + SCHEDULED_CHANGES + " failed for " + failures + " tenant(s)");
                    }
                });
    }
}
