package com.rincoltech.bms.core.notifications.internal;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The keyed hash of a recipient number (ADR-036 decision 3 and 8, spec section 4.5): HMAC-SHA256
 * under a key derived from the application data key with a fixed purpose label, so the data key
 * itself never keys the hash, the same number always gives the same hash on every instance, and the
 * delivery log and the opt-out table hold no number a stolen copy could read back. Only a key holder
 * can test whether a number is in the table. In the dev and test profiles a blank data key gives a
 * random key per run; a server refuses to start without the data key (the identity module).
 */
@Component
class SmsRecipientHash {

    private static final String PURPOSE = "bms.sms.recipient-hash.v1";

    private final byte[] key;

    SmsRecipientHash(@Value("${bms.auth.data-key:}") String dataKey) {
        byte[] derived;
        if (dataKey == null || dataKey.isBlank()) {
            derived = new byte[32];
            new SecureRandom().nextBytes(derived);
        } else {
            derived = hmac(dataKey.getBytes(StandardCharsets.UTF_8), PURPOSE.getBytes(StandardCharsets.UTF_8));
        }
        this.key = derived;
    }

    /** 64 lower case hex characters. */
    String of(String e164) {
        return HexFormat.of().formatHex(hmac(key, e164.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
