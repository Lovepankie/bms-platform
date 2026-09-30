package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.kernel.BusinessClock;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Chooses the object storage adapter (chapter 12 section 12.7): {@code r2} on servers, {@code fake}
 * only in the dev and test profiles. Startup fails on anything else, and on an r2 setting left empty.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageConfiguration.StorageProperties.class)
class StorageConfiguration {

    /**
     * @param fakeSecret HMAC key of the fake's signed URLs; random per start when empty
     */
    @ConfigurationProperties("bms.storage")
    record StorageProperties(String provider, R2 r2, String fakeSecret) {

        record R2(String endpoint, String accessKeyId, String secretAccessKey, String bucket) {}
    }

    /** {@code Cache-Control} for every signed download. */
    static final String NO_STORE = "private, no-store";

    /** {@code Content-Disposition} for every signed download; the name is server-made, but quote-safe anyway. */
    static String attachment(String name) {
        return "attachment; filename=\"" + name.replaceAll("[\"\\\\\\r\\n]", "_") + "\"";
    }

    private static boolean allBlank(StorageProperties.R2 r2) {
        return r2 == null
                || (blank(r2.endpoint())
                        && blank(r2.accessKeyId())
                        && blank(r2.secretAccessKey())
                        && blank(r2.bucket()));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @Bean
    ObjectStorage objectStorage(StorageProperties properties, Environment environment, BusinessClock clock) {
        String provider = properties.provider() == null ? "" : properties.provider();
        return switch (provider) {
            case "fake" -> {
                if (!environment.acceptsProfiles(Profiles.of("dev", "test"))) {
                    throw new IllegalStateException("OBJECT_STORAGE=fake is only allowed in the dev and test profiles");
                }
                String secret = properties.fakeSecret() == null
                                || properties.fakeSecret().isBlank()
                        ? HexFormat.of().formatHex(new SecureRandom().generateSeed(32))
                        : properties.fakeSecret();
                yield new FakeObjectStorage(secret, clock);
            }
            case "r2" -> {
                if (allBlank(properties.r2())) {
                    // Hillary's #23 review, blocker 2: main auto-deploys to staging, so an unset bucket must
                    // not crash-loop the app. Documents answer 503 until R2 is configured; a PARTIAL r2
                    // setting still fails startup below, as a typo should.
                    LoggerFactory.getLogger(StorageConfiguration.class)
                            .warn(
                                    "OBJECT_STORAGE=r2 but no R2 settings are set: document upload and download will answer 503 "
                                            + "until R2_ENDPOINT, R2_DOCUMENTS_ACCESS_KEY_ID, R2_DOCUMENTS_SECRET_ACCESS_KEY and "
                                            + "R2_DOCUMENTS_BUCKET are provided");
                    yield new UnconfiguredObjectStorage();
                }
                yield R2ObjectStorage.create(properties.r2());
            }
            default ->
                throw new IllegalStateException("bms.storage.provider must be 'r2' or 'fake', not '" + provider + "'");
        };
    }
}
