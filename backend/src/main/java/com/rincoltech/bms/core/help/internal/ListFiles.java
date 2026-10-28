package com.rincoltech.bms.core.help.internal;

import com.rincoltech.bms.core.help.SetupDrafts.Upload;
import com.rincoltech.bms.kernel.ApiException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;

/**
 * What a file the owner chose becomes before anything is sent (ADR-102): a photo is decoded and written again as a JPEG
 * no larger than it needs, which drops its metadata (a phone's location among it); a PDF gives its text, or a picture of
 * its first page when it is a scan; an Excel workbook gives the cells of its first sheet; a CSV or text file is read as
 * UTF-8. The kind is read from the bytes, never from the name or the declared type. Anything else, or a file that cannot
 * be read, is 422 {@code setup_file_not_read} in plain words. Bounded throughout: a workbook may not unpack past a limit.
 */
final class ListFiles {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    /** The longest side of a picture that is sent: ample to read a stock book, small to send. */
    static final int MAX_SIDE = 2000;
    /**
     * The most pixels a photo may declare: a phone's usual 12 to 24 megapixel picture fits, and a progressive JPEG, whose
     * decoder holds the whole picture's coefficients in native memory whatever the subsampling, stays bounded.
     */
    static final long MAX_PIXELS = 40_000_000L;

    /** The most pixels a WebP photo may declare: at full size it stays near 64 MB in memory. */
    static final long MAX_WEBP_PIXELS = 16_000_000L;

    static final int MAX_PDF_PAGES = 10;
    static final long MAX_UNPACKED_BYTES = 20L * 1024 * 1024;
    static final int MAX_CELLS = 50_000;
    /** Excel's last column, XFD. */
    static final int MAX_COLUMNS = 16_384;
    /** The most text a sheet becomes, tabs included; far more than the helper sends (BMS_SETUP_AI_MAX_TEXT_CHARS). */
    static final int MAX_SHEET_CHARS = 2_000_000;

    /**
     * How many pictures the server decodes at once, whatever the number of drafts in flight: each may briefly hold tens of
     * megabytes, so a burst of photos waits its turn instead of running the heap out.
     */
    static final int DECODES_AT_ONCE = 2;

    private static final Semaphore DECODES = new Semaphore(DECODES_AT_ONCE, true);

