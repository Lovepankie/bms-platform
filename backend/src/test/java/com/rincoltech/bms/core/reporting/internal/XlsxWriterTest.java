package com.rincoltech.bms.core.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.reporting.ReportFailure;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.core.reporting.internal.Renderers.Header;
import com.rincoltech.bms.core.reporting.internal.Spool.TableMeta;
import com.rincoltech.bms.core.reporting.internal.XlsxReader.Cell;
import com.rincoltech.bms.core.reporting.internal.XlsxReader.Sheet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * The streaming workbook writer on its own (ADR-038 decision 6, FR-RPT-07, FR-RPT-05): every figure is a number of the
 * right kind and exact value, text is always a string cell and a text that begins like a formula carries the quote prefix,
 * markup and characters XML cannot hold are handled, sheet names are legal and unique, a file past its cap ends the run, and
 * the whole package parses as XML. Every name and amount is fabricated; the expected numbers are written by hand.
 */
class XlsxWriterTest {

    private static final Instant MADE = Instant.parse("2026-10-08T10:05:00Z");

    @TempDir
    Path dir;

    private Spool newSpool() {
        return new Spool(dir.resolve("spool"), JsonMapper.builder().build());
    }

    private Header header(String currency) {
        return new Header(
                "test.sheet",
                "Test report",
                Map.of("group_by", "branch"),
                LocalDate.of(2026, 10, 8),
                Instant.parse("2026-10-08T10:00:00Z"),
                MADE,
                currency,
                List.of("Only active loans are counted."));
    }

    private Presentation presentation() {
        return new Presentation(
                "Test User",
                ZoneId.of("Africa/Kampala"),
                "Test Business Ltd",
                "#0D5C75",
                "#FFFFFF",
                "#E3EFF3",
                "Footer",
                null,
                "All branches in your access",
                List.of(new Presentation.Line("Group by", "Branch"), new Presentation.Line("Branches", "2 selected")));
    }

    private XlsxReader write(String currency, long maxBytes, List<TableMeta> tables, Spool s) {
        Path file = dir.resolve("out.xlsx");
        XlsxWriter.write(file, maxBytes, header(currency), presentation(), tables, s, () -> {});
        return XlsxReader.read(file);
    }

    private static final List<Column> KINDS = List.of(
            new Column("name", "Name", Kind.TEXT),
            new Column("loans", "Loans", Kind.COUNT),
            new Column("po", "Principal", Kind.MONEY),
            new Column("par", "PAR 30", Kind.BASIS_POINTS),
            new Column("since", "Since", Kind.DATE));

    @Test
    void everyKindIsANumberOfTheRightTypeAndExactValueAndATotalIsSetOff() {
        Spool s = newSpool();
        try (Spool.Writer out = s.writer(100, 1_000_000, () -> {})) {
            var t = out.table("rows", "Branches", KINDS);
            t.row("Head Office", 3, 7_200_000_000_000L, 1389, LocalDate.of(2026, 10, 8));
            t.row("Second Branch", 0, -5_000L, null, null);
            t.total("Total", 3, 7_199_999_995_000L, 1800, null);
            XlsxReader x = write("UGX", 10_000_000, out.finish(), s);

            assertThat(x.sheets).extracting(Sheet::name).containsExactly("Parameters", "Branches");
            Sheet sh = x.sheet("Branches");
            assertThat(sh.frozenHeader()).isTrue();
            // Header row: text headings, the money heading names the currency.
            assertThat(sh.text("A1")).isEqualTo("Name");
            assertThat(sh.text("C1")).isEqualTo("Principal (UGX)");
            // Row 2: exact integers, never a float, and the rate as the fraction 0.1389 (shown 13.9%).
            assertThat(sh.cell("A2").isText()).isTrue();
            assertThat(sh.cell("B2")).extracting(Cell::t, Cell::value).containsExactly("", "3");
            assertThat(sh.cell("C2").value()).isEqualTo("7200000000000");
            assertThat(sh.cell("D2").value()).isEqualTo("0.1389");
            // 2026-10-08 is day 46303 of the 1900 date system (2026-01-01 is 46023, and 8 October is the 281st day).
            assertThat(sh.cell("E2").value()).isEqualTo("46303");
            // Row 3: a negative amount, a missing rate and date are empty cells with no value at all.
            assertThat(sh.cell("C3").value()).isEqualTo("-5000");
            assertThat(sh.cell("D3").value()).isNull();
            assertThat(sh.cell("E3").value()).isNull();
            assertThat(sh.cell("D3").t()).isEmpty();
            // Number formats: counts and money with thousands separators, rate as 0.0%, date as yyyy-mm-dd.
            assertThat(x.styles.get(sh.cell("B2").style()).numFmt()).isEqualTo("3");
            assertThat(x.styles.get(sh.cell("C2").style()).numFmt()).isEqualTo("3");
            assertThat(x.numFmts.get(x.styles.get(sh.cell("D2").style()).numFmt()))
                    .isEqualTo("0.0%");
            assertThat(x.numFmts.get(x.styles.get(sh.cell("E2").style()).numFmt()))
                    .contains("yyyy");
            // Row 4 is the total: bold and ruled, the same figures as the table's own code wrote.
            assertThat(sh.text("A4")).isEqualTo("Total");
            assertThat(sh.cell("C4").value()).isEqualTo("7199999995000");
            assertThat(x.styles.get(sh.cell("C4").style()).bold()).isTrue();
            assertThat(x.styles.get(sh.cell("C4").style()).topBorder()).isTrue();
            assertThat(x.styles.get(sh.cell("C2").style()).bold()).isFalse();
            assertThat(sh.rows()).hasSize(4);
        }
    }

