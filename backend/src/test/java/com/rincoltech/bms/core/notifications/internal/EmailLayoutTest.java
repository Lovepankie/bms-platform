package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.core.tenancy.PlatformHost;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The branded emails of issue #396 (ADR-054), without a database or a mail server: every email template renders a text
 * and an HTML part from the same values, the links of both parts are the same, every value is escaped in the HTML, and the
 * SMTP channel composes {@code multipart/alternative} with the plain part first. Fabricated names, addresses and links.
 */
class EmailLayoutTest {

    static final String PLATFORM = "https://bms-staging.rincoltech.test";
    static final Pattern TEXT_URL = Pattern.compile("https?://\\S+");
    static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");

    final OutboxTemplates templates = new OutboxTemplates();

    /** Fabricated values for every placeholder; the links carry an ampersand so the escaping of an href is tested. */
    static Map<String, String> params() {
        Map<String, String> p = new HashMap<>();
        p.put("contact_name", "Test Applicant 01");
        p.put("business_name", "Test Shop");
        p.put("tenant_name", "Test Lender");
        p.put("link", "https://test-bms-staging.rincoltech.test/accept-invitation?x=1&y=2#token=fabricated");
        p.put(
                "links",
                "Test Shop: https://shop-bms-staging.rincoltech.test/sign-in\nTest Lender: https://lender-bms-staging.rincoltech.test/sign-in");
        p.put("sign_in_url", "https://test-bms-staging.rincoltech.test/sign-in");
        p.put("portal_url", "https://bms-staging.rincoltech.test/platform/applications/fabricated");
        p.put("note", "Test note, line one.\nLine two.");
        p.put("expires_at", "9 Oct 2026 08:00 UTC");
        p.put("reference", "A-TEST0001");
        p.put("modules", "retail");
        p.put("text", "Test digest line.");
        p.put("date", "6 Oct 2026");
        p.put("change", "saved");
        p.put("who", "Test Administrator");
        p.put("when", "18 Oct 2026 10:00 UTC");
        // In-app feedback (issue #447): the sender's words are a value a user typed.
        p.put("sender_name", "Test Staff 01");
        p.put("kind", "problem");
        p.put("page", "/staff/retail/sale");
        p.put("message", "Test message <b>with</b> markup & an ampersand.");
        // The Owner's own plan (issue #472): the module asked for and the decision, with the operator's reason.
        p.put("module", "Shop");
        p.put("decision", "declined. The platform operator wrote: Test reason <i>typed</i> & sent.");
        return p;
    }

    OutboxChannel.Delivery delivery(String key, Map<String, String> params) {
        OutboxTemplates.Rendered rendered = templates.render("email", key, params);
        OutboxChannel.RowContext context = new OutboxChannel.RowContext(
                UUID.randomUUID(), null, "transactional", null, null, key, params, 1, null, null);
        return new OutboxChannel.Delivery(
                "someone@example.test", rendered.subject(), rendered.text(), key + ":test", context);
    }

    /** The platform host and the tenant hosts of the test pattern, as the tenancy module answers them. */
    static final PlatformHost HOSTS = new PlatformHost() {
        @Override
        public boolean servesPlatform(String host) {
            return "bms-staging.rincoltech.test".equalsIgnoreCase(host);
        }

        @Override
        public boolean servesTenant(String host) {
            return host != null && host.toLowerCase().matches("^[a-z0-9-]+-bms-staging\\.rincoltech\\.test$");
        }

        @Override
        public boolean isValidSlug(String slug) {
            return true;
        }

        @Override
        public String tenantOrigin(String slug) {
            return "https://" + slug + "-bms-staging.rincoltech.test";
        }

        @Override
        public String platformOrigin() {
            return PLATFORM;
        }
    };

    static final Predicate<String> TRUSTED = SmtpEmailChannel.trustedLinks(HOSTS);

    SmtpEmailChannel.Body body(String key, Map<String, String> params, EmailLayout.Brand brand, String support) {
        return SmtpEmailChannel.body(templates, delivery(key, params), brand, support, TRUSTED);
    }

    /** The trusted URLs of the text part, cut before trailing punctuation as the HTML part cuts them. */
    static Set<String> textLinks(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = TEXT_URL.matcher(text);
        while (m.find()) {
            String url = EmailLayout.trimTrailing(m.group());
            if (TRUSTED.test(url)) {
                out.add(url);
            }
        }
        return out;
    }

