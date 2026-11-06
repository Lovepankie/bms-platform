package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportMappings.Mapping;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.Field;
import com.rincoltech.bms.core.imports.ImportTemplate.Kind;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.core.imports.ImportValidation.Issue;
import com.rincoltech.bms.core.imports.internal.RowValidator.RawCell;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.Cursor;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.kernel.Versions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ImportReview} over {@code import_issues}, {@code import_rows} and {@code import_issue_resolutions} (migration V141).
 * Each change runs in one transaction that takes the run {@code FOR UPDATE} and then the issue {@code FOR UPDATE}, checks
 * the issue's version, writes the resolution row and its audit row, and applies it: on the issues and the row directly for
 * a decision that changes no value (an exclusion, an override, a link, a question), or through
 * {@link ValidationService#revalidate} for the rows whose values change (an edit, an accepted value, a value map). Logs and
 * audit rows carry ids, codes, fields, lines and counts only, never a value or a note.
 */
@Service
class ReviewService implements ImportReview {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    static final Set<String> KINDS = Set.of(
            "edit_value", "exclude_row", "accept_suggestion", "link_row", "link_member", "override", "map_value");

    static final Set<String> BULK_KINDS =
            Set.of("edit_value", "exclude_row", "accept_suggestion", "override", "ask_customer");

    /** The longest text an edit may give a field: a cell's limit (chapter 13 section 13.14.1). */
    static final int MAX_VALUE = 32_767;

    /** The longest value a value map may give a label. */
    static final int MAX_MAPPED = 200;

    static final int MAX_NOTE = 500;

    static final int DEFAULT_PAGE = 100;
    static final int MAX_PAGE = 500;

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnly;
    private final AuditLog audit;
    private final ImportTemplates templates;
    private final ImportMappings mappings;
    private final ValidationService validation;

    ReviewService(
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            AuditLog audit,
            ImportTemplates templates,
            ImportMappings mappings,
            ValidationService validation) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.audit = audit;
        this.templates = templates;
        this.mappings = mappings;
        this.validation = validation;
    }

    /** An issue with what a resolution needs of its batch and row. */
    private record Target(
            UUID id,
            UUID runId,
            UUID batchId,
            String batchStatus,
            UUID rowId,
            int line,
            String code,
            String severity,
            String field,
            String status,
            int version,
            String original,
            Map<String, Object> suggestion,
            UUID mappingId,
            String source,
            String templateKey,
            Integer templateVersion,
            String rowKey,
            String sha,
            String raw) {}

    private static final String TARGET = """
            SELECT i.id, b.run_id, i.batch_id, b.status AS batch_status, i.row_id, i.source_row_no, i.code, i.severity,
                   i.field, i.status, i.version, i.original_value::text AS original_value,
                   i.suggested_value::text AS suggested_value, b.mapping_id, b.mapping_source, b.template_key,
                   b.template_version, r.source_ref, r.raw::text AS raw,
                   encode(sha256(convert_to(r.raw::text, 'UTF8')), 'hex') AS sha
              FROM import_issues i
              JOIN import_batches b ON b.id = i.batch_id
              JOIN import_rows r ON r.id = i.row_id
            """;

    private static Target target(ResultSet rs, int n) throws SQLException {
        String original = rs.getString("original_value");
        String suggested = rs.getString("suggested_value");
        String source = rs.getString("mapping_source");
        int line = rs.getInt("source_row_no");
        return new Target(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getString("batch_status"),
                rs.getObject("row_id", UUID.class),
                line,
                rs.getString("code"),
                rs.getString("severity"),
                rs.getString("field"),
                rs.getString("status"),
                rs.getInt("version"),
                original == null ? null : JSON.readValue(original, String.class),
                suggested == null ? null : JSON.readValue(suggested, OBJECT),
                rs.getObject("mapping_id", UUID.class),
                source,
                rs.getString("template_key"),
                (Integer) rs.getObject("template_version"),
                RowValidator.rowKeyOf(rs.getString("source_ref"), source, line),
                rs.getString("sha"),
                rs.getString("raw"));
    }

    // ---------------------------------------------------------------------------------------------
    // Reading the queue.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Page queue(UUID runId, Filter filter, Integer limit, String cursor) {
        TenantContext.require();
        String status = filter == null || filter.status() == null ? "open" : filter.status();
        if (!Set.of("open", "resolved", "all").contains(status)) {
            throw ApiException.validation(List.of(new FieldProblem("status", "invalid", "Use open, resolved or all.")));
        }
        int size = limit == null ? DEFAULT_PAGE : limit;
        if (size < 1 || size > MAX_PAGE) {
            throw ApiException.validation(
                    List.of(new FieldProblem("limit", "out_of_range", "From 1 to " + MAX_PAGE + ".")));
        }
        int[] after = Cursor.decode(cursor).map(ReviewService::position).orElse(null);
        return readOnly.execute(s -> {
            runExists(runId);
            StringBuilder sql = new StringBuilder("SELECT " + ValidationService.ISSUE_COLUMNS + """
                    , b.seq, i.ordinal
                      FROM import_issues i
                      JOIN import_batches b ON b.id = i.batch_id
                      LEFT JOIN import_issue_resolutions r ON r.id = i.resolution_id
                     WHERE b.run_id = ?
                    """);
            List<Object> params = new ArrayList<>(List.of(runId));
            if (!"all".equals(status)) {
                sql.append(" AND i.status = ?");
                params.add(status);
            }
            if (filter != null && filter.code() != null) {
                sql.append(" AND i.code = ?");
                params.add(filter.code());
            }
            if (filter != null && filter.batchId() != null) {
                sql.append(" AND i.batch_id = ?");
                params.add(filter.batchId());
            }
            if (filter != null && filter.askCustomer() != null) {
                sql.append(" AND i.ask_customer = ?");
                params.add(filter.askCustomer());
            }
            if (after != null) {
                sql.append(" AND (b.seq, i.source_row_no, i.ordinal) > (?, ?, ?)");
                params.add(after[0]);
                params.add(after[1]);
                params.add(after[2]);
            }
            sql.append(" ORDER BY b.seq, i.source_row_no, i.ordinal LIMIT ?");
            params.add(size + 1);
            List<Object[]> rows = new ArrayList<>();
            jdbc.sql(sql.toString())
                    .params(params)
                    .query((rs, n) -> rows.add(new Object[] {
                        ValidationService.issue(rs, n), rs.getInt("seq"), rs.getInt("source_row_no"), ordinal(rs)
                    }))
                    .list();
            List<Issue> items = new ArrayList<>();
            for (int i = 0; i < Math.min(size, rows.size()); i++) {
                items.add((Issue) rows.get(i)[0]);
            }
            String next = null;
            if (rows.size() > size) {
                Object[] last = rows.get(size - 1);
                next = Cursor.encode(last[1] + "|" + last[2] + "|" + last[3]);
            }
            return new Page(items, next);
        });
    }

    private static int ordinal(ResultSet rs) throws SQLException {
        return rs.getInt("ordinal");
    }

    /** A cursor of the queue: the batch's order, the line and the ordinal of the last issue. */
    private static int[] position(String key) {
        String[] parts = key.split("\\|");
        if (parts.length != 3) {
            throw malformedCursor();
        }
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            throw malformedCursor();
        }
    }

    private static ApiException malformedCursor() {
        return new ApiException(HttpStatus.BAD_REQUEST, "malformed_request", "Malformed request", "Invalid cursor.");
    }

    private void runExists(UUID runId) {
        jdbc.sql("SELECT id FROM import_runs WHERE id = ?")
                .param(runId)
                .query(UUID.class)
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    // ---------------------------------------------------------------------------------------------
    // One resolution.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Outcome resolve(UUID issueId, Resolution resolution, UUID by) {
        TenantContext.require();
        String kind = resolution == null ? null : resolution.resolution();
        if (kind == null || !KINDS.contains(kind)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "resolution",
                    "invalid",
                    "Use edit_value, exclude_row, accept_suggestion, link_row, link_member, override or map_value.")));
        }
        requireBy(by);
        return tx.execute(s -> {
            Target t = locked(issueId, resolution.version());
            requireOpenIssue(t);
            Applied applied = apply(
                    t,
                    kind,
                    resolution.value(),
                    resolution.linkRowId(),
                    resolution.linkMemberId(),
                    resolution.note(),
                    by,
                    null,
                    null,
                    null);
            int rows = applied.revalidate() ? validation.revalidate(t.runId(), applied.rows()) : 0;
            List<Issue> now = jdbc.sql("SELECT " + ValidationService.ISSUE_COLUMNS + """
                              FROM import_issues i LEFT JOIN import_issue_resolutions r ON r.id = i.resolution_id
                             WHERE i.row_id = ? ORDER BY i.ordinal
                            """)
                    .param(t.rowId())
                    .query(ValidationService::issue)
                    .list();
            log.info(
                    "import issue {} of run {} resolved: {} on {} line {}, {} rows validated again",
                    issueId,
                    t.runId(),
                    kind,
                    t.code(),
                    t.line(),
                    rows);
            return new Outcome(applied.resolutionId(), kind, rows, applied.mappingVersion(), now);
        });
    }

    /**
     * The issue under the run's lock: the run {@code FOR UPDATE} (as a validation's write and every other resolution of
     * the run take it), then the issue {@code FOR UPDATE}, and its version as the caller read it. An issue another
     * resolution or a validation replaced is a conflict, not a missing issue, when a resolution names it.
     */
    private Target locked(UUID issueId, int version) {
        Target first = jdbc.sql(TARGET + " WHERE i.id = ?")
                .param(issueId)
                .query(ReviewService::target)
                .optional()
                .orElseThrow(() -> missing(issueId));
        lockRun(first.runId());
        Target t = jdbc.sql(TARGET + " WHERE i.id = ? FOR UPDATE OF i")
                .param(issueId)
                .query(ReviewService::target)
                .optional()
                .orElseThrow(() -> missing(issueId));
        if (t.version() != version) {
            throw Versions.conflict(t.version());
        }
        if ("committed".equals(t.batchStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "batch_committed",
                    "File already imported",
                    "This file is imported; its issues are kept as they were.");
        }
        // Only a file read and checked is reviewed, as a bulk resolution only takes those (review of #868, item 2).
        if (!"parsed".equals(t.batchStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "batch_not_parsed",
                    "File not read",
                    "This file is not read and checked now; read it again and check the import before resolving.");
        }
        return t;
    }

    /**
     * Each row of the run that has a verbatim twin: which of the rows with the same identity and the same cells it is, in
     * the run's order (batch, then line), and how many there are, as the validation counts them before its first pass. A
     * row not in the map has no twin ({@link #ONE}). The identity is made from the stored {@code source_ref}, as
     * {@link RowValidator#rowKeyOf} makes it. JUSTIFICATION-A3: the review's must-fix 1 needs each copy's occurrence.
     */
    private Map<UUID, Copy> occurrences(UUID runId) {
        Map<UUID, Copy> out = new HashMap<>();
        jdbc.sql("""
                        SELECT id, occurrence, copies FROM (
                            SELECT r.id,
                                   row_number() OVER (PARTITION BY rk, sha ORDER BY b.seq, r.source_row_no) AS occurrence,
                                   count(*) OVER (PARTITION BY rk, sha) AS copies
                              FROM import_rows r JOIN import_batches b ON b.id = r.batch_id,
                                   LATERAL (SELECT CASE WHEN r.source_ref LIKE 'col:%' THEN r.source_ref
                                                        ELSE 'row:' || b.mapping_source || ':' || r.source_row_no END
                                                   AS rk,
                                                   encode(sha256(convert_to(r.raw::text, 'UTF8')), 'hex') AS sha) k
                             WHERE b.run_id = ? AND b.status IN ('parsed', 'committed') AND b.mapping_id IS NOT NULL
                        ) counted WHERE copies > 1
                        """)
                .param(runId)
                .query((rs, n) ->
                        out.put(rs.getObject("id", UUID.class), new Copy(rs.getInt("occurrence"), rs.getInt("copies"))))
                .list();
        return out;
    }

    /** Which of the run's verbatim copies of a row it is, and how many there are; {@link #ONE} for a row with no twin. */
    record Copy(int occurrence, int copies) {}

    static final Copy ONE = new Copy(1, 1);

    /** 409 {@code version_conflict} for an issue a resolution was made on and a validation then replaced; else 404. */
    private ApiException missing(UUID issueId) {
        boolean resolved = jdbc.sql("SELECT EXISTS (SELECT 1 FROM import_issue_resolutions WHERE issue_id = ?)")
                .param(issueId)
                .query(Boolean.class)
                .single();
        return resolved
                ? new ApiException(
                        HttpStatus.CONFLICT,
                        "version_conflict",
                        "Version conflict",
                        "This issue was resolved or checked again since it was read; reload the queue.")
                : ApiException.notFound();
    }

    private void lockRun(UUID runId) {
        String status = jdbc.sql("SELECT status FROM import_runs WHERE id = ? FOR UPDATE")
                .param(runId)
                .query(String.class)
                .optional()
                .orElseThrow(ApiException::notFound);
        ValidationService.requireOpen(status);
    }

    private static void requireOpenIssue(Target t) {
        if (!"open".equals(t.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "issue_not_open",
                    "Issue already resolved",
                    "This issue is resolved already; reload the queue.");
        }
    }

    private static void requireBy(UUID by) {
        if (by == null) {
            throw ApiException.validation(
                    List.of(new FieldProblem("resolved_by", "required", "Who resolves the issue is required.")));
        }
    }

    /**
     * What one resolution did: its row, whether the rows it names must be validated again (an edit, an accepted value, a
     * value map), and the mapping version a value map made.
     */
    private record Applied(UUID resolutionId, boolean revalidate, Set<UUID> rows, Integer mappingVersion) {}

    /**
     * Checks one resolution against its issue, writes its row and its audit row, and applies what changes no value. A bulk
     * resolution passes the run's review and its rows' occurrences, read once; one resolution passes null for both.
     */
    private Applied apply(
            Target t,
            String kind,
            String value,
            UUID linkRowId,
            UUID linkMemberId,
            String note,
            UUID by,
            UUID bulkId,
            ReviewState review,
            Map<UUID, Copy> copies) {
        Map<UUID, Copy> occurrences = copies == null ? occurrences(t.runId()) : copies;
        Copy copy = occurrences.getOrDefault(t.rowId(), ONE);
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > MAX_NOTE) {
            throw ApiException.validation(
                    List.of(new FieldProblem("note", "too_long", "At most " + MAX_NOTE + " characters.")));
        }
        Map<String, Object> before = null;
        Map<String, Object> after = new LinkedHashMap<>();
        boolean revalidate = false;
        Set<UUID> rows = Set.of(t.rowId());
        Integer mappingVersion = null;
        switch (kind) {
            case "edit_value" -> {
                requireField(t);
                if (value != null && value.length() > MAX_VALUE) {
                    throw ApiException.validation(List.of(
                            new FieldProblem("value", "too_long", "At most " + MAX_VALUE + " characters, as a cell.")));
                }
                before = valueMap(currentText(t, review, copy));
                after = valueMap(value == null || value.isBlank() ? null : value);
                revalidate = true;
            }
            case "accept_suggestion" -> {
                if (t.suggestion() == null) {
                    throw ApiException.rule("no_suggestion", "This issue has no suggested value to accept.");
                }
                if (t.suggestion().containsKey("value")) {
                    before = valueMap(currentText(t, review, copy));
                    after = valueMap(String.valueOf(t.suggestion().get("value")));
                } else {
                    after.putAll(t.suggestion());
                }
                revalidate = true;
            }
            case "exclude_row" -> {
                requireNote(cleanNote, "Say why the row is left out.");
                after.put("excluded", true);
            }
            case "override" -> {
                if (!"warning".equals(t.severity())) {
                    throw ApiException.rule(
                            "override_not_allowed",
                            "Only a warning can be overridden; a blocking issue must be fixed or its row left out.");
                }
                requireNote(cleanNote, "Say why the warning is accepted.");
            }
            case "link_row" -> {
                if (linkRowId == null || linkRowId.equals(t.rowId())) {
                    throw linkUnknown();
                }
                Map<String, Object> other = jdbc.sql("""
                                SELECT r.id, r.source_ref, r.source_row_no, b.mapping_source,
                                       encode(sha256(convert_to(r.raw::text, 'UTF8')), 'hex') AS sha
                                  FROM import_rows r JOIN import_batches b ON b.id = r.batch_id
                                 WHERE r.id = ? AND b.run_id = ? AND b.status = 'parsed'
                                """)
                        .params(linkRowId, t.runId())
                        .query((rs, n) -> {
                            // The exact copy linked to, so a link never moves to a twin (review of #868, item 6).
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put(
                                    "row_key",
                                    RowValidator.rowKeyOf(
                                            rs.getString("source_ref"),
                                            rs.getString("mapping_source"),
                                            rs.getInt("source_row_no")));
                            m.put("sha", rs.getString("sha"));
                            m.put(
                                    "occurrence",
                                    occurrences.getOrDefault(linkRowId, ONE).occurrence());
                            m.put("row_id", linkRowId.toString());
                            return m;
                        })
                        .optional()
                        .orElseThrow(ReviewService::linkUnknown);
                after.putAll(other);
            }
            case "link_member" -> {
                ImportTemplate template = templates.require(t.templateKey(), t.templateVersion());
                if (linkMemberId == null || !template.recordExists(entitySet(t), linkMemberId)) {
                    throw linkUnknown();
                }
                after.put("member_id", linkMemberId.toString());
            }
            case "map_value" -> {
                MapResult mapped = mapValue(t, value, by, occurrences);
                before = Map.of("mapping_version", mapped.from());
                after.put("mapping_version", mapped.to());
                after.put("label", t.original());
                after.put("value", value.strip());
                rows = mapped.rows();
                revalidate = true;
                mappingVersion = mapped.to();
            }
            case "ask_customer" -> after.put("ask_customer", Boolean.parseBoolean(value));
            default -> throw new IllegalStateException("not a resolution: " + kind);
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO import_issue_resolutions (id, tenant_id, run_id, batch_id, issue_id, row_key, raw_sha256,
                                                              occurrence, copies, source_row_no, code, field,
                                                              resolution, before_value, after_value, note, resolved_by,
                                                              bulk_id)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
                        """)
                .params(
                        id,
                        t.runId(),
                        t.batchId(),
                        t.id(),
                        t.rowKey(),
                        t.sha(),
                        copy.occurrence(),
                        copy.copies(),
                        t.line(),
                        t.code(),
                        t.field(),
                        kind,
                        before == null ? null : JSON.writeValueAsString(before),
                        after.isEmpty() ? null : JSON.writeValueAsString(after),
                        cleanNote,
                        by,
                        bulkId)
                .update();
        settle(t, kind, id, after);
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("run_id", t.runId().toString());
        audited.put("batch_id", t.batchId().toString());
        audited.put("line", t.line());
        audited.put("code", t.code());
        audited.put("field", t.field() == null ? "-" : t.field());
        audited.put("resolution", kind);
        audited.put("resolution_id", id.toString());
        if (bulkId != null) {
            audited.put("bulk_id", bulkId.toString());
        }
        if (mappingVersion != null) {
            audited.put("mapping_version", mappingVersion);
        }
        audit.record(AuditLog.Entry.created("core.import_issue.resolved", "import_issue", t.id(), null, audited));
        return new Applied(id, revalidate, rows, mappingVersion);
    }

    /** What a resolution that changes no value does at once: the issues it resolves, the row's exclusion or link. */
    private void settle(Target t, String kind, UUID resolutionId, Map<String, Object> after) {
        switch (kind) {
            case "exclude_row" -> {
                jdbc.sql("UPDATE import_rows SET excluded_by = ? WHERE id = ?")
                        .params(resolutionId, t.rowId())
                        .update();
                jdbc.sql("""
                                UPDATE import_issues SET status = 'resolved', resolution_id = ?, version = version + 1
                                 WHERE row_id = ? AND status = 'open'
                                """).params(resolutionId, t.rowId()).update();
            }
            case "override", "link_row", "link_member" -> {
                if ("link_row".equals(kind)) {
                    jdbc.sql("UPDATE import_rows SET linked_row_id = ? WHERE id = ?")
                            .params(UUID.fromString(String.valueOf(after.get("row_id"))), t.rowId())
                            .update();
                } else if ("link_member".equals(kind)) {
                    jdbc.sql("UPDATE import_rows SET linked_member_id = ? WHERE id = ?")
                            .params(UUID.fromString(String.valueOf(after.get("member_id"))), t.rowId())
                            .update();
                }
                // Every open issue of this code and field on the row, as a validation resolves them.
                jdbc.sql("""
                                UPDATE import_issues SET status = 'resolved', resolution_id = ?, version = version + 1
                                 WHERE row_id = ? AND code = ? AND field IS NOT DISTINCT FROM ? AND status = 'open'
                                """)
                        .params(resolutionId, t.rowId(), t.code(), t.field())
                        .update();
            }
            case "ask_customer" ->
                jdbc.sql("""
                            UPDATE import_issues SET ask_customer = ?, version = version + 1
                             WHERE row_id = ? AND code = ? AND field IS NOT DISTINCT FROM ?
                            """)
                        .params(Boolean.TRUE.equals(after.get("ask_customer")), t.rowId(), t.code(), t.field())
                        .update();
            default -> {
                // An edit, an accepted value or a value map: the validation of the rows writes their issues again.
            }
        }
    }

    private static Map<String, Object> valueMap(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", text);
        return m;
    }

    private static void requireField(Target t) {
        if (t.field() == null) {
            throw ApiException.rule(
                    "resolution_not_available", "This issue is about the whole row; there is no value to change.");
        }
    }

    private static void requireNote(String note, String why) {
        if (note == null || note.length() < 3) {
            throw ApiException.validation(List.of(new FieldProblem("note", "required", why)));
        }
    }

    private static ApiException linkUnknown() {
        return ApiException.rule(
                "link_target_unknown", "Choose another row of this import, or a record of the business.");
    }

    /** The field's text as the row now reads it: the edit in force (in {@code review}, read now when null), or the cell. */
    private String currentText(Target t, ReviewState review, Copy copy) {
        ReviewState.Row decided = (review == null ? ReviewState.load(jdbc, t.runId()) : review)
                .row(t.rowKey(), t.sha(), copy.occurrence(), copy.copies());
        if (decided.edits().containsKey(t.field())) {
            return decided.edits().get(t.field());
        }
        return cellText(source(t), ValidationService.cells(t.raw()), t.field());
    }

    /** The text of the cell of the column that feeds a field in a source of a mapping, or null. */
    private static String cellText(Map<String, Object> source, Map<String, RawCell> cells, String field) {
        String column = columnOf(source, field);
        if (column == null) {
            return null;
        }
        for (Map.Entry<String, RawCell> cell : cells.entrySet()) {
            if (Parsing.key(cell.getKey()).equals(column)) {
                return RowValidator.text(cell.getValue());
            }
        }
        return null;
    }

    /** The column feeding a field in a source of a mapping, compared as the parse compares headers; or null. */
    private static String columnOf(Map<String, Object> source, String field) {
        if (source.get("columns") instanceof Map<?, ?> columns) {
            for (Map.Entry<?, ?> c : columns.entrySet()) {
                if (c.getValue() instanceof Map<?, ?> target && field.equals(target.get("field"))) {
                    return Parsing.key(String.valueOf(c.getKey()));
                }
            }
        }
        return null;
    }

    private Map<String, Object> source(Target t) {
        Map<String, Object> source =
                MappingValidator.sources(mappings.get(t.mappingId()).document()).get(t.source());
        if (source == null) {
            throw new IllegalStateException("a batch's source is not in its mapping");
        }
        return source;
    }

    private String entitySet(Target t) {
        return String.valueOf(source(t).get("entity_set"));
    }

    private Field fieldOf(Target t) {
        ImportTemplate template = templates.require(t.templateKey(), t.templateVersion());
        String set = entitySet(t);
        return template.entitySets().stream()
                .filter(e -> e.key().equals(set))
                .flatMap(e -> e.fields().stream())
                .filter(f -> f.name().equals(t.field()))
                .findFirst()
                .orElseThrow(() -> ApiException.rule(
                        "resolution_not_available", "This issue's field is not a field of the template."));
    }

    // ---------------------------------------------------------------------------------------------
    // A value map (map_value).
    // ---------------------------------------------------------------------------------------------

    private record MapResult(int from, int to, Set<UUID> rows) {}

    /**
     * Adds the issue's cell text as a label of the field's value map (an enum map for an enum field) in a new version of the
     * batch's mapping, reads every parsed batch of the run that was read with the old version with the new one, and finds
     * the rows of the source whose field the map now reaches. Refused for a personal field (a mapping never holds a cell of
     * a customer's records, spec section 4.2), an issue with no cell text, and a batch whose mapping is no longer the newest
     * version of its name.
     */
    private MapResult mapValue(Target t, String value, UUID by, Map<UUID, Copy> occurrences) {
        requireField(t);
        Field field = fieldOf(t);
        if (field.isPersonal()) {
            throw ApiException.rule(
                    "field_is_personal",
                    "A value of " + t.field()
                            + " is personal data and never goes into a mapping; edit the row instead.");
        }
        if (t.original() == null || t.original().isBlank()) {
            throw ApiException.rule("nothing_to_map", "The cell is empty; there is no value to map.");
        }
        if (value == null || value.isBlank() || value.strip().length() > MAX_MAPPED) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "value", "required", "The value the cell stands for, up to " + MAX_MAPPED + " characters.")));
        }
        Mapping current = mappings.get(t.mappingId());
        Optional<Mapping> latest = mappings.latest(current.name());
        if (latest.isEmpty() || !latest.get().id().equals(current.id())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "mapping_not_latest",
                    "Newer mapping",
                    "A newer version of this file's mapping exists; read the file again with it before mapping a value.");
        }
        Map<String, Object> document = JSON.readValue(JSON.writeValueAsString(current.document()), OBJECT);
        Map<String, Object> source = MappingValidator.sources(document).get(t.source());
        String maps = field.type().kind() == Kind.ENUM ? "enum_maps" : "value_maps";
        @SuppressWarnings("unchecked")
        Map<String, Object> byField = (Map<String, Object>) source.computeIfAbsent(maps, k -> new LinkedHashMap<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> labels =
                (Map<String, Object>) byField.computeIfAbsent(t.field(), k -> new LinkedHashMap<>());
        // A label the mapping already maps, in any spelling the parse compares alike, is the operator's earlier word: a
        // different value is refused, never silently replaced (review of #868, item 7).
        labels.forEach((l, v) -> {
            if (Parsing.key(l).equals(Parsing.key(t.original()))
                    && !value.strip().equals(String.valueOf(v))) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "label_already_mapped",
                        "Value already mapped",
                        "The mapping already maps this value of " + t.field()
                                + " to something else; change the mapping itself to change it.");
            }
        });
        labels.keySet().removeIf(l -> Parsing.key(l).equals(Parsing.key(t.original())));
        labels.put(t.original().strip(), value.strip());
        Mapping saved = mappings.save(
                current.name(), document, by, "Review queue: a value of " + t.field() + " mapped (issue #131).");
        jdbc.sql("UPDATE import_batches SET mapping_id = ? WHERE run_id = ? AND status = 'parsed' AND mapping_id = ?")
                .params(saved.id(), t.runId(), current.id())
                .update();
        // Exactly the rows of the source whose field reads this label, as stored or as edited.
        ReviewState review = ReviewState.load(jdbc, t.runId());
        String label = Parsing.key(t.original());
        Set<UUID> rows = new LinkedHashSet<>();
        jdbc.sql("""
                        SELECT r.id, r.source_row_no, r.source_ref, r.raw::text AS raw,
                               encode(sha256(convert_to(r.raw::text, 'UTF8')), 'hex') AS sha
                          FROM import_rows r JOIN import_batches b ON b.id = r.batch_id
                         WHERE b.run_id = ? AND b.status = 'parsed' AND b.mapping_id = ? AND b.mapping_source = ?
                         ORDER BY b.seq, r.source_row_no
                        """)
                .params(t.runId(), saved.id(), t.source())
                .query((rs, n) -> {
                    String key =
                            RowValidator.rowKeyOf(rs.getString("source_ref"), t.source(), rs.getInt("source_row_no"));
                    Copy copy = occurrences.getOrDefault(rs.getObject("id", UUID.class), ONE);
                    ReviewState.Row decided = review.row(key, rs.getString("sha"), copy.occurrence(), copy.copies());
                    String text = decided.edits().containsKey(t.field())
                            ? decided.edits().get(t.field())
                            : cellText(source, ValidationService.cells(rs.getString("raw")), t.field());
                    if (text != null && Parsing.key(text).equals(label)) {
                        rows.add(rs.getObject("id", UUID.class));
                    }
                    return null;
                })
                .list();
        return new MapResult(current.version(), saved.version(), rows);
    }

    // ---------------------------------------------------------------------------------------------
    // In bulk, and the customer's questions.
    // ---------------------------------------------------------------------------------------------

    @Override
    public BulkOutcome resolveAll(UUID runId, Bulk bulk, UUID by) {
        TenantContext.require();
        String kind = bulk == null ? null : bulk.resolution();
        if (kind == null || !BULK_KINDS.contains(kind)) {
            throw ApiException.validation(List.of(new FieldProblem(
                    "resolution",
                    "invalid",
                    "Use edit_value, exclude_row, accept_suggestion, override or ask_customer for many issues.")));
        }
        if (bulk.code() == null || bulk.code().isBlank()) {
            throw ApiException.validation(List.of(new FieldProblem("code", "required", "The code to resolve.")));
        }
        requireBy(by);
        return tx.execute(s -> {
            lockRun(runId);
            // A batch not of this run is a mistake, never a quiet "nothing resolved" (review of #868, item 3).
            if (bulk.batchId() != null
                    && jdbc.sql("SELECT count(*) FROM import_batches WHERE id = ? AND run_id = ?")
                                    .params(bulk.batchId(), runId)
                                    .query(Long.class)
                                    .single()
                            == 0) {
                throw ApiException.notFound();
            }
            List<Target> targets = jdbc.sql(TARGET + """
                             WHERE b.run_id = ? AND i.code = ? AND i.status = 'open' AND b.status = 'parsed'
                               AND (CAST(? AS uuid) IS NULL OR i.batch_id = ?)
                             ORDER BY b.seq, i.source_row_no, i.ordinal
                               FOR UPDATE OF i
                            """)
                    .params(runId, bulk.code(), bulk.batchId(), bulk.batchId())
                    .query(ReviewService::target)
                    .list();
            UUID bulkId = UUID.randomUUID();
            ReviewState review = ReviewState.load(jdbc, runId);
            Map<UUID, Copy> copies = occurrences(runId);
            Set<UUID> revalidate = new LinkedHashSet<>();
            Set<UUID> excluded = new LinkedHashSet<>();
            int resolved = 0;
            for (Target t : targets) {
                // A row already left out by this bulk resolution has no open issue left to resolve.
                if (excluded.contains(t.rowId())) {
                    continue;
                }
                String value = "ask_customer".equals(kind) ? "true" : bulk.value();
                Applied applied = apply(t, kind, value, null, null, bulk.note(), by, bulkId, review, copies);
                resolved++;
                if (applied.revalidate()) {
                    revalidate.addAll(applied.rows());
                }
                if ("exclude_row".equals(kind)) {
                    excluded.add(t.rowId());
                }
            }
            int rows = revalidate.isEmpty() ? 0 : validation.revalidate(runId, revalidate);
            log.info(
                    "import run {}: {} issues of {} resolved together ({}), {} rows validated again",
                    runId,
                    resolved,
                    bulk.code(),
                    kind,
                    rows);
            return new BulkOutcome(bulkId, resolved, rows);
        });
    }

    @Override
    public Issue askCustomer(UUID issueId, int version, boolean ask, UUID by) {
        TenantContext.require();
        requireBy(by);
        return tx.execute(s -> {
            Target t = locked(issueId, version);
            requireOpenIssue(t);
            apply(t, "ask_customer", String.valueOf(ask), null, null, null, by, null, null, null);
            return jdbc.sql("SELECT " + ValidationService.ISSUE_COLUMNS + """
                              FROM import_issues i LEFT JOIN import_issue_resolutions r ON r.id = i.resolution_id
                             WHERE i.id = ?
                            """)
                    .param(issueId)
                    .query(ValidationService::issue)
                    .single();
        });
    }

    @Override
    public List<Question> questions(UUID runId, boolean export) {
        TenantContext.require();
        return tx.execute(s -> {
            runExists(runId);
            List<Question> out = new ArrayList<>();
            jdbc.sql("""
                            SELECT b.seq, b.source_filename, i.source_row_no, i.field, i.code, i.message
                              FROM import_issues i JOIN import_batches b ON b.id = i.batch_id
                             WHERE b.run_id = ? AND i.ask_customer AND i.status = 'open'
                             ORDER BY b.seq, i.source_row_no, i.ordinal
                            """)
                    .param(runId)
                    .query((rs, n) -> {
                        String file = rs.getString("source_filename");
                        return out.add(new Question(
                                out.size() + 1,
                                RowValidator.shortName(file == null ? "batch " + rs.getInt("seq") : file),
                                rs.getInt("source_row_no"),
                                rs.getString("field"),
                                rs.getString("code"),
                                rs.getString("message")));
                    })
                    .list();
            // Only the file sent to the customer is audited, not a list read on a screen (review of #868, item 10).
            if (export) {
                audit.record(AuditLog.Entry.created(
                        "core.import_run.questions_exported",
                        "import_run",
                        runId,
                        null,
                        Map.of("questions", out.size())));
            }
            return out;
        });
    }
}
