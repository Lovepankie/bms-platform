package com.rincoltech.bms.core.imports.internal;

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.QuarantineStorage;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily cleanup of abandoned uploads (issue #128), at 04:25 Kampala time, leased by db-scheduler as the document sweep
 * is. For each active business it removes:
 *
 * <ul>
 *   <li>the staged rows and the quarantined files of a cancelled run, and marks its batches cancelled;
 *   <li>the file of a failed or cancelled batch whose delete failed at the time;
 *   <li>a quarantined file that no batch names (the upload stopped between storage and its batch row), once it is older
 *       than {@code bms.imports.orphan-age} (24 hours), so an upload still in progress is never touched.
 * </ul>
 *
 * It also removes this instance's encrypted work files of the same age that a crash left. A run that is simply left open is
 * not touched here: its purge, after a warning to the operator, is the retention of #139. Each removal is audited
 * ({@code core.import_batch.quarantine_deleted}, actor {@code system}); logs carry counts only, never a key or a name. Every
 * step is safe to repeat.
 */
@Component
class QuarantineCleanup {

    static final String JOB = "core.imports.quarantine-cleanup";

    private static final Logger log = LoggerFactory.getLogger(QuarantineCleanup.class);

    /** Counts of one run. */
    record Result(int cancelledBatches, int filesDeleted, int orphansDeleted, int failed, int workFilesDeleted) {}

    private final TenantJobs tenantJobs;
    private final QuarantineStorage quarantine;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final UploadSettings.Properties settings;

    QuarantineCleanup(
            TenantJobs tenantJobs,
            QuarantineStorage quarantine,
            JdbcClient jdbc,
            PlatformTransactionManager transactions,
            AuditLog audit,
            BusinessClock clock,
            UploadSettings.Properties settings) {
        this.tenantJobs = tenantJobs;
        this.quarantine = quarantine;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.audit = audit;
        this.clock = clock;
        this.settings = settings;
    }

    Result cleanAll() {
        requireNoTransaction();
        List<UUID> tenants = new ArrayList<>();
        tenantJobs.forEachActiveTenant(JOB + ".list", tenants::add);
        int cancelled = 0;
        int files = 0;
        int orphans = 0;
        int failed = 0;
        int errors = 0;
        for (UUID tenantId : tenants) {
            try {
                Result r = tenantJobs.callAs(tenantId, this::cleanBound);
                cancelled += r.cancelledBatches();
                files += r.filesDeleted();
                orphans += r.orphansDeleted();
                failed += r.failed();
            } catch (RuntimeException e) {
                errors++;
                log.error(
                        "job {} failed for tenant {}: {}",
                        JOB,
                        tenantId,
                        e.getClass().getSimpleName());
            }
        }
        int work = cleanWorkFiles();
        log.info(
                "job {}: batches cancelled {}, files deleted {}, orphans deleted {}, failed {}, work files {}, errors {}",
                JOB,
                cancelled,
                files,
                orphans,
                failed,
                work,
                errors);
        if (errors > 0 || failed > 0) {
            throw new IllegalStateException("job " + JOB + " did not finish every business; it runs again tomorrow");
        }
        return new Result(cancelled, files, orphans, failed, work);
    }

    /** One business, bound to the thread, with no transaction open. */
    Result cleanBound() {
        return cleanBound(clock.now().minus(settings.orphanAge()));
    }

    /** As {@link #cleanBound()}, removing the files no batch names that storage wrote before {@code before}. */
    Result cleanBound(Instant before) {
        requireNoTransaction();
        // The batches of cancelled runs: rows gone and marked cancelled in one transaction; their files follow.
        int cancelled = tx.execute(s -> {
            List<UUID> ids = jdbc.sql("""
                            SELECT b.id FROM import_batches b JOIN import_runs r ON r.id = b.run_id
                             WHERE r.status = 'cancelled' AND b.status NOT IN ('cancelled', 'committed')
                             FOR UPDATE OF b
                            """).query(UUID.class).list();
            for (UUID id : ids) {
                jdbc.sql("DELETE FROM import_rows WHERE batch_id = ?").param(id).update();
                jdbc.sql("UPDATE import_batches SET status = 'cancelled', cancelled_at = now() WHERE id = ?")
                        .param(id)
                        .update();
            }
            return ids.size();
        });
        List<Held> held = tx.execute(s -> jdbc.sql("""
                        SELECT b.id, b.object_key FROM import_batches b JOIN import_runs r ON r.id = b.run_id
                         WHERE b.object_key IS NOT NULL AND (b.status IN ('failed', 'cancelled') OR r.status = 'cancelled')
                        """)
                .query((rs, n) -> new Held(rs.getObject("id", UUID.class), rs.getString("object_key")))
                .list());
        int files = 0;
        int failed = 0;
        for (Held h : held) {
            try {
                quarantine.delete(h.key());
            } catch (RuntimeException e) {
                failed++;
                continue;
            }
            tx.executeWithoutResult(s -> {
                jdbc.sql("""
                                UPDATE import_batches SET object_key = NULL,
                                       quarantine_deleted_at = coalesce(quarantine_deleted_at, now())
                                 WHERE id = ? AND object_key = ?
                                """).params(h.id(), h.key()).update();
                auditDeleted(h.id(), "cancelled");
            });
            files++;
        }
        // Files no batch names, older than the age an upload in progress could have.
        List<String> old;
        try (Stream<QuarantineStorage.Held> listed = quarantine.list()) {
            old = listed.filter(o -> o.lastModified().isBefore(before))
                    .map(QuarantineStorage.Held::key)
                    .toList();
        }
        int orphans = 0;
        for (int from = 0; from < old.size(); from += 500) {
            List<String> page = old.subList(from, Math.min(from + 500, old.size()));
            Set<String> named = new HashSet<>(
                    tx.execute(s -> jdbc.sql("SELECT object_key FROM import_batches WHERE object_key = ANY (?)")
                            .param(page.toArray(String[]::new))
                            .query(String.class)
                            .list()));
            List<String> orphaned =
                    page.stream().filter(k -> !named.contains(k)).toList();
            if (orphaned.isEmpty()) {
                continue;
            }
            List<String> refused = quarantine.deleteAll(orphaned);
            failed += refused.size();
            orphans += orphaned.size() - refused.size();
            if (orphaned.size() > refused.size()) {
                int removed = orphaned.size() - refused.size();
                tx.executeWithoutResult(s -> audit.record(
                        AuditLog.Entry.created(
                                "core.import_batch.quarantine_deleted",
                                "import_batch",
                                null,
                                null,
                                Map.of("reason", "orphan", "files", removed)),
                        null,
                        "system"));
            }
        }
        return new Result(cancelled, files, orphans, failed, 0);
    }

    /** The encrypted work files of this instance that a crash left, by the same age. */
    int cleanWorkFiles() {
        Path dir = settings.work();
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        Instant before = clock.now().minus(settings.orphanAge());
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, UploadService.TEMP_PREFIX + "*.bin")) {
            for (Path f : files) {
                try {
                    if (Files.getLastModifiedTime(f).toInstant().isBefore(before) && Files.deleteIfExists(f)) {
                        deleted++;
                    }
                } catch (IOException e) {
                    // Counted out; the next run tries again.
                }
            }
        } catch (IOException e) {
            log.warn("job {}: the import work directory could not be listed", JOB);
        }
        return deleted;
    }

    private void auditDeleted(UUID batchId, String reason) {
        audit.record(
                AuditLog.Entry.created(
                        "core.import_batch.quarantine_deleted",
                        "import_batch",
                        batchId,
                        null,
                        Map.of("reason", reason)),
                null,
                "system");
    }

    private record Held(UUID id, String key) {}

    /** Storage is listed and deleted with no database transaction open, as in the upload. */
    private static void requireNoTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "The quarantine cleanup takes its own short transactions; call it outside one");
        }
    }

    /** The job, shaped like {@code DocumentJobs}; {@code bms.imports.cleanup-enabled=false} removes it. */
    @Configuration(proxyBeanMethods = false)
    static class Jobs {

        @Bean
        @ConditionalOnProperty(name = "bms.imports.cleanup-enabled", havingValue = "true", matchIfMissing = true)
        RecurringTask<Void> importQuarantineCleanupTask(QuarantineCleanup cleanup) {
            return Tasks.recurring(JOB, Schedules.daily(BusinessClock.DEFAULT_ZONE, LocalTime.of(4, 25)))
                    .execute((instance, context) -> cleanup.cleanAll());
        }
    }
}
