package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.QuarantineStorage;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.internal.QuarantineWriter.FileType;
import com.rincoltech.bms.core.imports.internal.SourceParser.StagedRow;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ImportUploads} over {@code import_batches} and {@code import_rows} (migration V129) and the quarantine of
 * {@code core.documents}.
 *
 * <p>No transaction is open while a file streams in, goes to storage or comes back from it: the upload takes one short
 * transaction to make or open the run's key, streams, stores, then one to write the batch row (a failed row write deletes
 * the stored object again). The parse copies the encrypted object to a temporary file, then reads it in one transaction
 * that writes every row, so a whole-file refusal rolls them all back. The temporary files hold encrypted bytes only, are
 * readable by the API's user alone, and are deleted when the call ends; the cleanup removes any a crash left.
 *
 * <p>Logs carry ids, sizes, counts and codes only: never a file name, a header or a cell (spec section 6.5).
 */
@Service
class UploadService implements ImportUploads {

    static final String TEMP_PREFIX = "bms-import-";

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    /** The run states a file may be added in or parsed again in: before the run is ready. */
    private static final Set<String> OPEN = Set.of(RunStates.DRAFT, RunStates.UPLOADED, RunStates.IN_REVIEW);

    private static final String BATCH_COLUMNS = """
            id, run_id, seq, status, source_filename, file_type, size_bytes, source_sha256, encoding, sheet_name,
            header_row_no, row_count, column_count, parse_warnings, failure_code, failure_reason, uploaded_at, mapping_id,
            mapping_source, template_key, template_version, source_headers""";

    private final JdbcClient jdbc;
    private final JdbcTemplate batchJdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final QuarantineStorage quarantine;
    private final UploadSettings.Properties settings;
    private final UploadSettings.ImportKey importKey;
    private final ObjectMapper mapper;
    private final ImportMappings mappings;

    private final Seams seams;

    /**
     * Where a test reaches into an upload (review of #790): the free disk it sees, and a step between fetching a batch's
     * file and writing its rows, where a concurrent cancel or move can land. Production has no such bean, so these
     * defaults are what it runs.
     */
    interface Seams {

        default long freeDisk(Path work) throws IOException {
            return Files.getFileStore(work).getUsableSpace();
        }

        default void afterFetch() {}
    }

