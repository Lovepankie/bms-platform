package com.rincoltech.bms.core.payments.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Where money crosses the provider's boundary (ADR-035 decision 3, spec 3.12): the provider accepts and returns fractions
 * of a shilling, so this conversion is what keeps one out. Exact, no float, whole shillings only.
 */
class MinorUnitsTest {

    @Test
    void ugxHasNoMinorUnitSoMinorUnitsAreShillings() {
        assertThat(MinorUnits.exponent("UGX")).isZero();
        assertThat(MinorUnits.toDecimal(1500, "UGX"))
                .isEqualByComparingTo("1500")
                .hasToString("1500");
        assertThat(MinorUnits.toDecimal(Long.MAX_VALUE, "UGX").toPlainString()).isEqualTo("9223372036854775807");
    }

    @Test
    void aWholeShillingAmountConvertsBackExactlyWhateverTheTrailingZeros() {
        assertThat(MinorUnits.fromDecimal(new BigDecimal("1000"), "UGX")).contains(1000L);
        assertThat(MinorUnits.fromDecimal(new BigDecimal("1000.0"), "UGX")).contains(1000L);
        assertThat(MinorUnits.fromDecimal(new BigDecimal("1000.000"), "UGX")).contains(1000L);
        assertThat(MinorUnits.fromDecimal(new BigDecimal("1E+3"), "UGX")).contains(1000L);
        assertThat(MinorUnits.fromDecimal(new BigDecimal("1"), "UGX")).contains(1L);
        assertThat(MinorUnits.fromDecimal(new BigDecimal("50000000"), "UGX")).contains(50_000_000L);
        assertThat(MinorUnits.fromDecimal(new BigDecimal("1000000000000"), "UGX"))
                .contains(1_000_000_000_000L);
    }

    @Test
    void aFractionOfAShillingIsRefusedNeverRounded() {
        for (String fraction :
                new String[] {"1000.55", "0.5", "1000.01", "999.999999", "0.0000001", "1.5E+0", "15E-1"}) {
            assertThat(MinorUnits.fromDecimal(new BigDecimal(fraction), "UGX"))
                    .as("%s", fraction)
                    .isEmpty();
        }
    }

    @Test
    void zeroNegativeAndTooLargeAreRefused() {
        assertThat(MinorUnits.fromDecimal(new BigDecimal("0"), "UGX")).isEmpty();
        assertThat(MinorUnits.fromDecimal(new BigDecimal("0.0"), "UGX")).isEmpty();
        assertThat(MinorUnits.fromDecimal(new BigDecimal("-1000"), "UGX")).isEmpty();
        assertThat(MinorUnits.fromDecimal(new BigDecimal("9223372036854775808"), "UGX"))
                .isEmpty();
        assertThat(MinorUnits.fromDecimal(null, "UGX")).isEmpty();
    }

    @Test
    void aCurrencyThatIsNotListedCannotBeCollected() {
        assertThat(MinorUnits.supported("UGX")).isTrue();
        assertThat(MinorUnits.supported("USD")).isFalse();
        assertThat(MinorUnits.supported("ugx")).isFalse();
        assertThat(MinorUnits.supported(null)).isFalse();
        assertThatThrownBy(() -> MinorUnits.toDecimal(100, "USD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinorUnits.fromDecimal(BigDecimal.ONE, "KES"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Property: every positive long survives the round trip through the provider's decimal, with no float anywhere. */
    @Test
    void everyPositiveAmountRoundTripsExactly() {
        Random random = new Random(213);
        for (int i = 0; i < 50_000; i++) {
            long minor = switch (i % 4) {
                case 0 -> 1 + random.nextInt(10_000);
                case 1 -> 1 + (long) random.nextInt(Integer.MAX_VALUE);
                case 2 -> 1 + (random.nextLong() & Long.MAX_VALUE) % 1_000_000_000_000_000L;
                default -> Math.max(1, random.nextLong() & Long.MAX_VALUE);
            };
            BigDecimal decimal = MinorUnits.toDecimal(minor, "UGX");
            Optional<Long> back = MinorUnits.fromDecimal(decimal, "UGX");
            assertThat(back).as("round trip of %d", minor).contains(minor);
            // The provider may send the same number with a point; the value is unchanged.
            assertThat(MinorUnits.fromDecimal(decimal.setScale(2), "UGX")).contains(minor);
        }
    }

    /** Property: any decimal with a non-zero digit after the point is refused, however it is written. */
    @Test
    void anyDecimalWithAFractionalDigitIsRefused() {
        Random random = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            long whole = random.nextInt(1_000_000);
            int fraction = 1 + random.nextInt(99);
            BigDecimal value = new BigDecimal(whole + "." + String.format("%02d", fraction));
            assertThat(MinorUnits.fromDecimal(value, "UGX")).as("%s", value).isEmpty();
        }
    }
}
