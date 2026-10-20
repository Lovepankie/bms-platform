package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pure parts of Web Push (ADR-050), with no network and no database: the message encryption against the inputs of RFC 8291
 * appendix A and in a round trip, the VAPID token, the keys at startup, the allowed push services and the push texts. Keys are
 * generated here or are the published test values of the RFC; nothing is a real subscription.
 */
class WebPushTest {

    // RFC 8291 appendix A: the sender key, the receiver key, the auth secret and the salt of the worked example.
    static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    static final String AS_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    static final String MESSAGE = "When I grow up, I want to be a watermelon";

    @Test
    void theRfcExampleDecryptsToItsMessage() throws Exception {
        byte[] body = WebPushCrypto.encrypt(
                MESSAGE.getBytes(StandardCharsets.UTF_8),
                WebPushCrypto.decode(UA_PUBLIC),
                WebPushCrypto.decode(AUTH),
                new KeyPair(
                        WebPushCrypto.publicKey(WebPushCrypto.decode(AS_PUBLIC)),
                        WebPushCrypto.privateKey(WebPushCrypto.decode(AS_PRIVATE))),
                WebPushCrypto.decode(SALT));
        // Header: salt, record size 4096, key id length 65, the sender's public key.
        assertThat(java.util.Arrays.copyOfRange(body, 0, 16)).isEqualTo(WebPushCrypto.decode(SALT));
        assertThat(java.util.Arrays.copyOfRange(body, 16, 21)).containsExactly(0, 0, 16, 0, 65);
        assertThat(java.util.Arrays.copyOfRange(body, 21, 86)).isEqualTo(WebPushCrypto.decode(AS_PUBLIC));
        // The sealed record: the message, the delimiter byte and the 16 byte tag.
        assertThat(body).hasSize(86 + MESSAGE.length() + 1 + 16);
        byte[] plain = WebPushCrypto.decrypt(
                body,
                WebPushCrypto.privateKey(WebPushCrypto.decode(UA_PRIVATE)),
                WebPushCrypto.decode(UA_PUBLIC),
                WebPushCrypto.decode(AUTH));
        assertThat(new String(plain, StandardCharsets.UTF_8)).isEqualTo(MESSAGE);
    }

    @Test
    void aFreshMessageRoundTripsAndIsNotReadableWithTheWrongAuthSecret() throws Exception {
        KeyPair receiver = WebPushCrypto.generateKeyPair();
        byte[] receiverPublic = WebPushCrypto.publicBytes(receiver.getPublic());
        byte[] auth = new byte[16];
        new SecureRandom().nextBytes(auth);
        String text = "{\"title\":\"A sale was voided\",\"path\":\"/staff/retail/sales\"}";
        byte[] body = WebPushCrypto.encrypt(text.getBytes(StandardCharsets.UTF_8), receiverPublic, auth);
        assertThat(new String(
                        WebPushCrypto.decrypt(body, receiver.getPrivate(), receiverPublic, auth),
                        StandardCharsets.UTF_8))
                .isEqualTo(text);
        // The text is not in the body, and a different auth secret cannot open it.
        assertThat(new String(body, StandardCharsets.ISO_8859_1)).doesNotContain("voided");
        byte[] other = auth.clone();
        other[0] ^= 1;
        assertThatThrownBy(() -> WebPushCrypto.decrypt(body, receiver.getPrivate(), receiverPublic, other))
                .isInstanceOf(java.security.GeneralSecurityException.class);
    }

