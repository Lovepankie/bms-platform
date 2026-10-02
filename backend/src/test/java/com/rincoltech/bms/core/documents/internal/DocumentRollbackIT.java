package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.kernel.TenantContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** #29: an object stored by a transaction that rolls back is deleted again, so none is orphaned. */
class DocumentRollbackIT extends IntegrationTest {

    @Autowired
    Documents documents;

    @Autowired
    ObjectStorage storage;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void aRolledBackUploadLeavesNoObjectBehind() throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("orphan", true);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", png);
        FakeObjectStorage fake = (FakeObjectStorage) storage;
        int before = fake.objectCount();

        CurrentPrincipal.set(Principal.uniform(UUID.randomUUID(), "staff", Set.of(), true, Set.of()));
        try {
            TenantContext.callAs(
                    t.tenantId(),
                    () -> new TransactionTemplate(transactions).execute(status -> {
                        documents.upload(new Documents.Upload(
                                "test.subject", UUID.randomUUID(), t.headOffice(), png.toByteArray()));
                        assertThat(fake.objectCount()).isEqualTo(before + 1);
                        status.setRollbackOnly();
                        return null;
                    }));
        } finally {
            CurrentPrincipal.clear();
        }

        assertThat(fake.objectCount()).isEqualTo(before);
    }

    /** A real failure, not a manual rollback: the row insert violates the branch key after the object is stored. */
    @Test
    void aFailingInsertLeavesNoObjectBehind() throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("orphan-fk", true);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", png);
        FakeObjectStorage fake = (FakeObjectStorage) storage;
        int before = fake.objectCount();
        UUID noSuchBranch = UUID.randomUUID();

        CurrentPrincipal.set(Principal.uniform(UUID.randomUUID(), "staff", Set.of(), true, Set.of()));
        try {
            assertThatThrownBy(() -> TenantContext.callAs(
                            t.tenantId(),
                            () -> documents.upload(new Documents.Upload(
                                    "test.subject", UUID.randomUUID(), noSuchBranch, png.toByteArray()))))
                    .isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            CurrentPrincipal.clear();
        }

        assertThat(fake.objectCount()).isEqualTo(before);
    }

    /** With every re-encode slot taken, an image upload waits its 10 seconds and gets 503 uploads_busy. */
    @Test
    void anImageWaitsForASlotAndThenGets503() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", png);
        // The bean is proxied for its transactions; the slots live on the target object.
        DocumentService service = (DocumentService) AopTestUtils.getTargetObject(documents);
        service.reencodes.acquire(DocumentService.MAX_CONCURRENT_REENCODES);
        try {
            assertThatThrownBy(() -> documents.prepare(png.toByteArray()))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.code()).isEqualTo("uploads_busy");
                    });
            // A PDF is not re-encoded, so it needs no slot.
            assertThat(documents
                            .prepare("%PDF-1.4\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                            .contentType())
                    .isEqualTo("application/pdf");
        } finally {
            service.reencodes.release(DocumentService.MAX_CONCURRENT_REENCODES);
        }
    }
}
