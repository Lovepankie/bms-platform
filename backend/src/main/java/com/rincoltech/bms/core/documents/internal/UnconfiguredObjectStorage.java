package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.kernel.ApiException;
import java.time.Duration;
import org.springframework.http.HttpStatus;

/**
 * Stands in for R2 while a server has no bucket configured (Hillary's #23 review, blocker 2): the app
 * starts and everything else works; any document upload or download answers 503 until the R2 settings
 * are provided and the app restarts.
 */
class UnconfiguredObjectStorage implements ObjectStorage {

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        throw unavailable();
    }

    /** Nothing was ever stored here. */
    @Override
    public void delete(String key) {}

    @Override
    public String signedGetUrl(String key, Duration ttl, String downloadName, String contentType) {
        throw unavailable();
    }

    private static ApiException unavailable() {
        return new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "storage_unavailable",
                "Document storage unavailable",
                "Document storage is not configured on this server yet.");
    }
}
