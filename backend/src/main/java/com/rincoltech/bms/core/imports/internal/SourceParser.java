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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses a quarantined file with the streaming reader of its type and hands its rows on in chunks of {@link #CHUNK} (spec
 * section 3.1: the API never holds more than one chunk of 1,000 rows). Plain Java, no Spring, so the memory test runs the
 * same code in a child JVM at the staging heap.
 */
final class SourceParser {

    static final int CHUNK = 1_000;

    /**
     * The JSON characters at which a chunk is handed on before it holds {@link #CHUNK} rows (review of #790): 8 million
     * characters is 16 MB of heap at most, plus the one row that crossed it, so wide rows of long texts cannot fill the 224 MB
     * staging heap between flushes.
     */
    static final long CHUNK_CHARS = 8_000_000;

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
        Chunks chunks = new Chunks(mapper, flush, limits);
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
        return new BufferedReader(new InputStreamReader(in, utf8 ? StandardCharsets.UTF_8 : WINDOWS_1252), 64 * 1024);
    }

    /**
     * Collects rows into chunks of {@link #CHUNK} as JSON and hands each chunk on when full, or sooner once it holds
     * {@link #CHUNK_CHARS} characters of JSON; nothing more is held. Charges every kept header name and text against the
     * batch's {@link Parsing#EXPANSION_FACTOR} cap before the row is serialised.
     */
    private static final class Chunks implements Parsing.Sink {

        private final ObjectMapper mapper;
        private final Consumer<List<StagedRow>> flush;
        private final Parsing.Limits limits;
        private List<StagedRow> chunk = new ArrayList<>(CHUNK);
        private long chunkChars;
        private long chars;
        int rows;

        Chunks(ObjectMapper mapper, Consumer<List<StagedRow>> flush, Parsing.Limits limits) {
            this.mapper = mapper;
            this.flush = flush;
            this.limits = limits;
        }

        @Override
        public void header(int headerRowNo, List<String> names) {}

        @Override
        public void row(int sourceRowNo, Map<String, Cell> raw) {
            Map<String, Object> json = new LinkedHashMap<>();
            raw.forEach((name, cell) -> {
                // PostgreSQL cannot store a NUL in jsonb, though JSON may carry one (JSON Lines {"a":"\u0000"}).
                if (name.indexOf('\0') >= 0
                        || (cell.value() instanceof String s && s.indexOf('\0') >= 0)
                        || (cell.value() instanceof JsonNode n && hasNul(n))) {
                    throw Parsing.unreadable("Row " + sourceRowNo + " holds a NUL character.");
                }
                // A text referenced again (a shared string, a header name as a key) is charged at every use.
                chars += name.length() + (cell.value() instanceof String s ? s.length() : 0);
                if (chars > Parsing.maxExpandedChars(limits)) {
                    throw Parsing.tooMuchText(limits);
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("value", cell.value());
                c.put("type", cell.type());
                json.put(name, c);
            });
            String rawJson = mapper.writeValueAsString(json);
            chunk.add(new StagedRow(sourceRowNo, rawJson));
            chunkChars += rawJson.length();
            rows++;
            if (chunk.size() == CHUNK || chunkChars >= CHUNK_CHARS) {
                flush();
            }
        }

        /** A NUL in any text or key of nested JSON, found in the values, so a backslash followed by u0000 is not one. */
        private static boolean hasNul(JsonNode n) {
            if (n.isString()) {
                return n.asString().indexOf('\0') >= 0;
            }
            if (n.isObject()) {
                return n.properties().stream().anyMatch(e -> e.getKey().indexOf('\0') >= 0 || hasNul(e.getValue()));
            }
            for (JsonNode child : n) {
                if (hasNul(child)) {
                    return true;
                }
            }
            return false;
        }

        void flush() {
            if (!chunk.isEmpty()) {
                flush.accept(chunk);
                chunk = new ArrayList<>(CHUNK);
                chunkChars = 0;
            }
        }
    }
}
