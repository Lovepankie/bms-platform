package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class CatalogueLookup implements RetailCatalogue {

    private final CatalogueRepository repo;
    private final CatalogueService service;

    CatalogueLookup(CatalogueRepository repo, CatalogueService service) {
        this.repo = repo;
        this.service = service;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductSnapshot> find(UUID productId) {
        return repo.find(productId).map(CatalogueLookup::snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PackUnit> packUnit(UUID productId, UUID unitId) {
        return repo.pack(productId, unitId)
                .map(k -> new PackUnit(k.unitId(), k.unit(), new java.math.BigDecimal(k.factor()), k.active()));
    }

    @Override
    @Transactional(readOnly = true)
    public long sellMinorAt(UUID productId, java.time.Instant at) {
        return repo.sellMinorAt(productId, at);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProductSnapshot> lock(UUID productId) {
        return repo.lock(productId).map(CatalogueLookup::snapshot);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void useBuyingSize(
            UUID productId, UUID unitId, java.math.BigDecimal factor, String sourceType, UUID sourceId) {
        service.useBuyingSize(productId, unitId, factor, sourceType, sourceId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductSnapshot applyPurchasePrices(UUID productId, long costMinor, Long sellMinor, UUID purchaseId) {
        CatalogueApi.Product locked = repo.lock(productId).orElseThrow(ApiException::notFound);
        long sell = sellMinor == null ? locked.sellMinor() : sellMinor;
        if (costMinor != locked.costMinor() || sell != locked.sellMinor()) {
            service.changePrices(locked, costMinor, sell, "purchase", purchaseId, null);
        }
        return snapshot(repo.find(productId).orElseThrow());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProductSnapshot applyCostChange(UUID productId, long costMinor, UUID sourceId, String reason) {
        CatalogueApi.Product locked = repo.lock(productId).orElseThrow(ApiException::notFound);
        if (costMinor != locked.costMinor()) {
            service.changePrices(locked, costMinor, locked.sellMinor(), "purchase", sourceId, reason);
        }
        return snapshot(repo.find(productId).orElseThrow());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean revertPurchasePrices(UUID productId, UUID purchaseId, String reason, boolean laterPurchase) {
        CatalogueApi.Product locked = repo.lock(productId).orElseThrow(ApiException::notFound);
        return service.revertPurchasePrices(locked, purchaseId, reason, laterPurchase);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void noteCostNotApplied(UUID productId, UUID sourceId, String reason) {
        CatalogueApi.Product locked = repo.lock(productId).orElseThrow(ApiException::notFound);
        service.noteCostUnchanged(locked, sourceId, reason);
    }

    static ProductSnapshot snapshot(CatalogueApi.Product p) {
        return new ProductSnapshot(
                p.id(),
                p.code(),
                p.description(),
                p.unitId(),
                p.unit(),
                p.costMinor(),
                p.sellMinor(),
                p.currency(),
                p.active());
    }
}
