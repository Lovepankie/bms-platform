package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.reporting.internal.RunStore.Expired;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes the stored files of finished runs once they are 30 days old (ADR-038 decision 7, chapter 12 section
 * 12.5): a report holds member names and balances and is not kept longer than needed. The object goes; the
 * {@code documents} row (written once) and the {@code report_runs} row stay as the record that the report was made,
 * and a download then answers 410 {@code report_expired}. Run per tenant by the nightly job; safe to run again, since
 * deleting a missing object is not an error and a run is marked only after its object is gone.
 *
 * <p>No call to storage is made inside a transaction: each batch of {@link #BATCH} runs is read in a short read
 * transaction, each object is deleted with none open, and each run is then marked and audited in a transaction of its
 * own, so a slow or failing storage never holds a connection or a lock and a failure costs one object, not the batch.
 */
@Service
class ReportExpiry {

    static final String AUDIT = "core.reports.files_expired";
    /** Runs read at a time. */
    static final int BATCH = 50;
    /** Runs handled per tenant per call, so one night's work is bounded; the rest wait for the next. */
    static final int PER_NIGHT = 2000;

    private static final Logger log = LoggerFactory.getLogger(ReportExpiry.class);

    private final RunStore store;
    private final Documents documents;
    private final AuditLog audit;
    private final BusinessClock clock;
    private final TransactionTemplate transaction;
    private final TenantJobs tenantJobs;
    private final JdbcClient jdbc;

    ReportExpiry(
            RunStore store,
            Documents documents,
            AuditLog audit,
            BusinessClock clock,
            PlatformTransactionManager transactions,
            TenantJobs tenantJobs,
            JdbcClient jdbc) {
        this.store = store;
        this.documents = documents;
        this.audit = audit;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.tenantJobs = tenantJobs;
        this.jdbc = jdbc;
    }

    /**
     * The nightly pass: every business with a file past its 30 days, whatever its status. A business that was suspended or
     * whose subscription ended keeps its data, but not its report files past their time (ADR-038 decision 7, ADR-074), so
     * the list comes from {@code app_tenants_with_expired_report_files} and not from the active tenants. Not
     * {@code forEachActiveTenant}: that wraps each tenant in one transaction, and the expiry calls storage.
     */
    int expireEveryTenant() {
        int expired = 0;
        int failures = 0;
        for (UUID tenantId : jdbc.sql("SELECT app_tenants_with_expired_report_files(?)")
                .param(java.sql.Timestamp.from(clock.now()))
                .query(UUID.class)
                .list()) {
            try {
                expired += tenantJobs.callAs(tenantId, this::expireDue);
            } catch (RuntimeException e) {
                failures++;
                log.error("report file expiry failed for tenant {}", tenantId, e);
            }
        }
        if (failures > 0) {
            throw new IllegalStateException("report file expiry failed for " + failures + " tenant(s)");
        }
        return expired;
    }

    /** Deletes the files due now for the bound tenant, outside any transaction; returns how many runs were expired. */
    int expireDue() {
        Instant now = clock.now();
        int done = 0;
        int attempted = 0;
        Set<UUID> skipped = new HashSet<>();
        // Attempts count toward the night's limit, successful or not: a storage outage must not walk the whole backlog.
        while (attempted < PER_NIGHT) {
            // Runs whose object could not be deleted stay first in the list, so ask for that many more.
            List<Expired> due = transaction.execute(s -> store.expired(now, BATCH + skipped.size()));
            List<Expired> fresh =
                    due.stream().filter(e -> !skipped.contains(e.runId())).toList();
            if (fresh.isEmpty()) {
                break;
            }
            for (Expired e : fresh) {
                if (attempted++ >= PER_NIGHT) {
                    return done;
                }
                try {
                    documents.deleteReportObject(e.documentId());
                } catch (RuntimeException ex) {
                    // Storage hiccup: leave the run for tomorrow's pass; the other files still go.
                    log.warn("report file of run {} could not be deleted: {}", e.runId(), RunExecutor.describe(ex));
                    skipped.add(e.runId());
                    continue;
                }
                transaction.executeWithoutResult(s -> {
                    store.markFilesDeleted(e.runId(), now);
                    audit.record(
                            AuditLog.Entry.created(
                                    AUDIT, RunService.ENTITY, e.runId(), null, Map.of("document_id", e.documentId())),
                            null,
                            "system");
                });
                done++;
            }
        }
        return done;
    }
}
