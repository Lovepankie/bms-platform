package com.rincoltech.bms.core.identity.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Field encryption with the application data key (chapter 8 section 8.7): AES-256-GCM with a
 * random 96 bit nonce. The ciphertext carries the key id, so a rotation can re-encrypt row by row:
 * {@code [1: format 1][1: key id length][key id][12: nonce][ciphertext and 16 byte tag]}.
 */
final class SecretBox {

    private static final byte FORMAT = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;
    private final byte[] keyId;

    SecretBox(byte[] key, String keyId) {
        if (key.length != 32) {
            throw new IllegalArgumentException("the data key must be 32 bytes");
        }
        this.key = key.clone();
        this.keyId = keyId.getBytes(StandardCharsets.UTF_8);
        if (this.keyId.length == 0 || this.keyId.length > 64) {
            throw new IllegalArgumentException("the data key id must be 1 to 64 bytes");
        }
    }

    byte[] seal(byte[] plaintext) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(keyId);
            byte[] sealed = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(2 + keyId.length + nonce.length + sealed.length)
                    .put(FORMAT)
                    .put((byte) keyId.length)
                    .put(keyId)
                    .put(nonce)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    byte[] open(byte[] box) {
        try {
            ByteBuffer in = ByteBuffer.wrap(box);
            if (in.get() != FORMAT) {
                throw new IllegalStateException("unknown ciphertext format");
            }
            byte[] id = new byte[in.get()];
            in.get(id);
            if (!Arrays.equals(id, keyId)) {
                throw new IllegalStateException("ciphertext was sealed with another data key");
            }
            byte[] nonce = new byte[12];
            in.get(nonce);
            byte[] sealed = new byte[in.remaining()];
            in.get(sealed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(keyId);
            return cipher.doFinal(sealed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed", e);
        }
    }
}
