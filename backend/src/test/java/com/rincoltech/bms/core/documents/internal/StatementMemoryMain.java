package com.rincoltech.bms.core.documents.internal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Run by {@link StatementMemoryTest} in a child JVM with a 224 MB heap, the smallest host's: renders
 * the real statement template with a model of the same shape as {@code LoanDocuments} builds, five
 * hundred lines long, several times in a row, and prints the page count.
 */
public final class StatementMemoryMain {

    private StatementMemoryMain() {}

    static Map<String, Object> model(int lines) throws Exception {
        BufferedImage logo = new BufferedImage(256, 128, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(logo, "png", png);
        Map<String, Object> lh = new LinkedHashMap<>();
        lh.put("name", "Test Lending Ltd");
        lh.put("colour", "#1E5631");
        lh.put("onColour", "#FFFFFF");
        lh.put("tint", "#E3E9E5");
        lh.put("footer", "Test Lending Ltd, Plot 1 Test Road. Keep this document safe.");
        lh.put("logo", "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray()));
        Map<String, Object> branch = Map.of("name", "Head Office", "location", "Plot 1, Test Road, Kampala");
        Map<String, Object> member = Map.of("name", "Test Borrower 01", "number", "M000001");
        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("number", "LN000001");
        loan.put("product", "TEST");
        loan.put("currency", "UGX");
        loan.put("status", "active");
        loan.put("terms", "3 months, flat interest at 10.00% per month, monthly instalments");
        loan.put("disbursed", "7 Oct 2026");
        Map<String, Object> doc =
                Map.of("title", "STATEMENT", "number", "ST-HQ-LN000001-20261013", "date", "13 Oct 2026");
        List<Map<String, Object>> txns = new ArrayList<>();
        long balance = 1_560_000;
        for (int i = 0; i < lines; i++) {
            balance -= 1_000;
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("date", "12 Oct 2026");
            line.put("number", "RC-HQ-" + String.format("%06d", i + 2));
            line.put("description", "Repayment");
            line.put("note", false);
            line.put("charged", "-");
            line.put("credited", "1,000");
            line.put("balance", String.format("%,d", balance));
            txns.add(line);
        }
        Map<String, Object> totals = Map.of(
                "charged",
                "1,560,000",
                "credited",
                String.format("%,d", lines * 1_000L),
                "closing",
                String.format("%,d", balance),
                "closingWords",
                "One million uganda shillings only");
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("principal", "1,000,000");
        position.put("interest", "0");
        position.put("fees", "0");
        position.put("penalties", null);
        position.put("credit", null);
        position.put("dpd", 0);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("lh", lh);
        model.put("branch", branch);
        model.put("member", member);
        model.put("loan", loan);
        model.put("doc", doc);
        model.put("txns", txns);
        model.put("totals", totals);
        model.put("position", position);
        return model;
    }

    /** The customer statement of the optional retail documents (issue #197), at the cap the API allows (800 lines). */
    static Map<String, Object> customerModel(int lines) throws Exception {
        Map<String, Object> model = model(0);
        model.remove("member");
        model.remove("loan");
        model.remove("txns");
        model.remove("totals");
        model.remove("position");
        model.put(
                "doc",
                Map.of("title", "STATEMENT OF ACCOUNT", "number", "ST-20261013-0F07DE94", "date", "13 Oct 2026"));
        model.put("currency", "UGX");
        model.put("buyer", Map.of("name", "Test Buyer 01", "contact", "+256700000001"));
        model.put("period", Map.of("from", "1 Jan 2026", "to", "13 Oct 2026"));
        model.put("scope", "All branches");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", "12 Oct 2026");
            row.put("reference", "RS" + String.format("%08d", i + 1));
            row.put("description", "Sale on credit");
            row.put("charged", "1,000");
            row.put("paid", "-");
            row.put("balance", String.format("%,d", (i + 1) * 1_000L));
            rows.add(row);
        }
        model.put("lines", rows);
        model.put(
                "totals",
                Map.of(
                        "opening", "0",
                        "charged", String.format("%,d", lines * 1_000L),
                        "paid", "0",
                        "closing", String.format("%,d", lines * 1_000L),
                        "closingWords", "Eight hundred thousand Uganda shillings only"));
        model.put("imported", null);
        return model;
    }

    public static void main(String[] args) throws Exception {
        PdfRenderer renderer = new PdfRenderer();
        int pages = 0;
        boolean customer = args.length > 0 && args[0].equals("customer_statement");
        for (int round = 0; round < 3; round++) {
            byte[] pdf = customer
                    ? renderer.render("customer_statement", customerModel(800))
                    : renderer.render("loan_statement", model(500));
            try (PDDocument d = Loader.loadPDF(pdf)) {
                pages = d.getNumberOfPages();
            }
        }
        System.out.println(
                "pages=" + pages + " maxHeapMb=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
    }
}
