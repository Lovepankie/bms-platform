package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.imports.ImportMetrics;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.LogRedaction;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.kernel.Versions;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The metrics of a run, its defect log and the platform report (issue #140, spec section 8, migration V148). The metrics
 * are the database function {@code import_run_metrics} read as JSON: one definition for the business's route and the
 * platform report, from stored columns only. Defects are written here, each change audited in the business's audit log
 * with codes and never the description.
 */
@Service
class MetricsService implements ImportMetrics {

    /** At most this many runs on the platform report and in its CSV. */
    static final int PLATFORM_LIMIT = 500;

    /** Seven digits or more in a row: an account, ID or phone number (a line number or a date never has so many). */
    private static final Pattern DIGITS = Pattern.compile("(?<!\\d)\\d{7,}(?!\\d)");

    private static final String DEFECT_COLUMNS = """
            id, run_id, created_at, logged_by, description, expected_code, new_code_needed, status, fix_ref, closed_by,
            closed_at, version
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final AuditLog audit;
    private final PlatformAuditLog platformAudit;
    private final ImportTemplates templates;
    private final PreviewService preview;

    MetricsService(
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            ObjectMapper mapper,
            AuditLog audit,
            PlatformAuditLog platformAudit,
            ImportTemplates templates,
            PreviewService preview) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.mapper = mapper;
        this.audit = audit;
        this.platformAudit = platformAudit;
        this.templates = templates;
        this.preview = preview;
    }

    /** Milliseconds since a {@link System#nanoTime()} reading, for a step's time stored on its run or batch. */
    static int millisSince(long startedNanos) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000));
    }

    // ---- Metrics ----

    @Override
    public Metrics metrics(UUID runId) {
        TenantContext.require();
        String json = tx.execute(s -> jdbc.sql("SELECT CAST(import_run_metrics(?) AS text)")
                .param(runId)
                .query(String.class)
                .optional()
                .orElse(null));
        if (json == null) {
            throw ApiException.notFound();
        }
        return metricsOf(mapper.readTree(json), false);
    }

    @Override
    public List<PlatformRun> platformReport(Instant since, int limit) {
        requireUnbound();
        return tx.execute(s -> platformRuns(since, limit));
    }

    @Override
    public String platformReportCsv(Instant since, UUID operatorId) {
        requireUnbound();
        return tx.execute(s -> {
            List<PlatformRun> runs = platformRuns(since, PLATFORM_LIMIT);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("runs", runs.size());
            data.put("since", since == null ? "all" : since.toString());
            platformAudit.record("platform.imports.report_exported", operatorId, null, data);
            return csv(runs);
        });
    }

    private List<PlatformRun> platformRuns(Instant since, int limit) {
        return jdbc.sql("SELECT tenant_slug, CAST(metrics AS text) AS metrics"
                        + " FROM platform_import_runs_report(CAST(? AS timestamptz), ?)")
                .params(since == null ? null : Timestamp.from(since), Math.clamp(limit, 1, PLATFORM_LIMIT))
                .query((rs, n) -> new PlatformRun(
                        rs.getString("tenant_slug"), metricsOf(mapper.readTree(rs.getString("metrics")), true)))
                .list();
    }

    private static void requireUnbound() {
        if (TenantContext.current().isPresent()) {
            throw new IllegalStateException("the platform report reads across businesses and is never bound to one");
        }
    }

    /** One row per run: the business's slug and the run's figures, never a business's name or a value of a row. */
    static String csv(List<PlatformRun> runs) {
        StringBuilder out = new StringBuilder(String.join(
                        ",",
                        "tenant_slug",
                        "run_id",
                        "status",
                        "origin",
                        "fabricated",
                        "templates",
                        "created_at",
                        "committed_at",
                        "signed_off_at",
                        "purged_at",
                        "batches",
                        "rows",
                        "excluded",
                        "written",
                        "existing",
                        "skipped",
                        "issues",
                        "blocking_issues",
                        "resolutions",
                        "questions_asked",
                        "questions_answered",
                        "median_days_to_answer",
                        "mapping_versions",
                        "mapping_versions_made",
                        "rehearsals",
                        "rehearsal_differences",
                        "defects_open",
                        "defects_closed",
                        "cutover_seconds"))
                .append('\n');
        for (PlatformRun r : runs) {
            Metrics m = r.metrics();
            List<String> cells = new ArrayList<>();
            cells.add(ReviewController.cell(r.tenantSlug()));
            cells.add(String.valueOf(m.runId()));
            cells.add(ReviewController.cell(m.status()));
            cells.add(ReviewController.cell(m.origin()));
            cells.add(String.valueOf(m.fabricated()));
            cells.add(ReviewController.cell(String.join(
                    " ",
                    m.templates().stream()
                            .map(t -> t.templateKey() + "_v" + t.templateVersion())
                            .toList())));
            cells.add(text(m.stamps().createdAt()));
            cells.add(text(m.stamps().committedAt()));
            cells.add(text(m.stamps().signedOffAt()));
            cells.add(text(m.stamps().purgedAt()));
            cells.add(String.valueOf(m.batches().size()));
            cells.add(String.valueOf(sum(m.templates(), TemplateRows::rows)));
            cells.add(String.valueOf(sum(m.templates(), TemplateRows::excluded)));
            cells.add(String.valueOf(sum(m.templates(), TemplateRows::written)));
            cells.add(String.valueOf(sum(m.templates(), TemplateRows::existing)));
            cells.add(String.valueOf(sum(m.templates(), TemplateRows::skipped)));
            cells.add(String.valueOf(
                    m.issues().stream().mapToLong(IssueCount::total).sum()));
            cells.add(String.valueOf(m.issues().stream()
                    .filter(i -> "blocking".equals(i.severity()))
                    .mapToLong(IssueCount::total)
                    .sum()));
            cells.add(String.valueOf(
                    m.resolutions().stream().mapToLong(ResolutionCount::count).sum()));
            cells.add(String.valueOf(m.questions().asked()));
            cells.add(String.valueOf(m.questions().answered()));
            cells.add(
                    m.questions().medianDays() == null
                            ? ""
                            : m.questions().medianDays().toPlainString());
            cells.add(String.valueOf(m.mappings().size()));
            cells.add(String.valueOf(m.mappingVersionsMade()));
            cells.add(String.valueOf(m.rehearsals().size()));
            cells.add(String.valueOf(m.rehearsalDifferences().size()));
            cells.add(String.valueOf(m.defects().open()));
            cells.add(String.valueOf(m.defects().closed()));
            // A run not committed has no cutover seconds: an empty cell (findFirst refuses a null element).
            Long cutover = m.stages().stream()
                    .filter(st -> "cutover".equals(st.key()))
                    .findFirst()
                    .map(Stage::seconds)
                    .orElse(null);
            cells.add(cutover == null ? "" : String.valueOf(cutover));
            out.append(String.join(",", cells)).append('\n');
        }
        return out.toString();
    }

    private static String text(Instant at) {
        return at == null ? "" : at.toString();
    }

    private static long sum(List<TemplateRows> rows, Function<TemplateRows, Long> of) {
        return rows.stream()
                .map(of)
                .filter(v -> v != null)
                .mapToLong(Long::longValue)
                .sum();
    }

    /**
     * The JSON of {@code import_run_metrics} as the port's records; keys are the function's, read one by one. For the
     * platform the business's own labels are left out (review of #888): a mapping's name, a batch's mapping source and a
     * rehearsal source's file name are typed by the business and may carry its trading name; only the slug names it.
     */
    static Metrics metricsOf(JsonNode m, boolean platform) {
        JsonNode st = m.path("stamps");
        JsonNode q = m.path("questions");
        JsonNode d = m.path("defects");
        return new Metrics(
                UUID.fromString(str(m, "run_id")),
                str(m, "status"),
                str(m, "origin"),
                m.path("fabricated").asBoolean(),
                new Stamps(
                        at(st, "created_at"),
                        at(st, "uploaded_at"),
                        at(st, "in_review_at"),
                        at(st, "validated_at"),
                        at(st, "ready_at"),
                        at(st, "dry_run_at"),
                        at(st, "committing_at"),
                        at(st, "committed_at"),
                        at(st, "reconciled_at"),
                        at(st, "signed_off_at"),
                        at(st, "purged_at"),
                        at(st, "cancelled_at")),
                list(
                        m,
                        "stages",
                        x -> new Stage(
                                x.path("number").asInt(),
                                str(x, "key"),
                                at(x, "started_at"),
                                at(x, "ended_at"),
                                num(x, "seconds"))),
                list(
                        m,
                        "steps",
                        x -> new Step(
                                str(x, "step"),
                                num(x, "batch_seq") == null
                                        ? null
                                        : num(x, "batch_seq").intValue(),
                                x.path("millis").asLong())),
                list(
                        m,
                        "rehearsal_timings",
                        x -> new Timing(str(x, "step"), x.path("millis").asLong())),
                list(
                        m,
                        "batches",
                        x -> new BatchRows(
                                x.path("seq").asInt(),
                                str(x, "status"),
                                str(x, "template_key"),
                                num(x, "template_version") == null
                                        ? null
                                        : num(x, "template_version").intValue(),
                                platform ? null : str(x, "mapping_source"),
                                num(x, "rows"),
                                x.path("excluded").asLong(),
                                num(x, "read"),
                                num(x, "written"),
                                num(x, "existing"),
                                num(x, "skipped"))),
                list(
                        m,
                        "templates",
                        x -> new TemplateRows(
                                str(x, "template_key"),
                                x.path("template_version").asInt(),
                                x.path("batches").asLong(),
                                num(x, "rows"),
                                num(x, "excluded"),
                                num(x, "read"),
                                num(x, "written"),
                                num(x, "existing"),
                                num(x, "skipped"))),
                list(
                        m,
                        "issues",
                        x -> new IssueCount(
                                str(x, "code"),
                                str(x, "severity"),
                                x.path("total").asLong(),
                                x.path("open").asLong())),
                list(
                        m,
                        "resolutions",
                        x -> new ResolutionCount(str(x, "kind"), x.path("count").asLong())),
                new Questions(
                        q.path("asked").asLong(),
                        q.path("answered").asLong(),
                        q.path("open").asLong(),
                        decimal(q, "median_days"),
                        decimal(q, "max_days")),
                list(
                        m,
                        "mappings",
                        x -> new MappingVersion(
                                platform ? null : str(x, "name"),
                                x.path("version").asInt(),
                                str(x, "template_key"),
                                x.path("template_version").asInt())),
                m.path("mapping_versions_made").asLong(),
                list(
                        m,
                        "rehearsals",
                        x -> new Rehearsal(
                                at(x, "at"),
                                str(x, "outcome"),
                                x.path("matches_dry_run").isBoolean()
                                        ? x.path("matches_dry_run").asBoolean()
                                        : null)),
                list(
                        m,
                        "rehearsal_differences",
                        x -> new Difference(
                                platform ? null : str(x, "source"),
                                str(x, "measure"),
                                num(x, "rehearsal"),
                                num(x, "cutover"))),
                new Defects(
                        d.path("open").asLong(),
                        d.path("closed").asLong(),
                        d.path("new_codes_needed").asLong(),
                        list(
                                d,
                                "codes",
                                x -> new CodeCount(
                                        str(x, "code"), x.path("count").asLong()))));
    }

    private static <T> List<T> list(JsonNode parent, String key, Function<JsonNode, T> each) {
        List<T> out = new ArrayList<>();
        JsonNode array = parent.path(key);
        if (array.isArray()) {
            for (JsonNode x : array) {
                out.add(each.apply(x));
            }
        }
        return List.copyOf(out);
    }

    private static String str(JsonNode node, String key) {
        JsonNode v = node.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asString();
    }

    private static Long num(JsonNode node, String key) {
        JsonNode v = node.path(key);
        return v.isNumber() ? v.asLong() : null;
    }

    private static BigDecimal decimal(JsonNode node, String key) {
        JsonNode v = node.path(key);
        return v.isNumber() ? new BigDecimal(v.asString()) : null;
    }

    private static Instant at(JsonNode node, String key) {
        String s = str(node, key);
        return s == null ? null : Instant.parse(s);
    }

    // ---- The defect log ----

    @Override
    public List<Defect> defects(UUID runId) {
        TenantContext.require();
        return tx.execute(s -> {
            runStatus(runId, false);
            return jdbc.sql("SELECT " + DEFECT_COLUMNS
                            + " FROM import_defects WHERE run_id = ? ORDER BY created_at, id")
                    .param(runId)
                    .query(MetricsService::defect)
                    .list();
        });
    }

    @Override
    public Defect logDefect(UUID runId, NewDefect defect, UUID by) {
        TenantContext.require();
        if (by == null) {
            throw new IllegalArgumentException("a defect needs who logs it");
        }
        String description =
                defect.description() == null ? "" : defect.description().strip();
        // No code: a new one is needed and the operator proposes it in the description (Lead on #888).
        String code = defect.expectedCode() == null || defect.expectedCode().isBlank()
                ? null
                : defect.expectedCode().strip();
        return tx.execute(s -> {
            String status = runStatus(runId, true);
            if (!List.of(RunStates.COMMITTED, RunStates.SIGNED_OFF, RunStates.PURGED)
                    .contains(status)) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "run_not_imported",
                        "Import not committed",
                        "A defect is logged against an import that was committed; this one is " + status + ".");
            }
            if (namesPersonalData(runId, description)) {
                throw ApiException.rule(
                        "description_names_personal_data",
                        "Describe the defect by file, line, field and code, never by a person's name, phone, ID number"
                                + " or email, and with no number of seven digits or more (write a date as 2026-10-01).");
            }
            // Only a code of the platform's own catalogue, never one the business typed: so the purge keeps it and the
            // platform report shows it (review of #888, M1).
            if (code != null && !catalogueOf(runId).contains(code)) {
                throw ApiException.rule(
                        "expected_code_unknown",
                        "That code is not in the issue-code catalogue of this import's template. Leave the code empty"
                                + " and write the code you propose in the description.");
            }
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO import_defects (id, tenant_id, run_id, logged_by, description, expected_code,
                                                        new_code_needed)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?)
                            """).params(id, runId, by, description, code, code == null).update();
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("defect_id", id);
            after.put("expected_code", code == null ? "none" : code);
            after.put("new_code_needed", code == null);
            audit.record(AuditLog.Entry.created("core.import_run.defect_logged", "import_run", runId, null, after));
            return defect(id);
        });
    }

    @Override
    public Defect closeDefect(UUID defectId, int version, String fixRef, UUID by) {
        TenantContext.require();
        if (by == null) {
            throw new IllegalArgumentException("a defect needs who closes it");
        }
        return tx.execute(s -> {
            Defect current = jdbc.sql("SELECT " + DEFECT_COLUMNS + " FROM import_defects WHERE id = ? FOR UPDATE")
                    .param(defectId)
                    .query(MetricsService::defect)
                    .optional()
                    .orElseThrow(ApiException::notFound);
            if ("closed".equals(current.status())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "defect_closed",
                        "Defect closed",
                        "This defect is closed with its fix already.");
            }
            if (current.version() != version) {
                throw Versions.conflict(current.version());
            }
            jdbc.sql("""
                            UPDATE import_defects SET status = 'closed', fix_ref = ?, closed_by = ?, closed_at = now(),
                                   version = version + 1
                             WHERE id = ?
                            """).params(fixRef.strip(), by, defectId).update();
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("defect_id", defectId);
            after.put("status", "closed");
            after.put("fix_ref", fixRef.strip());
            audit.record(new AuditLog.Entry(
                    "core.import_run.defect_closed",
                    "import_run",
                    current.runId(),
                    null,
                    Map.of("defect_id", defectId, "status", "open"),
                    after));
            return defect(defectId);
        });
    }

    /** The run's status, under a share lock when logging against it; 404 for a run not of the business. */
    private String runStatus(UUID runId, boolean lock) {
        return jdbc.sql("SELECT status FROM import_runs WHERE id = ?" + (lock ? " FOR SHARE" : ""))
                .param(runId)
                .query(String.class)
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    /**
     * Whether a description names a personal value: one the kernel's log redaction recognises (an email address, an ID
     * number, a phone number, a name given as a name), seven digits or more in a row, or a personal value of
     * the run's staged rows while they are kept (none after the purge).
     */
    private boolean namesPersonalData(UUID runId, String description) {
        return !LogRedaction.redact(description).equals(description)
                || DIGITS.matcher(description).find()
                || preview.privacy(runId).holds(description);
    }

    /** The issue codes of the templates the run's batches were read with. */
    private List<String> catalogueOf(UUID runId) {
        List<String> codes = new ArrayList<>();
        List<Map<String, Object>> used = jdbc.sql("""
                        SELECT DISTINCT template_key, template_version FROM import_batches
                         WHERE run_id = ? AND template_key IS NOT NULL
                        """).param(runId).query().listOfRows();
        for (Map<String, Object> t : used) {
            String key = (String) t.get("template_key");
            int version = ((Number) t.get("template_version")).intValue();
            if (templates.find(key, version).isPresent()) {
                templates.catalogue(key, version).stream()
                        .map(ImportTemplate.IssueCode::code)
                        .forEach(codes::add);
            }
        }
        return codes;
    }

    private Defect defect(UUID id) {
        return jdbc.sql("SELECT " + DEFECT_COLUMNS + " FROM import_defects WHERE id = ?")
                .param(id)
                .query(MetricsService::defect)
                .single();
    }

    private static Defect defect(ResultSet rs, int n) throws SQLException {
        Timestamp closed = rs.getTimestamp("closed_at");
        return new Defect(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("logged_by", UUID.class),
                rs.getString("description"),
                rs.getString("expected_code"),
                rs.getBoolean("new_code_needed"),
                rs.getString("status"),
                rs.getString("fix_ref"),
                rs.getObject("closed_by", UUID.class),
                closed == null ? null : closed.toInstant(),
                rs.getInt("version"));
    }
}
