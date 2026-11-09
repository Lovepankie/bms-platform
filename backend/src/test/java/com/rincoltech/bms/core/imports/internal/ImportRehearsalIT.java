package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportRehearsals;
import com.rincoltech.bms.core.imports.ImportRehearsals.Rehearsal;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.core.imports.ScriptedTemplate;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * The rehearsal of issue #138 (ADR-027 decision 9, spec section 5.5) on real PostgreSQL as bms_app, through the test-only
 * {@link ScriptedTemplate}: in a database that holds the rehearsal's token (the table deploy/restore-drill.sh makes in its
 * throwaway database), a ready run is taken back to review, dry run again, made ready and committed without the checker's
 * approval and the pre-import dump, and one {@code import_rehearsals} row is stored with the report, the reconciliation, the
 * comparison with the run's stored dry run and the timings. Refused, with nothing written: a database without the token
 * (every live one), a wrong token, on staging a run not marked fabricated, and a run that is not in review or ready. The
 * ordinary commit still needs its approval and dump while the token is there. Fabricated data only: made-up codes and counts.
 */
class ImportRehearsalIT extends IntegrationTest {

    static final String TOKEN = "f00dfeed".repeat(6);

    static final String BACKUP = "bms-staging-20261024T011500Z.dump.enc";

    /** Three made-up rows read and written by every batch, and a note, the same in the dry run and the commit. */
    static final BatchWriter THREE_ROWS = (runId, batchId, seq, checkpoint) -> {
        checkpoint.note("three fabricated rows");
        return Map.of("read", 3, "written", 3, "existing", 0, "skipped", 0);
    };

    @Autowired
    ImportRuns runs;

    @Autowired
    ImportRehearsals rehearsals;

    @Autowired
    ImportRunService runService;

    @Autowired
    ImportMappings mappings;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ObjectMapper mapper;

