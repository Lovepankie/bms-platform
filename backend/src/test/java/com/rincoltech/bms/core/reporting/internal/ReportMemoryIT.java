package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.lending.seed.internal.LendingSeeder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * ADR-038 decision 7 and {@code docs/specs/client-extras.md} section 6.3: report runs on the smallest host's heap. The
 * parent seeds a fabricated book on a full heap; the runs themselves execute in a child JVM capped at 224 MB, the heap of
 * the staging API, with the staging limits (one run at a time, 20,000 rows a table, an 8 MiB file) and the real
 * application context, so an out of memory error fails this test and not a production run (the precedent is
 * {@code StatementMemoryTest}). Three {@code lending.par} runs read the seeded book, and the synthetic report writes the
 * largest table the staging cap allows, as JSON and as CSV: the rows go to the spool one at a time, so no table is ever
 * held in memory. The pack of slice D2 joins this test when it exists. The book is twice the demo seed; raise it with
 * {@code -Dreports.memory.scale=N}.
 */
@Import(TestReports.class)
class ReportMemoryIT extends IntegrationTest {

    @Autowired
    LendingSeeder seeder;

    @Test
    void theReportsRunOnAStagingSizedHeap() throws Exception {
        int scale = Integer.getInteger("reports.memory.scale", 2);
        TestDatabase.Fixture t = TestDatabase.tenant("report-memory", true);
        seeder.seed(t.slug(), new LendingSeeder.Options(true, scale));
        TestDatabase.owner().sql("""
                        INSERT INTO tenant_settings (id, tenant_id, settings) VALUES (?, ?, '{"reports_enabled": true}')
                        ON CONFLICT (tenant_id) DO UPDATE SET settings = tenant_settings.settings || '{"reports_enabled": true}'
                        """).params(UUID.randomUUID(), t.tenantId()).update();

        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(List.of(
                        javaBin,
                        "-Xmx224m",
                        "-XX:MaxMetaspaceSize=128m",
                        "-XX:+UseSerialGC",
                        "-XX:+ExitOnOutOfMemoryError",
                        "-Djava.awt.headless=true",
                        "-cp",
                        System.getProperty("java.class.path"),
                        ReportMemoryMain.class.getName(),
                        TestDatabase.url(),
                        TestDatabase.APP_PASSWORD,
                        t.tenantId().toString()))
                .redirectErrorStream(true)
                .start();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(child.waitFor(5, TimeUnit.MINUTES)).as("the child finished").isTrue();
        String lines = output.lines().filter(l -> l.startsWith("MEMORY ")).reduce("", (a, b) -> a + b + "\n");
        assertThat(child.exitValue()).as(lines.isEmpty() ? output : lines).isZero();
        // -Xmx224m: the serial collector reports a little less (it keeps one survivor space out of the figure).
        java.util.regex.Matcher heap =
                java.util.regex.Pattern.compile("MEMORY maxHeapMb=(\\d+)").matcher(output);
        assertThat(heap.find()).as(output).isTrue();
        assertThat(Integer.parseInt(heap.group(1))).isBetween(190, 224);
        // The upload streams: files of megabytes were taken in blocks of at most 64 KiB, never as a whole.
        java.util.regex.Matcher up = java.util.regex.Pattern.compile(
                        "MEMORY upload maxReadBytes=(\\d+) largestFileBytes=(\\d+) totalBytes=(\\d+)")
                .matcher(output);
        assertThat(up.find()).as(output).isTrue();
        assertThat(Long.parseLong(up.group(1))).as("largest single read").isLessThanOrEqualTo(64 * 1024);
        assertThat(Long.parseLong(up.group(2))).as("largest file").isGreaterThan(7_000_000L);
        assertThat(Long.parseLong(up.group(3))).as("all files").isGreaterThan(15_000_000L);
        // The old generation's peak stays clear of the heap limit with a tenth spare
        // (promoted garbage counts in the peak, so this is a ceiling, not the live set; an out of memory error would
        // have ended the child).
        java.util.regex.Matcher heapUse = java.util.regex.Pattern.compile("oldPeakMb=(\\d+) oldMaxMb=(\\d+)")
                .matcher(output);
        java.util.regex.Matcher base =
                java.util.regex.Pattern.compile("MEMORY oldBaselineMb=(\\d+)").matcher(output);
        assertThat(heapUse.find() && base.find()).as(output).isTrue();
        long peak = Long.parseLong(heapUse.group(1));
        long max = Long.parseLong(heapUse.group(2));
        long baseline = Long.parseLong(base.group(1));
        assertThat(peak * 100)
                .as(
                        "old generation peak against its limit (%s, the application alone holds %s after a collection)",
                        heapUse.group(0), baseline)
                .isLessThanOrEqualTo(max * 90);
        assertThat(output.lines().filter(l -> l.startsWith("MEMORY run ")).toList())
                .as(lines)
                .hasSize(7)
                .allMatch(l -> l.contains("status=ready"));
        assertThat(output).contains("MEMORY run test.rows json status=ready rows=20000");
        assertThat(output).contains("MEMORY run test.rows csv status=ready rows=20000");
        // The files at the size cap: between 7 and 8 MiB each, stored from disk.
        for (String format : List.of("json", "csv")) {
            java.util.regex.Matcher fat = java.util.regex.Pattern.compile(
                            "MEMORY run test.fat " + format + " status=ready rows=20000 bytes=(\\d+)")
                    .matcher(output);
            assertThat(fat.find()).as(output).isTrue();
            assertThat(Long.parseLong(fat.group(1))).isBetween(7_000_000L, 8_388_608L);
        }
    }
}
