package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.tenancy.PlatformHost;
import org.springframework.stereotype.Component;

@Component
class TenancyHosts implements PlatformHost {

    private final TenancyProperties properties;
    private final TenantHostPattern tenantHosts;

    TenancyHosts(TenancyProperties properties) {
        this.properties = properties;
        this.tenantHosts = properties.hostPattern();
    }

    @Override
    public boolean servesPlatform(String host) {
        return host != null && host.equalsIgnoreCase(properties.platformHost());
    }

    @Override
    public boolean isValidSlug(String slug) {
        return tenantHosts.isValidSlug(slug);
    }

    @Override
    public String tenantOrigin(String slug) {
        Integer port = properties.linkPort();
        return properties.linkScheme() + "://" + tenantHosts.hostFor(slug) + (port == null ? "" : ":" + port);
    }

    @Override
    public String platformOrigin() {
        Integer port = properties.linkPort();
        return properties.linkScheme() + "://" + properties.platformHost() + (port == null ? "" : ":" + port);
    }
}
