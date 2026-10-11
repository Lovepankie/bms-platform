package com.rincoltech.bms.retail.stock;

import java.util.UUID;

/**
 * The role of a retail branch (ADR-033). Retail stock owns it because branches and role checks
 * already live here; sales and purchasing depend on this interface and never on each other.
 * With the {@code branch_roles} capability off, and for a branch with no role row, every branch
 * is {@link BranchRole#BOTH}, which is the behaviour before roles existed.
 */
public interface BranchRoles {

    /** The role in force for the branch: {@code BOTH} when the capability is off or no row exists. */
    BranchRole roleOf(UUID branchId);

    /** Refuses with 422 {@code branch_role_forbids_sales} when the branch is a store. */
    void requireMaySell(UUID branchId);
}
