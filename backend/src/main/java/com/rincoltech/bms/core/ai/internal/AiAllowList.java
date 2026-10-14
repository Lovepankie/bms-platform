package com.rincoltech.bms.core.ai.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The platform allow-list of tenants that may use the assistant (ADR-037 decision 7): the
 * environment value {@code BMS_AI_ENABLED_TENANTS}, tenant ids only (never slugs), set by the operator once the
 * provider's written terms are on file. A tenant switch alone is never enough, and a tenant removed
 * from the list stops at once: the gateway asks again on every call, not only when the switch is
 * turned on. Read at start; a change is a restart, like every other environment value.
 */
@Component
class AiAllowList {

    private final Set<UUID> entries;

    AiAllowList(AiProperties properties) {
        this.entries = properties.allowedTenants();
    }

    boolean allows(CurrentTenant.Profile tenant) {
        return entries.contains(tenant.id());
    }
}
