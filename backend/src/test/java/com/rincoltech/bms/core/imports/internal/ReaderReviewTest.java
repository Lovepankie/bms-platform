package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.imports.internal.QuarantineWriter.FileType;
import com.rincoltech.bms.core.imports.internal.SourceParser.StagedRow;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The second review of #790: an inline string's runs add up to the cell limit, a sheet name longer than the batch keeps is
 * refused, a named sheet that is not there is refused by the options with its own code, no header name hides another's
 * cell, and the escaped text of a backslash and {@code u0000} is not a NUL. Hand-made files, fabricated values only.
 */
class ReaderReviewTest {

    final List<StagedRow> rows = new ArrayList<>();

    SourceParser.Parsed parse(FileType type, byte[] bytes, String sheet) throws IOException {
        return SourceParser.parse(
                type,
                true,
                () -> new ByteArrayInputStream(bytes),
                sheet,
                ReaderLimitsTest.LIMITS,
                ReaderLimitsTest.JSON,
                rows::addAll);
    }

    JsonNode raw(int i) {
        return ReaderLimitsTest.JSON.readTree(rows.get(i).rawJson());
    }

    @Test
    void theRunsOfAnInlineStringAddUpToTheCellLimit() {
        // Each run is under the limit; together they are over it.
        String run = "<r><t>" + "x".repeat(20_000) + "</t></r>";
        String sheet = ReaderLimitsTest.SHEET + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is>" + run + run
                + "</is></c></row>" + ReaderLimitsTest.END;
        ReaderLimitsTest.refusedWith(
                Parsing.FILE_UNREADABLE,
                () -> ReaderLimitsTest.read(
                        ReaderLimitsTest.workbook(ReaderLimitsTest.sst("<si><t>Code</t></si>"), sheet, null, Map.of()),
                        XlsxRows.Caps.DEFAULT));
    }

    @Test
    void aSheetNameLongerThanTheBatchKeepsIsRefused() throws IOException {
        byte[] book = ReaderLimitsTest.workbook(
                ReaderLimitsTest.sst("<si><t>Code</t></si>"),
                ReaderLimitsTest.SHEET + ReaderLimitsTest.END,
                null,
                Map.of());
        // Rename the one sheet, S, to 101 characters in the workbook part.
        byte[] longName = renamed(book, "x".repeat(101));
        ReaderLimitsTest.refusedWith(Parsing.FILE_UNREADABLE, () -> parse(FileType.XLSX, longName, null));
    }

    @Test
    void aNamedSheetThatIsNotThereIsRefusedByTheOptions() throws IOException {
        byte[] book = ReaderLimitsTest.workbook(
                ReaderLimitsTest.sst("<si><t>Code</t></si>"),
                ReaderLimitsTest.SHEET + ReaderLimitsTest.END,
                null,
                Map.of());
        ReaderLimitsTest.refusedWith(Parsing.SHEET_NOT_FOUND, () -> parse(FileType.XLSX, book, "Missing"));
        assertThat(Parsing.BY_OPTIONS).contains(Parsing.SHEET_NOT_FOUND, Parsing.HEADER_NOT_FOUND);
    }

    @Test
    void noHeaderNameHidesAnotherCell() throws IOException {
        parse(FileType.CSV, TestFiles.csv("Phone,Phone,Phone (2),,column_4\na,b,c,d,e,f\n"), null);
        JsonNode row = raw(0);
        assertThat(row.properties()).hasSize(6);
        assertThat(row.get("Phone").get("value").asString()).isEqualTo("a");
        assertThat(row.get("Phone (2)").get("value").asString()).isEqualTo("b");
        assertThat(row.get("Phone (2) (2)").get("value").asString()).isEqualTo("c");
        assertThat(row.get("column_4").get("value").asString())
                .as("the blank header's own name")
                .isEqualTo("d");
        assertThat(row.get("column_4 (2)").get("value").asString())
                .as("the real header column_4")
                .isEqualTo("e");
        assertThat(row.get("column_6").get("value").asString())
                .as("a cell past the header")
                .isEqualTo("f");
    }

    @Test
    void theEscapedTextOfABackslashAndU0000IsNotANul() throws IOException {
        // In the file: "C:\\u0000x", a backslash then the letters u0000, which is no NUL at all.
        parse(FileType.JSONL, TestFiles.csv("{\"code\":\"FAB-1\",\"tags\":[\"C:\\\\u0000x\"]}\n"), null);
        assertThat(raw(0).get("tags").get("value").get(0).asString()).isEqualTo("C:\\u0000x");
    }

    /** The workbook with its sheet renamed from {@code S} to {@code name}, rezipped. */
    static byte[] renamed(byte[] book, String name) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (var in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(book));
                var zip = new java.util.zip.ZipOutputStream(out)) {
            for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                byte[] bytes = in.readAllBytes();
                if (e.getName().equals("xl/workbook.xml")) {
                    bytes = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("name=\"S\"", "name=\"" + name + "\"")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                zip.putNextEntry(new java.util.zip.ZipEntry(e.getName()));
                zip.write(bytes);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
