package com.rincoltech.bms.retail.sales;

import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.Api;
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
        // Redirects stay off, so the test reads the 302 itself.
        return Api.withoutRetries(http)
                .exchange(
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
        assertThat(followed.getStatusCode()).as("%s", followed.getBody()).isEqualTo(HttpStatus.FOUND);
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
        // Numbers kept, so the receipt is seen to be this sale's.
        String text = SaleReceiptPdfIT.printedText(pdf);
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

        // JUSTIFICATION-A3: second review of #762, item 1: one live link per sale, given again until it expires.
        // A second call, a second device (another person), and a call after 6 minutes give the same link and write no
        // row.
        assertThat(token(makeLink(seller, sale, SALES))).isEqualTo(token);
        assertThat(token(makeLink(other, sale, SaleReceiptPdfIT.SUPERVISOR))).isEqualTo(token);
        shift(sale, "6 minutes");
        assertThat(token(makeLink(seller, sale, SALES))).isEqualTo(token);
        assertThat(links(sale)).isEqualTo(1);
        // Even with minutes left it is the sale's one link; once expired, the next share makes a new one for 90 days.
        shift(sale, "89 days 23 hours 50 minutes");
        assertThat(token(makeLink(seller, sale, SALES))).isEqualTo(token);
        shift(sale, "1 day");
        String renewed = token(makeLink(seller, sale, SALES));
        assertThat(renewed).isNotEqualTo(token);
        assertThat(links(sale)).isEqualTo(2);
        assertThat(current(sale))
                .as("the expired link is revoked as the new one is made")
                .isEqualTo(1);
        assertThat(token(makeLink(other, sale, SaleReceiptPdfIT.SUPERVISOR))).isEqualTo(renewed);
        gonePage(open(t, token));
        // A sealed token no configured data key opens (a retired key) is replaced too, never a second current link.
        TestDatabase.owner()
                .sql("DO $$ BEGIN PERFORM set_config('bms.allow_mutation', 'on', true);"
                        + " UPDATE retail_receipt_links SET token_sealed = decode('00', 'hex')"
                        + " WHERE revoked_at IS NULL AND sale_id = '" + sale + "'; END $$")
                .update();
        String resealed = token(makeLink(seller, sale, SALES));
        assertThat(resealed).isNotEqualTo(renewed);
        assertThat(current(sale)).isEqualTo(1);
        gonePage(open(t, renewed));
        // The database refuses a second current link however it is written.
        assertThatThrownBy(() -> TestDatabase.owner()
                        .sql(
                                "INSERT INTO retail_receipt_links (id, tenant_id, sale_id, token_hash, token_sealed, created_by,"
                                        + " created_at, expires_at) VALUES (?, ?, ?, ?, decode('00', 'hex'), ?, now(),"
                                        + " now() + interval '1 day')")
                        .params(UUID.randomUUID(), t.tenantId(), sale, "f".repeat(64), seller.userId())
                        .update())
                .hasMessageContaining("retail_receipt_links_one_current");
    }

    static long current(UUID sale) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_receipt_links WHERE sale_id = ? AND revoked_at IS NULL")
                .param(sale)
                .query(Long.class)
                .single();
    }

    // JUSTIFICATION-A3: third review of #762, item 3: two shares at the same moment, which the unique index exists for.
    /** Two shares of one sale at the same moment give one link and one row. */
    @Test
    void twoSharesAtOnceGiveOneLink() throws Exception {
        UUID sale = cashSale("Test Customer 45", null);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<ResponseEntity<JsonNode>> share = () -> {
                start.await();
                return makeLink(seller, sale, SALES);
            };
            var first = pool.submit(share);
            var second = pool.submit(share);
            start.countDown();
            String a = token(first.get(60, java.util.concurrent.TimeUnit.SECONDS));
            String b = token(second.get(60, java.util.concurrent.TimeUnit.SECONDS));
            assertThat(a).isEqualTo(b);
            assertThat(links(sale)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Moves the sale's links back in time, as if made that long ago. */
    static void shift(UUID sale, String ago) {
        shiftWhere("sale_id = '" + sale + "'", ago);
    }

    /**
     * The owner moves links back in time with bms.allow_mutation on, the guard's one way round (V128): the application
     * may only set revoked_at. {@code where} and {@code ago} are this test's own constants.
     */
    static void shiftWhere(String where, String ago) {
        TestDatabase.owner()
                .sql("DO $$ BEGIN PERFORM set_config('bms.allow_mutation', 'on', true);"
                        + " UPDATE retail_receipt_links SET created_at = created_at - interval '" + ago + "',"
                        + " expires_at = expires_at - interval '" + ago + "' WHERE " + where + "; END $$")
                .update();
    }

    static long links(UUID sale) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM retail_receipt_links WHERE sale_id = ?")
                .param(sale)
                .query(Long.class)
                .single();
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

        shiftWhere("tenant_id = '" + t.tenantId() + "'", "91 days");
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

    // JUSTIFICATION-A3: a new test case for review item 7 of #762; no existing case opens one link many times.
    /** One link opened too often in ten minutes is turned away with a plain page, before anything is read or drawn. */
    @Test
    void oneLinkOpenedTooOftenIsTurnedAway() {
        String token = UUID.randomUUID()
                .toString()
                .replaceAll("[^a-z2-7]", "")
                .concat("abcdefghijklmnopqrstuvwxyz")
                .substring(0, 26);
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Forwarded-For", "198.51.100." + (1 + Math.floorMod(token.hashCode(), 250)));
        for (int i = 0; i < 20; i++) {
            gonePage(http.exchange(
                    RetailTestSupport.BASE + "/receipt-links/" + token,
                    HttpMethod.GET,
                    new HttpEntity<>(h),
                    byte[].class));
        }
        // The shared client waits out a 429's Retry-After and asks again; this one reads the 429 itself.
        ResponseEntity<byte[]> turnedAway = Api.withoutRetries(http)
                .exchange(
                        RetailTestSupport.BASE + "/receipt-links/" + token,
                        HttpMethod.GET,
                        new HttpEntity<>(h),
                        byte[].class);
        assertThat(turnedAway.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(new String(turnedAway.getBody(), StandardCharsets.UTF_8)).contains("Too many tries");
    }

    // JUSTIFICATION-A3: a new test case for review item 5 of #762; no existing case reads the lookup's plan.
    /**
     * The public lookup reads one row through the unique index on the hash, bound as the app binds it (a JDBC string), so
     * a stream of wrong links never scans the table, which keeps every business's expired links.
     */
    @Test
    void theTokenLookupUsesTheUniqueIndex() throws Exception {
        try (java.sql.Connection c = TestDatabase.ownerDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (java.sql.Statement s = c.createStatement()) {
                s.execute("SET LOCAL enable_seqscan = off");
            }
            try (java.sql.PreparedStatement ps = c.prepareStatement(
                    "EXPLAIN SELECT t.slug FROM retail_receipt_links l JOIN tenants t ON t.id = l.tenant_id"
                            + " WHERE l.token_hash = ? AND l.expires_at > ? AND t.status = 'active'")) {
                ps.setString(1, "0".repeat(64));
                ps.setTimestamp(2, java.sql.Timestamp.from(Instant.now()));
                StringBuilder plan = new StringBuilder();
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        plan.append(rs.getString(1)).append('\n');
                    }
                }
                assertThat(plan.toString()).contains("retail_receipt_links_token_hash_key");
            }
            c.rollback();
        }
    }
}
