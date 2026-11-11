package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.imports.BatchWriter;
import com.rincoltech.bms.core.imports.CommitReport;
import com.rincoltech.bms.core.imports.ImportCommandRuns;
import com.rincoltech.bms.core.imports.ImportMappings;
import com.rincoltech.bms.core.imports.ImportRefs;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ImportedRecords;
import com.rincoltech.bms.kernel.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link ImportCommandRuns}: the runs of the {@code import-retail} command (issue #135), in the caller's transaction. Its
 * batches are written with the commit's own guards (the import key, {@code app.import_commit}, the imported marker,
 * {@code import_refs}, the opening journals and the end-of-batch journal check of {@link RefService}).
 */
@Service
class CommandRunService implements ImportCommandRuns {

    /** No person opened the run: the command runs on the host, as the platform operator's tool. */
    static final UUID NO_PERSON = new UUID(0, 0);

    /** How long a command run stays live after its last batch: longer than the gap between two of its files. */
    static final String LEASE = "15 minutes";

    private final JdbcClient jdbc;
    private final RefService refs;
    private final ImportMappings mappings;
    private final AuditLog audit;
    private final ObjectMapper mapper;

    CommandRunService(JdbcClient jdbc, RefService refs, ImportMappings mappings, AuditLog audit, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.refs = refs;
        this.mappings = mappings;
        this.audit = audit;
        this.mapper = mapper;
    }