    static Set<String> htmlLinks(String html) {
        Set<String> out = new TreeSet<>();
        Matcher m = HREF.matcher(html);
        while (m.find()) {
            out.add(m.group(1).replace("&amp;", "&"));
        }
        return out;
    }

    static EmailLayout.Brand platform() {
        return EmailLayout.Brand.platform(PLATFORM);
    }

    @Test
    void everyEmailTemplateRendersBothPartsWithTheSameLinks() {
        List<String> keys = OutboxTemplates.keys("email");
        assertThat(keys).isNotEmpty().contains("onboarding.activation", OutboxTemplates.ACTIVATION_TWO_STEP);
        for (String key : keys) {
            SmtpEmailChannel.Body body = body(key, params(), platform(), "support@example.test");
            assertThat(body.text()).as(key).isNotBlank().doesNotContain("{");
            assertThat(body.html())
                    .as(key)
                    .startsWith("<!DOCTYPE html>")
                    .contains("max-width:600px")
                    .contains("<h1 ")
                    .contains(EmailLayout.SENDER_LINE)
                    .contains("Need help? support@example.test")
                    .doesNotContain("{")
                    .doesNotContain("<script")
                    .doesNotContain("<link ")
                    .doesNotContain("<style");
            // The header logo: an absolute URL on the platform host, with alt text, width and height.
            assertThat(body.html())
                    .contains("src=\"" + PLATFORM + EmailLayout.PLATFORM_LOGO_PATH + "\"")
                    .contains("alt=\"Rincol Tech\"")
                    .contains("width=\"" + EmailLayout.PLATFORM_LOGO_WIDTH + "\"")
                    .contains("height=\"" + EmailLayout.PLATFORM_LOGO_HEIGHT + "\"");
            assertThat(htmlLinks(body.html())).as("links of %s", key).isEqualTo(textLinks(body.text()));
        }
    }

    @Test
    void noEmailTemplateIsLeftPlainOnly() {
        for (String key : OutboxTemplates.keys("email")) {
            Set<String> names = OutboxTemplates.placeholders("email", key);
            String html = body(key, params(), platform(), "").html();
            // Every email says why the reader got it.
            assertThat(templates.emailReason(key, params()))
                    .as("reason of %s", key)
                    .isNotBlank();
            assertThat(html).as(key).contains("You got this email because");
            // An email with a main link has the big button, and the same link again below it for copy and paste.
            if (names.contains("link") || names.contains("portal_url")) {
                assertThat(html).as("button of %s", key).contains("If the button does not work");
                String url = params().get(names.contains("link") ? "link" : "portal_url");
                String href = "href=\"" + EmailLayout.escape(url) + "\"";
                assertThat(html.split(Pattern.quote(href), -1)).as(key).hasSize(3);
            }
            // The sign-off is two words.
            assertThat(templates.render("email", key, params()).text()).as(key).doesNotContain("Rincoltech");
        }
    }

    @Test
    void theHtmlEscapesEveryValueAUserTyped() {
        Map<String, String> p = params();
        p.put("contact_name", "<b onmouseover=\"x()\">Test</b>");
        p.put("business_name", "<script>alert('x')</script> & \"Test\" Shop");
        p.put("note", "<img src=x onerror=alert(1)> see https://evil.example.test/");
        p.put("who", "<a href=\"https://evil.example.test\">Test</a>");
        for (String key : OutboxTemplates.keys("email")) {
            String html = body(key, p, platform(), "<i>Test support</i>").html();
            assertThat(html)
                    .as(key)
                    .doesNotContain("<script>")
                    .doesNotContain("<b onmouseover")
                    .doesNotContain("<img src=x")
                    .doesNotContain("<i>Test support</i>")
                    .doesNotContain("href=\"https://evil.example.test");
        }
        String needsInfo = body("onboarding.needs_info", p, platform(), "").html();
        assertThat(needsInfo)
                .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &quot;Test&quot; Shop")
                .contains("&lt;b onmouseover=&quot;x()&quot;&gt;Test&lt;/b&gt;")
                // A typed note is escaped text, and a URL in it is never linked.
                .contains("&lt;img src=x onerror=alert(1)&gt; see https://evil.example.test/")
                .doesNotContain("href=\"https://evil");
    }

