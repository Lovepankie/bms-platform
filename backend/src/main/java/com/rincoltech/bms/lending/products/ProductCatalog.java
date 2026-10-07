package com.rincoltech.bms.lending.products;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What other lending sub-domains (loans) may ask of loan products: the terms of a version. */
public interface ProductCatalog {

    /** The product's current version, whatever its status; the caller checks {@code productStatus}. */
    Optional<ProductTerms> current(UUID productId);

    /** A specific version, for loans that keep the terms they were created with (FR-PRD-04). */
    Optional<ProductTerms> version(UUID productVersionId);

    /**
     * The version's fees with timing {@code added_to_loan} for a principal (FR-PRD-02), so a loan's
     * schedule carries the same fees as the product preview.
     */
    long addedFeesMinor(UUID productVersionId, long principalMinor);

    /**
     * Every fee of the version computed for a principal (FR-PRD-02), so disbursement deducts,
     * collects or schedules each one exactly as the product states.
     */
    List<FeeCharge> fees(UUID productVersionId, long principalMinor);

    /** One fee for one principal; {@code timing} is {@code deducted_at_disbursement}, {@code added_to_loan} or {@code paid_upfront}. */
    record FeeCharge(String name, String timing, long amountMinor) {}

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
            boolean requiresGuarantor,
            List<String> allocationOrder,
            boolean flatEarlySettlementRebate) {

        public ProductTerms {
            allocationOrder = List.copyOf(allocationOrder);
        }

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
