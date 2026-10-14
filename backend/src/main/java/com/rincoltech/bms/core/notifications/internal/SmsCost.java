package com.rincoltech.bms.core.notifications.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the cost string a provider returns for a message, such as {@code UGX 32.0000}, into
 * integer minor units (FR-NTF-15; money is never a float, ADR-004). The amount goes through
 * {@link BigDecimal}, scaled to the currency's exponent and rounded half up. A string that is not
 * a three letter currency, a space and a plain non-negative decimal gives empty, and the caller
 * keeps only the raw string. Pure and golden tested.
 */
final class SmsCost {

    /** Currency exponents the providers we use bill in; any other currency is kept raw, not guessed. */
    private static final Map<String, Integer> EXPONENT = Map.of("UGX", 0, "KES", 2, "TZS", 2, "RWF", 0, "USD", 2);

    private static final Pattern COST = Pattern.compile("^([A-Z]{3}) (\\d{1,12}(?:\\.\\d{1,8})?)$");

    record Cost(String currency, long minor) {}

    private SmsCost() {}

    static Optional<Cost> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher m = COST.matcher(raw.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        Integer exponent = EXPONENT.get(m.group(1));
        if (exponent == null) {
            return Optional.empty();
        }
        BigDecimal minor = new BigDecimal(m.group(2)).movePointRight(exponent).setScale(0, RoundingMode.HALF_UP);
        if (minor.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
            return Optional.empty();
        }
        return Optional.of(new Cost(m.group(1), minor.longValueExact()));
    }
}
