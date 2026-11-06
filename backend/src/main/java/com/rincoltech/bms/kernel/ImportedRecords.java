package com.rincoltech.bms.kernel;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Whether the current transaction writes imported records (issue #133, Lead ruling 3 on #872): the customer data onboarding
 * marks each batch's transaction, and an event published inside it is an imported record's, so every listener that sends,
 * notifies or starts a workflow skips it ({@link PushNotice#imported()}). It lives in the kernel so a publisher and a
 * listener read it without depending on the imports module. The mark is bound to the transaction and ends with it.
 */
public final class ImportedRecords {

    private static final Object KEY = ImportedRecords.class;

    private ImportedRecords() {}

    /**
     * An application event that says whether an import batch published it (Lead's note on the review of 8e570b0, #872):
     * every event the application publishes is one, set from {@link #inImport()} when it is made, and every listener that
     * writes to the outbox, sends or starts a workflow skips an imported one.
     */
    public interface Marked {

        /** Whether an import batch's transaction published this event. */
        boolean imported();
    }

    /** Marks the current transaction as an import batch's, until it ends. Needs an active transaction. */
    public static void markTransaction() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("an import batch is marked inside its transaction");
        }
        if (TransactionSynchronizationManager.hasResource(KEY)) {
            return;
        }
        TransactionSynchronizationManager.bindResource(KEY, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
            }
        });
    }

    /** Whether the current transaction writes imported records. */
    public static boolean inImport() {
        return TransactionSynchronizationManager.hasResource(KEY);
    }
}
