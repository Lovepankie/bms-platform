package com.rincoltech.bms.lending.products.internal;

import com.rincoltech.bms.lending.products.ProductCatalog;
import com.rincoltech.bms.lending.products.ScheduleCalculator;
import com.rincoltech.bms.lending.products.internal.ProductApi.Version;
import com.rincoltech.bms.lending.products.internal.ProductRepository.Row;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ProductCatalog} for the loans module. */
@Service
class ProductCatalogService implements ProductCatalog {

    private final ProductRepository repo;
    private final JdbcClient jdbc;

    ProductCatalogService(ProductRepository repo, JdbcClient jdbc) {
        this.repo = repo;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductTerms> current(UUID productId) {
        return repo.find(productId).flatMap(row -> terms(row, row.currentVersionId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductTerms> version(UUID productVersionId) {
        return jdbc.sql("SELECT product_id FROM lending_loan_product_versions WHERE id = ?")
                .param(productVersionId)
                .query(UUID.class)
                .optional()
                .flatMap(repo::find)
                .flatMap(row -> terms(row, productVersionId));
    }

    @Override
    @Transactional(readOnly = true)
    public long addedFeesMinor(UUID productVersionId, long principalMinor) {
        return repo.fees(productVersionId).stream()
                .filter(f -> f.timing().equals("added_to_loan"))
                .mapToLong(f -> f.calcMethod().equals("flat")
                        ? f.amountMinor()
                        : ScheduleCalculator.percentOf(principalMinor, f.rateBp()))
                .reduce(0, Math::addExact);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeeCharge> fees(UUID productVersionId, long principalMinor) {
        return repo.fees(productVersionId).stream()
                .map(f -> new FeeCharge(
                        f.name(),
                        f.timing(),
                        f.calcMethod().equals("flat")
                                ? f.amountMinor()
                                : ScheduleCalculator.percentOf(principalMinor, f.rateBp())))
                .toList();
    }

    private Optional<ProductTerms> terms(Row row, UUID versionId) {
        return repo.versions(row.id()).stream()
                .filter(v -> v.id().equals(versionId))
                .findFirst()
                .map(v -> of(row, v));
    }

    private static ProductTerms of(Row row, Version v) {
        return new ProductTerms(
                row.id(),
                row.code(),
                row.status(),
                v.id(),
                v.versionNo(),
                v.currency(),
                new ScheduleCalculator.Terms(
                        v.interestMethod(),
                        v.interestRateBp(),
                        v.rateUnit(),
                        v.termUnit(),
                        v.defaultTermCount(),
                        v.repaymentPattern(),
                        v.instalmentFrequency()),
                v.minTermCount(),
                v.maxTermCount(),
                v.minPrincipalMinor(),
                v.maxPrincipalMinor(),
                v.requiresCollateral(),
                v.minCollateralCoverBp(),
                v.requiresGuarantor(),
                v.allocationOrder(),
                v.flatEarlySettlementRebate());
    }
}
