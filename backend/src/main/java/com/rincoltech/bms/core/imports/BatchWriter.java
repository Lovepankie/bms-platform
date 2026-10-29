package com.rincoltech.bms.core.imports;

import java.util.Map;
import java.util.UUID;

/**
 * Writes one batch of a run into the business's tables (a template's loader, issue #127). Called inside the batch's own
 * transaction, after the advisory lock on the business's import key is held, with RLS bound to the business. It calls
 * {@link Checkpoint#rowWritten()} after each row, so a kill switch or halt set while it runs stops the batch within 1,000
 * rows; the exception that stops it rolls the whole batch back.
 */
@FunctionalInterface
public interface BatchWriter {

    /** Writes the batch; returns its counts (rows written, existing, skipped, by entity), stored on the batch. */
    Map<String, Object> write(UUID runId, UUID batchId, int seq, Checkpoint checkpoint);

    /** The guard the writer calls after each row. */
    interface Checkpoint {

        /** Counts one row; every 1,000 rows re-reads the kill switch and the run's halt flag and stops the batch if set. */
        void rowWritten();
    }
}
