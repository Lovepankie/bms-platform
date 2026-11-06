package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.testsupport.Api;
import com.rincoltech.bms.testsupport.Api.Session;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The Resend webhook of issue #333 (ADR-117 decision 8) over HTTP: a signed bounce and a signed complaint each become one
 * platform audit row with the outbox row id and the recipient's domain only, a repeat writes nothing more, a wrong
 * signature or an old timestamp gets 401 and writes nothing, and the operator's outbox view lists them. The secret is
 * the test profile's fabricated one; addresses are fabricated.
 */
class ResendWebhookIT extends IntegrationTest {

    static final byte[] KEY = Base64.getDecoder().decode("dGVzdC1vbmx5LXJlc2VuZC13ZWJob29rLXNlY3JldA==");

    @Autowired
    TestRestTemplate http;

    @Autowired
    EmailSuppressions suppressions;

    static String sign(byte[] key, String id, long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return "v1,"
                + Base64.getEncoder()
                        .encodeToString(
                                mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    ResponseEntity<String> post(String id, long timestamp, String signature, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("svix-id", id);
        headers.set("svix-timestamp", String.valueOf(timestamp));
        headers.set("svix-signature", signature);
        return http.postForEntity("/api/v1/platform/webhooks/resend", new HttpEntity<>(body, headers), String.class);
    }

    static String event(String type, UUID outboxId, UUID tenantId) {
        // The test profile sets no BMS_ENVIRONMENT, so this environment's tag is "local".
        return event(type, outboxId, tenantId, "local");
    }

    static String event(String type, UUID outboxId, UUID tenantId, String env) {
        return event(type, outboxId, tenantId, env, "Staff01@Bounce.Example.Test", "Permanent");
    }

    // JUSTIFICATION-A3: the recipient and bounce type of the suppression tests (third #759 review, must-fix 2).
    static String event(String type, UUID outboxId, UUID tenantId, String env, String to, String bounceType) {
        return """
                {"type":"%s","created_at":"2026-11-02T10:00:00.000Z","data":{"email_id":"4ef9a417-02e9-4d39-ad75-9611e0fcc33c",
                "from":"Test Shop <no-reply@example.test>","to":["%s"],"subject":"Your BMS password was changed",
                "bounce":{"type":"%s","subType":"General","message":"The address does not exist"},
                "tags":{"outbox_id":"%s","tenant_id":"%s","template":"identity_password_changed","env":"%s"}}}
                """.formatted(type, to, bounceType, outboxId, tenantId, env);
    }

    @Test
    void anotherEnvironmentsEventIsIgnoredASuppressionIsRecordedAndAForgedTimestampIsRefused() throws Exception {
        // JUSTIFICATION-A3: items 1, 2 and 6 of the third #759 review, through HTTP.
        UUID outboxId = UUID.randomUUID();
        long now = Instant.now().getEpochSecond();
        // Staging's bounce, signed for this endpoint (one Resend account sends every event to every endpoint).
        for (String env : new String[] {"staging", ""}) {
            String id = "msg_" + UUID.randomUUID().toString().replace("-", "");
            String body = event("email.bounced", outboxId, UUID.randomUUID(), env);
            // Answered 200 so Resend stops retrying, logged at debug, never recorded.
            assertThat(post(id, now, sign(KEY, id, now, body), body)
                            .getStatusCode()
                            .value())
                    .isEqualTo(200);
            assertThat(rows(id)).isZero();
        }
        // Resend dropped a later send to an address on its suppression list: recorded, so the operator sees it.
        String suppressedId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String suppressed = event("email.suppressed", outboxId, UUID.randomUUID());
        assertThat(post(suppressedId, now, sign(KEY, suppressedId, now, suppressed), suppressed)
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        assertThat(TestDatabase.owner()
                        .sql("SELECT action FROM platform_audit_log WHERE data ->> 'svix_id' = ?")
                        .param(suppressedId)
                        .query(String.class)
                        .single())
                .isEqualTo("platform.email.suppressed");
        // A timestamp out of Instant's range is a refusal (401), never a 500.
        String forged = "msg_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(post(forged, Long.MAX_VALUE, sign(KEY, forged, Long.MAX_VALUE, suppressed), suppressed)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
    }

    long rows(String svixId) {
        return TestDatabase.owner()
                .sql("SELECT count(*) FROM platform_audit_log WHERE data ->> 'svix_id' = ?")
                .param(svixId)
                .query(Long.class)
                .single();
    }

    @Test
    void aSignedBounceAndComplaintAreRecordedOnceWithTheDomainOnlyAndShownToTheOperator() throws Exception {
        UUID outboxId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        long now = Instant.now().getEpochSecond();
        String bounceId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String bounce = event("email.bounced", outboxId, tenantId);

        assertThat(post(bounceId, now, sign(KEY, bounceId, now, bounce), bounce)
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        // The same message again (Resend retries until it hears 2xx): nothing more is written.
        assertThat(post(bounceId, now, sign(KEY, bounceId, now, bounce), bounce)
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        assertThat(rows(bounceId)).isEqualTo(1);
        var row = TestDatabase.owner()
                .sql(
                        "SELECT action, tenant_id, data::text AS data FROM platform_audit_log WHERE data ->> 'svix_id' = ?")
                .param(bounceId)
                .query()
                .singleRow();
        assertThat(row.get("action")).isEqualTo("platform.email.bounced");
        assertThat(row.get("tenant_id")).isEqualTo(tenantId);
        assertThat((String) row.get("data"))
                .contains(outboxId.toString())
                .contains("bounce.example.test")
                .contains("Permanent")
                .doesNotContainIgnoringCase("staff01");

        String complaintId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String complaint = event("email.complained", outboxId, tenantId);
        assertThat(post(complaintId, now, sign(KEY, complaintId, now, complaint), complaint)
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        assertThat(rows(complaintId)).isEqualTo(1);

        // Another event type is acknowledged and not recorded.
        String deliveredId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String delivered = event("email.delivered", outboxId, tenantId);
        assertThat(post(deliveredId, now, sign(KEY, deliveredId, now, delivered), delivered)
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        assertThat(rows(deliveredId)).isZero();

        Api platform = Api.platform(http);
        String opEmail = Api.email("operator");
        UUID opId = Api.platformUser(opEmail);
        Session op = platform.signIn(opId, opEmail, Api.PASSWORD, null);
        JsonNode status =
                platform.get("/api/v1/platform/outbox", op.accessToken()).getBody();
        int found = 0;
        for (JsonNode b : status.get("bounces")) {
            if (b.get("outbox_id").asString().equals(outboxId.toString())) {
                found++;
                assertThat(b.get("recipient_domain").asString()).isEqualTo("bounce.example.test");
                assertThat(b.get("tenant_id").asString()).isEqualTo(tenantId.toString());
            }
        }
        assertThat(found).isEqualTo(2);
        assertThat(status.toString()).doesNotContainIgnoringCase("staff01@");
    }

    @Test
    void aWrongSignatureOrAnOldTimestampGets401AndWritesNothing() throws Exception {
        UUID outboxId = UUID.randomUUID();
        long now = Instant.now().getEpochSecond();
        String body = event("email.bounced", outboxId, UUID.randomUUID());

        String forged = "msg_" + UUID.randomUUID().toString().replace("-", "");
        byte[] other = "another-secret-entirely".getBytes(StandardCharsets.UTF_8);
        assertThat(post(forged, now, sign(other, forged, now, body), body)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(rows(forged)).isZero();

        // Signed with the right key, but over another body.
        String tampered = "msg_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(post(tampered, now, sign(KEY, tampered, now, body), body.replace("Permanent", "Transient"))
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(rows(tampered)).isZero();

        // A replay: correctly signed, but ten minutes old.
        String old = "msg_" + UUID.randomUUID().toString().replace("-", "");
        long then = now - 600;
        assertThat(post(old, then, sign(KEY, old, then, body), body)
                        .getStatusCode()
                        .value())
                .isEqualTo(401);
        assertThat(rows(old)).isZero();
    }

    // JUSTIFICATION-A3: the suppression cases of the third #759 review's must-fix 2, one per event and the clear.
    /** A fabricated address of its own per test, in mixed case: the hash is of the lower-cased address. */
    static String address() {
        return "Staff-" + UUID.randomUUID().toString().substring(0, 8) + "@Suppress.Example.Test";
    }

    /** Posts one signed event of this environment; returns its message id. */
    String deliver(String type, String to, String bounceType) throws Exception {
        long now = Instant.now().getEpochSecond();
        String id = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String body = event(type, UUID.randomUUID(), UUID.randomUUID(), "local", to, bounceType);
        assertThat(post(id, now, sign(KEY, id, now, body), body).getStatusCode().value())
                .isEqualTo(204);
        return id;
    }

    /** The row's data, which must never hold the address, and its recipient hash (null when none). */
    String hashOf(String svixId, String address) {
        String data = TestDatabase.owner()
                .sql("SELECT data::text FROM platform_audit_log WHERE data ->> 'svix_id' = ?")
                .param(svixId)
                .query(String.class)
                .single();
        assertThat(data).doesNotContainIgnoringCase(address.substring(0, address.indexOf('@')));
        // coalesce: a row with no hash reads as null here, since single() refuses a null value.
        String hash = TestDatabase.owner()
                .sql(
                        "SELECT coalesce(data ->> 'recipient_hash', '') FROM platform_audit_log WHERE data ->> 'svix_id' = ?")
                .param(svixId)
                .query(String.class)
                .single();
        return hash.isEmpty() ? null : hash;
    }

    @Test
    void aHardBounceSuppressesTheAddressByItsKeyedHashAndASoftBounceDoesNot() throws Exception {
        String soft = address();
        assertThat(hashOf(deliver("email.bounced", soft, "Transient"), soft)).isNull();
        assertThat(suppressions.suppressed(soft)).isFalse();

        String hard = address();
        String id = deliver("email.bounced", hard, "Permanent");
        assertThat(hashOf(id, hard))
                .isEqualTo(suppressions.hash(hard.toLowerCase(java.util.Locale.ROOT)))
                .hasSize(64);
        assertThat(suppressions.suppressed(hard)).isTrue();
        assertThat(suppressions.suppressed(hard.toLowerCase(java.util.Locale.ROOT)))
                .isTrue();
    }

    // JUSTIFICATION-A3: the fourth #759 review's must-fix: email sent by SMTP is not tracked or suppressed.
    @Test
    void aHardBounceOfAnEmailSentBySmtpCarriesNoTagsAndIsNeitherRecordedNorSuppressed() throws Exception {
        // ADR-117 decision 8: an email sent over SMTP has no tags at all, so its event arrives with none.
        String to = address();
        String id = "msg_" + UUID.randomUUID().toString().replace("-", "");
        long now = Instant.now().getEpochSecond();
        String body = """
                {"type":"email.bounced","created_at":"2026-11-02T10:00:00.000Z","data":{"email_id":"5af0b528-13fa-4e4a-be86-0722f1adc44d",
                "from":"Test Shop <no-reply@example.test>","to":["%s"],"subject":"Your BMS password was changed",
                "bounce":{"type":"Permanent","subType":"General","message":"The address does not exist"}}}
                """.formatted(to);
        assertThat(post(id, now, sign(KEY, id, now, body), body).getStatusCode().value())
                .isEqualTo(200);
        assertThat(rows(id)).isZero();
        assertThat(suppressions.suppressed(to)).isFalse();
    }

    @Test
    void aComplaintSuppressesTheAddress() throws Exception {
        String to = address();
        assertThat(hashOf(deliver("email.complained", to, "Permanent"), to)).isEqualTo(suppressions.hash(to));
        assertThat(suppressions.suppressed(to)).isTrue();
    }

    @Test
    void resendsSuppressedEventSuppressesTheAddress() throws Exception {
        String to = address();
        assertThat(hashOf(deliver("email.suppressed", to, "Permanent"), to)).isEqualTo(suppressions.hash(to));
        assertThat(suppressions.suppressed(to)).isTrue();
    }

    @Test
    void anOperatorClearsASuppressionAndALaterBounceSuppressesAgain() throws Exception {
        String to = address();
        deliver("email.complained", to, "Permanent");
        assertThat(suppressions.suppressed(to)).isTrue();

        Api platform = Api.platform(http);
        String opEmail = Api.email("operator");
        UUID opId = Api.platformUser(opEmail);
        Session op = platform.signIn(opId, opEmail, Api.PASSWORD, null);
        assertThat(platform.post("/api/v1/platform/outbox/unsuppress", java.util.Map.of("email", to), op.accessToken())
                        .getStatusCode()
                        .value())
                .isEqualTo(204);
        assertThat(suppressions.suppressed(to)).isFalse();
        var cleared = TestDatabase.owner()
                .sql("SELECT platform_user_id, data::text AS data FROM platform_audit_log"
                        + " WHERE action = 'platform.email.unsuppressed' AND data ->> 'recipient_hash' = ?")
                .param(suppressions.hash(to))
                .query()
                .singleRow();
        assertThat(cleared.get("platform_user_id")).isEqualTo(opId);
        assertThat((String) cleared.get("data")).doesNotContainIgnoringCase("staff-");
        // Clearing an address that is not suppressed writes nothing.
        assertThat(platform.post("/api/v1/platform/outbox/unsuppress", java.util.Map.of("email", to), op.accessToken())
                        .getBody()
                        .get("code")
                        .asString())
                .isEqualTo("email_not_suppressed");

        deliver("email.bounced", to, "Permanent");
        assertThat(suppressions.suppressed(to)).isTrue();
    }

    @Autowired
    com.rincoltech.bms.core.audit.PlatformAuditLog audit;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    // JUSTIFICATION-A3: the race test issue #777 asks for (fifth #759 review, 5); no existing test orders two writers.
    @Test
    void aBounceWhoseTransactionStartedBeforeAClearIsStillTheNewer() {
        String to = "late-bounce-01@suppress.example.test";
        String hash = suppressions.hash(to);
        TransactionTemplate webhook = new TransactionTemplate(transactionManager);
        TransactionTemplate operator = new TransactionTemplate(transactionManager);
        operator.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        webhook.executeWithoutResult(s -> {
            // The webhook's transaction starts, then waits (as on its svix lock) while the operator's clear commits.
            jdbc.sql("SELECT pg_sleep(0.05)").query().singleRow();
            operator.executeWithoutResult(
                    o -> audit.record(EmailSuppressions.UNSUPPRESSED, null, null, Map.of(EmailSuppressions.KEY, hash)));
            audit.record(ResendWebhookController.BOUNCED, null, null, Map.of(EmailSuppressions.KEY, hash));
        });
        long checks = meters.get(EmailSuppressions.CHECK_METRIC).timer().count();
        assertThat(suppressions.suppressed(to))
                .as("the bounce was written after the clear, so it decides")
                .isTrue();
        assertThat(meters.get(EmailSuppressions.CHECK_METRIC).timer().count())
                .as("each check is timed, so its growing cost shows")
                .isGreaterThan(checks);
    }

    @Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    // JUSTIFICATION-A3: issue #767's cases (the row itself marked, tenant bound; the unset secret), none existed.
    /** A sent email row of this business to this fabricated address, as the dispatcher leaves it. */
    static UUID sentRow(UUID tenantId, String to) {
        UUID id = UUID.randomUUID();
        TestDatabase.owner()
                .sql("INSERT INTO notification_outbox (id, channel, recipient, template_key, idempotency_key,"
                        + " tenant_id, status, attempts, sent_at)"
                        + " VALUES (?, 'email', ?, 'identity.password_changed', ?, ?, 'sent', 1, now())")
                .params(id, to, "test.767:" + id, tenantId)
                .update();
        return id;
    }

    /** The row's status and last error, as "failed/bounced". */
    static String state(UUID outboxId) {
        return TestDatabase.owner()
                .sql("SELECT status || '/' || coalesce(last_error, '') FROM notification_outbox WHERE id = ?")
                .param(outboxId)
                .query(String.class)
                .single();
    }

    /** Posts one signed event of this environment about this row and business; answers the status code. */
    int deliverFor(String type, UUID outboxId, UUID tenantId, String to) throws Exception {
        long now = Instant.now().getEpochSecond();
        String id = "msg_" + UUID.randomUUID().toString().replace("-", "");
        String body = event(type, outboxId, tenantId, "local", to, "Permanent");
        return post(id, now, sign(KEY, id, now, body), body).getStatusCode().value();
    }

    @Test
    void aBounceMarksItsOwnRowFailedAndNeverAnotherBusinesssRow() throws Exception {
        TestDatabase.Fixture first = TestDatabase.tenant("test-767-first", false);
        TestDatabase.Fixture second = TestDatabase.tenant("test-767-second", false);
        String to = address();
        UUID firstRow = sentRow(first.tenantId(), to);
        UUID secondRow = sentRow(second.tenantId(), to);

        assertThat(deliverFor("email.bounced", firstRow, first.tenantId(), to)).isEqualTo(204);
        assertThat(state(firstRow)).isEqualTo("failed/bounced");
        assertThat(state(secondRow))
                .as("the other business's row to the same address")
                .isEqualTo("sent/");

        // Tags that name the first business with the second business's row id: the row is not found, nothing moves.
        assertThat(deliverFor("email.bounced", secondRow, first.tenantId(), to)).isEqualTo(204);
        assertThat(state(secondRow)).isEqualTo("sent/");

        // The same bounce again under a new message id leaves the row as it is.
        assertThat(deliverFor("email.bounced", firstRow, first.tenantId(), to)).isEqualTo(204);
        assertThat(state(firstRow)).isEqualTo("failed/bounced");
        assertThat(TestDatabase.owner()
                        .sql("SELECT failed_at IS NOT NULL AND sent_at IS NOT NULL"
                                + " FROM notification_outbox WHERE id = ?")
                        .param(firstRow)
                        .query(Boolean.class)
                        .single())
                .as("failed now, and the time it was sent is kept")
                .isTrue();
    }

    @Test
    void aComplaintMarksItsRowAndStopsLaterSendsToTheAddress() throws Exception {
        TestDatabase.Fixture business = TestDatabase.tenant("test-767-complaint", false);
        String to = address();
        UUID row = sentRow(business.tenantId(), to);

        assertThat(deliverFor("email.complained", row, business.tenantId(), to)).isEqualTo(204);

        assertThat(state(row)).isEqualTo("failed/complained");
        assertThat(suppressions.suppressed(to)).isTrue();
        NotificationProperties properties = new NotificationProperties(
                new NotificationProperties.Smtp(null, null, null, null, "no-reply@example.test"),
                null,
                "",
                new NotificationProperties.Mail("resend", "re_fabricated_test_key_0767", "http://127.0.0.1:1", null));
        SmtpEmailChannel smtp = new SmtpEmailChannel(
                properties, new OutboxTemplates(), EmailLayoutTest.HOSTS, null, null, null, null, suppressions, "");
        assertThat(smtp.send(new OutboxChannel.Delivery(to, "s", "t", "k")))
                .as("a later email to the address is refused before it is sent")
                .isEqualTo(OutboxChannel.Result.refused("recipient_suppressed"));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void withTheSecretUnsetTheRouteAnswers503AndLogsOnce(CapturedOutput output) {
        ResendWebhookController off = new ResendWebhookController(null, null, null, null, null, "", "");
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/platform/webhooks/resend");
            request.setContent(
                    event("email.bounced", UUID.randomUUID(), UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> off.receive(request))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            e -> assertThat(e.status().value()).isEqualTo(503));
        }
        assertThat(output.getAll().split("BMS_RESEND_WEBHOOK_SECRET is unset", -1))
                .as("one warning, not one per retry")
                .hasSize(2);
    }
}
