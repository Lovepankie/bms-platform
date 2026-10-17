package com.rincoltech.bms.core.notifications;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A member's SMS opt-out, for the module that owns the member screen (FR-NTF-14, ADR-036 decision 8, spec 4.9).
 * Runs in the caller's transaction, which must exist and be bound to the tenant. The caller has already checked its
 * own permission and the member's branch scope; this port only keeps the table.
 *
 * <p>An opt-out recorded here is about the member and follows them to a new number: it blocks the member whatever
 * number a message goes to (a number reused by someone else is not blocked, since the row names the member). A number
 * alone, with no member, is the administrator's list (see the opt-out routes of {@code /sms/opt-outs}). Every change
 * is audited with the masked number only.
 */
public interface SmsOptOuts {

    /** {@code REMINDERS} blocks reminders and arrears notices; {@code ALL} blocks every SMS, receipts included. */
    enum Scope {
        REMINDERS,
        ALL;

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Optional<Scope> of(String key) {
            for (Scope s : values()) {
                if (s.key().equals(key)) {
                    return Optional.of(s);
                }
            }
            return Optional.empty();
        }
    }

    /** One active opt-out of a member. {@code maskedNumber} is the number the member had when it was recorded. */
    record Entry(UUID id, UUID memberId, Scope scope, String maskedNumber, Instant createdAt) {}

    /** The member's active opt-out, if any. */
    Optional<Entry> status(UUID memberId);

    /**
     * Opts the member out of {@code scope}, or changes the scope of the active opt-out. Doing it twice with the same
     * scope changes nothing and audits nothing. {@code phoneE164} (the member's phone, may be null) is kept only as a
     * masked number for the screen. Narrowing {@code ALL} to {@code REMINDERS} is a partial lift: it needs the tenant
     * administrator and a {@code reason} of 3 to 200 characters (422 {@code reason_required}), kept in the audit row;
     * for any other change the reason is ignored.
     */
    Entry optOut(UUID memberId, String phoneE164, Scope scope, String reason);

    /**
     * Lifts the member's active opt-out; false when there was none. Only the tenant administrator may (the opt-out is the
     * member's protection against being pestered, so a field officer cannot undo it): 403 {@code permission_denied}
     * otherwise, and a reason of 3 to 200 characters is required (422 {@code reason_required}) and goes into the audit row.
     * Narrowing {@code ALL} to {@code REMINDERS} through {@link #optOut} is a lift of part of it and needs the same standing.
     */
    boolean lift(UUID memberId, String reason);
}
