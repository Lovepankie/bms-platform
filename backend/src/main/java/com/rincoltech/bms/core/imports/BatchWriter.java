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
 * <p>The same writer runs in a dry run (issue #132), inside one outer transaction around every batch that is rolled back at
 * the end, so it must write exactly as it would in a real run and never look at whether it is a dry run: the dry run's
 * report predicts the real run's only because the code is the same. It reports through the checkpoint (the
 * {@link CommitReport.Sink} methods) what the report prints: per file counts when the batch holds several files, anomalies
 * with their line, opening journals with their totals, notes and the balances checksum.
 *
 * <p>So that the dry run writes nothing and stays bounded, a writer never calls {@code nextval}, writes a file, opens a
 * transaction of its own or keeps a long step without a statement: the dry run's time limit is a deadline the database
 * driver checks at each statement (review of 1c607ea on #869), so the business's rows are held until the next one.
 *
 * <p>Branch limits are the writer's contract, not the run's (review of #736): each template's writer checks that every row
 * it writes falls in a branch the import may write to and refuses the batch otherwise. The first writer, and so the first
 * place this is enforced and tested, is the template of #136.
 */
@FunctionalInterface
public interface BatchWriter {

    /**
     * Writes the batch; returns its counts (rows {@code read}, {@code written}, {@code existing}, {@code skipped}, by entity),
     * stored on the batch.
     */
    Map<String, Object> write(UUID runId, UUID batchId, int seq, Checkpoint checkpoint);

    /**
     * What this writer is, stored with a dry run and compared at the commit and the resume (issue #132): a run commits only
     * with the writer its dry run rehearsed ({@code dry_run_writer_differs} otherwise). The default is the writer's class,
     * without the per-process suffix of a lambda's class, so it is the same after a restart. A writer whose behaviour
     * depends on more than its code names that too: the retail export's writer adds the SHA-256 of the export's files, and
     * a template's loader (#133) its template and version. Every lambda of one class is one writer by default, whatever
     * its site, since the JDK numbers lambda classes per process.
     */
    default String identity() {
        String name = getClass().getName();
        int lambda = name.indexOf("$$Lambda");
        if (lambda >= 0) {
            return name.substring(0, lambda + "$$Lambda".length());
        }
        int hidden = name.indexOf('/');
        return hidden < 0 ? name : name.substring(0, hidden);
    }

    /** The guard the writer calls after each row, and the report it tells what it did (issue #132). */
    interface Checkpoint extends CommitReport.Sink {

        /** Counts one row; every 1,000 rows re-reads the kill switch and the run's halt flag and stops the batch if set. */
        void rowWritten();
    }
}
