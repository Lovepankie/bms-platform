package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.QuarantineStorage;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportMappings.Mapping;
import com.rincoltech.bms.core.imports.ImportPurges;
import com.rincoltech.bms.core.imports.ImportPurges.Certificate;
import com.rincoltech.bms.core.imports.ImportPurges.Purge;
import com.rincoltech.bms.core.imports.ImportReview;
import com.rincoltech.bms.core.imports.ImportReview.Resolution;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ImportUploads;
import com.rincoltech.bms.core.imports.ImportUploads.Batch;
import com.rincoltech.bms.core.imports.ImportUploads.Upload;
import com.rincoltech.bms.core.imports.ImportValidation;
import com.rincoltech.bms.core.imports.ImportValidation.Issue;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * The purge of issue #139 (spec section 6.4, ADR-027 decision 8, migration V147) on real PostgreSQL as bms_app, through the
 * golden files of {@code fixtures/imports/golden/} uploaded into the quarantine, validated and reviewed (a phone typed in
 * place of a bad one, kept in the resolution), with the run's batches marked committed and the run signed off by the owner
 * role, so the guards of a committed batch are in force. Proven here: after the purge no personal value of the fixture is in
 * any text or JSON column of any table that refers to the run (found from the foreign keys, so a table added later is
 * scanned too) or in object storage; the certificate lists the files with their sizes and SHA-256, the staged row counts,
 * the dump as the operator confirmed it, the operator and the witness; the purge is audited once and a second purge
 * changes nothing; the refusals; another run of the business and another business are untouched; and the guards let
 * nothing through but the purge of the run, in its own transaction. The real commit, reconciliation and sign-off before a
 * purge are in {@code ImportReconciliationIT}; the schedule is {@code ImportRetentionIT}. Fabricated data only.
 */
class ImportPurgeIT extends IntegrationTest {

    static final String BUYERS = "fab-buyers.csv";

    /** A fabricated phone an operator types in place of a bad one; before the purge it is in the resolution only. */
    static final String TYPED_PHONE = "0700000177";

    static final String DUMP = "pre-import-fabricated.dump";

    static final String DUMP_SHA = "e".repeat(64);

    /** The fixture's personal values and the typed phone, in the forms a column could hold them. */
    static final List<String> PERSONAL = Stream.concat(GoldenRun.PERSONAL.stream(), Stream.of(TYPED_PHONE, "700000177"))
            .toList();

    @Autowired
    ImportPurges purges;

    @Autowired
    ImportRuns runs;

    @Autowired
    ImportMappings mappings;

    @Autowired
    ImportUploads uploads;

    @Autowired
    ImportValidation validation;

    @Autowired
    ImportReview review;

    @Autowired
    QuarantineStorage quarantine;

