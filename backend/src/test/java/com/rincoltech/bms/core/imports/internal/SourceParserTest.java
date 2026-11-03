package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.imports.internal.QuarantineWriter.FileType;
import com.rincoltech.bms.core.imports.internal.SourceParser.StagedRow;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The three streaming readers and the limits (issue #128, spec sections 3.1, 3.2 and 6.1), without a database: CSV, XLSX
 * and JSON Lines into rows of {@code raw}, every refusal code, every row below the header kept, ignored columns dropped,
 * and rows handed on in chunks of 1,000. Fabricated data only.
 */
class SourceParserTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    final List<StagedRow> rows = new ArrayList<>();
    final List<Integer> chunkSizes = new ArrayList<>();

    SourceParser.Parsed parse(FileType type, byte[] bytes, Parsing.Limits limits) throws IOException {
        return parse(type, bytes, true, null, limits);
    }

    SourceParser.Parsed parse(FileType type, byte[] bytes, boolean utf8, String sheet, Parsing.Limits limits)
            throws IOException {
        return SourceParser.parse(type, utf8, () -> new ByteArrayInputStream(bytes), sheet, limits, JSON, chunk -> {
            chunkSizes.add(chunk.size());
            rows.addAll(chunk);
        });
    }

    static Parsing.Limits limits() {
        return new Parsing.Limits(100_000, 200, Set.of(), Set.of());
    }

    JsonNode raw(int i) {
        return JSON.readTree(rows.get(i).rawJson());
    }

    @Test
    void csvKeepsEveryRowBelowTheHeaderAsWrittenAndDropsIgnoredColumns() throws IOException {
        byte[] file = TestFiles.csv("﻿Fabricated stock list\n\nCode,Description,Phone,Qty\r\n"
                + "FAB-1,\"Plain, with a comma\",0700000000,3\r\n"
                + "\r\n"
                + "FAB-2,\"Two\nlines and a \"\"quote\"\"\",0700000001,4\n"
                + ",,,\n\n\n");
        var parsed = parse(FileType.CSV, file, new Parsing.Limits(100, 200, Set.of("code", "QTY"), Set.of(" phone ")));
        assertThat(parsed.headerRowNo()).isEqualTo(3);
        assertThat(parsed.columns()).isEqualTo(4);
        assertThat(parsed.rows())
                .as("two rows and the blank row between them; trailing blanks are not rows")
                .isEqualTo(3);
        assertThat(rows).extracting(StagedRow::sourceRowNo).containsExactly(4, 5, 6);
        assertThat(raw(0).get("Description").get("value").asString()).isEqualTo("Plain, with a comma");
        assertThat(raw(0).get("Qty").get("type").asString()).isEqualTo("text");
        assertThat(raw(0).has("Phone")).as("an ignored column is not stored").isFalse();
        assertThat(raw(1).isEmpty()).as("a blank row is kept, empty").isTrue();
        assertThat(raw(2).get("Description").get("value").asString()).isEqualTo("Two\nlines and a \"quote\"");
        assertThat(parsed.warnings()).isEmpty();
    }

    @Test
    void windows1252IsReadAndWarned() throws IOException {
        byte[] file = "Code,Name\nFAB-1,Café fabricated\n".getBytes(Charset.forName("windows-1252"));
        var parsed = parse(FileType.CSV, file, false, null, limits());
        assertThat(parsed.warnings()).containsExactly(Parsing.ENCODING_CONVERTED);
        assertThat(raw(0).get("Name").get("value").asString()).isEqualTo("Café fabricated");
    }

    @Test
    void theRowLimitIsExactAndOneMoreIsRefused() throws IOException {
        StringBuilder s = new StringBuilder("Code\n");
        for (int i = 1; i <= 2_500; i++) {
            s.append("FAB-").append(i).append('\n');
        }
        var parsed =
                parse(FileType.CSV, TestFiles.csv(s.toString()), new Parsing.Limits(2_500, 200, Set.of(), Set.of()));
        assertThat(parsed.rows()).isEqualTo(2_500);
        assertThat(chunkSizes).as("handed on 1,000 at a time").containsExactly(1_000, 1_000, 500);
        assertThatThrownBy(() -> parse(
                        FileType.CSV, TestFiles.csv(s.toString()), new Parsing.Limits(2_499, 200, Set.of(), Set.of())))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(Parsing.TOO_MANY_ROWS));
    }

    @Test
    void blankRowsCountTowardsTheRowLimit() {
        byte[] file = TestFiles.csv("Code\nFAB-1\n\n\n\nFAB-2\n");
        assertThatThrownBy(() -> parse(FileType.CSV, file, new Parsing.Limits(4, 200, Set.of(), Set.of())))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(Parsing.TOO_MANY_ROWS));
    }

    @Test
    void tooManyColumnsInTheHeaderOrInARowIsRefused() {
        String wide = String.join(",", TestFiles.header(201)) + "\nFAB-1\n";
        assertThatThrownBy(() -> parse(FileType.CSV, TestFiles.csv(wide), limits()))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(Parsing.TOO_MANY_COLUMNS));
        String row = "Code\nFAB-1" + ",".repeat(200) + "x\n";
        assertThatThrownBy(() -> parse(FileType.CSV, TestFiles.csv(row), limits()))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(Parsing.TOO_MANY_COLUMNS));
    }

    @Test
    void theHeaderMustBeInTheFirstTwentyRows() {
        String late = "note\n".repeat(20) + "Code,Qty\nFAB-1,2\n";
        var needs = new Parsing.Limits(100, 200, Set.of("Code", "Qty"), Set.of());
        assertThatThrownBy(() -> parse(FileType.CSV, TestFiles.csv(late), needs))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(Parsing.HEADER_NOT_FOUND));
        assertThatThrownBy(() -> parse(FileType.CSV, TestFiles.csv("\n\n"), limits()))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(Parsing.HEADER_NOT_FOUND));
    }

    @Test
    void anUnclosedQuoteOrAHugeCellIsUnreadable() {
        assertThatThrownBy(() -> parse(FileType.CSV, TestFiles.csv("Code\n\"FAB-1\n"), limits()))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(Parsing.FILE_UNREADABLE));
        byte[] huge = TestFiles.csv("Code\n" + "x".repeat(Parsing.MAX_CELL_CHARS + 1) + "\n");
        assertThatThrownBy(() -> parse(FileType.CSV, huge, limits()))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(Parsing.FILE_UNREADABLE));
    }

    @Test
    void xlsxReadsTheFirstVisibleSheetWithValuesAsShown() throws IOException {
        byte[] book = TestFiles.xlsx(
                List.of("Code", "Description", "Qty", "Bought on", "Phone", "Active"),
                Arrays.asList(
                        Arrays.asList(
                                "FAB-1", "Fabricated soap", 0.1 + 0.2, LocalDate.of(2026, 6, 30), "0700000000", true),
                        Arrays.asList(null, null, null, null, null, null),
                        Arrays.asList("FAB-2", null, 12, LocalDate.of(1900, 1, 1), null, false)));
        var parsed = parse(FileType.XLSX, book, true, null, new Parsing.Limits(100, 200, Set.of(), Set.of("phone")));
        assertThat(parsed.sheetName()).as("the hidden sheet is skipped").isEqualTo("Items");
        assertThat(parsed.headerRowNo()).isEqualTo(1);
        assertThat(parsed.rows()).isEqualTo(3);
        assertThat(rows).extracting(StagedRow::sourceRowNo).containsExactly(2, 3, 4);
        assertThat(raw(0).get("Qty").get("value").decimalValue()).isEqualByComparingTo("0.3");
        assertThat(raw(0).get("Bought on").get("value").asString()).isEqualTo("2026-06-30");
        assertThat(raw(0).get("Bought on").get("type").asString()).isEqualTo("date");
        assertThat(raw(0).get("Active").get("type").asString()).isEqualTo("bool");
        assertThat(raw(0).has("Phone")).isFalse();
        assertThat(raw(1).isEmpty()).isTrue();
        assertThat(raw(2).get("Bought on").get("value").asString()).isEqualTo("1900-01-01");
        assertThat(raw(2).has("Description")).isFalse();
    }

    @Test
    void xlsxReadsANamedSheetAndRefusesAnUnknownOneOrADamagedFile() throws IOException {
        byte[] book = TestFiles.xlsx(List.of("Code"), List.of(List.of("FAB-1")));
        var parsed = parse(FileType.XLSX, book, true, "notes", limits());
        assertThat(parsed.sheetName()).isEqualTo("Notes");
        assertThatThrownBy(() -> parse(FileType.XLSX, book, true, "Missing", limits()))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(Parsing.FILE_UNREADABLE));
        byte[] cut = Arrays.copyOf(book, book.length / 2);
        assertThatThrownBy(() -> parse(FileType.XLSX, cut, true, null, limits()))
                .isInstanceOf(Parsing.Failure.class);
    }

    @Test
    void xlsxRowAndColumnLimits() {
        List<List<Object>> many = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            many.add(List.of("FAB-" + i));
        }
        assertThatThrownBy(() -> parse(
                        FileType.XLSX,
                        TestFiles.xlsx(List.of("Code"), many),
                        true,
                        null,
                        new Parsing.Limits(10, 200, Set.of(), Set.of())))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(Parsing.TOO_MANY_ROWS));
        assertThatThrownBy(() ->
                        parse(FileType.XLSX, TestFiles.xlsx(TestFiles.header(201), List.of()), true, null, limits()))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(Parsing.TOO_MANY_COLUMNS));
    }

    @Test
    void jsonLinesTakesTheKeysAsColumns() throws IOException {
        byte[] file = TestFiles.csv("{\"code\":\"FAB-1\",\"qty\":2.5,\"phone\":\"0700000000\",\"tags\":[\"a\"]}\n"
                + "\n"
                + "{\"code\":\"FAB-2\",\"active\":true,\"qty\":null}\n");
        var parsed = parse(FileType.JSONL, file, new Parsing.Limits(100, 200, Set.of("code"), Set.of("phone")));
        assertThat(parsed.headerRowNo()).isZero();
        assertThat(parsed.columns()).isEqualTo(5);
        assertThat(rows).extracting(StagedRow::sourceRowNo).containsExactly(1, 2, 3);
        assertThat(raw(0).get("qty").get("type").asString()).isEqualTo("number");
        assertThat(raw(0).get("tags").get("type").asString()).isEqualTo("json");
        assertThat(raw(0).has("phone")).isFalse();
        assertThat(raw(2).has("qty")).as("null is empty").isFalse();
        assertThatThrownBy(() -> parse(FileType.JSONL, TestFiles.csv("{\"code\":1}\n[1]\n"), limits()))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(Parsing.FILE_UNREADABLE));
        assertThatThrownBy(() -> parse(
                        FileType.JSONL,
                        TestFiles.csv("{\"name\":1}\n"),
                        new Parsing.Limits(10, 200, Set.of("code"), Set.of())))
                .isInstanceOfSatisfying(
                        Parsing.Failure.class, f -> assertThat(f.code).isEqualTo(Parsing.HEADER_NOT_FOUND));
    }

    @Test
    void theWriterRefusesAWrongTypeAndAFileOverTheLimit() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        assertThatThrownBy(() -> QuarantineWriter.copy(
                        new ByteArrayInputStream(TestFiles.csv("Code\n")), FileType.XLSX, 1_000, sink))
                .isInstanceOfSatisfying(
                        QuarantineWriter.Refused.class,
                        r -> assertThat(r.code).isEqualTo(Parsing.FILE_TYPE_UNSUPPORTED));
        byte[] zip = TestFiles.xlsx(List.of("Code"), List.of());
        assertThatThrownBy(() -> QuarantineWriter.copy(new ByteArrayInputStream(zip), FileType.CSV, 100_000, sink))
                .isInstanceOfSatisfying(
                        QuarantineWriter.Refused.class,
                        r -> assertThat(r.code).isEqualTo(Parsing.FILE_TYPE_UNSUPPORTED));
        byte[] utf16 = "Code\nFAB-1\n".getBytes(StandardCharsets.UTF_16LE);
        assertThatThrownBy(() -> QuarantineWriter.copy(new ByteArrayInputStream(utf16), FileType.CSV, 100_000, sink))
                .isInstanceOf(QuarantineWriter.Refused.class);
        assertThatThrownBy(() ->
                        QuarantineWriter.copy(new ByteArrayInputStream(new byte[2_000]), FileType.CSV, 1_000, sink))
                .isInstanceOf(QuarantineWriter.Refused.class);
        byte[] big = TestFiles.csv("x".repeat(1_001));
        assertThatThrownBy(() -> QuarantineWriter.copy(new ByteArrayInputStream(big), FileType.CSV, 1_000, sink))
                .isInstanceOfSatisfying(
                        QuarantineWriter.Refused.class, r -> assertThat(r.code).isEqualTo(Parsing.FILE_TOO_LARGE));
    }

    @Test
    void theWriterTellsUtf8FromWindows1252AcrossBlocks() throws IOException {
        // A two byte character split across the 64 KiB block boundary is still UTF-8.
        byte[] utf8 = ("a".repeat(64 * 1024 - 1) + "é fabricated\n").getBytes(StandardCharsets.UTF_8);
        assertThat(QuarantineWriter.copy(
                                new ByteArrayInputStream(utf8), FileType.CSV, 1_000_000, new ByteArrayOutputStream())
                        .utf8())
                .isTrue();
        byte[] cp1252 = "Café\n".getBytes(Charset.forName("windows-1252"));
        assertThat(QuarantineWriter.copy(
                                new ByteArrayInputStream(cp1252), FileType.CSV, 1_000_000, new ByteArrayOutputStream())
                        .utf8())
                .isFalse();
    }
}
