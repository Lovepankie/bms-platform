package com.rincoltech.bms.core.tenancy;

/** Typed reads of the bound tenant's settings (chapter 6 table {@code tenant_settings}). */
public interface TenantSettings {

    /**
     * The amount below which an action executes without a checker (FR-APR-04); 0 when unset,
     * meaning every instance needs a checker.
     */
    long approvalThresholdMinor(String actionType);

    /** FR-IAM-06: every staff user must use TOTP, not only the roles that require it. */
    boolean requireMfaAllStaff();
}
