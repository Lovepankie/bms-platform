package com.rincoltech.bms.core.notifications;

import java.time.Instant;
import java.util.Map;

/**
 * The notification outbox (FR-NTF-01, FR-NTF-10, spec section 11): a message is written in the
 * caller's transaction, so it exists exactly when the change that causes it commits, and a sender
 * job delivers it afterwards, at least once. The idempotency key is unique: writing the same key
 * twice keeps the first row, so a retried cause never queues a second copy.
 */
public interface Outbox {

    String EMAIL = "email";
    String TELEGRAM = "telegram";
    /**
     * A member SMS (chapter 11 section 11.3). No sender exists until the aggregator is chosen
     * (pending ADR-013): rows stay pending until their {@code expiresAt}, then the purge clears them.
     */
    String SMS = "sms";
    /** The recipient of a Telegram operator alert; the chat id stays in the environment. */
    String OPERATOR_CHAT = "operator";
    /** The prefix of a Telegram recipient that names a numeric chat (a tenant's digest, ADR-030). */
    String TELEGRAM_CHAT_PREFIX = "chat:";

    /**
     * Writes one row in the caller's transaction, which must exist. Returns false when the
     * idempotency key was already used.
     */
    boolean enqueue(Message message);

    /**
     * Rows written since {@code since} with this throttle key, or, with a null key, of this
     * template: the volume bounds of the sign-up endpoints are counted in the database, not in
     * memory. Runs in the caller's transaction.
     */
    int countSince(String throttleKey, String templateKey, Instant since, Instant until);

    /**
     * @param channel {@link #EMAIL}, {@link #TELEGRAM} or {@link #SMS}
     * @param recipient an email address, {@link #OPERATOR_CHAT}, {@link #TELEGRAM_CHAT_PREFIX} and a numeric
     *     chat id, or an E.164 phone number for SMS; never logged unmasked
     * @param templateKey for example {@code onboarding.activation}; rendered when sent
     * @param params template values; cleared from the row once it is sent
     * @param idempotencyKey unique per message, for example {@code onboarding.activation:<id>}
     * @param throttleKey groups rows for a volume bound (see {@link #countSince}), or null
     * @param expiresAt when the link the message carries stops working, or null: the row is never
     *     sent, sent again or kept with its parameters after it
     */
    record Message(
            String channel,
            String recipient,
            String templateKey,
            Map<String, String> params,
            String idempotencyKey,
            String throttleKey,
            Instant expiresAt) {

        public Message {
            params = Map.copyOf(params);
        }

        public Message(
                String channel,
                String recipient,
                String templateKey,
                Map<String, String> params,
                String idempotencyKey) {
            this(channel, recipient, templateKey, params, idempotencyKey, null, null);
        }
    }
}
