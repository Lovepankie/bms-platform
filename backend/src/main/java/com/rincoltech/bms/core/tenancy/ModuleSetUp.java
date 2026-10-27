package com.rincoltech.bms.core.tenancy;

/**
 * A vertical's own set-up when its module is switched on for a tenant (issue #618, ADR-098): at the tenant's creation with
 * the module, or when the module is switched on later. The core calls every bean whose {@link #moduleKey()} was switched on,
 * in the switching transaction bound to that tenant, so the set-up commits or rolls back with the switch, and never names a
 * vertical. It must be safe to run again: a module switched off keeps its rows and finds them when switched on again.
 */
public interface ModuleSetUp {

    /** The module this set-up belongs to; matches its {@link ModuleManifest#key()}. */
    String moduleKey();

    /** Runs in the switching transaction, bound to the tenant; inserts take the tenant from {@code app.tenant_id}. */
    void switchedOn();
}
