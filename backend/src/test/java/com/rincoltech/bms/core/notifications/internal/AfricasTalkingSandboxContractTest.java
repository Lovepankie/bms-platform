package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

/**
 * The contract test of the Africa's Talking adapter against the provider's <b>sandbox</b> (spec
 * section 8, ADR-036): one real message to the sandbox, to confirm that the answer format and the
 * status codes the adapter reads are the provider's. It never runs in the normal build: it needs
 * {@code BMS_SMS_CONTRACT=true}, the sandbox key in {@code BMS_SMS_AT_API_KEY} and a sandbox test
 * number in {@code BMS_SMS_CONTRACT_TO}, which the manual workflow {@code sms-sandbox-contract}
 * provides. It builds its settings through the startup guard, as a staging host would, so it also
 * proves the guard accepts exactly the sandbox host and account. Never run it against production.
 */
@EnabledIfEnvironmentVariable(named = "BMS_SMS_CONTRACT", matches = "true")
class AfricasTalkingSandboxContractTest {

    @Test
    void theSandboxAcceptsAMessageAndAnswersInTheShapeTheAdapterReads() throws Exception {
        SmsSettings settings = SmsSettings.resolve(
                "staging",
                new SmsProperties(
                        "africastalking",
                        "allowlist",
                        System.getenv("BMS_SMS_CONTRACT_TO"),
                        null,
                        new SmsProperties.AfricasTalking(
                                "https://api.sandbox.africastalking.com",
                                "sandbox",
                                System.getenv("BMS_SMS_AT_API_KEY"))));
        assertThat(settings.providerConfigured()).isTrue();
        AfricasTalkingSmsProvider provider = new AfricasTalkingSmsProvider(
                settings.baseUrl(), settings.username(), settings.apiKey(), new ObjectMapper());

        SmsProvider.Outcome outcome = provider.send(
                settings.allowlist().iterator().next(),
                "Test Lender: contract check, please ignore.",
                null,
                "contract-1");

        assertThat(outcome.status()).isEqualTo(SmsProvider.Status.ACCEPTED);
        assertThat(outcome.messageId()).isNotBlank();
        assertThat(outcome.costRaw()).isNotBlank();
        // The cost string must be one the parser reads (a currency, a space, a decimal).
        assertThat(SmsCost.parse(outcome.costRaw())).isPresent();
    }
}
