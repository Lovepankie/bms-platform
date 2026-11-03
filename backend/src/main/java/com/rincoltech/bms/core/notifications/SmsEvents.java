package com.rincoltech.bms.core.notifications;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

/**
 * The one way a vertical queues a member SMS for a business event (FR-NTF-02, ADR-036 third amendment): a loan disbursed, a
 * repayment received, a loan closed, a savings deposit or withdrawal, an investment funded, maturing or paid out. It sits on
 * the outbox ({@link Outbox}) and adds what every event needs and no module should write again: the number is read without
 * guessing (a number we would not text is never queued), the key is deterministic per event and member (so a retried or
 * replayed event never texts twice), the tenant's name and balance rules are applied, an event whose value date is old is
 * never texted (a register import), and nothing that goes wrong here reaches the business transaction that called.
 *
 * <p>Every call runs in the caller's transaction, which must exist and be bound to the tenant. The row is written in that
 * transaction, so it exists exactly when the event commits and a rolled-back event sends nothing (FR-NTF-01). A provider
 * outage cannot reach the caller: the row is only written here, and the sender job delivers it later.
 *
 * <p><b>The guard.</b> Everything a call does, the lookups its suppliers make included, runs inside a savepoint of the
 * caller's transaction. A failure of any kind (a Java error, a database error that aborts the PostgreSQL transaction, an
 * exception that passed through a transactional proxy and marked the transaction rollback-only) rolls back to the savepoint,
 * is logged by event name and error class only and answered {@link Result#FAILED}; the caller's own work stays intact and
 * commits. Only a JVM-level error (out of memory) escapes.
 *
 * <p>What this port does not decide is left to the channel and checked again on every attempt: the operator's and the
 * tenant's switches, the switch-on time (a row queued before it is never sent), the opt-out, the sending window, the caps,
 * the tenant's own wording and the segment count. Personal data: the phone number is never logged.
 */
public interface SmsEvents {

    /** The one permission that may record a money event without telling the member: the business administrator's. */
    String WITHHOLD_PERMISSION = "core.settings.manage";