    TestDatabase.Fixture t;
    final UUID operator = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("rehearse", false);
        ScriptedTemplate.SCRIPT = THREE_ROWS;
    }

    @AfterEach
    void reset() {
        ScriptedTemplate.SCRIPT = null;
        TestDatabase.owner().sql("DROP TABLE IF EXISTS bms_rehearsal_marker").update();
    }

    <T> T as(Supplier<T> work) {
        return TenantContext.callAs(t.tenantId(), work);
    }

    /** The token in this database, as the rehearsal script puts it in its throwaway one. */
    static void mark(String token) {
        JdbcClient owner = TestDatabase.owner();
        owner.sql("CREATE TABLE bms_rehearsal_marker (token text NOT NULL)").update();
        owner.sql("INSERT INTO bms_rehearsal_marker (token) VALUES (?)")
                .param(token)
                .update();
        owner.sql("GRANT SELECT ON bms_rehearsal_marker TO bms_app").update();
    }

    /** A run of two batches of the scripted template, dry run and ready: no dump recorded and no commit approved. */
    UUID readyRun(boolean fabricated) {
        return as(() -> {
            UUID run = runs.open(operator, fabricated).id();
            runs.addBatch(run, 1);
            runs.addBatch(run, 2);
            UUID mapping = mappings.latest("scripted")
                    .orElseGet(() -> mappings.save("scripted", ScriptedTemplate.mapping(), operator, null))
                    .id();
            TestDatabase.owner()
                    .sql("""
                            UPDATE import_batches SET mapping_id = ?, mapping_source = 'rows', template_key = ?,
                                   template_version = 1
                             WHERE run_id = ?
                            """)
                    .params(mapping, ScriptedTemplate.KEY, run)
                    .update();
            runs.transition(run, "uploaded");
            runs.transition(run, "in_review");
            runs.allowTradingTenant(
                    run, Api.platformUser(Api.email("rehearsal-maker")), "Rehearsal of a fabricated tab");
            runs.approveTradingTenant(run, Api.platformUser(Api.email("rehearsal-checker")));
            runs.dryRun(run, operator);
            runs.transition(run, "ready");
            return run;
        });
    }

    long rehearsalsOf(UUID run) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM import_rehearsals WHERE run_id = ?")
                .param(run)
                .query(Long.class)
                .single();
    }

    static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    /** A rehearsal of the run in the test's business, by the given service, with no host timings. */
    Rehearsal rehearse(ImportRehearsals by, UUID run, String token) {
        return as(() -> by.rehearse(run, token, BACKUP, Map.of()));
    }

    @Test
    void aRunIsRefusedInADatabaseWithoutTheTokenAndNothingIsWritten() {
        UUID run = readyRun(true);
        assertCode(() -> rehearse(rehearsals, run, TOKEN), "not_a_rehearsal_database");
        assertCode(() -> as(() -> runService.commitRehearsal(run, TOKEN)), "not_a_rehearsal_database");
        mark(TOKEN);
        assertCode(() -> rehearse(rehearsals, run, "0".repeat(48)), "not_a_rehearsal_database");
        assertCode(() -> as(() -> runService.commitRehearsal(run, "")), "not_a_rehearsal_database");
        assertThat(rehearsalsOf(run)).isZero();
        assertThat(as(() -> runs.get(run)).status()).isEqualTo("ready");
    }

    @Test
    void aRehearsalCommitsWithoutApprovalOrDumpAndStoresItsReport() {
        UUID run = readyRun(true);
        mark(TOKEN);
        // The token loosens nothing for the ordinary commit: it still needs the dump and the checker.
        assertCode(() -> as(() -> runs.commit(run)), "pre_import_dump_missing");

        Map<String, Long> host = Map.of("download_ms", 12L, "restore_ms", 34L);
        Rehearsal rehearsal = as(() -> rehearsals.rehearse(run, TOKEN, BACKUP, host));

        assertThat(rehearsal.outcome()).isEqualTo("committed");
        assertThat(rehearsal.failure()).isNull();
        assertThat(rehearsal.matchesDryRun())
                .as("the commit says what the run's stored dry run said")
                .isTrue();
        assertThat(rehearsal.report().sources()).hasSize(2);
        assertThat(rehearsal.report().notes()).contains("three fabricated rows");
        assertThat(rehearsal.timings())
                .containsEntry("download_ms", 12L)
                .containsEntry("restore_ms", 34L)
                .containsKeys("start_ms", "dry_run_ms", "commit_ms");
        assertThat(as(() -> runs.get(run)).status()).isEqualTo("committed");
        Map<String, Object> row =
                TestDatabase.owner().sql("""
                        SELECT tenant_id, backup, outcome, failure IS NULL AS no_failure, matches_dry_run,
                               report->>'mode' AS mode,
                               (reconciliation IS NULL) = NOT EXISTS (SELECT 1 FROM import_reconciliations r
                                                                       WHERE r.run_id = h.run_id) AS reconciled,
                               coalesce(reconciliation->>'sha256' = (SELECT r.sha256 FROM import_reconciliations r
                                                                      WHERE r.run_id = h.run_id
                                                                      ORDER BY r.version DESC LIMIT 1), true) AS same,
                               timings->>'commit_ms' IS NOT NULL AS timed
                          FROM import_rehearsals h WHERE run_id = ?
                        """).param(run).query().singleRow();
        assertThat(row)
                .containsEntry("tenant_id", t.tenantId())
                .containsEntry("backup", BACKUP)
                .containsEntry("outcome", "committed")
                .containsEntry("no_failure", true)
                .containsEntry("matches_dry_run", true)
                .containsEntry("mode", "commit")
                .containsEntry("reconciled", true)
                .containsEntry("same", true)
                .containsEntry("timed", true);
        // The rehearsal's row is append-only, like the reconciliation it carries.
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE import_rehearsals SET outcome = 'failed' WHERE run_id = ?")
                        .param(run)
                        .update())
                .hasStackTraceContaining("append-only");
    }

    @Test
    void onStagingARunNotMarkedFabricatedIsRefused() {
        UUID run = readyRun(false);
        mark(TOKEN);
        RehearsalService staging = new RehearsalService(runService, jdbc, transactions, mapper, "staging");
        assertCode(() -> rehearse(staging, run, TOKEN), "run_not_fabricated");
        assertThat(rehearsalsOf(run)).isZero();
        assertThat(as(() -> runs.get(run)).status()).isEqualTo("ready");

        UUID fabricated = readyRun(true);
        assertThat(rehearse(staging, fabricated, TOKEN).outcome()).isEqualTo("committed");
    }

    @Test
    void aRunNeitherInReviewNorReadyIsRefused() {
        UUID run = as(() -> runs.open(operator, true).id());
        mark(TOKEN);
        assertCode(() -> rehearse(rehearsals, run, TOKEN), "run_not_rehearsable");
        assertThat(rehearsalsOf(run)).isZero();
    }

    @Test
    void whetherARunIsFabricatedIsSaidWhenItOpensAndNeverChanges() {
        ImportRuns.Run fabricated = as(() -> runs.open(operator, true));
        ImportRuns.Run plain = as(() -> runs.open(operator));
        assertThat(fabricated.fabricated()).isTrue();
        assertThat(plain.fabricated()).isFalse();
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE import_runs SET fabricated = true WHERE id = ?")
                        .param(plain.id())
                        .update())
                .hasStackTraceContaining("import_runs_fabricated_fixed");
    }
}
