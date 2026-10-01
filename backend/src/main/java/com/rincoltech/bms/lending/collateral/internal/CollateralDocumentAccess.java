package com.rincoltech.bms.lending.collateral.internal;

import com.rincoltech.bms.core.documents.DocumentAccess;
import com.rincoltech.bms.kernel.Principal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Whoever may read a collateral item, in its branch, may read its photos and scans (FR-DOC-03). */
@Component
class CollateralDocumentAccess implements DocumentAccess {

    private final CollateralRepository repo;

    CollateralDocumentAccess(CollateralRepository repo) {
        this.repo = repo;
    }

    @Override
    public String subjectType() {
        return CollateralService.SUBJECT;
    }

    @Override
    public boolean canRead(Principal principal, UUID subjectId) {
        return repo.find(subjectId)
                .filter(c -> principal.may("lending.collateral.read", c.branchId()))
                .isPresent();
    }
}
