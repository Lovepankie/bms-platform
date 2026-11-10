package com.rincoltech.bms.core.imports;

import com.rincoltech.bms.core.imports.ImportValidation.Issue;
import java.util.List;
import java.util.UUID;

/**
 * The review queue of a run (issue #131, spec section 3.5, FR-IMP-05, SDD chapter 13 sections 13.4 and 13.7), for every
 * template. Every method works for the business bound to the thread (ADR-003); a run, batch or issue of another business is
 * not found. Every change is made while the run is {@code uploaded} or {@code in_review} (409 {@code run_not_open}) and
 * takes the run {@code FOR UPDATE}, as a validation's writing does, so resolutions and validations of one run happen one at
 * a time.
 *
 * <p>Every resolution is one append-only {@code import_issue_resolutions} row (who, when, the kind, the value before and
 * after, the note) and one audit row {@code core.import_issue.resolved} that names the kind, the code, the field and the
 * line and never a value or the note. The value before and after an {@code edit_value} may be personal: it is kept only in
 * the resolution row, the quarantine, never in an issue's message or an audit row.
 *
 * <p>The kinds: {@code edit_value} (the text in place of the field's cell; the row is validated again),
 * {@code exclude_row} (with a reason; the row stays in the batch and in the report, its issues resolved),
 * {@code accept_suggestion} (the engine's suggested value, as an edit; or, on {@code ROW_CHANGED_SINCE_REVIEW}, the row
 * as it now is), {@code link_row} (to another row of the run), {@code link_member} (to a record the business already
 * holds, which the template confirms), {@code override} (a warning only, with a note; 422 {@code override_not_allowed} on
 * a blocking issue), and {@code map_value} (the issue's cell text becomes a value map of the mapping: a new mapping version,
 * the run's batches read with it, and exactly the rows whose cell the map reaches validated again). A resolution names the
 * issue's version; another resolver's version is 409 {@code version_conflict}.
 *
 * <p>A resolution applies while its row's cells are the ones it was made on: it carries across a new validation, a new
 * parse and a new upload of the same file into the run, matched by the row's reference; a row whose cells changed comes
 * back to the queue with {@code ROW_CHANGED_SINCE_REVIEW} (spec section 10.1).
 */
public interface ImportReview {

    /** The issues of a run in commit order (file, line, then the engine's order), filtered, a page at a time. */
    Page queue(UUID runId, Filter filter, Integer limit, String cursor);

    /** Resolves one issue. */
    Outcome resolve(UUID issueId, Resolution resolution, UUID by);

    /**
     * Resolves every open issue of one code in the run, or in one batch of it, with the same kind, value and note: one
     * resolution row each, made together (their {@code bulk_id}). {@code edit_value}, {@code accept_suggestion},
     * {@code exclude_row}, {@code override} and {@code ask_customer} only; refused as a whole when one issue refuses.
     */
    BulkOutcome resolveAll(UUID runId, Bulk bulk, UUID by);

    /** Marks an issue as a question for the customer, or no longer one; an {@code ask_customer} resolution row. */
    Issue askCustomer(UUID issueId, int version, boolean ask, UUID by);

    /**
     * The open questions for the customer: every open issue of the run marked {@code ask_customer}, numbered in commit
     * order, each with its file, line, field and message, and never a value of a row or an operator's note. With
     * {@code export} (the file sent to the customer) audited as {@code core.import_run.questions_exported} with the count.
     */
    List<Question> questions(UUID runId, boolean export);

    /**
     * The row of an issue as it was uploaded, for the review screen of a holder of {@code core.imports.view_raw} (issue #141,
     * spec section 6.3): each kept cell by its column, in the file's order, and the issue's original value. Audited as
     * {@code core.import_row.raw_viewed} with the run, the batch and the line, never a value, on every call. A purged run
     * answers no cells.
     */
    RawRow raw(UUID issueId);

    /** One cell as uploaded: its column, its value as text and the type the parser read. */
    record RawValue(String column, String value, String type) {}

    record RawRow(
            UUID issueId,
            UUID rowId,
            String file,
            int line,
            String field,
            String originalValue,
            List<RawValue> cells) {}

    /**
     * @param status {@code open} (the default), {@code resolved} or {@code all}
     * @param code one code, or null
     * @param batchId one batch of the run, or null
     * @param askCustomer only the questions for the customer, or null for every issue
     */
    record Filter(String status, String code, UUID batchId, Boolean askCustomer) {}

    record Page(List<Issue> items, String nextCursor) {

        public Page {
            items = List.copyOf(items);
        }
    }

    /**
     * One resolution as asked for. {@code version} is the issue's version as read. {@code value}: the text of an
     * {@code edit_value} (null or blank to leave the field empty) or the value a {@code map_value} maps the cell to.
     * {@code linkRowId} for {@code link_row}, {@code linkMemberId} for {@code link_member}. {@code note}: required for
     * {@code override} and {@code exclude_row}.
     */
    record Resolution(String resolution, int version, String value, UUID linkRowId, UUID linkMemberId, String note) {}

    /**
     * What a resolution did: its row, the rows validated again, the mapping version a {@code map_value} made (else null), and
     * the issues of the resolved issue's row as they now are.
     */
    record Outcome(
            UUID resolutionId, String resolution, int rowsValidated, Integer mappingVersion, List<Issue> issues) {

        public Outcome {
            issues = List.copyOf(issues);
        }
    }

    /** One bulk resolution as asked for: every open issue of {@code code} in the run, or in {@code batchId}. */
    record Bulk(String code, UUID batchId, String resolution, String value, String note) {}

    record BulkOutcome(UUID bulkId, int resolved, int rowsValidated) {}

    /** One question of the export: its number, the file (its name cut to 100 characters), the line, the field and the message. */
    record Question(int number, String file, int line, String field, String code, String message) {}
}