    private static <T> T oneAtATime(Supplier<T> work) {
        try {
            if (!DECODES.tryAcquire(20, TimeUnit.SECONDS)) {
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "setup_busy",
                        "The app is busy",
                        "The app is reading other photos just now. Try again in a minute, or type the list.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw notRead("The photo could not be read just now. Try again.");
        }
        try {
            return work.get();
        } finally {
            DECODES.release();
        }
    }

    /** What is sent: text, or one picture as a data URL; {@code source} is {@code photo} or {@code file} for the log. */
    record Read(String text, String pictureDataUrl, String source) {}

    private ListFiles() {}

    /** How the list was given, from the first bytes only, so the limits are taken before anything is decoded. */
    static String source(Upload upload) {
        byte[] b = upload.bytes() == null ? new byte[0] : upload.bytes();
        return starts(b, 0xFF, 0xD8, 0xFF) || starts(b, 0x89, 'P', 'N', 'G') || webp(b) ? "photo" : "file";
    }

    static Read read(Upload upload) {
        byte[] b = upload.bytes();
        if (b == null || b.length == 0) {
            throw notRead("The file is empty. Choose it again.");
        }
        if (b.length > MAX_BYTES) {
            throw notRead("The file is bigger than 5 MB. Choose a smaller one, or take a photo of the page.");
        }
        if (starts(b, 0xFF, 0xD8, 0xFF) || starts(b, 0x89, 'P', 'N', 'G') || webp(b)) {
            return new Read(null, oneAtATime(() -> picture(decodeNow(b))), "photo");
        }
        if (starts(b, '%', 'P', 'D', 'F')) {
            return pdf(b);
        }
        if (starts(b, 'P', 'K', 3, 4)) {
            return new Read(workbook(b), null, "file");
        }
        if (starts(b, 0xD0, 0xCF, 0x11, 0xE0)) {
            throw notRead("This is an old Excel file. Save it as Excel Workbook (.xlsx) or CSV, then choose it again.");
        }
        return new Read(utf8(b), null, "file");
    }

    private static boolean starts(byte[] b, int... prefix) {
        if (b.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean webp(byte[] b) {
        return starts(b, 'R', 'I', 'F', 'F')
                && b.length > 12
                && b[8] == 'W'
                && b[9] == 'E'
                && b[10] == 'B'
                && b[11] == 'P';
    }

    /**
     * The photo decoded small: its size is read from the header first, a picture of more than {@link #MAX_PIXELS} pixels is
     * refused, and the decoder skips rows and columns so the picture in memory is about twice {@link #MAX_SIDE} on its
     * longest side, whatever the file declares. A crafted header cannot make the server decode gigabytes.
     */
    /** How many rows and columns the decoder steps over, rounded up: the picture in memory is at most twice MAX_SIDE long. */
    static int subsampling(long width, long height) {
        return (int) Math.max(1, Math.ceilDiv(Math.max(width, height), 2L * MAX_SIDE));
    }

    private static BufferedImage decodeNow(byte[] b) {
        String unread = "This photo cannot be read. Take it again, or choose a JPEG or PNG.";
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(b))) {
            Iterator<ImageReader> readers = in == null ? Collections.emptyIterator() : ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw notRead(unread);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                // WebP's reader may decode at full size whatever the subsampling, so it is held to a smaller picture.
                long cap = "webp".equalsIgnoreCase(reader.getFormatName()) ? MAX_WEBP_PIXELS : MAX_PIXELS;
                if (width < 1 || height < 1 || width * height > cap) {
                    throw notRead(
                            "This photo is too big to read. Take it again with the camera, or choose a smaller one.");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int step = subsampling(width, height);
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw notRead(unread);
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException a) {
                throw a;
            }
            throw notRead(unread);
        }
    }

    /** Drawn again at most {@link #MAX_SIDE} on its longest side, on white, as a JPEG: no metadata survives. */
    static String picture(BufferedImage image) {
        int longest = Math.max(image.getWidth(), image.getHeight());
        double scale = longest > MAX_SIDE ? (double) MAX_SIDE / longest : 1.0;
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(image, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(out, "jpeg", bytes)) {
                throw new IllegalStateException("the JDK writes JPEG");
            }
        } catch (IOException e) {
            throw notRead("This photo cannot be read. Take it again, or choose a JPEG or PNG.");
        }
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static Read pdf(byte[] b) {
        try (PDDocument doc = Loader.loadPDF(b)) {
            if (doc.isEncrypted()) {
                throw notRead("This PDF is locked with a password. Save it without one, or take a photo of the page.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(doc.getNumberOfPages(), MAX_PDF_PAGES));
            String text = stripper.getText(doc);
            if (text != null && !text.isBlank()) {
                return new Read(text.strip(), null, "file");
            }
            // A scan holds no text: its first page is read as a photo.
            if (doc.getNumberOfPages() == 0) {
                throw notRead("This PDF has no pages.");
            }
            // At 150 dots an inch, but never more than MAX_SIDE on the longest side, whatever size the page declares.
            PDRectangle box = doc.getPage(0).getCropBox();
            float longest = Math.max(1f, Math.max(box.getWidth(), box.getHeight()));
            float scale = Math.min(150f / 72f, MAX_SIDE / longest);
            // Drawn and written as JPEG inside one turn, so no more than DECODES_AT_ONCE pages are held at any time.
            String picture = oneAtATime(() -> {
                try {
                    return picture(new PDFRenderer(doc).renderImage(0, scale));
                } catch (IOException e) {
                    throw notRead("This PDF cannot be read. Take a photo of the page instead.");
                }
            });
            return new Read(null, picture, "file");
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException a) {
                throw a;
            }
            throw notRead("This PDF cannot be read. Take a photo of the page instead.");
        }
    }

    /** The cells of the first sheet of an Excel workbook, a row per line and a tab between cells. */
    static String workbook(byte[] b) {
        Map<String, byte[]> parts = new HashMap<>();
        long unpacked = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(b))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 2000) {
                    throw notRead("This workbook has too many parts. Save the sheet as CSV and choose that.");
                }
                String name = entry.getName();
                boolean wanted = name.equals("xl/sharedStrings.xml") || name.matches("xl/worksheets/sheet\\d+\\.xml");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = zip.read(buffer)) > 0) {
                    unpacked += n;
                    if (unpacked > MAX_UNPACKED_BYTES) {
                        throw notRead("This workbook is too big. Save the sheet as CSV and choose that.");
                    }
                    if (wanted) {
                        out.write(buffer, 0, n);
                    }
                }
                if (wanted) {
                    parts.put(name, out.toByteArray());
                }
            }
        } catch (IOException e) {
            throw notRead("This file cannot be read. Save it as Excel Workbook (.xlsx) or CSV, then choose it again.");
        }
        String first = parts.keySet().stream()
                .filter(n -> n.startsWith("xl/worksheets/"))
                .min((x, y) -> Integer.compare(sheetNo(x), sheetNo(y)))
                .orElseThrow(() -> notRead("This file is not an Excel workbook. Save it as .xlsx or CSV."));
        try {
            List<String> shared = parts.containsKey("xl/sharedStrings.xml")
                    ? sharedStrings(parts.get("xl/sharedStrings.xml"))
                    : List.of();
            return sheet(parts.get(first), shared);
        } catch (XMLStreamException | RuntimeException e) {
            if (e instanceof ApiException a) {
                throw a;
            }
            throw notRead("This workbook cannot be read. Save the sheet as CSV and choose that.");
        }
    }

    /** The number of a sheet part; a number too long to be one sorts last, so a crafted name is never a 500. */
    private static int sheetNo(String name) {
        return parseInt(name.replaceAll("\\D", ""), Integer.MAX_VALUE);
    }

    private static XMLStreamReader xml(byte[] part) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        InputStream in = new ByteArrayInputStream(part);
        return f.createXMLStreamReader(in);
    }

    private static List<String> sharedStrings(byte[] part) throws XMLStreamException {
        List<String> out = new ArrayList<>();
        XMLStreamReader r = xml(part);
        StringBuilder current = null;
        boolean inText = false;
        while (r.hasNext()) {
            int e = r.next();
            if (e == XMLStreamReader.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "si" -> current = new StringBuilder();
                    case "t" -> inText = true;
                    default -> {}
                }
            } else if (e == XMLStreamReader.CHARACTERS && inText && current != null) {
                current.append(r.getText());
            } else if (e == XMLStreamReader.END_ELEMENT) {
                if (r.getLocalName().equals("t")) {
                    inText = false;
                } else if (r.getLocalName().equals("si") && current != null) {
                    out.add(current.toString());
                    current = null;
                    if (out.size() > MAX_CELLS) {
                        throw notRead("This workbook is too big. Save the sheet as CSV and choose that.");
                    }
                }
            }
        }
        return out;
    }

    private static String sheet(byte[] part, List<String> shared) throws XMLStreamException {
        TreeMap<Integer, TreeMap<Integer, String>> rows = new TreeMap<>();
        XMLStreamReader r = xml(part);
        int cells = 0;
        String ref = null;
        String type = null;
        StringBuilder value = null;
        boolean inValue = false;
        int rowNo = 0;
        while (r.hasNext()) {
            int e = r.next();
            if (e == XMLStreamReader.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> rowNo = parseInt(r.getAttributeValue(null, "r"), rowNo + 1);
                    case "c" -> {
                        ref = r.getAttributeValue(null, "r");
                        type = r.getAttributeValue(null, "t");
                        value = new StringBuilder();
                        if (++cells > MAX_CELLS) {
                            throw notRead("This sheet is too big. Split it into smaller files.");
                        }
                    }
                    case "v", "t" -> inValue = value != null;
                    default -> {}
                }
            } else if (e == XMLStreamReader.CHARACTERS && inValue) {
                value.append(r.getText());
            } else if (e == XMLStreamReader.END_ELEMENT) {
                switch (r.getLocalName()) {
                    case "v", "t" -> inValue = false;
                    case "c" -> {
                        String text = value.toString();
                        if ("s".equals(type)) {
                            int i = parseInt(text.strip(), -1);
                            text = i >= 0 && i < shared.size() ? shared.get(i) : "";
                        }
                        text = text.replaceAll("[\\t\\r\\n]+", " ").strip();
                        if (!text.isEmpty()) {
                            int col = column(ref);
                            if (col < 0) {
                                throw notRead("This sheet has a cell past the last column Excel allows. Open it, save"
                                        + " it again, and choose it again, or save it as CSV.");
                            }
                            rows.computeIfAbsent(rowNo, k -> new TreeMap<>()).put(col, text);
                        }
                        value = null;
                    }
                    default -> {}
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (TreeMap<Integer, String> row : rows.values()) {
            int last = row.lastKey();
            for (int c = 0; c <= last; c++) {
                if (c > 0) {
                    out.append('\t');
                }
                out.append(row.getOrDefault(c, ""));
                if (out.length() > MAX_SHEET_CHARS) {
                    throw notRead("This sheet is too big. Split it into smaller files.");
                }
            }
            out.append('\n');
        }
        return out.toString().strip();
    }

    /** The column index of a cell reference such as {@code C12}; 0 when it has none. */
    static int column(String ref) {
        if (ref == null) {
            return 0;
        }
        int col = 0;
        for (char ch : ref.toCharArray()) {
            if (ch < 'A' || ch > 'Z') {
                break;
            }
            col = col * 26 + (ch - 'A' + 1);
            if (col > MAX_COLUMNS) {
                // Past Excel's last column (XFD): a crafted reference, whose empty cells before it would be millions;
                // refused.
                return -1;
            }
        }
        return Math.max(0, col - 1);
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String utf8(byte[] b) {
        try {
            String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b))
                    .toString();
            if (text.indexOf('\0') >= 0) {
                throw notRead("This kind of file cannot be read. Choose a photo, a CSV, an Excel workbook or a PDF.");
            }
            return text.replace("﻿", "").strip();
        } catch (CharacterCodingException e) {
            throw notRead("This kind of file cannot be read. Choose a photo, a CSV, an Excel workbook or a PDF.");
        }
    }

    static ApiException notRead(String message) {
        return new ApiException(
                HttpStatus.UNPROCESSABLE_CONTENT, "setup_file_not_read", "The file cannot be read", message);
    }
}
