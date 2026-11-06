package com.rincoltech.bms.core.imports;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A business's mappings (issue #129, spec section 4.2, ADR-027 decision 3): the versioned data that says how one customer's
 * sheets feed a canonical template. Every method works for the business bound to the thread (ADR-003); a mapping of another
 * business is not found. Stored in {@code import_mappings}, append-only: a change is a new version under the same name, and a
 * version once stored never changes, so a batch that recorded it (its {@code mapping_id}) keeps reading what it was parsed
 * with.
 *
 * <p>A mapping document says, per source (a file or a tab): the entity set it feeds, the header row ({@code "auto"} or a
 * fixed row from 1 to 20), each column to a field or ignored with a reason, value maps and enum maps per field, the row
 * rules the entity set offers, and how {@code source_ref} is formed ({@code row_hash}: the tab, row number and content
 * hash; or {@code column}: a column the source keeps). For the whole mapping: unit maps, the date convention
 * ({@code day_first}, {@code month_first} or {@code iso}), the time zone and the currency. The fabricated examples are in
 * {@code fixtures/imports/}.
 *
 * <p>The validator first checks the document against the template version's JSON Schema
 * ({@link ImportTemplates#schema}), then what a schema cannot say: every required field has a column
 * ({@code REQUIRED_FIELD_UNMAPPED}), no field is fed twice, no column is written twice, value and enum maps are for fields a
 * column feeds, the {@code source_ref} column is kept, and the zone is known. Against a file's headers it adds one
 * {@code COLUMN_UNMAPPED} per column neither mapped nor ignored and {@code REQUIRED_COLUMN_MISSING} per required field whose
 * column is not in the file. A mapping is valid only with no problem at all.
 */
public interface ImportMappings {

    /**
     * Stores the next version of the named mapping (1 for a new name). Refused with 422 {@code TEMPLATE_UNKNOWN} when the
     * document names a template not registered, 422 {@code mapping_invalid} with every problem when the document is not a
     * valid mapping of it, and 409 {@code mapping_version_conflict} when another version of the same name was stored at the
     * same moment. Audited as {@code core.import_mapping.saved}. Takes its own transaction or joins the caller's.
     *
     * @param name lower-case letters, digits, dashes and underscores, up to 80: the business's own name for one source
     *     shape, never a person's name
     * @param note why this version exists, for example the review question it answers; optional
     */
    Mapping save(String name, Map<String, Object> document, UUID createdBy, String note);

    Mapping get(UUID mappingId);

    /** The newest version of the named mapping. */
    Optional<Mapping> latest(String name);

    /** Every version of the named mapping, oldest first. */
    List<Mapping> versions(String name);

    /** The problems of a document, without storing it; no file is involved. */
    Validation check(Map<String, Object> document);

    /** A stored version against the headers of one file of the given source. */
    Validation validate(UUID mappingId, String source, List<String> headers);

    /**
     * A parsed batch against the mapping version it recorded and the headers its parse found. A batch parsed without a
     * mapping has one problem, {@code BATCH_HAS_NO_MAPPING}; a batch not parsed, or failed, is 409 {@code batch_not_parsed}.
     */
    Validation validateBatch(UUID batchId);

    /**
     * What the upload needs from one source of a stored version: its tab, its header row, the columns of its required
     * fields (to find an automatic header row), the columns it ignores (never stored, spec section 6.1), and the mapping
     * itself, which the batch then records. 422 {@code SOURCE_UNKNOWN} for a source the mapping does not have.
     */
    ImportUploads.ParseOptions parseOptions(UUID mappingId, String source);

    /** One stored version. {@code document} is the mapping as saved, as plain maps, lists and values. */
    record Mapping(
            UUID id,
            String name,
            int version,
            String templateKey,
            int templateVersion,
            Map<String, Object> document,
            UUID createdBy,
            String note,
            Instant createdAt) {}

    /**
     * One problem: its code from {@link ImportTemplates#SHARED_CODES}, the code's severity, where it is (the source, the
     * column, the field, or the JSON Pointer into the document for a schema problem; each may be null) and the message built
     * from the code's template. Header names are not personal data; no cell value is ever in a problem.
     */
    record Problem(
            String code, String severity, String source, String column, String field, String pointer, String message) {}

    record Validation(List<Problem> problems) {

        public Validation {
            problems = List.copyOf(problems);
        }

        public boolean valid() {
            return problems.isEmpty();
        }
    }
}
