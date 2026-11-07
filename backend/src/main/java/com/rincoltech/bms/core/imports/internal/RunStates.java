package com.rincoltech.bms.core.imports.internal;

import java.util.Map;
import java.util.Set;

/**
 * The run state diagram of spec section 3.11, as data:
 *
 * <pre>
 * draft -> uploaded -> in_review -> ready -> committing -> committed -> signed_off -> purged
 *    \________\__________\___________\-> cancelled
 * </pre>
 *
 * A run is cancelled only before it commits: once a batch may be written, the pre-import dump is the only way back.
 */
final class RunStates {

    static final String DRAFT = "draft";
    static final String UPLOADED = "uploaded";
    static final String IN_REVIEW = "in_review";
    static final String READY = "ready";
    static final String COMMITTING = "committing";
    static final String COMMITTED = "committed";
    static final String SIGNED_OFF = "signed_off";
    static final String PURGED = "purged";
    static final String CANCELLED = "cancelled";

    private static final Map<String, Set<String>> NEXT = Map.of(
            DRAFT, Set.of(UPLOADED, CANCELLED),
            UPLOADED, Set.of(IN_REVIEW, CANCELLED),
            IN_REVIEW, Set.of(READY, CANCELLED),
            READY, Set.of(COMMITTING, CANCELLED),
            COMMITTING, Set.of(COMMITTED),
            COMMITTED, Set.of(SIGNED_OFF),
            SIGNED_OFF, Set.of(PURGED),
            PURGED, Set.of(),
            CANCELLED, Set.of());

    /** The states only the commit reaches, never a plain transition. */
    static final Set<String> COMMIT_ONLY = Set.of(COMMITTING, COMMITTED);

    /** The state only the customer's sign-off reaches (issue #134, spec section 3.10), never a plain transition. */
    static final Set<String> SIGN_OFF_ONLY = Set.of(SIGNED_OFF);

    private RunStates() {}

    static boolean known(String status) {
        return NEXT.containsKey(status);
    }

    static boolean allowed(String from, String to) {
        return NEXT.getOrDefault(from, Set.of()).contains(to);
    }

    private static final Map<String, String> WORDS = Map.of(
            DRAFT, "a draft",
            UPLOADED, "uploaded",
            IN_REVIEW, "in review",
            READY, "ready",
            COMMITTING, "being imported",
            COMMITTED, "imported",
            SIGNED_OFF, "signed off",
            PURGED, "purged",
            CANCELLED, "cancelled");

    /** A state in plain words for a message a person reads, for example "in review"; an unknown state as given. */
    static String words(String status) {
        return WORDS.getOrDefault(status, status);
    }

    /** The stage time column a state stamps, for example {@code ready_at}. */
    static String stampColumn(String status) {
        return status + "_at";
    }
}
