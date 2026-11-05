package com.rincoltech.bms.retail.purchasing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.rincoltech.bms.kernel.Principal;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.LineBranch;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.Purchase;
import com.rincoltech.bms.retail.purchasing.internal.PurchasingApi.PurchaseLine;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Who sees what a delivery should sell for (issue #821, ADR-126): never an internal caller with no principal, never on a
 * voided delivery or one with no line in sight, and only with retail.profit.read at every branch the delivery stocked.
 * Fabricated ids and amounts.
 */
class DeliveryWorthShownTest {

    static final UUID SHOP_A = new UUID(0, 1);
    static final UUID SHOP_B = new UUID(0, 2);
    static final String PROFIT = "retail.profit.read";

    static PurchaseLine line(UUID branch) {
        return new PurchaseLine(
                new UUID(1, 1),
                1,
                new UUID(2, 1),
                "T-1",
                "Test item",
                100,
                null,
                "3.000",
                300,
                List.of(new LineBranch(branch, "3.000", null)),
                null,
                null,
                null,
                null,
                null,
                "piece",
                null,
                null);
    }

    static Purchase delivery(String status, List<PurchaseLine> lines) {
        return new Purchase(
                new UUID(3, 1),
                "RP00000001",
                null,
                null,
                "cash",
                "UGX",
                300,
                null,
                lines,
                null,
                null,
                status,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null);
    }

    static Optional<Principal> caller(Set<String> permissions, boolean allBranches, Set<UUID> branches) {
        return Optional.of(Principal.uniform(new UUID(4, 1), "staff", permissions, allBranches, branches));
    }

    static final Purchase AT_A = delivery("posted", List.of(line(SHOP_A)));

    @Test
    void anInternalCallerWithNoPrincipalSeesNoWorthAndNothingThrows() {
        assertThat(PurchasingService.showsWorth(AT_A, Optional.empty())).isFalse();
    }

    @Test
    void aProfitReaderSeesTheWorthOfADeliveryThatStands() {
        Optional<Principal> owner = caller(Set.of(PROFIT), true, Set.of());
        Purchase voided = delivery("voided", List.of(line(SHOP_A)));
        Purchase noLines = delivery("posted", List.of());
        assertThat(PurchasingService.showsWorth(AT_A, owner)).isTrue();
        assertThat(PurchasingService.showsWorth(voided, owner)).isFalse();
        assertThat(PurchasingService.showsWorth(noLines, owner)).isFalse();
    }

    @Test
    void profitReadMustCoverEveryBranchTheDeliveryStocked() {
        Optional<Principal> oneShop = caller(Set.of(PROFIT), false, Set.of(SHOP_A));
        Purchase twoShops = delivery("posted", List.of(line(SHOP_A), line(SHOP_B)));
        Optional<Principal> buyer = caller(Set.of("retail.purchase.create"), true, Set.of());
        assertThat(PurchasingService.showsWorth(AT_A, oneShop)).isTrue();
        assertThat(PurchasingService.showsWorth(twoShops, oneShop)).isFalse();
        assertThat(PurchasingService.showsWorth(AT_A, buyer)).isFalse();
    }

    /** Issue #852 (review item 8): the catalogue answers 0 for an item it no longer has or never had. */
    @Test
    void aDeletedOrUnknownItemWithNoLinePriceIsUnpriced() {
        Long worth = PurchasingService.worth(AT_A.lines(), id -> 0L);
        assertThat(worth).isNull();
        Purchase shown = AT_A.withWorth(worth);
        assertThat(shown.hasUnpricedItems()).isTrue();
        assertThat(shown.sellWorthMinor()).isNull();
        assertThat(shown.expectedProfitMinor()).isNull();
    }
}
