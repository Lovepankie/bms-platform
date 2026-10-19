package com.rincoltech.bms.core.identity.internal;

import com.rincoltech.bms.core.identity.DataKeys;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Field encryption with the application data key (chapter 8 section 8.7): AES-256-GCM with a
 * random 96 bit nonce. The ciphertext carries the key id, so a rotation can re-encrypt row by row:
 * {@code [1: format 1][1: key id length][key id][12: nonce][ciphertext and 16 byte tag]}.
 */
final class SecretBox implements DataKeys {

    private static final byte FORMAT = 1;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;
    private final byte[] keyId;
    private final String keyIdText;
    /** Retired keys, by id: they open old boxes during a rotation and never seal. */
    private final Map<String, byte[]> retired;

    SecretBox(byte[] key, String keyId) {
        this(key, keyId, Map.of());
    }

    SecretBox(byte[] key, String keyId, Map<String, byte[]> retired) {
        if (key.length != 32) {
            throw new IllegalArgumentException("the data key must be 32 bytes");
        }
        this.key = key.clone();
        this.keyIdText = keyId;
        this.keyId = keyId.getBytes(StandardCharsets.UTF_8);
        if (this.keyId.length == 0 || this.keyId.length > 64) {
            throw new IllegalArgumentException("the data key id must be 1 to 64 bytes");
        }
        Map<String, byte[]> copy = new LinkedHashMap<>();
        retired.forEach((id, k) -> {
            if (k.length != 32 || id.isEmpty() || id.getBytes(StandardCharsets.UTF_8).length > 64) {
                throw new IllegalArgumentException("a retired data key must be 32 bytes with an id of 1 to 64 bytes");
            }
            if (id.equals(keyId)) {
                // Rotating the key without changing its id would make every old box undecryptable, silently.
                throw new IllegalArgumentException(
                        "a retired data key must not have the id of the current one (change BMS_DATA_KEY_ID when rotating)");
            }
            copy.put(id, k.clone());
        });
        this.retired = Map.copyOf(copy);
    }

    @Override
    public java.util.List<String> blindIndexes(String purpose, String value) {
        java.util.List<String> all = new java.util.ArrayList<>();
        all.add(digest(key, purpose, value));
        retired.values().forEach(k -> all.add(digest(k, purpose, value)));
        return all;
    }

    @Override
    public String blindIndex(String purpose, String value) {
        return digest(key, purpose, value);
    }

    private static String digest(byte[] key, String purpose, String value) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] derived = mac.doFinal(("blind-index:" + purpose).getBytes(StandardCharsets.UTF_8));
            mac.init(new SecretKeySpec(derived, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("digest failed", e);
        }
    }

    @Override
    public String currentKeyId() {
        return keyIdText;
    }

    @Override
    public String keyIdOf(byte[] box) {
        try {
            ByteBuffer in = ByteBuffer.wrap(box);
            if (in.get() != FORMAT) {
                throw new IllegalStateException("unknown ciphertext format");
            }
            byte[] id = new byte[in.get()];
            in.get(id);
            return new String(id, StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new IllegalStateException("unreadable ciphertext");
        }
    }

    @Override
    public byte[] seal(byte[] plaintext) {
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

    @Override
    public byte[] open(byte[] box) {
        try {
            ByteBuffer in = ByteBuffer.wrap(box);
            if (in.get() != FORMAT) {
                throw new IllegalStateException("unknown ciphertext format");
            }
            byte[] id = new byte[in.get()];
            in.get(id);
            String sealedWith = new String(id, StandardCharsets.UTF_8);
            byte[] opening = sealedWith.equals(keyIdText) ? key : retired.get(sealedWith);
            if (opening == null) {
                throw new IllegalStateException("ciphertext was sealed with another data key");
            }
            byte[] nonce = new byte[12];
            in.get(nonce);
            byte[] sealed = new byte[in.remaining()];
            in.get(sealed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(opening, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(id);
            return cipher.doFinal(sealed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed", e);
        } catch (java.nio.BufferUnderflowException | NegativeArraySizeException e) {
            throw new IllegalStateException("unreadable ciphertext");
        }
    }
}
