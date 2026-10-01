package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.Documents;
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
}
