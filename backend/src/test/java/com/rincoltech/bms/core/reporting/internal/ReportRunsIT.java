package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The request side of report runs (extras slice D1, issue #214; FR-RPT-01, FR-RPT-04; ADR-038 decision 1): the tenant
 * switch (off by default), the catalogue by module and permission, the life of a run from queued to ready with its
 * parameters echoed, two identical requests giving one run (also when they arrive together), the idempotency key,
 * tenant isolation of runs and files, cancel, the rate limit, and the 30 day expiry of files.
 */
@Import(TestReports.class)
@TestPropertySource(
        properties = {
            "bms.reports.max-concurrent-runs=1",
            "bms.reports.timeout=PT2S",
            "bms.reports.xlsx-row-cap=500",
            "bms.reports.pdf-row-cap=200",
            "bms.reports.max-file-bytes=1048576",
            "bms.reports.cancel-poll=PT0.2S",
            "bms.reports.heartbeat=PT0.1S",
            "bms.reports.runner-enabled=false"
        })
class ReportRunsIT extends ReportsItSupport {

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ApplicationContext context;

    // ---- the tenant switch and the catalogue ---------------------------------------------------

    @Test
    void reportsAreOffUntilTheTenantSwitchesThemOnInTheSettings() {
        TestDatabase.Fixture t = tenant(false);
        UUID user = UUID.randomUUID();

        TestDatabase.owner()
                .sql(
                        "INSERT INTO users (id, tenant_id, kind, full_name, phone_e164, status) VALUES (?, ?, 'staff', 'Test Staff 55', '+256700000055', 'active')")
                .params(user, t.tenantId())
                .update();
        // Off means off everywhere: the catalogue answers like every other report route (spec principle 1).
        ResponseEntity<JsonNode> catalogue = call(HttpMethod.GET, "/api/v1/reports", t, user, PORTFOLIO);
        assertThat(catalogue.getStatusCode().value()).isEqualTo(404);
        assertThat(catalogue.getBody().get("code").asString()).isEqualTo("reports_not_enabled");
        // /me tells the PWA, so the Reports tab is not offered.
        assertThat(call(HttpMethod.GET, "/api/v1/me", t, user, PORTFOLIO)
                        .getBody()
                        .get("reports_enabled")
                        .asBoolean())
                .isFalse();
        assertThat(post(t, user, PORTFOLIO, "test.rows", Map.of(), "json")
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        // Every other run route answers the same while the switch is off: a run, its status and a cancel.
        UUID someRun = UUID.randomUUID();
        for (ResponseEntity<JsonNode> answer : List.of(
                call(HttpMethod.GET, "/api/v1/report-runs/" + someRun, t, user, PORTFOLIO),
                call(HttpMethod.POST, "/api/v1/report-runs/" + someRun + "/cancel", t, user, PORTFOLIO))) {
            assertThat(answer.getStatusCode().value()).isEqualTo(404);
            assertThat(answer.getBody().get("code").asString()).isEqualTo("reports_not_enabled");
        }
        JsonNode problem =
                post(t, user, PORTFOLIO, "test.rows", Map.of(), "json").getBody();
        assertThat(problem.get("code").asString()).isEqualTo("reports_not_enabled");
        assertThat(call(HttpMethod.GET, "/api/v1/report-runs", t, user, PORTFOLIO)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);

        // The administrator switches it on through the settings (core.settings.manage), with the version.
        String admin = "core.settings.read,core.settings.manage";
        ResponseEntity<JsonNode> settings = call(HttpMethod.GET, "/api/v1/settings", t, user, admin);
        assertThat(settings.getBody().get("reports_enabled").asBoolean()).isFalse();
        HttpHeaders h = headers(t, user, admin);
        h.add("If-Match", settings.getHeaders().getETag());
        ResponseEntity<JsonNode> changed = http.exchange(
                "/api/v1/settings",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("reports_enabled", true), h),
                JsonNode.class);
        assertThat(changed.getStatusCode().value()).as("%s", changed.getBody()).isEqualTo(200);
        assertThat(changed.getBody().get("reports_enabled").asBoolean()).isTrue();

        assertThat(call(HttpMethod.GET, "/api/v1/reports", t, user, PORTFOLIO)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(HttpMethod.GET, "/api/v1/me", t, user, PORTFOLIO)
                        .getBody()
                        .get("reports_enabled")
                        .asBoolean())
                .isTrue();
        assertThat(post(t, user, PORTFOLIO, "test.rows", Map.of(), "json")
                        .getStatusCode()
                        .value())
                .isEqualTo(202);
        // The change is audited by the settings module with the old and new values.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*)::int FROM audit_log WHERE tenant_id = ? AND action = 'core.settings.updated'"
                                        + " AND data -> 'after' ->> 'reports_enabled' = 'true'")
                        .param(t.tenantId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void theCatalogueListsOnlyReportsOfTheTenantsModulesUnderPermissionsTheUserHolds() {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();

        List<String> keys = new ArrayList<>();
        call(HttpMethod.GET, "/api/v1/reports", t, user, PORTFOLIO)
                .getBody()
                .forEach(r -> keys.add(r.get("key").asString()));
        assertThat(keys).contains("lending.par", "test.rows").doesNotContain("test.retail");

        JsonNode par = null;
        for (JsonNode r :
                call(HttpMethod.GET, "/api/v1/reports", t, user, PORTFOLIO).getBody()) {
            if (r.get("key").asString().equals("lending.par")) {
                par = r;
            }
        }
        assertThat(par.get("permission").asString()).isEqualTo(PORTFOLIO);
        assertThat(par.get("formats")).extracting(JsonNode::asString).containsExactly("csv", "json", "pdf", "xlsx");
        assertThat(par.get("params"))
                .extracting(p -> p.get("name").asString())
                .contains("as_at", "branch_id", "group_by");

        // Without the report permission the report is not listed, and not runnable.
        JsonNode none = call(HttpMethod.GET, "/api/v1/reports", t, user, "lending.members.read")
                .getBody();
        assertThat(none).isEmpty();
        assertThat(post(t, user, "lending.members.read", "test.rows", Map.of(), "json")
                        .getStatusCode()
                        .value())
                .isEqualTo(403);
        // A report of a module the tenant does not have is not found, whatever the permission.
        ResponseEntity<JsonNode> retail =
                post(t, user, "retail.reports.read," + PORTFOLIO, "test.retail", Map.of(), "json");
        assertThat(retail.getStatusCode().value()).isEqualTo(404);
        assertThat(retail.getBody().get("code").asString()).isEqualTo("report_not_found");
    }

    @Test
    void aRequestIsRefusedWhenItsFormatOrParametersAreWrong() {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();

        ResponseEntity<JsonNode> pdf = post(t, user, PORTFOLIO, "test.rows", Map.of(), "docx");
        assertThat(pdf.getStatusCode().value()).isEqualTo(422);
        assertThat(pdf.getBody().toString()).contains("format");
        ResponseEntity<JsonNode> unknown = post(t, user, PORTFOLIO, "test.rows", Map.of("zzz", 1), "json");
        assertThat(unknown.getStatusCode().value()).isEqualTo(422);
        assertThat(unknown.getBody().toString()).contains("params.zzz");
        ResponseEntity<JsonNode> bad = post(t, user, PORTFOLIO, "test.rows", Map.of("n", -1), "json");
        assertThat(bad.getStatusCode().value()).isEqualTo(422);
        assertThat(post(t, user, PORTFOLIO, "no.such.report", Map.of(), "json")
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
    }

    // ---- the life of a run ---------------------------------------------------------------------

    @Test
    void aRunGoesQueuedRunningReadyAndItsFileEchoesTheParameters() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        String currency = TestDatabase.owner()
                .sql("SELECT currency FROM tenants WHERE id = ?")
                .param(t.tenantId())
                .query(String.class)
                .single();

        // Asked a minute before the gated run below, so it is the older and the queue takes it first.
        clock.set(TestReports.START.minusSeconds(60));
        ResponseEntity<JsonNode> posted = post(t, user, PORTFOLIO, "test.rows", Map.of("n", 5), "json");
        clock.set(TestReports.START);
        assertThat(posted.getStatusCode().value()).isEqualTo(202);
        assertThat(posted.getHeaders().getCacheControl()).isEqualTo("no-store");
        JsonNode queued = posted.getBody();
        assertThat(queued.get("status").asString()).isEqualTo("queued");
        assertThat(queued.get("report_key").asString()).isEqualTo("test.rows");
        assertThat(queued.get("as_of").asString())
                .as("the business date in Kampala")
                .isEqualTo("2026-10-08");
        assertThat(queued.get("params").get("n").asInt()).isEqualTo(5);

        // A tick claims it: running while the worker holds it open.
        TestReports.gate = new CountDownLatch(1);
        ResponseEntity<JsonNode> gated = post(t, user, PORTFOLIO, "test.gate", Map.of(), "json");
        String gatedId = gated.getBody().get("id").asString();
        // One run at a time (the first queued run goes first), so drain the older one, then start the gated one.
        drain1();
        runner.tick();
        assertThat(TestReports.gateEntered.await(30, java.util.concurrent.TimeUnit.SECONDS))
                .isTrue();
        JsonNode running = run(t, user, PORTFOLIO, gatedId);
        assertThat(running.get("status").asString()).isEqualTo("running");
        assertThat(running.get("started_at").asString()).isNotEmpty();
        TestReports.gate.countDown();
        assertThat(runner.awaitIdle(Duration.ofSeconds(60))).isTrue();

        JsonNode ready = run(t, user, PORTFOLIO, queued.get("id").asString());
        assertThat(ready.get("status").asString()).isEqualTo("ready");
        assertThat(ready.get("row_count").asInt()).isEqualTo(5);
        assertThat(ready.get("snapshot_at").asString())
                .as("the data was read at")
                .isNotEmpty();
        assertThat(ready.get("expires_at").asString()).isEqualTo("2026-11-07T10:00:00Z");
        assertThat(ready.get("expired").asBoolean()).isFalse();
        assertThat(ready.get("error").isNull()).isTrue();

        JsonNode file = mapper.readTree(
                download(t, user, PORTFOLIO, ready.get("document_id").asString()));
        assertThat(file.get("report_key").asString()).isEqualTo("test.rows");
        assertThat(file.get("as_of").asString()).isEqualTo("2026-10-08");
        assertThat(file.get("currency").asString()).isEqualTo(currency);
        assertThat(file.get("parameters").get("n").asInt())
                .as("parameters are echoed (FR-RPT-02)")
                .isEqualTo(5);
        JsonNode rows = file.get("tables").get(0).get("rows");
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0).toString()).isEqualTo("[1,\"Test Row 1\",1000]");
        assertThat(file.get("tables").get(0).get("row_count").asInt()).isEqualTo(5);
        assertThat(file.get("tables").get(0).get("columns").get(2).get("kind").asString())
                .isEqualTo("money");

        // The list shows it, newest first, for its requester only.
        ResponseEntity<JsonNode> mine = call(HttpMethod.GET, "/api/v1/report-runs", t, user, PORTFOLIO);
        assertThat(mine.getBody()).hasSize(2);
        assertThat(call(HttpMethod.GET, "/api/v1/report-runs", t, UUID.randomUUID(), PORTFOLIO)
                        .getBody())
                .isEmpty();
    }

    /** Runs the queue until the first queued run has finished (the gated run, queued last, is not claimed). */
    private void drain1() throws InterruptedException {
        runner.tick();
        assertThat(runner.awaitIdle(Duration.ofSeconds(60))).isTrue();
    }

    @Test
    void theCsvCarriesTheParametersAndTheCurrencyInTheHeader() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        JsonNode ready = runToTheEnd(t, user, "test.rows", Map.of("n", 2), "csv");
        assertThat(ready.get("status").asString()).isEqualTo("ready");

        String csv = download(t, user, PORTFOLIO, ready.get("document_id").asString());
        List<String> lines = List.of(csv.split("\r\n", -1));
        assertThat(lines).contains("Report key,test.rows", "As at,2026-10-08", "Parameter n,2");
        assertThat(csv).contains("Number,Label,Amount (UGX minor units)\r\n1,Test Row 1,1000\r\n2,Test Row 2,2000\r\n");
    }

    // ---- two identical requests give one run -----------------------------------------------------

    @Test
    void twoIdenticalRequestsOfOneUserGiveOneRunWhileItIsOpen() {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();

        ResponseEntity<JsonNode> first = post(t, user, PORTFOLIO, "test.rows", Map.of("n", 4), "json");
        ResponseEntity<JsonNode> second = post(t, user, PORTFOLIO, "test.rows", Map.of("n", 4), "json");
        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(second.getStatusCode().value())
                .as("the existing run is returned")
                .isEqualTo(200);
        assertThat(second.getBody().get("id").asString())
                .isEqualTo(first.getBody().get("id").asString());

        // A parameter spelled the other way round is the same request once normalised (n defaults to 3).
        String other = post(t, user, PORTFOLIO, "test.rows", Map.of("n", 4.0), "json")
                .getBody()
                .get("id")
                .asString();
        assertThat(other).isEqualTo(first.getBody().get("id").asString());

        // A different parameter, a different format or a different user is a different run.
        assertThat(post(t, user, PORTFOLIO, "test.rows", Map.of("n", 5), "json")
                        .getBody()
                        .get("id")
                        .asString())
                .isNotEqualTo(first.getBody().get("id").asString());
        assertThat(post(t, user, PORTFOLIO, "test.rows", Map.of("n", 4), "csv")
                        .getBody()
                        .get("id")
                        .asString())
                .isNotEqualTo(first.getBody().get("id").asString());
        assertThat(post(t, UUID.randomUUID(), PORTFOLIO, "test.rows", Map.of("n", 4), "json")
                        .getBody()
                        .get("id")
                        .asString())
                .isNotEqualTo(first.getBody().get("id").asString());
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*)::int FROM report_runs WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(4);
    }

    @Test
    void aFinishedRunIsNotReusedByTheNextIdenticalRequest() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        JsonNode first = runToTheEnd(t, user, "test.rows", Map.of("n", 1), "json");
        ResponseEntity<JsonNode> again = post(t, user, PORTFOLIO, "test.rows", Map.of("n", 1), "json");
        assertThat(again.getStatusCode().value()).isEqualTo(202);
        assertThat(again.getBody().get("id").asString())
                .isNotEqualTo(first.get("id").asString());
    }

    @Test
    void identicalRequestsArrivingTogetherStillGiveOneRun() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<String>> ids = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<String> call = () -> {
                    go.await();
                    return post(t, user, PORTFOLIO, "test.rows", Map.of("n", 9), "json")
                            .getBody()
                            .get("id")
                            .asString();
                };
                ids.add(pool.submit(call));
            }
            go.countDown();
            List<String> seen = new ArrayList<>();
            for (Future<String> f : ids) {
                seen.add(f.get());
            }
            assertThat(seen).as("everyone got the same run").allMatch(id -> id.equals(seen.get(0)));
            assertThat(TestDatabase.owner()
                            .sql("SELECT count(*)::int FROM report_runs WHERE tenant_id = ?")
                            .param(t.tenantId())
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anIdempotencyKeyReplaysTheFirstAnswerAndRefusesADifferentRequest() {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        String key = "report-key-" + UUID.randomUUID();

        ResponseEntity<JsonNode> first = post(t, user, PORTFOLIO, "test.rows", Map.of("n", 2), "json", key);
        ResponseEntity<JsonNode> replay = post(t, user, PORTFOLIO, "test.rows", Map.of("n", 2), "json", key);
        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(replay.getStatusCode().value()).isEqualTo(200);
        assertThat(replay.getBody().get("id").asString())
                .isEqualTo(first.getBody().get("id").asString());
        ResponseEntity<JsonNode> reused = post(t, user, PORTFOLIO, "test.rows", Map.of("n", 7), "json", key);
        assertThat(reused.getStatusCode().value()).isEqualTo(422);
        assertThat(reused.getBody().get("code").asString()).isEqualTo("idempotency_key_reused");
    }

    @Test
    void aUserMayQueueTenNewRunsAMinuteAndThenWaits() {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        for (int i = 1; i <= 10; i++) {
            assertThat(post(t, user, PORTFOLIO, "test.rows", Map.of("n", i), "json")
                            .getStatusCode()
                            .value())
                    .as("request %d", i)
                    .isEqualTo(202);
        }
        ResponseEntity<JsonNode> eleventh = post(t, user, PORTFOLIO, "test.rows", Map.of("n", 11), "json");
        assertThat(eleventh.getStatusCode().value()).isEqualTo(429);
        assertThat(eleventh.getBody().get("code").asString()).isEqualTo("rate_limited");
        // Another user is not affected, and a minute later the first is served again.
        assertThat(post(t, UUID.randomUUID(), PORTFOLIO, "test.rows", Map.of("n", 11), "json")
                        .getStatusCode()
                        .value())
                .isEqualTo(202);
        clock.set(TestReports.START.plus(Duration.ofSeconds(61)));
        assertThat(post(t, user, PORTFOLIO, "test.rows", Map.of("n", 11), "json")
                        .getStatusCode()
                        .value())
                .isEqualTo(202);
    }

    // JUSTIFICATION-A3: new cases for the order of the repeat check and the limit, and for the limit under concurrent
    // requests.
    @Test
    void anIdenticalRepeatPastTheLimitGetsTheExistingRunNotA429() {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        String first = null;
        for (int i = 1; i <= 10; i++) {
            ResponseEntity<JsonNode> r = post(t, user, PORTFOLIO, "test.rows", Map.of("n", i), "json");
            assertThat(r.getStatusCode().value()).isEqualTo(202);
            if (i == 1) {
                first = r.getBody().get("id").asString();
            }
        }
        // The eleventh distinct request is refused; a repeat of the first is answered with the first.
        assertThat(post(t, user, PORTFOLIO, "test.rows", Map.of("n", 11), "json")
                        .getStatusCode()
                        .value())
                .isEqualTo(429);
        ResponseEntity<JsonNode> again = post(t, user, PORTFOLIO, "test.rows", Map.of("n", 1), "json");
        assertThat(again.getStatusCode().value()).isEqualTo(200);
        assertThat(again.getBody().get("id").asString()).isEqualTo(first);
    }

    @Test
    void theLimitHoldsWhenRequestsArriveTogether() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        int asked = 16;
        ExecutorService pool = Executors.newFixedThreadPool(asked);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> codes = new ArrayList<>();
            for (int i = 1; i <= asked; i++) {
                int n = i;
                Callable<Integer> call = () -> {
                    go.await();
                    return post(t, user, PORTFOLIO, "test.rows", Map.of("n", n), "json")
                            .getStatusCode()
                            .value();
                };
                codes.add(pool.submit(call));
            }
            go.countDown();
            int accepted = 0;
            int refused = 0;
            for (Future<Integer> f : codes) {
                int code = f.get();
                if (code == 202) {
                    accepted++;
                } else if (code == 429) {
                    refused++;
                }
            }
            assertThat(accepted).as("exactly ten runs, not about ten").isEqualTo(10);
            assertThat(refused).isEqualTo(asked - 10);
            assertThat(TestDatabase.owner()
                            .sql("SELECT count(*)::int FROM report_runs WHERE tenant_id = ?")
                            .param(t.tenantId())
                            .query(Integer.class)
                            .single())
                    .isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- who may see a run and its file ---------------------------------------------------------

    @Test
    void aRunAndItsFileBelongToTheRequesterAndToNoOtherTenant() throws Exception {
        TestDatabase.Fixture a = tenant(true);
        TestDatabase.Fixture b = tenant(true);
        UUID owner = UUID.randomUUID();
        UUID colleague = UUID.randomUUID();
        UUID auditor = UUID.randomUUID();
        JsonNode ready = runToTheEnd(a, owner, "test.rows", Map.of("n", 2), "json");
        String runId = ready.get("id").asString();
        String documentId = ready.get("document_id").asString();

        // Another tenant: the run and the document do not exist for it, even with the same permissions.
        assertThat(call(HttpMethod.GET, "/api/v1/report-runs/" + runId, b, owner, PORTFOLIO)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/api/v1/report-runs/" + runId + "/cancel", b, owner, PORTFOLIO)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/api/v1/documents/" + documentId + "/download-url", b, owner, PORTFOLIO)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/api/v1/report-runs", b, owner, PORTFOLIO)
                        .getBody())
                .isEmpty();

        // Another user of the same tenant: not found, and no file.
        assertThat(call(HttpMethod.GET, "/api/v1/report-runs/" + runId, a, colleague, PORTFOLIO)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/api/v1/documents/" + documentId + "/download-url", a, colleague, PORTFOLIO)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        // An auditor may read the status, not the file.
        assertThat(call(HttpMethod.GET, "/api/v1/report-runs/" + runId, a, auditor, "core.audit.read")
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(
                                HttpMethod.POST,
                                "/api/v1/documents/" + documentId + "/download-url",
                                a,
                                auditor,
                                "core.audit.read," + PORTFOLIO)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        // The requester who has lost the report's permission loses the file with it.
        assertThat(call(
                                HttpMethod.POST,
                                "/api/v1/documents/" + documentId + "/download-url",
                                a,
                                owner,
                                "lending.members.read")
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(call(HttpMethod.POST, "/api/v1/documents/" + documentId + "/download-url", a, owner, PORTFOLIO)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
    }

    // ---- cancel ----------------------------------------------------------------------------------

    @Test
    void aQueuedRunIsCancelledAtOnceAndNeverStarts() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        String id = post(t, user, PORTFOLIO, "test.rows", Map.of("n", 3), "json")
                .getBody()
                .get("id")
                .asString();

        ResponseEntity<JsonNode> cancelled =
                call(HttpMethod.POST, "/api/v1/report-runs/" + id + "/cancel", t, user, PORTFOLIO);
        assertThat(cancelled.getStatusCode().value()).isEqualTo(200);
        assertThat(cancelled.getBody().get("status").asString()).isEqualTo("cancelled");
        drain();
        assertThat(run(t, user, PORTFOLIO, id).get("status").asString()).isEqualTo("cancelled");
        // Cancelling again is the same answer; a colleague cannot cancel it.
        assertThat(call(HttpMethod.POST, "/api/v1/report-runs/" + id + "/cancel", t, user, PORTFOLIO)
                        .getStatusCode()
                        .value())
                .isEqualTo(200);
        assertThat(call(HttpMethod.POST, "/api/v1/report-runs/" + id + "/cancel", t, UUID.randomUUID(), PORTFOLIO)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        // The same request can then be made again: the cancelled run no longer holds the dedupe place.
        assertThat(post(t, user, PORTFOLIO, "test.rows", Map.of("n", 3), "json")
                        .getStatusCode()
                        .value())
                .isEqualTo(202);
    }

    @Test
    void aRunningRunIsAskedToStopAndEndsCancelledWithNoFile() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        String id = post(t, user, PORTFOLIO, "test.slow", Map.of(), "json")
                .getBody()
                .get("id")
                .asString();
        runner.tick();
        awaitStatus(t, user, id, "running");

        ResponseEntity<JsonNode> asked =
                call(HttpMethod.POST, "/api/v1/report-runs/" + id + "/cancel", t, user, PORTFOLIO);
        assertThat(asked.getStatusCode().value()).isEqualTo(202);
        assertThat(asked.getBody().get("cancel_requested").asBoolean()).isTrue();
        assertThat(runner.awaitIdle(Duration.ofSeconds(30))).isTrue();

        JsonNode end = run(t, user, PORTFOLIO, id);
        assertThat(end.get("status").asString()).isEqualTo("cancelled");
        assertThat(end.get("document_id").isNull()).isTrue();
        assertThat(documentsOf(id)).isZero();
        // A finished run cannot be cancelled.
        JsonNode done = runToTheEnd(t, user, "test.rows", Map.of("n", 1), "json");
        ResponseEntity<JsonNode> late = call(
                HttpMethod.POST, "/api/v1/report-runs/" + done.get("id").asString() + "/cancel", t, user, PORTFOLIO);
        assertThat(late.getStatusCode().value()).isEqualTo(409);
        assertThat(late.getBody().get("code").asString()).isEqualTo("run_finished");
    }

    @Test
    void aCancelThatArrivesAfterTheQueryButBeforeTheFileIsRecordedStillWins() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        TestReports.gate = new CountDownLatch(1);
        String id = post(t, user, PORTFOLIO, "test.gate", Map.of(), "json")
                .getBody()
                .get("id")
                .asString();
        runner.tick();
        assertThat(TestReports.gateEntered.await(30, java.util.concurrent.TimeUnit.SECONDS))
                .isTrue();
        call(HttpMethod.POST, "/api/v1/report-runs/" + id + "/cancel", t, user, PORTFOLIO);
        TestReports.gate.countDown();
        assertThat(runner.awaitIdle(Duration.ofSeconds(30))).isTrue();

        assertThat(run(t, user, PORTFOLIO, id).get("status").asString()).isEqualTo("cancelled");
        assertThat(documentsOf(id))
                .as("no document row survives a cancelled run")
                .isZero();
    }

    private long documentsOf(String runId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM documents WHERE subject_type = 'core.report_run' AND subject_id = ?")
                .param(UUID.fromString(runId))
                .query(Long.class)
                .single();
    }

    private void awaitStatus(TestDatabase.Fixture t, UUID user, String id, String status) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            if (run(t, user, PORTFOLIO, id).get("status").asString().equals(status)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("run " + id + " never became " + status);
    }

    // ---- expiry ---------------------------------------------------------------------------------

    @Test
    void aFileIsDeletedAfterThirtyDaysAndADownloadThenAnswers410() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        JsonNode early = runToTheEnd(t, user, "test.rows", Map.of("n", 1), "json");
        clock.set(TestReports.START.plus(Duration.ofDays(20)));
        JsonNode late = runToTheEnd(t, user, "test.rows", Map.of("n", 2), "json");
        String earlyKey = objectKey(early.get("document_id").asString());
        String lateKey = objectKey(late.get("document_id").asString());
        assertThat(stored(earlyKey)).isTrue();

        // Day 29: nothing is due yet.
        clock.set(TestReports.START.plus(Duration.ofDays(29)));
        assertThat(tenantJobs.callAs(t.tenantId(), expiry::expireDue)).isZero();
        assertThat(stored(earlyKey)).isTrue();

        // Day 31: the first file is due, the second (made on day 20) is not.
        clock.set(TestReports.START.plus(Duration.ofDays(31)));
        assertThat(tenantJobs.callAs(t.tenantId(), expiry::expireDue)).isEqualTo(1);
        assertThat(stored(earlyKey)).as("the object is gone").isFalse();
        assertThat(stored(lateKey)).as("a later file is kept").isTrue();
        assertThat(tenantJobs.callAs(t.tenantId(), expiry::expireDue))
                .as("nothing twice")
                .isZero();

        JsonNode view = run(t, user, PORTFOLIO, early.get("id").asString());
        assertThat(view.get("expired").asBoolean()).isTrue();
        assertThat(view.get("status").asString())
                .as("the run row stays as the record")
                .isEqualTo("ready");
        ResponseEntity<JsonNode> gone = call(
                HttpMethod.POST,
                "/api/v1/documents/" + early.get("document_id").asString() + "/download-url",
                t,
                user,
                PORTFOLIO);
        assertThat(gone.getStatusCode().value()).isEqualTo(410);
        assertThat(gone.getBody().get("code").asString()).isEqualTo("report_expired");
        assertThat(documentsOf(early.get("id").asString()))
                .as("the document row stays (write once)")
                .isEqualTo(1);
        // A stranger learns nothing from the status code.
        assertThat(call(
                                HttpMethod.POST,
                                "/api/v1/documents/" + early.get("document_id").asString() + "/download-url",
                                t,
                                UUID.randomUUID(),
                                PORTFOLIO)
                        .getStatusCode()
                        .value())
                .isEqualTo(404);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*)::int FROM audit_log WHERE tenant_id = ? AND action = 'core.reports.files_expired'")
                        .param(t.tenantId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    private String objectKey(String documentId) {
        return TestDatabase.owner()
                .sql("SELECT object_key FROM documents WHERE id = ?")
                .param(UUID.fromString(documentId))
                .query(String.class)
                .single();
    }

    /** Whether the fake object store still holds the key (the interface is package-private to core.documents). */
    private boolean stored(String key) throws Exception {
        Class<?> type = Class.forName("com.rincoltech.bms.core.documents.internal.ObjectStorage");
        Object storage = context.getBean(type);
        var get = type.getDeclaredMethod("get", String.class);
        get.setAccessible(true);
        return ((java.util.Optional<?>) get.invoke(storage, key)).isPresent();
    }
}
