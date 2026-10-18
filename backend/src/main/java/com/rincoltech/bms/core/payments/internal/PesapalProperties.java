package com.rincoltech.bms.core.payments.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Pesapal gateway's environment values (ADR-035 decision 10, spec 3.11, 9.1). Raw strings exactly as the host
 * environment gives them; {@link PesapalSettings#resolve} turns them into settings and refuses every combination that
 * could reach the live provider from outside production. None has a default that reaches a provider, and none is ever
 * printed: {@code toString} names no value.
 *
 * @param provider {@code BMS_PESAPAL_PROVIDER}: {@code pesapal}, {@code fake} (an in-process stand-in, refused in
 *     production) or {@code off}; empty means {@code pesapal} when the tenant pair below is complete, else off
 * @param baseUrl {@code BMS_PESAPAL_BASE_URL}: the sandbox or the live API base; outside production only the sandbox
 * @param consumerKey {@code BMS_PESAPAL_CONSUMER_KEY}
 * @param consumerSecret {@code BMS_PESAPAL_CONSUMER_SECRET}
 * @param ipnId {@code BMS_PESAPAL_IPN_ID}: the registration of this credential pair; one IPN id never serves two pairs
 * @param ipnToken {@code BMS_PESAPAL_IPN_TOKEN}: the random path token that selects this pair on the callback host
 * @param tenantId {@code BMS_PESAPAL_TENANT_ID}: the one tenant this merchant account is bound to (ADR-035 decision 11)
 * @param callbackBaseUrl {@code BMS_PESAPAL_CALLBACK_BASE_URL}: the public https origin of the callback host
 * @param platform the platform operator's own pair, for subscription payments (slice #228)
 */
@ConfigurationProperties("bms.pesapal")
record PesapalProperties(
        String provider,
        String baseUrl,
        String consumerKey,
        String consumerSecret,
        String ipnId,
        String ipnToken,
        String tenantId,
        String callbackBaseUrl,
        Platform platform) {

    PesapalProperties {
        platform = platform == null ? new Platform(null, null, null, null) : platform;
    }

    record Platform(String consumerKey, String consumerSecret, String ipnId, String ipnToken) {

        @Override
        public String toString() {
            return "Platform[]";
        }
    }

    @Override
    public String toString() {
        return "PesapalProperties[provider=" + provider + "]";
    }
}
