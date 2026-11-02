package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * Issue #760 (ADR-116): the customer's name on the receipt of a sale paid now, and the short link to one sale's receipt
 * that goes in a message to a typed WhatsApp number. A name typed at the sale prints as Bill to and Received by, and a
 * blank one keeps Cash customer; it makes no customer record. The link is made by whoever may share the receipt, is 26
 * characters on the short link host, opens through the platform's short link endpoint on the shop's host with no sign-in,
 * shows that one receipt as a PDF and nothing else, and a wrong, expired or foreign link shows the plain page "This
 * receipt link has expired". Only the token's hash is stored. Names, numbers and amounts are fabricated.
 */
class ReceiptLinkIT extends IntegrationTest {

    static final String GONE = "This receipt link has expired";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport admin;
    RetailTestSupport seller;
    RetailTestSupport other;
    UUID product;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-rlink", false, true);
        admin = new RetailTestSupport(http, t);
        seller = new RetailTestSupport(http, t);
        other = new RetailTestSupport(http, t);
        product = admin.product("RLNK-1", 3_000, 5_000);
        admin.stockUp(t.headOffice(), product, "50");
        TestDatabase.owner()
                .sql("INSERT INTO users (id, tenant_id, kind, full_name, email, status)"
                        + " VALUES (?, ?, 'staff', 'Test Seller 41', ?, 'active')")
                .params(seller.userId(), t.tenantId(), "test.seller41." + t.slug() + "@example.test")
                .update();
    }

    UUID cashSale(String name, String phone) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("payment_method", "cash");
        if (name != null) {
            body.put("buyer_name", name);
        }
        if (phone != null) {
            body.put("buyer_contact", phone);
        }
        body.put("lines", List.of(Map.of("product_id", product, "qty", "2")));
        return RetailTestSupport.id(
                seller.postKeyed("/sales", body, SALES, "*", UUID.randomUUID().toString()));
    }

    ResponseEntity<JsonNode> makeLink(RetailTestSupport who, UUID sale, String permissions) {
        return who.post("/sales/" + sale + "/receipt/link", null, permissions);
    }

    String token(ResponseEntity<JsonNode> made) {
        assertThat(made.getStatusCode()).as("%s", made.getBody()).isEqualTo(HttpStatus.OK);
        String url = made.getBody().get("url").asString();
        assertThat(url).matches("https://[^/]+/[a-z2-7]{26}");
        assertThat(made.getBody().get("link").asString()).isEqualTo(url.substring("https://".length()));
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /** The shop's host with no sign-in: only the business, from the host, as a customer's phone would open it. */
    ResponseEntity<byte[]> open(TestDatabase.Fixture shop, String token) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", shop.slug());
        return http.exchange(
                RetailTestSupport.BASE + "/receipt-links/" + token, HttpMethod.GET, new HttpEntity<>(h), byte[].class);
    }

    /** The platform's short link endpoint, where the one Cloudflare rule sends rincoltech.com/<token> (ADR-108). */
    ResponseEntity<String> follow(String name) {
        return http.exchange(
                "/api/v1/platform/short-links/" + name,
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                String.class);
    }

    static void gonePage(ResponseEntity<?> r) {
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML))
                .isTrue();
        Object body = r.getBody();
        String text = body instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : String.valueOf(body);
        assertThat(text).contains(GONE).doesNotContain("Test Seller").doesNotContain("RS");
    }

    @Test
    void aNameTypedAtTheSalePrintsOnTheReceiptAndABlankOneKeepsCashCustomer() throws Exception {
        long customers = TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_customers WHERE tenant_id = ?")
                .param(t.tenantId())
                .query(Long.class)
                .single();
        UUID named = cashSale("Test Customer 41", "0700 000 061");
        String text = SaleReceiptPdfIT.text(http.exchange(
                RetailTestSupport.BASE + "/sales/" + named + "/receipt/pdf",
                HttpMethod.GET,
                new HttpEntity<>(seller.headers(SALES, "*", Map.of())),
                byte[].class));
        assertThat(text).contains("Test Customer 41").contains("Received by Test Customer 41");
        assertThat(text).doesNotContain("Cash customer");
        JsonNode facts = seller.get("/sales/" + named + "/receipt", SALES).getBody();
        assertThat(facts.get("buyer_name").asString()).isEqualTo("Test Customer 41");
        assertThat(facts.get("buyer_contact").asString()).isEqualTo("0700 000 061");
        assertThat(facts.has("customer_id")).isFalse();

        UUID blank = cashSale("  ", null);
        String plain = SaleReceiptPdfIT.text(http.exchange(
                RetailTestSupport.BASE + "/sales/" + blank + "/receipt/pdf",
                HttpMethod.GET,
                new HttpEntity<>(seller.headers(SALES, "*", Map.of())),
                byte[].class));
        assertThat(plain).contains("Received by Cash customer");
        // Kept on the sale only: no customer record was made.
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM retail_customers WHERE tenant_id = ?")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isEqualTo(customers);
    }

    @Test
    void theLinkOpensThatOneReceiptWithNoSignInThroughTheShortLinkEndpoint() throws Exception {
        UUID sale = cashSale("Test Customer 42", null);
        String saleNo =
                seller.get("/sales/" + sale, SALES).getBody().get("sale_no").asString();
        ResponseEntity<JsonNode> made = makeLink(seller, sale, SALES);
        String token = token(made);
        Instant expires = Instant.parse(made.getBody().get("expires_at").asString());
        assertThat(Duration.between(Instant.now(), expires)).isBetween(Duration.ofDays(89), Duration.ofDays(91));

        // rincoltech.com/<token> reaches the platform, which sends the phone on to the shop's own host.
        ResponseEntity<String> followed = follow(token);
        assertThat(followed.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(followed.getHeaders().getLocation().toString())
                .contains(t.slug())
                .endsWith("/api/v1/retail/receipt-links/" + token);
        assertThat(follow(token.toUpperCase(java.util.Locale.ROOT)).getHeaders().getLocation())
                .isEqualTo(followed.getHeaders().getLocation());

        ResponseEntity<byte[]> pdf = open(t, token);
        assertThat(pdf.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(pdf.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(pdf.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(pdf.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline");
        String text = SaleReceiptPdfIT.text(pdf);
        assertThat(text)
                .contains(saleNo)
                .contains("Received by Test Customer 42")
                .contains("Test Seller 41");

        // Only the hash is stored, and the link is the one sale's.
        Map<String, Object> row = TestDatabase.owner()
                .sql("SELECT sale_id, token_hash, created_by FROM retail_receipt_links WHERE tenant_id = ?")
                .param(t.tenantId())
                .query()
                .singleRow();
        assertThat(row.get("sale_id")).isEqualTo(sale);
        assertThat(row.get("created_by")).isEqualTo(seller.userId());
        assertThat(String.valueOf(row.get("token_hash"))).hasSize(64).isNotEqualTo(token);

        // Each tap makes a new link to the same receipt.
        assertThat(token(makeLink(seller, sale, SALES))).isNotEqualTo(token);
    }

    @Test
    void aWrongExpiredOrForeignLinkShowsThePlainPage() {
        UUID sale = cashSale("Test Customer 43", null);
        String token = token(makeLink(seller, sale, SALES));

        gonePage(open(t, "abcdefghijklmnopqrstuvwxyz"));
        gonePage(open(t, "short"));
        gonePage(follow("abcdefghijklmnopqrstuvwxyz"));

        // Another business's host never opens this business's receipt.
        TestDatabase.Fixture elsewhere = TestDatabase.tenant("retail-rlink-b", false, true);
        gonePage(open(elsewhere, token));

        TestDatabase.owner()
                .sql("UPDATE retail_receipt_links SET created_at = created_at - interval '91 days',"
                        + " expires_at = expires_at - interval '91 days' WHERE tenant_id = ?")
                .param(t.tenantId())
                .update();
        gonePage(open(t, token));
        gonePage(follow(token));
    }

    @Test
    void onlyWhoeverMayShareTheReceiptMakesALink() {
        UUID sale = cashSale(null, null);
        // A seller who sees only their own figures never makes a link to another seller's sale.
        assertThat(makeLink(other, sale, SALES).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(makeLink(other, sale, SaleReceiptPdfIT.STOREKEEPER).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        token(makeLink(other, sale, SaleReceiptPdfIT.SUPERVISOR));
        // A link made while the sale had a receipt stops opening once the sale no longer has one.
        String token = token(makeLink(seller, sale, SALES));
        assertThat(admin.post("/sales/" + sale + "/void", Map.of("reason", "Wrong item sold"), RetailTestSupport.ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        gonePage(open(t, token));
        assertThat(makeLink(seller, sale, SALES).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
