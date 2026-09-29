package com.rincoltech.bms.core.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * One-time tokens and recovery codes (chapter 8 section 8.2): random, shown once, stored as
 * SHA-256 hashes only.
 */
final class Secrets {

    static final int RECOVERY_CODES = 10;
    /** No 0, 1, I or O: a recovery code is typed by hand from paper. */
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {}

    /** 256 random bits, base64url: invitation links, refresh tokens. */
    static String token() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ten codes of the form {@code XXXXX-XXXXX} (50 random bits each). */
    static List<String> recoveryCodes() {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder code = new StringBuilder();
            for (int j = 0; j < 10; j++) {
                if (j == 5) {
                    code.append('-');
                }
                code.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
            }
            codes.add(code.toString());
        }
        return codes;
    }

    /** Hash of a recovery code as typed: case, spaces and dashes do not matter. */
    static String recoveryCodeHash(String typed) {
        return sha256(typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT));
    }

    static boolean looksLikeRecoveryCode(String typed) {
        return typed != null && typed.replaceAll("[\\s-]", "").matches("^[A-Za-z0-9]{10}$");
    }
}