    @Test
    void aCurrencyWithAMinorUnitIsWrittenAsTheExactDecimalAndFormattedWithTwoPlaces() {
        Spool s = newSpool();
        try (Spool.Writer out = s.writer(100, 1_000_000, () -> {})) {
            out.table("rows", "Rows", List.of(new Column("po", "Principal", Kind.MONEY)))
                    .row(123_456L);
            XlsxReader x = write("USD", 10_000_000, out.finish(), s);
            Sheet sh = x.sheet("Rows");
            assertThat(sh.cell("A2").value()).isEqualTo("1234.56");
            assertThat(x.styles.get(sh.cell("A2").style()).numFmt()).isEqualTo("4");
            assertThat(sh.text("A1")).isEqualTo("Principal (USD)");
        }
    }

    @Test
    void textThatBeginsLikeAFormulaIsAStringCellWithTheQuotePrefixAndOrdinaryTextIsNot() {
        Spool s = newSpool();
        List<String> evil = List.of(
                "=HYPERLINK(\"http://example.invalid\",\"x\")",
                "+1+1",
                "-2+3",
                "@SUM(A1:A9)",
                "\t=1+1",
                "\r=1+1",
                "=cmd|' /C calc'!A0");
        try (Spool.Writer out = s.writer(100, 1_000_000, () -> {})) {
            var t = out.table(
                    "names", "Names", List.of(new Column("name", "Name", Kind.TEXT), new Column("n", "N", Kind.COUNT)));
            for (String e : evil) {
                t.row(e, -7);
            }
            t.row("Test Borrower 01", -7);
            t.row("1+1", 1);
            XlsxReader x = write("UGX", 10_000_000, out.finish(), s);
            Sheet sh = x.sheet("Names");
            for (int i = 0; i < evil.size(); i++) {
                Cell c = sh.cell("A" + (i + 2));
                assertThat(c.t())
                        .as("a string cell, never a formula: %s", evil.get(i))
                        .isEqualTo("inlineStr");
                assertThat(c.value())
                        .as("the value is kept as it was, not rewritten")
                        .isEqualTo(evil.get(i));
                assertThat(x.styles.get(c.style()).quotePrefix())
                        .as("quote prefix on %s", evil.get(i))
                        .isTrue();
                // And no cell of the sheet is a formula element at all.
            }
            assertThat(x.styles.get(sh.cell("A9").style()).quotePrefix()).isFalse();
            assertThat(x.styles.get(sh.cell("A10").style()).quotePrefix()).isFalse();
            // A negative number in a numeric column is a number, not text.
            assertThat(sh.cell("B2")).extracting(Cell::t, Cell::value).containsExactly("", "-7");
            for (String part : x.parts.keySet()) {
                if (part.startsWith("xl/worksheets/")) {
                    assertThat(new String(x.parts.get(part), java.nio.charset.StandardCharsets.UTF_8))
                            .as("no formula element in %s", part)
                            .doesNotContain("<f>")
                            .doesNotContain("<f ");
                }
            }
        }
    }

    @Test
    void markupQuotesAndNonLatinTextSurviveAndCharactersXmlCannotHoldAreDropped() {
        Spool s = newSpool();
        String name = "Test <Co> & \"Quoted\" 'single' Ünï Ссылка 日本語 😀 line\nbreak";
        String dirty = "A\u0000B\u0007C￾d\uD800e";
        String huge = "x".repeat(40_000);
        try (Spool.Writer out = s.writer(100, 10_000_000, () -> {})) {
            var t = out.table("names", "Names", List.of(new Column("name", "Name", Kind.TEXT)));
            t.row(name);
            t.row(dirty);
            t.row(huge);
            XlsxReader x = write("UGX", 10_000_000, out.finish(), s);
            Sheet sh = x.sheet("Names");
            assertThat(sh.text("A2")).isEqualTo(name);
            // The unpaired surrogate was made a replacement character by the spool; the two control characters and the
            // non character are gone.
            assertThat(sh.text("A3")).isEqualTo("ABCd\uFFFDe");
            assertThat(sh.text("A4")).hasSize(XlsxWriter.MAX_CELL_CHARS);
            assertThat(XlsxWriter.escape("<a href=\"x\">&'</a>"))
                    .isEqualTo("&lt;a href=&quot;x&quot;&gt;&amp;&apos;&lt;/a&gt;");
        }
    }

