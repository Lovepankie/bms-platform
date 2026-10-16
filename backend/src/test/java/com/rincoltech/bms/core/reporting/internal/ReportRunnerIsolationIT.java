package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * The default test context does not schedule the report runner. Every cached context of the test JVM shares one
 * database and one {@code scheduled_tasks} row for {@code core.reporting.report-runner}, so a runner ticking in a context
 * that is not under test claimed the queued runs of the report tests (failing them as {@code report_unavailable} when it
 * did not know the report, or finishing them before the test looked) and swept runs whose heartbeat another context's
 * clock had written (failing them as {@code interrupted}). {@link IntegrationTest} switches it off for every context;
 * the report tests call {@link ReportRunner#tick} themselves. This class adds no configuration, so it asserts on the same
 * context that every other plain integration test uses.
 */
class ReportRunnerIsolationIT extends IntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void noTestContextSchedulesTheReportRunner() {
        assertThat(context.containsBean("reportRunnerTask"))
                .as("the recurring report-runner task is not registered in a test context")
                .isFalse();
        assertThat(context.containsBean("reportExpiryTask"))
                .as("the other report job is untouched")
                .isTrue();
    }
}
