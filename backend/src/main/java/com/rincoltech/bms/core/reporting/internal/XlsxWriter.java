package com.rincoltech.bms.core.reporting.internal;

import com.rincoltech.bms.core.reporting.ReportFailure;
import com.rincoltech.bms.core.reporting.ReportOutput.Column;
import com.rincoltech.bms.core.reporting.ReportOutput.Kind;
import com.rincoltech.bms.core.reporting.internal.Renderers.Header;
import com.rincoltech.bms.core.reporting.internal.Spool.Guard;
import com.rincoltech.bms.core.reporting.internal.Spool.TableMeta;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A minimal streaming {@code .xlsx} writer (ADR-038 decision 6, {@code docs/specs/client-extras.md} section 6.6). Apache POI
 * is not in the build and its streaming workbook would add about 20 MB of libraries to an API that runs on a 224 MB heap,
 * so the few parts of SpreadsheetML a report needs are written directly: a zip of XML parts, the sheets written row by row
 * from the spool as the rows are read, so memory does not grow with the table (one row, one buffer of 64 KiB).
 *
 * <p>The workbook: a {@code Parameters} sheet first (the report, the date it stands at, when the data was read and the file
 * made, by whom, the currency, the parameters in words and the notes), then a sheet for each table of the report. Money is a
 * numeric cell in whole currency units with a thousands format (the exact decimal text of the integer minor units, never a
 * floating point value); a rate is the exact fraction shown as a percentage to one decimal; a date is a real date; a count is
 * a number; a missing figure is an empty cell. The header row is frozen, filled in the tenant's brand colour, and repeated on
 * every printed page; totals are values written by the report (not formulas), set off in bold between rules, so the
 * workbook cannot differ from the PDF.
 *
 * <p><b>Formula injection (FR-RPT-07).</b> Text is always written as a string cell, never as a formula. A text value that
 * begins with {@code =}, {@code +}, {@code -}, {@code @}, a tab or a carriage return also carries the {@code quotePrefix}
 * style, which is what a spreadsheet itself sets for text typed with a leading apostrophe: it stays text if someone edits the
 * cell. Characters XML cannot hold are dropped, markup characters are escaped, and a cell is cut at the 32,767 characters a
 * cell can hold.
 */
final class XlsxWriter {

    /** The most rows a sheet can hold. */
    static final int MAX_ROWS = 1_048_576;

    static final int MAX_CELL_CHARS = 32_767;

    private static final String MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    // cellXfs indexes; styles() writes them in this order.
    private static final int S_HEAD = 1;
    private static final int S_HEAD_RIGHT = 2;
    private static final int S_TEXT = 3;
    private static final int S_COUNT = 4;
    private static final int S_MONEY = 5;
    private static final int S_RATE = 6;
    private static final int S_DATE = 7;
    private static final int S_TEXT_Q = 8;
    private static final int S_TOTAL = 9; // + 0 text, 1 count, 2 money, 3 rate, 4 date, 5 quoted text
    private static final int S_TITLE = 15;
    private static final int S_LABEL = 16;
    private static final int S_WRAP = 17;
    private static final int S_WRAP_Q = 18;

    private XlsxWriter() {}

    /** Writes the workbook of one run to {@code target}, stopping with {@code report_too_large} past {@code maxBytes}. */
    static void write(
            Path target, long maxBytes, Header h, Presentation p, List<TableMeta> tables, Spool spool, Guard guard) {
        int exponent = ReportFormats.exponent(h.currency());
        List<String> names = sheetNames(tables);
        for (TableMeta t : tables) {
            if (t.rows() + 1 > MAX_ROWS) {
                throw new ReportFailure("report_too_large", "A table has more rows than a sheet can hold.");
            }
        }
        try (OutputStream raw = Renderers.capped(Files.newOutputStream(target), maxBytes);
                ZipOutputStream zip = new ZipOutputStream(raw)) {
            long stamp = h.generatedAt().toEpochMilli();
            part(zip, stamp, "[Content_Types].xml", contentTypes(tables.size()));
            part(zip, stamp, "_rels/.rels", rootRels());
            part(zip, stamp, "xl/workbook.xml", workbook(names, tables));
            part(zip, stamp, "xl/_rels/workbook.xml.rels", workbookRels(tables.size()));
            part(zip, stamp, "xl/styles.xml", styles(p, exponent));
            sheet(zip, stamp, "xl/worksheets/sheet1.xml", w -> parametersSheet(w, h, p, exponent));
            for (int i = 0; i < tables.size(); i++) {
                TableMeta t = tables.get(i);
                sheet(zip, stamp, "xl/worksheets/sheet" + (i + 2) + ".xml", w -> tableSheet(w, t, h, spool, guard));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- parts
    // -------------------------------------------------------------------------------------------------------

    private static void part(ZipOutputStream zip, long stamp, String name, String xml) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(stamp);
        zip.putNextEntry(e);
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private interface Body {
        void write(Writer w) throws IOException;
    }

    private static void sheet(ZipOutputStream zip, long stamp, String name, Body body) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(stamp);
        zip.putNextEntry(e);
        Writer w = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8), 64 * 1024);
        body.write(w);
        w.flush();
        zip.closeEntry();
    }

