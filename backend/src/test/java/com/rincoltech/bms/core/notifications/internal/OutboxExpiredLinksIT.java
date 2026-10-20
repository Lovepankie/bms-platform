package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.notifications.Outbox;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-IAM-15, ADR-042 decision 11: a one-time link that has expired is cleared out of its outbox row by the frequent job, not
 * left for the nightly purge. A row that has not expired, and a row with no expiry, are left alone.
 */
class OutboxExpiredLinksIT extends IntegrationTest {

    @Autowired
    Outbox outbox;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    PlatformTransactionManager transactionManager;

    String enqueue(String recipient) {
        String key = "test.expired-links:" + UUID.randomUUID();
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> outbox.enqueue(new Outbox.Message(
                        "email",
                        recipient,
                        "onboarding.operator_alert",
                        Map.of(
                                "reference", "A-TEST0001",
                                "business_name", "Test Outbox Shop",
                                "modules", "retail",
                                "portal_url", "https://localhost/platform/applications/x"),
                        key)));
        return key;
    }

    Map<String, Object> row(String key) {
        return TestDatabase.owner()
                .sql(
                        "SELECT status, params::text AS params, last_error FROM notification_outbox WHERE idempotency_key = ?")
                .param(key)
                .query()
                .singleRow();
    }

    @Test
    void anExpiredLinkLeavesTheRowWithinTheFrequentJobsInterval() {
        String expired = enqueue("test-exp@example.test");
        String alive = enqueue("test-exp@example.test");
        String noExpiry = enqueue("test-exp@example.test");
        var owner = TestDatabase.owner();
        owner.sql("UPDATE notification_outbox SET expires_at = now() - interval '1 minute' WHERE idempotency_key = ?")
                .param(expired)
                .update();
        owner.sql("UPDATE notification_outbox SET expires_at = now() + interval '30 minutes' WHERE idempotency_key = ?")
                .param(alive)
                .update();

        assertThat(dispatcher.clearExpiredLinks()).isGreaterThanOrEqualTo(1);

        assertThat(row(expired).get("params")).isEqualTo("{}");
        assertThat(row(expired).get("status")).isEqualTo("failed");
        assertThat(row(expired).get("last_error")).isEqualTo("expired");
        assertThat(row(alive).get("params")).isNotEqualTo("{}");
        assertThat(row(alive).get("status")).isEqualTo("pending");
        assertThat(row(noExpiry).get("params")).isNotEqualTo("{}");
    }
}
