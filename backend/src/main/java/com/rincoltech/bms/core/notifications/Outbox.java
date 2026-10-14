package com.rincoltech.bms.core.notifications;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

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
     * A member SMS (chapter 11 section 11.3, ADR-036). The channel sends according to {@code BMS_SMS_MODE}
     * and the tenant's switch: with either off no row is sent, and a row for a tenant whose SMS is
     * switched off is not written at all. A row always has an expiry: a transactional SMS lives one
     * hour at most.
     */
    String SMS = "sms";
    /** Receipts, payment links: never held by the sending window (ADR-036 decision 5). */
    String TRANSACTIONAL = "transactional";
    /** Reminders before a due date: sent only inside the tenant's sending window. */
    String REMINDER = "reminder";
    /** Notices after a due date: sent only inside the tenant's sending window. */
    String ARREARS = "arrears";
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
     * @param category {@link #TRANSACTIONAL}, {@link #REMINDER} or {@link #ARREARS}; null means transactional
     * @param subject the member or account the message is about, for the opt-out and the delivery
     *     log; null when it is about neither
     * @param notBefore hold the first attempt until this time; null means now. A reminder or arrears
     *     SMS without it waits for the tenant's window and expires at the end of that window day
     */
    record Message(
            String channel,
            String recipient,
            String templateKey,
            Map<String, String> params,
            String idempotencyKey,
            String throttleKey,
            Instant expiresAt,
            String category,
            Subject subject,
            Instant notBefore) {

        public Message {
            params = Map.copyOf(params);
            category = category == null ? TRANSACTIONAL : category;
        }

        public Message(
                String channel,
                String recipient,
                String templateKey,
                Map<String, String> params,
                String idempotencyKey,
                String throttleKey,
                Instant expiresAt) {
            this(channel, recipient, templateKey, params, idempotencyKey, throttleKey, expiresAt, null, null, null);
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

    /** What a message is about: a {@code member} or an {@code account}, by id. */
    record Subject(String type, UUID id) {

        public static Subject member(UUID id) {
            return new Subject("member", id);
        }

        public static Subject account(UUID id) {
            return new Subject("account", id);
        }
    }
}
