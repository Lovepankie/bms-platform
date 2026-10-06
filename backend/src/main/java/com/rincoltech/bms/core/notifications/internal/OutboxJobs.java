package com.rincoltech.bms.core.notifications.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The outbox sender (every 30 seconds, at most 50 rows a run) and the nightly purge (ADR-008):
 * rows sent more than 30 days ago are deleted, and any unsent row older than the longest one-time
 * link lifetime (7 days) loses its parameters and is marked failed, so no link outlives its use in
 * the outbox (review N1). Platform work: no tenant is bound.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
class OutboxJobs {

    static final String SENDER = "core.notifications.outbox-sender";
    static final String PURGE = "core.notifications.outbox-purge";
    static final int BATCH = 50;
    /** The longest lifetime of a link an outbox row can carry: the applicant link, 7 days. */
    static final Duration LINK_LIFETIME = Duration.ofDays(7);

    @Bean
    RecurringTask<Void> outboxSender(OutboxDispatcher dispatcher) {
        return Tasks.recurring(SENDER, Schedules.fixedDelay(Duration.ofSeconds(30)))
                .execute((instance, context) -> dispatcher.dispatch(BATCH));
    }

    @Bean
    RecurringTask<Void> outboxPurge(OutboxDispatcher dispatcher) {
        return Tasks.recurring(PURGE, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(3, 15)))
                .execute((instance, context) -> dispatcher.purge());
    }
}
