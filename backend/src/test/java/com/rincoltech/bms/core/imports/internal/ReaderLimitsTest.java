package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.imports.internal.QuarantineWriter.FileType;
import com.rincoltech.bms.core.imports.internal.SourceParser.StagedRow;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
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
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put(
                "xl/workbook.xml",
                utf8(
                        "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                                + "relationships\"><sheets><sheet name=\"S\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"));
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
        Parsing.Sink none = new Parsing.Sink() {
            @Override
            public void header(int headerRowNo, List<String> names) {}

            @Override
            public void row(int sourceRowNo, Map<String, Parsing.Cell> raw) {}
        };
        XlsxRows.read(() -> new ByteArrayInputStream(book), null, new Parsing.Sheet(limits, none), caps);
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

    @Test
    void blankLinesBeforeTheFirstJsonObjectAreNotRows() throws IOException {
        var parsed = parse(FileType.JSONL, TestFiles.csv("\n\n{\"code\":\"FAB-1\"}\n\n{\"code\":\"FAB-2\"}\n"));
        assertThat(parsed.rows()).isEqualTo(3);
        assertThat(rows).extracting(StagedRow::sourceRowNo).containsExactly(3, 4, 5);
    }
}
