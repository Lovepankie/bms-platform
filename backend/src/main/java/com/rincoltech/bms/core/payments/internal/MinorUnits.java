package com.rincoltech.bms.core.payments.internal;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Where money crosses the provider's boundary, once and in one place (ADR-035 decision 3, ADR-004). Amounts are integer
 * minor units everywhere else; the provider takes and returns a decimal number. The conversion is exact: no float, no
 * rounding, and a value the currency cannot hold is refused, not trimmed. The sandbox spike showed the provider itself
 * accepts and returns fractions of a shilling (1000.55 and 0.5 were both carried), so this class is the only thing
 * that keeps a fraction out (spec 3.12).
 *
 * <p>Only currencies listed here can be collected. UGX has no minor unit, so 1,500 shillings are 1500.
 */
final class MinorUnits {

    /** Currency to the number of decimal places of its minor unit. */
    private static final Map<String, Integer> EXPONENTS = Map.of("UGX", 0);

    private MinorUnits() {}

    static boolean supported(String currency) {
        return currency != null && EXPONENTS.containsKey(currency);
    }

    static int exponent(String currency) {
        Integer exponent = currency == null ? null : EXPONENTS.get(currency);
        if (exponent == null) {
            throw new IllegalArgumentException("currency is not supported for collections");
        }
        return exponent;
    }

    /** The decimal the provider is sent: {@code 1500} minor units of UGX are {@code 1500}. */
    static BigDecimal toDecimal(long minor, String currency) {
        return BigDecimal.valueOf(minor, exponent(currency));
    }

    /**
     * The minor units a provider decimal stands for, or empty when it has a fraction the currency cannot hold, is out of
     * range for a long, or is not positive. {@code 1000.0} is 1000 (a trailing zero is not a fraction); {@code 1000.55}
     * and {@code 0.5} are empty.
     */
    static Optional<Long> fromDecimal(BigDecimal value, String currency) {
        if (value == null || value.signum() <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(value.movePointRight(exponent(currency)).longValueExact());
        } catch (ArithmeticException e) {
            return Optional.empty();
        }
    }
}
