package com.rincoltech.bms.lending.savings.internal;

import com.rincoltech.bms.core.tenancy.BranchDeactivationGuard;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** FR-BR-01: a branch with a savings account that is not closed cannot be deactivated. */
@Component
class SavingsBranchGuard implements BranchDeactivationGuard {

    private final SavingsRepository repo;

    SavingsBranchGuard(SavingsRepository repo) {
        this.repo = repo;
    }

    @Override
    public boolean hasOpenAccounts(UUID branchId) {
        return repo.hasOpenAccounts(branchId);
    }
}
