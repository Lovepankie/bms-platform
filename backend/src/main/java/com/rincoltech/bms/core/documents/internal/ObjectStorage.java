package com.rincoltech.bms.core.documents.internal;

import java.time.Duration;

/** The object storage port of chapter 12 section 12.5. Buckets are private; reads go through signed URLs. */
interface ObjectStorage {

    void put(String key, byte[] bytes, String contentType);

    /** A URL that serves the object until {@code ttl} passes, and refuses it after. */
    String signedGetUrl(String key, Duration ttl);
}
