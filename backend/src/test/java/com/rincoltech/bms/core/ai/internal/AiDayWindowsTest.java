package com.rincoltech.bms.core.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * The two day windows of the caps (spec 5.7): the tenant's own timezone for the tenant cap, Kampala's for the
 * platform ceiling, and the edge where the UTC date and the Kampala date differ. Pure arithmetic on fixed
 * instants; nothing here reads a clock.
 */
class AiDayWindowsTest {

    static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");

    @Test
    void aMomentAfterNineInTheEveningUtcIsAlreadyTheNextDayInKampala() {
        // 22:30 UTC on 7 October is 01:30 on 8 October in Kampala (UTC+3).
        AiCallLog.Windows w = AiGatewayService.windows(Instant.parse("2026-10-07T22:30:00Z"), KAMPALA);
        assertThat(w.tenantFrom()).isEqualTo(Instant.parse("2026-10-07T21:00:00Z"));
        assertThat(w.tenantTo()).isEqualTo(Instant.parse("2026-10-08T21:00:00Z"));
        assertThat(w.platformFrom()).isEqualTo(w.tenantFrom());
        assertThat(w.platformTo()).isEqualTo(w.tenantTo());
    }

    @Test
    void theLastSecondBeforeKampalaMidnightAndTheFirstAfterAreDifferentDays() {
        AiCallLog.Windows before = AiGatewayService.windows(Instant.parse("2026-10-07T20:59:59Z"), KAMPALA);
        AiCallLog.Windows after = AiGatewayService.windows(Instant.parse("2026-10-07T21:00:00Z"), KAMPALA);
        assertThat(before.tenantTo()).isEqualTo(after.tenantFrom()).isEqualTo(Instant.parse("2026-10-07T21:00:00Z"));
        assertThat(before.tenantFrom()).isEqualTo(Instant.parse("2026-10-06T21:00:00Z"));
        assertThat(after.tenantTo()).isEqualTo(Instant.parse("2026-10-08T21:00:00Z"));
    }

    @Test
    void aTenantInAnotherTimezoneKeepsItsOwnDayWhileThePlatformKeepsKampalas() {
        // The tenant sits in UTC: at 22:30 UTC on 7 October its day is still 7 October, Kampala's is already the 8th.
        AiCallLog.Windows w = AiGatewayService.windows(Instant.parse("2026-10-07T22:30:00Z"), ZoneId.of("UTC"));
        assertThat(w.tenantFrom()).isEqualTo(Instant.parse("2026-10-07T00:00:00Z"));
        assertThat(w.tenantTo()).isEqualTo(Instant.parse("2026-10-08T00:00:00Z"));
        assertThat(w.platformFrom()).isEqualTo(Instant.parse("2026-10-07T21:00:00Z"));
        assertThat(w.platformTo()).isEqualTo(Instant.parse("2026-10-08T21:00:00Z"));
    }

    @Test
    void aWindowIsExactlyOneDayLongAndContainsItsMoment() {
        Instant moment = Instant.parse("2026-01-31T23:59:59Z");
        AiCallLog.Windows w = AiGatewayService.windows(moment, KAMPALA);
        assertThat(java.time.Duration.between(w.tenantFrom(), w.tenantTo())).hasHours(24);
        assertThat(w.tenantFrom()).isBeforeOrEqualTo(moment);
        assertThat(w.tenantTo()).isAfter(moment);
    }
}
