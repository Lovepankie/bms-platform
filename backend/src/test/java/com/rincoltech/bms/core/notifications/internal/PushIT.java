package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.kernel.PushNotice;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Web Push through the real application and a real PostgreSQL, with a fake push service so nothing leaves the machine
 * (ADR-050): the device routes (subscribe, list, unsubscribe, who may touch what, the allowed push services), the outbox
 * {@code push} channel (every device of the person, a gone device removed, a temporary failure retried under the outbox's own
 * rules) and the notice a module publishes (one row for each person with a device, never for the person who caused it).
 * The scheduler is off in this context, so only the test drives the sender. Fabricated tenants, people and devices.
 */
@Import(PushIT.Fake.class)
@TestPropertySource(properties = {"bms.push.ephemeral-keys=true", "db-scheduler.enabled=false"})
class PushIT extends IntegrationTest {

    static final String BASE = "/api/v1/notifications/push";
    static final SecureRandom RANDOM = new SecureRandom();

    /** A push service that records what it is asked to send, and answers as the test says. */
    static final class FakePushSender implements PushSender {
        final List<String[]> sent = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Outcome> answers = new HashMap<>();

        @Override
        public Outcome send(Target target, String payload, int ttlSeconds) {
            sent.add(new String[] {target.endpoint(), payload, Integer.toString(ttlSeconds)});
            return answers.getOrDefault(target.endpoint(), Outcome.SENT);
        }

