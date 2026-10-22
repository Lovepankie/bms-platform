package com.rincoltech.bms.core.notifications.internal;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

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
 */
@ConfigurationProperties("bms.notifications")
record NotificationProperties(Smtp smtp, Telegram telegram, String supportContact) {

    NotificationProperties {
        smtp = smtp == null ? new Smtp(null, null, null, null, null) : smtp;
        telegram = telegram == null ? new Telegram(null, null) : telegram;
        supportContact = supportContact == null ? "" : supportContact.strip();
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
            // so one
            // value turns every production guard on or none of them.
            if (!(environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT)).equals("production")) {
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
