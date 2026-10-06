package com.rincoltech.bms.core.notifications.internal;

/**
 * One delivery channel of the outbox. A channel that is not {@link #enabled()} is never asked to
 * send: its rows stay {@code pending} until it is configured. Tests replace the real channels with
 * fakes; nothing here may log the recipient, the text or a secret.
 */
interface OutboxChannel {

    String channel();

    boolean enabled();

    /**
     * Sends one message or throws. Only a {@link SendFailure}'s message is ever stored or shown,
     * so a sender reports what went wrong through one, written to carry no text, recipient or
     * secret; any other exception is reduced to its class.
     */
    void send(Delivery delivery) throws Exception;

    /** A failure a sender describes itself: its message is safe to store, log and show. */
    final class SendFailure extends Exception {

        SendFailure(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * @param recipient an email address, or {@code operator} for the Telegram operator chat
     * @param idempotencyKey the row's key, carried to the provider where it supports one
     */
    record Delivery(String recipient, String subject, String text, String idempotencyKey) {

        @Override
        public String toString() {
            return "Delivery[key=" + idempotencyKey + "]";
        }
    }
}
