package com.rincoltech.bms.core.documents.internal;

import com.rincoltech.bms.kernel.BusinessClock;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The recording fake of chapter 12 section 12.8, for the dev and test profiles only: objects live
 * in memory and its signed URLs point at {@link FakeStorageController}, carrying an expiry and an
 * HMAC over key and expiry, so expiry and tampering behave as they do with R2.
 */
class FakeObjectStorage implements ObjectStorage {

    static final String PATH = "/api/v1/storage/fake";

    record StoredObject(byte[] bytes, String contentType) {}

    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
    private final byte[] secret;
    private final BusinessClock clock;

    FakeObjectStorage(String secret, BusinessClock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        objects.put(key, new StoredObject(bytes.clone(), contentType));
    }

    @Override
    public String signedGetUrl(String key, Duration ttl, String downloadName, String contentType) {
        long expires = clock.now().plus(ttl).getEpochSecond();
        return PATH + "?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&expires=" + expires
                + "&name=" + URLEncoder.encode(downloadName, StandardCharsets.UTF_8)
                + "&sig=" + sign(key + "\n" + downloadName, expires);
    }

    /** The object when the signature matches and has not expired; empty otherwise. */
    Optional<StoredObject> read(String key, long expires, String name, String sig) {
        boolean signed = MessageDigest.isEqual(
                sign(key + "\n" + name, expires).getBytes(StandardCharsets.US_ASCII),
                sig.getBytes(StandardCharsets.US_ASCII));
        if (!signed || clock.now().getEpochSecond() > expires) {
            return Optional.empty();
        }
        return Optional.ofNullable(objects.get(key));
    }

    String sign(String key, long expires) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((key + "\n" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
