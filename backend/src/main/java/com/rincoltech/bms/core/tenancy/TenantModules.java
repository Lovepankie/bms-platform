package com.rincoltech.bms.core.tenancy;

import java.util.List;

/** Whether the bound tenant has switched a vertical module on (FR-TEN-03). */
public interface TenantModules {

    boolean isEnabled(String moduleKey);

    /** The keys of the modules the bound tenant has switched on, sorted; empty when none. */
    List<String> enabledKeys();
}
