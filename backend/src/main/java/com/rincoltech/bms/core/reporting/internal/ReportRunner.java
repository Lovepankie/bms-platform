package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.audit.AuditLog;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.BusinessClock;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The job {@code core.reporting.report-runner} (ADR-038 decision 1, ADR-008). Every 15 seconds {@link #tick} looks across
 * tenants for queued runs and starts as many as there are free places:
 *
 * <ul>
 *   <li>at most {@code BMS_REPORTS_MAX_CONCURRENT_RUNS} run at once across the platform (1 on staging, 2
 *       elsewhere), counted in the database so it holds across instances;
 *   <li>at most one run per tenant, claimed with {@code FOR UPDATE SKIP LOCKED} and backed by a unique index;
 *   <li>the oldest request first.
 * </ul>
 *
 * A tick claims and returns at once; the run itself executes on a worker thread, so a ten minute run does not hold
 * the scheduler or delay the claim of another tenant's run.
 *
 * <p><b>A run knows who holds it.</b> A claim stamps the run with this process ({@code host/instance}, for the log and
 * the audit), and every {@code heartbeat} the process beats for each run it is executing. A run is failed as
 * {@code interrupted} when its heartbeat is older than {@code heartbeat-stale} (a killed process stops beating), the
 * same limit on every tick including a new process's first one: a peer instance that is alive but slow (a long pause on
 * a loaded host) must not lose a healthy run to a process that has just started, and a recreated container has a new
 * host name, so the rule does not look at who owned the run, only at whether it still beats. On a clean stop the
 * workers are interrupted, given {@link #STOP_WAIT} to close their runs as {@code interrupted} themselves, and only
 * the runs this process was executing that are still open are failed (and audited) here, never another instance's;
 * the containers allow 30 seconds to stop, so a deploy neither blocks the single staging place nor is killed halfway.
 */
@Component
class ReportRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportRunner.class);

    /** How long a clean stop waits for the workers to close their runs. */
    static final Duration STOP_WAIT = Duration.ofSeconds(15);

    // The api containers set stop_grace_period 30s (deploy/compose.yml, deploy/compose.pi-staging.yml): STOP_WAIT stays
    // below it.

    private final TenantJobs tenantJobs;
    private final RunStore store;
    private final RunExecutor executor;
    private final JdbcClient jdbc;
    private final BusinessClock clock;
    private final ReportsProperties limits;
    private final AuditLog audit;
    private final TransactionTemplate plain;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService beat;
    private final String owner;
    private final AtomicBoolean stopped = new AtomicBoolean();

    /** The runs this process is executing, by run id, with their tenant. */
    private final Map<UUID, UUID> active = new ConcurrentHashMap<>();

    @Autowired
    ReportRunner(
            TenantJobs tenantJobs,
            RunStore store,
            RunExecutor executor,
            JdbcClient jdbc,
            BusinessClock clock,
            ReportsProperties limits,
            AuditLog audit,
            PlatformTransactionManager transactions) {
        this.tenantJobs = tenantJobs;
        this.store = store;
        this.executor = executor;
        this.jdbc = jdbc;
        this.clock = clock;
        this.limits = limits;
        this.audit = audit;
        this.plain = new TransactionTemplate(transactions);
        this.owner = hostName() + "/" + UUID.randomUUID().toString().substring(0, 8);
        AtomicInteger n = new AtomicInteger();
        this.workers = new ThreadPoolExecutor(
                limits.maxConcurrentRuns,
                limits.maxConcurrentRuns,
                0,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "report-run-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        this.beat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "report-heartbeat");
            t.setDaemon(true);
            return t;
        });
        long ms = Math.max(50, limits.heartbeat.toMillis());
        this.beat.scheduleWithFixedDelay(this::beatAll, ms, ms, TimeUnit.MILLISECONDS);
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (java.io.IOException e) {
            return "unknown-host";
        }
    }

    /** The "host/instance" this process stamps on the runs it claims. */
    String owner() {
        return owner;
    }

    /** Claims what can start now and hands it to the workers; returns how many runs it started. */
    int tick() {
        if (stopped.get()) {
            return 0;
        }
        sweepAbandoned();
        Integer running = jdbc.sql("SELECT app_count_running_report_runs()")
                .query(Integer.class)
                .single();
        int free = limits.maxConcurrentRuns - (running == null ? 0 : running);
        int started = 0;
        if (free <= 0) {
            return 0;
        }
        List<UUID> tenants = jdbc.sql("SELECT app_tenants_with_queued_report_runs()")
                .query(UUID.class)
                .list();
        for (UUID tenantId : tenants) {
            if (started >= free) {
                break;
            }
            Optional<UUID> claimed = tenantJobs.callAs(tenantId, this::claim);
            if (claimed.isPresent()) {
                UUID runId = claimed.get();
                active.put(runId, tenantId);
                workers.execute(() -> run(tenantId, runId));
                started++;
            }
        }
        return started;
    }

    private Optional<UUID> claim() {
        try {
            return store.claimNext(clock.now(), owner).map(RunStore.Run::id);
        } catch (DuplicateKeyException e) {
            // Another claimer got this tenant's one running place first.
            return Optional.empty();
        }
    }

    private void run(UUID tenantId, UUID runId) {
        try {
            tenantJobs.callAs(tenantId, () -> {
                executor.execute(runId);
                return null;
            });
        } catch (RuntimeException e) {
            log.error("report run {} could not start: {}", runId, RunExecutor.describe(e));
        } finally {
            active.remove(runId);
        }
    }

    /** Tells the database that every run this process is executing is still alive. */
    void beatAll() {
        for (Map.Entry<UUID, UUID> e : active.entrySet()) {
            try {
                tenantJobs.callAs(e.getValue(), () -> {
                    store.heartbeat(e.getKey(), clock.now());
                    return null;
                });
            } catch (RuntimeException ex) {
                log.warn("report run {} heartbeat failed: {}", e.getKey(), RunExecutor.describe(ex));
            }
        }
    }

    /**
     * Fails the runs whose process is gone, per tenant, audited: no heartbeat for {@code heartbeat-stale}. The limit
     * is the same on the first tick of a new process as on every other (earlier it was two beats on the first tick,
     * which let a freshly started instance fail a healthy run of a live peer whose beat was a few seconds late).
     */
    private void sweepAbandoned() {
        Instant cutoff = clock.now().minus(limits.heartbeatStale);
        List<UUID> tenants = jdbc.sql("SELECT app_tenants_with_stale_report_runs(?)")
                .param(java.sql.Timestamp.from(cutoff))
                .query(UUID.class)
                .list();
        for (UUID tenantId : tenants) {
            tenantJobs.callAs(tenantId, () -> {
                plain.executeWithoutResult(s -> {
                    Instant now = clock.now();
                    for (UUID id : store.failStale(cutoff, now)) {
                        audit.record(
                                AuditLog.Entry.created(
                                        RunExecutor.AUDIT_FAILED,
                                        RunService.ENTITY,
                                        id,
                                        null,
                                        Map.of("error", "interrupted")),
                                null,
                                "system");
                    }
                });
                return null;
            });
        }
    }

    /** Test hook: waits until no run is executing or waiting for a worker. */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long end = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < end) {
            if (active.isEmpty()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    /**
     * A clean stop (a deploy, a restart): no more claims; the workers are interrupted and have {@link #STOP_WAIT} to close
     * their runs as {@code interrupted} themselves; whatever is still open after that is failed here. Safe to call twice.
     */
    @PreDestroy
    void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        beat.shutdownNow();
        workers.shutdownNow();
        try {
            if (!workers.awaitTermination(STOP_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("report runs still working at stop: {}", active.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Map.Entry<UUID, UUID> e : Map.copyOf(active).entrySet()) {
            try {
                tenantJobs.callAs(e.getValue(), () -> {
                    plain.executeWithoutResult(s -> {
                        if (store.failInterrupted(e.getKey(), clock.now())) {
                            audit.record(
                                    AuditLog.Entry.created(
                                            RunExecutor.AUDIT_FAILED,
                                            RunService.ENTITY,
                                            e.getKey(),
                                            null,
                                            Map.of("error", "interrupted")),
                                    null,
                                    "system");
                        }
                    });
                    return null;
                });
            } catch (RuntimeException ex) {
                // The heartbeat sweep of the next process fails it.
                log.warn("report run {} could not be closed at stop: {}", e.getKey(), RunExecutor.describe(ex));
            }
        }
    }
}