    @Test
    void theActivationAsksForTwoStepSignInOnlyWhenTheBusinessRequiresIt() {
        String plain =
                templates.render("email", "onboarding.activation", params()).text();
        String twoStep = templates
                .render("email", OutboxTemplates.ACTIVATION_TWO_STEP, params())
                .text();
        assertThat(plain).contains("set your password").doesNotContain("two-step");
        assertThat(twoStep).contains("turn on two-step sign-in").contains("requires");
        assertThat(OutboxTemplates.placeholders("email", OutboxTemplates.ACTIVATION_TWO_STEP))
                .isEqualTo(OutboxTemplates.placeholders("email", "onboarding.activation"));
        assertThat(body("onboarding.activation", params(), platform(), "").html())
                .contains(">Set my password</a>");
    }

    @Test
    void aBusinessLogoHeadsTheEmailsOfItsOwnStaff() {
        EmailLayout.Brand tenant = new EmailLayout.Brand(
                "https://shop-bms-staging.rincoltech.test/api/v1/branding/logo",
                "Test \"Shop\" <Ltd>",
                120,
                64,
                "#112233",
                "#FFFFFF",
                true);
        SmtpEmailChannel.Body body = body("identity.password_reset", params(), tenant, "");
        assertThat(body.html())
                .contains("src=\"https://shop-bms-staging.rincoltech.test/api/v1/branding/logo\"")
                .contains("alt=\"Test &quot;Shop&quot; &lt;Ltd&gt;\"")
                .contains("width=\"120\" height=\"64\"")
                .contains(EmailLayout.POWERED_BY)
                .contains("bgcolor=\"#112233\"")
                .doesNotContain("Need help?");
        assertThat(body.text()).contains(EmailLayout.POWERED_BY).doesNotContain("Need help?");
        // A logo keeps its shape inside the box and is never enlarged.
        assertThat(SmtpEmailChannel.fit(512, 128)).containsExactly(200, 50);
        assertThat(SmtpEmailChannel.fit(128, 128)).containsExactly(64, 64);
        assertThat(SmtpEmailChannel.fit(100, 30)).containsExactly(100, 30);
        assertThat(SmtpEmailChannel.fit(new byte[] {1, 2, 3})).isEmpty();
    }

    @Test
    void theTextPartEndsWithTheSameFooter() {
        SmtpEmailChannel.Body body = body("onboarding.verify_email", params(), platform(), "support@example.test");
        assertThat(body.text())
                .contains("Rincol Tech\n")
                .contains("\n-- \n" + EmailLayout.SENDER_LINE + "\n")
                .contains("You got this email because this address was given on an application to use BMS.")
                .endsWith("Need help? support@example.test\n");
    }

    @Test
    void theSmtpChannelSendsMultipartAlternativeWithThePlainPartFirst() throws Exception {
        OutboxChannel.Delivery delivery = delivery("identity.password_reset", params());
        SmtpEmailChannel.Body body = SmtpEmailChannel.body(templates, delivery, platform(), "", TRUSTED);
        MimeMessage message = SmtpEmailChannel.compose(
                Session.getInstance(new Properties()), "BMS by Rincol Tech <no-reply@example.test>", delivery, body);
        message.saveChanges();
        // BMS_MAIL_FROM may carry a display name.
        jakarta.mail.internet.InternetAddress from = (jakarta.mail.internet.InternetAddress) message.getFrom()[0];
        assertThat(from.getPersonal()).isEqualTo("BMS by Rincol Tech");
        assertThat(from.getAddress()).isEqualTo("no-reply@example.test");
        assertThat(message.getContentType()).startsWith("multipart/alternative");
        assertThat(message.getHeader("Message-ID")[0]).isEqualTo(SmtpEmailChannel.messageId(delivery.idempotencyKey()));
        MimeMultipart parts = (MimeMultipart) message.getContent();
        assertThat(parts.getCount()).isEqualTo(2);
        assertThat(parts.getBodyPart(0).getContentType())
                .startsWith("text/plain")
                .containsIgnoringCase("utf-8");
        assertThat(parts.getBodyPart(1).getContentType())
                .startsWith("text/html")
                .containsIgnoringCase("utf-8");
        assertThat((String) parts.getBodyPart(0).getContent()).isEqualTo(body.text());
        assertThat((String) parts.getBodyPart(1).getContent()).isEqualTo(body.html());
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        message.writeTo(raw);
        assertThat(raw.toString(StandardCharsets.UTF_8)).contains("multipart/alternative");
    }

