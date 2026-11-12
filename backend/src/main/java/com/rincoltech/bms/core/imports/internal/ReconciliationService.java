package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportBooks;
import com.rincoltech.bms.core.imports.ImportForecast;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportReconciliation;
import com.rincoltech.bms.core.imports.ImportRefs;
import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplates;
import com.rincoltech.bms.core.ledger.LedgerAccounts;
import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ImportReconciliation} over {@code import_reconciliations} and {@code import_signoffs} (migration V144, issue #134,
 * spec sections 3.9 and 3.10).
 *
 * <p>The <b>source</b> side is the run's committed batches as staged: every row that is not blank, read 1,000 at a time,
 * measured by its template's own forecast ({@link ImportTemplate#forecast(Map)}): the rows written are netted per product
 * over the whole run (one forecast fed every written row of every batch, as the writer posts them), and each row left out
 * is measured alone (the forecast given that one row), so it is itemised by exactly its own part. Under a receivable
 * decision the source is measured as if every credit sale were opened ({@code open_from_list}), so the decision's effect is
 * itemised as {@code policy}. The <b>system</b> side is the books: the run's journals, found in {@code import_refs} and read
 * through the ledger's port line by line; the ledger of the whole business; and the stock ledger and the loan book through
 * the template's {@link ImportBooks}. The commit's stored report is never read, so the reconciliation does not depend on
 * what a writer said it did.
 */
// Named: core.payments has a ReconciliationService of its own.
@Service("importReconciliationService")
class ReconciliationService implements ImportReconciliation {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    static final String COUNTS = "counts";
    static final String STOCK_QUANTITY = "stock_quantity";
    static final String STOCK_VALUE = "stock_value";
    static final String RECEIVABLES = "receivables";
    static final String LOAN_PRINCIPAL = "loan_principal";
    static final String ARREARS = "arrears";
    static final String LEDGER = "ledger";
    static final String REVIEW = "review";

    /** Each statement's limit while the sign-off measures again; a test shortens it to reach the refusal. */
    static volatile String signOffTimeout = "60s";

    /** The sections in the order the signer reads them, with their titles. */
    private static final Map<String, String> TITLES = new LinkedHashMap<>();

    static {
        TITLES.put(COUNTS, "Rows and records");
        TITLES.put(STOCK_QUANTITY, "Stock quantity per branch and product");
        TITLES.put(STOCK_VALUE, "Stock value per branch");
        TITLES.put(RECEIVABLES, "Money owed by customers");
        TITLES.put(LOAN_PRINCIPAL, "Loan principal outstanding");
        TITLES.put(ARREARS, "Loans by days past due");
        TITLES.put(LEDGER, "Journals and trial balance");
        TITLES.put(REVIEW, "Decisions made in the review");
    }

