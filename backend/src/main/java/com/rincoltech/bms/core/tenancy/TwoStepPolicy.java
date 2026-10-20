package com.rincoltech.bms.core.tenancy;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/**
 * Who a tenant requires to sign in with a second factor, and from when (FR-IAM-06, issue #348, ADR-046). Two-step sign-in is
 * optional unless a tenant administrator turns this on; a role flagged in {@code roles.mfa_required} is a separate, platform-wide
 * rule that this record does not carry.
 *
 * @param everyone every staff user is covered
 * @param roles staff role keys that are covered (their holders), unused when {@code everyone}
 * @param graceDays the grace period last chosen, or null when none was
 * @param enforceFrom when a covered user without a factor must enrol; null means at once
 */
public record TwoStepPolicy(boolean everyone, Set<String> roles, Integer graceDays, Instant enforceFrom) {

    public static final TwoStepPolicy OFF = new TwoStepPolicy(false, Set.of(), null, null);

    public boolean off() {
        return !everyone && roles.isEmpty();
    }

    /** {@code off}, {@code roles} or {@code everyone}. */
    public String mode() {
        return everyone ? "everyone" : roles.isEmpty() ? "off" : "roles";
    }

    /** Whether a user holding these active role keys is covered by the policy. */
    public boolean appliesTo(Collection<String> roleKeys) {
        return everyone || roleKeys.stream().anyMatch(roles::contains);
    }

    /** Whether a covered user without a factor must enrol at {@code at}; false while the grace period runs. */
    public boolean enforcedAt(Instant at) {
        return enforceFrom == null || !at.isBefore(enforceFrom);
    }
}
