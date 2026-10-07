package com.rincoltech.bms.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.ImageFixtures;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Tenant branding (FR-TEN-08): the logo upload and its rules, the theme colour rule, and the two
 * public routes with their cache and type headers and their tenant isolation. The images are
 * built in code ({@link ImageFixtures}); nothing here is a real logo or name.
 */
class BrandingIT extends IntegrationTest {

    static final String ADMIN = "core.settings.read,core.settings.manage";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("brand-a", true);
    }

    HttpHeaders staff(TestDatabase.Fixture tenant, String permissions) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", tenant.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", "*");
        return h;
    }

    ResponseEntity<JsonNode> uploadAs(TestDatabase.Fixture tenant, String permissions, byte[] bytes, String filename) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders h = staff(tenant, permissions);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange("/api/v1/settings/logo", HttpMethod.PUT, new HttpEntity<>(form, h), JsonNode.class);
    }

    ResponseEntity<JsonNode> upload(byte[] bytes, String filename) {
        return uploadAs(t, ADMIN, bytes, filename);
    }

    ResponseEntity<JsonNode> patch(Map<String, Object> body) {
        HttpHeaders h = staff(t, ADMIN);
        String version =
                String.valueOf(http.exchange("/api/v1/settings", HttpMethod.GET, new HttpEntity<>(h), JsonNode.class)
                        .getBody()
                        .get("version")
                        .asInt());
        h.add("If-Match", "\"" + version + "\"");
        return http.exchange("/api/v1/settings", HttpMethod.PATCH, new HttpEntity<>(body, h), JsonNode.class);
    }

    /** A public call: no token, the tenant from the host (or X-Tenant, which the test profile allows). */
    <T> ResponseEntity<T> publicGet(String path, String slug, String host, Class<T> type, String... extra) {
        HttpHeaders h = new HttpHeaders();
        if (slug != null) {
            h.add("X-Tenant", slug);
        }
        if (host != null) {
            h.add(HttpHeaders.HOST, host);
        }
        for (int i = 0; i + 1 < extra.length; i += 2) {
            h.add(extra[i], extra[i + 1]);
        }
        return http.exchange(URI.create(http.getRootUri() + path), HttpMethod.GET, new HttpEntity<>(h), type);
    }

    ResponseEntity<JsonNode> branding(TestDatabase.Fixture tenant) {
        return publicGet("/api/v1/branding", tenant.slug(), null, JsonNode.class);
    }

    ResponseEntity<byte[]> logo(TestDatabase.Fixture tenant) {
        return publicGet("/api/v1/branding/logo", tenant.slug(), null, byte[].class);
    }

    static BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    static boolean contains(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        for (int i = 0; i + n.length <= haystack.length; i++) {
            if (Arrays.equals(haystack, i, i + n.length, n, 0, n.length)) {
                return true;
            }
        }
        return false;
    }

    // ---- The public routes ------------------------------------------------------------------

    @Test
    void aTenantWithNoBrandShowsItsNameAndNoLogoOrColour() {
        ResponseEntity<JsonNode> r = branding(t);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("display_name").asString()).isNotBlank();
        assertThat(r.getBody().get("theme_primary").isNull()).isTrue();
        assertThat(r.getBody().get("theme_text").isNull()).isTrue();
        assertThat(r.getBody().get("logo_url").isNull()).isTrue();
        ResponseEntity<JsonNode> none = publicGet("/api/v1/branding/logo", t.slug(), null, JsonNode.class);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(none.getBody().get("code").asString()).isEqualTo("not_found");
    }

    /** #99: the public branding lists the enabled module keys, sorted, so the landing copy follows them. */
    @Test
    void theBrandingListsOnlyTheModulesTheTenantHasSwitchedOn() {
        assertThat(modules(t)).containsExactly("lending");
        assertThat(modules(TestDatabase.tenant("brand-r", false, true))).containsExactly("retail");
        assertThat(modules(TestDatabase.tenant("brand-b", true, true))).containsExactly("lending", "retail");
        assertThat(modules(TestDatabase.tenant("brand-n", false, false))).isEmpty();
    }

    List<String> modules(TestDatabase.Fixture tenant) {
        ResponseEntity<JsonNode> r = branding(tenant);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> keys = new ArrayList<>();
        r.getBody().get("modules").forEach(n -> keys.add(n.asString()));
        return keys;
    }

    /** FR-TEN-08: the logo is re-encoded, stored as a tenant document and served publicly with the safe headers. */
    @Test
    void anUploadedLogoIsServedPubliclyWithCacheAndTypeHeaders() throws Exception {
        ResponseEntity<JsonNode> up = upload(ImageFixtures.png(200, 160, new Color(13, 92, 117)), "logo.png");
        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.OK);
        String documentId = up.getBody().get("logo_document_id").asString();

        JsonNode b = branding(t).getBody();
        assertThat(b.get("logo_url").asString()).isEqualTo("/api/v1/branding/logo?v=" + documentId);

        ResponseEntity<byte[]> image = publicGet(b.get("logo_url").asString(), t.slug(), null, byte[].class);
        assertThat(image.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders h = image.getHeaders();
        assertThat(h.getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(h.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(h.getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=3600, public");
        assertThat(h.getFirst(HttpHeaders.CONTENT_DISPOSITION)).isEqualTo("inline");
        assertThat(h.getETag()).isNotBlank();
        BufferedImage decoded = decode(image.getBody());
        assertThat(decoded.getWidth()).isEqualTo(200);

        // The document is a core document of the tenant, under its prefix, subject core.tenant.
        Map<String, Object> row = TestDatabase.owner()
                .sql("SELECT subject_type, subject_id, object_key, doc_type FROM documents WHERE id = ?::uuid")
                .param(documentId)
                .query()
                .singleRow();
        assertThat(row.get("subject_type")).isEqualTo("core.tenant");
        assertThat(row.get("subject_id")).isEqualTo(t.tenantId());
        assertThat((String) row.get("object_key")).startsWith("tenants/" + t.tenantId() + "/upload/");

        // A conditional request with the ETag is a 304 with no body.
        ResponseEntity<byte[]> again =
                publicGet("/api/v1/branding/logo", t.slug(), null, byte[].class, "If-None-Match", h.getETag());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(again.getBody()).isNull();
    }

    @Test
    void thePublicRoutesWorkForASuspendedTenant() {
        upload(ImageFixtures.png(200, 200, Color.BLUE), "logo.png");
        TestDatabase.owner()
                .sql("UPDATE tenants SET status = 'suspended' WHERE id = ?")
                .param(t.tenantId())
                .update();
        try {
            assertThat(branding(t).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(logo(t).getStatusCode()).isEqualTo(HttpStatus.OK);
            // Changing the brand is a write: refused while suspended (FR-TEN-06).
            assertThat(upload(ImageFixtures.png(200, 200, Color.RED), "logo.png")
                            .getStatusCode())
                    .isEqualTo(HttpStatus.LOCKED);
        } finally {
            TestDatabase.owner()
                    .sql("UPDATE tenants SET status = 'active' WHERE id = ?")
                    .param(t.tenantId())
                    .update();
        }
    }

    // ---- Tenant isolation -------------------------------------------------------------------

    /** NFR-ISO: the host decides the tenant, and no header or unknown host reaches another tenant's brand. */
    @Test
    void theBrandingOfOneTenantIsNeverServedForAnotherOrAnUnknownHost() throws Exception {
        TestDatabase.Fixture other = TestDatabase.tenant("brand-b", true);
        byte[] ofA = ImageFixtures.png(200, 200, new Color(10, 20, 30));
        byte[] ofB = ImageFixtures.jpeg(300, 300, new Color(200, 100, 50));
        assertThat(upload(ofA, "a.png").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(uploadAs(other, ADMIN, ofB, "b.jpg").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(logo(t).getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(logo(other).getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(decode(logo(other).getBody()).getWidth()).isEqualTo(300);

        // The host wins over an X-Tenant header that names someone else.
        ResponseEntity<byte[]> byHost = publicGet(
                "/api/v1/branding/logo", t.slug(), other.slug() + "-bms-staging.rincoltech.test", byte[].class);
        assertThat(byHost.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);

        // Unknown hosts and slugs: the same 404 as every tenant route, for both routes, with no brand.
        for (String path : new String[] {"/api/v1/branding", "/api/v1/branding/logo"}) {
            ResponseEntity<JsonNode> unknownHost =
                    publicGet(path, null, "nobody-bms-staging.rincoltech.test", JsonNode.class);
            assertThat(unknownHost.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(unknownHost.getBody().get("code").asString()).isEqualTo("unknown_tenant");
            ResponseEntity<JsonNode> foreignHost = publicGet(path, null, "brand.evil.test", JsonNode.class);
            assertThat(foreignHost.getBody().get("code").asString()).isEqualTo("unknown_tenant");
            ResponseEntity<JsonNode> platformHost = publicGet(path, null, null, JsonNode.class);
            assertThat(platformHost.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            ResponseEntity<JsonNode> unknownSlug = publicGet(path, "no-such-tenant", null, JsonNode.class);
            assertThat(unknownSlug.getBody().get("code").asString()).isEqualTo("unknown_tenant");
        }

        // A tenant's admin cannot change another tenant's brand: the tenant comes from the host only.
        HttpHeaders h = staff(t, ADMIN);
        h.add(HttpHeaders.HOST, other.slug() + "-bms-staging.rincoltech.test");
        h.set("X-Tenant", t.slug());
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(ImageFixtures.png(200, 200, Color.BLACK)) {
            @Override
            public String getFilename() {
                return "x.png";
            }
        });
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        // The dev principal is honoured on whichever tenant the host resolves, so what must hold is
        // that the upload lands on the host's tenant (other), never on the header's (t).
        http.exchange("/api/v1/settings/logo", HttpMethod.PUT, new HttpEntity<>(form, h), JsonNode.class);
        assertThat(decode(logo(t).getBody()).getWidth()).isEqualTo(200);
        assertThat(branding(t).getBody().get("logo_url").asString()).contains(logoIdOf(t));
    }

    String logoIdOf(TestDatabase.Fixture tenant) {
        HttpHeaders h = staff(tenant, ADMIN);
        return http.exchange("/api/v1/settings", HttpMethod.GET, new HttpEntity<>(h), JsonNode.class)
                .getBody()
                .get("logo_document_id")
                .asString();
    }

    // ---- Upload rules -----------------------------------------------------------------------

    @Test
    void jpegAndWebpAreAcceptedAndWebpIsStoredAsPng() throws Exception {
        assertThat(upload(ImageFixtures.jpeg(300, 200, Color.GREEN), "logo.jpg").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(logo(t).getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);

        ResponseEntity<JsonNode> webp = upload(ImageFixtures.webp(160, 140, new Color(20, 90, 160)), "logo.webp");
        assertThat(webp.getStatusCode()).as("%s", webp.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<byte[]> served = logo(t);
        assertThat(served.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        BufferedImage decoded = decode(served.getBody());
        assertThat(decoded.getWidth()).isEqualTo(160);
        assertThat(decoded.getHeight()).isEqualTo(140);
        assertThat(decoded.getRGB(80, 70) & 0xFFFFFF).isEqualTo(new Color(20, 90, 160).getRGB() & 0xFFFFFF);
    }

    /** The type comes from the bytes: a JPEG named .png is a JPEG, and the name is never used. */
    @Test
    void theTypeComesFromTheBytesNotTheFileName() {
        assertThat(upload(ImageFixtures.jpeg(200, 200, Color.GRAY), "logo.png").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(logo(t).getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
    }

    @Test
    void metadataIsStrippedAndALargeLogoIsScaledToFiveHundredAndTwelve() throws Exception {
        byte[] withExif = ImageFixtures.jpegWithExif(1000, 600, Color.ORANGE);
        assertThat(contains(withExif, "Exif")).isTrue();
        assertThat(upload(withExif, "logo.jpg").getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] served = logo(t).getBody();
        assertThat(contains(served, "Exif")).isFalse();
        assertThat(contains(served, "GPS")).isFalse();
        BufferedImage decoded = decode(served);
        assertThat(decoded.getWidth()).isEqualTo(512);
        assertThat(decoded.getHeight()).isEqualTo(307);
    }

    @Test
    void svgIsRefusedWithAClearCode() {
        for (byte[] svg : new byte[][] {ImageFixtures.svg(), ImageFixtures.svgUtf16()}) {
            ResponseEntity<JsonNode> r = upload(svg, "logo.svg");
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(r.getBody().get("code").asString()).isEqualTo("svg_not_allowed");
        }
        // An SVG renamed .png is still an SVG, and nothing was stored.
        assertThat(upload(ImageFixtures.svg(), "logo.png").getBody().get("code").asString())
                .isEqualTo("svg_not_allowed");
        assertThat(logo(t).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void otherTypesAndBrokenImagesAreRefused() {
        for (byte[] bad : new byte[][] {
            ImageFixtures.gif(),
            "<html><script>alert(1)</script></html>".getBytes(),
            "%PDF-1.4 test".getBytes(),
            Arrays.copyOf(ImageFixtures.png(200, 200, Color.RED), 60)
        }) {
            ResponseEntity<JsonNode> r = upload(bad, "logo.png");
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(r.getBody().get("code").asString()).isEqualTo("unsupported_file_type");
        }
        assertThat(logo(t).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void oversizeAndUndersizeAreRefused() {
        byte[] big = Arrays.copyOf(ImageFixtures.png(200, 200, Color.RED), 1024 * 1024 + 1);
        ResponseEntity<JsonNode> tooBig = upload(big, "logo.png");
        assertThat(tooBig.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(tooBig.getBody().get("code").asString()).isEqualTo("file_too_large");

        for (byte[] small :
                new byte[][] {ImageFixtures.png(127, 400, Color.RED), ImageFixtures.jpeg(400, 100, Color.RED)}) {
            ResponseEntity<JsonNode> r = upload(small, "logo.png");
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(r.getBody().get("code").asString()).isEqualTo("image_too_small");
        }
        // Exactly 128 on the short side is fine.
        assertThat(upload(ImageFixtures.png(128, 400, Color.RED), "logo.png").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void onlyThoseWhoManageSettingsMayChangeTheLogo() {
        byte[] png = ImageFixtures.png(200, 200, Color.RED);
        assertThat(uploadAs(t, "core.settings.read", png, "logo.png").getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(png) {
            @Override
            public String getFilename() {
                return "logo.png";
            }
        });
        HttpHeaders anonymous = new HttpHeaders();
        anonymous.add("X-Tenant", t.slug());
        anonymous.setContentType(MediaType.MULTIPART_FORM_DATA);
        assertThat(http.exchange(
                                "/api/v1/settings/logo",
                                HttpMethod.PUT,
                                new HttpEntity<>(form, anonymous),
                                JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.exchange(
                                "/api/v1/settings/logo",
                                HttpMethod.DELETE,
                                new HttpEntity<>(staff(t, "core.settings.read")),
                                JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** The previous logo is superseded, not deleted; each change is audited with was and now. */
    @Test
    void replacingAndRemovingKeepTheOldFilesAndAreAudited() {
        String first = upload(ImageFixtures.png(200, 200, Color.RED), "a.png")
                .getBody()
                .get("logo_document_id")
                .asString();
        String second = upload(ImageFixtures.png(210, 210, Color.BLUE), "b.png")
                .getBody()
                .get("logo_document_id")
                .asString();
        assertThat(second).isNotEqualTo(first);
        assertThat(branding(t).getBody().get("logo_url").asString()).endsWith(second);

        Map<String, Object> audit = TestDatabase.owner()
                .sql("""
                        SELECT data->'before'->>'logo_document_id' AS was, data->'after'->>'logo_document_id' AS now
                          FROM audit_log
                         WHERE tenant_id = ? AND action = 'core.settings.updated'
                           AND data->'after'->>'logo_document_id' = ?
                         ORDER BY created_at DESC LIMIT 1
                        """)
                .params(t.tenantId(), second)
                .query()
                .singleRow();
        assertThat(audit.get("was")).isEqualTo(first);
        assertThat(audit.get("now")).isEqualTo(second);

        ResponseEntity<JsonNode> removed = http.exchange(
                "/api/v1/settings/logo", HttpMethod.DELETE, new HttpEntity<>(staff(t, ADMIN)), JsonNode.class);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(removed.getBody().get("logo_document_id").isNull()).isTrue();
        assertThat(branding(t).getBody().get("logo_url").isNull()).isTrue();
        assertThat(logo(t).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        long kept = TestDatabase.owner()
                .sql("SELECT count(*) FROM documents WHERE tenant_id = ? AND id IN (?::uuid, ?::uuid)")
                .params(t.tenantId(), first, second)
                .query(Long.class)
                .single();
        assertThat(kept).isEqualTo(2);
        // Removing again changes nothing.
        assertThat(http.exchange(
                                "/api/v1/settings/logo",
                                HttpMethod.DELETE,
                                new HttpEntity<>(staff(t, ADMIN)),
                                JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void exifOrientationIsAppliedBeforeTheMetadataIsDropped() throws Exception {
        assertThat(upload(ImageFixtures.jpegWithOrientation(300, 200, Color.MAGENTA, 6), "p.jpg")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        BufferedImage decoded = decode(logo(t).getBody());
        assertThat(decoded.getWidth()).isEqualTo(200);
        assertThat(decoded.getHeight()).isEqualTo(300);
    }

    @Test
    void polyglotsLoseTheirTrailerOnReencode() {
        for (byte[] polyglot : new byte[][] {
            ImageFixtures.jpegWithTrailingScript(200, 200, Color.CYAN),
            ImageFixtures.pngWithTrailer(200, 200, Color.CYAN)
        }) {
            assertThat(upload(polyglot, "logo.png").getStatusCode()).isEqualTo(HttpStatus.OK);
            byte[] served = logo(t).getBody();
            assertThat(contains(served, "<script")).isFalse();
            assertThat(contains(served, "test-polyglot")).isFalse();
        }
    }

    /** A few KB on the wire, a huge canvas decoded: refused before decoding (4 megapixel logo cap). */
    @Test
    void aDecompressionBombIsRefusedBeforeItIsDecoded() {
        ResponseEntity<JsonNode> r = upload(ImageFixtures.bigCanvasPng(5000, 5000), "logo.png");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("image_too_large");
        assertThat(upload(ImageFixtures.bigCanvasPng(2000, 2000), "ok.png").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void conditionalRequestsAcceptWeakTagsListsAndStar() {
        upload(ImageFixtures.png(200, 200, Color.RED), "a.png");
        String etag = logo(t).getHeaders().getETag();
        for (String header : new String[] {"W/" + etag, "\"other\", " + etag, "*"}) {
            assertThat(publicGet("/api/v1/branding/logo", t.slug(), null, byte[].class, "If-None-Match", header)
                            .getStatusCode())
                    .as(header)
                    .isEqualTo(HttpStatus.NOT_MODIFIED);
        }
        assertThat(publicGet("/api/v1/branding/logo", t.slug(), null, byte[].class, "If-None-Match", "\"nope\"")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(logo(t).getHeaders().getFirst(HttpHeaders.VARY)).isEqualTo("Host");
        assertThat(publicGet("/api/v1/branding/logo?v=not-a-uuid", t.slug(), null, JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void theThemeColourCanBeClearedWithAnEmptyString() {
        patch(Map.of("theme_primary", "#0D5C75"));
        ResponseEntity<JsonNode> cleared = patch(Map.of("theme_primary", ""));
        assertThat(cleared.getStatusCode()).as("%s", cleared.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody().get("theme_primary").isNull()).isTrue();
        assertThat(branding(t).getBody().get("theme_primary").isNull()).isTrue();
    }

    // ---- The theme colour -------------------------------------------------------------------

    @Test
    void aReadableThemeColourIsSavedUppercasedAndPublished() {
        ResponseEntity<JsonNode> r = patch(Map.of("theme_primary", "#0d5c75"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("theme_primary").asString()).isEqualTo("#0D5C75");
        JsonNode b = branding(t).getBody();
        assertThat(b.get("theme_primary").asString()).isEqualTo("#0D5C75");
        assertThat(b.get("theme_text").asString()).isEqualTo("#FFFFFF");

        assertThat(patch(Map.of("theme_primary", "#FFD54F"))
                        .getBody()
                        .get("theme_primary")
                        .asString())
                .isEqualTo("#FFD54F");
        assertThat(branding(t).getBody().get("theme_text").asString()).isEqualTo("#111111");
    }

    @Test
    void aColourWithNoReadableTextOrNotHexIsRefused() {
        ResponseEntity<JsonNode> grey = patch(Map.of("theme_primary", "#777777"));
        assertThat(grey.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(grey.getBody().get("errors").get(0).get("field").asString()).isEqualTo("theme_primary");
        assertThat(grey.getBody().get("errors").get(0).get("code").asString()).isEqualTo("insufficient_contrast");
        for (String bad : new String[] {"red", "#FFF", "0D5C75", "#GGGGGG", "#0D5C75; x"}) {
            assertThat(patch(Map.of("theme_primary", bad)).getStatusCode())
                    .as(bad)
                    .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        }
        assertThat(branding(t).getBody().get("theme_primary").isNull()).isTrue();
    }

    @Test
    void theColourChangeIsAuditedWithWasAndNow() {
        patch(Map.of("theme_primary", "#0D5C75"));
        patch(Map.of("theme_primary", "#1B5E20"));
        Map<String, Object> audit =
                TestDatabase.owner().sql("""
                        SELECT data->'before'->>'theme_primary' AS was, data->'after'->>'theme_primary' AS now
                          FROM audit_log
                         WHERE tenant_id = ? AND action = 'core.settings.updated'
                         ORDER BY created_at DESC LIMIT 1
                        """).param(t.tenantId()).query().singleRow();
        assertThat(audit.get("was")).isEqualTo("#0D5C75");
        assertThat(audit.get("now")).isEqualTo("#1B5E20");
    }
}
