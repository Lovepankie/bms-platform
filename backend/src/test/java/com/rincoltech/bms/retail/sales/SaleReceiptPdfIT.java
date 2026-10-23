package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Role;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Issue #439, ADR-061: the receipt a seller shares from their own phone, its PDF and the facts its caption reads. It is
 * rendered now and never stored, so it needs neither the documents capability nor {@code retail.document.issue}; a seller
 * who sees only their own figures (ADR-055) gets only their own sales' receipts, even of another seller's credit sale they
 * may read to take a payment, and only in their own branches; a storekeeper gets none; a supervisor gets every seller's at
 * the branches it supervises and an administrator everywhere. What the receipt says follows the money: the balance the
 * sale carries after payments and returns, the credit notes and the net, and no receipt at all for a voided, written-off
 * or wholly returned sale. Names and amounts are fabricated.
 */
class SaleReceiptPdfIT extends IntegrationTest {

    static final String STOREKEEPER =
            "retail.stock.read,retail.stock.transfer,retail.stocktake.commit,retail.usage.report";
    static final String SUPERVISOR = "retail.sale.read,retail.sale.all_sellers,retail.stock.read";
    static final String SETTINGS = "core.settings.manage";
    static final String MANAGER = ADMIN
            + ",retail.credit.manage,retail.credit.write_off,retail.sale.return,core.approvals.read,retail.savings.record,"
            + "retail.cashbook.read";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport admin;
    RetailTestSupport sellerA;
    RetailTestSupport sellerB;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-receipt", false, true);
        admin = new RetailTestSupport(http, t);
        sellerA = new RetailTestSupport(http, t);
        sellerB = new RetailTestSupport(http, t);
        product = admin.product("RCPT-1", 3_000, 5_000);
        admin.stockUp(t.headOffice(), product, "50");
        admin.stockUp(t.secondBranch(), product, "50");
        TestDatabase.owner()
                .sql("INSERT INTO users (id, tenant_id, kind, full_name, email, status)"
                        + " VALUES (?, ?, 'staff', 'Test Seller 01', 'test.seller01@example.test', 'active')")
                .params(sellerA.userId(), t.tenantId())
                .update();
    }

    static LocalDate today() {
        return LocalDate.now(BusinessClock.DEFAULT_ZONE);
    }

    UUID sellAs(RetailTestSupport seller, String method, UUID branch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", branch);
        body.put("payment_method", method);
        if (method.equals("credit")) {
            body.put("buyer_name", "Test Buyer 01");
        }
        body.put("lines", List.of(Map.of("product_id", product, "qty", "2")));
        return RetailTestSupport.id(
                seller.postKeyed("/sales", body, SALES, "*", UUID.randomUUID().toString()));
    }

    UUID sellAs(RetailTestSupport seller, String method) {
        return sellAs(seller, method, t.headOffice());
    }

    ResponseEntity<byte[]> receipt(RetailTestSupport who, UUID sale, String permissions, String branches) {
        return http.exchange(
                RetailTestSupport.BASE + "/sales/" + sale + "/receipt/pdf",
                HttpMethod.GET,
                new HttpEntity<>(who.headers(permissions, branches, Map.of())),
                byte[].class);
    }

    ResponseEntity<byte[]> receipt(RetailTestSupport who, UUID sale, String permissions) {
        return receipt(who, sale, permissions, "*");
    }

    ResponseEntity<JsonNode> facts(RetailTestSupport who, UUID sale, String permissions) {
        return who.get("/sales/" + sale + "/receipt", permissions);
    }

    /** The printed receipt for the screen's preview (issue #500), which must carry {@code printed}. */
    JsonNode printed(RetailTestSupport who, UUID sale, String permissions) {
        ResponseEntity<JsonNode> r = who.get("/sales/" + sale + "/receipt/printed", permissions);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("sale_id").asString()).isEqualTo(sale.toString());
        assertThat(r.getBody().has("printed")).as("%s", r.getBody()).isTrue();
        return r.getBody().get("printed");
    }

    /**
     * Every value the screen's receipt shows is on the PDF, with the label the PDF gives it (issue #500): the letterhead,
     * the number and date, the buyer, the sale, each line, the totals, the payment status and balance, the returns and the
     * net, the notes, the signature and the footer.
     */
    static void printsAsThePdf(JsonNode p, String text) {
        String cur = p.get("currency").asString();
        List<String> shown = new ArrayList<>();
        for (String key : List.of(
                "title",
                "number",
                "date",
                "business_name",
                "branch_name",
                "branch_location",
                "buyer_name",
                "buyer_contact",
                "total_words",
                "status",
                "due",
                "notes",
                "issued_on",
                "footer")) {
            if (p.has(key)) {
                shown.add(p.get(key).asString());
            }
        }
        shown.add("Sale " + p.get("sale_no").asString());
        shown.add("Sale date " + p.get("sale_date").asString());
        shown.add("Payment " + p.get("payment").asString());
        if (p.has("due_date")) {
            shown.add("Due date " + p.get("due_date").asString());
        }
        if (p.has("price_list")) {
            shown.add("Price list " + p.get("price_list").asString());
        }
        if (p.has("quotation")) {
            shown.add("Quotation " + p.get("quotation").asString());
        }
        // Each line as one row in the PDF's order: number, description, the code and price list line, quantity, pack,
        // unit price and amount, so the screen's line layout is the PDF's.
        for (JsonNode l : p.get("lines")) {
            shown.add(l.get("no").asInt() + " " + l.get("description").asString() + " "
                    + l.get("detail").asString()
                    + " " + l.get("quantity").asString()
                    + (l.has("pack") ? " " + l.get("pack").asString() : "")
                    + " " + l.get("unit_price").asString() + " "
                    + l.get("amount").asString());
            assertThat(l.get("detail").asString())
                    .isEqualTo(
                            l.has("price_list")
                                    ? l.get("code").asString() + "  |  "
                                            + l.get("price_list").asString()
                                    : l.get("code").asString());
        }
        shown.add("Total (" + cur + ") " + p.get("total").asString());
        for (JsonNode r : p.get("returns")) {
            shown.add(r.asString());
        }
        if (p.has("net")) {
            shown.add("Net after returns (" + cur + "): " + p.get("net").asString());
        }
        shown.add("Issued by " + p.get("issued_by").asString());
        // The received line is read from the model the template prints, never written by the screen.
        assertThat(p.get("received").asString())
                .isEqualTo("Received by " + p.get("buyer_name").asString());
        shown.add(p.get("received").asString());
        for (String s : shown) {
            assertThat(text).as("the PDF prints %s", s).contains(s.replaceAll("\\s+", " "));
        }
    }

    static void isPdf(ResponseEntity<byte[]> r) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(new String(r.getBody(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    static String text(ResponseEntity<byte[]> r) throws Exception {
        isPdf(r);
        // Lines of the PDF may wrap anywhere: the words are compared with every run of white space as one space.
        return RetailDocumentsIT.text(r.getBody()).replaceAll("\\s+", " ");
    }

    /** The PDF's words as printed, numbers and dates kept, for comparing with the screen's receipt (issue #500). */
    static String printedText(ResponseEntity<byte[]> r) throws Exception {
        isPdf(r);
        try (org.apache.pdfbox.pdmodel.PDDocument d = org.apache.pdfbox.Loader.loadPDF(r.getBody())) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(d).replaceAll("\\s+", " ");
        }
    }

    static void refused(ResponseEntity<?> r, String code) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        Object body = r.getBody();
        String words = body instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : String.valueOf(body);
        assertThat(words).contains(code);
    }

    long documents() {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_documents WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
    }

    void capability(String key, boolean on) {
        String etag = admin.get("/settings/capabilities", SETTINGS).getHeaders().getETag();
        ResponseEntity<JsonNode> r = admin.call(
                HttpMethod.PUT,
                "/settings/capabilities",
                Map.of(key, on),
                SETTINGS,
                "*",
                etag == null ? Map.of() : Map.of("If-Match", etag));
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.OK);
    }

    void pay(UUID sale, long amount) {
        ResponseEntity<JsonNode> r = admin.postKeyed(
                "/sales/" + sale + "/payments",
                Map.of("amount_minor", amount, "method", "cash", "paid_on", today().toString()),
                ADMIN,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode().is2xxSuccessful()).as("%s", r.getBody()).isTrue();
    }

    /** Takes back {@code qty} pieces of the sale's one line, paid back in cash or taken off the buyer's balance. */
    void takeBack(UUID sale, String qty, String refundMethod) {
        UUID line = UUID.fromString(admin.get("/sales/" + sale, ADMIN)
                .getBody()
                .get("lines")
                .get(0)
                .get("id")
                .asString());
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("sale_line_id", line);
        l.put("qty", qty);
        l.put("reason", "defective");
        l.put("disposition", "restock");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", List.of(l));
        body.put("refund_method", refundMethod);
        if (refundMethod.equals("account")) {
            body.put("money_method", "cash");
        }
        ResponseEntity<JsonNode> r = admin.postKeyed(
                "/sales/" + sale + "/returns",
                body,
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    // ---- who may have it ---------------------------------------------------------------------------

    @Test
    void aSellerGetsTheReceiptOfTheirOwnSaleWithTheDocumentsCapabilityOffAndNothingIsStored() throws Exception {
        UUID sale = sellAs(sellerA, "cash");
        String text = text(receipt(sellerA, sale, SALES));
        assertThat(text).contains("RECEIPT").contains("Paid in full by Cash.").contains("10,000");
        assertThat(text).doesNotContain("AFTER RETURNS");
        assertThat(documents()).isZero();
        JsonNode f = facts(sellerA, sale, SALES).getBody();
        assertThat(f.get("receipt_no").asString())
                .isEqualTo(sellerA.get("/sales/" + sale, SALES)
                        .getBody()
                        .get("sale_no")
                        .asString());
        assertThat(f.get("paid_minor").asLong()).isEqualTo(10_000);
        assertThat(f.get("balance_minor").asLong()).isZero();
        assertThat(f.get("returned_minor").asLong()).isZero();
        assertThat(f.get("buyer_name")).isNull();
        // The facts stay a cheap read: the printed receipt is its own request (issue #500).
        assertThat(f.has("printed")).isFalse();
        JsonNode p = printed(sellerA, sale, SALES);
        assertThat(p.get("title").asString()).isEqualTo("RECEIPT");
        assertThat(p.get("number").asString()).isEqualTo(f.get("receipt_no").asString());
        assertThat(p.get("buyer_name").asString()).isEqualTo("Cash customer");
        assertThat(p.get("payment").asString()).isEqualTo("Cash");
        assertThat(p.get("total").asString()).isEqualTo("10,000");
        assertThat(p.get("status").asString()).isEqualTo("Paid in full by Cash.");
        assertThat(p.get("issued_by").asString()).isEqualTo("Test Seller 01");
        assertThat(p.has("due_date")).isFalse();
        assertThat(p.has("due")).isFalse();
        assertThat(p.has("logo_url")).isFalse();
        assertThat(p.get("returns")).isEmpty();
        assertThat(p.has("net")).isFalse();
        JsonNode line = p.get("lines").get(0);
        assertThat(line.get("no").asInt()).isEqualTo(1);
        assertThat(line.get("quantity").asString()).matches("2 \\S+");
        assertThat(line.get("unit_price").asString()).isEqualTo("5,000");
        assertThat(line.get("amount").asString()).isEqualTo("10,000");
        printsAsThePdf(p, printedText(receipt(sellerA, sale, SALES)));
    }

    /** A credit sale in packs at a price list with a due date prints, on the screen, every value its PDF prints. */
    @Test
    void theScreensReceiptOfACreditSaleInPacksAtAPriceListWithADueDateMatchesThePdf() throws Exception {
        capability("units", true);
        capability("price_lists", true);
        String setup = ADMIN + ",retail.pricelist.manage,retail.pricelist.use";
        UUID carton = admin.unit("carton");
        assertThat(admin.post("/products/" + product + "/units", Map.of("unit_id", carton, "factor", "12"), setup)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        UUID list = RetailTestSupport.id(admin.post("/price-lists", Map.of("name", "Wholesale"), setup));
        assertThat(admin.post(
                                "/price-lists/" + list + "/items",
                                Map.of("product_id", product, "unit_id", carton, "price_minor", 50_000),
                                setup)
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        admin.stockUp(t.headOffice(), product, "50");
        LocalDate due = today().plusDays(30);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "credit");
        body.put("buyer_name", "Test Buyer 01");
        body.put("due_date", due.toString());
        body.put("price_list_id", list);
        body.put("lines", List.of(Map.of("product_id", product, "qty", "2", "unit_id", carton)));
        ResponseEntity<JsonNode> sold = sellerA.postKeyed(
                "/sales",
                body,
                SALES + ",retail.pricelist.use",
                "*",
                UUID.randomUUID().toString());
        assertThat(sold.getStatusCode()).as("%s", sold.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID sale = RetailTestSupport.id(sold);
        pay(sale, 40_000);

        JsonNode p = printed(sellerA, sale, SALES);
        assertThat(p.get("payment").asString()).isEqualTo("Credit");
        assertThat(p.get("due_date").asString())
                .isEqualTo(due.format(
                        java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)));
        assertThat(p.get("price_list").asString()).isEqualTo("Wholesale");
        assertThat(p.get("buyer_name").asString()).isEqualTo("Test Buyer 01");
        assertThat(p.get("total").asString()).isEqualTo("100,000");
        assertThat(p.get("status").asString()).isEqualTo("Part paid.");
        assertThat(p.get("due").asString()).startsWith("Paid so far: 40,000.").endsWith("60,000 UGX");
        JsonNode line = p.get("lines").get(0);
        assertThat(line.get("quantity").asString()).isEqualTo("2 carton");
        assertThat(line.get("pack").asString()).startsWith("= 24 ");
        assertThat(line.get("unit_price").asString()).isEqualTo("50,000");
        assertThat(line.get("amount").asString()).isEqualTo("100,000");
        printsAsThePdf(p, printedText(receipt(sellerA, sale, SALES)));
    }

    @Test
    void aSellerCannotFetchAnotherSellersReceiptNotEvenOfACreditSaleTheyMayRead() {
        UUID cash = sellAs(sellerA, "cash");
        UUID credit = sellAs(sellerA, "credit");
        assertThat(receipt(sellerB, cash, SALES).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(facts(sellerB, cash, SALES).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // The sale itself is readable to take a payment on (ADR-055); its receipt is not.
        assertThat(sellerB.get("/sales/" + credit, SALES).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(receipt(sellerB, credit, SALES).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(facts(sellerB, credit, SALES).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aSellerScopedToOneBranchCannotFetchTheirOwnSaleAtAnother() {
        UUID atSecond = sellAs(sellerA, "cash", t.secondBranch());
        ResponseEntity<byte[]> r =
                receipt(sellerA, atSecond, SALES, t.headOffice().toString());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aSaleOfAnotherBusinessIsNotFound() {
        TestDatabase.Fixture other = TestDatabase.tenant("retail-receipt-other", false, true);
        RetailTestSupport theirs = new RetailTestSupport(http, other);
        UUID item = theirs.product("RCPT-2", 3_000, 5_000);
        theirs.stockUp(other.headOffice(), item, "5");
        UUID sale = RetailTestSupport.id(theirs.sell(other.headOffice(), "cash", item, "1"));
        assertThat(receipt(admin, sale, ADMIN).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(facts(admin, sale, ADMIN).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aStorekeeperCannotFetchAReceiptAtAll() {
        UUID sale = sellAs(sellerA, "cash");
        ResponseEntity<byte[]> r = receipt(sellerB, sale, STOREKEEPER);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(new String(r.getBody(), StandardCharsets.UTF_8)).contains("permission_denied");
        assertThat(facts(sellerB, sale, STOREKEEPER).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aSupervisorAndAnAdministratorGetEverySellersReceiptSignedByTheSeller() throws Exception {
        UUID sale = sellAs(sellerA, "cash");
        assertThat(text(receipt(sellerB, sale, SUPERVISOR))).contains("Issued by Test Seller 01");
        isPdf(receipt(admin, sale, ADMIN));
    }

    @Test
    void aSupervisorOfAnotherBranchWhoSellsHereGetsOnlyTheirOwnSalesHere() {
        String email = Api.email("receipt-supervisor");
        UUID id = Api.staff(
                t, email, new Role("retail_supervisor", t.secondBranch()), new Role("retail_sales", t.headOffice()));
        Api api = Api.tenant(http, t.slug());
        String token = api.signIn(id, email, Api.PASSWORD, null).accessToken();
        UUID sale = sellAs(sellerA, "cash");
        assertThat(api.get(RetailTestSupport.BASE + "/sales/" + sale + "/receipt", token)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.setBearerAuth(token);
        ResponseEntity<byte[]> pdf = http.exchange(
                RetailTestSupport.BASE + "/sales/" + sale + "/receipt/pdf",
                HttpMethod.GET,
                new HttpEntity<>(h),
                byte[].class);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- what it says --------------------------------------------------------------------------------

    @Test
    void aPartlyPaidCreditSaleShowsWhatWasPaidAndWhatIsStillOwed() throws Exception {
        UUID sale = sellAs(sellerA, "credit");
        pay(sale, 4_000);
        String text = text(receipt(sellerA, sale, SALES));
        assertThat(text).contains("Part paid.").contains("Paid so far: 4,000").contains("6,000 UGX");
        JsonNode f = facts(sellerA, sale, SALES).getBody();
        assertThat(f.get("paid_minor").asLong()).isEqualTo(4_000);
        assertThat(f.get("balance_minor").asLong()).isEqualTo(6_000);
        assertThat(f.get("buyer_name").asString()).isEqualTo("Test Buyer 01");
    }

    @Test
    void aReturnOffTheBalanceIsPrintedWithItsCreditNoteTheNetAndTheBalanceTheSaleCarries() throws Exception {
        capability("returns", true);
        // A 10,000 credit sale, nothing paid, one piece of 5,000 taken back off the balance: 5,000 is still owed.
        UUID sale = sellAs(sellerA, "credit");
        takeBack(sale, "1", "account");
        assertThat(admin.get("/sales/" + sale, ADMIN)
                        .getBody()
                        .get("balance_minor")
                        .asLong())
                .isEqualTo(5_000);
        JsonNode f = facts(sellerA, sale, SALES).getBody();
        // The ledger books the credit as paid on the sale; the receipt shows it as a return: the buyer paid nothing.
        assertThat(f.get("paid_minor").asLong()).isZero();
        assertThat(f.get("returned_minor").asLong()).isEqualTo(5_000);
        assertThat(f.get("net_minor").asLong()).isEqualTo(5_000);
        assertThat(f.get("balance_minor").asLong()).isEqualTo(5_000);
        assertThat(f.get("credit_notes")).hasSize(1);
        JsonNode p = printed(sellerA, sale, SALES);
        assertThat(p.get("title").asString()).isEqualTo("RECEIPT AFTER RETURNS");
        assertThat(p.get("returns")).hasSize(1);
        assertThat(p.get("net").asString()).isEqualTo("5,000");
        assertThat(p.get("status").asString()).isEqualTo("Sold on credit.");
        String text = text(receipt(sellerA, sale, SALES));
        assertThat(p.get("returns").get(0).asString())
                .startsWith("Credit note " + f.get("credit_notes").get(0).asString())
                .endsWith("5,000 taken off the balance.");
        printsAsThePdf(p, printedText(receipt(sellerA, sale, SALES)));
        assertThat(text)
                .contains("RECEIPT AFTER RETURNS")
                .contains("Credit note " + f.get("credit_notes").get(0).asString())
                .contains("5,000 taken off the balance")
                .contains("Net after returns (UGX): 5,000")
                .contains("Sold on credit.")
                .contains("5,000 UGX")
                .doesNotContain("10,000 UGX")
                .doesNotContain("paid back")
                .doesNotContain("Part paid")
                .doesNotContain("Paid so far")
                .doesNotContain("Paid in full");
    }

    void aPartReturnPaidBackShowsWhatWasPaidBackAndTheNet(String method) throws Exception {
        capability("returns", true);
        UUID sale = sellAs(sellerA, method);
        takeBack(sale, "1", method);
        JsonNode f = facts(sellerA, sale, SALES).getBody();
        assertThat(f.get("paid_minor").asLong()).isEqualTo(10_000);
        assertThat(f.get("returned_minor").asLong()).isEqualTo(5_000);
        assertThat(f.get("net_minor").asLong()).isEqualTo(5_000);
        assertThat(f.get("balance_minor").asLong()).isZero();
        assertThat(text(receipt(sellerA, sale, SALES)))
                .contains("RECEIPT AFTER RETURNS")
                .contains("goods worth 5,000 taken back, 5,000 paid back.")
                .contains("Net after returns (UGX): 5,000")
                .doesNotContain("taken off the balance");
    }

    @Test
    void aPartReturnRefundedInCashShowsWhatWasPaidBackAndTheNet() throws Exception {
        aPartReturnPaidBackShowsWhatWasPaidBackAndTheNet("cash");
    }

    @Test
    void aPartReturnRefundedByMobileMoneyShowsWhatWasPaidBackAndTheNet() throws Exception {
        aPartReturnPaidBackShowsWhatWasPaidBackAndTheNet("mobile_money");
    }

    @Test
    void aSaleWhoseGoodsWereAllTakenBackHasNoReceipt() {
        capability("returns", true);
        UUID sale = sellAs(sellerA, "cash");
        takeBack(sale, "2", "cash");
        refused(receipt(sellerA, sale, SALES), "sale_fully_returned");
        refused(facts(sellerA, sale, SALES), "sale_fully_returned");
    }

    @Test
    void aWrittenOffSaleHasNoReceipt() {
        UUID sale = sellAs(sellerA, "credit");
        ResponseEntity<JsonNode> w = admin.postKeyed(
                "/sales/" + sale + "/write-off",
                Map.of("reason", "Buyer left town"),
                MANAGER,
                "*",
                UUID.randomUUID().toString());
        assertThat(w.getStatusCode()).as("%s", w.getBody()).isEqualTo(HttpStatus.CREATED);
        refused(receipt(sellerA, sale, SALES), "sale_written_off");
        refused(facts(sellerA, sale, SALES), "sale_written_off");
    }

    @Test
    void aVoidedSaleHasNoReceipt() {
        UUID sale = sellAs(sellerA, "cash");
        assertThat(admin.post("/sales/" + sale + "/void", Map.of("reason", "Wrong item sold"), ADMIN)
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();
        refused(receipt(sellerA, sale, SALES), "sale_voided");
    }
}
