package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.QuarantineStorage;
import com.rincoltech.bms.kernel.TenantContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The purge of a removed business when storage refuses to delete one of its quarantined import files (review of #790):
 * the purge stops with an error, so the closure job leaves the business's rows and runs it again the next night, and the
 * next run deletes the file. The happy path is in {@code BusinessClosureIT}. Fabricated ids and bytes only.
 */
class QuarantinePurgeIT extends IntegrationTest {

    @Autowired
    Documents documents;

    @Autowired
    QuarantineStorage quarantine;

    @Autowired
    ObjectStorage storage;

    FakeObjectStorage fake() {
        return (FakeObjectStorage) storage;
    }

    @AfterEach
    void forgetRefusals() {
        fake().failDeletes.clear();
    }

    @Test
    void aRefusedQuarantineDeleteStopsThePurgeAndTheNextRunDeletesIt() throws Exception {
        TestDatabase.Fixture t = TestDatabase.tenant("purge-quarantine", false);
        Path file = Files.createTempFile("purge-quarantine", ".bin");
        Files.write(file, new byte[] {1, 2, 3});
        String key =
                TenantContext.callAs(t.tenantId(), () -> quarantine.put(UUID.randomUUID(), UUID.randomUUID(), file));
        String other =
                TenantContext.callAs(t.tenantId(), () -> quarantine.put(UUID.randomUUID(), UUID.randomUUID(), file));
        Files.delete(file);

        fake().failDeletes.add(key);
        assertThatThrownBy(() -> TenantContext.callAs(t.tenantId(), documents::deleteAllStoredFiles))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(key);
        assertThat(fake().has(key)).as("the refused file is still there").isTrue();
        assertThat(fake().has(other)).as("the others went").isFalse();

        // The next night's run: storage takes the delete now.
        fake().failDeletes.clear();
        assertThat(TenantContext.callAs(t.tenantId(), documents::deleteAllStoredFiles))
                .isEqualTo(1);
        assertThat(fake().has(key)).isFalse();
        assertThat(TenantContext.callAs(t.tenantId(), () -> quarantine.list().toList()))
                .isEmpty();
    }
}
