package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

/**
 * Optional documents (documents capability; ADR-033, issue #197; docs/specs/trade-capabilities.md sections 6 and 13):
 * the invoice, delivery note and quotation with their numbered series per branch, stored PDFs, the customer
 * statement PDF, and "capability off equals today". Every name and amount is fabricated. The clock is pinned at 22:30
 * UTC, which is already 01:30 the next day in Africa/Kampala, so every test runs at the edge where the UTC date and
 * the business date differ and none depends on the day it runs.
 */
@Import(RetailDocumentsIT.FixedClock.class)
class RetailDocumentsIT extends IntegrationTest {

    /** 2026-10-08 01:30 in Kampala, 2026-10-07 22:30 in UTC. */
    static final Instant NOW = Instant.parse("2026-10-07T22:30:00Z");

    static final String TODAY = "2026-10-08";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    static final String SETTINGS = "core.settings.manage";
    /** The administrator column after migration V46: the document permission is the administrator's only. */
    static final String DOCS = ADMIN + ",retail.document.issue,retail.credit.manage,retail.credit.override";

    @Autowired
    TestRestTemplate http;

    /** Spied so that a render can be made to fail; every other call runs the real documents module. */
    @MockitoSpyBean
    Documents documents;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    UUID hq;
    UUID br2;
    /** Cost 30, sell 100 per piece. */
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-docs", false, true);
        api = new RetailTestSupport(http, t);
        hq = t.headOffice();
        br2 = t.secondBranch();
        product = api.product("DC-1", 30, 100);
        api.stockUp(hq, product, "100000");
        api.stockUp(br2, product, "100000");
        capability("documents", true);
        namedStaff();
    }

    // ---- helpers ---------------------------------------------------------------------------

    // JUSTIFICATION-A3: new test fixture in a new test class; a golden text needs a fixed name, user, address and logo.
    /** A named user, a branch address, and a fixed name, footer, brand colour and logo, so the golden text is stable. */
    void namedStaff() {
        TestDatabase.owner()
                .sql("INSERT INTO users (id, tenant_id, kind, full_name, email, status)"
                        + " VALUES (?, ?, 'staff', 'Test Cashier', 'test.cashier@example.test', 'active')"
                        + " ON CONFLICT DO NOTHING")
                .params(api.userId(), t.tenantId())
                .update();
        TestDatabase.owner()
                .sql("UPDATE branches SET location = 'Plot 1, Test Road, Kampala' WHERE id = ?")
                .param(hq)
                .update();
        HttpHeaders h = api.headers(SETTINGS + ",core.settings.read", "*", Map.of());
        int version = http.exchange(
                        "/api/v1/settings",
                        HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(h),
                        JsonNode.class)
                .getBody()
                .get("version")
                .asInt();
        h.add("If-Match", "\"" + version + "\"");
        assertThat(http.exchange(
                                "/api/v1/settings",
                                HttpMethod.PATCH,
                                new org.springframework.http.HttpEntity<>(
                                        Map.of(
                                                "display_name", "Test Trading Ltd",
                                                "receipt_footer",
                                                        "Test Trading Ltd, Plot 1 Test Road. Thank you for your business.",
                                                "theme_primary", "#1E5631"),
                                        h),
                                JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        org.springframework.util.LinkedMultiValueMap<String, Object> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", new org.springframework.core.io.ByteArrayResource(logoPng()) {
            @Override
            public String getFilename() {
                return "logo.png";
            }
        });
        HttpHeaders upload = api.headers(SETTINGS + ",core.settings.read", "*", Map.of());
        upload.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        assertThat(http.exchange(
                                "/api/v1/settings/logo",
                                HttpMethod.PUT,
                                new org.springframework.http.HttpEntity<>(form, upload),
                                JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** A fabricated logo: a green square with a white bar, 256 by 128 pixels. */
    static byte[] logoPng() {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(256, 128, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(0x1E, 0x56, 0x31));
        g.fillRect(0, 0, 256, 128);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(32, 52, 192, 24);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            javax.imageio.ImageIO.write(img, "png", out);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    void capability(String key, boolean on) {
        String etag = api.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = api.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of(key, on),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    static String code(ResponseEntity<JsonNode> r) {
        return r.getBody().get("code").asString();
    }

    UUID sale(UUID branch, String method, String qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", method);
        if (method.equals("credit")) {
            body.put("buyer_name", "Test Buyer 01");
        }
        body.put("lines", List.of(Map.of("product_id", product, "qty", qty)));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, DOCS, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    ResponseEntity<JsonNode> issue(UUID sale, String type, String perms, String branches, String key) {
        return api.postKeyed("/sales/" + sale + "/documents", Map.of("type", type), perms, branches, key);
    }

    JsonNode issued(UUID sale, String type) {
        ResponseEntity<JsonNode> r =
                issue(sale, type, DOCS, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isTrue();
        return r.getBody();
    }

    /** The stored PDF of a document, downloaded through the signed URL as the staff user would. */
    byte[] pdf(JsonNode document) {
        return pdf(document, DOCS);
    }

    byte[] pdf(JsonNode document, String perms) {
        assertThat(document.has("pdf_document_id")).as("%s", document).isTrue();
        ResponseEntity<JsonNode> url = http.exchange(
                "/api/v1/documents/" + document.get("pdf_document_id").asString() + "/download-url",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(api.headers(perms, "*", Map.of())),
                JsonNode.class);
        assertThat(url.getStatusCode()).as("%s", url.getBody()).isEqualTo(HttpStatus.OK);
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        return http.exchange(
                        java.net.URI.create(
                                http.getRootUri() + url.getBody().get("url").asString()),
                        HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(h),
                        byte[].class)
                .getBody();
    }

    /** The text of a PDF with every printed date replaced, so a golden file does not move with the calendar. */
    static String text(byte[] pdf) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String raw = new org.apache.pdfbox.text.PDFTextStripper().getText(d);
            return raw.replaceAll("\\b\\d{1,2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) \\d{4}\\b", "{DATE}")
                    .replaceAll("ST-\\d{8}-[0-9A-F]{8}", "ST-{YYYYMMDD}-{ID}")
                    .replaceAll("RS\\d{8}", "RS{N}")
                    .replace("\r", "")
                    .strip();
        }
    }

    /** With {@code -Dbms.documents.samples}: the PDF and a PNG of each page, for the visual check (ADR-034 item 8). */
    static void sample(String name, byte[] pdf) throws Exception {
        if (System.getProperty("bms.documents.samples") == null) {
            return;
        }
        java.nio.file.Path dir = java.nio.file.Path.of("target/document-samples");
        java.nio.file.Files.createDirectories(dir);
        java.nio.file.Files.write(dir.resolve(name + ".pdf"), pdf);
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            org.apache.pdfbox.rendering.PDFRenderer r = new org.apache.pdfbox.rendering.PDFRenderer(d);
            for (int i = 0; i < d.getNumberOfPages(); i++) {
                javax.imageio.ImageIO.write(
                        r.renderImageWithDPI(i, 110),
                        "png",
                        dir.resolve(name + "-p" + (i + 1) + ".png").toFile());
            }
        }
    }

    /** Compares with {@code golden/documents/<name>.txt}; {@code -Dbms.golden.record=true} writes it instead. */
    static void golden(String name, byte[] pdf) throws Exception {
        sample(name, pdf);
        java.nio.file.Path file = java.nio.file.Path.of("src/test/resources/golden/documents/" + name + ".txt");
        String actual = text(pdf) + "\n";
        if (Boolean.getBoolean("bms.golden.record")) {
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, actual);
            return;
        }
        assertThat(actual).as("golden text of %s", name).isEqualTo(java.nio.file.Files.readString(file));
    }

    static int pages(byte[] pdf) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return d.getNumberOfPages();
        }
    }

    long count(String table) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM " + table + " WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    // ---- capability off ----------------------------------------------------------------------

    /** Spec 1.1 and 6.3: off is the default; no route answers, nothing is written, a sale is as it was. */
    @Test
    void withTheCapabilityOffNoRouteAnswersAndASaleIsAsItWas() {
        capability("documents", false);
        UUID s = sale(hq, "cash", "2");
        JsonNode sale = api.get("/sales/" + s, ADMIN).getBody();
        assertThat(sale.has("documents")).isFalse();
        assertThat(sale.get("status").asString()).isEqualTo("completed");
        UUID any = UUID.randomUUID();
        List<ResponseEntity<JsonNode>> answers = List.of(
                api.postKeyed("/sales/" + s + "/documents", Map.of("type", "invoice"), DOCS, "*", "key-00000001"),
                api.get("/sales/" + s + "/documents", DOCS),
                api.postKeyed(
                        "/transfers/" + any + "/documents", Map.of("type", "delivery_note"), DOCS, "*", "key-00000002"),
                api.get("/documents", DOCS),
                api.get("/documents/" + any, DOCS),
                api.post("/documents/" + any + "/pdf", Map.of(), DOCS),
                api.get("/document-series", DOCS),
                api.call(
                        HttpMethod.PUT,
                        "/document-series/invoice",
                        Map.of("prefix", "TAX"),
                        DOCS + "," + SETTINGS,
                        "*",
                        Map.of()),
                api.get("/quotations", DOCS),
                api.get("/quotations/" + any, DOCS),
                api.post("/quotations/" + any + "/cancel", Map.of("reason", "no longer needed"), DOCS),
                api.postKeyed(
                        "/quotations/" + any + "/convert", Map.of("payment_method", "cash"), DOCS, "*", "key-00000003"),
                api.get("/customers/" + any + "/statement/pdf", DOCS));
        for (ResponseEntity<JsonNode> r : answers) {
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(code(r)).isEqualTo("capability_not_enabled");
        }
        assertThat(count("retail_documents")).isZero();
        assertThat(count("retail_document_series")).isZero();
        assertThat(count("retail_quotations")).isZero();
    }

    // ---- the invoice -------------------------------------------------------------------------

    /** Numbered in the branch's series, stored once, idempotent by sale and type, text as the golden file. */
    @Test
    void anInvoiceIsNumberedStoredOnceAndIssuedOncePerSale() throws Exception {
        UUID s1 = sale(hq, "cash", "3");
        ResponseEntity<JsonNode> first = issue(s1, "invoice", DOCS, "*", "invoice-key-1");
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode doc = first.getBody();
        assertThat(doc.get("number").asString()).isEqualTo("INV-HQ-000001");
        assertThat(doc.get("type").asString()).isEqualTo("invoice");
        assertThat(doc.get("status").asString()).isEqualTo("issued");
        assertThat(doc.get("total_minor").asLong()).isEqualTo(300);
        assertThat(doc.get("created").asBoolean()).isTrue();
        assertThat(doc.get("issue_date").asString()).isEqualTo(TODAY);

        // The same key replays; another key finds the document already issued, whatever the type of call.
        ResponseEntity<JsonNode> replay = issue(s1, "invoice", DOCS, "*", "invoice-key-1");
        assertThat(replay.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.getBody().get("id").asString())
                .isEqualTo(doc.get("id").asString());
        ResponseEntity<JsonNode> again = issue(s1, "invoice", DOCS, "*", "invoice-key-2");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("id").asString()).isEqualTo(doc.get("id").asString());
        assertThat(again.getBody().get("created").asBoolean()).isFalse();
        assertThat(again.getBody().get("pdf_document_id").asString())
                .isEqualTo(doc.get("pdf_document_id").asString());

        // A second sale takes the next number; the other branch has a series of its own.
        UUID s2 = sale(hq, "cash", "1");
        assertThat(issued(s2, "invoice").get("number").asString()).isEqualTo("INV-HQ-000002");
        UUID s3 = sale(br2, "cash", "1");
        assertThat(issued(s3, "invoice").get("number").asString()).isEqualTo("INV-BR2-000001");

        assertThat(count("retail_documents")).isEqualTo(3);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM documents WHERE tenant_id = ? AND doc_type = 'invoice'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .as("one stored PDF each")
                .isEqualTo(3);
        byte[] pdf = pdf(doc);
        assertThat(pages(pdf)).isEqualTo(1);
        golden("retail_invoice", pdf);

        // The list and the sale's own list show it, with the stored PDF.
        JsonNode forSale = api.get("/sales/" + s1 + "/documents", DOCS).getBody();
        assertThat(forSale.get("items")).hasSize(1);
        assertThat(forSale.get("items").get(0).get("number").asString()).isEqualTo("INV-HQ-000001");
        JsonNode list = api.get("/documents?type=invoice", DOCS).getBody();
        assertThat(list.get("items")).hasSize(3);
        assertThat(api.get("/documents?query=INV-BR2", DOCS).getBody().get("items"))
                .hasSize(1);
        assertThat(api.get("/documents?type=quotation", DOCS).getBody().get("items"))
                .isEmpty();
    }

    /** Spec 14.3: the prefix is configurable, audited and needs the settings permission; numbers already issued never change. */
    @Test
    void thePrefixIsConfigurableAuditedAndNeverRewritesAnIssuedNumber() {
        UUID s1 = sale(hq, "cash", "1");
        assertThat(issued(s1, "invoice").get("number").asString()).isEqualTo("INV-HQ-000001");

        ResponseEntity<JsonNode> denied =
                api.call(HttpMethod.PUT, "/document-series/invoice", Map.of("prefix", "TAX"), DOCS, "*", Map.of());
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        ResponseEntity<JsonNode> lower = api.call(
                HttpMethod.PUT,
                "/document-series/invoice",
                Map.of("prefix", "tax"),
                DOCS + "," + SETTINGS,
                "*",
                Map.of());
        assertThat(lower.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> changed = api.call(
                HttpMethod.PUT,
                "/document-series/invoice",
                Map.of("prefix", "TAX"),
                DOCS + "," + SETTINGS,
                "*",
                Map.of());
        assertThat(changed.getStatusCode()).as("%s", changed.getBody()).isEqualTo(HttpStatus.OK);

        UUID s2 = sale(hq, "cash", "1");
        assertThat(issued(s2, "invoice").get("number").asString()).isEqualTo("TAX-HQ-000002");
        JsonNode first = api.get("/sales/" + s1 + "/documents", DOCS)
                .getBody()
                .get("items")
                .get(0);
        assertThat(first.get("number").asString()).isEqualTo("INV-HQ-000001");

        JsonNode invoice = null;
        for (JsonNode series : api.get("/document-series", DOCS).getBody().get("items")) {
            if (series.get("type").asString().equals("invoice")) {
                invoice = series;
            }
        }
        assertThat(invoice.get("prefix").asString()).isEqualTo("TAX");
        assertThat(invoice.get("default_prefix").asString()).isEqualTo("INV");
        for (JsonNode b : invoice.get("branches")) {
            if (b.get("branch_code").asString().equals("HQ")) {
                assertThat(b.get("issued").asInt()).isEqualTo(2);
                assertThat(b.get("next").asString()).isEqualTo("TAX-HQ-000003");
            }
        }
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'retail.document.prefix_changed'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /** Spec 13: gapless numbering under concurrent issue, and one document per sale however many race for it. */
    @Test
    void numberingIsGaplessUnderConcurrentIssueAndOneDocumentPerSale() throws Exception {
        List<UUID> sales = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            sales.add(sale(hq, "cash", "1"));
        }
        UUID raced = sales.get(0);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
        for (UUID s : sales) {
            calls.add(pool.submit(() -> {
                start.await();
                return issue(s, "invoice", DOCS, "*", UUID.randomUUID().toString());
            }));
        }
        for (int i = 0; i < 8; i++) {
            calls.add(pool.submit(() -> {
                start.await();
                return issue(raced, "invoice", DOCS, "*", UUID.randomUUID().toString());
            }));
        }
        start.countDown();
        for (Future<ResponseEntity<JsonNode>> f : calls) {
            ResponseEntity<JsonNode> r = f.get(60, TimeUnit.SECONDS);
            assertThat(r.getStatusCode().is2xxSuccessful())
                    .as("%s", r.getBody())
                    .isTrue();
        }
        pool.shutdown();
        List<Integer> numbers = TestDatabase.owner()
                .sql("SELECT seq_no FROM retail_documents WHERE tenant_id = ? AND type = 'invoice' ORDER BY seq_no")
                .param(t.tenantId())
                .query(Integer.class)
                .list();
        assertThat(numbers)
                .as("eight sales, eight numbers, no gap and no duplicate")
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(count("documents"))
                .as("one stored PDF each, however many raced")
                .isGreaterThanOrEqualTo(8);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM documents WHERE tenant_id = ? AND doc_type = 'invoice'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(8);
        assertThat(TestDatabase.owner()
                        .sql("SELECT last_no FROM retail_document_series WHERE tenant_id = ? AND type = 'invoice'")
                        .param(t.tenantId())
                        .query(Integer.class)
                        .single())
                .isEqualTo(8);
    }

    // JUSTIFICATION-A3: the remaining cases of the same new test class (delivery note, void, quotation, scope,
    // transfer, statement).

    // ---- the delivery note -------------------------------------------------------------------

    /** Quantities only by default, prices when the issuer asks, a signature block for the receiver, linked to the invoice. */
    @Test
    void aDeliveryNoteShowsQuantitiesAndNoPricesUnlessAsked() throws Exception {
        UUID s = sale(hq, "credit", "5");
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + s + "/documents", Map.of("type", "delivery_note"), DOCS, "*", "delivery-key-1");
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode dn = r.getBody();
        assertThat(dn.get("number").asString()).isEqualTo("DN-HQ-000001");
        assertThat(dn.has("total_minor"))
                .as("a delivery note without prices carries no total")
                .isFalse();
        byte[] pdf = pdf(dn);
        String text = text(pdf);
        assertThat(text).contains("DELIVERY NOTE", "Test Buyer 01", "Delivered by", "Received by", "Test Product DC-1");
        assertThat(text).doesNotContain("Unit price").doesNotContain("Amount (UGX)");
        golden("retail_delivery_note", pdf);

        // The invoice of the same sale is its own document in its own series; a delivery note issued after it names it.
        UUID s2 = sale(hq, "cash", "2");
        assertThat(issued(s2, "invoice").get("number").asString()).isEqualTo("INV-HQ-000001");
        ResponseEntity<JsonNode> priced = api.postKeyed(
                "/sales/" + s2 + "/documents",
                Map.of("type", "delivery_note", "show_prices", true),
                DOCS,
                "*",
                "delivery-key-2");
        assertThat(priced.getBody().get("number").asString()).isEqualTo("DN-HQ-000002");
        assertThat(priced.getBody().get("total_minor").asLong()).isEqualTo(200);
        String pricedText = text(pdf(priced.getBody()));
        assertThat(pricedText).contains("Unit price (UGX)", "INV-HQ-000001");
    }

    /** Spec 6.2 and 13: a void sale voids its documents and keeps the number; no new document is issued for it. */
    @Test
    void aVoidedSaleVoidsItsDocumentsAndKeepsTheNumber() {
        UUID s = sale(hq, "cash", "1");
        JsonNode invoice = issued(s, "invoice");
        assertThat(invoice.get("status").asString()).isEqualTo("issued");
        ResponseEntity<JsonNode> voided = api.post("/sales/" + s + "/void", Map.of("reason", "Wrong item sold"), DOCS);
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);

        JsonNode after =
                api.get("/documents/" + invoice.get("id").asString(), DOCS).getBody();
        assertThat(after.get("status").asString()).isEqualTo("void");
        assertThat(after.get("number").asString()).isEqualTo("INV-HQ-000001");
        ResponseEntity<JsonNode> late =
                issue(s, "delivery_note", DOCS, "*", UUID.randomUUID().toString());
        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(late)).isEqualTo("sale_voided");
        // The number is never reused: the next sale takes the next one.
        UUID s2 = sale(hq, "cash", "1");
        assertThat(issued(s2, "invoice").get("number").asString()).isEqualTo("INV-HQ-000002");
        // The document row itself is append-only for every role.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TestDatabase.owner()
                        .sql("UPDATE retail_documents SET buyer_name = 'x' WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .update())
                .hasStackTraceContaining("append-only");
    }

    // ---- the quotation -----------------------------------------------------------------------

    ResponseEntity<JsonNode> quote(String validUntil, String price, String key) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("qty", "10");
        if (price != null) {
            line.put("unit_price_minor", Long.parseLong(price));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("buyer_name", "Test Buyer 01");
        body.put("buyer_contact", "+256700000001");
        body.put("valid_until", validUntil);
        body.put("notes", "Delivery within three days of the order.");
        body.put("lines", List.of(line));
        return api.postKeyed("/quotations", body, DOCS, "*", key);
    }

    ResponseEntity<JsonNode> convert(UUID quotation, boolean keep, boolean invoice, String key) {
        return api.postKeyed(
                "/quotations/" + quotation + "/convert",
                Map.of("payment_method", "cash", "keep_quoted_prices", keep, "issue_invoice", invoice),
                DOCS,
                "*",
                key);
    }

    /** A quotation reserves nothing, is numbered and stored, and converts once to a sale and its linked invoice. */
    @Test
    void aQuotationConvertsOnceToASaleAndItsInvoice() throws Exception {
        long before = TestDatabase.owner()
                .sql("SELECT qty FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                .params(t.tenantId(), hq, product)
                .query(Long.class)
                .single();
        long entries = count("journal_entries");
        ResponseEntity<JsonNode> made = quote("2026-11-08", "120", "quote-key-1");
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode q = made.getBody();
        assertThat(q.get("number").asString()).isEqualTo("QT-HQ-000001");
        assertThat(q.get("total_minor").asLong()).isEqualTo(1_200);
        assertThat(q.get("status").asString()).isEqualTo("open");
        assertThat(q.get("expired").asBoolean()).isFalse();
        assertThat(q.get("lines").get(0).get("unit_price_minor").asLong()).isEqualTo(120);
        byte[] pdf = pdf(q);
        golden("retail_quotation", pdf);
        assertThat(api.postKeyed("/quotations", Map.of(), DOCS, "*", "quote-key-1")
                        .getStatusCode())
                .as("the same key with another body is refused")
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT qty FROM retail_stock_balances WHERE tenant_id = ? AND branch_id = ? AND product_id = ?")
                        .params(t.tenantId(), hq, product)
                        .query(Long.class)
                        .single())
                .as("a quotation reserves no stock")
                .isEqualTo(before);
        assertThat(count("journal_entries")).as("and posts nothing").isEqualTo(entries);
        UUID id = RetailTestSupport.id(made);
        assertThat(api.get("/quotations/" + id, DOCS).getBody().get("lines")).hasSize(1);
        assertThat(api.get("/quotations?status=open&query=QT-HQ", DOCS)
                        .getBody()
                        .get("items"))
                .hasSize(1);
        assertThat(api.get("/quotations?status=converted", DOCS).getBody().get("items"))
                .isEmpty();

        ResponseEntity<JsonNode> done = convert(id, true, true, "convert-key-1");
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        UUID saleId = UUID.fromString(done.getBody().get("sale_id").asString());
        assertThat(api.get("/sales/" + saleId, DOCS)
                        .getBody()
                        .get("total_minor")
                        .asLong())
                .as("the quoted price is kept")
                .isEqualTo(1_200);
        JsonNode invoice = done.getBody().get("invoice");
        assertThat(invoice.get("number").asString()).isEqualTo("INV-HQ-000001");
        assertThat(invoice.get("source_document_id").asString())
                .isEqualTo(api.get("/quotations/" + id, DOCS)
                        .getBody()
                        .get("document_id")
                        .asString());
        assertThat(text(pdf(invoice))).contains("Quotation", "QT-HQ-000001");
        JsonNode converted = api.get("/quotations/" + id, DOCS).getBody();
        assertThat(converted.get("status").asString()).isEqualTo("converted");
        assertThat(converted.get("converted_sale_id").asString()).isEqualTo(saleId.toString());

        // A replay answers the same; another key finds it converted already.
        assertThat(convert(id, true, true, "convert-key-1").getHeaders().getFirst("Idempotent-Replayed"))
                .isEqualTo("true");
        ResponseEntity<JsonNode> twice = convert(id, true, true, "convert-key-2");
        assertThat(twice.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(code(twice)).isEqualTo("quotation_not_open");
        assertThat(count("retail_sales")).isEqualTo(1);
    }

    /** Without keep_quoted_prices the sale is priced again today, through the ordinary sale path. */
    @Test
    void aQuotationIsPricedAgainUnlessTheQuotedPricesAreKept() {
        UUID id = RetailTestSupport.id(quote("2026-11-08", "120", "quote-key-2"));
        ResponseEntity<JsonNode> done = convert(id, false, false, "convert-key-3");
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().has("invoice")).isFalse();
        assertThat(api.get("/sales/" + done.getBody().get("sale_id").asString(), DOCS)
                        .getBody()
                        .get("total_minor")
                        .asLong())
                .as("the product's price today, 100, not the quoted 120")
                .isEqualTo(1_000);
        assertThat(count("retail_documents"))
                .as("only the quotation's own document")
                .isEqualTo(1);
    }

    /** A quotation is cancelled, not edited; it voids its document, and it cannot be converted after. */
    @Test
    void aCancelledQuotationIsVoidAndCannotBeConverted() {
        JsonNode q = quote("2026-11-08", null, "quote-key-3").getBody();
        UUID id = UUID.fromString(q.get("id").asString());
        assertThat(api.post("/quotations/" + id + "/cancel", Map.of("reason", " "), DOCS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        ResponseEntity<JsonNode> cancelled =
                api.post("/quotations/" + id + "/cancel", Map.of("reason", "Customer went elsewhere"), DOCS);
        assertThat(cancelled.getStatusCode()).as("%s", cancelled.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status").asString()).isEqualTo("cancelled");
        assertThat(api.get("/documents/" + q.get("document_id").asString(), DOCS)
                        .getBody()
                        .get("status")
                        .asString())
                .isEqualTo("void");
        assertThat(code(convert(id, false, false, "convert-key-4"))).isEqualTo("quotation_not_open");
        assertThat(code(api.post("/quotations/" + id + "/cancel", Map.of("reason", "Again please"), DOCS)))
                .isEqualTo("quotation_not_open");
    }

    /** Valid until is a business date: a past one is refused, and an expired quotation can no longer be converted. */
    @Test
    void anExpiredQuotationCannotBeConverted() {
        ResponseEntity<JsonNode> past = quote("2026-10-07", null, "quote-key-4");
        assertThat(past.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        // Valid today in Kampala (2026-10-08, while UTC is still the 7th) is allowed.
        assertThat(quote(TODAY, null, "quote-key-5").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID expired = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO retail_quotations (id, tenant_id, created_by, branch_id, quote_date, valid_until, buyer_name,
                            currency, total_minor)
                        VALUES (?, ?, ?, ?, DATE '2026-01-05', DATE '2026-01-31', 'Test Buyer 03', 'UGX', 0)
                        """)
                .params(expired, t.tenantId(), api.userId(), hq)
                .update();
        ResponseEntity<JsonNode> r = convert(expired, false, false, "convert-key-5");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(code(r)).isEqualTo("quotation_expired");
        assertThat(api.get("/quotations/" + expired, DOCS)
                        .getBody()
                        .get("expired")
                        .asBoolean())
                .isTrue();
    }

    // ---- permissions and scope ---------------------------------------------------------------

    /** Seeded permission rows only: the sales role cannot issue, and a user scoped to one branch sees only its documents. */
    @Test
    void documentsFollowThePermissionAndTheBranchScope() {
        UUID hqSale = sale(hq, "cash", "1");
        UUID br2Sale = sale(br2, "cash", "1");
        String sales = RetailTestSupport.SALES;
        assertThat(issue(hqSale, "invoice", sales, "*", "scope-key-1").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.get("/documents", sales).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.postKeyed("/quotations", Map.of(), sales, "*", "scope-key-2")
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        // Scoped to branch 2: the head office sale is invisible (404), its own branch's is fine.
        assertThat(issue(hqSale, "invoice", DOCS, br2.toString(), "scope-key-3").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<JsonNode> own = issue(br2Sale, "invoice", DOCS, br2.toString(), "scope-key-4");
        assertThat(own.getStatusCode()).as("%s", own.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode hqDoc = issued(hqSale, "invoice");
        JsonNode scoped = api.call(HttpMethod.GET, "/documents", null, DOCS, br2.toString(), Map.of())
                .getBody();
        assertThat(scoped.get("items")).hasSize(1);
        assertThat(scoped.get("items").get(0).get("branch_id").asString()).isEqualTo(br2.toString());
        assertThat(api.call(
                                HttpMethod.GET,
                                "/documents/" + hqDoc.get("id").asString(),
                                null,
                                DOCS,
                                br2.toString(),
                                Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(api.call(
                                HttpMethod.POST,
                                "/documents/" + hqDoc.get("id").asString() + "/pdf",
                                Map.of(),
                                DOCS,
                                br2.toString(),
                                Map.of())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // The signed link of a stored PDF follows the same rule.
        ResponseEntity<JsonNode> link = http.exchange(
                "/api/v1/documents/" + hqDoc.get("pdf_document_id").asString() + "/download-url",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(api.headers(DOCS, br2.toString(), Map.of())),
                JsonNode.class);
        assertThat(link.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // Another tenant sees nothing at all.
        TestDatabase.Fixture other = TestDatabase.tenant("retail-docs-other", false, true);
        RetailTestSupport theirs = new RetailTestSupport(http, other);
        ResponseEntity<JsonNode> foreign =
                theirs.get("/documents/" + hqDoc.get("id").asString(), DOCS);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- the delivery note of a stock move ---------------------------------------------------

    /** Quantities only, from the source branch to the destination, one per stock move. */
    @Test
    void aStockMoveHasADeliveryNoteWithoutPrices() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from_branch_id", hq);
        body.put("to_branch_id", br2);
        body.put("lines", List.of(Map.of("product_id", product, "qty", "4")));
        ResponseEntity<JsonNode> move =
                api.postKeyed("/transfers", body, DOCS, "*", UUID.randomUUID().toString());
        assertThat(move.getStatusCode()).as("%s", move.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID transfer = RetailTestSupport.id(move);
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/transfers/" + transfer + "/documents", Map.of("type", "delivery_note"), DOCS, "*", "transfer-key-1");
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("number").asString()).isEqualTo("DN-HQ-000001");
        assertThat(r.getBody().get("transfer_id").asString()).isEqualTo(transfer.toString());
        assertThat(r.getBody().has("total_minor")).isFalse();
        byte[] pdf = pdf(r.getBody());
        assertThat(text(pdf))
                .contains("Stock move", "Test Branch Two", "Test Product DC-1")
                .doesNotContain("Unit price");
        golden("retail_delivery_note_transfer", pdf);
        ResponseEntity<JsonNode> again = api.postKeyed(
                "/transfers/" + transfer + "/documents", Map.of("type", "delivery_note"), DOCS, "*", "transfer-key-2");
        assertThat(again.getBody().get("id").asString())
                .isEqualTo(r.getBody().get("id").asString());
        assertThat(api.postKeyed(
                                "/transfers/" + transfer + "/documents",
                                Map.of("type", "invoice"),
                                DOCS,
                                "*",
                                "transfer-key-3")
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.call(
                                HttpMethod.POST,
                                "/transfers/" + transfer + "/documents",
                                Map.of("type", "delivery_note"),
                                DOCS,
                                br2.toString(),
                                Map.of("Idempotency-Key", "transfer-key-4"))
                        .getStatusCode())
                .as("the source branch issues it")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    // JUSTIFICATION-A3: one more case of the same new test class (a failed render leaves the sale and a numbered
    // document).
    /** Spec 6.1 and 13: a failing render leaves the sale committed and a numbered document, and the next request renders it. */
    @Test
    void aFailedRenderLeavesTheSaleAndANumberedDocumentThatRendersOnTheNextRequest() throws Exception {
        UUID s = sale(hq, "cash", "2");
        Mockito.doThrow(new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE, "documents_busy", "Documents busy", "Try again."))
                .when(documents)
                .renderPdf(ArgumentMatchers.any(), ArgumentMatchers.any());
        ResponseEntity<JsonNode> failed = issue(s, "invoice", DOCS, "*", "render-key-1");
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(api.get("/sales/" + s, ADMIN).getBody().get("status").asString())
                .as("the sale is untouched")
                .isEqualTo("completed");
        JsonNode listed = api.get("/sales/" + s + "/documents", DOCS)
                .getBody()
                .get("items")
                .get(0);
        assertThat(listed.get("number").asString())
                .as("the number was taken and is kept")
                .isEqualTo("INV-HQ-000001");
        assertThat(listed.has("pdf_document_id")).as("no PDF yet").isFalse();

        Mockito.reset(documents);
        // The same key replays the issue and finishes the PDF from the stored snapshot.
        ResponseEntity<JsonNode> retried = issue(s, "invoice", DOCS, "*", "render-key-1");
        assertThat(retried.getStatusCode().is2xxSuccessful())
                .as("%s", retried.getBody())
                .isTrue();
        assertThat(retried.getBody().get("id").asString())
                .isEqualTo(listed.get("id").asString());
        assertThat(text(pdf(retried.getBody()))).contains("INV-HQ-000001", "Test Product DC-1");
        UUID next = sale(hq, "cash", "1");
        assertThat(issued(next, "invoice").get("number").asString())
                .as("no gap and no duplicate")
                .isEqualTo("INV-HQ-000002");
        assertThat(count("retail_documents")).isEqualTo(2);
    }

    // JUSTIFICATION-A3: one more case of the same new test class (packs and the price list as sold).
    /** Pack units are printed as they were sold (units V39) and the price list that applied is named (price lists V41). */
    @Test
    void packsAndThePriceListAreShownAsSold() throws Exception {
        capability("units", true);
        capability("price_lists", true);
        String perms = DOCS + ",retail.pricelist.manage,retail.pricelist.use";
        UUID carton = api.unit("carton");
        assertThat(api.post("/products/" + product + "/units", Map.of("unit_id", carton, "factor", "12"), perms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        UUID list = RetailTestSupport.id(api.post("/price-lists", Map.of("name", "Wholesale"), perms));
        assertThat(api.post(
                                "/price-lists/" + list + "/items",
                                Map.of("product_id", product, "unit_id", carton, "price_minor", 1_100),
                                perms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("payment_method", "cash");
        body.put("price_list_id", list);
        body.put("lines", List.of(Map.of("product_id", product, "qty", "3", "unit_id", carton)));
        ResponseEntity<JsonNode> sold =
                api.postKeyed("/sales", body, perms, "*", UUID.randomUUID().toString());
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(sold.getBody().get("total_minor").asLong()).isEqualTo(3_300);
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + RetailTestSupport.id(sold) + "/documents",
                Map.of("type", "invoice"),
                perms,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        byte[] pdf = pdf(r.getBody());
        assertThat(text(pdf)).contains("3 carton", "= 36 u-dc-1", "Price list", "Wholesale", "1,100", "3,300");
        golden("retail_invoice_pack", pdf);
    }

    // JUSTIFICATION-A3: one more case of the same new test class (a long invoice runs to several pages).
    /** ADR-034 quality bar 6: a long table repeats its header on each page and the footer says page x of y with the real numbers. */
    @Test
    void aLongInvoiceRepeatsItsHeaderAndNumbersItsPages() throws Exception {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 1; i <= 45; i++) {
            UUID p = api.product("LP-" + i, 30, 100 + i);
            api.stockUp(hq, p, "50");
            lines.add(Map.of("product_id", p, "qty", "2"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("payment_method", "cash");
        body.put("lines", lines);
        ResponseEntity<JsonNode> sold =
                api.postKeyed("/sales", body, DOCS, "*", UUID.randomUUID().toString());
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        byte[] pdf = pdf(issued(RetailTestSupport.id(sold), "invoice"));
        int pages = pages(pdf);
        assertThat(pages).as("forty-five lines run to several pages").isGreaterThanOrEqualTo(3);
        String text = text(pdf);
        for (int page = 1; page <= pages; page++) {
            assertThat(text).contains("page " + page + " of " + pages);
        }
        assertThat(text.split("Unit price \\(UGX\\)", -1).length - 1)
                .as("the table header is on every page")
                .isEqualTo(pages);
        sample("retail_invoice_long", pdf);
    }

    // JUSTIFICATION-A3: one more case of the same new test class (the lists page and filter).
    /** The document and quotation lists page newest first with an opaque cursor, and filter by type, date and status. */
    @Test
    void theListsPageNewestFirstAndFilter() {
        List<String> numbers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            numbers.add(issued(sale(hq, "cash", "1"), "invoice").get("number").asString());
        }
        JsonNode first = api.get("/documents?limit=2", DOCS).getBody();
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("items").get(0).get("number").asString()).isEqualTo(numbers.get(2));
        assertThat(first.get("items").get(1).get("number").asString()).isEqualTo(numbers.get(1));
        JsonNode second = api.get(
                        "/documents?limit=2&cursor=" + first.get("next_cursor").asString(), DOCS)
                .getBody();
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("items").get(0).get("number").asString()).isEqualTo(numbers.get(0));
        assertThat(second.get("next_cursor").isNull()).isTrue();
        assertThat(api.get("/documents?cursor=not-a-cursor", DOCS).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(api.get("/documents?type=receipt", DOCS).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(api.get("/documents?from=2026-10-09", DOCS).getBody().get("items"))
                .isEmpty();
        assertThat(api.get("/documents?from=2026-10-08&to=2026-10-08&type=invoice", DOCS)
                        .getBody()
                        .get("items"))
                .hasSize(3);

        quote("2026-11-08", null, "list-key-1");
        quote("2026-11-09", null, "list-key-2");
        JsonNode quotes = api.get("/quotations?limit=1", DOCS).getBody();
        assertThat(quotes.get("items")).hasSize(1);
        assertThat(quotes.get("items").get(0).get("number").asString()).isEqualTo("QT-HQ-000002");
        JsonNode more = api.get(
                        "/quotations?limit=1&cursor="
                                + quotes.get("next_cursor").asString(),
                        DOCS)
                .getBody();
        assertThat(more.get("items").get(0).get("number").asString()).isEqualTo("QT-HQ-000001");
        assertThat(api.get("/quotations?status=open", DOCS).getBody().get("items"))
                .hasSize(2);
        assertThat(api.get("/quotations?status=weird", DOCS).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    // ---- the customer statement --------------------------------------------------------------

    byte[] statementPdf(UUID customer, String query, String perms) {
        ResponseEntity<byte[]> r = http.exchange(
                "/api/v1/retail/customers/" + customer + "/statement/pdf" + query,
                HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(api.headers(perms, "*", Map.of())),
                byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-store");
        return r.getBody();
    }

    /** The fourth document: rendered as at today and never stored, closing at what the account screen shows. */
    @Test
    void theCustomerStatementIsAPdfOfTheAccountAndIsNeverStored() throws Exception {
        capability("customer_accounts", true);
        UUID customer = RetailTestSupport.id(
                api.post("/customers", Map.of("name", "Test Buyer 02", "contact", "+256700000002"), DOCS));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("payment_method", "credit");
        body.put("customer_id", customer);
        body.put("sale_date", "2026-10-01");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "50")));
        ResponseEntity<JsonNode> sold =
                api.postKeyed("/sales", body, DOCS, "*", UUID.randomUUID().toString());
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> paid = api.postKeyed(
                "/customers/" + customer + "/payments",
                Map.of("branch_id", hq, "amount_minor", 2_000, "method", "cash"),
                DOCS,
                "*",
                UUID.randomUUID().toString());
        assertThat(paid.getStatusCode()).as("%s", paid.getBody()).isEqualTo(HttpStatus.CREATED);

        byte[] pdf = statementPdf(customer, "?from=2026-09-01&to=2026-10-08", DOCS);
        String text = text(pdf);
        assertThat(text).contains("STATEMENT OF ACCOUNT", "Test Buyer 02", "+256700000002", "Closing balance", "3,000");
        assertThat(text).contains("Three thousand Uganda shillings only");
        JsonNode screen = api.get("/customers/" + customer + "/statement?from=2026-09-01&to=2026-10-08", DOCS)
                .getBody();
        assertThat(screen.get("closing_minor").asLong()).isEqualTo(3_000);
        golden("retail_customer_statement", pdf);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM documents WHERE tenant_id = ? AND subject_type = 'retail.document'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .as("a statement is rendered on each request and never stored")
                .isZero();

        // It needs both capabilities and the permission.
        capability("customer_accounts", false);
        ResponseEntity<byte[]> off = http.exchange(
                "/api/v1/retail/customers/" + customer + "/statement/pdf",
                HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(api.headers(DOCS, "*", Map.of())),
                byte[].class);
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        capability("customer_accounts", true);
        ResponseEntity<byte[]> denied = http.exchange(
                "/api/v1/retail/customers/" + customer + "/statement/pdf",
                HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(api.headers(RetailTestSupport.SALES, "*", Map.of())),
                byte[].class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // JUSTIFICATION-A3: the review follow-up cases of the same new test class (void banner, switched-off features at
    // conversion,
    // statement reconciliation, escaping, part payment, the void race, the prefix race).

    // ---- a void document is never printed clean ------------------------------------------------

    /** A void document is served only as a version with the VOID banner; the copy issued before is no longer readable. */
    @Test
    void aVoidDocumentIsServedOnlyWithAVoidBanner() throws Exception {
        UUID s = sale(hq, "cash", "2");
        JsonNode invoice = issued(s, "invoice");
        String cleanPdf = invoice.get("pdf_document_id").asString();
        assertThat(text(pdf(invoice))).doesNotContain("VOID");
        assertThat(api.post("/sales/" + s + "/void", Map.of("reason", "Wrong item sold"), DOCS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        JsonNode after =
                api.get("/documents/" + invoice.get("id").asString(), DOCS).getBody();
        assertThat(after.get("status").asString()).isEqualTo("void");
        assertThat(after.has("pdf_document_id"))
                .as("the clean copy is not offered")
                .isFalse();
        ResponseEntity<JsonNode> link = http.exchange(
                "/api/v1/documents/" + cleanPdf + "/download-url",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(api.headers(DOCS, "*", Map.of())),
                JsonNode.class);
        assertThat(link.getStatusCode()).as("the clean copy is not served").isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> marked =
                api.post("/documents/" + invoice.get("id").asString() + "/pdf", Map.of(), DOCS);
        assertThat(marked.getStatusCode()).as("%s", marked.getBody()).isEqualTo(HttpStatus.OK);
        String voidPdf = marked.getBody().get("pdf_document_id").asString();
        assertThat(voidPdf).isNotEqualTo(cleanPdf);
        byte[] bytes = pdf(marked.getBody());
        String text = text(bytes);
        assertThat(text).contains("VOID: its sale was voided", "INV-HQ-000001", "Test Product DC-1");
        sample("retail_invoice_void", bytes);
        // One void version, however many ask.
        assertThat(api.post("/documents/" + invoice.get("id").asString() + "/pdf", Map.of(), DOCS)
                        .getBody()
                        .get("pdf_document_id")
                        .asString())
                .isEqualTo(voidPdf);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM documents WHERE tenant_id = ? AND doc_number = 'INV-HQ-000001 VOID'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        // A cancelled quotation reads the same way.
        JsonNode q = quote("2026-11-08", null, "void-quote-1").getBody();
        String quoteDoc = q.get("document_id").asString();
        assertThat(api.post(
                                "/quotations/" + q.get("id").asString() + "/cancel",
                                Map.of("reason", "Changed our mind"),
                                DOCS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode cancelled =
                api.post("/documents/" + quoteDoc + "/pdf", Map.of(), DOCS).getBody();
        assertThat(text(pdf(cancelled))).contains("VOID: the quotation was cancelled");
    }

    // ---- a feature switched off since the quotation was made -------------------------------------

    /** Spec 14.1: with lists or packs off at conversion the sale is priced at the product's price, in single units, and says so. */
    @Test
    void aQuotationConvertsAfterPriceListsAndPacksAreSwitchedOff() throws Exception {
        capability("units", true);
        capability("price_lists", true);
        String perms = DOCS + ",retail.pricelist.manage,retail.pricelist.use";
        UUID carton = api.unit("carton");
        assertThat(api.post("/products/" + product + "/units", Map.of("unit_id", carton, "factor", "12"), perms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        UUID list = RetailTestSupport.id(api.post("/price-lists", Map.of("name", "Wholesale"), perms));
        assertThat(api.post(
                                "/price-lists/" + list + "/items",
                                Map.of("product_id", product, "unit_id", carton, "price_minor", 1_100),
                                perms)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("buyer_name", "Test Buyer 01");
        body.put("price_list_id", list);
        body.put("valid_until", "2026-11-08");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "2", "unit_id", carton)));
        ResponseEntity<JsonNode> made = api.postKeyed("/quotations", body, perms, "*", "off-quote-1");
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(made.getBody().get("total_minor").asLong()).isEqualTo(2_200);

        capability("price_lists", false);
        capability("units", false);
        ResponseEntity<JsonNode> done = convert(RetailTestSupport.id(made), false, true, "off-convert-1");
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(done.getBody().get("note").asString())
                .contains("Price lists are switched off", "Pack sizes are switched off");
        JsonNode sale = api.get("/sales/" + done.getBody().get("sale_id").asString(), DOCS)
                .getBody();
        assertThat(sale.get("lines").get(0).get("qty").asString())
                .as("two cartons of twelve")
                .isEqualTo("24.000");
        assertThat(sale.get("total_minor").asLong())
                .as("24 pieces at the product's 100")
                .isEqualTo(2_400);
        assertThat(sale.has("price_list_id") && !sale.get("price_list_id").isNull())
                .isFalse();
        assertThat(done.getBody().get("invoice").get("number").asString()).isEqualTo("INV-HQ-000001");

        // Keeping the quoted prices also works with both off: the price per piece the carton came to.
        capability("units", true);
        capability("price_lists", true);
        body.put("valid_until", "2026-11-09");
        ResponseEntity<JsonNode> second = api.postKeyed("/quotations", body, perms, "*", "off-quote-2");
        capability("price_lists", false);
        capability("units", false);
        ResponseEntity<JsonNode> kept = convert(RetailTestSupport.id(second), true, false, "off-convert-2");
        assertThat(kept.getStatusCode()).as("%s", kept.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(kept.getBody().get("note").asString()).contains("Pack sizes are switched off");
    }

    // ---- the statement reconciles ----------------------------------------------------------------

    /**
     * A hand-computed account: 500 sold before the range and 100 paid, so 400 opens it; then 300, 200 (later voided) and
     * 100 (branch two) are sold, 150 is paid on account and reversed. The running balance, the closing balance, the imported
     * sale apart, the credit limit, a branch-scoped view and the permission to read sales are all checked.
     */
    @Test
    void theStatementReconcilesLineByLineWithTheLedgerAndTheScope() throws Exception {
        capability("customer_accounts", true);
        UUID customer = RetailTestSupport.id(
                api.post("/customers", Map.of("name", "Test Buyer 02", "contact", "+256700000002"), DOCS));
        int version = api.get("/customers/" + customer + "/account", DOCS)
                .getBody()
                .get("version")
                .asInt();
        assertThat(api.call(
                                HttpMethod.PUT,
                                "/customers/" + customer + "/account",
                                Map.of("credit_limit_minor", 5_000, "clear_terms_days", true),
                                DOCS,
                                "*",
                                Map.of("If-Match", String.valueOf(version)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // Sales are made with exact quantities: 5 x 100 before the range, 3 x 100, 2 x 100 and 1 x 100 inside it.
        UUID s1 = creditSale(customer, hq, "2026-09-10", "5");
        assertThat(api.postKeyed(
                                "/sales/" + s1 + "/payments",
                                Map.of("amount_minor", 100, "method", "cash", "paid_on", "2026-09-12"),
                                DOCS,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        creditSale(customer, hq, "2026-10-02", "3");
        UUID s3 = creditSale(customer, hq, "2026-10-03", "2");
        creditSale(customer, br2, "2026-10-04", "1");
        ResponseEntity<JsonNode> receipt = api.postKeyed(
                "/customers/" + customer + "/payments",
                Map.of("branch_id", hq, "amount_minor", 150, "method", "cash", "paid_on", "2026-10-05"),
                DOCS,
                "*",
                UUID.randomUUID().toString());
        assertThat(receipt.getStatusCode()).as("%s", receipt.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(api.post("/sales/" + s3 + "/void", Map.of("reason", "Wrong buyer"), DOCS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(api.postKeyed(
                                "/customers/" + customer + "/payments/" + RetailTestSupport.id(receipt) + "/reverse",
                                Map.of("reason", "Entered twice"),
                                DOCS,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // An imported credit sale from before the system started: listed apart, never in the ledger.
        TestDatabase.owner().sql("""
                        INSERT INTO retail_sales (id, tenant_id, branch_id, sale_no, sale_date, payment_method, customer_id,
                            due_date, currency, total_minor, cost_total_minor, paid_minor, status, historical, created_by)
                        VALUES (gen_random_uuid(), ?, ?, 'RS99999990', DATE '2025-12-01', 'credit', ?, DATE '2025-12-31', 'UGX',
                            1000, 700, 0, 'completed', true, gen_random_uuid())
                        """).params(t.tenantId(), hq, customer).update();

        // A void is dated by the database clock (not the pinned one), so the range runs well past it: no test depends
        // on the day it runs.
        String range = "from=2026-10-01&to=2028-06-30";
        JsonNode screen =
                api.get("/customers/" + customer + "/statement?" + range, DOCS).getBody();
        // 400 opens it. The hand-computed running balance, in the order the lines fall.
        assertThat(screen.get("opening_minor").asLong()).isEqualTo(400);
        List<String> kinds = new ArrayList<>();
        List<Long> running = new ArrayList<>();
        for (JsonNode l : screen.get("lines")) {
            kinds.add(l.get("kind").asString());
            running.add(l.get("balance_minor").asLong());
        }
        assertThat(kinds).containsExactly("sale", "sale", "sale", "account_payment", "reversal", "void");
        assertThat(running).containsExactly(700L, 900L, 1_000L, 850L, 1_000L, 800L);
        assertThat(screen.get("closing_minor").asLong()).isEqualTo(800);
        assertThat(screen.get("balance_minor").asLong())
                .as("with the imported sale")
                .isEqualTo(1_800);

        byte[] pdf = statementPdf(customer, "?" + range, DOCS);
        String text = text(pdf);
        assertThat(text)
                .contains(
                        "Opening balance",
                        "Sale on credit",
                        "Payment on account (Cash)",
                        "Sale voided",
                        "Payment reversed")
                .contains("Closing balance", "800", "Eight hundred Uganda shillings only")
                .contains("Credit sales before the system started", "1,000", "1,800 UGX")
                .contains("Credit limit", "5,000", "All branches");
        for (String balance : List.of("700", "900", "1,000", "850")) {
            assertThat(text).contains(balance);
        }
        golden("retail_customer_statement_reconciled", pdf);

        // Scoped to branch two the statement is that branch's sale only; the imported sale of another branch is not
        // there.
        ResponseEntity<byte[]> scoped = http.exchange(
                "/api/v1/retail/customers/" + customer + "/statement/pdf?" + range,
                HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(api.headers(DOCS, br2.toString(), Map.of())),
                byte[].class);
        assertThat(scoped.getStatusCode()).isEqualTo(HttpStatus.OK);
        String scopedText = text(scoped.getBody());
        assertThat(scopedText)
                .contains("Test Branch Two", "Closing balance", "100", "One hundred Uganda shillings only");
        assertThat(scopedText)
                .doesNotContain(
                        "RS99999990", "Sale voided", "Payment reversed", "Credit sales before the system started");
        JsonNode scopedScreen = api.call(
                        HttpMethod.GET,
                        "/customers/" + customer + "/statement?" + range,
                        null,
                        DOCS,
                        br2.toString(),
                        Map.of())
                .getBody();
        assertThat(scopedScreen.get("closing_minor").asLong()).isEqualTo(100);

        // The lines are the sales the caller may read, so the document needs that permission as well as the document
        // one.
        ResponseEntity<byte[]> noSales = http.exchange(
                "/api/v1/retail/customers/" + customer + "/statement/pdf?" + range,
                HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(api.headers("retail.document.issue", "*", Map.of())),
                byte[].class);
        assertThat(noSales.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    UUID creditSale(UUID customer, UUID branch, String date, String qty) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", "credit");
        body.put("customer_id", customer);
        body.put("sale_date", date);
        body.put("lines", List.of(Map.of("product_id", product, "qty", qty)));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/sales", body, DOCS, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    // ---- escaping and other scripts --------------------------------------------------------------

    static final String AWKWARD = "Amélie & Sons <b>\"Ltd\"</b> Привет";

    /** Markup characters print as typed, Greek and Cyrillic print, and a script the font lacks prints as "?" and never breaks the PDF. */
    @Test
    void awkwardNamesPrintAsTypedOnEveryDocument() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("payment_method", "credit");
        body.put("buyer_name", AWKWARD + " 日本語");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        ResponseEntity<JsonNode> sold =
                api.postKeyed("/sales", body, DOCS, "*", UUID.randomUUID().toString());
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID s = RetailTestSupport.id(sold);
        for (String type : List.of("invoice", "delivery_note")) {
            String text = text(pdf(issued(s, type)));
            assertThat(text).as(type).contains(AWKWARD).contains("???").doesNotContain("<script");
        }
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("branch_id", hq);
        quote.put("buyer_name", AWKWARD);
        quote.put("notes", "<i>Pay & collect</i>");
        quote.put("valid_until", "2026-11-08");
        quote.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        ResponseEntity<JsonNode> q = api.postKeyed("/quotations", quote, DOCS, "*", "awkward-quote");
        assertThat(q.getStatusCode()).as("%s", q.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(text(pdf(q.getBody()))).contains(AWKWARD, "<i>Pay & collect</i>");
    }

    // ---- an invoice after a part payment ---------------------------------------------------------

    /** The invoice of a credit sale says what was paid when it was issued, and what was still owed. */
    @Test
    void anInvoiceAfterAPartPaymentSaysWhatWasPaidAndWhatIsOwed() throws Exception {
        UUID s = sale(hq, "credit", "5");
        assertThat(api.postKeyed(
                                "/sales/" + s + "/payments",
                                Map.of("amount_minor", 200, "method", "cash"),
                                DOCS,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        String text = text(pdf(issued(s, "invoice")));
        assertThat(text)
                .contains("Part paid when issued.", "Paid when issued: 200", "Balance due when issued: 300 UGX");
    }

    // ---- an invoice after a write-off (issue #354) ------------------------------------------------

    // JUSTIFICATION-A3: two new regression tests for issue #354, placed beside the part-payment invoice test they
    // mirror.
    /**
     * ADR-043: a document may still be issued for a written-off sale (the goods left and the sale stands), but the invoice
     * says what was paid and what was written off, never "Paid in full", and the delivery note says nothing about payment.
     */
    @Test
    void anInvoiceAfterAWriteOffSaysWhatWasWrittenOffNotPaidInFull() throws Exception {
        UUID s = sale(hq, "credit", "5");
        assertThat(api.postKeyed(
                                "/sales/" + s + "/payments",
                                Map.of("amount_minor", 200, "method", "cash"),
                                DOCS,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        writeOff(s);
        String invoice = text(pdf(issued(s, "invoice")));
        assertThat(invoice)
                .contains(
                        "Unpaid part written off as a loss. Nothing is owed.",
                        "Paid: 200.",
                        "Written off on {DATE}: 300 UGX")
                .doesNotContain("Paid in full", "Balance due");
        String note = text(pdf(issued(s, "delivery_note")));
        assertThat(note).doesNotContain("Paid in full", "Paid when issued");
    }

    /** With nothing paid, the invoice of a written-off sale says so. */
    @Test
    void anInvoiceAfterAWriteOffOfAnUnpaidSaleSaysNothingWasPaid() throws Exception {
        UUID s = sale(hq, "credit", "2");
        writeOff(s);
        assertThat(text(pdf(issued(s, "invoice"))))
                .contains("Nothing was paid.", "Written off on {DATE}: 200 UGX")
                .doesNotContain("Paid in full");
    }

    void writeOff(UUID s) {
        ResponseEntity<JsonNode> w = api.postKeyed(
                "/sales/" + s + "/write-off",
                Map.of("reason", "Buyer cannot be found"),
                DOCS + ",retail.credit.write_off",
                "*",
                UUID.randomUUID().toString());
        assertThat(w.getStatusCode()).as("%s", w.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    // ---- races -------------------------------------------------------------------------------------

    /** A void that has not committed yet finishes first: the invoice of that moment is refused, not numbered for a voided sale. */
    @Test
    void anInvoiceWaitsForAVoidThatIsInFlightAndThenRefuses() throws Exception {
        UUID s = sale(hq, "cash", "1");
        try (java.sql.Connection voiding = TestDatabase.ownerDataSource().getConnection()) {
            voiding.setAutoCommit(false);
            try (java.sql.PreparedStatement st = voiding.prepareStatement(
                    "UPDATE retail_sales SET status = 'voided', voided_at = now(), voided_by = gen_random_uuid(),"
                            + " void_reason = 'Race test', version = version + 1 WHERE id = ?")) {
                st.setObject(1, s);
                assertThat(st.executeUpdate()).isEqualTo(1);
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            Future<ResponseEntity<JsonNode>> issuing = pool.submit(
                    () -> issue(s, "invoice", DOCS, "*", UUID.randomUUID().toString()));
            Thread.sleep(700);
            assertThat(issuing.isDone()).as("the invoice waits for the void").isFalse();
            voiding.commit();
            ResponseEntity<JsonNode> r = issuing.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(code(r)).isEqualTo("sale_voided");
        }
        assertThat(count("retail_documents")).isZero();
        assertThat(count("retail_document_series") == 0
                        || TestDatabase.owner()
                                        .sql(
                                                "SELECT last_no FROM retail_document_series WHERE tenant_id = ? AND type = 'invoice'")
                                        .param(t.tenantId())
                                        .query(Integer.class)
                                        .single()
                                == 0)
                .as("no number was taken")
                .isTrue();
    }

    /** Two first changes of one prefix at once both succeed: the second updates what the first wrote. */
    @Test
    void twoFirstChangesOfAPrefixDoNotCollide() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
        for (String prefix : List.of("AAA", "BBB", "CCC", "DDD")) {
            calls.add(pool.submit(() -> {
                start.await();
                return api.call(
                        HttpMethod.PUT,
                        "/document-series/invoice",
                        Map.of("prefix", prefix),
                        DOCS + "," + SETTINGS,
                        "*",
                        Map.of());
            }));
        }
        start.countDown();
        for (Future<ResponseEntity<JsonNode>> f : calls) {
            assertThat(f.get(30, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        pool.shutdown();
        assertThat(count("retail_document_prefixes")).isEqualTo(1);
    }

    // ---- the credit note PDF (the return numbers it) -------------------------------------------------

    static final String RET = DOCS + ",retail.sale.return";

    JsonNode takeBack(UUID sale, int lineIndex, String qty, UUID unit, String refund) {
        UUID saleLine = UUID.fromString(api.get("/sales/" + sale, DOCS)
                .getBody()
                .get("lines")
                .get(lineIndex)
                .get("id")
                .asString());
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("sale_line_id", saleLine);
        line.put("qty", qty);
        if (unit != null) {
            line.put("unit_id", unit);
        }
        line.put("reason", "damaged");
        line.put("disposition", "write_off");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(line));
        body.put("refund_method", refund);
        if (refund.equals("account")) {
            body.put("money_method", "cash");
        }
        body.put("note", "Two arrived wet");
        ResponseEntity<JsonNode> r = api.postKeyed(
                "/sales/" + sale + "/returns", body, RET, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("sale_return");
    }

    ResponseEntity<JsonNode> creditNote(UUID returnId, String perms, String branches) {
        return api.call(HttpMethod.POST, "/returns/" + returnId + "/document", Map.of(), perms, branches, Map.of());
    }

    /** The credit note has the return's own number, totals as the return recorded, stored once, and the invoice it was sold on. */
    @Test
    void aCreditNoteCarriesTheReturnsOwnNumberAndTotals() throws Exception {
        capability("returns", true);
        UUID s = sale(hq, "cash", "5");
        JsonNode invoice = issued(s, "invoice");
        JsonNode ret = takeBack(s, 0, "2", null, "cash");
        UUID id = UUID.fromString(ret.get("id").asString());
        ResponseEntity<JsonNode> first = creditNote(id, RET, "*");
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("credit_note_no").asString())
                .isEqualTo(ret.get("credit_note_no").asString());
        assertThat(first.getBody().get("status").asString()).isEqualTo("issued");
        assertThat(first.getBody().get("created").asBoolean()).isTrue();
        assertThat(first.getHeaders().getCacheControl()).isEqualTo("no-store");
        byte[] pdf = pdf(first.getBody(), RET);
        String text = text(pdf);
        assertThat(text)
                .contains(
                        "CREDIT NOTE",
                        ret.get("credit_note_no").asString(),
                        "Against invoice",
                        invoice.get("number").asString())
                .contains("Test Product DC-1", "Damaged", "Cash", "Two arrived wet")
                .contains("Two hundred Uganda shillings only", "Received by Cash customer");
        assertThat(ret.get("total_minor").asLong()).isEqualTo(200);
        assertThat(pages(pdf)).isEqualTo(1);
        golden("retail_credit_note", pdf);
        // Stored once: the same request finds it.
        ResponseEntity<JsonNode> again = creditNote(id, RET, "*");
        assertThat(again.getBody().get("pdf_document_id").asString())
                .isEqualTo(first.getBody().get("pdf_document_id").asString());
        assertThat(again.getBody().get("created").asBoolean()).isFalse();
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM documents WHERE tenant_id = ? AND subject_type = 'retail.credit_note'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // No second numbered series: nothing of this type is in the series tables.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_document_series WHERE tenant_id = ? AND type = 'credit_note'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(api.get("/document-series", DOCS).getBody().toString()).doesNotContain("credit_note");
    }

    /** A return of a pack prints the pack and the base units, with the credit the return recorded. */
    @Test
    void aCreditNoteShowsPackUnitsAsReturned() throws Exception {
        capability("returns", true);
        capability("units", true);
        UUID carton = api.unit("carton");
        assertThat(api.post("/products/" + product + "/units", Map.of("unit_id", carton, "factor", "12"), DOCS)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("payment_method", "cash");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "3", "unit_id", carton)));
        ResponseEntity<JsonNode> sold =
                api.postKeyed("/sales", body, DOCS, "*", UUID.randomUUID().toString());
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode ret = takeBack(RetailTestSupport.id(sold), 0, "1", carton, "cash");
        assertThat(ret.get("total_minor").asLong())
                .as("one carton of the three at 1,200")
                .isEqualTo(1_200);
        JsonNode doc =
                creditNote(UUID.fromString(ret.get("id").asString()), RET, "*").getBody();
        String text = text(pdf(doc, RET));
        assertThat(text)
                .contains("1 carton", "= 12 u-dc-1", "1,200", "One thousand, two hundred Uganda shillings only");
    }

    /** Taken off a credit buyer's balance, and a void keeps the number and is served only with the VOID banner. */
    @Test
    void aVoidedReturnsCreditNoteIsServedOnlyWithAVoidBanner() throws Exception {
        capability("returns", true);
        UUID s = sale(hq, "credit", "5");
        JsonNode ret = takeBack(s, 0, "1", null, "account");
        UUID id = UUID.fromString(ret.get("id").asString());
        JsonNode clean = creditNote(id, RET, "*").getBody();
        String cleanPdf = clean.get("pdf_document_id").asString();
        assertThat(text(pdf(clean, RET))).doesNotContain("VOID");
        ResponseEntity<JsonNode> voided = api.postKeyed(
                "/returns/" + id + "/void",
                Map.of("reason", "Entered by mistake"),
                RET,
                "*",
                UUID.randomUUID().toString());
        assertThat(voided.getStatusCode()).as("%s", voided.getBody()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> marked = creditNote(id, RET, "*");
        assertThat(marked.getBody().get("status").asString()).isEqualTo("void");
        assertThat(marked.getBody().get("credit_note_no").asString())
                .isEqualTo(ret.get("credit_note_no").asString());
        String voidPdf = marked.getBody().get("pdf_document_id").asString();
        assertThat(voidPdf).isNotEqualTo(cleanPdf);
        byte[] bytes = pdf(marked.getBody(), RET);
        assertThat(text(bytes))
                .contains("VOID: voided on", ret.get("credit_note_no").asString());
        sample("retail_credit_note_void", bytes);
        assertThat(creditNote(id, RET, "*").getBody().get("pdf_document_id").asString())
                .as("one void version")
                .isEqualTo(voidPdf);
        ResponseEntity<JsonNode> link = http.exchange(
                "/api/v1/documents/" + cleanPdf + "/download-url",
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(api.headers(RET, "*", Map.of())),
                JsonNode.class);
        assertThat(link.getStatusCode())
                .as("the copy issued before the void is not served")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** Scope and permissions: the return's branch, both permissions, both capabilities. */
    @Test
    void aCreditNoteFollowsTheScopeThePermissionsAndTheCapabilities() {
        capability("returns", true);
        UUID s = sale(hq, "cash", "3");
        UUID id = UUID.fromString(takeBack(s, 0, "1", null, "cash").get("id").asString());
        assertThat(creditNote(id, RET, br2.toString()).getStatusCode())
                .as("another branch's return")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(creditNote(id, DOCS, "*").getStatusCode())
                .as("no permission to read returns")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(creditNote(id, ADMIN + ",retail.sale.return", "*").getStatusCode())
                .as("no document permission")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(creditNote(UUID.randomUUID(), RET, "*").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(creditNote(id, RET, "*").getStatusCode()).isEqualTo(HttpStatus.OK);
        capability("returns", false);
        ResponseEntity<JsonNode> off = creditNote(id, RET, "*");
        assertThat(off.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(off)).isEqualTo("capability_not_enabled");
        capability("returns", true);
        capability("documents", false);
        assertThat(code(creditNote(id, RET, "*"))).isEqualTo("capability_not_enabled");
    }

    /** The statement prints the return and its void in words, and the running balance follows them. */
    @Test
    void theStatementPrintsReturnsAndTheirVoidsReadably() throws Exception {
        capability("returns", true);
        capability("customer_accounts", true);
        UUID customer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 03"), DOCS));
        UUID s = creditSale(customer, hq, "2026-10-02", "5");
        JsonNode ret = takeBack(s, 0, "2", null, "account");
        String range = "?from=2026-10-01&to=2028-06-30";
        String before = text(statementPdf(customer, range, DOCS));
        assertThat(before)
                .contains(
                        "Goods returned (credit note)",
                        ret.get("credit_note_no").asString(),
                        "Closing balance",
                        "300");
        assertThat(before).doesNotContain("Credit note voided");
        assertThat(api.postKeyed(
                                "/returns/" + ret.get("id").asString() + "/void",
                                Map.of("reason", "Entered by mistake"),
                                RET,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        String after = text(statementPdf(customer, range, DOCS));
        assertThat(after).contains("Goods returned (credit note)", "Credit note voided", "Closing balance", "500");
        JsonNode screen =
                api.get("/customers/" + customer + "/statement" + range, DOCS).getBody();
        List<String> kinds = new ArrayList<>();
        for (JsonNode l : screen.get("lines")) {
            kinds.add(l.get("kind").asString());
        }
        assertThat(kinds).contains("return", "return_void");
        assertThat(screen.get("closing_minor").asLong()).isEqualTo(500);
    }

    // JUSTIFICATION-A3: the second review follow-up cases of the same new test class (inert markup on all five PDFs,
    // other tenant,
    // the facts of a credit note after a void, a void that lands while a PDF is being recorded).

    static final String HOSTILE = "<script>alert(1)</script> & < > \"q\" 'a'";

    /** A buyer named with a literal script tag, ampersand, angle brackets and quotes prints exactly as typed on all five PDFs. */
    @Test
    void markupInANamePrintsInertOnTheInvoiceQuotationDeliveryNoteCreditNoteAndStatement() throws Exception {
        capability("returns", true);
        capability("customer_accounts", true);
        UUID customer = RetailTestSupport.id(
                api.post("/customers", Map.of("name", HOSTILE, "contact", "<b>+256700000009</b>"), DOCS));
        UUID s = creditSale(customer, hq, "2026-10-02", "4");
        for (String type : List.of("invoice", "delivery_note")) {
            assertThat(text(pdf(issued(s, type)))).as(type).contains(HOSTILE);
        }
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("branch_id", hq);
        quote.put("customer_id", customer);
        quote.put("valid_until", "2026-11-08");
        quote.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        ResponseEntity<JsonNode> q = api.postKeyed("/quotations", quote, DOCS, "*", "hostile-quote");
        assertThat(q.getStatusCode()).as("%s", q.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(text(pdf(q.getBody()))).as("quotation").contains(HOSTILE, "<b>+256700000009</b>");
        JsonNode ret = takeBack(s, 0, "1", null, "account");
        JsonNode note =
                creditNote(UUID.fromString(ret.get("id").asString()), RET, "*").getBody();
        assertThat(text(pdf(note, RET))).as("credit note").contains(HOSTILE, "<b>+256700000009</b>");
        String statement = text(statementPdf(customer, "?from=2026-10-01&to=2028-06-30", DOCS));
        assertThat(statement).as("statement").contains(HOSTILE, "<b>+256700000009</b>");
    }

    /** Another tenant cannot see a credit note of this one: a plain 404, not a hint that it exists. */
    @Test
    void anotherTenantGetsA404ForACreditNote() {
        capability("returns", true);
        UUID s = sale(hq, "cash", "2");
        UUID id = UUID.fromString(takeBack(s, 0, "1", null, "cash").get("id").asString());
        TestDatabase.Fixture other = TestDatabase.tenant("retail-docs-cn-other", false, true);
        RetailTestSupport theirs = new RetailTestSupport(http, other);
        for (String key : List.of("documents", "returns")) {
            String etag =
                    theirs.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
            assertThat(theirs.call(
                                    HttpMethod.PUT,
                                    "/settings/capabilities",
                                    Map.of(key, true),
                                    SETTINGS,
                                    "*",
                                    etag == null ? Map.of() : Map.of("If-Match", etag))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<JsonNode> r =
                theirs.call(HttpMethod.POST, "/returns/" + id + "/document", Map.of(), RET, "*", Map.of());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(code(r)).isEqualTo("not_found");
        assertThat(creditNote(id, RET, "*").getStatusCode())
                .as("and it is still ours")
                .isEqualTo(HttpStatus.OK);
    }

    /** The copy marked void keeps the issuer, the issue day and the buyer of the original, and says who voided it and when. */
    @Test
    void theVoidCreditNoteKeepsTheOriginalIssuerDayAndBuyer() throws Exception {
        capability("returns", true);
        UUID customer = RetailTestSupport.id(api.post("/customers", Map.of("name", "Test Buyer 04"), DOCS));
        UUID s = creditSale(customer, hq, "2026-10-02", "5");
        JsonNode ret = takeBack(s, 0, "1", null, "account");
        UUID id = UUID.fromString(ret.get("id").asString());
        assertThat(creditNote(id, RET, "*").getStatusCode()).isEqualTo(HttpStatus.OK);
        // After the issue the buyer is renamed and a different user asks: the void copy must not take either.
        TestDatabase.owner()
                .sql("UPDATE retail_customers SET name = 'Renamed Buyer' WHERE id = ?")
                .param(customer)
                .update();
        RetailTestSupport asker = new RetailTestSupport(http, t);
        TestDatabase.owner()
                .sql("INSERT INTO users (id, tenant_id, kind, full_name, email, status)"
                        + " VALUES (?, ?, 'staff', 'Someone Else', 'someone.else@example.test', 'active')")
                .params(asker.userId(), t.tenantId())
                .update();
        assertThat(api.postKeyed(
                                "/returns/" + id + "/void",
                                Map.of("reason", "Entered by mistake"),
                                RET,
                                "*",
                                UUID.randomUUID().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> marked =
                asker.call(HttpMethod.POST, "/returns/" + id + "/document", Map.of(), RET, "*", Map.of());
        assertThat(marked.getStatusCode()).as("%s", marked.getBody()).isEqualTo(HttpStatus.OK);
        String text = text(pdf(marked.getBody(), RET));
        assertThat(text).contains("VOID: voided on", "Entered by mistake", "Test Buyer 04", "Issued by Test Cashier");
        assertThat(text).doesNotContain("Renamed Buyer", "Issued by Someone Else");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_credit_note_issues WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /** A void that lands while the PDF is being recorded is seen under the lock: the copy stored and returned is the void one. */
    @Test
    void aVoidLandingWhileAPdfIsBeingRecordedYieldsTheVoidCopy() throws Exception {
        UUID s = sale(hq, "cash", "1");
        Mockito.doThrow(new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE, "documents_busy", "Documents busy", "Try again."))
                .when(documents)
                .renderPdf(ArgumentMatchers.any(), ArgumentMatchers.any());
        ResponseEntity<JsonNode> failed = issue(s, "invoice", DOCS, "*", "race-render-1");
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        Mockito.reset(documents);
        JsonNode doc = api.get("/sales/" + s + "/documents", DOCS)
                .getBody()
                .get("items")
                .get(0);
        try (java.sql.Connection voiding = TestDatabase.ownerDataSource().getConnection()) {
            voiding.setAutoCommit(false);
            try (java.sql.PreparedStatement st = voiding.prepareStatement(
                    "UPDATE retail_sales SET status = 'voided', voided_at = now(), voided_by = gen_random_uuid(),"
                            + " void_reason = 'Race test', version = version + 1 WHERE id = ?")) {
                st.setObject(1, s);
                assertThat(st.executeUpdate()).isEqualTo(1);
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            Future<ResponseEntity<JsonNode>> rendering =
                    pool.submit(() -> api.post("/documents/" + doc.get("id").asString() + "/pdf", Map.of(), DOCS));
            Thread.sleep(700);
            assertThat(rendering.isDone()).as("recording waits for the void").isFalse();
            voiding.commit();
            ResponseEntity<JsonNode> r = rendering.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(r.getBody().get("status").asString()).isEqualTo("void");
            assertThat(text(pdf(r.getBody()))).contains("VOID: its sale was voided");
        }
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM documents WHERE tenant_id = ? AND doc_number = 'INV-HQ-000001'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .as("no clean copy was stored")
                .isZero();
    }
}