    /**
     * What a request's {@code notify_member} means. Omitted or true: the member is told (where the tenant has SMS on).
     * False is accepted only from a holder of {@value #WITHHOLD_PERMISSION}, which only the business administrator holds
     * (a register import is run by one): any other caller gets 403, before anything is recorded. Otherwise a cashier
     * could take money out of a member's account and the member would never be told. Call it in the request handler
     * before any money moves; it needs the request's principal.
     */
    static boolean notifyMember(Boolean requested) {
        if (!Boolean.FALSE.equals(requested)) {
            return true;
        }
        if (!CurrentPrincipal.require().hasPermission(WITHHOLD_PERMISSION)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "notify_member_forbidden",
                    "Permission denied",
                    "Only a business administrator can record this without sending the member a text message. Leave"
                            + " notify_member out, or ask an administrator.");
        }
        return false;
    }

    /** The outcome of a call, for the caller's tests and logs; no caller needs to act on it. */
    enum Result {
        /** A new row was written. */
        QUEUED,
        /** Nothing was written: the event was queued before, or SMS is switched off for the tenant. */
        NOT_QUEUED,
        /** The member has no number we would text (none, or one that cannot be read without guessing). */
        NO_PHONE,
        /** The event's value date is older than the cutoff: a register import is never texted. */
        TOO_OLD,
        /** Something failed; nothing was written and the caller's transaction is unharmed. */
        FAILED
    }

    /**
     * One message about a member.
     *
     * @param templateKey for example {@code loan.repayment_received}
     * @param memberId the member the message is about (the opt-out is matched on it); the message goes to {@code phone}
     * @param phone the member's primary phone, as stored; null or unreadable queues nothing
     * @param params the template values; {@code tenant_name} is added when absent, and {@code balance} is dropped
     *     unless the tenant asked for it ({@link #includeBalance})
     * @param eventRef what makes the event one event, for example the id of the transaction; with the template and the
     *     member it is the idempotency key, and it is what {@link #cancel} withdraws
     * @param category {@link Outbox#TRANSACTIONAL} (a receipt, not held by the sending window, expires after an hour),
     *     {@link Outbox#REMINDER} or {@link Outbox#ARREARS} (both held for the tenant's window and expiring at its end)
     * @param valueDate the date the event is for (the repayment's value date, the funding date); older than the cutoff
     *     ({@code BMS_SMS_EVENT_MAX_AGE_DAYS}, default 3 days before the tenant's today) queues nothing; null for an
     *     event that is about today by nature (the maturity reminder)
     * @param expiresAt for a reminder or an arrears notice, when it stops being true (a "due today" text at the end of
     *     the due date); it then expires at the earlier of this and the end of the window day, so a text deferred by the
     *     daily cap or a run resumed after the window is never sent late. Null: the end of the window day. For a
     *     transactional text, an expiry sooner than its hour shortens it (a payment link expires with its intent, issue
     *     #231); null or later keeps the hour.
     */
    record MemberSms(
            String templateKey,
            UUID memberId,
            String phone,
            Map<String, String> params,
            String eventRef,
            String category,
            LocalDate valueDate,
            Instant expiresAt) {

        public MemberSms {
            params = Map.copyOf(params);
            category = category == null ? Outbox.TRANSACTIONAL : category;
        }

        public MemberSms(
                String templateKey,
                UUID memberId,
                String phone,
                Map<String, String> params,
                String eventRef,
                String category,
                LocalDate valueDate) {
            this(templateKey, memberId, phone, params, eventRef, category, valueDate, null);
        }

        public MemberSms(String templateKey, UUID memberId, String phone, Map<String, String> params, String eventRef) {
            this(templateKey, memberId, phone, params, eventRef, null, null, null);
        }

        public MemberSms(
                String templateKey,
                UUID memberId,
                String phone,
                Map<String, String> params,
                String eventRef,
                String category) {
            this(templateKey, memberId, phone, params, eventRef, category, null, null);
        }
    }

    /**
     * Whether a message could be sent for the bound tenant now: the operator's switch and the tenant's own are both on
     * and neither cap is 0. {@link #queueWhenActive} asks first, so a tenant without SMS costs a money event two small
     * reads and no member lookup; {@link #queue} checks the switches again in the database, so the answer is an
     * optimisation and never the authority. Not guarded: call it only inside {@link #queueWhenActive}.
     */
    boolean active();

    /** Whether the tenant asked for the balance in receipts (default no: a phone may be shared). Not guarded. */
    boolean includeBalance();

    /** {@code 1234567} with the currency's exponent 0 is {@code 1,234,567}; with exponent 2, {@code 12,345.67}. Not guarded. */
    String amount(long minor, String currency);

    /** {@code 15/10/2026}: day, month and year, the order Uganda writes them, the same length always. */
    String date(LocalDate date);

    /**
     * Whether an event with this value date is recent enough to be texted (not older than the cutoff). Guarded: a failure
     * answers true, the safe side for a note that only records a decision.
     */
    boolean withinCutoff(LocalDate valueDate);

    /**
     * The words for the audit entry of a money event: whether the member was to be told ({@code notify_member}, after the
     * source and the request's flag) and whether the value date is within the cutoff ({@code sms_within_cutoff}). A
     * module puts them in the entry's after-map so that a movement recorded without a text can be found afterwards.
     */
    Map<String, Object> auditNote(boolean notifyMember, LocalDate valueDate);

    /** Writes the row in the caller's transaction (see the class comment). Never throws except for a JVM error. */
    Result queue(MemberSms message);

    /**
     * The call a module makes for an event: nothing is looked up, formatted or written unless {@link #active}, and the
     * whole of it, {@code phone} and {@code params} included, runs inside the guard. {@code phone} and {@code params} are
     * asked for only when SMS is active and the event is not too old.
     */
    default Result queueWhenActive(
            String templateKey,
            UUID memberId,
            String eventRef,
            String category,
            LocalDate valueDate,
            Supplier<String> phone,
            Supplier<Map<String, String>> params) {
        return queueWhenActive(templateKey, memberId, eventRef, category, valueDate, null, phone, params);
    }

    /** As above, with the message's own expiry ({@link MemberSms#expiresAt}). */
    Result queueWhenActive(
            String templateKey,
            UUID memberId,
            String eventRef,
            String category,
            LocalDate valueDate,
            Instant expiresAt,
            Supplier<String> phone,
            Supplier<Map<String, String>> params);

    /**
     * Where one member message stands (issue #772), read from its outbox row and the delivery log, never guessed.
     * JUSTIFICATION-A3: the port had no way to read a message's status; the anchor is only the insertion point.
     */
    enum TextStatus {
        /** Waiting in the outbox or held by the sender: it has not reached the provider yet. */
        PENDING,
        /** The provider took it (on staging, the sink did). */
        SENT,
        /** It reached the provider and failed, was refused or blocked: it did not arrive. */
        FAILED,
        /** It went to the provider but the outcome is unknown (no automatic retry): it may or may not have arrived. */
        UNCONFIRMED,
        /** It never reached the provider: it expired, was withdrawn or dropped, or no row was ever written. */
        NOT_SENT
    }

    /** What {@link #queueTracked} did: the outcome and, when a row was written, its outbox id. */
    record Queued(Result result, UUID rowId) {}

    /**
     * As {@link #queue}, also answering the outbox id of the row written, for a caller that will ask its
     * {@link #status} later (the payment link, issue #772). Guarded like {@link #queue}.
     */
    Queued queueTracked(MemberSms message);

    /**
     * Where the member message with this outbox id ({@link #queueTracked}) stands, for the bound tenant; a null id (one
     * queued before ids were kept) answers {@link TextStatus#NOT_SENT}. Guarded: a failure answers
     * {@link TextStatus#PENDING}, which never claims that a text went.
     */
    TextStatus status(UUID rowId);

    /**
     * The bound business's name as a short plain text carries it (issue #772): every dash written as a space, a curly
     * quote as a straight one, any other character outside the GSM-7 default alphabet dropped (one would make the whole
     * text UCS-2 and more than double its segments), spaces collapsed, and at most {@code maxLength} characters, cut
     * after a whole word when one ends in the second half. Not guarded.
     */
    String plainTenantName(int maxLength);

    /**
     * Withdraws the messages of the event {@code eventRef} of the bound tenant that nobody has claimed yet, as a reversal
     * must: a receipt for an entry made on the wrong account would tell the wrong person. A message already claimed by the
     * sender may be at the provider and is left. Guarded like the rest; returns how many were withdrawn (0 on a failure).
     */
    int cancel(String eventRef);

    /**
     * As {@link #cancel}, for a reminder or arrears notice the member no longer needs (the instalment was paid, the loan
     * closed or written off): the rows are labelled {@code cancelled_settled}, not as a reversal. Guarded likewise.
     */
    int cancelSettled(String eventRef);
}
