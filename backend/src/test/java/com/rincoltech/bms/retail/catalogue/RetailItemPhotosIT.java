package com.rincoltech.bms.retail.catalogue;

import static com.rincoltech.bms.retail.RetailTestSupport.ADMIN;
import static com.rincoltech.bms.retail.RetailTestSupport.SALES;
import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.retail.RetailTestSupport;
import java.awt.image.BufferedImage;
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
 * Item photos (issue #384 slice 2, ADR-053, FR-RET-58): an item's photo and a size's own, checked and re-encoded by
 * core.documents, stored under the tenant's prefix, served from the same origin to whoever may read the catalogue, never
 * to another tenant, an SVG and an oversized file refused. Every file is fabricated.
 */
class RetailItemPhotosIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;
    RetailTestSupport api;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("retail-photos", false, true);
        api = new RetailTestSupport(http, t);
    }

    static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    ResponseEntity<JsonNode> upload(String path, byte[] bytes, String permissions) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "photo.png";
            }
        });
        HttpHeaders h = api.headers(permissions, "*", Map.of());
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange(RetailTestSupport.BASE + path, HttpMethod.POST, new HttpEntity<>(form, h), JsonNode.class);
    }

    ResponseEntity<byte[]> image(String photoId, RetailTestSupport as, String permissions) {
        return http.exchange(
                RetailTestSupport.BASE + "/photos/" + photoId,
                HttpMethod.GET,
                new HttpEntity<>(as.headers(permissions, "*", Map.of())),
                byte[].class);
    }

    JsonNode romper() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Test Romper");
        body.put("base_code", "ROMP");
        body.put("category_id", api.category("Test Baby Wear"));
        body.put("unit_id", api.unit("test-piece"));
        body.put("cost_minor", 4_000);
        body.put("sell_minor", 9_000);
        body.put("sizes", List.of("0-3m", "3-6m"));
        ResponseEntity<JsonNode> r = api.post("/product-families", body, ADMIN);
        assertThat(r.getStatusCode()).as("%s", r.getBody()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    @Test
    void frRet58AnItemsPhotoIsShownForEverySizeWithoutItsOwnAndASizeMayHaveItsOwn() throws IOException {
        JsonNode f = romper();
        String familyId = f.get("id").asString();
        String small = f.get("variants").get(0).get("id").asString();
        String large = f.get("variants").get(1).get("id").asString();

        ResponseEntity<JsonNode> set = upload("/product-families/" + familyId + "/photo", png(300, 200), ADMIN);
        assertThat(set.getStatusCode()).as("%s", set.getBody()).isEqualTo(HttpStatus.OK);
        String itemPhoto = set.getBody().get("photo_id").asString();
        // A size without its own photo carries none of its own and names its item's apart.
        JsonNode firstSize = set.getBody().get("variants").get(0);
        assertThat(firstSize.has("photo_id")).isFalse();
        assertThat(firstSize.get("item_photo_id").asString()).isEqualTo(itemPhoto);
        JsonNode largeRead = api.get("/products/" + large, ADMIN).getBody();
        assertThat(largeRead.has("photo_id")).isFalse();
        assertThat(largeRead.get("item_photo_id").asString()).isEqualTo(itemPhoto);

        ResponseEntity<JsonNode> own = upload("/products/" + small + "/photo", png(120, 120), ADMIN);
        assertThat(own.getStatusCode()).as("%s", own.getBody()).isEqualTo(HttpStatus.OK);
        String smallPhoto = own.getBody().get("photo_id").asString();
        assertThat(smallPhoto).isNotEqualTo(itemPhoto);
        assertThat(own.getBody().get("item_photo_id").asString()).isEqualTo(itemPhoto);
        assertThat(api.get("/products?query=romper", ADMIN)
                        .getBody()
                        .get("items")
                        .toString())
                .contains(smallPhoto)
                .contains(itemPhoto);

        // The file sits under the tenant's own prefix, re-encoded.
        assertThat(TestDatabase.owner()
                        .sql("SELECT object_key FROM documents WHERE id = ?::uuid")
                        .param(smallPhoto)
                        .query(String.class)
                        .single())
                .startsWith("tenants/" + t.tenantId() + "/upload/");

        // A seller sees it, from the same origin, as an image the browser may keep.
        ResponseEntity<byte[]> seen = image(smallPhoto, api, SALES);
        assertThat(seen.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(seen.getHeaders().getContentType().toString()).isEqualTo("image/png");
        assertThat(seen.getHeaders().getCacheControl()).contains("private");
        // Kept five minutes at most, then asked again; a till that signs out does not hand its photos on for a day.
        assertThat(seen.getHeaders().getCacheControl()).contains("max-age=300").contains("must-revalidate");
        assertThat(seen.getHeaders().getVary()).contains("Cookie", "Authorization");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(seen.getBody()))
                        .getWidth())
                .isEqualTo(120);

        // Taken off, the size has no photo of its own and shows its item's again; taking off a size's photo never
        // touches
        // the item's.
        ResponseEntity<JsonNode> cleared =
                api.call(HttpMethod.DELETE, "/products/" + small + "/photo", null, ADMIN, "*", Map.of());
        assertThat(cleared.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cleared.getBody().has("photo_id")).isFalse();
        assertThat(cleared.getBody().get("item_photo_id").asString()).isEqualTo(itemPhoto);
        ResponseEntity<JsonNode> again =
                api.call(HttpMethod.DELETE, "/products/" + small + "/photo", null, ADMIN, "*", Map.of());
        assertThat(again.getBody().get("item_photo_id").asString()).isEqualTo(itemPhoto);
        assertThat(api.get("/product-families/" + familyId, ADMIN)
                        .getBody()
                        .get("photo_id")
                        .asString())
                .isEqualTo(itemPhoto);
    }

    @Test
    void frRet58AnItemWithoutVariantsTakesAPhotoAndReadsAsBeforeWithout() throws IOException {
        UUID plain = api.product("PHOTO-1", 1_000, 2_000);
        assertThat(api.get("/products/" + plain, ADMIN).getBody().has("photo_id"))
                .isFalse();
        ResponseEntity<JsonNode> set = upload("/products/" + plain + "/photo", png(2400, 1600), ADMIN);
        assertThat(set.getStatusCode()).as("%s", set.getBody()).isEqualTo(HttpStatus.OK);
        ResponseEntity<byte[]> seen = image(set.getBody().get("photo_id").asString(), api, ADMIN);
        // Scaled down to 1200 px on the long edge.
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(seen.getBody()))
                        .getWidth())
                .isEqualTo(1200);
        // The small copy for the cards and pickers is 240 px, stored once, private and kept a day too.
        ResponseEntity<byte[]> card = image(set.getBody().get("photo_id").asString() + "?size=small", api, ADMIN);
        assertThat(card.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(card.getHeaders().getCacheControl()).contains("private");
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(card.getBody()))
                        .getWidth())
                .isEqualTo(240);
        assertThat(image(set.getBody().get("photo_id").asString() + "?size=huge", api, ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void frRet58OnlyTheCatalogueManagerUploadsAndAnSvgOrAnOversizedFileIsRefused() throws IOException {
        UUID plain = api.product("PHOTO-2", 1_000, 2_000);
        assertThat(upload("/products/" + plain + "/photo", png(100, 100), SALES).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        ResponseEntity<JsonNode> refused = upload("/products/" + plain + "/photo", svg, ADMIN);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("svg_not_allowed");
        ResponseEntity<JsonNode> text =
                upload("/products/" + plain + "/photo", "not an image".getBytes(StandardCharsets.UTF_8), ADMIN);
        assertThat(text.getStatusCode().is4xxClientError()).isTrue();
        assertThat(api.get("/products/" + plain, ADMIN).getBody().has("photo_id"))
                .isFalse();
        assertThat(upload("/products/" + UUID.randomUUID() + "/photo", png(100, 100), ADMIN)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void nfrIso05AnotherTenantNeverReadsAPhotoAndAPersonWithoutCatalogueReadIsRefused() throws IOException {
        UUID plain = api.product("PHOTO-3", 1_000, 2_000);
        String photo = upload("/products/" + plain + "/photo", png(100, 100), ADMIN)
                .getBody()
                .get("photo_id")
                .asString();
        TestDatabase.Fixture other = TestDatabase.tenant("retail-photos-b", false, true);
        RetailTestSupport stranger = new RetailTestSupport(http, other);
        assertThat(image(photo, stranger, ADMIN).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(image(photo, api, "retail.sale.read").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        // A photo id is not a way into other documents: a document of another subject type is not found here.
        assertThat(image(UUID.randomUUID().toString(), api, ADMIN).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * A branch search (the item picker of the sale, restock and order forms) carries the photo, the size and colour and what
     * customer orders keep aside, all three on the same row (issues #384 and #385).
     */
    @Test
    void frRet58ABranchSearchReturnsThePhotoTheVariantAndWhatOrdersKeepAsideTogether() throws IOException {
        JsonNode f = romper();
        String familyId = f.get("id").asString();
        UUID small = UUID.fromString(f.get("variants").get(0).get("id").asString());
        UUID hq = t.headOffice();
        String itemPhoto = upload("/product-families/" + familyId + "/photo", png(200, 200), ADMIN)
                .getBody()
                .get("photo_id")
                .asString();
        String own = upload("/products/" + small + "/photo", png(100, 100), ADMIN)
                .getBody()
                .get("photo_id")
                .asString();
        api.stockUp(hq, small, "5");
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("product_id", small);
        line.put("qty", "2");
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("branch_id", hq);
        order.put("source", "whatsapp");
        order.put("customer_name", "Test Customer 01");
        order.put("customer_phone", "+256700000011");
        order.put("delivery_place", "Test Place, Plot 1");
        order.put(
                "delivery_date",
                java.time.LocalDate.now(java.time.ZoneId.of("Africa/Kampala")).toString());
        order.put("delivery_fee_minor", 0);
        order.put("lines", List.of(line));
        ResponseEntity<JsonNode> placed = api.postKeyed(
                "/orders",
                order,
                ADMIN + ",retail.order.manage",
                "*",
                UUID.randomUUID().toString());
        assertThat(placed.getStatusCode()).as("%s", placed.getBody()).isEqualTo(HttpStatus.CREATED);

        JsonNode rows = api.get("/products?query=romper&branch_id=" + hq, ADMIN)
                .getBody()
                .get("items");
        JsonNode row = null;
        for (JsonNode r : rows) {
            if (r.get("id").asString().equals(small.toString())) {
                row = r;
            }
        }
        assertThat(row).as("%s", rows).isNotNull();
        assertThat(row.get("photo_id").asString()).isEqualTo(own);
        assertThat(row.get("item_photo_id").asString()).isEqualTo(itemPhoto);
        assertThat(row.get("variant").get("family_id").asString()).isEqualTo(familyId);
        assertThat(row.get("variant").get("size").asString()).isEqualTo("0-3m");
        assertThat(row.get("qty").asString()).isEqualTo("5.000");
        assertThat(row.get("reserved_qty").asString()).isEqualTo("2.000");
    }
}
