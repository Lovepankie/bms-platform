package com.rincoltech.bms.core.notifications.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The SMS channel's environment values (ADR-036 decision 11, spec section 4.4). Raw strings exactly as
 * the host environment gives them; {@link SmsSettings#resolve} turns them into settings and refuses
 * every combination that could text a real phone from the wrong place. None has a default that
 * sends anything. The API key is never printed.
 *
 * @param provider {@code BMS_SMS_PROVIDER}: {@code africastalking} or {@code fake}; empty means no provider
 * @param mode {@code BMS_SMS_MODE}: {@code off}, {@code sink}, {@code allowlist} or {@code live}; empty is
 *     {@code sink} on staging and {@code off} everywhere else
 * @param allowlist {@code BMS_SMS_ALLOWLIST}: numbers, comma separated, for mode {@code allowlist}; an
 *     environment value, never in the repository
 * @param platformDailyCap {@code BMS_SMS_PLATFORM_DAILY_CAP}: the most messages the platform sends in a
 *     business day, over every tenant (default 2000)
 * @param africasTalking {@code BMS_SMS_AT_BASE_URL}, {@code BMS_SMS_AT_USERNAME}, {@code BMS_SMS_AT_API_KEY}
 */
@ConfigurationProperties("bms.sms")
record SmsProperties(
        String provider, String mode, String allowlist, Integer platformDailyCap, AfricasTalking africasTalking) {

    SmsProperties {
        africasTalking = africasTalking == null ? new AfricasTalking(null, null, null) : africasTalking;
    }

    record AfricasTalking(String baseUrl, String username, String apiKey) {

        @Override
        public String toString() {
            return "AfricasTalking[baseUrl=" + baseUrl + ", apiKey="
                    + (apiKey == null || apiKey.isBlank() ? "unset" : "set") + "]";
        }
    }

    @Override
    public String toString() {
        return "SmsProperties[provider=" + provider + ", mode=" + mode + "]";
    }
}
