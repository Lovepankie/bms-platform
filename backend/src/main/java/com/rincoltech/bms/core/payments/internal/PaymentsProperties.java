package com.rincoltech.bms.core.payments.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning of the payment jobs and the public endpoints. Not secrets: plain settings with the defaults below, validated at
 * start so a typo cannot turn a limit into nothing.
 *
 * @param pollerEnabled the checker job and the nightly housekeeping; the integration tests switch it off and drive
 *     {@code PaymentPoller#tick} themselves
 * @param pollPerTenant the most due intents one tick takes from one tenant, so a tenant with a thousand due intents
 *     leaves room for the others (fairness, spec 3.6 and 9.9)
 * @param pollTotal the most intents one tick takes in all, bounding the calls made to the provider per tick
 * @param callbackIpPerMinute notifications one client address may send a minute before it is answered 429
 * @param callbackGlobalPerMinute the same for every address together, a backstop for the database
 * @param maxBodyBytes the largest notification body read; more is answered 413
 */
@ConfigurationProperties("bms.payments")
record PaymentsProperties(
        Boolean pollerEnabled,
        Integer pollPerTenant,
        Integer pollTotal,
        Integer callbackIpPerMinute,
        Integer callbackGlobalPerMinute,
        Integer maxBodyBytes) {

    PaymentsProperties {
        pollerEnabled = pollerEnabled == null || pollerEnabled;
        pollPerTenant = positive("BMS_PAYMENTS_POLL_PER_TENANT", pollPerTenant, 5);
        pollTotal = positive("BMS_PAYMENTS_POLL_TOTAL", pollTotal, 40);
        callbackIpPerMinute = positive("BMS_PAYMENTS_CALLBACK_IP_PER_MINUTE", callbackIpPerMinute, 60);
        callbackGlobalPerMinute = positive("BMS_PAYMENTS_CALLBACK_GLOBAL_PER_MINUTE", callbackGlobalPerMinute, 3000);
        maxBodyBytes = positive("BMS_PAYMENTS_MAX_BODY_BYTES", maxBodyBytes, 4096);
    }

    private static int positive(String name, Integer value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be 1 or more");
        }
        return value;
    }
}
