package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.core.imports.ImportUploads.ParseOptions;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ImportMappings} over {@code import_mappings} (migration V139), append-only: a save inserts the next version and
 * nothing here updates or deletes a row. The validation is {@link MappingValidator} against the templates of
 * {@link ImportTemplates}. A document is read back through JSON before it is checked, so what is checked is exactly what is
 * stored. Logs and audit rows carry ids, names, versions and counts, never a header or a label.
 */
@Service
class MappingService implements ImportMappings {

    static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,79}$");
    static final int MAX_NOTE = 500;

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() {};

    private static final String COLUMNS =
            "id, name, version, template_key, template_version, document::text AS document, created_by, note, created_at";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final ImportTemplates templates;
    private final ObjectMapper mapper;

    MappingService(
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            AuditLog audit,
            ImportTemplates templates,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.audit = audit;
        this.templates = templates;
        this.mapper = mapper;
    }

    @Override
    public Mapping save(String name, Map<String, Object> document, UUID createdBy, String note) {
        TenantContext.require();
        List<ApiException.FieldProblem> wrong = new ArrayList<>();
        if (name == null || !NAME.matcher(name).matches()) {
            wrong.add(new ApiException.FieldProblem(
                    "name", "invalid", "Use lower-case letters, digits, dashes and underscores, up to 80."));
        }
        if (note != null && note.length() > MAX_NOTE) {
            wrong.add(new ApiException.FieldProblem("note", "too_long", "At most " + MAX_NOTE + " characters."));
        }
        if (createdBy == null) {
            wrong.add(new ApiException.FieldProblem("created_by", "required", "Who stores the mapping is required."));
        }
        if (!wrong.isEmpty()) {
            throw ApiException.validation(wrong);
        }
        Map<String, Object> doc = normalise(document);
        List<Problem> problems = MappingValidator.check(doc, templates::find);
        refuseIfProblems(problems);
        String templateKey = (String) doc.get("template");
        int templateVersion = ((Number) doc.get("template_version")).intValue();
        // Stored as a whole number, so 1.0 and 1 are the same version and match the row's CHECK.
        doc.put("template_version", templateVersion);
        String json = mapper.writeValueAsString(doc);
        UUID id = UUID.randomUUID();
        try {
            return tx.execute(s -> {
                int version = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM import_mappings WHERE name = ?")
                        .param(name)
                        .query(Integer.class)
                        .single();
                jdbc.sql("""
                                INSERT INTO import_mappings (id, tenant_id, name, version, template_key, template_version,
                                                             document, created_by, note)
                                VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                                """)
                        .params(id, name, version, templateKey, templateVersion, json, createdBy, note)
                        .update();
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("name", name);
                after.put("version", version);
                after.put("template", templateKey);
                after.put("template_version", templateVersion);
                after.put("sources", MappingValidator.sources(doc).size());
                audit.record(AuditLog.Entry.created("core.import_mapping.saved", "import_mapping", id, null, after));
                return get(id);
            });
        } catch (DuplicateKeyException e) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "mapping_version_conflict",
                    "Mapping changed",
                    "Another version of this mapping was stored at the same moment; reload it and save again.");
        }
    }

    @Override
    public Mapping get(UUID mappingId) {
        return tx.execute(s -> jdbc.sql("SELECT " + COLUMNS + " FROM import_mappings WHERE id = ?")
                .param(mappingId)
                .query(this::row)
                .optional()
                .orElseThrow(ApiException::notFound));
    }

    @Override
    public Optional<Mapping> latest(String name) {
        return tx.execute(s -> jdbc.sql(
                        "SELECT " + COLUMNS + " FROM import_mappings WHERE name = ? ORDER BY version DESC LIMIT 1")
                .param(name)
                .query(this::row)
                .optional());
    }

    @Override
    public List<Mapping> versions(String name) {
        return tx.execute(s -> jdbc.sql("SELECT " + COLUMNS + " FROM import_mappings WHERE name = ? ORDER BY version")
                .param(name)
                .query(this::row)
                .list());
    }

    @Override
    public Validation check(Map<String, Object> document) {
        return new Validation(MappingValidator.check(normalise(document), templates::find));
    }

    @Override
    public Validation validate(UUID mappingId, String source, List<String> headers) {
        Mapping mapping = get(mappingId);
        List<Problem> problems = new ArrayList<>(MappingValidator.check(mapping.document(), templates::find));
        if (problems.isEmpty()) {
            ImportTemplate template = templates.require(mapping.templateKey(), mapping.templateVersion());
            problems.addAll(MappingValidator.headers(template, mapping.document(), source, headers));
        }
        return new Validation(problems);
    }

    @Override
    public Validation validateBatch(UUID batchId) {
        BatchMapping batch = tx.execute(s -> jdbc.sql("""
                        SELECT mapping_id, mapping_source, source_headers FROM import_batches WHERE id = ?
                        """)
                .param(batchId)
                .query((rs, n) -> {
                    Array headers = rs.getArray("source_headers");
                    return new BatchMapping(
                            rs.getObject("mapping_id", UUID.class),
                            rs.getString("mapping_source"),
                            headers == null ? null : List.of((String[]) headers.getArray()));
                })
                .optional()
                .orElseThrow(ApiException::notFound));
        if (batch.headers() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "batch_not_parsed",
                    "Batch not parsed",
                    "The batch has not been read yet, so there are no headers to check.");
        }
        if (batch.mappingId() == null) {
            return new Validation(
                    List.of(MappingValidator.problem("BATCH_HAS_NO_MAPPING", null, null, null, null, Map.of())));
        }
        return validate(batch.mappingId(), batch.source(), batch.headers());
    }

    @Override
    public ParseOptions parseOptions(UUID mappingId, String source) {
        Mapping mapping = get(mappingId);
        ImportTemplate template = templates.require(mapping.templateKey(), mapping.templateVersion());
        Map<String, Object> s = MappingValidator.sources(mapping.document()).get(source);
        if (s == null) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "SOURCE_UNKNOWN",
                    "Source unknown",
                    "The mapping has no source " + source + ".");
        }
        int headerRow = s.get("header_row") instanceof Number n ? n.intValue() : 0;
        return new ParseOptions(
                (String) s.get("sheet"),
                MappingValidator.requiredColumns(template, s),
                MappingValidator.ignoredColumns(s),
                headerRow,
                mapping.id(),
                source);
    }

    private record BatchMapping(UUID mappingId, String source, List<String> headers) {}

    /** The document as JSON gives it back: maps, lists, texts, numbers, booleans and nulls. */
    private Map<String, Object> normalise(Map<String, Object> document) {
        if (document == null) {
            return null;
        }
        return mapper.readValue(mapper.writeValueAsString(document), DOCUMENT);
    }

    private static void refuseIfProblems(List<Problem> problems) {
        if (problems.isEmpty()) {
            return;
        }
        if (problems.size() == 1
                && "TEMPLATE_UNKNOWN".equals(problems.getFirst().code())) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "TEMPLATE_UNKNOWN",
                    "Template unknown",
                    problems.getFirst().message());
        }
        List<ApiException.FieldProblem> errors = problems.stream()
                .map(p -> new ApiException.FieldProblem(where(p), p.code(), p.message()))
                .toList();
        throw new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "mapping_invalid",
                "Mapping invalid",
                "The mapping has " + problems.size() + " problem" + (problems.size() == 1 ? "" : "s")
                        + "; nothing was stored.",
                errors);
    }

    /** Where a problem is, as a JSON Pointer into the document. */
    private static String where(Problem p) {
        if (p.pointer() != null) {
            return p.pointer();
        }
        StringBuilder at = new StringBuilder();
        if (p.source() != null) {
            at.append("/sources/").append(SchemaCheck.escape(p.source()));
            if (p.column() != null) {
                at.append("/columns/").append(SchemaCheck.escape(p.column()));
            }
        }
        return at.toString();
    }

    private Mapping row(ResultSet rs, int n) throws SQLException {
        return new Mapping(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getInt("version"),
                rs.getString("template_key"),
                rs.getInt("template_version"),
                mapper.readValue(rs.getString("document"), DOCUMENT),
                rs.getObject("created_by", UUID.class),
                rs.getString("note"),
                rs.getTimestamp("created_at").toInstant());
    }
}
