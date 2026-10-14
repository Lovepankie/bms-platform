package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.reporting.ReportFailure;
import com.rincoltech.bms.core.reporting.ReportOutput;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import tools.jackson.databind.ObjectMapper;

/**
 * The compact temporary file a run's rows go to (ADR-038 decision 2, {@code docs/specs/client-extras.md} section
 * 6.3): one file per table, one JSON array per line, in a directory of its own that the run removes when it ends.
 * A report writes through {@link Writer} inside the snapshot transaction and holds no more than one row; the
 * renderers read the files back after the commit, one line at a time. Nothing here keeps a table in memory.
 */
final class Spool {

    /**
     * One table as written: its columns, its row count and the file holding its rows. When {@code hasTotal} the last
     * row is the table's total (see {@link ReportOutput.Table#total}).
     */
    record TableMeta(
            String key, String title, List<ReportOutput.Column> columns, long rows, Path file, boolean hasTotal) {}

    /** What a run's ending requires of the code that writes or reads its rows. */
    interface Guard {

        /** Throws when the run is over time or has been cancelled; called as rows are written and read. */
        void checkpoint();
    }

    private final Path dir;
    private final ObjectMapper mapper;

    Spool(Path dir, ObjectMapper mapper) {
        this.dir = dir;
        this.mapper = mapper;
    }

    Path dir() {
        return dir;
    }

