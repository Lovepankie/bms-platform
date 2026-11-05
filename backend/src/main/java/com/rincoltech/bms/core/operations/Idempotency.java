package com.rincoltech.bms.core.operations;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The {@code Idempotency-Key} protocol of chapter 7 section 7.8, for every money-moving route (retail and lending alike).
 * Runs inside the caller's transaction, so the key row and the business write commit or roll back
 * together: a missing key is 422 {@code idempotency_key_missing}; a key still held by a concurrent
 * request after the 5 second lock timeout is 409 {@code idempotency_in_progress}; a key reused with a
 * different request is 422 {@code idempotency_key_reused}; a completed key replays its stored
 * response without doing the work again, to the person who stored it only: the same key from anyone
 * else in the business is 422 {@code idempotency_key_other_user} (issue #852, ADR-026 decision 5).
 */
public interface Idempotency {

    /** The code of a key still held by a concurrent request: the first attempt may yet succeed. */
    String IN_PROGRESS = "idempotency_in_progress";

    /** The code of a key sent again with a different request: the first request may have succeeded. */
    String KEY_REUSED = "idempotency_key_reused";

    /**
     * The code of a key another person already used in this business (issue #852): the stored answer is theirs and is
     * never shown, and nothing is done.
     */
    String OTHER_USER = "idempotency_key_other_user";

    <T> Outcome<T> once(
            String key, String method, String path, Object request, Class<T> responseType, Supplier<T> work);

    /**
     * For a job or a provider's callback, with no signed-in person (ADR-026 decision 5): the key is held under
     * {@link #jobPrincipal} of {@code job}, the same on every run, so a retried run replays its first answer.
     */
    <T> Outcome<T> onceAsJob(
            String job, String key, String method, String path, Object request, Class<T> type, Supplier<T> work);

    /** A name-based UUID per job, so it never equals a person's random one. */
    static UUID jobPrincipal(String job) {
        return UUID.nameUUIDFromBytes(("idempotency-job:" + job).getBytes(StandardCharsets.UTF_8));
    }

    record Outcome<T>(T body, boolean replayed) {}
}
