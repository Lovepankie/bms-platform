package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.internal.Parsing.Cell;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads one sheet of an XLSX workbook with a streaming reader (StAX over the zip, read forward), never the workbook as a
 * whole. Two passes over the file, each a fresh stream: the first reads the workbook (sheet names, the 1904 date system),
 * its relationships, the styles (which cells are dates) and the shared strings; the second streams the chosen sheet row by
 * row. What the first pass keeps (the shared strings above all, then the styles, sheets and relationships) is charged
 * against a memory budget, {@link Caps#heldBytes}, at an estimate of what each entry costs on the heap, so many empty or
 * one-character strings are stopped as surely as long ones; every text is read in pieces and stopped at
 * {@value Parsing#MAX_CELL_CHARS} characters; a part named twice is refused; and the inflated size of one pass is capped
 * at {@link Caps#inflatedBytes} (a zip bomb). Over a cap is {@code FILE_TOO_LARGE}.
 *
 * <p>Values are what the sheet shows (the lesson of the retail data dictionary, section 3): a formula's cached value; a
 * number as Excel shows it in General, to 15 significant digits; a number in a date style as the date it shows,
 * {@code 2026-06-30}, or with its time, {@code 2026-06-30T14:05:00}, never shifted by a time zone; a time alone as
 * {@code 14:05:00}; text, booleans and errors as they are. The sheet is the one named, or the first that is not hidden.
 */
final class XlsxRows {

    /**
     * The reader's own memory and size caps.
     *
     * @param inflatedBytes the most a pass may unpack
     * @param heldBytes the most the first pass may keep, at the estimate of {@link Workbook#charge}
     */
    record Caps(long inflatedBytes, long heldBytes) {

        /**
         * 1 GiB unpacked, and 96 MiB kept, under half the staging heap of 224 MB: room for a workbook at the row limit
         * whose every text is its own (100,000 rows of nine texts each, {@code UploadMemoryTest}), and no more.
         */
        static final Caps DEFAULT = new Caps(1L << 30, 96L << 20);
    }

    /** The longest sheet name a batch records: {@code import_batches.sheet_name} is {@code varchar(100)} (V129). */
    static final int MAX_SHEET_NAME = 100;

    /**
     * The type of a cell that holds a formula saved without its value (issue #137): a file a program wrote and no
     * spreadsheet ever calculated. Its value is empty, and the validation reports it, never reading it as an empty field.
     */
    static final String FORMULA = "formula";

    /** The parts of the first pass; each may appear once. */
    private static final java.util.Set<String> PARTS =
            java.util.Set.of("xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/sharedStrings.xml");

    /** The built-in number formats that are dates, times or both (ECMA-376 part 1, 18.8.30). */
    private static final BitSet BUILTIN_DATE = new BitSet();

    private static final BitSet BUILTIN_TIME_ONLY = new BitSet();

    /** The precision Excel shows a number with in General. */
    private static final MathContext SHOWN = new MathContext(15);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    static {
        BUILTIN_DATE.set(14, 23);
        BUILTIN_DATE.set(27, 37);
        BUILTIN_DATE.set(45, 48);
        BUILTIN_DATE.set(50, 59);
        BUILTIN_TIME_ONLY.set(18, 22);
        BUILTIN_TIME_ONLY.set(45, 48);
    }

    /** Opens the file again from its start. */
    @FunctionalInterface
    interface Source {

        InputStream open() throws IOException;
    }

    private XlsxRows() {}

    /** @return the name of the sheet read */
    static String read(Source source, String sheetName, Parsing.Sheet sheet) throws IOException {
        return read(source, sheetName, sheet, Caps.DEFAULT);
    }

    static String read(Source source, String sheetName, Parsing.Sheet sheet, Caps caps) throws IOException {
        // No sheet within the limits can name more distinct texts than it has cells below and in its header.
        Workbook book = new Workbook(
                caps, ((long) sheet.limits().maxRows() + 1) * sheet.limits().maxColumns());
        java.util.Set<String> seen = new java.util.HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(source.open())) {
            Inflated in = new Inflated(zip, caps.inflatedBytes());
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (PARTS.contains(e.getName()) && !seen.add(e.getName())) {
                    throw Parsing.unreadable("The workbook holds one of its parts twice.");
                }
                switch (e.getName()) {
                    case "xl/workbook.xml" -> book.readWorkbook(in);
                    case "xl/_rels/workbook.xml.rels" -> book.readRelationships(in);
                    case "xl/styles.xml" -> book.readStyles(in);
                    case "xl/sharedStrings.xml" -> book.readSharedStrings(in);
                    default -> in.skipEntry();
                }
            }
        } catch (IOException | XMLStreamException e) {
            // A zip cut short or damaged, or XML that is not well formed.
            throw damaged(e, "The workbook is damaged or not an XLSX file.");
        }
        Workbook.SheetRef chosen = book.choose(sheetName);
        boolean found = false;
        try (ZipInputStream zip = new ZipInputStream(source.open())) {
            Inflated in = new Inflated(zip, caps.inflatedBytes());
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals(chosen.path())) {
                    book.readSheet(in, sheet);
                    found = true;
                    break;
                }
                in.skipEntry();
            }
        } catch (IOException | XMLStreamException e) {
            throw damaged(e, "The sheet is damaged and cannot be read.");
        }
        if (!found) {
            throw Parsing.unreadable("The workbook names a sheet it does not hold.");
        }
        sheet.end();
        return chosen.name();
    }

    /**
     * A damaged file is {@code FILE_UNREADABLE}; a quarantined file that failed its authentication is not the customer's
     * damage, even when the XML reader wrapped it, and goes up as it is.
     */
    private static Parsing.Failure damaged(Exception e, String reason) throws QuarantineCipher.Unreadable {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof QuarantineCipher.Unreadable u) {
                throw u;
            }
        }
        return Parsing.unreadable(reason);
    }

    private static XMLStreamReader xml(InputStream in) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        // Not coalescing: a long text comes in pieces, so readText can stop it before it is whole.
        f.setProperty(XMLInputFactory.IS_COALESCING, false);
        return f.createXMLStreamReader(in);
    }

    // JUSTIFICATION-A3: review of #790 item 1; getElementText has no bound, so texts are read in pieces with one.
    /**
     * The text of the element just started, read piece by piece up to its end; refused past
     * {@value Parsing#MAX_CELL_CHARS} characters, so no single text is ever held whole beyond that.
     */
    private static String readText(XMLStreamReader r, String where) throws XMLStreamException {
        StringBuilder s = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int ev = r.next();
            switch (ev) {
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    if (s.length() + r.getTextLength() > Parsing.MAX_CELL_CHARS) {
                        throw Parsing.unreadable(
                                "A text " + where + " is longer than " + Parsing.MAX_CELL_CHARS + " characters.");
                    }
                    s.append(r.getTextCharacters(), r.getTextStart(), r.getTextLength());
                }
                case XMLStreamConstants.START_ELEMENT -> depth++;
                case XMLStreamConstants.END_ELEMENT -> depth--;
                case XMLStreamConstants.END_DOCUMENT -> throw Parsing.unreadable("A text " + where + " is cut off.");
                default -> {}
            }
        }
        return s.toString();
    }

    private static final class Workbook {

        record SheetRef(String name, String path) {}

        private record Declared(String name, String relationshipId, boolean hidden) {}

        private final List<Declared> sheets = new ArrayList<>();
        private final Map<String, String> targets = new HashMap<>();
        private final List<String> shared = new ArrayList<>();
        private final Map<Integer, String> customFormats = new HashMap<>();
        private final List<Integer> styleFormats = new ArrayList<>();
        private final Map<Integer, DateKind> dateKinds = new HashMap<>();
        private boolean date1904;
        private final Caps caps;
        private long held;

        private final long maxShared;

        Workbook(Caps caps, long maxShared) {
            this.caps = caps;
            this.maxShared = maxShared;
        }

        /**
         * Charges one kept entry against {@link Caps#heldBytes}: 56 bytes for its objects and the list's slot, so an empty
         * string costs what it really does, and a byte a character (a compact Latin-1 string) or two otherwise.
         */
        private void charge(int chars) {
            charge(56L + chars);
        }

        private void charge(CharSequence s) {
            boolean latin1 = s.chars().allMatch(c -> c < 256);
            charge(56L + (latin1 ? s.length() : 2L * s.length()));
        }

        private void charge(long bytes) {
            held += bytes;
            if (held > caps.heldBytes()) {
                throw new Parsing.Failure(
                        Parsing.FILE_TOO_LARGE,
                        "The workbook holds more text than an import can read at once; split it and upload the parts.");
            }
        }

        void readWorkbook(InputStream in) throws XMLStreamException {
            XMLStreamReader r = xml(in);
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "workbookPr" -> {
                            String v = attr(r, "date1904");
                            date1904 = "1".equals(v) || "true".equals(v);
                        }
                        case "sheet" -> {
                            charge(64);
                            String state = attr(r, "state");
                            sheets.add(new Declared(
                                    attr(r, "name"),
                                    relationshipId(r),
                                    "hidden".equals(state) || "veryHidden".equals(state)));
                        }
                        default -> {}
                    }
                }
            }
        }

        void readRelationships(InputStream in) throws XMLStreamException {
            XMLStreamReader r = xml(in);
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && "Relationship".equals(r.getLocalName())) {
                    String target = attr(r, "Target");
                    if (target != null) {
                        charge(target.length() + 32);
                        targets.put(attr(r, "Id"), target.startsWith("/") ? target.substring(1) : "xl/" + target);
                    }
                }
            }
        }

        void readStyles(InputStream in) throws XMLStreamException {
            XMLStreamReader r = xml(in);
            boolean inCellXfs = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "numFmt" -> {
                            String code = String.valueOf(attr(r, "formatCode"));
                            charge(code.length());
                            customFormats.put(intAttr(r, "numFmtId", -1), code);
                        }
                        case "cellXfs" -> inCellXfs = true;
                        case "xf" -> {
                            if (inCellXfs) {
                                charge(0);
                                styleFormats.add(intAttr(r, "numFmtId", 0));
                            }
                        }
                        default -> {}
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT && "cellXfs".equals(r.getLocalName())) {
                    inCellXfs = false;
                }
            }
        }

        void readSharedStrings(InputStream in) throws XMLStreamException {
            XMLStreamReader r = xml(in);
            StringBuilder si = null;
            int phonetic = 0;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "si" -> si = new StringBuilder();
                        case "rPh" -> phonetic++;
                        case "t" -> {
                            if (si != null && phonetic == 0) {
                                si.append(readText(r, "in the shared strings"));
                                if (si.length() > Parsing.MAX_CELL_CHARS) {
                                    throw Parsing.unreadable("A text in the shared strings is longer than "
                                            + Parsing.MAX_CELL_CHARS + " characters.");
                                }
                            }
                        }
                        default -> {}
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if ("rPh".equals(r.getLocalName())) {
                        phonetic--;
                    } else if ("si".equals(r.getLocalName()) && si != null) {
                        if (shared.size() >= maxShared) {
                            throw new Parsing.Failure(
                                    Parsing.FILE_TOO_LARGE,
                                    "The workbook holds more texts than its sheet can have cells; split it and upload the parts.");
                        }
                        charge(si);
                        shared.add(si.isEmpty() ? "" : si.toString());
                        si = null;
                    }
                }
            }
        }

        SheetRef choose(String wanted) {
            for (Declared d : sheets) {
                boolean match = wanted == null || wanted.isBlank()
                        ? !d.hidden()
                        : Parsing.key(d.name()).equals(Parsing.key(wanted));
                if (match && targets.containsKey(d.relationshipId())) {
                    // Excel keeps names to 31 characters; a hand-edited file can carry any, and the batch records it.
                    if (d.name() != null && d.name().length() > MAX_SHEET_NAME) {
                        throw Parsing.unreadable("The sheet's name is longer than " + MAX_SHEET_NAME + " characters.");
                    }
                    return new SheetRef(d.name(), targets.get(d.relationshipId()));
                }
            }
            throw Parsing.unreadable(
                    wanted == null || wanted.isBlank()
                            ? "The workbook has no sheet to read."
                            : "The workbook has no sheet of the name given.");
        }

        void readSheet(InputStream in, Parsing.Sheet sheet) throws XMLStreamException {
            XMLStreamReader r = xml(in);
            List<Cell> row = null;
            int rowNo = 0;
            int col = 0;
            String type = null;
            int style = 0;
            String value = null;
            StringBuilder inline = null;
            boolean formula = false;
            boolean valueSeen = false;
            int phonetic = 0;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "row" -> {
                            int n = intAttr(r, "r", rowNo + 1);
                            if (n <= rowNo) {
                                throw Parsing.unreadable("The sheet's rows are out of order after row " + rowNo + ".");
                            }
                            rowNo = n;
                            row = new ArrayList<>();
                            col = 0;
                        }
                        case "c" -> {
                            String ref = attr(r, "r");
                            col = ref == null ? col + 1 : column(ref, rowNo);
                            type = attr(r, "t");
                            style = intAttr(r, "s", 0);
                            value = null;
                            inline = null;
                            formula = false;
                            valueSeen = false;
                        }
                        case "f" -> formula = true;
                        case "v" -> {
                            value = readText(r, "on row " + rowNo);
                            valueSeen = true;
                        }
                        case "is" -> inline = new StringBuilder();
                        // A phonetic reading is not the cell's text, here as in the shared strings (review of #790).
                        case "rPh" -> phonetic++;
                        case "t" -> {
                            if (inline != null && phonetic == 0) {
                                inline.append(readText(r, "on row " + rowNo));
                                // Checked after every run, as shared strings are, so many runs cannot grow one cell.
                                if (inline.length() > Parsing.MAX_CELL_CHARS) {
                                    throw Parsing.unreadable("A cell on row " + rowNo + " is longer than "
                                            + Parsing.MAX_CELL_CHARS + " characters.");
                                }
                            }
                        }
                        default -> {}
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "rPh" -> phonetic--;
                        case "c" -> {
                            if (row == null) {
                                throw Parsing.unreadable("The sheet has a cell outside a row.");
                            }
                            if (col > CsvRows.MAX_FIELDS) {
                                throw Parsing.unreadable("A cell on row " + rowNo + " is beyond the last column.");
                            }
                            Cell cell = cell(type, style, value, inline, rowNo);
                            // A formula whose value is empty text (=IF(A2="","",...)) is an empty cell, as shown.
                            if (cell == null && formula && !valueSeen && inline == null) {
                                cell = new Cell("", FORMULA);
                            }
                            while (row.size() < col) {
                                row.add(null);
                            }
                            row.set(col - 1, cell);
                        }
                        case "row" -> {
                            sheet.row(rowNo, row);
                            row = null;
                        }
                        default -> {}
                    }
                }
            }
        }

        private Cell cell(String type, int style, String value, StringBuilder inline, int rowNo) {
            String t = type == null ? "n" : type;
            if ("inlineStr".equals(t)) {
                return inline == null || inline.isEmpty() ? null : text(inline.toString(), rowNo);
            }
            if (value == null || value.isEmpty()) {
                return null;
            }
            return switch (t) {
                case "s" -> {
                    int i = parseIndex(value, rowNo);
                    if (i < 0 || i >= shared.size()) {
                        throw Parsing.unreadable("A cell on row " + rowNo + " names text the workbook does not hold.");
                    }
                    String s = shared.get(i);
                    yield s.isEmpty() ? null : text(s, rowNo);
                }
                case "str" -> text(value, rowNo);
                case "b" -> new Cell("1".equals(value) || "true".equalsIgnoreCase(value), "bool");
                case "e" -> new Cell(value, "error");
                case "d" -> value.contains("T") ? new Cell(value, "datetime") : new Cell(value, "date");
                default -> number(value, style, rowNo);
            };
        }

        private static Cell text(String s, int rowNo) {
            if (s.length() > Parsing.MAX_CELL_CHARS) {
                throw Parsing.unreadable(
                        "A cell on row " + rowNo + " is longer than " + Parsing.MAX_CELL_CHARS + " characters.");
            }
            return Cell.text(s);
        }

        private Cell number(String value, int style, int rowNo) {
            double d;
            try {
                d = Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw Parsing.unreadable("A number on row " + rowNo + " cannot be read.");
            }
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw Parsing.unreadable("A number on row " + rowNo + " cannot be read.");
            }
            int format = style >= 0 && style < styleFormats.size() ? styleFormats.get(style) : 0;
            DateKind kind = dateKind(format);
            if (kind != DateKind.NONE && d >= 0 && d < 2_958_466) {
                return date(d, kind);
            }
            BigDecimal n = new BigDecimal(d).round(SHOWN).stripTrailingZeros();
            return new Cell(n.scale() < 0 ? n.setScale(0) : n, "number");
        }

        private enum DateKind {
            NONE,
            DATE,
            DATETIME,
            TIME
        }

        /** Each format is analysed once, not once a cell. */
        private DateKind dateKind(int format) {
            return dateKinds.computeIfAbsent(format, this::analyse);
        }

        private DateKind analyse(int format) {
            if (BUILTIN_TIME_ONLY.get(format)) {
                return DateKind.TIME;
            }
            if (BUILTIN_DATE.get(format)) {
                return format == 22 ? DateKind.DATETIME : DateKind.DATE;
            }
            String code = customFormats.get(format);
            if (code == null) {
                return DateKind.NONE;
            }
            // Drop quoted text, escapes and [colour] or [$-locale] sections, then look for date and time tokens.
            String bare = code.replaceAll("\"[^\"]*\"", "")
                    .replaceAll("\\\\.", "")
                    .replaceAll("\\[[^\\]]*\\]", "")
                    .toLowerCase(Locale.ROOT);
            boolean time = bare.matches(".*[hs].*");
            // A lone m is a month only in a format without digit placeholders: "0.0 mm" is a number with a unit.
            boolean digits = bare.matches(".*[0#?].*");
            boolean date = bare.matches(".*[dy].*") || (bare.matches(".*m.*") && !time && !digits);
            if (date && time) {
                return DateKind.DATETIME;
            }
            if (date) {
                return DateKind.DATE;
            }
            return time ? DateKind.TIME : DateKind.NONE;
        }

        private Cell date(double serial, DateKind kind) {
            long days = (long) Math.floor(serial);
            long seconds = Math.round((serial - days) * 86_400);
            if (seconds == 86_400) {
                days++;
                seconds = 0;
            }
            LocalDate day;
            if (date1904) {
                day = LocalDate.of(1904, 1, 1).plusDays(days);
            } else {
                // Excel's 1900 system counts a 29 February 1900 that never was (serial 60); before it, one day less.
                day = days < 60
                        ? LocalDate.of(1899, 12, 31).plusDays(days)
                        : LocalDate.of(1899, 12, 30).plusDays(days);
            }
            LocalTime time = LocalTime.ofSecondOfDay(seconds);
            return switch (kind) {
                case TIME -> new Cell(TIME.format(time), "time");
                case DATETIME -> new Cell(DATETIME.format(LocalDateTime.of(day, time)), "datetime");
                default -> new Cell(day.toString(), "date");
            };
        }

        private static int parseIndex(String v, int rowNo) {
            try {
                return Integer.parseInt(v.strip());
            } catch (NumberFormatException e) {
                throw Parsing.unreadable("A cell on row " + rowNo + " names text the workbook does not hold.");
            }
        }
    }

    /** The 1-based column of a reference such as {@code AB12}. */
    static int column(String ref, int rowNo) {
        int col = 0;
        for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++) {
            char ch = Character.toUpperCase(ref.charAt(i));
            if (ch < 'A' || ch > 'Z' || col > CsvRows.MAX_FIELDS) {
                throw Parsing.unreadable("A cell on row " + rowNo + " has a reference that cannot be read.");
            }
            col = col * 26 + (ch - 'A' + 1);
        }
        if (col == 0) {
            throw Parsing.unreadable("A cell on row " + rowNo + " has a reference that cannot be read.");
        }
        return col;
    }

    private static String attr(XMLStreamReader r, String name) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (name.equals(r.getAttributeLocalName(i))) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    /** The {@code r:id} of a sheet, in the relationships namespace whatever its prefix. */
    private static String relationshipId(XMLStreamReader r) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if ("id".equals(r.getAttributeLocalName(i)) && r.getAttributeNamespace(i) != null) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    private static int intAttr(XMLStreamReader r, String name, int fallback) {
        String v = attr(r, name);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            throw Parsing.unreadable("The workbook has a number it cannot read in its structure.");
        }
    }

    /**
     * The zip's entries as the XML reader takes them: counts the inflated bytes of one pass, the entries skipped included,
     * and stops at the cap, and never closes the zip under the loop when the reader is done with an entry.
     */
    private static final class Inflated extends FilterInputStream {

        private final long cap;
        private long taken;

        Inflated(ZipInputStream zip, long cap) {
            super(zip);
            this.cap = cap;
        }

        /** Reads past the rest of the current entry, counted, so an entry not read is not a way round the cap. */
        void skipEntry() throws IOException {
            transferTo(OutputStream.nullOutputStream());
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            count(b == -1 ? 0 : 1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            count(Math.max(n, 0));
            return n;
        }

        private void count(int n) {
            taken += n;
            if (taken > cap) {
                throw new Parsing.Failure(
                        Parsing.FILE_TOO_LARGE,
                        "The workbook unpacks to more than an import can read; split it and upload the parts.");
            }
        }

        @Override
        public void close() {}
    }
}