    @Override
    public UUID open(boolean dryRun) {
        requireTransaction();
        if (!dryRun
                && !jdbc.sql("SELECT commit_enabled FROM platform_import_settings WHERE id = 1")
                        .query(Boolean.class)
                        .single()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "imports_disabled",
                    "Imports are switched off",
                    "The platform operator has switched imports off; nothing was written.");
        }
        // A batch being written holds the business's import key: another command (or a commit) is live, so this one
        // is refused at once, never cancels it (review of 97cb1be, finding 2).
        if (!jdbc.sql("SELECT pg_try_advisory_xact_lock(" + ImportRunService.LOCK_KEY + ")")
                .query(Boolean.class)
                .single()) {
            throw importInProgress();
        }
        // Between two batches the run's lease is its last batch: a command run that wrote within LEASE is live and
        // this one is refused. One left committing for longer was stopped by a killed process; it is cancelled, its
        // committed batches stay, and this run recognises what they wrote.
        boolean live =
                jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM import_runs
                                        WHERE tenant_id = current_setting('app.tenant_id')::uuid AND origin = ?
                                          AND status = 'committing' AND updated_at > now() - CAST(? AS interval))
                        """).params(IMPORT_RETAIL, LEASE).query(Boolean.class).single();
        if (live) {
            throw importInProgress();
        }
        cancelExpired(Map.of("status", "cancelled"));
        UUID run = UUID.randomUUID();
        try {
            jdbc.sql("""
                            INSERT INTO import_runs (id, tenant_id, created_by, status, committing_at, origin)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, 'committing', now(), ?)
                            """).params(run, NO_PERSON, IMPORT_RETAIL).update();
        } catch (DuplicateKeyException e) {
            throw importInProgress();
        }
        audit.record(
                AuditLog.Entry.created(
                        "core.import_run.opened", "import_run", run, null, Map.of("origin", IMPORT_RETAIL)),
                null,
                "system");
        return run;
    }

    @Override
    public UUID batch(UUID runId, int seq, String file, UUID mappingId, String source) {
        requireTransaction();
        requireCommitting(runId);
        UUID batch = UUID.randomUUID();
        if (mappingId == null) {
            // A file of the command outside the template (the cash book's): a batch without a mapping.
            jdbc.sql("""
                            INSERT INTO import_batches (id, tenant_id, run_id, seq, source_filename)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?)
                            """).params(batch, runId, seq, file).update();
            return batch;
        }
        ImportMappings.Mapping mapping = mappings.get(mappingId);
        if (!MappingValidator.sources(mapping.document()).containsKey(source)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "SOURCE_UNKNOWN",
                    "Source unknown",
                    "The mapping has no source " + source + ".");
        }
        jdbc.sql("""
                        INSERT INTO import_batches (id, tenant_id, run_id, seq, source_filename, mapping_id, mapping_source,
                                                    template_key, template_version)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(batch, runId, seq, file, mapping.id(), source, mapping.templateKey(), mapping.templateVersion())
                .update();
        return batch;
    }

    @Override
    public Map<String, Object> write(
            UUID runId, UUID batchId, Function<BatchWriter.Checkpoint, Map<String, Object>> work) {
        requireTransaction();
        // Never a wait, as the commit's batch: whoever finds the business's import key taken is answered at once.
        if (!jdbc.sql("SELECT pg_try_advisory_xact_lock(" + ImportRunService.LOCK_KEY + ")")
                .query(Boolean.class)
                .single()) {
            throw importInProgress();
        }
        requireCommitting(runId);
        String file = jdbc.sql("""
                        SELECT source_filename FROM import_batches
                         WHERE id = ? AND run_id = ? AND status = 'pending' FOR UPDATE
                        """)
                .params(batchId, runId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("the command's batch is not pending"));
        // As a commit's batch: ten minutes a statement, nothing that sends a message, starts a task or raises an
        // approval (V143's triggers), and every event the batch publishes marked imported.
        jdbc.sql("SELECT set_config('statement_timeout', '10min', true)")
                .query()
                .singleRow();
        jdbc.sql("SET LOCAL app.import_commit = 'on'").update();
        ImportedRecords.markTransaction();
        CommandCheckpoint checkpoint = new CommandCheckpoint(runId, batchId, file);
        Map<String, Object> counts = work.apply(checkpoint);
        refs.requireOnlyOpeningJournals(runId, batchId);
        jdbc.sql("""
                        UPDATE import_batches SET status = 'committed', committed_at = now(), counts = CAST(? AS jsonb),
                               report = CAST(? AS jsonb)
                         WHERE id = ?
                        """)
                .params(
                        mapper.writeValueAsString(counts == null ? Map.of() : counts),
                        mapper.writeValueAsString(checkpoint.part.build(CommitReport.COMMIT)),
                        batchId)
                .update();
        // The run's lease: a batch written now keeps the run live for the next file.
        jdbc.sql("""
                        UPDATE import_runs SET updated_at = clock_timestamp()
                         WHERE id = ? AND tenant_id = current_setting('app.tenant_id')::uuid
                        """).param(runId).update();
        return counts;
    }

    @Override
    public void finish(UUID runId, boolean stopped) {
        requireTransaction();
        String status = stopped ? "cancelled" : "committed";
        int changed = jdbc.sql("UPDATE import_runs SET status = ?, " + (stopped ? "cancelled_at" : "committed_at")
                        + " = now(), updated_at = now(), version = version + 1"
                        + " WHERE id = ? AND tenant_id = current_setting('app.tenant_id')::uuid AND origin = ?"
                        + " AND status = 'committing'")
                .params(status, runId, IMPORT_RETAIL)
                .update();
        if (changed != 1 && stopped) {
            // Already ended (cancelled as stale by a later command): a stopped run has nothing more to record.
            return;
        }
        if (changed != 1) {
            throw new IllegalStateException("the command's run is not committing");
        }
        record(runId, "core.import_run.status_changed", "committing", status);
    }

    // JUSTIFICATION-A3: issue #876 (nice-to-have 3): a screens commit takes over a command run whose lease expired.
    /**
     * Before a run of the import screens moves to {@code committing}, in its transaction (issue #876): a run of the command
     * left {@code committing} past its lease ({@link #LEASE} after its last batch) was left by a killed process, so it
     * becomes {@code cancelled}, audited with the run that took it over, and its committed batches stay, as the next
     * command would have done. A claim is a lease: a command run that wrote within it is live and is never taken over (the
     * screens run is then refused by the one committing run per business), and neither is one writing a batch now, which
     * holds the business's import key: that is answered with 409 {@code IMPORT_IN_PROGRESS} at once.
     */
    void takeOverExpired(UUID screensRunId) {
        requireTransaction();
        boolean expired =
                jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM import_runs
                                        WHERE tenant_id = current_setting('app.tenant_id')::uuid AND origin = ?
                                          AND status = 'committing' AND updated_at <= now() - CAST(? AS interval))
                        """).params(IMPORT_RETAIL, LEASE).query(Boolean.class).single();
        if (!expired) {
            return;
        }
        if (!jdbc.sql("SELECT pg_try_advisory_xact_lock(" + ImportRunService.LOCK_KEY + ")")
                .query(Boolean.class)
                .single()) {
            throw importInProgress();
        }
        cancelExpired(Map.of("status", "cancelled", "taken_over_by", screensRunId.toString()));
    }

    /** Cancels the command's runs left {@code committing} past their lease, each audited with {@code after}. */
    private void cancelExpired(Map<String, Object> after) {
        List<UUID> left =
                jdbc.sql("""
                        UPDATE import_runs SET status = 'cancelled', cancelled_at = now(), updated_at = now(),
                               version = version + 1
                         WHERE tenant_id = current_setting('app.tenant_id')::uuid AND origin = ?
                           AND status = 'committing' AND updated_at <= now() - CAST(? AS interval)
                        RETURNING id
                        """).params(IMPORT_RETAIL, LEASE).query(UUID.class).list();
        left.forEach(id -> audit.record(
                new AuditLog.Entry(
                        "core.import_run.status_changed",
                        "import_run",
                        id,
                        null,
                        Map.of("status", "committing"),
                        after),
                null,
                "system"));
    }

    private void requireCommitting(UUID runId) {
        boolean open = jdbc.sql("SELECT EXISTS (SELECT 1 FROM import_runs WHERE id = ?"
                        + " AND tenant_id = current_setting('app.tenant_id')::uuid AND origin = ?"
                        + " AND status = 'committing')")
                .params(runId, IMPORT_RETAIL)
                .query(Boolean.class)
                .single();
        if (!open) {
            throw new IllegalStateException("the command's run is not committing");
        }
    }

    private void record(UUID runId, String action, String before, String after) {
        audit.record(
                new AuditLog.Entry(
                        action, "import_run", runId, null, Map.of("status", before), Map.of("status", after)),
                null,
                "system");
    }

    private static void requireTransaction() {
        TenantContext.require();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("the command's run is written inside the caller's transaction");
        }
    }

    private static ApiException importInProgress() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "IMPORT_IN_PROGRESS",
                "Import in progress",
                "Another import is being written for this business; try again when it ends.");
    }

    /** The checkpoint of a command batch: the framework's side of the commit, and the batch's part of the report. */
    private final class CommandCheckpoint implements BatchWriter.Checkpoint {

        private final UUID runId;
        private final UUID batchId;
        private final CommitReport.Builder part;

        CommandCheckpoint(UUID runId, UUID batchId, String file) {
            this.runId = runId;
            this.batchId = batchId;
            this.part = new CommitReport.Builder(file);
        }

        @Override
        public void rowWritten() {
            // The command never stopped between rows; the kill switch is read when its run opens.
        }

        @Override
        public List<ImportRefs.StagedRow> rows(int afterLine, int limit) {
            throw new UnsupportedOperationException("the import-retail command reads its own files");
        }

        @Override
        public void register(String sourceRef, String targetType, UUID targetId) {
            refs.register(runId, batchId, sourceRef, targetType, targetId);
        }

        @Override
        public String openingJournal(ImportRefs.Opening opening) {
            RefService.Posted posted = refs.openingJournal(runId, batchId, opening);
            journal(
                    opening.branchCode() == null ? opening.branchId().toString() : opening.branchCode(),
                    opening.kind(),
                    posted.debitMinor(),
                    posted.creditMinor(),
                    posted.status(),
                    posted.entryNo());
            return posted.status();
        }

        @Override
        public Optional<String> policy(String key) {
            // The command records no policy decision: its loader keeps its own rule for what the run does not decide.
            return Optional.empty();
        }

        @Override
        public void counts(String source, int read, int written, int existing, int skipped) {
            part.counts(source, read, written, existing, skipped);
        }

        @Override
        public void anomaly(int line, String message) {
            part.anomaly(line, message);
        }

        @Override
        public void anomaly(String other, int line, String message) {
            part.anomaly(other, line, message);
        }

        @Override
        public void journal(
                String branch, String kind, long debitMinor, long creditMinor, String status, String entryNo) {
            part.journal(branch, kind, debitMinor, creditMinor, status, entryNo);
        }

        @Override
        public void stock(String branch, BigDecimal qty, long valueMinor) {
            part.stock(branch, qty, valueMinor);
        }

        @Override
        public void receivable(String branch, long amountMinor) {
            part.receivable(branch, amountMinor);
        }

        @Override
        public void loanPrincipal(String branch, long amountMinor) {
            part.loanPrincipal(branch, amountMinor);
        }

        @Override
        public void arrears(String branch, String bucket, int loans, long principalMinor) {
            part.arrears(branch, bucket, loans, principalMinor);
        }

        @Override
        public void note(String note) {
            part.note(note);
        }

        @Override
        public void checksum(String line) {
            part.checksum(line);
        }
    }
}
