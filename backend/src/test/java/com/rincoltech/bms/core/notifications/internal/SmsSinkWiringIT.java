package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.kernel.TenantContext;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The configuration as a staging host runs it, through the real Spring wiring (acceptance 1 of #212):
 * {@code BMS_ENVIRONMENT=staging} and no mode set is the sink, the SMS sender is among the senders
 * that send, a message queued by the application's own outbox ends as a {@code sunk} delivery row, and
 * nothing is sent. The scheduler is off in this context, so only this test's own dispatch runs.
 */
@TestPropertySource(properties = {"bms.environment=staging", "db-scheduler.enabled=false"})
class SmsSinkWiringIT extends IntegrationTest {

    @Autowired
    SmsSettings settings;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    Outbox outbox;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void aStagingContextDefaultsToTheSinkAndAQueuedReceiptEndsSunk() {
        assertThat(settings.environment()).isEqualTo("staging");
        assertThat(settings.mode()).isEqualTo(SmsSettings.Mode.SINK);
        assertThat(dispatcher.enabledChannels()).contains("sms");

        TestDatabase.Fixture t = TestDatabase.tenant("sms-sink", true);
        TestDatabase.owner()
                .sql("INSERT INTO platform_sms_controls (tenant_id, enabled, daily_cap) VALUES (?, true, 1000)")
                .param(t.tenantId())
                .update();
        String key = "test.sms.sink:" + UUID.randomUUID();
        boolean queued = Boolean.TRUE.equals(TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager)
                        .execute(tx -> outbox.enqueue(new Outbox.Message(
                                Outbox.SMS,
                                "0700000301",
                                "savings.deposit",
                                Map.of(
                                        "tenant_name", "Test Lender",
                                        "currency", "UGX",
                                        "amount", "50,000",
                                        "account_no", "SV000001",
                                        "receipt_no", "RC-1"),
                                key)))));
        assertThat(queued).isTrue();

        dispatcher.dispatchOnly(Outbox.SMS, 50, null);

        Map<String, Object> delivery = TestDatabase.owner()
                .sql("SELECT d.status, d.provider, d.tenant_id FROM sms_deliveries d"
                        + " JOIN notification_outbox o ON o.id = d.outbox_id WHERE o.idempotency_key = ?")
                .param(key)
                .query()
                .singleRow();
        assertThat(delivery.get("status")).isEqualTo("sunk");
        assertThat(delivery.get("provider")).isEqualTo("sink");
        assertThat(delivery.get("tenant_id")).isEqualTo(t.tenantId());
        assertThat(TestDatabase.owner()
                        .sql("SELECT status FROM notification_outbox WHERE idempotency_key = ?")
                        .param(key)
                        .query(String.class)
                        .single())
                .isEqualTo("sent");
    }
}
