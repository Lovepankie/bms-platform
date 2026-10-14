package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.notifications.internal.SmsProvider.Ambiguous;
import com.rincoltech.bms.core.notifications.internal.SmsProvider.Outcome;
import com.rincoltech.bms.core.notifications.internal.SmsProvider.Status;
import com.rincoltech.bms.core.notifications.internal.SmsProvider.Transient;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The provider selection of ADR-036 decision 4: the sink sends nothing, the allow-list sends only to
 * its numbers and sinks the rest, the fake records and can fail, and the configuration builds the
 * provider that matches the mode.
 */
class SmsProvidersTest {

    static final String LISTED = "+256700000001";
    static final String OTHER = "+256700000002";

    @Test
    void theSinkAnswersSunkAndHoldsNothingThatCouldReachANetwork() {
        Outcome outcome = new SinkSmsProvider().send(LISTED, "Test text", null, "key-1");
        assertThat(outcome.status()).isEqualTo(Status.SUNK);
        assertThat(outcome.provider()).isEqualTo("sink");
        assertThat(outcome.costRaw()).isNull();
        // By construction: no field of the sink is a client, a URL or a credential.
        assertThat(SinkSmsProvider.class.getDeclaredFields())
                .filteredOn(f -> !f.isSynthetic())
                .allSatisfy(f -> assertThat(f.getName()).isEqualTo("NAME"));
    }

    @Test
    void theAllowListSendsOnlyToItsNumbersAndSinksTheRest() throws Exception {
        RecordingSmsProvider real = new RecordingSmsProvider();
        AllowlistSmsProvider provider = new AllowlistSmsProvider(real, new SinkSmsProvider(), Set.of(LISTED));
        assertThat(provider.send(LISTED, "Test text", null, "key-1").status()).isEqualTo(Status.ACCEPTED);
        assertThat(provider.send(OTHER, "Test text", null, "key-2").status()).isEqualTo(Status.SUNK);
        assertThat(real.sent()).extracting(RecordingSmsProvider.Sent::to).containsExactly(LISTED);
    }

    @Test
    void theFakeRecordsAndCanBeToldToFailOrTimeOut() throws Exception {
        RecordingSmsProvider fake = new RecordingSmsProvider();
        Outcome first = fake.send(LISTED, "Test text", "TESTCO", "key-1");
        assertThat(first.messageId()).isEqualTo("fake-1");
        assertThat(first.costRaw()).isEqualTo("UGX 32.0000");
        assertThat(fake.sent()).containsExactly(new RecordingSmsProvider.Sent(LISTED, "Test text", "TESTCO", "key-1"));
        fake.failWith("provider_http_503");
        assertThatThrownBy(() -> fake.send(LISTED, "x", null, "key-2")).isInstanceOf(Transient.class);
        fake.timeOut("provider_timeout");
        assertThatThrownBy(() -> fake.send(LISTED, "x", null, "key-3")).isInstanceOf(Ambiguous.class);
        // Every call is recorded, including the ones that failed.
        assertThat(fake.sent()).hasSize(3);
    }

    @Test
    void theConfigurationBuildsTheProviderThatMatchesTheMode() {
        ObjectMapper mapper = new ObjectMapper();
        assertThat(provider("staging", null, "fake", null)).isInstanceOf(SinkSmsProvider.class);
        assertThat(provider("staging", "off", "fake", null)).isNull();
        assertThat(provider("staging", "sink", "africastalking", null)).isInstanceOf(SinkSmsProvider.class);
        assertThat(provider("production", "live", "africastalking", null)).isNull();
        assertThat(SmsConfiguration.providerFor(
                        SmsSettings.resolve(
                                "staging", new SmsProperties("fake", "allowlist", "+256700000001", null, null)),
                        mapper))
                .isInstanceOf(AllowlistSmsProvider.class);
        assertThat(SmsConfiguration.providerFor(
                        SmsSettings.resolve(
                                "production",
                                new SmsProperties(
                                        "africastalking",
                                        "live",
                                        null,
                                        null,
                                        new SmsProperties.AfricasTalking(null, "acme", "test-api-key"))),
                        mapper))
                .isInstanceOf(AfricasTalkingSmsProvider.class);
    }

    private static SmsProvider provider(String env, String mode, String kind, String allow) {
        return SmsConfiguration.providerFor(
                SmsSettings.resolve(env, new SmsProperties(kind, mode, allow, null, null)), new ObjectMapper());
    }
}
