package com.rincoltech.bms.retail.catalogue;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.Principal;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * Issue #819, ADR-124, FR-RET-89: the photo of a paper receipt on a sale, a delivery, an expense or a usage or damage entry,
 * through the item photo's checks, limits and storage (ADR-053). The photo is a document whose subject is the record; the
 * newest is shown. Each owning module registers a {@link Rule}: whoever may see the record sees its photo, on the module's
 * own route and on the generic document routes alike.
 */
public interface ReceiptPhotos {

    /** The four records a receipt photo goes on, with the subject type of its documents and the audit entity type. */
    enum Kind {
        SALE("retail.sale_receipt_photo", "retail.sale"),
        DELIVERY("retail.delivery_receipt_photo", "retail.purchase"),
        USAGE("retail.usage_receipt_photo", "retail.usage"),
        /** The subject type the expense API took before #819 for {@code receipt_document_id}. */
        EXPENSE("retail.expense_receipt", "retail.expense");

        private final String subjectType;
        private final String entityType;

        Kind(String subjectType, String entityType) {
            this.subjectType = subjectType;
            this.entityType = entityType;
        }

        public String subjectType() {
            return subjectType;
        }

        public String entityType() {
            return entityType;
        }
    }

    /**
     * Adds a photo to a record. {@code record} answers the record's branch after checking that the caller may add to it
     * there, or throws (404 for a record that is not found or not in scope). It runs once in a short read before the image
     * is decoded, so nobody makes the server decode a picture for a record that is not there, and again in the write
     * transaction, which stores the photo and audits it. Answers the photo's id.
     */
    UUID attach(Kind kind, UUID recordId, byte[] bytes, Supplier<UUID> record);

    /**
     * A photo sent before its record, for the expense form's {@code receipt_document_id}: stored under a fresh subject at
     * the branch given (null when not known yet). Nobody can read it until a record names it.
     */
    UUID upload(Kind kind, byte[] bytes, UUID branchId);

    /**
     * The image of the record's newest photo, or of {@code photoId} when the record names one; 404 when it has none. The
     * caller has checked the record; the documents module checks the {@link Rule} again.
     */
    Image image(Kind kind, UUID recordId, UUID photoId);

    /** A stored image, its type and its checksum (an ETag). */
    record Image(byte[] bytes, String contentType, String sha256) {}

    /**
     * The newest photo of each of the records that has one, by record id, in the caller's transaction and one query: what a
     * list marks as {@code has_receipt_photo}, through core.documents rather than its table. Records with none are absent.
     */
    Map<UUID, UUID> newest(Kind kind, Collection<UUID> recordIds);

    /** How long a photo sent before its record may wait to be named by one before its file is deleted (ADR-124). */
    Duration CLAIM_WITHIN = Duration.ofHours(24);

    /** Registered by the module that owns the record: may this person see the record, and so its photo? */
    interface Rule {

        Kind kind();

        /**
         * The record's own read check (branch scope and any narrower rule of its module). {@code photoId} names the photo
         * asked for, for a photo sent before its record whose subject is not the record.
         */
        boolean canSee(Principal principal, UUID recordId, UUID photoId);

        /**
         * Is a photo sent before its record (an expense's) named by one? Only the expense's rule answers no; a photo that
         * stays unnamed for {@link #CLAIM_WITHIN} has its file deleted by the documents module's nightly sweep.
         */
        default boolean claimed(UUID recordId, UUID photoId) {
            return true;
        }
    }

    /** 409 {@code record_voided}: a voided sale, delivery or expense, or an undone usage entry, takes no receipt photo. */
    static ApiException voided() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "record_voided",
                "Entry deleted",
                "This entry was deleted or undone, so it takes no receipt photo.");
    }

    /** The bytes of an uploaded file part; 400 when it cannot be read. */
    static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "The upload could not be read.");
        }
    }

    /** The image answer, cached as an item photo is: private, short, revalidated, keyed by who asks. */
    static ResponseEntity<byte[]> serve(Image image) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5))
                        .cachePrivate()
                        .mustRevalidate())
                .varyBy("Cookie", "Authorization")
                .eTag(image.sha256())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }

    /** The answer of an add: the photo's id. */
    @Schema(name = "RetailReceiptPhotoAdded")
    record Added(UUID photoId) {}
}
