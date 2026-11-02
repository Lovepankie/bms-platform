package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.Documents.GeneratedObject;
import com.rincoltech.bms.core.documents.Documents.StoredDocument;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.reporting.ReportDefinition;
import com.rincoltech.bms.core.reporting.ReportDefinition.Prepared;
import com.rincoltech.bms.core.reporting.ReportFailure;
import com.rincoltech.bms.core.reporting.ReportPack;
import com.rincoltech.bms.core.reporting.internal.Renderers.Header;
import com.rincoltech.bms.core.reporting.internal.RunStore.Run;
import com.rincoltech.bms.core.reporting.internal.Spool.Guard;
import com.rincoltech.bms.core.reporting.internal.Spool.TableMeta;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.Letterheads;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.core.tenancy.TenantSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.PushNotice;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 *   <li><b>Snapshot time.</b> The business clock's time at the start of that transaction is recorded on the run.
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
    private final Letterheads letterheads;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final ObjectMapper mapper;
    private final ReportsProperties limits;
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactions;
    private final TransactionTemplate snapshotRead;
    private final TransactionTemplate plain;
    private final TransactionTemplate separate;
    private final Packs packs;
    private final Outbox outbox;
    private final PlatformHost hosts;
    private final ApplicationEventPublisher events;

    RunExecutor(
            RunStore store,
            ReportRegistry registry,
            TenantSettings settings,
            CurrentTenant tenant,
            Documents documents,
            Letterheads letterheads,
            AuditLog audit,
            BusinessClock clock,
            ObjectMapper mapper,
            ReportsProperties limits,
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            Packs packs,
            Outbox outbox,
            PlatformHost hosts,
            ApplicationEventPublisher events) {
        this.packs = packs;
        this.outbox = outbox;
        this.hosts = hosts;
        this.events = events;
        this.store = store;
        this.registry = registry;
        this.settings = settings;
        this.tenant = tenant;
        this.documents = documents;
        this.letterheads = letterheads;
        this.audit = audit;
        this.clock = clock;
        this.mapper = mapper;
        this.limits = limits;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.snapshotRead = snapshotRead(limits.timeout);
        this.plain = new TransactionTemplate(transactions);
        // The cancel poll must see other sessions' commits, so it never joins the snapshot transaction.
        this.separate = new TransactionTemplate(transactions);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.separate.setReadOnly(true);
    }

    /** One snapshot for every query of a run (the reports of a pack agree with each other), bounded by the run's limit. */
    private TransactionTemplate snapshotRead(Duration timeout) {
        TransactionTemplate read = new TransactionTemplate(transactions);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setTimeout((int) Math.max(1, timeout.toSeconds()));
        return read;
    }

    /** The run's limit: the report's own when it sets one, else the runner's. */
    private Duration timeout(ReportDefinition def) {
        return def.timeout() == null ? limits.timeout : def.timeout();
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
    private record Rendered(Path file, String contentType, String extension, String docNumber) {}

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
            RunGuard guard = new RunGuard(runId, timeout(def));
            if (def instanceof ReportPack pack) {
                runPack(run, pack, spool, guard);
                return;
            }
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
        ReportDefinition def = registry.find(run.reportKey())
                .orElseThrow(() -> new ReportFailure("report_unavailable", "The report is no longer offered."));
        // The business's own copy of its data is made whatever the reports switch says (ADR-074).
        if (!def.everyBusiness() && !Boolean.TRUE.equals(plain.execute(s -> settings.reportsEnabled()))) {
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
        return def;
    }

    private Queried query(Run run, ReportDefinition def, Spool spool, RunGuard guard) {
        Prepared prepared = new Prepared(run.params(), run.scope(), run.asOf());
        int rowCap = Math.min(run.rowCap(), limits.tableRowCap);
        long sizeCap = Math.min(run.sizeCapBytes(), limits.maxFileBytes);
        TransactionTemplate read = def.timeout() == null ? snapshotRead : snapshotRead(timeout(def));
        return read.execute(status -> {
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
            // The business clock, like every other timestamp of a run, so a test that pins the clock pins this too.
            Instant snapshotAt = clock.now();
            String currency = tenant.profile().currency();
            return queryInto(def, prepared, spool, rowCap, sizeCap, guard, snapshotAt, currency);
        });
    }

    // JUSTIFICATION-A3: the pack path of issue #229 (one snapshot over every part, two files, the ready notice) is new.
    /** One report's query into its spool, inside the caller's snapshot transaction. */
    private static Queried queryInto(
            ReportDefinition def,
            Prepared prepared,
            Spool spool,
            int rowCap,
            long sizeCap,
            RunGuard guard,
            Instant snapshotAt,
            String currency) {
        try (Spool.Writer out = spool.writer(rowCap, sizeCap, guard)) {
            def.query(prepared, out);
            List<TableMeta> tables = out.finish();
            return new Queried(tables, out.notes(), out.rows(), snapshotAt, currency);
        }
    }

    // ---- a pack (ADR-038 decision 4, issue #229) ------------------------------------------------------------

    /** One part of a pack after the query phase: its definition and request, its spool and what it wrote. */
    private record PartQueried(Packs.Part part, Spool spool, Queried queried) {}

    /**
     * A pack: every part is queried in the run's one snapshot transaction, each into a spool of its own, so the reports
     * agree with each other; then one combined PDF and one workbook are rendered from the spools, stored as two files of
     * the run, and the requester is told the pack is ready.
     */
    private void runPack(Run run, ReportPack pack, Spool spool, RunGuard guard) {
        Prepared stored = new Prepared(run.params(), run.scope(), run.asOf());
        List<Packs.Part> parts = packs.parts(stored);
        int rowCap = Math.min(run.rowCap(), limits.tableRowCap);
        long sizeCap = Math.min(run.sizeCapBytes(), limits.maxFileBytes);
        TransactionTemplate read = pack.timeout() == null ? snapshotRead : snapshotRead(timeout(pack));
        List<PartQueried> queried = read.execute(status -> {
            Duration remaining = guard.remaining();
            jdbc.sql(
                            "SELECT set_config('statement_timeout', ?, true), set_config('idle_in_transaction_session_timeout', ?, true)")
                    .params(
                            remaining.plusSeconds(1).toMillis() + "ms",
                            remaining.plusSeconds(30).toMillis() + "ms")
                    .query((rs, n) -> 1)
                    .single();
            Instant snapshotAt = clock.now();
            String currency = tenant.profile().currency();
            List<PartQueried> out = new ArrayList<>();
            for (Packs.Part part : parts) {
                Spool own = new Spool(spool.dir().resolve("part-" + part.number()), mapper);
                out.add(new PartQueried(
                        part,
                        own,
                        queryInto(part.def(), part.prepared(), own, rowCap, sizeCap, guard, snapshotAt, currency)));
            }
            return out;
        });
        Instant snapshotAt = queried.getFirst().queried().snapshotAt();
        String currency = queried.getFirst().queried().currency();
        long rows = queried.stream().mapToLong(q -> q.queried().rows()).sum();
        plain.executeWithoutResult(s -> store.recordSnapshot(run.id(), snapshotAt, clock.now()));

        Instant made = clock.now();
        List<String> notes = List.of(
                "Every report in this pack was read from the books at the same moment, so their figures agree.",
                "Each report keeps its own scope and notes; a report on dates in the month covers the month up to the"
                        + " period end.");
        Header header =
                new Header(pack.key(), pack.title(), run.params(), run.asOf(), snapshotAt, made, currency, notes);
        Presentation p = presentation(run.requestedBy(), pack, Packs.own(stored));
        String number = ReportFormats.reportNumber(run.id(), made, p.zone());
        List<PackPdf.Section> sections = new ArrayList<>();
        List<XlsxWriter.PackPart> sheets = new ArrayList<>();
        for (PartQueried q : queried) {
            ReportDefinition def = q.part().def();
            Prepared prepared = q.part().prepared();
            Header h = new Header(
                    def.key(),
                    def.title(),
                    prepared.params(),
                    prepared.asOf(),
                    snapshotAt,
                    made,
                    currency,
                    q.queried().notes());
            // A missing figure prints blank in every report of a pack, as the register and the summary print it alone
            // (ADR-038 amendment for #225): a board pack reads a dash as a figure.
            Presentation pp = presentation(run.requestedBy(), def, prepared).blankingMissing();
            sections.add(new PackPdf.Section(h, pp, q.queried().tables(), q.spool()));
            sheets.add(new XlsxWriter.PackPart(h, pp, q.queried().tables(), q.spool()));
        }
        Path pdf = spool.dir().resolve("pack.pdf");
        PackPdf.write(documents, limits, header, p, number, sections, spool.dir(), pdf, guard);
        guard.checkpoint();
        Path xlsx = spool.dir().resolve("pack.xlsx");
        XlsxWriter.writePack(xlsx, sizeCap, header, p, sheets, guard);
        guard.checkpoint();
        long size;
        try {
            if (Files.size(pdf) > sizeCap || Files.size(xlsx) > sizeCap) {
                throw new ReportFailure("report_too_large", "The pack is larger than the size limit.");
            }
            size = Files.size(pdf) + Files.size(xlsx);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        finishPack(run, pack, rows, size, number, pdf, xlsx);
    }

    /** Stores the pack's two files with no transaction open, then records both and marks the run ready in one. */
    private void finishPack(Run run, ReportPack pack, long rows, long size, String number, Path pdf, Path xlsx) {
        GeneratedObject pdfObject = documents.putFile(pdf, "report", "application/pdf", "pdf");
        GeneratedObject xlsxObject = null;
        boolean recorded = false;
        try {
            xlsxObject = documents.putFile(xlsx, "report", XLSX, "xlsx");
            GeneratedObject workbook = xlsxObject;
            Instant now = clock.now();
            Boolean ready = plain.execute(s -> {
                StoredDocument doc =
                        documents.recordGenerated(pdfObject, number, SUBJECT, run.id(), null, run.requestedBy());
                documents.recordGenerated(workbook, number, SUBJECT, run.id(), null, run.requestedBy());
                boolean marked = store.markReady(
                        run.id(), doc.id(), (int) rows, size, now, now.plus(Duration.ofDays(limits.retentionDays)));
                if (!marked) {
                    s.setRollbackOnly();
                    return false;
                }
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("report_key", pack.key());
                after.put("format", run.format());
                after.put("as_of", run.asOf().toString());
                after.put("row_count", rows);
                after.put("size_bytes", size);
                audit.record(
                        AuditLog.Entry.created(AUDIT_FINISHED, RunService.ENTITY, run.id(), null, after),
                        run.requestedBy(),
                        "system");
                tellReady(run, pack);
                return true;
            });
            recorded = Boolean.TRUE.equals(ready);
            if (!recorded) {
                throw new Cancelled();
            }
            log.info("report pack run {} of {} ready: {} rows, {} bytes", run.id(), pack.key(), rows, size);
        } finally {
            if (!recorded) {
                documents.discardGenerated(pdfObject);
                if (xlsxObject != null) {
                    documents.discardGenerated(xlsxObject);
                }
            }
        }
    }

    /**
     * The ready notice of a pack (ADR-038 decision 7, FR-RPT-04), written in the transaction that marks the run ready, so
     * it exists exactly when the run is ready: a push notice to the requester's devices (sent only when push is on and they
     * have one) and an email to their address (sent when email is configured). Neither carries a figure or a name.
     */
    private void tellReady(Run run, ReportPack pack) {
        String asOf = ReportFormats.date(run.asOf());
        events.publishEvent(new PushNotice(
                "core.report_pack_ready",
                run.id().toString(),
                new PushNotice.Users(java.util.Set.of(run.requestedBy())),
                Map.of("report_title", pack.title(), "as_of", asOf, "path", "/staff/reports")));
        jdbc.sql("""
                        SELECT coalesce(full_name, 'there') AS full_name, email FROM users
                         WHERE id = ? AND status = 'active' AND email IS NOT NULL
                        """)
                .param(run.requestedBy())
                .query((rs, n) -> Map.of("name", rs.getString("full_name"), "email", rs.getString("email")))
                .optional()
                .ifPresent(user -> outbox.enqueue(new Outbox.Message(
                        Outbox.EMAIL,
                        user.get("email"),
                        "core.report_pack_ready",
                        Map.of(
                                "name",
                                user.get("name"),
                                "report_title",
                                pack.title().toLowerCase(java.util.Locale.ENGLISH),
                                "as_of",
                                asOf,
                                "link",
                                hosts.tenantOrigin(tenant.profile().slug()) + "/staff/reports"),
                        "core.report_pack_ready:" + run.id(),
                        null,
                        null)));
    }

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** The format of a stored report file, from its content type. */
    static String formatOf(String contentType) {
        return switch (contentType == null ? "" : contentType) {
            case "application/pdf" -> "pdf";
            case XLSX -> "xlsx";
            case "text/csv" -> "csv";
            default -> "json";
        };
    }

    private Rendered render(Run run, ReportDefinition def, Spool spool, Queried q, RunGuard guard) {
        Instant made = clock.now();
        Header header = new Header(
                def.key(), def.title(), run.params(), run.asOf(), q.snapshotAt(), made, q.currency(), q.notes());
        long sizeCap = Math.min(run.sizeCapBytes(), limits.maxFileBytes);
        Path target = spool.dir().resolve("output");
        Rendered rendered = switch (run.format()) {
            case "json" -> {
                Renderers.json(mapper, spool, header, q.tables(), target, sizeCap, guard);
                yield new Rendered(target, "application/json", "json", null);
            }
            case "csv" -> {
                Renderers.csv(spool, header, q.tables(), target, sizeCap, guard);
                yield new Rendered(target, "text/csv", "csv", null);
            }
            case "xlsx" -> {
                // Everything the file says around the figures is read first, in one short transaction; the rendering
                // that follows holds no connection (decision 2).
                Presentation p = presentation(run, def);
                XlsxWriter.write(target, sizeCap, header, p, q.tables(), spool, guard);
                yield new Rendered(
                        target,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "xlsx",
                        ReportFormats.reportNumber(run.id(), made, p.zone()));
            }
            case "pdf" -> {
                Presentation p = presentation(run, def);
                String number = ReportFormats.reportNumber(run.id(), made, p.zone());
                PdfReport.write(documents, limits, header, p, number, q.tables(), spool, target, guard);
                yield new Rendered(target, "application/pdf", "pdf", number);
            }
            default -> throw new ReportFailure("format_unavailable", "This build cannot render " + run.format() + ".");
        };
        try {
            if (Files.size(rendered.file()) > sizeCap) {
                throw new ReportFailure("report_too_large", "The report is larger than the size limit.");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rendered;
    }

    // JUSTIFICATION-A3: the PDF and Excel renderers (issue #217) need the letterhead and the requester's name; nothing
    // here read them.
    /** The letterhead, the requester's name and the time zone, read in one short transaction before rendering. */
    private Presentation presentation(Run run, ReportDefinition def) {
        return presentation(run.requestedBy(), def, new Prepared(run.params(), run.scope(), run.asOf()));
    }

    /** As above for one report of a pack, or the pack itself, from what it was prepared with. */
    private Presentation presentation(UUID requestedBy, ReportDefinition def, Prepared prepared) {
        return plain.execute(s -> {
            String by = jdbc.sql("SELECT full_name FROM users WHERE id = ?")
                    .param(requestedBy)
                    .query(String.class)
                    .optional()
                    .orElse("Staff");
            String scope = def.describeScope(prepared);
            return Presentation.of(
                    letterheads.current(), by, tenant.profile().timezone(), def, prepared.params(), scope);
        });
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
                StoredDocument doc = documents.recordGenerated(
                        object, rendered.docNumber(), SUBJECT, run.id(), null, run.requestedBy());
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
