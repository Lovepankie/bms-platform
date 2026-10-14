package com.rincoltech.bms.core.ai.internal;

import com.rincoltech.bms.core.ai.AiSettings;
import com.rincoltech.bms.core.ai.LlmClient;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.BusinessClock;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reads and writes the tenant's AI settings group through the core's settings store, so the switch
 * and the caps are versioned with the tenant settings and audited with before and after, and so the
 * group's own rules (the platform allow-list, the acknowledgement) hold on every path.
 */
@Service
class AiSettingsService implements AiSettings {

    /** What the gateway needs on every call. */
    record Effective(boolean enabled, long dailyTokenCap, long dailyCostCapMicro) {}

    private final ModuleSettings settings;
    private final AiAllowList allowList;
    private final AiProperties properties;
    private final LlmClient client;
    private final AiCallLog callLog;
    private final BusinessClock clock;
    private final CurrentTenant tenant;

    AiSettingsService(
            ModuleSettings settings,
            AiAllowList allowList,
            AiProperties properties,
            LlmClient client,
            AiCallLog callLog,
            BusinessClock clock,
            CurrentTenant tenant) {
        this.settings = settings;
        this.allowList = allowList;
        this.properties = properties;
        this.client = client;
        this.callLog = callLog;
        this.clock = clock;
        this.tenant = tenant;
    }

    Effective effective() {
        Map<String, Object> group = settings.group(AiSettingsGroup.KEY);
        return new Effective(
                Boolean.TRUE.equals(group.get(AiSettingsGroup.ENABLED)),
                ((Number) group.get(AiSettingsGroup.TOKEN_CAP)).longValue(),
                ((Number) group.get(AiSettingsGroup.COST_CAP)).longValue());
    }

    @Override
    public Status status() {
        Map<String, Object> group = settings.group(AiSettingsGroup.KEY);
        CurrentTenant.Profile profile = tenant.profile();
        AiCallLog.Windows windows = AiGatewayService.windows(clock.now(), profile.timezone());
        AiCallLog.Usage used = callLog.tenantUsageToday(windows.tenantFrom(), windows.tenantTo());
        return new Status(
                Boolean.TRUE.equals(group.get(AiSettingsGroup.ENABLED)),
                allowList.allows(profile),
                client.available(),
                ((Number) group.get(AiSettingsGroup.TOKEN_CAP)).longValue(),
                ((Number) group.get(AiSettingsGroup.COST_CAP)).longValue(),
                properties.priceCurrency(),
                used.tokens(),
                used.costMicro(),
                group.get(AiSettingsGroup.ACK_BY) instanceof String s ? UUID.fromString(s) : null,
                group.get(AiSettingsGroup.ACK_AT) instanceof String s ? Instant.parse(s) : null,
                (String) group.get(AiSettingsGroup.ACK_VERSION),
                settings.version());
    }

    @Override
    public Status enable(String acknowledgedTextVersion, int expectedVersion) {
        if (acknowledgedTextVersion == null || acknowledgedTextVersion.isBlank()) {
            throw ApiException.validation(List.of(
                    new FieldProblem(AiSettingsGroup.ACK_VERSION, "required", "Acknowledge the text that was shown.")));
        }
        Map<String, Object> change = new LinkedHashMap<>();
        change.put(AiSettingsGroup.ENABLED, true);
        change.put(AiSettingsGroup.ACK_BY, CurrentPrincipal.require().userId().toString());
        change.put(AiSettingsGroup.ACK_AT, clock.now().toString());
        change.put(AiSettingsGroup.ACK_VERSION, acknowledgedTextVersion);
        if (Boolean.TRUE.equals(settings.group(AiSettingsGroup.KEY).get(AiSettingsGroup.ENABLED))) {
            // Already on: nothing to acknowledge again, nothing written.
            return status();
        }
        settings.change(AiSettingsGroup.KEY, change, expectedVersion);
        return status();
    }

    @Override
    public Status disable(int expectedVersion) {
        settings.change(AiSettingsGroup.KEY, Map.of(AiSettingsGroup.ENABLED, false), expectedVersion);
        return status();
    }

    @Override
    public Status setCaps(Long dailyTokenCap, Long dailyCostCapMicro, int expectedVersion) {
        Map<String, Object> change = new LinkedHashMap<>();
        if (dailyTokenCap != null) {
            change.put(AiSettingsGroup.TOKEN_CAP, dailyTokenCap);
        }
        if (dailyCostCapMicro != null) {
            change.put(AiSettingsGroup.COST_CAP, dailyCostCapMicro);
        }
        settings.change(AiSettingsGroup.KEY, change, expectedVersion);
        return status();
    }
}
