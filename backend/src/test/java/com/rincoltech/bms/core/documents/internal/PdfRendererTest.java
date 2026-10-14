package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.kernel.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * ADR-034: the renderer loads only {@code data:} URIs (the tenant logo). A value that points anywhere
 * else, a URL on a machine of the attacker's choosing or a file on this one, is never fetched.
 */
class PdfRendererTest {

    private final PdfRenderer renderer = new PdfRenderer();

    static String dataUri() throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    static int images(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            PDResources res = d.getPage(0).getResources();
            int n = 0;
            for (COSName name : res.getXObjectNames()) {
                if (res.getXObject(name) instanceof PDImageXObject) {
                    n++;
                }
            }
            return n;
        }
    }

    static String text(byte[] pdf) throws Exception {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(d).replaceAll("\\s+", " ").strip();
        }
    }

    /** ADR-034: markup characters print as typed, control characters vanish, and a script the font lacks prints as "?". */
    @Test
    void awkwardTextIsEscapedCleanedAndNeverAnEmptyBox() throws Exception {
        String typed =
                "Zo\u00eb M\u00fcller & Sons <b>x</b> \"q\" 'a' \u03a9\u03bc\u03ad\u03b3\u03b1 \u041f\u0440\u0438\u0432\u0435\u0442";
        String awkward = typed
                + " \u0001\u0007\u001f\ufffe \u65e5\u672c\u8a9e \u0627\u0644\u0639\u0631\u0628\u064a\u0629 \ud83d\ude00 end";
        String text = text(renderer.render("probe", Map.of("text", awkward)));
        assertThat(text)
                .contains(typed)
                .endsWith("end")
                .doesNotContain("\u0001")
                .doesNotContain("<script");
        assertThat(text).contains("???");
        assertThat(renderer.printable("a\u0000b\tc\nd\u0085e")).isEqualTo("ab c de");
        assertThat(renderer.printable("\u65e5x")).isEqualTo("?x");
        // A combining sequence is normalised so the letter the font has is the one printed.
        assertThat(renderer.printable("e\u0301")).isEqualTo("\u00e9");
    }

    /** A burst of renders cannot take the heap: when no slot frees in time the answer is 503, and the slot comes back. */
    @Test
    void aFullRendererAnswersBusyInsteadOfRunningOutOfMemory() throws Exception {
        PdfRenderer small = new PdfRenderer(1, 50);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual()
                .start(() -> small.withSlot(() -> {
                    holding.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return 1;
                }));
        holding.await();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> small.render("probe", Map.of("text", "x")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.code()).isEqualTo("documents_busy");
                });
        release.countDown();
        holder.join();
        assertThat(small.render("probe", Map.of("text", "x")))
                .as("the slot was released")
                .isNotEmpty();
    }

    // JUSTIFICATION-A3: review cases of issue #217 (a name that looks like a data URI, the places reserved for
    // documents).
    /** Only the tenant's logo is a data: URI the application built; a value that merely begins with "data:" is cleaned. */
    @Test
    void aNameThatBeginsWithDataIsCleanedLikeAnyOtherText() throws Exception {
        String text = text(renderer.render("probe", Map.of("text", "data:\u0001\u0007x <b>y</b>")));
        assertThat(text).contains("data:x <b>y</b>").doesNotContain("\u0001");
    }

    /**
     * A report takes at most one of the two render places, builds its model only once it holds them, and a receipt or a
     * statement (the interactive {@code render}, which uses the same places) is never turned away by two reports at once.
     */
    @Test
    void aReportHoldsAtMostOneOfTwoPlacesBuildsItsModelLateAndLeavesOneForAnInteractiveRender() throws Exception {
        PdfRenderer two = new PdfRenderer(2, 300);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.nio.file.Path first = java.nio.file.Files.createTempFile("report-a", ".pdf");
        java.nio.file.Path second = java.nio.file.Files.createTempFile("report-b", ".pdf");
        AtomicInteger secondModels = new AtomicInteger();
        Thread running = Thread.ofVirtual()
                .start(() -> two.renderTo(
                        "probe",
                        () -> {
                            holding.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return Map.of("text", "first report");
                        },
                        first,
                        5_000));
        holding.await();
        // A second report waits for the one report place, and gives up with 503; its model was never built.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> two.renderTo(
                        "probe",
                        () -> {
                            secondModels.incrementAndGet();
                            return Map.of("text", "second report");
                        },
                        second,
                        200))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo("documents_busy"));
        assertThat(secondModels.get()).as("a report that waits holds no model").isZero();
        // An interactive render, with a report in the middle of its layout, finds the other place at once.
        assertThat(two.render("probe", Map.of("text", "receipt"))).isNotEmpty();
        release.countDown();
        running.join();
        assertThat(text(java.nio.file.Files.readAllBytes(first))).contains("first report");
        // Both places are free again.
        two.renderTo("probe", () -> Map.of("text", "second report"), second, 1_000);
        assertThat(text(java.nio.file.Files.readAllBytes(second))).contains("second report");
        java.nio.file.Files.deleteIfExists(first);
        java.nio.file.Files.deleteIfExists(second);
    }

    @Test
    void aDataUriImageIsDrawn() throws Exception {
        byte[] pdf = renderer.render("probe", Map.of("src", dataUri()));
        assertThat(images(pdf)).as("the logo is on the page").isPositive();
    }

    @Test
    void aUrlIsNeverFetched() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Thread listener = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        Socket s = server.accept();
                        connections.incrementAndGet();
                        s.close();
                    }
                } catch (java.io.IOException closed) {
                    // the server is closed at the end of the test
                }
            });
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/logo.png";
            byte[] pdf = renderer.render("probe", Map.of("src", url));
            Thread.sleep(300);
            assertThat(connections.get()).as("connections made by the renderer").isZero();
            assertThat(images(pdf)).as("nothing was drawn").isZero();
            listener.interrupt();
        }
    }

    @Test
    void aFileIsNeverRead() throws Exception {
        java.nio.file.Path secret = java.nio.file.Files.createTempFile("probe", ".png");
        try {
            java.nio.file.Files.write(
                    secret,
                    Base64.getDecoder().decode(dataUri().substring(dataUri().indexOf(',') + 1)));
            byte[] pdf = renderer.render("probe", Map.of("src", secret.toUri().toString()));
            assertThat(images(pdf)).as("a file: URI draws nothing").isZero();
        } finally {
            java.nio.file.Files.deleteIfExists(secret);
        }
    }
}
