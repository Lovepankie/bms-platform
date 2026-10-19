package com.rincoltech.bms.core.payments.internal;

import com.rincoltech.bms.core.tenancy.ModuleSettings;
import com.rincoltech.bms.kernel.Versions;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the tenant's payments settings group through the core's settings store, so a change is versioned
 * under {@code If-Match}, audited with before and after, and held to the group's own rules on every path
 * ({@link PaymentsSettingsGroup}: the tenant's own verified account, the mapped gateway method, the configured gateway).
 */
@Service
class PaymentsSettingsService {

    /** What intent creation needs. */
    record Effective(boolean enabled, Long dailyCeilingMinor) {}

    @Schema(name = "PaymentsSettings")
    record View(
            boolean collectionsEnabled,
            Long dailyCeilingMinor,

            @Schema(
                    description =
                            "The host can serve a business's own payment account (a provider and a callback address)")
            boolean gatewayAvailable,

            @Schema(description = "This business has connected and tested its own payment account (Receive payments)")
            boolean merchantAccountVerified,

            @Schema(description = "The gateway payment method posts to an active ledger account")
            boolean gatewayMethodMapped,

            int version) {}

    /** A partial change; null is unchanged. */
    record Change(Boolean collectionsEnabled, Long dailyCeilingMinor, boolean clearDailyCeiling) {}

    private final ModuleSettings settings;
    private final PaymentsSettingsGroup group;

    PaymentsSettingsService(ModuleSettings settings, PaymentsSettingsGroup group) {
        this.settings = settings;
        this.group = group;
    }

    @Transactional(readOnly = true)
    Effective effective() {
        Map<String, Object> g = settings.group(PaymentsSettingsGroup.KEY);
        return new Effective(
                Boolean.TRUE.equals(g.get(PaymentsSettingsGroup.ENABLED)),
                g.get(PaymentsSettingsGroup.CEILING) instanceof Number n ? n.longValue() : null);
    }

    @Transactional(readOnly = true)
    View view() {
        Effective e = effective();
        return new View(
                e.enabled(),
                e.dailyCeilingMinor(),
                group.gatewayAvailable(),
                group.merchantAccountVerified(),
                group.gatewayMethodMapped(),
                settings.version());
    }

    @Transactional
    View update(String ifMatch, Change c) {
        int expected = Versions.fromIfMatch(ifMatch);
        // The version first: a request that lost a race is a conflict whatever else is wrong with it.
        int current = settings.version();
        if (current != expected) {
            throw Versions.conflict(current);
        }
        Map<String, Object> proposed = new LinkedHashMap<>();
        if (c.collectionsEnabled() != null) {
            proposed.put(PaymentsSettingsGroup.ENABLED, c.collectionsEnabled());
        }
        if (c.clearDailyCeiling()) {
            proposed.put(PaymentsSettingsGroup.CEILING, null);
        } else if (c.dailyCeilingMinor() != null) {
            proposed.put(PaymentsSettingsGroup.CEILING, c.dailyCeilingMinor());
        }
        settings.change(PaymentsSettingsGroup.KEY, proposed, expected);
        return view();
    }
}
