package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.imports.internal.QuarantineWriter.FileType;
import com.rincoltech.bms.core.imports.internal.SourceParser.StagedRow;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The readers' memory and size caps and the shapes they must refuse rather than misread (review of #790): many empty
 * shared strings, a zip bomb hidden in a part the reader skips, a text over the cell limit, a part named twice, a number
 * format with a unit, a NUL in any format, and blank lines before the first JSON object. Hand-made workbooks, fabricated
 * values only.
 */
class ReaderLimitsTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    static final Parsing.Limits LIMITS = new Parsing.Limits(100_000, 200, Set.of(), Set.of());

    static final String SHEET = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>";

    static final String END = "</sheetData></worksheet>";

    final List<StagedRow> rows = new ArrayList<>();

    static String sst(String items) {
        return "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" + items + "</sst>";
    }

    /** A workbook of one sheet, its shared strings, optional styles and any extra parts, in that order. */
    static byte[] workbook(String sharedStrings, String sheet, String styles, Map<String, byte[]> extra)
            throws IOException {
        return workbook("S", sharedStrings, sheet, styles, extra);
    }

    static byte[] workbook(
            String sheetName, String sharedStrings, String sheet, String styles, Map<String, byte[]> extra)
            throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put(
                "xl/workbook.xml",
                utf8(
                        "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"
                                + "<sheet name=\"" + sheetName
                                + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"));
        parts.put(
                "xl/_rels/workbook.xml.rels",
                utf8("<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>"));
        if (styles != null) {
            parts.put("xl/styles.xml", utf8(styles));
        }
        parts.put("xl/sharedStrings.xml", utf8(sharedStrings));
        parts.put("xl/worksheets/sheet1.xml", utf8(sheet));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> p : parts.entrySet()) {
                put(zip, p.getKey(), p.getValue());
            }
            for (Map.Entry<String, byte[]> p : extra.entrySet()) {
                put(zip, p.getKey(), p.getValue());
            }
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static void read(byte[] book, XlsxRows.Caps caps) throws IOException {
        read(book, caps, LIMITS);
    }

    static void read(byte[] book, XlsxRows.Caps caps, Parsing.Limits limits) throws IOException {
        read(() -> new ByteArrayInputStream(book), caps, limits);
    }

    static void read(XlsxRows.Source source, XlsxRows.Caps caps, Parsing.Limits limits) throws IOException {
        Parsing.Sink none = new Parsing.Sink() {
            @Override
            public void header(int headerRowNo, List<String> names) {}

            @Override
            public void row(int sourceRowNo, Map<String, Parsing.Cell> raw) {}
        };
        XlsxRows.read(source, null, new Parsing.Sheet(limits, none), caps);
    }

    SourceParser.Parsed parse(FileType type, byte[] bytes) throws IOException {
        return SourceParser.parse(type, true, () -> new ByteArrayInputStream(bytes), null, LIMITS, JSON, rows::addAll);
    }

    static void refusedWith(String code, ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(code));
    }

    @FunctionalInterface
    interface ThrowingCall {
        void run() throws Exception;
    }

    @Test
    void manyEmptySharedStringsAreChargedAndStopped() throws IOException {
        // A few kilobytes zipped: 200,000 empty entries each cost a slot and an object though they hold no text.
        byte[] book = workbook(sst("<si><t>Code</t></si>" + "<si/>".repeat(200_000)), SHEET + END, null, Map.of());
        assertThat(book.length).isLessThan(100_000);
        refusedWith(Parsing.FILE_TOO_LARGE, () -> read(book, new XlsxRows.Caps(1L << 30, 1L << 20)));
        read(book, new XlsxRows.Caps(1L << 30, 64L << 20));
    }

    @Test
    void millionsOfEmptySharedStringsAreRefusedAtTheProductionCaps() throws IOException {
        // Two million empty entries: 10 MB of XML, a few kilobytes zipped, and over 100 MB of heap if they were kept.
        byte[] book = workbook(sst("<si><t>Code</t></si>" + "<si/>".repeat(2_000_000)), SHEET + END, null, Map.of());
        assertThat(book.length).as("small on disk").isLessThan(1_000_000);
        refusedWith(Parsing.FILE_TOO_LARGE, () -> read(book, XlsxRows.Caps.DEFAULT));
    }

    @Test
    void noMoreSharedStringsThanTheSheetCanHaveCells() throws IOException {
        // Ten rows of two columns, and a header row: at most 22 distinct texts.
        Parsing.Limits small = new Parsing.Limits(10, 2, Set.of(), Set.of());
        byte[] fits = workbook(sst("<si><t>Code</t></si>" + "<si/>".repeat(21)), SHEET + END, null, Map.of());
        byte[] over = workbook(sst("<si><t>Code</t></si>" + "<si/>".repeat(22)), SHEET + END, null, Map.of());
        read(fits, XlsxRows.Caps.DEFAULT, small);
        refusedWith(Parsing.FILE_TOO_LARGE, () -> read(over, XlsxRows.Caps.DEFAULT, small));
    }

    @Test
    void aZipBombIsStoppedEvenInAPartTheReaderSkips() throws IOException {
        byte[] book = workbook(
                sst("<si><t>Code</t></si>"), SHEET + END, null, Map.of("xl/media/padding.bin", new byte[8 << 20]));
        assertThat(book.length).as("small on disk").isLessThan(200_000);
        refusedWith(Parsing.FILE_TOO_LARGE, () -> read(book, new XlsxRows.Caps(4L << 20, 64L << 20)));
        read(book, new XlsxRows.Caps(64L << 20, 64L << 20));
    }

    @Test
    void aTextOverTheCellLimitIsStoppedWhileItIsRead() {
        String longText = "x".repeat(Parsing.MAX_CELL_CHARS + 1);
        refusedWith(
                Parsing.FILE_UNREADABLE,
                () -> read(
                        workbook(sst("<si><t>" + longText + "</t></si>"), SHEET + END, null, Map.of()),
                        XlsxRows.Caps.DEFAULT));
        String inline =
                SHEET + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>" + longText + "</t></is></c></row>" + END;
        refusedWith(
                Parsing.FILE_UNREADABLE,
                () -> read(workbook(sst("<si><t>Code</t></si>"), inline, null, Map.of()), XlsxRows.Caps.DEFAULT));
    }

    // JUSTIFICATION-A3: two tests the review of 6e4f8c0 asks for; the sheet streams so no 100 MB text is held.
    @Test
    void manyLongRunsInOneInlineCellAreStoppedAtTheCellLimit() throws IOException {
        // One inline cell of 3,000 runs of 32,767 characters each, about 100 MB unpacked and about 100 KB zipped. Each
        // run is within the limit; the cell is refused as its second run is added, not when the cell ends.
        byte[] run = utf8("<r><t>" + "x".repeat(Parsing.MAX_CELL_CHARS) + "</t></r>");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] plain = workbook(sst("<si><t>Code</t></si>"), SHEET + END, null, Map.of());
            try (ZipInputStream parts = new ZipInputStream(new ByteArrayInputStream(plain))) {
                for (ZipEntry e; (e = parts.getNextEntry()) != null; ) {
                    if (!e.getName().equals("xl/worksheets/sheet1.xml")) {
                        put(zip, e.getName(), parts.readAllBytes());
                    }
                }
            }
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(utf8(SHEET + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is>"));
            for (int i = 0; i < 3_000; i++) {
                zip.write(run);
            }
            zip.write(utf8("</is></c></row>" + END));
            zip.closeEntry();
        }
        byte[] book = out.toByteArray();
        assertThat(book.length).as("small on disk").isLessThan(1_000_000);

        // The bytes the sheet's pass (the second open) takes before it stops: a small part, not the whole cell.
        long[] sheetPass = new long[1];
        int[] opens = new int[1];
        XlsxRows.Source source = () -> {
            boolean second = ++opens[0] == 2;
            return new FilterInputStream(new ByteArrayInputStream(book)) {
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    sheetPass[0] += second && n > 0 ? n : 0;
                    return n;
                }
            };
        };
        assertThatThrownBy(() -> read(source, XlsxRows.Caps.DEFAULT, LIMITS))
                .isInstanceOfSatisfying(Parsing.Failure.class, f -> {
                    assertThat(f.code).isEqualTo(Parsing.FILE_UNREADABLE);
                    assertThat(f.getMessage()).contains("row 2");
                });
        assertThat(opens[0]).isEqualTo(2);
        assertThat(sheetPass[0]).as("stopped early in the cell").isLessThan(book.length / 4);
    }

    // JUSTIFICATION-A3: the tests the review of 339d6a2 asks for (must-fix 1, nice-to-have 1); no new mechanism.
    /**
     * One text at the cell limit in the shared strings, referenced by every cell of 1,000 rows of 200 columns below a
     * header (review of 339d6a2): about 4 MB of sheet XML, small zipped, and 6.5 billion characters if every use expanded.
     */
    static byte[] oneLongTextInEveryCell() throws IOException {
        StringBuilder texts = new StringBuilder("<si><t>" + "x".repeat(Parsing.MAX_CELL_CHARS) + "</t></si>");
        StringBuilder sheet = new StringBuilder(SHEET.substring(0, SHEET.indexOf("<row")) + "<row r=\"1\">");
        for (int c = 1; c <= 200; c++) {
            texts.append("<si><t>H").append(c).append("</t></si>");
            sheet.append("<c t=\"s\"><v>").append(c).append("</v></c>");
        }
        sheet.append("</row>");
        String cells = "<c t=\"s\"><v>0</v></c>".repeat(200);
        for (int r = 2; r <= 1_001; r++) {
            sheet.append("<row r=\"").append(r).append("\">").append(cells).append("</row>");
        }
        return workbook(sst(texts.toString()), sheet + END, null, Map.of());
    }

    @Test
    void oneLongSharedTextInEveryCellIsRefusedBeforeItExpands() throws IOException {
        byte[] book = oneLongTextInEveryCell();
        assertThat(book.length).as("small on disk").isLessThan(500_000);
        long[] staged = new long[2];
        assertThatThrownBy(() -> SourceParser.parse(
                        FileType.XLSX, true, () -> new ByteArrayInputStream(book), null, LIMITS, JSON, chunk -> {
                            staged[0] += chunk.size();
                            staged[1] = Math.max(
                                    staged[1],
                                    chunk.stream()
                                            .mapToLong(r -> r.rawJson().length())
                                            .sum());
                        }))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(Parsing.FILE_TOO_LARGE));
        long row = 200L * (Parsing.MAX_CELL_CHARS + 40);
        assertThat(staged[0])
                .as("refused at the cap, long before the 1,000th row")
                .isLessThan(40);
        assertThat(staged[1]).as("a chunk is handed on by its size").isLessThan(SourceParser.CHUNK_CHARS + row);
    }

    @Test
    void aPhoneticReadingIsNotPartOfAnInlineText() throws IOException {
        String sheet = SHEET + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>FAB</t>"
                + "<rPh sb=\"0\" eb=\"3\"><t>fabu</t></rPh></is></c></row>" + END;
        parse(FileType.XLSX, workbook(sst("<si><t>Code</t></si>"), sheet, null, Map.of()));
        assertThat(JSON.readTree(rows.get(0).rawJson()).get("Code").get("value").asString())
                .isEqualTo("FAB");
    }

    @Test
    void aSheetNameLongerThanTheBatchRecordsIsUnreadable() throws IOException {
        String sheet = SHEET + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>FAB-1</t></is></c></row>" + END;
        String fits = "N".repeat(XlsxRows.MAX_SHEET_NAME);
        read(workbook(fits, sst("<si><t>Code</t></si>"), sheet, null, Map.of()), XlsxRows.Caps.DEFAULT);
        byte[] over = workbook(fits + "N", sst("<si><t>Code</t></si>"), sheet, null, Map.of());
        refusedWith(Parsing.FILE_UNREADABLE, () -> read(over, XlsxRows.Caps.DEFAULT));
    }

    @Test
    void aPartNamedTwiceIsRefused() throws IOException {
        // The JDK's writer refuses a duplicate name, so the second part is written under a name of the same length and
        // renamed in the bytes (local header and central directory), as a hand-edited file would be.
        byte[] book = workbook(
                sst("<si><t>Code</t></si>"),
                SHEET + END,
                null,
                Map.of("xl/sharedStrings.xmZ", utf8(sst("<si><t>Other</t></si>"))));
        byte[] from = utf8("xl/sharedStrings.xmZ");
        for (int i = 0; i + from.length <= book.length; i++) {
            if (java.util.Arrays.equals(book, i, i + from.length, from, 0, from.length)) {
                book[i + from.length - 1] = 'l';
            }
        }
        refusedWith(Parsing.FILE_UNREADABLE, () -> read(book, XlsxRows.Caps.DEFAULT));
    }

    @Test
    void aNumberFormatWithAUnitIsNotADate() throws IOException {
        String styles = "<styleSheet><numFmts><numFmt numFmtId=\"164\" formatCode=\"0.0 mm\"/>"
                + "<numFmt numFmtId=\"165\" formatCode=\"mmm yyyy\"/></numFmts>"
                + "<cellXfs><xf numFmtId=\"0\"/><xf numFmtId=\"164\"/><xf numFmtId=\"165\"/></cellXfs></styleSheet>";
        String sheet = SHEET + "<row r=\"2\"><c r=\"A2\" s=\"1\"><v>12.5</v></c></row>"
                + "<row r=\"3\"><c r=\"A3\" s=\"2\"><v>46203</v></c></row>" + END;
        parse(FileType.XLSX, workbook(sst("<si><t>Length</t></si>"), sheet, styles, Map.of()));
        JsonNode first = JSON.readTree(rows.get(0).rawJson()).get("Length");
        assertThat(first.get("type").asString()).isEqualTo("number");
        assertThat(first.get("value").decimalValue()).isEqualByComparingTo("12.5");
        JsonNode second = JSON.readTree(rows.get(1).rawJson()).get("Length");
        assertThat(second.get("type").asString())
                .as("a month format is still a date")
                .isEqualTo("date");
    }

    @Test
    void aNulIsUnreadableInEveryFormat() {
        // Past the 4 KiB the upload's sniff looks at.
        byte[] csv = TestFiles.csv("Code\n" + "FAB-1\n".repeat(1_000) + "FAB\u00002\n");
        refusedWith(Parsing.FILE_UNREADABLE, () -> parse(FileType.CSV, csv));
        byte[] value = TestFiles.csv("{\"code\":\"FAB-1\"}\n{\"code\":\"FAB\\u0000\"}\n");
        refusedWith(Parsing.FILE_UNREADABLE, () -> parse(FileType.JSONL, value));
        byte[] key = TestFiles.csv("{\"co\\u0000de\":\"FAB-1\"}\n");
        refusedWith(Parsing.FILE_UNREADABLE, () -> parse(FileType.JSONL, key));
        byte[] nested = TestFiles.csv("{\"code\":\"FAB-1\",\"tags\":[\"a\\u0000\"]}\n");
        refusedWith(Parsing.FILE_UNREADABLE, () -> parse(FileType.JSONL, nested));
    }

    // JUSTIFICATION-A3: the two tests for items 3 and 5 of the review of 6e4f8c0, no new mechanism.
    @Test
    void theTextOfABackslashAndU0000IsNotANul() throws IOException {
        // The JSON holds an escaped backslash then the letters u0000, as a Windows path might: no NUL at all.
        var parsed = parse(FileType.JSONL, TestFiles.csv("{\"code\":\"FAB-1\",\"tags\":[\"C:\\\\u0000x\"]}\n"));
        assertThat(parsed.rows()).isEqualTo(1);
        JsonNode tags = JSON.readTree(rows.get(0).rawJson()).get("tags").get("value");
        assertThat(tags.get(0).asString()).isEqualTo("C:\\u0000x");
    }

    @Test
    void renamedHeadersNeverTakeAnotherHeadersName() throws IOException {
        // Phone (2) is a real header, and so is column_5, the name a fifth cell beyond the header would get.
        parse(FileType.CSV, TestFiles.csv("Phone,Phone,Phone (2),column_5\nA,B,C,D,E\n"));
        JsonNode raw = JSON.readTree(rows.get(0).rawJson());
        assertThat(raw.size()).as("no cell is lost").isEqualTo(5);
        assertThat(raw.get("Phone").get("value").asString()).isEqualTo("A");
        assertThat(raw.get("Phone (2)").get("value").asString()).isEqualTo("B");
        assertThat(raw.get("Phone (2) (2)").get("value").asString()).isEqualTo("C");
        assertThat(raw.get("column_5").get("value").asString()).isEqualTo("D");
        assertThat(raw.get("column_5 (2)").get("value").asString()).isEqualTo("E");
    }

    @Test
    void blankLinesBeforeTheFirstJsonObjectAreNotRows() throws IOException {
        var parsed = parse(FileType.JSONL, TestFiles.csv("\n\n{\"code\":\"FAB-1\"}\n\n{\"code\":\"FAB-2\"}\n"));
        assertThat(parsed.rows()).isEqualTo(3);
        assertThat(rows).extracting(StagedRow::sourceRowNo).containsExactly(3, 4, 5);
    }
}
