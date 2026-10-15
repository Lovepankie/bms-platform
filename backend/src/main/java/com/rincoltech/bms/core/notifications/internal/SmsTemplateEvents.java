package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.internal.SmsTemplateLanguage.Event;
import com.rincoltech.bms.core.notifications.internal.SmsTemplateLanguage.Placeholder;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The event types whose SMS wording a tenant may change (FR-NTF-03, spec 4.10), each with the placeholders it
 * offers. An event appears here when the application sends it today: the two savings receipts. The loan and
 * investment events of spec 4.11 and the reminders (#227) join the list when their slice queues them, with
 * their own whitelist. The default text is the one the channel sends when a tenant has no override
 * ({@link OutboxTemplates}); a test renders every default through this language so the two cannot drift.
 *
 * <p>No event offers a balance: an override never carries one (on a phone a household shares, a balance tells
 * everyone what a member holds). A tenant that switched {@code include_balance} on keeps the default wording with
 * the balance; reword a receipt and it goes without.
 */
final class SmsTemplateEvents {

    static final String CHANNEL = "sms";

    private static final List<Placeholder> RECEIPT = List.of(
            new Placeholder("tenant_name", "Your business name", "Sunrise Savers", 100),
            new Placeholder("currency", "The currency code", "UGX", 3),
            new Placeholder("amount", "The amount of this transaction", "50,000", 24),
            new Placeholder("account_no", "The savings account number", "SV000123", 30),
            new Placeholder("receipt_no", "The receipt or voucher number", "RC-0042", 30));

    private static final Set<String> RECEIPT_REQUIRED = Set.of("amount", "account_no");

    static final List<Event> ALL = List.of(
            new Event(
                    "savings.deposit",
                    "Savings deposit receipt",
                    OutboxTemplates.defaultText(CHANNEL, "savings.deposit"),
                    RECEIPT,
                    RECEIPT_REQUIRED),
            new Event(
                    "savings.withdrawal",
                    "Savings withdrawal receipt",
                    OutboxTemplates.defaultText(CHANNEL, "savings.withdrawal"),
                    RECEIPT,
                    RECEIPT_REQUIRED));

    private SmsTemplateEvents() {}

    static Optional<Event> find(String key) {
        return ALL.stream().filter(e -> e.key().equals(key)).findFirst();
    }
}
