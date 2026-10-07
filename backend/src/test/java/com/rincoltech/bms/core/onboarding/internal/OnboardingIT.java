package com.rincoltech.bms.core.onboarding.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Self-onboarding slice 1 (FR-ONB-01 to FR-ONB-09, ADR-024, spec sections 4, 8, 10 and 12): the
 * public sign-up endpoints, the operator queue and decisions, Activate, the housekeeping job, and
 * the database protection of the application table. Fabricated businesses, people and numbers
 * only; every test calls from its own documentation address (RFC 5737) so the per-address rate
 * limits of one test never touch another.
 */
class OnboardingIT extends IntegrationTest {

    private static final AtomicInteger ADDRESS = new AtomicInteger(1);

    @Autowired
    TestRestTemplate http;

    @Autowired
    ApplicationService service;

    @Autowired
    ObjectMapper mapper;

    Api platform;
    Session operator;
    UUID operatorId;
    String ip;

    @BeforeEach
    void setUp() {
        platform = Api.platform(http);
        String email = Api.email("operator");
        operatorId = Api.platformUser(email);
        operator = platform.signIn(operatorId, email, Api.PASSWORD, null);
        int n = ADDRESS.getAndIncrement();
        ip = "198.51." + (n / 250) + "." + (n % 250 + 1);
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private static final AtomicInteger PHONE = new AtomicInteger();

    /** Fabricated numbers in the +2567000000NN range, a different one per call. */
    static String phone() {
        return "+2567000000" + String.format("%02d", PHONE.getAndIncrement() % 100);
    }

    Map<String, Object> form(String email, String business, String phone) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("business_name", business);
        form.put("contact_name", "Test Applicant 01");
        form.put("contact_email", email);
        form.put("contact_phone", phone);
        form.put("country", "UG");
        form.put("modules", List.of("retail"));
        form.put("term", "monthly");
        form.put("way_in", "trial");
        form.put("message", "We run one fabricated shop.");
        return form;
    }

    ResponseEntity<JsonNode> publicPost(String path, Object body) {
        return publicPost(path, body, ip);
    }

    ResponseEntity<JsonNode> publicPost(String path, Object body, String from) {
        return platform.call(HttpMethod.POST, path, body, null, Map.of("X-Forwarded-For", from));
    }

    ResponseEntity<JsonNode> apply(Map<String, Object> form) {
        return publicPost("/api/v1/platform/sign-up/applications", form);
    }

    static JdbcClient owner() {
        return TestDatabase.owner();
    }

    /** The newest outbox row of a template for a recipient: its token and parameters. */
    Map<String, String> outboxParams(String template, String recipient) {
        String json =
                owner().sql("""
                        SELECT params::text FROM notification_outbox WHERE template_key = ? AND recipient = ?
                         ORDER BY created_at DESC, id DESC LIMIT 1
                        """).params(template, recipient).query(String.class).single();
        @SuppressWarnings("unchecked")
        Map<String, String> params = mapper.readValue(json, Map.class);
        return params;
    }

    static String tokenOf(String link) {
        return link.substring(link.indexOf("#token=") + 7);
    }

    UUID applicationId(String email) {
        return owner().sql(
                        "SELECT id FROM onboarding_applications WHERE contact_email = ? ORDER BY created_at DESC LIMIT 1")
                .param(email)
                .query(UUID.class)
                .single();
    }

    long outboxRows(String template, String recipient) {
        return owner().sql("SELECT count(*) FROM notification_outbox WHERE template_key = ? AND recipient = ?")
                .params(template, recipient)
                .query(Long.class)
                .single();
    }

    long platformAudits(String action, UUID applicationId) {
        return owner().sql("SELECT count(*) FROM platform_audit_log WHERE action = ? AND data->>'application_id' = ?")
                .params(action, applicationId.toString())
                .query(Long.class)
                .single();
    }