    /** What an opening journal is registered as in import_refs. */
    private static final String JOURNAL = RefService.JOURNAL;

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    /** Decimals as BigDecimal, so a quantity of 1.1 is read as written. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PreviewService preview;
    private final ImportTemplates templates;
    private final ImportMappings mappings;
    private final LedgerAccounts ledger;
    private final Branches branches;
    private final CurrentTenant tenant;
    private final BusinessClock clock;
    private final AuditLog audit;
    private final ObjectMapper mapper;

    ReconciliationService(
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            PreviewService preview,
            ImportTemplates templates,
            ImportMappings mappings,
            LedgerAccounts ledger,
            Branches branches,
            CurrentTenant tenant,
            BusinessClock clock,
            AuditLog audit,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.preview = preview;
        this.templates = templates;
        this.mappings = mappings;
        this.ledger = ledger;
        this.branches = branches;
        this.tenant = tenant;
        this.clock = clock;
        this.audit = audit;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------------------------------------
    // The API.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Report reconcile(UUID runId, UUID by) {
        TenantContext.require();
        return tx.execute(s -> generate(runId, by));
    }

    /**
     * The first reconciliation, made as the commit finishes (spec section 3.9: generated after commit). Never fails the
     * commit, which is already done: a failure is logged with the run and the kind of failure only, and the reconciliation
     * is made again by {@link #reconcile}.
     */
    void afterCommit(UUID runId) {
        try {
            tx.executeWithoutResult(s -> generate(runId, null));
        } catch (RuntimeException e) {
            log.warn(
                    "import run {}: the reconciliation after the commit failed ({})",
                    runId,
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public Optional<Report> latest(UUID runId) {
        TenantContext.require();
        return tx.execute(s -> {
            run(runId, false);
            return newest(runId);
        });
    }

    // JUSTIFICATION-A3: the sign-off screen's list of imports, a new read of this new service (issue #134).
    @Override
    public List<RunSummary> forSignOff() {
        TenantContext.require();
        return tx.execute(s -> jdbc.sql("""
                        SELECT r.id, r.status, r.committed_at, c.version, c.signable, r.signed_off_at
                          FROM import_runs r
                          LEFT JOIN LATERAL (SELECT version, signable FROM import_reconciliations x
                                              WHERE x.run_id = r.id ORDER BY version DESC LIMIT 1) c ON true
                         WHERE r.status IN ('committed', 'signed_off')
                           -- Not the import-retail command's runs nor V145's backfill run (#135): neither staged rows
                           -- to reconcile against.
                           AND r.origin = 'framework'
                         ORDER BY r.committed_at DESC NULLS LAST, r.id
                         LIMIT 20
                        """)
                .query((rs, n) -> {
                    Timestamp committed = rs.getTimestamp(3);
                    Timestamp signed = rs.getTimestamp(6);
                    return new RunSummary(
                            rs.getObject(1, UUID.class),
                            rs.getString(2),
                            committed == null ? null : committed.toInstant(),
                            (Integer) rs.getObject(4),
                            (Boolean) rs.getObject(5),
                            signed == null ? null : signed.toInstant());
                })
                .list());
    }

    @Override
    public Report signOff(UUID runId, SignOff signOff) {
        TenantContext.require();
        String name = signOff.name() == null ? "" : signOff.name().strip();
        if (name.isEmpty()) {
            throw ApiException.rule("name_required", "Type your name to sign.");
        }
        if (name.length() > 120) {
            throw ApiException.rule("name_too_long", "A name is at most 120 characters.");
        }
        LocalDate today = clock.today(tenant.profile().timezone());
        if (signOff.date() == null || !signOff.date().equals(today)) {
            throw ApiException.rule("sign_off_date_not_today", "Type today's date, " + today + ", to sign.");
        }
        Report[] remade = new Report[1];
        Report signed = tx.execute(s -> {
            RunRow run = run(runId, true);
            if (!RunStates.COMMITTED.equals(run.status())) {
                throw notCommitted(run.status());
            }
            Report newest = newest(runId)
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.CONFLICT,
                            "reconciliation_missing",
                            "No reconciliation yet",
                            "This import has no reconciliation yet; make it first."));
            if (!newest.id().equals(signOff.reconciliationId())
                    || !newest.sha256().equals(signOff.sha256())) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "reconciliation_changed",
                        "Reconciliation changed",
                        "The reconciliation was made again since you opened it, as version " + newest.version()
                                + ". Read it and sign that.",
                        List.of(),
                        Map.of("reconciliation_id", newest.id().toString(), "version", newest.version()));
            }
            if (!newest.signable()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "unitemised_difference",
                        "A difference is not explained",
                        "A difference in the reconciliation is not explained by a left out row, an override or a"
                                + " decision, so it cannot be signed. Find and correct it, then make the reconciliation"
                                + " again.");
            }
            // The books as they are now, measured again in this transaction, which holds the run: a sign-off of figures
            // the books no longer hold is refused (review of #873). JUSTIFICATION-A3: must-fix 2 of that review.
            // Inside a request and holding the run, so each statement gets 60 seconds, not a batch's 10 minutes (the
            // Lead on the review of 391e1dc); a run too big for that is refused plainly, and nothing is signed.
            Set<String> wanted = new TreeSet<>();
            newest.sections().forEach(sec -> wanted.add(sec.key()));
            Set<String> ticked = new TreeSet<>(signOff.sections());
            if (!ticked.equals(wanted)) {
                throw ApiException.rule("sections_not_ticked", "Tick every section of the reconciliation to sign it.");
            }
            Measured now;
            try {
                now = measure(runId, run, signOffTimeout);
            } catch (RuntimeException e) {
                if (!UploadService.queryTimedOut(e)) {
                    throw e;
                }
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "sign_off_too_big",
                        "Too big to sign at once",
                        "This import is too big to sign here at once, and nothing was signed. Ask Rincol Tech to help"
                                + " you sign it.");
            }
            String again = sha256(new Report(
                            newest.id(),
                            runId,
                            newest.version(),
                            newest.generatedAt(),
                            now.asAt(),
                            "",
                            now.signable(),
                            now.sections(),
                            null)
                    .render());
            if (!again.equals(newest.sha256())) {
                // The books moved: what was measured is stored as the next version, so the signer reads the figures
                // the books hold now instead of the same version again (review of 1ea73e5, must-fix 1). Refused below,
                // once this transaction has stored it.
                remade[0] = store(runId, signOff.by(), now);
                return null;
            }
            jdbc.sql("""
                            INSERT INTO import_signoffs (id, tenant_id, run_id, reconciliation_id, report_sha256, sections,
                                                         signed_name, signed_on, signed_by)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(
                            UUID.randomUUID(),
                            runId,
                            newest.id(),
                            newest.sha256(),
                            ticked.toArray(String[]::new),
                            name,
                            Date.valueOf(signOff.date()),
                            signOff.by())
                    .update();
            jdbc.sql("""
                            UPDATE import_runs SET status = 'signed_off', signed_off_at = now(), version = version + 1,
                                   updated_at = now()
                             WHERE id = ?
                            """).param(runId).update();
            // Who signed and what, never the typed name, which stays in the sign-off row alone.
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", RunStates.SIGNED_OFF);
            after.put("reconciliation_id", newest.id().toString());
            after.put("reconciliation_version", newest.version());
            after.put("sha256", newest.sha256());
            after.put("sections", List.copyOf(ticked));
            audit.record(new AuditLog.Entry(
                    "core.import_run.signed_off",
                    "import_run",
                    runId,
                    null,
                    Map.of("status", RunStates.COMMITTED),
                    after));
            return newest(runId).orElseThrow();
        });
        if (remade[0] != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "reconciliation_changed",
                    "Reconciliation changed",
                    "What the import brought in changed in the books since you opened it, so the reconciliation was"
                            + " made again as version " + remade[0].version() + ". Read it and sign that.",
                    List.of(),
                    Map.of(
                            "reconciliation_id", remade[0].id().toString(),
                            "version", remade[0].version()));
        }
        return signed;
    }

    // ---------------------------------------------------------------------------------------------
    // The run and the stored documents.
    // ---------------------------------------------------------------------------------------------

    private record RunRow(
            String status, Instant cutoverAt, Instant committedAt, Map<String, Map<String, Object>> policy) {}

    private RunRow run(UUID runId, boolean forUpdate) {
        // A run of the import-retail command or V145's backfill run has no staged rows to reconcile: not found (#135).
        return jdbc.sql("SELECT status, cutover_at, committed_at, policy::text FROM import_runs"
                        + " WHERE id = ? AND origin = 'framework'" + (forUpdate ? " FOR UPDATE" : ""))
                .param(runId)
                .query((rs, n) -> {
                    Map<String, Map<String, Object>> policy = new TreeMap<>();
                    JSON.readValue(rs.getString(4), OBJECT).forEach((k, v) -> {
                        if (v instanceof Map<?, ?> m) {
                            Map<String, Object> decision = new LinkedHashMap<>();
                            m.forEach((a, b) -> decision.put(String.valueOf(a), b));
                            policy.put(k, decision);
                        }
                    });
                    Timestamp cut = rs.getTimestamp(2);
                    Timestamp done = rs.getTimestamp(3);
                    return new RunRow(
                            rs.getString(1),
                            cut == null ? null : cut.toInstant(),
                            done == null ? null : done.toInstant(),
                            policy);
                })
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    private static ApiException notCommitted(String status) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "run_not_committed",
                "Not committed",
                "The import is " + RunStates.words(status)
                        + "; only an import not signed off is reconciled or signed.");
    }

    private Optional<Report> newest(UUID runId) {
        Optional<Report> stored = jdbc.sql("""
                        SELECT report::text FROM import_reconciliations WHERE run_id = ? ORDER BY version DESC LIMIT 1
                        """)
                .param(runId)
                .query((rs, n) -> mapper.readValue(rs.getString(1), Report.class))
                .optional();
        if (stored.isEmpty()) {
            return stored;
        }
        Signed signed = jdbc.sql("""
                        SELECT created_at, signed_by, signed_name, signed_on, sections
                          FROM import_signoffs WHERE run_id = ?
                        """)
                .param(runId)
                .query((rs, n) -> new Signed(
                        rs.getTimestamp(1).toInstant(),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getDate(4).toLocalDate(),
                        List.of((String[]) rs.getArray(5).getArray())))
                .optional()
                .orElse(null);
        Report r = stored.get();
        return Optional.of(new Report(
                r.id(),
                r.runId(),
                r.version(),
                r.generatedAt(),
                r.asAt(),
                r.sha256(),
                r.signable(),
                r.sections(),
                signed));
    }

    // ---------------------------------------------------------------------------------------------
    // Making a reconciliation.
    // ---------------------------------------------------------------------------------------------

    /** One check: its section, its key in the section, its label in plain words and its unit. */
    private record CheckId(String section, String key, String label, String unit) {}

    /** One committed batch of the run, its template's entity set and where its rows come from. */
    private record BatchInfo(UUID id, int seq, String file, EntitySet set, PreviewService.BatchSource source) {}

    /** The figures of a reconciliation as it is made. */
    private static final class Sheet {
        final Map<CheckId, BigDecimal> source = new LinkedHashMap<>();
        final Map<CheckId, BigDecimal> system = new LinkedHashMap<>();
        final Map<CheckId, List<Item>> items = new LinkedHashMap<>();
        final Map<String, List<Fact>> facts = new LinkedHashMap<>();
        /** Per check, the part of the rows to write that the policy decision leaves out of the books. */
        final Map<CheckId, BigDecimal> policy = new LinkedHashMap<>();

        void add(Map<CheckId, BigDecimal> to, CheckId id, BigDecimal v) {
            if (v != null && v.signum() != 0) {
                to.merge(id, v, BigDecimal::add);
            } else {
                to.putIfAbsent(id, BigDecimal.ZERO);
            }
        }

        void item(CheckId id, Item item) {
            items.computeIfAbsent(id, k -> new ArrayList<>()).add(item);
            source.putIfAbsent(id, BigDecimal.ZERO);
        }

        void fact(String section, String label, Object value) {
            facts.computeIfAbsent(section, k -> new ArrayList<>()).add(new Fact(label, String.valueOf(value)));
        }
    }

    // JUSTIFICATION-A3: the review of #873 (must-fix 2): the measurement is split out so the sign-off measures again.
    private Report generate(UUID runId, UUID by) {
        RunRow run = run(runId, true);
        if (!RunStates.COMMITTED.equals(run.status())) {
            throw notCommitted(run.status());
        }
        return store(runId, by, measure(runId, run, "10min"));
    }

    /** Stores a measurement as the run's next version, audited; inside the caller's transaction, which holds the run. */
    private Report store(UUID runId, UUID by, Measured m) {
        LocalDate asAt = m.asAt();
        boolean signable = m.signable();
        List<Section> sections = m.sections();
        int version = jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM import_reconciliations WHERE run_id = ?")
                .param(runId)
                .query(Integer.class)
                .single();
        UUID id = UUID.randomUUID();
        Instant now = clock.now();
        String sha256 = sha256(new Report(id, runId, version, now, asAt, "", signable, sections, null).render());
        Report report = new Report(id, runId, version, now, asAt, sha256, signable, sections, null);
        jdbc.sql("""
                        INSERT INTO import_reconciliations (id, tenant_id, run_id, version, generated_by, as_at, report,
                                                            sha256, signable)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                        """)
                .params(id, runId, version, by, Date.valueOf(asAt), mapper.writeValueAsString(report), sha256, signable)
                .update();
        long notTied = sections.stream()
                .flatMap(sec -> sec.checks().stream())
                .filter(c -> !c.ties())
                .count();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("version", version);
        after.put("signable", signable);
        after.put("checks_not_tied", notTied);
        after.put("sha256", sha256);
        audit.record(AuditLog.Entry.created("core.import_run.reconciled", "import_run", runId, null, after));
        log.info(
                "import run {}: reconciliation {} made, signable {}, {} checks not tied",
                runId,
                version,
                signable,
                notTied);
        return report;
    }

    /** What a measurement of the run against the books found: the day it is as at, whether it can be signed, and why. */
    private record Measured(LocalDate asAt, boolean signable, List<Section> sections) {}

    /**
     * Measures the run's source against the books, inside the caller's transaction (which holds the run). It reads every
     * staged row of the run, so it runs under a statement timeout like a batch's (review of #873).
     */
    private Measured measure(UUID runId, RunRow run, String timeout) {
        jdbc.sql("SELECT set_config('statement_timeout', ?, true)")
                .param(timeout)
                .query()
                .singleRow();
        ZoneId zone = tenant.profile().timezone();
        Instant asAtMoment = run.cutoverAt() != null ? run.cutoverAt() : run.committedAt();
        LocalDate asAt = asAtMoment == null ? clock.today(zone) : LocalDate.ofInstant(asAtMoment, zone);
        PreviewService.RunPrivacy privacy = preview.privacy(runId);
        Map<UUID, String> branchCodes = new HashMap<>();
        branches.all().forEach(b -> branchCodes.put(b.id(), b.code()));

        Sheet sheet = new Sheet();
        List<BatchInfo> batches = new ArrayList<>();
        ImportTemplate template = batches(runId, batches);

        // The decisions as recorded, and as if every credit sale were opened (spec section 3.8).
        Map<String, String> actual = new TreeMap<>();
        run.policy().forEach((k, v) -> actual.put(k, String.valueOf(v.get("option"))));
        Map<String, String> open = new TreeMap<>(actual);
        boolean receivableDecision = template != null
                && template.policies().stream().anyMatch(p -> ImportTemplate.Policy.RECEIVABLE.equals(p.key()));
        if (receivableDecision) {
            open.put(ImportTemplate.Policy.RECEIVABLE, ImportTemplate.Policy.OPEN_FROM_LIST);
        }
        boolean sameDecision = open.equals(actual);

        // What the books hold for the run: every record it registered, its journals and their kinds.
        Map<String, List<UUID>> targets = new TreeMap<>();
        Map<UUID, String> journalKinds = new LinkedHashMap<>();
        jdbc.sql("SELECT target_type, target_id, source_key FROM import_refs WHERE run_id = ? ORDER BY created_at, id")
                .param(runId)
                .query((rs, n) -> {
                    String type = rs.getString(1);
                    UUID id = rs.getObject(2, UUID.class);
                    targets.computeIfAbsent(type, k -> new ArrayList<>()).add(id);
                    if (JOURNAL.equals(type)) {
                        journalKinds.put(id, kindOf(rs.getString(3)));
                    }
                    return null;
                })
                .list();
        ImportBooks.Scope scope = new ImportBooks.Scope(runId, targets);

        // The source: each row left out alone, with its own part, and the rows written netted together over the whole
        // run, as the forecast and the writer count them (Lead on the review of 4105c2a): an item counted in two
        // batches nets to one balance.
        Map<CheckId, BigDecimal> writtenParts = new LinkedHashMap<>();
        // Each row's part is a fresh forecast of that row alone; what the template reads for one is read once per run
        // (#880), and the whole run's forecast is made from the same read (issue #876, nice-to-have 4).
        Supplier<ImportForecast> openParts = template == null ? null : template.forecasts(open, runId);
        ImportForecast whole = openParts == null ? null : openParts.get();
        Supplier<ImportForecast> decidedParts =
                template == null || sameDecision ? openParts : template.forecasts(actual, runId);
        for (BatchInfo b : batches) {
            if (whole != null) {
                whole.batch(b.seq());
            }
            rows(runId, b, template, openParts, decidedParts, sameDecision, privacy, whole, writtenParts, sheet);
        }
        together(whole, writtenParts, sheet);
        resolutions(runId, batches, privacy, sheet);
        targets.forEach((type, ids) -> {
            if (!JOURNAL.equals(type)) {
                sheet.fact(COUNTS, "Records created as " + type, ids.size());
            }
        });

        // The policy decision's part, one item per check (spec section 3.8: listed, never silent).
        sheet.policy.forEach((id, amount) -> {
            if (amount.signum() != 0) {
                sheet.item(id, new Item(id.key(), "-", 0, POLICY, decisionWords(run.policy()), plain(amount)));
            }
        });

        // The books.
        system(template, scope, asAt, journalKinds, branchCodes, privacy, sheet);
        List<Check> ledgerChecks = ledgerChecks(journalKinds);

        // The document.
        List<Section> sections = new ArrayList<>();
        boolean signable = true;
        for (Map.Entry<String, String> title : TITLES.entrySet()) {
            String key = title.getKey();
            List<Check> checks = new ArrayList<>();
            List<Item> items = new ArrayList<>();
            Set<CheckId> ids = new LinkedHashSet<>();
            sheet.source.keySet().stream()
                    .filter(id -> id.section().equals(key))
                    .forEach(ids::add);
            sheet.system.keySet().stream()
                    .filter(id -> id.section().equals(key))
                    .forEach(ids::add);
            sheet.items.keySet().stream().filter(id -> id.section().equals(key)).forEach(ids::add);
            for (CheckId id : sorted(ids)) {
                BigDecimal src = sheet.source.getOrDefault(id, BigDecimal.ZERO);
                BigDecimal sys = sheet.system.getOrDefault(id, BigDecimal.ZERO);
                List<Item> its = sheet.items.getOrDefault(id, List.of());
                if (src.signum() == 0 && sys.signum() == 0 && its.isEmpty()) {
                    continue;
                }
                BigDecimal itemised =
                        its.stream().map(i -> new BigDecimal(i.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal difference = src.subtract(sys);
                BigDecimal unitemised = difference.subtract(itemised);
                String checkKey = screen(id.key(), privacy);
                checks.add(new Check(
                        checkKey,
                        screen(id.label(), privacy),
                        id.unit(),
                        plain(src),
                        plain(sys),
                        plain(difference),
                        plain(itemised),
                        plain(unitemised),
                        unitemised.signum() == 0));
                for (Item i : its) {
                    items.add(new Item(
                            checkKey, i.file(), i.line(), i.reason(), screen(i.detail(), privacy), i.amount()));
                }
            }
            if (LEDGER.equals(key)) {
                checks.addAll(ledgerChecks);
            }
            List<Fact> facts = sheet.facts.getOrDefault(key, List.of());
            if (checks.isEmpty() && facts.isEmpty() && !COUNTS.equals(key) && !LEDGER.equals(key)) {
                continue;
            }
            for (Check c : checks) {
                signable &= c.ties();
            }
            sections.add(new Section(key, title.getValue(), facts, checks, items));
        }
        return new Measured(asAt, signable, sections);
    }

    /** The run's committed batches in the commit's order, and the one template they were read with (or null). */
    private ImportTemplate batches(UUID runId, List<BatchInfo> out) {
        record Row(
                UUID id, int seq, String file, UUID mapping, String source, String key, Integer version, String sha) {}
        List<Row> rows = jdbc.sql("""
                        SELECT id, seq, source_filename, mapping_id, mapping_source, template_key, template_version,
                               source_sha256
                          FROM import_batches
                         WHERE run_id = ? AND status = 'committed'
                         ORDER BY dependency_rank NULLS LAST, seq
                        """)
                .param(runId)
                .query((rs, n) -> new Row(
                        rs.getObject(1, UUID.class),
                        rs.getInt(2),
                        rs.getString(3),
                        rs.getObject(4, UUID.class),
                        rs.getString(5),
                        rs.getString(6),
                        (Integer) rs.getObject(7),
                        rs.getString(8)))
                .list();
        ImportTemplate template = null;
        Map<UUID, Map<String, Object>> documents = new HashMap<>();
        for (Row r : rows) {
            if (r.key() == null || r.version() == null || r.mapping() == null) {
                continue;
            }
            ImportTemplate t = templates.find(r.key(), r.version()).orElse(null);
            if (t == null) {
                continue;
            }
            template = template == null ? t : template;
            Map<String, Object> document =
                    documents.computeIfAbsent(r.mapping(), m -> mappings.get(m).document());
            Map<String, Object> source = MappingValidator.sources(document).get(r.source());
            String setKey = source == null || !(source.get("entity_set") instanceof String s) ? null : s;
            EntitySet set = t.entitySets().stream()
                    .filter(e -> e.key().equals(setKey))
                    .findFirst()
                    .orElse(null);
            if (set == null) {
                continue;
            }
            String file = r.file() == null ? "batch " + r.seq() : r.file();
            out.add(new BatchInfo(
                    r.id(), r.seq(), file, set, PreviewService.sourceOf(document, r.source(), set, r.sha())));
        }
        return template;
    }

    /**
     * The source side of one batch, 1,000 rows at a time: per row its outcome (blank, left out, in the books by this run or
     * an earlier one, or missing from the books) and its part in every check by the template's own rules.
     */
    private void rows(
            UUID runId,
            BatchInfo b,
            ImportTemplate template,
            Supplier<ImportForecast> openParts,
            Supplier<ImportForecast> decidedParts,
            boolean sameDecision,
            PreviewService.RunPrivacy privacy,
            ImportForecast whole,
            Map<CheckId, BigDecimal> writtenParts,
            Sheet sheet) {
        String file = screen(b.file(), privacy);
        CheckId batchRows = new CheckId(COUNTS, "batch " + b.seq(), "Rows of " + file, "rows");
        CheckId setRows =
                new CheckId(COUNTS, "set " + b.set().key(), "Rows of " + b.set().key(), "rows");
        int read = 0;
        int blank = 0;
        int excluded = 0;
        int written = 0;
        int earlier = 0;
        int missing = 0;
        int unmeasured = 0;
        // The kinds of failure of the written rows the rules could not measure, one entry per row.
        List<String> unmeasuredKinds = new ArrayList<>();
        int after = 0;
        while (true) {
            record Staged(
                    int line,
                    String ref,
                    String normalised,
                    boolean excluded,
                    String excludedCode,
                    boolean blank,
                    String overridden,
                    String blocked) {}
            // A row the review left out, and a row an open blocking issue held back (the commit writes neither, as the
            // preview counts them), with their codes; and the warnings overridden on a row. A row a code of
            // ImportTemplates.LEFT_OUT_CODES leaves out (blank, or a sub-header since #136) counts as blank.
            List<Staged> chunk = jdbc.sql("""
                            SELECT r.source_row_no, r.source_ref, r.normalised::text, r.excluded_by IS NOT NULL,
                                   (SELECT res.code FROM import_issue_resolutions res WHERE res.id = r.excluded_by),
                                   EXISTS (SELECT 1 FROM import_issues i WHERE i.row_id = r.id
                                                                       AND i.code IN %s),
                                   (SELECT string_agg(DISTINCT i.code, ' ' ORDER BY i.code)
                                      FROM import_issues i JOIN import_issue_resolutions res ON res.id = i.resolution_id
                                     WHERE i.row_id = r.id AND res.resolution = 'override'),
                                   (SELECT string_agg(DISTINCT i.code, ' ' ORDER BY i.code)
                                      FROM import_issues i
                                     WHERE i.row_id = r.id AND i.status = 'open' AND i.severity = 'blocking')
                              FROM import_rows r
                             WHERE r.batch_id = ? AND r.source_row_no > ?
                             ORDER BY r.source_row_no LIMIT 1000
                            """.formatted(ImportTemplates.leftOutSql()))
                    .params(b.id(), after)
                    .query((rs, n) -> new Staged(
                            rs.getInt(1),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getBoolean(4),
                            rs.getString(5),
                            rs.getBoolean(6),
                            rs.getString(7),
                            rs.getString(8)))
                    .list();
            // The references of the chunk's rows, and which run registered each.
            Map<Integer, Map<String, Object>> stored = new HashMap<>();
            Map<Integer, String> refs = new HashMap<>();
            for (Staged s : chunk) {
                if (s.normalised() == null) {
                    continue;
                }
                Map<String, Object> values = JSON.readValue(s.normalised(), OBJECT);
                stored.put(s.line(), values);
                try {
                    refs.put(s.line(), RefService.sourceRef(b.source(), s.ref(), s.line(), values));
                } catch (RuntimeException e) {
                    // A row left out before the validation gave it a reference has no record to look for.
                }
            }
            Map<String, Boolean> byThisRun = new HashMap<>();
            if (!refs.isEmpty()) {
                // By the indexed key (import_refs_by_key), never by source_ref, which no index covers.
                Set<String> wanted = new HashSet<>(refs.values());
                // Each key by the index, as RefService reads a page of them (issue #880).
                jdbc.sql("SELECT r.source_ref, r.run_id = ?" + RefService.BY_KEYS)
                        .params(
                                runId,
                                wanted.stream()
                                        .map(ImportRefs::keyOf)
                                        .distinct()
                                        .toArray(String[]::new))
                        .query((rs, n) -> wanted.contains(rs.getString(1))
                                ? byThisRun.merge(rs.getString(1), rs.getBoolean(2), Boolean::logicalOr)
                                : null)
                        .list();
            }
            for (Staged s : chunk) {
                after = s.line();
                read++;
                if (s.blank()) {
                    blank++;
                    continue;
                }
                sheet.add(sheet.source, batchRows, BigDecimal.ONE);
                sheet.add(sheet.source, setRows, BigDecimal.ONE);
                String ref = refs.get(s.line());
                Boolean held = ref == null ? null : byThisRun.get(ref);
                // The row's part in every check by the template's own rules. A row the rules cannot measure (a value
                // they need is missing) is counted and named, never dropped without a trace (review of #873).
                Map<CheckId, BigDecimal> part = null;
                String unmeasurable = null;
                if (template != null && stored.containsKey(s.line())) {
                    try {
                        part = part(openParts, b, stored.get(s.line()));
                    } catch (RuntimeException e) {
                        unmeasured++;
                        unmeasurable = e.getClass().getSimpleName();
                    }
                }
                if (part != null) {
                    part.forEach((id, v) -> sheet.add(sheet.source, id, v));
                }
                if (s.excluded() || s.blocked() != null) {
                    excluded++;
                    String why = (s.excluded()
                                    ? "left out in the review"
                                            + (s.excludedCode() == null ? "" : " (" + s.excludedCode() + ")")
                                    : "held back by an open blocking issue (" + s.blocked() + ")")
                            + (unmeasurable == null
                                    ? ""
                                    : "; the template's rules could not measure its values (" + unmeasurable + ")");
                    // Left out is decided first and counted once (Lead on #873): the row adds nothing to the system
                    // side, whatever an earlier run holds, and is one explained row. Its values are not in this run's
                    // postings or stock either, unless this run holds it after all.
                    sheet.item(batchRows, new Item(batchRows.key(), file, s.line(), EXCLUDED, why, "1"));
                    sheet.item(setRows, new Item(setRows.key(), file, s.line(), EXCLUDED, why, "1"));
                    if (!Boolean.TRUE.equals(held)) {
                        itemise(sheet, part, file, s.line(), held == null ? why : why + "; imported by an earlier run");
                    }
                    continue;
                }
                if (held != null) {
                    sheet.add(sheet.system, batchRows, BigDecimal.ONE);
                    sheet.add(sheet.system, setRows, BigDecimal.ONE);
                }
                if (held == null) {
                    // Meant to be written and not in the books: never itemised, so it holds the sign-off.
                    missing++;
                    continue;
                }
                if (!held) {
                    earlier++;
                    itemise(sheet, part, file, s.line(), "already imported by an earlier run");
                    continue;
                }
                written++;
                if (unmeasurable != null) {
                    unmeasuredKinds.add(unmeasurable);
                }
                if (part != null && whole != null) {
                    whole.row(b.set().key(), PreviewService.typed(b.set(), stored.get(s.line())));
                    part.forEach((id, v) -> writtenParts.merge(id, v, BigDecimal::add));
                }
                if (s.overridden() != null) {
                    sheet.item(
                            batchRows,
                            new Item(
                                    batchRows.key(),
                                    file,
                                    s.line(),
                                    OVERRIDDEN,
                                    "written as reviewed; warning overridden (" + s.overridden() + ")",
                                    "0"));
                }
                if (part != null && !sameDecision) {
                    // The same rules under the recorded decision. If they cannot measure the row, its decision is not
                    // itemised, so its difference stays unexplained and holds the sign-off.
                    Map<CheckId, BigDecimal> asDecided = null;
                    try {
                        asDecided = part(decidedParts, b, stored.get(s.line()));
                    } catch (RuntimeException e) {
                        // Named like any other row the rules cannot measure (review of 391e1dc, point 3).
                        unmeasured++;
                        unmeasuredKinds.add("decision:" + e.getClass().getSimpleName());
                    }
                    if (asDecided != null) {
                        Set<CheckId> keys = new HashSet<>(part.keySet());
                        keys.addAll(asDecided.keySet());
                        for (CheckId id : keys) {
                            BigDecimal o = part.getOrDefault(id, BigDecimal.ZERO);
                            BigDecimal d = asDecided.getOrDefault(id, BigDecimal.ZERO);
                            sheet.policy.merge(id, o.subtract(d), BigDecimal::add);
                        }
                    }
                }
            }
            if (chunk.size() < 1000) {
                break;
            }
        }
        sheet.fact(COUNTS, file + ": rows read", read);
        sheet.fact(COUNTS, file + ": blank rows", blank);
        sheet.fact(COUNTS, file + ": rows left out", excluded);
        sheet.fact(COUNTS, file + ": rows written by this import", written);
        sheet.fact(COUNTS, file + ": rows already imported before", earlier);
        sheet.fact(COUNTS, file + ": rows not found in the books", missing);
        sheet.fact(COUNTS, file + ": rows the template's rules could not measure", unmeasured);
        if (!unmeasuredKinds.isEmpty()) {
            // A written row whose part the rules could not measure is a difference nothing explains (Lead on #873): its
            // values are in the books and not in the source, so it holds the sign-off, named with the kind of failure.
            CheckId failed = new CheckId(
                    COUNTS,
                    "unmeasured " + b.seq(),
                    "Rows of " + file + " written that the rules could not measure ("
                            + String.join(" ", new TreeSet<>(unmeasuredKinds)) + ")",
                    "rows");
            sheet.add(sheet.source, failed, BigDecimal.valueOf(unmeasuredKinds.size()));
        }
        if (unmeasured > 0) {
            log.warn("import run {}: {} rows of batch {} could not be measured", runId, unmeasured, b.seq());
        }
    }

    // JUSTIFICATION-A3: FR-IMP-08, the Lead's ruling 3 on the review of 4105c2a: every resolution in the signed report.
    /**
     * Every resolution of the run's review, in the order made (FR-IMP-08): the file and line, the code and field, what was
     * decided, who (the user's id) and when, before and after, and the note. A before or after is shown only for a field
     * the committed batch's set knows and does not mark personal; any other is "a personal value, not shown". Any text
     * that names a personal value of the run is hidden too, as the resolution's audit row keeps them out.
     */
    private void resolutions(UUID runId, List<BatchInfo> batches, PreviewService.RunPrivacy privacy, Sheet sheet) {
        Map<UUID, BatchInfo> byId = new HashMap<>();
        batches.forEach(b -> byId.put(b.id(), b));
        jdbc.sql("""
                        SELECT batch_id, source_row_no, code, field, resolution, before_value::text, after_value::text,
                               note, resolved_by, created_at
                          FROM import_issue_resolutions
                         WHERE run_id = ?
                         ORDER BY seq
                        """)
                .param(runId)
                .query((rs, n) -> {
                    BatchInfo b = byId.get(rs.getObject(1, UUID.class));
                    String file = b == null ? "-" : screen(b.file(), privacy);
                    String field = rs.getString(4);
                    // Shown only for a field the batch's set knows and does not mark personal: a batch that is not
                    // among the run's committed ones, a row-level resolution or a field the set does not know fails
                    // closed (Lead on the review of 5b32682), since screening finds only values the run still holds.
                    boolean personal = b == null
                            || field == null
                            || b.set().fields().stream().noneMatch(f -> f.name().equals(field) && !f.personal());
                    String note = rs.getString(8);
                    String value = rs.getString(5)
                            + " by " + rs.getObject(9, UUID.class)
                            + " at " + rs.getTimestamp(10).toInstant()
                            + "; before " + shown(rs.getString(6), personal, privacy)
                            + "; after " + shown(rs.getString(7), personal, privacy)
                            + (note == null ? "" : "; note: " + screen(note, privacy));
                    sheet.fact(
                            REVIEW,
                            file + " line " + rs.getInt(2) + ", " + rs.getString(3)
                                    + (field == null ? "" : " on " + field),
                            value);
                    return null;
                })
                .list();
    }

    /** A resolution's before or after as the report shows it: none, hidden for a personal field, or its text screened. */
    private static String shown(String json, boolean personal, PreviewService.RunPrivacy privacy) {
        if (json == null) {
            return "none";
        }
        return personal ? "a personal value, not shown" : screen(json, privacy);
    }

    // JUSTIFICATION-A3: the block moved out of rows() so it runs once for the run (Lead on the review of 4105c2a).
    /**
     * The run's written rows counted together, as the forecast and the writer count them (Lead on the reviews of 1ea73e5
     * and 4105c2a): the source holds their parts measured as one over the whole run, in place of the sum of their parts
     * row by row, so each item's inventory journal follows its net balance over every batch. The rows left out keep their
     * own parts, which their items explain.
     */
    private static void together(ImportForecast whole, Map<CheckId, BigDecimal> writtenParts, Sheet sheet) {
        if (whole == null || writtenParts.isEmpty()) {
            return;
        }
        Map<CheckId, BigDecimal> together = parts(whole);
        Set<CheckId> ids = new LinkedHashSet<>(writtenParts.keySet());
        ids.addAll(together.keySet());
        for (CheckId id : ids) {
            BigDecimal d =
                    together.getOrDefault(id, BigDecimal.ZERO).subtract(writtenParts.getOrDefault(id, BigDecimal.ZERO));
            if (d.signum() != 0) {
                sheet.add(sheet.source, id, d);
            }
        }
    }

    /** Lists a row's part in each check as one item, left out of this run's books, with why. */
    private static void itemise(Sheet sheet, Map<CheckId, BigDecimal> part, String file, int line, String why) {
        if (part == null) {
            return;
        }
        part.forEach((id, v) -> {
            if (v.signum() != 0) {
                sheet.item(id, new Item(id.key(), file, line, EXCLUDED, why, plain(v)));
            }
        });
    }

    /**
     * One row's part in every check, by the template's own rules: a fresh forecast given only that row. Throws when the
     * rules cannot measure it (a row left out for a value they need); the caller counts and names that row.
     */
    private static Map<CheckId, BigDecimal> part(
            Supplier<ImportForecast> forecasts, BatchInfo b, Map<String, Object> stored) {
        ImportForecast f = forecasts.get();
        f.batch(b.seq());
        f.row(b.set().key(), PreviewService.typed(b.set(), stored));
        return parts(f);
    }

    /** What a forecast fed one row or many gives, per check. */
    private static Map<CheckId, BigDecimal> parts(ImportForecast f) {
        ImportForecast.Balances balances = f.balances();
        Map<CheckId, BigDecimal> out = new LinkedHashMap<>();
        for (ImportForecast.ProductStock p : f.productStock()) {
            out.merge(stockId(p.branch(), p.product()), p.qty(), BigDecimal::add);
        }
        for (ImportForecast.Stock s : balances.stock()) {
            out.merge(valueId(STOCK_VALUE, s.branch()), BigDecimal.valueOf(s.valueMinor()), BigDecimal::add);
        }
        for (ImportForecast.Amount a : balances.receivables()) {
            out.merge(valueId(RECEIVABLES, a.branch()), BigDecimal.valueOf(a.amountMinor()), BigDecimal::add);
        }
        for (ImportForecast.Amount a : balances.loanPrincipal()) {
            out.merge(valueId(LOAN_PRINCIPAL, a.branch()), BigDecimal.valueOf(a.amountMinor()), BigDecimal::add);
        }
        for (ImportForecast.ArrearsBucket a : balances.arrears()) {
            out.merge(arrearsId(a.branch(), a.bucket(), true), BigDecimal.valueOf(a.loans()), BigDecimal::add);
            out.merge(
                    arrearsId(a.branch(), a.bucket(), false), BigDecimal.valueOf(a.principalMinor()), BigDecimal::add);
        }
        for (ImportForecast.Journal j : balances.journals()) {
            out.merge(journalId(j.branch(), j.kind()), BigDecimal.valueOf(j.debitMinor()), BigDecimal::add);
        }
        return out;
    }

    /** The system side: the stock ledger and the loan book through the template, the run's journals through the ledger. */
    private void system(
            ImportTemplate template,
            ImportBooks.Scope scope,
            LocalDate asAt,
            Map<UUID, String> journalKinds,
            Map<UUID, String> branchCodes,
            PreviewService.RunPrivacy privacy,
            Sheet sheet) {
        ImportBooks books = template == null ? ImportBooks.NONE : template.books();
        for (ImportForecast.ProductStock p : books.stock(scope)) {
            sheet.add(sheet.system, stockId(p.branch(), p.product()), p.qty());
            if (p.qty().signum() < 0) {
                // A negative balance is listed separately (spec section 3.9), with its value at current cost.
                CheckId value = valueId(STOCK_VALUE, p.branch());
                sheet.item(
                        value,
                        new Item(
                                value.key(),
                                "-",
                                0,
                                POLICY,
                                screen("negative balance of " + p.product() + " listed separately", privacy),
                                plain(p.valueMinor())));
            }
        }
        for (ImportForecast.ArrearsBucket a : books.arrears(scope, asAt)) {
            sheet.add(sheet.system, arrearsId(a.branch(), a.bucket(), true), BigDecimal.valueOf(a.loans()));
            sheet.add(sheet.system, arrearsId(a.branch(), a.bucket(), false), BigDecimal.valueOf(a.principalMinor()));
        }
        Map<UUID, long[]> perEntry = new LinkedHashMap<>();
        Map<UUID, UUID> entryBranch = new HashMap<>();
        Map<UUID, String> entryNo = new HashMap<>();
        for (LedgerAccounts.EntryLine l : ledger.lines(journalKinds.keySet())) {
            String branch = branchCodes.getOrDefault(l.branchId(), "-");
            long net = Math.subtractExact(l.debitMinor(), l.creditMinor());
            String account = l.accountKey() == null ? "" : l.accountKey();
            switch (account) {
                case "inventory" -> sheet.add(sheet.system, valueId(STOCK_VALUE, branch), BigDecimal.valueOf(net));
                case "trade_debtors" -> sheet.add(sheet.system, valueId(RECEIVABLES, branch), BigDecimal.valueOf(net));
                case "loans_receivable" ->
                    sheet.add(sheet.system, valueId(LOAN_PRINCIPAL, branch), BigDecimal.valueOf(net));
                default -> {}
            }
            long[] dc = perEntry.computeIfAbsent(l.entryId(), k -> new long[2]);
            dc[0] = Math.addExact(dc[0], l.debitMinor());
            dc[1] = Math.addExact(dc[1], l.creditMinor());
            entryBranch.put(l.entryId(), l.branchId());
            entryNo.put(l.entryId(), l.entryNo());
        }
        perEntry.forEach((entry, dc) -> {
            String branch = branchCodes.getOrDefault(entryBranch.get(entry), "-");
            String kind = journalKinds.get(entry) == null ? "opening" : journalKinds.get(entry);
            sheet.add(sheet.system, journalId(branch, kind), BigDecimal.valueOf(dc[0]));
            // Each opening journal by its entry number, in the ledger's order (FR-IMP-08, Lead on the review of
            // 4105c2a).
            sheet.fact(
                    LEDGER,
                    "Opening journal " + entryNo.get(entry),
                    branch + ", " + kind.replace('_', ' ') + ": debits " + dc[0] + ", credits " + dc[1]);
        });
    }

    /**
     * The ledger's own checks, which nothing itemises: every journal the run posted is balanced and is an opening journal,
     * and the business's trial balance balances.
     */
    private List<Check> ledgerChecks(Map<UUID, String> journalKinds) {
        long debits = 0;
        long credits = 0;
        Map<UUID, long[]> perEntry = new LinkedHashMap<>();
        Map<UUID, Boolean> opening = new HashMap<>();
        Map<UUID, Boolean> equity = new HashMap<>();
        for (LedgerAccounts.EntryLine l : ledger.lines(journalKinds.keySet())) {
            debits = Math.addExact(debits, l.debitMinor());
            credits = Math.addExact(credits, l.creditMinor());
            long[] dc = perEntry.computeIfAbsent(l.entryId(), k -> new long[2]);
            dc[0] = Math.addExact(dc[0], l.debitMinor());
            dc[1] = Math.addExact(dc[1], l.creditMinor());
            // The framework's opening journal whatever ledger type its template named (retail_v1's is
            // retail.import_opening, #135), or a cash book opening of the import-retail command.
            boolean isOpening = RefService.MODULE.equals(l.sourceModule())
                    || ("retail".equals(l.sourceModule()) && RefService.RETAIL_OPENINGS.contains(l.sourceType()));
            opening.merge(l.entryId(), isOpening, Boolean::logicalAnd);
            // And against opening balance equity, as RefService checks it (re-review of 0bb283a, nice-to-have 3).
            equity.merge(l.entryId(), "opening_balance_equity".equals(l.accountKey()), Boolean::logicalOr);
        }
        equity.forEach((entry, against) -> opening.merge(entry, against, Boolean::logicalAnd));
        long unbalanced =
                perEntry.values().stream().filter(dc -> dc[0] != dc[1]).count();
        long notOpening = opening.values().stream().filter(o -> !o).count();
        LedgerAccounts.TrialBalanceCheck tb = ledger.trialBalanceCheck();
        List<Check> out = new ArrayList<>();
        out.add(compare(
                "journals balanced",
                "Journals of this import: debits (source) and credits (system)",
                "minor",
                debits,
                credits));
        out.add(compare(
                "journals out of balance", "Journals of this import that do not balance", "entries", 0, unbalanced));
        out.add(compare(
                "journals not opening",
                "Journals of this import that are not opening balances",
                "entries",
                0,
                notOpening));
        out.add(compare(
                // Debits less credits, not the totals, which move with every sale: the sign-off measures again (review
                // of #873) and must only stop for a change that matters.
                "trial balance",
                "Trial balance of the business: debits less credits",
                "minor",
                0,
                Math.subtractExact(tb.debitMinor(), tb.creditMinor())));
        out.add(compare(
                "entries out of balance",
                "Entries of the business that do not balance",
                "entries",
                0,
                tb.unbalancedEntries()));
        return out;
    }

    private static Check compare(String key, String label, String unit, long source, long system) {
        BigDecimal difference = BigDecimal.valueOf(source).subtract(BigDecimal.valueOf(system));
        return new Check(
                key,
                label,
                unit,
                Long.toString(source),
                Long.toString(system),
                plain(difference),
                "0",
                plain(difference),
                difference.signum() == 0);
    }

    // ---------------------------------------------------------------------------------------------
    // Keys, words and numbers.
    // ---------------------------------------------------------------------------------------------

    private static CheckId stockId(String branch, String product) {
        return new CheckId(STOCK_QUANTITY, branch + " " + product, product + " at " + branch, "qty");
    }

    private static CheckId valueId(String section, String branch) {
        String what = switch (section) {
            case STOCK_VALUE -> "Stock value";
            case RECEIVABLES -> "Owed by customers";
            default -> "Loan principal";
        };
        return new CheckId(section, branch, what + " at " + branch, "minor");
    }

    private static CheckId arrearsId(String branch, String bucket, boolean loans) {
        String days = bucket.matches("\\d+-\\d+") ? bucket.replace("-", " to ") + " days" : bucket.replace('-', ' ');
        return loans
                ? new CheckId(ARREARS, branch + " " + bucket + " loans", "Loans " + days + " at " + branch, "loans")
                : new CheckId(
                        ARREARS, branch + " " + bucket + " principal", "Principal " + days + " at " + branch, "minor");
    }

    private static CheckId journalId(String branch, String kind) {
        String k = kind == null ? "opening" : kind;
        return new CheckId(
                LEDGER,
                "journal " + branch + " " + k,
                "Opening journal " + k.replace('_', ' ') + " at " + branch,
                "minor");
    }

    /** Checks in a fixed order: by key, so two reconciliations of the same books read alike. */
    private static List<CheckId> sorted(Set<CheckId> ids) {
        List<CheckId> out = new ArrayList<>(ids);
        out.sort((a, b) -> {
            // The rows of a batch before the rows of its entity set, each by its number or name.
            int ka = a.key().startsWith("batch ") ? 0 : 1;
            int kb = b.key().startsWith("batch ") ? 0 : 1;
            if (ka != kb) {
                return Integer.compare(ka, kb);
            }
            if (ka == 0) {
                return Integer.compare(
                        Integer.parseInt(a.key().substring(6)),
                        Integer.parseInt(b.key().substring(6)));
            }
            return a.key().compareTo(b.key());
        });
        return out;
    }

    /** The kind of an opening journal from its key in import_refs: the tab part of {@code opening|<kind>|...}. */
    static String kindOf(String sourceKey) {
        String[] parts = sourceKey == null ? new String[0] : sourceKey.split("\\|", -1);
        return parts.length >= 3 ? parts[1] : "opening";
    }

    /** The recorded decisions in words: the option and the day the customer confirmed it, never the reference. */
    private static String decisionWords(Map<String, Map<String, Object>> policy) {
        List<String> words = new ArrayList<>();
        policy.forEach(
                (key, d) -> words.add(key + " decision " + d.get("option") + ", confirmed on " + d.get("confirmed_on")
                        + (d.get("follow_up_on") == null ? "" : ", follow up on " + d.get("follow_up_on"))));
        return words.isEmpty() ? "policy decision" : String.join("; ", words);
    }

    /** A text of the report, or {@link CommitReport#HIDDEN} when it names a personal value of the run's rows. */
    private static String screen(String text, PreviewService.RunPrivacy privacy) {
        return text != null && privacy.holds(text) ? CommitReport.HIDDEN : text;
    }

    /** An exact decimal as text: no exponent, no trailing zeros, never rounded. */
    static String plain(BigDecimal v) {
        return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
