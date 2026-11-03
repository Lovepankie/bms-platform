package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.audit.PlatformAuditLog;
import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.ImportRuns;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.LiveRecordProbe;
import com.rincoltech.bms.kernel.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ImportRuns} over {@code import_runs}, {@code import_batches} and {@code platform_import_settings} (migration V127).
 *
 * <p>The commit is a sequence of transactions, never one: the start (guards, then {@code ready} to {@code committing}),
 * one per batch, and the finish. A batch takes {@code pg_advisory_xact_lock} on the business's import key before it reads
 * its own status, so two resumes of one run, or a resume racing a commit, write each batch once. The kill switch and the
 * halt are read with a fresh snapshot each time (read committed), so an operator's stop is seen within 1,000 rows.
 *
 * <p>The commit loop itself has an owner (issue #779): a commit or a resume first takes {@code pg_try_advisory_lock}, a
 * session lock, on the business's loop key, on a connection of its own that it holds outside every transaction until
 * the loop ends. While that lock is held, a second commit or resume of the business is refused with
 * {@code IMPORT_IN_PROGRESS}, also in the gap between two batches, so an operator's halt can never be cleared under a
 * live loop. The lock is the loop's heartbeat: PostgreSQL drops it when the loop's connection or process dies, so a
 * crashed loop never blocks a resume, and no column is needed.
 *
 * <p>The loop also checks its own claim before each batch (issue #789): if the claim connection died, or its session
 * no longer holds the loop key (an idle timeout, or a NAT or firewall that dropped the idle connection), a resume may
 * already own the business, so the loop writes nothing more, audits {@code core.import_run.claim_lost} and ends
 * {@code HALTED} with the run still {@code committing} and resumable.
 */
@Service
class ImportRunService implements ImportRuns {

    /** Rows between two reads of the kill switch and the halt inside a batch (spec section 5.4). */
    static final int CHECKPOINT_ROWS = 1_000;

    /** The business's import key: the advisory lock every batch takes. */
    private static final String LOCK_KEY = "hashtextextended('core.imports:' || current_setting('app.tenant_id'), 0)";

    /** The business's loop key, followed by the business's id: the session lock the whole commit loop holds. */
    private static final String LOOP_KEY = "core.imports.loop:";

    /** True while this session holds the loop key: a bigint advisory key is split over classid and objid. */
    private static final String CLAIM_HELD = """
            SELECT EXISTS (SELECT 1 FROM pg_locks
                            WHERE locktype = 'advisory' AND granted AND pid = pg_backend_pid() AND objsubid = 1
                              AND ((classid::bigint << 32) | objid::bigint) = hashtextextended(?, 0))""";

    /**
     * Milliseconds the probe of the claim may wait on the database before the claim counts as lost (issue #792). It is
     * the claim connection's network timeout, not a query timeout: pgjdbc cancels a query on a new socket and never
     * unblocks the read on a black-holed one, while a socket read that times out ends the connection.
     */
    private static final int CLAIM_PROBE_TIMEOUT_MILLIS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(ImportRunService.class);

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private static final String RUN_COLUMNS = """
            id, status, version, cutover_at, halt_requested, allow_trading_tenant, allow_trading_set_by,
            allow_trading_approved_by, pre_import_dump_file, committing_at, committed_at""";

    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final PlatformAuditLog platformAudit;
    private final List<LiveRecordProbe> probes;
    private final ObjectMapper mapper;

    ImportRunService(
            JdbcClient jdbc,
            DataSource dataSource,
            PlatformTransactionManager transactions,
            AuditLog audit,
            PlatformAuditLog platformAudit,
            List<LiveRecordProbe> probes,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
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
            throw invalidTransition(
                    RunStates.known(status)
                            ? "A run is " + RunStates.words(status) + " only through the import itself."
                            : "That is not a state an import can be in.");
        }
        return tx.execute(s -> {
            Run run = find(runId, true);
            if (!RunStates.allowed(run.status(), status)) {
                throw invalidTransition("The import is " + RunStates.words(run.status()) + ", so it cannot be "
                        + RunStates.words(status) + " now.");
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
                throw invalidTransition("Only an import being imported can be stopped; this one is "
                        + RunStates.words(run.status()) + ".");
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
        try (LoopClaim claim = claimLoop()) {
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
            return writeBatches(runId, writer, claim);
        }
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
            throw invalidTransition(
                    "Only an import that is ready can start; this one is " + RunStates.words(run.status()) + ".");
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
        // A live commit loop owns the business's loop lock, inside a batch and between two: going on now would clear
        // the operator's halt under it, and the halt would be lost (reviews of #736, issue #779).
        try (LoopClaim claim = claimLoop()) {
            tx.executeWithoutResult(s -> {
                Run run = find(runId, true);
                if (!RunStates.COMMITTING.equals(run.status())) {
                    throw invalidTransition("Only an import being imported can go on; this one is "
                            + RunStates.words(run.status()) + ".");
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
            return writeBatches(runId, writer, claim);
        }
    }

    /**
     * Claims the business's commit loop: {@code pg_try_advisory_lock} on the loop key, on a connection of its own, in
     * autocommit so it never sits idle in a transaction. Refused with {@code IMPORT_IN_PROGRESS} when another loop of the
     * business holds it, on this instance or another.
     */
    private LoopClaim claimLoop() {
        String key = LOOP_KEY + TenantContext.require();
        Connection connection = DataSourceUtils.getConnection(dataSource);
        boolean claimed;
        try {
            connection.setAutoCommit(true);
            try (PreparedStatement claim =
                    connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
                claim.setString(1, key);
                try (ResultSet rs = claim.executeQuery()) {
                    claimed = rs.next() && rs.getBoolean(1);
                }
            }
        } catch (SQLException e) {
            DataSourceUtils.releaseConnection(connection, dataSource);
            throw new DataAccessResourceFailureException("The import's commit loop could not be claimed", e);
        }
        if (!claimed) {
            DataSourceUtils.releaseConnection(connection, dataSource);
            throw importInProgress();
        }
        return new LoopClaim(connection, key);
    }

    /** The claimed loop; closing it unlocks the loop key and gives the connection back. */
    private final class LoopClaim implements AutoCloseable {

        private final Connection connection;
        private final String key;

        LoopClaim(Connection connection, String key) {
            this.connection = connection;
            this.key = key;
        }

        /**
         * Asks the claim's own session whether it still holds the loop key. Null while it does; otherwise why the
         * claim is lost: {@code claim_connection_lost} when the probe fails, {@code claim_not_held} when it answers no.
         */
        String lostReason() {
            return probeClaim(connection, key, CLAIM_PROBE_TIMEOUT_MILLIS);
        }

        @Override
        public void close() {
            try (PreparedStatement unlock =
                    connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
                unlock.setString(1, key);
                unlock.execute();
            } catch (SQLException e) {
                // A session that could not unlock must not go back to the pool still holding the loop: end it.
                log.warn(
                        "import commit loop not unlocked, closing its connection: {}",
                        e.getClass().getSimpleName());
                try {
                    connection.abort(Runnable::run);
                } catch (SQLException ignored) {
                    // The connection is being thrown away either way.
                }
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        }
    }

    // JUSTIFICATION-A3: the probe body moved out of LoopClaim, static so issue #792's stalled read test can reach it.
    /**
     * The probe of {@link LoopClaim#lostReason()}: every read on the connection is bounded by {@code timeoutMillis}
     * through its network timeout, and the previous network timeout is put back afterwards, on every path. A timeout
     * that cannot be put back means the connection is closed or broken (a read that times out ends it), so the claim
     * counts as {@code claim_connection_lost} whatever the probe read.
     */
    static String probeClaim(Connection connection, String key, int timeoutMillis) {
        int previous;
        try {
            previous = connection.getNetworkTimeout();
            connection.setNetworkTimeout(Runnable::run, timeoutMillis);
        } catch (SQLException e) {
            return "claim_connection_lost";
        }
        String reason;
        boolean restored = false;
        try (PreparedStatement probe = connection.prepareStatement(CLAIM_HELD)) {
            probe.setString(1, key);
            try (ResultSet rs = probe.executeQuery()) {
                reason = rs.next() && rs.getBoolean(1) ? null : "claim_not_held";
            }
        } catch (SQLException e) {
            reason = "claim_connection_lost";
        } finally {
            try {
                connection.setNetworkTimeout(Runnable::run, previous);
                restored = true;
            } catch (SQLException e) {
                // Closed or broken: the session and its loop key may be gone; the close of the claim discards it.
            }
        }
        return restored ? reason : "claim_connection_lost";
    }

    private CommitOutcome writeBatches(UUID runId, BatchWriter writer, LoopClaim claim) {
        List<PendingBatch> pending = tx.execute(s -> jdbc.sql("""
                        SELECT id, seq FROM import_batches WHERE run_id = ? AND status = 'pending' ORDER BY seq
                        """)
                .param(runId)
                .query((rs, n) -> new PendingBatch(rs.getObject("id", UUID.class), rs.getInt("seq")))
                .list());
        for (PendingBatch batch : pending) {
            // Without the claim a resume may already own the business: write nothing more, leave the run resumable.
            String lost = claim.lostReason();
            if (lost != null) {
                log.warn("import commit loop lost its claim before batch {}: {}", batch.seq(), lost);
                tx.executeWithoutResult(s -> audit(
                        runId,
                        "core.import_run.claim_lost",
                        Map.of(),
                        Map.of("batch_seq", batch.seq(), "reason", lost)));
                return CommitOutcome.HALTED;
            }
            try {
                tx.executeWithoutResult(s -> writeBatch(runId, batch, writer));
            } catch (Halted e) {
                tx.executeWithoutResult(s -> audit(
                        runId,
                        "core.import_run.halted",
                        Map.of(),
                        Map.of("batch_seq", batch.seq(), "reason", e.reason)));
                return CommitOutcome.HALTED;
            } catch (RuntimeException e) {
                // The batch rolled back as a whole and the run stays committing, so it can go on once fixed. The audit
                // says which batch failed and the kind of failure, never the message, which may quote the row's data.
                throw audited(
                        e,
                        () -> tx.executeWithoutResult(s -> audit(
                                runId,
                                "core.import_run.batch_failed",
                                Map.of(),
                                Map.of(
                                        "batch_seq",
                                        batch.seq(),
                                        "error",
                                        e.getClass().getSimpleName()))));
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
                // A halt pressed during the last batch, which ended before its next checkpoint, means nothing now.
                jdbc.sql("UPDATE import_runs SET halt_requested = false WHERE id = ? AND halt_requested")
                        .param(runId)
                        .update();
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
        jdbc.sql("SELECT pg_advisory_xact_lock(" + LOCK_KEY + ")").query().singleRow();
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

    /**
     * Runs the audit of a failed batch and returns the batch's failure to throw. When the audit fails too (a lost
     * connection fails both), its error is added to the failure as suppressed, so the cause is never replaced.
     */
    static RuntimeException audited(RuntimeException failure, Runnable audit) {
        try {
            audit.run();
        } catch (RuntimeException auditFailure) {
            failure.addSuppressed(auditFailure);
        }
        return failure;
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
            throw invalidTransition(
                    "The import is " + RunStates.words(run.status()) + ", so it can no longer be changed.");
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
