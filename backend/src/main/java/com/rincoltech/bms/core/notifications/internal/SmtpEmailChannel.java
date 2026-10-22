package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.jobs.TenantJobs;
import com.rincoltech.bms.core.notifications.EmailAttachments;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.Letterheads;
import com.rincoltech.bms.core.tenancy.PlatformHost;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Email over SMTP with implicit TLS (spec section 11; port 465 by default, because port 587 is
 * blocked on the staging host). Server certificate and host name are checked. The Message-ID is
 * derived from the outbox idempotency key, so a resend after a crash carries the same id and a
 * receiving server can drop the copy.
 *
 * <p>Every email is {@code multipart/alternative} (issue #396, ADR-054): the template's plain text first, then the HTML
 * part that {@link EmailLayout} builds from the same template and values. The header is the Rincol Tech logo from the
 * platform host; a row of a tenant whose logo is set (the emails of its own staff) shows that logo, from the tenant's
 * public logo route, with "Powered by BMS from Rincol Tech" in the footer. A brand that cannot be read never stops a
 * send: the email then carries the platform's look.
 *
 * <p>A row whose parameters name a file ({@link EmailAttachments#PARAM}, the weekly copy of a business's workbook, ADR-074)
 * is sent as {@code multipart/mixed}: the two parts above, then the file, read when the email is sent by the module that owns
 * it. A file that is gone fails the send cleanly ({@code attachment_unavailable}); the email never goes without it.
 */
@Component
class SmtpEmailChannel implements OutboxChannel {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailChannel.class);

    /** The tenant logo is drawn inside this box, keeping its shape. */
    static final int LOGO_MAX_WIDTH = 200;

    static final int LOGO_MAX_HEIGHT = 64;

    private final NotificationProperties.Smtp smtp;
    private final String supportContact;
    private final OutboxTemplates templates;
    private final PlatformHost hosts;
    private final TenantJobs tenantJobs;
    private final CurrentTenant tenant;
    private final Letterheads letterheads;
    private final ObjectProvider<EmailAttachments> attachments;

    SmtpEmailChannel(
            NotificationProperties properties,
            OutboxTemplates templates,
            PlatformHost hosts,
            TenantJobs tenantJobs,
            CurrentTenant tenant,
            Letterheads letterheads,
            ObjectProvider<EmailAttachments> attachments,
            @Value("${bms.environment:}") String environment) {
        properties.smtp().requireInProduction(environment);
        this.smtp = properties.smtp();
        this.supportContact = properties.supportContact();
        this.templates = templates;
        this.hosts = hosts;
        this.tenantJobs = tenantJobs;
        this.tenant = tenant;
        this.letterheads = letterheads;
        this.attachments = attachments;
        this.trusted = trustedLinks(hosts);
    }

    private final Predicate<String> trusted;

    /**
     * The links an email may carry as anchors or its button: the platform's link scheme ({@code https} on staging and
     * production) on the platform host or a tenant host under the pattern. Anything else is printed as text.
     */
    static Predicate<String> trustedLinks(PlatformHost hosts) {
        String origin = hosts.platformOrigin();
        String scheme = origin.substring(0, origin.indexOf(':'));
        return EmailLayout.trustedLinks(scheme, host -> hosts.servesPlatform(host) || hosts.servesTenant(host));
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
    public Result send(Delivery delivery) throws Exception {
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
        Body body = body(delivery, brand(delivery.context()));
        MimeMessage message = compose(session, smtp.from(), delivery, body, attachment(delivery.context()));
        try (Transport transport = session.getTransport("smtps")) {
            transport.connect(smtp.host(), smtp.port(), smtp.user(), smtp.password());
            message.saveChanges();
            transport.sendMessage(message, message.getAllRecipients());
        }
        return Result.SENT;
    }

    /** The two parts of one email: the plain text and the HTML, from the same template and values. */
    record Body(String text, String html) {}

    /**
     * Both parts. A row with a template gets the layout of its template; a delivery without one (none is sent today)
     * gets its text as escaped paragraphs, so no email ever goes out as plain text only.
     */
    Body body(Delivery delivery, EmailLayout.Brand brand) {
        return body(templates, delivery, brand, supportContact, trusted);
    }

    static Body body(
            OutboxTemplates templates,
            Delivery delivery,
            EmailLayout.Brand brand,
            String support,
            Predicate<String> trusted) {
        RowContext context = delivery.context() == null ? RowContext.NONE : delivery.context();
        String key = context.templateKey();
        EmailLayout.Content content;
        String reason;
        if (key != null && OutboxTemplates.keys("email").contains(key)) {
            content = templates.emailContent(key, context.params(), trusted);
            reason = templates.emailReason(key, context.params());
        } else {
            content = EmailLayout.fromText(delivery.subject(), delivery.text());
            reason = null;
        }
        String text = delivery.text().stripTrailing() + "\n" + EmailLayout.textFooter(brand, reason, support);
        return new Body(text, EmailLayout.html(brand, content, support));
    }

    /** The file a row names, read now for the row's tenant; null when it names none. */
    EmailAttachments.Attachment attachment(RowContext context) throws SendFailure {
        String ref = context == null || context.params() == null
                ? null
                : context.params().get(EmailAttachments.PARAM);
        if (ref == null) {
            return null;
        }
        int colon = ref.indexOf(':');
        String kind = colon < 0 ? ref : ref.substring(0, colon);
        String id = colon < 0 ? "" : ref.substring(colon + 1);
        Optional<EmailAttachments.Attachment> file = attachments
                .orderedStream()
                .filter(a -> a.kind().equals(kind))
                .findFirst()
                .flatMap(a -> context.tenantId() == null ? Optional.empty() : a.read(context.tenantId(), id));
        if (file.isEmpty()) {
            throw new SendFailure("attachment_unavailable");
        }
        return file.get();
    }

    /** The message, ready to send: {@code multipart/alternative} with the plain part first, as the standard asks. */
    static MimeMessage compose(Session session, String from, Delivery delivery, Body body) throws MessagingException {
        return compose(session, from, delivery, body, null);
    }

    /** As {@link #compose(Session, String, Delivery, Body)}, inside {@code multipart/mixed} with the file after it. */
    static MimeMessage compose(
            Session session, String from, Delivery delivery, Body body, EmailAttachments.Attachment attachment)
            throws MessagingException {
        MimeMessage message = new IdMessage(session, messageId(delivery.idempotencyKey()));
        message.setFrom(new InternetAddress(from, true));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(delivery.recipient(), true));
        message.setSubject(oneLine(delivery.subject()), StandardCharsets.UTF_8.name());
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText(body.text(), StandardCharsets.UTF_8.name(), "plain");
        MimeBodyPart html = new MimeBodyPart();
        html.setText(body.html(), StandardCharsets.UTF_8.name(), "html");
        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(plain);
        alternative.addBodyPart(html);
        if (attachment == null) {
            message.setContent(alternative);
            return message;
        }
        MimeBodyPart text = new MimeBodyPart();
        text.setContent(alternative);
        MimeBodyPart file = new MimeBodyPart();
        file.setDataHandler(new jakarta.activation.DataHandler(
                new ByteArrayDataSource(attachment.bytes(), attachment.contentType())));
        file.setFileName(oneLine(attachment.fileName()));
        file.setDisposition(MimeBodyPart.ATTACHMENT);
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(text);
        mixed.addBodyPart(file);
        message.setContent(mixed);
        return message;
    }

    /**
     * A subject on one line: a CR, LF or other line break a value carried (a business's name) becomes a space, so no
     * value can start a header of its own.
     */
    static String oneLine(String subject) {
        return subject == null
                ? ""
                : subject.replaceAll("[\\r\\n\\u0085\\u2028\\u2029\\t]+", " ").strip();
    }

    /**
     * The look of a row: the platform's, or for a row of a tenant with a readable logo, the tenant's (its logo and its
     * brand colour). Read in one short transaction of that tenant; any failure (a suspended tenant, storage down, an
     * image that cannot be read) falls back to the platform's look and is logged without the recipient.
     */
    EmailLayout.Brand brand(RowContext context) {
        EmailLayout.Brand platform = EmailLayout.Brand.platform(hosts.platformOrigin());
        if (context == null || context.tenantId() == null) {
            return platform;
        }
        try {
            return tenantJobs.callForTenant(context.tenantId(), () -> tenantBrand(platform));
        } catch (RuntimeException e) {
            log.warn(
                    "email brand of outbox row {} falls back to the platform's: {}",
                    context.outboxId(),
                    e.getClass().getSimpleName());
            return platform;
        }
    }

    private EmailLayout.Brand tenantBrand(EmailLayout.Brand platform) {
        Optional<String> path = letterheads.logoPath();
        if (path.isEmpty()) {
            return platform;
        }
        Letterheads.Letterhead letterhead = letterheads.current();
        Optional<int[]> size = letterhead.logo().flatMap(logo -> fit(logo.bytes()));
        if (size.isEmpty()) {
            return platform;
        }
        CurrentTenant.Profile profile = tenant.profile();
        String name =
                letterhead.displayName() == null || letterhead.displayName().isBlank()
                        ? profile.name()
                        : letterhead.displayName();
        return new EmailLayout.Brand(
                hosts.tenantOrigin(profile.slug()) + path.get(),
                name,
                size.get()[0],
                size.get()[1],
                letterhead.brandColour(),
                letterhead.textOnBrand(),
                true);
    }

    /** The logo's drawn width and height inside {@link #LOGO_MAX_WIDTH} by {@link #LOGO_MAX_HEIGHT}, never enlarged. */
    static Optional<int[]> fit(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return Optional.empty();
            }
            return Optional.of(fit(image.getWidth(), image.getHeight()));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static int[] fit(int width, int height) {
        double scale = Math.min(1.0, Math.min((double) LOGO_MAX_WIDTH / width, (double) LOGO_MAX_HEIGHT / height));
        return new int[] {Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))};
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
