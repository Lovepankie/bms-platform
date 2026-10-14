package com.rincoltech.bms.core.notifications.internal;

/**
 * The SMS provider port (chapter 12 section 12.3.1, ADR-036 decision 1). One call: send one message
 * to one E.164 number. Africa's Talking is the first adapter; the recording fake serves tests; the
 * sink records that a message would have gone and sends nothing. Reading delivery reports comes
 * with the delivery report slice (#215).
 *
 * <p>An adapter never logs or puts into an exception the number, the text or a key. It answers with
 * an {@link Outcome}, or throws one of two exceptions that decide the retry: {@link Transient}
 * means the request certainly did not produce a message (retry is safe), {@link Ambiguous} means it
 * may have (retry could text twice, so the row is marked unknown and not retried).
 */
interface SmsProvider {

    /** The name written to the delivery log: {@code africastalking}, {@code sink} or {@code fake}. */
    String name();

    /**
     * @param to an E.164 number
     * @param senderId an approved alphanumeric sender id, or null for the provider's shared sender
     * @param clientRef the outbox row's idempotency key, for the provider's records where it keeps any
     */
    Outcome send(String to, String body, String senderId, String clientRef) throws Transient, Ambiguous;

    enum Status {
        /** The provider took the message. */
        ACCEPTED,
        /** Nothing was sent: the sink recorded it. */
        SUNK,
        /** The provider refused this message for good (bad number, blocked, unsupported). */
        REJECTED
    }

    /**
     * @param provider the adapter's name, as in {@link #name()}
     * @param messageId the provider's id for the message, or null
     * @param providerSegments the segments the provider says it used, or null
     * @param costRaw the provider's cost string, for example {@code UGX 32.0000}, or null
     * @param failureReason for {@link Status#REJECTED}, a short code; never text or a number
     */
    record Outcome(
            String provider,
            Status status,
            String messageId,
            Integer providerSegments,
            String costRaw,
            String failureReason) {}

    /** The request certainly produced no message: the connection failed, or the provider answered with an error. */
    final class Transient extends Exception {

        Transient(String reason) {
            super(reason, null, false, false);
        }
    }

    /** The request may have produced a message: it was written and no usable answer came back. */
    final class Ambiguous extends Exception {

        Ambiguous(String reason) {
            super(reason, null, false, false);
        }
    }
}
