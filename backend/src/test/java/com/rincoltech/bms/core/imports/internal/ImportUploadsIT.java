package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.QuarantineStorage;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Batch;
import com.rincoltech.bms.core.imports.ImportUploads.ParseOptions;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.core.tenancy.RlsIsolationIT;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The quarantined upload (issue #128) against real PostgreSQL as bms_app and the fake object storage: a file is stored
 * encrypted under its business's quarantine prefix and opens only with its run's key; its rows reach {@code import_rows}
 * and nothing else is written; every refusal code leaves no rows; a run of one business is invisible to another; a file at
 * the row limit is streamed in blocks; no file content reaches the logs; and the cleanup removes the files of abandoned
 * uploads. Fabricated data only: the names and phones below are made up for this test.
 */
@ExtendWith(OutputCaptureExtension.class)
class ImportUploadsIT extends IntegrationTest {

    static final String NAME = "Zawadi Fabricatedname";
    static final String PHONE = "0700123987";
    static final String FILE_NAME = "Fabricated Customer List.csv";
    static final String CSV = "Code,Customer,Phone,Balance\r\n"
            + "FAB-1," + NAME + "," + PHONE + ",15000\r\n"
            + "FAB-2,Another Madeup Person,0700456123,2500\r\n";

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

    @Autowired
    UploadSettings.ImportKey importKey;

    @Autowired
    UploadSettings.Properties settings;

    @Autowired
    com.rincoltech.bms.kernel.BusinessClock clock;

    @TempDir
    Path dir;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("upload", false);
        other = TestDatabase.tenant("upload-b", false);
    }

    @AfterEach
    void diskBack() {
        seams.freeDisk = null;
    }

    <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    UUID openRun(TestDatabase.Fixture f) {
        return as(f, () -> runs.open(operator).id());
    }

    Batch upload(TestDatabase.Fixture f, UUID runId, String name, byte[] bytes, ParseOptions options) {
        return as(
                f,
                () -> uploads.upload(
                        runId, new Upload(name, new ByteArrayInputStream(bytes), bytes.length, operator, options)));
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

    byte[] stored(TestDatabase.Fixture f, String key) {
        return as(f, () -> {
            try (InputStream in = quarantine.open(key).orElseThrow()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    byte[] runKey(TestDatabase.Fixture f, UUID runId) throws IOException {
        byte[] wrapped = TestDatabase.owner()
                .sql("SELECT data_key_wrapped FROM import_runs WHERE id = ?")
                .param(runId)
                .query(byte[].class)
                .single();
        return QuarantineCipher.unwrap(importKey.bytes().orElseThrow(), wrapped, f.tenantId(), runId);
    }

    /** The size of the largest encrypted work file in the work directory now, 0 when there is none. */
    long largestWorkFile() {
        try (var files = Files.list(settings.work())) {
            return files.filter(p -> p.getFileName().toString().startsWith(UploadService.TEMP_PREFIX))
                    .mapToLong(p -> p.toFile().length())
                    .max()
                    .orElse(0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Row counts of every table the business owns except the staging area and the audit log. */
    Map<String, Long> outsideImports(TestDatabase.Fixture f) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : RlsIsolationIT.tenantOwnedTables()) {
            if (table.startsWith("import_") || table.equals("audit_log") || table.equals("tenants")) {
                continue;
            }
            counts.put(
                    table,
                    TestDatabase.owner()
                            .sql("SELECT count(*) FROM " + table + " WHERE tenant_id = ?")
                            .param(f.tenantId())
                            .query(Long.class)
                            .single());
        }
        return counts;
    }

    static void refused(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void aFileIsStoredEncryptedParsedIntoImportRowsAndNothingElseIsWritten(CapturedOutput output) throws IOException {
        Map<String, Long> before = outsideImports(t);
        UUID runId = openRun(t);
        Batch batch = upload(
                t, runId, FILE_NAME, TestFiles.csv(CSV), new ParseOptions(null, Set.of("Code"), Set.of("phone")));

        assertThat(batch.status()).isEqualTo("parsed");
        assertThat(batch.fileType()).isEqualTo("csv");
        assertThat(batch.fileName()).isEqualTo(FILE_NAME);
        assertThat(batch.sizeBytes()).isEqualTo(CSV.length());
        assertThat(batch.sha256()).matches("[0-9a-f]{64}");
        assertThat(batch.encoding()).isEqualTo("utf-8");
        assertThat(batch.headerRowNo()).isEqualTo(1);
        assertThat(batch.rowCount()).isEqualTo(2);
        assertThat(batch.columnCount()).isEqualTo(4);
        assertThat(rows(batch.id())).isEqualTo(2);
        assertThat(as(t, () -> runs.get(runId)).status())
                .as("the first file moves the run on")
                .isEqualTo("uploaded");
        String raw = TestDatabase.owner()
                .sql("SELECT raw::text FROM import_rows WHERE batch_id = ? AND source_row_no = 2")
                .param(batch.id())
                .query(String.class)
                .single();
        assertThat(raw).contains(NAME).doesNotContain(PHONE).doesNotContain("Phone");

        // Acceptance criterion 1: no vertical or ledger table is written by upload or parse.
        assertThat(outsideImports(t)).isEqualTo(before);

        // Acceptance criterion 2: the stored object opens with the run's key and not without it.
        String key = objectKey(batch.id());
        assertThat(key).startsWith("tenants/" + t.tenantId() + "/quarantine/imports/" + runId + "/");
        byte[] sealed = stored(t, key);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1))
                .doesNotContain(NAME)
                .doesNotContain("Customer");
        byte[] dataKey = runKey(t, runId);
        assertThat(QuarantineCipher.decrypt(new ByteArrayInputStream(sealed), dataKey, batch.id())
                        .readAllBytes())
                .isEqualTo(TestFiles.csv(CSV));
        assertThatThrownBy(() -> QuarantineCipher.decrypt(
                                new ByteArrayInputStream(sealed), QuarantineCipher.newDataKey(), batch.id())
                        .readAllBytes())
                .isInstanceOf(QuarantineCipher.Unreadable.class);

        // Acceptance criterion 5: no file content or personal value in the logs, nor the file's name.
        assertThat(output.getAll())
                .contains("import upload stored: run " + runId)
                .doesNotContain(NAME)
                .doesNotContain(PHONE)
                .doesNotContain("Another Madeup Person")
                .doesNotContain(FILE_NAME);
    }

    @Test
    void everyRefusalAtUploadStoresNothing() {
        UUID runId = openRun(t);
        refused(() -> upload(t, runId, "fabricated.xls", TestFiles.csv(CSV), null), Parsing.FILE_TYPE_UNSUPPORTED);
        refused(() -> upload(t, runId, "fabricated.xlsx", TestFiles.csv(CSV), null), Parsing.FILE_TYPE_UNSUPPORTED);
        refused(
                () -> as(
                        t,
                        () -> uploads.upload(
                                runId,
                                new Upload(
                                        "big.csv", new ByteArrayInputStream(new byte[0]), 26L << 20, operator, null))),
                Parsing.FILE_TOO_LARGE);
        // Streamed past the limit with no size announced: refused while it streams.
        refused(
                () -> as(
                        t,
                        () -> uploads.upload(
                                runId, new Upload("big.csv", new Repeating((25 << 20) + 1), -1, operator, null))),
                Parsing.FILE_TOO_LARGE);
        seams.freeDisk = () -> 10;
        refused(() -> upload(t, runId, "fabricated.csv", TestFiles.csv(CSV), null), "UPLOAD_DISK_LOW");
        seams.freeDisk = null;
        assertThat(as(t, () -> uploads.batches(runId))).isEmpty();
        assertThat(as(t, () -> quarantine.list().toList())).as("no file stored").isEmpty();

        upload(t, runId, "fabricated.csv", TestFiles.csv(CSV), null);
        refused(() -> upload(t, runId, "again.csv", TestFiles.csv(CSV), null), "FILE_ALREADY_UPLOADED");
        assertThat(as(t, () -> uploads.batches(runId))).hasSize(1);
    }

    @Test
    void everyWholeFileRefusalAtParseLeavesAFailedBatchWithNoRowsAndNoFile() {
        UUID runId = openRun(t);
        StringBuilder tooMany = new StringBuilder("Code\n");
        for (int i = 1; i <= 100_001; i++) {
            tooMany.append("FAB-").append(i).append('\n');
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(Parsing.TOO_MANY_ROWS, TestFiles.csv(tooMany.toString()));
        files.put(Parsing.TOO_MANY_COLUMNS, TestFiles.csv(String.join(",", TestFiles.header(201)) + "\nFAB-1\n"));
        files.put(Parsing.HEADER_NOT_FOUND, TestFiles.csv("Name,Qty\nFAB-1,2\n"));
        files.put(Parsing.FILE_UNREADABLE, TestFiles.csv("Code\n\"FAB-1\n"));
        int n = 0;
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Batch b = upload(
                    t,
                    runId,
                    "fabricated-" + n++ + ".csv",
                    e.getValue(),
                    new ParseOptions(null, Set.of("code"), Set.of()));
            assertThat(b.status()).as(e.getKey()).isEqualTo("failed");
            assertThat(b.failureCode()).isEqualTo(e.getKey());
            assertThat(b.failureReason()).doesNotContain("FAB-");
            assertThat(rows(b.id())).as("a whole-file failure creates no rows").isZero();
            assertThat(objectKey(b.id())).as("and keeps no file").isNull();
        }
        assertThat(as(t, () -> quarantine.list().toList())).isEmpty();

        // A batch parsed once and parsed again into a refusal loses its rows too.
        Batch ok = upload(t, runId, "fabricated.csv", TestFiles.csv(CSV), null);
        assertThat(rows(ok.id())).isEqualTo(2);
        Batch again = as(t, () -> uploads.parse(ok.id(), new ParseOptions(null, Set.of("Not there"), Set.of())));
        assertThat(again.failureCode()).isEqualTo(Parsing.HEADER_NOT_FOUND);
        assertThat(rows(ok.id())).isZero();
    }

    @Test
    void aRunIsReadyOnlyWhenEveryFileIsParsedAndTakesNoFilesOnceReady() {
        UUID runId = openRun(t);
        Batch failed = upload(
                t, runId, "bad.csv", TestFiles.csv("Name\nFAB\n"), new ParseOptions(null, Set.of("code"), Set.of()));
        upload(t, runId, "good.csv", TestFiles.csv(CSV), null);
        as(t, () -> runs.transition(runId, "in_review"));
        refused(() -> as(t, () -> runs.transition(runId, "ready")), "invalid_status_transition");
        Batch cancelled = as(t, () -> uploads.cancelBatch(failed.id()));
        assertThat(cancelled.status()).isEqualTo("cancelled");
        assertThat(as(t, () -> runs.transition(runId, "ready")).status()).isEqualTo("ready");
        refused(() -> upload(t, runId, "late.csv", TestFiles.csv("Code\nFAB-9\n"), null), "invalid_status_transition");
    }

    @Test
    void xlsxAndJsonLinesUploadToo() {
        UUID runId = openRun(t);
        Batch book = upload(
                t,
                runId,
                "fabricated.xlsx",
                TestFiles.xlsx(List.of("Code", "Customer"), List.of(List.of("FAB-1", NAME))),
                new ParseOptions(null, Set.of(), Set.of("customer")));
        assertThat(book.status()).isEqualTo("parsed");
        assertThat(book.sheetName()).isEqualTo("Items");
        assertThat(book.encoding()).isNull();
        Batch lines = upload(t, runId, "fabricated.jsonl", TestFiles.csv("{\"code\":\"FAB-2\"}\n"), null);
        assertThat(lines.status()).isEqualTo("parsed");
        assertThat(lines.rowCount()).isEqualTo(1);
        assertThat(lines.headerRowNo()).isNull();
        Batch cp1252 = upload(
                t, runId, "cp.csv", "Code\nCafé\n".getBytes(java.nio.charset.Charset.forName("windows-1252")), null);
        assertThat(cp1252.encoding()).isEqualTo("windows-1252");
        assertThat(cp1252.warnings()).containsExactly(Parsing.ENCODING_CONVERTED);
        assertThat(as(t, () -> uploads.batches(runId))).extracting(Batch::seq).containsExactly(1, 2, 3);
    }

    @Test
    void theFileIsTiedToARunOfTheUploadingBusinessOnly() {
        UUID runOfT = openRun(t);
        Batch batch = upload(t, runOfT, "fabricated.csv", TestFiles.csv(CSV), null);
        String key = objectKey(batch.id());

        // Another business cannot add to the run, nor see, read again or cancel its batch.
        refused(() -> upload(other, runOfT, "fabricated.csv", TestFiles.csv(CSV), null), "not_found");
        refused(() -> as(other, () -> uploads.batch(batch.id())), "not_found");
        refused(() -> as(other, () -> uploads.batches(runOfT)), "not_found");
        refused(() -> as(other, () -> uploads.parse(batch.id(), null)), "not_found");
        refused(() -> as(other, () -> uploads.cancelBatch(batch.id())), "not_found");
        assertThatThrownBy(() -> as(other, () -> quarantine.open(key)))
                .as("the quarantine refuses another business's key")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(as(other, () -> quarantine.list().toList())).isEmpty();

        // A file moved onto another batch of the same run does not open there: nothing is changed or deleted.
        Batch second = upload(t, runOfT, "second.csv", TestFiles.csv("Code\nFAB-7\n"), null);
        String secondKey = objectKey(second.id());
        byte[] first = stored(t, key);
        TenantContext.callAs(t.tenantId(), () -> {
            try {
                Path f = Files.write(dir.resolve("moved.bin"), first);
                // Put the first file's bytes under the second batch's key.
                quarantine.delete(secondKey);
                assertThat(quarantine.put(runOfT, second.id(), f)).isEqualTo(secondKey);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        refused(() -> as(t, () -> uploads.parse(second.id(), null)), "quarantine_unreadable");
        assertThat(as(t, () -> uploads.batch(second.id())).status()).isEqualTo("parsed");
        assertThat(rows(second.id())).isEqualTo(1);
    }

    @Test
    void aFileAtTheRowLimitIsStreamedInBlocksAndEveryRowIsStaged() throws IOException {
        Path csv = dir.resolve("fabricated-at-the-limit.csv");
        TestFiles.writeCsv(csv, TestFiles.header(12), TestFiles.generated(UploadMemoryTest.ROWS, 12));
        UUID runId = openRun(t);
        AtomicInteger largestRead = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        java.util.concurrent.atomic.AtomicLong served = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong onDiskAtEight = new java.util.concurrent.atomic.AtomicLong(-1);
        Batch batch;
        try (InputStream in = new FilterInputStream(Files.newInputStream(csv)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                largestRead.accumulateAndGet(len, Math::max);
                reads.incrementAndGet();
                if (served.get() < (8 << 20) && served.get() + len >= (8 << 20)) {
                    onDiskAtEight.set(largestWorkFile());
                }
                served.addAndGet(len);
                return super.read(b, off, len);
            }
        }) {
            batch = as(
                    t, () -> uploads.upload(runId, new Upload("fabricated-at-the-limit.csv", in, -1, operator, null)));
        }
        assertThat(batch.status()).isEqualTo("parsed");
        assertThat(batch.sizeBytes()).isEqualTo(Files.size(csv));
        assertThat(batch.rowCount()).isEqualTo(UploadMemoryTest.ROWS);
        assertThat(rows(batch.id())).isEqualTo(UploadMemoryTest.ROWS);
        // Bounded memory on the way in: when the eighth megabyte is asked for, all but the last block and segment of
        // the
        // bytes before it are already encrypted on disk, so the upload held a block and a segment, never the file. The
        // heap of the parse at the row limit is proven by UploadMemoryTest, in a JVM capped at the staging heap.
        assertThat(onDiskAtEight.get())
                .as("encrypted bytes on disk when 8 MB had been read")
                .isGreaterThanOrEqualTo((8L << 20) - 3 * 64 * 1024);
        assertThat(largestRead.get()).isLessThanOrEqualTo(64 * 1024);
        assertThat(reads.get()).isGreaterThanOrEqualTo((int) (Files.size(csv) / (64 * 1024)));
        try (var work = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            assertThat(work.filter(p -> p.getFileName().toString().startsWith(UploadService.TEMP_PREFIX))
                            .filter(p -> !p.startsWith(dir))
                            .count())
                    .as("no work file is left behind")
                    .isZero();
        }
    }

    @Test
    void theCleanupRemovesTheFilesOfAbandonedUploads() throws IOException {
        // An upload that died between storage and its batch row: a file no batch names.
        UUID runId = openRun(t);
        Path f = Files.write(dir.resolve("orphan.bin"), new byte[] {1, 2, 3});
        String orphan = as(t, () -> quarantine.put(runId, UUID.randomUUID(), f));
        // A cancelled run with a parsed file.
        UUID cancelledRun = openRun(t);
        Batch parsed = upload(t, cancelledRun, "fabricated.csv", TestFiles.csv(CSV), null);
        String parsedKey = objectKey(parsed.id());
        as(t, () -> runs.transition(cancelledRun, "cancelled"));
        // A live run's file stays.
        UUID liveRun = openRun(t);
        Batch live = upload(t, liveRun, "live.csv", TestFiles.csv("Code\nFAB-5\n"), null);

        // Within the orphan age, the orphan is not touched: an upload may still be finishing.
        QuarantineCleanup.Result first = as(t, cleanup::cleanBound);
        assertThat(first.cancelledBatches()).isEqualTo(1);
        assertThat(first.filesDeleted()).isEqualTo(1);
        assertThat(first.orphansDeleted()).isZero();
        assertThat(rows(parsed.id())).isZero();
        assertThat(objectKey(parsed.id())).isNull();
        assertThat(as(t, () -> uploads.batch(parsed.id())).status()).isEqualTo("cancelled");
        assertThat(as(t, () -> quarantine.open(parsedKey))).isEmpty();
        assertThat(as(t, () -> quarantine.open(orphan))).isPresent();

        QuarantineCleanup.Result later =
                as(t, () -> cleanup.cleanBound(clock.now().plusSeconds(60)));
        assertThat(later.orphansDeleted()).isEqualTo(1);
        assertThat(as(t, () -> quarantine.open(orphan))).isEmpty();
        assertThat(as(t, () -> quarantine.open(objectKey(live.id()))))
                .as("a live file stays")
                .isPresent();
        assertThat(rows(live.id())).isEqualTo(1);

        // Safe to repeat.
        QuarantineCleanup.Result again =
                as(t, () -> cleanup.cleanBound(clock.now().plusSeconds(60)));
        assertThat(again).isEqualTo(new QuarantineCleanup.Result(0, 0, 0, 0, 0));
    }

    @Test
    void theCleanupRemovesWorkFilesACrashLeft() throws IOException {
        Path left = Files.createTempFile(UploadService.TEMP_PREFIX, ".bin");
        Files.setLastModifiedTime(
                left, java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(3 * 86_400)));
        Path fresh = Files.createTempFile(UploadService.TEMP_PREFIX, ".bin");
        try {
            assertThat(cleanup.cleanWorkFiles()).isGreaterThanOrEqualTo(1);
            assertThat(left).doesNotExist();
            assertThat(fresh).as("a work file in use stays").exists();
        } finally {
            Files.deleteIfExists(left);
            Files.deleteIfExists(fresh);
        }
    }

    /** {@code size} bytes of fabricated CSV, made as they are read. */
    static final class Repeating extends InputStream {

        private final byte[] line = "FAB-1,fabricated\n".getBytes(StandardCharsets.US_ASCII);
        private long left;
        private int pos;

        Repeating(long size) {
            this.left = size;
        }

        @Override
        public int read() {
            if (left == 0) {
                return -1;
            }
            left--;
            byte b = line[pos];
            pos = (pos + 1) % line.length;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (left == 0) {
                return -1;
            }
            int n = (int) Math.min(len, left);
            for (int i = 0; i < n; i++) {
                b[off + i] = (byte) read();
            }
            return n;
        }
    }
}
