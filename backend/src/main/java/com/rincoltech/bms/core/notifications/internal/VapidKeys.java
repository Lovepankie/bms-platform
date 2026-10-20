package com.rincoltech.bms.core.notifications.internal;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.Locale;

/**
 * The server's VAPID identity (RFC 8292): the key that signs each push request, and the public half the browser is given to
 * subscribe against, so a subscription is bound to this server and no one else can push to it. {@link #none()} means push is
 * off. The private key is never printed: {@link #toString()} says only whether keys exist.
 */
record VapidKeys(PrivateKey privateKey, byte[] publicBytes, String subject, boolean ephemeral) {

    static VapidKeys none() {
        return new VapidKeys(null, new byte[0], "", false);
    }

    boolean available() {
        return privateKey != null;
    }

    /** The public key as the browser's {@code applicationServerKey}: unpadded base64url of the 65 byte point. */
    String publicKeyText() {
        return WebPushCrypto.encode(publicBytes);
    }

    /**
     * The keys of this run: those in the environment (checked as a pair, and the subject checked, or the application does not
     * start), or a fresh pair when {@code ephemeral} is asked for and the environment is not staging or production, or none.
     */
    static VapidKeys resolve(PushProperties properties, String environment) {
        String env = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
        boolean live = env.equals("staging") || env.equals("production");
        if (!properties.keysSet()) {
            if (properties.ephemeralKeys()) {
                if (live) {
                    throw new IllegalStateException(
                            "bms.push.ephemeral-keys is for development and tests only: set BMS_PUSH_VAPID_PUBLIC_KEY and"
                                    + " BMS_PUSH_VAPID_PRIVATE_KEY, or leave push off");
                }
                KeyPair pair = WebPushCrypto.generateKeyPair();
                return new VapidKeys(
                        pair.getPrivate(),
                        WebPushCrypto.publicBytes(pair.getPublic()),
                        "mailto:push-dev@example.invalid",
                        true);
            }
            return none();
        }
        String subject =
                properties.subject() == null ? "" : properties.subject().trim();
        if (!subject.startsWith("mailto:") && !subject.startsWith("https://")) {
            throw new IllegalStateException("BMS_PUSH_SUBJECT must be a mailto: address or an https: URL");
        }
        try {
            byte[] publicBytes =
                    WebPushCrypto.decode(properties.vapidPublicKey().trim());
            PrivateKey privateKey = WebPushCrypto.privateKey(
                    WebPushCrypto.decode(properties.vapidPrivateKey().trim()));
            // A pair that does not match would sign tokens every push service rejects: fail at start, not at the first
            // push.
            String probe = WebPushCrypto.vapidToken(privateKey, "https://push.example.invalid", 1, subject);
            if (!WebPushCrypto.verifyVapid(probe, WebPushCrypto.publicKey(publicBytes))) {
                throw new IllegalStateException("BMS_PUSH_VAPID_PRIVATE_KEY does not match BMS_PUSH_VAPID_PUBLIC_KEY");
            }
            return new VapidKeys(privateKey, publicBytes, subject, false);
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
            throw new IllegalStateException(
                    "the VAPID keys are not valid: both are unpadded base64url, the public key a 65 byte P-256 point and"
                            + " the private key its 32 byte scalar");
        }
    }

    @Override
    public String toString() {
        return "VapidKeys[available=" + available() + ", ephemeral=" + ephemeral + "]";
    }
}
