package com.rincoltech.bms.core.reporting.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Reads back a report PDF for the tests: its text by page (what the golden tests compare), its page size, and whether every
 * font in it is embedded. Test code, reading only files the tests made.
 */
final class PdfProbe {

    private final byte[] bytes;

    PdfProbe(byte[] bytes) {
        this.bytes = bytes;
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F') {
            throw new AssertionError("not a PDF");
        }
    }

    static PdfProbe of(Path file) {
        try {
            return new PdfProbe(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * With {@code -Dbms.documents.samples}: writes {@code bytes} to {@code target/document-samples/<name>} and, for a PDF, a
     * PNG of each page at 110 dpi next to it ({@code <name>-p1.png}), as the other document tests do.
     */
    static void sample(String name, byte[] bytes) {
        if (System.getProperty("bms.documents.samples") == null) {
            return;
        }
        try {
            Path dir = Path.of("target/document-samples");
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), bytes);
            if (name.endsWith(".pdf")) {
                String stem = name.substring(0, name.length() - 4);
                try (var old = Files.newDirectoryStream(dir, stem + "-p*.png")) {
                    for (Path previous : old) {
                        Files.delete(previous);
                    }
                }
                try (PDDocument d = Loader.loadPDF(bytes)) {
                    PDFRenderer r = new PDFRenderer(d);
                    for (int i = 0; i < d.getNumberOfPages(); i++) {
                        ImageIO.write(
                                r.renderImageWithDPI(i, 110),
                                "png",
                                dir.resolve(stem + "-p" + (i + 1) + ".png").toFile());
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The whole text with runs of whitespace as one space: stable across layout changes that keep the words. */
    String text() {
        try (PDDocument d = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(d).replaceAll("\\s+", " ").strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The text of each page, whitespace collapsed. */
    List<String> pages() {
        try (PDDocument d = Loader.loadPDF(bytes)) {
            List<String> out = new ArrayList<>();
            PDFTextStripper s = new PDFTextStripper();
            for (int p = 1; p <= d.getNumberOfPages(); p++) {
                s.setStartPage(p);
                s.setEndPage(p);
                out.add(s.getText(d).replaceAll("\\s+", " ").strip());
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int pageCount() {
        try (PDDocument d = Loader.loadPDF(bytes)) {
            return d.getNumberOfPages();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Width and height of a page in points (A4 is 595 by 842). */
    float[] pageSize(int index) {
        try (PDDocument d = Loader.loadPDF(bytes)) {
            PDPage page = d.getPage(index);
            return new float[] {
                page.getMediaBox().getWidth(), page.getMediaBox().getHeight()
            };
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True when the document has fonts and every one of them is embedded (none is left to the reader's own fonts). */
    boolean allFontsEmbedded() {
        try (PDDocument d = Loader.loadPDF(bytes)) {
            int fonts = 0;
            for (PDPage page : d.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    fonts++;
                    if (!font.isEmbedded()) {
                        return false;
                    }
                }
            }
            return fonts > 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
