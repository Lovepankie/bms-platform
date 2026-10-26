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
            // Sent to the Owner's old address when the Owner's email or phone changes (ADR-062). No link.
            "identity.contact_changed",
            new Template(
                    "Your BMS email or phone was changed",
                    """
                    Hello,

                    The email or phone of your BMS sign-in at {tenant_name} was changed on {when}. You are the owner of \
                    this business, so ownership cannot be handed over for 24 hours after this change.

                    If this was you, nothing more is needed. If it was not, contact Rincol Tech support at once: the \
                    platform operator can help you get your business back.

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

    // In-app feedback (issue #447, ADR-065): one email per active platform operator for each item a business sends. The
    // message is the sender's own words, shown as text (every value is escaped in the HTML part); the link is the
    // operator's Feedback page on the platform host.
    private static final Map<String, Template> EMAIL_FEEDBACK = Map.of(
            "feedback.new",
            new Template(
                    "New feedback from {business_name}: {kind}",
                    """
                    {sender_name} at {business_name} sent feedback ({kind}) from the page {page}:

                    {message}

                    Open the Feedback page of the operator portal to mark it seen or done and to reply:

                    {portal_url}
                    """,
                    "Open the Feedback page",
                    "You got this email because you are a BMS platform operator."));

    // The weekly copy of the business's data (issue #334, ADR-074): to the Owner, with the workbook attached, or with a
    // link to Business set-up when the file is too large to attach. The attached copy leaves out contact details and ID
    // numbers (decision 6), and the email says so; the full copy is made and downloaded by the signed-in Owner from the
    // business's own sign-in page, never through a link that works without signing in.
    private static final Map<String, Template> EMAIL_DATA_EXPORT = Map.of(
            "core.data_export_weekly",
            new Template(
                    "Your weekly copy of {business_name}'s data",
                    """
                    Hello {owner_name},

                    This week's copy of the data of {business_name}, as it stood on {as_of}, is attached as an Excel \
                    workbook, with a sheet for each table.

                    Because email can be forwarded and read on any phone, this copy leaves out phone numbers, \
                    addresses, dates of birth and ID numbers: contact details and ID numbers are only in the copy you \
                    download after signing in. To get that copy, sign in and press Download my data in Business set-up:

                    {link}

                    To stop these emails, untick the weekly email under Download my data in Business set-up.

                    Rincol Tech
                    """,
                    "Open Business set-up",
                    "You got this email because you are the Owner of {business_name} on BMS."),
            "core.data_export_weekly_link",
            new Template(
                    "Your weekly copy of {business_name}'s data is too large to email",
                    """
                    Hello {owner_name},

                    This week's copy of the data of {business_name}, as it stood on {as_of}, is too large to attach \
                    to an email. Sign in and press Download my data in Business set-up to make your copy and save it:

                    {link}

                    That copy is complete: contact details and ID numbers are only in the copy you download after \
                    signing in.

                    To stop these emails, untick the weekly email under Download my data in Business set-up.

                    Rincol Tech
                    """,
                    "Open Business set-up",
                    "You got this email because you are the Owner of {business_name} on BMS."),
            "core.data_export_weekly_failed",
            new Template(
                    "Your weekly copy of {business_name}'s data could not be made",
                    """
                    Hello {owner_name},

                    Your weekly copy of the data of {business_name} could not be made this week. Nothing in the \
                    business changed. You can download a copy now from Business set-up, under Download my data:

                    {link}

                    To stop these emails, untick the weekly email under Download my data in Business set-up.

                    Rincol Tech
                    """,
                    "Open Business set-up",
                    "You got this email because you are the Owner of {business_name} on BMS."));

    // The Owner's own plan (issue #472, ADR-073): the Owner is told the operator's decision on a module they asked for,
    // and
    // every active platform operator is told of a request, a module stopped, a cancellation or an undo. No price or
    // figure.
    private static final Map<String, Template> EMAIL_PLAN = Map.of(
            "platform.plan_request_decided",
            new Template(
                    "{business_name}: your request for {module} was answered",
                    """
                    Hello,

                    Your request to add {module} to {business_name} was {decision}.

                    The Plan and modules card in Business set-up shows it:

                    {link}

                    Rincol Tech
                    """,
                    "Open Business set-up",
                    "You got this email because you are the owner of this business in BMS."),
            "platform.plan_change_for_operator",
            new Template(
                    "{business_name}: a change to the plan",
                    """
                    The owner of {business_name} {change}.

                    The module requests and the changes still to come are in the operator portal:

                    {portal_url}
                    """,
                    "Open the operator portal",
                    "You got this email because you are a BMS platform operator."));

    // Closing the business (issue #571, ADR-090): the Owner is told the request was made, with the day the platform
    // operator may
    // remove the business, and told again when the request is cancelled.
    private static final Map<String, Template> EMAIL_CLOSURE = Map.of(
            "platform.closure_requested",
            new Template(
                    "{business_name}: your request to close the business",
                    """
                    Hello {owner_name},

                    You asked to close {business_name} on BMS. From now on it is read only: everyone can still sign in, \
                    look at everything and download the data, but nobody can add or change anything.

                    A full copy of your data is being prepared. Save it from Download my data in Business set-up:

                    {link}

                    Rincol Tech may remove the business from {remove_after}. Removal deletes the names, phone numbers, \
                    ID numbers and other personal details. The money records the law requires are kept without them \
                    for a time, then deleted. Until the removal you can cancel the closing in Business set-up, and \
                    the business works again as before.

                    If you did not ask for this, sign in and cancel it now, and call Rincol Tech.

                    Rincol Tech
                    """,
                    "Open Business set-up",
                    "You got this email because you are the owner of {business_name} on BMS."),
            "platform.closure_cancelled",
            new Template(
                    "{business_name}: the closing was cancelled",
                    """
                    Hello {owner_name},

                    The request to close {business_name} was cancelled. The business works again as before and \
                    nothing will be removed.

                    {link}

                    Rincol Tech
                    """,
                    "Open Business set-up",
                    "You got this email because you are the owner of {business_name} on BMS."));

    // Subscription billing (issue #480, ADR-075): the reminders to the Owner of a business before the last day paid for
    // or
    // the last day of the free month, and the receipt of a payment the operator recorded (the PDF is attached when the
    // email
    // is sent, EmailAttachments). Plain words; the amount is the plan's price from the price book; how to pay is the
    // operator's own text.
    private static final Map<String, Template> EMAIL_BILLING = Map.of(
            "billing.due_in_7",
            new Template(
                    "Your BMS subscription is due on {due_date}",
                    """
                    Hello {owner_name},

                    The BMS subscription of {business_name} is paid until {due_date}, 7 days from today. Pay before then to keep using BMS without a break. {amount_line}

                    How to pay:

                    {how_to_pay}

                    Your plan, what is paid and your receipts are on the Plan and billing page:

                    {link}

                    Rincol Tech
                    """,
                    "Open Plan and billing",
                    "You got this email because you receive the bills of {business_name} on BMS."),
            "billing.due_in_1",
            new Template(
                    "Your BMS subscription is due tomorrow",
                    """
                    Hello {owner_name},

                    The BMS subscription of {business_name} is paid until tomorrow, {due_date}. Pay before then to keep using BMS without a break. {amount_line}

                    How to pay:

                    {how_to_pay}

                    Your plan, what is paid and your receipts are on the Plan and billing page:

                    {link}

                    Rincol Tech
                    """,
                    "Open Plan and billing",
                    "You got this email because you receive the bills of {business_name} on BMS."),
            "billing.due_today",
            new Template(
                    "Your BMS subscription is due today",
                    """
                    Hello {owner_name},

                    The BMS subscription of {business_name} is paid until today, {due_date}. Pay today to keep using BMS without a break. {amount_line}

                    How to pay:

                    {how_to_pay}

                    Your plan, what is paid and your receipts are on the Plan and billing page:

                    {link}

                    Rincol Tech
                    """,
                    "Open Plan and billing",
                    "You got this email because you receive the bills of {business_name} on BMS."),
            "billing.trial_in_7",
            new Template(
                    "Your free month of BMS ends in 7 days",
                    """
                    Hello {owner_name},

                    The free month of {business_name} on BMS ends on {due_date}, 7 days from today. To keep using BMS after that, pay for your plan. {amount_line}

                    How to pay:

                    {how_to_pay}

                    Your plan, what is paid and your receipts are on the Plan and billing page:

                    {link}

                    Rincol Tech
                    """,
                    "Open Plan and billing",
                    "You got this email because you receive the bills of {business_name} on BMS."),
            "billing.trial_in_1",
            new Template(
                    "Your free month of BMS ends tomorrow",
                    """
                    Hello {owner_name},

                    The free month of {business_name} on BMS ends tomorrow, {due_date}. To keep using BMS after that, pay for your plan. {amount_line}

                    How to pay:

                    {how_to_pay}

                    Your plan, what is paid and your receipts are on the Plan and billing page:

                    {link}

                    Rincol Tech
                    """,
                    "Open Plan and billing",
                    "You got this email because you receive the bills of {business_name} on BMS."),
            "billing.trial_today",
            new Template(
                    "Your free month of BMS ends today",
                    """
                    Hello {owner_name},

                    The free month of {business_name} on BMS ends today, {due_date}. To keep using BMS after today, pay for your plan. {amount_line}

                    How to pay:

                    {how_to_pay}

                    Your plan, what is paid and your receipts are on the Plan and billing page:

                    {link}

                    Rincol Tech
                    """,
                    "Open Plan and billing",
                    "You got this email because you receive the bills of {business_name} on BMS."),
            "billing.receipt",
            new Template(
                    "Receipt {receipt_no} for your BMS subscription",
                    """
                    Hello {owner_name},

                    Thank you. We received {amount} for the BMS subscription of {business_name}, for {period}. Your \
                    receipt {receipt_no} is attached.

                    Your receipts are also on the Plan and billing page:

                    {link}

                    Rincol Tech
                    """,
                    "Open Plan and billing",
                    "You got this email because you receive the bills of {business_name} on BMS."));

    private static final Map<String, Template> EMAIL = emailTemplates();

    private static Map<String, Template> emailTemplates() {
        Map<String, Template> all = new java.util.HashMap<>(EMAIL_CORE);
        all.putAll(EMAIL_MORE);
        all.putAll(EMAIL_RETAIL);
        all.putAll(EMAIL_POLICY);
        all.putAll(EMAIL_FIND_SIGN_IN);
        all.putAll(EMAIL_FEEDBACK);
        all.putAll(EMAIL_DATA_EXPORT);
        all.putAll(EMAIL_PLAN);
        all.putAll(EMAIL_BILLING);
        all.putAll(EMAIL_CLOSURE);
        return Map.copyOf(all);
    }

    // Web Push (ADR-050): the title is the template's subject and the body its text. A notification is shown on a lock
    // screen, so
    // no text names a person or carries a phone number, an identity number or an amount: it says what happened and
    // where to
    // look. {path} is not a placeholder here: the channel reads it from the row's parameters as the page a tap opens.
    private static final Map<String, Template> PUSH = Map.ofEntries(
            Map.entry(
                    "retail.sale_voided",
                    new Template(
                            "A sale was voided", "A sale at {branch_name} was voided. Open it to see the reason.")),
            // ADR-067: a sale made offline was recorded with something that changed meanwhile. No item, no amount.
            Map.entry(
                    "retail.sale_clash",
                    new Template(
                            "An offline sale needs a look",
                            "A sale made offline at {branch_name} was recorded with a stock or price clash. Open it to"
                                    + " resolve it.")),
            Map.entry(
                    "retail.write_off_alone",
                    new Template(
                            "A debt was written off",
                            "One administrator wrote off a debt at {business_name} without a second approval. Check the"
                                    + " sale.")),
            Map.entry(
                    "core.approval_policy_weakened",
                    new Template(
                            "An approval rule was loosened",
                            "Fewer of these now need a second person: {action_label}. If you did not expect this, check"
                                    + " Approval rules in Business set-up.")),
            Map.entry(
                    "core.signin_policy_weakened",
                    new Template(
                            "Two-step sign-in is no longer required",
                            "Two-step sign-in is no longer required of every user. If you did not expect this, check"
                                    + " Business set-up.")),
            Map.entry(
                    "retail.transfer_dispatched",
                    new Template(
                            "Stock is on its way to you",
                            "Stock was sent from {from_branch_name} to {to_branch_name}. Receive it when it arrives.")),
            Map.entry(
                    "core.approval_waiting",
                    new Template(
                            "An approval is waiting for you",
                            "{action_label} is waiting for your decision. Open Approvals to see it.")),
            // Issue #412, ADR-057: the everyday notices. Same lock-screen rule: no customer's name or phone, no amount.
            Map.entry(
                    "retail.order_new",
                    new Template(
                            "New order at {branch_name}",
                            "A new order came in by {source_label} for {branch_name}. Open it to pack it.")),
            Map.entry(
                    "retail.order_out_for_delivery",
                    new Template(
                            "Your order is out for delivery",
                            "An order you took at {branch_name} is out for delivery. Open it to follow it.")),
            Map.entry(
                    "retail.order_delivered",
                    new Template(
                            "Your order was delivered",
                            "An order you took at {branch_name} was delivered and is now a sale.")),
            // A loan the nightly arrears run could not update two nights running (#270 review): a safeguard, no switch.
            Map.entry(
                    "lending.arrears_loan_failing",
                    new Template(
                            "A loan was not updated overnight",
                            "Loan {loan_no} failed the nightly arrears run two nights in a row. Its arrears may be out of"
                                    + " date: tell support.")),
            Map.entry(
                    "retail.low_stock",
                    new Template("Stock is running low", "{item_name} at {branch_name} is {reason}. Buy more soon.")),
            Map.entry(
                    "retail.credit_due",
                    new Template(
                            "Credit payments to collect", "{credit_words}. Open Credit control to follow them up.")),
            Map.entry(
                    "retail.credit_due_branch",
                    new Template(
                            "Credit payments to collect",
                            "At {branch_name}, {credit_words}. Open Credit control to follow them up.")),
            Map.entry(
                    "retail.daily_summary",
                    new Template("Today's sales, {day}", "{shops}. Open the day's sales to see the totals.")),
            // The summary of a day whose closing time passed while the server was down, sent the next morning.
            Map.entry(
                    "retail.daily_summary_late",
                    new Template("Yesterday's sales, {day}", "{shops}. Open the sales to see the totals.")));

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
            // To the Owner's old phone when an Owner with no email changes their email or phone (ADR-062). No link.
            Map.entry(
                    "identity.contact_changed",
                    new Template(
                            null,
                            "{tenant_name}: the phone or email of the BMS owner sign-in was changed. Not you? Call Rincol Tech"
                                    + " support at once.")),
            // Subscription reminders to the Owner of a business (issue #480, ADR-075), only when the platform's billing
            // SMS is
            // switched on: no amount and no link, the email carries them.
            Map.entry(
                    "billing.due_in_7",
                    new Template(
                            null,
                            "{tenant_name}: your BMS subscription is paid until {due_date}. Pay before then to keep using"
                                    + " BMS. Rincol Tech")),
            Map.entry(
                    "billing.due_in_1",
                    new Template(
                            null,
                            "{tenant_name}: your BMS subscription is paid until tomorrow, {due_date}. Pay to keep using"
                                    + " BMS. Rincol Tech")),
            Map.entry(
                    "billing.due_today",
                    new Template(
                            null,
                            "{tenant_name}: your BMS subscription is paid until today, {due_date}. Pay today to keep"
                                    + " using BMS. Rincol Tech")),
            Map.entry(
                    "billing.trial_in_7",
                    new Template(
                            null,
                            "{tenant_name}: your free month of BMS ends on {due_date}. Pay for your plan to keep using"
                                    + " BMS. Rincol Tech")),
            Map.entry(
                    "billing.trial_in_1",
                    new Template(
                            null,
                            "{tenant_name}: your free month of BMS ends tomorrow, {due_date}. Pay for your plan to keep"
                                    + " using BMS. Rincol Tech")),
            Map.entry(
                    "billing.trial_today",
                    new Template(
                            null,
                            "{tenant_name}: your free month of BMS ends today, {due_date}. Pay for your plan to keep using"
                                    + " BMS. Rincol Tech")),
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
                    new Template(
                            null, "{tenant_name}: {currency} {amount} for loan {loan_no} is due today, {due_date}.")),
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
