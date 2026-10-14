package com.rincoltech.bms.core.notifications.internal;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One delivery channel of the outbox. A channel that is not {@link #enabled()} is never asked to
 * send: its rows stay {@code pending} until it is configured. Tests replace the real channels with
 * fakes; nothing here may log the recipient, the text or a secret.
 */
interface OutboxChannel {

    String channel();

    boolean enabled();

    /**
     * True when the channel renders its own text (SMS does, inside a tenant transaction, because a
     * tenant setting decides what the text holds): the dispatcher then passes the template key and
     * parameters and no rendered text.
     */
    default boolean rendersItself() {
        return false;
    }

    /**
     * Sends one message and says what happened (ADR-036 decision 6), or throws. A thrown
     * {@link SendFailure} is a clean failure the dispatcher retries with backoff; its message is the
     * only text ever stored or shown, so a sender writes it to carry no text, recipient or secret;
     * any other exception is reduced to its class and also counts as a clean failure.
     */
    Result send(Delivery delivery) throws Exception;

    /** A failure a sender describes itself: its message is safe to store, log and show. */
    final class SendFailure extends Exception {

        SendFailure(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * What a send came to. {@link Sent}: done. {@link Deferred}: not sent yet and not a failure
     * (outside the sending window, over a cap): the attempt is given back and the row waits until
     * the given time. {@link Unknown}: the request may or may not have reached the provider, so it
     * is never retried automatically. {@link Refused}: the channel will never send this row (an
     * opt-out, SMS switched off for the tenant, a number it will not text); the reason is a short
     * code, never text or a number.
     */
    sealed interface Result {

        Result SENT = new Sent();

        record Sent() implements Result {}

        /** @param extendExpiryTo a later expiry the row should get (bounded by the database), or null */
        record Deferred(Instant until, Instant extendExpiryTo) implements Result {

            public Deferred(Instant until) {
                this(until, null);
            }
        }

        record Unknown() implements Result {}

        record Refused(String reason) implements Result {}

        static Result deferred(Instant until) {
            return new Deferred(until);
        }

        static Result deferred(Instant until, Instant extendExpiryTo) {
            return new Deferred(until, extendExpiryTo);
        }

        static Result unknown() {
            return new Unknown();
        }

        static Result refused(String reason) {
            return new Refused(reason);
        }
    }

    /**
     * @param recipient an email address, {@code operator} for the Telegram operator chat, or
     *     {@code chat:<id>} for a numeric Telegram chat, or an E.164 phone number for SMS
     * @param idempotencyKey the row's key, carried to the provider where it supports one
     * @param context what the row says about itself, for a channel that needs the tenant, the
     *     category or the subject (SMS); {@link RowContext#NONE} for a platform row
     */
    record Delivery(String recipient, String subject, String text, String idempotencyKey, RowContext context) {

        public Delivery(String recipient, String subject, String text, String idempotencyKey) {
            this(recipient, subject, text, idempotencyKey, RowContext.NONE);
        }

        @Override
        public String toString() {
            return "Delivery[key=" + idempotencyKey + "]";
        }
    }

    /**
     * The outbox row beyond its recipient and text. {@code params} are the template values, only
     * for a channel that {@link #rendersItself()}.
     */
    record RowContext(
            UUID outboxId,
            UUID tenantId,
            String category,
            String subjectType,
            UUID subjectId,
            String templateKey,
            Map<String, String> params,
            int attempt,
            Instant queuedAt,
            Instant expiresAt) {

        static final RowContext NONE =
                new RowContext(null, null, "transactional", null, null, null, Map.of(), 1, null, null);

        @Override
        public String toString() {
            return "RowContext[outbox=" + outboxId + "]";
        }
    }
}
