package com.rincoltech.bms.lending.products;

import java.util.Optional;
import java.util.UUID;

/** What other lending sub-domains (loans) may ask of loan products: the terms of a version. */
public interface ProductCatalog {

    /** The product's current version, whatever its status; the caller checks {@code productStatus}. */
    Optional<ProductTerms> current(UUID productId);

    /** A specific version, for loans that keep the terms they were created with (FR-PRD-04). */
    Optional<ProductTerms> version(UUID productVersionId);

    record ProductTerms(
            UUID productId,
            String productCode,
            String productStatus,
            UUID versionId,
            int versionNo,
            String currency,
            ScheduleCalculator.Terms defaults,
            int minTermCount,
            int maxTermCount,
            long minPrincipalMinor,
            long maxPrincipalMinor,
            boolean requiresCollateral,
            Integer minCollateralCoverBp,
            boolean requiresGuarantor) {

        /** The calculator terms for a chosen term count. */
        public ScheduleCalculator.Terms withTermCount(int termCount) {
            return new ScheduleCalculator.Terms(
                    defaults.interestMethod(),
                    defaults.interestRateBp(),
                    defaults.rateUnit(),
                    defaults.termUnit(),
                    termCount,
                    defaults.repaymentPattern(),
                    defaults.instalmentFrequency());
        }
    }
}
