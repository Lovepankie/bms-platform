package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.reporting.internal.XlsxReader.Sheet;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;

/**
 * The PDF and Excel renderers on the report framework (extras slice D2, issue #217; ADR-038 decisions 5 and 6, FR-RPT-02,
 * FR-RPT-05, FR-RPT-07), proved on the synthetic reports of {@link TestReports} so that every rule is shown without a loan
 * book: the shared letterhead, the parameters box, the figures in words, the landscape turn, the row cap with its pointer to
 * the Excel file, the total that survives the cut, escaping of markup and of scripts the font lacks, text that begins like a
 * formula, and the same numbers in the PDF, the workbook and the JSON of one report. The lending reports' own figures are
 * proved in {@link LendingReportFormatsIT}. Every figure is written by hand from the report's rule; nothing depends on the
 * date the suite runs.
 */
@Import(TestReports.class)
@TestPropertySource(
        properties = {
            "bms.reports.max-concurrent-runs=1",
            "bms.reports.timeout=PT30S",
            "bms.reports.xlsx-row-cap=500",
            "bms.reports.pdf-row-cap=200",
            "bms.reports.max-file-bytes=1048576",
            "bms.reports.cancel-poll=PT0.2S",
            "bms.reports.heartbeat=PT0.1S",
            "bms.reports.runner-enabled=false"
        })
class ReportFormatsIT extends ReportsItSupport {

    @MockitoSpyBean
    com.rincoltech.bms.core.documents.Documents documents;

    @Autowired
    javax.sql.DataSource dataSource;

    private PdfProbe pdf(TestDatabase.Fixture t, UUID user, JsonNode run) {
        assertThat(run.get("status").asString()).as("%s", run).isEqualTo("ready");
        byte[] bytes = downloadBytes(t, user, PORTFOLIO, run.get("document_id").asString());
        sample(sampleName(run) + ".pdf", bytes);
        return new PdfProbe(bytes);
    }

    private XlsxReader xlsx(TestDatabase.Fixture t, UUID user, JsonNode run) {
        assertThat(run.get("status").asString()).as("%s", run).isEqualTo("ready");
        byte[] bytes = downloadBytes(t, user, PORTFOLIO, run.get("document_id").asString());
        sample(sampleName(run) + ".xlsx", bytes);
        return XlsxReader.read(bytes);
    }

    /** The sample's name: the report and, for a synthetic one, the rows asked for, so two runs never overwrite each other. */
    private static String sampleName(JsonNode run) {
        JsonNode n = run.get("params").get("n");
        return run.get("report_key").asString() + (n == null ? "" : "-n" + n.asString());
    }

    /**
     * With {@code -Dbms.documents.samples}: the file, and a PNG of each page of a PDF, in {@code target/document-samples},
     * for the visual check of the ADR-034 quality bar (item 8; {@code make document-samples}).
     */
    static void sample(String name, byte[] bytes) {
        PdfProbe.sample("report-" + name, bytes);
    }

    // ---- the PDF ---------------------------------------------------------------------------------------

    @Test
    void aPdfCarriesTheLetterheadTheParametersBoxAndEveryFigureInWords() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        JsonNode run = runToTheEnd(t, user, "test.kinds", Map.of("n", 3), "pdf");
        PdfProbe p = pdf(t, user, run);

