package com.rincoltech.bms.retail.catalogue;

import java.text.Normalizer;
import java.util.Optional;
import java.util.UUID;

/**
 * The catalogue's public API for the other retail modules: a product's current prices, read in the
 * caller's transaction. A sale snapshots {@code costMinor} and {@code sellMinor} from here
 * (ADR-020 decisions 5 and 6).
 */
public interface RetailCatalogue {

    Optional<ProductSnapshot> find(UUID productId);

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

    record ProductSnapshot(
            UUID id,
            String code,
            String description,
            String unit,
            long costMinor,
            long sellMinor,
            String currency,
            boolean active) {}
}
