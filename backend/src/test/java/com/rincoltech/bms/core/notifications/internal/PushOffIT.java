package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.kernel.PushNotice;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.Api;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Push is off until the VAPID keys are set (ADR-050): with the plain test configuration the channel does not send, the config
 * route says so and gives no key, a device cannot register, and a module's notice writes nothing. A business that does not use
 * push pays nothing for it. Fabricated tenant and person.
 */
class PushOffIT extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void withoutKeysThereIsNoChannelNoDeviceAndNoRow() {
        TestDatabase.Fixture t = TestDatabase.tenant("pushoff", false);
        UUID alice = Api.staff(t, Api.email("pushoff-alice"), new Api.Role("tenant_admin", null));
        HttpHeaders h = new HttpHeaders();
        h.add("X-Tenant", t.slug());
        h.add("X-Dev-User-Id", alice.toString());
        h.add("X-Dev-Permissions", "core.settings.manage");
        h.add("X-Dev-Branch-Ids", "*");

        assertThat(dispatcher.enabledChannels()).doesNotContain("push");
        ResponseEntity<JsonNode> config = http.exchange(
                "/api/v1/notifications/push/config", HttpMethod.GET, new HttpEntity<>(null, h), JsonNode.class);
        assertThat(config.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(config.getBody().get("enabled").asBoolean()).isFalse();
        assertThat(config.getBody().get("public_key").isNull()).isTrue();

        Map<String, Object> subscription = PushIT.subscription("https://fcm.googleapis.com/fcm/send/test-off");
        ResponseEntity<JsonNode> refused = http.exchange(
                "/api/v1/notifications/push/subscriptions",
                HttpMethod.POST,
                new HttpEntity<>(subscription, h),
                JsonNode.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("push_not_available");

        // A device that was registered while push was on (a row put there by hand) still draws no row now.
        TestDatabase.owner()
                .sql("""
                        INSERT INTO push_subscriptions (id, tenant_id, user_id, endpoint, endpoint_hash, p256dh, auth)
                        VALUES (?, ?, ?, 'https://fcm.googleapis.com/fcm/send/test-off', ?, ?, ?)
                        """)
                .params(
                        UUID.randomUUID(),
                        t.tenantId(),
                        alice,
                        PushDevices.hash("https://fcm.googleapis.com/fcm/send/test-off"),
                        ((Map<?, ?>) subscription.get("keys")).get("p256dh"),
                        ((Map<?, ?>) subscription.get("keys")).get("auth"))
                .update();
        String ref = UUID.randomUUID().toString();
        TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager).execute(status -> {
                    events.publishEvent(new PushNotice(
                            "core.approval_waiting",
                            ref,
                            new PushNotice.Users(Set.of(alice)),
                            Map.of("action_label", "Test action", "path", "/staff/approvals")));
                    return null;
                }));
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM notification_outbox WHERE idempotency_key LIKE ?")
                        .param("push:%:" + ref + ":%")
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