        List<String[]> to(String endpoint) {
            synchronized (sent) {
                return sent.stream().filter(s -> s[0].equals(endpoint)).toList();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fake {

        @Bean
        @Primary
        FakePushSender fakePushSender() {
            return new FakePushSender();
        }
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    FakePushSender fake;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    Outbox outbox;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    TestDatabase.Fixture t;
    UUID alice;
    UUID bob;

    @BeforeEach
    void tenant() {
        t = TestDatabase.tenant("push", false);
        alice = Api.staff(t, Api.email("push-alice"), new Api.Role("tenant_admin", null));
        bob = Api.staff(t, Api.email("push-bob"), new Api.Role("tenant_admin", null));
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    HttpHeaders as(String slug, UUID user) {
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", slug);
        h.add("X-Dev-User-Id", user.toString());
        h.add("X-Dev-Permissions", "core.settings.manage");
        h.add("X-Dev-Branch-Ids", "*");
        h.add("User-Agent", "Mozilla/5.0 (Linux; Android 14; Test) Chrome/130.0 Mobile Safari/537.36");
        return h;
    }

    ResponseEntity<JsonNode> call(HttpMethod method, String path, UUID user, Object body) {
        return http.exchange(path, method, new HttpEntity<>(body, as(t.slug(), user)), JsonNode.class);
    }

    static String endpoint() {
        return "https://fcm.googleapis.com/fcm/send/test-" + UUID.randomUUID();
    }

    /** A subscription body as a browser's PushSubscription.toJSON() gives it: a real P-256 point and a 16 byte secret. */
    static Map<String, Object> subscription(String endpoint) {
        byte[] auth = new byte[16];
        RANDOM.nextBytes(auth);
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put(
                "p256dh",
                WebPushCrypto.encode(WebPushCrypto.publicBytes(
                        WebPushCrypto.generateKeyPair().getPublic())));
        keys.put("auth", WebPushCrypto.encode(auth));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endpoint", endpoint);
        body.put("keys", keys);
        return body;
    }

    ResponseEntity<JsonNode> subscribe(UUID user, String endpoint) {
        return call(HttpMethod.POST, BASE + "/subscriptions", user, subscription(endpoint));
    }

    JsonNode devices(UUID user) {
        return call(HttpMethod.GET, BASE + "/subscriptions", user, null)
                .getBody()
                .get("items");
    }

    Map<String, Object> row(String key) {
        return TestDatabase.owner()
                .sql("SELECT status, attempts, last_error FROM notification_outbox WHERE idempotency_key = ?")
                .param(key)
                .query()
                .singleRow();
    }

    /** Queues a push row for the person in their tenant, as a module's transaction would, and returns its key. */
    String queue(UUID person, String template, Map<String, String> params, Instant expires) {
        String key = "test.push:" + UUID.randomUUID();
        TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager)
                        .execute(status -> outbox.enqueue(new Outbox.Message(
                                Outbox.PUSH, "user:" + person, template, params, key, null, expires))));
        return key;
    }

    void send() {
        dispatcher.dispatchOnly(Outbox.PUSH, 100, Duration.ofSeconds(20));
    }

    // ---- The device routes -----------------------------------------------------------------------

    @Test
    void theServerOffersItsPublicKeyWhenPushIsOn() {
        ResponseEntity<JsonNode> config = call(HttpMethod.GET, BASE + "/config", alice, null);
        assertThat(config.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(config.getBody().get("enabled").asBoolean()).isTrue();
        assertThat(WebPushCrypto.decode(config.getBody().get("public_key").asString()))
                .hasSize(65);
    }

    @Test
    void aPersonRegistersListsAndRemovesTheirOwnDevice() {
        String endpoint = endpoint();
        ResponseEntity<JsonNode> created = subscribe(alice, endpoint);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("label").asString()).isEqualTo("Chrome on Android");
        // Registering the same device again refreshes the row: still one.
        assertThat(subscribe(alice, endpoint).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode listed = devices(alice);
        assertThat(listed).hasSize(1);
        // The page is never given the address or the keys back.
        String text = listed.toString();
        assertThat(text).doesNotContain("endpoint", "p256dh", "auth", "fcm.googleapis.com");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM push_subscriptions WHERE tenant_id = ? AND endpoint_hash = ?")
                        .params(t.tenantId(), PushDevices.hash(endpoint))
                        .query(Long.class)
                        .single())
                .isEqualTo(1);

        ResponseEntity<JsonNode> removed =
                call(HttpMethod.POST, BASE + "/subscriptions/unsubscribe", alice, Map.of("endpoint", endpoint));
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(devices(alice)).isEmpty();
        // Nothing left to remove is still a success.
        assertThat(call(HttpMethod.POST, BASE + "/subscriptions/unsubscribe", alice, Map.of("endpoint", endpoint))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void aDeviceCanBeRemovedByItsIdByItsOwnerOnly() {
        String id = subscribe(alice, endpoint()).getBody().get("id").asString();
        assertThat(call(HttpMethod.DELETE, BASE + "/subscriptions/" + id, bob, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(devices(alice)).hasSize(1);
        assertThat(call(HttpMethod.DELETE, BASE + "/subscriptions/" + id, alice, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(devices(alice)).isEmpty();
    }

    @Test
    void anAddressOutsideThePushServicesAndBadKeysAreRefused() {
        for (String bad : List.of(
                "https://evil.example.test/x",
                "http://fcm.googleapis.com/fcm/send/x",
                "https://127.0.0.1/x",
                "https://169.254.169.254/latest/meta-data")) {
            ResponseEntity<JsonNode> response = subscribe(alice, bad);
            assertThat(response.getStatusCode()).as(bad).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(response.getBody().get("errors").get(0).get("field").asString())
                    .isEqualTo("endpoint");
        }
        Map<String, Object> body = subscription(endpoint());
        body.put("keys", Map.of("p256dh", "AAAA", "auth", "BBBB"));
        assertThat(call(HttpMethod.POST, BASE + "/subscriptions", alice, body).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(devices(alice)).isEmpty();
    }

    @Test
    void eachPersonSeesOnlyTheirOwnDevicesAndAnotherBusinessSeesNone() {
        subscribe(alice, endpoint());
        assertThat(devices(alice)).hasSize(1);
        assertThat(devices(bob)).isEmpty();
        TestDatabase.Fixture other = TestDatabase.tenant("pushother", false);
        UUID stranger = Api.staff(other, Api.email("push-stranger"), new Api.Role("tenant_admin", null));
        ResponseEntity<JsonNode> theirs = http.exchange(
                BASE + "/subscriptions",
                HttpMethod.GET,
                new HttpEntity<>(null, as(other.slug(), stranger)),
                JsonNode.class);
        assertThat(theirs.getBody().get("items")).isEmpty();
        // The same address in two businesses is two devices rows, one in each: nothing is shared across the tenants.
        String shared = endpoint();
        subscribe(alice, shared);
        http.exchange(
                BASE + "/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(subscription(shared), as(other.slug(), stranger)),
                JsonNode.class);
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(DISTINCT tenant_id) FROM push_subscriptions WHERE endpoint_hash = ?")
                        .param(PushDevices.hash(shared))
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
        assertThat(devices(alice)).hasSize(2);
    }

    @Test
    void aDeviceMovesToTheNextPersonWhoTurnsNotificationsOnIt() {
        String shared = endpoint();
        subscribe(alice, shared);
        assertThat(devices(alice)).hasSize(1);
        subscribe(bob, shared);
        assertThat(devices(alice)).isEmpty();
        assertThat(devices(bob)).hasSize(1);
    }

    @Test
    void aPersonKeepsAtMostTenDevices() {
        for (int i = 0; i < PushDevices.MAX_DEVICES + 2; i++) {
            assertThat(subscribe(alice, endpoint()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        assertThat(devices(alice)).hasSize(PushDevices.MAX_DEVICES);
    }

    // ---- The outbox channel ----------------------------------------------------------------------

    @Test
    void aRowIsSentToEveryDeviceOfThePersonWithTheRenderedTitleAndPath() {
        String phone = endpoint();
        String laptop = endpoint();
        subscribe(alice, phone);
        subscribe(alice, laptop);
        subscribe(bob, endpoint());
        String key = queue(
                alice,
                "core.approval_waiting",
                Map.of("action_label", "Test action", "path", "/staff/approvals"),
                Instant.now().plus(Duration.ofHours(1)));
        send();
        assertThat(fake.to(phone)).hasSize(1);
        assertThat(fake.to(laptop)).hasSize(1);
        String payload = fake.to(phone).get(0)[1];
        assertThat(payload)
                .contains("\"title\":\"An approval is waiting for you\"")
                .contains("Test action is waiting for your decision")
                .contains("\"path\":\"/staff/approvals\"");
        // The message holds a lifetime of an hour or less here, not the day's maximum.
        assertThat(Integer.parseInt(fake.to(phone).get(0)[2])).isBetween(60, 3600);
        assertThat(row(key).get("status")).isEqualTo("sent");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM push_subscriptions WHERE endpoint = ? AND last_used_at IS NOT NULL")
                        .param(phone)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aPathThatIsNotAnAppRouteBecomesTheStaffHome() {
        String device = endpoint();
        subscribe(alice, device);
        queue(
                alice,
                "core.approval_waiting",
                Map.of("action_label", "Test action", "path", "https://evil.example.test/steal"),
                Instant.now().plus(Duration.ofHours(1)));
        send();
        assertThat(fake.to(device).get(0)[1]).contains("\"path\":\"/staff\"").doesNotContain("evil");
    }

    @Test
    void aDeviceThePushServiceSaysIsGoneIsRemovedAndANoticeForNobodyIsRefused() {
        String gone = endpoint();
        subscribe(alice, gone);
        fake.answers.put(gone, PushSender.Outcome.GONE);
        String key = queue(
                alice,
                "core.approval_waiting",
                Map.of("action_label", "Test action", "path", "/staff/approvals"),
                Instant.now().plus(Duration.ofHours(1)));
        send();
        assertThat(devices(alice)).isEmpty();
        assertThat(row(key).get("status")).isEqualTo("failed");

        // Now the person has no device at all: refused, never retried, and nothing is sent.
        int before = fake.sent.size();
        String second = queue(
                alice,
                "core.approval_waiting",
                Map.of("action_label", "Test action", "path", "/staff/approvals"),
                Instant.now().plus(Duration.ofHours(1)));
        send();
        assertThat(fake.sent).hasSize(before);
        assertThat(row(second).get("status")).isEqualTo("failed");
        assertThat(row(second).get("last_error").toString()).isEqualTo("no_device");
    }

    @Test
    void aTemporaryFailureIsRetriedByTheOutboxAndKeepsTheDevice() {
        String device = endpoint();
        subscribe(alice, device);
        fake.answers.put(device, PushSender.Outcome.TRANSIENT);
        String key = queue(
                alice,
                "core.approval_waiting",
                Map.of("action_label", "Test action", "path", "/staff/approvals"),
                Instant.now().plus(Duration.ofHours(1)));
        send();
        Map<String, Object> after = row(key);
        // Pending with the attempt counted: the outbox's own backoff decides when it is tried again.
        assertThat(after.get("status")).isEqualTo("pending");
        assertThat(((Number) after.get("attempts")).intValue()).isEqualTo(1);
        assertThat(devices(alice)).hasSize(1);
    }

    @Test
    void aRejectedDeviceIsKeptSoAMisconfigurationNeverWipesThem() {
        String device = endpoint();
        subscribe(alice, device);
        fake.answers.put(device, PushSender.Outcome.REJECTED);
        String key = queue(
                alice,
                "core.approval_waiting",
                Map.of("action_label", "Test action", "path", "/staff/approvals"),
                Instant.now().plus(Duration.ofHours(1)));
        send();
        assertThat(row(key).get("status")).isEqualTo("failed");
        assertThat(devices(alice)).hasSize(1);
    }

    // ---- The notice a module publishes -----------------------------------------------------------

    void publish(PushNotice notice) {
        TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager).execute(status -> {
                    events.publishEvent(notice);
                    return null;
                }));
    }

    List<String> recipientsOf(String ref) {
        return TestDatabase.owner()
                .sql("SELECT recipient FROM notification_outbox WHERE channel = 'push' AND idempotency_key LIKE ?")
                .param("push:core.approval_waiting:" + ref + ":%")
                .query(String.class)
                .list();
    }

    @Test
    void aNoticeBecomesOneRowForEachHolderWithADeviceExceptTheMaker() {
        UUID carol = Api.staff(t, Api.email("push-carol"), new Api.Role("tenant_admin", null));
        UUID dave = Api.staff(t, Api.email("push-dave"), new Api.Role("tenant_admin", null));
        subscribe(alice, endpoint());
        subscribe(bob, endpoint());
        subscribe(carol, endpoint());
        // Dave holds the permission but has no device; Bob caused the event.
        String ref = UUID.randomUUID().toString();
        publish(new PushNotice(
                "core.approval_waiting",
                ref,
                new PushNotice.Holders("core.settings.manage", null, bob),
                Map.of("action_label", "Test action", "path", "/staff/approvals")));
        assertThat(recipientsOf(ref)).containsExactlyInAnyOrder("user:" + alice, "user:" + carol);
        assertThat(recipientsOf(ref)).doesNotContain("user:" + bob, "user:" + dave);
        // Published again for the same event: no second row for anyone.
        publish(new PushNotice(
                "core.approval_waiting",
                ref,
                new PushNotice.Holders("core.settings.manage", null, bob),
                Map.of("action_label", "Test action", "path", "/staff/approvals")));
        assertThat(recipientsOf(ref)).hasSize(2);
    }

    @Test
    void aNoticeToNamedPeopleSkipsAnyoneWithoutADeviceOrNotActive() {
        subscribe(alice, endpoint());
        String ref = UUID.randomUUID().toString();
        publish(new PushNotice(
                "core.approval_waiting",
                ref,
                new PushNotice.Users(Set.of(alice, bob)),
                Map.of("action_label", "Test action", "path", "/staff/approvals")));
        assertThat(recipientsOf(ref)).containsExactly("user:" + alice);
        TestDatabase.owner()
                .sql("UPDATE users SET status = 'deactivated' WHERE id = ?")
                .param(alice)
                .update();
        String later = UUID.randomUUID().toString();
        publish(new PushNotice(
                "core.approval_waiting",
                later,
                new PushNotice.Users(Set.of(alice)),
                Map.of("action_label", "Test action", "path", "/staff/approvals")));
        assertThat(recipientsOf(later)).isEmpty();
    }

    @Test
    void aBranchNameIsFilledFromTheBranchIdAndABadNoticeNeverBreaksTheCaller() {
        subscribe(alice, endpoint());
        String ref = UUID.randomUUID().toString();
        publish(new PushNotice(
                "retail.sale_voided",
                ref,
                new PushNotice.Holders("core.settings.manage", t.headOffice(), bob),
                Map.of("branch_id", t.headOffice().toString(), "path", "/staff/retail/sales")));
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox WHERE idempotency_key = ?")
                        .param("push:retail.sale_voided:" + ref + ":" + alice)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        // A template that does not exist is skipped inside its savepoint; the caller's transaction commits regardless.
        publish(new PushNotice(
                "no.such_template",
                UUID.randomUUID().toString(),
                new PushNotice.Users(Set.of(alice)),
                Map.of("path", "/staff")));
    }
}