    UploadService(
            JdbcClient jdbc,
            DataSource dataSource,
            PlatformTransactionManager transactions,
            AuditLog audit,
            QuarantineStorage quarantine,
            UploadSettings.Properties settings,
            UploadSettings.ImportKey importKey,
            ObjectMapper mapper,
            ImportMappings mappings,
            ObjectProvider<Seams> seams) {
        this.seams = seams.getIfAvailable(() -> new Seams() {});
        this.mappings = mappings;
        this.jdbc = jdbc;
        this.batchJdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactions);
        this.audit = audit;
        this.quarantine = quarantine;
        this.settings = settings;
        this.importKey = importKey;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------------------------------------
    // Upload.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Batch upload(UUID runId, Upload upload) {
        // The caller's stream is closed whatever happens, a refusal before it is read included.
        try (InputStream content = upload.content()) {
            return store(runId, upload, content);
        } catch (IOException e) {
            throw new UncheckedIOException("the upload could not be closed", e);
        }
    }

    private Batch store(UUID runId, Upload upload, InputStream content) {
        requireNoTransaction();
        UUID tenantId = TenantContext.require();
        if (upload.options() != null && upload.options().mappingId() != null) {
            // An unknown source or another business's mapping is refused before the file is stored.
            mappings.parseOptions(upload.options().mappingId(), upload.options().mappingSource());
        }
        long maxBytes = settings.maxFileSize().toBytes();
        FileType type = FileType.fromName(upload.fileName());
        if (type == null) {
            throw refusal(new QuarantineWriter.Refused(
                    Parsing.FILE_TYPE_UNSUPPORTED, "The file is not CSV, XLSX or JSON Lines."));
        }
        if (upload.declaredSize() > maxBytes) {
            throw refusal(QuarantineWriter.tooLarge(maxBytes));
        }
        String fileName = cleanName(upload.fileName());
        byte[] platformKey = platformKey();
        requireDisk(upload.declaredSize() >= 0 ? upload.declaredSize() : maxBytes);
        byte[] dataKey = tx.execute(s -> runKey(runId, tenantId, platformKey));
        UUID batchId = UUID.randomUUID();
        Path temp = tempFile();
        String objectKey = null;
        try {
            QuarantineWriter.Written written;
            try (OutputStream out = QuarantineCipher.encrypt(Files.newOutputStream(temp), dataKey, batchId)) {
                written = QuarantineWriter.copy(content, type, maxBytes, out);
            } catch (QuarantineWriter.Refused e) {
                throw refusal(e);
            } catch (IOException e) {
                throw new UncheckedIOException("the upload could not be read", e);
            }
            if (alreadyUploaded(runId, written.sha256())) {
                throw alreadyUploadedError();
            }
            objectKey = quarantine.put(runId, batchId, temp);
            String key = objectKey;
            try {
                tx.executeWithoutResult(s -> recordBatch(runId, batchId, fileName, type, written, key, upload));
            } catch (DuplicateKeyException e) {
                throw alreadyUploadedError();
            }
            objectKey = null;
            log.info("import upload stored: run {}, batch {}, {} bytes", runId, batchId, written.size());
        } finally {
            Arrays.fill(dataKey, (byte) 0);
            deleteQuietly(temp);
            if (objectKey != null) {
                deleteObjectQuietly(objectKey, batchId);
            }
        }
        return parse(batchId, upload.options() == null ? ParseOptions.DEFAULT : upload.options());
    }

    /** The run's data key, made and wrapped at its first upload; the run must still take files. */
    private byte[] runKey(UUID runId, UUID tenantId, byte[] platformKey) {
        RunRow run = run(runId, true);
        requireOpen(run.status());
        byte[] wrapped = run.wrappedKey();
        if (wrapped == null) {
            byte[] dataKey = QuarantineCipher.newDataKey();
            jdbc.sql("UPDATE import_runs SET data_key_wrapped = ?, updated_at = now() WHERE id = ?")
                    .params(QuarantineCipher.wrap(platformKey, dataKey, tenantId, runId), runId)
                    .update();
            return dataKey;
        }
        return unwrap(platformKey, wrapped, tenantId, runId);
    }

    private void recordBatch(
            UUID runId,
            UUID batchId,
            String fileName,
            FileType type,
            QuarantineWriter.Written written,
            String objectKey,
            Upload upload) {
        RunRow run = run(runId, true);
        requireOpen(run.status());
        int seq = jdbc.sql("SELECT coalesce(max(seq), 0) + 1 FROM import_batches WHERE run_id = ?")
                .param(runId)
                .query(Integer.class)
                .single();
        jdbc.sql("""
                        INSERT INTO import_batches (id, tenant_id, run_id, seq, status, source_filename, source_sha256,
                                                    size_bytes, file_type, object_key, encoding, uploaded_by, uploaded_at)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, 'uploaded', ?, ?, ?, ?, ?, ?, ?, now())
                        """)
                .params(
                        batchId,
                        runId,
                        seq,
                        fileName,
                        written.sha256(),
                        written.size(),
                        type.key,
                        objectKey,
                        type == FileType.XLSX ? null : written.utf8() ? "utf-8" : "windows-1252",
                        upload.uploadedBy())
                .update();
        if (RunStates.DRAFT.equals(run.status())) {
            jdbc.sql("""
                            UPDATE import_runs SET status = 'uploaded', uploaded_at = now(), version = version + 1,
                                   updated_at = now()
                             WHERE id = ?
                            """).param(runId).update();
            auditRun(runId, Map.of("status", RunStates.DRAFT), Map.of("status", RunStates.UPLOADED));
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("run_id", runId);
        after.put("seq", seq);
        after.put("file_type", type.key);
        after.put("size_bytes", written.size());
        after.put("sha256", written.sha256());
        audit.record(AuditLog.Entry.created("core.import_batch.uploaded", "import_batch", batchId, null, after));
    }

    // ---------------------------------------------------------------------------------------------
    // Parse.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Batch parse(UUID batchId, ParseOptions options) {
        requireNoTransaction();
        UUID tenantId = TenantContext.require();
        ParseOptions given = options == null ? ParseOptions.DEFAULT : options;
        // A batch that records a mapping is parsed with that mapping's own options for the source, never the caller's
        // (review of #866): the sheet, header row and required and ignored columns all come from the stored version, a
        // source the mapping lacks is SOURCE_UNKNOWN and another business's mapping is not found.
        ParseOptions opts =
                given.mappingId() == null ? given : mappings.parseOptions(given.mappingId(), given.mappingSource());
        byte[] platformKey = platformKey();
        Held held = tx.execute(s -> {
            Held h = held(batchId, true);
            requireOpen(run(h.runId(), false).status());
            if (!List.of("uploaded", "parsed").contains(h.status()) || h.objectKey() == null) {
                throw invalidTransition(
                        "Only a batch that is uploaded or parsed can be read again; this one is " + h.status() + ".");
            }
            return h;
        });
        requireDisk(held.sizeBytes());
        byte[] dataKey = unwrap(platformKey, held.wrappedKey(), tenantId, held.runId());
        Path temp = null;
        try {
            temp = tempFile();
            try (InputStream in = quarantine.open(held.objectKey()).orElseThrow(UploadService::fileGone)) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("the quarantined file could not be fetched", e);
            }
            seams.afterFetch();
            Path copy = temp;
            Parsing.Limits limits = new Parsing.Limits(
                    settings.maxRows(),
                    settings.maxColumns(),
                    opts.requiredHeaders(),
                    opts.ignoredColumns(),
                    settings.maxFileSize().toBytes(),
                    opts.headerRow());
            try {
                SourceParser.Parsed parsed = tx.execute(s -> readInto(held, copy, dataKey, limits, opts));
                log.info(
                        "import batch parsed: run {}, batch {}, {} rows, {} columns",
                        held.runId(),
                        batchId,
                        parsed.rows(),
                        parsed.columns());
            } catch (Parsing.Failure f) {
                fail(held, f.code, f.getMessage());
            } catch (DataIntegrityViolationException e) {
                // A value PostgreSQL refuses anywhere in the write, the batch's own UPDATE included (review of #790):
                // the file's fault, so the batch fails with a code instead of staying uploaded behind a server error.
                // The message may quote the value and is not kept. A transient error (a lost connection, a lock
                // timeout) is not the file's fault: it stays a server error and the batch keeps its file for a retry.
                log.info("import batch {}: the database refused a value from the file", batchId);
                fail(held, Parsing.FILE_UNREADABLE, "The file holds a value that cannot be stored.");
            } catch (DataAccessException e) {
                // statement_timeout (57014) while the rows were written (review of #790): a retry of the same file
                // would time out again, so the batch fails instead of staying uploaded behind a server error. Spring
                // may give it as a QueryTimeoutException or as a transient error carrying the SQLState; anything
                // else is rethrown.
                if (!queryTimedOut(e)) {
                    throw e;
                }
                log.info("import batch {}: writing its rows timed out", batchId);
                fail(
                        held,
                        Parsing.FILE_TOO_LARGE,
                        "Storing the file's rows took too long; split it and upload the parts.");
            }
        } finally {
            Arrays.fill(dataKey, (byte) 0);
            if (temp != null) {
                deleteQuietly(temp);
            }
        }
        return batch(batchId);
    }

    /**
     * The run and the batch again, under locks, in the transaction that writes (review of #790): the run shared, so it
     * cannot become ready or be cancelled while its file is read, then the batch, so a second parse waits. A batch
     * cancelled, or a run moved on, while the file was being fetched is refused here and nothing is written.
     */
    private boolean stillOpen(Held held) {
        String runStatus = jdbc.sql("SELECT status FROM import_runs WHERE id = ? FOR SHARE")
                .param(held.runId())
                .query(String.class)
                .optional()
                .orElseThrow(ApiException::notFound);
        Held now = held(held.id(), true);
        return OPEN.contains(runStatus)
                && List.of("uploaded", "parsed").contains(now.status())
                && held.objectKey().equals(now.objectKey());
    }

    private SourceParser.Parsed readInto(
            Held held, Path temp, byte[] dataKey, Parsing.Limits limits, ParseOptions opts) {
        if (!stillOpen(held)) {
            throw invalidTransition("The batch or its import changed while its file was read; nothing was written.");
        }
        // The mapping version the batch records (issue #129), read under RLS: one of another business is not found.
        MappingTemplate template = opts.mappingId() == null
                ? null
                : jdbc.sql("SELECT template_key, template_version FROM import_mappings WHERE id = ?")
                        .param(opts.mappingId())
                        .query((rs, n) -> new MappingTemplate(rs.getString(1), rs.getInt(2)))
                        .optional()
                        .orElseThrow(ApiException::notFound);
        jdbc.sql("DELETE FROM import_rows WHERE batch_id = ?").param(held.id()).update();
        SourceParser.Parsed parsed;
        try {
            parsed = SourceParser.parse(
                    FileType.fromKey(held.fileType()),
                    !"windows-1252".equals(held.encoding()),
                    () -> QuarantineCipher.decrypt(
                            new BufferedInputStream(Files.newInputStream(temp), 64 * 1024), dataKey, held.id()),
                    opts.sheetName(),
                    limits,
                    mapper,
                    chunk -> insertRows(held.id(), chunk));
        } catch (QuarantineCipher.Unreadable e) {
            // The file does not open with its own run's key: storage was changed, or the import key is not the one the
            // run was made with. Nothing is marked or deleted, so the operator can find out which.
            log.error("import batch {}: the quarantined file failed its authentication", held.id());
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "quarantine_unreadable",
                    "Quarantined file unreadable",
                    "The stored file does not open with its import's key; ask the platform team.");
        } catch (IOException e) {
            throw new UncheckedIOException("the quarantined file could not be read", e);
        }
        jdbc.sql("""
                        UPDATE import_batches SET status = 'parsed', sheet_name = ?, header_row_no = ?, row_count = ?,
                               column_count = ?, parse_warnings = ?, parsed_at = now(), source_headers = ?,
                               mapping_id = ?, mapping_source = ?, template_key = ?, template_version = ?
                         WHERE id = ?
                        """)
                .params(
                        parsed.sheetName(),
                        parsed.headerRowNo() == 0 ? null : parsed.headerRowNo(),
                        parsed.rows(),
                        parsed.columns(),
                        parsed.warnings().toArray(String[]::new),
                        parsed.headers().toArray(String[]::new),
                        opts.mappingId(),
                        opts.mappingSource(),
                        template == null ? null : template.key(),
                        template == null ? null : template.version(),
                        held.id())
                .update();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("rows", parsed.rows());
        after.put("columns", parsed.columns());
        after.put("ignored_columns", opts.ignoredColumns().size());
        after.put("warnings", parsed.warnings());
        if (opts.mappingId() != null) {
            after.put("mapping_id", opts.mappingId());
        }
        audit.record(new AuditLog.Entry(
                "core.import_batch.parsed", "import_batch", held.id(), null, Map.of("status", held.status()), after));
        return parsed;
    }

    private void insertRows(UUID batchId, List<StagedRow> chunk) {
        List<Object[]> args = new ArrayList<>(chunk.size());
        for (StagedRow r : chunk) {
            args.add(new Object[] {UUID.randomUUID(), batchId, r.sourceRowNo(), r.rawJson()});
        }
        try {
            batchJdbc.batchUpdate("""
                    INSERT INTO import_rows (id, tenant_id, batch_id, source_row_no, raw)
                    VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, CAST(? AS jsonb))
                    """, args);
        } catch (DataIntegrityViolationException e) {
            // A value PostgreSQL refuses to store (review of #790): the file's fault, so the batch fails with a code
            // instead of staying uploaded behind a server error. The message may quote the value and is not kept.
            throw Parsing.unreadable(
                    "A row from line " + chunk.getFirst().sourceRowNo() + " holds a value that cannot be stored.");
        }
    }

    /**
     * A whole-file refusal: the batch is failed with its code, holds no rows, and its file is deleted. When the batch or
     * its run moved on meanwhile (cancelled, ready), it is left as it is: the refusal no longer applies to it.
     */
    private void fail(Held held, String code, String reason) {
        boolean failed = Boolean.TRUE.equals(tx.execute(s -> {
            if (!stillOpen(held)) {
                return false;
            }
            jdbc.sql("DELETE FROM import_rows WHERE batch_id = ?")
                    .param(held.id())
                    .update();
            jdbc.sql("""
                            UPDATE import_batches SET status = 'failed', failure_code = ?, failure_reason = ?, failed_at = now(),
                                   row_count = 0, mapping_id = NULL, mapping_source = NULL, template_key = NULL,
                                   template_version = NULL, source_headers = NULL
                             WHERE id = ?
                            """).params(code, truncate(reason, 300), held.id()).update();
            audit.record(new AuditLog.Entry(
                    "core.import_batch.failed",
                    "import_batch",
                    held.id(),
                    null,
                    Map.of("status", held.status()),
                    Map.of("status", "failed", "code", code)));
            return true;
        }));
        if (failed) {
            log.info("import batch failed: run {}, batch {}, {}", held.runId(), held.id(), code);
            deleteHeld(held.id(), held.objectKey());
        }
    }

    /** Deletes a failed or cancelled batch's file, then forgets its key; a failed delete leaves it for the cleanup. */
    private void deleteHeld(UUID batchId, String objectKey) {
        if (deleteObjectQuietly(objectKey, batchId)) {
            tx.executeWithoutResult(
                    s -> jdbc.sql("""
                            UPDATE import_batches SET object_key = NULL, quarantine_deleted_at = now()
                             WHERE id = ? AND object_key = ?
                            """).params(batchId, objectKey).update());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Cancel and read.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Batch cancelBatch(UUID batchId) {
        requireNoTransaction();
        String objectKey = tx.execute(s -> {
            // The run first, shared, then the batch: the order of the parse, so the two never wait on each other.
            UUID runId = held(batchId, false).runId();
            requireOpen(jdbc.sql("SELECT status FROM import_runs WHERE id = ? FOR SHARE")
                    .param(runId)
                    .query(String.class)
                    .single());
            Held h = held(batchId, true);
            if (List.of("committed", "cancelled").contains(h.status())) {
                throw invalidTransition("The batch is " + h.status() + ", so it cannot be cancelled.");
            }
            jdbc.sql("DELETE FROM import_rows WHERE batch_id = ?")
                    .param(batchId)
                    .update();
            jdbc.sql("""
                            UPDATE import_batches SET status = 'cancelled', cancelled_at = now() WHERE id = ?
                            """).param(batchId).update();
            audit.record(new AuditLog.Entry(
                    "core.import_batch.cancelled",
                    "import_batch",
                    batchId,
                    null,
                    Map.of("status", h.status()),
                    Map.of("status", "cancelled")));
            return h.objectKey();
        });
        if (objectKey != null) {
            deleteHeld(batchId, objectKey);
        }
        return batch(batchId);
    }

    @Override
    public Batch batch(UUID batchId) {
        return tx.execute(s -> jdbc.sql("SELECT " + BATCH_COLUMNS + " FROM import_batches WHERE id = ?")
                .param(batchId)
                .query(UploadService::batchRow)
                .optional()
                .orElseThrow(ApiException::notFound));
    }

    @Override
    public List<Batch> batches(UUID runId) {
        return tx.execute(s -> {
            run(runId, false);
            return jdbc.sql("SELECT " + BATCH_COLUMNS + " FROM import_batches WHERE run_id = ? ORDER BY seq")
                    .param(runId)
                    .query(UploadService::batchRow)
                    .list();
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------------

    private record RunRow(String status, byte[] wrappedKey) {}

    private record MappingTemplate(String key, int version) {}

    private record Held(
            UUID id,
            UUID runId,
            String status,
            String fileType,
            String encoding,
            long sizeBytes,
            String objectKey,
            byte[] wrappedKey) {}

    private RunRow run(UUID runId, boolean forUpdate) {
        return jdbc.sql("SELECT status, data_key_wrapped FROM import_runs WHERE id = ?"
                        + (forUpdate ? " FOR UPDATE" : ""))
                .param(runId)
                .query((rs, n) -> new RunRow(rs.getString("status"), rs.getBytes("data_key_wrapped")))
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    private Held held(UUID batchId, boolean forUpdate) {
        return jdbc.sql("""
                        SELECT b.id, b.run_id, b.status, b.file_type, b.encoding, b.size_bytes, b.object_key,
                               r.data_key_wrapped
                          FROM import_batches b JOIN import_runs r ON r.id = b.run_id
                         WHERE b.id = ?""" + (forUpdate ? " FOR UPDATE OF b" : ""))
                .param(batchId)
                .query((rs, n) -> new Held(
                        rs.getObject("id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("file_type"),
                        rs.getString("encoding"),
                        rs.getLong("size_bytes"),
                        rs.getString("object_key"),
                        rs.getBytes("data_key_wrapped")))
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    private boolean alreadyUploaded(UUID runId, String sha256) {
        return Boolean.TRUE.equals(tx.execute(
                s -> jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM import_batches WHERE run_id = ? AND source_sha256 = ?
                                          AND status NOT IN ('failed', 'cancelled'))
                        """).params(runId, sha256).query(Boolean.class).single()));
    }

    private byte[] platformKey() {
        return importKey
                .bytes()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "imports_key_missing",
                        "Imports unavailable",
                        "This server has no import key yet; ask the platform team."));
    }

    private static byte[] unwrap(byte[] platformKey, byte[] wrapped, UUID tenantId, UUID runId) {
        try {
            return QuarantineCipher.unwrap(platformKey, wrapped, tenantId, runId);
        } catch (QuarantineCipher.Unreadable e) {
            log.error("import run {}: its data key does not open with this server's import key", runId);
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "quarantine_unreadable",
                    "Quarantined file unreadable",
                    "The import's key does not open with this server's import key; ask the platform team.");
        }
    }

    /** Free disk must be at least twice the file: the encrypted copy and room to spare. */
    private void requireDisk(long fileBytes) {
        long usable;
        try {
            Files.createDirectories(settings.work());
            usable = seams.freeDisk(settings.work());
        } catch (IOException e) {
            throw new UncheckedIOException("the import work directory cannot be used", e);
        }
        if (usable < 2 * Math.max(fileBytes, 0)) {
            log.warn("import upload refused: {} bytes free, {} needed", usable, 2 * fileBytes);
            throw new ApiException(
                    HttpStatus.INSUFFICIENT_STORAGE,
                    "UPLOAD_DISK_LOW",
                    "Not enough disk space",
                    "The server has too little free disk for this file now; try again later or ask the platform team.");
        }
    }

    private Path tempFile() {
        try {
            Path dir = Files.createDirectories(settings.work());
            Path temp = Files.createTempFile(dir, TEMP_PREFIX, ".bin");
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                // Not a POSIX file system (a developer's machine): the directory's own rights apply.
            }
            return temp;
        } catch (IOException e) {
            throw new UncheckedIOException("an import work file cannot be made", e);
        }
    }

    private static void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn(
                    "an import work file could not be deleted; the cleanup will: {}",
                    e.getClass().getSimpleName());
        }
    }

    /** @return whether storage confirmed the delete */
    private boolean deleteObjectQuietly(String objectKey, UUID batchId) {
        try {
            quarantine.delete(objectKey);
            return true;
        } catch (RuntimeException e) {
            log.warn("import batch {}: its quarantined file could not be deleted; the cleanup will", batchId);
            return false;
        }
    }

    /** The file's name without any folder, at most 255 characters. */
    static String cleanName(String fileName) {
        String name = fileName.strip().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "");
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    static boolean queryTimedOut(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof QueryTimeoutException
                    || (t instanceof SQLException s && "57014".equals(s.getSQLState()))) {
                return true;
            }
        }
        return false;
    }

    /** At most {@code max} characters, never ending on half of a surrogate pair. */
    static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max);
    }

    private void auditRun(UUID runId, Map<String, Object> before, Map<String, Object> after) {
        audit.record(new AuditLog.Entry("core.import_run.status_changed", "import_run", runId, null, before, after));
    }

    private static void requireOpen(String runStatus) {
        if (!OPEN.contains(runStatus)) {
            throw invalidTransition(
                    "The import is " + RunStates.words(runStatus) + ", so its files can no longer change.");
        }
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An import upload takes its own short transactions; call it outside one");
        }
        TenantContext.require();
    }

    private static ApiException refusal(QuarantineWriter.Refused e) {
        HttpStatus status =
                Parsing.FILE_TOO_LARGE.equals(e.code) ? HttpStatus.CONTENT_TOO_LARGE : HttpStatus.UNPROCESSABLE_CONTENT;
        return new ApiException(status, e.code, "File refused", e.getMessage());
    }

    private static ApiException alreadyUploadedError() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "FILE_ALREADY_UPLOADED",
                "File already uploaded",
                "This import already holds this file.");
    }

    private static ApiException fileGone() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "quarantine_file_missing",
                "Quarantined file missing",
                "The stored file of this batch is gone; upload it again.");
    }

    private static ApiException invalidTransition(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "invalid_status_transition", "Invalid status transition", detail);
    }

    private static Batch batchRow(ResultSet rs, int n) throws SQLException {
        Array warnings = rs.getArray("parse_warnings");
        Array headers = rs.getArray("source_headers");
        Timestamp uploaded = rs.getTimestamp("uploaded_at");
        return new Batch(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getInt("seq"),
                rs.getString("status"),
                rs.getString("source_filename"),
                rs.getString("file_type"),
                rs.getLong("size_bytes"),
                rs.getString("source_sha256"),
                rs.getString("encoding"),
                rs.getString("sheet_name"),
                rs.getObject("header_row_no", Integer.class),
                rs.getObject("row_count", Integer.class),
                rs.getObject("column_count", Integer.class),
                warnings == null ? List.of() : List.of((String[]) warnings.getArray()),
                rs.getString("failure_code"),
                rs.getString("failure_reason"),
                uploaded == null ? null : uploaded.toInstant(),
                rs.getObject("mapping_id", UUID.class),
                rs.getString("mapping_source"),
                rs.getString("template_key"),
                rs.getObject("template_version", Integer.class),
                headers == null ? List.of() : List.of((String[]) headers.getArray()));
    }
}
