package com.rincoltech.bms.core.identity.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.LocalTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The nightly retention of {@code sign_in_address_failures} (ADR-059, migration V78): a row nobody has failed on for
 * {@link SignInAddressFailures#FORGET_AFTER}, and that holds no lock, is deleted by the definer function
 * {@code sign_in_address_failures_purge}, so a spray of made-up addresses does not grow the table for ever.
 */
@Configuration(proxyBeanMethods = false)
class SignInFailureJobs {

    static final String PURGE = "core.identity.sign-in-failure-purge";

    @Bean
    RecurringTask<Void> signInFailurePurge(SignInAddressFailures failures, PlatformTransactionManager transactions) {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        return Tasks.recurring(PURGE, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(3, 35)))
                .execute((instance, context) -> transaction.execute(status -> failures.purge()));
    }
}
