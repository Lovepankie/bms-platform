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
@org.springframework.context.annotation.Import(TestUploadHooks.Config.class)
class ImportUploadReviewIT extends IntegrationTest {

    static final String CSV = "Code,Description\nFAB-1,Fabricated item\nFAB-2,Another fabricated item\n";

    @Autowired
    ImportUploads uploads;

    @Autowired
    TestUploadHooks hooks;

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
        hooks.afterFetch = null;
    }

    <T> T as(Supplier<T> work) {
        return TenantContext.callAs(t.tenantId(), work);
    }

    Batch upload(UUID runId, String name, String content) {
        byte[] bytes = TestFiles.csv(content);
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
        hooks.afterFetch = () -> uploads.cancelBatch(batch.id());
        assertThatThrownBy(() -> as(() -> uploads.parse(batch.id(), null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_status_transition"));
        hooks.afterFetch = null;

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

        hooks.afterFetch = () -> runs.transition(runId, "ready");
        assertThatThrownBy(() -> as(() -> uploads.parse(batch.id(), null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid_status_transition"));
        hooks.afterFetch = null;

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
                        UPDATE import_batches SET status = 'failed', failure_code = 'FILE_UNREADABLE',
                               failure_reason = 'fabricated'
                         WHERE id = ?
                        """).param(kept.id()).update();
        TestDatabase.owner()
                .sql("DELETE FROM import_rows WHERE batch_id = ?")
                .param(kept.id())
                .update();

        // A batch refused by its options keeps its file through the cleanup, to be read again.
        Batch byOptions = as(() -> uploads.upload(
                openRun,
                new Upload(
                        "mapped.csv",
                        new ByteArrayInputStream(TestFiles.csv("Name\nFAB-3\n")),
                        -1,
                        operator,
                        new ImportUploads.ParseOptions(null, java.util.Set.of("Code"), java.util.Set.of()))));
        assertThat(byOptions.failureCode()).isEqualTo(Parsing.HEADER_NOT_FOUND);
        String byOptionsKey = objectKey(byOptions.id());

        QuarantineCleanup.Result result = cleanup.cleanAll();

        assertThat(as(() -> quarantine.open(byOptionsKey))).isPresent();

        assertThat(result.failed()).isZero();
        assertThat(as(() -> uploads.batch(staged.id())).status()).isEqualTo("cancelled");
        assertThat(rows(staged.id())).isZero();
        assertThat(objectKey(staged.id())).isNull();
        assertThat(as(() -> quarantine.open(stagedKey))).isEmpty();
        assertThat(objectKey(kept.id())).isNull();
        assertThat(as(() -> quarantine.open(keptKey))).isEmpty();
    }
}
