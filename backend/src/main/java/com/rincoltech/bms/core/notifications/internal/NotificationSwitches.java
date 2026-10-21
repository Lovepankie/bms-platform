package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.PushPreferences;
import com.rincoltech.bms.core.tenancy.ModuleSettings;
import java.time.LocalTime;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Reads the tenant settings group {@code notifications} (issue #412): the switch of each everyday notice and the time of the
 * daily summary. {@link #allows} maps a push template to its switch; a template with no switch (the safeguards of ADR-050) is
 * always allowed.
 */
@Component
class NotificationSwitches implements PushPreferences {

    /** The push templates each switch covers. */
    static final Map<String, String> TEMPLATE_SWITCH = Map.of(
            "retail.order_new", NEW_ORDERS,
            "retail.order_out_for_delivery", ORDER_UPDATES,
            "retail.order_delivered", ORDER_UPDATES,
            "retail.low_stock", LOW_STOCK,
            "retail.credit_due", CREDIT_DUE,
            "retail.credit_due_branch", CREDIT_DUE,
            "retail.daily_summary", DAILY_SUMMARY,
            "retail.daily_summary_late", DAILY_SUMMARY);

    private final ModuleSettings settings;
    private final VapidKeys keys;

    NotificationSwitches(ModuleSettings settings, VapidKeys keys) {
        this.settings = settings;
        this.keys = keys;
    }

    @Override
    public boolean available() {
        return keys.available();
    }

    @Override
    public boolean on(String kind) {
        if (!NotificationSettingsGroup.SWITCHES.contains(kind)) {
            throw new IllegalArgumentException("no notification switch " + kind);
        }
        return !Boolean.FALSE.equals(group().get(kind));
    }

    @Override
    public LocalTime dailySummaryTime() {
        return LocalTime.parse((String) group().get(NotificationSettingsGroup.SUMMARY_TIME));
    }

    /** Whether a notice of this template may be queued for the bound tenant. */
    boolean allows(String template) {
        String kind = TEMPLATE_SWITCH.get(template);
        return kind == null || on(kind);
    }

    private Map<String, Object> group() {
        return settings.group(NotificationSettingsGroup.KEY);
    }
}
