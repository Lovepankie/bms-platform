package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

/**
 * The tenant's sending window at the Africa/Kampala day boundary (UTC+3 all year, so local midnight is 21:00 UTC): 20:59 UTC
 * is still the old local day and 21:01 UTC is the new one. The acceptance of issue #218: a window of 08:00 to 20:00 defers a
 * reminder queued at 21:00 local to 08:00 the next local day. No clock of the machine is read; every instant is fixed.
 */
class SmsWindowBoundaryTest {

    static final Instant LAST_MINUTE = Instant.parse("2031-04-02T20:59:00Z"); // 23:59 on 2 April in Kampala
    static final Instant FIRST_MINUTE = Instant.parse("2031-04-02T21:01:00Z"); // 00:01 on 3 April in Kampala
    static final Instant EIGHT_ON_THE_3RD = Instant.parse("2031-04-03T05:00:00Z"); // 08:00 on 3 April in Kampala

    @Test
    void aReminderQueuedAtNightWaitsForEightTheNextLocalMorningOnEitherSideOfMidnight() {
        SmsWindow window = SmsWindow.of(LocalTime.of(8, 0), LocalTime.of(20, 0));
        // 21:00 local, as the acceptance says: 18:00 UTC.
        Instant nineAtNight = Instant.parse("2031-04-02T18:00:00Z");
        assertThat(window.isOpen(nineAtNight)).isFalse();
        assertThat(window.nextOpen(nineAtNight)).isEqualTo(EIGHT_ON_THE_3RD);
        // Before midnight and after it the same morning is the answer: it is the same local night.
        assertThat(window.nextOpen(LAST_MINUTE)).isEqualTo(EIGHT_ON_THE_3RD);
        assertThat(window.nextOpen(FIRST_MINUTE)).isEqualTo(EIGHT_ON_THE_3RD);
    }

    @Test
    void theNextDayStartAndTheEndOfDayFollowTheLocalDateNotTheUtcOne() {
        SmsWindow window = SmsWindow.of(LocalTime.of(8, 0), LocalTime.of(20, 0));
        // At 23:59 local on the 2nd, "tomorrow's window" is the 3rd; at 00:01 local on the 3rd it is the 4th.
        assertThat(window.nextDayStart(LAST_MINUTE)).isEqualTo(EIGHT_ON_THE_3RD);
        assertThat(window.nextDayStart(FIRST_MINUTE)).isEqualTo(Instant.parse("2031-04-04T05:00:00Z"));
        // A reminder expires at 20:00 local on its own local day: the 2nd for one queued at 23:59, the 3rd at 00:01.
        assertThat(window.endOfDay(LAST_MINUTE)).isEqualTo(Instant.parse("2031-04-02T17:00:00Z"));
        assertThat(window.endOfDay(FIRST_MINUTE)).isEqualTo(Instant.parse("2031-04-03T17:00:00Z"));
    }

    @Test
    void aTenantsOwnWindowOpensAndClosesOnTheMinute() {
        SmsWindow window = SmsWindow.of(LocalTime.of(9, 30), LocalTime.of(17, 0));
        // 09:30 local is 06:30 UTC; the window is open from then and closed at 17:00 local, 14:00 UTC.
        assertThat(window.isOpen(Instant.parse("2031-04-02T06:29:59Z"))).isFalse();
        assertThat(window.isOpen(Instant.parse("2031-04-02T06:30:00Z"))).isTrue();
        assertThat(window.isOpen(Instant.parse("2031-04-02T13:59:59Z"))).isTrue();
        assertThat(window.isOpen(Instant.parse("2031-04-02T14:00:00Z"))).isFalse();
        assertThat(window.nextOpen(Instant.parse("2031-04-02T14:00:00Z")))
                .isEqualTo(Instant.parse("2031-04-03T06:30:00Z"));
    }
}
