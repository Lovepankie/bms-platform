package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.Documents.ImagePolicy;
import com.rincoltech.bms.core.documents.Documents.StoredDocument;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Family;
import com.rincoltech.bms.retail.catalogue.internal.CatalogueApi.Product;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Item photos (issue #384 slice 2, ADR-053): a photo on an item with sizes and colours, which each size shows unless it has
 * its own, or on any product. The file goes through core.documents exactly as a member's ID photo does: checked by its
 * bytes (PNG, JPEG or WebP, never SVG), at most {@link #PHOTO} in size, re-encoded and scaled down so no camera metadata
 * survives, stored in the private bucket under the tenant's own prefix, and read only as a five minute signed URL after
 * {@link PhotoAccess} says yes. The check and the re-encode run before any transaction opens, so no connection waits on
 * them.
 */
@Service
class PhotoService {

    static final String SUBJECT = "retail.item_photo";

    /** 5 MB in (a phone photo), 64 px on the short side at least, 1200 px on the long edge at most, 24 megapixels decoded. */
    static final ImagePolicy PHOTO = new ImagePolicy(5 * 1024 * 1024, 64, 1200, 24_000_000L);

    /** The small copy for the item cards and the pickers: 240 px on the long edge, stored once at upload, never made on a read. */
    static final ImagePolicy SMALL = new ImagePolicy(5 * 1024 * 1024, 1, 240, 24_000_000L);

    /** The subject type of a small copy; its subject is the full photo's document id. */
    static final String SMALL_SUBJECT = "retail.item_photo_small";

    private final CatalogueRepository repo;
    private final CatalogueService catalogue;
    private final FamilyService families;
    private final Documents documents;
    private final AuditLog audit;
    private final TransactionTemplate transactions;

    PhotoService(
            CatalogueRepository repo,
            CatalogueService catalogue,
            FamilyService families,
            Documents documents,
            AuditLog audit,
            PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.catalogue = catalogue;
        this.families = families;
        this.documents = documents;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** The image of an item photo for the PWA, same origin (the content security policy allows no other image host). */
    Documents.Image photo(UUID documentId, boolean small) {
        if (!small) {
            return documents.readImage(SUBJECT, documentId);
        }
        // Only this module stores small copies, always of an item photo; a photo without one gets its full image.
        try {
            return documents.readImageOf(SMALL_SUBJECT, documentId);
        } catch (ApiException e) {
            if (e.status() == org.springframework.http.HttpStatus.NOT_FOUND) {
                return documents.readImage(SUBJECT, documentId);
            }
            throw e;
        }
    }

    /** The full photo and its small copy, checked and re-encoded with no transaction open. */
    private record Prepared(Documents.Prepared full, Documents.Prepared small) {}

    private Prepared prepare(byte[] bytes) {
        return new Prepared(documents.prepareImage(bytes, PHOTO), documents.prepareImage(bytes, SMALL));
    }

    /** Stores both copies in the caller's transaction; the small one names the full one as its subject. */
    private StoredDocument store(Prepared prepared, UUID subjectId) {
        StoredDocument full = documents.store(prepared.full(), SUBJECT, subjectId, null);
        documents.store(prepared.small(), SMALL_SUBJECT, full.id(), null);
        return full;
    }

    /** A product's own photo; a size of an item shows it instead of the item's. */
    Product setProductPhoto(UUID productId, byte[] bytes) {
        // The product first, in a short read: nobody makes the server decode an image for an item that is not there.
        transactions.executeWithoutResult(s -> repo.find(productId).orElseThrow(ApiException::notFound));
        Prepared prepared = prepare(bytes);
        return transactions.execute(s -> {
            Product locked = repo.lock(productId).orElseThrow(ApiException::notFound);
            UUID before = repo.ownPhoto(productId).orElse(null);
            StoredDocument stored = store(prepared, productId);
            repo.setProductPhoto(productId, stored.id());
            record(CatalogueService.PRODUCT, locked.id(), before, stored.id());
            return catalogue.get(productId);
        });
    }

    /** The product's own photo taken off; a size of an item then shows the item's again. The file is kept, as every document is. */
    Product clearProductPhoto(UUID productId) {
        return transactions.execute(s -> {
            repo.lock(productId).orElseThrow(ApiException::notFound);
            UUID before = repo.ownPhoto(productId).orElse(null);
            if (before != null) {
                repo.setProductPhoto(productId, null);
                record(CatalogueService.PRODUCT, productId, before, null);
            }
            return catalogue.get(productId);
        });
    }

    /** The photo of an item with sizes and colours, shown for every size that has none of its own. */
    Family setFamilyPhoto(UUID familyId, byte[] bytes) {
        transactions.executeWithoutResult(s -> repo.family(familyId).orElseThrow(ApiException::notFound));
        Prepared prepared = prepare(bytes);
        return transactions.execute(s -> {
            var locked = repo.lockFamily(familyId).orElseThrow(ApiException::notFound);
            StoredDocument stored = store(prepared, familyId);
            repo.setFamilyPhoto(familyId, stored.id());
            record(FamilyService.FAMILY, familyId, locked.photoId(), stored.id());
            return families.get(familyId);
        });
    }

    Family clearFamilyPhoto(UUID familyId) {
        return transactions.execute(s -> {
            var locked = repo.lockFamily(familyId).orElseThrow(ApiException::notFound);
            if (locked.photoId() != null) {
                repo.setFamilyPhoto(familyId, null);
                record(FamilyService.FAMILY, familyId, locked.photoId(), null);
            }
            return families.get(familyId);
        });
    }

    private void record(String entityType, UUID id, UUID before, UUID after) {
        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        was.put("photo_document_id", before);
        now.put("photo_document_id", after);
        audit.record(new AuditLog.Entry(entityType + ".photo_changed", entityType, id, null, was, now));
    }
}
