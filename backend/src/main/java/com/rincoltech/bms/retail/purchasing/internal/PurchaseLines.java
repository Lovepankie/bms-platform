package com.rincoltech.bms.retail.purchasing.internal;

import com.rincoltech.bms.core.tenancy.Branches;
import com.rincoltech.bms.core.tenancy.CurrentTenant;
import com.rincoltech.bms.core.tenancy.TenantSequences;
import com.rincoltech.bms.kernel.ApiException;
import com.rincoltech.bms.kernel.ApiException.FieldProblem;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.PackUnit;
import com.rincoltech.bms.retail.catalogue.RetailCatalogue.ProductSnapshot;
import com.rincoltech.bms.retail.stock.Quantities;
import com.rincoltech.bms.retail.stock.RetailCapabilities;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What an order line and a receipt line have in common (ADR-033 decision 5): the product, the unit it is entered in,
 * the conversion to base units, and the money worked out once. The rules are those of the plain restock
 * ({@code PurchasingService}), which is left as it is: stock, the movements and the cost per base unit are in base
 * units, a quantity that does not convert exactly is refused, a cost per pack is divided by the factor and rounded
 * half up once, and the difference to the amount as entered is what purchase rounding takes.
 */
@Component
class PurchaseLines {

    private final RetailCatalogue catalogue;
    private final RetailCapabilities capabilities;
    private final CurrentTenant tenant;
    private final Branches branches;
    private final TenantSequences sequences;

    PurchaseLines(
            RetailCatalogue catalogue,
            RetailCapabilities capabilities,
            CurrentTenant tenant,
            Branches branches,
            TenantSequences sequences) {
        this.catalogue = catalogue;
        this.capabilities = capabilities;
        this.tenant = tenant;
        this.branches = branches;
        this.sequences = sequences;
    }

    /**
     * One line worked out. {@code unitQty} and {@code pack} are null for a line entered in the base unit.
     *
     * @param enteredCost the cost of one unit as entered (one pack for a pack line)
     * @param costBase the cost of one base unit
     * @param lineTotal the value as entered, which is what is owed
     * @param stocked the value the movements carry: base quantity times the cost of one base unit
     */
    record Planned(
            ProductSnapshot product,
            PackUnit pack,
            BigDecimal baseQty,
            BigDecimal unitQty,
            long enteredCost,
            long costBase,
            long lineTotal,
            long stocked) {}

    /** A document number and the sequence it came from. */
    record Numbered(String no, long seq) {}

    /** Null when a problem was added. */
    Planned plan(
            String field, UUID productId, UUID unitId, BigDecimal qty, long enteredCost, List<FieldProblem> problems) {
        ProductSnapshot p = catalogue.find(productId).orElse(null);
        if (p == null) {
            problems.add(new FieldProblem(field + ".product_id", "unknown_product", "No such product."));
            return null;
        }
        if (!p.currency().equals(tenant.profile().currency())) {
            problems.add(new FieldProblem(field + ".product_id", "currency_mismatch", "Priced in " + p.currency()));
            return null;
        }
        if (!Quantities.exact(qty)) {
            problems.add(new FieldProblem(field + ".qty", "invalid", "At most three decimal places."));
            return null;
        }
        PackUnit pack = null;
        if (unitId != null && !unitId.equals(p.unitId())) {
            if (!capabilities.enabled(RetailCapabilities.UNITS)) {
                problems.add(new FieldProblem(
                        field + ".unit_id", "capability_not_enabled", "Pack sizes are not enabled for this business."));
                return null;
            }
            pack = catalogue.packUnit(p.id(), unitId).orElse(null);
            if (pack == null || !pack.active()) {
                problems.add(new FieldProblem(
                        field + ".unit_id", "unknown_pack_unit", "This item is not bought in that unit."));
                return null;
            }
        }
        long costBase = pack == null ? enteredCost : Quantities.baseCost(enteredCost, pack.factor());
        if (costBase > RetailCatalogue.MAX_AMOUNT_MINOR || enteredCost > RetailCatalogue.MAX_AMOUNT_MINOR) {
            throw ApiException.rule("amount_out_of_range", "The cost on " + field + " is too large to compute.");
        }
        BigDecimal base = qty;
        if (pack != null) {
            PackUnit packed = pack;
            base = Quantities.toBase(qty, pack.factor())
                    .orElseThrow(() -> ApiException.rule(
                            "quantity_not_representable",
                            qty + " " + packed.unit() + " does not convert exactly to thousandths of the item's unit."
                                    + " Change the quantity."));
        }
        long total = value(qty, enteredCost);
        long stocked = pack == null ? total : value(base, costBase);
        return new Planned(p, pack, base, pack == null ? null : qty, enteredCost, costBase, total, stocked);
    }

    /** {@code qty x unitMinor} rounded half up once, refused when it does not fit the money columns. */
    static long value(BigDecimal qty, long unitMinor) {
        BigDecimal v = qty.multiply(BigDecimal.valueOf(unitMinor));
        if (v.compareTo(BigDecimal.valueOf(RetailCatalogue.MAX_AMOUNT_MINOR)) > 0) {
            throw ApiException.rule("amount_out_of_range", "A line comes to more than can be recorded.");
        }
        return Quantities.value(qty, unitMinor);
    }

    /** {@code amount x part / whole}, rounded half up once. */
    static long share(long amount, BigDecimal part, BigDecimal whole) {
        return BigDecimal.valueOf(amount)
                .multiply(part)
                .divide(whole, 0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** The next number of a series per tenant and branch: {@code prefix + branch code + "-" + six digits}. */
    Numbered next(String prefix, String sequence, UUID branchId) {
        String code = branches.all().stream()
                .filter(b -> b.id().equals(branchId))
                .findFirst()
                .orElseThrow(ApiException::notFound)
                .code();
        long seq = sequences.next(sequence + ":" + branchId);
        return new Numbered("%s%s-%06d".formatted(prefix, code, seq), seq);
    }
}
