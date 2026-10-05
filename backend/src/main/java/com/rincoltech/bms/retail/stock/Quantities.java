package com.rincoltech.bms.retail.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Quantities are exact decimals with three places (ADR-020 decision 3). A quantity times a unit
 * amount in minor units is rounded half up to the minor unit once, at the line it is computed for
 * (chapter 3 section 3.4, R-ROUND). No floating point.
 */
public final class Quantities {

    public static final int SCALE = 3;

    private Quantities() {}

    /** The value of {@code qty} units at {@code unitMinor} each, in minor units, rounded half up. */
    public static long value(BigDecimal qty, long unitMinor) {
        return qty.multiply(BigDecimal.valueOf(unitMinor))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** The wire form: a plain decimal string with three places, for example {@code "3.500"}. */
    public static String format(BigDecimal qty) {
        return qty.setScale(SCALE, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** True when the quantity has at most three decimal places. */
    public static boolean exact(BigDecimal qty) {
        return qty.stripTrailingZeros().scale() <= SCALE;
    }
}
