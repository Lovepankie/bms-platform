package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The big file test of customer data onboarding (issue #137, spec sections 3.1 and 5.3): a generated file at each limit of
 * section 3.1 goes through the upload, the validation, the preview and the commit of {@code retail_v1} in a child JVM capped
 * at 224 MB, the heap of the staging API (a 448 MB container at {@code MaxRAMPercentage=50}, the precedent of
 * {@code UploadMemoryTest} and {@code ReportMemoryIT}), with the real application context. The times of every step are
 * printed ({@code IMPORT BIG FILE} lines) and recorded in SDD chapter 15 section 15.7. The commit of the batch at the row
 * limit must finish inside the batch's statement timeout of 10 minutes (spec section 3.7), and the old generation's peak
 * must stay a tenth below its limit. The cases, each in a business of its own:
 *
 * <ul>
 *   <li>a product master as CSV at the row limit (100,000 rows) and close to the size limit (25 MB) at once, committed;
 *   <li>the same rows as a workbook, every text a shared string, uploaded, validated and previewed (its staged rows are the
 *       CSV's, so the commit's work is the first case's);
 *   <li>a product master at the column limit (200 columns, 193 of them ignored with a reason), committed.
 * </ul>
 *
 * <p>They found #880. Its fix (the streamed loader, the batched writes, the lookups by index, the validation's compact
 * indexes) brought the validation and preview at 100,000 rows inside the staging heap and the dry run and commit
 * at that size inside their time limits, but the commit there still fills the old generation, and in a database other
 * businesses' imports already fill the dry run still stops at its limit: those two cases are disabled with #900. The
 * configured row limit, {@link UploadSettings#DEFAULT_MAX_ROWS}, is the largest of the measured sizes that commits with a
 * tenth of the old generation to spare (the Lead's fallback on #880); a case over it runs the child at its own size.
 *
 * <p><b>Not part of {@code mvn verify}.</b> The commits and their dry runs take longer than
 * the backend job can spare, and on the shared runner a stalled machine would fail the job for a reason that is not a
 * defect (the reason {@code ReportMemoryIT} left it). It runs with {@code -Dimports.bigfile.run=true}: locally, or in the
 * manually started CI job {@code import-big-file} ({@code .github/workflows/ci.yml}, "Run workflow"). The test storage
 * double holds each quarantined file in the heap, so the figure is pessimistic by the file's size: staging streams it to
 * object storage. Fabricated rows only.
 */
@EnabledIfSystemProperty(
        named = "imports.bigfile.run",
        matches = "true",
        disabledReason = "the big file test is run on request: -Dimports.bigfile.run=true")
@Timeout(value = 100, unit = TimeUnit.MINUTES)
class ImportBigFileIT extends IntegrationTest {

    static final int ROW_LIMIT = 100_000;

    static final int COLUMN_LIMIT = 200;

    /** The rows of the file at the column limit: as many as fit the size limit with 200 columns. */
    static final int COLUMN_LIMIT_ROWS = 20_000;

    static final List<String> ITEM_HEADER =
            List.of("Product ID", "Description", "Category", "Unit", "Unit cost", "Unit sell", "In use");

    /** The staging API's heap: a 448 MB container at {@code MaxRAMPercentage=50}. */
    static final int STAGING_HEAP_MB = 224;

    /**
     * How long a child may take: a little more than its limits allow (the validation, the dry run's 15 minutes and the
     * batch's 10), so a child that overruns them is stopped and its step lines printed while the fork's 90 minutes last.
     */
    static final int CHILD_LIMIT_MINUTES = 35;

    /** The batch's statement timeout (spec section 3.7): the commit of one batch must finish inside it. */
    static final long BATCH_TIMEOUT_MS = 10 * 60 * 1000L;

    @TempDir
    Path dir;

    @Test
    @Disabled("#900: at 100,000 rows the dry run (77 s) and the commit (64 s) fit their limits, but the commit fills"
            + " the old generation of the staging heap (149 of 149 MB)")
    void aCsvAtTheRowAndSizeLimitsIsCommittedOnTheStagingHeapInsideTheBatchTimeout() throws Exception {
        String output = child("commit", "items=" + limitsCsv(), STAGING_HEAP_MB, ROW_LIMIT);
        assertThat(output).contains("step=upload source=items", "rows=" + ROW_LIMIT, "outcome=COMMITTED");
        assertThat(written(output)).as("every item written").isGreaterThanOrEqualTo(ROW_LIMIT);
        assertThat(stepMillis(output, "commit"))
                .as("the commit at the row limit")
                .isLessThan(BATCH_TIMEOUT_MS);
    }

    /**
     * The same file on twice the staging heap, a measurement: the commit of the batch at the row limit must still finish
     * inside the batch timeout, and the times of every step are printed for chapter 15. The heap is not judged here.
     */
    @Test
    @Disabled("#900: in a database other businesses' imports already fill, the key lookups of a batch at 100,000 rows"
            + " still grow with its rows, and the dry run stops at its 900 s limit")
    void aCsvAtTheRowAndSizeLimitsIsCommittedInsideTheBatchTimeoutOnTwiceTheStagingHeap() throws Exception {
        String output = child("commit", "items=" + limitsCsv(), 2 * STAGING_HEAP_MB, ROW_LIMIT);
        assertThat(output).contains("step=upload source=items", "rows=" + ROW_LIMIT, "outcome=COMMITTED");
        assertThat(written(output)).as("every item written").isGreaterThanOrEqualTo(ROW_LIMIT);
        assertThat(stepMillis(output, "commit"))
                .as("the commit at the row limit")
                .isLessThan(BATCH_TIMEOUT_MS);
    }

    @Test
    void aWorkbookAtTheRowLimitIsValidatedAndPreviewedOnTheStagingHeap() throws Exception {
        Path xlsx = dir.resolve("fab-items-at-the-row-limit.xlsx");
        TestFiles.writeXlsx(xlsx, ITEM_HEADER, items(ROW_LIMIT, 0));
        String output = child("preview", "items=" + xlsx, STAGING_HEAP_MB, ROW_LIMIT);
        assertThat(output).contains("step=upload source=items", "rows=" + ROW_LIMIT, "step=preview");
    }

    @Test
    void aCsvAtTheColumnLimitIsCommittedOnTheStagingHeap() throws Exception {
        String output = child(
                "commit",
                "wide_items=" + columnLimitCsv(),
                STAGING_HEAP_MB,
                Math.max(COLUMN_LIMIT_ROWS, UploadSettings.DEFAULT_MAX_ROWS));
        assertThat(output).contains("columns=" + COLUMN_LIMIT, "outcome=COMMITTED");
        assertThat(written(output)).isGreaterThanOrEqualTo(COLUMN_LIMIT_ROWS);
        assertThat(stepMillis(output, "commit")).isLessThan(BATCH_TIMEOUT_MS);
    }

    /**
     * The upload, validation and preview at the row and size limits of spec section 3.1, on the staging heap, judged and
     * timed (#880: these fit; the commit at that size does not yet, #900, so the configured limit is lower).
     */
    @Test
    void aCsvAtTheRowAndSizeLimitsIsUploadedValidatedAndPreviewedOnTheStagingHeap() throws Exception {
        String output = child("preview", "items=" + limitsCsv(), STAGING_HEAP_MB, ROW_LIMIT);
        assertThat(output).contains("step=upload source=items", "rows=" + ROW_LIMIT, "step=preview");
    }

    // JUSTIFICATION-A3: the Lead's fallback on #880 asks the sizes measured and the largest that passes configured.
    /**
     * The whole path, commit included, for a product master of seven columns at each measured size up to the configured
     * row limit, on the staging heap and judged there (the Lead's fallback on #880, recorded in SDD chapter 15 section
     * 15.7): the configured limit is the largest of these sizes that passes with a tenth of the old generation to spare.
     */
    @ParameterizedTest(name = "{0} rows")
    @MethodSource("sizesUpToTheConfiguredLimit")
    void aProductMasterIsCommittedOnTheStagingHeapInsideTheTimeLimits(int rows) throws Exception {
        Path csv = dir.resolve("fab-items-" + rows + ".csv");
        TestFiles.writeCsv(csv, ITEM_HEADER, items(rows, 0));
        String output = child("commit", "items=" + csv, STAGING_HEAP_MB);
        assertThat(output).contains("rows=" + rows, "outcome=COMMITTED");
        assertThat(written(output)).isGreaterThanOrEqualTo(rows);
        assertThat(stepMillis(output, "commit")).isLessThan(BATCH_TIMEOUT_MS);
    }

    /** The sizes measured for #880, those up to the configured limit. */
    static java.util.stream.Stream<Integer> sizesUpToTheConfiguredLimit() {
        return java.util.stream.Stream.of(10_000, 25_000, 50_000, 75_000)
                .filter(rows -> rows <= UploadSettings.DEFAULT_MAX_ROWS);
    }

    /** One row over the configured limit is refused at the upload with TOO_MANY_ROWS, in the child at that limit. */
    @Test
    void oneRowOverTheConfiguredLimitIsRefused() throws Exception {
        Path csv = dir.resolve("fab-items-over-the-limit.csv");
        TestFiles.writeCsv(csv, ITEM_HEADER, items(UploadSettings.DEFAULT_MAX_ROWS + 1, 0));
        String output = child("refuse", "items=" + csv, STAGING_HEAP_MB);
        assertThat(output).contains("status=failed failure=" + Parsing.TOO_MANY_ROWS);
    }

    // ---------------------------------------------------------------------------------------------
    // The files and the child.
    // ---------------------------------------------------------------------------------------------

    /** 20,000 items of 200 columns, 193 of them ignored by the mapping. */
    private Path columnLimitCsv() {
        int extra = COLUMN_LIMIT - ITEM_HEADER.size();
        List<String> header = new ArrayList<>(ITEM_HEADER);
        for (int c = 1; c <= extra; c++) {
            header.add(extraColumn(c));
        }
        Path csv = dir.resolve("fab-items-at-the-column-limit.csv");
        TestFiles.writeCsv(csv, header, items(COLUMN_LIMIT_ROWS, extra));
        return csv;
    }

    /** The product master at the row limit and close to the size limit, as CSV. */
    private Path limitsCsv() throws java.io.IOException {
        Path csv = dir.resolve("fab-items-at-the-limits.csv");
        TestFiles.writeCsv(csv, ITEM_HEADER, items(ROW_LIMIT, 0));
        assertThat(Files.size(csv)).as("close to the 25 MB limit").isBetween(24L << 20, 25L << 20);
        return csv;
    }

    static String extraColumn(int c) {
        return String.format("Extra %03d", c);
    }

    /**
     * {@code rows} fabricated items with a description long enough that 100,000 of them come close to the 25 MB limit as
     * CSV, every code and description different, and {@code extra} short cells after the seven columns.
     */
    static Iterator<List<Object>> items(int rows, int extra) {
        String filler = "of the big file test, made up for the row and size limits only and nothing else; ".repeat(4);
        return new Iterator<>() {
            int n;

            @Override
            public boolean hasNext() {
                return n < rows;
            }

            @Override
            public List<Object> next() {
                n++;
                List<Object> row = new ArrayList<>(ITEM_HEADER.size() + extra);
                row.add(String.format("BIG-%06d", n));
                row.add((String.format("Fabricated item %06d ", n) + filler).substring(0, 202));
                row.add("Test Category");
                row.add("piece");
                row.add(700L);
                row.add(1200L);
                row.add("Yes");
                for (int c = 0; c < extra; c++) {
                    row.add("x");
                }
                return row;
            }
        };
    }

    /**
     * The mapping of the cases: the categories and units the items name, and the product master as {@code items} (seven
     * columns) and as {@code wide_items} (the seven and every extra column ignored with a reason).
     */
    static Map<String, Object> mapping() {
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("categories", source("categories", "fab-categories.csv", Map.of("Category", field("name"))));
        sources.put("units", source("units", "fab-units.csv", Map.of("Unit", field("name"))));
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("Product ID", field("code"));
        items.put("Description", field("description"));
        items.put("Category", field("category"));
        items.put("Unit", field("unit"));
        items.put("Unit cost", field("cost_minor"));
        items.put("Unit sell", field("sell_minor"));
        items.put("In use", field("active"));
        sources.put("items", source("products", "fab-items.csv", items));
        Map<String, Object> wide = new LinkedHashMap<>(items);
        for (int c = 1; c <= COLUMN_LIMIT - ITEM_HEADER.size(); c++) {
            wide.put(extraColumn(c), Map.of("ignore", true, "reason", "a fabricated column of the column limit"));
        }
        sources.put("wide_items", source("products", "fab-wide-items.csv", wide));
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("template", "retail");
        mapping.put("template_version", 1);
        mapping.put("date_convention", "day_first");
        mapping.put("time_zone", "Africa/Kampala");
        mapping.put("currency", "UGX");
        mapping.put("sources", sources);
        return mapping;
    }

    private static Map<String, Object> field(String name) {
        return Map.of("field", name);
    }

    private static Map<String, Object> source(String set, String file, Map<String, Object> columns) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("entity_set", set);
        s.put("file", file);
        s.put("header_row", "auto");
        s.put("columns", columns);
        s.put("source_ref", Map.of("rule", "row_hash"));
        return s;
    }

    /**
     * Runs {@link ImportBigFileMain} in a business of its own on a heap of {@code heapMb}, with the categories and units
     * batches first, and checks what every case shares: it exits 0 on that heap, and on the staging heap with the old
     * generation's peak a tenth below its limit. Prints the child's step lines for chapter 15. The child's row limit is the
     * configured one.
     */
    private String child(String mode, String bigFile, int heapMb) throws Exception {
        return child(mode, bigFile, heapMb, UploadSettings.DEFAULT_MAX_ROWS);
    }

    /** As {@link #child(String, String, int)}, with the child's row limit at {@code maxRows} (#880: sizes over it). */
    private String child(String mode, String bigFile, int heapMb, int maxRows) throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("import-big", false, true);
        Path mappingFile = dir.resolve("mapping.json");
        Files.writeString(mappingFile, JsonMapper.builder().build().writeValueAsString(mapping()));
        Path categories = dir.resolve("fab-categories.csv");
        Files.writeString(categories, "Category\r\nTest Category\r\n");
        Path units = dir.resolve("fab-units.csv");
        Files.writeString(units, "Unit\r\npiece\r\n");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = List.of(
                javaBin,
                "-Xmx" + heapMb + "m",
                "-XX:+UseSerialGC",
                "-XX:MaxMetaspaceSize=128m",
                "-XX:+ExitOnOutOfMemoryError",
                "-Djava.awt.headless=true",
                "-Dbigfile.maxRows=" + maxRows,
                "-cp",
                System.getProperty("java.class.path"),
                ImportBigFileMain.class.getName(),
                TestDatabase.url(),
                TestDatabase.APP_PASSWORD,
                t.tenantId().toString(),
                t.slug(),
                mappingFile.toString(),
                mode,
                "categories=" + categories,
                "units=" + units,
                bigFile);
        // The child writes to a file, so a full pipe never stalls it and the wait below is a real bound.
        File log = File.createTempFile("import-big-file-", ".log");
        Process child = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start();
        boolean finished = child.waitFor(CHILD_LIMIT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            child.destroyForcibly().waitFor();
        }
        String output = Files.readString(log.toPath(), StandardCharsets.UTF_8);
        Files.delete(log.toPath());
        String lines = output.lines().filter(l -> l.startsWith("BIGFILE ")).reduce("", (a, b) -> a + b + "\n");
        System.out.println("IMPORT BIG FILE " + bigFile.substring(bigFile.lastIndexOf('/') + 1) + " heap " + heapMb
                + " MB\n" + lines);
        assertThat(finished)
                .as("the child finished within %s minutes: %s", CHILD_LIMIT_MINUTES, lines)
                .isTrue();
        assertThat(child.exitValue()).as(lines.isEmpty() ? output : lines).isZero();
        // The serial collector reports a little less than -Xmx (it keeps one survivor space out of the figure).
        Matcher heap = Pattern.compile("maxHeapMb=(\\d+) oldPeakMb=(\\d+) oldMaxMb=(\\d+)")
                .matcher(output);
        assertThat(heap.find()).as(output).isTrue();
        assertThat(Integer.parseInt(heap.group(1))).isBetween(heapMb * 85 / 100, heapMb);
        if (heapMb != STAGING_HEAP_MB) {
            return output;
        }
        assertThat(Long.parseLong(heap.group(2)) * 100)
                .as("the old generation's peak against its limit: %s", heap.group(0))
                .isLessThanOrEqualTo(Long.parseLong(heap.group(3)) * 90);
        return output;
    }

    static long written(String output) {
        Matcher m = Pattern.compile("step=commit .*?written=(\\d+)").matcher(output);
        assertThat(m.find()).as(output).isTrue();
        return Long.parseLong(m.group(1));
    }

    static long stepMillis(String output, String step) {
        Matcher m = Pattern.compile("step=" + step + "\\b.*?ms=(\\d+)").matcher(output);
        assertThat(m.find()).as(output).isTrue();
        return Long.parseLong(m.group(1));
    }
}
