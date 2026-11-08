package com.rincoltech.bms.core.imports.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Fabricated source files for the upload tests (issue #128): CSV and JSON Lines as text, and XLSX workbooks written by
 * hand as a spreadsheet program writes them (shared strings, a styles part with date formats, the sheet before the shared
 * strings in the zip, so the reader's two passes are exercised). No real person, phone or business is in any of them.
 */
public final class TestFiles {

    private TestFiles() {}

    static byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** The generated rows of the big file test: {@code rows} fabricated items of {@code columns} cells. */
    static Iterator<List<Object>> generated(int rows, int columns) {
        return new Iterator<>() {
            int n;

            @Override
            public boolean hasNext() {
                return n < rows;
            }

            @Override
            public List<Object> next() {
                n++;
                List<Object> row = new ArrayList<>(columns);
                row.add("ITEM-" + n);
                row.add("Fabricated item number " + n + " in a long description");
                for (int c = 2; c < columns; c++) {
                    row.add(c % 3 == 0 ? (Object) (n * 7L + c) : "cell " + c + " of row " + n);
                }
                return row;
            }
        };
    }

    static List<String> header(int columns) {
        List<String> h = new ArrayList<>(columns);
        h.add("Code");
        h.add("Description");
        for (int c = 2; c < columns; c++) {
            h.add("Field " + (c + 1));
        }
        return h;
    }

    /** Writes a CSV of the header and rows, quoting every text cell. */
    static void writeCsv(Path file, List<String> header, Iterator<List<Object>> rows) {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write(String.join(",", header.stream().map(TestFiles::quote).toList()));
            w.write("\r\n");
            while (rows.hasNext()) {
                List<Object> row = rows.next();
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) {
                        w.write(',');
                    }
                    Object v = row.get(i);
                    w.write(v instanceof String s ? quote(s) : String.valueOf(v));
                }
                w.write("\r\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /** An XLSX workbook in memory with one sheet, {@code Items}, after a hidden sheet {@code Notes}. */
    /** Public since #136: the lending template's golden test builds its workbook here too. */
    public static byte[] xlsx(List<String> header, List<List<Object>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeXlsx(out, header, rows.iterator(), true);
        return out.toByteArray();
    }

    static void writeXlsx(Path file, List<String> header, Iterator<List<Object>> rows) {
        try (OutputStream out = Files.newOutputStream(file)) {
            writeXlsx(out, header, rows, false);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Cells: a String is a shared string, a Number a number, a LocalDate a date serial in the style {@code dd/mm/yyyy}, a
     * Boolean a boolean, {@code null} no cell.
     */
    static void writeXlsx(OutputStream target, List<String> header, Iterator<List<Object>> rows, boolean hiddenNotes) {
        Map<String, Integer> shared = new LinkedHashMap<>();
        try (ZipOutputStream zip = new ZipOutputStream(target)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"></Types>""");
            put(
                    zip,
                    "xl/workbook.xml",
                    """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                              xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <workbookPr/><sheets>
                    """ + (hiddenNotes ? "<sheet name=\"Notes\" sheetId=\"2\" state=\"hidden\" r:id=\"rId2\"/>" : "")
                            + "<sheet name=\"Items\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml"/>
                    <Relationship Id="rId2" Type="worksheet" Target="worksheets/sheet2.xml"/>
                    <Relationship Id="rId3" Type="sharedStrings" Target="sharedStrings.xml"/>
                    </Relationships>""");
            put(zip, "xl/worksheets/sheet2.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                    <row r="1"><c r="A1" t="inlineStr"><is><t>hidden notes</t></is></c></row>
                    </sheetData></worksheet>""");
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            Writer w = new java.io.OutputStreamWriter(zip, StandardCharsets.UTF_8);
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            writeRow(w, 1, new ArrayList<>(header), shared);
            int r = 2;
            while (rows.hasNext()) {
                writeRow(w, r++, rows.next(), shared);
            }
            w.write("</sheetData></worksheet>");
            w.flush();
            zip.closeEntry();
            put(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <numFmts count="1"><numFmt numFmtId="164" formatCode="dd/mm/yyyy"/></numFmts>
                    <cellXfs count="4"><xf numFmtId="0"/><xf numFmtId="164"/><xf numFmtId="22"/><xf numFmtId="3"/></cellXfs>
                    </styleSheet>""");
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            w = new java.io.OutputStreamWriter(zip, StandardCharsets.UTF_8);
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
            for (String s : shared.keySet()) {
                w.write("<si><t xml:space=\"preserve\">" + escape(s) + "</t></si>");
            }
            w.write("</sst>");
            w.flush();
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeRow(Writer w, int r, List<Object> cells, Map<String, Integer> shared) throws IOException {
        w.write("<row r=\"" + r + "\">");
        for (int i = 0; i < cells.size(); i++) {
            Object v = cells.get(i);
            if (v == null) {
                continue;
            }
            String ref = column(i + 1) + r;
            switch (v) {
                case String s -> {
                    int idx = shared.computeIfAbsent(s, k -> shared.size());
                    w.write("<c r=\"" + ref + "\" t=\"s\"><v>" + idx + "</v></c>");
                }
                case Boolean b -> w.write("<c r=\"" + ref + "\" t=\"b\"><v>" + (b ? 1 : 0) + "</v></c>");
                // Excel's 1900 system: serial 1 is 1 January 1900, and it counts a 29 February 1900 that never was.
                case LocalDate d ->
                    w.write("<c r=\"" + ref + "\" s=\"1\"><v>"
                            + ChronoUnit.DAYS.between(
                                    d.isBefore(LocalDate.of(1900, 3, 1))
                                            ? LocalDate.of(1899, 12, 31)
                                            : LocalDate.of(1899, 12, 30),
                                    d)
                            + "</v></c>");
                default -> w.write("<c r=\"" + ref + "\"><v>" + v + "</v></c>");
            }
        }
        w.write("</row>");
    }

    static String column(int n) {
        StringBuilder s = new StringBuilder();
        while (n > 0) {
            int rem = (n - 1) % 26;
            s.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return s.toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
