package com.rincoltech.bms.core.imports.internal;

import static com.rincoltech.bms.core.imports.internal.ImportValidationIT.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportMappings.Mapping;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportReview.Bulk;
import com.rincoltech.bms.core.imports.ImportReview.BulkOutcome;
import com.rincoltech.bms.core.imports.ImportReview.Outcome;
import com.rincoltech.bms.core.imports.ImportReview.Resolution;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Batch;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.imports.ImportValidation.Issue;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * The review queue of issue #131 on real PostgreSQL as bms_app (spec sections 3.5 and 10.1, FR-IMP-05): every resolution
 * is one append-only row with who, when, before, after and note and one audit row without a value; an override of a
 * blocking issue is refused; a value map makes mapping version 2 and validates exactly the rows it reaches; a second
 * resolver of the same version gets {@code version_conflict}; a changed row comes back to the queue after a new upload and
 * an unchanged one keeps its resolution; the questions for the customer hold no personal value; and another business
 * reaches none of it. Fabricated data only: the golden files of {@code fixtures/imports/golden/} and the fixture mapping.
 */
@ExtendWith(OutputCaptureExtension.class)
class ImportReviewIT extends IntegrationTest {

    static final String ITEMS = "fab-item-list.csv";
    static final String BUYERS = "fab-buyers.csv";
    static final String TILL = "fab-till-roll.csv";

    /** A fabricated phone an operator types in place of a bad one; it may be kept only in the resolution row. */
    static final String TYPED_PHONE = "0700000177";

    @Autowired
    ImportReview review;

    @Autowired
    ImportValidation validation;

    @Autowired
    ImportMappings mappings;

    @Autowired
    ImportUploads uploads;

