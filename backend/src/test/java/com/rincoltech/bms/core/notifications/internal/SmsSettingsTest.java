package com.rincoltech.bms.core.notifications.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.notifications.internal.SmsSettings.Mode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The startup guard of ADR-036 decision 4 (FR-NTF-12, FR-NTF-18, spec section 4.4): staging must
 * never be able to text a real phone. Each refusal is a case here, in every mode, and the same
 * refusal stops a Spring context from starting through the real property names.
 */
class SmsSettingsTest {

    static final String SANDBOX = "https://api.sandbox.africastalking.com";
    static final String LIVE = "https://api.africastalking.com";

    static SmsProperties props(String provider, String mode, String baseUrl, String username, String allowlist) {
        return new SmsProperties(
                provider, mode, allowlist, null, new SmsProperties.AfricasTalking(baseUrl, username, "test-api-key"));
    }

    /** Acceptance: staging refuses the live provider URL in allowlist mode, and in every other mode too. */
    @ParameterizedTest
    @ValueSource(strings = {"off", "sink", "allowlist", "live", ""})
    void stagingRefusesTheLiveUrlInEveryMode(String mode) {
        assertThatThrownBy(() -> SmsSettings.resolve("staging", props("africastalking", mode, LIVE, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BMS_SMS_AT_BASE_URL")
                .hasMessageNotContaining(LIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "sink", "allowlist", "live", ""})
    void stagingRefusesAnyAccountButTheSandboxInEveryMode(String mode) {
        assertThatThrownBy(() -> SmsSettings.resolve("staging", props("africastalking", mode, SANDBOX, "acme", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BMS_SMS_AT_USERNAME")
                .hasMessageNotContaining("acme");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://api.africastalking.com/",
                "https://api.sandbox.africastalking.com.evil.test",
                "https://api.sandbox.africastalking.com@evil.test",
                "https://evil.test/api.sandbox.africastalking.com",
                "http://api.sandbox.africastalking.com",
                "https://api.sandbox.africastalking.com:8443",
                "https://sandbox.africastalking.com",
                "not a url"
            })
    void aLookAlikeOfTheSandboxHostIsNotTheSandbox(String url) {
        assertThatThrownBy(() -> SmsSettings.resolve("staging", props("africastalking", "off", url, null, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stagingAcceptsTheSandboxAndDefaultsToTheSink() {
        SmsSettings s = SmsSettings.resolve("staging", props("africastalking", null, SANDBOX, "sandbox", null));
        assertThat(s.mode()).isEqualTo(Mode.SINK);
        assertThat(s.baseUrl().getHost()).isEqualTo("api.sandbox.africastalking.com");
        // With nothing set at all, staging is the sink and the provider is the sandbox by default.
        SmsSettings bare = SmsSettings.resolve("staging", props("africastalking", null, null, null, null));
        assertThat(bare.mode()).isEqualTo(Mode.SINK);
        assertThat(bare.baseUrl().getHost()).isEqualTo("api.sandbox.africastalking.com");
        assertThat(bare.providerConfigured()).isFalse();
        // The allow-list is possible on staging against the sandbox.
        assertThat(SmsSettings.resolve(
                                "staging",
                                props("africastalking", "allowlist", SANDBOX, "sandbox", "+256700000001, 0700000002"))
                        .allowlist())
                .containsExactlyInAnyOrder("+256700000001", "+256700000002");
    }

    @Test
    void stagingRefusesLiveAndEveryOtherNonProductionEnvironmentToo() {
        for (String env : new String[] {"staging", "", "dev", "local", "test", "preproduction"}) {
            assertThatThrownBy(
                            () -> SmsSettings.resolve(env, props("africastalking", "live", SANDBOX, "sandbox", null)))
                    .as("live in %s", env)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("live");
            assertThatThrownBy(() -> SmsSettings.resolve(env, props("africastalking", "off", LIVE, null, null)))
                    .as("live URL in %s", env)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void productionRefusesTheSinkTheFakeAndTheSandbox() {
        assertThatThrownBy(() -> SmsSettings.resolve("production", props("africastalking", "sink", null, null, null)))
                .hasMessageContaining("sink");
        assertThatThrownBy(() -> SmsSettings.resolve("production", props("fake", "live", null, null, null)))
                .hasMessageContaining("fake");
        assertThatThrownBy(() -> SmsSettings.resolve("production", props("fake", "off", null, null, null)))
                .hasMessageContaining("fake");
        assertThatThrownBy(
                        () -> SmsSettings.resolve("production", props("africastalking", "live", SANDBOX, null, null)))
                .hasMessageContaining("sandbox");
        assertThatThrownBy(() ->
                        SmsSettings.resolve("production", props("africastalking", "allowlist", LIVE, "sandbox", null)))
                .hasMessageContaining("sandbox");
    }

    @Test
    void productionMayGoLiveOrCheckWithAnAllowListOnTheLiveHost() {
        SmsSettings live = SmsSettings.resolve("production", props("africastalking", "live", null, "acme", null));
        assertThat(live.mode()).isEqualTo(Mode.LIVE);
        assertThat(live.baseUrl().getHost()).isEqualTo("api.africastalking.com");
        assertThat(live.providerConfigured()).isTrue();
        SmsSettings pilot =
                SmsSettings.resolve("production", props("africastalking", "allowlist", LIVE, "acme", "+256700000003"));
        assertThat(pilot.mode()).isEqualTo(Mode.ALLOWLIST);
        // Production defaults to off, never to a mode that sends.
        assertThat(SmsSettings.resolve("production", props(null, null, null, null, null))
                        .mode())
                .isEqualTo(Mode.OFF);
    }

    @Test
    void unsetVariablesTurnTheAdapterOffWithoutFailing() {
        SmsSettings none = SmsSettings.resolve("", props(null, null, null, null, null));
        assertThat(none.mode()).isEqualTo(Mode.OFF);
        assertThat(none.providerConfigured()).isFalse();
        assertThatCode(() -> SmsSettings.resolve("production", props("africastalking", "live", null, null, null)))
                .doesNotThrowAnyException();
        assertThat(SmsSettings.resolve("production", props("africastalking", "live", null, null, null))
                        .providerConfigured())
                .isFalse();
        assertThat(SmsConfiguration.providerFor(SmsSettings.resolve("", props(null, null, null, null, null)), null))
                .isNull();
        assertThat(SmsConfiguration.providerFor(
                        SmsSettings.resolve("production", props("africastalking", "live", null, null, null)), null))
                .isNull();
    }

    @Test
    void unknownNamesAndBadAllowListEntriesAreRefusedWithoutEchoingThem() {
        assertThatThrownBy(() -> SmsSettings.resolve("staging", props("twilio", null, null, null, null)))
                .hasMessageContaining("BMS_SMS_PROVIDER")
                .hasMessageNotContaining("twilio");
        assertThatThrownBy(() -> SmsSettings.resolve("staging", props(null, "broadcast", null, null, null)))
                .hasMessageContaining("BMS_SMS_MODE");
        assertThatThrownBy(() -> SmsSettings.resolve(
                        "staging", props(null, "allowlist", null, null, "+256700000001, 0712345678901")))
                .hasMessageContaining("entry 2")
                .hasMessageNotContaining("0712345678901");
        assertThatThrownBy(() -> SmsSettings.resolve(
                        "staging",
                        new SmsProperties(null, null, null, -1, new SmsProperties.AfricasTalking(null, null, null))))
                .hasMessageContaining("BMS_SMS_PLATFORM_DAILY_CAP");
    }

    @Test
    void theApiKeyIsNeverPrinted() {
        SmsProperties p = props("africastalking", "off", SANDBOX, "sandbox", null);
        assertThat(p.toString()).doesNotContain("test-api-key");
        assertThat(p.africasTalking().toString()).doesNotContain("test-api-key");
        assertThat(SmsSettings.resolve("staging", p).toString()).doesNotContain("test-api-key");
    }

    // ---- The same refusal, through Spring's property binding ----------------------------------

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SmsProperties.class)
    static class GuardOnly {

        @Bean
        SmsSettings smsSettings(SmsProperties properties, @Value("${bms.environment:}") String environment) {
            return new SmsConfiguration().smsSettings(properties, environment);
        }
    }

    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(GuardOnly.class);
    }

    /** Startup test (spec section 8): a staging context with the live URL does not start, in any mode. */
    @ParameterizedTest
    @ValueSource(strings = {"off", "sink", "allowlist", "live"})
    void aStagingContextWithTheLiveUrlDoesNotStart(String mode) {
        runner().withPropertyValues(
                        "bms.environment=staging",
                        "bms.sms.mode=" + mode,
                        "bms.sms.provider=africastalking",
                        "bms.sms.africas-talking.base-url=" + LIVE,
                        "bms.sms.africas-talking.username=sandbox",
                        "bms.sms.africas-talking.api-key=test-api-key")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("SMS configuration refused");
                    assertThat(context.getStartupFailure().toString()).doesNotContain("test-api-key");
                });
    }

    @Test
    void aStagingContextWithTheLiveAccountDoesNotStartButTheSandboxDoes() {
        runner().withPropertyValues(
                        "bms.environment=staging",
                        "bms.sms.provider=africastalking",
                        "bms.sms.africas-talking.username=acme")
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues(
                        "bms.environment=staging",
                        "bms.sms.provider=africastalking",
                        "bms.sms.africas-talking.base-url=" + SANDBOX,
                        "bms.sms.africas-talking.username=sandbox",
                        "bms.sms.africas-talking.api-key=test-api-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SmsSettings.class).mode()).isEqualTo(Mode.SINK);
                });
    }

    @Test
    void aProductionContextWithTheSinkOrTheFakeDoesNotStart() {
        runner().withPropertyValues("bms.environment=production", "bms.sms.mode=sink")
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("bms.environment=production", "bms.sms.provider=fake")
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("bms.environment=production")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
