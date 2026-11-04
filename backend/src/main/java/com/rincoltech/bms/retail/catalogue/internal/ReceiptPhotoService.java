package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.catalogue.ReceiptPhotos;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Receipt photos (issue #819, ADR-124): the item photo's pipeline (ADR-053) for a photo of a paper receipt. The file is
 * checked by its bytes and re-encoded with {@link PhotoService#PHOTO} before any transaction opens, stored through
 * core.documents under the tenant's own prefix with the record as its subject and the record's branch, and audited. One copy
 * is kept: the small picture on a record is the same image drawn small.
 */
@Service
class ReceiptPhotoService implements ReceiptPhotos {

    private final Documents documents;
    private final AuditLog audit;
    private final TransactionTemplate transactions;

    ReceiptPhotoService(Documents documents, AuditLog audit, PlatformTransactionManager transactionManager) {
        this.documents = documents;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public UUID attach(Kind kind, UUID recordId, byte[] bytes, Supplier<UUID> record) {
        transactions.executeWithoutResult(s -> record.get());
        Documents.Prepared prepared = documents.prepareImage(bytes, PhotoService.PHOTO);
        return transactions.execute(s -> {
            UUID branch = record.get();
            Documents.StoredDocument stored = documents.store(prepared, kind.subjectType(), recordId, branch);
            audit.record(AuditLog.Entry.created(
                    kind.entityType() + ".receipt_photo_added",
                    kind.entityType(),
                    recordId,
                    branch,
                    Map.of("receipt_photo_document_id", stored.id())));
            return stored.id();
        });
    }

    @Override
    public UUID upload(Kind kind, byte[] bytes, UUID branchId) {
        Documents.Prepared prepared = documents.prepareImage(bytes, PhotoService.PHOTO);
        return transactions.execute(s -> {
            Documents.StoredDocument stored =
                    documents.store(prepared, kind.subjectType(), UUID.randomUUID(), branchId);
            // Review of #846: a photo sent before its record is audited too, by who sent it, as every photo added is.
            audit.record(AuditLog.Entry.created(
                    kind.entityType() + ".receipt_photo_sent",
                    "core.document",
                    stored.id(),
                    branchId,
                    Map.of("subject_type", kind.subjectType())));
            return stored.id();
        });
    }

    @Override
    public Map<UUID, UUID> newest(Kind kind, Collection<UUID> recordIds) {
        return documents.newestOf(kind.subjectType(), recordIds);
    }

    @Override
    public Image image(Kind kind, UUID recordId, UUID photoId) {
        Documents.Image image = photoId != null
                ? documents.readImage(kind.subjectType(), photoId)
                : documents.readImageOf(kind.subjectType(), recordId);
        return new Image(image.bytes(), image.contentType(), image.sha256());
    }

    /**
     * FR-DOC-03 for receipt photos: the documents module asks the owning module's {@link ReceiptPhotos.Rule}. A subject type
     * whose module registered no rule is readable by nobody.
     */
    record Access(Kind kind, ObjectProvider<ReceiptPhotos.Rule> rules) implements DocumentAccess {

        @Override
        public String subjectType() {
            return kind.subjectType();
        }

        @Override
        public boolean canRead(Principal principal, UUID subjectId) {
            return canRead(principal, subjectId, null);
        }

        @Override
        public boolean canRead(Principal principal, UUID subjectId, UUID documentId) {
            // Looked up on each read, so a rule may use its module's services, which may use the documents module.
            return rules.orderedStream()
                    .filter(r -> r.kind() == kind)
                    .anyMatch(r -> r.canSee(principal, subjectId, documentId));
        }

        @Override
        public boolean hideDenial() {
            // A record outside the caller's scope is not found on its own route; its photo is not found either.
            return true;
        }

        // Only an expense's photo is sent before its record; the others are stored with theirs.
        @Override
        public Duration claimWithin() {
            return kind == Kind.EXPENSE ? CLAIM_WITHIN : null;
        }

        @Override
        public boolean claimed(UUID subjectId, UUID documentId) {
            return rules.orderedStream().filter(r -> r.kind() == kind).anyMatch(r -> r.claimed(subjectId, documentId));
        }
    }

    /** One {@link DocumentAccess} per kind, each asking the rules the retail modules register. */
    @Configuration(proxyBeanMethods = false)
    static class AccessConfiguration {

        @Bean
        DocumentAccess saleReceiptPhotoAccess(ObjectProvider<ReceiptPhotos.Rule> rules) {
            return new Access(Kind.SALE, rules);
        }

        @Bean
        DocumentAccess deliveryReceiptPhotoAccess(ObjectProvider<ReceiptPhotos.Rule> rules) {
            return new Access(Kind.DELIVERY, rules);
        }

        @Bean
        DocumentAccess usageReceiptPhotoAccess(ObjectProvider<ReceiptPhotos.Rule> rules) {
            return new Access(Kind.USAGE, rules);
        }

        @Bean
        DocumentAccess expenseReceiptPhotoAccess(ObjectProvider<ReceiptPhotos.Rule> rules) {
            return new Access(Kind.EXPENSE, rules);
        }
    }
}