    /** Applies and confirms the email: the application is in the queue as submitted. */
    UUID submitted(String email, String business, String phone) {
        assertThat(apply(form(email, business, phone)).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String token = tokenOf(outboxParams("onboarding.verify_email", email).get("link"));
        ResponseEntity<JsonNode> verified = publicPost("/api/v1/platform/sign-up/verify", Map.of("token", token));
        assertThat(verified.getStatusCode()).as("%s", verified.getBody()).isEqualTo(HttpStatus.OK);
        return applicationId(email);
    }

    UUID verified(String email, String business) {
        UUID id = submitted(email, business, phone());
        ResponseEntity<JsonNode> v =
                platform.post("/api/v1/platform/applications/" + id + "/verify", Map.of(), operator.accessToken());
        assertThat(v.getStatusCode()).as("%s", v.getBody()).isEqualTo(HttpStatus.OK);
        return id;
    }

    Map<String, Object> activation(String slug, String wayIn, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("modules", List.of("retail"));
        body.put("term", "monthly");
        body.put("way_in", wayIn);
        body.put("payment_note", note);
        body.put("slug", slug);
        body.put("agent_code", "AGENT-01");
        return body;
    }

    ResponseEntity<JsonNode> activate(UUID id, Map<String, Object> body) {
        return platform.post("/api/v1/platform/applications/" + id + "/activate", body, operator.accessToken());
    }

    static String slug() {
        return "onb-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ---- Public endpoints ---------------------------------------------------------------------

    /** FR-ONB-01, FR-ONB-02, FR-ONB-08: apply, confirm the email, join the queue, alert operators. */
    @Test
    void anApplicantAppliesConfirmsTheEmailAndReachesTheQueue() {
        String email = Api.email("applicant");
        ResponseEntity<JsonNode> accepted = apply(form(email, "Test Shop One", "0700 000 001"));
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody().get("status").asString()).isEqualTo("check_your_email");
        UUID id = applicationId(email);
        assertThat(owner().sql("SELECT contact_phone_e164 FROM onboarding_applications WHERE id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("+256700000001");

        // Not in the queue until the email is confirmed.
        assertThat(platform.get("/api/v1/platform/applications", operator.accessToken())
                        .getBody()
                        .get("items")
                        .toString())
                .doesNotContain(id.toString());
        assertThat(platform.get("/api/v1/platform/applications/" + id, operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // The link goes to the platform host only, and the token is stored hashed.
        String link = outboxParams("onboarding.verify_email", email).get("link");
        assertThat(link).startsWith("https://localhost/sign-up/verify#token=");
        String token = tokenOf(link);
        assertThat(token).hasSize(43);
        assertThat(owner().sql("SELECT link_token_hash FROM onboarding_applications WHERE id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo(ApplicationService.sha256(token))
                .isNotEqualTo(token);

        ResponseEntity<JsonNode> view = publicPost("/api/v1/platform/sign-up/verify", Map.of("token", token));
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(view.getBody().get("status").asString()).isEqualTo("submitted");
        assertThat(view.getBody().get("email_verified").asBoolean()).isTrue();
        // The applicant page carries no contact details.
        assertThat(view.getBody().toString()).doesNotContain(email).doesNotContain("+256700000001");

        // Operator alerts: one email per active operator and one Telegram row, once.
        assertThat(outboxRows("onboarding.operator_alert", "operator")).isPositive();
        long alerts = owner().sql("SELECT count(*) FROM notification_outbox WHERE idempotency_key LIKE ?")
                .param("onboarding.operator_alert:" + id + ":%")
                .query(Long.class)
                .single();
        assertThat(alerts).isGreaterThanOrEqualTo(2);
        assertThat(publicPost("/api/v1/platform/sign-up/verify", Map.of("token", token))
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(owner().sql("SELECT count(*) FROM notification_outbox WHERE idempotency_key LIKE ?")
                        .param("onboarding.operator_alert:" + id + ":%")
                        .query(Long.class)
                        .single())
                .isEqualTo(alerts);
        assertThat(platformAudits("platform.application.email_verified", id)).isEqualTo(1);

        // In the queue now, with counts.
        JsonNode queue = platform.get("/api/v1/platform/applications?status=submitted", operator.accessToken())
                .getBody();
        assertThat(queue.get("items").toString()).contains(id.toString());
        assertThat(queue.get("counts").get("submitted").asLong()).isPositive();
        assertThat(publicPost("/api/v1/platform/sign-up/status", Map.of("token", token))
                        .getBody()
                        .get("reference")
                        .asString())
                .startsWith("A-");
    }

    /** FR-ONB-03: one open application per email, and the same answer for a known email. */
    @Test
    void aKnownEmailGetsTheSameAnswerAndAFreshLinkToTheOpenApplication() {
        String email = Api.email("again");
        ResponseEntity<JsonNode> first = apply(form(email, "Test First Shop " + email, phone()));
        String firstToken =
                tokenOf(outboxParams("onboarding.verify_email", email).get("link"));
        ResponseEntity<JsonNode> second = apply(form(email.toUpperCase(), "Test Other Name " + email, phone()));
        ResponseEntity<JsonNode> unknown = apply(form(Api.email("unknown"), "Test Third Shop", phone()));

        assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode()).isEqualTo(unknown.getStatusCode());
        assertThat(second.getBody()).isEqualTo(first.getBody()).isEqualTo(unknown.getBody());
        assertThat(owner().sql("SELECT count(*) FROM onboarding_applications WHERE lower(contact_email) = ?")
                        .param(email)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(outboxRows("onboarding.verify_email", email)).isEqualTo(2);
        // The confirmation email carries only the server's reference and the link: no text the
        // applicant typed reaches an unconfirmed address (review B1).
        Map<String, String> params = outboxParams("onboarding.verify_email", email);
        assertThat(params.keySet()).containsExactlyInAnyOrder("reference", "link");
        assertThat(params.get("reference"))
                .isEqualTo(owner().sql("SELECT reference FROM onboarding_applications WHERE contact_email = ?")
                        .param(email)
                        .query(String.class)
                        .single());
        // A +tag or Gmail dot variant reaches the same mailbox: still one open application, and the
        // fresh link goes to the stored address, not to the variant (review N5).
        String local = email.substring(0, email.indexOf('@'));
        String tagged = local + "+again@" + email.substring(email.indexOf('@') + 1);
        assertThat(apply(form(tagged, "Test Tagged Name", phone())).getBody()).isEqualTo(first.getBody());
        assertThat(owner().sql(
                                "SELECT count(*) FROM onboarding_applications WHERE contact_email_key = onboarding_email_key(?)")
                        .param(email)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // The link goes to the address just submitted (round 2 review item 2).
        assertThat(outboxRows("onboarding.verify_email", tagged)).isEqualTo(1);
        assertThat(outboxRows("onboarding.verify_email", email)).isEqualTo(2);
        String gmail = "test.dots." + UUID.randomUUID().toString().substring(0, 6) + "@gmail.com";
        String other = "203.0.113." + (ADDRESS.addAndGet(1) % 200 + 1);
        publicPost("/api/v1/platform/sign-up/applications", form(gmail, "Test Gmail Shop", phone()), other);
        publicPost(
                "/api/v1/platform/sign-up/applications",
                form(gmail.replace(".", "").replace("gmailcom", "googlemail.com"), "Test Gmail Shop", phone()),
                other);
        assertThat(owner().sql(
                                "SELECT count(*) FROM onboarding_applications WHERE contact_email_key = onboarding_email_key(?)")
                        .param(gmail)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // Only the newest link works.
        assertThat(publicPost("/api/v1/platform/sign-up/status", Map.of("token", firstToken))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** FR-ONB-03: the hidden field silently drops automated sign-ups. */
    @Test
    void aFilledHoneypotIsAcceptedAndDropped() {
        String email = Api.email("bot");
        Map<String, Object> form = form(email, "Test Bot Shop", phone());
        form.put("website", "http://spam.example.test");
        ResponseEntity<JsonNode> response = apply(form);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().get("status").asString()).isEqualTo("check_your_email");
        assertThat(owner().sql("SELECT count(*) FROM onboarding_applications WHERE contact_email = ?")
                        .param(email)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(outboxRows("onboarding.verify_email", email)).isZero();
    }

    /** FR-ONB-01: input rules and length caps. */
    @Test
    void invalidFormsAreRefused() {
        String email = Api.email("invalid");
        assertThat(apply(form(email, "Test Shop", "12345")).getBody().toString())
                .contains("invalid_phone");
        Map<String, Object> kenya = form(email, "Test Shop", "0700000000");
        kenya.put("country", "KE");
        assertThat(apply(kenya).getBody().toString()).contains("invalid_phone");
        kenya.put("contact_phone", "+254 700 000 000");
        assertThat(apply(kenya).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        String other = Api.email("invalid");
        Map<String, Object> accented = form("jos\u00e9@example.test", "Test Shop", phone());
        assertThat(apply(accented).getBody().get("code").asString()).isEqualTo("validation_failed");
        assertThat(apply(form(other, "x".repeat(201), phone()))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("validation_failed");
        Map<String, Object> module = form(other, "Test Shop", phone());
        module.put("modules", List.of("hospitality"));
        assertThat(apply(module).getBody().get("code").asString()).isEqualTo("validation_failed");
        Map<String, Object> country = form(other, "Test Shop", phone());
        country.put("country", "US");
        assertThat(apply(country).getBody().get("code").asString()).isEqualTo("validation_failed");
        Map<String, Object> notEmail = form("not-an-email", "Test Shop", phone());
        assertThat(apply(notEmail).getBody().get("code").asString()).isEqualTo("validation_failed");
        Map<String, Object> unknownField = form(other, "Test Shop", phone());
        unknownField.put("tenant_id", UUID.randomUUID().toString());
        assertThat(apply(unknownField).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * FR-ONB-03, review B2 and N6: 5 applications an hour from one address, IPv6 counted per /64,
     * a spoofed left-most X-Forwarded-For value changes nothing, and 30 link reads per 10 minutes.
     */
    @Test
    void applicationsAreRateLimitedPerAddress() {
        for (int i = 0; i < SignUpRateLimiter.CREATE_PER_IP.capacity(); i++) {
            assertThat(apply(form(Api.email("rate"), "Test Rate Shop", phone())).getStatusCode())
                    .isEqualTo(HttpStatus.ACCEPTED);
        }
        ResponseEntity<JsonNode> limited = apply(form(Api.email("rate"), "Test Rate Shop", phone()));
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody().get("code").asString()).isEqualTo("rate_limited");

        // Behind the trusted proxy the valve takes the right-most untrusted value: a client that
        // prepends its own X-Forwarded-For entries still lands in its real bucket.
        String real = "198.51.100." + (ADDRESS.addAndGet(1) % 250 + 1);
        for (int i = 0; i < SignUpRateLimiter.CREATE_PER_IP.capacity(); i++) {
            assertThat(publicPost(
                                    "/api/v1/platform/sign-up/applications",
                                    form(Api.email("spoof"), "Test Spoof Shop", phone()),
                                    "203.0.113." + (i + 1) + ", " + real)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.ACCEPTED);
        }
        assertThat(publicPost(
                                "/api/v1/platform/sign-up/applications",
                                form(Api.email("spoof"), "Test Spoof Shop", phone()),
                                "203.0.113.99, " + real)
                        .getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // One IPv6 /64 is one bucket, however many addresses inside it are used.
        String prefix = "2001:db8:" + Integer.toHexString(ADDRESS.addAndGet(1)) + ":1:";
        for (int i = 0; i < SignUpRateLimiter.CREATE_PER_IP.capacity(); i++) {
            assertThat(publicPost(
                                    "/api/v1/platform/sign-up/applications",
                                    form(Api.email("six"), "Test Six Shop", phone()),
                                    prefix + Integer.toHexString(i + 1) + ":0:0:1")
                            .getStatusCode())
                    .isEqualTo(HttpStatus.ACCEPTED);
        }
        assertThat(publicPost(
                                "/api/v1/platform/sign-up/applications",
                                form(Api.email("six"), "Test Six Shop", phone()),
                                prefix + "ffff:0:0:9")
                        .getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        for (int i = 0; i < SignUpRateLimiter.LINK_PER_IP.capacity(); i++) {
            publicPost("/api/v1/platform/sign-up/status", Map.of("token", "x".repeat(43)));
        }
        assertThat(publicPost("/api/v1/platform/sign-up/status", Map.of("token", "x".repeat(43)))
                        .getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void addressKeysGroupAnIpv6NetworkAndKeepIpv4Addresses() {
        assertThat(SignUpRateLimiter.addressKey("2001:db8:1:2::1"))
                .isEqualTo(SignUpRateLimiter.addressKey("2001:db8:1:2:ffff:ffff:ffff:ffff"))
                .isNotEqualTo(SignUpRateLimiter.addressKey("2001:db8:1:3::1"));
        assertThat(SignUpRateLimiter.addressKey("198.51.100.1"))
                .isNotEqualTo(SignUpRateLimiter.addressKey("198.51.100.2"));
        assertThat(SignUpRateLimiter.addressKey(null)).isEqualTo("unknown");
        assertThat(SignUpRateLimiter.addressKey("not-an-address.example")).startsWith("raw:");
    }

    long verifyRowsFor(String mailbox) {
        return owner().sql(
                        "SELECT count(*) FROM notification_outbox WHERE throttle_key = 'onboarding.verify:' || onboarding_email_key(?)")
                .param(mailbox)
                .query(Long.class)
                .single();
    }

    String linkHashOf(String mailbox) {
        return owner().sql(
                        "SELECT link_token_hash FROM onboarding_applications WHERE contact_email_key = onboarding_email_key(?)")
                .param(mailbox)
                .query(String.class)
                .single();
    }

    /**
     * Review B2 (b): at most 3 confirmation emails to one mailbox in 24 hours, counted in the
     * database, from any number of addresses and address variants. Over the bound the answer is the
     * same 202 and nothing is rotated or sent.
     */
    @Test
    void confirmationEmailsPerMailboxAreBoundedInTheDatabase() {
        String email = Api.email("bound");
        String local = email.substring(0, email.indexOf('@'));
        String domain = email.substring(email.indexOf('@'));
        int n = ADDRESS.addAndGet(10);
        for (int i = 0; i < 3; i++) {
            assertThat(publicPost(
                                    "/api/v1/platform/sign-up/applications",
                                    form(i == 0 ? email : local + "+v" + i + domain, "Test Bound Shop", phone()),
                                    "203.0.113." + (n % 200 + i))
                            .getStatusCode())
                    .isEqualTo(HttpStatus.ACCEPTED);
        }
        assertThat(verifyRowsFor(email)).isEqualTo(3);
        String hash = linkHashOf(email);
        ResponseEntity<JsonNode> over = publicPost(
                "/api/v1/platform/sign-up/applications",
                form(local + "+v9" + domain, "Test Bound Shop", phone()),
                "203.0.113." + (n % 200 + 7));
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(over.getBody().get("status").asString()).isEqualTo("check_your_email");
        assertThat(verifyRowsFor(email)).isEqualTo(3);
        assertThat(linkHashOf(email)).isEqualTo(hash);
    }

    /**
     * Round 2 review item 2: a variant submitted first (for example a +tag on a domain that does
     * not deliver them) cannot squat the mailbox. The real address's sign-up gets the link at the
     * real address, and while unconfirmed that address becomes the stored one.
     */
    @Test
    void aVariantSubmittedFirstCannotSquatTheMailbox() {
        String email = Api.email("victim");
        String variant = email.substring(0, email.indexOf('@')) + "+squat" + email.substring(email.indexOf('@'));
        int n = ADDRESS.addAndGet(5);
        publicPost(
                "/api/v1/platform/sign-up/applications",
                form(variant, "Test Squat Shop", phone()),
                "203.0.113." + (n % 200 + 1));
        assertThat(apply(form(email, "Test Victim Shop", phone())).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(outboxRows("onboarding.verify_email", email)).isEqualTo(1);
        assertThat(owner().sql(
                                "SELECT contact_email FROM onboarding_applications WHERE contact_email_key = onboarding_email_key(?)")
                        .param(email)
                        .query(String.class)
                        .single())
                .isEqualTo(email);
        // The real address confirms with its own link; the squatter's link no longer works.
        String token = tokenOf(outboxParams("onboarding.verify_email", email).get("link"));
        assertThat(publicPost("/api/v1/platform/sign-up/verify", Map.of("token", token))
                        .getBody()
                        .get("email_verified")
                        .asBoolean())
                .isTrue();
        String squatToken =
                tokenOf(outboxParams("onboarding.verify_email", variant).get("link"));
        assertThat(publicPost("/api/v1/platform/sign-up/status", Map.of("token", squatToken))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    com.rincoltech.bms.core.notifications.Outbox outbox;

    @Autowired
    com.rincoltech.bms.core.audit.PlatformAuditLog platformAudit;

    @Autowired
    com.rincoltech.bms.core.tenancy.PlatformHost hosts;

    @Autowired
    com.rincoltech.bms.core.platform.TenantProvisioning provisioning;

    @Autowired
    List<com.rincoltech.bms.core.tenancy.ModuleManifest> manifests;

    /**
     * Review B2 (c): the global caps. Over the hourly cap of new applications or of confirmation
     * emails a sign-up is answered and nothing is created or sent; the first request over it writes
     * one audit row and one Telegram alert for the hour. The test runs its own service with a clock
     * ten years ahead and caps of 5, and its rows lie in that hour, so it shares no counts with any
     * other test (round 2 review item 8): the counts are bounded above by the service's clock.
     */
    @Test
    void theGlobalCapsStopSignUpsAndAlertTheOperatorOnce() {
        Instant future = Instant.now()
                .plus(java.time.Duration.ofDays(3650))
                .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .plus(java.time.Duration.ofMinutes(30));
        com.rincoltech.bms.kernel.BusinessClock clock =
                new com.rincoltech.bms.kernel.BusinessClock(java.time.Clock.fixed(future, java.time.ZoneOffset.UTC));
        ApplicationService capped = new ApplicationService(
                jdbc,
                transactionManager,
                outbox,
                platformAudit,
                hosts,
                provisioning,
                clock,
                new SignUpRateLimiter(clock),
                new OnboardingProperties(5, 5, 3),
                manifests);
        String hour = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00", java.util.Locale.ROOT)
                .format(future.atOffset(java.time.ZoneOffset.UTC));
        JdbcClient owner = owner();
        String marker = UUID.randomUUID().toString().substring(0, 8);
        java.sql.Timestamp at = java.sql.Timestamp.from(future.minus(java.time.Duration.ofMinutes(10)));
        try {
            owner.sql("""
                            INSERT INTO onboarding_applications (id, reference, business_name, contact_name, contact_email,
                                   contact_phone_e164, country, modules, term, way_in, created_at, updated_at)
                            SELECT gen_random_uuid(), 'C-' || ? || g, 'Test Cap Shop', 'Test Cap', 'cap-' || ? || '-' || g || '@example.test',
                                   '+256700000000', 'UG', '{retail}', 'monthly', 'trial', ?, ?
                              FROM generate_series(1, 5) g
                            """).params(marker.substring(0, 6), marker, at, at).update();
            String email = Api.email("capped");
            capped.submit(request(email));
            capped.submit(request(Api.email("capped")));
            assertThat(owner.sql("SELECT count(*) FROM onboarding_applications WHERE contact_email = ?")
                            .param(email)
                            .query(Long.class)
                            .single())
                    .isZero();
            assertThat(outboxRows("onboarding.verify_email", email)).isZero();
            assertThat(owner.sql("SELECT count(*) FROM notification_outbox WHERE idempotency_key = ?")
                            .param("onboarding.cap_reached:" + hour)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
            assertThat(owner.sql(
                                    "SELECT count(*) FROM platform_audit_log WHERE action = 'platform.sign_up.cap_reached' AND data->>'hour' = ?")
                            .param(hour)
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
        } finally {
            owner.sql("DELETE FROM onboarding_applications WHERE contact_email LIKE ?")
                    .param("cap-" + marker + "-%")
                    .update();
        }
        // The same for confirmation emails.
        try {
            owner.sql("""
                            INSERT INTO notification_outbox (id, channel, recipient, template_key, status, idempotency_key, created_at)
                            SELECT gen_random_uuid(), 'email', 'cap@example.test', 'onboarding.verify_email', 'sent', ? || g, ?
                              FROM generate_series(1, 5) g
                            """).params("test.cap:" + marker + ":", at).update();
            String email = Api.email("capped");
            capped.submit(request(email));
            assertThat(outboxRows("onboarding.verify_email", email)).isZero();
            assertThat(owner.sql("SELECT count(*) FROM onboarding_applications WHERE contact_email = ?")
                            .param(email)
                            .query(Long.class)
                            .single())
                    .isZero();
        } finally {
            owner.sql("DELETE FROM notification_outbox WHERE idempotency_key LIKE ?")
                    .param("test.cap:" + marker + ":%")
                    .update();
        }
        // Below the caps the same service creates and sends.
        String email = Api.email("uncapped");
        capped.submit(request(email));
        assertThat(outboxRows("onboarding.verify_email", email)).isEqualTo(1);
    }

    static SignUpController.SignUpRequest request(String email) {
        return new SignUpController.SignUpRequest(
                "Test Capped Shop",
                "Test Applicant 01",
                email,
                "0700000001",
                "UG",
                List.of("retail"),
                "monthly",
                "trial",
                null,
                null,
                null);
    }

    /** The in-memory buckets are bounded: a flood of addresses never grows them past the cap. */
    @Test
    void theRateLimiterIsBounded() {
        SignUpRateLimiter limiter = new SignUpRateLimiter(new com.rincoltech.bms.kernel.BusinessClock(
                java.time.Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), java.time.ZoneOffset.UTC)));
        for (int i = 0; i < SignUpRateLimiter.MAX_KEYS + 500; i++) {
            limiter.tryAcquire(SignUpRateLimiter.CREATE_PER_IP, "10.0." + i);
        }
        assertThat(limiter.size()).isEqualTo(SignUpRateLimiter.MAX_KEYS);
    }

    /** A malformed, unknown or replaced token gets one answer. */
    @Test
    void anUnknownLinkIsRefusedWithOneAnswer() {
        ResponseEntity<JsonNode> malformed = publicPost("/api/v1/platform/sign-up/status", Map.of("token", "abc"));
        ResponseEntity<JsonNode> unknown =
                publicPost("/api/v1/platform/sign-up/verify", Map.of("token", ApplicationService.token()));
        assertThat(malformed.getStatusCode()).isEqualTo(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(malformed.getBody().get("code").asString())
                .isEqualTo(unknown.getBody().get("code").asString())
                .isEqualTo("link_invalid");
    }

    // ---- Operator decisions -------------------------------------------------------------------

    /** FR-ONB-04, FR-ONB-06: Needs info, the applicant's answer, Verify, Reject and refused moves. */
    @Test
    void theOperatorAsksForInformationVerifiesAndRejects() {
        String email = Api.email("decide");
        UUID id = submitted(email, "Test Decide Shop " + email, phone());
        String base = "/api/v1/platform/applications/" + id;

        assertThat(platform.post(base + "/needs-info", Map.of("note", " "), operator.accessToken())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("validation_failed");
        ResponseEntity<JsonNode> asked = platform.post(
                base + "/needs-info", Map.of("note", "Which town is the shop in?"), operator.accessToken());
        assertThat(asked.getBody().get("application").get("status").asString()).isEqualTo("needs_info");
        String token = tokenOf(outboxParams("onboarding.needs_info", email).get("link"));
        JsonNode page = publicPost("/api/v1/platform/sign-up/status", Map.of("token", token))
                .getBody();
        assertThat(page.get("operator_note").asString()).isEqualTo("Which town is the shop in?");

        ResponseEntity<JsonNode> replied = publicPost(
                "/api/v1/platform/sign-up/reply", Map.of("token", token, "reply", "Test Town, Main Street."));
        assertThat(replied.getBody().get("status").asString()).isEqualTo("submitted");
        assertThat(publicPost("/api/v1/platform/sign-up/reply", Map.of("token", token, "reply", "Again."))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("application_state");
        assertThat(platform.get(base, operator.accessToken())
                        .getBody()
                        .get("application")
                        .get("applicant_reply")
                        .asString())
                .isEqualTo("Test Town, Main Street.");

        JsonNode verified = platform.post(base + "/verify", Map.of(), operator.accessToken())
                .getBody();
        assertThat(verified.get("application").get("status").asString()).isEqualTo("verified");
        assertThat(verified.get("application").get("decided_by").asString()).isEqualTo(operatorId.toString());
        assertThat(platformAudits("platform.application.verified", id)).isEqualTo(1);
        // Verify twice: refused, nothing changes.
        ResponseEntity<JsonNode> again = platform.post(base + "/verify", Map.of(), operator.accessToken());
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("code").asString()).isEqualTo("application_state");

        JsonNode rejected = platform.post(
                        base + "/reject",
                        Map.of("note", "Test reason: outside the service area."),
                        operator.accessToken())
                .getBody();
        assertThat(rejected.get("application").get("status").asString()).isEqualTo("rejected");
        String rejectToken = tokenOf(outboxParams("onboarding.rejected", email).get("link"));
        assertThat(publicPost("/api/v1/platform/sign-up/status", Map.of("token", rejectToken))
                        .getBody()
                        .get("reject_reason")
                        .asString())
                .isEqualTo("Test reason: outside the service area.");
        // A rejected application cannot be verified or activated, and the email may apply again.
        assertThat(platform.post(base + "/verify", Map.of(), operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(activate(id, activation(slug(), "trial", null))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("application_not_verified");
        assertThat(apply(form(email, "Test Decide Shop " + email, phone())).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(owner().sql("SELECT count(*) FROM onboarding_applications WHERE contact_email = ?")
                        .param(email)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThat(platform.post(
                                "/api/v1/platform/applications/" + UUID.randomUUID() + "/verify",
                                Map.of(),
                                operator.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** FR-ONB-05: the operator is warned of a possible repeat by phone, email or business name. */
    @Test
    void possibleDuplicatesAreShownToTheOperator() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String phone = phone();
        UUID first = submitted(Api.email("dup"), "Test Duplicate " + suffix + " Ltd", phone);
        UUID second = submitted(Api.email("dup"), "test duplicate  " + suffix + " limited.", phone);
        UUID third = submitted(Api.email("dup"), "Test Duplicate " + suffix + " Company", phone());

        JsonNode detail = platform.get("/api/v1/platform/applications/" + second, operator.accessToken())
                .getBody();
        JsonNode duplicates = detail.get("possible_duplicates");
        assertThat(duplicates.toString()).contains(first.toString()).contains(third.toString());
        for (JsonNode d : duplicates) {
            if (d.get("id").asString().equals(first.toString())) {
                assertThat(d.get("matched_on").toString()).contains("phone").contains("business_name");
            }
            if (d.get("id").asString().equals(third.toString())) {
                assertThat(d.get("matched_on").toString())
                        .contains("business_name")
                        .doesNotContain("phone");
            }
        }
        assertThat(detail.get("suggested_slug").asString()).startsWith("test-duplicate-" + suffix);
    }

    // ---- Activate -------------------------------------------------------------------------------

    /**
     * FR-ONB-07: Activate creates exactly one tenant through the platform function with the
     * applicant as invited admin, records who and when, audits with the email masked, and queues
     * the activation email in the same transaction; the link works; a repeat does nothing.
     */
    @Test
    void activateCreatesOneTenantWithAWorkingInvitationAndIsIdempotent() {
        String email = Api.email("activate");
        UUID id = verified(email, "Test Activate Shop " + email);
        String slug = slug();

        ResponseEntity<JsonNode> done = activate(id, activation(slug, "trial", null));
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = done.getBody();
        assertThat(body.get("activated_now").asBoolean()).isTrue();
        assertThat(body.get("tenant_slug").asString()).isEqualTo(slug);
        UUID tenantId = UUID.fromString(body.get("tenant_id").asString());
        JsonNode application = body.get("application");
        assertThat(application.get("status").asString()).isEqualTo("activated");
        assertThat(application.get("activated_by").asString()).isEqualTo(operatorId.toString());
        assertThat(application.get("activated_at").isNull()).isFalse();
        assertThat(application.get("activation_way").asString()).isEqualTo("trial");

        JdbcClient owner = owner();
        assertThat(owner.sql("SELECT count(*) FROM tenants WHERE slug = ?")
                        .param(slug)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(owner.sql("SELECT status FROM subscriptions WHERE tenant_id = ?")
                        .param(tenantId)
                        .query(String.class)
                        .single())
                .isEqualTo("trial");
        assertThat(owner.sql("SELECT module_key FROM tenant_modules WHERE tenant_id = ?")
                        .param(tenantId)
                        .query(String.class)
                        .list())
                .containsExactly("retail");
        assertThat(owner.sql("""
                                SELECT count(*) FROM users u JOIN user_role_assignments a ON a.user_id = u.id
                                 WHERE u.tenant_id = ? AND u.email = ? AND u.status = 'invited' AND a.role_key = 'tenant_admin'
                                """).params(tenantId, email).query(Long.class).single())
                .isEqualTo(1);
        assertThat(platformAudits("platform.application.activated", id)).isEqualTo(1);
        String auditData = owner.sql(
                        "SELECT data::text FROM platform_audit_log WHERE action = 'platform.application.activated' AND data->>'application_id' = ?")
                .param(id.toString())
                .query(String.class)
                .single();
        assertThat(auditData).doesNotContain(email).contains(email.substring(email.length() - 4));
        assertThat(owner.sql(
                                "SELECT count(*) FROM platform_audit_log WHERE action = 'platform.tenant.created' AND tenant_id = ?")
                        .param(tenantId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        // The activation email is queued with the tenant host link, and the link works.
        Map<String, String> params = outboxParams("onboarding.activation", email);
        assertThat(params.get("link"))
                .isEqualTo(body.get("admin_invitation_url").asString())
                .startsWith("https://" + slug + "-bms-staging.rincoltech.test/accept-invitation#token=");
        assertThat(params.get("sign_in_url")).isEqualTo("https://" + slug + "-bms-staging.rincoltech.test/sign-in");
        Api tenant = Api.tenant(http, slug);
        // Accepting signs the new admin in through the sign-in rules (ADR-025): the role still
        // requires a second factor, so the answer is enrolment, not a session.
        ResponseEntity<JsonNode> accepted = tenant.post(
                "/api/v1/auth/staff/invitations/accept",
                Map.of("token", tokenOf(params.get("link")), "password", Api.PASSWORD),
                null);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accepted.getBody().get("status").asString()).isEqualTo("mfa_enrolment_required");
        assertThat(tenant.login(email, Api.PASSWORD).getBody().get("status").asString())
                .isEqualTo("mfa_enrolment_required");

        // A repeat does nothing: no second tenant, no second email, no second audit row.
        ResponseEntity<JsonNode> repeat = activate(id, activation(slug, "paid", "Test payment note"));
        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(repeat.getBody().get("activated_now").asBoolean()).isFalse();
        assertThat(repeat.getBody().get("tenant_id").asString()).isEqualTo(tenantId.toString());
        assertThat(repeat.getBody().get("admin_invitation_url").isNull()).isTrue();
        assertThat(outboxRows("onboarding.activation", email)).isEqualTo(1);
        assertThat(platformAudits("platform.application.activated", id)).isEqualTo(1);
        assertThat(owner.sql("SELECT count(*) FROM tenants WHERE name = ?")
                        .param("Test Activate Shop " + email)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(owner.sql("SELECT activation_way FROM onboarding_applications WHERE id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("trial");
    }

    /** FR-ONB-07: two concurrent activations create one tenant. */
    @Test
    void concurrentActivationsCreateOneTenant() throws Exception {
        String email = Api.email("race");
        UUID id = verified(email, "Test Race Shop " + email);
        String slug = slug();
        var statuses = Api.race(4, () -> activate(id, activation(slug, "trial", null)));
        assertThat(Api.count(statuses, HttpStatus.OK)).isEqualTo(4);
        assertThat(owner().sql("SELECT count(*) FROM tenants WHERE name = ?")
                        .param("Test Race Shop " + email)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(outboxRows("onboarding.activation", email)).isEqualTo(1);
    }

    /** FR-ONB-07: the paid way needs the payment note and starts the subscription active. */
    @Test
    void thePaidWayRecordsThePaymentNoteAndStartsActive() {
        String email = Api.email("paid");
        UUID id = verified(email, "Test Paid Shop " + email);
        assertThat(activate(id, activation(slug(), "paid", " ")).getBody().toString())
                .contains("payment_note");
        assertThat(activate(id, activation("Bad Slug!", "trial", null))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("validation_failed");
        assertThat(activate(id, activation("admin", "trial", null))
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("validation_failed");
        Map<String, Object> unknownModule = activation(slug(), "trial", null);
        unknownModule.put("modules", List.of("hospitality"));
        assertThat(activate(id, unknownModule).getBody().get("code").asString()).isEqualTo("validation_failed");
        // A refused activation changed nothing.
        assertThat(platform.get("/api/v1/platform/applications/" + id, operator.accessToken())
                        .getBody()
                        .get("application")
                        .get("status")
                        .asString())
                .isEqualTo("verified");

        String slug = slug();
        ResponseEntity<JsonNode> done = activate(id, activation(slug, "paid", "Test MoMo ref TEST-0001, 1 Oct 2026"));
        assertThat(done.getStatusCode()).as("%s", done.getBody()).isEqualTo(HttpStatus.OK);
        UUID tenantId = UUID.fromString(done.getBody().get("tenant_id").asString());
        assertThat(owner().sql("SELECT status FROM subscriptions WHERE tenant_id = ?")
                        .param(tenantId)
                        .query(String.class)
                        .single())
                .isEqualTo("active");
        assertThat(done.getBody().get("application").get("activation_note").asString())
                .isEqualTo("Test MoMo ref TEST-0001, 1 Oct 2026");
        assertThat(owner().sql(
                                "SELECT data->>'payment_note_recorded' FROM platform_audit_log WHERE action = 'platform.application.activated' AND tenant_id = ?")
                        .param(tenantId)
                        .query(String.class)
                        .single())
                .isEqualTo("true");
    }

    /** FR-ONB-07: a taken slug is refused and nothing is half created. */
    @Test
    void aTakenSlugRollsTheWholeActivationBack() {
        String email = Api.email("taken");
        UUID id = verified(email, "Test Taken Shop " + email);
        TestDatabase.Fixture existing = TestDatabase.tenant("onb-taken", false);
        ResponseEntity<JsonNode> refused = activate(id, activation(existing.slug(), "trial", null));
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("duplicate_slug");
        assertThat(owner().sql("SELECT status FROM onboarding_applications WHERE id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("verified");
        assertThat(outboxRows("onboarding.activation", email)).isZero();
    }

    // ---- Permissions, host rules and the database ----------------------------------------------

    /** NFR-SEC-04, ADR-016: no tenant token, no anonymous caller and no tenant host reaches the queue. */
    @Test
    void onlyPlatformOperatorsOnThePlatformHostReachTheQueue() {
        TestDatabase.Fixture t = TestDatabase.tenant("onb-staff", false);
        String staffEmail = Api.email("staff");
        UUID staffId = Api.staff(t, staffEmail, new Api.Role("tenant_admin", null));
        Api tenant = Api.tenant(http, t.slug());
        Session staff = tenant.signIn(staffId, staffEmail, Api.PASSWORD, null);
        String id = UUID.randomUUID().toString();
        List<String[]> routes = List.of(
                new String[] {"GET", "/api/v1/platform/applications"},
                new String[] {"GET", "/api/v1/platform/applications/" + id},
                new String[] {"POST", "/api/v1/platform/applications/" + id + "/verify"},
                new String[] {"POST", "/api/v1/platform/applications/" + id + "/needs-info"},
                new String[] {"POST", "/api/v1/platform/applications/" + id + "/reject"},
                new String[] {"POST", "/api/v1/platform/applications/" + id + "/activate"},
                new String[] {"GET", "/api/v1/platform/outbox"},
                new String[] {"POST", "/api/v1/platform/outbox/" + id + "/retry"});
        Map<String, String> dev = Map.of(
                "X-Dev-User-Id", UUID.randomUUID().toString(),
                "X-Dev-Permissions", "platform.tenants.read,platform.tenants.manage",
                "X-Dev-Branch-Ids", "*");
        for (String[] route : routes) {
            HttpMethod method = HttpMethod.valueOf(route[0]);
            Object body = method == HttpMethod.POST ? Map.of("note", "x") : null;
            assertThat(platform.call(method, route[1], body, staff.accessToken())
                            .getStatusCode())
                    .as("staff token on %s", route[1])
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(platform.call(method, route[1], body, null).getStatusCode())
                    .as("anonymous on %s", route[1])
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(platform.call(method, route[1], body, null, dev).getStatusCode())
                    .as("development headers on %s", route[1])
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(platform.onHost(t.slug() + "-bms-staging.rincoltech.test")
                            .call(method, route[1], body, operator.accessToken())
                            .getStatusCode())
                    .as("tenant host on %s", route[1])
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
        // The public endpoints exist only on the platform host too.
        assertThat(platform.onHost(t.slug() + "-bms-staging.rincoltech.test")
                        .call(HttpMethod.POST, "/api/v1/platform/sign-up/status", Map.of("token", "x"), null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** ADR-016, spec section 12: bms_app has no privilege on the tables, only the functions. */
    @Test
    void theApplicationRoleReachesTheTablesOnlyThroughTheDefinerFunctions() {
        JdbcClient app = JdbcClient.create(TestDatabase.appDataSource());
        for (String table : List.of("onboarding_applications", "notification_outbox")) {
            assertThatThrownBy(() -> app.sql("SELECT count(*) FROM " + table)
                            .query(Long.class)
                            .single())
                    .as("direct read of %s", table)
                    .hasStackTraceContaining("permission denied");
            assertThatThrownBy(() -> app.sql("DELETE FROM " + table).update())
                    .as("direct delete on %s", table)
                    .hasStackTraceContaining("permission denied");
            assertThat(owner().sql(
                                    "SELECT relrowsecurity FROM pg_class WHERE relname = ? AND relnamespace = 'public'::regnamespace")
                            .param(table)
                            .query(Boolean.class)
                            .single())
                    .isTrue();
            assertThat(owner().sql(
                                    "SELECT relforcerowsecurity FROM pg_class WHERE relname = ? AND relnamespace = 'public'::regnamespace")
                            .param(table)
                            .query(Boolean.class)
                            .single())
                    .as("%s has FORCE ROW LEVEL SECURITY (review N9)", table)
                    .isTrue();
        }
        List<String> functions = owner().sql("""
                        SELECT p.proname FROM pg_proc p
                         WHERE p.pronamespace = 'public'::regnamespace
                           AND (p.proname LIKE 'onboarding\\_application%' OR p.proname LIKE 'notification\\_outbox\\_%')
                        """).query(String.class).list();
        assertThat(functions).hasSizeGreaterThanOrEqualTo(19);
        for (String function : functions) {
            Map<String, Object> row = owner().sql("""
                            SELECT p.prosecdef, pg_get_userbyid(p.proowner) AS owner,
                                   has_function_privilege('bms_app', p.oid, 'EXECUTE') AS app,
                                   coalesce(array_to_string(p.proacl, ','), '') AS acl
                              FROM pg_proc p WHERE p.proname = ? AND p.pronamespace = 'public'::regnamespace
                            """).param(function).query().singleRow();
            assertThat(row.get("prosecdef"))
                    .as("%s is SECURITY DEFINER", function)
                    .isEqualTo(true);
            assertThat(row.get("owner")).as("%s owner", function).isEqualTo("bms_owner");
            assertThat(row.get("app")).as("%s executable by bms_app", function).isEqualTo(true);
            // A PUBLIC entry in an ACL has an empty grantee: "=X/owner".
            assertThat(List.of(((String) row.get("acl")).split(",")))
                    .as("%s not executable by PUBLIC", function)
                    .noneMatch(entry -> entry.startsWith("="));
        }
    }

    /** FR-ONB-09: unverified after 14 days expires; rejected and expired are deleted after 90 days. */
    @Test
    void housekeepingExpiresAndDeletes() {
        String unverified = Api.email("stale");
        apply(form(unverified, "Test Stale Shop", phone()));
        UUID stale = applicationId(unverified);
        owner().sql("UPDATE onboarding_applications SET created_at = now() - interval '15 days' WHERE id = ?")
                .param(stale)
                .update();
        String recentEmail = Api.email("recent");
        apply(form(recentEmail, "Test Recent Shop", phone()));
        UUID recent = applicationId(recentEmail);

        String oldEmail = Api.email("old");
        UUID old = submitted(oldEmail, "Test Old Shop", phone());
        platform.post(
                "/api/v1/platform/applications/" + old + "/reject",
                Map.of("note", "Test reason."),
                operator.accessToken());
        owner().sql("UPDATE onboarding_applications SET closed_at = now() - interval '91 days' WHERE id = ?")
                .param(old)
                .update();
        String youngEmail = Api.email("young");
        UUID young = submitted(youngEmail, "Test Young Shop", phone());
        platform.post(
                "/api/v1/platform/applications/" + young + "/reject",
                Map.of("note", "Test reason."),
                operator.accessToken());

        service.housekeeping();

        assertThat(statusOf(stale)).isEqualTo("expired");
        assertThat(statusOf(recent)).isEqualTo("submitted");
        assertThat(owner().sql("SELECT count(*) FROM onboarding_applications WHERE id = ?")
                        .param(old)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(statusOf(young)).isEqualTo("rejected");
        // An expired application frees the email for a new one.
        assertThat(apply(form(unverified, "Test Stale Shop", phone())).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(owner().sql("SELECT count(*) FROM onboarding_applications WHERE contact_email = ?")
                        .param(unverified)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    String statusOf(UUID id) {
        return owner().sql("SELECT status FROM onboarding_applications WHERE id = ?")
                .param(id)
                .query(String.class)
                .single();
    }

    /** Chapter 8 section 8.9: no token, link or address reaches the audit tables. */
    @Test
    void noTokenOrAddressReachesTheAuditLog() {
        String email = Api.email("mask");
        UUID id = submitted(email, "Test Mask Shop " + email, phone());
        String token = tokenOf(outboxParams("onboarding.verify_email", email).get("link"));
        assertThat(owner().sql("SELECT count(*) FROM platform_audit_log WHERE data::text LIKE ? OR data::text LIKE ?")
                        .params("%" + token + "%", "%" + email + "%")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(platformAudits("platform.application.submitted", id)).isEqualTo(1);
    }
}
