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
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ProductSnapshot> lock(UUID productId) {
        return repo.lock(productId).map(CatalogueLookup::snapshot);
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

    static ProductSnapshot snapshot(CatalogueApi.Product p) {
        return new ProductSnapshot(
                p.id(), p.code(), p.description(), p.unit(), p.costMinor(), p.sellMinor(), p.currency(), p.active());
    }
}