        // Eight columns: landscape A4 (842 x 595 points). Fonts are embedded. One page, "page 1 of 1" in the footer.
        assertThat(p.pageSize(0)[0]).isEqualTo(842f, org.assertj.core.data.Offset.offset(1f));
        assertThat(p.pageSize(0)[1]).isEqualTo(595f, org.assertj.core.data.Offset.offset(1f));
        assertThat(p.allFontsEmbedded()).isTrue();
        assertThat(p.pageCount()).isEqualTo(1);
        String text = p.text();
        assertThat(text).contains("page 1 of 1");
        // The title, the as-at date in business time (13:00 Kampala on 2026-10-08), the currency, who and when.
        assertThat(text)
                .contains("TEST REPORT TEST.KINDS")
                .contains("As at 8 Oct 2026")
                .contains("Currency UGX")
                .contains("Made 8 Oct 2026 13:00 (Africa/Kampala) by Staff")
                .contains("N 3")
                .doesNotContain("As at 8 Oct 2026 As at");
        // The document number is readable and unique to the run: RPT, the date, six characters of the run id.
        String id = run.get("id").asString().substring(0, 6).toUpperCase();
        assertThat(text).contains("RPT-20261008-" + id);
        // Headings: money names its currency. Rows: counts and money with separators, rates as one-decimal percentages,
        // dates in words, a missing figure as a dash (hand computed: 7 bp is 0.07 percent, shown 0.1%).
        assertThat(text)
                .contains("Group Loans Principal (UGX) Interest (UGX) PAR Share Since Note")
                .contains("Test Group 1 1 1,000,000 10,000 0.1% - 1 Oct 2026")
                .contains("Test Group 2 2 2,000,000 20,000 0.1% - 2 Oct 2026 even")
                .contains("Test Group 3 3 3,000,000 30,000 0.2% - 3 Oct 2026")
                // The total (1 + 2 + 3 = 6 loans) is set off and always printed.
                .contains("Total 6 6,000,000 60,000 - 100.0% -");
    }

    @Test
    void aNarrowReportStaysPortraitAndAnEmptyTableSaysSo() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        PdfProbe p = pdf(t, user, runToTheEnd(t, user, "test.rows", Map.of("n", 0), "pdf"));
        assertThat(p.pageSize(0)[0]).isEqualTo(595f, org.assertj.core.data.Offset.offset(1f));
        assertThat(p.pageSize(0)[1]).isEqualTo(842f, org.assertj.core.data.Offset.offset(1f));
        assertThat(p.text()).contains("No rows.");
    }

    @Test
    void aLongTableIsCutAtThePdfRowCapWithAPointerToTheExcelFileAndTheTotalIsKept() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        // 450 rows and a total: under the 500 row cap of every output, over the 200 row cap of the PDF.
        JsonNode pdfRun = runToTheEnd(t, user, "test.kinds", Map.of("n", 450), "pdf");
        PdfProbe p = pdf(t, user, pdfRun);
        String text = p.text();
        assertThat(p.pageCount())
                .as("a table of 200 rows runs over several pages")
                .isGreaterThan(3);
        assertThat(text)
                .contains("Test Group 1 ")
                .contains("Test Group 200 ")
                .doesNotContain("Test Group 201 ")
                .contains("Showing the first 200 of 450 rows. The full detail is in the Excel file.")
                // 450 x 451 / 2 = 101,475 loans in the total, which is kept when the middle is cut.
                .contains("Total 101,475 101,475,000,000 1,014,750,000");
        // The header repeats on every page: each page of the table carries the column headings.
        for (String page : p.pages().subList(0, 3)) {
            assertThat(page).contains("Group Loans Principal (UGX)");
        }
        // And "page x of y" is on every page.
        assertThat(p.pages().get(p.pageCount() - 1)).contains("page " + p.pageCount() + " of " + p.pageCount());

        // The workbook of the same report holds all 450 rows and the same total.
        XlsxReader x = xlsx(t, user, runToTheEnd(t, user, "test.kinds", Map.of("n", 450), "xlsx"));
        Sheet sh = x.sheet("Every kind");
        assertThat(sh.rows()).hasSize(1 + 450 + 1);
        assertThat(sh.text("A451")).isEqualTo("Test Group 450");
        assertThat(sh.text("A452")).isEqualTo("Total");
        assertThat(sh.cell("B452").value()).isEqualTo("101475");
        assertThat(sh.cell("C452").value()).isEqualTo("101475000000");
        // The JSON of the same run has the same total row as its last row.
        JsonNode json = new tools.jackson.databind.ObjectMapper()
                .readTree(download(
                        t,
                        user,
                        PORTFOLIO,
                        runToTheEnd(t, user, "test.kinds", Map.of("n", 450), "json")
                                .get("document_id")
                                .asString()));
        JsonNode rows = json.get("tables").get(0).get("rows");
        assertThat(rows).hasSize(451);
        assertThat(rows.get(450).get(1).asLong()).isEqualTo(101_475L);
        assertThat(rows.get(450).get(2).asLong()).isEqualTo(101_475_000_000L);
    }

    @Test
    void markupQuotesScriptsAndLongNamesAreEscapedAndAFontGapPrintsAsAQuestionMark() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        PdfProbe p = pdf(t, user, runToTheEnd(t, user, "test.markup", Map.of(), "pdf"));
        String text = p.text();
        // Markup prints as typed: nothing was interpreted, and the page was not broken.
        assertThat(text)
                .contains("Test <Branch> & \"Co\" 'North' >=< end 1,000")
                .contains("Ünï Çödé Ñandú 2,000")
                .contains("</td></tr><script>alert(1)</script> 4,000")
                .contains("Tab here and new line 5,000");
        // Scripts the font lacks print as "?" and the figure beside them is still there (ADR-034).
        assertThat(text).contains("Тестовый Банк").contains("????").doesNotContain("日本語");
        // A name of 120 unbroken letters wraps inside its cell: the page has no cut-off table, every letter is printed.
        long ws = text.chars().filter(c -> c == 'W').count();
        assertThat(ws).as("every W of the long name is printed").isGreaterThanOrEqualTo(120);
        assertThat(text).contains("6,000");
        assertThat(p.allFontsEmbedded()).isTrue();
    }

    @Test
    void thePdfIsLaidOutWithNoTransactionAndNoConnectionHeldByTheRunThread() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        org.mockito.Mockito.doAnswer(call -> {
                    // The layout of a long PDF is the slow part of a run: it must not hold a connection while it works.
                    seen.add(Thread.currentThread().getName()
                            + " tx=" + TransactionSynchronizationManager.isActualTransactionActive()
                            + " connection=" + TransactionSynchronizationManager.hasResource(dataSource));
                    return call.callRealMethod();
                })
                .when(documents)
                .renderPdf(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.<java.util.function.Supplier<Map<String, Object>>>any(),
                        org.mockito.ArgumentMatchers.any(Path.class),
                        org.mockito.ArgumentMatchers.anyLong());
        JsonNode run = runToTheEnd(t, user, "test.kinds", Map.of("n", 5), "pdf");
        assertThat(run.get("status").asString()).as("%s", run).isEqualTo("ready");
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).startsWith("report-run-").endsWith("tx=false connection=false");
    }

    // ---- the workbook ----------------------------------------------------------------------------------

    @Test
    void aWorkbookHasTheRealFiguresAndNamesInOneRowEachWithTheParametersFirst() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        JsonNode run = runToTheEnd(t, user, "test.kinds", Map.of("n", 3), "xlsx");
        XlsxReader x = xlsx(t, user, run);
        assertThat(x.sheets).extracting(Sheet::name).containsExactly("Parameters", "Every kind");
        Sheet sh = x.sheet("Every kind");
        // Row 2 is "Test Group 1": 1 loan, 1,000,000 principal, 10,000 interest, 7 bp (0.0007), 1 October 2026 (46296).
        assertThat(sh.text("A2")).isEqualTo("Test Group 1");
        assertThat(sh.cell("B2").value()).isEqualTo("1");
        assertThat(sh.cell("C2").value()).isEqualTo("1000000");
        assertThat(sh.cell("D2").value()).isEqualTo("10000");
        assertThat(sh.cell("E2").value()).isEqualTo("0.0007");
        assertThat(sh.cell("F2").value()).isNull();
        // 2026-10-01 is 7 days before 2026-10-08 (46303).
        assertThat(sh.cell("G2").value()).isEqualTo("46296");
        assertThat(sh.cell("G4").value()).isEqualTo("46298");
        assertThat(sh.text("H3")).isEqualTo("even");
        assertThat(sh.cell("H2").value()).isNull();
        // The total: 6 loans, 6,000,000 principal; the share is 100.00 percent, i.e. 1.
        assertThat(sh.text("A5")).isEqualTo("Total");
        assertThat(sh.cell("B5").value()).isEqualTo("6");
        assertThat(sh.cell("F5").value()).isEqualTo("1");
        assertThat(sh.orientation()).isEqualTo("landscape");
        Sheet params = x.sheet("Parameters");
        assertThat(params.text("A3")).isEqualTo("Report key");
        assertThat(params.text("B3")).isEqualTo("test.kinds");
        assertThat(params.cell("B4").value()).isEqualTo("46303");
        assertThat(run.get("format").asString()).isEqualTo("xlsx");
    }

    @Test
    void textThatBeginsLikeAFormulaIsAStringWithTheQuotePrefixInTheWorkbookAndPrintedAsTypedInThePdf()
            throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        XlsxReader x = xlsx(t, user, runToTheEnd(t, user, "test.evil", Map.of(), "xlsx"));
        Sheet sh = x.sheet("Names");
        for (int row = 2; row <= 5; row++) {
            XlsxReader.Cell c = sh.cell("A" + row);
            assertThat(c.isText()).isTrue();
            assertThat(x.styles.get(c.style()).quotePrefix()).as(c.value()).isTrue();
        }
        assertThat(sh.text("A2")).isEqualTo("=HYPERLINK(\"http://example.invalid\")");
        assertThat(sh.text("A3")).isEqualTo("+1+1");
        assertThat(sh.text("A4")).isEqualTo("-2");
        assertThat(sh.text("A5")).isEqualTo("@SUM(A1)");
        assertThat(sh.text("A6")).isEqualTo("Test, Borrower \"Quoted\"\nSecond line");
        assertThat(x.styles.get(sh.cell("A6").style()).quotePrefix()).isFalse();
        // The amounts beside them stay numbers, a loss included.
        assertThat(sh.cell("B2").value()).isEqualTo("-500");
        assertThat(sh.cell("B2").t()).isEmpty();
        // A missing name and amount are empty cells.
        assertThat(sh.cell("B7").value()).isNull();
        PdfProbe p = pdf(t, user, runToTheEnd(t, user, "test.evil", Map.of(), "pdf"));
        assertThat(p.text())
                .contains("=HYPERLINK(\"http://example.invalid\") -500")
                .contains("@SUM(A1) 8");
    }

    @Test
    void markupAndNonLatinNamesSurviveInTheWorkbookExactly() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        XlsxReader x = xlsx(t, user, runToTheEnd(t, user, "test.markup", Map.of(), "xlsx"));
        Sheet sh = x.sheet("Names");
        for (int i = 0; i < TestReports.MARKUP_NAMES.size(); i++) {
            assertThat(sh.text("A" + (i + 2))).isEqualTo(TestReports.MARKUP_NAMES.get(i));
            assertThat(sh.cell("B" + (i + 2)).value()).isEqualTo(String.valueOf((i + 1) * 1_000));
        }
        // The package is well formed XML in every part (the reader parsed them all) and no sheet holds a formula.
        assertThat(x.parts.keySet()).contains("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/styles.xml");
    }

    @Test
    void thePdfAndTheWorkbookAreNamedByNumberAndNeverByAPerson() throws Exception {
        TestDatabase.Fixture t = tenant(true);
        UUID user = UUID.randomUUID();
        JsonNode run = runToTheEnd(t, user, "test.markup", Map.of(), "xlsx");
        String doc = run.get("document_id").asString();
        // The stored document keeps the readable number, and the file served to a browser is named by type and id only.
        String number = TestDatabase.owner()
                .sql("SELECT doc_number FROM documents WHERE id = ?")
                .param(UUID.fromString(doc))
                .query(String.class)
                .single();
        assertThat(number).matches("RPT-20261008-[0-9A-F]{6}");
        assertThat(number)
                .isEqualTo("RPT-20261008-"
                        + run.get("id").asString().substring(0, 6).toUpperCase());
        String contentType = TestDatabase.owner()
                .sql("SELECT content_type FROM documents WHERE id = ?")
                .param(UUID.fromString(doc))
                .query(String.class)
                .single();
        assertThat(contentType).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        var url = http.exchange(
                "/api/v1/documents/" + doc + "/download-url",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(headers(t, user, PORTFOLIO)),
                JsonNode.class);
        var h = new org.springframework.http.HttpHeaders();
        h.add("X-Tenant", t.slug());
        var file = http.exchange(
                java.net.URI.create(http.getRootUri() + url.getBody().get("url").asString()),
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(h),
                byte[].class);
        assertThat(file.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename=\"report-" + doc + ".xlsx\"");
    }
}
