package com.rincoltech.bms.core.documents;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The quarantine of customer data onboarding (issue #128, ADR-027 decision 2): files an import was given, already
 * encrypted by {@code core.imports} with its run's key, kept in object storage under
 * {@code tenants/<tenant_id>/quarantine/imports/} of the business bound to the thread. No {@code documents} row names them,
 * no signed URL serves them and the orphan sweep of {@code upload/} never lists them; {@code core.imports} owns their
 * lifecycle and its own cleanup. Every method refuses a key outside the bound business's quarantine prefix. No method opens a
 * transaction or touches the database.
 */
public interface QuarantineStorage {

    /** Stores the encrypted file of one batch from disk, streamed; returns its key. */
    String put(UUID runId, UUID batchId, Path encryptedFile);

    /** The stored bytes as a stream the caller closes; empty when the object is gone. */
    Optional<InputStream> open(String key);

    /** Removes one object; a missing object is not an error. */
    void delete(String key);

    /** Every quarantined object of the bound business, read page by page as consumed. The caller closes the stream. */
    Stream<Held> list();

    /** Removes up to 1000 objects in one request and answers the keys that could not be removed. */
    List<String> deleteAll(List<String> keys);

    /** A quarantined object's key and when storage last wrote it. */
    record Held(String key, Instant lastModified) {}
}
