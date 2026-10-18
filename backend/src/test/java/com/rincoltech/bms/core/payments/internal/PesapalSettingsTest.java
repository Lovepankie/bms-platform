package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.payments.PaymentGateway;
import com.rincoltech.bms.core.payments.PaymentGateway.Scope;
import com.rincoltech.bms.core.payments.internal.PesapalSettings.Kind;
import com.rincoltech.bms.kernel.BusinessClock;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/**
 * The startup guard (ADR-035 decision 10 and 13, FR-PAY-08, spec 8 "startup refusal tests"), tested the way the SMS
 * staging guard is: outside production the only base URL that may be set is the provider's sandbox, in every mode,
 * so staging can never move real money; production refuses the sandbox and the fake; and no refusal message names a
 * value, a key, a secret or a token. The keys and secrets below are invented strings.
 */
class PesapalSettingsTest {

    static final String SANDBOX = "https://cybqa.pesapal.com/pesapalv3";
    static final String LIVE = "https://pay.pesapal.com/v3";
    static final String KEY = "invented-consumer-key-0001";
    static final String SECRET = "invented-consumer-secret-0001";
    static final String TOKEN = "invented-tenant-path-token-0001";
    static final String PLATFORM_TOKEN = "invented-platform-path-token-0002";
    static final String BOUND = "9d3f6a44-2b0c-4c63-8f0d-5b2f1d0a7e11";

    static PesapalProperties props(String provider, String baseUrl) {
        return new PesapalProperties(
                provider,
                baseUrl,
                KEY,
                SECRET,
                "aaaaaaaa-0000-4000-8000-000000000001",
                TOKEN,
                BOUND,
                "https://callbacks.example.test",
                new PesapalProperties.Platform(
                        "invented-platform-key",
                        "invented-platform-secret",
                        "aaaaaaaa-0000-4000-8000-000000000002",
                        PLATFORM_TOKEN));
    }

    static PesapalProperties bare(String provider, String baseUrl) {
        return new PesapalProperties(provider, baseUrl, null, null, null, null, null, null, null);
    }

    // ---- outside production: the sandbox only, in every mode ---------------------------------------------------

