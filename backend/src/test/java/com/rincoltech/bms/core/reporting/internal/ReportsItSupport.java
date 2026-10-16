package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.reporting.internal.TestReports.MovableClock;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
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

    /** The queue is run until nothing is queued or running (any tenant), a tick at a time. */
    void drain() throws InterruptedException {
        for (int i = 0; i < 60; i++) {
            runner.tick();
            assertThat(runner.awaitIdle(IDLE)).as("the workers finished").isTrue();
            Integer open = TestDatabase.owner()
                    .sql("SELECT count(*)::int FROM report_runs WHERE status IN ('queued', 'running')")
                    .query(Integer.class)
                    .single();
            if (open == 0) {
                return;
            }
        }
        throw new AssertionError("runs are still open after 60 ticks");
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
