package com.rincoltech.bms.core.identity.internal;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.security.SecureRandom;
import java.text.ParseException;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Loads the token signing key and the application data key (chapter 8 section 8.7). Outside the
 * dev and test profiles both are required; in those two profiles a missing key is generated for
 * the life of the process.
 */
@Configuration(proxyBeanMethods = false)
class KeyMaterial {

    private static final Logger log = LoggerFactory.getLogger(KeyMaterial.class);

    @Bean
    AccessTokens accessTokens(AuthProperties properties, Environment environment) {
        String jwk = properties.signingJwk();
        if (jwk == null || jwk.isBlank()) {
            requireDevOrTest(environment, "BMS_TOKEN_SIGNING_JWK");
            log.warn("no token signing key configured; using an ephemeral key (dev and test only)");
            return new AccessTokens(generateSigningKey());
        }
        try {
            return new AccessTokens(ECKey.parse(jwk));
        } catch (ParseException e) {
            throw new IllegalStateException("BMS_TOKEN_SIGNING_JWK is not an EC JWK", e);
        }
    }

    @Bean
    SecretBox secretBox(AuthProperties properties, Environment environment) {
        String key = properties.dataKey();
        if (key == null || key.isBlank()) {
            requireDevOrTest(environment, "BMS_DATA_KEY");
            log.warn("no data key configured; using an ephemeral key (dev and test only)");
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            return new SecretBox(random, "ephemeral");
        }
        return new SecretBox(Base64.getDecoder().decode(key), properties.dataKeyId());
    }

    /** A new P-256 signing key with a key id, for tests and the ephemeral dev and test key. */
    static ECKey generateSigningKey() {
        try {
            return new ECKeyGenerator(Curve.P_256)
                    .keyID("k-" + UUID.randomUUID().toString().substring(0, 8))
                    .generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requireDevOrTest(Environment environment, String variable) {
        if (!environment.acceptsProfiles(Profiles.of("dev", "test"))) {
            throw new IllegalStateException(variable + " must be set outside the dev and test profiles");
        }
    }
}
