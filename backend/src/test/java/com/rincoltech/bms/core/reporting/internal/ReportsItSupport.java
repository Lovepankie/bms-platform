package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.reporting.internal.TestReports.MovableClock;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Shared set-up of the report run tests (extras slice D1, ADR-038). Subclasses carry the same
 * {@code @Import} and {@code @TestPropertySource} lines, so the application context is built once for both: one run
 * at a time, a 2 second timeout, 500 rows a table, a 1 MiB file and a 0.2 second cancel poll, the staging shape.
 * The scheduler's own tick is off in the test profile, so a test starts runs only by calling
 * {@link ReportRunner#tick}. Every user, name and figure is fabricated, and the clock is the movable one of
 * {@link TestReports}, so nothing depends on the day the suite runs.
 */
abstract class ReportsItSupport extends IntegrationTest {

    static final String PORTFOLIO = "lending.reports.portfolio";
    static final String PROPS = "bms.reports.";

    /** How long a test waits for workers or a gate: far above any run on a loaded runner, so a wait is never the flake. */
    static final Duration IDLE = Duration.ofSeconds(180);

    @Autowired
    TestRestTemplate http;

    @Autowired
    ReportRunner runner;

    @Autowired
    TenantJobs tenantJobs;

    @Autowired
    MovableClock clock;

    @Autowired
    ReportExpiry expiry;

    @Autowired
    RunStore store;

    @Autowired
    ReportsProperties limits;

    @BeforeEach
    void freshClockAndGates() {
        clock.set(TestReports.START);
        TestReports.gate = new CountDownLatch(0);
        TestReports.gateEntered = new CountDownLatch(1);
    }

    /**
     * Nothing queued or running is left for the next test to start by accident, and no worker of this test is still
     * running when the next one resets the shared gates: the open runs are cancelled, then the workers are awaited (a
     * cancelled run stops at its next checkpoint, a fraction of a second).
     */
    @AfterEach
    void closeOpenRuns() throws InterruptedException {
        TestReports.gate.countDown();
        TestDatabase.owner()
                .sql(
                        "UPDATE report_runs SET status = 'cancelled', finished_at = now() WHERE status IN ('queued', 'running')")
                .update();
        assertThat(runner.awaitIdle(IDLE))
                .as("the workers of the test finished")
                .isTrue();
    }

    TestDatabase.Fixture tenant(boolean reportsOn) {
        TestDatabase.Fixture t = TestDatabase.tenant("rep", true);
        if (reportsOn) {
            enable(t);
        }
        return t;
    }

    void enable(TestDatabase.Fixture t) {
        TestDatabase.owner().sql("""
                        INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?, '{"reports_enabled": true}')
                        ON CONFLICT (tenant_id) DO UPDATE SET settings = tenant_settings.settings || '{"reports_enabled": true}'
                        """).params(UUID.randomUUID(), t.tenantId()).update();
    }

    HttpHeaders headers(TestDatabase.Fixture t, UUID user, String permissions) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", user.toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", "*");
        return h;
    }

    static Map<String, Object> body(String key, Map<String, Object> params, String format) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("report_key", key);
        b.put("params", params);
        b.put("format", format);
        return b;
    }

    ResponseEntity<JsonNode> post(
            TestDatabase.Fixture t,
            UUID user,
            String permissions,
            String key,
            Map<String, Object> params,
            String format) {
        return post(t, user, permissions, key, params, format, null);
    }

    ResponseEntity<JsonNode> post(
            TestDatabase.Fixture t,
            UUID user,
            String permissions,
            String key,
            Map<String, Object> params,
            String format,
            String idempotencyKey) {
        HttpHeaders h = headers(t, user, permissions);
        if (idempotencyKey != null) {
            h.add("Idempotency-Key", idempotencyKey);
        }
        return http.exchange(
                "/api/v1/report-runs", HttpMethod.POST, new HttpEntity<>(body(key, params, format), h), JsonNode.class);
    }

    ResponseEntity<JsonNode> call(
            HttpMethod method, String path, TestDatabase.Fixture t, UUID user, String permissions) {
        return http.exchange(path, method, new HttpEntity<>(headers(t, user, permissions)), JsonNode.class);
    }

    JsonNode run(TestDatabase.Fixture t, UUID user, String permissions, String id) {
        ResponseEntity<JsonNode> r = call(HttpMethod.GET, "/api/v1/report-runs/" + id, t, user, permissions);
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(200);
        return r.getBody();
    }

    /**
     * The queue is run until no run the runner serves is queued or running, in any tenant: a tick at a time, each
     * tick's workers awaited, until every such run has ended (its status is no longer queued or running and no worker of
     * this process holds it), or {@link #IDLE} has passed.
     *
     * <p>Only the businesses the runner serves count: active, or ended by the Owner's cancellation, as in V90's
     * {@code app_tenants_with_queued_report_runs}. A run of a business that was removed or suspended by the operator is
     * never claimed, so waiting for it can only fail; one such run, the full data export {@code BusinessClosureIT} queues
     * when it asks to close a business it then removes, failed the first drain of whatever report test came after it
     * (issue #621).
     */
    void drain() throws InterruptedException {
        long end = System.nanoTime() + IDLE.toNanos();
        while (true) {
            int started = runner.tick();
            assertThat(runner.awaitIdle(IDLE)).as("the workers finished").isTrue();
            List<String> open = openRuns();
            if (open.isEmpty()) {
                return;
            }
            if (System.nanoTime() > end) {
                throw new AssertionError("runs are still open after " + IDLE + ": " + open);
            }
            if (started == 0) {
                Thread.sleep(50);
            }
        }
    }

    /** The runs the runner serves that are still queued or running, each with its state, for drain's message. */
    static List<String> openRuns() {
        return TestDatabase.owner().sql("""
                        SELECT r.id || ' (' || r.report_key || ', ' || r.status || ', business ' || t.slug
                               || ', owner ' || coalesce(r.owner, 'none') || ', heartbeat '
                               || coalesce(r.heartbeat_at::text, 'none') || ')'
                          FROM report_runs r JOIN tenants t ON t.id = r.tenant_id
                          LEFT JOIN subscriptions s ON s.tenant_id = t.id
                         WHERE r.status IN ('queued', 'running')
                           AND (t.status = 'active'
                                OR (t.status = 'suspended' AND s.status = 'cancelled' AND s.ended_on IS NOT NULL))
                         ORDER BY r.created_at, r.id
                        """).query(String.class).list();
    }

    /** The text of a ready run's file, fetched through the signed URL as a browser would. */
    String download(TestDatabase.Fixture t, UUID user, String permissions, String documentId) {
        ResponseEntity<JsonNode> url = http.exchange(
                "/api/v1/documents/" + documentId + "/download-url",
                HttpMethod.POST,
                new HttpEntity<>(headers(t, user, permissions)),
                JsonNode.class);
        assertThat(url.getStatusCode().value()).as("%s", url.getBody()).isEqualTo(200);
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        ResponseEntity<String> file = http.exchange(
                URI.create(http.getRootUri() + url.getBody().get("url").asString()),
                HttpMethod.GET,
                new HttpEntity<>(h),
                String.class);
        assertThat(file.getStatusCode().value()).isEqualTo(200);
        return file.getBody();
    }

    // JUSTIFICATION-A3: the PDF and workbook files (issue #217) are binary; download() reads text and would corrupt
    // them.
    /** The bytes of a ready run's file, fetched through the signed URL as a browser would. */
    byte[] downloadBytes(TestDatabase.Fixture t, UUID user, String permissions, String documentId) {
        ResponseEntity<JsonNode> url = http.exchange(
                "/api/v1/documents/" + documentId + "/download-url",
                HttpMethod.POST,
                new HttpEntity<>(headers(t, user, permissions)),
                JsonNode.class);
        assertThat(url.getStatusCode().value()).as("%s", url.getBody()).isEqualTo(200);
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        ResponseEntity<byte[]> file = http.exchange(
                URI.create(http.getRootUri() + url.getBody().get("url").asString()),
                HttpMethod.GET,
                new HttpEntity<>(h),
                byte[].class);
        assertThat(file.getStatusCode().value()).isEqualTo(200);
        return file.getBody();
    }

    /** A finished run is ready; when it is not, the message says how it ended (status and failure code). */
    static void assertReady(JsonNode run, String what) {
        assertThat(run.get("status").asString())
                .as("%s: ended %s with error %s", what, run.get("status"), run.get("error"))
                .isEqualTo("ready");
    }

    /** Queues one run, drains the queue and returns the finished run. */
    JsonNode runToTheEnd(TestDatabase.Fixture t, UUID user, String key, Map<String, Object> params, String format)
            throws InterruptedException {
        ResponseEntity<JsonNode> posted = post(t, user, PORTFOLIO, key, params, format);
        assertThat(posted.getStatusCode().value()).as("%s", posted.getBody()).isEqualTo(202);
        drain();
        return run(t, user, PORTFOLIO, posted.getBody().get("id").asString());
    }
}
