package com.rincoltech.bms.core.documents.internal;

import java.time.Duration;
import java.util.Optional;

/** The object storage port of chapter 12 section 12.5. Buckets are private; reads go through signed URLs. */
interface ObjectStorage {

    void put(String key, byte[] bytes, String contentType);

    /**
     * Stores a file without reading it into memory (a report's output, up to its size cap). The caller keeps the file
     * until this returns.
     */
    void putFile(String key, java.nio.file.Path file, String contentType);

    /** The object's bytes; empty when there is none. For assets the application serves itself. */
    Optional<byte[]> get(String key);

    /** Removes an object; used when the transaction that stored it rolls back. */
    void delete(String key);

    /** An object's key and when storage last wrote it. */
    record Listed(String key, java.time.Instant lastModified) {}

    /**
     * Every object whose key starts with {@code prefix}, read page by page as the stream is consumed (R2 returns 1000 a
     * page), so a long listing is never held in memory at once. The caller closes the stream. Used by the orphan sweep
     * (issue #39).
     */
    java.util.stream.Stream<Listed> list(String prefix);

    /**
     * Removes up to 1000 objects in one request (S3 {@code DeleteObjects}) and answers the keys it could not remove; a
     * key that was already gone counts as removed. Used by the orphan sweep (issue #39).
     */
    java.util.List<String> deleteAll(java.util.List<String> keys);

    /**
     * A URL that serves the object until {@code ttl} passes, and refuses it after. It is always served
     * as a download ({@code Content-Disposition: attachment}) named {@code downloadName}, with
     * {@code contentType} (the sniffed type) and {@code Cache-Control: private, no-store}, so a stored
     * file never renders inline from the storage origin (Hillary's #23 review, blocker 3).
     */
    String signedGetUrl(String key, Duration ttl, String downloadName, String contentType);
}