    @Test
    void aDeliveryWithoutATemplateStillGetsAnHtmlPart() {
        OutboxChannel.Delivery delivery =
                new OutboxChannel.Delivery("someone@example.test", "Test <subject>", "Line <one>\n\nLine two", "k:1");
        SmtpEmailChannel.Body body = SmtpEmailChannel.body(templates, delivery, platform(), "", TRUSTED);
        assertThat(body.html())
                .contains("Test &lt;subject&gt;")
                .contains("Line &lt;one&gt;")
                .contains("Line two");
        assertThat(body.text()).startsWith("Line <one>\n\nLine two\n");
    }

    // JUSTIFICATION-A3: the four tests the review of #397 asked for (trusted links, colours, subject, tenant logo).

    /** Review of #397: only the platform's own hosts and scheme become links; trailing punctuation stays outside. */
    @Test
    void onlyTrustedUrlsBecomeLinksOrTheButton() {
        Map<String, String> p = params();
        // A business named to look like a link, inside a list of sign-in pages (the find-your-sign-in email's shape).
        p.put(
                "links",
                "Test https://evil.example.test/ Shop: https://evil.example.test/sign-in\n"
                        + "Test Shop: https://shop-bms-staging.rincoltech.test/sign-in.\n"
                        + "Test Plain: http://plain-bms-staging.rincoltech.test/sign-in");
        String html = EmailLayout.html(
                platform(), EmailLayout.content("Test", "Open your page:\n\n{links}", p, "Open", null, TRUSTED), "");
        assertThat(htmlLinks(html)).containsExactly("https://shop-bms-staging.rincoltech.test/sign-in");
        assertThat(html)
                .contains("Test https://evil.example.test/ Shop: https://evil.example.test/sign-in<br>")
                .contains("rincoltech.test/sign-in</a>.<br>")
                .contains("Test Plain: http://plain-bms-staging.rincoltech.test/sign-in")
                .doesNotContain("If the button does not work");

        // A main link to a host that is not ours is never the button and never an anchor.
        p.put("link", "https://evil.example.test/reset#token=fabricated");
        String reset = body("identity.password_reset", p, platform(), "").html();
        assertThat(reset)
                .doesNotContain("If the button does not work")
                .doesNotContain("href=\"https://evil")
                .contains("https://evil.example.test/reset#token=fabricated");
        // A host that only ends or starts like ours is not ours.
        assertThat(TRUSTED.test("https://bms-staging.rincoltech.test.evil.example/x"))
                .isFalse();
        assertThat(TRUSTED.test("https://user@evil.example.test/x")).isFalse();
        assertThat(TRUSTED.test("https://bms-staging.rincoltech.test/x")).isTrue();
    }

    @Test
    void aColourThatIsNotRrggbbNeverReachesAStyle() {
        EmailLayout.Brand odd = new EmailLayout.Brand(
                "https://shop-bms-staging.rincoltech.test/logo", "Test", 10, 10, "red;background:url(x)", "#FFF", true);
        assertThat(odd.colour()).isEqualTo(EmailLayout.PLATFORM_COLOUR);
        assertThat(odd.textOnColour()).isEqualTo("#FFFFFF");
        assertThat(new EmailLayout.Brand("u", "a", 1, 1, "#a1B2c3", "#111111", true).colour())
                .isEqualTo("#a1B2c3");
    }

