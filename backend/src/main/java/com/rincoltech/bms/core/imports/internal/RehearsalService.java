package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportRehearsals;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ImportRehearsals} over {@link ImportRunService} and {@code import_rehearsals} (migration V146, issue #138). Every
 * write it makes, the run back to review, the dry run, the commit and the row, follows a check, in the same transaction,
 * that this database holds the rehearsal's token; the live database never does.
 */
@Service
class RehearsalService implements ImportRehearsals {

    /** Who takes the rehearsal's dry run: no person, the nil id, as a run of the import-retail command is opened by. */
    static final UUID REHEARSAL = new UUID(0L, 0L);

    private static final Logger log = LoggerFactory.getLogger(RehearsalService.class);

    private final ImportRunService runs;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    /** BMS_ENVIRONMENT: on staging only a run marked fabricated is rehearsed (spec section 5.4). */
    private final String environment;

    RehearsalService(
            ImportRunService runs,
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            ObjectMapper mapper,
            @Value("${bms.environment:}") String environment) {
        this.runs = runs;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.mapper = mapper;
        this.environment = environment == null ? "" : environment.strip();
    }

    @Override
    public Rehearsal rehearse(UUID runId, String token, String backup, Map<String, Long> hostTimings) {
        TenantContext.require();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("A rehearsal takes one transaction per step; call it outside one");
        }
        ImportRuns.Run run = tx.execute(s -> {
            runs.requireRehearsalDatabase(token);
            return runs.get(runId);
        });
        if ("staging".equals(environment) && !run.fabricated()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "run_not_fabricated",
                    "Run not fabricated",
                    "On staging only an import its uploader marked as fabricated data is rehearsed.");
        }
        if (!List.of(RunStates.IN_REVIEW, RunStates.READY).contains(run.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "run_not_rehearsable",
                    "Import not rehearsable",
                    "Only an import in review or ready is rehearsed; this one is " + RunStates.words(run.status())
                            + ".");
        }
        // The run's stored dry run is the live one, copied with the run: kept before the rehearsal's own replaces it.
        CommitReport liveDryRun =
                runs.dryRunReport(runId).map(ImportRuns.DryRun::report).orElse(null);
        Map<String, Long> timings = new LinkedHashMap<>(hostTimings);
        timings.put("start_ms", ManagementFactory.getRuntimeMXBean().getUptime());
        String outcome;
        String failure = null;
        CommitReport dryRun = null;
        try {
            // Back to review in this database only, so the dry run is taken again on the restored state and this build,
            // as the live run's own was on the live state: the commit then checks it as it checks any run's.
            tx.executeWithoutResult(s -> {
                runs.requireRehearsalDatabase(token);
                jdbc.sql("""
                                UPDATE import_runs SET status = 'in_review', version = version + 1, updated_at = now()
                                 WHERE id = ? AND status = 'ready'
                                """).param(runId).update();
            });
            long step = System.nanoTime();
            dryRun = runs.dryRun(runId, REHEARSAL).report();
            timings.put("dry_run_ms", millisSince(step));
            runs.transition(runId, RunStates.READY);
            step = System.nanoTime();
            ImportRuns.CommitOutcome committed = runs.commitRehearsal(runId, token);
            timings.put("commit_ms", millisSince(step));
            outcome = committed == ImportRuns.CommitOutcome.COMMITTED ? "committed" : "halted";
            failure = committed == ImportRuns.CommitOutcome.COMMITTED ? null : "halted";
        } catch (ApiException e) {
            outcome = "failed";
            failure = e.code();
        } catch (RuntimeException e) {
            // The kind of failure only, never the message, which may quote a row (as the commit's own audit).
            outcome = "failed";
            failure = e.getClass().getSimpleName();
        }
        String status = runs.get(runId).status();
        CommitReport report = RunStates.COMMITTING.equals(status) || RunStates.COMMITTED.equals(status)
                ? runs.commitReport(runId)
                : dryRun;
        Boolean matches = report == null || liveDryRun == null ? null : report.sameAs(liveDryRun);
        UUID id = UUID.randomUUID();
        String reportJson = report == null ? null : mapper.writeValueAsString(report);
        String timingsJson = mapper.writeValueAsString(timings);
        String why = failure;
        String result = outcome;
        tx.executeWithoutResult(s -> {
            runs.requireRehearsalDatabase(token);
            jdbc.sql("""
                            INSERT INTO import_rehearsals (id, tenant_id, run_id, backup, build, outcome, failure, report,
                                                           reconciliation, matches_dry_run, timings)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, CAST(? AS jsonb),
                                    (SELECT jsonb_build_object('version', r.version, 'as_at', r.as_at, 'sha256', r.sha256,
                                                               'signable', r.signable, 'report', r.report)
                                       FROM import_reconciliations r WHERE r.run_id = ? ORDER BY r.version DESC LIMIT 1),
                                    ?, CAST(? AS jsonb))
                            """)
                    .params(
                            id,
                            runId,
                            backup,
                            Objects.requireNonNullElse(runs.build(), "unknown"),
                            result,
                            why,
                            reportJson,
                            runId,
                            matches,
                            timingsJson)
                    .update();
        });
        // The run, the outcome and the counts only: never a file name, an anomaly or a value.
        log.info("import run {} rehearsed: {}, matches the dry run {}", runId, outcome, matches);
        return new Rehearsal(id, outcome, failure, report, matches, Collections.unmodifiableMap(timings));
    }

    private static long millisSince(long nanos) {
        return (System.nanoTime() - nanos) / 1_000_000;
    }
}
