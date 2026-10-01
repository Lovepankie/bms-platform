package com.rincoltech.bms.lending.members;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * Member documents on the core documents module (#12; FR-MEM-09, FR-DOC-02, FR-DOC-03, FR-MEM-05,
 * NFR-ISO-05, chapter 8 section 8.8), with the fake object storage of the test profile. All data
 * and files fabricated.
 */
class MemberDocumentsIT extends IntegrationTest {

    static final String ALL = "lending.members.read,lending.members.create,lending.members.update";
    /** The fake's key in application-test.yml, so a test can sign an expired URL itself. */
    static final String FAKE_SECRET = "test-only-fake-storage-secret";

    @Autowired
    TestRestTemplate http;

    TestDatabase.Fixture t;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("docs", true);
    }

    HttpHeaders headers(String permissions, String branches) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", branches);
        return h;
    }

    String member(String name, String phone, String nin, String location) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("branch_id", t.headOffice());
        body.put("full_name", name);
        body.put("phone", phone);
        body.put("id_type", nin == null ? "none" : "nin");
        body.put("national_id", nin);
        body.put("location", location);
        return http.exchange(
                        "/api/v1/lending/members",
                        HttpMethod.POST,
                        new HttpEntity<>(body, headers(ALL, "*")),
                        JsonNode.class)
                .getBody()
                .get("id")
                .asString();
    }

    ResponseEntity<JsonNode> upload(String memberId, String kind, byte[] bytes, String filename) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("doc_kind", kind);
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders h = headers(ALL, "*");
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange(
                "/api/v1/lending/members/" + memberId + "/documents",
                HttpMethod.POST,
                new HttpEntity<>(form, h),
                JsonNode.class);
    }

    ResponseEntity<JsonNode> downloadUrl(String documentId, HttpHeaders h) {
        return http.exchange(
                "/api/v1/documents/" + documentId + "/download-url",
                HttpMethod.POST,
                new HttpEntity<>(h),
                JsonNode.class);
    }

    ResponseEntity<byte[]> fetch(String url) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        // A ready URI, as a browser sends it: a String would be expanded as a template and encoded twice.
        return http.exchange(URI.create(http.getRootUri() + url), HttpMethod.GET, new HttpEntity<>(h), byte[].class);
    }

    static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY), "png", out);
        return out.toByteArray();
    }

    /** A JPEG with an APP1 Exif segment spliced in after the start marker, as a phone camera writes it. */
    static byte[] jpegWithExif() throws IOException {
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB), "jpeg", plain);
        byte[] j = plain.toByteArray();
        byte[] payload = "Exif\0\0TEST-GPS-0.3476N-32.5825E".getBytes(StandardCharsets.ISO_8859_1);
        int len = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(j, 0, 2);
        out.write(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (len >> 8), (byte) len});
        out.write(payload);
        out.write(j, 2, j.length - 2);
        return out.toByteArray();
    }

    static boolean contains(byte[] haystack, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i + n.length <= haystack.length; i++) {
            if (Arrays.equals(haystack, i, i + n.length, n, 0, n.length)) {
                return true;
            }
        }
        return false;
    }

    /** FR-DOC-02, chapter 8 section 8.8: stored under the tenant prefix, re-encoded without metadata, checksum recorded. */
    @Test
    void anImageIsStoredUnderTheTenantPrefixWithoutItsMetadata() throws Exception {
        String id = member("Test Borrower 01", "0700000001", null, "Test Village A");
        byte[] original = jpegWithExif();
        assertThat(contains(original, "Exif")).isTrue();

        ResponseEntity<JsonNode> r = upload(id, "photo", original, "photo.jpg");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody().get("content_type").asString()).isEqualTo("image/jpeg");
        String documentId = r.getBody().get("document_id").asString();

        Map<String, Object> row = TestDatabase.owner()
                .sql("SELECT object_key, sha256, tenant_id FROM documents WHERE id = ?::uuid")
                .param(documentId)
                .query()
                .singleRow();
        assertThat((String) row.get("object_key")).startsWith("tenants/" + t.tenantId() + "/upload/");

        String url =
                downloadUrl(documentId, headers(ALL, "*")).getBody().get("url").asString();
        ResponseEntity<byte[]> file = fetch(url);
        assertThat(file.getStatusCode()).isEqualTo(HttpStatus.OK);
        // #23 review, blocker 3: a download under a server-made name, never inline, never cached.
        assertThat(file.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename=\"upload-" + documentId + ".jpg\"");
        assertThat(file.getHeaders().getFirst("Cache-Control")).isEqualTo("private, no-store");
        assertThat(file.getHeaders().getContentType().toString()).isEqualTo("image/jpeg");
        assertThat(contains(file.getBody(), "Exif")).isFalse();
        assertThat(contains(file.getBody(), "TEST-GPS")).isFalse();
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(file.getBody()))
                        .getWidth())
                .isEqualTo(40);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getBody())))
                .isEqualTo(((String) row.get("sha256")).trim());

        JsonNode list = http.exchange(
                        "/api/v1/lending/members/" + id + "/documents",
                        HttpMethod.GET,
                        new HttpEntity<>(headers(ALL, "*")),
                        JsonNode.class)
                .getBody()
                .get("items");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("doc_kind").asString()).isEqualTo("photo");
    }

    /** Chapter 8 section 8.8: the type comes from the content, never the name; only JPEG, PNG and PDF. */
    @Test
    void theTypeComesFromTheContent() throws Exception {
        String id = member("Test Borrower 02", "0700000002", null, null);

        ResponseEntity<JsonNode> disguised = upload(id, "other", png(10, 10), "statement.pdf");
        assertThat(disguised.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(disguised.getBody().get("content_type").asString()).isEqualTo("image/png");

        ResponseEntity<JsonNode> pdf =
                upload(id, "other", "%PDF-1.4\n% fabricated\n".getBytes(StandardCharsets.US_ASCII), "a.bin");
        assertThat(pdf.getBody().get("content_type").asString()).isEqualTo("application/pdf");

        ResponseEntity<JsonNode> text = upload(id, "other", "just text".getBytes(StandardCharsets.US_ASCII), "id.jpg");
        assertThat(text.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(text.getBody().get("code").asString()).isEqualTo("unsupported_file_type");

        ResponseEntity<JsonNode> badKind = upload(id, "selfie", png(10, 10), "x.png");
        assertThat(badKind.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    /** Chapter 8 section 8.8: 5 MB per file, and no decoding of a canvas beyond 40 megapixels. */
    @Test
    void oversizedFilesAndImagesAreRefused() throws Exception {
        String id = member("Test Borrower 03", "0700000003", null, null);
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, big, 0, 5);
        ResponseEntity<JsonNode> tooBig = upload(id, "other", big, "big.pdf");
        assertThat(tooBig.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(tooBig.getBody().get("code").asString()).isEqualTo("file_too_large");

        ResponseEntity<JsonNode> bomb = upload(id, "photo", png(8000, 6000), "bomb.png");
        assertThat(bomb.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(bomb.getBody().get("code").asString()).isEqualTo("image_too_large");
    }

    /** FR-DOC-03, NFR-ISO-05: permission before any URL; the URL is refused when tampered with or expired; issuing is audited. */
    @Test
    void downloadUrlsAreCheckedSignedAndShortLived() throws Exception {
        String id = member("Test Borrower 04", "0700000004", null, null);
        String documentId = upload(id, "other", png(10, 10), "a.png")
                .getBody()
                .get("document_id")
                .asString();

        ResponseEntity<JsonNode> otherBranch =
                downloadUrl(documentId, headers(ALL, t.secondBranch().toString()));
        assertThat(otherBranch.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(downloadUrl(UUID.randomUUID().toString(), headers(ALL, "*")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        JsonNode issued = downloadUrl(documentId, headers(ALL, "*")).getBody();
        String url = issued.get("url").asString();
        assertThat(Instant.parse(issued.get("expires_at").asString()))
                .isAfter(Instant.now().plusSeconds(240));
        assertThat(fetch(url).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetch(url.replaceAll("sig=[0-9a-f]{4}", "sig=0000")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        String key = TestDatabase.owner()
                .sql("SELECT object_key FROM documents WHERE id = ?::uuid")
                .param(documentId)
                .query(String.class)
                .single();
        long past = Instant.now().minusSeconds(1).getEpochSecond();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(FAKE_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String name = "upload-" + documentId + ".png";
        String sig = HexFormat.of()
                .formatHex(mac.doFinal((key + "\n" + name + "\n" + past).getBytes(StandardCharsets.UTF_8)));
        String expired = "/api/v1/storage/fake?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&expires="
                + past + "&name=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&sig=" + sig;
        assertThat(fetch(expired).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        long issuedAudits = TestDatabase.owner()
                .sql(
                        "SELECT count(*) FROM audit_log WHERE entity_id = ?::uuid AND action = 'core.document.download_url_issued'")
                .param(documentId)
                .query(Long.class)
                .single();
        assertThat(issuedAudits).isEqualTo(1);
    }

    /** FR-MEM-05: the ID front image is the last input; the member moves to pending_verification, audited. */
    @Test
    void theIdImageCompletesKyc() throws Exception {
        String id = member("Test Borrower 05", "0700000005", "CMTEST0000005A", "Test Village B");
        Map<String, Object> kin = Map.of("full_name", "Test Kin 05", "relationship", "spouse");
        http.exchange(
                "/api/v1/lending/members/" + id + "/next-of-kin",
                HttpMethod.POST,
                new HttpEntity<>(kin, headers(ALL, "*")),
                JsonNode.class);
        upload(id, "photo", png(10, 10), "p.png");
        assertThat(kycStatus(id)).isEqualTo("incomplete");

        upload(id, "id_front", png(10, 10), "front.png");

        assertThat(kycStatus(id)).isEqualTo("pending_verification");
        long audits = TestDatabase.owner()
                .sql(
                        "SELECT count(*) FROM audit_log WHERE entity_id = ?::uuid AND action = 'lending.member.kyc_complete'")
                .param(id)
                .query(Long.class)
                .single();
        assertThat(audits).isEqualTo(1);
    }

    /** #23 review, blocker 1: a rejected member re-enters pending_verification with a new document, audited as a resubmission. */
    @Test
    void aRejectedMemberCanResubmitWithANewDocument() throws Exception {
        String id = member("Test Borrower 06", "0700000006", "CMTEST0000006A", "Test Village C");
        Map<String, Object> kin = Map.of("full_name", "Test Kin 06", "relationship", "sibling");
        http.exchange(
                "/api/v1/lending/members/" + id + "/next-of-kin",
                HttpMethod.POST,
                new HttpEntity<>(kin, headers(ALL, "*")),
                JsonNode.class);
        upload(id, "id_front", png(10, 10), "front.png");
        assertThat(kycStatus(id)).isEqualTo("pending_verification");
        TestDatabase.owner()
                .sql("UPDATE lending_members SET kyc_status = 'rejected' WHERE id = ?::uuid")
                .param(id)
                .update();

        upload(id, "id_front", png(12, 12), "front-clearer.png");

        assertThat(kycStatus(id)).isEqualTo("pending_verification");
        long resubmitted = TestDatabase.owner()
                .sql(
                        "SELECT count(*) FROM audit_log WHERE entity_id = ?::uuid AND action = 'lending.member.kyc_resubmitted'")
                .param(id)
                .query(Long.class)
                .single();
        assertThat(resubmitted).isEqualTo(1);
    }

    String kycStatus(String memberId) {
        return TestDatabase.owner()
                .sql("SELECT kyc_status FROM lending_members WHERE id = ?::uuid")
                .param(memberId)
                .query(String.class)
                .single();
    }

    /** #29: an empty file is a 422; JPEG bytes named .pdf are stored as JPEG (type from content). */
    @Test
    void emptyFilesAreRefusedAndJpegsAreJpegsWhateverTheirName() throws Exception {
        String id = member("Test Borrower 21", "0700000021", null, null);
        ResponseEntity<JsonNode> empty = upload(id, "other", new byte[0], "empty.pdf");
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);

        ResponseEntity<JsonNode> jpeg = upload(id, "other", jpegWithExif(), "statement.pdf");
        assertThat(jpeg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jpeg.getBody().get("content_type").asString()).isEqualTo("image/jpeg");
    }

    /** #29: the decode bound is 16 megapixels now (a 20 MP canvas is refused before decoding). */
    @Test
    void canvasesOverSixteenMegapixelsAreRefused() throws Exception {
        String id = member("Test Borrower 22", "0700000022", null, null);
        ResponseEntity<JsonNode> r = upload(id, "photo", png(5000, 4000), "big.png");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(r.getBody().get("code").asString()).isEqualTo("image_too_large");
    }

    /** #29, NFR-ISO-05: another tenant asking for this document's URL gets 404, never a URL. */
    @Test
    void anotherTenantCannotGetADownloadUrl() throws Exception {
        String id = member("Test Borrower 24", "0700000024", null, null);
        String documentId = upload(id, "other", png(4, 4), "o.png")
                .getBody()
                .get("document_id")
                .asString();
        TestDatabase.Fixture other = TestDatabase.tenant("docs-other", true);
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", other.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", ALL);
        h.add("X-Dev-Branch-Ids", "*");
        ResponseEntity<JsonNode> r = downloadUrl(documentId, h);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().has("url")).isFalse();
    }

    HttpHeaders as(UUID user, String permissions) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", user.toString());
        h.add("X-Dev-Permissions", permissions);
        h.add("X-Dev-Branch-Ids", "*");
        return h;
    }

    /** The permissions the chapter 8 matrix seeds for a role (the real set, not a hand-picked one). */
    static String permissionsOf(String role) {
        return TestDatabase.owner()
                .sql("SELECT string_agg(permission_key, ',') FROM role_permissions WHERE role_key = ?")
                .param(role)
                .query(String.class)
                .single();
    }

    HttpHeaders as(UUID user, String permissions, String branches) {
        HttpHeaders h = as(user, permissions);
        h.set("X-Dev-Branch-Ids", branches);
        return h;
    }

    long audits(String action, String documentId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM audit_log WHERE action = ? AND entity_id = ?::uuid")
                .params(action, documentId)
                .query(Long.class)
                .single();
    }

    /**
     * Chapter 8 section 8.3.2: ID images need lending.members.verify_kyc. Each role signs in with
     * the permission set the matrix seeds for it. A branch manager gets the URL (audited with who,
     * which member and which document); a cashier and an auditor get a clean 403 on both ID images,
     * each denial audited, and still download a photo; a branch manager of another branch is
     * refused too.
     */
    @Test
    void idImagesNeedVerifyKycPerRoleAndPerBranch() throws Exception {
        String id = member("Test Borrower 25", "0700000025", null, null);
        String front = upload(id, "id_front", png(4, 4), "f.png")
                .getBody()
                .get("document_id")
                .asString();
        String back = upload(id, "id_back", png(4, 4), "b.png")
                .getBody()
                .get("document_id")
                .asString();
        String photo = upload(id, "photo", png(4, 4), "p.png")
                .getBody()
                .get("document_id")
                .asString();
        String managerPermissions = permissionsOf("branch_manager");
        assertThat(managerPermissions).contains("lending.members.verify_kyc");

        UUID manager = UUID.randomUUID();
        assertThat(downloadUrl(front, as(manager, managerPermissions)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> row =
                TestDatabase.owner().sql("""
                        SELECT actor_user_id, data::text AS data FROM audit_log
                         WHERE action = 'core.document.download_url_issued' AND entity_id = ?::uuid
                        """).param(front).query().singleRow();
        assertThat(row.get("actor_user_id")).isEqualTo(manager);
        assertThat((String) row.get("data")).contains(id);

        ResponseEntity<JsonNode> otherBranch = downloadUrl(
                front,
                as(UUID.randomUUID(), managerPermissions, t.secondBranch().toString()));
        assertThat(otherBranch.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(otherBranch.getBody().has("url")).isFalse();

        long deniedBefore = audits("core.document.access_denied", front);
        for (String role : new String[] {"cashier", "auditor"}) {
            String permissions = permissionsOf(role);
            assertThat(permissions)
                    .as("the %s set in the matrix", role)
                    .contains("lending.members.read")
                    .doesNotContain("lending.members.verify_kyc");
            HttpHeaders h = as(UUID.randomUUID(), permissions);
            for (String idImage : new String[] {front, back}) {
                ResponseEntity<JsonNode> denied = downloadUrl(idImage, h);
                assertThat(denied.getStatusCode()).as(role + " on an ID image").isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(denied.getBody().get("code").asString()).isEqualTo("permission_denied");
                assertThat(denied.getBody().has("url")).isFalse();
            }
            assertThat(downloadUrl(photo, h).getStatusCode())
                    .as(role + " on a photo")
                    .isEqualTo(HttpStatus.OK);

            // The list shows ID images only to those who may open them.
            JsonNode listed = http.exchange(
                            "/api/v1/lending/members/" + id + "/documents",
                            HttpMethod.GET,
                            new HttpEntity<>(h),
                            JsonNode.class)
                    .getBody()
                    .get("items");
            assertThat(listed.findValuesAsString("doc_kind")).as(role + " list").containsExactly("photo");
        }
        assertThat(audits("core.document.access_denied", front) - deniedBefore).isEqualTo(2);

        JsonNode managerList = http.exchange(
                        "/api/v1/lending/members/" + id + "/documents",
                        HttpMethod.GET,
                        new HttpEntity<>(as(manager, managerPermissions)),
                        JsonNode.class)
                .getBody()
                .get("items");
        assertThat(managerList.findValuesAsString("doc_kind"))
                .containsExactlyInAnyOrder("id_front", "id_back", "photo");
    }

    /** A member document with no link row has no known kind: denied, never read on the weaker permission. */
    @Test
    void aDocumentWithNoKnownKindIsDenied() throws Exception {
        String id = member("Test Borrower 26", "0700000026", null, null);
        UUID orphanRow = UUID.randomUUID();
        TestDatabase.owner()
                .sql("""
                        INSERT INTO documents (id, tenant_id, branch_id, doc_type, subject_type, subject_id, object_key,
                                               content_type, size_bytes, sha256)
                        VALUES (?, ?, ?, 'upload', 'lending.member', ?::uuid, ?, 'image/png', 4, repeat('0', 64))
                        """)
                .params(
                        orphanRow,
                        t.tenantId(),
                        t.headOffice(),
                        id,
                        "tenants/" + t.tenantId() + "/upload/x/" + orphanRow + ".png")
                .update();
        ResponseEntity<JsonNode> r =
                downloadUrl(orphanRow.toString(), as(UUID.randomUUID(), permissionsOf("branch_manager")));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(r.getBody().has("url")).isFalse();
    }

    long links(String memberId, String kind, boolean active) {
        return TestDatabase.owner()
                .sql(
                        "SELECT count(*) FROM lending_member_documents WHERE member_id = ?::uuid AND doc_kind = ? AND (superseded_at IS NULL) = ?")
                .params(memberId, kind, active)
                .query(Long.class)
                .single();
    }

    /** An upload beyond ten of a kind supersedes the oldest (kept for audit); a wrong file can always be replaced. */
    @Test
    void theEleventhDocumentOfAKindSupersedesTheOldest() throws Exception {
        String id = member("Test Borrower 23", "0700000023", null, null);
        byte[] tiny = png(4, 4);
        String first = upload(id, "id_front", tiny, "o.png")
                .getBody()
                .get("document_id")
                .asString();
        for (int i = 1; i < 10; i++) {
            assertThat(upload(id, "id_front", tiny, "o.png").getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        ResponseEntity<JsonNode> eleventh = upload(id, "id_front", tiny, "o.png");
        assertThat(eleventh.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(links(id, "id_front", true)).isEqualTo(10);
        assertThat(links(id, "id_front", false)).isEqualTo(1);
        String supersededDocument = TestDatabase.owner()
                .sql(
                        "SELECT document_id::text FROM lending_member_documents WHERE member_id = ?::uuid AND superseded_at IS NOT NULL")
                .param(id)
                .query(String.class)
                .single();
        assertThat(supersededDocument).isEqualTo(first);
        // Kept for the audit trail: those who may read it can still open the superseded file.
        assertThat(downloadUrl(first, as(UUID.randomUUID(), permissionsOf("branch_manager")))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    /** The limit holds under concurrency: the member row lock serialises one member's uploads. */
    @Test
    void concurrentUploadsNeverLeaveMoreThanTenActive() throws Exception {
        String id = member("Test Borrower 27", "0700000027", null, null);
        byte[] tiny = png(4, 4);
        int n = 14;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<HttpStatus> task = () -> {
                start.await();
                return HttpStatus.valueOf(
                        upload(id, "other", tiny, "o.png").getStatusCode().value());
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        for (Future<HttpStatus> f : results) {
            assertThat(f.get()).isEqualTo(HttpStatus.CREATED);
        }
        pool.shutdown();
        assertThat(links(id, "other", true)).isEqualTo(10);
        assertThat(links(id, "other", false)).isEqualTo(4);
    }

    /** A small upload that grows past 5 MB when re-encoded is refused: the stored bytes obey the limit. */
    @Test
    void anImageThatGrowsPastTheLimitWhenReEncodedIsRefused() throws Exception {
        String id = member("Test Borrower 28", "0700000028", null, null);
        BufferedImage noise = new BufferedImage(3600, 2700, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < noise.getWidth(); x++) {
                noise.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream small = new ByteArrayOutputStream();
        javax.imageio.ImageWriter writer =
                ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.4f); // measured: 3.2 MB in, 7.6 MB after re-encoding at 0.92
        try (javax.imageio.stream.ImageOutputStream out = ImageIO.createImageOutputStream(small)) {
            writer.setOutput(out);
            writer.write(null, new javax.imageio.IIOImage(noise, null, null), param);
        } finally {
            writer.dispose();
        }
        assertThat(small.size()).as("the upload itself is under the limit").isLessThan(5 * 1024 * 1024);

        ResponseEntity<JsonNode> r = upload(id, "photo", small.toByteArray(), "noise.jpg");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(r.getBody().get("code").asString()).isEqualTo("file_too_large");
    }
}
