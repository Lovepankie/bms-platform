package com.rincoltech.bms.core.imports;

import java.util.Map;
import java.util.UUID;

/**
 * The rehearsal of a run on last night's backup (issue #138, ADR-027 decision 9, spec section 5.5): the real dry run and
 * the real commit of the run, in the throwaway database that {@code deploy/restore-drill.sh --rehearse-import} restored and
 * copied the run's staged rows into, run by the {@code rehearse-import} command ({@link RehearseImportCommand}). The script
 * copies the row it stores onto the live run; the throwaway database is dropped.
 *
 * <p>Refusals, before anything is written: a database that does not hold the rehearsal's one-time token, the table the
 * script makes in its throwaway database only ({@code not_a_rehearsal_database}, 409); on staging, a run its uploader did
 * not mark fabricated ({@code run_not_fabricated}, 409); a run that is neither in review nor ready
 * ({@code run_not_rehearsable}, 409). The checker's approval and the pre-import dump, which guard a live database, are the
 * only guards of the commit a rehearsal does without; the kill switch, the trading guard, the dry run, the policy
 * decisions and the writers all hold.
 */
public interface ImportRehearsals {

    /**
     * Rehearses the run of the business bound to the thread, outside any transaction: moves it back to review in this
     * database only, takes the dry run again on the restored state and this build, makes it ready, commits it and stores
     * one {@code import_rehearsals} row: the backup, the build, the outcome, the report, the reconciliation the commit made,
     * whether the report says what the run's stored dry run (the live one, copied with the run) said, and the timings, the
     * host's steps ({@code hostTimings}, milliseconds) followed by the API's start, dry run and commit. A commit that stops is
     * stored too, with its failure's code; a refusal stores nothing.
     */
    Rehearsal rehearse(UUID runId, String token, String backup, Map<String, Long> hostTimings);

    /**
     * A stored rehearsal. {@code outcome} is {@code committed}, {@code halted} or {@code failed}; {@code failure} its code,
     * null when committed; {@code report} the commit's report, or the rehearsal's dry run's when the commit did not start;
     * {@code matchesDryRun} null when there is no report or no stored dry run to compare with.
     */
    record Rehearsal(
            UUID id,
            String outcome,
            String failure,
            CommitReport report,
            Boolean matchesDryRun,
            Map<String, Long> timings) {}
}