    /** Review of #397: a line break in a business's name cannot start a header of its own. */
    @Test
    void aLineBreakInATenantNameCannotReachTheSubjectHeader() throws Exception {
        Map<String, String> p = params();
        p.put("tenant_name", "Test Lender\r\nBcc: someone-else@example.test");
        OutboxChannel.Delivery delivery = delivery("identity.password_reset", p);
        SmtpEmailChannel.Body body = SmtpEmailChannel.body(templates, delivery, platform(), "", TRUSTED);
        MimeMessage message = SmtpEmailChannel.compose(
                Session.getInstance(new Properties()), "no-reply@example.test", delivery, body);
        message.saveChanges();
        assertThat(message.getHeader("Bcc")).isNull();
        assertThat(message.getSubject())
                .doesNotContain("\r")
                .doesNotContain("\n")
                .isEqualTo("Reset your BMS password for Test Lender Bcc: someone-else@example.test");
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        message.writeTo(raw);
        String headers = raw.toString(StandardCharsets.UTF_8).split("\r\n\r\n", 2)[0];
        assertThat(headers).doesNotContainPattern("(?m)^Bcc:");
    }

    /** The header of a business's own staff email: its logo by the versioned public route, sized from the image. */
    @Test
    void aTenantRowCarriesTheVersionedLogoOfItsBusiness() {
        UUID documentId = UUID.randomUUID();
        byte[] png = com.rincoltech.bms.testsupport.ImageFixtures.png(256, 128, java.awt.Color.BLUE);
        java.util.concurrent.atomic.AtomicReference<java.util.Optional<String>> path =
                new java.util.concurrent.atomic.AtomicReference<>(
                        java.util.Optional.of("/api/v1/branding/logo?v=" + documentId));
        com.rincoltech.bms.core.tenancy.Letterheads letterheads = new com.rincoltech.bms.core.tenancy.Letterheads() {
            @Override
            public Letterhead current() {
                return new Letterhead(
                        "Test \"Shop\"", "#112233", "#FFFFFF", "", java.util.Optional.of(new Logo("image/png", png)));
            }

            @Override
            public Letterhead forBranch(UUID branchId) {
                return current();
            }

            @Override
            public Letterhead forBranchCode(String code) {
                return current();
            }

            @Override
            public java.util.Optional<String> logoPath() {
                return path.get();
            }
        };
        com.rincoltech.bms.core.tenancy.CurrentTenant tenant =
                () -> new com.rincoltech.bms.core.tenancy.CurrentTenant.Profile(
                        UUID.randomUUID(), "shop", "Test Shop", "UGX", java.time.ZoneId.of("Africa/Kampala"));
        com.rincoltech.bms.core.jobs.TenantJobs jobs = new com.rincoltech.bms.core.jobs.TenantJobs() {
            @Override
            public int forEachActiveTenant(String jobName, java.util.function.Consumer<UUID> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int forEachActiveTenantWithModule(
                    String jobName, String moduleKey, java.util.function.Consumer<UUID> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T callAs(UUID tenantId, java.util.function.Supplier<T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T callAsTenant(String slug, String moduleKey, java.util.function.Supplier<T> work) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> T callForTenant(UUID tenantId, java.util.function.Supplier<T> work) {
                return work.get();
            }

            @Override
            public <T> T callForTenantTakingPayments(UUID tenantId, java.util.function.Supplier<T> work) {
                throw new UnsupportedOperationException();
            }
        };
        SmtpEmailChannel channel = new SmtpEmailChannel(
                new NotificationProperties(null, null, ""), templates, HOSTS, jobs, tenant, letterheads, "");
        OutboxChannel.RowContext row = new OutboxChannel.RowContext(
                UUID.randomUUID(), UUID.randomUUID(), "transactional", null, null, null, Map.of(), 1, null, null);

        EmailLayout.Brand brand = channel.brand(row);
        assertThat(brand.tenant()).isTrue();
        assertThat(brand.logoUrl())
                .isEqualTo("https://shop-bms-staging.rincoltech.test/api/v1/branding/logo?v=" + documentId);
        assertThat(brand.logoAlt()).isEqualTo("Test \"Shop\"");
        assertThat(brand.logoWidth()).isEqualTo(128);
        assertThat(brand.logoHeight()).isEqualTo(64);
        assertThat(brand.colour()).isEqualTo("#112233");

        // No logo set, or a platform row: the Rincol Tech look.
        path.set(java.util.Optional.empty());
        assertThat(channel.brand(row).tenant()).isFalse();
        assertThat(channel.brand(OutboxChannel.RowContext.NONE).logoUrl())
                .isEqualTo(PLATFORM + EmailLayout.PLATFORM_LOGO_PATH);
    }
}
