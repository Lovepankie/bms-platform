package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.LiveRecordProbe;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ImportRuns} over {@code import_runs}, {@code import_batches} and {@code platform_import_settings} (migration V114).
 *
 * <p>The commit is a sequence of transactions, never one: the start (guards, then {@code ready} to {@code committing}),
 * one per batch, and the finish. A batch takes {@code pg_advisory_xact_lock} on the business's import key before it reads
 * its own status, so two resumes of one run, or a resume racing a commit, write each batch once. The kill switch and the
 * halt are read with a fresh snapshot each time (read committed), so an operator's stop is seen within 1,000 rows.
 */
@Service
class ImportRunService implements ImportRuns {

    /** Rows between two reads of the kill switch and the halt inside a batch (spec section 5.4). */
    static final int CHECKPOINT_ROWS = 1_000;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final String RUN_COLUMNS = """
            id, status, version, cutover_at, halt_requested, allow_trading_tenant, allow_trading_set_by,
            allow_trading_approved_by, pre_import_dump_file, committing_at, committed_at""";

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final PlatformAuditLog platformAudit;
    private final List<LiveRecordProbe> probes;
    private final ObjectMapper mapper;

    ImportRunService(
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            AuditLog audit,
            PlatformAuditLog platformAudit,
            List<LiveRecordProbe> probes,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.audit = audit;
        this.platformAudit = platformAudit;
        this.probes = List.copyOf(probes);
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------------------------------------
    // The run and its states.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Run open(UUID createdBy) {
        return tx.execute(s -> {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO import_runs (id, tenant_id, created_by)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?)
                            """).params(id, createdBy).update();
            audit.record(AuditLog.Entry.created(
                    "core.import_run.opened", "import_run", id, null, Map.of("status", RunStates.DRAFT)));
            return find(id, false);
        });
    }

    @Override
    public Run get(UUID runId) {
        return tx.execute(s -> find(runId, false));
    }

    @Override
    public Run transition(UUID runId, String status) {
        if (!RunStates.known(status) || RunStates.COMMIT_ONLY.contains(status)) {
            throw invalidTransition("A run reaches " + status + " only through the commit.");
        }
        return tx.execute(s -> {
            Run run = find(runId, true);
            if (!RunStates.allowed(run.status(), status)) {
                throw invalidTransition("The run is " + run.status() + "; it cannot become " + status + ".");
            }
            setStatus(runId, status);
            audit(runId, "core.import_run.status_changed", Map.of("status", run.status()), Map.of("status", status));
            return find(runId, false);
        });
    }

    @Override
    public UUID addBatch(UUID runId, int seq) {
        return tx.execute(s -> {
            Run run = find(runId, true);
            requireBeforeCommit(run);
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                            INSERT INTO import_batches (id, tenant_id, run_id, seq)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?)
                            """).params(id, runId, seq).update();
            return id;
        });
    }

    @Override
    public Run recordPreImportDump(UUID runId, String file, Instant takenAt, String sha256) {
        if (file == null
                || file.isBlank()
                || file.length() > 255
                || takenAt == null
                || sha256 == null
                || !SHA256.matcher(sha256).matches()) {
            throw ApiException.rule(
                    "invalid_pre_import_dump", "The dump needs a file name, the time it was taken and its SHA-256.");
        }
        return tx.execute(s -> {
            requireBeforeCommit(find(runId, true));
            jdbc.sql("""
                            UPDATE import_runs SET pre_import_dump_file = ?, pre_import_dump_at = ?,
                                   pre_import_dump_sha256 = ?, version = version + 1, updated_at = now()
                             WHERE id = ?
                            """)
                    .params(file.strip(), Timestamp.from(takenAt), sha256, runId)
                    .update();
            audit(runId, "core.import_run.dump_recorded", Map.of(), Map.of("file", file.strip(), "sha256", sha256));
            return find(runId, false);
        });
    }

    @Override
    public Run requestHalt(UUID runId) {
        return tx.execute(s -> {
            Run run = find(runId, true);
            if (!RunStates.COMMITTING.equals(run.status())) {
                throw invalidTransition("Only a committing run can be stopped; this one is " + run.status() + ".");
            }
            jdbc.sql(
                            "UPDATE import_runs SET halt_requested = true, version = version + 1, updated_at = now() WHERE id = ?")
                    .param(runId)
                    .update();
            audit(runId, "core.import_run.halt_requested", Map.of(), Map.of("halt_requested", true));
            return find(runId, false);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // The trading tenant override: set by one operator, approved by another.
    // ---------------------------------------------------------------------------------------------

    @Override
    public Run allowTradingTenant(UUID runId, UUID platformUserId, String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 5 || why.length() > 500) {
            throw ApiException.rule("reason_required", "Say why this business may be imported into while it trades.");
        }
        return tx.execute(s -> {
            requireBeforeCommit(find(runId, true));
            // Setting it again clears an earlier approval: the checker approves the reason now on the run.
            jdbc.sql("""
                            UPDATE import_runs SET allow_trading_tenant = true, allow_trading_reason = ?,
                                   allow_trading_set_by = ?, allow_trading_approved_by = NULL,
                                   version = version + 1, updated_at = now()
                             WHERE id = ?
                            """).params(why, platformUserId, runId).update();
            audit.record(
                    new AuditLog.Entry(
                            "core.import_run.trading_override_set",
                            "import_run",
                            runId,
                            null,
                            Map.of(),
                            Map.of("allow_trading_tenant", true, "reason", why)),
                    platformUserId,
                    "platform");
            return find(runId, false);
        });
    }

    @Override
    public Run approveTradingTenant(UUID runId, UUID platformUserId) {
        return tx.execute(s -> {
            Run run = find(runId, true);
            requireBeforeCommit(run);
            if (!run.allowTradingTenant()) {
                throw invalidTransition("No trading override is set on this run.");
            }
            if (platformUserId.equals(run.allowTradingSetBy())) {
                throw new ApiException(
                        HttpStatus.FORBIDDEN,
                        "self_approval_forbidden",
                        "Self approval forbidden",
                        "The operator who set the override cannot approve it.");
            }
            jdbc.sql("""
                            UPDATE import_runs SET allow_trading_approved_by = ?, version = version + 1, updated_at = now()
                             WHERE id = ?
                            """).params(platformUserId, runId).update();
            audit.record(
                    new AuditLog.Entry(
                            "core.import_run.trading_override_approved",
                            "import_run",
                            runId,
                            null,
                            Map.of(),
                            Map.of("allow_trading_approved", true)),
                    platformUserId,
                    "platform");
            return find(runId, false);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // The commit.
    // ---------------------------------------------------------------------------------------------

    @Override
    public CommitOutcome commit(UUID runId, BatchWriter writer) {
        requireNoTransaction();
        List<String> trading;
        try {
            trading = tx.execute(s -> start(runId));
        } catch (DuplicateKeyException e) {
            // import_runs_one_committing: another run of this business is committing.
            throw importInProgress();
        }
        if (!trading.isEmpty()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "TENANT_TRADING",
                    "Business already trades",
                    "The tenant already has live transactions (" + String.join(", ", trading)
                            + "); an import needs the trading override.");
        }
        return writeBatches(runId, writer);
    }

    /**
     * The guards and the move to {@code committing}, in one transaction. Returns the areas that trade when the guard
     * refuses, so the refusal's audit row commits before the caller throws; empty when the run moved on.
     */
    private List<String> start(UUID runId) {
        Run run = find(runId, true);
        if (RunStates.COMMITTING.equals(run.status())) {
            throw importInProgress();
        }
        if (!RunStates.READY.equals(run.status())) {
            throw invalidTransition("Only a ready run can be committed; this one is " + run.status() + ".");
        }
        requireCommitPossible(run);
        List<String> trading = probes.stream()
                .filter(LiveRecordProbe::hasLiveRecords)
                .map(LiveRecordProbe::area)
                .sorted()
                .toList();
        if (!trading.isEmpty() && !run.tradingOverrideApproved()) {
            audit(
                    runId,
                    "core.import_run.commit_refused",
                    Map.of(),
                    Map.of("code", "TENANT_TRADING", "areas", trading));
            return trading;
        }
        setStatus(runId, RunStates.COMMITTING);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", RunStates.COMMITTING);
        if (!trading.isEmpty()) {
            after.put("trading_override_used", true);
            after.put("areas", trading);
        }
        audit(runId, "core.import_run.status_changed", Map.of("status", RunStates.READY), after);
        return List.of();
    }

    @Override
    public CommitOutcome resume(UUID runId, BatchWriter writer) {
        requireNoTransaction();
        tx.executeWithoutResult(s -> {
            Run run = find(runId, true);
            if (!RunStates.COMMITTING.equals(run.status())) {
                throw invalidTransition("Only a committing run can be resumed; this one is " + run.status() + ".");
            }
            requireCommitPossible(run);
            // The trading guard is not asked again: it was passed when the run started, and the batches this run
            // already committed are the business's records now.
            jdbc.sql(
                            "UPDATE import_runs SET halt_requested = false, version = version + 1, updated_at = now() WHERE id = ?")
                    .param(runId)
                    .update();
            audit(runId, "core.import_run.resumed", Map.of(), Map.of("halt_requested", false));
        });
        return writeBatches(runId, writer);
    }

    private CommitOutcome writeBatches(UUID runId, BatchWriter writer) {
        List<PendingBatch> pending = tx.execute(s -> jdbc.sql("""
                        SELECT id, seq FROM import_batches WHERE run_id = ? AND status = 'pending' ORDER BY seq
                        """)
                .param(runId)
                .query((rs, n) -> new PendingBatch(rs.getObject("id", UUID.class), rs.getInt("seq")))
                .list());
        for (PendingBatch batch : pending) {
            try {
                tx.executeWithoutResult(s -> writeBatch(runId, batch, writer));
            } catch (Halted e) {
                tx.executeWithoutResult(s -> audit(
                        runId,
                        "core.import_run.halted",
                        Map.of(),
                        Map.of("batch_seq", batch.seq(), "reason", e.reason)));
                return CommitOutcome.HALTED;
            }
        }
        tx.executeWithoutResult(s -> {
            Run run = find(runId, true);
            long left = jdbc.sql("SELECT count(*) FROM import_batches WHERE run_id = ? AND status = 'pending'")
                    .param(runId)
                    .query(Long.class)
                    .single();
            // A concurrent resume may have finished the run first.
            if (RunStates.COMMITTING.equals(run.status()) && left == 0) {
                setStatus(runId, RunStates.COMMITTED);
                audit(
                        runId,
                        "core.import_run.status_changed",
                        Map.of("status", RunStates.COMMITTING),
                        Map.of("status", RunStates.COMMITTED));
            }
        });
        return CommitOutcome.COMMITTED;
    }

    private void writeBatch(UUID runId, PendingBatch batch, BatchWriter writer) {
        jdbc.sql(
                        "SELECT pg_advisory_xact_lock(hashtextextended('core.imports:' || current_setting('app.tenant_id'), 0))")
                .query()
                .singleRow();
        String status = jdbc.sql("SELECT status FROM import_batches WHERE id = ? FOR UPDATE")
                .param(batch.id())
                .query(String.class)
                .single();
        if ("committed".equals(status)) {
            return;
        }
        stopIfRequested(runId);
        Map<String, Object> counts = writer.write(runId, batch.id(), batch.seq(), new RowCheckpoint(runId));
        jdbc.sql("""
                        UPDATE import_batches SET status = 'committed', committed_at = now(), counts = CAST(? AS jsonb)
                         WHERE id = ?
                        """)
                .params(mapper.writeValueAsString(counts == null ? Map.of() : counts), batch.id())
                .update();
    }

    /** Throws {@link Halted} when the kill switch is off or the run's halt is set, read with a fresh snapshot. */
    private void stopIfRequested(UUID runId) {
        String reason =
                jdbc.sql("""
                        SELECT CASE WHEN NOT s.commit_enabled THEN 'kill_switch'
                                    WHEN r.halt_requested THEN 'halt_requested' END
                          FROM import_runs r CROSS JOIN platform_import_settings s
                         WHERE r.id = ? AND s.id = 1
                        """).param(runId).query(String.class).optional().orElse(null);
        if (reason != null) {
            throw new Halted(reason);
        }
    }

    private final class RowCheckpoint implements BatchWriter.Checkpoint {

        private final UUID runId;
        private long rows;

        RowCheckpoint(UUID runId) {
            this.runId = runId;
        }

        @Override
        public void rowWritten() {
            if (++rows % CHECKPOINT_ROWS == 0) {
                stopIfRequested(runId);
            }
        }
    }

    /** Stops a batch and rolls it back; the run stays committing. */
    private static final class Halted extends RuntimeException {

        final String reason;

        Halted(String reason) {
            super("import halted: " + reason, null, false, false);
            this.reason = reason;
        }
    }

    private record PendingBatch(UUID id, int seq) {}

    // ---------------------------------------------------------------------------------------------
    // The kill switch.
    // ---------------------------------------------------------------------------------------------

    @Override
    public boolean commitEnabled() {
        return jdbc.sql("SELECT commit_enabled FROM platform_import_settings WHERE id = 1")
                .query(Boolean.class)
                .single();
    }

    @Override
    public void setCommitEnabled(boolean enabled, UUID platformUserId, String reason) {
        String why = reason == null ? "" : reason.strip();
        if (why.length() < 5 || why.length() > 300) {
            throw ApiException.rule("reason_required", "Say why the import commit is switched on or off.");
        }
        tx.executeWithoutResult(s -> {
            Boolean previous = jdbc.sql("SELECT platform_set_import_commit_enabled(?, ?)")
                    .params(enabled, platformUserId)
                    .query(Boolean.class)
                    .single();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("before", Map.of("commit_enabled", previous));
            data.put("after", Map.of("commit_enabled", enabled));
            data.put("reason", why);
            platformAudit.record("platform.imports.commit_enabled_changed", platformUserId, null, data);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------------

    private Run find(UUID runId, boolean forUpdate) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM import_runs WHERE id = ?" + (forUpdate ? " FOR UPDATE" : ""))
                .param(runId)
                .query(ImportRunService::run)
                .optional()
                .orElseThrow(ApiException::notFound);
    }

    private void setStatus(UUID runId, String status) {
        jdbc.sql("UPDATE import_runs SET status = ?, " + RunStates.stampColumn(status)
                        + " = now(), version = version + 1, updated_at = now() WHERE id = ?")
                .params(status, runId)
                .update();
    }

    private void requireBeforeCommit(Run run) {
        if (!List.of(RunStates.DRAFT, RunStates.UPLOADED, RunStates.IN_REVIEW, RunStates.READY)
                .contains(run.status())) {
            throw invalidTransition("The run is " + run.status() + "; it can no longer be changed.");
        }
    }

    private void requireCommitPossible(Run run) {
        if (!commitEnabled()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "imports_disabled",
                    "Imports are switched off",
                    "The platform's import commit is switched off; ask the operator.");
        }
        if (run.preImportDumpFile() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "pre_import_dump_missing",
                    "No pre-import dump",
                    "Record the verified dump taken before the import; without it there is no way back.");
        }
    }

    private void audit(UUID runId, String action, Map<String, Object> before, Map<String, Object> after) {
        audit.record(new AuditLog.Entry(action, "import_run", runId, null, before, after));
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An import commit takes one transaction per batch; call it outside one");
        }
        TenantContext.require();
    }

    private static ApiException importInProgress() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "IMPORT_IN_PROGRESS",
                "Import in progress",
                "Another import is being committed for this business; wait for it or stop it.");
    }

    private static ApiException invalidTransition(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "invalid_status_transition", "Invalid status transition", detail);
    }

    private static Run run(ResultSet rs, int n) throws SQLException {
        return new Run(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getInt("version"),
                instant(rs, "cutover_at"),
                rs.getBoolean("halt_requested"),
                rs.getBoolean("allow_trading_tenant"),
                rs.getObject("allow_trading_set_by", UUID.class),
                rs.getObject("allow_trading_approved_by", UUID.class),
                rs.getString("pre_import_dump_file"),
                instant(rs, "committing_at"),
                instant(rs, "committed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
