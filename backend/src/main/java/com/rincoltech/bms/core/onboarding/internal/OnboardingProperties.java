package com.rincoltech.bms.core.onboarding.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Volume bounds of the public sign-up (review B2, chapter 7 section 7.10), counted in the
 * database so no flood of fresh addresses can reset them. When a bound is reached the request is
 * still answered 202 and nothing is created or sent; the global caps also alert the operator once
 * an hour. An edge rule at Cloudflare is in front of these (docs/runbooks/onboard-tenant.md).
 *
 * @param maxApplicationsPerHour new applications, all addresses together
 *     ({@code BMS_SIGNUP_MAX_APPLICATIONS_PER_HOUR}, default 30)
 * @param maxVerificationEmailsPerHour confirmation emails, all addresses together
 *     ({@code BMS_SIGNUP_MAX_VERIFICATION_EMAILS_PER_HOUR}, default 60)
 * @param maxVerificationEmailsPerMailbox confirmation emails to one mailbox in 24 hours (default 3)
 */
@ConfigurationProperties("bms.onboarding")
record OnboardingProperties(
        Integer maxApplicationsPerHour, Integer maxVerificationEmailsPerHour, Integer maxVerificationEmailsPerMailbox) {

    OnboardingProperties {
        maxApplicationsPerHour = positive(maxApplicationsPerHour, 30);
        maxVerificationEmailsPerHour = positive(maxVerificationEmailsPerHour, 60);
        maxVerificationEmailsPerMailbox = positive(maxVerificationEmailsPerMailbox, 3);
    }

    private static int positive(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }
}
