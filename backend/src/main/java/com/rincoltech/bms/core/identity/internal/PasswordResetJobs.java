package com.rincoltech.bms.core.identity.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.kernel.BusinessClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The nightly retention of {@code user_password_resets} (FR-IAM-15, ADR-042): a link is useless an hour after it was made; the
 * row is kept {@link #RETENTION} for the audit trail and then deleted, for every tenant at once, suspended and closed ones
 * included. One database function does it (migration V62, {@code user_password_resets_purge}): a job has no tenant bound, so a
 * plain DELETE by the application role would see no row, and the role holds no DELETE grant on the table.
 */
@Configuration(proxyBeanMethods = false)
class PasswordResetJobs {

    static final String PURGE = "core.identity.password-reset-purge";
    static final Duration RETENTION = Duration.ofDays(30);

    @Bean
    RecurringTask<Void> passwordResetPurge(PasswordResetPurge purge) {
        return Tasks.recurring(PURGE, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(3, 25)))
                .execute((instance, context) -> purge.run());
    }

    @Component
    static class PasswordResetPurge {

        private final JdbcClient jdbc;
        private final TransactionTemplate transactions;
        private final BusinessClock clock;

        PasswordResetPurge(JdbcClient jdbc, PlatformTransactionManager transactionManager, BusinessClock clock) {
            this.jdbc = jdbc;
            this.transactions = new TransactionTemplate(transactionManager);
            this.clock = clock;
        }

        /** Returns the rows deleted. */
        int run() {
            return transactions.execute(status -> jdbc.sql("SELECT user_password_resets_purge(?)")
                    .param(Timestamp.from(clock.now().minus(RETENTION)))
                    .query(Integer.class)
                    .single());
        }
    }
}
