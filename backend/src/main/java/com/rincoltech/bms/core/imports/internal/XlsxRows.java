package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.internal.Parsing.Cell;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * row. The shared strings are the only part held, up to {@link #MAX_SHARED_CHARS} characters; the inflated size of the zip
 * is capped at {@link #MAX_INFLATED} bytes (a zip bomb is {@code FILE_TOO_LARGE}).
 *
 * <p>Values are what the sheet shows (the lesson of the retail data dictionary, section 3): a formula's cached value; a
 * number as Excel shows it in General, to 15 significant digits; a number in a date style as the date it shows,
 * {@code 2026-06-30}, or with its time, {@code 2026-06-30T14:05:00}, never shifted by a time zone; a time alone as
 * {@code 14:05:00}; text, booleans and errors as they are. The sheet is the one named, or the first that is not hidden.
 */
final class XlsxRows {

    static final long MAX_INFLATED = 1L << 30;

    static final int MAX_SHARED_CHARS = 32 * 1024 * 1024;

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
        Workbook book = new Workbook();
        try (ZipInputStream zip = new ZipInputStream(source.open())) {
            Inflated in = new Inflated(zip);
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                switch (e.getName()) {
                    case "xl/workbook.xml" -> book.readWorkbook(in);
                    case "xl/_rels/workbook.xml.rels" -> book.readRelationships(in);
                    case "xl/styles.xml" -> book.readStyles(in);
                    case "xl/sharedStrings.xml" -> book.readSharedStrings(in);
                    default -> {}
                }
            }
        } catch (IOException | XMLStreamException e) {
            // A zip cut short or damaged, or XML that is not well formed.
            throw damaged(e, "The workbook is damaged or not an XLSX file.");
        }
        Workbook.SheetRef chosen = book.choose(sheetName);
        boolean found = false;
        try (ZipInputStream zip = new ZipInputStream(source.open())) {
            Inflated in = new Inflated(zip);
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equals(chosen.path())) {
                    book.readSheet(in, sheet);
                    found = true;
                    break;
                }
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
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        return f.createXMLStreamReader(in);
    }

    private static final class Workbook {

        record SheetRef(String name, String path) {}

        private record Declared(String name, String relationshipId, boolean hidden) {}

        private final List<Declared> sheets = new ArrayList<>();
        private final Map<String, String> targets = new HashMap<>();
        private final List<String> shared = new ArrayList<>();
        private final Map<Integer, String> customFormats = new HashMap<>();
        private final List<Integer> styleFormats = new ArrayList<>();
        private boolean date1904;
        private long sharedChars;

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
                        targets.put(
                                attr(r, "Id"), target.startsWith("/") ? target.substring(1) : "xl/" + target);
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
                        case "numFmt" ->
                            customFormats.put(intAttr(r, "numFmtId", -1), String.valueOf(attr(r, "formatCode")));
                        case "cellXfs" -> inCellXfs = true;
                        case "xf" -> {
                            if (inCellXfs) {
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
                                si.append(r.getElementText());
                            }
                        }
                        default -> {}
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if ("rPh".equals(r.getLocalName())) {
                        phonetic--;
                    } else if ("si".equals(r.getLocalName()) && si != null) {
                        sharedChars += si.length();
                        if (sharedChars > MAX_SHARED_CHARS) {
                            throw new Parsing.Failure(
                                    Parsing.FILE_TOO_LARGE,
                                    "The workbook holds more text than an import can read at once; split it and upload the parts.");
                        }
                        shared.add(si.toString());
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
                        }
                        case "v" -> value = r.getElementText();
                        case "is" -> inline = new StringBuilder();
                        case "t" -> {
                            if (inline != null) {
                                inline.append(r.getElementText());
                            }
                        }
                        default -> {}
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "c" -> {
                            if (row == null) {
                                throw Parsing.unreadable("The sheet has a cell outside a row.");
                            }
                            if (col > CsvRows.MAX_FIELDS) {
                                throw Parsing.unreadable("A cell on row " + rowNo + " is beyond the last column.");
                            }
                            Cell cell = cell(type, style, value, inline, rowNo);
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

        private DateKind dateKind(int format) {
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
            boolean date = bare.matches(".*[dy].*") || bare.matches(".*m.*") && !bare.matches(".*[hs].*");
            boolean time = bare.matches(".*[hs].*");
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
                day = days < 60 ? LocalDate.of(1899, 12, 31).plusDays(days) : LocalDate.of(1899, 12, 30).plusDays(days);
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
     * The zip's entries as the XML reader takes them: counts the inflated bytes of one pass and stops at
     * {@link #MAX_INFLATED}, and never closes the zip under the loop when the reader is done with an entry.
     */
    private static final class Inflated extends FilterInputStream {

        private long taken;

        Inflated(ZipInputStream zip) {
            super(zip);
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
            if (taken > MAX_INFLATED) {
                throw new Parsing.Failure(
                        Parsing.FILE_TOO_LARGE,
                        "The workbook unpacks to more than an import can read; split it and upload the parts.");
            }
        }

        @Override
        public void close() {}
    }
}
