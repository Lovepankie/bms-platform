package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.TestDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The runner and the run itself (extras slice D1, issue #214; FR-RPT-04, FR-RPT-08; ADR-038 decisions 1, 2 and 7), on
 * the staging shape of limits: one run at a time, a 2 second timeout, 500 rows a table, a 1 MiB file. Proves that a run
 * reads in one read-only {@code REPEATABLE READ} transaction with the connection's request limits lifted for it; that
 * the platform limit and the one-run-per-tenant rule hold; the timeout, row cap and size cap each end a run with their
 * code; that a failure keeps only a safe code and never a value; that a run left running by a dead process is failed;
 * and that a spreadsheet formula in a name cannot survive into the CSV.
 */
@Import(TestReports.class)
@TestPropertySource(
        properties = {
            "bms.reports.max-concurrent-runs=1",
            "bms.reports.timeout=PT10S",
            "bms.reports.xlsx-row-cap=500",
            "bms.reports.pdf-row-cap=200",
            "bms.reports.max-file-bytes=1048576",
            "bms.reports.cancel-poll=PT0.2S",
            "bms.reports.heartbeat=PT0.1S",
            "bms.reports.runner-enabled=false"
        })
@ExtendWith(OutputCaptureExtension.class)
class ReportRunnerIT extends ReportsItSupport {

    // ---- the snapshot ---------------------------------------------------------------------------

    @Test
    void theQueryPhaseReadsInOneReadOnlyRepeatableReadTransactionWithTheRequestLimitsLifted() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        JsonNode ready = runToTheEnd(t, UUID.randomUUID(), "test.settings", Map.of(), "json");
        assertThat(ready.get("status").asString()).isEqualTo("ready");

        Map<String, String> seen = TestReports.SEEN.get();
        assertThat(seen.get("isolation")).isEqualTo("repeatable read");
        assertThat(seen.get("read_only")).isEqualTo("on");
        // The connection starts with 60 s (a request's limit); the run lifts both to its own remaining time plus a
        // margin (1 s for a statement, 30 s idle), so the application's deadline of the test profile fires before the
        // server's limits do. The window is relative to that deadline, which is generous because the runner is shared.
        long deadline = limits.timeout.toMillis();
        assertThat(millis(seen.get("statement_timeout"))).isBetween(deadline - 500L + 1_000L, deadline + 1_100L);
        assertThat(millis(seen.get("idle"))).isBetween(deadline - 1_500L + 30_000L, deadline + 30_100L);
        // Recorded on the run: when the snapshot was taken.
        assertThat(ready.get("snapshot_at").asString()).isNotEmpty();
    }

    private static long millis(String shown) {
        if (shown.endsWith("ms")) {
            return Long.parseLong(shown.substring(0, shown.length() - 2));
        }
        if (shown.endsWith("min")) {
            return Long.parseLong(shown.substring(0, shown.length() - 3)) * 60_000;
        }
        return Long.parseLong(shown.substring(0, shown.length() - 1)) * 1_000;
    }

    // ---- limits ---------------------------------------------------------------------------------

    @Test
    void aTableOfExactlyTheRowCapIsWrittenAndOneRowMoreEndsTheRunTooLarge() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();

        JsonNode exactly = runToTheEnd(t, user, "test.rows", Map.of("n", 500), "json");
        assertThat(exactly.get("status").asString()).isEqualTo("ready");
        assertThat(exactly.get("row_count").asInt()).isEqualTo(500);
        JsonNode file = new ObjectMapper()
                .readTree(
                        download(t, user, PORTFOLIO, exactly.get("document_id").asString()));
        assertThat(file.get("tables").get(0).get("rows")).hasSize(500);

        JsonNode over = runToTheEnd(t, user, "test.rows", Map.of("n", 501), "json");
        assertThat(over.get("status").asString()).isEqualTo("failed");
        assertThat(over.get("error").asString()).isEqualTo("report_too_large");
        assertThat(over.get("document_id").isNull()).isTrue();
        assertThat(spoolLeft(t)).as("the spool is removed").isEmpty();
        // The same in CSV, where the file is built from the same spool.
        JsonNode csv = runToTheEnd(t, user, "test.rows", Map.of("n", 500), "csv");
        assertThat(csv.get("status").asString()).isEqualTo("ready");
    }

    @Test
    void aFileOverTheSizeCapEndsTheRunTooLargeWhateverTheRowCount() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        JsonNode over = runToTheEnd(t, UUID.randomUUID(), "test.wide", Map.of(), "json");
        assertThat(over.get("status").asString()).isEqualTo("failed");
        assertThat(over.get("error").asString()).isEqualTo("report_too_large");
        assertThat(spoolLeft(t)).isEmpty();
    }

    @Test
    void aRunThatTakesLongerThanTheTimeoutEndsWithTimeout() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        long start = System.nanoTime();
        JsonNode timedOut = runToTheEnd(t, UUID.randomUUID(), "test.slow", Map.of(), "json");
        assertThat(timedOut.get("status").asString()).isEqualTo("failed");
        assertThat(timedOut.get("error").asString()).isEqualTo("timeout");
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("it stopped soon after the limit")
                .isLessThan(limits.timeout.plusSeconds(20));
        assertThat(spoolLeft(t)).isEmpty();
        // The tenant is free again: the next run is not held by the failed one.
        assertThat(runToTheEnd(t, UUID.randomUUID(), "test.rows", Map.of("n", 1), "json")
                        .get("status")
                        .asString())
                .isEqualTo("ready");
    }

    // ---- concurrency ----------------------------------------------------------------------------

    @Test
    void onlyTheConfiguredNumberOfRunsExecuteAtOnceAcrossTenants() throws Exception {
        TestDatabase.Fixture a = tenant(true);
        TestDatabase.Fixture b = tenant(true);
        UUID ua = UUID.randomUUID();
        UUID ub = UUID.randomUUID();
        TestReports.gate = new CountDownLatch(1);
        // A asked a minute before B (the clock is ours), so A is the oldest and starts first.
        clock.set(TestReports.START.minusSeconds(60));
        String idA = post(a, ua, PORTFOLIO, "test.gate", Map.of(), "json")
                .getBody()
                .get("id")
                .asString();
        clock.set(TestReports.START);
        String idB = post(b, ub, PORTFOLIO, "test.rows", Map.of("n", 2), "json")
                .getBody()
                .get("id")
                .asString();

        assertThat(runner.tick()).as("one place, one run started").isEqualTo(1);
        assertThat(TestReports.gateEntered.await(IDLE.toSeconds(), TimeUnit.SECONDS))
                .isTrue();
        assertThat(runner.tick()).as("the platform is full").isZero();
        assertThat(count("running", a, b)).isEqualTo(1);
        assertThat(count("queued", a, b)).isEqualTo(1);
        assertThat(run(a, ua, PORTFOLIO, idA).get("status").asString()).isEqualTo("running");
        assertThat(run(b, ub, PORTFOLIO, idB).get("status").asString()).isEqualTo("queued");

        TestReports.gate.countDown();
        assertThat(runner.awaitIdle(IDLE)).isTrue();
        assertThat(runner.tick()).as("a place is free again").isEqualTo(1);
        assertThat(runner.awaitIdle(IDLE)).isTrue();
        assertThat(run(a, ua, PORTFOLIO, idA).get("status").asString()).isEqualTo("ready");
        assertThat(run(b, ub, PORTFOLIO, idB).get("status").asString()).isEqualTo("ready");
    }

    @Test
    void theOldestRequestRunsFirst() throws Exception {
        TestDatabase.Fixture a = tenant(true);
        TestDatabase.Fixture b = tenant(true);
        UUID ua = UUID.randomUUID();
        UUID ub = UUID.randomUUID();
        String later = post(a, ua, PORTFOLIO, "test.rows", Map.of("n", 1), "json")
                .getBody()
                .get("id")
                .asString();
        // Asked a second later (the clock is ours), in the other tenant.
        clock.set(TestReports.START.minusSeconds(60));
        String earlier = post(b, ub, PORTFOLIO, "test.rows", Map.of("n", 1), "json")
                .getBody()
                .get("id")
                .asString();
        clock.set(TestReports.START);

        runner.tick();
        assertThat(runner.awaitIdle(IDLE)).isTrue();
        assertThat(run(b, ub, PORTFOLIO, earlier).get("status").asString()).isEqualTo("ready");
        assertThat(run(a, ua, PORTFOLIO, later).get("status").asString()).isEqualTo("queued");
    }

    @Test
    void aTenantNeverHasTwoRunsRunningAndAClaimSkipsItWhileOneIs() {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        var owner = TestDatabase.owner();
        UUID running = insertRun(t, user, "running");
        UUID queued = insertRun(t, user, "queued");

        // The database refuses a second running run in the tenant (migration V49, report_runs_one_running).
        assertThatThrownBy(() -> insertRun(t, user, "running")).hasMessageContaining("report_runs_one_running");
        // And a claim finds nothing to start while one is running.
        assertThat(tenantJobs.callAs(t.tenantId(), () -> store.claimNext(TestReports.START, "test/1")))
                .isEmpty();
        assertThat(owner.sql("SELECT status FROM report_runs WHERE id = ?")
                        .param(queued)
                        .query(String.class)
                        .single())
                .isEqualTo("queued");
        owner.sql("UPDATE report_runs SET status = 'cancelled', finished_at = now() WHERE id = ?")
                .param(running)
                .update();
        assertThat(tenantJobs
                        .callAs(t.tenantId(), () -> store.claimNext(TestReports.START, "test/1"))
                        .map(RunStore.Run::id))
                .contains(queued);
    }

    @Test
    void aRunLeftRunningByADeadProcessIsFailedAsInterruptedAndTheTenantIsFreed() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        UUID stale = insertRun(t, user, "running");
        // It started an hour ago by the clock: past the 2 second timeout and the two minutes of grace.
        TestDatabase.owner()
                .sql("UPDATE report_runs SET started_at = ? WHERE id = ?")
                .params(java.sql.Timestamp.from(TestReports.START.minus(Duration.ofHours(1))), stale)
                .update();
        UUID next = insertRun(t, user, "queued");

        runner.tick();
        assertThat(runner.awaitIdle(IDLE)).isTrue();
        assertThat(TestDatabase.owner()
                        .sql("SELECT status || ':' || coalesce(error, '') FROM report_runs WHERE id = ?")
                        .param(stale)
                        .query(String.class)
                        .single())
                .isEqualTo("failed:interrupted");
        // The freed tenant's queued run is started by the next tick (the old one was failed in this one).
        drain();
        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM report_runs WHERE id = ?")
                        .param(next)
                        .query(String.class)
                        .single())
                .isIn("ready", "failed");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*)::int FROM audit_log WHERE tenant_id = ? AND action = 'core.reports.run_failed'")
                        .param(t.tenantId())
                        .query(Integer.class)
                        .single())
                .isGreaterThanOrEqualTo(1);
    }

    // ---- guards before a run starts ---------------------------------------------------------------

    @Test
    void aRunWhoseTenantSwitchedReportsOffBeforeItStartedFailsWithReportsDisabled() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        String id = post(t, user, PORTFOLIO, "test.rows", Map.of(), "json")
                .getBody()
                .get("id")
                .asString();
        TestDatabase.owner()
                .sql(
                        "UPDATE tenant_settings SET settings = settings || '{\"reports_enabled\": false}' WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        drain();
        assertThat(TestDatabase.owner()
                        .sql("SELECT error FROM report_runs WHERE id = ?")
                        .param(UUID.fromString(id))
                        .query(String.class)
                        .single())
                .isEqualTo("reports_disabled");
    }

    @Test
    void aRunOfARequesterWhoWasDeactivatedInTheMeantimeDoesNotRun() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, phone_e164, status) VALUES (?, ?, 'staff', 'Test Staff 77', '+256700000077', 'active')")
                .params(user, t.tenantId())
                .update();
        String id = post(t, user, PORTFOLIO, "test.rows", Map.of(), "json")
                .getBody()
                .get("id")
                .asString();
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'deactivated' WHERE id = ?")
                .param(user)
                .update();
        drain();
        assertThat(TestDatabase.owner()
                        .sql("SELECT error FROM report_runs WHERE id = ?")
                        .param(UUID.fromString(id))
                        .query(String.class)
                        .single())
                .isEqualTo("requester_deactivated");
    }

    // ---- failure and files ----------------------------------------------------------------------

    @Test
    void aFailureKeepsASafeCodeAndNoValueReachesTheRowTheAuditOrTheLog(CapturedOutput log) throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        JsonNode failed = runToTheEnd(t, user, "test.boom", Map.of(), "json");

        assertThat(failed.get("status").asString()).isEqualTo("failed");
        assertThat(failed.get("error").asString()).isEqualTo("run_failed");
        assertThat(failed.toString()).doesNotContain("256700000041").doesNotContain("Test Borrower 41");
        String audit = TestDatabase.owner()
                .sql("SELECT string_agg(action || ' ' || data::text, E'\\n') FROM audit_log WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(String.class)
                .single();
        assertThat(audit)
                .contains("core.reports.run_failed")
                .doesNotContain("256700000041")
                .doesNotContain("Test Borrower 41");
        assertThat(log.getAll())
                .contains("failed: java.lang.IllegalStateException")
                .doesNotContain("256700000041")
                .doesNotContain("Test Borrower 41");
        assertThat(spoolLeft(t)).isEmpty();
    }

    @Test
    void textThatASpreadsheetWouldRunIsPrefixedInTheCsvAndQuotedWhereNeeded() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        JsonNode ready = runToTheEnd(t, user, "test.evil", Map.of(), "csv");
        assertThat(ready.get("status").asString()).isEqualTo("ready");

        String csv = download(t, user, PORTFOLIO, ready.get("document_id").asString());
        String table = csv.substring(csv.indexOf("Names\r\n"));
        assertThat(table)
                .contains("\"'=HYPERLINK(\"\"http://example.invalid\"\")\",-500\r\n")
                .contains("'+1+1,0\r\n")
                .contains("'-2,7\r\n")
                .contains("'@SUM(A1),8\r\n")
                .contains("\"Test, Borrower \"\"Quoted\"\"\nSecond line\",9\r\n")
                .contains(",\r\n");
        assertThat(table).as("a negative amount is a number, not text").doesNotContain("'-500");
        // The same report as JSON keeps the text exactly, as data (no executable meaning in JSON).
        JsonNode json = new ObjectMapper()
                .readTree(download(
                        t,
                        user,
                        PORTFOLIO,
                        runToTheEnd(t, user, "test.evil", Map.of(), "json")
                                .get("document_id")
                                .asString()));
        assertThat(json.get("tables").get(0).get("rows").get(0).get(0).asString())
                .startsWith("=HYPERLINK(");
    }

    @Test
    void theReportAuditNamesTheRunAndItsParametersButNeverARow() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        JsonNode ready = runToTheEnd(t, user, "test.rows", Map.of("n", 3), "json");
        List<Map<String, Object>> rows = TestDatabase.owner()
                .sql("SELECT action, actor_user_id, actor_kind, data::text AS data FROM audit_log WHERE tenant_id = ?"
                        + " AND entity_id = ? ORDER BY created_at, action")
                .params(t.tenantId(), UUID.fromString(ready.get("id").asString()))
                .query()
                .listOfRows();
        assertThat(rows).extracting(r -> r.get("action")).contains("core.reports.run", "core.reports.run_finished");
        Map<String, Object> requested = rows.stream()
                .filter(r -> r.get("action").equals("core.reports.run"))
                .findFirst()
                .orElseThrow();
        assertThat(requested.get("actor_user_id")).isEqualTo(user);
        assertThat(requested.get("data").toString()).contains("test.rows").contains("\"n\"");
        Map<String, Object> finished = rows.stream()
                .filter(r -> r.get("action").equals("core.reports.run_finished"))
                .findFirst()
                .orElseThrow();
        assertThat(finished.get("actor_kind")).isEqualTo("system");
        assertThat(finished.get("data").toString()).contains("\"row_count\": 3").doesNotContain("Test Row");
        // The file's creation is audited by the documents module, and so is each download link.
        ResponseEntity<JsonNode> url = call(
                HttpMethod.POST,
                "/api/v1/documents/" + ready.get("document_id").asString() + "/download-url",
                t,
                user,
                PORTFOLIO);
        assertThat(url.getStatusCode().value()).isEqualTo(200);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*)::int FROM audit_log WHERE tenant_id = ? AND action IN ('core.document.generated', 'core.document.download_url_issued')")
                        .param(t.tenantId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    // JUSTIFICATION-A3: new cases for the heartbeat, a restart, a clean stop, the single snapshot and the upload; none
    // existed.
    // ---- who holds a run, and what a restart does to it -------------------------------------------

    @org.springframework.beans.factory.annotation.Autowired
    RunExecutor runExecutor;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    com.rincoltech.bms.kernel.BusinessClock businessClock;

    @org.springframework.beans.factory.annotation.Autowired
    com.rincoltech.bms.core.audit.AuditLog auditLog;

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.transaction.PlatformTransactionManager transactions;

    /** A second runner stands in for another process (or the next one after a restart). */
    private ReportRunner anotherProcess() {
        return new ReportRunner(tenantJobs, store, runExecutor, jdbc, businessClock, limits, auditLog, transactions);
    }

    private void setHeartbeat(UUID run, java.time.Instant at) {
        TestDatabase.owner()
                .sql("UPDATE report_runs SET heartbeat_at = ?, owner = 'other-host/aaaa0001' WHERE id = ?")
                .params(java.sql.Timestamp.from(at), run)
                .update();
    }

    private String statusOf(UUID run) {
        return TestDatabase.owner()
                .sql("SELECT status || ':' || coalesce(error, '') FROM report_runs WHERE id = ?")
                .param(run)
                .query(String.class)
                .single();
    }

    @Test
    void aRunWithAFreshHeartbeatIsLeftAloneHoweverOldItIsAndOneWithoutIsFailed() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        UUID run = insertRun(t, user, "running");
        // Started an hour ago, but its process beat a moment ago: it is working.
        TestDatabase.owner()
                .sql("UPDATE report_runs SET started_at = ? WHERE id = ?")
                .params(java.sql.Timestamp.from(TestReports.START.minus(Duration.ofHours(1))), run)
                .update();
        setHeartbeat(run, TestReports.START);
        runner.tick();
        assertThat(statusOf(run)).isEqualTo("running:");

        // Its process stopped beating two minutes ago (the stale limit is one): it is gone, and the tenant is freed.
        setHeartbeat(run, TestReports.START.minus(Duration.ofMinutes(2)));
        runner.tick();
        assertThat(statusOf(run)).isEqualTo("failed:interrupted");
    }

    @Test
    void aNewProcessNeverFailsALateButLiveRunOfAnotherInstanceAndFailsOneQuietForAMinute() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        // Another instance (another host name: a recreated container has a new one) beat 30 seconds ago, three beats
        // late on a loaded host: it is slow, not dead.
        UUID late = insertRun(t, user, "running");
        setHeartbeat(late, TestReports.START.minusSeconds(30));
        TestDatabase.Fixture u = tenant(true);
        UUID alive = insertRun(u, user, "running");
        setHeartbeat(alive, TestReports.START);

        ReportRunner replacement = anotherProcess();
        try {
            // The first tick of a process that has just started judges exactly as every later tick does.
            replacement.tick();
            assertThat(statusOf(late))
                    .as("30 s without a beat is not a minute: the run of a live peer is left alone")
                    .isEqualTo("running:");
            assertThat(statusOf(alive)).as("a run that is beating is untouched").isEqualTo("running:");
            replacement.tick();
            assertThat(statusOf(late)).isEqualTo("running:");
            // A minute without a beat, and it is gone.
            setHeartbeat(late, TestReports.START.minus(Duration.ofMinutes(2)));
            replacement.tick();
            assertThat(statusOf(late)).isEqualTo("failed:interrupted");
            assertThat(statusOf(alive)).isEqualTo("running:");
        } finally {
            replacement.stop();
        }
    }

    @Test
    void everyProcessHasItsOwnInstanceIdAndAStopClosesOnlyTheRunsOfTheProcessThatStops() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        TestReports.gate = new CountDownLatch(1);
        String id = post(t, user, PORTFOLIO, "test.gate", Map.of(), "json")
                .getBody()
                .get("id")
                .asString();
        ReportRunner holder = anotherProcess();
        ReportRunner bystander = anotherProcess();
        try {
            assertThat(java.util.Set.of(runner.owner(), holder.owner(), bystander.owner()))
                    .as("one instance id per runner, so a sweep or a stop can tell whose run it is")
                    .hasSize(3);
            assertThat(holder.tick()).isEqualTo(1);
            assertThat(TestReports.gateEntered.await(IDLE.toSeconds(), TimeUnit.SECONDS))
                    .isTrue();
            // Another process, in the same JVM and database, starts, ticks and stops while the run is open.
            bystander.tick();
            bystander.stop();
            assertThat(statusOf(UUID.fromString(id)))
                    .as("the run of a live process is not touched by another's stop")
                    .isEqualTo("running:");
            TestReports.gate.countDown();
            assertThat(holder.awaitIdle(IDLE)).isTrue();
            assertThat(statusOf(UUID.fromString(id))).isEqualTo("ready:");
        } finally {
            TestReports.gate.countDown();
            holder.stop();
            bystander.stop();
        }
    }

    @Test
    void theHeartbeatOfARunningRunIsRenewedWhileItWorks() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        TestReports.gate = new CountDownLatch(1);
        String id = post(t, user, PORTFOLIO, "test.gate", Map.of(), "json")
                .getBody()
                .get("id")
                .asString();
        runner.tick();
        assertThat(TestReports.gateEntered.await(IDLE.toSeconds(), TimeUnit.SECONDS))
                .isTrue();
        UUID run = UUID.fromString(id);
        assertThat(TestDatabase.owner()
                        .sql("SELECT owner FROM report_runs WHERE id = ?")
                        .param(run)
                        .query(String.class)
                        .single())
                .isEqualTo(runner.owner());

        java.time.Instant later = TestReports.START.plusSeconds(30);
        clock.set(later);
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        java.time.Instant beat = null;
        while (System.nanoTime() < deadline) {
            beat = TestDatabase.owner()
                    .sql("SELECT heartbeat_at FROM report_runs WHERE id = ?")
                    .param(run)
                    .query(java.sql.Timestamp.class)
                    .single()
                    .toInstant();
            if (beat.equals(later)) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(beat)
                .as("the heartbeat follows the clock while the run works")
                .isEqualTo(later);
        TestReports.gate.countDown();
        assertThat(runner.awaitIdle(IDLE)).isTrue();
    }

    @Test
    void aCleanStopClosesTheRunItWasExecutingAsInterruptedAndFreesTheTenant() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        TestReports.gate = new CountDownLatch(1);
        String id = post(t, user, PORTFOLIO, "test.gate", Map.of(), "json")
                .getBody()
                .get("id")
                .asString();
        ReportRunner process = anotherProcess();
        process.tick();
        assertThat(TestReports.gateEntered.await(IDLE.toSeconds(), TimeUnit.SECONDS))
                .isTrue();
        long start = System.nanoTime();
        process.stop();
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("stop waits briefly, not for the run")
                .isLessThan(Duration.ofSeconds(20));

        assertThat(statusOf(UUID.fromString(id))).isEqualTo("failed:interrupted");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*)::int FROM audit_log WHERE tenant_id = ? AND entity_id = ? AND action = 'core.reports.run_failed'")
                        .params(t.tenantId(), UUID.fromString(id))
                        .query(Integer.class)
                        .single())
                .as("the run closed at stop is audited")
                .isEqualTo(1);
        // Another run of the tenant is not held behind it.
        assertThat(process.tick()).as("a stopped process claims nothing").isZero();
        assertThat(runToTheEnd(t, user, "test.rows", Map.of("n", 1), "json")
                        .get("status")
                        .asString())
                .isEqualTo("ready");
    }

    // ---- one snapshot, and nothing held while the file goes out ----------------------------------------

    @Test
    void twoReadsOfOneRunSeeOneSnapshotWhileAWriteLandsBetweenThem() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        TestReports.snapshotGo = new CountDownLatch(1);
        TestReports.snapshotMid = new CountDownLatch(1);
        ResponseEntity<JsonNode> posted = post(t, user, PORTFOLIO, "test.snapshot", Map.of(), "json");
        long before = TestDatabase.owner()
                .sql("SELECT count(*) FROM branches WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        runner.tick();
        assertThat(TestReports.snapshotMid.await(IDLE.toSeconds(), TimeUnit.SECONDS))
                .isTrue();
        // A write by another session commits between the run's two reads.
        TestDatabase.owner()
                .sql("INSERT INTO branches (id, tenant_id, code, name) VALUES (?, ?, 'BR9', 'Test Branch Nine')")
                .params(UUID.randomUUID(), t.tenantId())
                .update();
        TestReports.snapshotGo.countDown();
        assertThat(runner.awaitIdle(IDLE)).isTrue();

        JsonNode run = run(t, user, PORTFOLIO, posted.getBody().get("id").asString());
        assertThat(run.get("status").asString()).as("%s", run).isEqualTo("ready");
        JsonNode counts = new ObjectMapper()
                .readTree(download(t, user, PORTFOLIO, run.get("document_id").asString()))
                .get("tables")
                .get(0)
                .get("rows")
                .get(0);
        assertThat(counts.get(0).asLong()).isEqualTo(before);
        assertThat(counts.get(1).asLong())
                .as("the second read still sees the first read's world")
                .isEqualTo(before);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM branches WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .as("and the write did land")
                .isEqualTo(before + 1);
    }

    @Test
    void theFileIsUploadedFromDiskWithNoTransactionAndNoConnectionHeld() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        com.rincoltech.bms.core.documents.internal.WatchedStorage.UPLOAD.set("");
        JsonNode ready = runToTheEnd(t, UUID.randomUUID(), "test.fat", Map.of("n", 50), "json");
        assertThat(ready.get("status").asString()).isEqualTo("ready");
        assertThat(ready.get("size_bytes").asLong()).isGreaterThan(50 * 360);
        String seen = com.rincoltech.bms.core.documents.internal.WatchedStorage.UPLOAD.get();
        // On the uploading thread: no transaction is open and no pooled connection is bound to it.
        assertThat(seen).as("the upload hook ran").isEqualTo("tx=false conn=false");
        // The file was taken in blocks, never read as a whole (the test file is a few kilobytes: the block is the
        // limit).
        assertThat(com.rincoltech.bms.core.documents.internal.WatchedStorage.MAX_READ.get())
                .isLessThanOrEqualTo(64 * 1024);
        // And the stored object is the file, whole: the checksum recorded for the document is the object's own.
        assertThat(TestDatabase.owner()
                        .sql("SELECT size_bytes FROM documents WHERE id = ?")
                        .param(UUID.fromString(ready.get("document_id").asString()))
                        .query(Long.class)
                        .single())
                .isEqualTo(ready.get("size_bytes").asLong());
    }

    // ---- the 30 day expiry works in batches --------------------------------------------------------

    @Test
    void theExpiryHandlesMoreRunsThanOneBatchAndMarksEachOnce() {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        int n = ReportExpiry.BATCH * 2 + 20;
        for (int i = 0; i < n; i++) {
            UUID doc = UUID.randomUUID();
            TestDatabase.owner()
                    .sql("""
                            INSERT INTO documents (id, tenant_id, doc_type, subject_type, subject_id, object_key, content_type,
                                                   size_bytes, sha256)
                            VALUES (?, ?, 'report', 'core.report_run', ?, ?, 'application/json', 1, repeat('0', 64))
                            """)
                    .params(
                            doc,
                            t.tenantId(),
                            UUID.randomUUID(),
                            "tenants/" + t.tenantId() + "/report/bulk/" + doc + ".json")
                    .update();
            TestDatabase.owner()
                    .sql("""
                            INSERT INTO report_runs (id, tenant_id, created_at, report_key, params, params_hash, scope, format,
                                                     status, requested_by, as_of, finished_at, document_id, expires_at, row_cap,
                                                     size_cap_bytes)
                            VALUES (?, ?, ?, 'test.rows', '{}', ?, '{}', 'json', 'ready', ?, DATE '2026-09-01', ?, ?, ?, 500, 1048576)
                            """)
                    .params(
                            UUID.randomUUID(),
                            t.tenantId(),
                            java.sql.Timestamp.from(TestReports.START.minus(Duration.ofDays(40))),
                            (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""),
                            user,
                            java.sql.Timestamp.from(TestReports.START.minus(Duration.ofDays(40))),
                            doc,
                            java.sql.Timestamp.from(TestReports.START.minus(Duration.ofDays(10))))
                    .update();
        }
        assertThat(tenantJobs.callAs(t.tenantId(), expiry::expireDue)).isEqualTo(n);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*)::int FROM report_runs WHERE tenant_id = ? AND files_deleted_at IS NOT NULL")
                        .param(t.tenantId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(n);
        assertThat(tenantJobs.callAs(t.tenantId(), expiry::expireDue))
                .as("nothing twice")
                .isZero();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*)::int FROM audit_log WHERE tenant_id = ? AND action = 'core.reports.files_expired'")
                        .param(t.tenantId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(n);
    }

    // ---- a business whose subscription ended (ADR-073) ---------------------------------------------

    // JUSTIFICATION-A3: a new test case for review fix 2 of #474; it needs its own fixture steps.
    /**
     * After the Owner's cancellation took effect the exports still work: a run asked for then, and one queued just before,
     * are taken by the runner and finish.
     */
    @Test
    void theRunsOfABusinessWhoseSubscriptionEndedStillFinish() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        ResponseEntity<JsonNode> before = post(t, user, PORTFOLIO, "test.rows", Map.of("n", 3), "json");
        assertThat(before.getStatusCode().value()).as("%s", before.getBody()).isEqualTo(202);
        TestDatabase.owner().sql("""
                        INSERT INTO subscriptions (id, tenant_id, plan_id, status, ended_on)
                        SELECT ?, t.id, t.plan_id, 'cancelled', current_date FROM tenants t WHERE t.id = ?
                        ON CONFLICT (tenant_id) DO UPDATE SET status = 'cancelled', ended_on = current_date
                        """).params(UUID.randomUUID(), t.tenantId()).update();
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .param(t.tenantId())
                .update();

        ResponseEntity<JsonNode> after = post(t, user, PORTFOLIO, "test.rows", Map.of("n", 2), "json");
        assertThat(after.getStatusCode().value()).as("%s", after.getBody()).isEqualTo(202);
        drain();

        assertReady(run(t, user, PORTFOLIO, before.getBody().get("id").asString()), "queued before the end");
        assertReady(run(t, user, PORTFOLIO, after.getBody().get("id").asString()), "asked for after the end");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private long count(String status, TestDatabase.Fixture... tenants) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM report_runs WHERE status = :status AND tenant_id IN (:ids)")
                .param("status", status)
                .param(
                        "ids",
                        java.util.Arrays.stream(tenants)
                                .map(TestDatabase.Fixture::tenantId)
                                .toList())
                .query(Long.class)
                .single();
    }

    private UUID insertRun(TestDatabase.Fixture t, UUID user, String status) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO report_runs (id, tenant_id, created_at, report_key, params, params_hash, scope, format,
                                                 status, requested_by, as_of, started_at, row_cap, size_cap_bytes)
                        VALUES (?, ?, ?, 'test.rows', '{}', ?, '{}', 'json', ?, ?, DATE '2026-10-08',
                                CASE WHEN CAST(? AS text) = 'running' THEN CAST(? AS timestamptz) END, 500, 1048576)
                        """)
                .params(
                        id,
                        t.tenantId(),
                        java.sql.Timestamp.from(TestReports.START),
                        (id.toString() + id).replace("-", ""),
                        status,
                        user,
                        status,
                        java.sql.Timestamp.from(TestReports.START))
                .update();
        return id;
    }

    /** Spool directories left for the tenant: none once a run has ended, whatever way. */
    private List<String> spoolLeft(TestDatabase.Fixture t) throws IOException {
        Path dir = limits.spoolDir;
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var children = Files.list(dir)) {
            return children.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(t.tenantId().toString()))
                    .toList();
        }
    }
}
