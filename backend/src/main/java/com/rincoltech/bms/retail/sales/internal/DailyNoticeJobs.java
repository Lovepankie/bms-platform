package com.rincoltech.bms.retail.sales.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.notifications.PushPreferences;
import com.rincoltech.bms.kernel.BusinessClock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The daily push notices of retail (issue #412, ADR-057, ADR-008): on every quarter hour, for every active tenant with retail on,
 * {@link DailyNotices} prepares what is due on that tenant's clock (each notice once a day). With push off for the server
 * nothing runs at all.
 */
@Configuration(proxyBeanMethods = false)
class DailyNoticeJobs {

    static final String DAILY_NOTICES = "retail.daily-notices";

    @Bean
    RecurringTask<Void> retailDailyNotices(
            TenantJobs tenantJobs, DailyNotices notices, PushPreferences preferences, BusinessClock clock) {
        // On every quarter hour of the Kampala clock, so a summary set for 20:15 is prepared at 20:15, never skipped by
        // drift.
        return Tasks.recurring(DAILY_NOTICES, Schedules.cron("0 0/15 * * * *", BusinessClock.DEFAULT_ZONE))
                .execute((instance, context) -> {
                    if (!preferences.available()) {
                        return;
                    }
                    var now = clock.now();
                    tenantJobs.forEachActiveTenantWithModule(DAILY_NOTICES, "retail", tenantId -> notices.runAt(now));
                });
    }
}
