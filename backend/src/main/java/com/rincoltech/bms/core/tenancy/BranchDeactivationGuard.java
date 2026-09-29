package com.rincoltech.bms.core.tenancy;

import java.util.UUID;

/**
 * Lets a module that owns branch-owned accounts (loans, savings, investments) refuse the
 * deactivation of a branch that still has open ones (FR-BR-01, {@code branch_has_open_accounts}).
 * The core iterates the beans of this type and never names a vertical.
 */
public interface BranchDeactivationGuard {

    /** @return true when the branch still has open accounts of this module */
    boolean hasOpenAccounts(UUID branchId);
}