    @Test
    void aPayloadOverOneRecordAndAPointOffTheCurveAreRefused() throws Exception {
        byte[] receiverPublic =
                WebPushCrypto.publicBytes(WebPushCrypto.generateKeyPair().getPublic());
        byte[] auth = new byte[16];
        assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[WebPushCrypto.MAX_PAYLOAD + 1], receiverPublic, auth))
                .isInstanceOf(java.security.GeneralSecurityException.class);
        byte[] offCurve = receiverPublic.clone();
        offCurve[64] ^= 1;
        assertThatThrownBy(() -> WebPushCrypto.publicKey(offCurve))
                .isInstanceOf(java.security.GeneralSecurityException.class);
        assertThatThrownBy(() -> WebPushCrypto.publicKey(new byte[10]))
                .isInstanceOf(java.security.GeneralSecurityException.class);
    }

    @Test
    void theVapidTokenIsAnEs256JwtOverTheOriginSignedByTheServerKey() throws Exception {
        KeyPair server = WebPushCrypto.generateKeyPair();
        PrivateKey rebuilt = WebPushCrypto.privateKey(WebPushCrypto.privateBytes(server.getPrivate()));
        String token = WebPushCrypto.vapidToken(
                rebuilt, "https://fcm.googleapis.com", 1_900_000_000L, "mailto:ops@example.test");
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(new String(WebPushCrypto.decode(parts[0]), StandardCharsets.UTF_8))
                .isEqualTo("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
        assertThat(new String(WebPushCrypto.decode(parts[1]), StandardCharsets.UTF_8))
                .isEqualTo(
                        "{\"aud\":\"https://fcm.googleapis.com\",\"exp\":1900000000,\"sub\":\"mailto:ops@example.test\"}");
        assertThat(WebPushCrypto.decode(parts[2])).hasSize(64);
        assertThat(WebPushCrypto.verifyVapid(token, server.getPublic())).isTrue();
        assertThat(WebPushCrypto.verifyVapid(
                        token, WebPushCrypto.generateKeyPair().getPublic()))
                .isFalse();
    }

    // ---- The keys at startup ---------------------------------------------------------------------

    static PushProperties props(String pub, String priv, String subject, boolean ephemeral) {
        return new PushProperties(pub, priv, subject, ephemeral, "");
    }

    @Test
    void withNoKeysPushIsOffAndEphemeralKeysAreForDevelopmentOnly() {
        assertThat(VapidKeys.resolve(props("", "", "", false), "").available()).isFalse();
        VapidKeys dev = VapidKeys.resolve(props("", "", "", true), "");
        assertThat(dev.available()).isTrue();
        assertThat(dev.ephemeral()).isTrue();
        assertThat(WebPushCrypto.decode(dev.publicKeyText())).hasSize(65);
        for (String live : new String[] {"staging", "production", "Production"}) {
            assertThatThrownBy(() -> VapidKeys.resolve(props("", "", "", true), live))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("development and tests only");
        }
        assertThat(dev.toString()).doesNotContain(dev.publicKeyText());
    }

    @Test
    void aMatchingPairStartsAndAMismatchedOrHalfSetOneStopsTheApplication() {
        KeyPair pair = WebPushCrypto.generateKeyPair();
        String pub = WebPushCrypto.encode(WebPushCrypto.publicBytes(pair.getPublic()));
        String priv = WebPushCrypto.encode(WebPushCrypto.privateBytes(pair.getPrivate()));
        VapidKeys keys = VapidKeys.resolve(props(pub, priv, "mailto:ops@example.test", false), "production");
        assertThat(keys.available()).isTrue();
        assertThat(keys.publicKeyText()).isEqualTo(pub);
        assertThat(keys.toString()).doesNotContain(priv);

        String otherPub = WebPushCrypto.encode(
                WebPushCrypto.publicBytes(WebPushCrypto.generateKeyPair().getPublic()));
        assertThatThrownBy(() -> VapidKeys.resolve(props(otherPub, priv, "mailto:ops@example.test", false), ""))
                .hasMessageContaining("does not match");
        assertThatThrownBy(() -> VapidKeys.resolve(props(pub, "", "mailto:ops@example.test", false), ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> VapidKeys.resolve(props(pub, priv, "ops@example.test", false), ""))
                .hasMessageContaining("BMS_PUSH_SUBJECT");
        assertThatThrownBy(
                        () -> VapidKeys.resolve(props("not-a-key", "also-not", "mailto:ops@example.test", false), ""))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---- Where a message may be posted -----------------------------------------------------------

    @Test
    void onlyHttpsAddressesOfKnownPushServicesAreAllowed() {
        PushEndpoints endpoints = new PushEndpoints("");
        assertThat(endpoints.allowed("https://fcm.googleapis.com/fcm/send/abc")).isTrue();
        assertThat(endpoints.allowed("https://updates.push.services.mozilla.com/wpush/v2/abc"))
                .isTrue();
        assertThat(endpoints.allowed("https://web.push.apple.com/abc")).isTrue();
        assertThat(endpoints.allowed("https://db5p.notify.windows.com/?token=abc"))
                .isTrue();
        for (String refused : new String[] {
            "http://fcm.googleapis.com/fcm/send/abc",
            "https://evil.example.test/fcm.googleapis.com",
            "https://fcm.googleapis.com.evil.example.test/x",
            "https://notfcm.googleapis.com.example.test/x",
            "https://127.0.0.1/x",
            "https://[::1]/x",
            "https://169.254.169.254/latest/meta-data",
            "https://user:pass@fcm.googleapis.com/x",
            "https://fcm.googleapis.com:8443/x",
            "https://fcm.googleapis.com/x#frag",
            "ftp://fcm.googleapis.com/x",
            "",
            "not a url",
        }) {
            assertThat(endpoints.allowed(refused)).as(refused).isFalse();
        }
        assertThat(endpoints.allowed(null)).isFalse();
        assertThat(endpoints.allowed("https://fcm.googleapis.com/" + "a".repeat(2100)))
                .isFalse();
        assertThat(new PushEndpoints("push.example.test, other.example.test").allowed("https://push.example.test/x"))
                .isTrue();
    }

    // ---- What a notification says ----------------------------------------------------------------

    @Test
    void aTappedNotificationOpensAnAppRouteAndNothingElse() {
        assertThat(PushOutboxChannel.safePath("/staff/approvals")).isEqualTo("/staff/approvals");
        for (String bad : new String[] {
            null,
            "",
            "staff",
            "//evil.example.test",
            "https://evil.example.test",
            "/a\\b",
            "/a\nb",
            "/" + "x".repeat(300)
        }) {
            assertThat(PushOutboxChannel.safePath(bad)).as("%s", bad).isEqualTo("/staff");
        }
    }

    @Test
    void aDeviceIsDescribedInPlainWords() {
        assertThat(PushDevices.label(
                        "Mozilla/5.0 (Linux; Android 14; Test) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36"))
                .isEqualTo("Chrome on Android");
        assertThat(PushDevices.label(
                        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) Version/18.0 Safari/604.1"))
                .isEqualTo("Safari on iPhone or iPad");
        assertThat(PushDevices.label("Mozilla/5.0 (Windows NT 10.0) Gecko/20100101 Firefox/130.0"))
                .isEqualTo("Firefox on Windows");
        assertThat(PushDevices.label(null)).isEqualTo("Unknown device");
        assertThat(PushDevices.hash("https://fcm.googleapis.com/x")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void everyPushTemplateRendersFromItsPlaceholdersAndFitsALockScreen() {
        OutboxTemplates templates = new OutboxTemplates();
        assertThat(OutboxTemplates.keys("push"))
                .contains(
                        "retail.sale_voided",
                        "retail.write_off_alone",
                        "core.approval_policy_weakened",
                        "core.signin_policy_weakened",
                        "retail.transfer_dispatched",
                        "core.approval_waiting");
        for (String key : OutboxTemplates.keys("push")) {
            Map<String, String> params = new HashMap<>();
            for (String name : OutboxTemplates.placeholders("push", key)) {
                params.put(name, "Test " + name);
            }
            OutboxTemplates.Rendered rendered = templates.render("push", key, params);
            assertThat(rendered.subject()).as(key).isNotBlank().hasSizeLessThanOrEqualTo(PushOutboxChannel.TITLE_MAX);
            assertThat(rendered.text()).as(key).isNotBlank().hasSizeLessThanOrEqualTo(PushOutboxChannel.BODY_MAX);
            // A notification is read on a lock screen: no figure, no name and no number placeholder.
            assertThat(OutboxTemplates.placeholders("push", key))
                    .as(key)
                    .doesNotContain("amount", "phone", "member_name", "customer_name", "full_name");
        }
        assertThatThrownBy(() -> templates.render("push", "retail.sale_voided", Map.of()))
                .hasMessageContaining("missing template parameter");
    }
}
