package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The nightly sweep of uploads no record claimed (issue #819, ADR-124): a subject type whose {@link DocumentAccess} gives a
 * {@code claimWithin} (an expense's receipt photo, sent before its expense) has the file of each upload older than that, and
 * that its owning module says no record names, deleted from storage. The {@code documents} row stays, as every document's
 * does, and is audited {@code core.document.unclaimed_removed}; a later read of it is 404. Only uploads of the last
 * {@link #LOOK_BACK} past their window are looked at, so a run is bounded and a missed night is caught up; deleting a file
 * already gone is harmless. The tenant's rows are read in its own transaction; storage is called with none open.
 */
@Component
class UnclaimedSweep {

    private static final Logger log = LoggerFactory.getLogger(UnclaimedSweep.class);

    static final String JOB = "core.documents.unclaimed-sweep";

    /** How far past its window an upload is still looked at: a month of nights the job may have missed. */
    static final Duration LOOK_BACK = Duration.ofDays(31);

    private final List<DocumentAccess> access;
    private final ObjectStorage storage;
    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final TenantJobs tenantJobs;
    private final TransactionTemplate transaction;

    UnclaimedSweep(
            List<DocumentAccess> access,
            ObjectStorage storage,
            JdbcClient jdbc,
            AuditLog audit,
            BusinessClock clock,
            TenantJobs tenantJobs,
            PlatformTransactionManager transactionManager) {
        this.access = access;
        this.storage = storage;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.tenantJobs = tenantJobs;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    private record Unclaimed(UUID id, UUID branchId, String subjectType, UUID subjectId, String key) {}

    /** Sweeps every active business; one business's failure is logged and the others still run. Returns the files deleted. */
    int sweepAll() {
        List<UUID> tenants = new ArrayList<>();
        tenantJobs.forEachActiveTenant(JOB + ".list", tenants::add);
        int removed = 0;
        for (UUID tenantId : tenants) {
            try {
                removed += tenantJobs.callAs(tenantId, this::sweep);
            } catch (RuntimeException e) {
                log.error("job {} failed for tenant {}", JOB, tenantId, e);
            }
        }
        log.info("job {}: removed {}", JOB, removed);
        return removed;
    }

    /** The bound business: its unclaimed uploads past their window, their files deleted. */
    int sweep() {
        Instant now = clock.now();
        List<Unclaimed> found = new ArrayList<>();
        for (DocumentAccess a : access) {
            Duration window = a.claimWithin();
            if (window == null) {
                continue;
            }
            Instant before = now.minus(window);
            found.addAll(transaction.execute(s -> jdbc
                    .sql("""
                            SELECT id, branch_id, subject_type, subject_id, object_key FROM documents
                             WHERE subject_type = :type AND doc_type = 'upload'
                               AND created_at < :before AND created_at >= :since
                             ORDER BY created_at, id
                            """)
                    .param("type", a.subjectType())
                    .param("before", Timestamp.from(before))
                    .param("since", Timestamp.from(before.minus(LOOK_BACK)))
                    .query((rs, n) -> new Unclaimed(
                            rs.getObject("id", UUID.class),
                            rs.getObject("branch_id", UUID.class),
                            rs.getString("subject_type"),
                            rs.getObject("subject_id", UUID.class),
                            rs.getString("object_key")))
                    .list()
                    .stream()
                    // Asked in the business's own transaction: the owning module reads its records under RLS.
                    .filter(u -> !a.claimed(u.subjectId(), u.id()))
                    .toList()));
        }
        int removed = 0;
        for (Unclaimed u : found) {
            if (!storage.exists(u.key())) {
                continue;
            }
            // The audit row and the delete go together (review of #846): a delete that fails rolls the row back, so no
            // removal is audited that did not happen; the object store is called last, inside the short transaction.
            transaction.executeWithoutResult(s -> {
                audit.record(
                        AuditLog.Entry.created(
                                "core.document.unclaimed_removed",
                                "core.document",
                                u.id(),
                                u.branchId(),
                                Map.of("subject_type", u.subjectType())),
                        null,
                        "system");
                storage.delete(u.key());
            });
            removed++;
        }
        return removed;
    }
}
