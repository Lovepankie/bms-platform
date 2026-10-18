package com.rincoltech.bms.lending.advisor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The business date of the daily brief job (ADR-037, spec 5.7, issue #226): the Africa/Kampala date of the time the run was
 * due, not of the time it runs, with the 20:59 and 21:01 UTC edge, and nothing before 06:00 Kampala time.
 */
class AdvisorJobsTest {

    @Test
    void theDateIsTheKampalaDateOfTheDueTimeAtTheMidnightEdge() {
        // 20:59 UTC is 23:59 in Kampala (UTC+3): still the 14th, and after 06:00.
        assertThat(AdvisorJobs.businessDateFor(Instant.parse("2026-10-14T20:59:00Z")))
                .isEqualTo(LocalDate.of(2026, 10, 14));
        // 21:01 UTC is 00:01 on the 15th: before the earliest hour, so nothing runs and no date is chosen.
        assertThat(AdvisorJobs.businessDateFor(Instant.parse("2026-10-14T21:01:00Z")))
                .isNull();
    }

    @Test
    void aRunDueBeforeMidnightAndServedAfterItKeepsTheDateItWasDueFor() {
        // The date comes from when the run was due, never from the clock at the time it executes.
        Instant dueAt = Instant.parse("2026-10-14T20:50:00Z");
        assertThat(AdvisorJobs.businessDateFor(dueAt)).isEqualTo(LocalDate.of(2026, 10, 14));
    }

    @Test
    void nothingRunsBeforeSixInTheMorningAndTheDayStartsAtSix() {
        assertThat(AdvisorJobs.businessDateFor(Instant.parse("2026-10-15T02:59:00Z")))
                .isNull();
        assertThat(AdvisorJobs.businessDateFor(Instant.parse("2026-10-15T03:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 10, 15));
    }
}