    /** The write side, open inside the snapshot transaction. */
    Writer writer(int tableRowCap, long maxBytes, Guard guard) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Writer(tableRowCap, maxBytes, guard);
    }

    /** Streams the rows of one table as raw JSON arrays, one per call. */
    void forEachRow(TableMeta table, Guard guard, Consumer<List<Object>> sink) {
        try (BufferedReader in = Files.newBufferedReader(table.file(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                guard.checkpoint();
                @SuppressWarnings("unchecked")
                List<Object> row = mapper.readValue(line, List.class);
                sink.accept(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * As {@link #forEachRow} for the first {@code head} rows and, when {@code keepLast}, the table's last row (its total):
     * the lines in between are read and counted but never parsed, so cutting a table of 200,000 rows to a few hundred for
     * a PDF costs a scan of the file and no more memory.
     */
    void forEachRowCut(TableMeta table, Guard guard, long head, boolean keepLast, Consumer<List<Object>> sink) {
        try (BufferedReader in = Files.newBufferedReader(table.file(), StandardCharsets.UTF_8)) {
            String line;
            long n = 0;
            while ((line = in.readLine()) != null) {
                guard.checkpoint();
                n++;
                if (n <= head || (keepLast && n == table.rows())) {
                    @SuppressWarnings("unchecked")
                    List<Object> row = mapper.readValue(line, List.class);
                    sink.accept(row);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Streams the raw JSON text of each row, for the JSON renderer, which copies it as it is. */
    void forEachRawRow(TableMeta table, Guard guard, Consumer<String> sink) {
        try (BufferedReader in = Files.newBufferedReader(table.file(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                guard.checkpoint();
                sink.accept(line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes the directory and everything in it; never throws. */
    void delete() {
        try {
            if (!Files.exists(dir)) {
                return;
            }
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            }
        } catch (IOException | RuntimeException e) {
            // A leftover directory is removed by the runner's sweep at the next start; nothing more to do.
        }
    }

    /** The {@link ReportOutput} a definition writes to. */
    final class Writer implements ReportOutput, AutoCloseable {

        private final int tableRowCap;
        private final long maxBytes;
        private final Guard guard;
        private final List<TableMeta> tables = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        private BufferedWriter current;
        private String currentKey;
        private String currentTitle;
        private List<Column> currentColumns;
        private Path currentFile;
        private long currentRows;
        private boolean currentTotal;
        private long total;
        private long bytes;

        private Writer(int tableRowCap, long maxBytes, Guard guard) {
            this.tableRowCap = tableRowCap;
            this.maxBytes = maxBytes;
            this.guard = guard;
        }

        @Override
        public Table table(String key, String title, List<Column> columns) {
            finishTable();
            currentKey = key;
            currentTitle = title;
            currentColumns = List.copyOf(columns);
            currentFile = dir.resolve(tables.size() + ".jsonl");
            currentRows = 0;
            currentTotal = false;
            try {
                current = Files.newBufferedWriter(currentFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new Table() {
                @Override
                public void row(Object... cells) {
                    Writer.this.row(false, cells);
                }

                @Override
                public void total(Object... cells) {
                    Writer.this.row(true, cells);
                }
            };
        }

        @Override
        public void note(String text) {
            notes.add(text);
        }

        @Override
        public long rows() {
            return total;
        }

        @Override
        public void checkpoint() {
            guard.checkpoint();
        }

        private void row(boolean isTotal, Object... cells) {
            if (current == null) {
                throw new IllegalStateException("No table is open.");
            }
            if (currentTotal) {
                throw new IllegalStateException("Table " + currentKey + " ended with its total row.");
            }
            if (cells.length != currentColumns.size()) {
                throw new IllegalArgumentException(
                        "Table " + currentKey + " has " + currentColumns.size() + " columns, not " + cells.length);
            }
            // Cheap (a clock read and a flag), so every row: a run that writes slowly still ends on time.
            guard.checkpoint();
            if (currentRows >= tableRowCap) {
                throw new ReportFailure(
                        "report_too_large", "A table of the report has more than " + tableRowCap + " rows.");
            }
            List<Object> out = new ArrayList<>(cells.length);
            for (int i = 0; i < cells.length; i++) {
                out.add(cell(currentColumns.get(i), cells[i]));
            }
            String line = mapper.writeValueAsString(out);
            // Bytes as written (UTF-8), not characters: a name in another script is two or three bytes a character.
            bytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (bytes > 2 * maxBytes) {
                throw new ReportFailure("report_too_large", "The report is larger than the size limit.");
            }
            try {
                current.write(line);
                current.write('\n');
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            currentRows++;
            total++;
            currentTotal = isTotal;
        }

        private static Object cell(Column column, Object value) {
            if (value == null) {
                return null;
            }
            return switch (column.kind()) {
                case TEXT -> {
                    if (!(value instanceof String s)) {
                        throw wrong(column, value);
                    }
                    yield wellFormed(s);
                }
                case DATE -> {
                    if (!(value instanceof LocalDate d)) {
                        throw wrong(column, value);
                    }
                    yield d.toString();
                }
                default -> {
                    if (!(value instanceof Long || value instanceof Integer)) {
                        throw wrong(column, value);
                    }
                    yield ((Number) value).longValue();
                }
            };
        }

        /** Text that UTF-8 can hold: an unpaired surrogate (which a database cannot store, but a report could build) becomes U+FFFD. */
        private static String wellFormed(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isSurrogate(c)) {
                    StringBuilder out = new StringBuilder(s.length());
                    for (int j = 0; j < s.length(); j++) {
                        char d = s.charAt(j);
                        if (Character.isHighSurrogate(d)
                                && j + 1 < s.length()
                                && Character.isLowSurrogate(s.charAt(j + 1))) {
                            out.append(d).append(s.charAt(++j));
                        } else {
                            out.append(Character.isSurrogate(d) ? '\uFFFD' : d);
                        }
                    }
                    return out.toString();
                }
            }
            return s;
        }

        private static IllegalArgumentException wrong(Column column, Object value) {
            return new IllegalArgumentException("Column " + column.key() + " is " + column.kind() + ", not "
                    + value.getClass().getSimpleName());
        }

        private void finishTable() {
            if (current == null) {
                return;
            }
            try {
                current.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            tables.add(new TableMeta(currentKey, currentTitle, currentColumns, currentRows, currentFile, currentTotal));
            current = null;
        }

        /** Closes the last table and returns what was written. */
        List<TableMeta> finish() {
            finishTable();
            return List.copyOf(tables);
        }

        List<String> notes() {
            return List.copyOf(notes);
        }

        @Override
        public void close() {
            if (current != null) {
                try {
                    current.close();
                } catch (IOException e) {
                    // The run is ending; the directory is removed next.
                }
                current = null;
            }
        }
    }
}
