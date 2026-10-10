package com.rincoltech.bms.core.operations;

import java.util.function.Supplier;

/**
 * The {@code Idempotency-Key} protocol of chapter 7 section 7.8, for every money-moving route (retail and lending alike).
 * Runs inside the caller's transaction, so the key row and the business write commit or roll back
 * together: a missing key is 422 {@code idempotency_key_missing}; a key still held by a concurrent
 * request after the 5 second lock timeout is 409 {@code idempotency_in_progress}; a key reused with a
 * different request is 422 {@code idempotency_key_reused}; a completed key replays its stored
 * response without doing the work again.
 */
public interface Idempotency {

    <T> Outcome<T> once(
            String key, String method, String path, Object request, Class<T> responseType, Supplier<T> work);

    record Outcome<T>(T body, boolean replayed) {}
}
