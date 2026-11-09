package com.rincoltech.bms.core.imports.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The deadlines of the scheduled purge (issue #139, spec section 6.4) without a database: a signed run is due 29 days after
 * its sign-off (a day's margin on the 30 of the spec, review of #886), a run never signed off 90 days after its last
 * activity, both whatever happened to their warning (Lead ruling on #886, M4); the warning is due a week before the
 * deadline, once per start of the clock.
 * {@code ImportRetentionIT} runs the same rules against PostgreSQL with the business clock fixed.
 */
class PurgeDeadlinesTest {

    static final Instant DAY0 = Instant.parse("2026-06-01T09:00:00Z");

    static Instant days(long n) {
        return DAY0.plus(Duration.ofDays(n));
    }

    static PurgeService.Candidate signed(Instant warnedAt) {
        return new PurgeService.Candidate(UUID.randomUUID(), PurgeService.SIGNED_OFF_30_DAYS, DAY0, warnedAt);
    }

    static PurgeService.Candidate unsigned(Instant warnedAt) {
        return new PurgeService.Candidate(UUID.randomUUID(), PurgeService.INACTIVE_90_DAYS, DAY0, warnedAt);
    }

    @Test
    void aSignedRunIsDueADayBeforeItsThirtyDaysWarnedOrNot() {
        // Day 29, so the daily job, which may run up to a day after a deadline, never purges after the 30 days.
        assertThat(signed(null).dueAt()).isEqualTo(days(29));
        assertThat(signed(null).warnable(days(22).minusSeconds(1))).isFalse();
        assertThat(signed(null).warnable(days(22))).isTrue();
        assertThat(signed(days(22)).warnable(days(23))).isFalse();
        assertThat(signed(null).due(days(29).minusSeconds(1))).isFalse();
        assertThat(signed(null).due(days(29)))
                .as("due; with no warning standing the purge sends its notice")
                .isTrue();
        assertThat(signed(days(28)).due(days(29))).isTrue();
    }

    @Test
    void aRunNeverSignedOffIsDueNinetyDaysAfterItsLastActivityWarnedOrNot() {
        assertThat(unsigned(null).dueAt()).isEqualTo(days(90));
        assertThat(unsigned(null).warnable(days(83).minusSeconds(1))).isFalse();
        assertThat(unsigned(null).warnable(days(83))).isTrue();
        assertThat(unsigned(days(83)).due(days(90).minusSeconds(1))).isFalse();
        assertThat(unsigned(days(83)).due(days(90))).isTrue();
        // The deadline wins over a warning that never went (Lead ruling on #886, M4); the purge then sends its notice.
        assertThat(unsigned(null).due(days(90))).isTrue();
        assertThat(unsigned(null).warned()).isFalse();
        // A warning older than the last activity does not stand: the clock started again.
        assertThat(unsigned(DAY0.minus(Duration.ofDays(1))).warnable(days(83))).isTrue();
        assertThat(unsigned(DAY0.minus(Duration.ofDays(1))).warned()).isFalse();
    }
}