    @Autowired
    ImportRuns runs;

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("review", false);
        other = TestDatabase.tenant("review-b", false);
    }

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    record Run(UUID runId, Map<String, Batch> batches) {}

    /** A run of the business with the named golden files uploaded through the quarantine and validated. */
    Run validated(TestDatabase.Fixture f, List<GoldenRun.GoldenFile> files) throws IOException {
        Mapping mapping = as(f, () -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, null));
        UUID runId = as(f, () -> runs.open(operator).id());
        Map<String, Batch> batches = new LinkedHashMap<>();
        for (GoldenRun.GoldenFile g : files) {
            batches.put(g.file(), upload(f, runId, g.file(), golden(g.file()), mapping.id(), g.source()));
        }
        TestDatabase.owner()
                .sql("UPDATE import_runs SET cutover_at = ? WHERE id = ?")
                .params(Timestamp.from(GoldenRun.CUTOVER), runId)
                .update();
        as(f, () -> validation.validate(runId));
        return new Run(runId, batches);
    }

    Run validatedAll() throws IOException {
        return validated(t, GoldenRun.FILES);
    }

    static byte[] golden(String file) throws IOException {
        return Files.readAllBytes(GoldenRun.DIR.resolve(file));
    }

    Batch upload(TestDatabase.Fixture f, UUID runId, String file, byte[] bytes, UUID mappingId, String source) {
        Batch batch = as(
                f,
                () -> uploads.upload(
                        runId,
                        new Upload(
                                file,
                                new ByteArrayInputStream(bytes),
                                bytes.length,
                                operator,
                                mappings.parseOptions(mappingId, source))));
        assertThat(batch.status()).as(file).isEqualTo("parsed");
        return batch;
    }

    List<Issue> issues(Batch batch) {
        return as(t, () -> validation.issues(batch.id()));
    }

    List<Issue> onLine(Batch batch, int line) {
        return issues(batch).stream().filter(i -> i.line() == line).toList();
    }

    Issue issue(Batch batch, int line, String code) {
        List<Issue> found =
                onLine(batch, line).stream().filter(i -> i.code().equals(code)).toList();
        assertThat(found).as("%s line %d %s", batch.fileName(), line, code).hasSize(1);
        return found.getFirst();
    }

    /** The one issue of a code on a field of a line, where the line has the code on more than one field. */
    Issue issue(Batch batch, int line, String code, String field) {
        List<Issue> found = onLine(batch, line).stream()
                .filter(i -> i.code().equals(code) && field.equals(i.field()))
                .toList();
        assertThat(found)
                .as("%s line %d %s %s", batch.fileName(), line, code, field)
                .hasSize(1);
        return found.getFirst();
    }

    Outcome resolve(Issue i, String kind, String value, String note) {
        return as(
                t, () -> review.resolve(i.id(), new Resolution(kind, i.version(), value, null, null, note), operator));
    }

    /** The SHA-256 of a staged row's cells, as a resolution records it. */
    static String shaOf(UUID rowId) {
        return TestDatabase.owner()
                .sql("SELECT encode(sha256(convert_to(raw::text, 'UTF8')), 'hex') FROM import_rows WHERE id = ?")
                .param(rowId)
                .query(String.class)
                .single();
    }

    long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    HttpHeaders headers(TestDatabase.Fixture f) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", f.slug());
        h.add("X-Dev-User-Id", operator.toString());
        h.add("X-Dev-Permissions", "core.imports.manage");
        h.add("X-Dev-Branch-Ids", "*");
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    ResponseEntity<JsonNode> send(TestDatabase.Fixture f, HttpMethod method, String path, Object body) {
        return http.exchange(path, method, new HttpEntity<>(body, headers(f)), JsonNode.class);
    }

    // ---------------------------------------------------------------------------------------------
    // Acceptance criterion 1: one append-only resolution row and one audit row each.
    // ---------------------------------------------------------------------------------------------

    @Test
    void everyResolutionIsOneAppendOnlyRowWithWhoWhenBeforeAfterAndNoteAndOneAuditRowWithoutAValue(
            CapturedOutput output) throws IOException {
        Run run = validatedAll();
        Batch items = run.batches().get(ITEMS);
        Batch buyers = run.batches().get(BUYERS);
        Batch till = run.batches().get(TILL);

        resolve(issue(items, 4, "CODE_DUPLICATE"), "override", null, "The twin is the same fabricated kettle");
        resolve(issue(items, 5, "REQUIRED_MISSING"), "exclude_row", null, "The fabricated source has no name here");
        Issue fraction = issue(items, 6, "MONEY_FRACTION");
        assertThat(fraction.suggestion()).containsEntry("value", "1501");
        Outcome accepted = resolve(fraction, "accept_suggestion", null, null);
        Outcome edited = resolve(issue(buyers, 5, "TYPE_INVALID", "phone"), "edit_value", TYPED_PHONE, null);
        Issue unknown = issue(till, 5, "REF_UNKNOWN", "item");
        UUID itemRow = issues(items).stream()
                .filter(i -> i.line() == 4)
                .findFirst()
                .orElseThrow()
                .rowId();
        as(
                t,
                () -> review.resolve(
                        unknown.id(),
                        new Resolution("link_row", unknown.version(), null, itemRow, null, "Same fabricated item"),
                        operator));

        // The accepted suggestion and the edit validated their row again: the issue is gone, the value typed.
        assertThat(accepted.rowsValidated()).isEqualTo(1);
        assertThat(accepted.issues()).extracting(Issue::code).doesNotContain("MONEY_FRACTION");
        assertThat(edited.issues())
                .filteredOn(i -> "phone".equals(i.field()))
                .extracting(Issue::code)
                .doesNotContain("TYPE_INVALID");
        assertThat(TestDatabase.owner()
                        .sql("SELECT normalised ->> 'price' FROM import_rows WHERE batch_id = ? AND source_row_no = 6")
                        .param(items.id())
                        .query(String.class)
                        .single())
                .isEqualTo("1501");
        assertThat(TestDatabase.owner()
                        .sql("SELECT normalised ->> 'phone' FROM import_rows WHERE batch_id = ? AND source_row_no = 5")
                        .param(buyers.id())
                        .query(String.class)
                        .single())
                .isEqualTo("+256700000177");
        // The exclusion resolved every open issue of its row and marks the row; the row stays in the batch.
        assertThat(onLine(items, 5))
                .allMatch(i -> "resolved".equals(i.status()) && "exclude_row".equals(i.resolution()));
        assertThat(count(
                        "SELECT count(*) FROM import_rows WHERE batch_id = ? AND source_row_no = 5 AND excluded_by IS NOT"
                                + " NULL",
                        items.id()))
                .isEqualTo(1);
        assertThat(issue(items, 4, "CODE_DUPLICATE").status()).isEqualTo("resolved");
        assertThat(issue(till, 5, "REF_UNKNOWN", "item").resolution()).isEqualTo("link_row");
        assertThat(issue(till, 5, "REF_UNKNOWN", "customer").status()).isEqualTo("open");
        assertThat(count(
                        "SELECT count(*) FROM import_rows WHERE batch_id = ? AND source_row_no = 5 AND linked_row_id = ?",
                        till.id(),
                        itemRow))
                .isEqualTo(1);

        // Who, when, the kind, before, after, note: one row each, in the order made.
        List<String> rows = TestDatabase.owner()
                .sql("""
                        SELECT resolution || '|' || code || '|' || coalesce(field, '-') || '|' || source_row_no || '|'
                               || (resolved_by = ?) || '|' || (created_at IS NOT NULL) || '|'
                               || coalesce(before_value::text, '-') || '|' || coalesce(after_value::text, '-') || '|'
                               || coalesce(note, '-')
                          FROM import_issue_resolutions WHERE run_id = ? ORDER BY seq
                        """)
                .params(operator, run.runId())
                .query(String.class)
                .list();
        assertThat(rows)
                .containsExactly(
                        "override|CODE_DUPLICATE|code|4|true|true|-|-|The twin is the same fabricated kettle",
                        "exclude_row|REQUIRED_MISSING|name|5|true|true|-|{\"excluded\": true}|The fabricated source has no"
                                + " name here",
                        "accept_suggestion|MONEY_FRACTION|price|6|true|true|{\"value\": \"1500.50\"}|{\"value\":"
                                + " \"1501\"}|-",
                        "edit_value|TYPE_INVALID|phone|5|true|true|{\"value\": \"12345\"}|{\"value\": \"" + TYPED_PHONE
                                + "\"}|-",
                        "link_row|REF_UNKNOWN|item|5|true|true|-|{\"sha\": \"" + shaOf(itemRow) + "\", \"row_id\": \""
                                + itemRow + "\", \"row_key\": \"col:item_list:FAB -001\", \"occurrence\": 1}|Same"
                                + " fabricated item");
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.import_issue.resolved'",
                        t.tenantId()))
                .isEqualTo(5);

        // No value and no note in a message, an original, an audit row or a log line; the typed phone only in its row.
        String audit = String.join(
                "\n",
                TestDatabase.owner()
                        .sql("SELECT data::text FROM audit_log WHERE tenant_id = ? AND action LIKE 'core.import%'")
                        .param(t.tenantId())
                        .query(String.class)
                        .list());
        List<String> stored = TestDatabase.owner()
                .sql(
                        "SELECT message || ' ' || coalesce(original_value::text, '') FROM import_issues WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(String.class)
                .list();
        List<String> leaks = new ArrayList<>(GoldenRun.PERSONAL);
        leaks.addAll(List.of(TYPED_PHONE, "700000177", "+256700000177", "fabricated kettle", "Same fabricated"));
        for (String value : leaks) {
            assertThat(audit).as("audit").doesNotContain(value);
            assertThat(output.getAll()).as("logs").doesNotContain(value);
        }
        // The bad phone's cell text too; not looked for in the logs, where any id could hold five such digits.
        assertThat(audit).doesNotContain("12345");
        assertThat(stored).noneMatch(s -> s.contains(TYPED_PHONE) || s.contains("700000177"));
        assertThat(count(
                        "SELECT count(*) FROM import_issue_resolutions WHERE run_id = ? AND after_value::text LIKE ?",
                        run.runId(),
                        "%" + TYPED_PHONE + "%"))
                .isEqualTo(1);

        // Append-only for every role.
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("DELETE FROM import_issue_resolutions WHERE run_id = ?")
                        .param(run.runId())
                        .update())
                .hasStackTraceContaining("append-only");
        UUID any = TestDatabase.owner()
                .sql("SELECT id FROM import_issue_resolutions WHERE run_id = ? LIMIT 1")
                .param(run.runId())
                .query(UUID.class)
                .single();
        assertThatThrownBy(() ->
                        ImportValidationIT.asApp(t, "UPDATE import_issue_resolutions SET note = 'x' WHERE id = ?", any))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> ImportValidationIT.asApp(t, "DELETE FROM import_issue_resolutions WHERE id = ?", any))
                .hasStackTraceContaining("permission denied");

        // A full validation afterwards keeps every decision: the review is applied, not lost.
        as(t, () -> validation.validate(run.runId()));
        assertThat(issue(items, 4, "CODE_DUPLICATE").status()).isEqualTo("resolved");
        assertThat(onLine(items, 5)).allMatch(i -> "resolved".equals(i.status()));
        assertThat(onLine(items, 6)).extracting(Issue::code).doesNotContain("MONEY_FRACTION");
        assertThat(onLine(buyers, 5))
                .filteredOn(i -> "phone".equals(i.field()))
                .extracting(Issue::code)
                .containsExactly("VALUE_NORMALISED");
        assertThat(issue(till, 5, "REF_UNKNOWN", "item").status()).isEqualTo("resolved");
    }

    @Test
    void manyIssuesOfOneCodeAreResolvedTogetherWithOneRowEach() throws IOException {
        Run run = validatedAll();

        BulkOutcome o = as(
                t,
                () -> review.resolveAll(
                        run.runId(),
                        new Bulk("CODE_DUPLICATE", null, "override", null, "Fabricated duplicates become one record"),
                        operator));

        assertThat(o.resolved()).isEqualTo(3);
        assertThat(count(
                        "SELECT count(*) FROM import_issue_resolutions WHERE run_id = ? AND bulk_id = ?",
                        run.runId(),
                        o.bulkId()))
                .isEqualTo(3);
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.import_issue.resolved'",
                        t.tenantId()))
                .isEqualTo(3);
        assertThat(count(
                        "SELECT count(*) FROM import_issues i JOIN import_batches b ON b.id = i.batch_id WHERE b.run_id"
                                + " = ? AND i.code = 'CODE_DUPLICATE' AND i.status = 'open'",
                        run.runId()))
                .isZero();
        // A blocking code is never overridden, in bulk either: nothing is written.
        assertCode(
                () -> as(
                        t,
                        () -> review.resolveAll(
                                run.runId(),
                                new Bulk("REQUIRED_MISSING", null, "override", null, "Not allowed"),
                                operator)),
                "override_not_allowed");
        assertThat(count("SELECT count(*) FROM import_issue_resolutions WHERE run_id = ?", run.runId()))
                .isEqualTo(3);
    }

    // ---------------------------------------------------------------------------------------------
    // Acceptance criterion 2: override on a blocking issue is refused.
    // ---------------------------------------------------------------------------------------------

    @Test
    void anOverrideOfABlockingIssueIsRefusedAndAnOverrideWithoutANoteToo() throws IOException {
        Run run = validatedAll();
        Issue blocking = issue(run.batches().get(ITEMS), 5, "REQUIRED_MISSING");

        assertCode(() -> resolve(blocking, "override", null, "Fabricated reason"), "override_not_allowed");
        assertCode(
                () -> resolve(issue(run.batches().get(ITEMS), 4, "CODE_DUPLICATE"), "override", null, " "),
                "validation_failed");

        assertThat(count("SELECT count(*) FROM import_issue_resolutions WHERE run_id = ?", run.runId()))
                .isZero();
        Issue after = issue(run.batches().get(ITEMS), 5, "REQUIRED_MISSING");
        assertThat(after.status()).isEqualTo("open");
        assertThat(after.version()).isEqualTo(blocking.version());
        // Over HTTP as well: 422 with the code.
        ResponseEntity<JsonNode> refused = send(
                t,
                HttpMethod.POST,
                "/api/v1/imports/issues/" + blocking.id() + "/resolve",
                Map.of("resolution", "override", "version", blocking.version(), "note", "Fabricated reason"));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("override_not_allowed");
    }

    // ---------------------------------------------------------------------------------------------
    // Acceptance criterion 3: map_value makes version n + 1 and validates exactly the rows it reaches.
    // ---------------------------------------------------------------------------------------------

    @Test
    void mapValueMakesMappingVersionTwoAndValidatesExactlyTheRowsItReaches() throws IOException {
        Run run = validatedAll();
        Batch till = run.batches().get(TILL);
        Issue returned = onLine(till, 9).stream()
                .filter(i -> "TYPE_INVALID".equals(i.code()) && "kind".equals(i.field()))
                .findFirst()
                .orElseThrow();
        Map<UUID, String> before = new LinkedHashMap<>();
        run.batches().values().forEach(b -> issues(b).forEach(i -> before.put(i.id(), i.version() + "|" + i.status())));

        Outcome o = resolve(returned, "map_value", "adjustment", null);

        assertThat(o.mappingVersion()).isEqualTo(2);
        assertThat(o.rowsValidated()).isEqualTo(1);
        List<Mapping> versions = as(t, () -> mappings.versions("fab-shop"));
        assertThat(versions).extracting(Mapping::version).containsExactly(1, 2);
        assertThat(sourceOf(versions.get(1), "till_roll").get("enum_maps").toString())
                .contains("Returned=adjustment");
        assertThat(sourceOf(versions.get(0), "till_roll").get("enum_maps").toString())
                .doesNotContain("Returned");
        // Every batch of the run now reads version 2.
        assertThat(TestDatabase.owner()
                        .sql("SELECT DISTINCT mapping_id FROM import_batches WHERE run_id = ? AND status = 'parsed'")
                        .param(run.runId())
                        .query(UUID.class)
                        .list())
                .containsExactly(versions.get(1).id());
        // The row the map reaches: its kind is typed, its other issue written again with the next version.
        assertThat(onLine(till, 9)).extracting(Issue::code).containsExactly("REQUIRED_MISSING");
        Issue sold = issue(till, 9, "REQUIRED_MISSING");
        assertThat(sold.version())
                .isEqualTo(Integer.parseInt(before.get(sold.id()).split("\\|")[0]) + 1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT normalised ->> 'kind' FROM import_rows WHERE batch_id = ? AND source_row_no = 9")
                        .param(till.id())
                        .query(String.class)
                        .single())
                .isEqualTo("adjustment");
        // Every other issue of the run is untouched: same id, version and status.
        Map<UUID, String> after = new LinkedHashMap<>();
        run.batches()
                .values()
                .forEach(b -> issues(b).stream()
                        .filter(i -> !(b.id().equals(till.id()) && i.line() == 9))
                        .forEach(i -> after.put(i.id(), i.version() + "|" + i.status())));
        assertThat(after).hasSize(before.size() - 2);
        after.forEach((id, state) -> assertThat(state).as("issue %s", id).isEqualTo(before.get(id)));
        // The resolution names both versions, the label and the value; a value of a personal field is never mapped.
        assertThat(TestDatabase.owner()
                        .sql("SELECT before_value::text || '|' || after_value::text FROM import_issue_resolutions"
                                + " WHERE run_id = ? AND resolution = 'map_value'")
                        .param(run.runId())
                        .query(String.class)
                        .single())
                .isEqualTo("{\"mapping_version\": 1}|{\"label\": \"Returned\", \"value\": \"adjustment\","
                        + " \"mapping_version\": 2}");
        assertCode(
                () -> resolve(
                        issue(run.batches().get(BUYERS), 5, "TYPE_INVALID", "phone"),
                        "map_value",
                        "+256700000188",
                        null),
                "field_is_personal");
        assertThat(as(t, () -> mappings.versions("fab-shop"))).hasSize(2);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> sourceOf(Mapping mapping, String source) {
        return (Map<String, Object>) ((Map<String, Object>) mapping.document().get("sources")).get(source);
    }

    // ---------------------------------------------------------------------------------------------
    // Acceptance criterion 4: the second resolver gets version_conflict.
    // ---------------------------------------------------------------------------------------------

    @Test
    void twoResolversOfOneIssueTheSecondGetsVersionConflict() throws Exception {
        Run run = validatedAll();
        Issue duplicate = issue(run.batches().get(ITEMS), 4, "CODE_DUPLICATE");
        Map<String, Object> body =
                Map.of("resolution", "override", "version", duplicate.version(), "note", "Fabricated same kettle");
        String path = "/api/v1/imports/issues/" + duplicate.id() + "/resolve";

        // Two at once on the same version: the run's lock puts them one after the other, and the second is refused.
        Callable<HttpStatus> call = () -> HttpStatus.valueOf(
                send(t, HttpMethod.POST, path, body).getStatusCode().value());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpStatus> first = pool.submit(call);
            Future<HttpStatus> second = pool.submit(call);
            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        ResponseEntity<JsonNode> again = send(t, HttpMethod.POST, path, body);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("version_conflict");
        assertThat(count("SELECT count(*) FROM import_issue_resolutions WHERE issue_id = ?", duplicate.id()))
                .isEqualTo(1);

        // An edit validates its row again and the issue it fixed is gone: the second editor gets the conflict too.
        Issue price = onLine(run.batches().get(ITEMS), 7).stream()
                .filter(i -> "price".equals(i.field()))
                .findFirst()
                .orElseThrow();
        resolve(price, "edit_value", "1000", null);
        assertCode(() -> resolve(price, "edit_value", "2000", null), "version_conflict");
    }

    // ---------------------------------------------------------------------------------------------
    // Acceptance criterion 5: after a new upload, a changed row comes back; an unchanged row keeps its resolution.
    // ---------------------------------------------------------------------------------------------

    @Test
    void aChangedRowComesBackToTheQueueAndAnUnchangedRowKeepsItsResolution() throws IOException {
        Run run = validated(t, List.of(GoldenRun.FILES.getFirst()));
        Batch items = run.batches().get(ITEMS);
        resolve(issue(items, 4, "CODE_DUPLICATE"), "override", null, "The twin is the same fabricated kettle");
        resolve(issue(items, 5, "REQUIRED_MISSING"), "exclude_row", null, "The fabricated source has no name here");
        // Lines 9 and 10 share one reference with different cells: resolving line 10 never marks line 9 changed.
        resolve(issue(items, 10, "CODE_DUPLICATE"), "override", null, "The copy is the same fabricated tray");
        assertThat(onLine(items, 9)).isEmpty();
        // The customer sends the file again with line 4 changed and line 5 as it was.
        String original = new String(golden(ITEMS), StandardCharsets.UTF_8);
        String changed = original.replace(
                "FAB -001,Fabricated Kettle Twin,pcs,45000", "FAB -001,Fabricated Kettle Twin,pcs,46000");
        assertThat(changed).isNotEqualTo(original);
        as(t, () -> uploads.cancelBatch(items.id()));
        UUID mapping = as(t, () -> mappings.latest("fab-shop").orElseThrow().id());
        Batch again = upload(t, run.runId(), ITEMS, changed.getBytes(StandardCharsets.UTF_8), mapping, "item_list");

        as(t, () -> validation.validate(run.runId()));

        // The changed row is back in the queue: the review is asked again and its old override no longer applies.
        assertThat(onLine(again, 4))
                .extracting(Issue::code)
                .containsExactly("ROW_CHANGED_SINCE_REVIEW", "CODE_DUPLICATE");
        assertThat(onLine(again, 4)).allMatch(i -> "open".equals(i.status()));
        Issue changedRow = issue(again, 4, "ROW_CHANGED_SINCE_REVIEW");
        assertThat(changedRow.severity()).isEqualTo("blocking");
        assertThat(changedRow.message()).isEqualTo("Line 4 changed since it was reviewed; review it again.");
        assertThat(changedRow.suggestion()).containsEntry("review", "as_uploaded");
        // The unchanged row keeps its exclusion, made on the batch that was cancelled.
        assertThat(onLine(again, 5))
                .allMatch(i -> "resolved".equals(i.status()) && "exclude_row".equals(i.resolution()));
        assertThat(count(
                        "SELECT count(*) FROM import_rows WHERE batch_id = ? AND source_row_no = 5 AND excluded_by IS NOT"
                                + " NULL",
                        again.id()))
                .isEqualTo(1);
        // The duplicate pair: line 10 keeps its override, line 9 is not taken for a changed row.
        assertThat(onLine(again, 10)).extracting(Issue::code).containsExactly("DUPLICATE_SOURCE_REF", "CODE_DUPLICATE");
        assertThat(issue(again, 10, "CODE_DUPLICATE").status()).isEqualTo("resolved");
        assertThat(issue(again, 10, "DUPLICATE_SOURCE_REF").status()).isEqualTo("open");
        assertThat(onLine(again, 9)).isEmpty();

        // Reviewed again as it now is: the question goes away and stays away; the warning is the operator's to decide.
        resolve(changedRow, "accept_suggestion", null, null);
        assertThat(onLine(again, 4)).extracting(Issue::code).containsExactly("CODE_DUPLICATE");
        as(t, () -> validation.validate(run.runId()));
        assertThat(onLine(again, 4)).extracting(Issue::code).containsExactly("CODE_DUPLICATE");
        assertThat(issue(again, 4, "CODE_DUPLICATE").status()).isEqualTo("open");
    }

    // ---------------------------------------------------------------------------------------------
    // Lines repeated verbatim (review of #868, must-fix 1, and the Lead's ruling on it).
    // JUSTIFICATION-A3: the tests the review's must-fix 1 and the Lead ask for, one per kind.
    // ---------------------------------------------------------------------------------------------

    /** One line of the fabricated item list, repeated verbatim from line 9 on. */
    static final String TWIN = "FAB-008,Fabricated Tray,pcs,800.50,Goods,yes,";

    record Twins(UUID runId, Batch items) {}

    /** The golden item list with lines 9 onwards one line repeated verbatim, {@code copies} times. */
    static byte[] twinsFile(int copies) throws IOException {
        StringBuilder file = new StringBuilder(new String(golden(ITEMS), StandardCharsets.UTF_8)
                .replace("FAB-008,Fabricated Tray,pcs,800,Goods,yes,", TWIN)
                .replace("FAB-008,Fabricated Tray Copy,pcs,800,Goods,yes,", TWIN));
        for (int i = 2; i < copies; i++) {
            file.append(TWIN).append('\n');
        }
        return file.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A run with three verbatim copies on lines 9, 10 and 11, validated. */
    Twins threeCopies() throws IOException {
        Mapping mapping = as(t, () -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, null));
        UUID runId = as(t, () -> runs.open(operator).id());
        Batch items = upload(t, runId, ITEMS, twinsFile(3), mapping.id(), "item_list");
        as(t, () -> validation.validate(runId));
        assertThat(onLine(items, 9)).extracting(Issue::code).containsExactly("MONEY_FRACTION");
        for (int line : new int[] {10, 11}) {
            assertThat(onLine(items, line))
                    .extracting(Issue::code)
                    .containsExactly("MONEY_FRACTION", "DUPLICATE_SOURCE_REF", "CODE_DUPLICATE");
        }
        return new Twins(runId, items);
    }

    @Test
    void anExclusionOfOneOfIdenticalLinesNeverReachesTheOthers() throws IOException {
        Twins run = threeCopies();
        Batch items = run.items();

        resolve(issue(items, 10, "MONEY_FRACTION"), "exclude_row", null, "The fabricated line is entered twice");
        as(t, () -> validation.validate(run.runId()));

        // Only the second copy is left out; the first and third keep their own issues open.
        assertThat(onLine(items, 10))
                .allMatch(i -> "resolved".equals(i.status()) && "exclude_row".equals(i.resolution()));
        assertThat(onLine(items, 9)).allMatch(i -> "open".equals(i.status()));
        assertThat(onLine(items, 11)).allMatch(i -> "open".equals(i.status()));
        assertThat(onLine(items, 11))
                .extracting(Issue::code)
                .containsExactly("MONEY_FRACTION", "DUPLICATE_SOURCE_REF", "CODE_DUPLICATE");
        assertThat(count("SELECT count(*) FROM import_rows WHERE batch_id = ? AND excluded_by IS NOT NULL", items.id()))
                .isEqualTo(1);
        assertThat(count(
                        "SELECT count(*) FROM import_rows WHERE batch_id = ? AND source_row_no = 10 AND excluded_by IS"
                                + " NOT NULL",
                        items.id()))
                .isEqualTo(1);
        assertThat(TestDatabase.owner()
                        .sql("SELECT occurrence || '/' || copies FROM import_issue_resolutions WHERE run_id = ?")
                        .param(run.runId())
                        .query(String.class)
                        .single())
                .isEqualTo("2/3");
        // A bulk resolution names a batch of its own run only.
        assertCode(
                () -> as(
                        t,
                        () -> review.resolveAll(
                                run.runId(),
                                new Bulk("MONEY_FRACTION", UUID.randomUUID(), "override", null, "Fabricated"),
                                operator)),
                "not_found");
    }

    @Test
    void anOverrideOfOneOfIdenticalLinesNeverReachesTheOthers() throws IOException {
        Twins run = threeCopies();
        Batch items = run.items();

        resolve(issue(items, 10, "CODE_DUPLICATE"), "override", null, "The fabricated copy is the same tray");
        as(t, () -> validation.validate(run.runId()));

        assertThat(issue(items, 10, "CODE_DUPLICATE").status()).isEqualTo("resolved");
        assertThat(issue(items, 10, "CODE_DUPLICATE").resolution()).isEqualTo("override");
        assertThat(issue(items, 11, "CODE_DUPLICATE").status()).isEqualTo("open");
        assertThat(issue(items, 11, "CODE_DUPLICATE").resolutionId()).isNull();
        assertThat(onLine(items, 9)).allMatch(i -> "open".equals(i.status()));
    }

    @Test
    void anEditOfOneOfIdenticalLinesNeverReachesTheOthers() throws IOException {
        Twins run = threeCopies();
        Batch items = run.items();

        resolve(issue(items, 10, "MONEY_FRACTION"), "edit_value", "800", null);
        as(t, () -> validation.validate(run.runId()));

        assertThat(onLine(items, 10)).extracting(Issue::code).doesNotContain("MONEY_FRACTION");
        assertThat(issue(items, 9, "MONEY_FRACTION").status()).isEqualTo("open");
        assertThat(issue(items, 11, "MONEY_FRACTION").status()).isEqualTo("open");
        assertThat(TestDatabase.owner()
                        .sql("SELECT source_row_no || '=' || coalesce(normalised ->> 'price', '-') FROM import_rows"
                                + " WHERE batch_id = ? AND source_row_no >= 9 ORDER BY source_row_no")
                        .param(items.id())
                        .query(String.class)
                        .list())
                .containsExactly("9=-", "10=800", "11=-");
    }

    /**
     * A new upload that holds another number of identical copies never guesses which copy a decision was for: every copy
     * comes back to the queue as changed and the old decision applies to none (Lead ruling on #868).
     */
    @Test
    void aNewUploadWithAnotherNumberOfIdenticalLinesSendsThemBackToTheQueue() throws IOException {
        Twins run = threeCopies();
        resolve(issue(run.items(), 10, "MONEY_FRACTION"), "exclude_row", null, "The fabricated line is entered twice");
        as(t, () -> uploads.cancelBatch(run.items().id()));
        UUID mapping = as(t, () -> mappings.latest("fab-shop").orElseThrow().id());
        Batch again = upload(t, run.runId(), ITEMS, twinsFile(2), mapping, "item_list");

        as(t, () -> validation.validate(run.runId()));

        assertThat(onLine(again, 9))
                .extracting(Issue::code)
                .containsExactly("ROW_CHANGED_SINCE_REVIEW", "MONEY_FRACTION");
        assertThat(onLine(again, 10))
                .extracting(Issue::code)
                .containsExactly(
                        "ROW_CHANGED_SINCE_REVIEW", "MONEY_FRACTION", "DUPLICATE_SOURCE_REF", "CODE_DUPLICATE");
        assertThat(onLine(again, 9)).allMatch(i -> "open".equals(i.status()));
        assertThat(onLine(again, 10)).allMatch(i -> "open".equals(i.status()));
        assertThat(count("SELECT count(*) FROM import_rows WHERE batch_id = ? AND excluded_by IS NOT NULL", again.id()))
                .isZero();
    }

    // ---------------------------------------------------------------------------------------------
    // Acceptance criterion 6: the questions for the customer hold no personal value.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theQuestionListIsNumberedWithFileAndLineAndHoldsNoPersonalValue() throws IOException {
        Run run = validatedAll();
        Batch buyers = run.batches().get(BUYERS);
        Issue phone = issue(buyers, 5, "TYPE_INVALID", "phone");
        Issue nin = issue(buyers, 6, "TYPE_INVALID", "nin");
        Issue asked = as(t, () -> review.askCustomer(phone.id(), phone.version(), true, operator));
        assertThat(asked.askCustomer()).isTrue();
        assertThat(asked.version()).isEqualTo(phone.version() + 1);
        assertThat(asked.status()).isEqualTo("open");
        as(t, () -> review.askCustomer(nin.id(), nin.version(), true, operator));
        as(
                t,
                () -> review.resolveAll(
                        run.runId(), new Bulk("NIN_DUPLICATE", null, "ask_customer", null, null), operator));
        // An issue asked about and then resolved is no longer a question.
        Issue duplicateRef = issue(buyers, 7, "CODE_DUPLICATE");
        as(t, () -> review.askCustomer(duplicateRef.id(), duplicateRef.version(), true, operator));
        resolve(issue(buyers, 7, "CODE_DUPLICATE"), "override", null, "Fabricated Buyer Epsilon is Alpha");

        ResponseEntity<JsonNode> list =
                send(t, HttpMethod.GET, "/api/v1/imports/runs/" + run.runId() + "/questions", null);
        ResponseEntity<String> csv = http.exchange(
                "/api/v1/imports/runs/" + run.runId() + "/questions.csv",
                HttpMethod.GET,
                new HttpEntity<>(null, headers(t)),
                String.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> questions = new ArrayList<>();
        list.getBody()
                .get("items")
                .forEach(q -> questions.add(q.get("number").asInt() + "|"
                        + q.get("file").asString() + "|" + q.get("line").asInt() + "|"
                        + q.get("field").asString() + "|" + q.get("code").asString()));
        assertThat(questions)
                .containsExactly(
                        "1|fab-buyers.csv|5|phone|TYPE_INVALID",
                        "2|fab-buyers.csv|5|nin|NIN_DUPLICATE",
                        "3|fab-buyers.csv|6|nin|TYPE_INVALID");
        assertThat(csv.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(csv.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(csv.getBody())
                .startsWith("number,file,line,field,code,question\n1,\"fab-buyers.csv\",5,\"phone\",\"TYPE_INVALID\","
                        + "\"phone on fab-buyers.csv line 5 is not a valid phone.\"\n");
        List<String> leaks = new ArrayList<>(GoldenRun.PERSONAL);
        leaks.addAll(List.of("12345", "Epsilon", "Alpha"));
        for (String value : leaks) {
            assertThat(list.getBody().toString()).as("questions").doesNotContain(value);
            assertThat(csv.getBody()).as("questions as CSV").doesNotContain(value);
        }
        // Only the file sent to the customer is audited, not the list read on a screen (review of #868, item 10).
        assertThat(count(
                        "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.import_run.questions_exported'",
                        t.tenantId()))
                .isEqualTo(1);
        // The queue filters on the questions too.
        ResponseEntity<JsonNode> queue =
                send(t, HttpMethod.GET, "/api/v1/imports/runs/" + run.runId() + "/issues?ask_customer=true", null);
        assertThat(queue.getBody().get("items").size()).isEqualTo(3);
    }

    // ---------------------------------------------------------------------------------------------
    // Tenancy.
    // ---------------------------------------------------------------------------------------------

    @Test
    void anotherBusinessReachesNoneOfTheQueue() throws IOException {
        Run run = validatedAll();
        Issue duplicate = issue(run.batches().get(ITEMS), 4, "CODE_DUPLICATE");
        String runPath = "/api/v1/imports/runs/" + run.runId();
        String issuePath = "/api/v1/imports/issues/" + duplicate.id();

        List<ResponseEntity<JsonNode>> answers = List.of(
                send(other, HttpMethod.GET, runPath + "/issues", null),
                send(other, HttpMethod.GET, runPath + "/questions", null),
                send(
                        other,
                        HttpMethod.POST,
                        issuePath + "/resolve",
                        Map.of("resolution", "override", "version", duplicate.version(), "note", "Fabricated")),
                send(
                        other,
                        HttpMethod.POST,
                        runPath + "/issues/bulk-resolve",
                        Map.of("code", "CODE_DUPLICATE", "resolution", "override", "note", "Fabricated")),
                send(
                        other,
                        HttpMethod.PUT,
                        issuePath + "/ask-customer",
                        Map.of("ask_customer", true, "version", duplicate.version())));

        assertThat(answers).allSatisfy(a -> assertThat(a.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(count("SELECT count(*) FROM import_issue_resolutions WHERE run_id = ?", run.runId()))
                .isZero();
        assertThat(issue(run.batches().get(ITEMS), 4, "CODE_DUPLICATE").version())
                .isEqualTo(duplicate.version());
        // The business's own call answers, and an unknown property is refused (fail-on-unknown-properties).
        assertThat(send(t, HttpMethod.GET, runPath + "/issues", null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(send(
                                t,
                                HttpMethod.POST,
                                issuePath + "/resolve",
                                Map.of("resolution", "override", "version", 1, "note", "Fabricated", "extra", 1))
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
