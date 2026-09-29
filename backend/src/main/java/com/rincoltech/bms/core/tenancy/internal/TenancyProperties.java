package com.rincoltech.bms.core.tenancy.internal;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Hosts of chapter 7 section 7.2, validated at startup: a bad value stops the application.
 *
 * @param tenantHostPattern tenant hosts, with exactly one {@code {slug}} in the leftmost label, for
 *     example {@code {slug}-bms-staging.rincoltech.com}
 * @param platformHost the one host that serves the platform console and platform API, for example
 *     {@code bms-staging.rincoltech.com}; it must not match the tenant pattern
 * @param allowTenantHeader accept {@code X-Tenant: <slug>} when the host carries no slug; only
 *     the {@code dev} and {@code test} profiles may turn this on (chapter 7 section 7.2)
 * @param linkScheme scheme of the one-time links sent to users; {@code https} unless local
 * @param linkPort port of those links, or null for the scheme's default (local development only)
 */
@ConfigurationProperties("bms.tenancy")
record TenancyProperties(
        String tenantHostPattern, String platformHost, boolean allowTenantHeader, String linkScheme, Integer linkPort) {

    private static final Pattern HOST =
            Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");

    TenancyProperties {
        TenantHostPattern pattern = TenantHostPattern.parse(tenantHostPattern);
        tenantHostPattern = pattern.toString();
        if (platformHost == null || platformHost.isBlank()) {
            throw new IllegalArgumentException("bms.tenancy.platform-host (BMS_PLATFORM_HOST) must be set");
        }
        platformHost = platformHost.trim().toLowerCase(Locale.ROOT);
        if (!HOST.matcher(platformHost).matches()) {
            throw new IllegalArgumentException(
                    "bms.tenancy.platform-host (BMS_PLATFORM_HOST) '" + platformHost + "' is not a host name");
        }
        if (pattern.slugFromHost(platformHost).isPresent()) {
            throw new IllegalArgumentException("bms.tenancy.platform-host (BMS_PLATFORM_HOST) '" + platformHost
                    + "' matches the tenant host pattern '" + pattern + "'");
        }
        linkScheme = linkScheme == null || linkScheme.isBlank() ? "https" : linkScheme;
        if (!linkScheme.equals("https") && !linkScheme.equals("http")) {
            throw new IllegalArgumentException("bms.tenancy.link-scheme must be https or http");
        }
    }

    TenantHostPattern hostPattern() {
        return TenantHostPattern.parse(tenantHostPattern);
    }
}
