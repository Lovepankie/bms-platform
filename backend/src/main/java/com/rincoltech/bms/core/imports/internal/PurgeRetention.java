package com.rincoltech.bms.core.imports.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.kernel.BusinessClock;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scheduled purge of spec section 6.4 (issue #139, ADR-008), daily at 04:40 Kampala time, after the quarantine cleanup,
 * leased by db-scheduler. For each business holding a run to purge ({@code imports_retention_tenants()}: suspended ones
 * too, not one whose removal has begun), on the business clock:
 *
 * <ul>
 *   <li>a run signed off is purged within 30 days of its sign-off (on day 29, so a daily job is never late);
 *   <li>a run never signed off (any state but {@code committing}, which only a resume or the dev lead's restore ends) is
 *       purged 90 days after its last activity; a run used again after its warning starts again.
 * </ul>
 *
 * Its operators are warned a week before: a Telegram message to the platform operators' chat through the outbox
 * ({@code imports.purge_warning}) and an audit row ({@code core.import_run.purge_warned}, actor {@code system}), written
 * with {@code purge_warned_at} in one transaction. The deadline wins over a warning that never went (Lead ruling on #886,
 * M4): the purge goes ahead and sends {@code imports.purged_without_warning} to the operators and the business's Owner in
 * its own transaction. The purge is {@link PurgeService}'s, audited and with its certificate. Logs carry counts and run ids only.
 * Every step is safe to repeat: a run purged is never listed again, and a warning stands until the run is used again.
 */
@Component
class PurgeRetention {

    static final String JOB = "core.imports.retention";

    static final String WARNING_TEMPLATE = "imports.purge_warning";

    private static final Logger log = LoggerFactory.getLogger(PurgeRetention.class);

    /** Counts of one run of the job. */
    record Result(int warned, int purged, int failed) {}

    private final TenantJobs tenantJobs;
    private final PurgeService purges;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final Outbox outbox;
    private final CurrentTenant tenant;
    private final BusinessClock clock;

    PurgeRetention(
            TenantJobs tenantJobs,
            PurgeService purges,
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            AuditLog audit,
            Outbox outbox,
            CurrentTenant tenant,
            BusinessClock clock) {
        this.tenantJobs = tenantJobs;
        this.purges = purges;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.audit = audit;
        this.outbox = outbox;
        this.tenant = tenant;
        this.clock = clock;
    }

    Result runAll() {
        requireNoTransaction();
        // Every business with a run to purge, suspended ones too (review of #886, M3): the deadline holds whatever the
        // business's state, except one whose removal has begun, which erases its imports itself.
        List<UUID> tenants = tx.execute(s ->
                jdbc.sql("SELECT imports_retention_tenants()").query(UUID.class).list());
        Instant now = clock.now();
        int warned = 0;
        int purged = 0;
        int failed = 0;
        for (UUID tenantId : tenants) {
            try {
                Result r = tenantJobs.callAs(tenantId, () -> runBound(now));
                warned += r.warned();
                purged += r.purged();
                failed += r.failed();
            } catch (RuntimeException e) {
                failed++;
                log.error(
                        "job {} failed for tenant {}: {}",
                        JOB,
                        tenantId,
                        e.getClass().getSimpleName());
            }
        }
        log.info("job {}: warned {}, purged {}, failed {}", JOB, warned, purged, failed);
        if (failed > 0) {
            throw new IllegalStateException("job " + JOB + " did not finish every business; it runs again tomorrow");
        }
        return new Result(warned, purged, failed);
    }

    /** One business, bound to the thread, with no transaction open, now on the business clock. */
    Result runBound() {
        return runBound(clock.now());
    }

    private Result runBound(Instant now) {
        requireNoTransaction();
        List<PurgeService.Candidate> candidates = tx.execute(s -> purges.candidates(null));
        int warned = 0;
        int purged = 0;
        int failed = 0;
        for (PurgeService.Candidate c : candidates) {
            if (c.due(now)) {
                // The deadline wins over a warning that never went (Lead ruling on #886, M4); the purge then sends the
                // notice imports.purged_without_warning in its own transaction.
                try {
                    if (purges.purgeScheduled(c.id(), now).isPresent()) {
                        purged++;
                    }
                } catch (RuntimeException e) {
                    failed++;
                    log.warn(
                            "job {}: import run {} not purged: {}",
                            JOB,
                            c.id(),
                            e.getClass().getSimpleName());
                }
            } else if (c.warnable(now) && Boolean.TRUE.equals(tx.execute(s -> warn(c, now)))) {
                warned++;
            }
        }
        return new Result(warned, purged, failed);
    }

    /** Warns once per start of the run's clock; false when the run changed since it was read. */
    private boolean warn(PurgeService.Candidate c, Instant now) {
        int changed = jdbc.sql("""
                        UPDATE import_runs SET purge_warned_at = ?
                         WHERE tenant_id = current_setting('app.tenant_id')::uuid AND id = ?
                           AND status NOT IN ('purged', 'committing')
                           AND purge_warned_at IS NOT DISTINCT FROM CAST(? AS timestamptz)
                        """)
                .params(Timestamp.from(now), c.id(), c.warnedAt() == null ? null : Timestamp.from(c.warnedAt()))
                .update();
        if (changed == 0) {
            return false;
        }
        CurrentTenant.Profile business = tenant.profile();
        // The deadline wins (Lead ruling on #886): the warning names it, even when the warning itself is late.
        LocalDate day = LocalDate.ofInstant(
                c.dueAt(), business.timezone() == null ? BusinessClock.DEFAULT_ZONE : business.timezone());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("reason", c.reason());
        after.put("purge_on", day.toString());
        audit.record(
                AuditLog.Entry.created("core.import_run.purge_warned", "import_run", c.id(), null, after),
                null,
                "system");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("run", c.id().toString());
        params.put("business", business.slug());
        params.put("day", day.toString());
        params.put(
                "reason",
                PurgeService.SIGNED_OFF_30_DAYS.equals(c.reason())
                        ? "30 days after the customer's sign-off"
                        : "90 days after its last activity, never signed off");
        outbox.enqueue(new Outbox.Message(
                Outbox.TELEGRAM,
                Outbox.OPERATOR_CHAT,
                WARNING_TEMPLATE,
                params,
                WARNING_TEMPLATE + ":" + c.id() + ":" + c.since().toEpochMilli()));
        return true;
    }

    private static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The scheduled purge takes its own transactions; call it outside one");
        }
    }

    /** The job, shaped like the quarantine cleanup; {@code bms.imports.retention-enabled=false} removes it. */
    @Configuration(proxyBeanMethods = false)
    static class Jobs {

        @Bean
        @ConditionalOnProperty(name = "bms.imports.retention-enabled", havingValue = "true", matchIfMissing = true)
        RecurringTask<Void> importRetentionTask(PurgeRetention retention) {
            return Tasks.recurring(JOB, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(4, 40)))
                    .execute((instance, context) -> retention.runAll());
        }
    }
}
