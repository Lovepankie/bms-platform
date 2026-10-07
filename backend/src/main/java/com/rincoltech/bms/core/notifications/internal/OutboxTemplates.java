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
                    """));

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
                            + " hour passes. Check the edge rule and the portal."));

    /**
     * Member receipts (chapter 11 section 11.3.1). Short enough for one SMS; no name, only the
     * account number. Nothing sends them until an SMS sender exists (pending ADR-013).
     */
    private static final Map<String, Template> SMS = Map.of(
            "savings.deposit",
            new Template(
                    null,
                    "{tenant_name}: Deposit of {currency} {amount} on account {account_no}. Receipt {receipt_no}."
                            + " Balance {currency} {balance}."),
            "savings.withdrawal",
            new Template(
                    null,
                    "{tenant_name}: Withdrawal of {currency} {amount} from account {account_no}. Voucher {receipt_no}."
                            + " Balance {currency} {balance}."));

    private static Map<String, Template> byChannel(String channel) {
        return switch (channel) {
            case "email" -> EMAIL;
            case "telegram" -> TELEGRAM;
            case "sms" -> SMS;
            default -> Map.of();
        };
    }

    Rendered render(String channel, String templateKey, Map<String, String> params) {
        Template template = byChannel(channel).get(templateKey);
        if (template == null) {
            throw new IllegalArgumentException("no " + channel + " template " + templateKey);
        }
        return new Rendered(
                template.subject() == null ? null : fill(template.subject(), params), fill(template.text(), params));
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
