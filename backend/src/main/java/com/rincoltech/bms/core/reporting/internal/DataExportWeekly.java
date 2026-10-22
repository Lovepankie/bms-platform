package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.notifications.EmailAttachments;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.reporting.internal.RunService.Requested;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.Letterheads;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import com.rincoltech.bms.kernel.BusinessClock;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The weekly copy of the business's data (issue #334 step 1, ADR-074), the job {@code core.reporting.data-export-weekly}
 * every 15 minutes. For each active business, in two short transactions of its own:
 *
 * <ol>
 *   <li><b>Queue.</b> From 02:00 on Monday in the business's time zone, while the weekly email is on, the week's workbook is
 *       queued as an ordinary run of {@code core.data_export} requested for the Owner (ADR-062), and the week's row of
 *       {@code data_export_weekly} is written. The row is unique per business and week, so a second tick, a second instance
 *       or a restart queues nothing more. With no active Owner who has an email address the week is recorded as skipped.
 *   <li><b>Email.</b> A week whose run is ready is emailed to the Owner through the outbox, once (the row's id is the
 *       outbox key): the workbook attached when it is at most {@code bms.data-export.attach-max-bytes}, else a link to
 *       Business set-up, where the signed-in Owner downloads it until the file expires. A run that failed or was cancelled
 *       ends the week as failed with its code.
 * </ol>
 *
 * The runs themselves are made by the report runner like any other (one at a time per business, read only).
 */
@Component
class DataExportWeekly {

    private static final Logger log = LoggerFactory.getLogger(DataExportWeekly.class);

    static final String ATTACHED = "core.data_export_weekly";
    static final String LINKED = "core.data_export_weekly_link";

    /** The first moment of the week, in the business's time zone, at which the week's copy is made. */
    static final LocalTime FROM = LocalTime.of(2, 0);

    private final JdbcClient jdbc;
    private final TenantJobs tenantJobs;
    private final RunService runs;
    private final DataExportReport report;
    private final DataExportService exports;
    private final Outbox outbox;
    private final CurrentTenant tenant;
    private final Letterheads letterheads;
    private final PlatformHost hosts;
    private final BusinessClock clock;
    private final TransactionTemplate tx;
    private final long attachMaxBytes;

    DataExportWeekly(
            JdbcClient jdbc,
            TenantJobs tenantJobs,
            RunService runs,
            DataExportReport report,
            DataExportService exports,
            Outbox outbox,
            CurrentTenant tenant,
            Letterheads letterheads,
            PlatformHost hosts,
            BusinessClock clock,
            PlatformTransactionManager transactions,
            @Value("${bms.data-export.attach-max-bytes:5242880}") long attachMaxBytes) {
        this.jdbc = jdbc;
        this.tenantJobs = tenantJobs;
        this.runs = runs;
        this.report = report;
        this.exports = exports;
        this.outbox = outbox;
        this.tenant = tenant;
        this.letterheads = letterheads;
        this.hosts = hosts;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.attachMaxBytes = attachMaxBytes;
    }

    /** One pass over every active business; returns how many emails it queued. */
    int tick() {
        int emailed = 0;
        int failures = 0;
        for (UUID tenantId :
                jdbc.sql("SELECT app_list_active_tenants()").query(UUID.class).list()) {
            try {
                emailed += tenantJobs.callAs(tenantId, () -> {
                    tx.executeWithoutResult(this::queueWeek);
                    Integer sent = tx.execute(s -> deliver());
                    return sent == null ? 0 : sent;
                });
            } catch (RuntimeException e) {
                failures++;
                log.error("weekly data export failed for tenant {}: {}", tenantId, RunExecutor.describe(e));
            }
        }
        if (failures > 0) {
            throw new IllegalStateException("weekly data export failed for " + failures + " tenant(s)");
        }
        return emailed;
    }

