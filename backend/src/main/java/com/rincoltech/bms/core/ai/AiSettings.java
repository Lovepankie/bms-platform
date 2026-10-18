package com.rincoltech.bms.core.ai;

import java.time.Instant;
import java.util.UUID;

/**
 * The bound tenant's AI settings (ADR-037, spec 5.2): the switch, the daily caps and the
 * acknowledgement record. They are the tenant settings group {@code ai}, so a change is versioned
 * with the tenant settings row ({@code If-Match}) and audited with before and after. The caller
 * (the advisor's settings route, slice C3) checks {@code core.settings.manage} itself.
 */
public interface AiSettings {

    Status status();

    /**
     * True when the assistant is really on for the bound tenant: the tenant's own switch is on AND the tenant is named in
     * {@code BMS_AI_ENABLED_TENANTS}. A cheap read (two lookups, no usage sums), for callers that ask on every request or
     * every job tick; "off" for a caller means this is false, whichever of the two is the reason.
     */
    boolean active();

    /**
     * Switches the assistant on after the administrator read what is sent and where. The user and
     * the time come from the request and the kernel clock, and are stored with the text version.
     * Refused with 403 {@code advisor_not_available} when the tenant is not on the platform's
     * {@code BMS_AI_ENABLED_TENANTS} list, and with 422 when the text version is blank.
     */
    Status enable(String acknowledgedTextVersion, int expectedVersion);

    /** Switches it off; every call is refused from then on. The acknowledgement record is kept. */
    Status disable(int expectedVersion);

    /** Sets the tenant's daily caps; null leaves one unchanged. Both are at most the platform ceiling. */
    Status setCaps(Long dailyTokenCap, Long dailyCostCapMicro, int expectedVersion);

    /**
     * @param allowed the tenant is named in {@code BMS_AI_ENABLED_TENANTS}
     * @param providerConfigured a provider (or the test stub) is wired; false means every call falls back
     * @param tokensUsedToday what today's calls hold against the cap, a call in flight included
     * @param costUsedTodayMicro the same for estimated cost, in micro-units of {@code costCurrency}
     * @param version the tenant settings version, the ETag of the next change
     */
    record Status(
            boolean enabled,
            boolean allowed,
            boolean providerConfigured,
            long dailyTokenCap,
            long dailyCostCapMicro,
            String costCurrency,
            long tokensUsedToday,
            long costUsedTodayMicro,
            UUID acknowledgedBy,
            Instant acknowledgedAt,
            String acknowledgedTextVersion,
            int version) {}
}
