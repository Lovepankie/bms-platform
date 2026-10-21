package com.rincoltech.bms.core.notifications.internal;

import com.rincoltech.bms.core.notifications.PushPreferences;
import com.rincoltech.bms.core.tenancy.ModuleSettingsGroup;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The tenant settings group {@code notifications} (issue #412, ADR-057): a switch per everyday push notice, all on by default,
 * and the time of the daily closing summary ({@code HH:mm} on the business's clock, 20:00 by default). Stored in the tenant
 * settings row, versioned and audited with it, like the {@code sms} group. The safeguard notices of ADR-050 (a sale voided, a
 * write-off alone, an approval limit raised, two-step sign-in relaxed, an approval waiting, stock on its way) have no switch.
 */
@Component
class NotificationSettingsGroup implements ModuleSettingsGroup {

    static final String KEY = "notifications";
    static final String SUMMARY_TIME = "daily_summary_time";
    static final String DEFAULT_SUMMARY_TIME = "20:00";

    static final List<String> SWITCHES = List.of(
            PushPreferences.NEW_ORDERS,
            PushPreferences.ORDER_UPDATES,
            PushPreferences.LOW_STOCK,
            PushPreferences.CREDIT_DUE,
            PushPreferences.DAILY_SUMMARY);

    /**
     * A quarter hour up to 23:30: the job wakes on every quarter hour, so a time between two is never met exactly, and 23:45
     * would leave no tick before midnight should one be late.
     */
    static final Pattern CLOCK = Pattern.compile("^(([01][0-9]|2[0-2]):(00|15|30|45)|23:(00|15|30))$");

    @Override
    public String moduleKey() {
        return KEY;
    }

    @Override
    public String auditAction() {
        return "core.notifications.settings.changed";
    }

    @Override
    public Map<String, Object> normalise(Map<String, Object> current, Map<String, Object> proposed) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : SWITCHES) {
            // Absent means on: a business that never opened the screen hears everything.
            out.put(key, !Boolean.FALSE.equals(current.get(key)));
        }
        out.put(
                SUMMARY_TIME,
                current.get(SUMMARY_TIME) instanceof String t
                                && CLOCK.matcher(t).matches()
                        ? t
                        : DEFAULT_SUMMARY_TIME);
        List<FieldProblem> problems = new ArrayList<>();
        proposed.forEach((key, value) -> {
            if (SWITCHES.contains(key)) {
                if (value instanceof Boolean b) {
                    out.put(key, b);
                } else {
                    problems.add(new FieldProblem(key, "invalid", "Use true or false."));
                }
            } else if (key.equals(SUMMARY_TIME)) {
                if (value instanceof String t && CLOCK.matcher(t).matches()) {
                    out.put(key, t);
                } else {
                    problems.add(new FieldProblem(
                            key, "invalid", "Use a quarter hour up to 23:30, such as 20:00 or 18:45."));
                }
            } else {
                problems.add(new FieldProblem(key, "unknown", "Not a notification setting."));
            }
        });
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        return out;
    }

    @Override
    public List<String> enabledCapabilities(Map<String, Object> normalised) {
        return List.of();
    }
}
