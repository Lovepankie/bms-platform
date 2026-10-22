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

    /** A booked payment is asked about again once a day while it is young, so a reversal whose notification was lost is still seen. */
    static final Duration REVIEW_EVERY = Duration.ofDays(1);

    /** Mobile money is reversed within a week or not at all (spec 3.9). */
    static final Duration MOBILE_MONEY_WINDOW = Duration.ofDays(7);

    /** A card payment can be charged back for months (spec 3.9): daily for the first 14 days, weekly after, to 120 days. */
    static final Duration CARD_WINDOW = Duration.ofDays(120);

    static final Duration CARD_DAILY_FOR = Duration.ofDays(14);
    static final Duration CARD_THEN_EVERY = Duration.ofDays(7);

    /** A reversal that waits for a checker is looked at again every week: the request may have gone stale or expired. */
    static final Duration REVIEW_OPEN_EVERY = Duration.ofDays(7);

    /**
     * The method strings the provider is known to report for a mobile money wallet: the family strings of its documentation
     * (spec 3.12) and the two names of Airtel's and M-Pesa's. The exact Ugandan strings are not observed yet (spec 3.12.2), so
     * a string that is not here is treated as a card (the longer window) and logged once, so the list can be corrected from what
     * the provider really sends rather than guessed at.
     */
    static final java.util.Set<String> MOBILE_MONEY_METHODS =
            java.util.Set.of("MTN", "AIRTEL", "MPESA", "M-PESA", "TIGO");

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CheckSchedule.class);
    private static final java.util.Set<String> UNSEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static boolean mobileMoney(String reportedMethod) {
        if (reportedMethod == null || reportedMethod.isBlank()) {
            return false;
        }
        String m = reportedMethod.trim().toUpperCase(java.util.Locale.ROOT);
        if (MOBILE_MONEY_METHODS.contains(m)) {
            return true;
        }
        if (m.matches("^[A-Z0-9 _.-]{1,40}$") && UNSEEN.size() < 50 && UNSEEN.add(m)) {
            log.warn(
                    "payment method {} is not on the mobile money list, so its payments are reviewed as card payments",
                    m);
        }
        return false;
    }

    /**
     * The next review of a booked payment, or null once its window is over: for mobile money, daily for 7 days; for a card,
     * daily for 14 days, then weekly until 120 days after it was paid (spec 3.9).
     */
    static Instant nextReview(Instant now, Instant paidAt, String reportedMethod) {
        boolean wallet = mobileMoney(reportedMethod);
        Duration window = wallet ? MOBILE_MONEY_WINDOW : CARD_WINDOW;
        Duration step =
                wallet || Duration.between(paidAt, now).compareTo(CARD_DAILY_FOR) < 0 ? REVIEW_EVERY : CARD_THEN_EVERY;
        Instant next = now.plus(step);
        return next.isAfter(paidAt.plus(window)) ? null : next;
    }

    /** Retry of a booking that failed technically: one minute, doubling to thirty. */
    static Instant nextBooking(Instant now, int technicalFailures) {
        Duration wait = Duration.ofMinutes(1).multipliedBy(1L << Math.min(Math.max(technicalFailures - 1, 0), 5));
        return now.plus(wait.compareTo(Duration.ofMinutes(30)) > 0 ? Duration.ofMinutes(30) : wait);
    }
}
