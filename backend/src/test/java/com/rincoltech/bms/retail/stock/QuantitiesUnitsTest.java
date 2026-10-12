package com.rincoltech.bms.retail.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The unit conversions of ADR-033 (docs/specs/trade-capabilities.md section 4.2): quantities never
 * round, money rounds half up once, and no floating point is involved. Amounts are fabricated.
 */
class QuantitiesUnitsTest {

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void aWholePackConvertsExactly() {
        assertThat(Quantities.toBase(d("2"), d("12.000"))).contains(d("24.000"));
        assertThat(Quantities.toBase(d("0.5"), d("12"))).contains(d("6.000"));
    }

    @Test
    void aQuantityThatNeedsMoreThanThreePlacesIsRefusedNotRounded() {
        // 0.001 of a pack of 1.5 is 0.0015 of the base unit.
        assertThat(Quantities.toBase(d("0.001"), d("1.5"))).isEmpty();
        // 0.003 packs of 1.25 is 0.00375.
        assertThat(Quantities.toBase(d("0.003"), d("1.25"))).isEmpty();
        // 0.002 packs of 1.5 is exactly 0.003, and 3 packs of 1.333 exactly 3.999.
        assertThat(Quantities.toBase(d("0.002"), d("1.5"))).contains(d("0.003"));
        assertThat(Quantities.toBase(d("3"), d("1.333"))).contains(d("3.999"));
    }

    @Test
    void aQuantityBeyondTheColumnIsRefused() {
        assertThat(Quantities.toBase(d("99999999999.999"), d("1"))).isPresent();
        assertThat(Quantities.toBase(d("99999999999.999"), d("1.001"))).isEmpty();
        assertThat(Quantities.toBase(d("50000000000"), d("2"))).isEmpty();
    }

    @Test
    void aPackPriceRoundsHalfUpOnceAndNeverFromAFloat() {
        assertThat(Quantities.packPrice(83, d("12"))).isEqualTo(996);
        // 85 x 12.5 = 1062.5 rounds up; 85 x 12.25 = 1041.25 rounds down.
        assertThat(Quantities.packPrice(85, d("12.5"))).isEqualTo(1063);
        assertThat(Quantities.packPrice(85, d("12.25"))).isEqualTo(1041);
    }

    @Test
    void aPackCostPerBaseUnitRoundsHalfUp() {
        assertThat(Quantities.baseCost(1_000, d("12"))).isEqualTo(83); // 83.33
        assertThat(Quantities.baseCost(1_002, d("12"))).isEqualTo(84); // 83.5 rounds up
        assertThat(Quantities.baseCost(5, d("2"))).isEqualTo(3); // 2.5 rounds up
        assertThat(Quantities.baseCost(0, d("12"))).isZero();
        assertThat(Quantities.baseCost(1, d("1000"))).isZero(); // 0.001
    }

    @Test
    void thePricePerBaseUnitOfALineIsTheRoundedTotalOverTheBaseQuantity() {
        assertThat(Quantities.perBase(1_000, d("12.000"))).isEqualTo(83);
        assertThat(Quantities.perBase(1_002, d("12.000"))).isEqualTo(84);
        assertThat(Quantities.perBase(997, d("12.000"))).isEqualTo(83);
    }

    /** Edge of the below-cost rule: 996 is the cost of a pack of twelve at 83; 1,000 is above it though 1,000 / 12 rounds to 83. */
    @Test
    void aPackLineAboveCostIsAboveCostOnTheLineTotalEvenWhenThePerBasePriceRoundsToTheCost() {
        BigDecimal factor = d("12");
        long lineCost = Quantities.value(Quantities.toBase(d("1"), factor).orElseThrow(), 83);
        long lineTotal = Quantities.value(d("1"), 1_000);
        assertThat(lineCost).isEqualTo(996);
        assertThat(lineTotal).isGreaterThan(lineCost);
        assertThat(Quantities.perBase(lineTotal, d("12.000"))).isEqualTo(83);
    }

    /** Property: a pack quantity entered in three places round-trips through the base unit, or is refused; never rounded. */
    @Test
    void conversionIsExactOrRefusedForRandomFactorsAndQuantities() {
        Random random = new Random(194);
        int accepted = 0;
        int refused = 0;
        for (int n = 0; n < 5_000; n++) {
            BigDecimal factor = BigDecimal.valueOf(1_000 + random.nextInt(200_000), 3);
            BigDecimal qty = BigDecimal.valueOf(1 + random.nextInt(50_000), random.nextInt(4));
            var base = Quantities.toBase(qty, factor);
            if (base.isPresent()) {
                accepted++;
                assertThat(base.get().scale()).isEqualTo(3);
                assertThat(base.get()).isEqualByComparingTo(qty.multiply(factor));
                assertThat(Quantities.fromBase(base.get(), factor)).isEqualByComparingTo(qty);
            } else {
                refused++;
                assertThat(Quantities.exact(qty.multiply(factor))).isFalse();
            }
        }
        assertThat(accepted).isPositive();
        assertThat(refused).isPositive();
    }

    /** Property: the amount owed for a restock in packs differs from the stocked value by under half a minor unit per base unit. */
    @Test
    void thePurchaseRoundingRemainderIsBoundedByHalfAMinorUnitPerBaseUnit() {
        Random random = new Random(1940);
        for (int n = 0; n < 5_000; n++) {
            BigDecimal factor = BigDecimal.valueOf(1_000 + random.nextInt(100_000), 3);
            long packCost = random.nextInt(2_000_000);
            BigDecimal qty = BigDecimal.valueOf(1 + random.nextInt(1_000));
            var base = Quantities.toBase(qty, factor);
            if (base.isEmpty()) {
                continue;
            }
            long costBase = Quantities.baseCost(packCost, factor);
            long entered = Quantities.value(qty, packCost);
            long stocked = Quantities.value(base.get(), costBase);
            // Per base unit the cost is off by at most 0.5; plus half a unit for each of the two line roundings.
            BigDecimal bound = base.get().multiply(new BigDecimal("0.5")).add(BigDecimal.ONE);
            assertThat(BigDecimal.valueOf(Math.abs(entered - stocked))).isLessThanOrEqualTo(bound);
        }
    }
}
