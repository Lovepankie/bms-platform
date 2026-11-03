package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The outbox (FR-NTF-01, FR-NTF-09 to FR-NTF-11, spec section 11): rows written in the cause's
 * transaction, at least once delivery with the idempotency key, SKIP LOCKED claims, three failures
 * then {@code failed} and visible in the portal, and senders that stay off when unconfigured.
 * Real senders are never called: fake channels stand in for SMTP and Telegram.
 */
class OutboxIT extends IntegrationTest {

    @Autowired
    Outbox outbox;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    TestRestTemplate http;

    /** A fake channel; fails the first {@code failures} sends. */
    static final class Fake implements OutboxChannel {
        final String name;
        int failures;
        final List<Delivery> sent = Collections.synchronizedList(new ArrayList<>());
        final List<String> tried = Collections.synchronizedList(new ArrayList<>());

        Fake(String name, int failures) {
            this.name = name;
            this.failures = failures;
        }

        @Override
        public String channel() {
            return name;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Result send(Delivery delivery) throws SendFailure {
            tried.add(delivery.idempotencyKey());
            if (failures > 0) {
                failures--;
                throw new SendFailure("fake provider down");
            }
            sent.add(delivery);
            return Result.SENT;
        }
    }

    String key() {
        return "test.outbox:" + UUID.randomUUID();
    }

