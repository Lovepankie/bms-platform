package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Issue #39: the orphan sweep is registered as the recurring task {@code core.documents.orphan-sweep} by default, and
 * {@code bms.documents.orphan-sweep-enabled=false} removes it.
 */
class DocumentJobsTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DocumentJobs.class)
            .withBean(OrphanSweep.class, () -> mock(OrphanSweep.class))
            .withBean(UnclaimedSweep.class, () -> mock(UnclaimedSweep.class));

    @Test
    void theSweepIsARecurringTaskByDefault() {
        context.run(c -> {
            assertThat(c).hasBean("documentOrphanSweepTask");
            RecurringTask<?> task = c.getBean("documentOrphanSweepTask", RecurringTask.class);
            assertThat(task.getName()).isEqualTo(OrphanSweep.JOB);
        });
    }

    @Test
    void theUnclaimedSweepIsARecurringTaskByDefaultAndItsSwitchRemovesIt() {
        context.run(c -> assertThat(c.getBean("documentUnclaimedSweepTask", RecurringTask.class)
                        .getName())
                .isEqualTo(UnclaimedSweep.JOB));
        context.withPropertyValues("bms.documents.unclaimed-sweep-enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean("documentUnclaimedSweepTask"));
    }

    @Test
    void theSwitchRemovesTheTask() {
        context.withPropertyValues("bms.documents.orphan-sweep-enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean("documentOrphanSweepTask"));
    }
}
