package com.rincoltech.bms.retail.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Quantities are exact decimals with three places (ADR-020 decision 3). A quantity times a unit
 * amount in minor units is rounded half up to the minor unit once, at the line it is computed for
 * (chapter 3 section 3.4, R-ROUND). No floating point.
 */
public final class Quantities {

    public static final int SCALE = 3;

    /** The largest quantity a {@code numeric(14,3)} column holds. */
    public static final BigDecimal MAX_QTY = new BigDecimal("99999999999.999");

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

    // ---- Units of measure (ADR-033, docs/specs/trade-capabilities.md section 4) -----------------
    // Stock is held in the base unit. A pack has a factor, the number of base units in one pack.
    // Quantities never round: a conversion that is not exact in three places is refused. Money
    // rounds half up once per line. All of it is BigDecimal and long, never a double.

    /** {@code qty x factor} in base units, or empty when it is not exact in three places or does not fit the column. */
    public static Optional<BigDecimal> toBase(BigDecimal qty, BigDecimal factor) {
        BigDecimal base = qty.multiply(factor);
        if (!exact(base) || base.compareTo(MAX_QTY) > 0) {
            return Optional.empty();
        }
        return Optional.of(base.setScale(SCALE, RoundingMode.UNNECESSARY));
    }

    /** The quantity in the pack unit of a base quantity that was entered in that unit (the division is exact). */
    public static BigDecimal fromBase(BigDecimal baseQty, BigDecimal factor) {
        return baseQty.divide(factor, SCALE, RoundingMode.HALF_UP);
    }

    /** The price of one pack from the price of one base unit: {@code base x factor}, rounded half up once. */
    public static long packPrice(long baseUnitMinor, BigDecimal factor) {
        return value(factor, baseUnitMinor);
    }

    /** The cost of one base unit from the cost of one pack: {@code pack / factor}, rounded half up to the minor unit. */
    public static long baseCost(long packMinor, BigDecimal factor) {
        return BigDecimal.valueOf(packMinor)
                .divide(factor, 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** The display price per base unit of a line: {@code total / baseQty}, rounded half up. */
    public static long perBase(long totalMinor, BigDecimal baseQty) {
        return BigDecimal.valueOf(totalMinor)
                .divide(baseQty, 0, RoundingMode.HALF_UP)
                .longValueExact();
    }
}
