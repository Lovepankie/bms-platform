package com.rincoltech.bms.core.notifications.internal;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The two cryptographic pieces of Web Push, on the JDK alone (ADR-050): the message encryption of RFC 8291 ({@code aes128gcm}
 * content coding of RFC 8188, one record) and the VAPID proof of RFC 8292 (an ES256 token). Nothing here logs or stores a
 * key. A subscription's {@code p256dh} and {@code auth} values and the VAPID keys travel as unpadded base64url, as browsers
 * and the {@code web-push} tools write them.
 */
final class WebPushCrypto {

    /** The record size written in the header; the push services accept 4096 and the payload is far smaller. */
    static final int RECORD_SIZE = 4096;
    /** A push payload of one record holds at most this many bytes (RFC 8291 section 4). */
    static final int MAX_PAYLOAD = RECORD_SIZE - 16 - 1;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final ECParameterSpec P256 = p256();

    private WebPushCrypto() {}

    static String encode(byte[] bytes) {
        return B64.encodeToString(bytes);
    }

    /** Decodes unpadded base64url; throws {@link IllegalArgumentException} for anything else. */
    static byte[] decode(String text) {
        return B64D.decode(text);
    }

    // ---- Keys -----------------------------------------------------------------------------------

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the JDK has no P-256 curve", e);
        }
    }

    static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot generate a P-256 key", e);
        }
    }

    /** The 65 byte uncompressed point, {@code 0x04 || X || Y}. */
    static byte[] publicBytes(PublicKey key) {
        ECPoint w = ((ECPublicKey) key).getW();
        byte[] out = new byte[65];
        out[0] = 4;
        System.arraycopy(fixed(w.getAffineX(), 32), 0, out, 1, 32);
        System.arraycopy(fixed(w.getAffineY(), 32), 0, out, 33, 32);
        return out;
    }

    /** The 32 byte private scalar. */
    static byte[] privateBytes(PrivateKey key) {
        return fixed(((ECPrivateKey) key).getS(), 32);
    }

    static PublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 4) {
            throw new GeneralSecurityException("not an uncompressed P-256 point");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        // The JDK does not promise to check that a point is on the curve; a point that is not is an invalid-curve
        // probe.
        BigInteger p = ((ECFieldFp) P256.getCurve().getField()).getP();
        BigInteger left = y.multiply(y).mod(p);
        BigInteger right = x.multiply(x)
                .add(P256.getCurve().getA())
                .multiply(x)
                .add(P256.getCurve().getB())
                .mod(p);
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0 || !left.equals(right)) {
            throw new GeneralSecurityException("the point is not on the P-256 curve");
        }
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    static PrivateKey privateKey(byte[] scalar) throws GeneralSecurityException {
        return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), P256));
    }

    private static byte[] fixed(BigInteger value, int length) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[length];
        int copy = Math.min(raw.length, length);
        System.arraycopy(raw, raw.length - copy, out, length - copy, copy);
        return out;
    }

    // ---- RFC 8291 -------------------------------------------------------------------------------

    /**
     * Encrypts {@code plaintext} for one subscription: the body of the push request.
     *
     * @param uaPublic the subscription's {@code p256dh}, 65 bytes
     * @param authSecret the subscription's {@code auth}, 16 bytes
     */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret) throws GeneralSecurityException {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, generateKeyPair(), salt);
    }

    /** As {@link #encrypt(byte[], byte[], byte[])} with the sender's key and salt given, for the RFC's test vector. */
    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair sender, byte[] salt)
            throws GeneralSecurityException {
        if (plaintext.length > MAX_PAYLOAD) {
            throw new GeneralSecurityException("payload too large");
        }
        if (authSecret.length != 16 || salt.length != 16) {
            throw new GeneralSecurityException("auth secret and salt are 16 bytes");
        }
        byte[] asPublic = publicBytes(sender.getPublic());
        byte[] secret = agree(sender.getPrivate(), publicKey(uaPublic));
        byte[] prkKey = hmac(authSecret, secret);
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[] {1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(
                hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})),
                16);
        byte[] nonce = Arrays.copyOf(
                hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 12);
        // One record, so the last-record delimiter 0x02 follows the data (RFC 8188 section 2).
        byte[] padded = concat(plaintext, new byte[] {2});
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] sealed = cipher.doFinal(padded);
        ByteBuffer out = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length + sealed.length);
        out.put(salt)
                .putInt(RECORD_SIZE)
                .put((byte) asPublic.length)
                .put(asPublic)
                .put(sealed);
        return out.array();
    }

    /** The receiving side, for the tests (a browser does this): returns the plaintext of a one record message. */
    static byte[] decrypt(byte[] body, PrivateKey uaPrivate, byte[] uaPublic, byte[] authSecret)
            throws GeneralSecurityException {
        byte[] salt = Arrays.copyOfRange(body, 0, 16);
        int idLength = body[20] & 0xff;
        byte[] asPublic = Arrays.copyOfRange(body, 21, 21 + idLength);
        byte[] sealed = Arrays.copyOfRange(body, 21 + idLength, body.length);
        byte[] secret = agree(uaPrivate, publicKey(asPublic));
        byte[] prkKey = hmac(authSecret, secret);
        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[] {1}));
        byte[] prk = hmac(salt, ikm);
        byte[] cek = Arrays.copyOf(
                hmac(prk, concat("Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})),
                16);
        byte[] nonce = Arrays.copyOf(
                hmac(prk, concat("Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), new byte[] {1})), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] padded = cipher.doFinal(sealed);
        int end = padded.length;
        while (end > 0 && padded[end - 1] == 0) {
            end--;
        }
        if (end == 0 || padded[end - 1] != 2) {
            throw new GeneralSecurityException("no last record delimiter");
        }
        return Arrays.copyOf(padded, end - 1);
    }

    private static byte[] agree(PrivateKey own, PublicKey other) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(own);
        agreement.doPhase(other, true);
        return agreement.generateSecret();
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    // ---- RFC 8292 -------------------------------------------------------------------------------

    /**
     * The VAPID token for one push service: audience is the origin of the endpoint, it lapses in twelve hours (the limit is
     * twenty-four), and the subject is a contact the push service can reach if the sender misbehaves.
     */
    static String vapidToken(PrivateKey key, String audience, long expiresAtEpochSecond, String subject)
            throws GeneralSecurityException {
        String header = encode("{\"typ\":\"JWT\",\"alg\":\"ES256\"}".getBytes(StandardCharsets.UTF_8));
        String claims = encode(("{\"aud\":\"" + json(audience) + "\",\"exp\":" + expiresAtEpochSecond + ",\"sub\":\""
                        + json(subject) + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(key);
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + encode(derToRaw(signature.sign()));
    }

    /** Checks a token's signature, for the tests. */
    static boolean verifyVapid(String token, PublicKey key) throws GeneralSecurityException {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initVerify(key);
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        return signature.verify(rawToDer(decode(parts[2])));
    }

    private static String json(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** The JDK signs as DER {@code SEQUENCE { r, s }}; JWS wants the two 32 byte integers side by side. */
    static byte[] derToRaw(byte[] der) throws GeneralSecurityException {
        try {
            int at = 2;
            if ((der[1] & 0x80) != 0) {
                at = 2 + (der[1] & 0x7f);
            }
            int rLength = der[at + 1] & 0xff;
            byte[] r = Arrays.copyOfRange(der, at + 2, at + 2 + rLength);
            int sAt = at + 2 + rLength;
            int sLength = der[sAt + 1] & 0xff;
            byte[] s = Arrays.copyOfRange(der, sAt + 2, sAt + 2 + sLength);
            return concat(fixed(new BigInteger(1, r), 32), fixed(new BigInteger(1, s), 32));
        } catch (RuntimeException e) {
            throw new GeneralSecurityException("malformed signature", e);
        }
    }

    static byte[] rawToDer(byte[] raw) {
        byte[] r = new BigInteger(1, Arrays.copyOfRange(raw, 0, 32)).toByteArray();
        byte[] s = new BigInteger(1, Arrays.copyOfRange(raw, 32, 64)).toByteArray();
        byte[] body = concat(new byte[] {2, (byte) r.length}, r, new byte[] {2, (byte) s.length}, s);
        return concat(new byte[] {0x30, (byte) body.length}, body);
    }
}
