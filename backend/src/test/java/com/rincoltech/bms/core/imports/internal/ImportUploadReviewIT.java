package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.QuarantineStorage;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Batch;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.ByteArrayInputStream;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The findings of the review of #790 against real PostgreSQL: a parse whose batch is cancelled, or whose run moves to
 * ready, while its file is being fetched writes nothing and leaves the batch as the other change left it; a value the
 * database cannot store fails the batch with {@code FILE_UNREADABLE} instead of leaving it stuck behind a server error;
 * and the daily job, as the scheduler runs it, cleans a cancelled run and a file whose delete failed. Fabricated data only.
 */
class ImportUploadReviewIT extends IntegrationTest {

    static final String CSV = "Code,Description\nFAB-1,Fabricated item\nFAB-2,Another fabricated item\n";

    @Autowired
    ImportUploads uploads;

    @Autowired
    UploadTestSeams seams;

    @Autowired
    ImportRuns runs;

    @Autowired
    QuarantineStorage quarantine;

    @Autowired
    QuarantineCleanup cleanup;

    TestDatabase.Fixture t;
    final UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("upload-review", false);
    }

    @AfterEach
    void unhook() {
        seams.afterFetch = null;
    }

    <T> T as(Supplier<T> work) {
        return TenantContext.callAs(t.tenantId(), work);
    }

    Batch upload(UUID runId, String name, String content) {
        return upload(runId, name, TestFiles.csv(content));
    }

    Batch upload(UUID runId, String name, byte[] bytes) {
        return as(() ->
                uploads.upload(runId, new Upload(name, new ByteArrayInputStream(bytes), bytes.length, operator, null)));
    }

    long rows(UUID batchId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM import_rows WHERE batch_id = ?")
                .param(batchId)
                .query(Long.class)
                .single();
    }

    String objectKey(UUID batchId) {
        return TestDatabase.owner()
                .sql("SELECT object_key FROM import_batches WHERE id = ?")
                .param(batchId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    @Test
    void aBatchCancelledWhileItsFileIsFetchedIsNotBroughtBack() {
        UUID runId = as(() -> runs.open(operator).id());
        Batch batch = upload(runId, "fabricated.csv", CSV);
        assertThat(rows(batch.id())).isEqualTo(2);

        // The cancel lands between the fetch and the write, as a second operator's would.
        seams.afterFetch = () -> uploads.cancelBatch(batch.id());
        assertThatThrownBy(() -> as(() -> uploads.parse(batch.id(), null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_status_transition"));
        seams.afterFetch = null;

        Batch after = as(() -> uploads.batch(batch.id()));
        assertThat(after.status()).isEqualTo("cancelled");
        assertThat(rows(batch.id())).as("no staged rows come back").isZero();
        assertThat(objectKey(batch.id())).isNull();
    }

    @Test
    void aRunMovedToReadyWhileAFileIsFetchedKeepsItsRows() {
        UUID runId = as(() -> runs.open(operator).id());
        Batch batch = upload(runId, "fabricated.csv", CSV);
        as(() -> runs.transition(runId, "in_review"));

        // Ready needs a dry run since #132, which a file read without a mapping cannot have (no template writes it).
        // The run is moved on by the owner, as a second operator's ready would land, and the parse must still see it.
        seams.afterFetch = () -> TestDatabase.owner()
                .sql("UPDATE import_runs SET status = 'ready', ready_at = now() WHERE id = ?")
                .param(runId)
                .update();
        assertThatThrownBy(() -> as(() -> uploads.parse(batch.id(), null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_status_transition"));
        seams.afterFetch = null;

        assertThat(as(() -> runs.get(runId)).status()).isEqualTo("ready");
        assertThat(as(() -> uploads.batch(batch.id())).status()).isEqualTo("parsed");
        assertThat(rows(batch.id())).isEqualTo(2);
    }

    @Test
    void aValueTheDatabaseCannotStoreFailsTheBatchWithACode() {
        UUID runId = as(() -> runs.open(operator).id());
        Batch batch = upload(runId, "fabricated.jsonl", "{\"code\":\"FAB-1\"}\n{\"code\":\"FAB\\u0000\"}\n");
        assertThat(batch.status()).isEqualTo("failed");
        assertThat(batch.failureCode()).isEqualTo(Parsing.FILE_UNREADABLE);
        assertThat(rows(batch.id())).isZero();
        assertThat(objectKey(batch.id())).isNull();
        Batch csv = upload(runId, "fabricated.csv", "Code\n" + "FAB-1\n".repeat(1_000) + "FAB\u00002\n");
        assertThat(csv.failureCode())
                .as("a NUL past the bytes the upload sniffs")
                .isEqualTo(Parsing.FILE_UNREADABLE);
    }

    // JUSTIFICATION-A3: the two tests the review of 6e4f8c0 asks for, a long sheet name and a refused final UPDATE.
    @Test
    void aSheetNameOverOneHundredCharactersFailsTheBatchWithACode() throws Exception {
        // The column holds 100 characters; a hand-edited workbook can name its sheet anything.
        UUID runId = as(() -> runs.open(operator).id());
        String sheet = ReaderLimitsTest.SHEET
                + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>FAB-1</t></is></c></row>" + ReaderLimitsTest.END;
        byte[] book = ReaderLimitsTest.workbook(
                "N".repeat(120), ReaderLimitsTest.sst("<si><t>Code</t></si>"), sheet, null, java.util.Map.of());
        Batch batch = upload(runId, "fabricated.xlsx", book);
        assertThat(batch.status()).isEqualTo("failed");
        assertThat(batch.failureCode()).isEqualTo(Parsing.FILE_UNREADABLE);
        assertThat(rows(batch.id())).isZero();
        assertThat(objectKey(batch.id())).as("the file is deleted").isNull();
    }

    @Test
    void aDatabaseRefusalOfTheBatchsOwnUpdateFailsTheBatchWithACode() {
        // A refusal the parser cannot foresee, raised on the final UPDATE of the batch (a value too long, 22001), so
        // the whole write is proven mapped and not only the row inserts.
        TestDatabase.owner().sql("""
                CREATE FUNCTION test_refuse_parsed() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'fabricated refusal' USING ERRCODE = '22001';
                END $$
                """).update();
        TestDatabase.owner().sql("""
                CREATE TRIGGER test_refuse_parsed BEFORE UPDATE ON import_batches FOR EACH ROW
                 WHEN (NEW.status = 'parsed' AND NEW.source_filename = 'refused-update.csv')
                 EXECUTE FUNCTION test_refuse_parsed()
                """).update();
        try {
            UUID runId = as(() -> runs.open(operator).id());
            Batch batch = upload(runId, "refused-update.csv", CSV);
            assertThat(batch.status()).isEqualTo("failed");
            assertThat(batch.failureCode()).isEqualTo(Parsing.FILE_UNREADABLE);
            assertThat(rows(batch.id()))
                    .as("the rows inserted before the UPDATE are rolled back")
                    .isZero();
            assertThat(objectKey(batch.id())).isNull();
        } finally {
            TestDatabase.owner()
                    .sql("DROP TRIGGER test_refuse_parsed ON import_batches")
                    .update();
            TestDatabase.owner().sql("DROP FUNCTION test_refuse_parsed()").update();
        }
    }

    @Test
    void theDailyJobCleansACancelledRunAndAFileWhoseDeleteFailed() {
        UUID cancelledRun = as(() -> runs.open(operator).id());
        Batch staged = upload(cancelledRun, "fabricated.csv", CSV);
        String stagedKey = objectKey(staged.id());
        as(() -> runs.transition(cancelledRun, "cancelled"));

        // A failed batch whose file's delete failed at the time: it still names its file.
        UUID openRun = as(() -> runs.open(operator).id());
        Batch kept = upload(openRun, "other.csv", "Code\nFAB-9\n");
        String keptKey = objectKey(kept.id());
        TestDatabase.owner().sql("""
                        UPDATE import_batches SET status = 'failed', failure_code = 'HEADER_NOT_FOUND',
                               failure_reason = 'fabricated'
                         WHERE id = ?
                        """).param(kept.id()).update();
        TestDatabase.owner()
                .sql("DELETE FROM import_rows WHERE batch_id = ?")
                .param(kept.id())
                .update();

        QuarantineCleanup.Result result = cleanup.cleanAll();

        assertThat(result.failed()).isZero();
        assertThat(as(() -> uploads.batch(staged.id())).status()).isEqualTo("cancelled");
        assertThat(rows(staged.id())).isZero();
        assertThat(objectKey(staged.id())).isNull();
        assertThat(as(() -> quarantine.open(stagedKey))).isEmpty();
        assertThat(objectKey(kept.id())).isNull();
        assertThat(as(() -> quarantine.open(keptKey))).isEmpty();
    }
}
