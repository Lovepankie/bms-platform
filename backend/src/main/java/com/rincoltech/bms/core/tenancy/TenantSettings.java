package com.rincoltech.bms.core.tenancy;

import java.util.Map;
import java.util.Set;

/** Typed reads of the bound tenant's settings (chapter 6 table {@code tenant_settings}). */
public interface TenantSettings {

    /**
     * The amount below which an action executes without a checker (FR-APR-04); 0 when unset,
     * meaning every instance needs a checker.
     */
    long approvalThresholdMinor(String actionType);

    /** FR-IAM-06: every staff user must use TOTP, not only the roles that require it. */
    boolean requireMfaAllStaff();

    /** FR-COL-05: collateral types this tenant does not accept; empty when unset. */
    Set<String> disabledCollateralTypes();

    /** FR-MEM-05: whether a loan may be submitted for a member whose KYC is not verified (default false). */
    boolean allowLoansBeforeKycVerified();

    /** FR-ORG-04: the maximum points of each score component, chapter 3 section 3.18.1 defaults. */
    Map<String, Integer> appraisalWeights();

    /** FR-ORG-08: days an approved loan may wait for disbursement before it expires (default 14). */
    int approvalValidityDays();

    /** FR-ORG-07: the most active loans one member may hold; null when the tenant sets no limit. */
    Integer maxActiveLoansPerMember();
}
