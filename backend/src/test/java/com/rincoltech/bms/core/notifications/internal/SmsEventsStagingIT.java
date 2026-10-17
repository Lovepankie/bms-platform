package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.IntegrationTest;
import com.rincoltech.bms.TestDatabase;
import com.rincoltech.bms.core.notifications.Outbox;
import com.rincoltech.bms.core.notifications.SmsEvents;
import com.rincoltech.bms.core.notifications.SmsEvents.MemberSms;
import com.rincoltech.bms.core.notifications.SmsEvents.Result;
import com.rincoltech.bms.kernel.TenantContext;
import com.rincoltech.bms.testsupport.SmsTenants;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The staging guard with the member events of issue #221 (FR-NTF-12, FR-NTF-18), through the real Spring wiring and the
 * real {@link SmsEvents} port: {@code BMS_ENVIRONMENT=staging} with no mode set is the sink, so a loan, savings or investment
 * event queued by the application ends as a {@code sunk} delivery row and nothing is sent, whatever the number. The refusal
 * of a live URL or account at start, in every mode, is {@code SmsSettingsTest}; the sink wiring is {@code SmsSinkWiringIT}.
 * The scheduler is off in this context, so only this test's own dispatch runs.
 */
@TestPropertySource(properties = {"bms.environment=staging", "db-scheduler.enabled=false"})
class SmsEventsStagingIT extends IntegrationTest {

    @Autowired
    SmsSettings settings;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    SmsEvents events;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void everyMemberEventQueuedOnStagingEndsSunkAndNothingIsSent() {
        assertThat(settings.environment()).isEqualTo("staging");
        assertThat(settings.mode()).isEqualTo(SmsSettings.Mode.SINK);
        assertThat(dispatcher.enabledChannels()).contains("sms");

        TestDatabase.Fixture t = TestDatabase.tenant("smsev-stg", true);
        SmsTenants.operator(t.tenantId(), true, 1000);
        SmsTenants.enable(t.tenantId());
        Map<String, Map<String, String>> byEvent = Map.of(
                "loan.disbursed",
                Map.of(
                        "loan_no",
                        "LN-HQ-000001",
                        "currency",
                        "UGX",
                        "amount",
                        "500,000",
                        "date",
                        "15/10/2026",
                        "next_amount",
                        "600,000",
                        "next_due_date",
                        "15/11/2026"),
                "loan.repayment_received",
                Map.of(
                        "loan_no",
                        "LN-HQ-000001",
                        "currency",
                        "UGX",
                        "amount",
                        "100,000",
                        "date",
                        "15/10/2026",
                        "receipt_no",
                        "RC-HQ-000001"),
                "loan.closed",
                Map.of("loan_no", "LN-HQ-000001"),
                "investment.funded",
                Map.of(
                        "account_no",
                        "IV000001",
                        "currency",
                        "UGX",
                        "amount",
                        "1,000,000",
                        "date",
                        "15/10/2026",
                        "maturity_date",
                        "15/04/2027",
                        "receipt_no",
                        "RC-HQ-000002"),
                "investment.maturing",
                Map.of(
                        "account_no",
                        "IV000001",
                        "currency",
                        "UGX",
                        "amount",
                        "1,000,000",
                        "maturity_date",
                        "15/04/2027"),
                "investment.paid_out",
                Map.of(
                        "account_no",
                        "IV000001",
                        "currency",
                        "UGX",
                        "amount",
                        "1,060,000",
                        "date",
                        "15/04/2027",
                        "voucher_no",
                        "VC-HQ-000001"));
        // A mobile number that looks like anyone's: on staging the sink takes it, and no network call exists to make.
        TenantContext.callAs(
                t.tenantId(),
                () -> new TransactionTemplate(transactionManager).execute(tx -> {
                    byEvent.forEach((template, params) -> assertThat(events.queue(new MemberSms(
                                    template,
                                    UUID.randomUUID(),
                                    "0700000301",
                                    params,
                                    "stg-" + template,
                                    "investment.maturing".equals(template) ? Outbox.REMINDER : null)))
                            .as(template)
                            .isEqualTo(Result.QUEUED));
                    return null;
                }));

        // The reminder waits for its window (the clock here is real, so it may or may not be inside it); a receipt does
        // not.
        dispatcher.dispatchOnly(Outbox.SMS, 50, null);

        List<Map<String, Object>> deliveries = TestDatabase.owner()
                .sql(
                        "SELECT template_key, status, provider, cost_minor, tenant_id FROM sms_deliveries WHERE tenant_id = ?")
                .param(t.tenantId())
                .query()
                .listOfRows();
        assertThat(deliveries).isNotEmpty();
        assertThat(deliveries).allSatisfy(d -> {
            assertThat(d.get("status")).as("%s", d.get("template_key")).isEqualTo("sunk");
            assertThat(d.get("provider")).isEqualTo("sink");
            assertThat(d.get("cost_minor")).isNull();
            assertThat(d.get("tenant_id")).isEqualTo(t.tenantId());
        });
        assertThat(deliveries.stream().map(d -> d.get("template_key")))
                .contains(
                        "loan.disbursed",
                        "loan.repayment_received",
                        "loan.closed",
                        "investment.funded",
                        "investment.paid_out");
        assertThat(TestDatabase.owner()
                        .sql("SELECT count(*) FROM sms_deliveries WHERE tenant_id = ? AND status NOT IN ('sunk')")
                        .param(t.tenantId())
                        .query(Long.class)
                        .single())
                .isZero();
    }
}
