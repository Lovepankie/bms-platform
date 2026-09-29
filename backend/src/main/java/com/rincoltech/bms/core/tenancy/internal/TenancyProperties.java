package com.rincoltech.bms.core.tenancy.internal;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseDomain the base domain tenant hosts sit under ({@code <slug>.<baseDomain>})
 * @param allowTenantHeader accept {@code X-Tenant: <slug>} when the host carries no slug; only
 *     the {@code dev} and {@code test} profiles may turn this on (chapter 7 section 7.2)
 * @param linkOrigin origin of the one-time links sent to users, with {@code {slug}} for the
 *     tenant; defaults to {@code https://{slug}.<baseDomain>}
 */
@ConfigurationProperties("bms.tenancy")
record TenancyProperties(String baseDomain, boolean allowTenantHeader, String linkOrigin) {

    static final Set<String> RESERVED_LABELS = Set.of("www", "api", "app", "admin", "static", "mail");

    TenancyProperties {
        if (baseDomain == null || baseDomain.isBlank()) {
            throw new IllegalArgumentException("bms.tenancy.base-domain must be set");
        }
        baseDomain = baseDomain.toLowerCase();
        if (linkOrigin == null || linkOrigin.isBlank()) {
            linkOrigin = "https://{slug}." + baseDomain;
        }
    }
}
