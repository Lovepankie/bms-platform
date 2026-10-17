package com.rincoltech.bms.core.notifications.internal;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The outbox's message texts, by template key and channel. Plain text, plain wording, no
 * commercial figures. A {@code {name}} placeholder takes the parameter of that name; a missing
 * parameter or an unknown key is an error, so a message is never sent half filled.
 */
@Component
class OutboxTemplates {

    record Rendered(String subject, String text) {}

    private record Template(String subject, String text) {}

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    private static final Map<String, Template> EMAIL = Map.of(
            // Sent to an address nobody has confirmed yet: it carries no text the applicant typed,
            // only the server's reference and link, so the form cannot be used to send someone
            // else's words from the platform's sender (review B1).
            "onboarding.verify_email",
            new Template("Confirm your email for BMS application {reference}", """
                    Hello,

                    This email address was used to apply to use BMS (application {reference}).
                    Open this link to confirm the address and follow the application:

                    {link}

                    The link works for 7 days. If you did not apply, ignore this email: nothing
                    happens without the confirmation.

                    Rincoltech
                    """),
            "onboarding.needs_info",
            new Template("We need a little more information", """
                    Hello {contact_name},

                    We are looking at the application for {business_name} and need a little more information:

                    {note}

                    Open your application page to answer:

                    {link}

                    Rincoltech
                    """),
            "onboarding.rejected",
            new Template("About your BMS application", """
                    Hello {contact_name},

                    We are sorry: we cannot accept the application for {business_name} at this time.

                    {note}

                    You can see your application here: {link}

                    Rincoltech
                    """),
            "onboarding.activation",
            new Template("Your BMS account is ready", """
                    Hello {contact_name},

                    {business_name} is ready on BMS. Open this link to set your password and turn on
                    two-step sign-in. The link works once, until {expires_at}.

                    {link}

                    After that, sign in at {sign_in_url}

                    Rincoltech
                    """),
            "onboarding.operator_alert",
            new Template("New verified application {reference}", """
                    A new application has confirmed its email and is waiting in the queue.

                    Reference: {reference}
                    Business: {business_name}
                    Modules: {modules}

                    Open the operator portal: {portal_url}
                    """),
            // The insights digest (ADR-030): the text is built by lending.insights as plain text
            // from the tenant's own figures, ready for a WhatsApp relay later.
            "insights.daily_digest",
            new Template("{business_name}: daily brief for {date}", "{text}\n"));

    private static final Map<String, Template> TELEGRAM = Map.of(
            "onboarding.operator_alert",
            new Template(
                    null,
                    "New BMS application {reference}: {business_name} ({modules}). Open the portal: {portal_url}"),
            "onboarding.cap_reached",
            new Template(
                    null,
                    "BMS sign-up cap reached for the hour starting {hour} UTC: {applications} new applications and"
                            + " {emails} confirmation emails. New sign-ups are answered but nothing is sent until the"
                            + " hour passes. Check the edge rule and the portal."),
            "insights.daily_digest",
            new Template(null, "{text}"),
            // ADR-036 decision 13: the operator hears when SMS use reaches 80 percent of a daily cap or
            // a cap holds messages back. The scope is "platform" or a tenant id; no number, no text.
            "sms.provider_alert",
            new Template(
                    null,
                    "BMS SMS provider problem on {day}: {reason}. Check the Africa's Talking key and credit. Messages"
                            + " are retried, then fail; receipts expire after an hour."),
            "sms.cap_alert",
            new Template(
                    null,
                    "BMS SMS cap on {day}: {scope} is at {state} of its daily limit of {cap}. Messages over the cap"
                            + " are held, not dropped, until they expire. Check the usage and the cap."));

