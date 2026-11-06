package com.rincoltech.bms.core.imports;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The validation of a run's staged rows (issue #130, spec sections 3.3, 3.4 and 6.5, SDD chapter 13 sections 13.7 and
 * 13.14.1). Every method works for the business bound to the thread (ADR-003) and reads only its own rows: a run or batch of
 * another business is not found.
 *
 * <p>{@link #validate} checks every parsed batch of a run against the template and the mapping version each batch recorded:
 * each field's normaliser (the shared functions the forms use; every change an {@code info} issue with the original), its
 * type, whether it is required, its range, the references a field makes to the rows of other batches of the run or to the
 * business's own records, duplicates across the run (the same {@code source_ref}, the same code ignoring case and spaces,
 * the same NIN), and the template's cross-field rules. Each issue has a stable code of the template's catalogue, the code's
 * severity and a message built from the code's template with the field, the file and the line, never with a value of a
 * personal field. The same rows, template and mapping version always give the same issues in the same order.
 *
 * <p>The results are stored on the batch: the typed values and the {@code source_ref} of each row ({@code import_rows}) and
 * its issues ({@code import_issues}), replaced on each validation. A committed batch is read, for its codes and references,
 * and never written. Logs carry the run, the batch and counts by code only.
 */
public interface ImportValidation {

    /**
     * Validates every parsed batch of a run that is {@code uploaded} or {@code in_review}, and moves an {@code uploaded} run
     * to {@code in_review}. Refused with 409 {@code run_not_open} for a run in any other state, 409 {@code batch_not_parsed}
     * while a file of the run is stored and not yet read, 422 {@code BATCH_HAS_NO_MAPPING} when a parsed batch was read
     * without a mapping, 409 {@code templates_differ} when the batches name more than one template version, and 409
     * {@code batch_changed} when a file is added, read again, cancelled or committed while the run is checked. Audited as {@code core.import_run.validated} with counts by code. Takes
     * its own transactions, one per batch written; none may be open around it.
     */
    RunReport validate(UUID runId);

    /** The issues of one batch in the order they were found: line, then field, then the row's own checks. */
    List<Issue> issues(UUID batchId);

    /**
     * One issue as anyone with {@code core.imports.manage} may read it: its code, severity, field, line and message. The
     * original value is not here; it is shown only in the review screen to a holder of {@code core.imports.view_raw}.
     */
    record Issue(
            UUID id,
            UUID batchId,
            UUID rowId,
            int line,
            String code,
            String severity,
            String field,
            String message,
            String status) {}

    /** What one batch's validation found: rows read, and issues by severity and by code, each sorted by name. */
    record BatchReport(UUID batchId, int seq, int rows, Map<String, Integer> bySeverity, Map<String, Integer> byCode) {

        public BatchReport {
            bySeverity = Map.copyOf(bySeverity);
            byCode = Map.copyOf(byCode);
        }
    }

    record RunReport(UUID runId, String templateKey, int templateVersion, List<BatchReport> batches) {

        public RunReport {
            batches = List.copyOf(batches);
        }

        /** Issues of one severity across the run. */
        public int count(String severity) {
            return batches.stream()
                    .mapToInt(b -> b.bySeverity().getOrDefault(severity, 0))
                    .sum();
        }
    }
}
