package com.rincoltech.bms.core.notifications.internal;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Outbox senders (spec section 11, chapter 9). Every value comes from the environment and none
 * has a default that sends anything: a sender whose values are unset is switched off, its rows
 * stay {@code pending}, and nothing fails. Values are never logged.
 *
 * @param smtp {@code BMS_SMTP_HOST}, {@code BMS_SMTP_PORT} (default 465, implicit TLS),
 *     {@code BMS_SMTP_USER}, {@code BMS_SMTP_PASSWORD}, {@code BMS_MAIL_FROM} (an address, or a display name and an
 *     address such as {@code BMS by Rincol Tech <no-reply@example.test>})
 * @param telegram {@code BMS_TELEGRAM_BOT_TOKEN}, {@code BMS_TELEGRAM_OPERATOR_CHAT_ID}
 * @param supportContact {@code BMS_SUPPORT_CONTACT}: how a reader reaches support, shown in the footer of every email
 *     (issue #396), for example an address and a phone number; empty by default, and then the line is left out
 * @param mail which sender carries email and who may get it on staging (issue #333, ADR-117)
 */
@ConfigurationProperties("bms.notifications")
record NotificationProperties(Smtp smtp, Telegram telegram, String supportContact, Mail mail) {

    // JUSTIFICATION-A3: the provider choice, Resend key and staging allow-list are new settings (#333).
    @ConstructorBinding
    NotificationProperties {
        smtp = smtp == null ? new Smtp(null, null, null, null, null) : smtp;
        telegram = telegram == null ? new Telegram(null, null) : telegram;
        supportContact = supportContact == null ? "" : supportContact.strip();
        mail = mail == null ? new Mail(null, null, null, null) : mail;
    }

    /** SMTP as the email sender, as before issue #333. */
    NotificationProperties(Smtp smtp, Telegram telegram, String supportContact) {
        this(smtp, telegram, supportContact, null);
    }

    /**
     * The production startup guard of the chosen sender (ADR-059, ADR-117): SMTP's own, or for Resend the API key and
     * {@code BMS_MAIL_FROM}. Names the unset variables, never a value.
     */
    void requireEmailInProduction(String environment) {
        if (!mail.resend()) {
            smtp.requireInProduction(environment);
            return;
        }
        if (!isProduction(environment)) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (!present(mail.resendApiKey())) {
            missing.add("BMS_RESEND_API_KEY");
        }
        if (!present(smtp.from())) {
            missing.add("BMS_MAIL_FROM");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "BMS_ENVIRONMENT=production needs email: set " + String.join(", ", missing));
        }
        if (!mail.resendUrl().startsWith("https://")) {
            // The loopback exception is for tests only.
            throw new IllegalStateException("BMS_RESEND_API_URL must be an https address (BMS_ENVIRONMENT=production)");
        }
        smtp.requireValidFrom();
    }

    /**
     * @param provider {@code BMS_MAIL_PROVIDER}: {@code smtp} (the default when unset) or {@code resend}; any other value
     *     stops the start, so a typo never leaves email quietly off
     * @param resendApiKey {@code BMS_RESEND_API_KEY}: a send-only key, put on the host by the operator, never in git, never
     *     logged
     * @param resendUrl {@code BMS_RESEND_API_URL}: the API base, {@code https://api.resend.com} unless a test points elsewhere
     * @param allowlist {@code BMS_MAIL_ALLOWLIST}: on staging the only recipients, comma separated, each an address or
     *     {@code @domain}; letter case is ignored; empty on staging means nothing is sent. Not read elsewhere.
     */
    record Mail(String provider, String resendApiKey, String resendUrl, String allowlist) {

        Mail {
            provider = provider == null || provider.isBlank()
                    ? "smtp"
                    : provider.strip().toLowerCase(Locale.ROOT);
            if (!provider.equals("smtp") && !provider.equals("resend")) {
                throw new IllegalStateException("BMS_MAIL_PROVIDER must be smtp or resend");
            }
            resendUrl = resendUrl == null || resendUrl.isBlank() ? "https://api.resend.com" : resendUrl.strip();
            // The key travels in a header: never over plain http, except to this machine (a test's stand-in).
            if (!resendUrl.startsWith("https://")
                    && !resendUrl.matches("^http://(127\\.0\\.0\\.1|localhost)(:\\d+)?(/.*)?$")) {
                throw new IllegalStateException("BMS_RESEND_API_URL must be an https address");
            }
            allowlist = allowlist == null ? "" : allowlist;
        }

        boolean resend() {
            return provider.equals("resend");
        }

        boolean resendConfigured(Smtp smtp) {
            return resend() && present(resendApiKey) && present(smtp.from());
        }

        /** True when the list names at least one entry. */
        boolean hasAllowlist() {
            return !allowlist.isBlank() && Arrays.stream(allowlist.split(",")).anyMatch(e -> !e.isBlank());
        }

        /** True when {@code address} is on the allow-list: the address itself or {@code @} and its domain. */
        boolean allows(String address) {
            String to = address == null ? "" : address.strip().toLowerCase(Locale.ROOT);
            int at = to.lastIndexOf('@');
            return Arrays.stream(allowlist.split(","))
                    .map(e -> e.strip().toLowerCase(Locale.ROOT))
                    .anyMatch(e -> !e.isEmpty() && (e.equals(to) || (at > 0 && e.equals(to.substring(at)))));
        }

        @Override
        public String toString() {
            return "Mail[provider=" + provider + ", resendKey=" + present(resendApiKey) + "]";
        }
    }

    static boolean isProduction(String environment) {
        return (environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT)).equals("production");
    }

    static boolean isStaging(String environment) {
        return (environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT)).equals("staging");
    }

    record Smtp(String host, Integer port, String user, String password, String from) {

        Smtp {
            port = port == null ? 465 : port;
        }

        boolean configured() {
            return present(host) && present(user) && present(password) && present(from);
        }

        /**
         * The production startup guard (launch readiness M10, ADR-059, issue #426): password resets, invitations and
         * activation links are email only, so production refuses to start with email off instead of letting them wait
         * unseen in the outbox. Names the unset variables, never a value. Elsewhere unset SMTP stays a quiet off.
         */
        void requireInProduction(String environment) {
            // The same reading of BMS_ENVIRONMENT as SmsSettings and PesapalSettings: trimmed, lower case, then exact,
            // so one value turns every production guard on or none of them.
            if (!isProduction(environment)) {
                return;
            }
            List<String> missing = new ArrayList<>();
            if (!present(host)) {
                missing.add("BMS_SMTP_HOST");
            }
            if (!present(user)) {
                missing.add("BMS_SMTP_USER");
            }
            if (!present(password)) {
                missing.add("BMS_SMTP_PASSWORD");
            }
            if (!present(from)) {
                missing.add("BMS_MAIL_FROM");
            }
            if (!missing.isEmpty()) {
                throw new IllegalStateException(
                        "BMS_ENVIRONMENT=production needs email: set " + String.join(", ", missing));
            }
            requireValidFrom();
        }

        void requireValidFrom() {
            try {
                new InternetAddress(from, true);
            } catch (AddressException e) {
                throw new IllegalStateException("BMS_MAIL_FROM is not an email address (BMS_ENVIRONMENT=production)");
            }
        }

        @Override
        public String toString() {
            return "Smtp[configured=" + configured() + "]";
        }
    }

    record Telegram(String botToken, String operatorChatId) {

        boolean configured() {
            return present(botToken) && present(operatorChatId);
        }

        @Override
        public String toString() {
            return "Telegram[configured=" + configured() + "]";
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
