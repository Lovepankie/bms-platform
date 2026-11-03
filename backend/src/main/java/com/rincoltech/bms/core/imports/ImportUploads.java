package com.rincoltech.bms.core.imports;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The quarantined upload of customer data onboarding (issue #128, spec sections 3.1, 3.2, 6.1 and 6.2, ADR-027 decision 2).
 * Every method works for the business bound to the thread (ADR-003) and only on its own runs; a run or batch of another
 * business is not found. None may be called inside a caller's transaction: each takes short ones of its own and holds none
 * while a file streams.
 *
 * <p>The upload streams the file once: it checks the type from the name and the first bytes, counts the size against the
 * limit, takes the SHA-256, and encrypts it with the run's data key into a temporary file that is the only copy on disk;
 * the encrypted file goes to object storage under the quarantine prefix and a batch row records it. The parse then reads
 * it back, decrypting as it reads, with a streaming reader for its type, and writes its rows into {@code import_rows}
 * 1,000 at a time, in one transaction, so a whole-file problem leaves no rows. Nothing outside the {@code import_*} tables
 * and the audit log is written.
 *
 * <p>Refusals, thrown before anything is stored: {@code FILE_TYPE_UNSUPPORTED} (422), {@code FILE_TOO_LARGE} (413),
 * {@code FILE_ALREADY_UPLOADED} (409), {@code UPLOAD_DISK_LOW} (507, free disk under twice the file),
 * {@code imports_key_missing} (503, no platform import key on this server) and {@code invalid_status_transition} (409, a run
 * past review). The parse ends a batch {@code failed} with one of {@code FILE_TOO_LARGE}, {@code TOO_MANY_ROWS},
 * {@code TOO_MANY_COLUMNS}, {@code HEADER_NOT_FOUND} or {@code FILE_UNREADABLE} and no rows, and the quarantined file is
 * deleted.
 */
public interface ImportUploads {

    /** Stores the file into the run's quarantine, then parses it; returns the batch, {@code parsed} or {@code failed}. */
    Batch upload(UUID runId, Upload upload);

    /**
     * Parses a batch again from its quarantined file with other options (a new mapping version), replacing its rows. Only
     * before the run is ready.
     */
    Batch parse(UUID batchId, ParseOptions options);

    /** Cancels a batch not yet committed: its rows and its quarantined file are deleted. Audited. */
    Batch cancelBatch(UUID batchId);

    Batch batch(UUID batchId);

    List<Batch> batches(UUID runId);

    /**
     * @param fileName the name the file came with; only its extension decides anything
     * @param declaredSize the size the client announced, or -1 when unknown; the limit is enforced on the bytes read
     */
    record Upload(String fileName, InputStream content, long declaredSize, UUID uploadedBy, ParseOptions options) {}

    /**
     * What the mapping says about the file (until #129 brings mappings, the caller passes it).
     *
     * @param sheetName the XLSX sheet to read; blank for the first that is not hidden
     * @param requiredHeaders the header row is the first of the first 20 that holds all of these; empty for the first row
     *     that is not blank
     * @param ignoredColumns columns not stored (spec section 6.1)
     */
    record ParseOptions(String sheetName, Set<String> requiredHeaders, Set<String> ignoredColumns) {

        public static final ParseOptions DEFAULT = new ParseOptions(null, Set.of(), Set.of());

        public ParseOptions {
            requiredHeaders = requiredHeaders == null ? Set.of() : Set.copyOf(requiredHeaders);
            ignoredColumns = ignoredColumns == null ? Set.of() : Set.copyOf(ignoredColumns);
        }
    }

    /** A batch as stored. {@code failureCode} and {@code failureReason} are set only when {@code failed}. */
    record Batch(
            UUID id,
            UUID runId,
            int seq,
            String status,
            String fileName,
            String fileType,
            long sizeBytes,
            String sha256,
            String encoding,
            String sheetName,
            Integer headerRowNo,
            Integer rowCount,
            Integer columnCount,
            List<String> warnings,
            String failureCode,
            String failureReason,
            Instant uploadedAt) {}
}
