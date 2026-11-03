package com.rincoltech.bms.core.imports.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the three streaming readers share (issue #128, spec sections 3.1, 3.2 and 6.1): the cell, the whole-file refusal,
 * the limits, and {@link Sheet}, which turns the physical rows of a CSV file or an XLSX sheet into the header and the rows to
 * store. Plain Java, no Spring, so the memory test runs it in a child JVM.
 */
final class Parsing {

    /** A whole-file refusal. Its codes are the spec's (section 3.4.1); its reason names a limit, a line or a column, never a value. */
    static final String FILE_TYPE_UNSUPPORTED = "FILE_TYPE_UNSUPPORTED";

    static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    static final String TOO_MANY_ROWS = "TOO_MANY_ROWS";
    static final String TOO_MANY_COLUMNS = "TOO_MANY_COLUMNS";
    static final String HEADER_NOT_FOUND = "HEADER_NOT_FOUND";
    static final String FILE_UNREADABLE = "FILE_UNREADABLE";

    /** The warning of a text file read as Windows-1252. */
    static final String ENCODING_CONVERTED = "ENCODING_CONVERTED";

    /** Rows above which no header is looked for (spec section 3.4.1, {@code HEADER_NOT_FOUND}). */
    static final int HEADER_SEARCH_ROWS = 20;

    /** The longest cell kept, Excel's own limit; a longer one is a file we cannot have been given by a spreadsheet. */
    static final int MAX_CELL_CHARS = 32_767;

    /** The default of {@code bms.imports.max-file-size}. */
    static final long DEFAULT_MAX_FILE_BYTES = 25L * 1024 * 1024;

    /**
     * Characters of header names and text a batch's rows may hold per byte of the file size limit (review of #790). A file
     * can name a text once and use it many times: every cell may reference one XLSX shared string, and every row repeats
     * each header name as a key. One 32,767 character shared string in every cell of a few KB of zipped sheet would
     * otherwise stage gigabytes. Honest CSV and JSON Lines stay near one character per byte, and XLSX text near its
     * inflated size, so eight times the limit (about 200 million characters at 25 MB) refuses no real file.
     */
    static final long EXPANSION_FACTOR = 8;

    private Parsing() {}

    /** One cell as read: its value (a String, a BigDecimal, a Boolean, or a JSON value) and its type. */
    record Cell(Object value, String type) {

        static Cell text(String s) {
            return new Cell(s, "text");
        }

        boolean blank() {
            return value == null || (value instanceof String s && s.isBlank());
        }
    }

    static final class Failure extends RuntimeException {

        final String code;

        Failure(String code, String reason) {
            super(reason, null, false, false);
            this.code = code;
        }
    }

    /**
     * The limits of one batch and the mapping's say: the headers the header row must hold (empty: the first row that is not
     * blank) and the columns not to keep (spec section 6.1). Header names are compared trimmed and ignoring case.
     */
    record Limits(
            int maxRows, int maxColumns, Set<String> requiredHeaders, Set<String> ignoredColumns, long maxFileBytes) {

        Limits(int maxRows, int maxColumns, Set<String> requiredHeaders, Set<String> ignoredColumns) {
            this(maxRows, maxColumns, requiredHeaders, ignoredColumns, DEFAULT_MAX_FILE_BYTES);
        }

        Limits {
            requiredHeaders = requiredHeaders.stream().map(Parsing::key).collect(Collectors.toUnmodifiableSet());
            ignoredColumns = ignoredColumns.stream().map(Parsing::key).collect(Collectors.toUnmodifiableSet());
        }
    }

    static String key(String header) {
        return header == null ? "" : header.strip().toLowerCase(Locale.ROOT);
    }

    /** Where the rows go: the header once, then each row below it with the cells kept, in source order. */
    interface Sink {

        /** @param headerRowNo the header's 1-based row in the source, or 0 when the format has none (JSON Lines) */
        void header(int headerRowNo, List<String> names);

        /** A row below the header; {@code raw} holds the kept cells that are not empty, by header name. */
        void row(int sourceRowNo, Map<String, Cell> raw);
    }

    /**
     * Finds the header in the first {@value #HEADER_SEARCH_ROWS} rows, then passes each row below it on with the cells of
     * ignored columns dropped. Blank rows are kept (FR-IMP-02) but only once a later row is not blank, so a sheet's trailing
     * empty rows are not rows. Counts every row against the row limit and every column against the column limit before it
     * is passed on.
     */
    static final class Sheet {

        private final Limits limits;
        private final Sink sink;
        private List<String> names;
        private int headerRowNo;
        private int lastRowNo;
        private int rows;
        private final Set<String> seen = new HashSet<>();

        Limits limits() {
            return limits;
        }

        Sheet(Limits limits, Sink sink) {
            this.limits = limits;
            this.sink = sink;
        }

        /** One physical row: its 1-based number and its cells by column position, {@code null} for empty. */
        void row(int rowNo, List<Cell> cells) {
            boolean blank = cells.stream().allMatch(c -> c == null || c.blank());
            if (names == null) {
                if (rowNo > HEADER_SEARCH_ROWS) {
                    throw headerNotFound();
                }
                if (!blank && isHeader(cells)) {
                    header(rowNo, cells);
                }
                return;
            }
            if (blank) {
                return;
            }
            // The blank rows since the last row passed on are rows of the source too.
            int gap = rowNo - lastRowNo - 1;
            if ((long) rows + gap + 1 > limits.maxRows()) {
                throw tooManyRows();
            }
            for (int r = lastRowNo + 1; r < rowNo; r++) {
                sink.row(r, Map.of());
            }
            rows += gap + 1;
            lastRowNo = rowNo;
            Map<String, Cell> raw = new LinkedHashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                Cell c = cells.get(i);
                if (c == null || c.value() == null) {
                    continue;
                }
                if (i >= limits.maxColumns()) {
                    throw tooManyColumns();
                }
                String name = i < names.size() ? names.get(i) : free("column_" + (i + 1), false);
                if (!limits.ignoredColumns().contains(key(name))) {
                    raw.put(name, c);
                }
            }
            sink.row(rowNo, raw);
        }

        /** The end of the source: refuses a source without a header. */
        void end() {
            if (names == null) {
                throw headerNotFound();
            }
        }

        int rows() {
            return rows;
        }

        int columns() {
            return names == null ? 0 : names.size();
        }

        int headerRowNo() {
            return headerRowNo;
        }

        private boolean isHeader(List<Cell> cells) {
            if (limits.requiredHeaders().isEmpty()) {
                return true;
            }
            Set<String> present = cells.stream()
                    .filter(c -> c != null && c.value() != null)
                    .map(c -> key(String.valueOf(c.value())))
                    .collect(Collectors.toSet());
            return present.containsAll(limits.requiredHeaders());
        }

        private void header(int rowNo, List<Cell> cells) {
            int width = 0;
            for (int i = 0; i < cells.size(); i++) {
                if (cells.get(i) != null && !cells.get(i).blank()) {
                    width = i + 1;
                }
            }
            if (width > limits.maxColumns()) {
                throw tooManyColumns();
            }
            List<String> out = new ArrayList<>(width);
            for (int i = 0; i < width; i++) {
                Cell c = cells.get(i);
                String name = c == null || c.blank()
                        ? "column_" + (i + 1)
                        : String.valueOf(c.value()).strip();
                out.add(free(name, true));
            }
            names = List.copyOf(out);
            headerRowNo = rowNo;
            lastRowNo = rowNo;
            sink.header(rowNo, names);
        }

        /**
         * The name, or the name with the first count no header holds, {@code Phone (2)}, so no cell is lost under another's
         * name, even for {@code Phone, Phone, Phone (2)} or a header named {@code column_7}; {@code take} keeps it.
         */
        private String free(String name, boolean take) {
            String candidate = name;
            for (int n = 2; take ? !seen.add(key(candidate)) : seen.contains(key(candidate)); n++) {
                candidate = name + " (" + n + ")";
            }
            return candidate;
        }

        private Failure tooManyRows() {
            return Parsing.tooManyRows(limits);
        }

        private Failure tooManyColumns() {
            return Parsing.tooManyColumns(limits);
        }
    }

    static Failure tooManyRows(Limits limits) {
        return new Failure(
                TOO_MANY_ROWS,
                "The file is over the limit of " + limits.maxRows() + " rows; split it and upload the parts.");
    }

    static Failure tooManyColumns(Limits limits) {
        return new Failure(
                TOO_MANY_COLUMNS,
                "The file is over the limit of " + limits.maxColumns() + " columns; remove the columns not needed.");
    }

    static long maxExpandedChars(Limits limits) {
        return EXPANSION_FACTOR * limits.maxFileBytes();
    }

    static Failure tooMuchText(Limits limits) {
        return new Failure(
                FILE_TOO_LARGE,
                "The file's cells expand to more than " + maxExpandedChars(limits) / 1_000_000
                        + " million characters when read; split it and upload the parts.");
    }

    static Failure headerNotFound() {
        return new Failure(
                HEADER_NOT_FOUND, "No row matches the mapping's headers in the first " + HEADER_SEARCH_ROWS + " rows.");
    }

    static Failure unreadable(String reason) {
        return new Failure(FILE_UNREADABLE, reason);
    }
}