    TestDatabase.Fixture t;
    TestDatabase.Fixture other;
    final UUID operator = UUID.randomUUID();
    String operatorEmail;
    String witnessEmail;
    UUID operatorId;
    UUID witnessId;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("purge", false);
        other = TestDatabase.tenant("purge-b", false);
        operatorEmail = Api.email("purge-operator");
        witnessEmail = Api.email("purge-witness");
        operatorId = Api.platformUser(operatorEmail);
        witnessId = Api.platformUser(witnessEmail);
        // Two names, so the certificate shows which is which (review of #886, S11).
        TestDatabase.owner()
                .sql("UPDATE platform_users SET full_name = CASE WHEN id = ? THEN 'Test Operator 01'"
                        + " ELSE 'Test Operator 02' END WHERE id IN (?, ?)")
                .params(operatorId, operatorId, witnessId)
                .update();
    }

    static <T> T as(TestDatabase.Fixture f, Supplier<T> work) {
        return TenantContext.callAs(f.tenantId(), work);
    }

    static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    // ---- The run ----

    /**
     * A run of the business with the golden files uploaded into the quarantine and validated, the bad phone of the buyer
     * book's line 5 replaced by a typed one in the review, then its batches committed and the run signed off with a dump
     * recorded (as the owner, standing in for the commit and the sign-off, which ImportReconciliationIT runs for real).
     */
    UUID signedRun(TestDatabase.Fixture f) throws IOException {
        UUID run = validatedRun(f);
        // The run's own typed text carries personal values on purpose (review of #886, round 3): the trading override's
        // reason, set through the service so its audit row is written too, and a policy decision's confirmation.
        as(f, () -> runs.allowTradingTenant(run, operatorId, "Fabricated Buyer Gamma " + TYPED_PHONE + " still buys"));
        String policy =
                "{\"receivable\": {\"option\": \"defer\", \"confirmation\": \"Confirmed by " + TYPED_PHONE + "\"}}";
        TestDatabase.owner()
                .sql("UPDATE import_runs SET policy = CAST(? AS jsonb) WHERE id = ?")
                .params(policy, run)
                .update();
        // Every other text column of the run's tables holds a personal value too (Lead on #886: one rule), written as
        // the owner where the application would never put one: an issue's message and suggestion, a batch's tab name
        // and failure reason.
        String personal = "Fabricated Buyer Gamma " + TYPED_PHONE;
        TestDatabase.owner().sql("""
                        UPDATE import_issues SET message = message || ' ' || ?,
                               suggested_value = jsonb_build_object('value', CAST(? AS text))
                         WHERE id = (SELECT i.id FROM import_issues i JOIN import_batches b ON b.id = i.batch_id
                                      WHERE b.run_id = ? ORDER BY i.id LIMIT 1)
                        """).params(personal, personal, run).update();
        TestDatabase.owner()
                .sql("UPDATE import_batches SET sheet_name = ?, failure_reason = ? WHERE run_id = ?")
                .params(personal, personal, run)
                .update();
        TestDatabase.owner()
                .sql("UPDATE import_batches SET status = 'committed', committed_at = now() WHERE run_id = ?")
                .param(run)
                .update();
        TestDatabase.owner().sql("""
                        UPDATE import_runs SET status = 'committed', committed_at = now(),
                               pre_import_dump_file = ?, pre_import_dump_at = now(), pre_import_dump_sha256 = ?
                         WHERE id = ?
                        """).params(DUMP, DUMP_SHA, run).update();
        // A reconciliation and its sign-off, the name typed a personal value.
        UUID reconciliation = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO import_reconciliations (id, tenant_id, run_id, version, as_at, report, sha256, signable)
                        VALUES (?, ?, ?, 1, DATE '2026-10-01', '{"sections": []}', ?, true)
                        """)
                .params(reconciliation, f.tenantId(), run, DUMP_SHA)
                .update();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO import_signoffs (id, tenant_id, run_id, reconciliation_id, report_sha256, sections,
                                                     signed_name, signed_on, signed_by)
                        VALUES (?, ?, ?, ?, ?, ARRAY['counts'], ?, DATE '2026-10-02', ?)
                        """)
                .params(UUID.randomUUID(), f.tenantId(), run, reconciliation, DUMP_SHA, personal, operator)
                .update();
        TestDatabase.owner()
                .sql("UPDATE import_runs SET status = 'signed_off', signed_off_at = now() WHERE id = ?")
                .param(run)
                .update();
        return run;
    }

    /** The golden files uploaded and validated, and the buyer book's bad phone edited in the review. */
    UUID validatedRun(TestDatabase.Fixture f) throws IOException {
        Mapping mapping = as(
                f,
                () -> mappings.latest("fab-shop")
                        .orElseGet(() -> mappings.save("fab-shop", ImportCatalogueTest.mapping(), operator, null)));
        UUID run = as(f, () -> runs.open(operator).id());
        UUID buyers = null;
        for (GoldenRun.GoldenFile g : GoldenRun.FILES) {
            byte[] bytes = Files.readAllBytes(GoldenRun.DIR.resolve(g.file()));
            Batch batch = as(
                    f,
                    () -> uploads.upload(
                            run,
                            new Upload(
                                    g.file(),
                                    new ByteArrayInputStream(bytes),
                                    bytes.length,
                                    operator,
                                    mappings.parseOptions(mapping.id(), g.source()))));
            assertThat(batch.status()).as(g.file()).isEqualTo("parsed");
            if (BUYERS.equals(g.file())) {
                buyers = batch.id();
            }
        }
        TestDatabase.owner()
                .sql("UPDATE import_runs SET cutover_at = ? WHERE id = ?")
                .params(Timestamp.from(GoldenRun.CUTOVER), run)
                .update();
        as(f, () -> validation.validate(run));
        UUID buyerBatch = buyers;
        Issue phone = as(f, () -> validation.issues(buyerBatch)).stream()
                .filter(i -> i.line() == 5 && "TYPE_INVALID".equals(i.code()) && "phone".equals(i.field()))
                .findFirst()
                .orElseThrow();
        // The note carries personal values on purpose, as an operator's free text might (review of #886, MF1).
        String note = "Fabricated Buyer Gamma confirmed " + TYPED_PHONE;
        Resolution edit = new Resolution("edit_value", phone.version(), TYPED_PHONE, null, null, note);
        as(f, () -> review.resolve(phone.id(), edit, operator));
        return run;
    }

    Certificate purge(TestDatabase.Fixture f, UUID run) {
        return as(f, () -> purges.purge(run, new Purge(operatorEmail, witnessEmail, DUMP, DUMP_SHA)));
    }

    // ---- The scan ----

    /**
     * Every table that refers to {@code import_runs} through a chain of foreign keys, and {@code import_runs} itself: the
     * tables of a run, read from the catalogue so that a table a later migration adds is scanned without a change here.
     */
    static List<String> runTables() {
        return TestDatabase.owner().sql("""
                        WITH RECURSIVE t(rel) AS (
                            SELECT CAST(CAST('import_runs' AS regclass) AS oid)
                            UNION
                            SELECT c.conrelid FROM pg_constraint c JOIN t ON c.confrelid = t.rel WHERE c.contype = 'f'
                        )
                        SELECT CAST(CAST(rel AS regclass) AS text) FROM t ORDER BY 1
                        """).query(String.class).list();
    }

    /**
     * Each {@code table.column} of the run's tables (its own row, its rows by run or by batch) whose text holds one of the
     * values, ignoring case: every text, character, JSON and text array column is read.
     */
    static List<String> personalValuesOf(UUID run, List<String> values) {
        List<String> found = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (String table : runTables()) {
            String scope = scopeOf(table);
            ids.addAll(TestDatabase.owner()
                    .sql("SELECT CAST(id AS text) FROM " + table + " WHERE " + scope)
                    .param(run)
                    .query(String.class)
                    .list());
            scan(table, scope, run, values, found);
        }
        // The run's import_commit request, whose checker types a note (review of #886, MF2).
        String approvals = "action_type = 'import_commit' AND subject_id = ?";
        ids.addAll(TestDatabase.owner()
                .sql("SELECT CAST(id AS text) FROM approval_requests WHERE " + approvals)
                .param(run)
                .query(String.class)
                .list());
        scan("approval_requests", approvals, run, values, found);
        // And the rows that name the run without a foreign key (review of #886, S4 and MF2): every audit row of the
        // business about the run or any row of its tables or its approval (the sign-off, the reconciliation, the
        // request), or whose data names the run; and the outbox rows of its warning.
        String in = "{" + String.join(",", ids) + "}";
        for (String value : values) {
            if (count(AUDIT_OF_RUN, run, in, run, value) > 0) {
                found.add("audit_log.data");
                break;
            }
        }
        for (String value : values) {
            if (count(OUTBOX_OF_RUN, run, value) > 0) {
                found.add("notification_outbox.params");
                break;
            }
        }
        return found;
    }

    /** The rows of one table of the run: its own row, its rows by run, by batch, or (none today) by business. */
    static String scopeOf(String table) {
        List<String> columns = TestDatabase.owner()
                .sql("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND table_name = ?")
                .param(table)
                .query(String.class)
                .list();
        if (table.equals("import_runs")) {
            return "id = ?";
        } else if (columns.contains("run_id")) {
            return "run_id = ?";
        } else if (columns.contains("batch_id")) {
            return "batch_id IN (SELECT id FROM import_batches WHERE run_id = ?)";
        }
        return "tenant_id = (SELECT tenant_id FROM import_runs WHERE id = ?)";
    }

    /** Every text, character, JSON and text array column of a table, as the scan and the catalogue read them. */
    static List<String> textColumns(String table) {
        return TestDatabase.owner().sql("""
                        SELECT column_name FROM information_schema.columns
                         WHERE table_schema = 'public' AND table_name = ?
                           AND (data_type IN ('text', 'character varying', 'character', 'jsonb', 'json',
                                              'USER-DEFINED')
                                OR (data_type = 'ARRAY' AND udt_name IN ('_text', '_varchar')))
                         ORDER BY column_name
                        """).param(table).query(String.class).list();
    }

    private static void scan(String table, String scope, UUID run, List<String> values, List<String> found) {
        for (String column : textColumns(table)) {
            String sql = "SELECT count(*) FROM " + table + " WHERE " + scope + " AND strpos(lower(CAST(\"" + column
                    + "\" AS text)), lower(?)) > 0";
            for (String value : values) {
                if (count(sql, run, value) > 0) {
                    found.add(table + "." + column);
                    break;
                }
            }
        }
    }

    static final String AUDIT_OF_RUN = """
            SELECT count(*) FROM audit_log
             WHERE tenant_id = (SELECT tenant_id FROM import_runs WHERE id = ?)
               AND (entity_id = ANY (CAST(? AS uuid[])) OR strpos(CAST(data AS text), CAST(? AS text)) > 0)
               AND strpos(lower(CAST(data AS text)), lower(?)) > 0
            """;

    // JUSTIFICATION-A3: review of #886 MF1 asks for a catalogue test naming every text column of a run in one list.
    // ---- The catalogue of every text column of a run (review of #886, MF1) ----

    /** Hashed or cleared by import_purge_run, or deleted with the file: the typed and the staged text of the run. */
    static final Set<String> HASHED_OR_CLEARED = Set.of(
            "import_runs.allow_trading_reason",
            "import_runs.policy",
            "import_batches.sheet_name",
            "import_batches.failure_reason",
            "import_batches.object_key",
            "import_rows.raw",
            "import_rows.normalised",
            "import_rows.source_ref",
            "import_issues.message",
            "import_issues.original_value",
            "import_issues.suggested_value",
            "import_issue_resolutions.row_key",
            "import_issue_resolutions.before_value",
            "import_issue_resolutions.after_value",
            "import_issue_resolutions.note",
            "import_signoffs.signed_name",
            "approval_requests.decision_note",
            "approval_requests.execution_error");

    /** Kept readable, each for the one reason the V147 header and ADR-027 give. */
    static final Set<String> KEPT_READABLE = Set.of(
            "import_runs.pre_import_dump_file",
            "import_runs.dry_run_report",
            "import_batches.source_filename",
            "import_batches.source_headers",
            "import_batches.report",
            "import_refs.source_key",
            "import_refs.source_ref",
            "import_reconciliations.report",
            "import_rehearsals.report",
            "import_rehearsals.reconciliation",
            "import_deletion_certificates.body",
            "import_deletion_certificates.document",
            "approval_requests.payload");

    /**
     * Codes, hashes and statuses only, never typed: each is held to a code by a CHECK, written from a constant, or a hash.
     * {@code import_runs.policy}'s other keys are an option code, dates and user ids; {@code import_rehearsals.failure} is
     * an error code or an exception's class name ({@code RehearsalService}), never a message.
     */
    static final Set<String> CODES = Set.of(
            "import_runs.status",
            "import_runs.pre_import_dump_sha256",
            "import_runs.dry_run_stamp",
            "import_runs.dry_run_writer",
            "import_runs.origin",
            "import_batches.status",
            "import_batches.counts",
            "import_batches.source_sha256",
            "import_batches.file_type",
            "import_batches.encoding",
            "import_batches.parse_warnings",
            "import_batches.failure_code",
            "import_batches.mapping_source",
            "import_batches.template_key",
            "import_issues.code",
            "import_issues.severity",
            "import_issues.field",
            "import_issues.status",
            "import_issue_resolutions.raw_sha256",
            "import_issue_resolutions.code",
            "import_issue_resolutions.field",
            "import_issue_resolutions.resolution",
            "import_refs.target_type",
            "import_reconciliations.sha256",
            "import_signoffs.report_sha256",
            "import_signoffs.sections",
            "import_rehearsals.backup",
            "import_rehearsals.build",
            "import_rehearsals.outcome",
            "import_rehearsals.failure",
            "import_rehearsals.timings",
            "import_deletion_certificates.reason",
            "import_deletion_certificates.sha256",
            "approval_requests.action_type",
            "approval_requests.subject_type",
            "approval_requests.currency",
            "approval_requests.status");

    /**
     * Every text, character, JSON and text array column of every table of a run (from the catalogue, so a column a later
     * migration adds is found) and of its approval request is in exactly one of the three lists above: a new column fails
     * here until it is hashed, kept for a stated reason, or shown to hold codes only.
     */
    @Test
    void everyTextColumnOfARunIsHashedKeptForAReasonOrACode() {
        Set<String> actual = new TreeSet<>();
        for (String table : Stream.concat(runTables().stream(), Stream.of("approval_requests"))
                .toList()) {
            textColumns(table).forEach(c -> actual.add(table + "." + c));
        }
        Set<String> listed = new TreeSet<>();
        for (Set<String> list : List.of(HASHED_OR_CLEARED, KEPT_READABLE, CODES)) {
            for (String column : list) {
                assertThat(listed.add(column))
                        .as("%s is in one list only", column)
                        .isTrue();
            }
        }
        Set<String> unlisted = new TreeSet<>(actual);
        unlisted.removeAll(listed);
        Set<String> gone = new TreeSet<>(listed);
        gone.removeAll(actual);
        assertThat(unlisted).as("text columns of a run in no list").isEmpty();
        assertThat(gone).as("listed columns that do not exist").isEmpty();
    }

    static final String OUTBOX_OF_RUN =
            "SELECT count(*) FROM notification_outbox WHERE params ->> 'run' = CAST(? AS text)"
                    + " AND strpos(lower(CAST(params AS text)), lower(?)) > 0";

    /** The objects of the run left in the business's quarantine. */
    List<String> objectsOf(TestDatabase.Fixture f, UUID run) {
        return as(f, () -> {
            try (Stream<QuarantineStorage.Held> listed = quarantine.list()) {
                return listed.map(QuarantineStorage.Held::key)
                        .filter(k -> k.contains("/" + run + "/"))
                        .toList();
            }
        });
    }

    static long count(String sql, Object... params) {
        return TestDatabase.owner().sql(sql).params(params).query(Long.class).single();
    }

    static String status(UUID run) {
        return TestDatabase.owner()
                .sql("SELECT status FROM import_runs WHERE id = ?")
                .param(run)
                .query(String.class)
                .single();
    }

    static final String ROWS_OF_RUN =
            " FROM import_rows w JOIN import_batches b ON b.id = w.batch_id WHERE b.run_id = ?";

    static final String ISSUES_OF_RUN =
            " FROM import_issues i JOIN import_batches b ON b.id = i.batch_id WHERE b.run_id = ?";

    // ---- The purge at stage 9 ----

    @Test
    void afterThePurgeNoPersonalValueOfTheFixtureIsInAnyTableOrObjectOfTheRun() throws IOException {
        UUID run = signedRun(t);
        assertThat(runTables())
                .as("the scan reads every table of a run")
                .contains(
                        "import_runs",
                        "import_batches",
                        "import_rows",
                        "import_issues",
                        "import_issue_resolutions",
                        "import_deletion_certificates");
        assertThat(personalValuesOf(run, PERSONAL))
                .as("before the purge the scan finds the staged cells, typed values, issue values and the resolution")
                .contains(
                        "import_rows.raw",
                        "import_rows.normalised",
                        "import_issues.original_value",
                        "import_issue_resolutions.after_value",
                        "import_issue_resolutions.note",
                        "import_runs.allow_trading_reason",
                        "import_runs.policy",
                        "import_issues.message",
                        "import_issues.suggested_value",
                        "import_batches.sheet_name",
                        "import_batches.failure_reason",
                        "import_signoffs.signed_name")
                .as("and never the audit rows, which keep only the typed text's hash")
                .doesNotContain("audit_log.data");
        assertThat(objectsOf(t, run)).hasSize(GoldenRun.FILES.size());
        long rows = count("SELECT count(*)" + ROWS_OF_RUN, run);
        long issues = count("SELECT count(*)" + ISSUES_OF_RUN, run);
        long resolutions = count("SELECT count(*) FROM import_issue_resolutions WHERE run_id = ?", run);

        Certificate certificate = purge(t, run);

        assertThat(status(run)).isEqualTo("purged");
        assertThat(personalValuesOf(run, PERSONAL))
                .as("columns still holding a personal value")
                .isEmpty();
        assertThat(objectsOf(t, run)).as("objects of the run left in storage").isEmpty();
        assertThat(count("SELECT count(*) FROM import_runs WHERE id = ? AND data_key_wrapped IS NULL", run))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM import_batches WHERE run_id = ? AND object_key IS NOT NULL", run))
                .isZero();
        // Kept: the records and their counts, without the values.
        String cleared = "SELECT count(*)" + ROWS_OF_RUN + " AND w.raw = '{}'::jsonb AND w.normalised IS NULL"
                + " AND (w.source_ref IS NULL OR w.source_ref ~ '^sha256:[0-9a-f]{64}$')";
        assertThat(count(cleared, run)).isEqualTo(rows);
        String issuesCleared = "SELECT count(*)" + ISSUES_OF_RUN + " AND i.original_value IS NULL"
                + " AND i.suggested_value IS NULL AND i.message ~ '^sha256:[0-9a-f]{64}$'";
        assertThat(count(issuesCleared, run)).isEqualTo(issues);
        String hashed = "SELECT count(*) FROM import_issue_resolutions WHERE run_id = ?"
                + " AND row_key ~ '^sha256:[0-9a-f]{64}$'"
                + " AND (after_value IS NULL OR after_value ->> 'sha256' ~ '^[0-9a-f]{64}$')"
                + " AND (note IS NULL OR note ~ '^sha256:[0-9a-f]{64}$')";
        assertThat(count(hashed, run)).isEqualTo(resolutions);
        assertThat(count(
                        "SELECT count(*) FROM import_runs WHERE id = ? AND allow_trading_reason ~ '^sha256:[0-9a-f]{64}$'"
                                + " AND policy -> 'receivable' ->> 'confirmation' ~ '^sha256:[0-9a-f]{64}$'"
                                + " AND policy -> 'receivable' ->> 'option' = 'defer'",
                        run))
                .as("the run's own typed text hashed, its decisions kept")
                .isEqualTo(1);
        // The certificate: on the run, its hash its text's, the files with sizes and hashes, the counts, the dump as
        // confirmed, the operator and the witness, and what is kept.
        String stored = """
                SELECT count(*) FROM import_deletion_certificates
                 WHERE run_id = ? AND version = 1 AND reason = 'operator' AND operator_id = ? AND witness_id = ?
                   AND dump_confirmed AND sha256 = encode(sha256(convert_to(document, 'UTF8')), 'hex')
                   AND jsonb_array_length(body -> 'files') = 3 AND CAST(body ->> 'staged_rows' AS bigint) = ?
                """;
        assertThat(count(stored, run, operatorId, witnessId, rows)).isEqualTo(1);
        List<String> files = TestDatabase.owner()
                .sql("SELECT source_filename || ', ' || size_bytes || ' bytes, SHA-256 ' || source_sha256"
                        + " FROM import_batches WHERE run_id = ? ORDER BY seq")
                .param(run)
                .query(String.class)
                .list();
        assertThat(files).hasSize(3);
        assertThat(certificate.document())
                .contains(files)
                .contains(DUMP + ", SHA-256 " + DUMP_SHA + "; its deletion on the host confirmed by the operator")
                .contains("Operator: Test Operator 01", "Witness: Test Operator 02", "Retained", rows + " staged");
        assertThat(certificate.reason()).isEqualTo("operator");
        assertThat(certificate.dumpConfirmed()).isTrue();
        // Audited once, with counts and never a value.
        List<String> audits = TestDatabase.owner()
                .sql("SELECT CAST(data AS text) FROM audit_log WHERE tenant_id = ?"
                        + " AND action = 'core.import_run.purged' AND entity_id = ?")
                .params(t.tenantId(), run)
                .query(String.class)
                .list();
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst()).contains(certificate.sha256());
        String audit = audits.getFirst().toLowerCase(Locale.ROOT);
        assertThat(PERSONAL).noneMatch(v -> audit.contains(v.toLowerCase(Locale.ROOT)));
    }

    @Test
    void aSecondPurgeChangesNothingAndAnswersTheSameCertificate() throws IOException {
        UUID run = signedRun(t);
        Certificate first = purge(t, run);
        Certificate again = purge(t, run);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.sha256()).isEqualTo(first.sha256());
        assertThat(as(t, () -> purges.certificate(run)).orElseThrow().id()).isEqualTo(first.id());
        assertThat(count("SELECT count(*) FROM import_deletion_certificates WHERE run_id = ?", run))
                .isEqualTo(1);
        String audited = "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.import_run.purged'"
                + " AND entity_id = ?";
        assertThat(count(audited, t.tenantId(), run)).isEqualTo(1);
        assertThat(status(run)).isEqualTo("purged");
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    AuditLog audit;

    @Autowired
    BusinessClock clock;

    @Autowired
    CurrentTenant tenant;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    Outbox outbox;

    // JUSTIFICATION-A3: review of #886, M1 asks for this new test case with its two injected failures.
    /**
     * Review of #886, M1: storage that refuses the second file, then a purge transaction that fails after every file is
     * deleted (its audit row refused). Each time the run, its key, its rows and its batches' keys are as they were, with
     * fewer files, and no certificate; the next purge finishes it with one certificate.
     */
    @Test
    void aPurgeStoppedByStorageOrByItsTransactionLeavesTheRunAsItWasAndTheNextOneFinishes() throws IOException {
        UUID run = signedRun(t);
        Purge request = new Purge(operatorEmail, witnessEmail, DUMP, DUMP_SHA);
        String unchanged = "SELECT count(*) FROM import_runs WHERE id = ? AND status = 'signed_off'"
                + " AND data_key_wrapped IS NOT NULL AND purged_at IS NULL";
        String keysKept = "SELECT count(*) FROM import_batches WHERE run_id = ? AND object_key IS NOT NULL";

        int[] deletes = {0};
        QuarantineStorage refusingSecond = new QuarantineStorage() {
            @Override
            public String put(UUID runId, UUID batchId, Path encryptedFile) {
                return quarantine.put(runId, batchId, encryptedFile);
            }

            @Override
            public Optional<InputStream> open(String key) {
                return quarantine.open(key);
            }

            @Override
            public void delete(String key) {
                if (++deletes[0] == 2) {
                    throw new IllegalStateException("storage refuses");
                }
                quarantine.delete(key);
            }

            @Override
            public Stream<Held> list() {
                return quarantine.list();
            }

            @Override
            public List<String> deleteAll(List<String> keys) {
                return quarantine.deleteAll(keys);
            }
        };
        PurgeService storageDown =
                new PurgeService(jdbc, transactions, refusingSecond, audit, clock, tenant, mapper, outbox);
        assertCode(() -> as(t, () -> storageDown.purge(run, request)), "storage_unavailable");
        assertThat(objectsOf(t, run)).as("the first file stays deleted").hasSize(GoldenRun.FILES.size() - 1);
        assertThat(count(unchanged, run)).isEqualTo(1);
        assertThat(count(keysKept, run)).isEqualTo(GoldenRun.FILES.size());
        assertThat(personalValuesOf(run, PERSONAL)).isNotEmpty();

        AuditLog refusingPurge = new AuditLog() {
            @Override
            public void record(Entry entry) {
                audit.record(entry);
            }

            @Override
            public void record(Entry entry, UUID actorUserId, String actorKind) {
                if ("core.import_run.purged".equals(entry.action())) {
                    throw new IllegalStateException("audit refuses");
                }
                audit.record(entry, actorUserId, actorKind);
            }
        };
        PurgeService failing =
                new PurgeService(jdbc, transactions, quarantine, refusingPurge, clock, tenant, mapper, outbox);
        assertThatThrownBy(() -> as(t, () -> failing.purge(run, request))).isInstanceOf(IllegalStateException.class);
        assertThat(objectsOf(t, run)).as("every file deleted").isEmpty();
        assertThat(count(unchanged, run)).isEqualTo(1);
        assertThat(count(keysKept, run))
                .as("the transaction that cleared them rolled back")
                .isEqualTo(GoldenRun.FILES.size());
        assertThat(count("SELECT count(*) FROM import_deletion_certificates WHERE run_id = ?", run))
                .isZero();
        assertThat(personalValuesOf(run, PERSONAL)).isNotEmpty();

        Certificate certificate = purge(t, run);
        assertThat(status(run)).isEqualTo("purged");
        assertThat(personalValuesOf(run, PERSONAL)).isEmpty();
        assertThat(count("SELECT count(*) FROM import_deletion_certificates WHERE run_id = ?", run))
                .isEqualTo(1);
        assertThat(certificate.document()).contains(DUMP + ", SHA-256 " + DUMP_SHA);
    }

    @Test
    void theOperatorPurgesOnlyASignedRunWithAWitnessAndTheDumpAsRecorded() throws IOException {
        UUID open = validatedRun(t);
        assertCode(() -> purge(t, open), "run_not_signed_off");
        // Purged is reached only by the purge.
        assertCode(() -> as(t, () -> runs.transition(open, "purged")), "invalid_status_transition");

        UUID run = signedRun(t);
        assertCode(
                () -> purgeWith(run, operatorEmail, witnessEmail, null, null), "pre_import_dump_confirmation_required");
        assertCode(() -> purgeWith(run, operatorEmail, witnessEmail, DUMP, "f".repeat(64)), "pre_import_dump_differs");
        assertCode(() -> purgeWith(run, operatorEmail, operatorEmail, DUMP, DUMP_SHA), "witness_is_operator");
        assertCode(
                () -> purgeWith(run, Api.email("nobody"), witnessEmail, DUMP, DUMP_SHA), "platform_operator_unknown");
        TestDatabase.owner()
                .sql("UPDATE platform_users SET is_active = false WHERE id = ?")
                .param(witnessId)
                .update();
        assertCode(() -> purge(t, run), "platform_operator_unknown");

        assertThat(status(open)).isEqualTo("in_review");
        assertThat(status(run)).isEqualTo("signed_off");
        assertThat(objectsOf(t, run)).hasSize(GoldenRun.FILES.size());
        assertThat(count("SELECT count(*) FROM import_deletion_certificates WHERE run_id IN (?, ?)", open, run))
                .isZero();
    }

    Certificate purgeWith(UUID run, String operatorAt, String witnessAt, String dumpFile, String dumpSha) {
        return as(t, () -> purges.purge(run, new Purge(operatorAt, witnessAt, dumpFile, dumpSha)));
    }

    @Test
    void aPurgeNeverTouchesAnotherRunOrAnotherBusiness() throws IOException {
        UUID purged = signedRun(t);
        UUID kept = signedRun(t);
        UUID elsewhere = signedRun(other);
        Map<String, Object> keptBefore = snapshot(kept);
        Map<String, Object> elsewhereBefore = snapshot(elsewhere);

        purge(t, purged);

        assertThat(snapshot(kept)).isEqualTo(keptBefore);
        assertThat(snapshot(elsewhere)).isEqualTo(elsewhereBefore);
        assertThat(status(kept)).isEqualTo("signed_off");
        assertThat(status(elsewhere)).isEqualTo("signed_off");
        assertThat(objectsOf(t, kept)).hasSize(GoldenRun.FILES.size());
        assertThat(objectsOf(other, elsewhere)).hasSize(GoldenRun.FILES.size());
        assertThat(personalValuesOf(kept, PERSONAL)).isNotEmpty();
        // Bound to this business, neither the service nor the definer function reaches the other business's run.
        assertThatThrownBy(() -> purge(t, elsewhere)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> asApp("SELECT import_purge_run(?)", elsewhere)).hasMessageContaining("no import run");
        assertThat(snapshot(elsewhere)).isEqualTo(elsewhereBefore);
    }

    /** What a run holds that the purge would change: its state, key, values and files. */
    static Map<String, Object> snapshot(UUID run) {
        return TestDatabase.owner().sql("""
                        SELECT r.status, r.version, encode(r.data_key_wrapped, 'hex') AS data_key,
                               (SELECT md5(string_agg(CAST(w.raw AS text) || coalesce(CAST(w.normalised AS text), '')
                                                      || coalesce(w.source_ref, ''), '|' ORDER BY w.id))
                                  FROM import_rows w JOIN import_batches b ON b.id = w.batch_id
                                 WHERE b.run_id = r.id) AS rows,
                               (SELECT md5(string_agg(coalesce(CAST(i.original_value AS text), ''), '|' ORDER BY i.id))
                                  FROM import_issues i JOIN import_batches b ON b.id = i.batch_id
                                 WHERE b.run_id = r.id) AS issues,
                               (SELECT md5(string_agg(x.row_key || coalesce(CAST(x.before_value AS text), '')
                                                      || coalesce(CAST(x.after_value AS text), ''), '|' ORDER BY x.id))
                                  FROM import_issue_resolutions x WHERE x.run_id = r.id) AS resolutions,
                               (SELECT string_agg(b.object_key, '|' ORDER BY b.seq) FROM import_batches b
                                 WHERE b.run_id = r.id) AS files
                          FROM import_runs r WHERE r.id = ?
                        """).param(run).query().singleRow();
    }

    // ---- The guards ----

    /**
     * The narrow way past the guards of a committed batch, tried from a definer function the test makes for the purpose
     * ({@link #asDefiner}: another definer function holding the purge's marker, the strongest position short of the
     * purge itself). It changes the run's own personal columns to what the purge writes once the run is purged in that
     * transaction, and nothing else: not before, not another run, not another column or value, no delete, no other
     * append-only table, nothing in a later transaction. A session of the owner role itself never passes (review of #886,
     * S1), nor may it or the application mark a run purged (M2).
     */
    @Test
    void theGuardsLetThroughOnlyThePurgeOfItsOwnRunInItsOwnTransaction() throws IOException, SQLException {
        UUID run = signedRun(t);
        UUID neighbour = signedRun(t);
        String issuesKept = "the issues of a committed import batch never change";
        String rowsKept = "the rows of a committed import batch never change";
        String appendOnly = "append-only";
        String purgedOnly = "import_runs_purged_by_purge";
        String hashRun = hashResolutions(run);
        String aHash = "'sha256:" + "a".repeat(64) + "'";
        String issuesOfRun = "UPDATE import_issues SET original_value = NULL, suggested_value = NULL, message = "
                + aHash + inBatches(run);
        String signOffOfRun = "UPDATE import_signoffs SET signed_name = " + aHash + " WHERE run_id = " + quote(run);
        String rowsOfRun = "UPDATE import_rows SET raw = '{}', normalised = NULL, source_ref = NULL" + inBatches(run);

        // Not purged in this transaction: refused by each guard.
        refused(null, run, issuesOfRun, issuesKept);
        refused(null, run, rowsOfRun, rowsKept);
        refused(null, run, hashRun, appendOnly);
        // Purged in this transaction, but the rows of another run, or the setting naming another run: refused.
        refused(run, run, hashResolutions(neighbour), appendOnly);
        refused(run, neighbour, hashRun, appendOnly);
        // Its own run, but a column that is not personal, a value that is not a hash, or a delete: refused.
        String resolutionsOfRun = " WHERE run_id = " + quote(run);
        refused(run, run, "UPDATE import_issue_resolutions SET field = NULL" + resolutionsOfRun, appendOnly);
        refused(run, run, "UPDATE import_issue_resolutions SET note = 'x'" + resolutionsOfRun, appendOnly);
        refused(run, run, "UPDATE import_issue_resolutions SET row_key = 'x'" + resolutionsOfRun, appendOnly);
        refused(run, run, "DELETE FROM import_issues" + inBatches(run), issuesKept);
        refused(run, run, "UPDATE import_issues SET message = 'x'" + inBatches(run), issuesKept);
        refused(run, run, "UPDATE import_signoffs SET signed_name = 'x' WHERE run_id = " + quote(run), appendOnly);
        refused(run, run, "UPDATE import_signoffs SET sections = ARRAY['x'] WHERE run_id = " + quote(run), appendOnly);
        refused(null, run, signOffOfRun, appendOnly);
        refused(run, run, "DELETE FROM import_issue_resolutions" + resolutionsOfRun, appendOnly);
        // Any other append-only table stays closed under the setting (review M5).
        refused(run, run, "UPDATE audit_log SET action = 'x' WHERE tenant_id = " + quote(t.tenantId()), appendOnly);
        // Its own run's personal columns, to what the purge writes, in the transaction that purged it: through.
        asDefiner(run, run, hashRun);
        asDefiner(run, run, issuesOfRun);
        asDefiner(run, run, signOffOfRun);
        asDefiner(run, run, rowsOfRun);

        // A session of the owner role (a migration's) cannot mark a run purged nor use the way through.
        String markPurged = "UPDATE import_runs SET status = 'purged', purged_at = now() WHERE id = " + quote(run);
        assertThatThrownBy(() -> asOwnerSession(run, markPurged)).hasMessageContaining(purgedOnly);
        assertThatThrownBy(() -> asOwnerSession(run, hashRun)).hasMessageContaining(appendOnly);
        // Nor can the application: purged only by the purge.
        assertThatThrownBy(() -> asApp("UPDATE import_runs SET status = 'purged' WHERE id = ?", run))
                .hasMessageContaining(purgedOnly);

        // The real purge passes; a later transaction cannot use its way through again, and the run stays purged.
        purge(t, run);
        refused(null, run, hashRun, appendOnly);
        assertThatThrownBy(() -> asApp("UPDATE import_runs SET status = 'signed_off' WHERE id = ?", run))
                .hasMessageContaining(purgedOnly);
        // The application role is refused by its grants before any guard.
        assertThatThrownBy(() -> asApp(hashResolutions(neighbour), null)).hasMessageContaining("permission denied");
        assertThat(status(neighbour)).isEqualTo("signed_off");
        assertThat(personalValuesOf(neighbour, PERSONAL)).isNotEmpty();
    }

    static String quote(UUID id) {
        return "CAST('" + id + "' AS uuid)";
    }

    static String inBatches(UUID run) {
        return " WHERE batch_id IN (SELECT id FROM import_batches WHERE run_id = " + quote(run) + ")";
    }

    /** What the purge writes in a run's resolutions, written by hand. */
    static String hashResolutions(UUID run) {
        return "UPDATE import_issue_resolutions SET row_key = 'sha256:" + "a".repeat(64)
                + "', before_value = NULL, after_value = NULL, note = NULL WHERE run_id = " + quote(run);
    }

    void refused(UUID marked, UUID named, String statement, String message) {
        assertThatThrownBy(() -> asDefiner(marked, named, statement))
                .as(statement)
                .hasMessageContaining(message);
    }

    /**
     * A definer function the test makes, owned by the owner role like every migration's: it marks {@code marked} purged in
     * the caller's transaction exactly as {@code import_purge_run} does (setting, then the state), sets the purge's
     * setting to {@code named}, then runs the statements. Dropped after each test.
     */
    static final String DEFINER = """
            CREATE OR REPLACE FUNCTION test_purge_as_definer(p_mark uuid, p_named text, p_sql text) RETURNS void
            LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
            BEGIN
                IF p_mark IS NOT NULL THEN
                    PERFORM set_config('bms.purging_import_run', CAST(p_mark AS text), true);
                    UPDATE import_runs SET status = 'purged', purged_at = now() WHERE id = p_mark;
                END IF;
                PERFORM set_config('bms.purging_import_run', coalesce(p_named, ''), true);
                EXECUTE p_sql;
            END
            $$
            """;

    @AfterEach
    void dropDefiner() {
        TestDatabase.owner()
                .sql("DROP FUNCTION IF EXISTS test_purge_as_definer(uuid, text, text)")
                .update();
    }

    /** The statements through {@link #DEFINER}, called by bms_app with this test's business bound; rolled back. */
    void asDefiner(UUID marked, UUID named, String statements) throws SQLException {
        TestDatabase.owner().sql(DEFINER).update();
        TestDatabase.owner()
                .sql("GRANT EXECUTE ON FUNCTION test_purge_as_definer(uuid, text, text) TO bms_app")
                .update();
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement bind = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    bind.setString(1, t.tenantId().toString());
                    bind.execute();
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT test_purge_as_definer(?, ?, ?)")) {
                    if (marked == null) {
                        ps.setNull(1, Types.OTHER);
                    } else {
                        ps.setObject(1, marked);
                    }
                    ps.setString(2, named.toString());
                    ps.setString(3, statements);
                    ps.execute();
                }
            } finally {
                c.rollback();
            }
        }
    }

    /** One transaction in a session of the owner role, the business bound and the purge's setting naming the run. */
    void asOwnerSession(UUID run, String statement) throws SQLException {
        try (Connection c = TestDatabase.ownerDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement bind = c.prepareStatement(
                        "SELECT set_config('app.tenant_id', ?, true), set_config('bms.purging_import_run', ?, true)")) {
                    bind.setString(1, t.tenantId().toString());
                    bind.setString(2, run.toString());
                    bind.execute();
                }
                try (PreparedStatement ps = c.prepareStatement(statement)) {
                    ps.execute();
                }
            } finally {
                c.rollback();
            }
        }
    }

    /** One statement as bms_app with this test's business bound, rolled back; {@code param} fills its one ? if any. */
    void asApp(String sql, UUID param) throws SQLException {
        try (Connection c = TestDatabase.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement bind = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                    bind.setString(1, t.tenantId().toString());
                    bind.execute();
                }
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    if (param != null) {
                        ps.setObject(1, param);
                    }
                    ps.execute();
                }
            } finally {
                c.rollback();
            }
        }
    }
}
