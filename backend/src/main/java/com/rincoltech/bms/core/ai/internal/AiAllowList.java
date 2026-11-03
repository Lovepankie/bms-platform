package com.rincoltech.bms.core.ai.internal;

import com.rincoltech.bms.core.tenancy.CurrentTenant;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether a business may use the assistant (ADR-037 decision 7 as amended by ADR-112, issue #423). The source of truth is the AI
 * assistant add-on, which the platform operator switches on per business ({@code subscriptions.ai_addon}, V126), read again on
 * every call, so a business the operator switches off stops at once. The environment value {@code BMS_AI_ENABLED_TENANTS} stays
 * as the platform's own control: {@code off} is the kill switch (nobody, whatever the add-on), {@code *} lets every business in
 * (a demo host only), and tenant ids (never slugs) are let in beside the add-on, as before it existed. Read at start; a change
 * of the environment is a restart. A business's own switch is never enough on its own.
 */
@Component
class AiAllowList {

    private final Set<UUID> entries;
    private final boolean killSwitch;
    private final boolean allowAll;
    private final JdbcClient jdbc;

    AiAllowList(AiProperties properties, JdbcClient jdbc, Environment environment) {
        this.entries = properties.allowedTenants();
        this.killSwitch = properties.killSwitch();
        this.allowAll = properties.allowAll();
        this.jdbc = jdbc;
        // As for the recording stub: letting every business in unbilled is refused at start outside the dev, test and
        // demo profiles, so a production host can never run with it (review of #743).
        if (allowAll && !environment.acceptsProfiles(Profiles.of("dev", "test", "demo"))) {
            throw new IllegalStateException(
                    "BMS_AI_ENABLED_TENANTS=* is only allowed in the dev, test and demo profiles");
        }
    }

    /** For the bound business; reads its add-on mark in a short read-only transaction of its own when none is open. */
    @Transactional(readOnly = true)
    boolean allows(CurrentTenant.Profile tenant) {
        if (killSwitch) {
            return false;
        }
        if (allowAll || entries.contains(tenant.id())) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.sql("SELECT ai_addon FROM subscriptions WHERE tenant_id = ?")
                .param(tenant.id())
                .query(Boolean.class)
                .optional()
                .orElse(false));
    }
}
