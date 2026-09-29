package com.rincoltech.bms.core.tenancy;

/**
 * A vertical module's registration with the core (chapter 5 section 5.4.3). A vertical declares
 * one bean of this type; the core iterates them and never names a vertical. Requests under
 * {@code /api/v1/<key>/...} return 404 {@code module_not_enabled} unless the bound tenant has the
 * module switched on.
 */
public record ModuleManifest(String key) {}
