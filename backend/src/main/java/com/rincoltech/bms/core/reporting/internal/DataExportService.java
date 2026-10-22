package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.operations.Idempotency;
import com.rincoltech.bms.core.reporting.ReportDefinition;
import com.rincoltech.bms.core.reporting.internal.RunService.Requested;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The "Download my data" card of Business set-up (issue #334 step 1, ADR-074): its status (the weekly email switch, when the
 * business's data was last exported and emailed, the caller's newest workbook), the request for a workbook, and the switch.
 * The routes need {@code core.data_export.run} and nothing else: not the reports switch, not a module. The file itself is
 * fetched like any report file, through {@code POST /documents/{id}/download-url}, by the person it was made for.
 */
@Service
class DataExportService {

    static final String AUDIT_SETTINGS = "core.data_export.settings_changed";

    private final RunService runs;
    private final RunStore store;
    private final DataExportReport report;
    private final Idempotency idempotency;
    private final AuditLog audit;
    private final JdbcClient jdbc;
    private final CurrentTenant tenant;

    DataExportService(
            RunService runs,
            RunStore store,
            DataExportReport report,
            Idempotency idempotency,
            AuditLog audit,
            JdbcClient jdbc,
            CurrentTenant tenant) {
        this.runs = runs;
        this.store = store;
        this.report = report;
        this.idempotency = idempotency;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Transactional(readOnly = true)
    DataExportApi.Status status() {
        Principal principal = CurrentPrincipal.require();
        Instant emailed = jdbc.sql("SELECT max(finished_at) AS at FROM data_export_weekly WHERE status = 'emailed'")
                .query((rs, n) -> instant(rs.getTimestamp("at")))
                .single();
        return new DataExportApi.Status(
                weeklyEmail(),
                store.lastReadyAt(DataExportReport.KEY),
                emailed,
                store.latestOf(principal.userId(), DataExportReport.KEY)
                        .map(runs::view)
                        .orElse(null));
    }

    /** Queues a workbook for the caller; an identical one of theirs still queued or running is returned instead. */
    @Transactional
    Requested request(String idempotencyKey) {
        UUID requester = CurrentPrincipal.require().userId();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return queue(requester);
        }
        AtomicBoolean created = new AtomicBoolean();
        Idempotency.Outcome<ReportsApi.RunView> outcome = idempotency.once(
                idempotencyKey, "POST", "/api/v1/data-export/runs", Map.of(), ReportsApi.RunView.class, () -> {
                    Requested made = queue(requester);
                    created.set(made.created());
                    return made.run();
                });
        return new Requested(outcome.body(), created.get() && !outcome.replayed());
    }

    private Requested queue(UUID requester) {
        ReportDefinition.Prepared prepared = report.prepare(Map.of());
        return runs.insert(report, "xlsx", prepared, requester, true);
    }

    /** Turns the weekly email on or off; audited with before and after. */
    @Transactional
    DataExportApi.Status setWeeklyEmail(boolean on) {
        Principal principal = CurrentPrincipal.require();
        boolean before = weeklyEmail();
        if (before != on) {
            jdbc.sql("""
                            INSERT INTO data_export_settings (id, tenant_id, weekly_email, updated_by)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?)
                            ON CONFLICT (tenant_id)
                            DO UPDATE SET weekly_email = EXCLUDED.weekly_email, updated_by = EXCLUDED.updated_by,
                                          updated_at = now()
                            """).params(UUID.randomUUID(), on, principal.userId()).update();
            audit.record(new AuditLog.Entry(
                    AUDIT_SETTINGS,
                    "core.data_export_settings",
                    tenant.profile().id(),
                    null,
                    Map.of("weekly_email", before),
                    Map.of("weekly_email", on)));
        }
        return status();
    }

    /** The switch: on until a holder turns it off. */
    boolean weeklyEmail() {
        return jdbc.sql("SELECT weekly_email FROM data_export_settings")
                .query(Boolean.class)
                .optional()
                .orElse(true);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
