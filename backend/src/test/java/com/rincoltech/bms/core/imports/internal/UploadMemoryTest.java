package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Issue #128, acceptance criterion 4: a file at the row limit parses with the API heap of the staging profile. The staging
 * API runs in a 448 MB container with {@code MaxRAMPercentage=50}, so about 224 MB of heap; the child JVM is capped at
 * exactly that, and an out of memory error fails this test rather than a cutover. Each file has 100,000 rows below its
 * header (the row limit), the CSV close to the 25 MB size limit; the XLSX keeps every text in its shared strings, the
 * worst case for its reader. Fabricated rows only.
 */
class UploadMemoryTest {

    static final int ROWS = 100_000;

    @TempDir
    Path dir;

    @Test
    void aCsvAtTheRowLimitParsesOnTheStagingHeap() throws Exception {
        Path csv = dir.resolve("fabricated-at-the-limit.csv");
        TestFiles.writeCsv(csv, TestFiles.header(12), TestFiles.generated(ROWS, 12));
        assertThat(Files.size(csv)).as("close to the 25 MB limit").isBetween(18L << 20, 25L << 20);
        assertParsesInChild(csv);
    }

    @Test
    void anXlsxAtTheRowLimitParsesOnTheStagingHeap() throws Exception {
        Path xlsx = dir.resolve("fabricated-at-the-limit.xlsx");
        TestFiles.writeXlsx(xlsx, TestFiles.header(12), TestFiles.generated(ROWS, 12));
        assertParsesInChild(xlsx);
    }

    // JUSTIFICATION-A3: the heap proof the review of 339d6a2 asks for; the child is the one the other tests run.
    @Test
    void oneLongSharedTextInEveryCellIsRefusedQuicklyOnTheStagingHeap() throws Exception {
        // One 32,767 character shared string in every cell of 1,000 rows of 200 columns: 6.5 billion characters if
        // every use expanded. Refused at the expansion cap in seconds, never holding more than a chunk of a few MB.
        Path xlsx = dir.resolve("fabricated-one-text-everywhere.xlsx");
        Files.write(xlsx, ReaderLimitsTest.oneLongTextInEveryCell());
        long started = System.nanoTime();
        String output = runChild(xlsx);
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started))
                .as("refused quickly")
                .isLessThan(60);
        Matcher m = Pattern.compile("refused=(\\S+) .*largestChunkChars=(\\d+)").matcher(output);
        assertThat(m.find()).as(output).isTrue();
        assertThat(m.group(1)).isEqualTo(Parsing.FILE_TOO_LARGE);
        assertThat(Long.parseLong(m.group(2))).isLessThan(SourceParser.CHUNK_CHARS + 200L * 33_000);
    }

    private static void assertParsesInChild(Path file) throws Exception {
        String output = runChild(file);
        Matcher m = Pattern.compile("rows=(\\d+) .*largestChunk=(\\d+)").matcher(output);
        assertThat(m.find()).as(output).isTrue();
        assertThat(Integer.parseInt(m.group(1))).isEqualTo(ROWS);
        assertThat(Integer.parseInt(m.group(2))).as("never more than one chunk").isEqualTo(1_000);
    }

    /** Runs {@link UploadMemoryMain} on the file at the staging heap; an out of memory error exits non-zero. */
    private static String runChild(Path file) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(List.of(
                        javaBin,
                        "-Xmx224m",
                        "-XX:+UseG1GC",
                        "-XX:+ExitOnOutOfMemoryError",
                        "-cp",
                        System.getProperty("java.class.path"),
                        UploadMemoryMain.class.getName(),
                        file.toString()))
                .redirectErrorStream(true)
                .start();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(child.waitFor(300, TimeUnit.SECONDS))
                .as("the child finished")
                .isTrue();
        assertThat(child.exitValue()).as(output).isZero();
        assertThat(output).contains("maxHeapMb=224");
        return output;
    }
}
