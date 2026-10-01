package com.rincoltech.bms.lending.collateral.internal;

import com.rincoltech.bms.lending.collateral.CollateralLookup;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link CollateralLookup} for the loans module. */
@Service
class CollateralLookupService implements CollateralLookup {

    private final CollateralRepository repo;

    CollateralLookupService(CollateralRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CollateralSummary> find(UUID collateralId) {
        return repo.find(collateralId)
                .map(c -> new CollateralSummary(
                        c.id(),
                        c.memberId(),
                        c.branchId(),
                        c.collateralType(),
                        c.custodyStatus(),
                        c.collateralValueMinor(),
                        c.currency()));
    }
}
