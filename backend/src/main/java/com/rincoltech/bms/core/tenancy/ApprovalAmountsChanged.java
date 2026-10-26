package com.rincoltech.bms.core.tenancy;

import java.util.Map;
import java.util.UUID;

/**
 * Published in the settings transaction when {@code PATCH /settings} changes approval amounts (ADR-091 decision 8). The approvals
 * module, which knows each action's default, tells the other administrators when the change loosens a rule: a higher amount, or
 * an amount lowered to 0 that switches an action off by its default.
 *
 * @param beforeMinor the amounts before, action type to amount (absent means 0)
 * @param afterMinor the amounts after
 * @param by the person who changed them, who is not told
 * @param version the settings version after the change
 */
public record ApprovalAmountsChanged(
        Map<String, Long> beforeMinor, Map<String, Long> afterMinor, UUID by, int version) {}
