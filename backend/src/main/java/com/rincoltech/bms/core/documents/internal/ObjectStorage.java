package com.rincoltech.bms.core.documents.internal;

import java.time.Duration;
import java.util.Optional;

/** The object storage port of chapter 12 section 12.5. Buckets are private; reads go through signed URLs. */
interface ObjectStorage {

    void put(String key, byte[] bytes, String contentType);

    /** The object's bytes; empty when there is none. For assets the application serves itself. */
    Optional<byte[]> get(String key);

    /** Removes an object; used when the transaction that stored it rolls back. */
    void delete(String key);

    /**
     * A URL that serves the object until {@code ttl} passes, and refuses it after. It is always served
     * as a download ({@code Content-Disposition: attachment}) named {@code downloadName}, with
     * {@code contentType} (the sniffed type) and {@code Cache-Control: private, no-store}, so a stored
     * file never renders inline from the storage origin (Hillary's #23 review, blocker 3).
     */
    String signedGetUrl(String key, Duration ttl, String downloadName, String contentType);
}
