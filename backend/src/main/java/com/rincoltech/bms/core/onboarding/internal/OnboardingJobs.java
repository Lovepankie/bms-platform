package com.rincoltech.bms.core.onboarding.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalTime;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Nightly application housekeeping (ADR-008, spec sections 4 and 12): an application whose email
 * is not confirmed within 14 days expires, and rejected and expired applications are deleted 90
 * days after they closed. Platform work: no tenant is bound.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OnboardingProperties.class)
class OnboardingJobs {

    static final String HOUSEKEEPING = "core.onboarding.application-housekeeping";

    @Bean
    RecurringTask<Void> applicationHousekeeping(ApplicationService applications) {
        return Tasks.recurring(HOUSEKEEPING, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(2, 45)))
                .execute((instance, context) -> applications.housekeeping());
    }
}
