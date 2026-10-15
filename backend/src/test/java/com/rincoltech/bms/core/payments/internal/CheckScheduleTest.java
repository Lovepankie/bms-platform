package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** When the status of an order is asked next (ADR-035 decision 8, spec 3.2): young orders often, old ones rarely, never past 48 hours. */
class CheckScheduleTest {

    static final Instant CREATED = Instant.parse("2026-10-08T10:00:00Z");

    @Test
    void anOrderUnderThirtyMinutesOldIsAskedEveryTwoMinutes() {
        assertThat(CheckSchedule.next(CREATED, CREATED, 0)).isEqualTo(CREATED.plus(Duration.ofMinutes(2)));
        Instant at29 = CREATED.plus(Duration.ofMinutes(29));
        assertThat(CheckSchedule.next(at29, CREATED, 0)).isEqualTo(at29.plus(Duration.ofMinutes(2)));
    }

    @Test
    void thenEveryTenMinutesUntilSixHoursAndThenHourly() {
        Instant at30 = CREATED.plus(Duration.ofMinutes(30));
        assertThat(CheckSchedule.next(at30, CREATED, 0)).isEqualTo(at30.plus(Duration.ofMinutes(10)));
        Instant at5h = CREATED.plus(Duration.ofHours(5));
        assertThat(CheckSchedule.next(at5h, CREATED, 0)).isEqualTo(at5h.plus(Duration.ofMinutes(10)));
        Instant at6h = CREATED.plus(Duration.ofHours(6));
        assertThat(CheckSchedule.next(at6h, CREATED, 0)).isEqualTo(at6h.plus(Duration.ofHours(1)));
    }

    @Test
    void eachFailureToReachTheProviderDoublesTheWaitUpToAnHour() {
        assertThat(CheckSchedule.next(CREATED, CREATED, 1)).isEqualTo(CREATED.plus(Duration.ofMinutes(4)));
        assertThat(CheckSchedule.next(CREATED, CREATED, 2)).isEqualTo(CREATED.plus(Duration.ofMinutes(8)));
        assertThat(CheckSchedule.next(CREATED, CREATED, 3)).isEqualTo(CREATED.plus(Duration.ofMinutes(16)));
        assertThat(CheckSchedule.next(CREATED, CREATED, 5)).isEqualTo(CREATED.plus(Duration.ofMinutes(60)));
        assertThat(CheckSchedule.next(CREATED, CREATED, 500)).isEqualTo(CREATED.plus(Duration.ofMinutes(60)));
        assertThat(CheckSchedule.next(CREATED, CREATED, -3)).isEqualTo(CREATED.plus(Duration.ofMinutes(2)));
    }

    @Test
    void theLastCheckIsAtFortyEightHoursAndNothingIsScheduledAfterIt() {
        Instant last = CREATED.plus(Duration.ofHours(48));
        assertThat(CheckSchedule.finalAt(CREATED)).isEqualTo(last);
        Instant at47h30 = CREATED.plus(Duration.ofHours(47)).plus(Duration.ofMinutes(30));
        assertThat(CheckSchedule.next(at47h30, CREATED, 0))
                .as("an hour later would pass the end: clamped to it")
                .isEqualTo(last);
        assertThat(CheckSchedule.isFinal(last.minusSeconds(1), CREATED)).isFalse();
        assertThat(CheckSchedule.isFinal(last, CREATED)).isTrue();
        assertThat(CheckSchedule.next(last, CREATED, 0)).isNull();
        assertThat(CheckSchedule.next(last.plus(Duration.ofDays(3)), CREATED, 0))
                .isNull();
    }

    @Test
    void aBookingThatFailedTechnicallyIsRetriedAfterOneMinuteDoublingToThirty() {
        Instant now = CREATED;
        assertThat(CheckSchedule.nextBooking(now, 1)).isEqualTo(now.plus(Duration.ofMinutes(1)));
        assertThat(CheckSchedule.nextBooking(now, 2)).isEqualTo(now.plus(Duration.ofMinutes(2)));
        assertThat(CheckSchedule.nextBooking(now, 4)).isEqualTo(now.plus(Duration.ofMinutes(8)));
        assertThat(CheckSchedule.nextBooking(now, 50)).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }

    @Test
    void thePayerIsOfferedTheOrderForThirtyMinutesAndAnIntentIsCheckedOnceInTenSeconds() {
        assertThat(CheckSchedule.OFFER).isEqualTo(Duration.ofMinutes(30));
        assertThat(CheckSchedule.THROTTLE).isEqualTo(Duration.ofSeconds(10));
        assertThat(CheckSchedule.WINDOW).isEqualTo(Duration.ofHours(48));
    }
}
