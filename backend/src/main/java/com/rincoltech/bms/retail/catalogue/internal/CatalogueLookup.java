package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CatalogueLookup implements RetailCatalogue {

    private final CatalogueRepository repo;

    CatalogueLookup(CatalogueRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductSnapshot> find(UUID productId) {
        return repo.find(productId).map(CatalogueLookup::snapshot);
    }

    static ProductSnapshot snapshot(CatalogueApi.Product p) {
        return new ProductSnapshot(
                p.id(), p.code(), p.description(), p.unit(), p.costMinor(), p.sellMinor(), p.currency(), p.active());
    }
}
