package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The daily sweep of orphaned uploads (issue #39). An upload stores its object before its row commits, so a process that
 * dies in between, or a failed delete after a rollback, leaves an object under {@code tenants/<tenant_id>/upload/} with
 * no {@code documents} row. For each active business this lists those objects and deletes the ones that are older than
 * {@link #MIN_AGE} and that no row names. Only {@code DocumentService.store} writes under {@code upload/}, and it writes
 * the {@code documents} row in the same transaction ({@code UploadKeysTest} holds that), so {@code documents.object_key}
 * is the only reference there is.
 *
 * <p><b>It never deletes anything live.</b> An object written in the last 24 hours is never touched. The reference check
 * reads {@code documents} in a short transaction of its own, batch by batch, and again just before each delete batch.
 *
 * <p><b>It fails closed.</b> Before anything of a business is deleted, the check proves it can see that business's rows:
 * the transaction must be bound to that business ({@code app.tenant_id}), and a business with old objects must show at
 * least one upload row. A business whose orphans would be more than {@code max-share} of its objects is not swept either.
 * Each of these skips the business, counts it as skipped and logs the counts; nothing is deleted.
 *
 * <p><b>It is bounded.</b> At most {@code max-per-tenant} objects of one business and {@code max-per-run} in all are
 * deleted in one run; the rest wait for the next night. Deletes go in {@code DeleteObjects} requests of {@link #BATCH}
 * keys with {@code pause} between them, so a backlog never floods the object store.
 *
 * <p>Logs carry counts only, never an object key. Each removal is audited ({@code core.document.orphan_removed}, actor
 * {@code system}) and counted on {@value #METRIC}; failed deletes on {@value #FAILED_METRIC}. No transaction is open while
 * storage is listed or an object deleted. Every step is safe to repeat (see {@link DocumentJobs} for the lease).
 */
@Component
class OrphanSweep {

    private static final Logger log = LoggerFactory.getLogger(OrphanSweep.class);

    static final String JOB = "core.documents.orphan-sweep";

    static final String METRIC = "bms.documents.orphans.removed";

    static final String FAILED_METRIC = "bms.documents.orphans.failed";

    static final String SKIPPED_METRIC = "bms.documents.orphans.skipped";

    /** Objects younger than this are never removed. */
    static final Duration MIN_AGE = Duration.ofHours(24);

    /** Keys checked against {@code documents} in one query, and deleted in one request. */
    static final int BATCH = 500;

    /** Below this many objects the share rule does not apply: a new business with a few failed uploads is swept. */
    static final int SHARE_MIN_OBJECTS = 20;

    /**
     * The bounds of one run.
     *
     * @param perTenant most objects of one business deleted in one run
     * @param perRun most objects deleted in one run in all
     * @param maxShare the most of a business's objects (0 to 1) that may be orphans; above it the business is skipped
     * @param pause the wait between two delete requests
     */
    record Limits(int perTenant, int perRun, double maxShare, Duration pause) {

        Limits withPerTenant(int n) {
            return new Limits(n, perRun, maxShare, pause);
        }
    }

    /**
     * What one run found and did, in counts only.
     *
     * @param orphans unreferenced old objects found (in a dry run, those that would go)
     * @param removed objects deleted
     * @param failed deletes the object store refused
     * @param auditFailed objects deleted whose audit row could not be written
     * @param skipped businesses not swept because a check could not prove it safe
     */
    record Result(int listed, int old, int orphans, int removed, int failed, int auditFailed, int skipped) {

        static final Result NONE = new Result(0, 0, 0, 0, 0, 0, 0);

        Result plus(Result o) {
            return new Result(
                    listed + o.listed,
                    old + o.old,
                    orphans + o.orphans,
                    removed + o.removed,
                    failed + o.failed,
                    auditFailed + o.auditFailed,
                    skipped + o.skipped);
        }
    }

    private final ObjectStorage storage;
    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final TenantJobs tenantJobs;
    private final TransactionTemplate transaction;
    private final Counter removedCounter;
    private final Counter failedCounter;
    private final Counter skippedCounter;
    private final Limits limits;

    OrphanSweep(
            ObjectStorage storage,
            JdbcClient jdbc,
            AuditLog audit,
            BusinessClock clock,
            TenantJobs tenantJobs,
            PlatformTransactionManager transactionManager,
            MeterRegistry meters,
            @Value("${bms.documents.orphan-sweep-max-per-tenant:1000}") int perTenant,
            @Value("${bms.documents.orphan-sweep-max-per-run:10000}") int perRun,
            @Value("${bms.documents.orphan-sweep-max-share:0.5}") double maxShare,
            @Value("${bms.documents.orphan-sweep-pause:1s}") Duration pause) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.tenantJobs = tenantJobs;
        this.transaction = new TransactionTemplate(transactionManager);
        this.removedCounter = Counter.builder(METRIC)
                .description("Orphaned upload objects removed by the daily sweep")
                .register(meters);
        this.failedCounter = Counter.builder(FAILED_METRIC)
                .description("Orphaned upload objects the object store refused to remove")
                .register(meters);
        this.skippedCounter = Counter.builder(SKIPPED_METRIC)
                .description("Businesses the orphan sweep skipped because a safety check failed")
                .register(meters);
        this.limits = new Limits(perTenant, perRun, maxShare, pause);
    }

    /** The bounds this instance runs with (the configured ones). */
    Limits limits() {
        return limits;
    }

    /**
     * Sweeps every active business within the configured bounds. One business's failure is logged and the others still
     * run; the run then fails as a whole (as it does when any delete was refused), so the scheduler records it.
     *
     * @param dryRun when true, nothing is deleted and no audit row is written: only the counts are logged and returned
     */
    Result sweepAll(boolean dryRun) {
        return sweepAll(dryRun, limits);
    }

    Result sweepAll(boolean dryRun, Limits bounds) {
        // The listing of active businesses comes from the platform (TenantJobs); its per-tenant transaction only
        // collects the ids, and each business is then swept with no transaction open.
        List<UUID> tenants = new ArrayList<>();
        tenantJobs.forEachActiveTenant(JOB + ".list", tenants::add);
        Result total = Result.NONE;
        int errors = 0;
        for (UUID tenantId : tenants) {
            // A dry run deletes nothing, so only a real run uses up the bound.
            int left = bounds.perRun() - total.removed() - total.failed();
            if (left <= 0) {
                log.info(
                        "job {}: the limit of {} for one run is reached; the rest waits for the next run",
                        JOB,
                        bounds.perRun());
                break;
            }
            Limits forTenant = bounds.withPerTenant(Math.min(bounds.perTenant(), left));
            try {
                total = total.plus(tenantJobs.callAs(tenantId, () -> sweep(tenantId, dryRun, forTenant)));
            } catch (RuntimeException e) {
                errors++;
                log.error("job {} failed for tenant {}", JOB, tenantId, e);
            }
        }
        log.info(
                "job {} {}: listed {}, old {}, orphans {}, removed {}, failed {}, audit failed {}, skipped tenants {},"
                        + " errors {}",
                JOB,
                dryRun ? "dry run" : "run",
                total.listed(),
                total.old(),
                total.orphans(),
                total.removed(),
                total.failed(),
                total.auditFailed(),
                total.skipped(),
                errors);
        if (errors > 0 || total.failed() > 0) {
            throw new IllegalStateException(
                    "job " + JOB + " failed for " + errors + " tenant(s) and " + total.failed() + " delete(s)");
        }
        return total;
    }

    /** Sweeps the business bound to the caller, within the configured bounds. Runs outside any transaction. */
    Result sweepTenant(boolean dryRun) {
        return sweep(TenantContext.require(), dryRun, limits);
    }

    /**
     * Sweeps the objects of {@code tenantId}. The objects listed are that business's; the reference check proves it is
     * bound to that same business before anything is deleted, so a caller that is not bound (or bound to another
     * business) deletes nothing and gets the business back as skipped.
     */
    Result sweep(UUID tenantId, boolean dryRun, Limits bounds) {
        String prefix = "tenants/" + tenantId + "/upload/";
        Instant cutoff = clock.now().minus(MIN_AGE);
        int listed = 0;
        int old = 0;
        int orphans = 0;
        List<String> toRemove = new ArrayList<>();
        List<String> batch = new ArrayList<>(BATCH);
        try (Stream<ObjectStorage.Listed> objects = storage.list(prefix)) {
            for (var it = objects.iterator(); it.hasNext(); ) {
                ObjectStorage.Listed object = it.next();
                listed++;
                if (object.lastModified() == null || !object.lastModified().isBefore(cutoff)) {
                    continue;
                }
                old++;
                batch.add(object.key());
                if (batch.size() == BATCH) {
                    orphans += collectUnreferenced(tenantId, batch, toRemove, bounds.perTenant());
                    batch.clear();
                }
            }
        } catch (BindingNotProven e) {
            return skip(tenantId, "the reference check could not prove it reads this business's rows", listed, old);
        }
        if (!batch.isEmpty()) {
            try {
                orphans += collectUnreferenced(tenantId, batch, toRemove, bounds.perTenant());
            } catch (BindingNotProven e) {
                return skip(tenantId, "the reference check could not prove it reads this business's rows", listed, old);
            }
        }
        if (old > 0 && visibleUploads(tenantId) == 0) {
            return skip(tenantId, "old objects but no upload row is visible", listed, old);
        }
        if (listed >= SHARE_MIN_OBJECTS && orphans > bounds.maxShare() * listed) {
            return skip(tenantId, "orphans are more than the allowed share of the objects", listed, old);
        }
        if (dryRun) {
            log.info(
                    "job {} dry run, tenant {}: listed {}, old {}, would remove {} (of {} orphans)",
                    JOB,
                    tenantId,
                    listed,
                    old,
                    toRemove.size(),
                    orphans);
            return new Result(listed, old, orphans, 0, 0, 0, 0);
        }
        int removed = 0;
        int failed = 0;
        int auditFailed = 0;
        for (int from = 0; from < toRemove.size(); from += BATCH) {
            if (from > 0 && !pause(bounds.pause())) {
                break;
            }
            List<String> keys = toRemove.subList(from, Math.min(from + BATCH, toRemove.size()));
            // Checked again just before the delete: still unreferenced, still this business.
            List<String> still;
            try {
                still = unreferenced(tenantId, keys);
            } catch (BindingNotProven e) {
                break;
            }
            Set<String> refused = new HashSet<>(storage.deleteAll(still));
            failed += refused.size();
            failedCounter.increment(refused.size());
            for (String key : still) {
                if (refused.contains(key)) {
                    continue;
                }
                removed++;
                removedCounter.increment();
                // Deleted first, audited after: a crash in between leaves no row for an object still there. A failed
                // audit write is counted and the sweep goes on.
                try {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("object_key", key);
                    transaction.executeWithoutResult(s -> audit.record(
                            AuditLog.Entry.created("core.document.orphan_removed", "core.document", null, null, data),
                            null,
                            "system"));
                } catch (RuntimeException e) {
                    auditFailed++;
                }
            }
        }
        log.info(
                "job {} run, tenant {}: listed {}, old {}, orphans {}, removed {}, failed {}, audit failed {}",
                JOB,
                tenantId,
                listed,
                old,
                orphans,
                removed,
                failed,
                auditFailed);
        return new Result(listed, old, orphans, removed, failed, auditFailed, 0);
    }

    /** Counts the unreferenced keys of the batch, and keeps them for deletion up to {@code cap} kept in all. */
    private int collectUnreferenced(UUID tenantId, List<String> keys, List<String> toRemove, int cap) {
        List<String> found = unreferenced(tenantId, keys);
        for (String key : found) {
            if (toRemove.size() < cap) {
                toRemove.add(key);
            }
        }
        return found.size();
    }

    /**
     * The keys no {@code documents} row names, read in a transaction proven to be bound to {@code tenantId}. Any failure
     * of the check (no binding, a database error) is {@link BindingNotProven}: the business is skipped, never swept.
     */
    private List<String> unreferenced(UUID tenantId, List<String> keys) {
        Set<String> referenced;
        try {
            referenced = transaction.execute(s -> {
                requireBound(tenantId);
                return new HashSet<>(jdbc.sql("SELECT object_key FROM documents WHERE object_key = ANY(?)")
                        .param(keys.toArray(String[]::new))
                        .query(String.class)
                        .list());
            });
        } catch (BindingNotProven e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BindingNotProven();
        }
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            if (!referenced.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /** The upload rows the check can see for {@code tenantId}, under the same proven binding. */
    private long visibleUploads(UUID tenantId) {
        try {
            return transaction.execute(s -> {
                requireBound(tenantId);
                return jdbc.sql("SELECT count(*) FROM documents WHERE doc_type = 'upload'")
                        .query(Long.class)
                        .single();
            });
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Row-level security filters {@code documents} by {@code app.tenant_id}: it must be this business, or nothing goes. */
    private void requireBound(UUID tenantId) {
        String bound;
        try {
            bound = jdbc.sql("SELECT current_setting('app.tenant_id', true)")
                    .query(String.class)
                    .optional()
                    .orElse(null);
        } catch (RuntimeException e) {
            throw new BindingNotProven();
        }
        if (bound == null || !bound.equals(tenantId.toString())) {
            throw new BindingNotProven();
        }
    }

    private Result skip(UUID tenantId, String why, int listed, int old) {
        skippedCounter.increment();
        log.warn("job {} skipped tenant {}: {}; listed {}, old {}, removed 0", JOB, tenantId, why, listed, old);
        return new Result(listed, old, 0, 0, 0, 0, 1);
    }

    private static boolean pause(Duration d) {
        if (d.isZero() || d.isNegative()) {
            return true;
        }
        try {
            Thread.sleep(d);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The reference check could not prove it reads this business's rows. */
    static final class BindingNotProven extends RuntimeException {

        BindingNotProven() {
            super("the reference check is not bound to the business being swept", null, false, false);
        }
    }
}
