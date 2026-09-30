package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.kernel.BusinessClock;
import java.security.SecureRandom;
import java.util.HexFormat;
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
            case "r2" -> R2ObjectStorage.create(properties.r2());
            default ->
                throw new IllegalStateException("bms.storage.provider must be 'r2' or 'fake', not '" + provider + "'");
        };
    }
}
