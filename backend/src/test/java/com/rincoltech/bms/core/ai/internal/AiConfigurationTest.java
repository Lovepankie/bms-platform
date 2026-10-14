package com.rincoltech.bms.core.ai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.ai.RecordingLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/**
 * The non-sending guard (spec 5.3, issue #220): which client a configuration yields. A host without a
 * key, a half-set provider, a key with a stray character and a plain http URL all give the
 * unavailable client, so nothing calls out; the recording stub is refused outside the dev and test
 * profiles.
 */
class AiConfigurationTest {

    static final String KEY = "test-key-0000-fabricated";

    final AiConfiguration configuration = new AiConfiguration();

    static AiProperties props(String provider, String baseUrl, String key, String model) {
        return new AiProperties(
                provider, baseUrl, key, model, null, null, null, null, null, null, null, null, null, null, null);
    }

    LlmClient choose(AiProperties p, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return configuration.llmClient(p, env, JsonMapper.builder().build());
    }

    @Test
    void theDefaultIsNoProviderAndNothingToCall() {
        LlmClient client = choose(props(null, null, null, null));
        assertThat(client.available()).isFalse();
    }

    @Test
    void anOffProviderIsUnavailableEvenWithEveryValueSet() {
        assertThat(choose(props("off", "https://llm.example.test", KEY, "m")).available())
                .isFalse();
    }

    @Test
    void aHalfSetProviderIsOffNotAStartupFailure() {
        assertThat(choose(props("openai_compatible", "https://llm.example.test", null, "m"), "server")
                        .available())
                .isFalse();
        assertThat(choose(props("openai_compatible", "https://llm.example.test", KEY, ""), "server")
                        .available())
                .isFalse();
        assertThat(choose(props("openai_compatible", "", KEY, "m"), "server").available())
                .isFalse();
    }

    @Test
    void aCompleteProviderYieldsTheAdapterWithTheConfiguredModel() {
        LlmClient client =
                choose(props("openai_compatible", "https://llm.example.test", KEY, "example-cheap-chat"), "server");
        assertThat(client.available()).isTrue();
        assertThat(client.model()).isEqualTo("example-cheap-chat");
        assertThat(client).isInstanceOf(OpenAiCompatibleClient.class);
    }

    @Test
    void aKeyWithAStrayLineBreakSwitchesTheProviderOff() {
        assertThat(choose(props("openai_compatible", "https://llm.example.test", KEY + "\r\n", "m"), "server")
                        .available())
                .isFalse();
        assertThat(choose(props("openai_compatible", "https://llm.example.test", "two words", "m"), "server")
                        .available())
                .isFalse();
    }

    @Test
    void aBaseUrlMustBeHttpsWithAHostAndNoCredentials() {
        for (String bad : new String[] {
            "http://llm.example.test",
            "ftp://llm.example.test",
            "https://user:pw@llm.example.test",
            "https://",
            "llm.example.test",
            "https://llm.example.test?x=1",
            "http://127.0.0.1:9999"
        }) {
            assertThat(choose(props("openai_compatible", bad, KEY, "m"), "server")
                            .available())
                    .as(bad)
                    .isFalse();
        }
    }

    @Test
    void plainHttpToALoopbackAddressIsAcceptedInDevAndTestOnly() {
        AiProperties local = props("openai_compatible", "http://127.0.0.1:9999/v1", KEY, "m");
        assertThat(choose(local, "test").available()).isTrue();
        assertThat(choose(local, "dev").available()).isTrue();
        assertThat(choose(local, "server").available()).isFalse();
        // A non-loopback host is never accepted over http, even in test, and no DNS lookup decides it.
        assertThat(choose(props("openai_compatible", "http://llm.example.test", KEY, "m"), "test")
                        .available())
                .isFalse();
    }

