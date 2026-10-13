package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * The credit note template of the optional documents (ADR-033, issue #197), rendered from a return (which numbers it) and with the
 * to the customer returns of issue #201 once they are on main. Until then nothing issues one, so this renders the template
 * with a fabricated model of the shape the returns follow-up will build, to prove the template reads, fits a page and
 * prints the amount credited in figures and words. Every name and amount is invented.
 */
class CreditNoteTemplateTest {

    private final PdfRenderer renderer = new PdfRenderer();

    static Map<String, Object> model() {
        Map<String, Object> lh = Map.of(
                "name", "Test Trading Ltd",
                "colour", "#1E5631",
                "onColour", "#FFFFFF",
                "tint", "#E3E9E5",
                "footer", "Test Trading Ltd, Plot 1 Test Road. Thank you for your business.");
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("lh", lh);
        m.put("branch", Map.of("name", "Head Office", "location", "Plot 1, Test Road, Kampala"));
        m.put("doc", Map.of("title", "CREDIT NOTE", "number", "CN-HQ-000001", "date", "8 Oct 2026"));
        m.put("currency", "UGX");
        m.put("buyer", Map.of("name", "Test Buyer 01", "contact", "+256700000001"));
        Map<String, Object> ref = new java.util.LinkedHashMap<>();
        ref.put("invoice", "INV-HQ-000001");
        ref.put("saleNo", "RS00000001");
        ref.put("date", "8 Oct 2026");
        ref.put("refund", "Cash");
        ref.put("note", "Two cartons arrived damaged");
        m.put("ref", ref);
        Map<String, Object> line = new java.util.LinkedHashMap<>();
        line.put("no", 1);
        line.put("code", "DC-1");
        line.put("description", "Test Product DC-1");
        line.put("qty", "2");
        line.put("unit", "carton");
        line.put("pack", "= 24 piece");
        line.put("reason", "Damaged");
        line.put("total", "2,200");
        m.put("lines", List.of(line));
        m.put("totals", Map.of("total", "2,200", "words", "Two thousand, two hundred Uganda shillings only"));
        m.put("notes", null);
        m.put("signs", Map.of("issuedBy", "Test Cashier", "issuedOn", "8 Oct 2026"));
        return m;
    }

    @Test
    void theCreditNoteTemplateRendersOnePageWithTheAmountInWords() throws Exception {
        byte[] pdf = renderer.render("credit_note", model());
        try (PDDocument d = Loader.loadPDF(pdf)) {
            assertThat(d.getNumberOfPages()).isEqualTo(1);
            String text = new PDFTextStripper().getText(d).replaceAll("\\s+", " ");
            assertThat(text)
                    .contains(
                            "CREDIT NOTE",
                            "CN-HQ-000001",
                            "Credited to",
                            "Test Buyer 01",
                            "Against invoice",
                            "INV-HQ-000001")
                    .contains("Two cartons arrived damaged", "2 carton", "= 24 piece", "Total credited (UGX)", "2,200")
                    .contains(
                            "Two thousand, two hundred Uganda shillings only",
                            "Received by Test Buyer 01",
                            "page 1 of 1");
            if (System.getProperty("bms.documents.samples") != null) {
                Path dir = Path.of("target/document-samples");
                Files.createDirectories(dir);
                Files.write(dir.resolve("retail_credit_note_template.pdf"), pdf);
                javax.imageio.ImageIO.write(
                        new PDFRenderer(d).renderImageWithDPI(0, 110),
                        "png",
                        dir.resolve("retail_credit_note_template-p1.png").toFile());
            }
        }
    }

    /** Markup characters print as typed, Greek and Cyrillic print, and a script the font lacks prints as "?" and breaks nothing. */
    @Test
    @SuppressWarnings("unchecked")
    void awkwardNamesAndReasonsPrintAsTypedAndNeverBreakTheDocument() throws Exception {
        String typed =
                "Am\u00e9lie & Sons <b>\"Ltd\"</b> \u03a9\u03bc\u03ad\u03b3\u03b1 \u041f\u0440\u0438\u0432\u0435\u0442";
        Map<String, Object> m = model();
        m.put("buyer", Map.of("name", typed + " \u65e5\u672c\u8a9e", "contact", "<script>x</script>"));
        Map<String, Object> ref = new java.util.LinkedHashMap<>((Map<String, Object>) m.get("ref"));
        ref.put("note", "Damaged & <wet> \"boxes\"");
        m.put("ref", ref);
        byte[] pdf = renderer.render("credit_note", m);
        try (PDDocument d = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(d).replaceAll("\\s+", " ");
            assertThat(text)
                    .contains(typed)
                    .contains("???")
                    .contains("<script>x</script>")
                    .contains("Damaged & <wet> \"boxes\"");
        }
    }
}
