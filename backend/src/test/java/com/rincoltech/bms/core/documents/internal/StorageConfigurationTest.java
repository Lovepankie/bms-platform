package com.rincoltech.bms.core.documents.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.kernel.ApiException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;

/** Hillary's #23 review, blocker 2: an unconfigured bucket must not stop the app from starting. */
class StorageConfigurationTest {

    private static ObjectStorage storageFor(StorageConfiguration.StorageProperties.R2 r2) {
        return new StorageConfiguration()
                .objectStorage(new StorageConfiguration.StorageProperties("r2", r2, null), new MockEnvironment(), null);
    }

    @Test
    void anUnconfiguredBucketStartsAndAnswers503OnUse() {
        ObjectStorage storage = storageFor(new StorageConfiguration.StorageProperties.R2("", "", "", ""));

        assertThat(storage).isInstanceOf(UnconfiguredObjectStorage.class);
        assertThatThrownBy(() -> storage.put("k", new byte[] {1}, "image/png"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getMessage()).contains("not configured"))
                .extracting("status")
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThatThrownBy(() -> storage.signedGetUrl("k", Duration.ofMinutes(5), "a.pdf", "application/pdf"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aPartialR2SettingStillFailsStartup() {
        assertThatThrownBy(() -> storageFor(new StorageConfiguration.StorageProperties.R2("https://x", "", "", "b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("R2_ENDPOINT");
    }

    @Test
    void theDownloadHeaderIsAnAttachmentAndQuoteSafe() {
        assertThat(StorageConfiguration.attachment("id_front-1.pdf"))
                .isEqualTo("attachment; filename=\"id_front-1.pdf\"");
        assertThat(StorageConfiguration.attachment("a\"b\r\nc")).isEqualTo("attachment; filename=\"a_b__c\"");
    }
}
