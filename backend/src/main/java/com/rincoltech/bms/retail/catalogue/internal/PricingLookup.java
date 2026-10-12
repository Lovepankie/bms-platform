package com.rincoltech.bms.retail.catalogue.internal;

import com.rincoltech.bms.retail.catalogue.RetailPricing;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The sale's read of the price lists (ADR-033, issue #195): the catalogue's public {@link RetailPricing}. */
@Service
class PricingLookup implements RetailPricing {

    private final PriceListRepository repo;

    PricingLookup(PriceListRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PriceListRef> priceList(UUID priceListId) {
        return repo.list(priceListId, LocalDate.EPOCH)
                .map(l -> new PriceListRef(l.id(), l.name(), l.strict(), l.active()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GroupRef> customerGroup(UUID groupId) {
        return repo.group(groupId).map(g -> new GroupRef(g.id(), g.name(), g.priceListId(), g.active()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ListedPrice> price(UUID priceListId, UUID productId, UUID unitId, BigDecimal qty, LocalDate on) {
        return repo.price(priceListId, productId, unitId, qty, on);
    }
}
