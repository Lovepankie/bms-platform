package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.Documents.GeneratedObject;
import com.rincoltech.bms.core.documents.Documents.StoredDocument;
import com.rincoltech.bms.core.reporting.ReportDefinition;
import com.rincoltech.bms.core.reporting.ReportDefinition.Prepared;
import com.rincoltech.bms.core.reporting.ReportFailure;
import com.rincoltech.bms.core.reporting.internal.Renderers.Header;
import com.rincoltech.bms.core.reporting.internal.RunStore.Run;
import com.rincoltech.bms.core.reporting.internal.Spool.Guard;
import com.rincoltech.bms.core.reporting.internal.Spool.TableMeta;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Executes one claimed run (ADR-038 decisions 2, 5 and 7), on a worker thread with the run's tenant bound and no
 * transaction open. Five steps, each its own transaction or none:
 *
 * <ol>
 *   <li><b>Query.</b> One read-only {@code REPEATABLE READ} transaction under the tenant's row-level security: the
 *       report's {@link ReportDefinition#query} writes its rows to the spool on disk, one at a time. The
 *       transaction then <b>commits</b>, so the snapshot (and the old row versions it pins) is released before
 *       anything slow happens. Every row of a run comes from this one snapshot.
 *   <li><b>Snapshot time.</b> The database's own start time of that transaction is recorded on the run.
 *   <li><b>Render.</b> The JSON or CSV file is streamed from the spool to a file, capped in size.
 *   <li><b>Store.</b> The object is written to storage without a transaction open, then one short transaction
 *       records the document and marks the run ready.
 *   <li><b>Clean up.</b> The spool directory is removed whatever happened.
 * </ol>
 *
 * A failure ends the run {@code failed} with a stable code; the message of the exception never reaches the row, the
 * audit log or the log (it could carry a value), only its class names and the application frames.
 */
