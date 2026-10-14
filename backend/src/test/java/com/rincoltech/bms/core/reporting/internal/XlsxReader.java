package com.rincoltech.bms.core.reporting.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads back a workbook written by {@link XlsxWriter} with the JDK's XML parser only, so a test asserts what a spreadsheet
 * would see: each part is well formed XML, each sheet's cells have a type, a style and a value, and a style says whether it
 * carries the quote prefix, a number format or bold. Test code; it reads only workbooks the tests made.
 */
final class XlsxReader {

    /** One cell: {@code t} is {@code inlineStr} for text and empty for a number; {@code style} indexes {@code cellXfs}. */
    record Cell(String ref, String t, int style, String value) {

        boolean isText() {
            return t.equals("inlineStr");
        }
    }

    /** One sheet: its rows of cells by reference, and its frozen pane state. */
    record Sheet(String name, List<Map<String, Cell>> rows, boolean frozenHeader, String orientation) {

        Cell cell(String ref) {
            for (Map<String, Cell> r : rows) {
                if (r.containsKey(ref)) {
                    return r.get(ref);
                }
            }
            return null;
        }

        String text(String ref) {
            Cell c = cell(ref);
            return c == null ? null : c.value();
        }
    }

    /** Style facts by {@code cellXfs} index. */
    record Style(String numFmt, boolean quotePrefix, boolean bold, boolean topBorder) {}

    final Map<String, byte[]> parts = new LinkedHashMap<>();
    final List<Sheet> sheets = new ArrayList<>();
    final List<Style> styles = new ArrayList<>();
    final Map<String, String> numFmts = new LinkedHashMap<>();

    static XlsxReader read(Path file) {
        try {
            return new XlsxReader(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static XlsxReader read(byte[] bytes) {
        return new XlsxReader(bytes);
    }

    private XlsxReader(byte[] bytes) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                parts.put(e.getName(), zip.readAllBytes());
            }
            Document workbook = xml("xl/workbook.xml");
            NodeList names = workbook.getElementsByTagName("sheet");
            readStyles(xml("xl/styles.xml"));
            for (int i = 0; i < names.getLength(); i++) {
                Element s = (Element) names.item(i);
                sheets.add(sheet(s.getAttribute("name"), xml("xl/worksheets/sheet" + (i + 1) + ".xml")));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every part of the package parses as XML; a part that does not throws here. */
    Document xml(String part) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder b = f.newDocumentBuilder();
            byte[] bytes = parts.get(part);
            if (bytes == null) {
                throw new IllegalStateException("no part " + part + " in " + parts.keySet());
            }
            return b.parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("part " + part + " is not well formed: " + e.getMessage(), e);
        }
    }

    Sheet sheet(String name) {
        return sheets.stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no sheet " + name + " in "
                        + sheets.stream().map(Sheet::name).toList()));
    }

    private void readStyles(Document doc) {
        NodeList fmts = doc.getElementsByTagName("numFmt");
        for (int i = 0; i < fmts.getLength(); i++) {
            Element e = (Element) fmts.item(i);
            numFmts.put(e.getAttribute("numFmtId"), e.getAttribute("formatCode"));
        }
        List<Boolean> bold = new ArrayList<>();
        NodeList fonts = ((Element) doc.getElementsByTagName("fonts").item(0)).getElementsByTagName("font");
        for (int i = 0; i < fonts.getLength(); i++) {
            bold.add(((Element) fonts.item(i)).getElementsByTagName("b").getLength() > 0);
        }
        NodeList xfs = ((Element) doc.getElementsByTagName("cellXfs").item(0)).getElementsByTagName("xf");
        for (int i = 0; i < xfs.getLength(); i++) {
            Element x = (Element) xfs.item(i);
            styles.add(new Style(
                    x.getAttribute("numFmtId"),
                    "1".equals(x.getAttribute("quotePrefix")),
                    bold.get(Integer.parseInt(x.getAttribute("fontId"))),
                    !"0".equals(x.getAttribute("borderId"))));
        }
    }

    private static Sheet sheet(String name, Document doc) {
        List<Map<String, Cell>> rows = new ArrayList<>();
        NodeList rowNodes = doc.getElementsByTagName("row");
        for (int i = 0; i < rowNodes.getLength(); i++) {
            Map<String, Cell> cells = new LinkedHashMap<>();
            NodeList cs = ((Element) rowNodes.item(i)).getElementsByTagName("c");
            for (int j = 0; j < cs.getLength(); j++) {
                Element c = (Element) cs.item(j);
                String t = c.getAttribute("t");
                String value = null;
                if (t.equals("inlineStr")) {
                    value = ((Element) c.getElementsByTagName("t").item(0)).getTextContent();
                } else if (c.getElementsByTagName("v").getLength() > 0) {
                    value = c.getElementsByTagName("v").item(0).getTextContent();
                }
                cells.put(
                        c.getAttribute("r"),
                        new Cell(c.getAttribute("r"), t, Integer.parseInt(c.getAttribute("s")), value));
            }
            rows.add(cells);
        }
        boolean frozen = false;
        NodeList panes = doc.getElementsByTagName("pane");
        if (panes.getLength() > 0) {
            Node n = panes.item(0);
            frozen = "frozen".equals(((Element) n).getAttribute("state"));
        }
        NodeList setup = doc.getElementsByTagName("pageSetup");
        String orientation = setup.getLength() == 0 ? "" : ((Element) setup.item(0)).getAttribute("orientation");
        return new Sheet(name, rows, frozen, orientation);
    }
}
