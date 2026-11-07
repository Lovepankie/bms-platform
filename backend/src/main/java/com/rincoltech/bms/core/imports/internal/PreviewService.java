package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportPreview;
import com.rincoltech.bms.core.imports.ImportRefs;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplate.Field;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.core.imports.internal.RowValidator.RawCell;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ImportPreview} over the staged rows (issue #132): reads only the {@code import_*} tables, in a read-only
 * transaction, so a preview writes nothing. The outcome of a row is decided in this order: excluded by the review, blank,
 * held back by an open blocking issue, else written. The rows to write are given to the template's forecast in commit
 * order, 1,000 at a time, with their typed values.
 *
 * <p>It also answers, for the commit's report, which personal values the run's staged rows hold ({@link #privacy}): the
 * cells of the columns a mapping feeds into a personal field, and those fields' typed values, so any text of the report
 * that names one, on any line or in any batch, is kept as hidden.
 */
@Service
class PreviewService implements ImportPreview {

    /** Rows given to the forecast per read. */
    static final int CHUNK = 1_000;

    /** Personal values shorter than this are not looked for, as the validation does. */
    private static final int SHORTEST_PERSONAL = 4;

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    /** Decimals as BigDecimal, so a quantity of 1.1 is read as written. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /** Whether row {@code r} is excluded, blank or blocked: the three outcomes before {@code write}. */
    private static final String EXCLUDED = "r.excluded_by IS NOT NULL";

    private static final String BLANK =
            "EXISTS (SELECT 1 FROM import_issues i WHERE i.row_id = r.id AND i.code = 'BLANK_ROW')";

    private static final String BLOCKED = """
            EXISTS (SELECT 1 FROM import_issues i WHERE i.row_id = r.id AND i.status = 'open' AND i.severity = 'blocking')""";

    /** A row the commit would write. */
    static final String WRITE = "NOT (" + EXCLUDED + ") AND NOT " + BLANK + " AND NOT " + BLOCKED;

    private final JdbcClient jdbc;
    private final TransactionTemplate readOnly;
    private final ImportTemplates templates;
    private final ImportMappings mappings;

    PreviewService(
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            ImportTemplates templates,
            ImportMappings mappings) {
        this.jdbc = jdbc;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.templates = templates;
        this.mappings = mappings;
    }

    /** One batch the commit writes: pending (added without a file) or parsed. */
    private record BatchRow(
            UUID id,
            int seq,
            String file,
            UUID mappingId,
            String source,
            String templateKey,
            Integer templateVersion,
            String sha) {}

    @Override
    public Preview preview(UUID runId) {
        TenantContext.require();
        return readOnly.execute(s -> compute(runId));
    }

    /** The preview, inside the caller's transaction. */
    Preview compute(UUID runId) {
        jdbc.sql("SELECT id FROM import_runs WHERE id = ?")
                .param(runId)
                .query(UUID.class)
                .optional()
                .orElseThrow(ApiException::notFound);
        List<BatchRow> batches = jdbc.sql("""
                        SELECT id, seq, source_filename, mapping_id, mapping_source, template_key, template_version,
                               source_sha256
                          FROM import_batches
                         WHERE run_id = ? AND status IN ('pending', 'parsed')
                         ORDER BY seq
                        """)
                .param(runId)
                .query((rs, n) -> new BatchRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("seq"),
                        rs.getString("source_filename"),
                        rs.getObject("mapping_id", UUID.class),
                        rs.getString("mapping_source"),
                        rs.getString("template_key"),
                        (Integer) rs.getObject("template_version"),
                        rs.getString("source_sha256")))
                .list();
        ImportTemplate template = template(batches);
        Map<UUID, String> sets = new HashMap<>();
        Map<UUID, Map<String, Object>> documents = new HashMap<>();
        for (BatchRow b : batches) {
            sets.put(b.id(), template == null || b.mappingId() == null ? null : entitySet(b, documents));
        }
        List<String> order = template == null
                ? List.of()
                : template.entitySets().stream().map(EntitySet::key).toList();
        List<BatchRow> ordered = batches.stream()
                .sorted(Comparator.comparingInt((BatchRow b) -> rank(order, sets.get(b.id())))
                        .thenComparingInt(BatchRow::seq))
                .toList();
        Map<String, Integer> records = new LinkedHashMap<>();
        order.forEach(set -> records.put(set, 0));
        List<BatchOutcome> outcomes = new ArrayList<>();
        ImportForecast forecast = template == null ? ImportForecast.NONE : template.forecast(policy(runId), runId);
        for (BatchRow b : ordered) {
            String set = sets.get(b.id());
            BatchOutcome outcome = outcome(b, set);
            outcomes.add(outcome);
            if (set != null) {
                records.merge(set, outcome.write(), Integer::sum);
                EntitySet entities = template.entitySets().stream()
                        .filter(e -> e.key().equals(set))
                        .findFirst()
                        .orElseThrow();
                forecast.batch(b.seq());
                feed(forecast, b.id(), entities, sourceOf(documents.get(b.mappingId()), b.source(), entities, b.sha()));
            }
        }
        return new Preview(
                runId,
                template == null ? null : template.key(),
                template == null ? null : template.version(),
                records,
                outcomes,
                forecast.balances());
    }

    // JUSTIFICATION-A3: the Lead's ruling 4 on #869: the service resolves the writer from the run's templates.
    /**
     * The writers of a run (Lead ruling on #869): for each batch the commit writes, the loader of its template for the
     * entity set its mapping source names, and their identity, one line per batch in order: its number, the template and
     * version, the template's class, the entity set and the loader's {@link BatchWriter#identity()}. A caller never passes
     * a writer, so the dry run rehearses exactly the code the commit runs.
     */
    record Writers(
            Map<UUID, BatchWriter> byBatch,
            String identity,
            List<UUID> order,
            Map<UUID, BatchSource> sources,
            Map<UUID, Integer> setRanks) {

        /** The index of the batch's entity set in its template: its place in the dependency order (Lead ruling 10). */
        int setRank(UUID batchId) {
            return setRanks.getOrDefault(batchId, Integer.MAX_VALUE);
        }

        BatchWriter of(UUID batchId) {
            BatchWriter writer = byBatch.get(batchId);
            if (writer == null) {
                throw new IllegalStateException("no writer resolved for a batch of the run");
            }
            return writer;
        }

        /** Where the batch's rows come from, for their {@code source_ref} (issue #133). */
        BatchSource source(UUID batchId) {
            return sources.get(batchId);
        }

        /** The position of a batch in the template's dependency order (issue #133): its entity set's, then its number. */
        int rank(UUID batchId) {
            int at = order.indexOf(batchId);
            return at < 0 ? Integer.MAX_VALUE : at;
        }
    }

    /**
     * A batch's source as {@link com.rincoltech.bms.core.imports.ImportRefs#sourceRef} reads it (issue #133): the entity
     * set it writes, the mapping source's key, and the file and tab the mapping names (the source's key when it names no
     * file).
     */
    record BatchSource(EntitySet set, String source, String file, String tab, boolean byPosition) {}

    /** The writers of the run's pending and parsed batches, inside the caller's transaction. */
    Writers writers(UUID runId) {
        List<BatchRow> batches = jdbc.sql("""
                        SELECT id, seq, source_filename, mapping_id, mapping_source, template_key, template_version,
                               source_sha256
                          FROM import_batches
                         WHERE run_id = ? AND status IN ('pending', 'parsed')
                         ORDER BY seq
                        """)
                .param(runId)
                .query((rs, n) -> new BatchRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("seq"),
                        rs.getString("source_filename"),
                        rs.getObject("mapping_id", UUID.class),
                        rs.getString("mapping_source"),
                        rs.getString("template_key"),
                        (Integer) rs.getObject("template_version"),
                        rs.getString("source_sha256")))
                .list();
        Map<UUID, BatchWriter> byBatch = new LinkedHashMap<>();
        Map<UUID, Map<String, Object>> documents = new HashMap<>();
        Map<UUID, BatchSource> sources = new HashMap<>();
        Map<UUID, Integer> ranks = new HashMap<>();
        StringBuilder identity = new StringBuilder();
        for (BatchRow b : batches) {
            if (b.mappingId() == null || b.templateKey() == null) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "BATCH_HAS_NO_MAPPING",
                        "Batch read without a mapping",
                        "A file of this import was read without a mapping, so no template writes it; read it again"
                                + " with one.");
            }
            ImportTemplate template = templates.require(b.templateKey(), b.templateVersion());
            String set = entitySet(b, documents);
            if (set == null) {
                throw new IllegalStateException("a batch's mapping source names no entity set of its template");
            }
            BatchWriter loader = template.loader(set);
            byBatch.put(b.id(), loader);
            List<String> order =
                    template.entitySets().stream().map(EntitySet::key).toList();
            ranks.put(b.id(), rank(order, set));
            sources.put(b.id(), source(template, b, set, documents.get(b.mappingId())));
            identity.append(b.seq())
                    .append(' ')
                    .append(template.id())
                    .append(' ')
                    .append(template.getClass().getName())
                    .append(' ')
                    .append(set)
                    .append(' ')
                    .append(loader.identity())
                    .append('\n');
        }
        // The template's dependency order, then the batch's own number (spec section 3.7; issue #133), as the
        // validation and the preview order them.
        List<UUID> order = batches.stream()
                .sorted(Comparator.comparingInt((BatchRow b) -> ranks.get(b.id()))
                        .thenComparingInt(BatchRow::seq))
                .map(BatchRow::id)
                .toList();
        return new Writers(byBatch, identity.toString(), order, sources, ranks);
    }

    /** The source of a batch: its entity set, and the file and tab its mapping source names. */
    private static BatchSource source(ImportTemplate template, BatchRow b, String set, Map<String, Object> document) {
        EntitySet entities = template.entitySets().stream()
                .filter(e -> e.key().equals(set))
                .findFirst()
                .orElseThrow();
        return sourceOf(document, b.source(), entities, b.sha());
    }

    // JUSTIFICATION-A3: the review of f3be0a6 on #872 (must-fix 1, Lead ruling 1): a line-keyed source has its upload.
    /**
     * The file and tab of a batch's rows: the commit's, the preview's and the validation's one reading of them. A source
     * whose rows are known by a reference column is the mapping's file (or the source's key), so a code is the same record
     * whatever the upload is called, across files and runs. A source whose rows are known by their position
     * ({@code row_hash}) carries its upload's identity, the SHA-256 of the file (Lead ruling 1 on #872): its line 7 is
     * another row in another file, so two till rolls of one run, or this month's and next month's, never share a key, and
     * only the same file uploaded again matches. A run never holds one file twice ({@code FILE_ALREADY_UPLOADED}).
     */
    static BatchSource sourceOf(Map<String, Object> document, String sourceKey, EntitySet set, String uploadSha256) {
        Map<String, Object> source = MappingValidator.sources(document).get(sourceKey);
        String mapped = source != null && source.get("file") instanceof String f && !f.isBlank() ? f : sourceKey;
        // A batch added without a file holds no row, so it has no line to key.
        boolean position = byPosition(source) && uploadSha256 != null && !uploadSha256.isBlank();
        String file = position ? "upload:" + uploadSha256.strip() : mapped;
        String tab = source != null && source.get("sheet") instanceof String s && !s.isBlank() ? s : null;
        return new BatchSource(set, sourceKey, file, tab, position);
    }

    /** Whether a mapping source knows its rows by position: the rule {@code row_hash}, or no reference column. */
    static boolean byPosition(Map<String, Object> source) {
        return source == null
                || !(source.get("source_ref") instanceof Map<?, ?> ref)
                || !"column".equals(ref.get("rule"));
    }

    // ---------------------------------------------------------------------------------------------
    // The policy decisions a run needs, and the rows a writer reads (issue #133, spec sections 3.7 and 3.8).
    // JUSTIFICATION-A3: new reads of the staged rows the commit of #133 needs, beside the preview's own reads.
    // ---------------------------------------------------------------------------------------------

    /** The run's recorded policy decisions, by key the option chosen. */
    private Map<String, String> policy(UUID runId) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.sql("SELECT key, value ->> 'option' FROM import_runs, jsonb_each(policy) WHERE id = ? ORDER BY key")
                .param(runId)
                .query((rs, n) -> out.put(rs.getString(1), rs.getString(2)))
                .list();
        return out;
    }

    /** The policies the run's template declares; none when no batch was read with a mapping. */
    List<ImportTemplate.Policy> policies(UUID runId) {
        ImportTemplate template = template(batchRows(runId));
        return template == null ? List.of() : template.policies();
    }

    /**
     * The policies the run needs and has not recorded, inside the caller's transaction: a policy is needed when a row the
     * commit writes, of the policy's entity set, matches its rule.
     */
    List<ImportTemplate.Policy> missingPolicies(UUID runId, Set<String> recorded) {
        List<BatchRow> batches = batchRows(runId);
        ImportTemplate template = template(batches);
        if (template == null) {
            return List.of();
        }
        List<ImportTemplate.Policy> missing = new ArrayList<>();
        Map<UUID, Map<String, Object>> documents = new HashMap<>();
        for (ImportTemplate.Policy p : template.policies()) {
            if (recorded.contains(p.key())) {
                continue;
            }
            EntitySet set = template.entitySets().stream()
                    .filter(e -> e.key().equals(p.entitySet()))
                    .findFirst()
                    .orElse(null);
            boolean needed = false;
            for (BatchRow b : batches) {
                if (set == null || needed || b.mappingId() == null || !set.key().equals(entitySet(b, documents))) {
                    continue;
                }
                needed = anyRow(b.id(), set, p.neededBy());
            }
            if (needed) {
                missing.add(p);
            }
        }
        return missing;
    }

    private boolean anyRow(UUID batchId, EntitySet set, java.util.function.Predicate<Map<String, Object>> rule) {
        int after = 0;
        while (true) {
            List<Object[]> chunk = jdbc.sql("SELECT r.source_row_no, r.normalised::text FROM import_rows r"
                            + " WHERE r.batch_id = ? AND r.source_row_no > ? AND r.normalised IS NOT NULL AND " + WRITE
                            + " ORDER BY r.source_row_no LIMIT " + CHUNK)
                    .params(batchId, after)
                    .query((rs, n) -> new Object[] {rs.getInt(1), rs.getString(2)})
                    .list();
            for (Object[] row : chunk) {
                if (rule.test(typed(set, JSON.readValue((String) row[1], OBJECT)))) {
                    return true;
                }
                after = (Integer) row[0];
            }
            if (chunk.size() < CHUNK) {
                return false;
            }
        }
    }

    private List<BatchRow> batchRows(UUID runId) {
        return jdbc.sql("""
                        SELECT id, seq, source_filename, mapping_id, mapping_source, template_key, template_version,
                               source_sha256
                          FROM import_batches
                         WHERE run_id = ? AND status IN ('pending', 'parsed', 'committed')
                         ORDER BY seq
                        """)
                .param(runId)
                .query((rs, n) -> new BatchRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("seq"),
                        rs.getString("source_filename"),
                        rs.getObject("mapping_id", UUID.class),
                        rs.getString("mapping_source"),
                        rs.getString("template_key"),
                        (Integer) rs.getObject("template_version"),
                        rs.getString("source_sha256")))
                .list();
    }

    /** The rows of a batch the commit writes, after a line, with their stored reference and typed values (issue #133). */
    List<StoredRow> rowsToWrite(UUID batchId, EntitySet set, int afterLine, int limit) {
        return jdbc.sql("SELECT r.source_row_no, r.source_ref, r.normalised::text FROM import_rows r"
                        + " WHERE r.batch_id = ? AND r.source_row_no > ? AND r.normalised IS NOT NULL AND " + WRITE
                        + " ORDER BY r.source_row_no LIMIT ?")
                .params(batchId, afterLine, Math.max(1, Math.min(limit, CHUNK)))
                .query((rs, n) -> {
                    Map<String, Object> stored = JSON.readValue(rs.getString(3), OBJECT);
                    return new StoredRow(rs.getInt(1), rs.getString(2), stored, typed(set, stored));
                })
                .list();
    }

    /** A staged row as stored: its line, the validation's reference, its values as stored and as typed. */
    record StoredRow(int line, String validationRef, Map<String, Object> stored, Map<String, Object> typed) {}

    /** The one template the batches were read with, or null when none was read with a mapping. */
    private ImportTemplate template(List<BatchRow> batches) {
        Set<String> ids = batches.stream()
                .filter(b -> b.templateKey() != null)
                .map(b -> b.templateKey() + "_v" + b.templateVersion())
                .collect(Collectors.toSet());
        if (ids.size() > 1) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "templates_differ",
                    "Templates differ",
                    "The files of this import were read with mappings of different templates; a run keeps one.");
        }
        return batches.stream()
                .filter(b -> b.templateKey() != null)
                .findFirst()
                .map(b -> templates.require(b.templateKey(), b.templateVersion()))
                .orElse(null);
    }

    private String entitySet(BatchRow b, Map<UUID, Map<String, Object>> documents) {
        Map<String, Object> document =
                documents.computeIfAbsent(b.mappingId(), id -> mappings.get(id).document());
        Map<String, Object> source = MappingValidator.sources(document).get(b.source());
        return source == null || !(source.get("entity_set") instanceof String set) ? null : set;
    }

    private static int rank(List<String> order, String set) {
        int at = set == null ? -1 : order.indexOf(set);
        return at < 0 ? order.size() : at;
    }

    private BatchOutcome outcome(BatchRow b, String set) {
        Map<String, Object> c = jdbc.sql("SELECT count(*) AS all_rows,"
                        + " count(*) FILTER (WHERE " + EXCLUDED + ") AS excluded,"
                        + " count(*) FILTER (WHERE NOT (" + EXCLUDED + ") AND " + BLANK + ") AS blank,"
                        + " count(*) FILTER (WHERE NOT (" + EXCLUDED + ") AND NOT " + BLANK + " AND " + BLOCKED
                        + ") AS blocked,"
                        + " count(*) FILTER (WHERE " + WRITE + ") AS to_write"
                        + " FROM import_rows r WHERE r.batch_id = ?")
                .param(b.id())
                .query()
                .singleRow();
        return new BatchOutcome(
                b.id(),
                b.seq(),
                b.file(),
                set,
                count(c.get("all_rows")),
                count(c.get("to_write")),
                count(c.get("excluded")),
                count(c.get("blank")),
                count(c.get("blocked")));
    }

    private static int count(Object n) {
        return n == null ? 0 : Math.toIntExact(((Number) n).longValue());
    }

    /**
     * Gives the forecast every row of the batch the commit would write, in line order, 1,000 at a time: since issue #133
     * not a row the business's imports already hold with the same content, which the commit counts as existing and writes
     * nothing for, so the preview of a re-run predicts only what is new.
     */
    private void feed(ImportForecast forecast, UUID batchId, EntitySet set, BatchSource source) {
        Map<String, Set<String>> imported = RefService.imported(jdbc, source.file(), source.tab());
        int after = 0;
        while (true) {
            List<Object[]> chunk = jdbc.sql("SELECT r.source_row_no, r.normalised::text, r.source_ref"
                            + " FROM import_rows r WHERE r.batch_id = ? AND r.source_row_no > ?"
                            + " AND r.normalised IS NOT NULL AND " + WRITE + " ORDER BY r.source_row_no LIMIT " + CHUNK)
                    .params(batchId, after)
                    .query((rs, n) -> new Object[] {rs.getInt(1), rs.getString(2), rs.getString(3)})
                    .list();
            for (Object[] row : chunk) {
                after = (Integer) row[0];
                Map<String, Object> stored = JSON.readValue((String) row[1], OBJECT);
                if (!imported.isEmpty()) {
                    String ref = RefService.sourceRef(source, (String) row[2], after, stored);
                    if (ImportRefs.holds(imported.get(ImportRefs.keyOf(ref)), ref)) {
                        continue;
                    }
                }
                forecast.row(set.key(), typed(set, stored));
            }
            if (chunk.size() < CHUNK) {
                return;
            }
        }
    }

    /** A row's stored typed values as the template's rules read them: Long, BigDecimal, LocalDate, Instant, Boolean, text. */
    static Map<String, Object> typed(EntitySet set, Map<String, Object> normalised) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : set.fields()) {
            Object v = normalised.get(f.name());
            if (v == null) {
                continue;
            }
            out.put(
                    f.name(),
                    switch (f.type().kind()) {
                        case MONEY -> v instanceof Number n ? n.longValue() : Long.parseLong(v.toString());
                        case QTY -> new BigDecimal(v.toString());
                        case DATE -> LocalDate.parse(v.toString());
                        case DATETIME -> Instant.parse(v.toString());
                        case BOOL -> v instanceof Boolean b ? b : Boolean.parseBoolean(v.toString());
                        default -> v.toString();
                    });
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------
    // The personal values of a run's staged rows, for the report (review of #869).
    // ---------------------------------------------------------------------------------------------

    /**
     * The personal values of every staged row of a run that is read with a mapping, every batch and every line: the cells
     * its mapping feeds into a personal field and those fields' typed values, of four characters or more (the validation's
     * rule), and their digits, of seven or more. {@link #holds} tells whether a text names one anywhere in it, so a message
     * on one line that quotes the buyer of another, or a note of one batch that quotes a row of another, is caught too.
     */
    static final class RunPrivacy {

        /** Values up to this length are found by substring lookups; a longer one by a plain search, there are few. */
        private static final int INDEXED = 64;

        private static final int SHORTEST_DIGITS = 7;

        private final Set<String> values = new HashSet<>();
        private final List<String> longValues = new ArrayList<>();
        private final Set<String> digits = new HashSet<>();
        private int shortest = Integer.MAX_VALUE;
        private int longest;
        private int longestDigits;

        void add(String value) {
            // In any case: a note that writes a name in capitals names it too (review of d8d1e5b).
            String s = value.strip().toLowerCase(Locale.ROOT);
            if (s.length() < SHORTEST_PERSONAL) {
                return;
            }
            if (s.length() > INDEXED) {
                longValues.add(s);
            } else if (values.add(s)) {
                shortest = Math.min(shortest, s.length());
                longest = Math.max(longest, s.length());
            }
            String d = national(s);
            if (d.length() >= SHORTEST_DIGITS && digits.add(d)) {
                longestDigits = Math.max(longestDigits, d.length());
            }
        }

        /** Whether the text holds a personal value, in any case or by its digits ({@code 700000101} in a phone). */
        boolean holds(String written) {
            if (written == null || written.isEmpty()) {
                return false;
            }
            String text = written.toLowerCase(Locale.ROOT);
            for (int i = 0; i < text.length() && !values.isEmpty(); i++) {
                for (int len = shortest; len <= longest && i + len <= text.length(); len++) {
                    if (values.contains(text.substring(i, i + len))) {
                        return true;
                    }
                }
            }
            for (String v : longValues) {
                if (text.contains(v)) {
                    return true;
                }
            }
            String d = text.replaceAll("\\D", "");
            for (int i = 0; i < d.length() && !digits.isEmpty(); i++) {
                for (int len = SHORTEST_DIGITS; len <= longestDigits && i + len <= d.length(); len++) {
                    if (digits.contains(d.substring(i, i + len))) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** The digits of a value without a leading 256 or 0, so one phone written three ways compares equal. */
        private static String national(String v) {
            String d = v.replaceAll("\\D", "");
            if (d.startsWith("256") && d.length() == 12) {
                return d.substring(3);
            }
            return d.startsWith("0") && d.length() == 10 ? d.substring(1) : d;
        }
    }

    /** The personal values of every staged row of the run, read 1,000 rows at a time inside the caller's transaction. */
    RunPrivacy privacy(UUID runId) {
        RunPrivacy privacy = new RunPrivacy();
        List<BatchRow> batches = jdbc.sql("""
                        SELECT id, seq, source_filename, mapping_id, mapping_source, template_key, template_version,
                               source_sha256
                          FROM import_batches
                         WHERE run_id = ? AND status IN ('parsed', 'committed') AND mapping_id IS NOT NULL
                         ORDER BY seq
                        """)
                .param(runId)
                .query((rs, n) -> new BatchRow(
                        rs.getObject("id", UUID.class),
                        rs.getInt("seq"),
                        rs.getString("source_filename"),
                        rs.getObject("mapping_id", UUID.class),
                        rs.getString("mapping_source"),
                        rs.getString("template_key"),
                        (Integer) rs.getObject("template_version"),
                        rs.getString("source_sha256")))
                .list();
        for (BatchRow b : batches) {
            ImportTemplate template = b.templateKey() == null
                    ? null
                    : templates.find(b.templateKey(), b.templateVersion()).orElse(null);
            Map<String, Object> source = MappingValidator.sources(
                            mappings.get(b.mappingId()).document())
                    .get(b.source());
            EntitySet set = template == null || source == null
                    ? null
                    : template.entitySets().stream()
                            .filter(e -> e.key().equals(source.get("entity_set")))
                            .findFirst()
                            .orElse(null);
            if (set == null) {
                continue;
            }
            Set<String> fields = set.fields().stream()
                    .filter(Field::isPersonal)
                    .map(Field::name)
                    .collect(Collectors.toSet());
            if (fields.isEmpty()) {
                continue;
            }
            Set<String> columns = new HashSet<>();
            if (source.get("columns") instanceof Map<?, ?> mapped) {
                mapped.forEach((column, target) -> {
                    if (target instanceof Map<?, ?> t
                            && t.get("field") instanceof String field
                            && fields.contains(field)) {
                        columns.add(Parsing.key(column.toString()));
                    }
                });
            }
            int after = 0;
            while (true) {
                List<Object[]> chunk = jdbc.sql("SELECT source_row_no, raw::text, normalised::text FROM import_rows"
                                + " WHERE batch_id = ? AND source_row_no > ? ORDER BY source_row_no LIMIT " + CHUNK)
                        .params(b.id(), after)
                        .query((rs, n) -> new Object[] {rs.getInt(1), rs.getString(2), rs.getString(3)})
                        .list();
                for (Object[] row : chunk) {
                    after = (Integer) row[0];
                    personal(privacy, (String) row[1], (String) row[2], columns, fields);
                }
                if (chunk.size() < CHUNK) {
                    break;
                }
            }
        }
        return privacy;
    }

    private static void personal(
            RunPrivacy privacy, String raw, String normalised, Set<String> columns, Set<String> fields) {
        for (Map.Entry<String, RawCell> cell : ValidationService.cells(raw).entrySet()) {
            if (columns.contains(Parsing.key(cell.getKey())) && cell.getValue().value() != null) {
                privacy.add(cell.getValue().value().toString());
            }
        }
        if (normalised != null) {
            JSON.readValue(normalised, OBJECT).forEach((field, v) -> {
                if (fields.contains(field) && v != null) {
                    privacy.add(v.toString());
                }
            });
        }
    }
}
