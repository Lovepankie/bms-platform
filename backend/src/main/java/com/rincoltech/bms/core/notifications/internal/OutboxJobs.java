package com.rincoltech.bms.core.notifications.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Duration;
import java.time.LocalTime;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The outbox sender (every 30 seconds, at most 50 rows a run), the SMS sender beside it (200 rows, a
 * 4 minute budget) and the nightly purge (ADR-008):
 * rows sent more than 30 days ago are deleted, and any unsent row older than the longest one-time
 * link lifetime (7 days) loses its parameters and is marked failed, so no link outlives its use in
 * the outbox (review N1). Platform work: no tenant is bound.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NotificationProperties.class, SmsProperties.class, PushProperties.class})
class OutboxJobs {

    static final String SENDER = "core.notifications.outbox-sender";
    static final String SMS_SENDER = "core.notifications.sms-sender";
    static final String PUSH_SENDER = "core.notifications.push-sender";
    static final String PURGE = "core.notifications.outbox-purge";
    static final String EXPIRED_LINKS = "core.notifications.outbox-expired-links";
    static final int BATCH = 50;
    /**
     * SMS gets its own run (spec section 4.6): a larger batch, and a time budget so a provider outage at
     * 15 seconds a message cannot hold one run for long. 200 rows in 4 minutes at most; the
     * throughput test in {@code SmsChannelIT} measures the dispatcher with the fake provider.
     */
    static final int SMS_BATCH = 200;

    static final Duration SMS_BUDGET = Duration.ofMinutes(4);
    /** The longest lifetime of a link an outbox row can carry: the applicant link, 7 days. */
    static final Duration LINK_LIFETIME = Duration.ofDays(7);

    @Bean
    RecurringTask<Void> outboxSender(OutboxDispatcher dispatcher) {
        return Tasks.recurring(SENDER, Schedules.fixedDelay(Duration.ofSeconds(30)))
                .execute((instance, context) -> dispatcher.dispatchExcept(Outbox.SMS, BATCH));
    }

    @Bean
    RecurringTask<Void> smsSender(OutboxDispatcher dispatcher) {
        return Tasks.recurring(SMS_SENDER, Schedules.fixedDelay(Duration.ofSeconds(30)))
                .execute((instance, context) -> dispatcher.dispatchOnly(Outbox.SMS, SMS_BATCH, SMS_BUDGET));
    }

    /** Push is read on a lock screen: its own run every 10 seconds, so a notice does not wait for the 30 second sender. */
    @Bean
    RecurringTask<Void> pushSender(OutboxDispatcher dispatcher) {
        return Tasks.recurring(PUSH_SENDER, Schedules.fixedDelay(Duration.ofSeconds(10)))
                .execute((instance, context) -> dispatcher.dispatchOnly(Outbox.PUSH, BATCH, Duration.ofSeconds(8)));
    }

    /** Every 5 minutes: an expired link leaves the row's parameters (FR-IAM-15, ADR-042). */
    @Bean
    RecurringTask<Void> outboxExpiredLinks(OutboxDispatcher dispatcher) {
        return Tasks.recurring(EXPIRED_LINKS, Schedules.fixedDelay(Duration.ofMinutes(5)))
                .execute((instance, context) -> dispatcher.clearExpiredLinks());
    }

    @Bean
    RecurringTask<Void> outboxPurge(OutboxDispatcher dispatcher) {
        return Tasks.recurring(PURGE, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(3, 15)))
                .execute((instance, context) -> dispatcher.purge());
    }
}
