package com.rincoltech.bms.core.payments.internal;

import java.time.Duration;
import java.time.Instant;

/**
 * When the status of an order is asked next (ADR-035 decision 8, spec 3.2 and 3.8). Every unpaid intent is checked every two
 * minutes while it is young (a lost notification costs a delay, not a payment), then less often as it ages, with a
 * longer wait after each failed attempt to reach the provider, and one last time 48 hours after it was made, when an
 * order still unpaid is cancelled at the provider (best effort) and the intent ends. The 30 minutes after which the payer
 * is no longer offered the order is the intent's own {@code expires_at}; it does not stop the checks.
 */
final class CheckSchedule {

    static final Duration OFFER = Duration.ofMinutes(30);
    static final Duration WINDOW = Duration.ofHours(48);
    /** One check per intent in this time, however many notifications arrive. */
    static final Duration THROTTLE = Duration.ofSeconds(10);
    /** How long a worker holds the intents it took, while it asks the provider and books. */
    static final Duration LEASE = Duration.ofSeconds(90);

    private static final Duration YOUNG = Duration.ofMinutes(30);
    private static final Duration MIDDLE = Duration.ofHours(6);
    private static final Duration LONGEST_WAIT = Duration.ofHours(1);

    private CheckSchedule() {}

    /** The last moment the provider is asked about an unpaid order. */
    static Instant finalAt(Instant createdAt) {
        return createdAt.plus(WINDOW);
    }

    static boolean isFinal(Instant now, Instant createdAt) {
        return !now.isBefore(finalAt(createdAt));
    }

    /**
     * The next check, or null once the final one has passed. {@code failures} is the number of checks in a row that could
     * not reach the provider; each doubles the wait up to an hour.
     */
    static Instant next(Instant now, Instant createdAt, int failures) {
        if (isFinal(now, createdAt)) {
            return null;
        }
        Duration age = Duration.between(createdAt, now);
        Duration base = age.compareTo(YOUNG) < 0
                ? Duration.ofMinutes(2)
                : age.compareTo(MIDDLE) < 0 ? Duration.ofMinutes(10) : Duration.ofHours(1);
        Duration wait = base.multipliedBy(1L << Math.min(Math.max(failures, 0), 5));
        if (wait.compareTo(LONGEST_WAIT) > 0) {
            wait = LONGEST_WAIT;
        }
        Instant candidate = now.plus(wait);
        Instant last = finalAt(createdAt);
        return candidate.isAfter(last) ? last : candidate;
    }

    /** A booked payment is asked about again once a day for 14 days, so a reversal whose notification was lost is still seen. */
    static final Duration REVIEW_EVERY = Duration.ofDays(1);

    static final Duration REVIEW_WINDOW = Duration.ofDays(14);

    /** The next review of a booked payment, or null once the 14 days since it was paid are over. */
    static Instant nextReview(Instant now, Instant paidAt) {
        Instant next = now.plus(REVIEW_EVERY);
        return next.isAfter(paidAt.plus(REVIEW_WINDOW)) ? null : next;
    }

    /** Retry of a booking that failed technically: one minute, doubling to thirty. */
    static Instant nextBooking(Instant now, int technicalFailures) {
        Duration wait = Duration.ofMinutes(1).multipliedBy(1L << Math.min(Math.max(technicalFailures - 1, 0), 5));
        return now.plus(wait.compareTo(Duration.ofMinutes(30)) > 0 ? Duration.ofMinutes(30) : wait);
    }
}