    /**
     * Member receipts (chapter 11 section 11.3.1, ADR-036 decision 14). Short enough for one SMS; no
     * name, only the account number. The default carries no balance: on a phone a household shares, a
     * balance tells everyone what a member holds. A template named {@code <key>.with_balance} is the
     * variant a tenant that switched {@code sms_include_balance} on gets, chosen when the parameters
     * carry a balance; the channel drops the balance parameter again when the setting is off.
     */
    private static final Map<String, Template> SMS = Map.ofEntries(
            Map.entry(
                    "savings.deposit",
                    new Template(
                            null,
                            "{tenant_name}: Deposit of {currency} {amount} on account {account_no}. Receipt {receipt_no}.")),
            Map.entry(
                    "savings.deposit.with_balance",
                    new Template(
                            null,
                            "{tenant_name}: Deposit of {currency} {amount} on account {account_no}. Receipt {receipt_no}."
                                    + " Balance {currency} {balance}.")),
            Map.entry(
                    "savings.withdrawal",
                    new Template(
                            null,
                            "{tenant_name}: Withdrawal of {currency} {amount} from account {account_no}. Voucher {receipt_no}.")),
            Map.entry(
                    "savings.withdrawal.with_balance",
                    new Template(
                            null,
                            "{tenant_name}: Withdrawal of {currency} {amount} from account {account_no}. Voucher {receipt_no}."
                                    + " Balance {currency} {balance}.")),
            // The loan and investment events of FR-NTF-02 (issue #221). Same rules as the savings receipts: no name
            // of the member, only the business, the loan or account number and the amounts; the repayment receipt
            // omits the balance unless the tenant asked for it. Dates are dd/MM/yyyy.
            Map.entry(
                    "loan.disbursed",
                    new Template(
                            null,
                            "{tenant_name}: Loan {loan_no}: {currency} {amount} paid out on {date}."
                                    + " First payment {currency} {next_amount} due {next_due_date}.")),
            Map.entry(
                    "loan.repayment_received",
                    new Template(
                            null,
                            "{tenant_name}: Received {currency} {amount} on {date} for loan {loan_no}. Receipt {receipt_no}.")),
            Map.entry(
                    "loan.repayment_received.with_balance",
                    new Template(
                            null,
                            "{tenant_name}: Received {currency} {amount} on {date} for loan {loan_no}. Receipt {receipt_no}."
                                    + " Balance {currency} {balance}.")),
            Map.entry("loan.closed", new Template(null, "{tenant_name}: Loan {loan_no} is fully paid. Thank you.")),
            Map.entry(
                    "investment.funded",
                    new Template(
                            null,
                            "{tenant_name}: Investment {account_no} of {currency} {amount} received on {date}."
                                    + " Matures {maturity_date}. Receipt {receipt_no}.")),
            Map.entry(
                    "investment.maturing",
                    new Template(
                            null,
                            "{tenant_name}: Investment {account_no} of {currency} {amount} matures on {maturity_date}."
                                    + " Please contact us about your instruction.")),
            // The reminders and arrears notices of FR-NTF-02 (issue #227), queued by the nightly arrears run, in the
            // wording of chapter 11 section 11.3.1: the business, the loan number, the amount and the due date or the
            // days overdue, never the member's name or anyone else, and never a threat (the chapter's "to avoid
            // penalties" is left out for that reason). The first notice says "overdue" without a count, so it never
            // reads "1 days".
            Map.entry(
                    "loan.due_in_3_days",
                    new Template(
                            null,
                            "{tenant_name}: Reminder, {currency} {amount} for loan {loan_no} is due on {due_date}.")),
            Map.entry(
                    "loan.due_today",
                    new Template(null, "{tenant_name}: {currency} {amount} for loan {loan_no} is due today.")),
            Map.entry(
                    "loan.overdue_1",
                    new Template(
                            null,
                            "{tenant_name}: Loan {loan_no} is overdue. Amount in arrears {currency} {arrears}. Please pay or"
                                    + " contact us.")),
            Map.entry(
                    "loan.overdue_7",
                    new Template(
                            null,
                            "{tenant_name}: Loan {loan_no} is {dpd} days overdue. Amount in arrears {currency} {arrears}."
                                    + " Please pay or contact us.")),
            Map.entry(
                    "loan.overdue_30",
                    new Template(
                            null,
                            "{tenant_name}: Loan {loan_no} is {dpd} days overdue. Amount in arrears {currency} {arrears}."
                                    + " Please pay or contact us.")),
            Map.entry(
                    "investment.paid_out",
                    new Template(
                            null,
                            "{tenant_name}: Paid {currency} {amount} on investment {account_no} on {date}."
                                    + " Voucher {voucher_no}.")));

    static final String WITH_BALANCE = ".with_balance";

    private static Map<String, Template> byChannel(String channel) {
        return switch (channel) {
            case "email" -> EMAIL;
            case "telegram" -> TELEGRAM;
            case "sms" -> SMS;
            default -> Map.of();
        };
    }

    Rendered render(String channel, String templateKey, Map<String, String> params) {
        Template template = byChannel(channel).get(withBalanceVariant(channel, templateKey, params));
        if (template == null) {
            throw new IllegalArgumentException("no " + channel + " template " + templateKey);
        }
        return new Rendered(
                template.subject() == null ? null : fill(template.subject(), params), fill(template.text(), params));
    }

    /** The SMS variant that carries the balance, when the parameters hold one and the template has it. */
    private static String withBalanceVariant(String channel, String templateKey, Map<String, String> params) {
        return "sms".equals(channel) && params.containsKey("balance") && SMS.containsKey(templateKey + WITH_BALANCE)
                ? templateKey + WITH_BALANCE
                : templateKey;
    }

    /** The default text of a template, for the tenant wording screens (the same text the channel sends when no override exists). */
    static String defaultText(String channel, String templateKey) {
        Template template = byChannel(channel).get(templateKey);
        return template == null ? null : template.text();
    }

    /** The placeholders a template uses, for the render tests. */
    static java.util.Set<String> placeholders(String channel, String templateKey) {
        Template template = byChannel(channel).get(templateKey);
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (String text : new String[] {template.subject(), template.text()}) {
            if (text != null) {
                PLACEHOLDER.matcher(text).results().forEach(r -> names.add(r.group(1)));
            }
        }
        return names;
    }

    /** The template keys of a channel, for the render test. */
    static List<String> keys(String channel) {
        return byChannel(channel).keySet().stream().sorted().toList();
    }

    private static String fill(String text, Map<String, String> params) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = params.get(m.group(1));
            if (value == null) {
                throw new IllegalArgumentException("missing template parameter " + m.group(1));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }
}
