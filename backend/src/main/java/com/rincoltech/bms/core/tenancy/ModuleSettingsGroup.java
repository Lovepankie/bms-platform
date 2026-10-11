package com.rincoltech.bms.core.tenancy;

import java.util.List;
import java.util.Map;

/**
 * A vertical's group of tenant settings (chapter 5 section 5.4.3, ADR-033). The core stores the
 * group as opaque jsonb under {@link #moduleKey()} and never interprets it: it calls the group to
 * validate and normalise a write, and asks it which capability names to list in {@code /me}. A
 * vertical declares one bean of this type; the lending module declares none.
 */
public interface ModuleSettingsGroup {

    /** The module this group belongs to; matches its {@link ModuleManifest#key()}. */
    String moduleKey();

    /** The audit action recorded when the group changes, {@code <module>.<entity>.<verb>}. */
    String auditAction();

    /**
     * The group after applying {@code proposed} on top of {@code current}, with every default
     * filled in. {@code current} is empty when the tenant never saved the group. Throws an
     * {@code ApiException} (422) when {@code proposed} is invalid.
     */
    Map<String, Object> normalise(Map<String, Object> current, Map<String, Object> proposed);

    /** The capability names switched on in a normalised group, sorted. */
    List<String> enabledCapabilities(Map<String, Object> normalised);
}
