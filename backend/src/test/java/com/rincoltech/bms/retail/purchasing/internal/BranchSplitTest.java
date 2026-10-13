package com.rincoltech.bms.retail.purchasing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The exact split of a payment over the branches that owe a credit restock (issue #198). Fabricated amounts, a seeded random. */
class BranchSplitTest {

    static final UUID A = new UUID(0, 1);
    static final UUID B = new UUID(0, 2);
    static final UUID C = new UUID(0, 3);

    @Test
    void aPartPaymentIsInProportionToWhatEachOwesWithTheRemainderToTheLargestFraction() {
        // 1,000 and 500 owed; 700 paid: 466.67 and 233.33, so the extra unit goes to the larger fraction.
        assertThat(BranchSplit.split(Map.of(A, 1_000L, B, 500L), 700))
                .containsEntry(A, 467L)
                .containsEntry(B, 233L);
        // Paying what is left settles every branch exactly.
        assertThat(BranchSplit.split(Map.of(A, 533L, B, 267L), 800))
                .containsEntry(A, 533L)
                .containsEntry(B, 267L);
    }

    @Test
    void aTieGoesToTheLowestBranchId() {
        Map<UUID, Long> split = BranchSplit.split(Map.of(A, 1L, B, 1L), 1);
        assertThat(split).containsOnlyKeys(A).containsEntry(A, 1L);
    }

    @Test
    void aBranchWithNothingOwedTakesNothingAndZeroIsNothing() {
        assertThat(BranchSplit.split(Map.of(A, 0L, B, 300L), 100))
                .containsOnlyKeys(B)
                .containsEntry(B, 100L);
        assertThat(BranchSplit.split(Map.of(A, 10L), 0)).isEmpty();
        assertThatThrownBy(() -> BranchSplit.split(Map.of(A, 10L), 11)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forAnyOwedAndAnyAmountThePartsAddUpAndNeverExceedWhatABranchOwes() {
        Random random = new Random(198);
        for (int i = 0; i < 5_000; i++) {
            Map<UUID, Long> owed = new TreeMap<>();
            owed.put(A, (long) random.nextInt(2_000));
            owed.put(B, (long) random.nextInt(2_000));
            owed.put(C, (long) random.nextInt(2_000));
            long total = owed.values().stream().mapToLong(Long::longValue).sum();
            long amount = total == 0 ? 0 : random.nextInt((int) total + 1);
            Map<UUID, Long> parts = BranchSplit.split(owed, amount);
            assertThat(parts.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(amount);
            parts.forEach((b, v) -> assertThat(v).isBetween(1L, owed.get(b)));
        }
    }

    @Test
    void severalPartPaymentsInARowSettleEveryBranchToZeroExactly() {
        Random random = new Random(199);
        for (int i = 0; i < 2_000; i++) {
            Map<UUID, Long> owed = new TreeMap<>(
                    Map.of(A, (long) random.nextInt(5_000) + 1, B, (long) random.nextInt(5_000) + 1, C, 7L));
            while (owed.values().stream().mapToLong(Long::longValue).sum() > 0) {
                long total = owed.values().stream().mapToLong(Long::longValue).sum();
                long amount = random.nextInt(3) == 0 ? total : random.nextInt((int) total) + 1;
                BranchSplit.split(owed, amount).forEach((b, v) -> owed.merge(b, -v, Long::sum));
                assertThat(owed.values()).allMatch(v -> v >= 0);
            }
        }
    }
}
