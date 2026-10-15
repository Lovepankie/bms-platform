package com.rincoltech.bms.testsupport;

import com.rincoltech.bms.TestDatabase;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * Arranges a fabricated tenant's own SMS switch and settings (the settings group {@code sms}) straight in the
 * database as the owner role, the way {@code platform_sms_controls} rows are arranged: a tenant sends no SMS until its
 * administrator has switched SMS on, so the tests that exercise the channel switch it on here. The settings screen's
 * own rules (acknowledgement, approved sender, ceilings) are exercised through the API in {@code SmsSettingsIT}.
 */
public final class SmsTenants {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SmsTenants() {}

    /** The tenant's own SMS switch on, with the group's defaults for the rest. */
    public static void enable(UUID tenantId) {
        set(tenantId, Map.of("enabled", true));
    }

    /** Merges {@code values} into the tenant's {@code sms} group, creating the settings row if there is none. */
    public static void set(UUID tenantId, Map<String, Object> values) {
        String json = JSON.writeValueAsString(values);
        TestDatabase.owner().sql("""
                        INSERT INTO tenant_settings (id, tenant_id, settings)
                        VALUES (gen_random_uuid(), ?, jsonb_build_object('module_groups', jsonb_build_object('sms', CAST(? AS jsonb))))
                        ON CONFLICT (tenant_id) DO UPDATE SET settings = jsonb_set(
                            CASE WHEN jsonb_typeof(tenant_settings.settings -> 'module_groups') = 'object'
                                 THEN tenant_settings.settings
                                 ELSE jsonb_set(tenant_settings.settings, '{module_groups}', '{}'::jsonb) END,
                            '{module_groups,sms}',
                            coalesce(tenant_settings.settings #> '{module_groups,sms}', '{}'::jsonb) || CAST(? AS jsonb))
                        """).params(tenantId, json, json).update();
    }

    /** The operator's controls for a tenant: the switch and the daily cap (platform table, owner role only). */
    public static void operator(UUID tenantId, boolean enabled, int dailyCap) {
        TestDatabase.owner()
                .sql("INSERT INTO platform_sms_controls (tenant_id, enabled, daily_cap) VALUES (?, ?, ?)"
                        + " ON CONFLICT (tenant_id) DO UPDATE SET enabled = excluded.enabled,"
                        + " daily_cap = excluded.daily_cap")
                .params(tenantId, enabled, dailyCap)
                .update();
    }
}
