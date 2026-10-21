package com.rincoltech.bms.core.notifications.internal;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The outbox's message texts, by template key and channel. Plain text, plain wording, no
 * commercial figures. A {@code {name}} placeholder takes the parameter of that name; a missing
 * parameter or an unknown key is an error, so a message is never sent half filled. An email is
 * sent with this text as its plain part and an HTML part built from the same template and values
 * ({@link #emailContent}, {@link EmailLayout}; issue #396, ADR-054).
 */
@Component
class OutboxTemplates {

    record Rendered(String subject, String text) {}

    /**
     * @param button the label of the main link's button (email only), or null when the email has no link to follow
     * @param reason why the reader got the email (email only), shown in the footer of both parts
     */
    private record Template(String subject, String text, String button, String reason) {

        Template(String subject, String text) {
            this(subject, text, null, null);
        }
    }

    /** The activation email for a business whose first administrator must turn on two-step sign-in (issue #396). */
    static final String ACTIVATION_TWO_STEP = "onboarding.activation_two_step";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

    // Email templates (issue #396, ADR-054): the text is the plain-text part, and EmailLayout builds the
    // HTML part from the same text and values. A prose paragraph is one line (a trailing backslash joins
    // source lines), so both parts wrap it to the screen; a kept line break is meant. A paragraph that is
    // only the main link becomes the button named by the third value; the fourth says why the reader got
    // the email. Signed "Rincol Tech".
    private static final Map<String, Template> EMAIL_CORE = Map.of(
            // Sent to an address nobody has confirmed yet: it carries no text the applicant typed,
            // only the server's reference and link, so the form cannot be used to send someone
            // else's words from the platform's sender (review B1).
            "onboarding.verify_email",
            new Template(
                    "Confirm your email for BMS application {reference}",
                    """
                    Hello,

                    This email address was used to apply to use BMS (application {reference}). \
                    Open this link to confirm the address and follow the application:

                    {link}

                    The link works for 7 days. If you did not apply, ignore this email: nothing \
                    happens without the confirmation.

                    Rincol Tech
                    """,
                    "Confirm my email",
                    "You got this email because this address was given on an application to use BMS."),
            "onboarding.needs_info",
            new Template(
                    "We need a little more information",
                    """
                    Hello {contact_name},

                    We are looking at the application for {business_name} and need a little more information:

                    {note}

                    Open your application page to answer:

                    {link}

                    Rincol Tech
                    """,
                    "Open my application",
                    "You got this email because you applied to use BMS."),
            "onboarding.rejected",
            new Template(
                    "About your BMS application",
                    """
                    Hello {contact_name},

                    We are sorry: we cannot accept the application for {business_name} at this time.

                    {note}

                    You can see your application here:

                    {link}

                    Rincol Tech
                    """,
                    "See my application",
                    "You got this email because you applied to use BMS."),
            // Two-step sign-in is optional by default (issue #348, V67): the activation asks for it only
            // when the business requires it of its first administrator, which is the variant below.
            "onboarding.activation",
            new Template(
                    "Your BMS account is ready",
                    """
                    Hello {contact_name},

                    {business_name} is ready on BMS. Open this link to set your password. \
                    The link works once, until {expires_at}.

                    {link}

                    After that, sign in at {sign_in_url}

                    Rincol Tech
                    """,
                    "Set my password",
                    "You got this email because {business_name} applied to use BMS and was accepted."),
            ACTIVATION_TWO_STEP,
            new Template(
                    "Your BMS account is ready",
                    """
                    Hello {contact_name},

                    {business_name} is ready on BMS. Open this link to set your password and turn on \
                    two-step sign-in, which your business requires. The link works once, until {expires_at}.

                    {link}

                    After that, sign in at {sign_in_url}

                    Rincol Tech
                    """,
                    "Set my password",
                    "You got this email because {business_name} applied to use BMS and was accepted."),
            "onboarding.operator_alert",
            new Template(
                    "New verified application {reference}",
                    """
                    A new application has confirmed its email and is waiting in the queue.

                    Reference: {reference}
                    Business: {business_name}
                    Modules: {modules}

                    Open the operator portal:

                    {portal_url}
                    """,
                    "Open the operator portal",
                    "You got this email because you are a BMS platform operator."),
            // A change to the business's own payment account (issue #233, ADR-035 amendment): tells the other
            // administrators that where repayments are paid was touched. Carries no key, secret or account detail.
            "payments.account_changed",
            new Template(
                    "The payment account of {business_name} was changed",
                    """
                    Hello,

                    The payment account that receives your members' payments for {business_name} was changed by {who}: \
                    {change}, on {when}.

                    If you or another administrator did this, nothing more is needed. If you do not know about it, open \
                    Receive payments in BMS now, check the account, and change your password.

                    Rincol Tech
                    """,
                    null,
                    "You got this email because you are an administrator of {business_name} on BMS."),
            // A staff user who forgot the password asks for a link on the sign-in page (FR-IAM-15, ADR-042). Plain, no
            // name, no figure; the link is the only secret and lapses with the row.
            "identity.password_reset",
            new Template(
                    "Reset your BMS password for {tenant_name}",
                    """
                    Hello,

                    Someone asked to reset the password for your BMS sign-in at {tenant_name}. If that was you, open \
                    this link and choose a new password. The link works once, until {expires_at}.

                    {link}

                    If you did not ask for this, ignore this email: your password stays as it is. If you keep \
                    getting it, tell your administrator.

                    Rincol Tech
                    """,
                    "Choose a new password",
                    "You got this email because a password reset was asked for your BMS sign-in at {tenant_name}."),
            // Sent to the user when an administrator or the platform operator issues a reset link for them. No link: it
            // is handed over, and nothing has changed until it is used.
            "identity.password_reset_issued",
            new Template(
                    "A password reset was issued for your BMS sign-in",
                    """
                    Hello,

                    {who} issued a password reset for your BMS sign-in at {tenant_name} on {when}. \
                    Nothing has changed yet: your password stays as it is until the reset link, which you are \
                    given in person or by message, is used.

                    If you did not expect this, tell your business's administrators, or Rincol Tech support, at once.

                    Rincol Tech
                    """,
                    null,
                    "You got this email because you have a BMS sign-in at {tenant_name}."));

    // Kept in their own maps: Map.of takes ten pairs at most and the core map is full.
    private static final Map<String, Template> EMAIL_MORE = Map.of(
            // Sent after a reset is used, to the address on file, so the owner of the mailbox hears of it. No link.
            "identity.password_changed",
            new Template(
                    "Your BMS password was changed",
                    """
                    Hello,

                    The password for your BMS sign-in at {tenant_name} was changed on {when}, and every device you were \
                    signed in on was signed out.

                    If this was you, nothing more is needed. If it was not, ask your administrator to reset your \
                    sign-in at once.

                    Rincol Tech
                    """,
                    null,
                    "You got this email because you have a BMS sign-in at {tenant_name}."),
            // The insights digest (ADR-030): the text is built by lending.insights as plain text from the tenant's own
            // figures, ready for a WhatsApp relay later.
            "insights.daily_digest",
            new Template(
                    "{business_name}: daily brief for {date}",
                    "{text}\n",
                    null,
                    "You got this email because {business_name} sends its daily brief to this address."));

    // Retail (issue #340, ADR-043).
    private static final Map<String, Template> EMAIL_RETAIL = Map.of(
            // A debt of a credit sale was written off without a second administrator (issue #340, ADR-043): the
            // administrators who count are told. No figure, no customer name.
            "retail.write_off_alone",
            new Template(
                    "A debt was written off at {business_name}",
                    """
                    Hello,

                    A debt on a credit sale was written off at {business_name} by one administrator, without a second \
                    administrator approving it.

                    If you or another administrator did this, nothing more is needed. If you do not know about it, open \
                    Credit sales in BMS, check the sale, and change your password.

                    Rincol Tech
                    """,
                    null,
                    "You got this email because you are an administrator of {business_name} on BMS."));

    // Sent to the administrators when two-step sign-in is turned off or narrowed (issue #348, ADR-046): what changed,
    // who, when.
    private static final Map<String, Template> EMAIL_POLICY = Map.of(
            "identity.two_step_policy_weakened",
            new Template(
                    "Two-step sign-in was relaxed for {business_name}",
                    """
                    Hello,

                    {who} changed the two-step sign-in rule of {business_name} on {when}: {change}.

                    If you or another administrator did this, nothing more is needed. If you do not know about it, \
                    open Business set-up in BMS now, check the rule, and change your password.

                    Rincol Tech
                    """,
                    null,
                    "You got this email because you are an administrator of {business_name} on BMS."));

    // Find your sign-in page (issue #383). Kept in its own map for the same reason as the retail one.
    private static final Map<String, Template> EMAIL_FIND_SIGN_IN = Map.of(
            // Staff who could not find their business's sign-in page ask for it on the platform home page (issue #383).
            // Sent only to the address typed, and only when it is an active staff user's; the links are the
            // businesses' public sign-in addresses, which carry no secret.
            // No button: there can be several pages, each its own link; only our own hosts become links (ADR-054).
            "identity.find_sign_in",
            new Template(
                    "Your BMS sign-in page",
                    """
                    Hello,

                    Someone asked for the BMS sign-in page for this email address. Open the page of your business \
                    and sign in there:

                    {links}

                    If you forgot your password, tap Forgot password on that page.

                    If you did not ask for this, ignore this email: nothing has changed.

                    Rincol Tech
                    """,
                    null,
                    "You got this email because someone asked for the BMS sign-in page of this address."));

    private static final Map<String, Template> EMAIL = emailTemplates();

    private static Map<String, Template> emailTemplates() {
        Map<String, Template> all = new java.util.HashMap<>(EMAIL_CORE);
        all.putAll(EMAIL_MORE);
        all.putAll(EMAIL_RETAIL);
        all.putAll(EMAIL_POLICY);
        all.putAll(EMAIL_FIND_SIGN_IN);
        return Map.copyOf(all);
    }

    // Web Push (ADR-050): the title is the template's subject and the body its text. A notification is shown on a lock
    // screen, so
    // no text names a person or carries a phone number, an identity number or an amount: it says what happened and
    // where to
    // look. {path} is not a placeholder here: the channel reads it from the row's parameters as the page a tap opens.
    private static final Map<String, Template> PUSH = Map.of(
            "retail.sale_voided",
            new Template("A sale was voided", "A sale at {branch_name} was voided. Open it to see the reason."),
            "retail.write_off_alone",
            new Template(
                    "A debt was written off",
                    "One administrator wrote off a debt at {business_name} without a second approval. Check the sale."),
            "core.approval_policy_weakened",
            new Template(
                    "An approval limit was raised",
                    "The amount that can be done without a second person was raised for {action_label}. If you did not expect"
                            + " this, check Business set-up."),
            "core.signin_policy_weakened",
            new Template(
                    "Two-step sign-in is no longer required",
                    "Two-step sign-in is no longer required of every user. If you did not expect this, check Business set-up."),
            "retail.transfer_dispatched",
            new Template(
                    "Stock is on its way to you",
                    "Stock was sent from {from_branch_name} to {to_branch_name}. Receive it when it arrives."),
            "core.approval_waiting",
            new Template(
                    "An approval is waiting for you",
                    "{action_label} is waiting for your decision. Open Approvals to see it."));

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
            case "push" -> PUSH;
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

    /**
     * The HTML content of an email template, from the same text and values as {@link #render} (issue #396, ADR-054): the
     * subject is the title, the paragraph that is only a trusted main link is the button, only trusted URLs become
     * anchors, and every value is escaped.
     */
    EmailLayout.Content emailContent(
            String templateKey, Map<String, String> params, java.util.function.Predicate<String> trusted) {
        Template template = EMAIL.get(templateKey);
        if (template == null) {
            throw new IllegalArgumentException("no email template " + templateKey);
        }
        return EmailLayout.content(
                fill(template.subject(), params),
                template.text(),
                params,
                template.button(),
                template.reason(),
                trusted);
    }

    /** Why the reader got an email, as plain text for the text part's footer; null when the template says nothing. */
    String emailReason(String templateKey, Map<String, String> params) {
        Template template = EMAIL.get(templateKey);
        return template == null || template.reason() == null ? null : fill(template.reason(), params);
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
        for (String text : new String[] {template.subject(), template.text(), template.reason()}) {
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
