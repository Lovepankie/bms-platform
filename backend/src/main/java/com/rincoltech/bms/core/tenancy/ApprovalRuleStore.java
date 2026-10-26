package com.rincoltech.bms.core.tenancy;

import java.util.Map;

/**
 * Writes the approval rules of the bound business (ADR-091): the switches and the amounts, two keys of the tenant settings row.
 * The approvals module validates, checks the administrator's proof and audits; this only stores, under the row lock.
 */
public interface ApprovalRuleStore {

    /** The version of the tenant settings row, the ETag of every settings write (1 when never saved). */
    int version();

    /**
     * Merges the given switches and amounts into the stored ones and returns the new settings version. Refuses with 409 when
     * {@code expectedVersion} is not the current one, as {@code PATCH /settings} does. The caller audits, in its transaction.
     */
    int saveApprovalRules(Map<String, Boolean> switches, Map<String, Long> thresholdsMinor, int expectedVersion);
}
