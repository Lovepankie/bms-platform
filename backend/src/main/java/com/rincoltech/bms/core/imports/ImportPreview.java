package com.rincoltech.bms.core.imports;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The preview of a run (issue #132, spec section 3.6, FR-IMP-06, SDD chapter 13 section 13.3): what the commit will
 * create, computed from the staged rows and nothing else, so it writes nothing. Works for the business bound to the thread
 * (ADR-003); a run of another business is not found.
 *
 * <p>A row the commit would write is a row of a parsed batch that is not excluded, not blank ({@code BLANK_ROW}) and has
 * no open blocking issue. The preview counts the rows of each batch by that outcome, the records each entity set would get,
 * and asks the template's {@link ImportForecast} for the predicted balances: stock quantity and value per branch,
 * receivables, loan principal outstanding and arrears buckets, and each opening journal with its debit and credit totals.
 * The dry run ({@link ImportRuns#dryRun}) runs the commit itself and is compared with it: a run is {@code ready} only when
 * the dry run's opening journals equal the preview's.
 */
public interface ImportPreview {

    /**
     * The preview of a run's parsed and pending batches. Refused with 409 {@code templates_differ} when the batches name more
     * than one template version. Takes its own read-only transaction; none may be open around it.
     */
    Preview preview(UUID runId);

    /**
     * What one batch's rows come to: every row read, those the commit would write, those excluded by the review, those
     * blank, and those held back by an open blocking issue.
     */
    record BatchOutcome(
            UUID batchId,
            int seq,
            String file,
            String entitySet,
            int rows,
            int write,
            int excluded,
            int blank,
            int blocked) {}

    /**
     * The preview: the template it was computed with (null when no batch was read with a mapping), the records each entity
     * set would get (the rows to write, in the template's order), each batch's outcome in commit order, and the predicted
     * balances.
     */
    record Preview(
            UUID runId,
            String templateKey,
            Integer templateVersion,
            Map<String, Integer> recordsByType,
            List<BatchOutcome> batches,
            ImportForecast.Balances balances) {

        public Preview {
            recordsByType = Collections.unmodifiableMap(new LinkedHashMap<>(recordsByType));
            batches = List.copyOf(batches);
        }
    }
}
