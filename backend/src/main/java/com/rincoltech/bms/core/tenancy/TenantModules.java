package com.rincoltech.bms.core.tenancy;

/** Whether the bound tenant has switched a vertical module on (FR-TEN-03). */
public interface TenantModules {

    boolean isEnabled(String moduleKey);
}