    /** The Monday that starts the business week holding {@code at}. */
    static LocalDate weekOf(ZonedDateTime at) {
        return at.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private record Owner(UUID id, String name, String email) {}

    private Optional<Owner> owner(UUID only) {
        return jdbc.sql("""
                        SELECT id, full_name, email FROM users
                         WHERE is_owner AND status = 'active' AND email IS NOT NULL AND (CAST(? AS uuid) IS NULL OR id = ?)
                         ORDER BY id LIMIT 1
                        """)
                .params(only, only)
                .query((rs, n) ->
                        new Owner(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("email")))
                .optional();
    }

    private void queueWeek(TransactionStatus status) {
        if (!exports.weeklyEmail()) {
            return;
        }
        ZonedDateTime now = clock.now().atZone(tenant.profile().timezone());
        if (now.getDayOfWeek() == DayOfWeek.MONDAY && now.toLocalTime().isBefore(FROM)) {
            return;
        }
        LocalDate week = weekOf(now);
        boolean done = jdbc.sql("SELECT EXISTS (SELECT 1 FROM data_export_weekly WHERE week_of = ?)")
                .param(week)
                .query(Boolean.class)
                .single();
        if (done) {
            return;
        }
        Optional<Owner> owner = owner(null);
        if (owner.isEmpty()) {
            jdbc.sql("""
                            INSERT INTO data_export_weekly (id, tenant_id, week_of, status, reason, finished_at)
                            VALUES (?, current_setting('app.tenant_id')::uuid, ?, 'skipped', 'no_owner_email', ?)
                            ON CONFLICT (tenant_id, week_of) DO NOTHING
                            """)
                    .params(UUID.randomUUID(), week, Timestamp.from(clock.now()))
                    .update();
            return;
        }
        Requested run = runs.insert(
                report, "xlsx", report.prepare(Map.of()), owner.get().id(), false);
        int written = jdbc.sql("""
                        INSERT INTO data_export_weekly (id, tenant_id, week_of, run_id, recipient_user_id, status)
                        VALUES (?, current_setting('app.tenant_id')::uuid, ?, ?, ?, 'queued')
                        ON CONFLICT (tenant_id, week_of) DO NOTHING
                        """)
                .params(UUID.randomUUID(), week, run.run().id(), owner.get().id())
                .update();
        if (written == 0) {
            // Another tick queued this week first: the run queued here goes with this transaction.
            status.setRollbackOnly();
        }
    }

    private record Week(
            UUID id,
            UUID runId,
            UUID recipient,
            String runStatus,
            Long sizeBytes,
            LocalDate asOf,
            Instant expiresAt,
            String error) {}

    private int deliver() {
        List<Week> open = jdbc.sql("""
                        SELECT w.id, w.run_id, w.recipient_user_id, r.status, r.size_bytes, r.as_of, r.expires_at, r.error
                          FROM data_export_weekly w JOIN report_runs r ON r.id = w.run_id
                         WHERE w.status = 'queued'
                         ORDER BY w.week_of
                           FOR UPDATE OF w SKIP LOCKED
                        """)
                .query((rs, n) -> new Week(
                        rs.getObject("id", UUID.class),
                        rs.getObject("run_id", UUID.class),
                        rs.getObject("recipient_user_id", UUID.class),
                        rs.getString("status"),
                        rs.getObject("size_bytes", Long.class),
                        rs.getObject("as_of", LocalDate.class),
                        rs.getTimestamp("expires_at") == null
                                ? null
                                : rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("error")))
                .list();
        int emailed = 0;
        for (Week w : open) {
            switch (w.runStatus()) {
                case "ready" -> {
                    if (email(w)) {
                        emailed++;
                    }
                }
                case "failed" -> close(w.id(), "failed", null, w.error());
                case "cancelled" -> close(w.id(), "failed", null, "cancelled");
                default -> {
                    // Still queued or running: the next tick looks again.
                }
            }
        }
        return emailed;
    }

    private boolean email(Week w) {
        Optional<Owner> owner = owner(w.recipient());
        if (owner.isEmpty()) {
            close(w.id(), "failed", null, "no_owner_email");
            return false;
        }
        ZoneId zone = tenant.profile().timezone();
        boolean attach = w.sizeBytes() != null && w.sizeBytes() <= attachMaxBytes;
        Map<String, String> params = new HashMap<>();
        params.put("owner_name", owner.get().name());
        params.put("business_name", businessName());
        params.put("as_of", ReportFormats.date(w.asOf()));
        params.put("expires_on", ReportFormats.date(LocalDate.ofInstant(w.expiresAt(), zone)));
        params.put("link", hosts.tenantOrigin(tenant.profile().slug()) + "/staff/setup");
        if (attach) {
            params.put(EmailAttachments.PARAM, DataExportAttachments.KIND + ":" + w.runId());
        }
        outbox.enqueue(new Outbox.Message(
                Outbox.EMAIL,
                owner.get().email(),
                attach ? ATTACHED : LINKED,
                params,
                "core.data_export_weekly:" + w.id(),
                null,
                w.expiresAt()));
        close(w.id(), "emailed", attach, null);
        return true;
    }

    private void close(UUID id, String status, Boolean attached, String reason) {
        jdbc.sql("UPDATE data_export_weekly SET status = ?, attached = ?, reason = ?, finished_at = ? WHERE id = ?")
                .params(status, attached, reason, Timestamp.from(clock.now()), id)
                .update();
    }

    private String businessName() {
        String display = letterheads.current().displayName();
        return display == null || display.isBlank() ? tenant.profile().name() : display;
    }
}
