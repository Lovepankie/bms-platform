package com.rincoltech.bms.core.imports;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * The runs of the operator's host command {@code import-retail} (issue #135, ADR-027 decision 1): the command reads its
 * export directory itself, with its own report of every line it cannot read, and writes each file as one batch of a run of
 * the framework, through the same template loader code a run of the import screens commits with. What it shares with a
 * commit: the run and its batches in {@code import_runs} and {@code import_batches} (origin {@code import_retail}), the
 * business's import key taken without waiting (409 {@code IMPORT_IN_PROGRESS}), one committing run per business, the kill
 * switch (read once, when the run opens: switching it off between two files does not stop a run already writing),
 * {@code app.import_commit} so nothing the batch writes sends a message, starts a task or raises an approval, the
 * events marked imported, every record registered in {@code import_refs}, the opening journals through
 * {@link BatchWriter.Checkpoint#openingJournal}, and the refusal of any other journal at the batch's end. What it does not:
 * the upload, the review, the preview, the stored dry run and the maker and checker of {@code import_commit}, which belong
 * to a run of the screens; the command is run on the host by the platform operator under its runbook
 * ({@code docs/runbooks/import-retail.md}: the verified dump first, the dry run, then the real run), as before. Its runs
 * are not reconciled or signed in the app (no staged rows to reconcile against).
 *
 * <p>Every method works for the business bound to the thread, inside the caller's transaction, so the command's dry run,
 * one outer transaction rolled back, leaves no run behind.
 */
public interface ImportCommandRuns {

    /** The origin of the command's runs in {@code import_runs}. */
    String IMPORT_RETAIL = "import_retail";

    /**
     * Opens a run of the command, {@code committing}, created by no person (the nil id), audited
     * {@code core.import_run.opened}. Refused with 409 {@code IMPORT_IN_PROGRESS} while a batch of any import holds the
     * business's import key, while a run of the command wrote a batch within its lease of 15 minutes, and while a run of
     * the import screens is committing (the unique index {@code import_runs_one_committing}, V127). A run of the command
     * left {@code committing} past its lease is one a killed process left behind: it becomes {@code cancelled} first,
     * audited, and its committed batches stay; a commit of the import screens takes it over the same way (issue #876).
     * And, for a real run ({@code dryRun} false), 409 {@code imports_disabled} with the kill switch
     * off; a dry run writes nothing, so the switch, which guards writes, does not stop it, as it does not stop the
     * framework's.
     */
    UUID open(boolean dryRun);

    /**
     * Adds the batch of one file, read with one source of a stored mapping, which the batch records with its template
     * (422 {@code SOURCE_UNKNOWN} for a source the mapping does not have); or, with no mapping, a file outside the
     * template (the cash book's) or a step without a file (the advance openings).
     */
    UUID batch(UUID runId, int seq, String file, UUID mappingId, String source);

    /**
     * Writes one batch as a commit writes one (see above) and marks it committed with the counts {@code work} returns and
     * its part of the report. The checkpoint's {@code rows} is refused: the command reads its own file. Refused for a run
     * that is not the command's and committing, or a batch already committed.
     */
    Map<String, Object> write(UUID runId, UUID batchId, Function<BatchWriter.Checkpoint, Map<String, Object>> work);

    /**
     * Ends the run: {@code committed}, or {@code cancelled} when a file stopped it; the batches it committed stay, and a
     * re-run of the command recognises what they wrote.
     */
    void finish(UUID runId, boolean stopped);
}
