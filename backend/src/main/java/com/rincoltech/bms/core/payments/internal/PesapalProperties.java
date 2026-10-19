package com.rincoltech.bms.core.payments.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * The Pesapal gateway's environment values (ADR-035 decision 10, spec 3.11, 9.1). Raw strings exactly as the host
 * environment gives them; {@link PesapalSettings#resolve} turns them into settings and refuses every combination that
 * could reach the live provider from outside production. None has a default that reaches a provider, and none is ever
 * printed: {@code toString} names no value.
 *
 * @param provider {@code BMS_PESAPAL_PROVIDER}: {@code pesapal}, {@code fake} (an in-process stand-in, refused in
 *     production) or {@code off}; empty means {@code pesapal} when the callback origin is set or the platform pair is
 *     complete, else off
 * @param baseUrl {@code BMS_PESAPAL_BASE_URL}: the sandbox or the live API base; outside production only the sandbox
 * @param callbackBaseUrl {@code BMS_PESAPAL_CALLBACK_BASE_URL}: the public https origin of the callback host
 * @param refusedKeyDigests {@code BMS_PESAPAL_REFUSED_KEY_DIGESTS}: comma separated SHA-256 digests (lower case hex) of every consumer
 *     key the platform operator owns, current and retired, which no lender may enter as its own account, whether or not the
 *     platform pair is in use here. Production refuses to start with the gateway on and this empty
 * @param platform the platform operator's own pair, for subscription payments (slice #228). A lender's own merchant
 *     account is not here: it is entered by the lender's administrator, stored encrypted per tenant and never read from
 *     the environment (ADR-035 amendment of 2026-10-18, issue #233)
 */
@ConfigurationProperties("bms.pesapal")
record PesapalProperties(
        String provider, String baseUrl, String callbackBaseUrl, Platform platform, String refusedKeyDigests) {

    /** Without a refused-key list (development, tests). */
    PesapalProperties(String provider, String baseUrl, String callbackBaseUrl, Platform platform) {
        this(provider, baseUrl, callbackBaseUrl, platform, null);
    }

    @ConstructorBinding
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
