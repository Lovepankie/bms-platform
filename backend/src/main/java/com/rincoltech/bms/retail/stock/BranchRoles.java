package com.rincoltech.bms.retail.stock;

import com.rincoltech.bms.core.tenancy.Branches;

/**
 * The role of a retail branch (ADR-033, ADR-104). Retail stock owns it because branches and role checks
 * already live here; sales and purchasing depend on this interface and never on each other.
 * With the {@code branch_roles} capability off every branch is {@link BranchRole#BOTH}, which is
 * the behaviour before roles existed. With it on, a Store ({@code sells_goods} false, ADR-048) is
 * {@link BranchRole#STORE} and any other branch is its stored {@code shop} or {@code both}. Whether a
 * branch may sell is asked of {@link RetailBranchContext#requireSells} alone.
 */
public interface BranchRoles {

    /**
     * The role in force for the branch, read from the row the caller already holds and its stored role: {@code BOTH} when
     * the capability is off or no row exists.
     */
    BranchRole roleOf(Branches.Branch branch);
}
