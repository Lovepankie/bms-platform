package com.rincoltech.bms.retail.catalogue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * The price lists as the sale reads them (ADR-033, issue #195; docs/specs/trade-capabilities.md section 3), read in
 * the caller's transaction. A sale decides which list applies (the sale's own, the customer's, the customer
 * group's) and asks here for the price of one product in one unit at a quantity on a date. Whether the
 * capability is on is the caller's check ({@code price_lists} in the tenant's retail.capabilities group).
 */
public interface RetailPricing {

    /** The key of the price-lists capability in the tenant's retail.capabilities settings group (ADR-033). */
    String PRICE_LISTS_CAPABILITY = "price_lists";

    Optional<PriceListRef> priceList(UUID priceListId);

    Optional<GroupRef> customerGroup(UUID groupId);

    /**
     * The price in the list for the product in the unit (its base unit or a pack) at the quantity, among the items
     * valid on the date: the one with the largest {@code min_qty} not above the quantity. A break applies to the whole
     * line, not in tiers. Empty when the list has no such item.
     */
    Optional<ListedPrice> price(UUID priceListId, UUID productId, UUID unitId, BigDecimal qty, LocalDate on);

    /** A list: {@code strict} refuses a product that is not in it instead of falling back to the retail price. */
    record PriceListRef(UUID id, String name, boolean strict, boolean active) {}

    /** A customer group and the list it prices from, if any. */
    record GroupRef(UUID id, String name, UUID priceListId, boolean active) {}

    /** The price per unit of the item and the quantity break it applies from. */
    record ListedPrice(long priceMinor, BigDecimal minQty) {}
}
