package com.rincoltech.bms.core.documents;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    /**
     * The checks and the image re-encode of {@link #upload}, with no transaction: call it before
     * opening one, so a database connection is never held while waiting for a re-encode slot.
     */
    Prepared prepare(byte[] bytes);

    /**
     * Like {@link #prepare} for an image the owner shows to the public (a tenant logo): PNG, JPEG or
     * WebP by content only, never SVG, within the policy's size and dimensions; downscaled to the
     * long-edge limit and re-encoded (PNG and WebP become PNG), so no metadata survives. No
     * transaction, for the same reason as {@link #prepare}.
     */
    Prepared prepareImage(byte[] bytes, ImagePolicy policy);

    /**
     * The limits of {@link #prepareImage}: bytes in, shortest side at least, longest edge at most,
     * and the largest canvas that is decoded at all (0 keeps the module's 16 megapixel default).
     */
    record ImagePolicy(int maxBytes, int minShortSide, int maxLongEdge, long maxPixels) {}

    /**
     * Type and checksum of a document that its owning module serves publicly by design (the tenant
     * logo), read without a permission check. Empty unless the document belongs to this subject type
     * and its subject is the current tenant, so it cannot be used to read anyone's other documents.
     * Only {@code core.tenancy} calls it (an architecture test enforces that).
     */
    Optional<AssetMeta> publicAssetMeta(String subjectType, UUID documentId);

    /** The bytes of a public asset, under the same subject rule as {@link #publicAssetMeta}. */
    Optional<byte[]> publicAssetBytes(String subjectType, UUID documentId);

    record AssetMeta(String contentType, String sha256, long sizeBytes) {}

    /** Stores and records a prepared file in the caller's transaction. */
    StoredDocument store(Prepared prepared, String subjectType, UUID subjectId, UUID branchId);

    /** A checked file ready to store: the bytes as they will be kept, their type and checksum. */
    record Prepared(byte[] bytes, String contentType, String extension, String sha256) {}

    Optional<StoredDocument> find(UUID documentId);

    /**
     * ADR-034: renders {@code templates/documents/<template>.html} (Thymeleaf, XML mode, every value
     * escaped) to an A4 PDF with the embedded Open Sans. No transaction and no storage: the caller
     * stores it with {@link #putGenerated} and {@link #recordGenerated}, or streams it. Only
     * {@code data:} URIs are ever loaded, so a template value can never make the renderer read a file
     * or fetch a URL.
     */
    byte[] renderPdf(String template, Map<String, Object> model);

    /**
     * First half of storing a generated PDF: writes the bytes to object storage under a fresh key.
     * No transaction and no database, so a caller can keep its row lock for {@link #recordGenerated}
     * alone and never hold it across a render or an upload. The object is unreferenced until recorded;
     * give it up with {@link #discardGenerated} when the record is not written.
     */
    GeneratedObject putGenerated(byte[] pdf, String docType);

    /**
     * As {@link #putGenerated} for a generated file that is not a PDF (a report's JSON or CSV, ADR-038):
     * the caller names the content type and the extension, and decides that the bytes are what it says,
     * since it wrote them. Same two-step contract: record it with {@link #recordGenerated} or give it up
     * with {@link #discardGenerated}.
     */
    GeneratedObject putFile(byte[] bytes, String docType, String contentType, String extension);

    /**
     * As {@link #putFile(byte[], String, String, String)} for a file on disk: the checksum is taken by reading it once
     * in blocks and the object is uploaded from the file, so the output of a report is never held in memory. Refuses a
     * file over 2 GiB. No transaction and no database.
     */
    GeneratedObject putFile(java.nio.file.Path file, String docType, String contentType, String extension);

    /**
     * Second half: records the object as {@code docType} with its {@code docNumber} (FR-DOC-02), in
     * the caller's transaction, which serialises concurrent issues of the same document (for example
     * under its subject's row lock) and has looked for it with {@link #findGenerated} first. A
     * rollback removes the object again.
     */
    StoredDocument recordGenerated(
            GeneratedObject object, String docNumber, String subjectType, UUID subjectId, UUID branchId);

    /**
     * As {@link #recordGenerated} for a job that has no signed-in user: {@code createdBy} is the user the
     * file was made for (a report run's requester), and no principal is read.
     */
    StoredDocument recordGenerated(
            GeneratedObject object,
            String docNumber,
            String subjectType,
            UUID subjectId,
            UUID branchId,
            UUID createdBy);

    /**
     * Deletes the stored object of a generated report (ADR-038: report files are kept 30 days). The
     * {@code documents} row is written once and stays as metadata; only the bytes go. Refuses any document
     * that is not of type {@code report}. Only {@code core.reporting} calls it (an architecture test
     * enforces that). Safe to call again: a missing object is not an error.
     */
    void deleteReportObject(UUID documentId);

    /** Removes an object that {@link #putGenerated} wrote and nothing recorded. Never throws. */
    void discardGenerated(GeneratedObject object);

    /** An uploaded generated PDF waiting to be recorded. */
    record GeneratedObject(UUID id, String key, String docType, String contentType, String sha256, int sizeBytes) {}

    /** Every generated document of the subject in one query, newest first. */
    List<GeneratedDocument> listGenerated(String subjectType, UUID subjectId);

    /** A stored generated document with the number it was issued under. */
    record GeneratedDocument(StoredDocument document, String docNumber) {}

    /** The generated document of this type and number for the subject, if one was stored. */
    Optional<StoredDocument> findGenerated(String subjectType, UUID subjectId, String docType, String docNumber);

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
