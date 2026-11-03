package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.internal.Parsing.Cell;
import com.rincoltech.bms.core.imports.internal.QuarantineWriter.FileType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses a quarantined file with the streaming reader of its type and hands its rows on in chunks of {@link #CHUNK} (spec
 * section 3.1: the API never holds more than one chunk of 1,000 rows). Plain Java, no Spring, so the memory test runs the
 * same code in a child JVM at the staging heap.
 */
final class SourceParser {

    static final int CHUNK = 1_000;

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private SourceParser() {}

    /** One row ready to store: its source row number and its {@code raw} as JSON. */
    record StagedRow(int sourceRowNo, String rawJson) {}

    /** What the parse found. {@code headerRowNo} is 0 for JSON Lines, which has no header row. */
    record Parsed(String sheetName, int headerRowNo, int columns, int rows, List<String> warnings) {}

    static Parsed parse(
            FileType type,
            boolean utf8,
            XlsxRows.Source source,
            String sheetName,
            Parsing.Limits limits,
            ObjectMapper mapper,
            Consumer<List<StagedRow>> flush)
            throws IOException {
        Chunks chunks = new Chunks(mapper, flush);
        List<String> warnings = new ArrayList<>();
        try {
            return read(type, utf8, source, sheetName, limits, mapper, chunks, warnings);
        } catch (IllegalArgumentException | IndexOutOfBoundsException | java.time.DateTimeException e) {
            // A shape no reader expected is a file we cannot read, never an unhandled error (spec section 5.3).
            throw Parsing.unreadable("The file cannot be read.");
        }
    }

    private static Parsed read(
            FileType type,
            boolean utf8,
            XlsxRows.Source source,
            String sheetName,
            Parsing.Limits limits,
            ObjectMapper mapper,
            Chunks chunks,
            List<String> warnings)
            throws IOException {
        Parsed parsed;
        switch (type) {
            case XLSX -> {
                Parsing.Sheet sheet = new Parsing.Sheet(limits, chunks);
                String read = XlsxRows.read(source, sheetName, sheet);
                parsed = new Parsed(read, sheet.headerRowNo(), sheet.columns(), sheet.rows(), warnings);
            }
            case CSV -> {
                Parsing.Sheet sheet = new Parsing.Sheet(limits, chunks);
                try (Reader in = reader(source.open(), utf8)) {
                    CsvRows.read(in, sheet, limits);
                }
                sheet.end();
                parsed = new Parsed(null, sheet.headerRowNo(), sheet.columns(), sheet.rows(), warnings);
            }
            case JSONL -> {
                int columns;
                try (Reader in = reader(source.open(), utf8)) {
                    columns = JsonLinesRows.read(in, mapper, limits, chunks);
                }
                parsed = new Parsed(null, 0, columns, chunks.rows, warnings);
            }
            default -> throw new IllegalStateException();
        }
        chunks.flush();
        if (type != FileType.XLSX && !utf8) {
            warnings.add(Parsing.ENCODING_CONVERTED);
        }
        return parsed;
    }

    private static Reader reader(InputStream in, boolean utf8) {
        return new BufferedReader(
                new InputStreamReader(in, utf8 ? StandardCharsets.UTF_8 : WINDOWS_1252), 64 * 1024);
    }

    /** Collects rows into chunks of {@link #CHUNK} as JSON and hands each full chunk on; nothing more is held. */
    private static final class Chunks implements Parsing.Sink {

        private final ObjectMapper mapper;
        private final Consumer<List<StagedRow>> flush;
        private List<StagedRow> chunk = new ArrayList<>(CHUNK);
        int rows;

        Chunks(ObjectMapper mapper, Consumer<List<StagedRow>> flush) {
            this.mapper = mapper;
            this.flush = flush;
        }

        @Override
        public void header(int headerRowNo, List<String> names) {}

        @Override
        public void row(int sourceRowNo, Map<String, Cell> raw) {
            Map<String, Object> json = new LinkedHashMap<>();
            raw.forEach((name, cell) -> {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("value", cell.value());
                c.put("type", cell.type());
                json.put(name, c);
            });
            chunk.add(new StagedRow(sourceRowNo, mapper.writeValueAsString(json)));
            rows++;
            if (chunk.size() == CHUNK) {
                flush();
            }
        }

        void flush() {
            if (!chunk.isEmpty()) {
                flush.accept(chunk);
                chunk = new ArrayList<>(CHUNK);
            }
        }
    }
}
