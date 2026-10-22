package com.rincoltech.bms.core.identity;

import java.util.UUID;

/**
 * Whether a staff user of the current tenant can act in a branch, for a module that assigns work
 * to a person (FR-CLN-01: a loan's responsible officer). Runs in the caller's transaction, which
 * must already be bound to the tenant.
 */
public interface StaffDirectory {

    /**
     * True when the user is an active staff user whose active role assignments grant
     * {@code permission} in {@code branchId} (chapter 8 section 8.3.1).
     */
    boolean mayAct(UUID userId, String permission, UUID branchId);
}