@Service
class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    static final String AUDIT_FINISHED = "core.reports.run_finished";
    static final String AUDIT_FAILED = "core.reports.run_failed";
    static final String SUBJECT = "core.report_run";

    private final RunStore store;
    private final ReportRegistry registry;
    private final TenantSettings settings;
    private final CurrentTenant tenant;
    private final Documents documents;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final ObjectMapper mapper;
    private final ReportsProperties limits;
    private final JdbcClient jdbc;
    private final TransactionTemplate snapshotRead;
    private final TransactionTemplate plain;
    private final TransactionTemplate separate;

    RunExecutor(
            RunStore store,
            ReportRegistry registry,
            TenantSettings settings,
            CurrentTenant tenant,
            Documents documents,
            AuditLog audit,
            BusinessClock clock,
            ObjectMapper mapper,
            ReportsProperties limits,
            JdbcClient jdbc,
            PlatformTransactionManager transactions) {
        this.store = store;
        this.registry = registry;
        this.settings = settings;
        this.tenant = tenant;
        this.documents = documents;
        this.audit = audit;
        this.clock = clock;
        this.mapper = mapper;
        this.limits = limits;
        this.jdbc = jdbc;
        // One snapshot for every query of a run: the reports of a pack agree with each other.
        this.snapshotRead = new TransactionTemplate(transactions);
        this.snapshotRead.setReadOnly(true);
        this.snapshotRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotRead.setTimeout((int) Math.max(1, limits.timeout.toSeconds()));
        this.plain = new TransactionTemplate(transactions);
        // The cancel poll must see other sessions' commits, so it never joins the snapshot transaction.
        this.separate = new TransactionTemplate(transactions);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.separate.setReadOnly(true);
    }

    /** Raised when the run was cancelled: not a failure. */
    private static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("cancelled", null, false, false);
        }
    }

    /** What the query phase leaves behind. */
    private record Queried(
            List<TableMeta> tables, List<String> notes, long rows, Instant snapshotAt, String currency) {}

    /** A rendered file waiting to be stored. */
    private record Rendered(Path file, String contentType, String extension) {}

    /** The run's deadline and its cancel check, called from wherever rows are written or read. */
    private final class RunGuard implements Guard {

        private final UUID runId;
        private final long deadline;
        private long lastPoll = System.nanoTime();

        RunGuard(UUID runId, Duration timeout) {
            this.runId = runId;
            this.deadline = System.nanoTime() + timeout.toNanos();
        }

        @Override
        public void checkpoint() {
            if (Thread.currentThread().isInterrupted()) {
                throw new ReportFailure("interrupted", "The worker was interrupted.");
            }
            long now = System.nanoTime();
            if (now - deadline > 0) {
                throw new ReportFailure("timeout", "The run took longer than its limit.");
            }
            if (now - lastPoll > limits.cancelPoll.toNanos()) {
                lastPoll = now;
                if (Boolean.TRUE.equals(separate.execute(s -> store.shouldStop(runId)))) {
                    throw new Cancelled();
                }
            }
        }

        Duration remaining() {
            return Duration.ofNanos(Math.max(Duration.ofSeconds(1).toNanos(), deadline - System.nanoTime()));
        }
    }

    // ---- the run ------------------------------------------------------------------------------

    /** Runs the claimed run to its end. Never throws: whatever goes wrong is the run's own status. */
    void execute(UUID runId) {
        Run run = plain.execute(s -> store.find(runId)).orElse(null);
        if (run == null || !run.status().equals("running")) {
            return;
        }
        Spool spool = new Spool(limits.spoolDir.resolve(TenantContext.require() + "-" + runId), mapper);
        try {
            ReportDefinition def = precheck(run);
            RunGuard guard = new RunGuard(runId, limits.timeout);
            Queried queried = query(run, def, spool, guard);
            plain.executeWithoutResult(s -> store.recordSnapshot(runId, queried.snapshotAt(), clock.now()));
            Rendered rendered = render(run, def, spool, queried, guard);
            guard.checkpoint();
            finish(run, def, queried, rendered);
        } catch (Cancelled c) {
            end(run, null, true);
        } catch (ReportFailure f) {
            end(run, f.code(), false);
        } catch (ApiException e) {
            // A rule the report states in its own words and with a stable code; the code is all that is kept.
            end(run, e.code().matches("^[a-z][a-z0-9_]{2,60}$") ? e.code() : "run_failed", false);
        } catch (Throwable t) {
            // A thread that was interrupted (the process is stopping) did not fail by its own fault.
            boolean stopping = Thread.currentThread().isInterrupted();
            if (!stopping) {
                log.error("report run {} failed: {}", runId, describe(t));
            }
            end(run, timedOut(t) ? "timeout" : stopping ? "interrupted" : "run_failed", false);
        } finally {
            spool.delete();
        }
    }

    /** Refuses a run that should not start: reports switched off, requester deactivated, report gone. */
    private ReportDefinition precheck(Run run) {
        if (!Boolean.TRUE.equals(plain.execute(s -> settings.reportsEnabled()))) {
            throw new ReportFailure("reports_disabled", "Reports were switched off.");
        }
        Boolean deactivated = plain.execute(
                s -> jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = ? AND status = 'deactivated')")
                        .param(run.requestedBy())
                        .query(Boolean.class)
                        .single());
        if (Boolean.TRUE.equals(deactivated)) {
            throw new ReportFailure("requester_deactivated", "The requester was deactivated.");
        }
        return registry.find(run.reportKey())
                .orElseThrow(() -> new ReportFailure("report_unavailable", "The report is no longer offered."));
    }

    private Queried query(Run run, ReportDefinition def, Spool spool, RunGuard guard) {
        Prepared prepared = new Prepared(run.params(), run.scope(), run.asOf());
        int rowCap = Math.min(run.rowCap(), limits.tableRowCap);
        long sizeCap = Math.min(run.sizeCapBytes(), limits.maxFileBytes);
        return snapshotRead.execute(status -> {
            // The connection's defaults (60 s statement, 60 s idle in transaction) suit a request, not a run that
            // reads a book and writes rows between statements: lift both to the run's remaining time, for this
            // transaction only. A margin keeps the server's limits behind the run's own deadline, so a run that is
            // over time ends with "timeout" from the application, not with a dropped connection.
            Duration remaining = guard.remaining();
            jdbc.sql(
                            "SELECT set_config('statement_timeout', ?, true), set_config('idle_in_transaction_session_timeout', ?, true)")
                    .params(
                            remaining.plusSeconds(1).toMillis() + "ms",
                            remaining.plusSeconds(30).toMillis() + "ms")
                    .query((rs, n) -> 1)
                    .single();
            Instant snapshotAt = jdbc.sql("SELECT transaction_timestamp()")
                    .query(Timestamp.class)
                    .single()
                    .toInstant();
            String currency = tenant.profile().currency();
            try (Spool.Writer out = spool.writer(rowCap, sizeCap, guard)) {
                def.query(prepared, out);
                List<TableMeta> tables = out.finish();
                return new Queried(tables, out.notes(), out.rows(), snapshotAt, currency);
            }
        });
    }

    private Rendered render(Run run, ReportDefinition def, Spool spool, Queried q, RunGuard guard) {
        Header header = new Header(
                def.key(), def.title(), run.params(), run.asOf(), q.snapshotAt(), clock.now(), q.currency(), q.notes());
        long sizeCap = Math.min(run.sizeCapBytes(), limits.maxFileBytes);
        Path target = spool.dir().resolve("output");
        return switch (run.format()) {
            case "json" -> {
                Renderers.json(mapper, spool, header, q.tables(), target, sizeCap, guard);
                yield new Rendered(target, "application/json", "json");
            }
            case "csv" -> {
                Renderers.csv(spool, header, q.tables(), target, sizeCap, guard);
                yield new Rendered(target, "text/csv", "csv");
            }
            default -> throw new ReportFailure("format_unavailable", "This build cannot render " + run.format() + ".");
        };
    }

    /** Stores the file with no transaction open, then records it and marks the run ready in one short one. */
    private void finish(Run run, ReportDefinition def, Queried q, Rendered rendered) {
        // Uploaded from the file: the output is never held in memory (the size cap bounds the disk, not the heap).
        long size;
        try {
            size = Files.size(rendered.file());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        GeneratedObject object =
                documents.putFile(rendered.file(), "report", rendered.contentType(), rendered.extension());
        boolean recorded = false;
        try {
            Instant now = clock.now();
            Boolean ready = plain.execute(s -> {
                StoredDocument doc =
                        documents.recordGenerated(object, null, SUBJECT, run.id(), null, run.requestedBy());
                boolean marked = store.markReady(
                        run.id(), doc.id(), (int) q.rows(), size, now, now.plus(Duration.ofDays(limits.retentionDays)));
                if (!marked) {
                    s.setRollbackOnly();
                    return false;
                }
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("report_key", def.key());
                after.put("format", run.format());
                after.put("as_of", run.asOf().toString());
                after.put("row_count", q.rows());
                after.put("size_bytes", size);
                audit.record(
                        AuditLog.Entry.created(AUDIT_FINISHED, RunService.ENTITY, run.id(), null, after),
                        run.requestedBy(),
                        "system");
                return true;
            });
            recorded = Boolean.TRUE.equals(ready);
            if (!recorded) {
                throw new Cancelled();
            }
            log.info("report run {} of {} ready: {} rows, {} bytes", run.id(), def.key(), q.rows(), size);
        } finally {
            if (!recorded) {
                documents.discardGenerated(object);
            }
        }
    }

    /** Ends a run that did not finish: failed with a code, or cancelled; audited. */
    private void end(Run run, String errorCode, boolean cancelled) {
        // A worker stopped by an interrupt still has its flag set, and a pooled connection cannot be taken with it set.
        boolean interrupted = Thread.interrupted();
        try {
            plain.executeWithoutResult(s -> {
                Instant now = clock.now();
                boolean changed =
                        cancelled ? store.markCancelled(run.id(), now) : store.markFailed(run.id(), errorCode, now);
                if (!changed) {
                    return;
                }
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("report_key", run.reportKey());
                if (!cancelled) {
                    after.put("error", errorCode);
                }
                audit.record(
                        AuditLog.Entry.created(
                                cancelled ? RunService.AUDIT_CANCEL : AUDIT_FAILED,
                                RunService.ENTITY,
                                run.id(),
                                null,
                                after),
                        run.requestedBy(),
                        "system");
            });
        } catch (RuntimeException e) {
            // The row stays running until the abandoned-run sweep fails it as interrupted.
            log.error("report run {} could not be closed: {}", run.id(), describe(e));
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The database cancelled a statement at its limit (SQLSTATE 57014) or the transaction passed its own deadline. */
    private static boolean timedOut(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof org.springframework.transaction.TransactionTimedOutException
                    || c instanceof java.sql.SQLException sql && "57014".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * What may be logged about a failure: the class names along the cause chain and the first application frames,
     * never a message (a database or parser message can quote a value).
     */
    static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 400; c = c.getCause()) {
            sb.append(c.getClass().getName());
            if (c instanceof java.sql.SQLException sql) {
                // The state code names the kind of database failure and carries no value.
                sb.append(" (sqlstate ").append(sql.getSQLState()).append(')');
            }
            sb.append(" < ");
        }
        Optional<StackTraceElement> frame = java.util.Arrays.stream(t.getStackTrace())
                .filter(e -> e.getClassName().startsWith("com.rincoltech.bms"))
                .findFirst();
        frame.ifPresent(f -> sb.append("at ")
                .append(f.getClassName())
                .append('.')
                .append(f.getMethodName())
                .append(':')
                .append(f.getLineNumber()));
        return sb.toString();
    }
}
