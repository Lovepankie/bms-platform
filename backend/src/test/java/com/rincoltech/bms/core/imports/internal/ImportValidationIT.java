package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportMappings.Mapping;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Batch;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.imports.ImportValidation.Issue;
import com.rincoltech.bms.core.imports.ImportValidation.RunReport;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The validation of issue #130 on real PostgreSQL as bms_app (spec sections 3.4 and 6.5): the golden files uploaded through
 * the quarantine with the fixture mapping are validated into exactly the expected issues, stored in {@code import_issues}
 * with the rows' typed values and references; the messages and the logs of the run hold none of the fixture's names, phones
 * or NINs; validating again gives the same issues; a committed batch is read and never written, by the service or by a
 * direct statement; and another business can neither validate the run nor read its issues, nor lend it a reference.
 * Fabricated data only: {@code fixtures/imports/golden/} and {@code fixtures/imports/fixture_shop_v1.mapping.json}.
 */
@ExtendWith(OutputCaptureExtension.class)
class ImportValidationIT extends IntegrationTest {

    @Autowired
    ImportValidation validation;

    @Autowired
    ImportMappings mappings;

    @Autowired
    ImportUploads uploads;

    @Autowired
    ImportRuns runs;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("validate", false);
        other = TestDatabase.tenant("validate-b", false);
    }

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    /** A run of the business with the golden files uploaded through the quarantine, each with its source of the mapping. */
    record GoldenUpload(UUID runId, Map<String, Batch> batches) {}

    GoldenUpload uploadGolden(TestDatabase.Fixture f, List<GoldenRun.GoldenFile> files) throws IOException {
        Mapping mapping = as(f, () -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, null));
        UUID runId = as(f, () -> runs.open(operator).id());
        Map<String, Batch> batches = new LinkedHashMap<>();
        for (GoldenRun.GoldenFile g : files) {
            byte[] bytes = Files.readAllBytes(GoldenRun.DIR.resolve(g.file()));
            Batch batch = as(
                    f,
                    () -> uploads.upload(
                            runId,
                            new Upload(
                                    g.file(),
                                    new ByteArrayInputStream(bytes),
                                    bytes.length,
                                    operator,
                                    mappings.parseOptions(mapping.id(), g.source()))));
            assertThat(batch.status()).as(g.file()).isEqualTo("parsed");
            batches.put(g.file(), batch);
        }
        TestDatabase.owner()
                .sql("UPDATE import_runs SET cutover_at = ? WHERE id = ?")
                .params(Timestamp.from(GoldenRun.CUTOVER), runId)
                .update();
        return new GoldenUpload(runId, batches);
    }

    /** Every stored issue of the run as the golden report writes it, in file then engine order. */
    List<String> report(GoldenUpload run) {
        List<String> out = new ArrayList<>();
        run.batches().forEach((file, batch) -> {
            for (Issue i : as(t, () -> validation.issues(batch.id()))) {
                out.add(file + "|" + i.line() + "|" + (i.field() == null ? "-" : i.field()) + "|" + i.code() + "|"
                        + i.severity() + "|" + i.message());
            }
        });
        return out;
    }

    /** What the validation stored that a re-run must reproduce: values, references, originals, statuses. */
    List<String> stored(UUID runId) {
        List<String> out = new ArrayList<>(
                TestDatabase.owner().sql("""
                        SELECT b.seq || '|' || r.source_row_no || '|' || coalesce(r.source_ref, '-') || '|'
                               || coalesce(r.normalised::text, '-')
                          FROM import_rows r JOIN import_batches b ON b.id = r.batch_id
                         WHERE b.run_id = ? ORDER BY b.seq, r.source_row_no
                        """).param(runId).query(String.class).list());
        out.addAll(
                TestDatabase.owner().sql("""
                        SELECT b.seq || '|' || i.source_row_no || '|' || i.ordinal || '|' || i.code || '|'
                               || coalesce(i.original_value::text, '-') || '|' || i.status
                          FROM import_issues i JOIN import_batches b ON b.id = i.batch_id
                         WHERE b.run_id = ? ORDER BY b.seq, i.source_row_no, i.ordinal
                        """).param(runId).query(String.class).list());
        return out;
    }

    @Test
    void theGoldenRunIsValidatedIntoTheExpectedIssuesWithNoPersonalValueInMessagesOrLogs(CapturedOutput output)
            throws IOException {
        GoldenUpload run = uploadGolden(t, GoldenRun.FILES);

        RunReport result = as(t, () -> validation.validate(run.runId()));

        List<String> expected =
                Files.readAllLines(GoldenRun.DIR.resolve("expected-issues.txt"), StandardCharsets.UTF_8).stream()
                        .filter(l -> !l.isBlank())
                        .toList();
        assertThat(report(run)).containsExactlyElementsOf(expected);
        assertThat(result.templateKey()).isEqualTo(FixtureShopTemplate.KEY);
        assertThat(result.batches()).hasSize(3);
        assertThat(result.count("info") + result.count("warning") + result.count("blocking"))
                .isEqualTo(expected.size());
        assertThat(as(t, () -> runs.get(run.runId()).status())).isEqualTo("in_review");
        // The typed values are stored with the rows; raw stays as read.
        assertThat(TestDatabase.owner()
                        .sql("SELECT normalised ->> 'phone' FROM import_rows WHERE batch_id = ? AND source_row_no = 3")
                        .param(run.batches().get("fab-buyers.csv").id())
                        .query(String.class)
                        .single())
                .isEqualTo("+256700000101");
        assertThat(TestDatabase.owner()
                        .sql("SELECT raw -> 'Fab Mobile' ->> 'value' FROM import_rows WHERE batch_id = ? AND"
                                + " source_row_no = 3")
                        .param(run.batches().get("fab-buyers.csv").id())
                        .query(String.class)
                        .single())
                .isEqualTo("0700000101");
        // Acceptance criterion 4: no name, phone or NIN of the fixture in a message, the audit row or a log line.
        List<String> messages = TestDatabase.owner()
                .sql("SELECT message FROM import_issues WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(String.class)
                .list();
        String audit = TestDatabase.owner()
                .sql("SELECT data::text FROM audit_log WHERE tenant_id = ? AND action = 'core.import_run.validated'")
                .param(t.tenantId())
                .query(String.class)
                .single();
        for (String personal : GoldenRun.PERSONAL) {
            assertThat(messages).noneMatch(m -> m.contains(personal));
            assertThat(audit).doesNotContain(personal);
            assertThat(output.getAll()).as("a personal value in the logs").doesNotContain(personal);
        }
    }

    @Test
    void validatingAgainGivesIdenticalIssuesValuesAndReferences() throws IOException {
        GoldenUpload run = uploadGolden(t, GoldenRun.FILES);
        as(t, () -> validation.validate(run.runId()));
        List<String> firstReport = report(run);
        List<String> firstStored = stored(run.runId());

        as(t, () -> validation.validate(run.runId()));

        assertThat(report(run)).isEqualTo(firstReport);
        assertThat(stored(run.runId())).isEqualTo(firstStored);
    }

    @Test
    void aCommittedBatchIsReadForItsCodesAndNeverWritten() throws IOException, SQLException {
        GoldenUpload run = uploadGolden(t, GoldenRun.FILES);
        as(t, () -> validation.validate(run.runId()));
        UUID items = run.batches().get("fab-item-list.csv").id();
        TestDatabase.owner()
                .sql("UPDATE import_batches SET status = 'committed', committed_at = now() WHERE id = ?")
                .param(items)
                .update();
        List<String> before =
                stored(run.runId()).stream().filter(s -> s.startsWith("1|")).toList();

        RunReport again = as(t, () -> validation.validate(run.runId()));

        assertThat(again.batches())
                .extracting(ImportValidation.BatchReport::batchId)
                .doesNotContain(items);
        assertThat(stored(run.runId()).stream().filter(s -> s.startsWith("1|")).toList())
                .isEqualTo(before);
        // The till roll still finds the items of the committed batch: only the unknown codes are unknown.
        assertThat(report(run))
                .filteredOn(l -> l.contains("|REF_UNKNOWN|"))
                .extracting(l -> l.split("\\|")[1] + "|" + l.split("\\|")[2])
                .containsExactly("5|item", "5|customer");
        // Nor can the application role write it directly.
        assertThatThrownBy(() -> asApp(t, "DELETE FROM import_issues WHERE batch_id = ?", items))
                .hasStackTraceContaining("the issues of a committed import batch never change");
        assertThatThrownBy(() -> asApp(t, "UPDATE import_rows SET normalised = '{}' WHERE batch_id = ?", items))
                .hasStackTraceContaining("the rows of a committed import batch never change");
        assertThatThrownBy(() -> asApp(t, "UPDATE import_rows SET raw = '{}' WHERE batch_id = ?", items))
                .hasStackTraceContaining("permission denied");
        // Nor through the cascade from its rows: the purge of #139 brings its own way past the guard (Lead, #867).
        assertThatThrownBy(() -> asApp(t, "DELETE FROM import_rows WHERE batch_id = ?", items))
                .hasStackTraceContaining("the issues of a committed import batch never change");
    }

    @Test
    void anotherBusinessCanNeitherValidateNorReadNorLendAReference() throws IOException {
        GoldenUpload run = uploadGolden(t, GoldenRun.FILES);
        // The other business holds the item the till roll names but this business does not.
        Mapping mapping = as(other, () -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, null));
        UUID otherRun = as(other, () -> runs.open(operator).id());
        byte[] csv =
                ("Fab Item Code,Fab Item Label,Fab Pack,Fab Asking Price,Fab Line Type,Fab In Use,Fab Shelf Note\r\n"
                                + "FAB-999,Fabricated Other Item,pcs,100,Goods,yes,\r\n")
                        .getBytes(StandardCharsets.UTF_8);
        as(
                other,
                () -> uploads.upload(
                        otherRun,
                        new Upload(
                                "fab-other-items.csv",
                                new ByteArrayInputStream(csv),
                                csv.length,
                                operator,
                                mappings.parseOptions(mapping.id(), "item_list"))));
        as(other, () -> validation.validate(otherRun));

        as(t, () -> validation.validate(run.runId()));

        assertThat(report(run))
                .contains("fab-till-roll.csv|5|item|REF_UNKNOWN|blocking|item on fab-till-roll.csv line 5 names a"
                        + " record of items that is not in the import or the tenant.");
        UUID batch = run.batches().get("fab-item-list.csv").id();
        assertCode(() -> as(other, () -> validation.validate(run.runId())), "not_found");
        assertCode(() -> as(other, () -> validation.issues(batch)), "not_found");
    }

    /**
     * A file read again between the passes (here its parse stamp moves, as a new parse with a new mapping version moves
     * it) is never checked with what pass 1 learned: the batch is refused with {@code batch_changed} and the run does not
     * move to review. A file stored and not yet read refuses the validation before it starts (review of #867).
     * JUSTIFICATION-A3: a new test case for the reviewer's must-fix 1.
     */
    @Test
    void aFileReadAgainDuringTheValidationOrNotYetReadIsRefused() throws IOException {
        GoldenUpload run = uploadGolden(t, GoldenRun.FILES);
        UUID tillRoll = run.batches().get("fab-till-roll.csv").id();
        FixtureShopTemplate.BETWEEN_PASSES = () -> TestDatabase.owner()
                .sql("UPDATE import_batches SET parsed_at = parsed_at + interval '1 second' WHERE id = ?")
                .param(tillRoll)
                .update();
        try {
            assertCode(() -> as(t, () -> validation.validate(run.runId())), "batch_changed");
        } finally {
            FixtureShopTemplate.BETWEEN_PASSES = null;
        }
        assertThat(as(t, () -> runs.get(run.runId()).status())).isEqualTo("uploaded");
        assertThat(as(t, () -> validation.issues(tillRoll))).isEmpty();

        // An earlier batch given a new mapping version, same parse time and row count: nothing of the run is written,
        // not even the batches before or after it whose own stamp did not move (second review of #867).
        UUID buyers = run.batches().get("fab-buyers.csv").id();
        Mapping v2 = as(t, () -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, "a new version"));
        FixtureShopTemplate.BETWEEN_PASSES = () -> TestDatabase.owner()
                .sql("UPDATE import_batches SET mapping_id = ? WHERE id = ?")
                .params(v2.id(), buyers)
                .update();
        try {
            assertCode(() -> as(t, () -> validation.validate(run.runId())), "batch_changed");
        } finally {
            FixtureShopTemplate.BETWEEN_PASSES = null;
        }
        run.batches()
                .values()
                .forEach(b -> assertThat(as(t, () -> validation.issues(b.id())))
                        .as(b.fileName())
                        .isEmpty());
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM import_rows r JOIN import_batches b ON b.id = r.batch_id"
                                + " WHERE b.run_id = ? AND r.normalised IS NOT NULL")
                        .param(run.runId())
                        .query(Long.class)
                        .single())
                .isZero();

        TestDatabase.owner()
                .sql("UPDATE import_batches SET status = 'uploaded' WHERE id = ?")
                .param(tillRoll)
                .update();
        assertCode(() -> as(t, () -> validation.validate(run.runId())), "batch_not_parsed");
    }

    @Test
    void aRunPastReviewIsNotValidated() throws IOException {
        GoldenUpload run = uploadGolden(t, List.of(GoldenRun.FILES.getFirst()));
        TestDatabase.owner()
                .sql("UPDATE import_runs SET status = 'cancelled', cancelled_at = now() WHERE id = ?")
                .param(run.runId())
                .update();

        assertCode(() -> as(t, () -> validation.validate(run.runId())), "run_not_open");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM import_issues WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    /** One statement as bms_app bound to the business, in its own transaction. */
    static int asApp(TestDatabase.Fixture f, String sql, UUID param) throws SQLException {
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement bind = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                bind.setString(1, f.tenantId().toString());
                bind.execute();
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, param);
                return ps.executeUpdate();
            } finally {
                c.rollback();
            }
        }
    }
}
