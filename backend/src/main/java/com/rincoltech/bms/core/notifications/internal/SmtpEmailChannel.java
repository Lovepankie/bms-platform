package com.rincoltech.bms.core.notifications.internal;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * Email over SMTP with implicit TLS (spec section 11; port 465 by default, because port 587 is
 * blocked on the staging host). Server certificate and host name are checked. The Message-ID is
 * derived from the outbox idempotency key, so a resend after a crash carries the same id and a
 * receiving server can drop the copy.
 */
@Component
class SmtpEmailChannel implements OutboxChannel {

    private final NotificationProperties.Smtp smtp;

    SmtpEmailChannel(NotificationProperties properties) {
        this.smtp = properties.smtp();
    }

    @Override
    public String channel() {
        return "email";
    }

    @Override
    public boolean enabled() {
        return smtp.configured();
    }

    @Override
    public void send(Delivery delivery) throws Exception {
        Properties props = new Properties();
        props.put("mail.transport.protocol", "smtps");
        props.put("mail.smtps.host", smtp.host());
        props.put("mail.smtps.port", String.valueOf(smtp.port()));
        props.put("mail.smtps.auth", "true");
        props.put("mail.smtps.ssl.enable", "true");
        props.put("mail.smtps.ssl.checkserveridentity", "true");
        props.put("mail.smtps.connectiontimeout", "10000");
        props.put("mail.smtps.timeout", "20000");
        props.put("mail.smtps.writetimeout", "20000");
        Session session = Session.getInstance(props);
        MimeMessage message = new IdMessage(session, messageId(delivery.idempotencyKey()));
        message.setFrom(new InternetAddress(smtp.from(), true));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(delivery.recipient(), true));
        message.setSubject(delivery.subject(), StandardCharsets.UTF_8.name());
        message.setText(delivery.text(), StandardCharsets.UTF_8.name());
        try (Transport transport = session.getTransport("smtps")) {
            transport.connect(smtp.host(), smtp.port(), smtp.user(), smtp.password());
            message.saveChanges();
            transport.sendMessage(message, message.getAllRecipients());
        }
    }

    /** {@code <key in base64url>@bms.outbox>}: stable per outbox row. */
    static String messageId(String idempotencyKey) {
        return "<"
                + Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(idempotencyKey.getBytes(StandardCharsets.UTF_8))
                + "@bms.outbox>";
    }

    /** Keeps the Message-ID set here; Jakarta Mail otherwise replaces it on saveChanges. */
    private static final class IdMessage extends MimeMessage {

        private final String id;

        IdMessage(Session session, String id) {
            super(session);
            this.id = id;
        }

        @Override
        protected void updateMessageID() throws jakarta.mail.MessagingException {
            setHeader("Message-ID", id);
        }
    }
}
