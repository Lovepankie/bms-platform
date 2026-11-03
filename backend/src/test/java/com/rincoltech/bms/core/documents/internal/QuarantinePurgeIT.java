package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.documents.Documents;
import com.rincoltech.bms.core.documents.QuarantineStorage;
import com.rincoltech.bms.kernel.TenantContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The purge of a removed business when storage refuses to delete one of its quarantined import files (second review of
 * #790): the purge stops with an error, logs how many files remain and never a key, and the next run, once storage takes
 * the delete, removes them. {@code BusinessClosureIT} shows the files deleted when storage takes every delete.
 */
@ExtendWith(OutputCaptureExtension.class)
class QuarantinePurgeIT extends IntegrationTest {

    @Autowired
    ObjectStorage storage;

    @Autowired
    Documents documents;

    @Autowired
    QuarantineStorage quarantine;

    FakeObjectStorage fake() {
        return (FakeObjectStorage) storage;
    }

    @AfterEach
    void storageTakesDeletesAgain() {
        fake().failDeletes.clear();
    }

    @Test
    void aRefusedDeleteStopsThePurgeWhichRemovesTheFileOnItsNextRun(CapturedOutput output) throws IOException {
        TestDatabase.Fixture t = TestDatabase.tenant("purge-quarantine", false);
        Path file = Files.createTempFile("purge-quarantine", ".bin");
        Files.write(file, new byte[] {1, 2, 3});
        String key =
                TenantContext.callAs(t.tenantId(), () -> quarantine.put(UUID.randomUUID(), UUID.randomUUID(), file));
        Files.delete(file);

        fake().failDeletes.add(key);
        assertThatThrownBy(() -> TenantContext.callAs(t.tenantId(), documents::deleteAllStoredFiles))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fake().has(key)).as("the refused file is still there").isTrue();
        assertThat(output.getAll())
                .contains("1 quarantined import files of a purged business could not be deleted")
                .doesNotContain(key);

        // The next night storage takes it.
        fake().failDeletes.clear();
        assertThat(TenantContext.callAs(t.tenantId(), documents::deleteAllStoredFiles))
                .isEqualTo(1);
        assertThat(fake().has(key)).isFalse();
    }
}
