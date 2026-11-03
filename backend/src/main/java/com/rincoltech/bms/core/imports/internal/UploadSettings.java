package com.rincoltech.bms.core.imports.internal;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.unit.DataSize;

/**
 * The upload's configuration (issue #128, spec section 3.1): the limits, raised only after the big file test passes at the
 * new size; the directory of the encrypted temporary copies; the age after which the cleanup removes a quarantined file no
 * batch names; and the platform import key, {@code BMS_IMPORT_KEY}, 32 bytes in base64, never in the repository. In the dev
 * and test profiles a missing key is made at each start; on a server without one the upload answers 503
 * {@code imports_key_missing} and everything else starts, as storage does without its bucket. A key that is set but not 32
 * bytes of base64 fails the start.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UploadSettings.Properties.class)
class UploadSettings {

    @ConfigurationProperties("bms.imports")
    record Properties(
            DataSize maxFileSize, Integer maxRows, Integer maxColumns, String key, String workDir, Duration orphanAge) {

        Properties {
            maxFileSize = maxFileSize == null ? DataSize.ofMegabytes(25) : maxFileSize;
            maxRows = maxRows == null ? 100_000 : maxRows;
            maxColumns = maxColumns == null ? 200 : maxColumns;
            workDir = workDir == null || workDir.isBlank() ? System.getProperty("java.io.tmpdir") : workDir;
            orphanAge = orphanAge == null ? Duration.ofHours(24) : orphanAge;
        }

        Path work() {
            return Path.of(workDir);
        }
    }

    /** The platform import key, when this server has one. */
    record ImportKey(Optional<byte[]> bytes) {}

    @Bean
    ImportKey importKey(Properties properties, Environment environment) {
        String key = properties.key();
        if (key == null || key.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("dev", "test"))) {
                byte[] random = new byte[QuarantineCipher.KEY_BYTES];
                new SecureRandom().nextBytes(random);
                return new ImportKey(Optional.of(random));
            }
            LoggerFactory.getLogger(UploadSettings.class)
                    .warn("BMS_IMPORT_KEY is not set: import uploads will answer 503 until it is provided");
            return new ImportKey(Optional.empty());
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("BMS_IMPORT_KEY must be 32 bytes in base64");
        }
        if (decoded.length != QuarantineCipher.KEY_BYTES) {
            throw new IllegalStateException("BMS_IMPORT_KEY must be 32 bytes in base64");
        }
        return new ImportKey(Optional.of(decoded));
    }
}