    @Test
    void theParametersSheetSaysWhatTheFileIsAndWhereItCameFrom() {
        Spool s = newSpool();
        try (Spool.Writer out = s.writer(100, 1_000_000, () -> {})) {
            out.table("rows", "Rows", List.of(new Column("id", "Id", Kind.COUNT)))
                    .row(1);
            Sheet p = write("UGX", 10_000_000, out.finish(), s).sheet("Parameters");
            assertThat(p.text("A1")).isEqualTo("Test report");
            List<String> labels = new ArrayList<>();
            p.rows().forEach(r -> {
                Cell c = r.values().iterator().next();
                if (c.value() != null) {
                    labels.add(c.value());
                }
            });
            assertThat(labels)
                    .contains("Report key", "As at", "Data read at", "File made at", "Made by", "Business", "Amounts")
                    .contains("Group by", "Branches", "Note");
            assertThat(p.cell("B4").value()).as("as at is a real date").isEqualTo("46303");
            // 10:00 UTC is 13:00 in Kampala, in the tenant's own time zone.
            assertThat(p.text("B5")).isEqualTo("8 Oct 2026 13:00 (Africa/Kampala)");
            assertThat(p.text("B7")).isEqualTo("Test User");
            assertThat(p.text("B9")).isEqualTo("In UGX, whole units");
        }
    }

    @Test
    void sheetNamesAreLegalUniqueAndNeverClashWithParameters() {
        Spool s = newSpool();
        try (Spool.Writer out = s.writer(100, 1_000_000, () -> {})) {
            List<Column> one = List.of(new Column("id", "Id", Kind.COUNT));
            out.table("a", "Portfolio [at] risk: 30/60*days?", one).row(1);
            out.table("b", "Portfolio at risk 30 60 days", one).row(1);
            out.table("c", "A very long table title that cannot fit in thirty-one characters", one)
                    .row(1);
            out.table("d", "parameters", one).row(1);
            out.table("e", "  ", one).row(1);
            List<TableMeta> tables = out.finish();
            assertThat(XlsxWriter.sheetNames(tables))
                    .containsExactly(
                            "Parameters",
                            "Portfolio at risk 30 60 days",
                            "Portfolio at risk 30 60 days 2",
                            "A very long table title that ca",
                            "parameters 2",
                            "Table");
            for (String name : XlsxWriter.sheetNames(tables)) {
                assertThat(name.length()).isLessThanOrEqualTo(31);
                assertThat(name).doesNotContainPattern("[\\[\\]:*?/\\\\]");
            }
            XlsxReader x = write("UGX", 10_000_000, tables, s);
            assertThat(x.sheets).hasSize(6);
        }
    }

    // JUSTIFICATION-A3: review cases of issue #217 (a control character hiding a formula, apostrophes at a cut sheet
    // name).
    @Test
    void aControlCharacterInFrontOfAFormulaDoesNotHideItFromTheQuotePrefix() {
        Spool s = newSpool();
        try (Spool.Writer out = s.writer(100, 1_000_000, () -> {})) {
            var t = out.table("names", "Names", List.of(new Column("name", "Name", Kind.TEXT)));
            t.row("\u0001=HYPERLINK(\"http://example.invalid\")");
            t.row("\u0007\u0000+1+1");
            t.row("\u0001 =1+1");
            XlsxReader x = write("UGX", 10_000_000, out.finish(), s);
            Sheet sh = x.sheet("Names");
            // The cell holds what the spreadsheet will see (the control character is gone) and carries the quote
            // prefix.
            assertThat(sh.text("A2")).isEqualTo("=HYPERLINK(\"http://example.invalid\")");
            assertThat(x.styles.get(sh.cell("A2").style()).quotePrefix()).isTrue();
            assertThat(sh.text("A3")).isEqualTo("+1+1");
            assertThat(x.styles.get(sh.cell("A3").style()).quotePrefix()).isTrue();
            // A space in front is text to a spreadsheet and needs no prefix.
            assertThat(sh.text("A4")).isEqualTo(" =1+1");
            assertThat(x.styles.get(sh.cell("A4").style()).quotePrefix()).isFalse();
        }
    }

