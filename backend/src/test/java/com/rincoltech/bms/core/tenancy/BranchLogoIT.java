package com.rincoltech.bms.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import com.rincoltech.bms.testsupport.ImageFixtures;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
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
 * Issue #461, ADR-068: a shop's own logo. It follows the rules and the processing of the business logo, only the owner and
 * the administrators (core.branches.manage) set it, it is served publicly like the business logo and only to the business
 * the host resolves, and a document of that shop carries it while every other shop keeps the business logo. The images
 * are built in code; nothing here is a real logo, name or figure.
 */
class BranchLogoIT extends IntegrationTest {

    static final String ADMIN = "core.branches.read,core.branches.manage";
    static final String SETTINGS = "core.settings.read,core.settings.manage";

    static final Color BUSINESS = new Color(20, 40, 200);
    static final Color SHOP = new Color(200, 30, 30);

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("shop-logo-a", false, true);
    }

    HttpHeaders staff(TestDatabase.Fixture tenant, String permissions, String branches) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", tenant.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", branches);
        return h;
    }

    static MultiValueMap<String, Object> file(byte[] bytes, String filename) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return form;
    }

    ResponseEntity<JsonNode> upload(
            TestDatabase.Fixture tenant, UUID branch, String permissions, String branches, byte[] bytes) {
        HttpHeaders h = staff(tenant, permissions, branches);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange(
                "/api/v1/branches/" + branch + "/logo",
                HttpMethod.PUT,
                new HttpEntity<>(file(bytes, "logo.png"), h),
                JsonNode.class);
    }

    ResponseEntity<JsonNode> upload(UUID branch, byte[] bytes) {
        return upload(t, branch, ADMIN, "*", bytes);
    }

    ResponseEntity<JsonNode> remove(TestDatabase.Fixture tenant, UUID branch, String permissions) {
        return http.exchange(
                "/api/v1/branches/" + branch + "/logo",
                HttpMethod.DELETE,
                new HttpEntity<>(staff(tenant, permissions, "*")),
                JsonNode.class);
    }

    JsonNode branch(UUID id) {
        return http.exchange(
                        "/api/v1/branches/" + id,
                        HttpMethod.GET,
                        new HttpEntity<>(staff(t, ADMIN, "*")),
                        JsonNode.class)
                .getBody();
    }

    /** A public call: no token, the business from X-Tenant (as the host would give it). */
    <T> ResponseEntity<T> publicGet(String path, String slug, Class<T> type, String... extra) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", slug);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            h.add(extra[i], extra[i + 1]);
        }
        return http.exchange(URI.create(http.getRootUri() + path), HttpMethod.GET, new HttpEntity<>(h), type);
    }

    void businessLogo(Color colour) {
        HttpHeaders h = staff(t, SETTINGS, "*");
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> r = http.exchange(
                "/api/v1/settings/logo",
                HttpMethod.PUT,
                new HttpEntity<>(file(ImageFixtures.png(256, 256, colour), "logo.png"), h),
                JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    static Color centre(byte[] image) throws Exception {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image));
        return new Color(decoded.getRGB(decoded.getWidth() / 2, decoded.getHeight() / 2));
    }

    static boolean near(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed()) < 24
                && Math.abs(a.getGreen() - b.getGreen()) < 24
                && Math.abs(a.getBlue() - b.getBlue()) < 24;
    }

    // ---- Upload, replace, remove -----------------------------------------------------------------------

    @Test
    void anAdminUploadsReplacesAndRemovesAShopLogoServedLikeTheBusinessLogo() throws Exception {
        UUID shop = t.secondBranch();
        assertThat(branch(shop).get("logo_url").isNull()).isTrue();
        int version = branch(shop).get("version").asInt();

        ResponseEntity<JsonNode> up = upload(shop, ImageFixtures.png(300, 200, SHOP));
        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.OK);
        String url = up.getBody().get("logo_url").asString();
        assertThat(url).startsWith("/api/v1/branches/" + shop + "/logo?v=");
        assertThat(up.getBody().get("version").asInt()).isEqualTo(version + 1);
        String documentId = url.substring(url.indexOf("?v=") + 3);

        // The same processing as the business logo: re-encoded, scaled to 512 px at most, a core.tenant document.
        ResponseEntity<byte[]> image = publicGet(url, t.slug(), byte[].class);
        assertThat(image.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders h = image.getHeaders();
        assertThat(h.getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(h.getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=3600, public");
        assertThat(h.getFirst("Content-Security-Policy")).isEqualTo("default-src 'none'; sandbox");
        assertThat(near(centre(image.getBody()), SHOP)).isTrue();
        Map<String, Object> row = TestDatabase.owner()
                .sql("SELECT subject_type, subject_id, branch_id FROM documents WHERE id = ?::uuid")
                .param(documentId)
                .query()
                .singleRow();
        assertThat(row.get("subject_type")).isEqualTo("core.tenant");
        assertThat(row.get("subject_id")).isEqualTo(t.tenantId());
        assertThat(row.get("branch_id")).isEqualTo(shop);
        ResponseEntity<byte[]> again = publicGet(url, t.slug(), byte[].class, "If-None-Match", h.getETag());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        // Only that shop has it: the head office still has none.
        assertThat(branch(t.headOffice()).get("logo_url").isNull()).isTrue();
        assertThat(publicGet("/api/v1/branches/" + t.headOffice() + "/logo", t.slug(), JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Replacing gives a new versioned URL; the old document stays.
        ResponseEntity<JsonNode> replaced = upload(shop, ImageFixtures.jpeg(256, 256, Color.GREEN));
        assertThat(replaced.getStatusCode()).isEqualTo(HttpStatus.OK);
        String second = replaced.getBody().get("logo_url").asString();
        assertThat(second).isNotEqualTo(url);
        assertThat(publicGet(second, t.slug(), byte[].class).getHeaders().getContentType())
                .isEqualTo(MediaType.IMAGE_JPEG);

        // Removing clears it (twice is fine) and the route is a 404; the shop shows the business logo again.
        ResponseEntity<JsonNode> removed = remove(t, shop, ADMIN);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(removed.getBody().get("logo_url").isNull()).isTrue();
        assertThat(remove(t, shop, ADMIN).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicGet("/api/v1/branches/" + shop + "/logo", t.slug(), JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM documents WHERE id = ?::uuid")
                        .param(documentId)
                        .query(Long.class)
                        .single())
                .isOne();

        // Every change is audited with was and now.
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'core.branch.logo_changed'"
                                        + " AND entity_id = ?")
                        .params(t.tenantId(), shop)
                        .query(Long.class)
                        .single())
                .isEqualTo(3);
    }

    @Test
    void theLogoFollowsTheRulesOfTheBusinessLogo() {
        UUID shop = t.secondBranch();
        ResponseEntity<JsonNode> small = upload(shop, ImageFixtures.png(100, 100, SHOP));
        assertThat(small.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(small.getBody().get("code").asString()).isEqualTo("image_too_small");

        ResponseEntity<JsonNode> svg = upload(shop, ImageFixtures.svg());
        assertThat(svg.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(svg.getBody().get("code").asString()).isEqualTo("svg_not_allowed");

        ResponseEntity<JsonNode> big = upload(shop, new byte[1024 * 1024 + 1]);
        assertThat(big.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(big.getBody().get("code").asString()).isEqualTo("file_too_large");

        ResponseEntity<JsonNode> huge = upload(shop, ImageFixtures.bigCanvasPng(5000, 5000));
        assertThat(huge.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(huge.getBody().get("code").asString()).isEqualTo("image_too_large");

        assertThat(branch(shop).get("logo_url").isNull()).isTrue();
    }

    // ---- Who may set it ------------------------------------------------------------------------------

    @Test
    void onlyTheOwnerAndAdministratorsMaySetOrRemoveIt() {
        UUID shop = t.secondBranch();
        byte[] png = ImageFixtures.png(200, 200, SHOP);
        // A seller who reads branches, and a settings manager without branch management, are refused.
        assertThat(upload(t, shop, "core.branches.read,retail.sale.create", "*", png)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(t, shop, SETTINGS, "*", png).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(remove(t, shop, "core.branches.read").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // A manager of another branch only cannot see this one: a 404, and nothing is stored.
        assertThat(upload(t, shop, ADMIN, t.headOffice().toString(), png).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM documents WHERE tenant_id = ? AND subject_type = 'core.tenant'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(upload(shop, png).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aClosedBranchIsRefusedALogo() {
        UUID shop = t.secondBranch();
        HttpHeaders h = staff(t, ADMIN, "*");
        h.setIfMatch(http.exchange(
                        "/api/v1/branches/" + shop,
                        HttpMethod.GET,
                        new HttpEntity<>(staff(t, ADMIN, "*")),
                        JsonNode.class)
                .getHeaders()
                .getETag());
        h.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.exchange(
                                "/api/v1/branches/" + shop + "/close",
                                HttpMethod.POST,
                                new HttpEntity<>(Map.of("reason", "Test closing"), h),
                                JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> refused = upload(shop, ImageFixtures.png(200, 200, SHOP));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("invalid_status_transition");
        assertThat(branch(shop).get("logo_url").isNull()).isTrue();
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM documents WHERE tenant_id = ? AND subject_type = 'core.tenant'")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // ---- Between businesses ----------------------------------------------------------------------------

    @Test
    void aShopLogoOfAnotherBusinessIsANotFound() {
        TestDatabase.Fixture other = TestDatabase.tenant("shop-logo-b", false, true);
        UUID shop = t.secondBranch();
        String url = upload(shop, ImageFixtures.png(200, 200, SHOP))
                .getBody()
                .get("logo_url")
                .asString();

        // Read through the other business's host: row-level security hides the branch.
        assertThat(publicGet(url, other.slug(), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Its administrators cannot set or clear it either.
        assertThat(upload(other, shop, ADMIN, "*", ImageFixtures.png(200, 200, Color.BLACK))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(remove(other, shop, ADMIN).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(publicGet(url, t.slug(), byte[].class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ---- Receipts and documents of each shop -------------------------------------------------------------

    @Test
    void eachShopsReceiptCarriesItsOwnLogoAndTheOthersTheBusinessLogo() throws Exception {
        RetailTestSupport admin = new RetailTestSupport(http, t);
        UUID product = admin.product("LOGO-1", 3_000, 5_000);
        admin.stockUp(t.headOffice(), product, "10");
        admin.stockUp(t.secondBranch(), product, "10");
        UUID atHeadOffice = RetailTestSupport.id(admin.sell(t.headOffice(), "cash", product, "1"));
        UUID atShop = RetailTestSupport.id(admin.sell(t.secondBranch(), "cash", product, "1"));

        // No logo anywhere: the receipt has none.
        assertThat(images(receipt(admin, atShop))).isEmpty();

        businessLogo(BUSINESS);
        assertThat(upload(t.secondBranch(), ImageFixtures.png(256, 256, SHOP)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        List<Color> shopReceipt = images(receipt(admin, atShop));
        assertThat(shopReceipt).hasSize(1);
        assertThat(near(shopReceipt.get(0), SHOP)).isTrue();

        // The head office has none of its own, so it falls back to the business logo.
        List<Color> headOfficeReceipt = images(receipt(admin, atHeadOffice));
        assertThat(headOfficeReceipt).hasSize(1);
        assertThat(near(headOfficeReceipt.get(0), BUSINESS)).isTrue();

        // Removing the shop's logo puts the business logo back on its receipts.
        remove(t, t.secondBranch(), ADMIN);
        List<Color> after = images(receipt(admin, atShop));
        assertThat(after).hasSize(1);
        assertThat(near(after.get(0), BUSINESS)).isTrue();
    }

    @Test
    void aShopLogoWhoseStoredFileIsGoneFallsBackToTheBusinessLogo() throws Exception {
        RetailTestSupport admin = new RetailTestSupport(http, t);
        UUID product = admin.product("LOGO-2", 3_000, 5_000);
        admin.stockUp(t.secondBranch(), product, "10");
        UUID atShop = RetailTestSupport.id(admin.sell(t.secondBranch(), "cash", product, "1"));

        businessLogo(BUSINESS);
        String url = upload(t.secondBranch(), ImageFixtures.png(256, 256, SHOP))
                .getBody()
                .get("logo_url")
                .asString();
        // The shop's logo is still on the branch, but its file is no longer in storage (lost, or unreadable).
        deleteStored(url.substring(url.indexOf("?v=") + 3));

        List<Color> receipt = images(receipt(admin, atShop));
        assertThat(receipt).hasSize(1);
        assertThat(near(receipt.get(0), BUSINESS)).isTrue();
    }

    @Autowired
    org.springframework.context.ApplicationContext context;

    /** Removes a document's object from the fake object store (the interface is package-private to core.documents). */
    void deleteStored(String documentId) throws Exception {
        String key = TestDatabase.owner()
                .sql("SELECT object_key FROM documents WHERE id = ?::uuid")
                .param(documentId)
                .query(String.class)
                .single();
        Class<?> type = Class.forName("com.rincoltech.bms.core.documents.internal.ObjectStorage");
        var delete = type.getDeclaredMethod("delete", String.class);
        delete.setAccessible(true);
        delete.invoke(context.getBean(type), key);
    }

    byte[] receipt(RetailTestSupport admin, UUID sale) {
        ResponseEntity<byte[]> r = http.exchange(
                RetailTestSupport.BASE + "/sales/" + sale + "/receipt/pdf",
                HttpMethod.GET,
                new HttpEntity<>(admin.headers(RetailTestSupport.ADMIN, "*", Map.of())),
                byte[].class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    /** The centre colour of every image the PDF draws, in page order. */
    static List<Color> images(byte[] pdf) throws Exception {
        List<Color> out = new ArrayList<>();
        try (PDDocument d = Loader.loadPDF(pdf)) {
            for (PDPage page : d.getPages()) {
                collect(page.getResources(), out);
            }
        }
        return out;
    }

    private static void collect(PDResources resources, List<Color> out) throws Exception {
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject x = resources.getXObject(name);
            if (x instanceof PDImageXObject image) {
                BufferedImage b = image.getImage();
                out.add(new Color(b.getRGB(b.getWidth() / 2, b.getHeight() / 2)));
            } else if (x instanceof PDFormXObject form) {
                collect(form.getResources(), out);
            }
        }
    }
}
