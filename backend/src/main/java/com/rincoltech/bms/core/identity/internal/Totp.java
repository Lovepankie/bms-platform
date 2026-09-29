package com.rincoltech.bms.core.identity.internal;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.commons.codec.binary.Base32;

/**
 * Time-based one-time passwords, RFC 6238 with the defaults every authenticator app supports:
 * HMAC-SHA1, 6 digits, 30 second steps, a 160 bit secret (ADR-014). One step either side is
 * accepted for clock drift, and the caller refuses a step at or before the last one used, so a
 * code works once.
 */
final class Totp {

    static final int DIGITS = 6;
    static final long STEP_SECONDS = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {}

    static byte[] newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return secret;
    }

    static String base32(byte[] secret) {
        return new Base32().encodeToString(secret).replace("=", "");
    }

    /** The {@code otpauth://} URI an authenticator app scans. */
    static String uri(String issuer, String account, byte[] secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + base32(secret) + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    static long step(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
    }

    static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = h[h.length - 1] & 0x0f;
            int binary = ((h[offset] & 0x7f) << 24)
                    | ((h[offset + 1] & 0xff) << 16)
                    | ((h[offset + 2] & 0xff) << 8)
                    | (h[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @return the matching step within one step of {@code at}, later than {@code lastUsedStep}
     */
    static OptionalLong verify(byte[] secret, String code, Instant at, Long lastUsedStep) {
        if (code == null || !code.matches("^[0-9]{6}$")) {
            return OptionalLong.empty();
        }
        long now = step(at);
        for (long s = now - 1; s <= now + 1; s++) {
            boolean fresh = lastUsedStep == null || s > lastUsedStep;
            if (fresh && MessageDigest.isEqual(code(secret, s).getBytes(), code.getBytes())) {
                return OptionalLong.of(s);
            }
        }
        return OptionalLong.empty();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
