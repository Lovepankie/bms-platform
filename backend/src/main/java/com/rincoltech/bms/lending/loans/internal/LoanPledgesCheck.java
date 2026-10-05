package com.rincoltech.bms.lending.loans.internal;

import com.rincoltech.bms.lending.collateral.CollateralPledges;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** This module's registration with the collateral register (FR-COL-04): items held by open loans are not released. */
@Component
class LoanPledgesCheck implements CollateralPledges {

    private final LoanRepository repo;

    LoanPledgesCheck(LoanRepository repo) {
        this.repo = repo;
    }

    @Override
    public boolean securesOpenLoan(UUID collateralId) {
        return repo.pledgedElsewhere(collateralId, null);
    }
}
