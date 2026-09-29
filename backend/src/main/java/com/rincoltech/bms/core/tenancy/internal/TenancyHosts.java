package com.rincoltech.bms.core.tenancy.internal;

import com.rincoltech.bms.core.tenancy.PlatformHost;
import org.springframework.stereotype.Component;

@Component
class TenancyHosts implements PlatformHost {

    private final TenancyProperties properties;
    private final TenantResolutionFilter hostRules;

    TenancyHosts(TenancyProperties properties) {
        this.properties = properties;
        this.hostRules = new TenantResolutionFilter(properties, null, null);
    }

    @Override
    public boolean servesPlatform(String host) {
        return hostRules.slugFromHost(host).isEmpty();
    }

    @Override
    public String tenantOrigin(String slug) {
        return properties.linkOrigin().replace("{slug}", slug);
    }
}
