package com.rincoltech.bms.retail.purchasing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.retail.purchasing.internal.Allocation.Split;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic of landed cost (ADR-033, issue #199 amendment): shares that always add up to the total, a remainder rule
 * that is deterministic, and the split between goods on hand and goods gone. Plain integers; no clock, no database.
 */
class AllocationTest {

    @Test
    void sharesAlwaysAddUpToTheTotalAndStayWithinOneUnitOfTheirExactProportion() {
        Random random = new Random(199);
        for (int round = 0; round < 5_000; round++) {
            int n = 1 + random.nextInt(9);
            long[] basis = new long[n];
            long sum = 0;
            for (int i = 0; i < n; i++) {
                basis[i] = random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(2_000_000);
                sum += basis[i];
            }
            if (sum == 0) {
                basis[random.nextInt(n)] = 1 + random.nextInt(100);
                sum = Arrays.stream(basis).sum();
            }
            long total = 1 + (long) (random.nextDouble() * 5_000_000_000L);
            long[] shares = Allocation.spread(total, basis);
            assertThat(Arrays.stream(shares).sum()).as("round %d", round).isEqualTo(total);
            for (int i = 0; i < n; i++) {
                assertThat(shares[i]).isGreaterThanOrEqualTo(0);
                if (basis[i] == 0) {
                    assertThat(shares[i]).isZero();
                }
                BigInteger exactFloor = BigInteger.valueOf(total)
                        .multiply(BigInteger.valueOf(basis[i]))
                        .divide(BigInteger.valueOf(sum));
                assertThat(shares[i] - exactFloor.longValueExact()).isIn(0L, 1L);
            }
            assertThat(Allocation.spread(total, basis)).as("deterministic").isEqualTo(shares);
        }
    }

    @Test
    void theLeftOverGoesToTheLargestRemainderThenTheLargerBasisThenTheEarlierLine() {
        // 1001 over 1000 and 3000: remainders 1000 and 3000 (of 4000), so the second takes the one left over.
        assertThat(Allocation.spread(1_001, new long[] {1_000, 3_000})).containsExactly(250, 751);
        // 100 over three equal lines: equal remainders and bases, so the earliest line takes it.
        assertThat(Allocation.spread(100, new long[] {5, 5, 5})).containsExactly(34, 33, 33);
        // 2 over 1, 1, 2 (sum 4): exact shares 0.5, 0.5, 1.0. One unit is left; the remainders of the first two tie and
        // so
        // do their bases, so the earlier line takes it.
        assertThat(Allocation.spread(2, new long[] {1, 1, 2})).containsExactly(1, 0, 1);
        // Equal remainders, different bases: 3 over 2 and 1 and 3 (sum 6): shares 1, 0.5, 1.5 -> floors 1, 0, 1, one
        // left;
        // remainders 0, 3, 3 (of 6) tie, and the larger basis (3) wins.
        assertThat(Allocation.spread(3, new long[] {2, 1, 3})).containsExactly(1, 0, 2);
    }

    @Test
    void aZeroTotalSpreadsToNothingAndAllZeroBasesAreRefused() {
        assertThat(Allocation.spread(0, new long[] {0, 0})).containsExactly(0, 0);
        assertThatThrownBy(() -> Allocation.spread(10, new long[] {0, 0})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Allocation.spread(-1, new long[] {1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Allocation.spread(1, new long[] {-1, 2})).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void largeAmountsDoNotOverflow() {
        long total = 10_000_000_000_000L;
        long[] shares = Allocation.spread(total, new long[] {9_999_999_999_999L, 3, 7});
        assertThat(Arrays.stream(shares).sum()).isEqualTo(total);
    }

    @Test
    void theShareOfGoodsOnHandIsRoundedHalfUpOnceAndTheRestIsGone() {
        assertThat(Allocation.onHandPart(100, new BigDecimal("6"), new BigDecimal("10")))
                .isEqualTo(new Split(60, 40));
        assertThat(Allocation.onHandPart(100, new BigDecimal("10"), new BigDecimal("10")))
                .isEqualTo(new Split(100, 0));
        assertThat(Allocation.onHandPart(100, BigDecimal.ZERO, new BigDecimal("10")))
                .isEqualTo(new Split(0, 100));
        // 7 x 2 / 4 = 3.5 rounds half up to 4.
        assertThat(Allocation.onHandPart(7, new BigDecimal("2"), new BigDecimal("4")))
                .isEqualTo(new Split(4, 3));
        // Thirds: 100 x 1 / 3 = 33.33.
        assertThat(Allocation.onHandPart(100, new BigDecimal("1"), new BigDecimal("3")))
                .isEqualTo(new Split(33, 67));
        assertThatThrownBy(() -> Allocation.onHandPart(1, new BigDecimal("11"), new BigDecimal("10")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whatIsLeftOfALineIsTheBalanceLessLaterStockWithinTheLine() {
        BigDecimal ten = new BigDecimal("10");
        // Plenty of stock and no later receipts: all ten.
        assertThat(Allocation.remaining(new BigDecimal("25"), BigDecimal.ZERO, ten))
                .isEqualByComparingTo("10");
        // 8 left, none later: 8.
        assertThat(Allocation.remaining(new BigDecimal("8"), BigDecimal.ZERO, ten))
                .isEqualByComparingTo("8");
        // 8 left and 10 came in later: none of this line's.
        assertThat(Allocation.remaining(new BigDecimal("8"), ten, ten)).isEqualByComparingTo("0");
        // 15 left and 10 came in later: 5 of this line's.
        assertThat(Allocation.remaining(new BigDecimal("15"), ten, ten)).isEqualByComparingTo("5");
        // A negative balance is nothing.
        assertThat(Allocation.remaining(new BigDecimal("-3"), BigDecimal.ZERO, ten))
                .isEqualByComparingTo("0");
    }
}
