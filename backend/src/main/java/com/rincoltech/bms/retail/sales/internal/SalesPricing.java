package com.rincoltech.bms.retail.sales.internal;

import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.kernel.CurrentPrincipal;
import com.rincoltech.bms.retail.catalogue.RetailPricing;
import com.rincoltech.bms.retail.catalogue.RetailPricing.GroupRef;
import com.rincoltech.bms.retail.catalogue.RetailPricing.ListedPrice;
import com.rincoltech.bms.retail.catalogue.RetailPricing.PriceListRef;
import com.rincoltech.bms.retail.sales.internal.SalesApi.Customer;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Which price a sale line takes while the {@code price_lists} capability is on (ADR-033, issue #195;
 * docs/specs/trade-capabilities.md section 3.2). The server decides, in this order: the list the sale names (a list
 * other than the customer's own or the customer group's needs {@code retail.pricelist.use}), else the customer's own
 * list, else the customer group's list, else none. In the list, the item for the product and the sale unit with the
 * largest quantity break not above the line's quantity, valid on the sale date. No item: the retail price of that
 * unit, unless the list is strict. A price the seller types that differs from the resolved one is an override.
 * Money here is integer minor units and nothing is rounded: the line total is rounded once by the caller.
 */
@Component
class SalesPricing {

    static final String USE = "retail.pricelist.use";
    static final String OVERRIDE = "retail.price.override";
    static final int MIN_REASON = 5;

    private final RetailCapabilities capabilities;
    private final RetailPricing pricing;

    SalesPricing(RetailCapabilities capabilities, RetailPricing pricing) {
        this.capabilities = capabilities;
        this.pricing = pricing;
    }

    /** True while the capability is on; off means the sale takes the path it took before price lists existed. */
    boolean on() {
        return capabilities.enabled(RetailCapabilities.PRICE_LISTS);
    }

    /** The list in force for a sale and why: {@code origin} is request, customer or customer_group. */
    record Chosen(UUID id, String name, boolean strict, String origin) {}

    /** The price of one line: where it came from and, for a list item, the list and the break that applied. */
    record Decision(String source, UUID listId, BigDecimal breakMinQty, long priceMinor, boolean refused) {}

    /**
     * The list that applies, or null for none. A list that is switched off is passed over (the next level applies);
     * a requested list that is off or unknown is a field problem.
     */
    Chosen choose(UUID branch, UUID requested, Customer customer, List<FieldProblem> problems) {
        Chosen own = customer == null ? null : active(customer.priceListId(), "customer");
        Chosen group = null;
        if (customer != null && customer.customerGroupId() != null) {
            group = pricing.customerGroup(customer.customerGroupId())
                    .filter(GroupRef::active)
                    .map(g -> active(g.priceListId(), "customer_group"))
                    .orElse(null);
        }
        Chosen automatic = own != null ? own : group;
        if (requested == null) {
            return automatic;
        }
        if (own != null && own.id().equals(requested)) {
            return own;
        }
        if (group != null && group.id().equals(requested)) {
            return group;
        }
        Chosen asked = active(requested, "request");
        if (asked == null) {
            problems.add(new FieldProblem(
                    "price_list_id", "unknown_price_list", "No such price list, or it is switched off."));
            return automatic;
        }
        if (!CurrentPrincipal.require().may(USE, branch)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "permission_denied",
                    "Permission denied",
                    "You do not have permission to price a sale from a list other than the customer's own.");
        }
        return asked;
    }

    private Chosen active(UUID listId, String origin) {
        if (listId == null) {
            return null;
        }
        Optional<PriceListRef> list = pricing.priceList(listId).filter(PriceListRef::active);
        return list.map(l -> new Chosen(l.id(), l.name(), l.strict(), origin)).orElse(null);
    }

    /**
     * The price per sale unit: the list item when there is one, else the retail price of that unit
     * ({@code defaultPrice}), or a refusal when the list is strict and has no such item.
     */
    Decision decide(Chosen list, UUID productId, UUID unitId, BigDecimal qty, long defaultPrice, LocalDate on) {
        if (list != null) {
            Optional<ListedPrice> item = pricing.price(list.id(), productId, unitId, qty, on);
            if (item.isPresent()) {
                return new Decision(
                        "list", list.id(), item.get().minQty(), item.get().priceMinor(), false);
            }
            if (list.strict()) {
                return new Decision("retail", null, null, defaultPrice, true);
            }
        }
        return new Decision("retail", null, null, defaultPrice, false);
    }
}
