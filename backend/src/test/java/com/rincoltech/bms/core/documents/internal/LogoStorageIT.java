package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.Documents.ImagePolicy;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.ImageFixtures;
import java.awt.Color;
import java.net.URI;
import java.util.UUID;
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
 * What the public logo route costs storage (FR-TEN-08): a conditional request reads nothing, a
 * logo is read once and then served from memory, an outage is a 503 and not a 500; and the
 * public-asset read of the documents module only ever returns the tenant's own logo subject. Lives in
 * this package for the fake storage's counters. Fabricated images.
 */
class LogoStorageIT extends IntegrationTest {

    static final String SUBJECT = "core.tenant";

    @Autowired
    TestRestTemplate http;

    @Autowired
    FakeObjectStorage storage;

    @Autowired
    Documents documentsApi;

    DocumentService documents;

    TestDatabase.Fixture t;

    @BeforeEach
    void setUp() {
        t = TestDatabase.tenant("logo-storage", true);
        storage.failGets = false;
        documents = org.springframework.test.util.AopTestUtils.getTargetObject(documentsApi);
    }

    HttpHeaders admin() {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", UUID.randomUUID().toString());
        h.add("X-Dev-Permissions", "core.settings.read,core.settings.manage");
        h.add("X-Dev-Branch-Ids", "*");
        return h;
    }

    void upload() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(ImageFixtures.png(200, 200, Color.BLUE)) {
            @Override
            public String getFilename() {
                return "logo.png";
            }
        });
        HttpHeaders h = admin();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> r =
                http.exchange("/api/v1/settings/logo", HttpMethod.PUT, new HttpEntity<>(form, h), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    ResponseEntity<byte[]> logo(String... extra) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        for (int i = 0; i + 1 < extra.length; i += 2) {
            h.add(extra[i], extra[i + 1]);
        }
        return http.exchange(
                URI.create(http.getRootUri() + "/api/v1/branding/logo"),
                HttpMethod.GET,
                new HttpEntity<>(h),
                byte[].class);
    }

    @Test
    void aConditionalRequestReadsNoStorageAndALogoIsReadOnce() {
        upload();
        int before = storage.gets.get();
        String etag = logo().getHeaders().getETag();
        int afterFirst = storage.gets.get();
        assertThat(afterFirst - before).isLessThanOrEqualTo(1);

        for (int i = 0; i < 5; i++) {
            assertThat(logo("If-None-Match", etag).getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
            assertThat(logo().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(storage.gets.get()).as("storage reads after the first").isEqualTo(afterFirst);
    }

    @Test
    void aStorageOutageIsA503OnThePublicRoute() {
        upload();
        storage.gets.set(0);
        storage.failGets = true;
        try {
            // A fresh logo, so the in-process cache cannot answer.
            upload();
            ResponseEntity<byte[]> r = logo();
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            storage.failGets = false;
        }
        assertThat(logo().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** The public-asset read cannot be used on a document of another subject, or of another tenant. */
    @Test
    void thePublicAssetReadIsLimitedToTheTenantsOwnSubject() {
        TestDatabase.Fixture other = TestDatabase.tenant("logo-storage-b", true);
        UUID memberDoc = insertDocument(t, "lending.member", t.tenantId());
        UUID foreignLogo = insertDocument(other, SUBJECT, other.tenantId());
        UUID ownLogo = insertDocument(t, SUBJECT, t.tenantId());
        TenantContext.callAs(t.tenantId(), () -> {
            assertThat(documentsApi.publicAssetMeta(SUBJECT, ownLogo)).isPresent();
            assertThat(documentsApi.publicAssetMeta(SUBJECT, memberDoc)).isEmpty();
            assertThat(documentsApi.publicAssetMeta("lending.member", memberDoc))
                    .isPresent();
            assertThat(documentsApi.publicAssetMeta(SUBJECT, foreignLogo)).isEmpty();
            assertThat(documentsApi.publicAssetBytes(SUBJECT, memberDoc)).isEmpty();
            assertThat(documentsApi.publicAssetBytes(SUBJECT, foreignLogo)).isEmpty();
            return null;
        });
    }

    /** NFR: image re-encoding is bounded; a caller that cannot get a slot in time gets 503 uploads_busy. */
    @Test
    void whenEveryReencodeSlotIsTakenAnImageUploadIsBusy() throws Exception {
        documents.reencodes.acquire(DocumentService.MAX_CONCURRENT_REENCODES);
        try {
            ImagePolicy policy = new ImagePolicy(1024 * 1024, 128, 512, 4_000_000L);
            assertThatThrownBy(() -> documents.prepareImage(ImageFixtures.png(200, 200, Color.RED), policy))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.code()).isEqualTo("uploads_busy");
                    });
        } finally {
            documents.reencodes.release(DocumentService.MAX_CONCURRENT_REENCODES);
        }
        assertThat(documents.prepareImage(
                        ImageFixtures.png(200, 200, Color.RED), new ImagePolicy(1024 * 1024, 128, 512, 4_000_000L)))
                .isNotNull();
    }

    private UUID insertDocument(TestDatabase.Fixture tenant, String subjectType, UUID subjectId) {
        UUID id = UUID.randomUUID();
        String key = "tenants/" + tenant.tenantId() + "/upload/test/" + id + ".png";
        storage.put(key, ImageFixtures.png(130, 130, Color.GREEN), "image/png");
        TestDatabase.owner()
                .sql("""
                        INSERT INTO documents (id, tenant_id, doc_type, subject_type, subject_id, object_key,
                                               content_type, size_bytes, sha256)
                        VALUES (?, ?, 'upload', ?, ?, ?, 'image/png', 5, repeat('2', 64))
                        """)
                .params(id, tenant.tenantId(), subjectType, subjectId, key)
                .update();
        return id;
    }
}
