package com.rincoltech.bms.core.tenancy;

import java.util.List;
import java.util.Map;

/**
 * Reads and writes the bound tenant's module settings groups (ADR-033). A write is validated by
 * the module's {@link ModuleSettingsGroup}, saved with the tenant settings and audited with the
 * old and new values; the caller checks its own permission.
 */
public interface ModuleSettings {

    /** The group, normalised with its defaults; the defaults alone when the tenant never saved it. */
    Map<String, Object> group(String moduleKey);

    /**
     * The group, read under a shared lock on the tenant settings row held to the end of the
     * caller's transaction, so a {@link #change} waits for that transaction and its {@link
     * ModuleSettingsChangeGuard}s see what it wrote (issue #239). Without a saved row the defaults
     * apply and nothing is locked. Needs a read-write transaction.
     */
    Map<String, Object> groupHeld(String moduleKey);

    /** The version of the tenant settings row, the ETag of every settings write (1 when never saved). */
    int version();

    /**
     * Applies a partial change to the group and returns the group as saved. Refuses with 409 when
     * {@code expectedVersion} is not the current settings version, as {@code PATCH /settings} does.
     */
    Map<String, Object> change(String moduleKey, Map<String, Object> proposed, int expectedVersion);

    /** The capabilities switched on, per enabled module that has a settings group. */
    Map<String, List<String>> enabledCapabilities();
}
