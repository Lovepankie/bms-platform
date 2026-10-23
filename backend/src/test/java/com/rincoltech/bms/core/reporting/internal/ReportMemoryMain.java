package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.BmsApplication;
import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Run by {@link ReportMemoryIT} in a child JVM with a 224 MB heap, the smallest host's: starts the real application
 * against the parent's database with the staging limits, queues the runs as a signed-in user would, runs the queue to its
 * end and prints one line per run. Exits 0 when every run is ready, 1 when one is not, and dies with an error when the heap
 * runs out ({@code -XX:+ExitOnOutOfMemoryError}).
 */
public final class ReportMemoryMain {

    private ReportMemoryMain() {}

    public static void main(String[] args) throws Exception {
        String url = args[0];
        String password = args[1];
        // One or more tenants, comma separated: a run is one at a time per tenant, so two runs at once need two
        // tenants.
        List<UUID> tenants = java.util.Arrays.stream(args[2].split(","))
                .map(UUID::fromString)
                .toList();
        UUID tenant = tenants.get(0);
        // The production measurement: a PDF row cap (fourth), runs at once (fifth); the default is staging's.
        boolean production = args.length > 3;
        int concurrent = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        SpringApplication app = new SpringApplication(BmsApplication.class, TestReports.class);
        // Command line arguments win over application.yml (default properties would lose to its ${...} values).
        String[] settings = {
            "--spring.profiles.active=test",
            "--spring.datasource.url=" + url,
            "--spring.datasource.username=bms_app",
            "--spring.datasource.password=" + password,
            "--server.port=0",
            "--management.server.port=0",
            // The staging limits (deploy/compose.pi-staging.yml).
            "--bms.reports.max-concurrent-runs=" + concurrent,
            "--bms.reports.xlsx-row-cap=20000",
            // The PDF row cap of staging (production's is 3,000); a fourth argument sets another, for the extra
            // measurement.
            "--bms.reports.pdf-row-cap=" + (args.length > 3 ? args[3] : "500"),
            "--bms.reports.max-file-bytes=8388608",
            "--bms.reports.runner-enabled=false"
        };
        int failures = 0;
        try (ConfigurableApplicationContext ctx = app.run(settings)) {
            // The storage double keeps nothing of an uploaded file, so what is measured is the code under test, not the
            // double.
            com.rincoltech.bms.core.documents.internal.WatchedStorage.discard = true;
            // What the application itself holds once started, after a collection: the reports are measured against it.
            System.gc();
            long baseline = 0;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP
                        && (pool.getName().contains("Tenured") || pool.getName().contains("Old"))) {
                    baseline = pool.getUsage().getUsed();
                }
            }
            System.out.println("MEMORY oldBaselineMb=" + baseline / 1024 / 1024);
            TenantJobs jobs = ctx.getBean(TenantJobs.class);
            RunService runs = ctx.getBean(RunService.class);
            ReportRunner runner = ctx.getBean(ReportRunner.class);
            // Every run is asked by its own user: ten new runs a minute is the limit of one (chapter 7 section 7.10).
            record Ask(String key, Map<String, Object> params, String format) {}
            // With a fourth argument: the production PDF cap of 3,000 rows, measured on the same 224 MB heap.
            List<Ask> asks = production
                    ? tenants.stream()
                            .flatMap(t -> java.util.stream.Stream.of(
                                    new Ask("test.kinds", Map.of("n", 19_999), "pdf"),
                                    new Ask("test.kinds", Map.of("n", 19_999), "xlsx")))
                            .toList()
                    : List.of(
                            new Ask("lending.par", Map.of("group_by", "none"), "json"),
                            new Ask("lending.par", Map.of("group_by", "product"), "csv"),
                            new Ask("lending.par", Map.of("group_by", "branch"), "json"),
                            // The largest table the staging cap allows, in both formats.
                            new Ask("test.rows", Map.of("n", 20_000), "json"),
                            new Ask("test.rows", Map.of("n", 20_000), "csv"),
                            // Files at the staging size cap (8 MiB): 20,000 rows of 360 characters make about 7.4 MB,
                            // uploaded
                            // from
                            // the file and never held in memory.
                            new Ask("test.fat", Map.of("n", 20_000), "json"),
                            new Ask("test.fat", Map.of("n", 20_000), "csv"),
                            // The PDF and Excel renderers (slice D2, issue #217): the lending reports on the seeded
                            // book, the
                            // largest sheet the staging cap allows (20,000 rows of eight columns) and a table far over
                            // the 500 row
                            // PDF cap, which is cut to its first rows and its total.
                            new Ask("lending.par", Map.of("group_by", "product"), "pdf"),
                            new Ask("lending.par", Map.of("group_by", "branch"), "xlsx"),
                            new Ask("lending.portfolio_outstanding", Map.of("group_by", "branch"), "pdf"),
                            new Ask("lending.portfolio_outstanding", Map.of("group_by", "officer"), "xlsx"),
                            new Ask("test.kinds", Map.of("n", 19_999), "xlsx"),
                            new Ask("test.kinds", Map.of("n", 19_999), "pdf"),
                            new Ask("test.markup", Map.of(), "pdf"),
                            // The whole business as one workbook (ADR-074): every table of the seeded book, read
                            // through the streaming reads.
                            new Ask("core.data_export", Map.of(), "xlsx"));
            List<UUID> ids = new ArrayList<>();
            Map<UUID, Principal> askers = new java.util.HashMap<>();
            Map<UUID, UUID> tenantOf = new java.util.HashMap<>();
            int n = 0;
            for (Ask ask : asks) {
                // Production mode: two asks (PDF, workbook) per tenant, in order.
                final UUID asked = production ? tenants.get(n++ / 2) : tenant;
                Principal who = Principal.uniform(
                        UUID.randomUUID(),
                        "staff",
                        Set.of(
                                "lending.reports.portfolio",
                                "lending.insights.read",
                                "lending.insights.all_officers",
                                "core.data_export.run"),
                        true,
                        Set.of());
                CurrentPrincipal.set(who);
                UUID id = jobs.callAs(
                                asked,
                                () -> runs.request(
                                        null, new ReportsApi.RunRequest(ask.key(), ask.params(), ask.format())))
                        .run()
                        .id();
                ids.add(id);
                askers.put(id, who);
                tenantOf.put(id, asked);
            }
            for (int i = 0; i < 100; i++) {
                runner.tick();
                if (!runner.awaitIdle(Duration.ofMinutes(5))) {
                    throw new IllegalStateException("a run did not finish");
                }
                boolean open = false;
                for (UUID id : ids) {
                    CurrentPrincipal.set(askers.get(id));
                    String status =
                            jobs.callAs(tenantOf.get(id), () -> runs.get(id)).status();
                    open |= status.equals("queued") || status.equals("running");
                }
                if (!open) {
                    break;
                }
            }
            for (int i = 0; i < asks.size(); i++) {
                final UUID id = ids.get(i);
                CurrentPrincipal.set(askers.get(id));
                ReportsApi.RunView view = jobs.callAs(tenantOf.get(id), () -> runs.get(id));
                if (!view.status().equals("ready")) {
                    failures++;
                }
                System.out.println(
                        "MEMORY run " + asks.get(i).key() + " " + asks.get(i).format() + " status="
                                + view.status() + " rows=" + view.rowCount() + " bytes=" + view.sizeBytes()
                                + (view.error() == null ? "" : " error=" + view.error()));
            }
            long peak = 0;
            long oldPeak = 0;
            long oldMax = 0;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP) {
                    peak += pool.getPeakUsage().getUsed();
                    // The old generation holds what survived collections: the live data of the process, which is what
                    // an out of memory error is made of (the young generation fills with garbage and proves nothing).
                    if (pool.getName().contains("Tenured") || pool.getName().contains("Old")) {
                        oldPeak = pool.getPeakUsage().getUsed();
                        oldMax = pool.getUsage().getMax();
                    }
                }
            }
            System.out.println("MEMORY maxHeapMb=" + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " peakHeapMb="
                    + peak / 1024 / 1024 + " oldPeakMb=" + oldPeak / 1024 / 1024 + " oldMaxMb=" + oldMax / 1024 / 1024);
            System.out.println("MEMORY upload maxReadBytes="
                    + com.rincoltech.bms.core.documents.internal.WatchedStorage.MAX_READ.get() + " largestFileBytes="
                    + com.rincoltech.bms.core.documents.internal.WatchedStorage.LARGEST.get() + " totalBytes="
                    + com.rincoltech.bms.core.documents.internal.WatchedStorage.TOTAL.get());
        }
        System.exit(failures == 0 ? 0 : 1);
    }
}
