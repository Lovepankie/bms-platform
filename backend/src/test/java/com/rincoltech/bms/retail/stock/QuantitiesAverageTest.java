package com.rincoltech.bms.retail.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The moving weighted average of ADR-079 (issue #523): (on hand x average + value in) / (on hand + quantity), half up to the
 * minor unit, and a remainder that makes the value carried plus the remainder equal the value that came in. Amounts fabricated.
 */
class QuantitiesAverageTest {

    static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    @Test
    void aDeliveryReaveragesWhatIsOnHand() {
        // 48 pieces at 750, then a carton of 30 for 24,000: 60,000 over 78 pieces is 769.23, carried as 769.
        Quantities.Averaged a = Quantities.average(d("48"), 750, d("30"), 24_000);
        assertThat(a.averageMinor()).isEqualTo(769);
        assertThat(a.remainderMinor()).isEqualTo(60_000 - 78 * 769);
    }

    @Test
    void stockComingIntoNothingTakesItsOwnCost() {
        assertThat(Quantities.average(d("0"), 500, d("24"), 18_000)).isEqualTo(new Quantities.Averaged(750, 0));
        // Less than nothing (imported history): the delivery's own cost, the old value's difference left over.
        Quantities.Averaged a = Quantities.average(d("-2"), 100, d("10"), 2_000);
        assertThat(a.averageMinor()).isEqualTo(200);
        assertThat(a.remainderMinor()).isEqualTo(-200 + 2_000 - 8 * 200);
    }

    @Test
    void takingOutAtAnotherCostReaveragesWhatStays() {
        // 15 at 150; the void of a restock of 10 at 200 leaves 5 carrying 250, 50 each.
        assertThat(Quantities.average(d("15"), 150, d("-10"), -2_000)).isEqualTo(new Quantities.Averaged(50, 0));
        // Too much taken: the average would be negative, which the ledger refuses.
        assertThat(Quantities.average(d("12"), 200, d("-10"), -3_000).averageMinor())
                .isNegative();
        // Emptied: the average is kept and what was left over is the remainder.
        assertThat(Quantities.average(d("10"), 150, d("-10"), -2_000)).isEqualTo(new Quantities.Averaged(150, -500));
    }

    @Test
    void aChangeOfValueAloneMovesTheAverage() {
        assertThat(Quantities.average(d("10"), 100, BigDecimal.ZERO, 100)).isEqualTo(new Quantities.Averaged(110, 0));
        assertThat(Quantities.average(d("3"), 100, BigDecimal.ZERO, 100)).isEqualTo(new Quantities.Averaged(133, 1));
        // Nothing on hand to carry it: all of it is the remainder.
        assertThat(Quantities.average(d("0"), 100, BigDecimal.ZERO, 100)).isEqualTo(new Quantities.Averaged(100, 100));
    }

    @Test
    void theValueCarriedPlusTheRemainderIsAlwaysTheValueThatCameIn() {
        Random r = new Random(523);
        for (int i = 0; i < 10_000; i++) {
            BigDecimal onHand = BigDecimal.valueOf(r.nextInt(100_000), 3);
            long average = r.nextInt(50_000);
            BigDecimal qty = BigDecimal.valueOf(1 + r.nextInt(100_000), 3);
            long value = r.nextInt(5_000_000);
            Quantities.Averaged a = Quantities.average(onHand, average, qty, value);
            assertThat(Quantities.value(onHand.add(qty), a.averageMinor()) + a.remainderMinor())
                    .isEqualTo(Quantities.value(onHand, average) + value);
            // The average lies between the two costs, give or take the rounding.
            long own = Quantities.perBase(value, qty);
            assertThat(a.averageMinor()).isBetween(Math.min(average, own) - 1, Math.max(average, own) + 1);
        }
    }
}