    @Test
    void outsideProductionTheLiveUrlIsRefusedInEveryModeAndEveryEnvironmentName() {
        for (String environment : new String[] {"staging", "", null, "dev", "test", "Staging", "preview"}) {
            for (String provider : new String[] {null, "", "off", "pesapal", "fake"}) {
                assertThatThrownBy(() -> PesapalSettings.resolve(environment, props(provider, LIVE)))
                        .as("env=%s provider=%s", environment, provider)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("sandbox outside production");
            }
        }
        // Even with nothing else configured: a live URL alone is a refusal.
        assertThatThrownBy(() -> PesapalSettings.resolve("staging", bare(null, LIVE)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void outsideProductionTheSandboxIsAccepted() {
        for (String environment : new String[] {"staging", "", "dev"}) {
            PesapalSettings s = PesapalSettings.resolve(environment, props("pesapal", SANDBOX));
            assertThat(s.baseUrl().getHost()).isEqualTo("cybqa.pesapal.com");
            assertThat(s.baseUrl().getPath()).isEqualTo("/pesapalv3");
            assertThat(s.provider()).isEqualTo(Kind.PESAPAL);
        }
        assertThat(PesapalSettings.resolve("staging", props("pesapal", SANDBOX + "/"))
                        .baseUrl()
                        .toString())
                .isEqualTo(SANDBOX);
        assertThat(PesapalSettings.resolve("staging", props("pesapal", "https://cybqa.pesapal.com"))
                        .baseUrl()
                        .toString())
                .isEqualTo(SANDBOX);
    }

    @Test
    void anUnsetBaseUrlMeansTheSandboxOutsideProductionAndTheLiveApiInProduction() {
        assertThat(PesapalSettings.resolve("staging", props("pesapal", null))
                        .baseUrl()
                        .toString())
                .isEqualTo(SANDBOX);
        assertThat(PesapalSettings.resolve("", props("pesapal", " ")).baseUrl().toString())
                .isEqualTo(SANDBOX);
        assertThat(PesapalSettings.resolve("production", props("pesapal", null))
                        .baseUrl()
                        .toString())
                .isEqualTo(LIVE);
    }

    @Test
    void noLookAlikeHostPortSchemeOrUserInfoPasses() {
        for (String url : new String[] {
            "https://cybqa.pesapal.com.evil.test/pesapalv3",
            "https://evil.test/cybqa.pesapal.com",
            "https://cybqa.pesapal.com@evil.test/pesapalv3",
            "https://user:pw@cybqa.pesapal.com/pesapalv3",
            "https://cybqa.pesapal.com:8443/pesapalv3",
            "http://cybqa.pesapal.com/pesapalv3",
            "https://cybqa.pesapal.com/other",
            "https://cybqa.pesapal.com/pesapalv3?x=1",
            "https://CYBQA.pesapal.com.evil.test",
            "https://pesapal.com/pesapalv3",
            "https://pay.pesapal.com/v3",
            "https://pay.pesapal.com.evil.test/v3",
            "not a url",
            "ftp://cybqa.pesapal.com/pesapalv3",
            "https://localhost:8443/pesapalv3",
            "http://127.0.0.1:9000/pesapalv3"
        }) {
            assertThatThrownBy(() -> PesapalSettings.resolve("staging", props("pesapal", url)))
                    .as(url)
                    .isInstanceOf(IllegalStateException.class);
        }
        for (String url : new String[] {
            "https://pay.pesapal.com.evil.test/v3",
            "https://pay.pesapal.com@evil.test/v3",
            "http://pay.pesapal.com/v3",
            "https://pay.pesapal.com:8443/v3",
            "https://pay.pesapal.com/other",
            "https://evil.test/v3",
            "https://localhost/v3"
        }) {
            assertThatThrownBy(() -> PesapalSettings.resolve("production", props("pesapal", url)))
                    .as(url)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ---- production --------------------------------------------------------------------------------------------

    @Test
    void productionRefusesTheSandboxAndTheFakeAndAcceptsTheLiveApi() {
        assertThatThrownBy(() -> PesapalSettings.resolve("production", props("pesapal", SANDBOX)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not use the Pesapal sandbox");
        assertThatThrownBy(() -> PesapalSettings.resolve("production", props("fake", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fake is not allowed in production");
        assertThatThrownBy(() -> PesapalSettings.resolve(" Production ", props("fake", LIVE)))
                .isInstanceOf(IllegalStateException.class);
        PesapalSettings live = PesapalSettings.resolve("production", props("pesapal", LIVE));
        assertThat(live.baseUrl().getHost()).isEqualTo("pay.pesapal.com");
        assertThat(live.configured(Scope.TENANT)).isTrue();
        assertThat(live.configured(Scope.PLATFORM)).isTrue();
    }

    // ---- the rest of the configuration -------------------------------------------------------------------------

    @Test
    void nothingConfiguredMeansTheGatewayIsOffAndNothingFails() {
        PesapalSettings off = PesapalSettings.resolve("staging", bare(null, null));
        assertThat(off.provider()).isEqualTo(Kind.OFF);
        assertThat(off.configured(Scope.TENANT)).isFalse();
        assertThat(off.configured(Scope.PLATFORM)).isFalse();
        assertThat(off.boundTenant()).isNull();
        assertThat(PesapalSettings.resolve("production", bare("off", null)).configured(Scope.TENANT))
                .isFalse();
    }

    @Test
    void credentialsWithoutAnExplicitProviderMeanPesapalAndAHalfConfiguredPairIsNotConfigured() {
        assertThat(PesapalSettings.resolve("staging", props(null, null)).provider())
                .isEqualTo(Kind.PESAPAL);
        PesapalProperties noCallbackHost = new PesapalProperties(
                "pesapal", null, KEY, SECRET, "aaaaaaaa-0000-4000-8000-000000000001", TOKEN, BOUND, null, null);
        assertThat(PesapalSettings.resolve("staging", noCallbackHost).configured(Scope.TENANT))
                .isFalse();
        PesapalProperties noSecret = new PesapalProperties(
                "pesapal",
                null,
                KEY,
                null,
                "aaaaaaaa-0000-4000-8000-000000000001",
                TOKEN,
                BOUND,
                "https://callbacks.example.test",
                null);
        assertThat(PesapalSettings.resolve("staging", noSecret).configured(Scope.TENANT))
                .isFalse();
    }

    @Test
    void theFakeIsConfiguredWithoutAnyCredentialsOutsideProduction() {
        PesapalSettings fake = PesapalSettings.resolve("staging", bare("fake", null));
        assertThat(fake.provider()).isEqualTo(Kind.FAKE);
        assertThat(fake.configured(Scope.TENANT)).isTrue();
    }

    @Test
    void anUnknownProviderNameIsRefused() {
        assertThatThrownBy(() -> PesapalSettings.resolve("staging", bare("stripe", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BMS_PESAPAL_PROVIDER");
    }

    @Test
    void theBoundTenantMustBeATenantIdAndIsKept() {
        assertThat(PesapalSettings.resolve("staging", props("pesapal", null)).boundTenant())
                .isEqualTo(UUID.fromString(BOUND));
        PesapalProperties typo = new PesapalProperties(
                "pesapal", null, KEY, SECRET, "aaaaaaaa-0000-4000-8000-000000000001", TOKEN, "demo-tenant", null, null);
        assertThatThrownBy(() -> PesapalSettings.resolve("staging", typo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BMS_PESAPAL_TENANT_ID");
    }

    @Test
    void theCallbackBaseMustBeAPublicHttpsOrigin() {
        for (String bad : new String[] {
            "http://callbacks.example.test",
            "https://localhost",
            "https://127.0.0.1",
            "https://10.0.0.5",
            "https://192.168.1.9",
            "https://172.16.0.1",
            "https://172.31.255.254",
            "https://169.254.169.254",
            "https://host.internal",
            "https://printer.local",
            "https://callbacks.example.test/with/path",
            "https://user@callbacks.example.test",
            "https://callbacks.example.test?x=1",
            "https://callbacks.example.test#frag"
        }) {
            PesapalProperties p = new PesapalProperties(
                    "pesapal", null, KEY, SECRET, "aaaaaaaa-0000-4000-8000-000000000001", TOKEN, BOUND, bad, null);
            assertThatThrownBy(() -> PesapalSettings.resolve("staging", p))
                    .as(bad)
                    .isInstanceOf(IllegalStateException.class);
        }
        PesapalProperties ok = new PesapalProperties(
                "pesapal",
                null,
                KEY,
                SECRET,
                "aaaaaaaa-0000-4000-8000-000000000001",
                TOKEN,
                BOUND,
                "https://callbacks.example.test/",
                null);
        assertThat(PesapalSettings.resolve("staging", ok).callbackBase().toString())
                .isEqualTo("https://callbacks.example.test");
        assertThat(PesapalSettings.publicHttps(java.net.URI.create("https://172.15.0.1/x")))
                .isTrue();
        assertThat(PesapalSettings.publicHttps(java.net.URI.create("https://172.32.0.1/x")))
                .isTrue();
    }

    @Test
    void anIpnPathTokenMustBeLongAndPathSafeAndTheTwoPairsMustDiffer() {
        for (String bad : new String[] {
            "short", "has space in it 0123456789abcdef", "slash/inside/0123456789abcdefgh", "q?uery0123456789abcdefghij"
        }) {
            PesapalProperties p = new PesapalProperties(
                    "pesapal", null, KEY, SECRET, "aaaaaaaa-0000-4000-8000-000000000001", bad, BOUND, null, null);
            assertThatThrownBy(() -> PesapalSettings.resolve("staging", p))
                    .as(bad)
                    .isInstanceOf(IllegalStateException.class);
        }
        PesapalProperties sameToken = new PesapalProperties(
                "pesapal",
                null,
                KEY,
                SECRET,
                "aaaaaaaa-0000-4000-8000-000000000001",
                TOKEN,
                BOUND,
                null,
                new PesapalProperties.Platform("k", "s", "aaaaaaaa-0000-4000-8000-000000000002", TOKEN));
        assertThatThrownBy(() -> PesapalSettings.resolve("staging", sameToken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different IPN path tokens");
        PesapalProperties sameIpnId = new PesapalProperties(
                "pesapal",
                null,
                KEY,
                SECRET,
                "aaaaaaaa-0000-4000-8000-000000000001",
                TOKEN,
                BOUND,
                null,
                new PesapalProperties.Platform("k", "s", "aaaaaaaa-0000-4000-8000-000000000001", PLATFORM_TOKEN));
        assertThatThrownBy(() -> PesapalSettings.resolve("staging", sameIpnId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different IPN ids");
    }

    @Test
    void thePathTokenSelectsTheCredentialPairInConstantTimeAndNothingElseMatches() {
        PesapalSettings s = PesapalSettings.resolve("staging", props("pesapal", null));
        assertThat(s.scopeOfToken(TOKEN)).contains(Scope.TENANT);
        assertThat(s.scopeOfToken(PLATFORM_TOKEN)).contains(Scope.PLATFORM);
        for (String other :
                new String[] {null, "", "x", TOKEN + "x", TOKEN.substring(1), TOKEN.toUpperCase(), "x".repeat(300)}) {
            assertThat(s.scopeOfToken(other)).as("%s", other).isEmpty();
        }
        // A host with no platform pair has no platform token: an empty configured value never matches an empty
        // presented one.
        PesapalSettings noPlatform = PesapalSettings.resolve(
                "staging",
                new PesapalProperties(
                        "pesapal",
                        null,
                        KEY,
                        SECRET,
                        "aaaaaaaa-0000-4000-8000-000000000001",
                        TOKEN,
                        BOUND,
                        null,
                        null));
        assertThat(noPlatform.scopeOfToken("")).isEmpty();
        assertThat(noPlatform.scopeOfToken(PLATFORM_TOKEN)).isEmpty();
    }

    // ---- no value in any message or printed form ---------------------------------------------------------------

    @Test
    void noRefusalAndNoPrintedFormNamesAKeyASecretATokenOrAUrl() {
        List<String> secrets = List.of(
                KEY,
                SECRET,
                TOKEN,
                PLATFORM_TOKEN,
                "invented-platform-key",
                "invented-platform-secret",
                "evil.test",
                BOUND);
        List<Throwable> refusals = List.of(
                catchRefusal(() -> PesapalSettings.resolve("staging", props("pesapal", "https://evil.test/v3"))),
                catchRefusal(() -> PesapalSettings.resolve("production", props("pesapal", SANDBOX))),
                catchRefusal(() -> PesapalSettings.resolve("production", props("fake", null))),
                catchRefusal(() -> PesapalSettings.resolve(
                        "staging",
                        new PesapalProperties(
                                "pesapal",
                                null,
                                KEY,
                                SECRET,
                                "aaaaaaaa-0000-4000-8000-000000000001",
                                "has space " + TOKEN,
                                BOUND,
                                null,
                                null))),
                catchRefusal(() -> PesapalSettings.resolve(
                        "staging",
                        new PesapalProperties(
                                "pesapal",
                                null,
                                KEY,
                                SECRET,
                                "aaaaaaaa-0000-4000-8000-000000000001",
                                TOKEN,
                                BOUND,
                                "https://evil.test/path",
                                null))));
        for (Throwable t : refusals) {
            for (String secret : secrets) {
                assertThat(t.getMessage()).doesNotContain(secret);
            }
        }
        PesapalSettings s = PesapalSettings.resolve("staging", props("pesapal", null));
        for (Object printed : new Object[] {
            s,
            s.tenantPair(),
            s.platformPair(),
            props("pesapal", null),
            props("pesapal", null).platform()
        }) {
            for (String secret : secrets) {
                assertThat(printed.toString()).doesNotContain(secret);
            }
        }
    }

    private static Throwable catchRefusal(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("expected a refusal");
    }

    // ---- the guard at application start, as a context would see it ----------------------------------------------

    private ApplicationContextRunner runner(String environment, String... properties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(PaymentsConfiguration.class)
                .withBean(BusinessClock.class, () -> new BusinessClock(Clock.systemUTC()))
                .withBean(
                        tools.jackson.databind.ObjectMapper.class,
                        () -> JsonMapper.builder().build())
                .withPropertyValues("bms.environment=" + environment)
                .withPropertyValues(properties);
    }

    @Test
    void stagingAskingForTheLiveUrlDoesNotStart() {
        runner(
                        "staging",
                        "bms.pesapal.base-url=" + LIVE,
                        "bms.pesapal.consumer-key=" + KEY,
                        "bms.pesapal.consumer-secret=" + SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootMessage(context.getStartupFailure()))
                            .contains("Pesapal configuration refused")
                            .doesNotContain(KEY)
                            .doesNotContain(SECRET);
                });
    }

    @Test
    void productionWithTheFakeOrTheSandboxDoesNotStart() {
        runner("production", "bms.pesapal.provider=fake")
                .run(context -> assertThat(context).hasFailed());
        runner("production", "bms.pesapal.base-url=" + SANDBOX)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void anUnconfiguredHostStartsWithTheGatewayOffAndASandboxConfiguredStagingStartsWithTheAdapter() {
        runner("staging").run(context -> {
            assertThat(context).hasNotFailed();
            PaymentGateway gateway = context.getBean(PaymentGateway.class);
            assertThat(gateway.available(Scope.TENANT)).isFalse();
            assertThat(gateway).isInstanceOf(PaymentsConfiguration.UnavailableGateway.class);
        });
        runner(
                        "staging",
                        "bms.pesapal.base-url=" + SANDBOX,
                        "bms.pesapal.consumer-key=" + KEY,
                        "bms.pesapal.consumer-secret=" + SECRET,
                        "bms.pesapal.ipn-id=aaaaaaaa-0000-4000-8000-000000000001",
                        "bms.pesapal.ipn-token=" + TOKEN,
                        "bms.pesapal.callback-base-url=https://callbacks.example.test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PaymentGateway gateway = context.getBean(PaymentGateway.class);
                    assertThat(gateway).isInstanceOf(PesapalGateway.class);
                    assertThat(gateway.available(Scope.TENANT)).isTrue();
                    assertThat(gateway.available(Scope.PLATFORM)).isFalse();
                    assertThat(gateway.toString())
                            .contains("cybqa.pesapal.com")
                            .doesNotContain(KEY)
                            .doesNotContain(SECRET);
                });
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return String.valueOf(c.getMessage());
    }
}
