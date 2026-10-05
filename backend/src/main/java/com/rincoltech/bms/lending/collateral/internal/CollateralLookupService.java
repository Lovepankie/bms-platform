package com.rincoltech.bms.lending.collateral.internal;

import com.rincoltech.bms.lending.collateral.CollateralLookup;
import com.rincoltech.bms.lending.collateral.internal.CollateralApi.CollateralResponse;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
        return repo.find(collateralId).map(CollateralLookupService::summary);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<CollateralSummary> lockForPledge(UUID collateralId) {
        return repo.lock(collateralId).map(CollateralLookupService::summary);
    }

    private static CollateralSummary summary(CollateralResponse c) {
        return new CollateralSummary(
                c.id(),
                c.memberId(),
                c.branchId(),
                c.collateralType(),
                c.custodyStatus(),
                c.collateralValueMinor(),
                c.currency());
    }
}
