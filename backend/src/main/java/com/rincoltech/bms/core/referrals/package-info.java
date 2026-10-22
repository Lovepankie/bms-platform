/**
 * Core: referral links and commission tracking (issue #464, ADR-069). Every business has a referral code and a link to the
 * public sign-up with it; the sign-up records the code an applicant brings, and Activate makes it the new business's
 * referral (one referrer per business, never itself). The platform operator sets or corrects the referrer with a reason,
 * sets the commission rule (a platform default and an override per referral), and records and marks paid the commission
 * lines; a payment the operator records for a referred business adds its line from the rule in force. The app records
 * commissions and never pays them. The owner and administrators of a business read its own link and referrals at
 * {@code /api/v1/referrals}. Public API: {@link com.rincoltech.bms.core.referrals.Referrals}, which {@code core.onboarding}
 * and {@code core.platform} use; every write goes through the SECURITY DEFINER functions of migration V89 (ADR-016).
 */
@ApplicationModule(
        id = "core.referrals",
        displayName = "Core: Referrals",
        allowedDependencies = {"kernel", "core.tenancy", "core.audit"})
package com.rincoltech.bms.core.referrals;

import org.springframework.modulith.ApplicationModule;
