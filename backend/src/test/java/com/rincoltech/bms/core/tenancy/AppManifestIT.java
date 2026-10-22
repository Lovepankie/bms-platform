package com.rincoltech.bms.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.ImageFixtures;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

/**
 * The installed app's manifest and icons of a business (ADR-066, issue #446, FR-TEN-08): the name,
 * colour and logo of the host's business, the icon sizes and the safe zone, the cache headers, and
 * the BMS look for a business with no logo and for a host that is no business. Every image is
 * built in code; no real logo or name.
 */
class AppManifestIT extends IntegrationTest {

    static final String ADMIN = "core.settings.read,core.settings.manage";
    static final String MANIFEST = "/api/v1/branding/app/manifest.webmanifest";

    @Autowired
    TestRestTemplate http;

    @Autowired
    Documents documents;

    @Autowired
    PlatformTransactionManager transactions;

    TestDatabase.Fixture t;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("app-icon-a", true);
    }

    HttpHeaders staff() {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", ADMIN);
        h.add("X-Dev-Branch-Ids", "*");
        return h;
    }

    void uploadLogo(byte[] bytes) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "logo.png";
            }
        });
        HttpHeaders h = staff();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        assertThat(http.exchange("/api/v1/settings/logo", HttpMethod.PUT, new HttpEntity<>(form, h), JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    void brand(String displayName, String colour) {
        HttpHeaders h = staff();
        int version = http.exchange("/api/v1/settings", HttpMethod.GET, new HttpEntity<>(h), JsonNode.class)
                .getBody()
                .get("version")
                .asInt();
        h.add("If-Match", "\"" + version + "\"");
        assertThat(http.exchange(
                                "/api/v1/settings",
                                HttpMethod.PATCH,
                                new HttpEntity<>(Map.of("display_name", displayName, "theme_primary", colour), h),
                                JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    <T> ResponseEntity<T> get(String path, String slug, Class<T> type, String... extra) {
        HttpHeaders h = new HttpHeaders();
        if (slug != null) {
            h.add("X-Tenant", slug);
        }
        for (int i = 0; i + 1 < extra.length; i += 2) {
            h.add(extra[i], extra[i + 1]);
        }
        return http.exchange(URI.create(http.getRootUri() + path), HttpMethod.GET, new HttpEntity<>(h), type);
    }

    static BufferedImage decode(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    static List<String> srcs(JsonNode manifest) {
        List<String> out = new ArrayList<>();
        manifest.get("icons").forEach(i -> out.add(i.get("src").asString()));
        return out;
    }

    @Test
    void theManifestCarriesTheBusinessNameColourAndLogoIcons() throws Exception {
        brand("Fabricated Trading Company", "#0D5C75");
        uploadLogo(ImageFixtures.png(300, 150, Color.WHITE));

        ResponseEntity<JsonNode> r = get(MANIFEST, t.slug(), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().toString()).startsWith("application/manifest+json");
        assertThat(r.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache");
        JsonNode m = r.getBody();
        assertThat(m.get("id").asString()).isEqualTo("/staff");
        assertThat(m.get("start_url").asString()).isEqualTo("/staff");
        assertThat(m.get("scope").asString()).isEqualTo("/");
        assertThat(m.get("display").asString()).isEqualTo("standalone");
        assertThat(m.get("name").asString()).isEqualTo("Fabricated Trading Company");
        // Twelve characters would cut "Fabricated T"; the word boundary after "Fabricated" is kept instead.
        assertThat(m.get("short_name").asString()).isEqualTo("Fabricated");
        assertThat(m.get("theme_color").asString()).isEqualTo("#0D5C75");
        assertThat(m.get("icons")).hasSize(4);
        assertThat(srcs(m)).allMatch(s -> s.startsWith("/api/v1/branding/app/icon-") && s.contains("?v="));
        assertThat(m.get("icons").get(2).get("purpose").asString()).isEqualTo("maskable");

        // The same manifest again with its ETag is a 304.
        ResponseEntity<String> again = get(
                MANIFEST,
                t.slug(),
                String.class,
                "If-None-Match",
                r.getHeaders().getETag());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);

        // Each icon is a PNG of its size, the corners the theme colour, cacheable by its ETag.
        Map<String, Integer> sizes = Map.of(
                "icon-192.png", 192,
                "icon-512.png", 512,
                "icon-maskable-192.png", 192,
                "icon-maskable-512.png", 512,
                "apple-touch-icon.png", 180);
        for (Map.Entry<String, Integer> e : sizes.entrySet()) {
            ResponseEntity<byte[]> icon = get("/api/v1/branding/app/" + e.getKey(), t.slug(), byte[].class);
            assertThat(icon.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(icon.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
            assertThat(icon.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("max-age=3600, public");
            BufferedImage image = decode(icon.getBody());
            assertThat(image.getWidth()).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(image.getHeight()).as(e.getKey()).isEqualTo(e.getValue());
            assertThat(image.getRGB(0, 0) & 0xFFFFFF).isEqualTo(0x0D5C75);
            // The white logo is in the middle.
            assertThat(image.getRGB(e.getValue() / 2, e.getValue() / 2) & 0xFFFFFF)
                    .isEqualTo(0xFFFFFF);
            assertThat(get(
                                    "/api/v1/branding/app/" + e.getKey(),
                                    t.slug(),
                                    byte[].class,
                                    "If-None-Match",
                                    icon.getHeaders().getETag())
                            .getStatusCode())
                    .isEqualTo(HttpStatus.NOT_MODIFIED);
        }

        // Maskable: the logo stays inside the safe circle (radius 40 percent), so the band outside it is the colour.
        BufferedImage maskable = decode(get("/api/v1/branding/app/icon-maskable-512.png", t.slug(), byte[].class)
                .getBody());
        for (int x = 0; x < 512; x += 4) {
            for (int y = 0; y < 512; y += 4) {
                if (Math.hypot(x + 0.5 - 256, y + 0.5 - 256) > 0.4 * 512 + 2) {
                    assertThat(maskable.getRGB(x, y) & 0xFFFFFF)
                            .as("%d,%d", x, y)
                            .isEqualTo(0x0D5C75);
                }
            }
        }

        assertThat(get("/api/v1/branding/app/icon-64.png", t.slug(), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aBusinessWithNoLogoAndAHostWithNoBusinessGetTheBmsIcons() throws Exception {
        JsonNode m = get(MANIFEST, t.slug(), JsonNode.class).getBody();
        assertThat(m.get("name").asString()).isNotBlank();
        assertThat(srcs(m)).contains("/icons/icon-192.png", "/icons/icon-maskable-512.png");
        assertThat(m.get("theme_color").asString()).isEqualTo("#0077B6");

        // The platform host (localhost here) and an unknown slug: the BMS manifest, never a 404.
        for (String slug : new String[] {null, "no-such-business"}) {
            ResponseEntity<JsonNode> platform = get(MANIFEST, slug, JsonNode.class);
            assertThat(platform.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(platform.getBody().get("name").asString()).isEqualTo("BMS");
            assertThat(platform.getBody().get("short_name").asString()).isEqualTo("BMS");
            assertThat(srcs(platform.getBody())).contains("/icons/icon-512.png", "/icon.svg");
        }

        // The icons redirect to the BMS icon of the same name (a plain client, so the redirect is seen, not followed).
        HttpClient client = HttpClient.newHttpClient();
        for (String slug : new String[] {t.slug(), null}) {
            HttpRequest.Builder req =
                    HttpRequest.newBuilder(URI.create(http.getRootUri() + "/api/v1/branding/app/apple-touch-icon.png"));
            if (slug != null) {
                req.header("X-Tenant", slug);
            }
            HttpResponse<Void> r = client.send(req.build(), HttpResponse.BodyHandlers.discarding());
            assertThat(r.statusCode()).isEqualTo(302);
            assertThat(r.headers().firstValue("Location")).hasValue("/icons/apple-touch-icon.png");
        }

        // Every other branding route still refuses a host that is no business.
        assertThat(get("/api/v1/branding", "no-such-business", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** The icon's ETag and the manifest's {@code ?v=} change with a new logo and with a new colour. */
    @Test
    void aNewLogoOrColourIsANewIconVersion() throws Exception {
        brand("Fabricated Versions", "#0D5C75");
        uploadLogo(ImageFixtures.png(200, 200, Color.WHITE));
        String etag1 = iconEtag();
        String v1 = manifestVersion();

        uploadLogo(ImageFixtures.png(300, 150, Color.YELLOW));
        String etag2 = iconEtag();
        String v2 = manifestVersion();
        assertThat(etag2).isNotEqualTo(etag1);
        assertThat(v2).isNotEqualTo(v1);
        assertThat(decode(get("/api/v1/branding/app/icon-512.png", t.slug(), byte[].class)
                                        .getBody())
                                .getRGB(256, 256)
                        & 0xFFFFFF)
                .isEqualTo(0xFFFF00);

        brand("Fabricated Versions", "#1B4332");
        String etag3 = iconEtag();
        String v3 = manifestVersion();
        assertThat(etag3).isNotIn(etag1, etag2);
        assertThat(v3).isNotIn(v1, v2);
        assertThat(decode(get("/api/v1/branding/app/icon-512.png", t.slug(), byte[].class)
                                        .getBody())
                                .getRGB(0, 0)
                        & 0xFFFFFF)
                .isEqualTo(0x1B4332);
    }

    String iconEtag() {
        ResponseEntity<byte[]> icon = get("/api/v1/branding/app/icon-512.png", t.slug(), byte[].class);
        assertThat(icon.getStatusCode()).isEqualTo(HttpStatus.OK);
        return icon.getHeaders().getETag();
    }

    String manifestVersion() {
        String src = srcs(get(MANIFEST, t.slug(), JsonNode.class).getBody()).getFirst();
        return src.substring(src.indexOf("?v="));
    }

    /** A name with a double quote and an emoji: valid JSON, and the short name never splits the emoji. */
    @Test
    void aNameWithAQuoteAndAnEmoji() {
        String family = "👨‍👩‍👧";
        brand("Test \"Q\" " + family + " Shop", "#0D5C75");
        ResponseEntity<String> raw = get(MANIFEST, t.slug(), String.class);
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(raw.getBody()).contains("\"name\":\"Test \\\"Q\\\" " + family + " Shop\"");
        JsonNode m = get(MANIFEST, t.slug(), JsonNode.class).getBody();
        assertThat(m.get("name").asString()).isEqualTo("Test \"Q\" " + family + " Shop");
        // Twelve characters end inside "Shop"; the cut falls back to the word boundary after the whole emoji.
        assertThat(m.get("short_name").asString()).isEqualTo("Test \"Q\" " + family);
    }

    /** A logo with a transparent background: the theme colour shows through it and in the corners. */
    @Test
    void aTransparentLogoShowsTheThemeColour() throws Exception {
        brand("Fabricated Clear Logo", "#0D5C75");
        BufferedImage logo = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        for (int x = 80; x < 120; x++) {
            for (int y = 80; y < 120; y++) {
                logo.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(logo, "png", out);
        uploadLogo(out.toByteArray());

        BufferedImage icon = decode(
                get("/api/v1/branding/app/icon-192.png", t.slug(), byte[].class).getBody());
        assertThat(icon.getRGB(0, 0) & 0xFFFFFF).isEqualTo(0x0D5C75);
        assertThat(icon.getRGB(191, 191) & 0xFFFFFF).isEqualTo(0x0D5C75);
        // Inside the logo's box but transparent there: the colour, not black or white.
        assertThat(icon.getRGB(40, 96) & 0xFFFFFF).isEqualTo(0x0D5C75);
        assertThat(icon.getRGB(96, 96) & 0xFFFFFF).isEqualTo(0xFFFFFF);
    }

    /**
     * A stored logo that cannot be drawn (here bytes that are no image at all) is never a 500: every icon
     * is the BMS icon of the same name, again on the next request, and the manifest still answers.
     */
    @Test
    void aLogoThatCannotBeDrawnFallsBackToTheBmsIcons() throws Exception {
        brand("Fabricated Broken Logo", "#0D5C75");
        byte[] broken = "this is no picture at all".getBytes(StandardCharsets.US_ASCII);
        String sha =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(broken));
        // Stored as the documents module stores any upload, by a signed-in member of staff, past the upload's own
        // checks.
        CurrentPrincipal.set(Principal.uniform(UUID.randomUUID(), "staff", Set.of(), true, Set.of()));
        UUID document;
        try {
            document = TenantContext.callAs(
                    t.tenantId(),
                    () -> new TransactionTemplate(transactions)
                            .execute(tx -> documents
                                    .store(
                                            new Documents.Prepared(broken, "image/png", "png", sha),
                                            "core.tenant",
                                            t.tenantId(),
                                            null)
                                    .id()));
        } finally {
            CurrentPrincipal.clear();
        }
        TestDatabase.owner().sql("""
                        UPDATE tenant_settings
                           SET settings = coalesce(settings, '{}'::jsonb) || jsonb_build_object('logo_document_id', ?::text)
                         WHERE tenant_id = ?
                        """).params(document.toString(), t.tenantId()).update();

        ResponseEntity<JsonNode> manifest = get(MANIFEST, t.slug(), JsonNode.class);
        assertThat(manifest.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(manifest.getBody().get("name").asString()).isEqualTo("Fabricated Broken Logo");

        HttpClient client = HttpClient.newHttpClient();
        for (int round = 0; round < 2; round++) {
            for (String file : List.of(
                    "icon-192.png",
                    "icon-512.png",
                    "icon-maskable-192.png",
                    "icon-maskable-512.png",
                    "apple-touch-icon.png")) {
                HttpResponse<Void> r = client.send(
                        HttpRequest.newBuilder(URI.create(http.getRootUri() + "/api/v1/branding/app/" + file))
                                .header("X-Tenant", t.slug())
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
                assertThat(r.statusCode()).as("%s round %d", file, round).isEqualTo(302);
                assertThat(r.headers().firstValue("Location")).hasValue("/icons/" + file);
            }
        }
    }

    /** Only the manifest and the five icon names answer without a business; nothing else under the prefix does. */
    @Test
    void onlyTheExactManifestAndIconPathsAnswerWithoutABusiness() {
        assertThat(get("/api/v1/branding/app/manifest.webmanifest", "no-such-business", JsonNode.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        for (String path : List.of(
                "/api/v1/branding/app/other",
                "/api/v1/branding/app/icon-64.png",
                "/api/v1/branding/app/icon-192.png/extra")) {
            ResponseEntity<JsonNode> r = get(path, "no-such-business", JsonNode.class);
            assertThat(r.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(r.getBody().get("code").asString()).as(path).isEqualTo("unknown_tenant");
        }
    }

    /** NFR-ISO: one business's icon is never served on another's host. */
    @Test
    void theIconsFollowTheHostsBusinessOnly() throws Exception {
        brand("Fabricated Shop A", "#0D5C75");
        uploadLogo(ImageFixtures.png(200, 200, Color.WHITE));
        TestDatabase.Fixture other = TestDatabase.tenant("app-icon-b", true);
        JsonNode theirs = get(MANIFEST, other.slug(), JsonNode.class).getBody();
        assertThat(theirs.get("name").asString()).isNotEqualTo("Fabricated Shop A");
        assertThat(srcs(theirs)).allMatch(s -> s.startsWith("/icons/") || s.equals("/icon.svg"));
    }
}
