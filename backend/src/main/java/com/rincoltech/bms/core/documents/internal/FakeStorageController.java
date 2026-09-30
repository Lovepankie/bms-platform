package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.PublicEndpoint;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the fake's signed URLs in the dev and test profiles, standing in for the bucket. Public:
 * the signature is the authorisation, as it is for an R2 presigned URL (FR-DOC-03, NFR-ISO-05).
 * Hidden from the OpenAPI contract: it does not exist on servers.
 */
@RestController
@ConditionalOnProperty(name = "bms.storage.provider", havingValue = "fake")
@Hidden
class FakeStorageController {

    private final FakeObjectStorage storage;

    FakeStorageController(ObjectStorage storage) {
        this.storage = (FakeObjectStorage) storage;
    }

    @GetMapping(FakeObjectStorage.PATH)
    @PublicEndpoint
    ResponseEntity<byte[]> get(
            @RequestParam("key") String key, @RequestParam("expires") long expires, @RequestParam("sig") String sig) {
        FakeObjectStorage.StoredObject object = storage.read(key, expires, sig)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.FORBIDDEN,
                        "signature_invalid",
                        "Access denied",
                        "The link is invalid or has expired."));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(object.contentType()))
                .body(object.bytes());
    }
}
