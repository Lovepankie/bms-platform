package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.kernel.PersonalDataEraser;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The documents' part of removing a closed business (issue #571, ADR-090): every stored file of the business (member ID images,
 * item photos, logos, report files, feedback screenshots, quotations and delivery notes) is deleted from the object store once
 * the removal commits, except the generated invoices, receipts, credit notes and vouchers, which are money records. The rows stay, as the money records that point at them do; their files are gone. A file that cannot be deleted is
 * logged by its key only and left for the orphan sweep, since the removal itself has committed.
 */
@Component
class DocumentErasure implements PersonalDataEraser {

    private static final Logger log = LoggerFactory.getLogger(DocumentErasure.class);

    /**
     * The generated documents that are money records (owner decision D46): invoices, receipts, credit notes and payment
     * vouchers. Kept, with the business's other money records, until {@code keep_until}, when the purge deletes their rows; the
     * files themselves stay in the object store's lifecycle (ADR-090 Watch for).
     */
    static final String MONEY_RECORDS = "'invoice', 'receipt', 'credit_note', 'voucher'";

    private final JdbcClient jdbc;
    private final ObjectStorage storage;

    DocumentErasure(JdbcClient jdbc, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @Override
    public String area() {
        return "documents";
    }

    @Override
    public int erase() {
        List<String> keys = jdbc.sql("SELECT object_key FROM documents WHERE doc_type NOT IN (" + MONEY_RECORDS
                        + ") ORDER BY created_at, id")
                .query(String.class)
                .list();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (String key : keys) {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("could not delete object {} of a removed business", key, e);
                    }
                }
            }
        });
        return keys.size();
    }
}
