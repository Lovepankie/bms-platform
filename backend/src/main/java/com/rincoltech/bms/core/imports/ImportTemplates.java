package com.rincoltech.bms.core.imports;

import com.rincoltech.bms.core.imports.ImportTemplate.IssueCode;
import com.rincoltech.bms.core.imports.ImportTemplate.Severity;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The registry of canonical templates (issue #129, spec section 4.1, chapter 5 section 5.4.3). It holds every
 * {@link ImportTemplate} bean a vertical registers, checked once at start-up, and answers for them by key and version. The
 * core iterates it and never names a vertical. Read only, no database.
 */
public interface ImportTemplates {

    /** Every registered template, by key then version. */
    List<ImportTemplate> list();

    Optional<ImportTemplate> find(String key, int version);

    /** The template, or 422 {@code TEMPLATE_UNKNOWN}. */
    ImportTemplate require(String key, int version);

    /**
     * The JSON Schema (draft 2020-12) a mapping for this template version must match, as plain maps and lists: the shared
     * shape of spec section 4.2 narrowed per entity set to its own fields, enum values and row rules. A mapping is checked
     * against exactly this document before anything else (see {@link ImportMappings}).
     */
    Map<String, Object> schema(String key, int version);

    /** The template's whole catalogue: the shared codes, then its own, in that order. */
    List<IssueCode> catalogue(String key, int version);

    /**
     * The codes every template shares (spec section 3.4.1, the shared part, and the mapping codes of issue #129). A template's
     * own codes are listed by the template; a code is never renamed or reused.
     */
    List<IssueCode> SHARED_CODES = List.of(
            new IssueCode("FILE_TYPE_UNSUPPORTED", Severity.BATCH_FAILS, "The file is not CSV, XLSX or JSON Lines."),
            new IssueCode(
                    "FILE_TOO_LARGE",
                    Severity.BATCH_FAILS,
                    "The file is over the limit of {limit}; split it and upload the parts."),
            new IssueCode(
                    "TOO_MANY_ROWS",
                    Severity.BATCH_FAILS,
                    "The file is over the limit of {limit}; split it and upload the parts."),
            new IssueCode(
                    "HEADER_NOT_FOUND",
                    Severity.BATCH_FAILS,
                    "No row matches the mapping's headers in the first 20 rows."),
            new IssueCode(
                    "TOO_MANY_COLUMNS",
                    Severity.BATCH_FAILS,
                    "The file is over the limit of {limit} columns; remove the columns not needed."),
            new IssueCode("FILE_UNREADABLE", Severity.BATCH_FAILS, "The file is damaged at {row}."),
            new IssueCode("FILE_ALREADY_UPLOADED", Severity.REFUSED, "This import already holds this file."),
            new IssueCode(
                    "UPLOAD_DISK_LOW",
                    Severity.REFUSED,
                    "The server has too little free disk for this file now; try again later."),
            new IssueCode(
                    "COLUMN_UNMAPPED",
                    Severity.WARNING,
                    "Column {column} is not in the mapping; map it or mark it ignored."),
            new IssueCode("REQUIRED_MISSING", Severity.BLOCKING, "{field} is empty on {file} line {line}."),
            new IssueCode("TYPE_INVALID", Severity.BLOCKING, "{field} on {file} line {line} is not a valid {type}."),
            new IssueCode(
                    "MONEY_FRACTION",
                    Severity.BLOCKING,
                    "{field} on {file} line {line} has a fraction; amounts are whole shillings."),
            new IssueCode(
                    "QTY_PRECISION",
                    Severity.BLOCKING,
                    "{field} on {file} line {line} has more than three decimal places."),
            new IssueCode(
                    "DUPLICATE_SOURCE_REF", Severity.BLOCKING, "Line {line} repeats the reference of line {other}."),
            new IssueCode(
                    "REF_UNKNOWN",
                    Severity.BLOCKING,
                    "{field} on {file} line {line} names a {entity} that is not in the import or the tenant."),
            new IssueCode(
                    "DATE_AFTER_CUTOVER",
                    Severity.BLOCKING,
                    "{field} on {file} line {line} is after the agreed cutover moment."),
            new IssueCode(
                    "TENANT_TRADING",
                    Severity.REFUSED,
                    "The tenant already has live transactions; an import needs the trading override."),
            new IssueCode(
                    "IMPORT_IN_PROGRESS",
                    Severity.REFUSED,
                    "Another import is being committed for this business; wait for it or stop it."),
            new IssueCode(
                    "ENCODING_CONVERTED",
                    Severity.WARNING,
                    "The file was not UTF-8 and was read as Windows-1252; check accented names in the preview."),
            new IssueCode(
                    "ROW_CHANGED_SINCE_REVIEW",
                    Severity.BLOCKING,
                    "Line {line} changed since it was reviewed; review it again."),
            new IssueCode(
                    "CHANGED_SINCE_IMPORT",
                    Severity.BLOCKING,
                    "Line {line} was already imported with other content; correct it in the app, not by import."),
            // The mapping codes (issue #129): what the validator of a mapping reports.
            new IssueCode("TEMPLATE_UNKNOWN", Severity.REFUSED, "No template {template} is registered on this server."),
            new IssueCode(
                    "MAPPING_INVALID",
                    Severity.REFUSED,
                    "The mapping does not match the template's schema at {pointer}: {reason}"),
            new IssueCode(
                    "REQUIRED_FIELD_UNMAPPED",
                    Severity.BLOCKING,
                    "{field} of {entity} is required and no column of source {source} feeds it."),
            new IssueCode(
                    "FIELD_MAPPED_TWICE",
                    Severity.BLOCKING,
                    "Columns {column} and {other} of source {source} both feed {field}; keep one and ignore the other."),
            new IssueCode(
                    "COLUMN_DUPLICATE",
                    Severity.BLOCKING,
                    "Column {column} of source {source} is written twice in the mapping, ignoring case and spaces."),
            new IssueCode(
                    "MAP_FIELD_UNMAPPED",
                    Severity.BLOCKING,
                    "Source {source} maps values of {field}, which no column of it feeds."),
            new IssueCode(
                    "SOURCE_REF_COLUMN_UNKNOWN",
                    Severity.BLOCKING,
                    "The reference column {column} of source {source} is not a column the mapping keeps."),
            new IssueCode("ZONE_UNKNOWN", Severity.BLOCKING, "The time zone {value} is not a known zone."),
            new IssueCode("SOURCE_UNKNOWN", Severity.REFUSED, "The mapping has no source {source}."),
            new IssueCode(
                    "BATCH_HAS_NO_MAPPING",
                    Severity.REFUSED,
                    "The file was read without a mapping; read it again with one before checking its columns."),
            new IssueCode(
                    "REQUIRED_COLUMN_MISSING",
                    Severity.BLOCKING,
                    "Column {column}, which feeds the required {field}, is not in the file of source {source}."));
}
