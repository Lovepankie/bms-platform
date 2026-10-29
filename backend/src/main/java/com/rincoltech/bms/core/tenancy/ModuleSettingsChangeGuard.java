package com.rincoltech.bms.core.tenancy;

import java.util.Map;

/**
 * Lets a module refuse a change to its settings group that its own open work forbids, for example
 * switching off a capability while records that need it are still open (issue #239). {@link
 * ModuleSettings#change} calls every bean of this type while it holds the settings row lock, after
 * the group is validated and before it is saved, so a write that reads the group under {@link
 * ModuleSettings#groupHeld} cannot commit in between. The core iterates the beans of this type and
 * never names a vertical.
 */
public interface ModuleSettingsChangeGuard {

    /**
     * Throws an {@code ApiException} to refuse the change. {@code before} and {@code after} are the
     * group normalised with its defaults; they differ.
     */
    void check(String moduleKey, Map<String, Object> before, Map<String, Object> after);
}
