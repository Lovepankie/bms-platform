package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

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
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fuzzed files of spec section 5.3 (issue #137), without a database: generated fabricated item lists as CSV, JSON Lines
 * and XLSX, each broken by one to three mutations (cut short anywhere, bytes inserted anywhere: a byte order mark, a quote,
 * a line break, a comma, a NUL, a lone or invalid UTF-8 byte, a right-to-left mark or override, a cell far over the cell
 * limit; bytes deleted or flipped anywhere), then read as the upload reads them: the quarantine writer's type and encoding
 * check, then the streaming reader of the type at the production limits. Every case ends in a refusal or a whole-file
 * failure with a code of the shared catalogue, or in rows; never another exception, and the same bytes always give the same
 * outcome. The database's part (coded row issues, nothing written outside the import tables) is
 * {@code ImportMalformedFilesIT}. jqwik prints the shrunk file on a failure.
 */
class MalformedFilesTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    /** The codes a whole file can end in (spec section 3.4.1, the batch failures). */
    static final Set<String> FILE_CODES = Set.of(
            Parsing.FILE_TYPE_UNSUPPORTED,
            Parsing.FILE_TOO_LARGE,
            Parsing.TOO_MANY_ROWS,
            Parsing.TOO_MANY_COLUMNS,
            Parsing.HEADER_NOT_FOUND,
            Parsing.FILE_UNREADABLE);

    static final List<String> HEADER = List.of("Code", "Description", "Qty", "Unit cost", "Bought on");

    /** What can be inserted: the byte sequences the spec names, and the characters that break a CSV or a JSON line. */
    static final List<byte[]> INSERTS = List.of(
            new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
            "\"".getBytes(StandardCharsets.UTF_8),
            "\r".getBytes(StandardCharsets.UTF_8),
            "\n".getBytes(StandardCharsets.UTF_8),
            ",".getBytes(StandardCharsets.UTF_8),
            "\"\"\"".getBytes(StandardCharsets.UTF_8),
            new byte[] {0},
            new byte[] {(byte) 0xFF},
            new byte[] {(byte) 0xC3},
            new byte[] {(byte) 0xFF, (byte) 0xFE},
            // A right-to-left override and a right-to-left mark, by code point so no editor can strip them.
            String.valueOf((char) 0x202E).getBytes(StandardCharsets.UTF_8),
            String.valueOf((char) 0x200F).getBytes(StandardCharsets.UTF_8),
            String.valueOf((char) 0x07).getBytes(StandardCharsets.UTF_8),
            "{".getBytes(StandardCharsets.UTF_8),
            "<".getBytes(StandardCharsets.UTF_8),
            "x".repeat(Parsing.MAX_CELL_CHARS + 10).getBytes(StandardCharsets.UTF_8));

    enum Kind {
        TRUNCATE,
        INSERT,
        DELETE,
        FLIP
    }

    /** One mutation at a point of the file, in thousandths of its length; {@code arg} picks the insert or the bits. */
    record Mutation(Kind kind, int atPerMille, int arg) {

        byte[] apply(byte[] file) {
            int at = (int) ((long) file.length * atPerMille / 1000);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            switch (kind) {
                case TRUNCATE -> out.write(file, 0, at);
                case INSERT -> {
                    out.write(file, 0, at);
                    out.writeBytes(INSERTS.get(arg % INSERTS.size()));
                    out.write(file, at, file.length - at);
                }
                case DELETE -> {
                    int end = Math.min(file.length, at + 1 + arg % 16);
                    out.write(file, 0, at);
                    out.write(file, end, file.length - end);
                }
                case FLIP -> {
                    byte[] copy = file.clone();
                    if (at < copy.length) {
                        copy[at] = (byte) (copy[at] ^ (1 + arg % 255));
                    }
                    return copy;
                }
            }
            return out.toByteArray();
        }
    }

    /** A fabricated item row: its number, a description, a quantity, a cost and a day of July 2026. */
    record Item(int no, String description, int qty, long costMinor, int day) {}

    @Property(tries = 1500, seed = "137101")
    void everyMutatedFileEndsInACodedFailureOrInRowsAndAlwaysTheSameOne(
            @ForAll("types") FileType type,
            @ForAll("items") List<Item> items,
            @ForAll("mutations") List<Mutation> mutations)
            throws IOException {
        byte[] file = file(type, items);
        for (Mutation m : mutations) {
            file = m.apply(file);
        }

        Outcome first = read(type, file);
        Outcome again = read(type, file);

        if (first.code() != null) {
            assertThat(FILE_CODES).as("the code of %s", first).contains(first.code());
        }
        assertThat(again).as("the same bytes, the same outcome").isEqualTo(first);
    }

    @Property(tries = 200, seed = "137102")
    void anUnbrokenFileIsReadWholeInEveryFormat(@ForAll("types") FileType type, @ForAll("items") List<Item> items)
            throws IOException {
        Outcome read = read(type, file(type, items));
        assertThat(read.code()).as("%s", read).isNull();
        assertThat(read.rows()).hasSize(items.size());
    }

    /** What reading a file ended in: a code, or the rows as they would be stored. */
    record Outcome(String code, List<String> rows) {}

    /** The upload's two steps on the bytes: the writer's check, then the reader of the type at the production limits. */
    static Outcome read(FileType type, byte[] bytes) throws IOException {
        QuarantineWriter.Written written;
        try {
            written = QuarantineWriter.copy(
                    new ByteArrayInputStream(bytes), type, Parsing.DEFAULT_MAX_FILE_BYTES, new ByteArrayOutputStream());
        } catch (QuarantineWriter.Refused r) {
            return new Outcome(r.code, List.of());
        }
        List<String> rows = new ArrayList<>();
        try {
            SourceParser.parse(
                    type,
                    written.utf8(),
                    () -> new ByteArrayInputStream(bytes),
                    null,
                    new Parsing.Limits(UploadSettings.DEFAULT_MAX_ROWS, 200, Set.of("code"), Set.of()),
                    JSON,
                    chunk -> chunk.stream().map(StagedRow::rawJson).forEach(rows::add));
        } catch (Parsing.Failure f) {
            return new Outcome(f.code, List.of());
        }
        return new Outcome(null, rows);
    }

    /** The items as a file of the type: RFC 4180 CSV, one JSON object a line, or a workbook of shared strings. */
    static byte[] file(FileType type, List<Item> items) {
        List<List<Object>> rows = new ArrayList<>();
        for (Item i : items) {
            rows.add(List.of(
                    String.format("FZ-%03d", i.no()),
                    i.description(),
                    i.qty(),
                    i.costMinor(),
                    String.format("%02d/07/2026", i.day())));
        }
        return switch (type) {
            case XLSX -> TestFiles.xlsx(HEADER, rows);
            case CSV -> {
                StringBuilder s = new StringBuilder(String.join(",", HEADER)).append("\r\n");
                for (List<Object> row : rows) {
                    List<String> cells = new ArrayList<>();
                    for (Object v : row) {
                        cells.add(v instanceof String text ? "\"" + text.replace("\"", "\"\"") + "\"" : v.toString());
                    }
                    s.append(String.join(",", cells)).append("\r\n");
                }
                yield s.toString().getBytes(StandardCharsets.UTF_8);
            }
            case JSONL -> {
                StringBuilder s = new StringBuilder();
                for (List<Object> row : rows) {
                    Map<String, Object> line = new LinkedHashMap<>();
                    for (int c = 0; c < HEADER.size(); c++) {
                        line.put(HEADER.get(c), row.get(c));
                    }
                    s.append(JSON.writeValueAsString(line)).append('\n');
                }
                yield s.toString().getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    @Provide
    Arbitrary<FileType> types() {
        return Arbitraries.of(FileType.CSV, FileType.JSONL, FileType.XLSX);
    }

    @Provide
    Arbitrary<List<Item>> items() {
        Arbitrary<String> description = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ', ',', '"', '\'', ';', (char) 0xE9, (char) 0xA0)
                .ofMinLength(1)
                .ofMaxLength(24)
                .map(s -> "Test " + s);
        return Combinators.combine(
                        Arbitraries.integers().between(1, 999),
                        description,
                        Arbitraries.integers().between(0, 500),
                        Arbitraries.longs().between(0, 10_000_000),
                        Arbitraries.integers().between(1, 31))
                .as(Item::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(12);
    }

    @Provide
    Arbitrary<List<Mutation>> mutations() {
        return Combinators.combine(
                        Arbitraries.of(Kind.class),
                        Arbitraries.integers().between(0, 1000),
                        Arbitraries.integers().between(0, 1000))
                .as(Mutation::new)
                .list()
                .ofMinSize(1)
                .ofMaxSize(3);
    }
}