    @Test
    void theRecordingStubIsRefusedOutsideDevAndTest() {
        assertThatThrownBy(() -> choose(props("recording", null, null, "m")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev and test");
        assertThatThrownBy(() -> choose(props("recording", null, null, "m"), "server"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> choose(props("recording", null, null, "m"), "production"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(choose(props("recording", null, null, "m"), "test")).isInstanceOf(RecordingLlmClient.class);
        assertThat(choose(props("recording", null, null, "m"), "dev")).isInstanceOf(RecordingLlmClient.class);
    }

    @Test
    void anUnknownProviderFailsStartup() {
        assertThatThrownBy(() -> choose(props("somethingelse", null, null, null), "test"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theUnavailableClientRefusesToCall() {
        LlmClient off = choose(props("off", null, null, null));
        assertThatThrownBy(() -> off.complete(
                        new LlmClient.Request(java.util.List.of(new LlmClient.Message("user", "hello")), 10, false)))
                .isInstanceOf(com.rincoltech.bms.core.ai.LlmException.class);
    }

    // ---- the arithmetic and the allow-list parsing --------------------------------------------------

    @Test
    void costIsRoundedUpInMicroUnitsFromTheOperationsPrices() {
        AiProperties p = new AiProperties(
                null, null, null, null, null, null, null, null, null, null, null, 270_000L, 1_100_000L, "UGX", null);
        // 1000 input tokens at 270000 per million = 270; 500 output tokens at 1100000 per million = 550.
        assertThat(p.costMicro(1000, 500)).isEqualTo(820);
        // Rounded up, never down: one token still costs one micro-unit.
        assertThat(p.costMicro(1, 0)).isEqualTo(1);
        assertThat(p.costMicro(0, 0)).isZero();
        assertThat(p.priceCurrency()).isEqualTo("UGX");
    }

    @Test
    void withNoPricesConfiguredCostIsZeroAndTheCurrencyDefaultsToUsd() {
        AiProperties p = props(null, null, null, null);
        assertThat(p.costMicro(1_000_000, 1_000_000)).isZero();
        assertThat(p.priceCurrency()).isEqualTo("USD");
    }

    @Test
    void theAllowListIsTenantIdsOnlyAndNeverSlugs() {
        String id = "11111111-2222-4333-8444-555555555555";
        AiProperties p = new AiProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                " " + id.toUpperCase() + " , alpha-co,,  not-a-uuid, 11111111-2222-4333-8444-55555555555");
        // A slug, a malformed id and a short id name nobody; a typo can only make the list shorter.
        assertThat(p.allowedTenants()).containsExactly(java.util.UUID.fromString(id));
        assertThat(props(null, null, null, null).allowedTenants()).isEmpty();
    }

    @Test
    void aCapOfZeroMeansStopAndANegativeOneFailsStartup() {
        AiProperties stop =
                new AiProperties(null, null, null, null, null, null, null, 0L, 0L, 0L, 0L, null, null, null, null);
        assertThat(stop.platformDailyTokens()).isZero();
        assertThat(stop.platformDailyCostMicro()).isZero();
        assertThat(stop.tenantDailyTokens()).isZero();
        assertThat(stop.tenantDailyCostMicro()).isZero();
        for (int field : new int[] {7, 8, 9, 10}) {
            Object[] args = new Object[15];
            args[field] = -1L;
            assertThatThrownBy(() -> construct(args))
                    .as("field %s", field)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aNegativePriceOrABadCurrencyFailsStartupInsteadOfQuietlyBecomingZeroOrUsd() {
        for (int field : new int[] {11, 12}) {
            Object[] args = new Object[15];
            args[field] = -1L;
            assertThatThrownBy(() -> construct(args))
                    .as("field %s", field)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String bad : new String[] {"US", "DOLLARS", "U5D", "12"}) {
            Object[] args = new Object[15];
            args[13] = bad;
            assertThatThrownBy(() -> construct(args)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        // Unset or blank is the documented default, and a lower case code is normalised.
        Object[] unset = new Object[15];
        unset[13] = " ";
        assertThat(construct(unset).priceCurrency()).isEqualTo("USD");
        Object[] lower = new Object[15];
        lower[13] = "ugx";
        assertThat(construct(lower).priceCurrency()).isEqualTo("UGX");
    }

    @Test
    void aPerCallLimitOfZeroOrLessFailsStartupInsteadOfBecomingTheDefault() {
        for (int field : new int[] {4, 5, 6}) {
            Object[] args = new Object[15];
            args[field] = 0;
            assertThatThrownBy(() -> construct(args))
                    .as("field %s", field)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static AiProperties construct(Object[] a) {
        return new AiProperties(
                (String) a[0],
                (String) a[1],
                (String) a[2],
                (String) a[3],
                (Integer) a[4],
                (Integer) a[5],
                (Integer) a[6],
                (Long) a[7],
                (Long) a[8],
                (Long) a[9],
                (Long) a[10],
                (Long) a[11],
                (Long) a[12],
                (String) a[13],
                (String) a[14]);
    }

    @Test
    void valuesLeftEmptyInTheEnvironmentTakeTheDefaultsAndDoNotBecomeZero() {
        // What the compose files pass when the operator sets nothing: set, but empty.
        java.util.Map<String, String> env = new java.util.HashMap<>();
        for (String key : new String[] {
            "budget-seconds",
            "max-tokens-per-call",
            "max-input-tokens-per-call",
            "platform-daily-tokens",
            "platform-daily-cost-micro",
            "tenant-daily-tokens",
            "tenant-daily-cost-micro",
            "price-input-micro-per-million",
            "price-output-micro-per-million"
        }) {
            env.put("bms.ai." + key, "");
        }
        env.put("bms.ai.provider", "off");
        AiProperties p = new org.springframework.boot.context.properties.bind.Binder(
                        new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(env))
                .bind("bms.ai", AiProperties.class)
                .get();
        assertThat(p.budgetSeconds()).isEqualTo(30);
        assertThat(p.maxTokensPerCall()).isEqualTo(800);
        assertThat(p.maxInputTokensPerCall()).isEqualTo(8000);
        assertThat(p.platformDailyTokens()).isEqualTo(200_000);
        assertThat(p.tenantDailyTokens()).isEqualTo(20_000);
        assertThat(p.tenantDailyCostMicro()).isEqualTo(500_000);
    }

    @Test
    void theDefaultsAreSmallAndBelowThePlatformCeiling() {
        AiProperties p = props(null, null, null, null);
        assertThat(p.maxTokensPerCall()).isEqualTo(800);
        assertThat(p.tenantDailyTokens()).isLessThan(p.platformDailyTokens());
        assertThat(p.tenantDailyCostMicro()).isLessThan(p.platformDailyCostMicro());
    }
}
