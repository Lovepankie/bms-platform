package com.rincoltech.bms.core.imports;

import java.util.Map;
import java.util.UUID;

/**
 * Writes one batch of a run into the business's tables (a template's loader, issue #127). Called inside the batch's own
 * transaction, after the advisory lock on the business's import key is held, with RLS bound to the business. It calls
 * {@link Checkpoint#rowWritten()} after each row, so a kill switch or halt set while it runs stops the batch within 1,000
 * rows; the exception that stops it rolls the whole batch back, and any other exception it throws does the same, leaves the
 * run committing (so it can go on once the cause is fixed) and is audited as {@code core.import_run.batch_failed}.
 *
 * <p>Branch limits are the writer's contract, not the run's (review of #736): each template's writer checks that every row
 * it writes falls in a branch the import may write to and refuses the batch otherwise. The first writer, and so the first
 * place this is enforced and tested, is the template of #136.
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
