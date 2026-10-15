package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.tenancy.ModuleSettings;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The bound tenant's SMS settings as the channel reads them on every message (the settings group {@code sms},
 * {@link SmsSettingsGroup}), with the defaults applied. Called inside a tenant transaction. The sender id here is the
 * one the tenant chose; whether the platform still approves it is checked separately, per message, so a
 * withdrawal takes effect at once.
 */
@Component
class SmsTenantConfig {

    /**
     * {@code dailyCap} and {@code monthlyCap} are null when the tenant set none of its own (the ceiling applies);
     * 0 means stop.
     */
    record Config(
            boolean enabled,
            String senderId,
            LocalTime windowStart,
            LocalTime windowEnd,
            Integer dailyCap,
            Integer monthlyCap,
            boolean includeBalance,
            Instant enabledSince) {}

    private final ModuleSettings settings;

    SmsTenantConfig(ModuleSettings settings) {
        this.settings = settings;
    }

    Config current() {
        Map<String, Object> g = settings.group(SmsSettingsGroup.KEY);
        return new Config(
                Boolean.TRUE.equals(g.get(SmsSettingsGroup.ENABLED)),
                g.get(SmsSettingsGroup.SENDER_ID) instanceof String s && !s.isBlank() ? s : null,
                LocalTime.parse((String) g.get(SmsSettingsGroup.WINDOW_START)),
                LocalTime.parse((String) g.get(SmsSettingsGroup.WINDOW_END)),
                SmsSettingsGroup.whole(g.get(SmsSettingsGroup.DAILY_CAP)).orElse(null),
                SmsSettingsGroup.whole(g.get(SmsSettingsGroup.MONTHLY_CAP)).orElse(null),
                Boolean.TRUE.equals(g.get(SmsSettingsGroup.INCLUDE_BALANCE)),
                // The acknowledgement is stamped at the moment of the last switch-on, so it is also the moment before
                // which
                // a queued row is old news: the channel refuses it even if it was claimed before the switch-on
                // committed.
                g.get(SmsSettingsGroup.ACK_AT) instanceof String at ? Instant.parse(at) : null);
    }
}
