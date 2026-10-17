package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.internal.SmsTemplateLanguage.Event;
import com.rincoltech.bms.core.notifications.internal.SmsTemplateLanguage.Placeholder;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The event types whose SMS wording a tenant may change (FR-NTF-03, spec 4.10), each with the placeholders it
 * offers. An event appears here when the application sends it today: the two savings receipts, the loan events
 * (disbursed, repayment received, closed) and the investment events (funded, maturing, paid out) of issue #221, and
 * the reminders and arrears notices of issue #227. The default text is the one
 * the channel sends when a tenant has no override ({@link OutboxTemplates}); a test renders every default through this
 * language so the two cannot drift.
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

    private static final Placeholder TENANT =
            new Placeholder("tenant_name", "Your business name", "Sunrise Savers", 100);
    private static final Placeholder CURRENCY = new Placeholder("currency", "The currency code", "UGX", 3);
    private static final Placeholder AMOUNT = new Placeholder("amount", "The amount of this transaction", "50,000", 24);
    private static final Placeholder DATE = new Placeholder("date", "The date, day/month/year", "15/10/2026", 10);
    private static final Placeholder LOAN_NO = new Placeholder("loan_no", "The loan number", "LN-HQ-000123", 24);
    private static final Placeholder ACCOUNT_NO =
            new Placeholder("account_no", "The investment account number", "IV000123", 30);
    private static final Placeholder MATURITY =
            new Placeholder("maturity_date", "The maturity date, day/month/year", "15/10/2027", 10);

    private static final List<Placeholder> LOAN_DISBURSED = List.of(
            TENANT,
            CURRENCY,
            new Placeholder("amount", "The amount paid out: the loan less fees deducted at disbursement", "50,000", 24),
            DATE,
            LOAN_NO,
            new Placeholder("next_amount", "The first instalment", "120,000", 24),
            new Placeholder("next_due_date", "The first due date, day/month/year", "15/11/2026", 10));

    private static final List<Placeholder> LOAN_RECEIPT = List.of(
            TENANT,
            CURRENCY,
            AMOUNT,
            DATE,
            LOAN_NO,
            new Placeholder("receipt_no", "The receipt number", "RC-HQ-000042", 30));

    private static final List<Placeholder> INVESTMENT_FUNDED = List.of(
            TENANT,
            CURRENCY,
            AMOUNT,
            DATE,
            ACCOUNT_NO,
            MATURITY,
            new Placeholder("receipt_no", "The receipt number", "RC-HQ-000042", 30));

    private static final List<Placeholder> INVESTMENT_MATURING =
            List.of(TENANT, CURRENCY, AMOUNT, ACCOUNT_NO, MATURITY);

    /** A reminder before or on a due date: what is due on the instalment, and its date. */
    private static final List<Placeholder> LOAN_DUE = List.of(
            TENANT,
            CURRENCY,
            new Placeholder("amount", "The amount due on the instalment", "120,000", 24),
            LOAN_NO,
            new Placeholder("due_date", "The due date, day/month/year", "15/11/2026", 10));

    /** An arrears notice: the loan's arrears and its days past due. */
    private static final List<Placeholder> LOAN_ARREARS = List.of(
            TENANT,
            CURRENCY,
            new Placeholder("arrears", "The amount in arrears", "120,000", 24),
            LOAN_NO,
            new Placeholder("dpd", "The days past due", "7", 5));

    private static final List<Placeholder> INVESTMENT_PAID_OUT = List.of(
            TENANT,
            CURRENCY,
            AMOUNT,
            DATE,
            ACCOUNT_NO,
            new Placeholder("voucher_no", "The voucher number", "VC-HQ-000042", 30));

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
                    RECEIPT_REQUIRED),
            event("loan.disbursed", "Loan disbursed", LOAN_DISBURSED, Set.of("loan_no", "amount")),
            event("loan.repayment_received", "Loan repayment receipt", LOAN_RECEIPT, Set.of("loan_no", "amount")),
            event("loan.closed", "Loan fully paid", List.of(TENANT, LOAN_NO), Set.of("loan_no")),
            event("investment.funded", "Investment received", INVESTMENT_FUNDED, Set.of("account_no", "amount")),
            event(
                    "investment.maturing",
                    "Investment maturing",
                    INVESTMENT_MATURING,
                    Set.of("account_no", "maturity_date")),
            event("investment.paid_out", "Investment paid out", INVESTMENT_PAID_OUT, Set.of("account_no", "amount")),
            event("loan.due_in_3_days", "Loan instalment due in 3 days", LOAN_DUE, Set.of("loan_no", "amount")),
            event("loan.due_today", "Loan instalment due today", LOAN_DUE, Set.of("loan_no", "amount")),
            event("loan.overdue_1", "Loan overdue (1 day)", LOAN_ARREARS, Set.of("loan_no", "arrears")),
            event("loan.overdue_7", "Loan overdue (7 days)", LOAN_ARREARS, Set.of("loan_no", "arrears")),
            event("loan.overdue_30", "Loan overdue (30 days)", LOAN_ARREARS, Set.of("loan_no", "arrears")));

    private static Event event(String key, String title, List<Placeholder> placeholders, Set<String> required) {
        return new Event(key, title, OutboxTemplates.defaultText(CHANNEL, key), placeholders, required);
    }

    private SmsTemplateEvents() {}

    static Optional<Event> find(String key) {
        return ALL.stream().filter(e -> e.key().equals(key)).findFirst();
    }
}
