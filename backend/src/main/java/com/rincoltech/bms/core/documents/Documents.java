package com.rincoltech.bms.core.documents;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** What other modules may ask of the documents module. */
public interface Documents {

    /**
     * Checks, stores and records an uploaded file in the caller's transaction (FR-DOC-02, chapter 8
     * section 8.8): JPEG, PNG or PDF by content, at most 5 MB, images re-encoded to drop metadata.
     * The caller has already checked its own permission on the subject.
     */
    StoredDocument upload(Upload upload);

    Optional<StoredDocument> find(UUID documentId);

    /** @param subjectType the owning module's key, for example {@code lending.member} */
    record Upload(String subjectType, UUID subjectId, UUID branchId, byte[] bytes) {}

    record StoredDocument(
            UUID id,
            String docType,
            String subjectType,
            UUID subjectId,
            UUID branchId,
            String contentType,
            long sizeBytes,
            String sha256,
            Instant createdAt,
            UUID createdBy) {}
}
