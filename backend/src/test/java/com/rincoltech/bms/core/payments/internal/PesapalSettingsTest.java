package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.core.payments.PaymentGateway;
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

    static PesapalProperties props(String provider, String baseUrl) {
        return new PesapalProperties(
                provider,
                baseUrl,
                "https://callbacks.example.test",
                new PesapalProperties.Platform(
                        "invented-platform-key",
                        "invented-platform-secret",
                        "aaaaaaaa-0000-4000-8000-000000000002",
                        PLATFORM_TOKEN),
                OWNED_DIGEST);
    }

    /** The SHA-256 of the invented consumer key "invented-owned-key-0001", which the platform operator owns. */
    static final String OWNED_DIGEST = "afbfcf8c44d688e3497cbcab588619eb0f2864ecf18c7d6800da4745d4eaa5d6";

    static PesapalProperties bare(String provider, String baseUrl) {
        return new PesapalProperties(provider, baseUrl, null, null);
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
        assertThat(live.tenantAccountsEnabled()).isTrue();
        assertThat(live.platformConfigured()).isTrue();
    }

    // ---- the rest of the configuration -------------------------------------------------------------------------

    @Test
    void nothingConfiguredMeansTheGatewayIsOffAndNothingFails() {
        PesapalSettings off = PesapalSettings.resolve("staging", bare(null, null));
        assertThat(off.provider()).isEqualTo(Kind.OFF);
        assertThat(off.tenantAccountsEnabled()).isFalse();
        assertThat(off.platformConfigured()).isFalse();
        assertThat(PesapalSettings.resolve("production", bare("off", null)).tenantAccountsEnabled())
                .isFalse();
    }

    @Test
    void aCallbackAddressWithoutAnExplicitProviderMeansPesapalAndALendersAccountNeedsTheAddress() {
        assertThat(PesapalSettings.resolve("staging", props(null, null)).provider())
                .isEqualTo(Kind.PESAPAL);
        assertThat(PesapalSettings.resolve("staging", props(null, null)).tenantAccountsEnabled())
                .isTrue();
        // No callback address and no platform pair: nothing to register a notification url on, so the gateway is off.
        assertThat(PesapalSettings.resolve("staging", bare(null, null)).provider())
                .isEqualTo(Kind.OFF);
        // The real adapter with no callback address cannot serve a lender's account: Test connection needs the address.
        PesapalSettings noCallbackHost = PesapalSettings.resolve("staging", bare("pesapal", null));
        assertThat(noCallbackHost.tenantAccountsEnabled()).isFalse();
        // The platform pair is half configured without its secret: not usable.
        PesapalProperties noSecret = new PesapalProperties(
                "pesapal",
                null,
                "https://callbacks.example.test",
                new PesapalProperties.Platform("k", null, "aaaaaaaa-0000-4000-8000-000000000002", PLATFORM_TOKEN));
        assertThat(PesapalSettings.resolve("staging", noSecret).platformConfigured())
                .isFalse();
    }

    @Test
    void theFakeIsConfiguredWithoutAnyCredentialsOutsideProduction() {
        PesapalSettings fake = PesapalSettings.resolve("staging", bare("fake", null));
        assertThat(fake.provider()).isEqualTo(Kind.FAKE);
        assertThat(fake.tenantAccountsEnabled()).isTrue();
    }

    @Test
    void anUnknownProviderNameIsRefused() {
        assertThatThrownBy(() -> PesapalSettings.resolve("staging", bare("stripe", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BMS_PESAPAL_PROVIDER");
    }

    @Test
    void aLendersMerchantAccountIsNotReadFromTheEnvironmentAtAll() {
        // The own-account rule (issue #233): the properties carry no tenant key, secret, registration or binding, so a
        // host cannot be set up to give one lender's account to whoever switches collections on first.
        assertThat(java.util.Arrays.stream(PesapalProperties.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName))
                .containsExactlyInAnyOrder("provider", "baseUrl", "callbackBaseUrl", "platform", "refusedKeyDigests");
        assertThat(java.util.Arrays.stream(PesapalSettings.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("tenantPair", "boundTenant");
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
            PesapalProperties p = new PesapalProperties("pesapal", null, bad, null);
            assertThatThrownBy(() -> PesapalSettings.resolve("staging", p))
                    .as(bad)
                    .isInstanceOf(IllegalStateException.class);
        }
        PesapalProperties ok = new PesapalProperties("pesapal", null, "https://callbacks.example.test/", null);
        assertThat(PesapalSettings.resolve("staging", ok).callbackBase().toString())
                .isEqualTo("https://callbacks.example.test");
        assertThat(PesapalSettings.publicHttps(java.net.URI.create("https://172.15.0.1/x")))
                .isTrue();
        assertThat(PesapalSettings.publicHttps(java.net.URI.create("https://172.32.0.1/x")))
                .isTrue();
    }

    @Test
    void anIpnPathTokenMustBeLongAndPathSafe() {
        for (String bad : new String[] {
            "short", "has space in it 0123456789abcdef", "slash/inside/0123456789abcdefgh", "q?uery0123456789abcdefghij"
        }) {
            PesapalProperties p = new PesapalProperties(
                    "pesapal",
                    null,
                    null,
                    new PesapalProperties.Platform("k", "s", "aaaaaaaa-0000-4000-8000-000000000002", bad));
            assertThatThrownBy(() -> PesapalSettings.resolve("staging", p))
                    .as(bad)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void thePathTokenSelectsThePlatformPairInConstantTimeAndNothingElseMatches() {
        PesapalSettings s = PesapalSettings.resolve("staging", props("pesapal", null));
        assertThat(s.isPlatformToken(PLATFORM_TOKEN)).isTrue();
        for (String other : new String[] {
            null,
            "",
            "x",
            PLATFORM_TOKEN + "x",
            PLATFORM_TOKEN.substring(1),
            PLATFORM_TOKEN.toUpperCase(),
            "x".repeat(300)
        }) {
            assertThat(s.isPlatformToken(other)).as("%s", other).isFalse();
        }
        // A host with no platform pair has no platform token: an empty configured value never matches an empty
        // presented one.
        PesapalSettings noPlatform = PesapalSettings.resolve("staging", bare("pesapal", null));
        assertThat(noPlatform.isPlatformToken("")).isFalse();
        assertThat(noPlatform.isPlatformToken(PLATFORM_TOKEN)).isFalse();
    }

    // ---- the platform operator's own keys can never be a lender's (issue #233, review round 2)
    // -------------------------

    @Test
    void productionWithTheGatewayOnRefusesToStartWithoutTheListOfKeysNoLenderMayEnter() {
        PesapalProperties none = new PesapalProperties(
                "pesapal",
                null,
                "https://callbacks.example.test",
                new PesapalProperties.Platform(null, null, null, null));
        assertThatThrownBy(() -> PesapalSettings.resolve("production", none))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BMS_PESAPAL_REFUSED_KEY_DIGESTS");
        // Off needs none, and outside production an empty list is allowed.
        assertThat(PesapalSettings.resolve("production", bare("off", null)).provider())
                .isEqualTo(Kind.OFF);
        assertThat(PesapalSettings.resolve("staging", none).refusedKeyDigests()).isEmpty();
        PesapalSettings ok = PesapalSettings.resolve("production", props("pesapal", LIVE));
        assertThat(ok.refusedKeyDigests()).containsExactly(OWNED_DIGEST);
    }

    @Test
    void aRefusedKeyIsRefusedWhetherOrNotThePlatformPairIsInUseAndAMalformedDigestStopsTheStart() {
        // The platform pair is unset (every host until subscriptions exist) but the operator's key is on the list.
        PesapalProperties unsetPair = new PesapalProperties(
                "pesapal",
                null,
                "https://callbacks.example.test",
                new PesapalProperties.Platform(null, null, null, null),
                OWNED_DIGEST.toUpperCase() + " , " + "0".repeat(64));
        PesapalSettings s = PesapalSettings.resolve("staging", unsetPair);
        assertThat(s.isRefusedKey("invented-owned-key-0001")).isTrue();
        assertThat(s.isRefusedKey("invented-owned-key-0002")).isFalse();
        assertThat(s.isRefusedKey(null)).isFalse();
        // The configured platform key is refused too, with or without the list.
        assertThat(PesapalSettings.resolve("staging", props("pesapal", null)).isRefusedKey("invented-platform-key"))
                .isTrue();
        // Issue #309: letter case does not get a key past the list: the platform key in another case, and the digest of
        // the
        // folded (lower case) form of a listed key.
        assertThat(PesapalSettings.resolve("staging", props("pesapal", null)).isRefusedKey("Invented-PLATFORM-Key"))
                .isTrue();
        PesapalSettings foldedList = PesapalSettings.resolve(
                "staging",
                new PesapalProperties(
                        "pesapal",
                        null,
                        "https://callbacks.example.test",
                        new PesapalProperties.Platform(null, null, null, null),
                        OWNED_DIGEST));
        assertThat(foldedList.isRefusedKey("Invented-Owned-Key-0001")).isTrue();
        assertThat(PesapalSettings.fold("AbC-1")).isEqualTo("abc-1");
        for (String bad : new String[] {"not-hex", "abc", "z".repeat(64)}) {
            PesapalProperties p = new PesapalProperties(
                    "pesapal", null, null, new PesapalProperties.Platform(null, null, null, null), bad);
            assertThatThrownBy(() -> PesapalSettings.resolve("staging", p))
                    .as(bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BMS_PESAPAL_REFUSED_KEY_DIGESTS")
                    .hasMessageNotContaining(bad);
        }
    }

    // ---- no value in any message or printed form ---------------------------------------------------------------

    @Test
    void noRefusalAndNoPrintedFormNamesAKeyASecretATokenOrAUrl() {
        List<String> secrets = List.of(
                KEY, SECRET, TOKEN, PLATFORM_TOKEN, "invented-platform-key", "invented-platform-secret", "evil.test");
        List<Throwable> refusals = List.of(
                catchRefusal(() -> PesapalSettings.resolve("staging", props("pesapal", "https://evil.test/v3"))),
                catchRefusal(() -> PesapalSettings.resolve("production", props("pesapal", SANDBOX))),
                catchRefusal(() -> PesapalSettings.resolve("production", props("fake", null))),
                catchRefusal(() -> PesapalSettings.resolve(
                        "staging",
                        new PesapalProperties(
                                "pesapal",
                                null,
                                null,
                                new PesapalProperties.Platform(
                                        "k",
                                        "s",
                                        "aaaaaaaa-0000-4000-8000-000000000002",
                                        "has space " + PLATFORM_TOKEN)))),
                catchRefusal(() -> PesapalSettings.resolve(
                        "staging", new PesapalProperties("pesapal", null, "https://evil.test/path", null))));
        for (Throwable t : refusals) {
            for (String secret : secrets) {
                assertThat(t.getMessage()).doesNotContain(secret);
            }
        }
        PesapalSettings s = PesapalSettings.resolve("staging", props("pesapal", null));
        for (Object printed : new Object[] {
            s, s.platformPair(), props("pesapal", null), props("pesapal", null).platform()
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
                .withBean(AccountCredentials.class, () -> org.mockito.Mockito.mock(AccountCredentials.class))
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
                        "bms.pesapal.platform.consumer-key=" + KEY,
                        "bms.pesapal.platform.consumer-secret=" + SECRET)
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
            assertThat(gateway.available(PaymentGateway.Account.tenant(UUID.randomUUID())))
                    .isFalse();
            assertThat(gateway).isInstanceOf(PaymentsConfiguration.UnavailableGateway.class);
        });
        runner(
                        "staging",
                        "bms.pesapal.base-url=" + SANDBOX,
                        "bms.pesapal.callback-base-url=https://callbacks.example.test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PaymentGateway gateway = context.getBean(PaymentGateway.class);
                    assertThat(gateway).isInstanceOf(PesapalGateway.class);
                    // The mocked credentials hold nothing: no account is available, and none is ever invented.
                    assertThat(gateway.available(PaymentGateway.Account.tenant(UUID.randomUUID())))
                            .isFalse();
                    assertThat(gateway.available(PaymentGateway.Account.platform()))
                            .isFalse();
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
