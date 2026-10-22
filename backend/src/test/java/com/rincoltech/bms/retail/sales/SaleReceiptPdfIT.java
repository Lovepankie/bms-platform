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
        String text = text(receipt(sellerA, sale, SALES));
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
