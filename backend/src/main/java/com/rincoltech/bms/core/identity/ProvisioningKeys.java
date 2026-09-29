package com.rincoltech.bms.core.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * New key material for a host env file (chapter 8 section 8.7): {@code java -jar bms-api.jar keys}
 * prints a fresh access token signing key and a fresh application data key, and nothing is kept.
 * Run once per environment; never share keys between staging and production.
 */
public final class ProvisioningKeys {

    private ProvisioningKeys() {}

    public static String envLines() {
        try {
            String jwk = new ECKeyGenerator(Curve.P_256)
                    .keyID("k-" + UUID.randomUUID().toString().substring(0, 8))
                    .generate()
                    .toJSONString();
            byte[] dataKey = new byte[32];
            new SecureRandom().nextBytes(dataKey);
            return "BMS_TOKEN_SIGNING_JWK='" + jwk + "'\n"
                    + "BMS_DATA_KEY=" + Base64.getEncoder().encodeToString(dataKey) + "\n"
                    + "BMS_DATA_KEY_ID=k1\n";
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
