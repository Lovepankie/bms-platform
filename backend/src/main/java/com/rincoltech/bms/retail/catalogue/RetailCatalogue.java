package com.rincoltech.bms.retail.catalogue;

import com.rincoltech.bms.kernel.Principal;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Optional;
import java.util.UUID;

/**
 * The catalogue's public API for the other retail modules: a product's current prices, read in the
 * caller's transaction. A sale snapshots {@code costMinor} and {@code sellMinor} from here
 * (ADR-020 decisions 5 and 6).
 */
public interface RetailCatalogue {

    /**
     * The largest amount in minor units any retail request may carry: 10^13 (review F7). Bean
     * Validation refuses more with 422, and the database CHECKs of migration V14 match it.
     */
    long MAX_AMOUNT_MINOR = 10_000_000_000_000L;

    /** The key of the units-of-measure capability in the tenant's retail.capabilities settings group (ADR-033). */
    String UNITS_CAPABILITY = "units";

    Optional<ProductSnapshot> find(UUID productId);

    /** What every selling price in a response says when it is left out (ADR-055). */
    String PRICE_ONLY =
            "Present only to whoever sells, oversees selling, sets prices or reads cost (retail.sale.create, retail.sale.all_sellers, retail.price.edit or retail.profit.read): the storekeeper sees no prices (ADR-055)";

    /**
     * ADR-055 (the owner's decision of 21 October 2026): selling prices are shown to whoever sells, sets prices or reads cost.
     * A storekeeper, who holds none of those, sees no price in any response. A supervisor (who may see every seller's sales,
     * {@code retail.sale.all_sellers}) oversees selling, so sees prices too, never cost. Prices are tenant-wide, so a
     * permission held at any branch counts.
     */
    static boolean maySeePrices(Principal principal) {
        return principal.hasPermission("retail.sale.create")
                || principal.hasPermission("retail.price.edit")
                || principal.hasPermission("retail.sale.all_sellers")
                || principal.hasPermission("retail.profit.read");
    }

    /**
     * ADR-033, issue #194: the pack of a product in the given unit (a carton of twelve pieces), read
     * in the caller's transaction. Empty when the product has no pack of that unit. Whether packs
     * may be used at all is the {@code units} capability, which the caller checks.
     */
    Optional<PackUnit> packUnit(UUID productId, UUID unitId);

    /**
     * Takes the product's row lock for the rest of the caller's transaction. A caller that changes
     * several products locks them in id order first, so concurrent events cannot deadlock.
     */
    Optional<ProductSnapshot> lock(UUID productId);

    /**
     * FR-RET-06, ADR-020 decision 5: a restock line's prices become the product's current prices in
     * the caller's transaction, with a {@code purchase} history row naming the purchase and an audit
     * row. A null sell price keeps the current one. Nothing is written when neither price changes.
     *
     * @return the product as it is now
     */
    ProductSnapshot applyPurchasePrices(UUID productId, long costMinor, Long sellMinor, UUID purchaseId);

    /**
     * ADR-033, issue #199: a landed cost or a supplier price credit moves the product's cost to {@code costMinor} in the
     * caller's transaction, with a {@code purchase} history row that names the document ({@code sourceId}) and says why, and
     * an audit row. The selling price is kept. The caller has decided that the cost may change (the cost update guard).
     *
     * @return the product as it is now
     */
    ProductSnapshot applyCostChange(UUID productId, long costMinor, UUID sourceId, String reason);

    /**
     * ADR-033, issue #199: records, without changing any price, that a landed cost or supplier credit did not move the
     * product's cost because a newer cost event had set it since (a {@code purchase} history row with the same cost on both
     * sides and the reason). Never overwrites a newer cost.
     */
    void noteCostNotApplied(UUID productId, UUID sourceId, String reason);

    /**
     * Issue #351, ADR-044: a voided restock gives back the prices it set, in the caller's transaction, but only while it is
     * still the product's latest price event (no later restock, manual change, landed cost or credit has moved them). The
     * cost and the selling price go back to what they were before the restock's first change to this product, with a
     * {@code purchase} history row that names the restock and says why. When a later event moved them, nothing changes and
     * a history row with the same prices on both sides says the restock was voided without moving them. The same when the
     * caller reports {@code laterPurchase}: a restock or receipt that still stands put stock in afterwards, and may have paid
     * those prices without writing a history row (the prices did not change then). Nothing is written when the restock
     * changed no price of this product.
     *
     * @return true when the prices went back
     */
    boolean revertPurchasePrices(UUID productId, UUID purchaseId, String reason, boolean laterPurchase);

    /**
     * The one normal form of a product code, for the API and the importer alike (review F9):
     * {@code strip()} and the no-break spaces it keeps are removed at the ends (a no-break space pasted from a
     * spreadsheet), then NFKC folds compatibility forms (full-width letters and digits). A code that
     * is then empty, or holds a control or format character or any space other than U+0020, is
     * refused. The database CHECK of migration V14 enforces the same rule.
     *
     * @return the normalised code, or empty when it is not acceptable
     */
    static Optional<String> normaliseCode(String raw) {
        if (raw == null || !acceptable(stripSpaces(raw))) {
            return Optional.empty();
        }
        String code = stripSpaces(Normalizer.normalize(stripSpaces(raw), Normalizer.Form.NFKC));
        return acceptable(code) ? Optional.of(code) : Optional.empty();
    }

    /** {@code strip()} plus the no-break spaces it leaves (U+00A0, U+2007, U+202F). */
    private static String stripSpaces(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isSpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private static boolean acceptable(String code) {
        return !code.isEmpty()
                && code.codePoints()
                        .allMatch(c -> c == ' '
                                || !(Character.isISOControl(c)
                                        || Character.getType(c) == Character.FORMAT
                                        || Character.isSpaceChar(c)
                                        || Character.isWhitespace(c)));
    }

    /** A pack size: {@code factor} base units in one pack of {@code unit}. */
    record PackUnit(UUID unitId, String unit, BigDecimal factor, boolean active) {}

    record ProductSnapshot(
            UUID id,
            String code,
            String description,
            UUID unitId,
            String unit,
            long costMinor,
            long sellMinor,
            String currency,
            boolean active) {}
}
