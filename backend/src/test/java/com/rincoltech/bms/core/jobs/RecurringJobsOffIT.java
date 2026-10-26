package com.rincoltech.bms.core.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.rincoltech.bms.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * No recurring job runs in a test context unless the test turns the scheduler on (issue #530). The test profile
 * switches db-scheduler off, so a recurring task added later cannot tick on the wall clock across other tests'
 * tenants (#487), whether or not it has its own enabled switch. The task beans stay, so tests can still call them.
 */
class RecurringJobsOffIT extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theTestProfileStartsNoSchedulerSoNoRecurringTaskRuns() {
        assertThat(context.getBeansOfType(RecurringTask.class))
                .as("the recurring tasks are registered, so this guard covers real tasks")
                .isNotEmpty();
        assertThat(context.getBeansOfType(Scheduler.class))
                .as("no db-scheduler runs in the shared test context; a test that needs one sets"
                        + " db-scheduler.enabled=true itself")
                .isEmpty();
    }
}
