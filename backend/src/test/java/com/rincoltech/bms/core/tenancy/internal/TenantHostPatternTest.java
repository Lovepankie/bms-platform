package com.rincoltech.bms.core.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The tenant host pattern and platform host of chapter 7 section 7.2 (FR-TEN-02, ADR-018). */
class TenantHostPatternTest {

    static final String STAGING = "{slug}-bms-staging.rincoltech.com";

    final TenantHostPattern staging = TenantHostPattern.parse(STAGING);

    @Test
    void theExactHostResolvesItsSlugCaseInsensitively() {
        assertThat(staging.slugFromHost("demo-bms-staging.rincoltech.com")).contains("demo");
        assertThat(staging.slugFromHost("demo-2-bms-staging.rincoltech.com")).contains("demo-2");
        // DNS names are case-insensitive: an upper case host is the same tenant, never another one.
        assertThat(staging.slugFromHost("DEMO-BMS-STAGING.RINCOLTECH.COM")).contains("demo");
        assertThat(TenantHostPattern.parse("{slug}.localhost").slugFromHost("demo.localhost"))
                .contains("demo");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "evil-demo-bms-staging.rincoltech.com.attacker.com",
                "demo-bms-staging.rincoltech.com.attacker.com",
                "demo-bms-staging.rincoltech.com.",
                "x.demo-bms-staging.rincoltech.com",
                "demo.bms-staging.rincoltech.com",
                "demo-bms-staging.rincoltech.co",
                "demo-bms-staging.other.com",
                "demo-bms-staging-rincoltech.com",
                "demobms-staging.rincoltech.com",
                "bms-staging.rincoltech.com",
                "-bms-staging.rincoltech.com",
                "de-bms-staging.rincoltech.com",
                "-demo-bms-staging.rincoltech.com",
                "demo--bms-staging.rincoltech.com",
                "admin-bms-staging.rincoltech.com",
                "demo_x-bms-staging.rincoltech.com",
                "demo-bms-staging.rincoltech.com:443",
                "http://demo-bms-staging.rincoltech.com",
                "xn--demo-bms-staging.rincoltech.com",
                "rincoltech.com",
                ""
            })
    void lookAlikeHostsCarryNoSlug(String host) {
        assertThat(staging.slugFromHost(host)).as(host).isEmpty();
        assertThat(staging.slugFromHost(null)).isEmpty();
    }

    @Test
    void theHostOfASlugIsOneLabelUnderTheZone() {
        assertThat(staging.hostFor("demo")).isEqualTo("demo-bms-staging.rincoltech.com");
        assertThat(TenantHostPattern.parse("{slug}-bms.rincoltech.com").hostFor("demo"))
                .isEqualTo("demo-bms.rincoltech.com");
        assertThatThrownBy(() -> staging.hostFor("Demo")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSlugMustLeaveALegalLabel() {
        // "-bms-staging" takes 12 of the label's 63 characters.
        assertThat(staging.isValidSlug("a".repeat(51))).isTrue();
        assertThat(staging.isValidSlug("a".repeat(52))).isFalse();
        assertThat(staging.isValidSlug("www")).isFalse();
        assertThat(staging.isValidSlug("ab")).isFalse();
        assertThat(staging.isValidSlug(null)).isFalse();
    }

    @Test
    void aceLookingSlugsAreReserved() {
        // ADR-018 finding L4: any slug shaped like an IDNA ACE prefix (hyphens at positions 3 and
        // 4) is refused, not only the "xn--" in use today, so a punycode A-label can never become a
        // tenant slug that browsers might render as different Unicode.
        assertThat(staging.isValidSlug("xn--demo")).isFalse();
        assertThat(staging.isValidSlug("ab--cdef")).isFalse();
        assertThat(staging.isValidSlug("ab--c")).isFalse();
        assertThat(staging.isValidSlug("abc-def")).isTrue();
        assertThat(staging.isValidSlug("a-b--c")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "rincoltech.com",
                "{slug}",
                "{slug}-{slug}.rincoltech.com",
                "bms.{slug}.rincoltech.com",
                "{slug}.bms-staging.{slug}.com",
                "{slug}.-bad.com",
                "{slug}-bms-.rincoltech.com",
                "-{slug}.rincoltech.com",
                "{slug}_bms.rincoltech.com",
                "{slug}-bms-staging.rincoltech.com.",
                "{slug}-bms.rincoltech.com:8443",
                "{slug}-bms..com",
                "{slug}-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.rincoltech.com",
                " "
            })
    void invalidPatternsStopTheApplication(String pattern) {
        assertThatThrownBy(() -> TenantHostPattern.parse(pattern))
                .as(pattern)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BMS_TENANT_HOST_PATTERN");
        assertThatThrownBy(() -> TenantHostPattern.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePlatformHostIsValidatedAndMustNotBeATenantHost() {
        assertThat(new TenancyProperties(STAGING, "BMS-Staging.rincoltech.com", false, null, null).platformHost())
                .isEqualTo("bms-staging.rincoltech.com");
        assertThatThrownBy(() -> new TenancyProperties(STAGING, null, false, null, null))
                .hasMessageContaining("BMS_PLATFORM_HOST");
        assertThatThrownBy(
                        () -> new TenancyProperties(STAGING, "https://bms-staging.rincoltech.com", false, null, null))
                .hasMessageContaining("BMS_PLATFORM_HOST");
        assertThatThrownBy(() -> new TenancyProperties(STAGING, "demo-bms-staging.rincoltech.com", false, null, null))
                .hasMessageContaining("matches the tenant host pattern");
        assertThatThrownBy(() -> new TenancyProperties(STAGING, "bms-staging.rincoltech.com", false, "ftp", null))
                .hasMessageContaining("link-scheme");
    }

    @Test
    void thePlatformRoutesAreServedOnlyOnThePlatformHost() {
        TenancyHosts hosts =
                new TenancyHosts(new TenancyProperties(STAGING, "bms-staging.rincoltech.com", false, null, null));
        assertThat(hosts.servesPlatform("bms-staging.rincoltech.com")).isTrue();
        assertThat(hosts.servesPlatform("BMS-STAGING.RINCOLTECH.COM")).isTrue();
        assertThat(hosts.servesPlatform("demo-bms-staging.rincoltech.com")).isFalse();
        assertThat(hosts.servesPlatform("bms-staging.rincoltech.com.attacker.com"))
                .isFalse();
        assertThat(hosts.servesPlatform("bms-staging.rincoltech.com.")).isFalse();
        assertThat(hosts.servesPlatform("localhost")).isFalse();
        assertThat(hosts.servesPlatform(null)).isFalse();
    }

    @Test
    void linksUseThePattern() {
        TenancyHosts staging =
                new TenancyHosts(new TenancyProperties(STAGING, "bms-staging.rincoltech.com", false, null, null));
        assertThat(staging.tenantOrigin("demo")).isEqualTo("https://demo-bms-staging.rincoltech.com");
        TenancyHosts local =
                new TenancyHosts(new TenancyProperties("{slug}.localhost", "localhost", true, "http", 8000));
        assertThat(local.tenantOrigin("demo")).isEqualTo("http://demo.localhost:8000");
        assertThat(local.servesPlatform("localhost")).isTrue();
    }
}