    private static String contentTypes(int tables) {
        StringBuilder sb = new StringBuilder(XML)
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append(
                        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append(
                        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                .append(
                        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= tables + 1; i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet")
                    .append(i)
                    .append(
                            ".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return sb.append("</Types>").toString();
    }

    private static String rootRels() {
        return XML
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + REL + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private static String workbook(List<String> names, List<TableMeta> tables) {
        StringBuilder sb = new StringBuilder(XML)
                .append("<workbook xmlns=\"")
                .append(MAIN)
                .append("\" xmlns:r=\"")
                .append(REL)
                .append("\">")
                .append("<bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>");
        for (int i = 0; i < names.size(); i++) {
            sb.append("<sheet name=\"")
                    .append(escape(names.get(i)))
                    .append("\" sheetId=\"")
                    .append(i + 1)
                    .append("\" r:id=\"rId")
                    .append(i + 1)
                    .append("\"/>");
        }
        sb.append("</sheets><definedNames>");
        for (int i = 0; i < tables.size(); i++) {
            // The header row repeats on every printed page.
            sb.append("<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"")
                    .append(i + 1)
                    .append("\">'")
                    .append(escape(names.get(i + 1).replace("'", "''")))
                    .append("'!$1:$1</definedName>");
        }
        return sb.append("</definedNames></workbook>").toString();
    }

    private static String workbookRels(int tables) {
        StringBuilder sb = new StringBuilder(XML)
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= tables + 1; i++) {
            sb.append("<Relationship Id=\"rId")
                    .append(i)
                    .append("\" Type=\"")
                    .append(REL)
                    .append("/worksheet\" Target=\"worksheets/sheet")
                    .append(i)
                    .append(".xml\"/>");
        }
        sb.append("<Relationship Id=\"rId")
                .append(tables + 2)
                .append("\" Type=\"")
                .append(REL)
                .append("/styles\" Target=\"styles.xml\"/>");
        return sb.append("</Relationships>").toString();
    }

    /** The sheet names: the Parameters sheet, then each table's title cleaned to what a sheet name may hold, made unique. */
    static List<String> sheetNames(List<TableMeta> tables) {
        List<String> names = new ArrayList<>();
        Set<String> used = new HashSet<>();
        names.add("Parameters");
        used.add("parameters");
        for (TableMeta t : tables) {
            String name = legalName(t.title(), 31);
            int n = 2;
            String base = t.title();
            while (!used.add(name.toLowerCase(Locale.ROOT))) {
                String suffix = " " + n++;
                name = legalName(base, 31 - suffix.length()) + suffix;
            }
            names.add(name);
        }
        return names;
    }

    /**
     * A sheet name from a title: characters XML cannot hold dropped first (so what is compared for uniqueness is what is
     * written), the characters a sheet name may not hold made spaces, then cut to {@code max} and only then stripped of the
     * apostrophes a name may not begin or end with (a cut can leave one at the end).
     */
    static String legalName(String title, int max) {
        String name = strip(title)
                .replaceAll("[\\[\\]:*?/\\\\]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        name = cut(name, max).replaceAll("^'+|'+$", "").trim();
        return name.isEmpty() ? "Table" : name;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).trim();
    }

    // ---- styles
    // ------------------------------------------------------------------------------------------------------

    private static String styles(Presentation p, int exponent) {
        String money = exponent == 0 ? "3" : "4"; // built in: #,##0 and #,##0.00
        String brand = "FF" + Presentation.hex(p.brand()).toUpperCase(Locale.ROOT);
        String onBrand = "FF" + Presentation.hex(p.onBrand()).toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(XML)
                .append("<styleSheet xmlns=\"")
                .append(MAIN)
                .append("\">");
        sb.append("<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"0.0%\"/>")
                .append("<numFmt numFmtId=\"165\" formatCode=\"yyyy\\-mm\\-dd\"/></numFmts>");
        sb.append("<fonts count=\"4\">")
                .append("<font><sz val=\"10\"/><name val=\"Arial\"/></font>")
                .append("<font><b/><sz val=\"10\"/><name val=\"Arial\"/></font>")
                .append("<font><b/><sz val=\"10\"/><color rgb=\"")
                .append(onBrand)
                .append("\"/><name val=\"Arial\"/></font>")
                .append("<font><b/><sz val=\"14\"/><name val=\"Arial\"/></font></fonts>");
        sb.append("<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill>")
                .append("<fill><patternFill patternType=\"gray125\"/></fill>")
                .append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"")
                .append(brand)
                .append("\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>");
        sb.append("<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>")
                .append("<border><left/><right/><top style=\"thin\"><color rgb=\"")
                .append(brand)
                .append("\"/></top><bottom style=\"thin\"><color rgb=\"")
                .append(brand)
                .append("\"/></bottom><diagonal/></border></borders>");
        sb.append(
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>");
        sb.append("<cellXfs count=\"19\">");
        xf(sb, "0", 0, 0, 0, "", false); // 0 default
        xf(sb, "0", 2, 2, 0, "<alignment horizontal=\"left\" vertical=\"center\"/>", false); // 1 header
        xf(sb, "0", 2, 2, 0, "<alignment horizontal=\"right\" vertical=\"center\"/>", false); // 2 header, numbers
        xf(sb, "0", 0, 0, 0, "", false); // 3 text
        xf(sb, "3", 0, 0, 0, "", false); // 4 count
        xf(sb, money, 0, 0, 0, "", false); // 5 money
        xf(sb, "164", 0, 0, 0, "", false); // 6 rate
        xf(sb, "165", 0, 0, 0, "<alignment horizontal=\"right\"/>", false); // 7 date
        xf(sb, "0", 0, 0, 0, "", true); // 8 text with the quote prefix
        xf(sb, "0", 1, 0, 1, "", false); // 9 total text
        xf(sb, "3", 1, 0, 1, "", false); // 10 total count
        xf(sb, money, 1, 0, 1, "", false); // 11 total money
        xf(sb, "164", 1, 0, 1, "", false); // 12 total rate
        xf(sb, "165", 1, 0, 1, "<alignment horizontal=\"right\"/>", false); // 13 total date
        xf(sb, "0", 1, 0, 1, "", true); // 14 total text with the quote prefix
        xf(sb, "0", 3, 0, 0, "", false); // 15 title
        xf(sb, "0", 1, 0, 0, "<alignment vertical=\"top\"/>", false); // 16 label
        xf(sb, "0", 0, 0, 0, "<alignment vertical=\"top\" wrapText=\"1\"/>", false); // 17 wrapped text
        xf(sb, "0", 0, 0, 0, "<alignment vertical=\"top\" wrapText=\"1\"/>", true); // 18 wrapped text, quote prefix
        sb.append("</cellXfs>");
        sb.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>");
        return sb.append("</styleSheet>").toString();
    }

    private static void xf(
            StringBuilder sb, String numFmt, int font, int fill, int border, String align, boolean quote) {
        sb.append("<xf numFmtId=\"")
                .append(numFmt)
                .append("\" fontId=\"")
                .append(font)
                .append("\" fillId=\"")
                .append(fill)
                .append("\" borderId=\"")
                .append(border)
                .append("\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\"");
        if (!align.isEmpty()) {
            sb.append(" applyAlignment=\"1\"");
        }
        if (quote) {
            sb.append(" quotePrefix=\"1\"");
        }
        if (align.isEmpty()) {
            sb.append("/>");
        } else {
            sb.append('>').append(align).append("</xf>");
        }
    }

    // ---- sheets
    // ------------------------------------------------------------------------------------------------------

    private static void sheetStart(Writer w, boolean frozen, List<Integer> widths) throws IOException {
        w.write(XML);
        w.write("<worksheet xmlns=\"" + MAIN + "\"><sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>");
        w.write("<sheetViews><sheetView workbookViewId=\"0\"");
        w.write(frozen ? ">" : " showGridLines=\"0\">");
        if (frozen) {
            w.write("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>");
            w.write("<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/>");
        }
        w.write("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/><cols>");
        for (int i = 0; i < widths.size(); i++) {
            w.write("<col min=\"" + (i + 1) + "\" max=\"" + (i + 1) + "\" width=\"" + widths.get(i)
                    + "\" customWidth=\"1\"/>");
        }
        w.write("</cols><sheetData>");
    }

    private static void sheetEnd(Writer w, boolean landscape) throws IOException {
        w.write(
                "</sheetData><pageMargins left=\"0.5\" right=\"0.5\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" footer=\"0.3\"/>");
        w.write("<pageSetup paperSize=\"9\" orientation=\"" + (landscape ? "landscape" : "portrait")
                + "\" fitToWidth=\"1\" fitToHeight=\"0\"/>");
        w.write("<headerFooter><oddFooter>&amp;CPage &amp;P of &amp;N</oddFooter></headerFooter></worksheet>");
    }

    private static void parametersSheet(Writer w, Header h, Presentation p, int exponent) throws IOException {
        sheetStart(w, false, List.of(26, 80));
        int row = 1;
        row = textRow(w, row, S_TITLE, h.title());
        row++;
        row = labelled(w, row, "Report key", h.reportKey());
        w.write("<row r=\"" + row + "\"><c r=\"A" + row + "\" t=\"inlineStr\" s=\"" + S_LABEL
                + "\"><is><t>As at</t></is></c><c r=\"B" + row + "\" s=\"" + S_DATE + "\"><v>"
                + ReportFormats.excelSerial(h.asOf()) + "</v></c></row>");
        row++;
        row = labelled(
                w, row, "Data read at", ReportFormats.dateTime(h.snapshotAt(), p.zone()) + " (" + p.zone() + ")");
        row = labelled(
                w, row, "File made at", ReportFormats.dateTime(h.generatedAt(), p.zone()) + " (" + p.zone() + ")");
        row = labelled(w, row, "Made by", p.by());
        row = labelled(w, row, "Business", p.businessName());
        row = labelled(
                w,
                row,
                "Amounts",
                "In " + h.currency() + (exponent == 0 ? ", whole units" : ", with " + exponent + " decimal places"));
        for (Presentation.Line line : p.parameters()) {
            row = labelled(w, row, line.label(), line.value());
        }
        for (String note : h.notes()) {
            row = labelled(w, row, "Note", note);
        }
        sheetEnd(w, false);
    }

    private static int labelled(Writer w, int row, String label, String value) throws IOException {
        w.write("<row r=\"" + row + "\">");
        w.write("<c r=\"A" + row + "\" t=\"inlineStr\" s=\"" + S_LABEL + "\"><is><t xml:space=\"preserve\">"
                + escape(label) + "</t></is></c>");
        w.write(textCell("B", row, value, true));
        w.write("</row>");
        return row + 1;
    }

    private static int textRow(Writer w, int row, int style, String value) throws IOException {
        w.write("<row r=\"" + row + "\"><c r=\"A" + row + "\" t=\"inlineStr\" s=\"" + style
                + "\"><is><t xml:space=\"preserve\">" + escape(value) + "</t></is></c></row>");
        return row + 1;
    }

    private static void tableSheet(Writer w, TableMeta t, Header h, Spool spool, Guard guard) throws IOException {
        int exponent = ReportFormats.exponent(h.currency());
        List<Column> columns = t.columns();
        List<Integer> widths = new ArrayList<>();
        for (Column c : columns) {
            widths.add(width(c, h.currency()));
        }
        sheetStart(w, true, widths);
        w.write("<row r=\"1\" ht=\"20\" customHeight=\"1\">");
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            w.write("<c r=\"" + ref(i) + "1\" t=\"inlineStr\" s=\"" + (c.kind() == Kind.TEXT ? S_HEAD : S_HEAD_RIGHT)
                    + "\"><is><t xml:space=\"preserve\">" + escape(header(c, h.currency())) + "</t></is></c>");
        }
        w.write("</row>");
        long[] rowNo = {1};
        spool.forEachRow(t, guard, row -> {
            rowNo[0]++;
            boolean total = t.hasTotal() && rowNo[0] - 1 == t.rows();
            try {
                w.write("<row r=\"" + rowNo[0] + "\">");
                for (int i = 0; i < columns.size(); i++) {
                    w.write(cell(i, (int) rowNo[0], columns.get(i), row.get(i), total, exponent));
                }
                w.write("</row>");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        sheetEnd(w, columns.size() > 7);
    }

    /** The heading of a column: money names its currency, a rate its unit is the cell format. */
    static String header(Column c, String currency) {
        return c.kind() == Kind.MONEY ? c.label() + " (" + currency + ")" : c.label();
    }

    private static int width(Column c, String currency) {
        int base = switch (c.kind()) {
            case TEXT -> 30;
            case MONEY -> 20;
            case DATE -> 14;
            case BASIS_POINTS -> 12;
            case COUNT -> 12;
        };
        return Math.min(60, Math.max(base, header(c, currency).length() + 3));
    }

    private static String cell(int col, int row, Column c, Object v, boolean total, int exponent) {
        String at = ref(col) + row;
        if (v == null) {
            return "<c r=\"" + at + "\" s=\"" + style(c.kind(), total, false) + "\"/>";
        }
        return switch (c.kind()) {
            case TEXT -> textCell(ref(col), row, String.valueOf(v), false, total);
            case COUNT -> number(at, style(c.kind(), total, false), String.valueOf(((Number) v).longValue()));
            case MONEY ->
                number(at, style(c.kind(), total, false), ReportFormats.plainMoney(((Number) v).longValue(), exponent));
            case BASIS_POINTS ->
                number(at, style(c.kind(), total, false), ReportFormats.plainRate(((Number) v).longValue()));
            case DATE ->
                number(
                        at,
                        style(c.kind(), total, false),
                        String.valueOf(ReportFormats.excelSerial(LocalDate.parse(String.valueOf(v)))));
        };
    }

    private static String number(String at, int style, String value) {
        return "<c r=\"" + at + "\" s=\"" + style + "\"><v>" + value + "</v></c>";
    }

    private static int style(Kind kind, boolean total, boolean quoted) {
        int offset = switch (kind) {
            case TEXT -> quoted ? 5 : 0;
            case COUNT -> 1;
            case MONEY -> 2;
            case BASIS_POINTS -> 3;
            case DATE -> 4;
        };
        if (total) {
            return S_TOTAL + offset;
        }
        return switch (kind) {
            case TEXT -> quoted ? S_TEXT_Q : S_TEXT;
            case COUNT -> S_COUNT;
            case MONEY -> S_MONEY;
            case BASIS_POINTS -> S_RATE;
            case DATE -> S_DATE;
        };
    }

    /** True for text a spreadsheet would run as a formula (or as a command) if it were typed into a cell. */
    static boolean looksLikeFormula(String s) {
        return !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0;
    }

    private static String textCell(String col, int row, String value, boolean wrap) {
        return textCell(col, row, value, wrap, false);
    }

    /** A string cell: inline text, never a formula, with the quote prefix when it begins like one. */
    private static String textCell(String col, int row, String value, boolean wrap, boolean total) {
        // The text a spreadsheet will hold: control characters are gone before the test, so a leading control character
        // cannot hide a leading "=" from it.
        String text = strip(value);
        if (text.length() > MAX_CELL_CHARS) {
            int end = Character.isHighSurrogate(text.charAt(MAX_CELL_CHARS - 1)) ? MAX_CELL_CHARS - 1 : MAX_CELL_CHARS;
            text = text.substring(0, end);
        }
        boolean quoted = looksLikeFormula(text);
        int s = wrap ? (quoted ? S_WRAP_Q : S_WRAP) : style(Kind.TEXT, total, quoted);
        return "<c r=\"" + col + row + "\" t=\"inlineStr\" s=\"" + s + "\"><is><t xml:space=\"preserve\">"
                + escape(text) + "</t></is></c>";
    }

    /** The column letters of a zero based index: 0 is A, 25 is Z, 26 is AA. */
    static String ref(int index) {
        StringBuilder sb = new StringBuilder();
        for (int i = index; i >= 0; i = i / 26 - 1) {
            sb.insert(0, (char) ('A' + i % 26));
        }
        return sb.toString();
    }

    /**
     * Text made safe for an XML text node or attribute: the five markup characters escaped, characters XML 1.0 cannot hold
     * (control characters, unpaired surrogates, the two non characters) dropped, and a carriage return written as a
     * character reference so that it survives.
     */
    static String escape(String s) {
        return escapeStripped(strip(s));
    }

    /** The text with every character XML 1.0 cannot hold dropped (and nothing else changed). */
    static String strip(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\t'
                    || cp == '\n'
                    || cp == '\r'
                    || (cp >= 0x20 && cp <= 0xD7FF)
                    || (cp >= 0xE000 && cp <= 0xFFFD)
                    || (cp >= 0x10000 && cp <= 0x10FFFF)) {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    private static String escapeStripped(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '&') {
                sb.append("&amp;");
            } else if (cp == '<') {
                sb.append("&lt;");
            } else if (cp == '>') {
                sb.append("&gt;");
            } else if (cp == '"') {
                sb.append("&quot;");
            } else if (cp == '\'') {
                sb.append("&apos;");
            } else if (cp == '\r') {
                sb.append("&#13;");
            } else if (cp == '\t' || cp == '\n') {
                sb.append((char) cp);
            } else if ((cp >= 0x20 && cp <= 0xD7FF)
                    || (cp >= 0xE000 && cp <= 0xFFFD)
                    || (cp >= 0x10000 && cp <= 0x10FFFF)) {
                sb.appendCodePoint(cp);
            }
            // anything else is not valid in XML 1.0 and is not text
        }
        return sb.toString();
    }
}
