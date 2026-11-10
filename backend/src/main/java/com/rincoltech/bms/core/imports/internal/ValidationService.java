package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportRefs;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.imports.internal.RowValidator.BatchInput;
import com.rincoltech.bms.core.imports.internal.RowValidator.Finding;
import com.rincoltech.bms.core.imports.internal.RowValidator.RawCell;
import com.rincoltech.bms.core.imports.internal.RowValidator.RowResult;
import com.rincoltech.bms.core.imports.internal.RowValidator.SourceRow;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ImportValidation} over {@code import_rows} and {@code import_issues} (migrations V140 and V141). It reads a run's
 * batches in commit order, 1,000 rows at a time, through {@link RowValidator} in two passes, and writes every parsed batch's
 * results in one transaction that holds the run {@code FOR UPDATE}, after checking that the run is open, every batch is as
 * pass 1 read it and no resolution was made meanwhile, so either all of the run's results are written from one consistent
 * view or none. A committed batch is read for its codes and references and never written; the triggers of V140 refuse it
 * as well. Every query runs under the business's row-level security, so only its own rows are read. Logs and the audit row
 * carry ids and counts by code only, never a cell, a name or a file name.
 *
 * <p>Since #131 the review is applied on both passes ({@link ReviewState}): a row reads the edits in force for its cells in
 * place of their fields' cells, its issues are resolved by the resolutions in force (an exclusion resolves them all), a row
 * whose cells changed since it was resolved gets {@code ROW_CHANGED_SINCE_REVIEW}, and an issue found again on the same row
 * with the same code and field keeps its id with the next version. {@link #revalidate} writes the results of some rows of
 * a run only, for the review queue, inside its transaction.
 */
@Service
class ValidationService implements ImportValidation {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    /** The run states validation works in: files are in, nothing is decided yet. */
    static final Set<String> OPEN = Set.of(RunStates.UPLOADED, RunStates.IN_REVIEW);

    private static final TypeReference<Map<String, Map<String, Object>>> RAW = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    /** Decimals as BigDecimal, so a quantity of 1.1 is read as written. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /** The SHA-256 of a row's cells as stored, in lower-case hex: what a resolution was made on (V141). */
    static final String RAW_SHA256 = "encode(sha256(convert_to(raw::text, 'UTF8')), 'hex')";

    /** The columns {@link #issue} reads, from {@code import_issues i} and its resolution {@code r}. */
    static final String ISSUE_COLUMNS = """
            i.id, i.batch_id, i.row_id, i.source_row_no, i.code, i.severity, i.field, i.message, i.status, i.version,
            i.ask_customer, i.suggested_value::text AS suggested_value, i.resolution_id, r.resolution
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate batchJdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnly;
    private final AuditLog audit;
    private final ImportTemplates templates;
    private final ImportMappings mappings;
    private final ImportRuns runs;
    private final RefService refs;

    ValidationService(
            JdbcClient jdbc,
            DataSource dataSource,
            PlatformTransactionManager transactions,
            AuditLog audit,
            ImportTemplates templates,
            ImportMappings mappings,
            ImportRuns runs,
            RefService refs) {
        this.refs = refs;
        this.jdbc = jdbc;
        this.batchJdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactions);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.audit = audit;
        this.templates = templates;
        this.mappings = mappings;
        this.runs = runs;
    }

    /**
     * One batch of the run as the service reads it. {@link #stamp} is what a new parse, a new mapping version, a cancel or a
     * commit changes: the validation writes nothing when any batch no longer matches pass 1 (review of
     * #867). JUSTIFICATION-A3: the stamp and its re-check are the reviewer's must-fix 1.
     */
    private record BatchRow(
            UUID id,
            int seq,
            String status,
            String file,
            UUID mappingId,
            String source,
            String templateKey,
            Integer templateVersion,
            Instant parsedAt,
            Integer rowCount,
            String sha) {

        String stamp() {
            return status + "|" + mappingId + "|" + parsedAt + "|" + rowCount;
        }
    }

    /** A staged row and the SHA-256 of its cells as stored. */
    private record Staged(SourceRow row, String sha) {}

    /**
     * Everything pass 1 learns of a run: the run, its batches and their stamps, the template and the engine with its index,
     * the batches in commit order with their inputs, the review, the row of each copy (for a link), the cells each identity
     * has in the run (to tell a changed row), and each row's occurrence among the rows of its identity and cells.
     */
    private record Prepared(
            RunRow run,
            Map<UUID, String> seen,
            ImportTemplate template,
            RowValidator engine,
            List<BatchRow> ordered,
            List<BatchInput> inputs,
            ReviewState review,
            Map<String, UUID> rowsByCopy,
            Map<String, Set<String>> shasByKey,
            Map<UUID, Integer> occurrences,
            Map<String, Integer> copies) {}

    private static final String BATCHES = """
            SELECT id, seq, status, source_filename, mapping_id, mapping_source, template_key, template_version,
                   parsed_at, row_count, source_sha256
              FROM import_batches
             WHERE run_id = ? AND status IN ('uploaded', 'parsed', 'committed')
             ORDER BY seq
            """;

    /** Every batch of the run that is read, being read or committed, in its order. */
    private List<BatchRow> batches(UUID runId) {
        return jdbc.sql(BATCHES)
                .param(runId)
                .query((rs, n) -> {
                    Timestamp parsed = rs.getTimestamp("parsed_at");
                    return new BatchRow(
                            rs.getObject("id", UUID.class),
                            rs.getInt("seq"),
                            rs.getString("status"),
                            rs.getString("source_filename"),
                            rs.getObject("mapping_id", UUID.class),
                            rs.getString("mapping_source"),
                            rs.getString("template_key"),
                            (Integer) rs.getObject("template_version"),
                            parsed == null ? null : parsed.toInstant(),
                            (Integer) rs.getObject("row_count"),
                            rs.getString("source_sha256"));
                })
                .list();
    }

    private static Map<UUID, String> stamps(List<BatchRow> batches) {
        Map<UUID, String> out = new LinkedHashMap<>();
        batches.forEach(b -> out.put(b.id(), b.stamp()));
        return out;
    }

    private static ApiException changed() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "batch_changed",
                "Import changed",
                "A file of this import was added, read again or cancelled while it was checked; check it again.");
    }

    @Override
    public RunReport validate(UUID runId) {
        TenantContext.require();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("validation takes its own transactions");
        }
        long started = System.nanoTime();
        Prepared p = prepare(runId, false);
        List<BatchReport> reports = finish(runId, p, started);
        return p.template() == null
                ? new RunReport(runId, null, 0, List.of())
                : new RunReport(runId, p.template().key(), p.template().version(), reports);
    }

    /**
     * Validates again only the given rows of a run (issue #131: a resolution's row, or the rows a new value map reaches),
     * inside the caller's transaction, which must already hold the run {@code FOR UPDATE}. Pass 1 reads the whole run, so
     * a row is checked against every other row as a full validation would check it; pass 2 writes those rows only. Returns
     * the number of rows written.
     */
    int revalidate(UUID runId, Collection<UUID> rowIds) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("a partial validation joins the resolution's transaction");
        }
        Prepared p = prepare(runId, true);
        int rows = 0;
        Set<UUID> only = Set.copyOf(rowIds);
        for (int i = 0; i < p.ordered().size(); i++) {
            if ("parsed".equals(p.ordered().get(i).status())) {
                rows += write(p.ordered().get(i), p.inputs().get(i), p, only).rows();
            }
        }
        return rows;
    }

    /** Runs {@code work} in a short read-only transaction, or in the caller's when {@code here}. */
    private <T> T read(boolean here, Supplier<T> work) {
        return here ? work.get() : readOnly.execute(s -> work.get());
    }

    /** The checks before any row is read, and pass 1: what the run holds. */
    private Prepared prepare(UUID runId, boolean here) {
        RunRow run = read(
                here,
                () -> jdbc.sql("SELECT status, cutover_at FROM import_runs WHERE id = ?")
                        .param(runId)
                        .query((rs, n) -> {
                            Timestamp cutover = rs.getTimestamp("cutover_at");
                            return new RunRow(rs.getString("status"), cutover == null ? null : cutover.toInstant());
                        })
                        .optional()
                        .orElseThrow(ApiException::notFound));
        requireOpen(run.status());
        List<BatchRow> all = read(here, () -> batches(runId));
        Map<UUID, String> seen = stamps(all);
        // A file stored and not yet read would be left out while the run moves on (review of #867).
        if (all.stream().anyMatch(b -> "uploaded".equals(b.status()))) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "batch_not_parsed",
                    "File not read yet",
                    "A file of this import is not read yet; wait for it, or cancel it, before checking the rows.");
        }
        if (all.stream().anyMatch(b -> b.mappingId() == null && "parsed".equals(b.status()))) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "BATCH_HAS_NO_MAPPING",
                    "Batch read without a mapping",
                    "A file was read without a mapping; read it again with one before checking its rows.");
        }
        // A committed batch stored before #129 has no mapping: its records are the business's now, and the template
        // answers for them through existingKeys.
        List<BatchRow> batches = all.stream().filter(b -> b.mappingId() != null).toList();
        if (batches.stream()
                        .map(b -> b.templateKey() + "_v" + b.templateVersion())
                        .distinct()
                        .count()
                > 1) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "templates_differ",
                    "Templates differ",
                    "The files of this import were read with mappings of different templates; a run keeps one.");
        }
        ReviewState review = read(here, () -> ReviewState.load(jdbc, runId));
        if (batches.isEmpty()) {
            return new Prepared(
                    run, seen, null, null, List.of(), List.of(), review, Map.of(), Map.of(), Map.of(), Map.of());
        }
        ImportTemplate template = templates.require(
                batches.getFirst().templateKey(), batches.getFirst().templateVersion());
        RowValidator engine =
                new RowValidator(template, templates.catalogue(template.key(), template.version()), run.cutoverAt());
        Map<UUID, Map<String, Object>> documents = new LinkedHashMap<>();
        List<BatchInput> inputs = new ArrayList<>();
        List<BatchRow> ordered = commitOrder(template, batches, documents);
        for (BatchRow b : ordered) {
            inputs.add(new BatchInput(
                    b.id(),
                    b.file() == null ? "batch " + b.seq() : b.file(),
                    documents.get(b.mappingId()),
                    b.source()));
        }
        engine.batches(inputs);
        // Each copy of a line repeated verbatim is counted in the run's order (batch, then line), so a decision on one
        // copy never reaches another, and the run's number of such copies is known before any decision is applied (Lead
        // ruling on #868). With no resolution in the run this is pass 1 itself; with some, the rows are read once more.
        // Only the identities the review concerns are counted (issue #880): the review answers nothing for any other
        // row, and four entries of long keys for every row of the run were held to the end of the validation.
        Map<String, UUID> rowsByCopy = new HashMap<>();
        Map<String, Set<String>> shasByKey = new HashMap<>();
        Map<String, Integer> copies = new HashMap<>();
        Map<UUID, Integer> occurrences = new HashMap<>();
        for (BatchInput input : inputs) {
            readRows(input.batchId(), here, staged -> {
                String key = engine.rowKey(input, staged.row());
                if (review.concerns(key)) {
                    int occurrence = copies.merge(key + "|" + staged.sha(), 1, Integer::sum);
                    occurrences.put(staged.row().rowId(), occurrence);
                    rowsByCopy.put(
                            ReviewState.copy(key, staged.sha(), occurrence),
                            staged.row().rowId());
                    shasByKey.computeIfAbsent(key, k -> new HashSet<>()).add(staged.sha());
                }
                if (review.isEmpty()) {
                    engine.index(input, staged.row());
                }
            });
        }
        // Pass 1 with a review: what the run holds, with the edits in force. A row the review left out is not indexed,
        // so it is no other row's duplicate and no reference reaches it (review of #868, item 5).
        if (!review.isEmpty()) {
            for (BatchInput input : inputs) {
                readRows(input.batchId(), here, staged -> {
                    String key = engine.rowKey(input, staged.row());
                    ReviewState.Row decided = review.row(
                            key,
                            staged.sha(),
                            occurrences.getOrDefault(staged.row().rowId(), 1),
                            copies.getOrDefault(key + "|" + staged.sha(), 1));
                    if (decided.excludedBy() == null) {
                        engine.index(input, reviewed(staged, decided));
                    }
                });
            }
        }
        // The codes references name that no row of the run holds: does the business hold them?
        read(here, () -> {
            engine.missingReferences()
                    .forEach((set, keys) -> engine.addExisting(set, template.existingKeys(set, keys)));
            return null;
        });
        return new Prepared(
                run, seen, template, engine, ordered, inputs, review, rowsByCopy, shasByKey, occurrences, copies);
    }

    /** The row with the review's edits read in place of their fields' cells. */
    private static SourceRow reviewed(Staged staged, ReviewState.Row decided) {
        SourceRow r = staged.row();
        return decided.edits().isEmpty() ? r : new SourceRow(r.rowId(), r.line(), r.cells(), decided.edits());
    }

    /**
     * Pass 2 and the end of a validation, in one transaction that holds the run {@code FOR UPDATE}, as an upload does, so no
     * file of the run is added, read again or cancelled while it runs (the parse and the cancel take the run
     * {@code FOR SHARE}). Every batch must still be as pass 1 saw it, or 409 {@code batch_changed} and nothing is written
     * (second review of #867: a batch written before another changed kept issues built from the old index), and no
     * resolution may have been made meanwhile, or 409 {@code review_changed}. Then each parsed batch's issues and typed
     * values are replaced, an {@code uploaded} run moves to {@code in_review} (the run's own transition joins this
     * transaction, so a second validation that finishes later finds it in review), and the validation is audited.
     */
    private List<BatchReport> finish(UUID runId, Prepared p, long started) {
        List<BatchReport> reports = tx.execute(s -> {
            String status = jdbc.sql("SELECT status FROM import_runs WHERE id = ? FOR UPDATE")
                    .param(runId)
                    .query(String.class)
                    .single();
            requireOpen(status);
            if (!stamps(batches(runId)).equals(p.seen())) {
                throw changed();
            }
            if (ReviewState.last(jdbc, runId) != p.review().last()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "review_changed",
                        "Review changed",
                        "An issue of this import was resolved while it was checked; check it again.");
            }
            List<BatchReport> out = new ArrayList<>();
            for (int i = 0; i < p.ordered().size(); i++) {
                if ("parsed".equals(p.ordered().get(i).status())) {
                    out.add(write(p.ordered().get(i), p.inputs().get(i), p, null));
                }
            }
            if (RunStates.UPLOADED.equals(status)) {
                runs.transition(runId, RunStates.IN_REVIEW);
            }
            // The run's last full validation and its time, for the run's metrics (issue #140); not the run's version.
            jdbc.sql("UPDATE import_runs SET validated_at = now(), validate_ms = ? WHERE id = ?")
                    .params(MetricsService.millisSince(started), runId)
                    .update();
            Map<String, Integer> byCode = new TreeMap<>();
            out.forEach(r -> r.byCode().forEach((code, n) -> byCode.merge(code, n, Integer::sum)));
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("template", p.template() == null ? "none" : p.template().id());
            after.put("batches", out.size());
            after.put("issues_by_code", byCode);
            audit.record(AuditLog.Entry.created("core.import_run.validated", "import_run", runId, null, after));
            return out;
        });
        Map<String, Integer> byCode = new TreeMap<>();
        reports.forEach(r -> r.byCode().forEach((code, n) -> byCode.merge(code, n, Integer::sum)));
        log.info("import run {} validated: {} batches, issues by code {}", runId, reports.size(), byCode);
        return reports;
    }

    /** The batches in commit order: the template's entity sets, then the batch's own order. */
    private List<BatchRow> commitOrder(
            ImportTemplate template, List<BatchRow> batches, Map<UUID, Map<String, Object>> documents) {
        List<String> sets = template.entitySets().stream().map(EntitySet::key).toList();
        Map<UUID, Integer> rank = new LinkedHashMap<>();
        for (BatchRow b : batches) {
            Map<String, Object> document = documents.computeIfAbsent(
                    b.mappingId(), id -> mappings.get(id).document());
            Map<String, Object> source = MappingValidator.sources(document).get(b.source());
            Object set = source == null ? null : source.get("entity_set");
            int at = sets.indexOf(set);
            if (at < 0) {
                throw new IllegalStateException("a batch's mapping source names no entity set of its template");
            }
            rank.put(b.id(), at);
        }
        return batches.stream()
                .sorted(Comparator.comparingInt((BatchRow b) -> rank.get(b.id()))
                        .thenComparingInt(BatchRow::seq))
                .toList();
    }

    /** An issue as it stood before it was written again: its id and version, kept when it is found again. */
    private record Kept(UUID id, int version) {}

    /**
     * Pass 2 of one parsed batch, inside the caller's transaction: its rows' typed values, references and review columns,
     * and its issues, replaced; every row of the batch, or only the rows in {@code only}. An issue found again on its row with
     * the same code and field (the n-th of them) keeps its id with the next version, so a resolver holding it sees a conflict
     * rather than a missing issue.
     */
    private BatchReport write(BatchRow b, BatchInput input, Prepared p, Set<UUID> only) {
        RowValidator engine = p.engine();
        UUID[] ids = only == null ? null : only.toArray(UUID[]::new);
        Map<String, Kept> kept = new HashMap<>();
        Map<String, Integer> seenKey = new HashMap<>();
        jdbc.sql("SELECT id, row_id, code, field, version FROM import_issues WHERE batch_id = ? AND severity <> 'info'"
                        + (only == null ? "" : " AND row_id = ANY(?)")
                        + " ORDER BY source_row_no, ordinal")
                .params(only == null ? List.of(b.id()) : List.of(b.id(), ids))
                .query((rs, n) -> {
                    String key = rs.getObject("row_id", UUID.class) + "|"
                            + ReviewState.key(rs.getString("code"), rs.getString("field"));
                    int nth = seenKey.merge(key, 1, Integer::sum);
                    kept.put(key + "|" + nth, new Kept(rs.getObject("id", UUID.class), rs.getInt("version")));
                    return null;
                })
                .list();
        if (only == null) {
            jdbc.sql("DELETE FROM import_issues WHERE batch_id = ?")
                    .param(b.id())
                    .update();
        } else {
            jdbc.sql("DELETE FROM import_issues WHERE batch_id = ? AND row_id = ANY(?)")
                    .params(b.id(), ids)
                    .update();
        }
        // The keys of this source file and tab the business already imported (issue #133): a row whose key is held with
        // another content hash is CHANGED_SINCE_IMPORT, blocking, so it is never written twice (spec section 10.1).
        PreviewService.BatchSource from = PreviewService.sourceOf(input.mapping(), input.sourceKey(), null, b.sha());
        Map<String, Set<String>> imported = refs.imported(from.file(), from.tab());
        Map<String, Integer> bySeverity = new TreeMap<>();
        Map<String, Integer> byCode = new TreeMap<>();
        int[] rows = {0};
        List<Object[]> updates = new ArrayList<>();
        List<Object[]> issues = new ArrayList<>();
        Consumer<Staged> each = staged -> {
            SourceRow row = staged.row();
            String rowKey = engine.rowKey(input, row);
            ReviewState.Row decided = p.review()
                    .row(
                            rowKey,
                            staged.sha(),
                            p.occurrences().getOrDefault(row.rowId(), 1),
                            p.copies().getOrDefault(rowKey + "|" + staged.sha(), 1),
                            p.shasByKey().getOrDefault(rowKey, Set.of(staged.sha())));
            RowResult result = engine.validate(input, reviewed(staged, decided));
            rows[0]++;
            updates.add(new Object[] {
                JSON.writeValueAsString(result.normalised()),
                result.sourceRef(),
                decided.excludedBy(),
                decided.linkedRowKey() == null ? null : p.rowsByCopy().get(decided.linkedRowKey()),
                decided.linkedMemberId(),
                row.rowId()
            });
            List<Finding> findings = new ArrayList<>(result.findings().size() + 1);
            if (decided.changed()) {
                findings.add(engine.rowChanged(input, row.line()));
            }
            if (!imported.isEmpty()) {
                String ref = RefService.sourceRef(from, result.sourceRef(), row.line(), result.normalised());
                Set<String> held = imported.get(ImportRefs.keyOf(ref));
                if (held != null && !ImportRefs.holds(held, ref)) {
                    findings.add(engine.changedSinceImport(input, row.line()));
                }
            }
            findings.addAll(result.findings());
            Map<String, Integer> nthOnRow = new HashMap<>();
            int ordinal = 0;
            for (Finding f : findings) {
                ordinal++;
                bySeverity.merge(f.severity(), 1, Integer::sum);
                byCode.merge(f.code(), 1, Integer::sum);
                boolean info = "info".equals(f.severity());
                UUID resolution = info
                        ? null
                        : decided.excludedBy() != null ? decided.excludedBy() : decided.resolution(f.code(), f.field());
                String key = row.rowId() + "|" + ReviewState.key(f.code(), f.field());
                Kept before = info ? null : kept.get(key + "|" + nthOnRow.merge(key, 1, Integer::sum));
                // The template's own suggestion (a repaired date, an implied rate: issue #136), else the engine's.
                String suggestion = f.suggestion() != null ? f.suggestion() : engine.suggestion(input, f);
                Map<String, Object> suggested = "ROW_CHANGED_SINCE_REVIEW".equals(f.code())
                        ? Map.of("review", "as_uploaded")
                        : suggestion == null ? null : Map.of("value", suggestion);
                issues.add(new Object[] {
                    before == null ? UUID.randomUUID() : before.id(),
                    b.id(),
                    row.rowId(),
                    row.line(),
                    ordinal,
                    f.code(),
                    f.severity(),
                    f.field(),
                    f.message(),
                    // An edited field's text is the operator's, kept only in its resolution row (issue #131).
                    f.original() == null
                                    || (f.field() != null && decided.edits().containsKey(f.field()))
                            ? null
                            : JSON.writeValueAsString(f.original()),
                    info || resolution != null ? "resolved" : "open",
                    before == null ? 1 : before.version() + 1,
                    !info && decided.asked(f.code(), f.field()),
                    suggested == null ? null : JSON.writeValueAsString(suggested),
                    resolution
                });
            }
            if (updates.size() >= RowValidator.CHUNK) {
                flush(updates, issues);
            }
        };
        if (only == null) {
            readRows(b.id(), true, each);
        } else {
            readRowsIn(b.id(), ids, each);
        }
        flush(updates, issues);
        return new BatchReport(b.id(), b.seq(), rows[0], bySeverity, byCode);
    }

    private void flush(List<Object[]> updates, List<Object[]> issues) {
        if (!updates.isEmpty()) {
            batchJdbc.batchUpdate("""
                    UPDATE import_rows SET normalised = CAST(? AS jsonb), source_ref = ?, excluded_by = ?, linked_row_id = ?,
                                           linked_member_id = ?
                     WHERE id = ?
                    """, updates);
            updates.clear();
        }
        if (!issues.isEmpty()) {
            batchJdbc.batchUpdate("""
                    INSERT INTO import_issues (id, tenant_id, batch_id, row_id, source_row_no, ordinal, code, severity,
                                               field, message, original_value, status, version, ask_customer,
                                               suggested_value, resolution_id)
                    VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?,
                            CAST(? AS jsonb), ?)
                    """, issues);
            issues.clear();
        }
    }

    /**
     * Every row of a batch in line order, 1,000 at a time: each chunk in a short read-only transaction, or in the caller's
     * when {@code here}.
     */
    private void readRows(UUID batchId, boolean here, Consumer<Staged> each) {
        int after = 0;
        while (true) {
            int from = after;
            List<Staged> chunk = read(here, () -> chunk(batchId, from));
            chunk.forEach(each);
            if (chunk.size() < RowValidator.CHUNK) {
                return;
            }
            after = chunk.getLast().row().line();
        }
    }

    /** The named rows of a batch in line order, inside the caller's transaction. */
    private void readRowsIn(UUID batchId, UUID[] ids, Consumer<Staged> each) {
        jdbc.sql("SELECT id, source_row_no, raw::text AS raw, " + RAW_SHA256 + " AS sha FROM import_rows"
                        + " WHERE batch_id = ? AND id = ANY(?) ORDER BY source_row_no")
                .params(batchId, ids)
                .query(ValidationService::staged)
                .list()
                .forEach(each);
    }

    private List<Staged> chunk(UUID batchId, int afterLine) {
        return jdbc.sql("SELECT id, source_row_no, raw::text AS raw, " + RAW_SHA256 + " AS sha FROM import_rows"
                        + " WHERE batch_id = ? AND source_row_no > ? ORDER BY source_row_no LIMIT ?")
                .params(batchId, afterLine, RowValidator.CHUNK)
                .query(ValidationService::staged)
                .list();
    }

    private static Staged staged(ResultSet rs, int n) throws SQLException {
        return new Staged(
                new SourceRow(rs.getObject("id", UUID.class), rs.getInt("source_row_no"), cells(rs.getString("raw"))),
                rs.getString("sha"));
    }

    /** A row's stored cells as the engine reads them. */
    static Map<String, RawCell> cells(String raw) {
        Map<String, RawCell> out = new LinkedHashMap<>();
        JSON.readValue(raw, RAW)
                .forEach((header, cell) -> out.put(
                        header,
                        new RawCell(
                                cell == null ? null : cell.get("value"),
                                cell == null ? null : (String) cell.get("type"))));
        return out;
    }

    @Override
    public List<Issue> issues(UUID batchId) {
        TenantContext.require();
        return readOnly.execute(s -> {
            jdbc.sql("SELECT id FROM import_batches WHERE id = ?")
                    .param(batchId)
                    .query(UUID.class)
                    .optional()
                    .orElseThrow(ApiException::notFound);
            return jdbc.sql("SELECT " + ISSUE_COLUMNS + """
                              FROM import_issues i LEFT JOIN import_issue_resolutions r ON r.id = i.resolution_id
                             WHERE i.batch_id = ? ORDER BY i.source_row_no, i.ordinal
                            """)
                    .param(batchId)
                    .query(ValidationService::issue)
                    .list();
        });
    }

    /** One issue from the columns of {@link #ISSUE_COLUMNS}. */
    static Issue issue(ResultSet rs, int n) throws SQLException {
        String suggested = rs.getString("suggested_value");
        return new Issue(
                rs.getObject("id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getObject("row_id", UUID.class),
                rs.getInt("source_row_no"),
                rs.getString("code"),
                rs.getString("severity"),
                rs.getString("field"),
                rs.getString("message"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getBoolean("ask_customer"),
                suggested == null ? null : JSON.readValue(suggested, OBJECT),
                rs.getObject("resolution_id", UUID.class),
                rs.getString("resolution"));
    }

    private record RunRow(String status, Instant cutoverAt) {}

    static void requireOpen(String status) {
        if (!OPEN.contains(status)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "run_not_open",
                    "Import not open for checking",
                    "Rows are checked only while the import is uploaded or in review; this one is "
                            + RunStates.words(status) + ".");
        }
    }
}
