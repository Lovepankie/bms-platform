package com.rincoltech.bms.core.identity;

import java.time.Instant;
import java.util.Optional;
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

    /**
     * When an administrator of the tenant (a user ever given the {@code tenant_admin} role) first signed in, or empty
     * when none has: the default go-live date of ADR-071.
     */
    Optional<Instant> firstAdminSignIn();
}