    @Test
    void aSheetNameNeverEndsWithAnApostropheAfterTheCutAndControlCharactersDoNotMakeNamesLookDifferent() {
        // Thirty characters, then an apostrophe: the cut at 31 leaves it at the end, where a sheet name may not have
        // one.
        String title = "Branch portfolio at risk of 30" + "'" + "s book";
        assertThat(title.charAt(30)).isEqualTo('\'');
        assertThat(XlsxWriter.legalName(title, 31))
                .isEqualTo("Branch portfolio at risk of 30")
                .doesNotEndWith("'");
        assertThat(XlsxWriter.legalName("'quoted'", 31)).isEqualTo("quoted");
        assertThat(XlsxWriter.legalName("\u0001\u0002", 31)).isEqualTo("Table");
        Spool s = newSpool();
        try (Spool.Writer out = s.writer(100, 1_000_000, () -> {})) {
            List<Column> one = List.of(new Column("id", "Id", Kind.COUNT));
            out.table("a", "Names", one).row(1);
            // Differs from the first only by a control character that is dropped when written: the same sheet name.
            out.table("b", "Na\u0001mes", one).row(1);
            out.table("c", title, one).row(1);
            List<TableMeta> tables = out.finish();
            List<String> names = XlsxWriter.sheetNames(tables);
            assertThat(names).containsExactly("Parameters", "Names", "Names 2", "Branch portfolio at risk of 30");
            for (String name : names) {
                assertThat(name).isEqualTo(XlsxWriter.escape(name).replace("&apos;", "'"));
            }
            assertThat(write("UGX", 10_000_000, tables, s).sheets)
                    .extracting(Sheet::name)
                    .isEqualTo(names);
        }
    }

    @Test
    void aSheetWithMoreThanSevenColumnsPrintsLandscapeAndTheHeaderRepeats() {
        Spool s = newSpool();
        try (Spool.Writer out = s.writer(100, 1_000_000, () -> {})) {
            List<Column> eight = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                eight.add(new Column("c" + i, "Column " + i, Kind.COUNT));
            }
            out.table("wide", "Wide", eight).row(1, 2, 3, 4, 5, 6, 7, 8);
            out.table("narrow", "Narrow", List.of(new Column("c", "C", Kind.COUNT)))
                    .row(1);
            XlsxReader x = write("UGX", 10_000_000, out.finish(), s);
            assertThat(x.sheet("Wide").orientation()).isEqualTo("landscape");
            assertThat(x.sheet("Narrow").orientation()).isEqualTo("portrait");
            assertThat(new String(x.parts.get("xl/workbook.xml"), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("_xlnm.Print_Titles")
                    .contains("'Wide'!$1:$1");
            assertThat(x.sheet("Wide").cell("H2").value()).isEqualTo("8");
        }
    }

    @Test
    void columnLettersRunPastZ() {
        assertThat(XlsxWriter.ref(0)).isEqualTo("A");
        assertThat(XlsxWriter.ref(25)).isEqualTo("Z");
        assertThat(XlsxWriter.ref(26)).isEqualTo("AA");
        assertThat(XlsxWriter.ref(701)).isEqualTo("ZZ");
        assertThat(XlsxWriter.ref(702)).isEqualTo("AAA");
    }

    @Test
    void aFilePastItsCapEndsTheRunTooLargeAndTheSameRowsFitWithRoom() throws Exception {
        Spool s = newSpool();
        try (Spool.Writer out = s.writer(10_000, 100_000_000, () -> {})) {
            var t = out.table(
                    "rows", "Rows", List.of(new Column("id", "Id", Kind.COUNT), new Column("n", "N", Kind.TEXT)));
            for (int i = 0; i < 5_000; i++) {
                t.row(i, "Test Row " + i + " " + "x".repeat(40));
            }
            List<TableMeta> tables = out.finish();
            Path file = dir.resolve("out.xlsx");
            XlsxWriter.write(file, 10_000_000, header("UGX"), presentation(), tables, s, () -> {});
            long size = Files.size(file);
            assertThat(size).isGreaterThan(10_000);
            assertThatThrownBy(() -> XlsxWriter.write(
                            dir.resolve("small.xlsx"), size / 2, header("UGX"), presentation(), tables, s, () -> {}))
                    .isInstanceOfSatisfying(
                            ReportFailure.class, e -> assertThat(e.code()).isEqualTo("report_too_large"));
            assertThat(XlsxReader.read(file).sheet("Rows").rows()).hasSize(5_001);
        }
    }

    @Test
    void anEmptyTableStillMakesASheetWithItsHeader() {
        Spool s = newSpool();
        try (Spool.Writer out = s.writer(100, 1_000_000, () -> {})) {
            out.table("none", "None", List.of(new Column("id", "Id", Kind.COUNT)));
            Sheet sh = write("UGX", 10_000_000, out.finish(), s).sheet("None");
            assertThat(sh.rows()).hasSize(1);
            assertThat(sh.text("A1")).isEqualTo("Id");
        }
    }
}