    void enqueue(String channel, String recipient, String key) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> outbox.enqueue(new Outbox.Message(
                        channel,
                        recipient,
                        "onboarding.operator_alert",
                        Map.of(
                                "reference", "A-TEST0001",
                                "business_name", "Test Outbox Shop",
                                "modules", "retail",
                                "portal_url", "https://localhost/platform/applications/x"),
                        key)));
    }

    Map<String, Object> row(String key) {
        return TestDatabase.owner()
                .sql(
                        "SELECT status, attempts, params::text AS params, last_error FROM notification_outbox WHERE idempotency_key = ?")
                .param(key)
                .query()
                .singleRow();
    }

    /** The test profile has no sender variables: both senders are off and rows stay pending. */
    @Test
    void unconfiguredSendersAreOffAndRowsStayPending() {
        assertThat(dispatcher.enabledChannels()).isEmpty();
        String key = key();
        enqueue("email", "test-op@example.test", key);
        assertThat(dispatcher.dispatch(10)).isZero();
        assertThat(row(key).get("status")).isEqualTo("pending");
        assertThat(new NotificationProperties(null, null, null).smtp().configured())
                .isFalse();
        assertThat(new NotificationProperties(null, null, null).telegram().configured())
                .isFalse();
        assertThat(new NotificationProperties.Smtp("smtp.example.test", null, "u", "secret-value", "a@example.test")
                        .toString())
                .doesNotContain("secret-value");
        assertThat(new NotificationProperties.Telegram("123:secret-token", "42").toString())
                .doesNotContain("secret-token");
        assertThat(new NotificationProperties.Smtp(null, null, null, null, null).port())
                .isEqualTo(465);
    }

    /** FR-NTF-01: no transaction, no row; the same key twice keeps one row. */
    @Test
    void enqueueNeedsATransactionAndTheKeyIsUnique() {
        String key = key();
        assertThatThrownBy(() -> outbox.enqueue(
                        new Outbox.Message("email", "a@example.test", "onboarding.operator_alert", Map.of(), key)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        enqueue("email", "test-op@example.test", key);
        enqueue("email", "test-op@example.test", key);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox WHERE idempotency_key = ?")
                        .param(key)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // A template that cannot render is refused in the cause's transaction.
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(status -> outbox.enqueue(new Outbox.Message(
                                "email", "a@example.test", "onboarding.operator_alert", Map.of(), key()))))
                .hasMessageContaining("missing template parameter");
        // A rolled back cause leaves no row.
        String rolledBack = key();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            enqueue("telegram", "operator", rolledBack);
            status.setRollbackOnly();
        });
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox WHERE idempotency_key = ?")
                        .param(rolledBack)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /**
     * Issue #333 (ADR-117): with the provider {@code resend} the SMTP sender is off and the dispatcher sends an email row
     * through Resend, whichever of the two {@code email} senders is listed first.
     */
    @Test
    void withResendChosenAnEmailRowReachesResendThroughTheDispatcher() throws Exception {
        // JUSTIFICATION-A3: the routing test the #759 review asked for, through the real dispatcher.
        List<String> keys = Collections.synchronizedList(new ArrayList<>());
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            exchange.getRequestBody().readAllBytes();
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] out = "{\"id\":\"4ef9a417-02e9-4d39-ad75-9611e0fcc33c\"}".getBytes();
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            NotificationProperties properties = new NotificationProperties(
                    new NotificationProperties.Smtp(
                            "smtp.example.test", null, "u", "fabricated-01", "no-reply@example.test"),
                    null,
                    "",
                    new NotificationProperties.Mail(
                            "resend",
                            "re_fabricated_test_key_0002",
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            null));
            SmtpEmailChannel smtp = new SmtpEmailChannel(
                    properties, new OutboxTemplates(), EmailLayoutTest.HOSTS, null, null, null, null, "");
            ResendEmailChannel resend = new ResendEmailChannel(
                    smtp,
                    properties,
                    new tools.jackson.databind.ObjectMapper(),
                    new com.rincoltech.bms.kernel.BusinessClock(java.time.Clock.systemUTC()),
                    "");
            assertThat(smtp.enabled()).isFalse();
            assertThat(resend.enabled()).isTrue();
            String key = key();
            enqueue("email", "test-op@example.test", key);

            dispatcher.dispatch(List.of(smtp, resend), 1000);

            assertThat(keys).anyMatch(k -> k.startsWith("bms:local:") && k.endsWith(":" + key));
            assertThat(row(key).get("status")).isEqualTo("sent");

            // JUSTIFICATION-A3: the second provider of the routing test, as the #759 lead asked.
            // With smtp chosen, Resend is off and the row goes to the SMTP sender, whichever is listed first. Port 1 on
            // this machine refuses at once, so the try fails as SMTP's (a mail class), never as Resend's.
            NotificationProperties smtpChosen = new NotificationProperties(
                    new NotificationProperties.Smtp("127.0.0.1", 1, "u", "fabricated-01", "no-reply@example.test"),
                    null,
                    "",
                    new NotificationProperties.Mail(
                            "smtp",
                            "re_fabricated_test_key_0002",
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            null));
            SmtpEmailChannel smtpOn = new SmtpEmailChannel(
                    smtpChosen, new OutboxTemplates(), EmailLayoutTest.HOSTS, null, null, null, null, "");
            ResendEmailChannel resendOff = new ResendEmailChannel(
                    smtpOn,
                    smtpChosen,
                    new tools.jackson.databind.ObjectMapper(),
                    new com.rincoltech.bms.kernel.BusinessClock(java.time.Clock.systemUTC()),
                    "");
            assertThat(smtpOn.enabled()).isTrue();
            assertThat(resendOff.enabled()).isFalse();
            String viaSmtp = key();
            enqueue("email", "test-op@example.test", viaSmtp);
            int before = keys.size();

            dispatcher.dispatch(List.of(resendOff, smtpOn), 1000);

            assertThat(keys).hasSize(before);
            Map<String, Object> tried = row(viaSmtp);
            assertThat(tried.get("attempts")).isEqualTo(1);
            assertThat(String.valueOf(tried.get("last_error"))).doesNotContain("resend");
        } finally {
            server.stop(0);
        }
    }

    /**
     * Issue #333 (ADR-117): a 429 from Resend gives the attempt back. The row waits for Retry-After, and the next run sends
     * it, so a burst of rate limits never uses up a reset email's three tries.
     */
    @Test
    void aRateLimitFromResendGivesTheAttemptBackAndTheRowIsSentLater() throws Exception {
        // JUSTIFICATION-A3: the dispatcher-level proof the second #759 review asked for.
        String key = key();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            exchange.getRequestBody().readAllBytes();
            // Only this row is rate limited, once; other tests' rows that the run claims are accepted.
            boolean ours = String.valueOf(exchange.getRequestHeaders().getFirst("Idempotency-Key"))
                    .endsWith(":" + key);
            boolean first = ours && calls.getAndIncrement() == 0;
            byte[] out = (first
                            ? "{\"name\":\"rate_limit_exceeded\"}"
                            : "{\"id\":\"4ef9a417-02e9-4d39-ad75-9611e0fcc33c\"}")
                    .getBytes();
            if (first) {
                exchange.getResponseHeaders().add("Retry-After", "60");
            }
            exchange.sendResponseHeaders(first ? 429 : 200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            NotificationProperties properties = new NotificationProperties(
                    new NotificationProperties.Smtp(null, null, null, null, "no-reply@example.test"),
                    null,
                    "",
                    new NotificationProperties.Mail(
                            "resend",
                            "re_fabricated_test_key_0003",
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            null));
            SmtpEmailChannel smtp = new SmtpEmailChannel(
                    properties, new OutboxTemplates(), EmailLayoutTest.HOSTS, null, null, null, null, "");
            ResendEmailChannel resend = new ResendEmailChannel(
                    smtp,
                    properties,
                    new tools.jackson.databind.ObjectMapper(),
                    new com.rincoltech.bms.kernel.BusinessClock(java.time.Clock.systemUTC()),
                    "");
            enqueue("email", "test-op@example.test", key);
            // Other tests' email rows may be claimed first: run until this row has had its 429.
            for (int i = 0; i < 20 && calls.get() == 0; i++) {
                dispatcher.dispatch(List.of(resend), 1);
            }
            Map<String, Object> waiting = row(key);
            assertThat(waiting.get("status")).isEqualTo("pending");
            assertThat(waiting.get("attempts")).isEqualTo(0);
            Boolean later = TestDatabase.owner()
                    .sql(
                            "SELECT next_attempt_at > now() + interval '30 seconds' FROM notification_outbox WHERE idempotency_key = ?")
                    .param(key)
                    .query(Boolean.class)
                    .single();
            assertThat(later).isTrue();

            TestDatabase.owner()
                    .sql(
                            "UPDATE notification_outbox SET next_attempt_at = now() - interval '1 second' WHERE idempotency_key = ?")
                    .param(key)
                    .update();
            dispatcher.dispatch(List.of(resend), 1000);

            Map<String, Object> sent = row(key);
            assertThat(sent.get("status")).isEqualTo("sent");
            assertThat(sent.get("attempts")).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Autowired
    EmailSuppressions suppressions;

    /**
     * Issue #333 (ADR-117 decision 9): an address suppressed after a complaint is refused by both senders with
     * {@code recipient_suppressed}, so the row is failed on "Messages not sent" and nothing reaches Resend; once an operator
     * clears it, a new row to it is sent.
     */
    @Test
    void aSuppressedAddressIsRefusedAndShownAsNotSentUntilAnOperatorClearsIt() throws Exception {
        // JUSTIFICATION-A3: the refusal and clear through the dispatcher, the third #759 review's must-fix 2.
        String to = "test-suppressed-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
        TestDatabase.owner()
                .sql("INSERT INTO platform_audit_log (id, action, data) VALUES (?, 'platform.email.complained', "
                        + "jsonb_build_object('recipient_hash', ?::text))")
                .params(UUID.randomUUID(), suppressions.hash(to))
                .update();
        List<String> keys = Collections.synchronizedList(new ArrayList<>());
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", exchange -> {
            exchange.getRequestBody().readAllBytes();
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] out = "{\"id\":\"4ef9a417-02e9-4d39-ad75-9611e0fcc33c\"}".getBytes();
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        try {
            NotificationProperties properties = new NotificationProperties(
                    new NotificationProperties.Smtp(null, null, null, null, "no-reply@example.test"),
                    null,
                    "",
                    new NotificationProperties.Mail(
                            "resend",
                            "re_fabricated_test_key_0004",
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            null));
            SmtpEmailChannel smtp = new SmtpEmailChannel(
                    properties, new OutboxTemplates(), EmailLayoutTest.HOSTS, null, null, null, null, suppressions, "");
            ResendEmailChannel resend = new ResendEmailChannel(
                    smtp,
                    properties,
                    new tools.jackson.databind.ObjectMapper(),
                    new com.rincoltech.bms.kernel.BusinessClock(java.time.Clock.systemUTC()),
                    "");
            String refused = key();
            enqueue("email", to, refused);

            dispatcher.dispatch(List.of(resend), 1000);

            Map<String, Object> row = row(refused);
            assertThat(row.get("status")).isEqualTo("failed");
            assertThat(row.get("last_error")).isEqualTo("recipient_suppressed");
            assertThat(keys).noneMatch(k -> k.endsWith(":" + refused));
            // The SMTP sender refuses the same address before it connects (no SMTP host is set here).
            assertThat(smtp.send(new OutboxChannel.Delivery(to, "s", "t", "k")))
                    .isEqualTo(OutboxChannel.Result.refused("recipient_suppressed"));

            Api platform = Api.platform(http);
            String opEmail = Api.email("operator");
            UUID opId = Api.platformUser(opEmail);
            Session op = platform.signIn(opId, opEmail, Api.PASSWORD, null);
            boolean shown = false;
            for (JsonNode f : platform.get("/api/v1/platform/outbox", op.accessToken())
                    .getBody()
                    .get("failures")) {
                shown |= "recipient_suppressed".equals(f.path("last_error").asString(""));
            }
            assertThat(shown).as("the refusal is on Messages not sent").isTrue();

            assertThat(platform.post("/api/v1/platform/outbox/unsuppress", Map.of("email", to), op.accessToken())
                            .getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            String again = key();
            enqueue("email", to, again);
            dispatcher.dispatch(List.of(resend), 1000);
            assertThat(row(again).get("status")).isEqualTo("sent");
            assertThat(keys).anyMatch(k -> k.endsWith(":" + again));
        } finally {
            server.stop(0);
        }
    }

    /**
     * Issue #333 (ADR-117 decision 1): with Resend chosen and SMTP still set, a row whose request could not reach Resend
     * goes by SMTP, is marked sent, and the platform audit log records that SMTP carried it.
     */
    @Test
    void aRowThatCannotReachResendGoesBySmtpAndTheSenderIsRecorded() throws Exception {
        // JUSTIFICATION-A3: the dispatcher-level proof of the fallback and of which sender sent (third #759 review, 3).
        int closed;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        NotificationProperties properties = new NotificationProperties(
                new NotificationProperties.Smtp(
                        "smtp.example.test", null, "u", "fabricated-01", "no-reply@example.test"),
                null,
                "",
                new NotificationProperties.Mail(
                        "resend", "re_fabricated_test_key_0005", "http://127.0.0.1:" + closed, null));
        List<String> carried = Collections.synchronizedList(new ArrayList<>());
        SmtpEmailChannel smtp =
                new SmtpEmailChannel(
                        properties,
                        new OutboxTemplates(),
                        EmailLayoutTest.HOSTS,
                        null,
                        null,
                        null,
                        null,
                        suppressions,
                        "") {
                    @Override
                    Result transmit(Delivery delivery) {
                        carried.add(delivery.idempotencyKey());
                        return Result.SENT;
                    }
                };
        ResendEmailChannel resend = new ResendEmailChannel(
                smtp,
                properties,
                new tools.jackson.databind.ObjectMapper(),
                new com.rincoltech.bms.kernel.BusinessClock(java.time.Clock.systemUTC()),
                "");
        String key = key();
        enqueue("email", "test-fallback@example.test", key);

        dispatcher.dispatch(List.of(resend), 1000);

        assertThat(carried).contains(key);
        assertThat(row(key).get("status")).isEqualTo("sent");
        String id = TestDatabase.owner()
                .sql("SELECT id::text FROM notification_outbox WHERE idempotency_key = ?")
                .param(key)
                .query(String.class)
                .single();
        assertThat(TestDatabase.owner()
                        .sql("SELECT data ->> 'via' FROM platform_audit_log"
                                + " WHERE action = 'platform.outbox.sent_via' AND data ->> 'outbox_id' = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("smtp");
    }

    /** FR-NTF-09: a sent row is marked sent and its parameters (which may hold a link) cleared. */
    @Test
    void aSentRowIsMarkedAndItsParametersCleared() {
        String key = key();
        enqueue("telegram", "operator", key);
        Fake telegram = new Fake("telegram", 0);
        dispatcher.dispatch(List.of(telegram), 1000);
        assertThat(telegram.sent.stream().map(OutboxChannel.Delivery::idempotencyKey))
                .contains(key);
        OutboxChannel.Delivery delivery = telegram.sent.stream()
                .filter(d -> d.idempotencyKey().equals(key))
                .findFirst()
                .orElseThrow();
        assertThat(delivery.text()).contains("Test Outbox Shop").contains("A-TEST0001");
        assertThat(delivery.toString()).doesNotContain("Test Outbox Shop");
        Map<String, Object> row = row(key);
        assertThat(row.get("status")).isEqualTo("sent");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("params")).isEqualTo("{}");
    }

    /**
     * FR-NTF-09: a failure is retried (at least once: the same key is offered again) and the third
     * failure marks the row failed; the portal lists it masked and can send it again.
     */
    @Test
    void threeFailuresMarkTheRowFailedAndThePortalShowsAndRetriesIt() {
        String key = key();
        String recipient = "test-fail-" + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
        enqueue("email", recipient, key);
        Fake email = new Fake("email", 1000);
        for (int attempt = 1; attempt <= 3; attempt++) {
            dispatcher.dispatch(List.of(email), 1000);
            Map<String, Object> row = row(key);
            assertThat(row.get("attempts")).isEqualTo(attempt);
            assertThat(row.get("status")).isEqualTo(attempt < 3 ? "pending" : "failed");
            assertThat((String) row.get("last_error")).isEqualTo("SendFailure: fake provider down");
            // The backoff holds the row back: make it due again for the next attempt.
            TestDatabase.owner()
                    .sql(
                            "UPDATE notification_outbox SET next_attempt_at = now() - interval '1 second' WHERE idempotency_key = ?")
                    .param(key)
                    .update();
        }
        assertThat(email.tried.stream().filter(key::equals).count()).isEqualTo(3);
        dispatcher.dispatch(List.of(email), 1000);
        assertThat(email.tried.stream().filter(key::equals).count()).isEqualTo(3);
        assertThat((String) row(key).get("params")).contains("Test Outbox Shop");

        Api platform = Api.platform(http);
        String opEmail = Api.email("operator");
        UUID opId = Api.platformUser(opEmail);
        Session op = platform.signIn(opId, opEmail, Api.PASSWORD, null);
        JsonNode status =
                platform.get("/api/v1/platform/outbox", op.accessToken()).getBody();
        assertThat(status.get("counts").get("failed").asLong()).isPositive();
        assertThat(status.get("enabled_channels").size()).isZero();
        JsonNode failure = null;
        for (JsonNode f : status.get("failures")) {
            if (f.get("recipient").asString().endsWith(recipient.substring(recipient.length() - 4))
                    && f.get("attempts").asInt() == 3
                    && f.get("channel").asString().equals("email")) {
                failure = f;
            }
        }
        assertThat(failure).isNotNull();
        assertThat(status.toString()).doesNotContain(recipient).doesNotContain("Test Outbox Shop");

        String id = TestDatabase.owner()
                .sql("SELECT id::text FROM notification_outbox WHERE idempotency_key = ?")
                .param(key)
                .query(String.class)
                .single();
        assertThat(platform.post("/api/v1/platform/outbox/" + id + "/retry", Map.of(), op.accessToken())
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(row(key).get("status")).isEqualTo("pending");
        assertThat(platform.post("/api/v1/platform/outbox/" + id + "/retry", Map.of(), op.accessToken())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("outbox_not_failed");
        Fake recovered = new Fake("email", 0);
        dispatcher.dispatch(List.of(recovered), 1000);
        assertThat(row(key).get("status")).isEqualTo("sent");
        assertThat(TestDatabase.owner()
                        .sql(
                                "SELECT count(*) FROM platform_audit_log WHERE action = 'platform.outbox.retried' AND data->>'outbox_id' = ?")
                        .param(id)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    /** FR-NTF-09: a row claimed by another sender is skipped, not waited for and not sent twice. */
    @Test
    void aRowLockedByAnotherSenderIsSkipped() throws Exception {
        String locked = key();
        enqueue("telegram", "operator", locked);
        String free = key();
        enqueue("telegram", "operator", free);
        // Another sender's transaction holds the first row, as a claim would.
        try (Connection other = TestDatabase.ownerDataSource().getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement ps =
                    other.prepareStatement("SELECT id FROM notification_outbox WHERE idempotency_key = ? FOR UPDATE")) {
                ps.setString(1, locked);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                }
            }
            Fake telegram = new Fake("telegram", 0);
            dispatcher.dispatch(List.of(telegram), 1000);
            assertThat(telegram.tried).doesNotContain(locked).contains(free);
            assertThat(row(locked).get("status")).isEqualTo("pending");
            other.rollback();
        }
        Fake telegram = new Fake("telegram", 0);
        dispatcher.dispatch(List.of(telegram), 1000);
        assertThat(telegram.tried).contains(locked).doesNotContain(free);
        assertThat(row(locked).get("status")).isEqualTo("sent");
    }

    /**
     * Review N3: the attempt is counted and leased when the row is claimed, in its own
     * transaction, so a send that dies with an Error (not an Exception) still uses up an attempt
     * and the row stays away for the lease instead of being claimed first again at once.
     */
    @Test
    void anAttemptThatDiesIsCountedAndTheRowIsLeased() {
        String key = key();
        enqueue("telegram", "operator", key);
        OutboxChannel dying = new OutboxChannel() {
            @Override
            public String channel() {
                return "telegram";
            }

            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public Result send(Delivery delivery) {
                if (delivery.idempotencyKey().equals(key)) {
                    throw new AssertionError("the send died");
                }
                return Result.SENT;
            }
        };
        assertThatThrownBy(() -> {
                    for (int i = 0; i < 10_000; i++) {
                        if (dispatcher.dispatch(List.of(dying), 1) == 0) {
                            break;
                        }
                    }
                })
                .isInstanceOf(AssertionError.class);
        Map<String, Object> row = TestDatabase.owner()
                .sql("SELECT status, attempts, next_attempt_at > now() + interval '1 minute' AS leased"
                        + " FROM notification_outbox WHERE idempotency_key = ?")
                .param(key)
                .query()
                .singleRow();
        assertThat(row.get("status")).isEqualTo("pending");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("leased")).isEqualTo(true);
        // Not due during the lease: another run does not pick it up.
        Fake telegram = new Fake("telegram", 0);
        dispatcher.dispatch(List.of(telegram), 1000);
        assertThat(telegram.tried).doesNotContain(key);

        // Round 2 review item 3: it keeps dying. The second and third deaths use up the attempts,
        // and the next claim marks the row failed instead of offering it a fourth time.
        for (int death = 2; death <= 3; death++) {
            TestDatabase.owner()
                    .sql(
                            "UPDATE notification_outbox SET next_attempt_at = now() - interval '1 second' WHERE idempotency_key = ?")
                    .param(key)
                    .update();
            assertThatThrownBy(() -> {
                        for (int i = 0; i < 10_000; i++) {
                            if (dispatcher.dispatch(List.of(dying), 1) == 0) {
                                break;
                            }
                        }
                    })
                    .isInstanceOf(AssertionError.class);
            assertThat(row(key).get("attempts")).isEqualTo(death);
        }
        TestDatabase.owner()
                .sql(
                        "UPDATE notification_outbox SET next_attempt_at = now() - interval '1 second' WHERE idempotency_key = ?")
                .param(key)
                .update();
        for (int i = 0; i < 10_000; i++) {
            if (dispatcher.dispatch(List.of(dying), 1) == 0) {
                break;
            }
        }
        Map<String, Object> done = row(key);
        assertThat(done.get("status")).isEqualTo("failed");
        assertThat(done.get("attempts")).isEqualTo(3);
        assertThat(done.get("last_error")).isEqualTo("attempts used up");
    }

    /**
     * Round 2 review item 5: a row whose own link has expired (an activation link lasts 72 hours)
     * is never sent and cannot be sent again, even inside the 7 day window.
     */
    @Test
    void aRowWhoseLinkExpiredIsNeitherSentNorRetried() {
        String key = key();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> outbox.enqueue(new Outbox.Message(
                        "telegram",
                        "operator",
                        "onboarding.operator_alert",
                        Map.of(
                                "reference", "A-TEST0002",
                                "business_name", "Test Expiry Shop",
                                "modules", "retail",
                                "portal_url", "https://localhost/platform/applications/x"),
                        key,
                        null,
                        java.time.Instant.now().minusSeconds(60))));
        Fake telegram = new Fake("telegram", 0);
        dispatcher.dispatch(List.of(telegram), 1000);
        assertThat(telegram.tried).doesNotContain(key);
        Map<String, Object> expired = row(key);
        assertThat(expired.get("status")).isEqualTo("failed");
        assertThat(expired.get("last_error")).isEqualTo("expired");

        String id = TestDatabase.owner()
                .sql("SELECT id::text FROM notification_outbox WHERE idempotency_key = ?")
                .param(key)
                .query(String.class)
                .single();
        Api platform = Api.platform(http);
        String opEmail = Api.email("operator");
        UUID opId = Api.platformUser(opEmail);
        Session op = platform.signIn(opId, opEmail, Api.PASSWORD, null);
        assertThat(platform.post("/api/v1/platform/outbox/" + id + "/retry", Map.of(), op.accessToken())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("outbox_too_old");
        assertThat(dispatcher.purge()).isPositive();
        assertThat(row(key).get("params")).isEqualTo("{}");
    }

    /**
     * Review N1: no one-time link outlives its use in the outbox. The nightly purge clears the
     * parameters of any unsent row older than 7 days (a pending one becomes failed, "expired") and
     * deletes rows sent more than 30 days ago; a failed row that old cannot be sent again.
     */
    @Test
    void oldLinksAreClearedAndCannotBeSentAgain() {
        String pending = key();
        enqueue("email", "test-old@example.test", pending);
        String failed = key();
        enqueue("email", "test-old@example.test", failed);
        String sent = key();
        enqueue("email", "test-old@example.test", sent);
        var owner = TestDatabase.owner();
        owner.sql(
                        "UPDATE notification_outbox SET created_at = now() - interval '8 days' WHERE idempotency_key IN (?, ?)")
                .params(pending, failed)
                .update();
        owner.sql(
                        "UPDATE notification_outbox SET status = 'failed', attempts = 3, failed_at = now() WHERE idempotency_key = ?")
                .param(failed)
                .update();
        owner.sql(
                        "UPDATE notification_outbox SET status = 'sent', sent_at = now() - interval '31 days' WHERE idempotency_key = ?")
                .param(sent)
                .update();

        String id = owner.sql("SELECT id::text FROM notification_outbox WHERE idempotency_key = ?")
                .param(failed)
                .query(String.class)
                .single();
        Api platform = Api.platform(http);
        String opEmail = Api.email("operator");
        UUID opId = Api.platformUser(opEmail);
        Session op = platform.signIn(opId, opEmail, Api.PASSWORD, null);
        ResponseEntity<JsonNode> tooOld =
                platform.post("/api/v1/platform/outbox/" + id + "/retry", Map.of(), op.accessToken());
        assertThat(tooOld.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(tooOld.getBody().get("code").asString()).isEqualTo("outbox_too_old");

        assertThat(dispatcher.purge()).isGreaterThanOrEqualTo(3);
        Map<String, Object> expired = row(pending);
        assertThat(expired.get("status")).isEqualTo("failed");
        assertThat(expired.get("last_error")).isEqualTo("expired");
        assertThat(expired.get("params")).isEqualTo("{}");
        assertThat(row(failed).get("params")).isEqualTo("{}");
        assertThat(owner.sql("SELECT count(*) FROM notification_outbox WHERE idempotency_key = ?")
                        .param(sent)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    /** Review N2: a malformed bot token switches the sender off instead of reaching any message. */
    @Test
    void aMalformedTelegramTokenSwitchesTheSenderOff() {
        tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
        String good = "123456789:" + "A".repeat(35);
        assertThat(new TelegramChannel(
                                new NotificationProperties(
                                        null, new NotificationProperties.Telegram(good, "-1001"), null),
                                mapper)
                        .enabled())
                .isTrue();
        for (String bad : List.of(good + "\r", good + " ", "123456789:short", "not a token")) {
            assertThat(new TelegramChannel(
                                    new NotificationProperties(
                                            null, new NotificationProperties.Telegram(bad, "-1001"), null),
                                    mapper)
                            .enabled())
                    .as("token %s", bad.length())
                    .isFalse();
        }
        assertThat(new TelegramChannel(
                                new NotificationProperties(
                                        null, new NotificationProperties.Telegram(good, "x y"), null),
                                mapper)
                        .enabled())
                .isFalse();
        // A JDK exception that quotes a URL is reduced to its class.
        assertThat(OutboxDispatcher.safeError(
                        new IllegalArgumentException("Illegal character in path: https://api.telegram.org/bot" + good)))
                .isEqualTo("IllegalArgumentException");
    }

    /** Every template renders with fabricated values; the SMTP Message-ID is stable per key. */
    @Test
    void templatesRenderAndTheMessageIdIsStable() {
        OutboxTemplates templates = new OutboxTemplates();
        Map<String, String> params = Map.ofEntries(
                Map.entry("contact_name", "Test Applicant 01"),
                Map.entry("business_name", "Test Shop"),
                Map.entry("link", "https://localhost/sign-up/verify#token=fabricated"),
                Map.entry("note", "Test note."),
                Map.entry("expires_at", "9 Oct 2026 08:00 UTC"),
                Map.entry("sign_in_url", "https://test-bms-staging.rincoltech.test/sign-in"),
                Map.entry("short_link", "rincoltech.test/testshop"),
                Map.entry("reference", "A-TEST0001"),
                Map.entry("modules", "retail"),
                Map.entry("portal_url", "https://localhost/platform/applications/x"),
                Map.entry("hour", "2026-10-06 08:00"),
                Map.entry("applications", "30"),
                Map.entry("emails", "60"),
                Map.entry("tenant_name", "Test Lender"),
                Map.entry("currency", "UGX"),
                Map.entry("amount", "50,000"),
                Map.entry("account_no", "SV000001"),
                Map.entry("receipt_no", "RC-HQ-000001"),
                Map.entry("balance", "75,000"),
                Map.entry("date", "6 Oct 2026"),
                Map.entry("loan_no", "LN-HQ-000123"),
                Map.entry("next_amount", "120,000"),
                Map.entry("next_due_date", "6 Nov 2026"),
                Map.entry("maturity_date", "6 Oct 2027"),
                Map.entry("voucher_no", "VC-HQ-000001"),
                Map.entry("due_date", "6 Nov 2026"),
                Map.entry("arrears", "120,000"),
                Map.entry("dpd", "7"),
                Map.entry("text", "Test digest line."),
                Map.entry("day", "2026-10-06"),
                Map.entry("scope", "platform"),
                Map.entry("state", "80 percent"),
                Map.entry("cap", "2000"),
                Map.entry("reason", "provider_auth_failed"),
                Map.entry("change", "saved"),
                Map.entry("who", "Test Administrator"),
                Map.entry("when", "18 Oct 2026 10:00 UTC"),
                Map.entry("sender_name", "Test Staff 01"),
                Map.entry("kind", "idea"),
                Map.entry("page", "/staff/retail/sale"),
                Map.entry("message", "Test feedback message."),
                // Subscription billing (issue #480).
                Map.entry("owner_name", "Test Owner 01"),
                Map.entry("amount_line", "The price is UGX 10,000 a month, or UGX 110,000 for a year."),
                Map.entry("how_to_pay", "Test: pay to the test account."),
                Map.entry("period", "1 Nov 2026 to 30 Nov 2026"),
                Map.entry("remove_after", "24 November 2026"),
                Map.entry("as_of", "12 Oct 2026"),
                Map.entry("expires_on", "11 Nov 2026"),
                Map.entry("module", "Shop"),
                Map.entry("decision", "approved: Shop is switched on now"),
                // A report pack is ready (issue #229).
                Map.entry("name", "Test Staff 02"),
                Map.entry("report_title", "monthly report pack"),
                Map.entry("links", "Test Shop\nhttps://test-bms-staging.rincoltech.test/sign-in"));
        for (String channel : List.of("email", "telegram", "sms")) {
            for (String key : OutboxTemplates.keys(channel)) {
                OutboxTemplates.Rendered rendered = templates.render(channel, key, params);
                assertThat(rendered.text()).as("%s %s", channel, key).doesNotContain("{");
                if (channel.equals("email")) {
                    assertThat(rendered.subject()).isNotBlank();
                }
            }
        }
        // ADR-046: the warning to the administrators when two-step sign-in is relaxed names the business, who and when.
        OutboxTemplates.Rendered relaxed = templates.render("email", "identity.two_step_policy_weakened", params);
        assertThat(relaxed.subject()).contains("Test Shop");
        assertThat(relaxed.text())
                .contains("Test Administrator")
                .contains("18 Oct 2026 10:00 UTC")
                .contains("saved");
        assertThat(OutboxTemplates.placeholders("email", "identity.two_step_policy_weakened"))
                .containsExactlyInAnyOrder("business_name", "who", "when", "change");
        // Review B1: the email to an unconfirmed address carries only the server's values.
        assertThat(OutboxTemplates.placeholders("email", "onboarding.verify_email"))
                .containsExactlyInAnyOrder("link", "reference");
        // ADR-030: a tenant's digest goes to a numeric chat it named; anything else is refused.
        assertThat(TelegramChannel.chatOf("operator", "-100123")).isEqualTo("-100123");
        assertThat(TelegramChannel.chatOf("chat:-100456", "-100123")).isEqualTo("-100456");
        assertThat(TelegramChannel.chatOf("chat:@somebody", "-100123")).isNull();
        assertThat(TelegramChannel.chatOf("someone@example.test", "-100123")).isNull();
        assertThatThrownBy(() -> templates.render("email", "no.such.template", params))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SmtpEmailChannel.messageId("onboarding.activation:1"))
                .isEqualTo(SmtpEmailChannel.messageId("onboarding.activation:1"))
                .isNotEqualTo(SmtpEmailChannel.messageId("onboarding.activation:2"))
                .startsWith("<")
                .endsWith("@bms.outbox>");
        assertThat(OutboxDispatcher.safeError(
                        new jakarta.mail.SendFailedException("Invalid Addresses: a@example.test")))
                .isEqualTo("SendFailedException");
    }
}
