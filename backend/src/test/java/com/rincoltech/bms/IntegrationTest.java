package com.rincoltech.bms;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Base for integration tests (*IT, run by Failsafe): the full application on a random port,
 * connected as bms_app to the shared {@link TestDatabase}. Every subclass shares one cached
 * application context.
 *
 * <p>No test context schedules the report runner. Every cached context in the test JVM shares one database, one
 * {@code scheduled_tasks} row for {@code core.reporting.report-runner} and one {@code report_runs} table, so a runner
 * ticking in a context that is not the one under test claimed the runs of another context's test (failing them as
 * {@code report_unavailable} when it did not know the report) and swept runs whose heartbeat the other context's clock
 * had written, failing them as {@code interrupted}. The report tests that need a runner call
 * {@code ReportRunner.tick} themselves.
 *
 * <p>Nor does any schedule the nightly arrears run ({@code lending.loan-arrears}): a tick at a quarter hour would claim
 * a business date, write {@code lending_arrears_runs} rows and charge penalties on the loans of whatever test was
 * running. The lending tests that need a run call {@code ArrearsRun} themselves.
 *
 * <p>Nor the plan changes ({@code core.platform.plan-scheduled-changes}, ADR-073): a tick would end the subscription or
 * stop a module of whatever test's business had one due. The tests that need it call {@code PlanService.applyDue}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "bms.reports.runner-enabled=false",
            "bms.lending.arrears-job-enabled=false",
            "bms.platform.plan-changes-job-enabled=false"
        })
@AutoConfigureTestRestTemplate
public abstract class IntegrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", () -> "bms_app");
        registry.add("spring.datasource.password", () -> TestDatabase.APP_PASSWORD);
    }
}
