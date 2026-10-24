package com.rincoltech.bms.retail.purchasing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rincoltech.bms.retail.purchasing.internal.LotRepository.Lot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The spread of a landed cost or price credit over the branches that hold the goods (ADR-079): the parts add up to the
 * inventory part, every part recorded is above zero (the check of {@code retail_cost_spreads}), and they come in branch
 * order (the order the balance locks are taken in). Plain numbers; fabricated.
 */
class ReceiptCostingTest {

    static Lot lot(UUID branch) {
        return new Lot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "GR-HQ-000001",
                1,
                branch,
                UUID.randomUUID(),
                LocalDate.of(2026, 10, 1),
                "posted",
                UUID.randomUUID(),
                "T1",
                "Test item",
                new BigDecimal("10.000"),
                null,
                null,
                null,
                100,
                1_000,
                1_000,
                0,
                0,
                BigDecimal.ZERO,
                0);
    }

    @Test
    void everyPartIsAboveZeroAddsUpAndComesInBranchOrder() {
        Random random = new Random(523);
        for (int round = 0; round < 5_000; round++) {
            Map<UUID, BigDecimal> balances = new TreeMap<>();
            int n = 1 + random.nextInt(5);
            for (int i = 0; i < n; i++) {
                // Holdings far apart, so small shares round to nothing for the small branch.
                balances.put(
                        UUID.randomUUID(),
                        BigDecimal.valueOf(1 + random.nextInt(random.nextBoolean() ? 3 : 1_000_000), 3));
            }
            long inventory = random.nextInt(5_000);
            Map<UUID, Long> parts = ReceiptCosting.spread(lot(UUID.randomUUID()), inventory, balances);
            assertThat(parts.values()).allMatch(p -> p > 0);
            assertThat(parts.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(inventory);
            assertThat(parts.keySet()).isSubsetOf(balances.keySet());
            assertThat(java.util.List.copyOf(parts.keySet())).isSortedAccordingTo(UUID::compareTo);
        }
    }

    @Test
    void withNothingHeldThePartGoesToTheReceivingBranchAndNothingIsSpreadForNothing() {
        UUID home = UUID.randomUUID();
        assertThat(ReceiptCosting.spread(lot(home), 300, Map.of())).containsExactly(Map.entry(home, 300L));
        assertThat(ReceiptCosting.spread(lot(home), 0, Map.of(UUID.randomUUID(), BigDecimal.TEN)))
                .isEmpty();
    }

    @Test
    void aBalanceRowOutsideTheLockedSetIsRefusedBeforeItIsRevalued() {
        UUID hq = UUID.randomUUID();
        UUID br2 = UUID.randomUUID();
        UUID product = UUID.randomUUID();
        Map<UUID, java.util.Set<UUID>> locked = Map.of(hq, java.util.Set.of(product));
        ReceiptCosting.requireLocked(locked, hq, product);
        assertThatThrownBy(() -> ReceiptCosting.requireLocked(locked, br2, product))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not lock");
        assertThatThrownBy(() -> ReceiptCosting.requireLocked(locked, hq, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReceiptCosting.requireLocked(null, hq, product))
                .isInstanceOf(IllegalStateException.class);
    }
}
