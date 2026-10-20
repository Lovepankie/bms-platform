package com.rincoltech.bms.core.notifications.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web Push (ADR-050, docs/runbooks/push-notifications.md). Every value comes from the environment and none has a default
 * that sends anything: with no VAPID keys the channel is off, nothing is queued and the settings toggle is not offered.
 * Values are never logged; {@link #toString()} says only whether the keys are set.
 *
 * @param vapidPublicKey {@code BMS_PUSH_VAPID_PUBLIC_KEY}: the application server key, 65 bytes, unpadded base64url
 * @param vapidPrivateKey {@code BMS_PUSH_VAPID_PRIVATE_KEY}: the matching private scalar, 32 bytes, unpadded base64url
 * @param subject {@code BMS_PUSH_SUBJECT}: a {@code mailto:} address or an {@code https:} URL the push services can reach
 * @param ephemeralKeys {@code bms.push.ephemeral-keys}: generate a key pair at start when none is set; the dev profile and
 *     the tests only, refused on staging and production
 * @param extraEndpointHosts {@code BMS_PUSH_EXTRA_ENDPOINT_HOSTS}: more push service hosts, comma separated, for a push
 *     service not in the built-in list
 */
@ConfigurationProperties("bms.push")
record PushProperties(
        String vapidPublicKey,
        String vapidPrivateKey,
        String subject,
        boolean ephemeralKeys,
        String extraEndpointHosts) {

    boolean keysSet() {
        return present(vapidPublicKey) || present(vapidPrivateKey);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        return "PushProperties[keysSet=" + keysSet() + ", ephemeral=" + ephemeralKeys + "]";
    }
}
