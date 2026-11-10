package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The routes of the import screens (issue #141) on real PostgreSQL as bms_app. The business's own routes: a run opened and
 * listed, its files and the mapping choice, and the raw row of an issue, refused without {@code core.imports.view_raw} and,
 * with it, answered and audited with the run, batch and line and never a value; another business's issue is 404. The
 * operator portal's routes: refused to anonymous calls and to a tenant's staff, a stop of a run not being imported refused
 * in plain words, a stop of one being imported recorded, the trading override asked by one operator and approved only by
 * another, the dump recorded and checked, a start before the approval refused. Fabricated data only.
 */
class ImportScreensIT extends IntegrationTest {

    static final String MANAGE = "core.imports.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID staff = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("screens", false);
        other = TestDatabase.tenant("screens-b", false);
    }

    ResponseEntity<JsonNode> call(TestDatabase.Fixture f, HttpMethod method, String path, String permissions) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", f.slug());
        h.add("X-Dev-User-Id", staff.toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", "*");
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(null, h), JsonNode.class);
    }

    static UUID run(TestDatabase.Fixture f, String status) {
        UUID run = UUID.randomUUID();
        TestDatabase.owner()
                .sql("INSERT INTO import_runs (id, tenant_id, created_by, status) VALUES (?, ?, ?, ?)")
                .params(run, f.tenantId(), UUID.randomUUID(), status)
                .update();
        if ("committing".equals(status)) {
            TestDatabase.owner()
                    .sql("UPDATE import_runs SET committing_at = now() WHERE id = ?")
                    .param(run)
                    .update();
        }
        return run;
    }

    /** One staged row with one issue, as the owner: the cells hold a fabricated phone number. */
    static UUID issue(TestDatabase.Fixture f, UUID run) {
        UUID batch = UUID.randomUUID();
        UUID row = UUID.randomUUID();
        UUID issue = UUID.randomUUID();
        TestDatabase.owner()
                .sql("INSERT INTO import_batches (id, tenant_id, run_id, seq, status, row_count, source_filename)"
                        + " VALUES (?, ?, ?, 1, 'parsed', 1, 'fab-buyers.csv')")
                .params(batch, f.tenantId(), run)
                .update();
        TestDatabase.owner()
                .sql("INSERT INTO import_rows (id, tenant_id, batch_id, source_row_no, raw) VALUES (?, ?, ?, 7,"
                        + " CAST(? AS jsonb))")
                .params(
                        row,
                        f.tenantId(),
                        batch,
                        "{\"Fab Buyer\": {\"value\": \"Fab Buyer One\", \"type\": \"text\"},"
                                + " \"Fab Phone\": {\"value\": \"0700000999\", \"type\": \"text\"}}")
                .update();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO import_issues (id, tenant_id, batch_id, row_id, source_row_no, ordinal, code, severity,
                                                   field, message, original_value, status)
                        VALUES (?, ?, ?, ?, 7, 1, 'TYPE_INVALID', 'blocking', 'phone',
                                'phone on fab-buyers.csv line 7 is not a valid phone.', CAST(? AS jsonb), 'open')
                        """)
                .params(issue, f.tenantId(), batch, row, "{\"value\": \"0700000999\", \"type\": \"text\"}")
                .update();
        return issue;
    }

    @Test
    void aRunIsOpenedListedAndReadWithItsFilesAndTheMappingChoice() {
        ResponseEntity<JsonNode> opened = call(t, HttpMethod.POST, "/api/v1/imports/runs", MANAGE);
        assertThat(opened.getStatusCode()).as("%s", opened.getBody()).isEqualTo(HttpStatus.CREATED);
        String id = opened.getBody().get("id").asString();
        assertThat(opened.getBody().get("status").asString()).isEqualTo("draft");

        JsonNode list = call(t, HttpMethod.GET, "/api/v1/imports/runs", MANAGE).getBody();
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("items").get(0).get("id").asString()).isEqualTo(id);
        assertThat(call(other, HttpMethod.GET, "/api/v1/imports/runs", MANAGE)
                        .getBody()
                        .get("items"))
                .isEmpty();

        assertThat(call(t, HttpMethod.GET, "/api/v1/imports/runs/" + id, MANAGE)
                        .getBody()
                        .get("commit_approved")
                        .asBoolean())
                .isFalse();
        assertThat(call(t, HttpMethod.GET, "/api/v1/imports/runs/" + id + "/files", MANAGE)
                        .getBody()
                        .get("items"))
                .isEmpty();
        assertThat(call(other, HttpMethod.GET, "/api/v1/imports/runs/" + id, MANAGE)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(t, HttpMethod.GET, "/api/v1/imports/mappings", MANAGE).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(call(t, HttpMethod.GET, "/api/v1/imports/runs/" + id + "/dry-run", MANAGE)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(t, HttpMethod.GET, "/api/v1/imports/runs/" + id + "/certificate", MANAGE)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(call(t, HttpMethod.GET, "/api/v1/imports/runs", "core.settings.manage")
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void theRawRowNeedsViewRawAndEveryOpeningIsAuditedWithoutAValue() {
        UUID run = run(t, "in_review");
        UUID issue = issue(t, run);
        String path = "/api/v1/imports/issues/" + issue + "/raw";

        assertThat(call(t, HttpMethod.GET, path, MANAGE).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> raw = call(t, HttpMethod.GET, path, "core.imports.view_raw");
        assertThat(raw.getStatusCode()).as("%s", raw.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody().get("line").asInt()).isEqualTo(7);
        assertThat(raw.getBody().get("file").asString()).isEqualTo("fab-buyers.csv");
        assertThat(raw.getBody().get("original_value").asString()).isEqualTo("0700000999");
        assertThat(raw.getBody().get("cells")).hasSize(2);
        assertThat(raw.getBody().toString()).contains("Fab Phone", "Fab Buyer One");
        call(t, HttpMethod.GET, path, "core.imports.view_raw");

        List<String> trail = TestDatabase.owner()
                .sql("SELECT actor_user_id || '|' || CAST(data AS text) FROM audit_log WHERE tenant_id = ?"
                        + " AND action = 'core.import_row.raw_viewed' ORDER BY created_at")
                .param(t.tenantId())
                .query(String.class)
                .list();
        assertThat(trail).as("one audit row per row opened").hasSize(2);
        assertThat(trail.get(0))
                .startsWith(staff.toString())
                .contains(issue.toString(), run.toString(), "\"line\": 7")
                .doesNotContain("0700000999", "Fab Buyer One");

        assertThat(call(other, HttpMethod.GET, path, "core.imports.view_raw").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // As the purge leaves them (V147: the cells become {} and the issue's original value is deleted), the row
        // answers no cells and no value, never a failure (review of 1c9568b on #895).
        TestDatabase.owner()
                .sql("UPDATE import_rows SET raw = '{}'::jsonb, normalised = NULL WHERE batch_id IN"
                        + " (SELECT id FROM import_batches WHERE run_id = ?)")
                .param(run)
                .update();
        TestDatabase.owner()
                .sql("UPDATE import_issues SET original_value = NULL WHERE id = ?")
                .param(issue)
                .update();
        ResponseEntity<JsonNode> purged = call(t, HttpMethod.GET, path, "core.imports.view_raw");
        assertThat(purged.getStatusCode()).as("%s", purged.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(purged.getBody().get("cells")).isEmpty();
        JsonNode original = purged.getBody().get("original_value");
        assertThat(original == null || original.isNull()).isTrue();
    }

    @Test
    void theOperatorPortalStopsOverridesAndRecordsTheDumpForOperatorsOnly() {
        Api platform = Api.platform(http);
        String emailA = Api.email("screens-operator-a");
        Session a = platform.signIn(Api.platformUser(emailA), emailA, Api.PASSWORD, null);
        String emailB = Api.email("screens-operator-b");
        Session b = platform.signIn(Api.platformUser(emailB), emailB, Api.PASSWORD, null);
        String staffEmail = Api.email("screens-admin");
        UUID staffId = Api.staff(t, staffEmail, new Api.Role("tenant_admin", null));
        String staffToken = Api.tenant(http, t.slug())
                .signIn(staffId, staffEmail, Api.PASSWORD, null)
                .accessToken();

        UUID draft = run(t, "draft");
        String base = "/api/v1/platform/imports/businesses/" + t.slug() + "/runs/" + draft;

        assertThat(platform.post(base + "/stop", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(platform.post(base + "/stop", null, staffToken).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<JsonNode> read = platform.get(base, a.accessToken());
        assertThat(read.getStatusCode()).as("%s", read.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(read.getBody().get("tenant_slug").asString()).isEqualTo(t.slug());
        assertThat(read.getBody().get("run").get("status").asString()).isEqualTo("draft");

        ResponseEntity<JsonNode> notImporting = platform.post(base + "/stop", null, a.accessToken());
        assertThat(notImporting.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notImporting.getBody().get("code").asString()).isEqualTo("invalid_status_transition");

        ResponseEntity<JsonNode> notStartable = platform.post(base + "/start", null, a.accessToken());
        assertThat(notStartable.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notStartable.getBody().get("code").asString()).isEqualTo("invalid_status_transition");

        assertThat(platform.post(
                                base + "/trading-override",
                                Map.of("reason", "Fab shop sold on paper only"),
                                a.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> self = platform.post(base + "/trading-override/approve", null, a.accessToken());
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(self.getBody().get("code").asString()).isEqualTo("self_approval_forbidden");
        ResponseEntity<JsonNode> approved = platform.post(base + "/trading-override/approve", null, b.accessToken());
        assertThat(approved.getStatusCode()).as("%s", approved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(approved.getBody()
                        .get("run")
                        .get("trading_override_approved")
                        .asBoolean())
                .isTrue();

        String sha = "a".repeat(64);
        ResponseEntity<JsonNode> dump = platform.post(
                base + "/dump",
                Map.of("file", "fab-pre-import.dump", "taken_at", "2026-11-10T06:00:00Z", "sha256", sha),
                a.accessToken());
        assertThat(dump.getStatusCode()).as("%s", dump.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(dump.getBody().get("run").get("pre_import_dump_file").asString())
                .isEqualTo("fab-pre-import.dump");
        ResponseEntity<JsonNode> checked =
                platform.post(base + "/dump/check", Map.of("sha256", sha, "listed_entries", 42), a.accessToken());
        assertThat(checked.getStatusCode()).as("%s", checked.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(checked.getBody().get("run").get("pre_import_dump_verified").asBoolean())
                .isTrue();

        UUID importing = run(t, "committing");
        ResponseEntity<JsonNode> stopped = platform.post(
                "/api/v1/platform/imports/businesses/" + t.slug() + "/runs/" + importing + "/stop",
                null,
                a.accessToken());
        assertThat(stopped.getStatusCode()).as("%s", stopped.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(stopped.getBody().get("run").get("halt_requested").asBoolean())
                .isTrue();

        assertThat(platform.get("/api/v1/platform/imports/businesses/no-such-business/runs/" + draft, a.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(platform.get("/api/v1/platform/imports/kill-switch", a.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
