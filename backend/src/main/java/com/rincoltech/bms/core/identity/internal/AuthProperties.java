package com.rincoltech.bms.core.identity.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Authentication settings (chapter 8 sections 8.2 and 8.7, ADR-014). Keys come from the host env
 * file; the dev and test profiles may leave them blank, in which case an ephemeral key is
 * generated at startup (every restart then signs everyone out). Any other profile refuses to
 * start without them.
 *
 * @param mode {@code none} or {@code dev}: whether the development header stub of chapter 7
 *     section 7.4.3 is also accepted; signed-in sessions work in both
 * @param signingJwk the access token signing key: an EC P-256 private JWK with a {@code kid}
 *     ({@code BMS_TOKEN_SIGNING_JWK})
 * @param dataKey the application data key, 32 bytes, base64 ({@code BMS_DATA_KEY}); encrypts TOTP
 *     secrets
 * @param dataKeyId identifies the data key inside each ciphertext ({@code BMS_DATA_KEY_ID})
 * @param argon2MemoryKib argon2id memory cost; 65536 (64 MiB) outside tests
 * @param argon2Iterations argon2id iterations; 3 outside tests
 */
@ConfigurationProperties("bms.auth")
record AuthProperties(
        String mode,
        String signingJwk,
        String dataKey,
        String dataKeyId,
        Integer argon2MemoryKib,
        Integer argon2Iterations) {

    AuthProperties {
        mode = mode == null ? "none" : mode;
        dataKeyId = dataKeyId == null || dataKeyId.isBlank() ? "k1" : dataKeyId;
        argon2MemoryKib = argon2MemoryKib == null ? 65536 : argon2MemoryKib;
        argon2Iterations = argon2Iterations == null ? 3 : argon2Iterations;
    }
}
