package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.lending.seed.internal.LendingSeeder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * ADR-038 decision 7 and {@code docs/specs/client-extras.md} section 6.3: report runs on the smallest host's heap. The
 * parent seeds a fabricated book on a full heap; the runs themselves execute in a child JVM capped at 224 MB, the heap of
 * the staging API, with the staging limits (one run at a time, 20,000 rows a table, 500 PDF rows, an 8 MiB file) and the
 * real application context, so an out of memory error fails this test and not a production run (the precedent is
 * {@code StatementMemoryTest}).
 *
 * <p>The child makes fifteen runs: three {@code lending.par} runs on the seeded book and the synthetic report at the
 * largest table the staging cap allows, as JSON and CSV (slice D1); files at the size cap, uploaded from disk; and, for
 * the renderers of slice D2 (issue #217), {@code lending.par} and {@code lending.portfolio_outstanding} as PDF and
 * Excel on the seeded book, an Excel sheet of 20,000 rows of eight columns (the cap), and a PDF of a table of 20,000 rows
 * (cut at the 500 row cap, its total kept); and the business's own copy of its data, {@code core.data_export} (ADR-074),
 * every table of the seeded book read from the server 2,000 rows at a time and written as it comes. Rows go to the spool one at a time and every renderer reads it a row at a
 * time, so no table is ever held in memory. A second child runs the PDF at the production cap of 3,000 rows on the same
 * heap, to show the headroom. The book is twice the demo seed; raise it with {@code -Dreports.memory.scale=N}. The pack of
 * slice D5 joins this test when it exists.
 *
 * <p><b>Not part of {@code mvn verify}.</b> It is a three to four minute proof on a 224 MB child JVM, and on the shared
 * CI runner a stalled machine would fail the whole backend job for a reason that is not a defect. It runs only with
 * {@code -Dreports.memory.run=true}: locally, or in the manually started CI job {@code report-memory}
 * ({@code .github/workflows/ci.yml}, "Run workflow"), which runs {@code mvn -B verify -Dit.test=ReportMemoryIT
 * -Dreports.memory.run=true}. What it proves is unchanged and stays documented in ADR-038 and
 * {@code docs/runbooks/reports-operations.md}. The report runner is off in every test context (see
 * {@link IntegrationTest}) and the child ticks its own runner, so nothing else can claim the child's runs.
 */
@Import(TestReports.class)
@EnabledIfSystemProperty(
        named = "reports.memory.run",
        matches = "true",
        disabledReason = "the memory proof is run on request: -Dreports.memory.run=true")
class ReportMemoryIT extends IntegrationTest {

    @Autowired
    LendingSeeder seeder;

    private String child(TestDatabase.Fixture t, boolean gate, String... extra) throws Exception {
        return child(t.tenantId().toString(), List.of("-Xmx224m", "-XX:+UseSerialGC"), 190, 224, gate, extra);
    }

    /** The staging heap or, with {@code heap}, another: a child JVM running {@link ReportMemoryMain} against the tenants. */
    private String child(String tenants, List<String> heap, int minHeapMb, int maxHeapMb, boolean gate, String... extra)
            throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>(List.of(javaBin));
        command.addAll(heap);
        command.addAll(List.of(
                "-XX:MaxMetaspaceSize=128m",
                "-XX:+ExitOnOutOfMemoryError",
                "-Djava.awt.headless=true",
                "-cp",
                System.getProperty("java.class.path"),
                ReportMemoryMain.class.getName(),
                TestDatabase.url(),
                TestDatabase.APP_PASSWORD,
                tenants));
        command.addAll(List.of(extra));
        // The child writes to a file, so a full pipe never stalls it and the wait below is a real bound (reading the
        // pipe
        // to its end would block for as long as the child lives): ten times what it takes on a quiet machine.
        java.io.File log = java.io.File.createTempFile("report-memory-", ".log");
        Process child = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start();
        boolean finished = child.waitFor(30, TimeUnit.MINUTES);
        if (!finished) {
            child.destroyForcibly().waitFor();
        }
        String output = java.nio.file.Files.readString(log.toPath(), StandardCharsets.UTF_8);
        log.delete();
        assertThat(finished)
                .as("the child finished within 30 minutes; its output: %s", output)
                .isTrue();
        String lines = output.lines().filter(l -> l.startsWith("MEMORY ")).reduce("", (a, b) -> a + b + "\n");
        assertThat(child.exitValue()).as(lines.isEmpty() ? output : lines).isZero();
        // -Xmx224m: the serial collector reports a little less (it keeps one survivor space out of the figure).
        Matcher heapMax = Pattern.compile("MEMORY maxHeapMb=(\\d+)").matcher(output);
        assertThat(heapMax.find()).as(output).isTrue();
        assertThat(Integer.parseInt(heapMax.group(1))).isBetween(minHeapMb, maxHeapMb);
        // The old generation's peak stays clear of the heap limit with a tenth spare
        // (promoted garbage counts in the peak, so this is a ceiling, not the live set; an out of memory error would
        // have ended the child).
        Matcher heapUse = Pattern.compile("oldPeakMb=(\\d+) oldMaxMb=(\\d+)").matcher(output);
        Matcher base = Pattern.compile("MEMORY oldBaselineMb=(\\d+)").matcher(output);
        if (!gate) {
            // Measurements print their figures and are judged by the caller.
            assertThat(heapUse.find()).as(output).isTrue();
            System.out.println("REPORT MEMORY " + String.join(" ", extra) + " " + heapUse.group(0));
            return output;
        }
        assertThat(heapUse.find() && base.find()).as(output).isTrue();
        long peak = Long.parseLong(heapUse.group(1));
        long max = Long.parseLong(heapUse.group(2));
        System.out.println(
                "REPORT MEMORY " + String.join(" ", extra) + " " + heapUse.group(0) + " baseline " + base.group(1));
        if (!gate) {
            return output;
        }
        assertThat(peak * 100)
                .as(
                        "old generation peak against its limit (%s, the application alone holds %s after a collection)",
                        heapUse.group(0), base.group(1))
                .isLessThanOrEqualTo(max * 90);
        return output;
    }

    private TestDatabase.Fixture seededTenant() {
        int scale = Integer.getInteger("reports.memory.scale", 2);
        TestDatabase.Fixture t = TestDatabase.tenant("report-memory", true);
        seeder.seed(t.slug(), new LendingSeeder.Options(true, scale));
        TestDatabase.owner().sql("""
                        INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?, '{"reports_enabled": true}')
                        ON CONFLICT (tenant_id) DO UPDATE SET settings = tenant_settings.settings || '{"reports_enabled": true}'
                        """).params(UUID.randomUUID(), t.tenantId()).update();
        return t;
    }

    @Test
    void theReportsRunOnAStagingSizedHeap() throws Exception {
        String output = child(seededTenant(), true);

        // The upload streams: files of megabytes were taken in blocks of at most 64 KiB, never as a whole.
        Matcher up = Pattern.compile("MEMORY upload maxReadBytes=(\\d+) largestFileBytes=(\\d+) totalBytes=(\\d+)")
                .matcher(output);
        assertThat(up.find()).as(output).isTrue();
        assertThat(Long.parseLong(up.group(1))).as("largest single read").isLessThanOrEqualTo(64 * 1024);
        assertThat(Long.parseLong(up.group(2))).as("largest file").isGreaterThan(7_000_000L);
        assertThat(Long.parseLong(up.group(3))).as("all files").isGreaterThan(15_000_000L);

        List<String> runs =
                output.lines().filter(l -> l.startsWith("MEMORY run ")).toList();
        assertThat(runs.stream().filter(l -> !l.contains("status=ready")).toList())
                .as("runs that did not end ready, with status and failure code; output: %s", output)
                .isEmpty();
        assertThat(runs).as(output).hasSize(15);
        // The whole book as one workbook, streamed: every lending table of the seeded tenant.
        assertThat(runs).as(output).anyMatch(l -> l.startsWith("MEMORY run core.data_export xlsx status=ready"));
        assertThat(output).contains("MEMORY run test.rows json status=ready rows=20000");
        assertThat(output).contains("MEMORY run test.rows csv status=ready rows=20000");
        // The files at the size cap: between 7 and 8 MiB each, stored from disk.
        for (String format : List.of("json", "csv")) {
            Matcher fat = Pattern.compile("MEMORY run test.fat " + format + " status=ready rows=20000 bytes=(\\d+)")
                    .matcher(output);
            assertThat(fat.find()).as(output).isTrue();
            assertThat(Long.parseLong(fat.group(1))).isBetween(7_000_000L, 8_388_608L);
        }
        // The renderers at their caps: a sheet of 19,999 rows and a total (20,000 rows, the cap of staging), and a PDF
        // of the
        // same table cut to 500 rows with its total, both ready, and the lending reports as PDF and Excel on the book.
        assertThat(output).contains("MEMORY run test.kinds xlsx status=ready rows=20000");
        assertThat(output).contains("MEMORY run test.kinds pdf status=ready rows=20000");
        for (String key : List.of("lending.par", "lending.portfolio_outstanding")) {
            for (String format : List.of("pdf", "xlsx")) {
                assertThat(runs)
                        .as("%s %s", key, format)
                        .anyMatch(l -> l.startsWith("MEMORY run " + key + " " + format));
            }
        }
        Matcher pdf = Pattern.compile("MEMORY run test.kinds pdf status=ready rows=20000 bytes=(\\d+)")
                .matcher(output);
        assertThat(pdf.find()).as(output).isTrue();
        // 500 rows of eight columns over many landscape pages: a few hundred kilobytes, not a table of 20,000 rows.
        assertThat(Long.parseLong(pdf.group(1))).isBetween(30_000L, 3_000_000L);
    }

    /**
     * A measurement, not a gate: the production PDF cap of 3,000 rows on the 224 MB staging heap. Staging itself sets 500
     * and passes the test above with room to spare; at 3,000 the run still finishes but the old generation fills its limit
     * (the old peak reaches its maximum), so there is no tenth to spare and the cap stays a production setting for hosts with
     * more heap. Run it with {@code -Dreports.memory.pdf3000=true}; the figures are in ADR-038 and the runbook.
     */
    @Test
    void thePdfAtTheProductionRowCapFinishesOnTheStagingHeap() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("reports.memory.pdf3000"), "a measurement, run with -Dreports.memory.pdf3000=true");
        String output = child(seededTenant(), false, "3000");
        List<String> runs =
                output.lines().filter(l -> l.startsWith("MEMORY run ")).toList();
        assertThat(runs.stream().filter(l -> !l.contains("status=ready")).toList())
                .as("runs that did not end ready, with status and failure code; output: %s", output)
                .isEmpty();
        assertThat(runs).as(output).hasSize(2);
        assertThat(output).contains("MEMORY run test.kinds pdf status=ready rows=20000");
    }

    /**
     * A measurement behind {@code -Dreports.memory.production=true}, not part of the build: the production shape, two
     * tenants asking for a PDF of 3,000 printed rows and a workbook of 20,000 each at the same time, on the heap of the
     * production container (1,536 MB of memory at 70 percent is 1,075 MB) with the default collector (G1), two runs at once.
     * Reports take one of the two render places, so the second PDF waits for the first; all four runs must be ready with no
     * out of memory error. The figures print as {@code REPORT MEMORY}; the runbook records them.
     */
    @Test
    void twoTenantsAskingForPdfsAtTheProductionRowCapOnTheProductionHeapAllFinish() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Boolean.getBoolean("reports.memory.production"),
                "a measurement, run with -Dreports.memory.production=true");
        TestDatabase.Fixture a = TestDatabase.tenant("report-memory-a", true);
        TestDatabase.Fixture b = TestDatabase.tenant("report-memory-b", true);
        for (TestDatabase.Fixture t : List.of(a, b)) {
            TestDatabase.owner()
                    .sql("""
                            INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?, '{"reports_enabled": true}')
                            ON CONFLICT (tenant_id) DO UPDATE SET settings = tenant_settings.settings || '{"reports_enabled": true}'
                            """)
                    .params(UUID.randomUUID(), t.tenantId())
                    .update();
        }
        String output = child(a.tenantId() + "," + b.tenantId(), List.of("-Xmx1075m"), 900, 1100, false, "3000", "2");
        List<String> runs =
                output.lines().filter(l -> l.startsWith("MEMORY run ")).toList();
        assertThat(runs.stream().filter(l -> !l.contains("status=ready")).toList())
                .as("runs that did not end ready, with status and failure code; output: %s", output)
                .isEmpty();
        assertThat(runs).as(output).hasSize(4);
        assertThat(runs.stream().filter(l -> l.contains("test.kinds pdf")).count())
                .isEqualTo(2);
    }
}
