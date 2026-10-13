package com.rincoltech.bms.core.documents.internal;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.rincoltech.bms.kernel.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * ADR-034: Thymeleaf in XML mode (strict XHTML, every value escaped) laid out by OpenHTMLtoPDF on
 * PDFBox, with the self-hosted Open Sans embedded in three weights. Templates live under
 * {@code templates/documents/} and share {@code layout.html} for the header and footer. The renderer
 * loads nothing from outside the document: an access rule, checked before any URI is resolved, allows
 * only {@code data:} URIs (the tenant logo), so no template value can reach a file or a URL.
 */
@Component
class PdfRenderer {

    private static final String FONTS = "/fonts/open-sans/OpenSans-";

    private final TemplateEngine engine;

    private final Semaphore slots;
    private final long waitMillis;
    private final CmapLookup glyphs;

    /**
     * @param maxConcurrent renders that may run at once: each holds a document in memory, and the
     *     smallest host has a heap of about 224 MB, so the default is two
     * @param waitMillis how long a request waits for a slot before it is answered 503 with Retry-After
     */
    @Autowired
    PdfRenderer(
            @Value("${bms.documents.max-concurrent-renders:2}") int maxConcurrent,
            @Value("${bms.documents.render-wait-ms:3000}") long waitMillis) {
        this.slots = new Semaphore(Math.max(1, maxConcurrent), true);
        this.waitMillis = waitMillis;
        this.glyphs = loadGlyphs();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/documents/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.XML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
    }

    PdfRenderer() {
        this(2, 3000);
    }

    String xhtml(String template, Map<String, Object> model) {
        return engine.process(template, new Context(null, clean(model)));
    }

    /**
     * Runs one render in a slot. When every slot stays taken for the wait, the answer is 503
     * {@code documents_busy} (the handler adds Retry-After): a burst of statements must not exhaust the
     * heap and take the whole service with it.
     */
    <T> T withSlot(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "documents_busy",
                    "Documents busy",
                    "Too many documents are being produced; try again shortly.");
        }
        try {
            return work.get();
        } finally {
            slots.release();
        }
    }

    byte[] render(String template, Map<String, Object> model) {
        return withSlot(() -> renderNow(template, model));
    }

    private byte[] renderNow(String template, Map<String, Object> model) {
        String html = xhtml(template, model);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withProducer("BMS Platform");
            builder.useExternalResourceAccessControl(
                    (uri, type) -> uri != null && uri.regionMatches(true, 0, "data:", 0, 5),
                    ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
            builder.withHtmlContent(html, "/");
            font(builder, "Regular", 400);
            font(builder, "SemiBold", 600);
            font(builder, "Bold", 700);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Every string of the model made safe to lay out: characters XML cannot hold (control characters)
     * are dropped, and a character Open Sans has no glyph for (Chinese, Japanese, Arabic, emoji) is
     * printed as "?" rather than as an empty box, so the document stays legible and the gap is visible.
     * {@code data:} URIs (the logo) are left as they are.
     */
    @SuppressWarnings("unchecked")
    private Object clean(Object value) {
        if (value instanceof String s) {
            return s.startsWith("data:") ? s : printable(s);
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), clean(v)));
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(v -> out.add(clean(v)));
            return out;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> clean(Map<String, Object> model) {
        return (Map<String, Object>) clean((Object) model);
    }

    String printable(String text) {
        String s = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (cp == '\t' || cp == '\n' || cp == '\r') {
                out.append(' ');
            } else if (cp < 0x20 || (cp >= 0x7F && cp < 0xA0) || cp == 0xFFFE || cp == 0xFFFF) {
                // control characters are not valid in XML 1.0 and are not text
            } else if (Character.isWhitespace(cp) || glyphs == null || glyphs.getGlyphId(cp) > 0) {
                out.appendCodePoint(cp);
            } else {
                out.append('?');
            }
        });
        return out.toString();
    }

    private static CmapLookup loadGlyphs() {
        try (var in = PdfRenderer.class.getResourceAsStream(FONTS + "Regular.ttf")) {
            TrueTypeFont ttf = new TTFParser().parse(new RandomAccessReadBuffer(in));
            return ttf.getUnicodeCmapLookup();
        } catch (IOException e) {
            // Without the table every character is passed through, as before.
            return null;
        }
    }

    private static void font(PdfRendererBuilder builder, String file, int weight) {
        builder.useFont(
                () -> PdfRenderer.class.getResourceAsStream(FONTS + file + ".ttf"),
                "Open Sans",
                weight,
                FontStyle.NORMAL,
                true);
    }
}
