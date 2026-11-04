package com.rincoltech.bms.retail;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static com.rincoltech.bms.retail.cashbook.CashbookTestSupport.ALL;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.cashbook.CashbookTestSupport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/**
 * FR-RET-89 (issue #819, ADR-124): a photo of the paper receipt on a sale, a delivery, a usage or damage entry and an
 * expense, through the item photo's checks and storage. For each record: the photo is added and read by a person who sees
 * the record, refused to another business and to a person without access, and a file of the wrong type or too large is
 * refused. Every file, name and amount is fabricated.
 */
class RetailReceiptPhotosIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;
    CashbookTestSupport cb;
    UUID hq;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-receipts", false, true);
        cb = new CashbookTestSupport(http, t);
        // One person sends the photos and records the entries: an expense names only a receipt its recorder sent.
        api = cb.api;
        hq = t.headOffice();
        product = api.product("RCPT-1", 1_000, 2_000);
        api.stockUp(hq, product, "20");
    }

    static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    ResponseEntity<JsonNode> upload(
            RetailTestSupport as, String path, byte[] bytes, String permissions, String branches) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "receipt.png";
            }
        });
        HttpHeaders h = as.headers(permissions, branches, Map.of());
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange(RetailTestSupport.BASE + path, HttpMethod.POST, new HttpEntity<>(form, h), JsonNode.class);
    }

    ResponseEntity<byte[]> image(RetailTestSupport as, String path, String permissions, String branches) {
        return http.exchange(
                RetailTestSupport.BASE + path,
                HttpMethod.GET,
                new HttpEntity<>(as.headers(permissions, branches, Map.of())),
                byte[].class);
    }

    UUID sale() {
        ResponseEntity<JsonNode> r = api.sell(hq, "cash", product, "1");
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    /** A restock made by the same person as the reads below: without retail.profit.read one reads only one's own (ADR-055). */
    UUID delivery() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", product);
        line.put("cost_minor", 1_000);
        line.put("sell_minor", 2_000);
        line.put("qty_by_branch", List.of(Map.of("branch_id", hq, "qty", "3")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payment_method", "cash");
        body.put("lines", List.of(line));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/purchases", body, ALL, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    UUID usage() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("kind", "damaged");
        body.put("reason", "Test water damage");
        body.put("lines", List.of(Map.of("product_id", product, "qty", "1")));
        ResponseEntity<JsonNode> r =
                api.postKeyed("/usage", body, ADMIN, "*", UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    UUID[] item;

    /** The expense category and item, made once per test: a second category of the same name is refused. */
    UUID[] expenseItem() {
        if (item == null) {
            item = cb.categoryWithItem("Test Transport", "Test Boda", false);
        }
        return item;
    }

    UUID expense() {
        UUID[] item = expenseItem();
        ResponseEntity<JsonNode> r = cb.expense(hq, item[0], item[1], 5_000, null);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return RetailTestSupport.id(r);
    }

    /** The shared checks of one record: added, read, kept to the business and to whoever may see the record. */
    void addedAndReadOnlyByWhoeverSeesTheRecord(String path, String adds, String reads) throws IOException {
        ResponseEntity<JsonNode> added = upload(api, path, png(2400, 1600), adds, "*");
        assertThat(added.getStatusCode()).as("%s", added.getBody()).isEqualTo(HttpStatus.CREATED);
        String photo = added.getBody().get("photo_id").asString();

        // Stored as an item photo is: under the tenant's own prefix, as an upload of the record's subject type.
        assertThat(TestDatabase.owner()
                        .sql("SELECT object_key FROM documents WHERE id = ?::uuid")
                        .param(photo)
                        .query(String.class)
                        .single())
                .startsWith("tenants/" + t.tenantId() + "/upload/");

        // A person who sees the record sees the photo, scaled to 1200 px as an item photo is, kept privately.
        ResponseEntity<byte[]> seen = image(api, path, reads, "*");
        assertThat(seen.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(seen.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(seen.getHeaders().getCacheControl()).contains("private");
        assertThat(ImageIO.read(new ByteArrayInputStream(seen.getBody())).getWidth())
                .isEqualTo(1200);

        // Another business never reads or adds it.
        RetailTestSupport stranger = new RetailTestSupport(http, TestDatabase.tenant("retail-receipts-b", false, true));
        assertThat(image(stranger, path, ALL, "*").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(upload(stranger, path, png(100, 100), ALL, "*").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // A person without the record's read permission is refused; one scoped to another shop does not find it.
        assertThat(image(api, path, "retail.catalogue.manage", "*").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(image(api, path, reads, t.secondBranch().toString()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(upload(api, path, png(100, 100), adds, t.secondBranch().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The generic document route applies the same rule: the photo of a record out of reach is not found.
        assertThat(http.exchange(
                                "/api/v1/documents/" + photo,
                                HttpMethod.GET,
                                new HttpEntity<>(
                                        api.headers(reads, t.secondBranch().toString(), Map.of())),
                                JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // A file that is not a picture, an SVG or one over 5 MB is refused and nothing is stored.
        long before = documents(path);
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(upload(api, path, svg, adds, "*").getStatusCode().is4xxClientError())
                .isTrue();
        assertThat(upload(api, path, "not an image".getBytes(StandardCharsets.UTF_8), adds, "*")
                        .getStatusCode()
                        .is4xxClientError())
                .isTrue();
        assertThat(upload(api, path, new byte[5 * 1024 * 1024 + 1], adds, "*")
                        .getStatusCode()
                        .is4xxClientError())
                .isTrue();
        assertThat(documents(path)).isEqualTo(before);

        // A record that does not exist takes no photo.
        String missing =
                path.replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", UUID.randomUUID().toString());
        assertThat(upload(api, missing, png(100, 100), adds, "*").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    long documents(String path) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM documents WHERE tenant_id = ? AND subject_type LIKE 'retail.%receipt%'")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    @Test
    void frRet89ASaleTakesAReceiptPhotoThatWhoeverSeesTheSaleSees() throws IOException {
        UUID sale = sale();
        assertThat(api.get("/sales/" + sale, ADMIN)
                        .getBody()
                        .get("has_receipt_photo")
                        .asBoolean())
                .isFalse();

        addedAndReadOnlyByWhoeverSeesTheRecord("/sales/" + sale + "/receipt-photo", SALES, "retail.sale.read");

        assertThat(api.get("/sales/" + sale, ADMIN)
                        .getBody()
                        .get("has_receipt_photo")
                        .asBoolean())
                .isTrue();
        // ADR-055: another seller, who sees only their own sales, does not see this one's photo either, nor adds one.
        RetailTestSupport otherSeller = new RetailTestSupport(http, t);
        assertThat(image(otherSeller, "/sales/" + sale + "/receipt-photo", SALES, "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(upload(otherSeller, "/sales/" + sale + "/receipt-photo", png(100, 100), SALES, "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // The owner always sees it: the Owner of a business always holds the administrator role (ADR-062), whose column
        // of the chapter 8 matrix is ALL in every branch; the retail tests have no separate Owner sign-in fixture.
        RetailTestSupport owner = new RetailTestSupport(http, t);
        assertThat(image(owner, "/sales/" + sale + "/receipt-photo", ALL, "*").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // Someone who may cancel sales adds one to another person's sale.
        assertThat(upload(owner, "/sales/" + sale + "/receipt-photo", png(100, 100), ALL, "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(cb.auditPayloads("retail.sale.receipt_photo_added")).hasSize(2);

        // Review of #846: a colleague's credit sale that still owes is seen by another seller, to take a payment on it,
        // but takes no photo from them.
        UUID credit = RetailTestSupport.id(api.sell(hq, "credit", product, "1"));
        assertThat(otherSeller.get("/sales/" + credit, SALES).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(upload(otherSeller, "/sales/" + credit + "/receipt-photo", png(100, 100), SALES, "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // A voided sale takes no photo.
        assertThat(api.post("/sales/" + sale + "/void", Map.of("reason", "Test entered twice"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> voided = upload(api, "/sales/" + sale + "/receipt-photo", png(100, 100), SALES, "*");
        assertThat(voided.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(voided.getBody().get("code").asString()).isEqualTo("record_voided");
    }

    @Test
    void frRet89ADeliveryTakesAReceiptPhotoThatWhoeverReadsTheRestockSees() throws IOException {
        UUID delivery = delivery();
        addedAndReadOnlyByWhoeverSeesTheRecord(
                "/purchases/" + delivery + "/receipt-photo", "retail.purchase.create", "retail.purchase.create");

        JsonNode read = api.get("/purchases/" + delivery, ADMIN).getBody();
        assertThat(read.get("has_receipt_photo").asBoolean()).isTrue();
        assertThat(api.get("/purchases", ADMIN)
                        .getBody()
                        .get("items")
                        .get(0)
                        .get("has_receipt_photo")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void frRet89AUsageOrDamageEntryTakesAReceiptPhotoThatStockReadersSee() throws IOException {
        UUID entry = usage();
        addedAndReadOnlyByWhoeverSeesTheRecord("/usage/" + entry + "/receipt-photo", SALES, "retail.stock.read");

        JsonNode rows = api.get("/stock/movements?kind=damage", ADMIN).getBody().get("items");
        assertThat(rows.get(0).get("source_id").asString()).isEqualTo(entry.toString());
        assertThat(rows.get(0).get("has_receipt_photo").asBoolean()).isTrue();

        // An undone entry takes no more photos.
        assertThat(api.post("/usage/" + entry + "/undo", Map.of("reason", "Test wrong entry"), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(upload(api, "/usage/" + entry + "/receipt-photo", png(100, 100), SALES, "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void frRet89AnExpenseTakesAReceiptPhotoAddedLaterThatCashBookReadersSee() throws IOException {
        UUID expense = expense();
        addedAndReadOnlyByWhoeverSeesTheRecord(
                "/expenses/" + expense + "/receipt-photo", "retail.expense.record", "retail.cashbook.read");

        JsonNode listed = cb.get("/expenses", ALL).getBody().get("items").get(0);
        assertThat(listed.get("id").asString()).isEqualTo(expense.toString());
        assertThat(listed.hasNonNull("receipt_document_id")).isTrue();

        // JUSTIFICATION-A3: the review of #846 asks for the reuse case and the voided record to be tested.
        // A photo belongs to one expense: another expense naming it, here at another shop, is refused, so the photo
        // never becomes readable there.
        Map<String, Object> elsewhere = new LinkedHashMap<>();
        elsewhere.put("branch_id", t.secondBranch());
        elsewhere.put("category_id", expenseItem()[0]);
        elsewhere.put("item_id", expenseItem()[1]);
        elsewhere.put("amount_minor", 1_000);
        elsewhere.put("receipt_document_id", listed.get("receipt_document_id").asString());
        ResponseEntity<JsonNode> reused = cb.post("/expenses", elsewhere, CashbookTestSupport.CASHBOOK_ADMIN);
        assertThat(reused.getStatusCode()).as("%s", reused.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reused.getBody().get("code").asString()).isEqualTo("receipt_taken");

        // A voided expense takes no more photos.
        assertThat(cb.voidIt("/expenses/" + expense, "Entered twice", ALL).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(upload(api, "/expenses/" + expense + "/receipt-photo", png(100, 100), "retail.expense.record", "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void frRet89TheExpenseFormSendsThePhotoFirstAndTheExpenseNamesIt() throws IOException {
        ResponseEntity<JsonNode> sent =
                upload(api, "/expenses/receipt-photos", png(800, 600), "retail.expense.record", "*");
        assertThat(sent.getStatusCode()).as("%s", sent.getBody()).isEqualTo(HttpStatus.CREATED);
        String photo = sent.getBody().get("photo_id").asString();
        // Nobody reads a photo no expense names yet.
        assertThat(http.exchange(
                                "/api/v1/documents/" + photo,
                                HttpMethod.GET,
                                new HttpEntity<>(api.headers(ALL, "*", Map.of())),
                                JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        UUID[] item = expenseItem();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", hq);
        body.put("category_id", item[0]);
        body.put("item_id", item[1]);
        body.put("amount_minor", 3_000);
        body.put("receipt_document_id", photo);
        ResponseEntity<JsonNode> recorded = cb.post("/expenses", body, CashbookTestSupport.CASHBOOK_ADMIN);
        assertThat(recorded.getStatusCode()).as("%s", recorded.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(recorded.getBody().get("receipt_document_id").asString()).isEqualTo(photo);
        UUID expense = RetailTestSupport.id(recorded);
        assertThat(cb.auditPayloads("retail.expense.receipt_photo_sent")).hasSize(1);

        // JUSTIFICATION-A3: the lead's decision on the second review of #846 asks for a test of the kept copy.
        // A phone that keeps an expense whose answer was lost sends it later without the photo, under the same key; the
        // key covers the expense without its photo, so it is the same expense, never refused as a reused key.
        String key = UUID.randomUUID().toString();
        String kept = upload(api, "/expenses/receipt-photos", png(300, 200), "retail.expense.record", "*")
                .getBody()
                .get("photo_id")
                .asString();
        Map<String, Object> withPhoto = new LinkedHashMap<>(body);
        withPhoto.put("amount_minor", 4_000);
        withPhoto.put("receipt_document_id", kept);
        ResponseEntity<JsonNode> first =
                api.postKeyed("/expenses", withPhoto, CashbookTestSupport.CASHBOOK_ADMIN, "*", key);
        assertThat(first.getStatusCode()).as("%s", first.getBody()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> withoutPhoto = new LinkedHashMap<>(withPhoto);
        withoutPhoto.remove("receipt_document_id");
        ResponseEntity<JsonNode> again =
                api.postKeyed("/expenses", withoutPhoto, CashbookTestSupport.CASHBOOK_ADMIN, "*", key);
        assertThat(again.getStatusCode()).as("%s", again.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getBody().get("id").asString())
                .isEqualTo(first.getBody().get("id").asString());

        // The lead's decision on #846: one expense per photo, named only by the person who sent it; 409 otherwise.
        ResponseEntity<JsonNode> twice = cb.post("/expenses", body, CashbookTestSupport.CASHBOOK_ADMIN);
        assertThat(twice.getStatusCode()).as("%s", twice.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(twice.getBody().get("code").asString()).isEqualTo("receipt_taken");
        String mine = upload(api, "/expenses/receipt-photos", png(200, 200), "retail.expense.record", "*")
                .getBody()
                .get("photo_id")
                .asString();
        body.put("receipt_document_id", mine);
        RetailTestSupport someoneElse = new RetailTestSupport(http, t);
        ResponseEntity<JsonNode> notTheirs = someoneElse.postKeyed(
                "/expenses",
                body,
                CashbookTestSupport.CASHBOOK_ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(notTheirs.getStatusCode()).as("%s", notTheirs.getBody()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<byte[]> seen =
                image(api, "/expenses/" + expense + "/receipt-photo", "retail.cashbook.read", "*");
        assertThat(seen.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ImageIO.read(new ByteArrayInputStream(seen.getBody())).getWidth())
                .isEqualTo(800);
        assertThat(image(
                                api,
                                "/expenses/" + expense + "/receipt-photo",
                                "retail.cashbook.read",
                                t.secondBranch().toString())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        // An expense without a photo answers not found, so the screen offers to add one.
        assertThat(image(api, "/expenses/" + expense() + "/receipt-photo", "retail.cashbook.read", "*")
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
